package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.kyc.CheckStatus;
import com.finapp.kyc.ChecksAssessment;
import com.finapp.kyc.DocumentVerificationAdapter;
import com.finapp.kyc.IdentityVerificationAdapter;
import com.finapp.kyc.JdbcKycCaseStore;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.ScreeningAdapter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
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
 * The acceptance of `P2-TSK-010`, against a real database and a real HTTP provider: <strong>a
 * hit case cannot terminate without a person</strong> ({@code INV-KYC-04}) — it routes to
 * {@code IN_REVIEW} with an explicit review task per raising check, idempotently, under a
 * ten-way race — and an adverse-media provider that keeps answering "unknown" is retried to a
 * budget and then routed to a person rather than left silently incomplete.
 */
@Tag("database")
@SpringBootTest
@DisplayName("screening routes a non-clean case to a person (P2-TSK-010)")
@SuppressWarnings("try") // correlation scopes are used for their close side effect
class ScreeningRunDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private static SimulatedProvider provider;

    @Autowired private VerificationRunService runs;
    @Autowired private MeterRegistry meterRegistry;

    private final JdbcKycCaseStore cases = new JdbcKycCaseStore();

    @BeforeAll
    static void startProvider() {
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
    @DisplayName("a sanctions hit routes to IN_REVIEW with one task on the hit check, idempotently")
    void aHitBecomesWorkForAPerson() throws Exception {
        Case opened = givenAnOpenCase();
        stubEveryPathClear();
        provider.succeedsWith(ScreeningAdapter.SANCTIONS_PATH, 200, "{\"status\":\"hit\"}");

        VerificationRunService.RunReport first = run(opened);

        assertThat(first.assessment()).isEqualTo(ChecksAssessment.BLOCKED);
        assertThat(statusOf(opened.caseId())).isEqualTo("IN_REVIEW");
        UUID hitCheck =
                first.checks().stream()
                        .filter(check -> check.status() == CheckStatus.HIT)
                        .findFirst()
                        .orElseThrow()
                        .id()
                        .value();
        assertThat(tasksFor(opened.caseId()))
                .as("the state and its work item are atomic: IN_REVIEW has something to resolve")
                .containsExactly(new TaskRow(hitCheck, "OPEN"));

        // Re-running converges at every layer: no second task (the total UNIQUE(check_id) is the
        // arbiter), no second provider call for an answered question, the case already moved.
        long questionsAsked = totalRequestCount();
        VerificationRunService.RunReport second = run(opened);
        assertThat(second.assessment()).isEqualTo(ChecksAssessment.BLOCKED);
        assertThat(tasksFor(opened.caseId())).hasSize(1);
        assertThat(totalRequestCount())
                .as("an answered question is never re-asked")
                .isEqualTo(questionsAsked);

        // The plan-named gauge, on the queue it measures: the review depth is now visible
        // fleet-wide (PHASE_2_PLAN.md §10 - the queue nobody watches is the queue that ages).
        assertThat(meterRegistry.get("finapp.kyc.review.queue").gauge().value())
                .isGreaterThanOrEqualTo(1.0d);
    }

    @Test
    @DisplayName("ten instances racing a hit case produce one task per hit check and one transition")
    void tenInstancesRouteOnce() throws Exception {
        Case opened = givenAnOpenCase();
        stubEveryPathClear();
        provider.succeedsWith(ScreeningAdapter.PEP_PATH, 200, "{\"status\":\"hit\"}");

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

        assertThat(statusOf(opened.caseId())).isEqualTo("IN_REVIEW");
        // One task per raising check, EVER - N assessors reaching BLOCKED insert against the
        // total unique index and exactly one wins per check. The recorded residual race can add
        // a redundant question (never a wrong answer), so the task count equals the HIT-check
        // count rather than a constant.
        List<TaskRow> tasks = tasksFor(opened.caseId());
        assertThat(tasks).hasSize((int) checkCountWithStatus(opened.caseId(), "HIT"));
        assertThat(tasks)
                .allSatisfy(task -> assertThat(task.status()).isEqualTo("OPEN"))
                .extracting(TaskRow::checkId)
                .doesNotHaveDuplicates();
        // The call-per-check equality, unchanged by the routing: dispatch is still the arbiter.
        assertThat(totalRequestCount()).isEqualTo(checkCountFor(opened.caseId()));
    }

    @Test
    @DisplayName("adverse-media unknowns are retried to the budget, then a person - never silence")
    void exhaustedIndeterminateGoesToAPerson() throws Exception {
        Case opened = givenAnOpenCase();
        stubEveryPathClear();
        provider.returnsUnknownState(ScreeningAdapter.ADVERSE_MEDIA_PATH, "PENDING_REVIEW_9");

        // Each run converges on the answered types and retries the unknown one as a NEW check
        // (ADR-0038: resolution is a new check) - until the budget.
        assertThat(run(opened).assessment()).isEqualTo(ChecksAssessment.INCOMPLETE);
        assertThat(run(opened).assessment()).isEqualTo(ChecksAssessment.INCOMPLETE);
        assertThat(run(opened).assessment())
                .as("the third unknown exhausts the budget: stop asking machines, ask a person")
                .isEqualTo(ChecksAssessment.BLOCKED);
        assertThat(statusOf(opened.caseId())).isEqualTo("IN_REVIEW");

        // A fourth run asks nothing more: at the budget the run converges on the newest unknown
        // rather than spending money on a provider that keeps not answering.
        run(opened);
        assertThat(provider.requestCount(ScreeningAdapter.ADVERSE_MEDIA_PATH))
                .as("three questions, not four - the budget is the bound")
                .isEqualTo(3);
        assertThat(checkCountWithStatus(opened.caseId(), "INDETERMINATE")).isEqualTo(3);

        // One task, on the NEWEST unknown - the freshest evidence of the provider's silence.
        assertThat(tasksFor(opened.caseId()))
                .containsExactly(new TaskRow(newestCheckOfType(opened.caseId(), "ADVERSE_MEDIA"), "OPEN"));
    }

    @Test
    @DisplayName("review tasks are INSERT-and-SELECT only - the resolution grant is P2-TSK-012's")
    void theResolutionGrantArrivesWithTheCapability() throws Exception {
        // Proven now so the UPDATE arriving with P2-TSK-012 is a migration with an argument
        // rather than a privilege that was silently always there (the V004 precedent).
        try (Connection app = DatabaseRoles.application();
                PreparedStatement update =
                        app.prepareStatement("UPDATE kyc.review_task SET status = 'RESOLVED'")) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(update::executeUpdate)
                    .withMessageContaining("permission denied");
        }
        try (Connection app = DatabaseRoles.application();
                PreparedStatement delete =
                        app.prepareStatement("DELETE FROM kyc.review_task")) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(delete::executeUpdate)
                    .withMessageContaining("permission denied");
        }
    }

    // -----------------------------------------------------------------

    private record Case(KycCaseId caseId, UUID customerId) {}

    private record TaskRow(UUID checkId, String status) {}

    private static final String[] ALL_PATHS = {
        IdentityVerificationAdapter.PATH,
        DocumentVerificationAdapter.PATH,
        ScreeningAdapter.SANCTIONS_PATH,
        ScreeningAdapter.PEP_PATH,
        ScreeningAdapter.ADVERSE_MEDIA_PATH
    };

    /** All five questions answer clear; a test then overrides the path it is about. */
    private static void stubEveryPathClear() {
        for (String path : ALL_PATHS) {
            provider.succeedsWith(path, 200, "{\"status\":\"clear\"}");
        }
    }

    private static long totalRequestCount() {
        long total = 0;
        for (String path : ALL_PATHS) {
            total += provider.requestCount(path);
        }
        return total;
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

    private static List<TaskRow> tasksFor(KycCaseId caseId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT check_id, status FROM kyc.review_task"
                                        + " WHERE case_id = ? ORDER BY opened_at")) {
            select.setObject(1, caseId.value());
            try (ResultSet rows = select.executeQuery()) {
                List<TaskRow> tasks = new ArrayList<>();
                while (rows.next()) {
                    tasks.add(
                            new TaskRow(rows.getObject(1, UUID.class), rows.getString(2)));
                }
                return tasks;
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

    private static long checkCountWithStatus(KycCaseId caseId, String status) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT count(*) FROM kyc.verification_check"
                                        + " WHERE case_id = ? AND status = ?")) {
            select.setObject(1, caseId.value());
            select.setString(2, status);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static UUID newestCheckOfType(KycCaseId caseId, String type) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT id FROM kyc.verification_check"
                                        + " WHERE case_id = ? AND check_type = ?"
                                        + " ORDER BY requested_at DESC, id DESC LIMIT 1")) {
            select.setObject(1, caseId.value());
            select.setString(2, type);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getObject(1, UUID.class);
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
