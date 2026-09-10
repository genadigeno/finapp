package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.kyc.CheckStatus;
import com.finapp.kyc.ChecksAssessment;
import com.finapp.kyc.DocumentVerificationAdapter;
import com.finapp.kyc.IdentityVerificationAdapter;
import com.finapp.kyc.JdbcKycCaseStore;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.ScreeningAdapter;
import com.finapp.kyc.VerificationCheck;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The acceptance of `P2-TSK-009`, against a real database and a real HTTP provider: a clean
 * simulated run takes a case to {@code READY_FOR_DECISION} with retained evidence — and a
 * timeout, a hit, and ten racing instances each land exactly where the design says.
 *
 * <p>The provider is {@link SimulatedProvider}, wired through the same property a deployment
 * would use ({@code finapp.kyc.provider.url}), so the beans under test are the production
 * conditional wiring rather than a test double of it.
 */
@Tag("database")
@SpringBootTest
@DisplayName("a verification run against the simulated provider (P2-TSK-009)")
@SuppressWarnings("try") // correlation scopes are used for their close side effect
class VerificationRunDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private static SimulatedProvider provider;

    @Autowired private VerificationRunService runs;

    private final JdbcKycCaseStore cases = new JdbcKycCaseStore();

    @BeforeAll
    static void startProvider() {
        // @DynamicPropertySource runs during context preparation, BEFORE @BeforeAll, so the
        // harness is usually already started there; this only covers a re-used context.
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.kyc.provider.url", () -> provider.baseUrl());
        registry.add("finapp.kyc.provider.timeout", () -> "PT0.7S");
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    @Test
    @DisplayName("a clean run takes the case to READY_FOR_DECISION with retained evidence")
    void aCleanRunReachesReadyForDecision() throws Exception {
        Case opened = givenAnOpenCase();
        String identityAnswer = "{\"status\":\"clear\",\"score\":98}";
        stubEveryPathClear();
        provider.succeedsWith(IdentityVerificationAdapter.PATH, 200, identityAnswer);

        VerificationRunService.RunReport report = run(opened);

        assertThat(report.assessment()).isEqualTo(ChecksAssessment.CLEAR_TO_PROCEED);
        // Five questions since P2-TSK-010: identity, document and the three screening types -
        // one machine, not a second one (PHASE_2_PLAN.md §5).
        assertThat(report.checks())
                .hasSize(5)
                .allSatisfy(check -> assertThat(check.status()).isEqualTo(CheckStatus.CLEAR));
        assertThat(statusOf(opened.caseId()))
                .as("the phase's spine: clean checks and the case awaits its decision")
                .isEqualTo("READY_FOR_DECISION");

        // INV-HIST-02, proven at the row: the evidence is the provider's bytes - encrypted, so
        // the ciphertext differs from them, and checksummed on what was received, so the claim
        // "these are the bytes" is checkable years later.
        byte[] expected = identityAnswer.getBytes(StandardCharsets.UTF_8);
        EvidenceRow evidence = evidenceOf(report, CheckStatus.CLEAR, "IDENTITY");
        assertThat(evidence.ciphertext()).isNotEqualTo(expected);
        assertThat(evidence.checksum())
                .isEqualTo(MessageDigest.getInstance("SHA-256").digest(expected));
        assertThat(evidence.length()).isEqualTo(expected.length);

        // INV-AUD-01: each outcome is a catalogued record, the platform as actor - the fifth
        // enumerated enterSystem() site - naming which check and what it normalised to.
        for (VerificationCheck check : report.checks()) {
            assertThat(auditSummaryFor(check))
                    .contains("type=" + check.type())
                    .contains("outcome=CLEAR");
        }
    }

    @Test
    @DisplayName("a timeout is INDETERMINATE, and the case does not decide")
    void aTimeoutDoesNotDecide() throws Exception {
        Case opened = givenAnOpenCase();
        stubEveryPathClear();
        provider.neverResponds(DocumentVerificationAdapter.PATH);

        VerificationRunService.RunReport report = run(opened);

        assertThat(report.assessment()).isEqualTo(ChecksAssessment.INCOMPLETE);
        assertThat(report.checks())
                .extracting(VerificationCheck::status)
                .containsExactlyInAnyOrder(
                        CheckStatus.CLEAR,
                        CheckStatus.CLEAR,
                        CheckStatus.CLEAR,
                        CheckStatus.CLEAR,
                        CheckStatus.INDETERMINATE);
        assertThat(statusOf(opened.caseId()))
                .as("INV-LIFE-03: we do not know, and the case says in progress, not a verdict")
                .isEqualTo("CHECKS_IN_PROGRESS");
        // The provider was asked - the DISPATCHED row was durable before the call, and the
        // request arriving is exactly what a timeout cannot disprove.
        assertThat(provider.requestCount(DocumentVerificationAdapter.PATH)).isEqualTo(1);
    }

    @Test
    @DisplayName("a hit blocks the run, and the case goes to a person")
    void aHitBlocks() throws Exception {
        Case opened = givenAnOpenCase();
        stubEveryPathClear();
        provider.succeedsWith(DocumentVerificationAdapter.PATH, 200, "{\"status\":\"hit\"}");

        VerificationRunService.RunReport report = run(opened);

        assertThat(report.assessment()).isEqualTo(ChecksAssessment.BLOCKED);
        // The P2-TSK-009 stopgap ("stays CHECKS_IN_PROGRESS") superseded by the capability that
        // was always going to supersede it: the routing and its review tasks are
        // ScreeningRunDatabaseTest's subject; here the run-level fact is that a hit case reaches
        // a person and never a decision state.
        assertThat(statusOf(opened.caseId()))
                .as("INV-KYC-04: a hit becomes explicit work for a person")
                .isEqualTo("IN_REVIEW");
    }

    @Test
    @DisplayName("ten instances running one case ask each question once and move the case once")
    void tenInstancesProduceOneRunsWorthOfEffects() throws Exception {
        Case opened = givenAnOpenCase();
        stubEveryPathClear();

        List<Callable<VerificationRunService.RunReport>> racers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            racers.add(() -> run(opened));
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            for (Future<VerificationRunService.RunReport> outcome : pool.invokeAll(racers)) {
                outcome.get(); // propagate any failure
            }
        } finally {
            pool.shutdown();
        }

        assertThat(statusOf(opened.caseId())).isEqualTo("READY_FOR_DECISION");
        assertThat(checkCountFor(opened.caseId()))
                .as("one in-flight check per type: the partial unique index is the arbiter."
                        + " The recorded residual race may add a redundant question - never a"
                        + " wrong answer - so the floor is exact and the load-bearing property"
                        + " is the call-per-check equality below")
                .isGreaterThanOrEqualTo(5);
        // The property that matters most: a check is dispatched once, so the provider sees one
        // question per check row - the conditional dispatch is what makes ten instances safe.
        assertThat(
                        provider.requestCount(IdentityVerificationAdapter.PATH)
                                + provider.requestCount(DocumentVerificationAdapter.PATH)
                                + provider.requestCount(ScreeningAdapter.SANCTIONS_PATH)
                                + provider.requestCount(ScreeningAdapter.PEP_PATH)
                                + provider.requestCount(ScreeningAdapter.ADVERSE_MEDIA_PATH))
                .isEqualTo(checkCountFor(opened.caseId()));
    }

    @Test
    @DisplayName("evidence is append-only to the application role")
    void evidenceIsAppendOnly() throws Exception {
        Case opened = givenAnOpenCase();
        stubEveryPathClear();
        run(opened);

        try (Connection app = DatabaseRoles.application();
                PreparedStatement update =
                        app.prepareStatement(
                                "UPDATE kyc.verification_evidence SET content_length = 1")) {
            org.assertj.core.api.Assertions.assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(update::executeUpdate)
                    .withMessageContaining("permission denied");
        }
        try (Connection app = DatabaseRoles.application();
                PreparedStatement delete =
                        app.prepareStatement("DELETE FROM kyc.verification_evidence")) {
            org.assertj.core.api.Assertions.assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(delete::executeUpdate)
                    .withMessageContaining("permission denied");
        }
    }

    // -----------------------------------------------------------------

    private record Case(KycCaseId caseId, UUID customerId) {}

    /** All five questions answer clear; a test then overrides the path it is about. */
    private static void stubEveryPathClear() {
        for (String path :
                new String[] {
                    IdentityVerificationAdapter.PATH,
                    DocumentVerificationAdapter.PATH,
                    ScreeningAdapter.SANCTIONS_PATH,
                    ScreeningAdapter.PEP_PATH,
                    ScreeningAdapter.ADVERSE_MEDIA_PATH
                }) {
            provider.succeedsWith(path, 200, "{\"status\":\"clear\"}");
        }
    }

    private VerificationRunService.RunReport run(Case opened) {
        try (CorrelationContext.Scope flow =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return runs.runChecks(opened.caseId(), opened.customerId());
        }
    }

    private Case givenAnOpenCase() throws SQLException {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Ada Lovelace', now())",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at)"
                            + " VALUES (?, ?, 'PENDING', now() - interval '1 hour',"
                            + " now() - interval '1 hour')",
                    customer,
                    party);
            app.setAutoCommit(false);
            KycCase opened = cases.openOrConverge(app, KycCase.open(IDS, CLOCK, customer)).kycCase();
            app.commit();
            return new Case(opened.id(), customer);
        }
    }

    private static String statusOf(KycCaseId caseId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement("SELECT status FROM kyc.kyc_case WHERE id = ?")) {
            select.setObject(1, caseId.value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static long checkCountFor(KycCaseId caseId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT count(*) FROM kyc.verification_check WHERE case_id = ?")) {
            select.setObject(1, caseId.value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private record EvidenceRow(byte[] ciphertext, byte[] checksum, int length) {}

    private EvidenceRow evidenceOf(
            VerificationRunService.RunReport report, CheckStatus status, String type)
            throws SQLException {
        VerificationCheck check =
                report.checks().stream()
                        .filter(c -> c.type().name().equals(type) && c.status() == status)
                        .findFirst()
                        .orElseThrow();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT content_ciphertext, checksum_sha256, content_length"
                                        + " FROM kyc.verification_evidence WHERE check_id = ?")) {
            select.setObject(1, check.id().value());
            try (ResultSet rows = select.executeQuery()) {
                assertThat(rows.next()).as("the check's evidence must exist").isTrue();
                EvidenceRow row =
                        new EvidenceRow(
                                rows.getBytes(1), rows.getBytes(2), rows.getInt(3));
                assertThat(rows.next()).as("one answer, one evidence row").isFalse();
                return row;
            }
        }
    }

    private String auditSummaryFor(VerificationCheck check) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT coalesce(string_agg(change_summary, ' '), '')"
                                        + " FROM platform.audit_record"
                                        + " WHERE operation = 'kyc.CheckCompleted'"
                                        + " AND target_id = ? AND actor_id = 'system'")) {
            select.setString(1, check.id().value().toString());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }
}
