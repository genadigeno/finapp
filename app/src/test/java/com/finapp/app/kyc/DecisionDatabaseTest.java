package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.kyc.CheckOutcome;
import com.finapp.kyc.CheckStatus;
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.CheckType;
import com.finapp.kyc.DecisionOutcome;
import com.finapp.kyc.DocumentVerificationAdapter;
import com.finapp.kyc.IdentityVerificationAdapter;
import com.finapp.kyc.JdbcKycCaseStore;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseKind;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycDecision;
import com.finapp.kyc.KycPolicyVersion;
import com.finapp.kyc.ReviewTask;
import com.finapp.kyc.ReviewTaskStore;
import com.finapp.kyc.ScreeningAdapter;
import com.finapp.kyc.VerificationCheck;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The decision, over real HTTP and through the real run (`P2-TSK-013`).
 *
 * <p>The acceptance, clause by clause: <strong>one immutable decision per case</strong> (a
 * ten-way race lands one; a second attempt is a 409 and the original stands; the schema refuses
 * UPDATE and DELETE on every column), <strong>attributable</strong> (a reviewer's decision names
 * the person, the automatic one names the platform), <strong>policy-pinned</strong> (the case's
 * own version, copied), and <strong>reproducible from what it references</strong> — the replay
 * test re-derives the automatic outcome from the referenced check rows and the pinned policy
 * code.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the decision endpoints and the automatic policy (P2-TSK-013)")
@SuppressWarnings("try") // correlation and security scopes are used for their close side effect
class DecisionDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    private static final String REJECTION =
            "{\"outcome\":\"REJECTED\",\"reason\":\"confirmed sanctions hit, ticket OPS-8080\"}";
    private static final String APPROVAL =
            "{\"outcome\":\"APPROVED\",\"reason\":\"false positive, cleared against list entry\"}";

    private static SimulatedProvider provider;

    @LocalServerPort private int port;

    @Autowired private Authorization authorization;
    @Autowired private CheckStore<Connection> checkStore;
    @Autowired private ReviewTaskStore<Connection> reviewTaskStore;
    @Autowired private VerificationRunService runs;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();
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

    // -----------------------------------------------------------------
    // The reviewer path

    @Test
    @DisplayName("a reviewer decides a reviewed case: recorded, attributed, terminal")
    void aReviewerDecidesAReviewedCase() throws Exception {
        Fixture fixture = givenACaseAwaitingDecision();
        IdentityId reviewer = givenAReviewer();
        assertThat(decisionRowOf(fixture.caseId()))
                .as("a reviewed case awaits its PERSON: the automatic policy deliberately"
                        + " never fires on a case whose checks raised something")
                .isNull();

        int status =
                post(decisionOf(fixture.caseId()), givenASessionFor(reviewer), REJECTION)
                        .statusCode();

        assertThat(status).isEqualTo(204);
        assertThat(caseStatusOf(fixture.caseId())).isEqualTo("REJECTED");
        DecisionRow decision = decisionRowOf(fixture.caseId());
        assertThat(decision.outcome()).isEqualTo("REJECTED");
        assertThat(decision.basis()).isEqualTo("REVIEWER");
        assertThat(decision.decidedBy())
                .as("the record names who decided (INV-KYC-02)")
                .isEqualTo(reviewer.value());
        assertThat(decision.reason()).contains("OPS-8080");
        assertThat(decision.policyVersion())
                .as("the CASE's pinned regime, copied - never CURRENT re-read (INV-HIST-04)")
                .isEqualTo(policyVersionOf(fixture.caseId()));
        assertThat(evidenceOf(decision.id()))
                .as("the decision references every check it rested on, explicitly")
                .containsExactlyInAnyOrderElementsOf(checkIdsOf(fixture.caseId()));

        assertThat(auditOfActor(
                        "kyc.DecisionRecorded",
                        fixture.caseId().value().toString(),
                        reviewer.value().toString()))
                .contains("OPS-8080")
                .contains("decision=")
                .contains("basis=REVIEWER");
    }

    @Test
    @DisplayName("a second decision is a 409, and the first stands untouched")
    void aSecondDecisionLoses() throws Exception {
        Fixture fixture = givenACaseAwaitingDecision();
        IdentityId first = givenAReviewer();
        IdentityId second = givenAReviewer();

        assertThat(post(decisionOf(fixture.caseId()), givenASessionFor(first), REJECTION)
                        .statusCode())
                .isEqualTo(204);
        HttpResponse<String> lost =
                post(decisionOf(fixture.caseId()), givenASessionFor(second), APPROVAL);

        assertThat(lost.statusCode())
                .as("a wrong decision is a NEW case, never an edit (INV-LIFE-04)")
                .isEqualTo(409);
        assertThat(lost.body()).contains("already decided");
        DecisionRow decision = decisionRowOf(fixture.caseId());
        assertThat(decision.outcome()).isEqualTo("REJECTED");
        assertThat(decision.decidedBy()).isEqualTo(first.value());
        assertThat(decision.reason()).contains("OPS-8080").doesNotContain("false positive");
        assertThat(auditCountOf("kyc.DecisionRecorded", fixture.caseId().value().toString()))
                .as("one decision, one record")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("ten concurrent decisions produce one decision and one record")
    void tenConcurrentDecisionsProduceOne() throws Exception {
        Fixture fixture = givenACaseAwaitingDecision();
        List<IdentityId> reviewers = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            IdentityId reviewer = givenAReviewer();
            reviewers.add(reviewer);
            tokens.add(givenASessionFor(reviewer));
        }

        List<Callable<Integer>> attempts = new ArrayList<>();
        for (String token : tokens) {
            attempts.add(
                    () -> post(decisionOf(fixture.caseId()), token, REJECTION).statusCode());
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> attempt : pool.invokeAll(attempts)) {
                statuses.add(attempt.get());
            }
        } finally {
            pool.shutdown();
        }

        assertThat(statuses.stream().filter(status -> status == 204)).hasSize(1);
        assertThat(statuses.stream().filter(status -> status == 409)).hasSize(9);
        assertThat(caseStatusOf(fixture.caseId())).isEqualTo("REJECTED");
        DecisionRow decision = decisionRowOf(fixture.caseId());
        assertThat(reviewers)
                .as("the recorded decider is one of the racers - the row and its record agree")
                .extracting(IdentityId::value)
                .contains(decision.decidedBy());
        assertThat(auditCountOf("kyc.DecisionRecorded", fixture.caseId().value().toString()))
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // The refusals

    @Test
    @DisplayName("a case still owing a person its review is not ready, and nothing is written")
    void anUnreviewedCaseIsNotReady() throws Exception {
        Fixture fixture = givenACaseInReview();

        HttpResponse<String> refused =
                post(decisionOf(fixture.caseId()), givenASessionFor(givenAReviewer()), REJECTION);

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body())
                .as("named for what is checked, not the commonest cause (the NOT_ACTIVE"
                        + " lesson): this is 'not ready', which tells the reviewer to wait -"
                        + " 'already decided' would tell them to stop")
                .contains("not ready");
        assertThat(caseStatusOf(fixture.caseId())).isEqualTo("IN_REVIEW");
        assertThat(decisionRowOf(fixture.caseId())).isNull();
        assertThat(auditCountOf("kyc.DecisionRecorded", fixture.caseId().value().toString()))
                .isZero();
    }

    @Test
    @DisplayName("an unknown or malformed case is a 404, and a guessed id leaves no record")
    void anUnknownCaseIsNotFound() throws Exception {
        String session = givenASessionFor(givenAReviewer());
        UUID guessed = IDS.next();

        assertThat(post("/kyc/cases/" + guessed + "/decision", session, REJECTION).statusCode())
                .isEqualTo(404);
        assertThat(post("/kyc/cases/not-a-uuid/decision", session, REJECTION).statusCode())
                .as("malformed and absent are one answer")
                .isEqualTo(404);
        assertThat(auditCountOf("kyc.DecisionRecorded", guessed.toString())).isZero();
    }

    @Test
    @DisplayName("a session holding no role is refused (INV-AUD-03), and nothing is written")
    void aSessionWithNoRoleIsRefused() throws Exception {
        Fixture fixture = givenACaseAwaitingDecision();

        assertThat(post(decisionOf(fixture.caseId()),
                                givenASessionFor(givenAnIdentity()),
                                REJECTION)
                        .statusCode())
                .isEqualTo(403);
        assertThat(caseStatusOf(fixture.caseId())).isEqualTo("READY_FOR_DECISION");
        assertThat(decisionRowOf(fixture.caseId())).isNull();
    }

    @Test
    @DisplayName("a missing outcome or reason is a 422 naming the field, never a 500")
    void aMissingFieldIsRefusedAtTheBoundary() throws Exception {
        Fixture fixture = givenACaseAwaitingDecision();
        String session = givenASessionFor(givenAReviewer());

        HttpResponse<String> empty = post(decisionOf(fixture.caseId()), session, "{}");
        assertThat(empty.statusCode()).isEqualTo(422);
        assertThat(empty.body()).contains("api.ValidationFailed");

        HttpResponse<String> unknownOutcome =
                post(
                        decisionOf(fixture.caseId()),
                        session,
                        "{\"outcome\":\"MAYBE\",\"reason\":\"x\"}");
        assertThat(unknownOutcome.statusCode())
                .as("an unknown outcome is the caller's mistake, never our 500")
                .isIn(400, 422);
        assertThat(decisionRowOf(fixture.caseId())).isNull();
    }

    // -----------------------------------------------------------------
    // The automatic policy

    @Test
    @DisplayName("an all-clear run ends APPROVED under the automatic policy, atomically")
    void anAllClearRunEndsApproved() throws Exception {
        Case opened = givenAnOpenCase();
        stubEveryPathClear();

        run(opened);

        // Not READY_FOR_DECISION: the all-clear-and-undecided state has no observable
        // instant, because the decision rides the assessment's own transaction. The four
        // suites that asserted READY_FOR_DECISION before this task were superseded by this
        // capability (the P2-TSK-010 stopgap-superseded precedent).
        assertThat(caseStatusOf(opened.caseId())).isEqualTo("APPROVED");
        DecisionRow decision = decisionRowOf(opened.caseId());
        assertThat(decision.outcome()).isEqualTo("APPROVED");
        assertThat(decision.basis()).isEqualTo("AUTOMATIC");
        assertThat(decision.decidedBy())
                .as("INV-KYC-02's second actor case: the platform, under the stated policy")
                .isNull();
        assertThat(decision.reason()).isEqualTo(KycDecision.AUTOMATIC_APPROVAL_REASON);
        assertThat(evidenceOf(decision.id()))
                .containsExactlyInAnyOrderElementsOf(checkIdsOf(opened.caseId()));
        assertThat(auditOfActor(
                        "kyc.DecisionRecorded", opened.caseId().value().toString(), "system"))
                .contains("basis=AUTOMATIC");

        // A re-run converges at every layer: no second decision, no second record.
        run(opened);
        assertThat(decisionRowOf(opened.caseId()).id()).isEqualTo(decision.id());
        assertThat(auditCountOf("kyc.DecisionRecorded", opened.caseId().value().toString()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the automatic decision is reproducible: refs + pinned policy re-derive it")
    void theAutomaticDecisionReplays() throws Exception {
        Case opened = givenAnOpenCase();
        stubEveryPathClear();
        run(opened);
        DecisionRow decision = decisionRowOf(opened.caseId());

        // The replay (INV-KYC-02, INV-CRD-01's regime three phases early): rebuild the
        // decision's inputs from what the record REFERENCES - the check rows it named and the
        // case's pinned policy version - and run the same policy code. The outcome must be the
        // stored one, with nothing read that the record does not point at.
        List<VerificationCheck> referenced = checksByIds(evidenceOf(decision.id()));
        assertThat(referenced).isNotEmpty();
        KycCase kycCase =
                KycCase.rehydrate(
                        opened.caseId(),
                        opened.customerId(),
                        KycCaseKind.KYC,
                        KycCaseStatus.READY_FOR_DECISION,
                        new KycPolicyVersion(decision.policyVersion()),
                        Instant.now(CLOCK).minusSeconds(3600),
                        Instant.now(CLOCK).minusSeconds(3600));
        KycDecision replayed = KycDecision.automatic(IDS, CLOCK, kycCase, referenced);

        assertThat(replayed.outcome().name()).isEqualTo(decision.outcome());
        assertThat(replayed.reason()).isEqualTo(decision.reason());
        assertThat(replayed.policyVersion().value()).isEqualTo(decision.policyVersion());
    }

    // -----------------------------------------------------------------
    // The schema holds the record still

    @Test
    @DisplayName("UPDATE and DELETE are denied on every column of the decision (P0-TST-007)")
    void theDecisionIsImmutableAtThePrivilegeLevel() throws Exception {
        Fixture fixture = givenACaseAwaitingDecision();
        post(decisionOf(fixture.caseId()), givenASessionFor(givenAReviewer()), REJECTION);
        DecisionRow decision = decisionRowOf(fixture.caseId());
        assertThat(decision).isNotNull();

        // Every column, with the list derived from the catalogue rather than remembered - a
        // column-level grant added later is invisible to table_privileges (P0-TST-007's
        // finding), and this sweep is what would meet it.
        List<String> columns = columnsOf("kyc_decision");
        assertThat(columns).contains("outcome", "reason", "decided_by");
        for (String column : columns) {
            try (Connection app = DatabaseRoles.application();
                    PreparedStatement update =
                            app.prepareStatement(
                                    "UPDATE kyc.kyc_decision SET " + column + " = " + column
                                            + " WHERE id = ?")) {
                update.setObject(1, decision.id());
                assertThatExceptionOfType(SQLException.class)
                        .as("UPDATE (%s) must be denied", column)
                        .isThrownBy(update::executeUpdate)
                        .withMessageContaining("permission denied");
            }
        }
        try (Connection app = DatabaseRoles.application();
                PreparedStatement delete =
                        app.prepareStatement("DELETE FROM kyc.kyc_decision WHERE id = ?")) {
            delete.setObject(1, decision.id());
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(delete::executeUpdate)
                    .withMessageContaining("permission denied");
        }
        try (Connection app = DatabaseRoles.application();
                PreparedStatement delete =
                        app.prepareStatement(
                                "DELETE FROM kyc.kyc_decision_check WHERE decision_id = ?")) {
            delete.setObject(1, decision.id());
            assertThatExceptionOfType(SQLException.class)
                    .as("the evidence references are as permanent as the decision")
                    .isThrownBy(delete::executeUpdate)
                    .withMessageContaining("permission denied");
        }
    }

    // -----------------------------------------------------------------

    private record Fixture(KycCaseId caseId) {}

    private record Case(KycCaseId caseId, UUID customerId) {}

    private record DecisionRow(
            UUID id,
            String outcome,
            String basis,
            UUID decidedBy,
            String reason,
            String policyVersion) {}

    private static final String[] ALL_PATHS = {
        IdentityVerificationAdapter.PATH,
        DocumentVerificationAdapter.PATH,
        ScreeningAdapter.SANCTIONS_PATH,
        ScreeningAdapter.PEP_PATH,
        ScreeningAdapter.ADVERSE_MEDIA_PATH
    };

    private static void stubEveryPathClear() {
        for (String path : ALL_PATHS) {
            provider.succeedsWith(path, 200, "{\"status\":\"clear\"}");
        }
    }

    private void run(Case opened) {
        try (CorrelationContext.Scope flow =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            runs.runChecks(opened.caseId(), opened.customerId());
        }
    }

    /** A case whose one HIT was reviewed and resolved: durably {@code READY_FOR_DECISION}. */
    private Fixture givenACaseAwaitingDecision() throws SQLException {
        Fixture inReview = givenACaseInReview();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            for (UUID taskId : openTaskIdsOf(app, inReview.caseId())) {
                reviewTaskStore.resolve(
                        app,
                        com.finapp.kyc.ReviewTaskId.of(taskId),
                        inReview.caseId(),
                        IDS.next(),
                        "resolved for the decision fixture",
                        Instant.now(CLOCK));
            }
            cases.moveStatus(
                    app,
                    inReview.caseId(),
                    KycCaseStatus.IN_REVIEW,
                    KycCaseStatus.READY_FOR_DECISION,
                    Instant.now(CLOCK));
            app.commit();
        }
        return inReview;
    }

    /** A case with one HIT check and its open task, in {@code IN_REVIEW}. */
    private Fixture givenACaseInReview() throws SQLException {
        Case opened = givenAnOpenCase();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            VerificationCheck check =
                    checkStore
                            .requestOrConverge(
                                    app,
                                    VerificationCheck.request(
                                            IDS, CLOCK, opened.caseId(), CheckType.SANCTIONS))
                            .check();
            checkStore.dispatch(app, check.id(), Instant.now(CLOCK));
            checkStore.complete(app, check.id(), CheckOutcome.HIT, Instant.now(CLOCK));
            reviewTaskStore.openForCheck(
                    app, ReviewTask.open(IDS, CLOCK, opened.caseId(), check.id()));
            cases.moveStatus(
                    app,
                    opened.caseId(),
                    KycCaseStatus.OPEN,
                    KycCaseStatus.CHECKS_IN_PROGRESS,
                    Instant.now(CLOCK));
            cases.moveStatus(
                    app,
                    opened.caseId(),
                    KycCaseStatus.CHECKS_IN_PROGRESS,
                    KycCaseStatus.IN_REVIEW,
                    Instant.now(CLOCK));
            app.commit();
        }
        return new Fixture(opened.caseId());
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
            KycCase opened = cases.openOrConverge(app, KycCase.open(IDS, CLOCK, customer, KycCaseKind.KYC)).kycCase();
            app.commit();
            return new Case(opened.id(), customer);
        }
    }

    private IdentityId givenAReviewer() throws SQLException {
        IdentityId reviewer = givenAnIdentity();
        CorrelationContext.Scope correlation =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
        SecurityContext.Scope actor = SecurityContext.enterSystem();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, reviewer, RoleName.KYC_REVIEWER, reviewer, "fixture");
            app.commit();
        } finally {
            actor.close();
            correlation.close();
        }
        return reviewer;
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session session =
                Session.issue(
                        IDS,
                        CLOCK,
                        identity,
                        SessionToken.of(plaintext),
                        AssuranceLevel.PASSWORD,
                        SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return plaintext;
    }

    private static IdentityId givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Ada Lovelace', now())",
                    party);
            execute(
                    app,
                    // Back-dated: the P1-TSK-031 remedy for the container clock's backwards
                    // corrections.
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at)"
                            + " VALUES (?, ?, ?, 'ACTIVE', now() - interval '1 hour',"
                            + " now() - interval '1 hour')",
                    identity,
                    party,
                    "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        return IdentityId.of(identity);
    }

    // -----------------------------------------------------------------

    private static String decisionOf(KycCaseId caseId) {
        return "/kyc/cases/" + caseId.value() + "/decision";
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static DecisionRow decisionRowOf(KycCaseId caseId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT id, outcome, decision_basis, decided_by, reason,"
                                        + " policy_version"
                                        + " FROM kyc.kyc_decision WHERE case_id = ?")) {
            select.setObject(1, caseId.value());
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new DecisionRow(
                        rows.getObject(1, UUID.class),
                        rows.getString(2),
                        rows.getString(3),
                        rows.getObject(4, UUID.class),
                        rows.getString(5),
                        rows.getString(6));
            }
        }
    }

    private static List<UUID> evidenceOf(UUID decisionId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT check_id FROM kyc.kyc_decision_check"
                                        + " WHERE decision_id = ?")) {
            select.setObject(1, decisionId);
            try (ResultSet rows = select.executeQuery()) {
                List<UUID> checks = new ArrayList<>();
                while (rows.next()) {
                    checks.add(rows.getObject(1, UUID.class));
                }
                return checks;
            }
        }
    }

    private static List<UUID> checkIdsOf(KycCaseId caseId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT id FROM kyc.verification_check WHERE case_id = ?")) {
            select.setObject(1, caseId.value());
            try (ResultSet rows = select.executeQuery()) {
                List<UUID> checks = new ArrayList<>();
                while (rows.next()) {
                    checks.add(rows.getObject(1, UUID.class));
                }
                return checks;
            }
        }
    }

    private List<VerificationCheck> checksByIds(List<UUID> checkIds) throws SQLException {
        List<VerificationCheck> referenced = new ArrayList<>();
        try (Connection app = DatabaseRoles.application()) {
            for (UUID checkId : checkIds) {
                try (PreparedStatement select =
                        app.prepareStatement(
                                "SELECT id, case_id, check_type, status, requested_at,"
                                        + " status_changed_at"
                                        + " FROM kyc.verification_check WHERE id = ?")) {
                    select.setObject(1, checkId);
                    try (ResultSet rows = select.executeQuery()) {
                        rows.next();
                        referenced.add(
                                VerificationCheck.rehydrate(
                                        com.finapp.kyc.CheckId.of(
                                                rows.getObject(1, UUID.class)),
                                        KycCaseId.of(rows.getObject(2, UUID.class)),
                                        CheckType.valueOf(rows.getString(3)),
                                        CheckStatus.valueOf(rows.getString(4)),
                                        rows.getTimestamp(5).toInstant(),
                                        rows.getTimestamp(6).toInstant()));
                    }
                }
            }
        }
        return referenced;
    }

    private static List<UUID> openTaskIdsOf(Connection app, KycCaseId caseId)
            throws SQLException {
        try (PreparedStatement select =
                app.prepareStatement(
                        "SELECT id FROM kyc.review_task WHERE case_id = ? AND status = 'OPEN'")) {
            select.setObject(1, caseId.value());
            try (ResultSet rows = select.executeQuery()) {
                List<UUID> tasks = new ArrayList<>();
                while (rows.next()) {
                    tasks.add(rows.getObject(1, UUID.class));
                }
                return tasks;
            }
        }
    }

    private static String caseStatusOf(KycCaseId caseId) throws SQLException {
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

    private static String policyVersionOf(KycCaseId caseId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT policy_version FROM kyc.kyc_case WHERE id = ?")) {
            select.setObject(1, caseId.value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static List<String> columnsOf(String table) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT column_name FROM information_schema.columns"
                                        + " WHERE table_schema = 'kyc' AND table_name = ?")) {
            select.setString(1, table);
            try (ResultSet rows = select.executeQuery()) {
                List<String> columns = new ArrayList<>();
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
                return columns;
            }
        }
    }

    private static String auditOfActor(String operation, String targetId, String actorId)
            throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT coalesce(string_agg(coalesce(reason, '') || ' ' ||"
                                        + " coalesce(change_summary, ''), ' '), '')"
                                        + " FROM platform.audit_record"
                                        + " WHERE operation = ? AND target_id = ? AND actor_id = ?")) {
            select.setString(1, operation);
            select.setString(2, targetId);
            select.setString(3, actorId);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static long auditCountOf(String operation, String targetId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.audit_record"
                                        + " WHERE operation = ? AND target_id = ?")) {
            select.setString(1, operation);
            select.setString(2, targetId);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
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
