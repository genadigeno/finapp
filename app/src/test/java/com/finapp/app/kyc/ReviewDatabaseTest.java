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
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.CheckType;
import com.finapp.kyc.JdbcKycCaseStore;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.ReviewTask;
import com.finapp.kyc.ReviewTaskId;
import com.finapp.kyc.ReviewTaskStore;
import com.finapp.kyc.VerificationCheck;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The reviewer endpoints, over real HTTP (`P2-TSK-012`).
 *
 * <p>The acceptance, clause by clause: a review task resolves <strong>exactly once</strong>
 * (conditional UPDATE, ten-way race), by an <strong>authorized person</strong> (negative
 * authorization tests, {@code INV-AUD-03}), with a <strong>reason</strong> (422 at the
 * boundary; the record's own {@code NOT NULL} coherence), <strong>audibly</strong>
 * ({@code kyc.ReviewResolved} naming the reviewer) — and the case whose last open task is
 * resolved exits {@code IN_REVIEW → READY_FOR_DECISION} through the conditional whose
 * no-open-task predicate lives in the statement.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the reviewer endpoints (P2-TSK-012)")
@SuppressWarnings("try") // correlation and security scopes are used for their close side effect
class ReviewDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    private static final String REASON =
            "{\"reason\":\"false positive: date of birth mismatch, case OPS-7731\"}";

    @LocalServerPort private int port;

    @Autowired private Authorization authorization;
    @Autowired private CheckStore<Connection> checkStore;
    @Autowired private ReviewTaskStore<Connection> reviewTaskStore;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final JdbcKycCaseStore cases = new JdbcKycCaseStore();

    // -----------------------------------------------------------------
    // The acceptance, end to end

    @Test
    @DisplayName("a reviewer resolves the last task: recorded, audited, and the case exits")
    void aResolutionIsRecordedAuditedAndExitsTheCase() throws Exception {
        Fixture fixture = givenACaseInReview(1);
        IdentityId reviewer = givenAReviewer();

        int status =
                post(resolutionOf(fixture.caseId(), fixture.tasks().get(0)),
                                givenASessionFor(reviewer),
                                REASON)
                        .statusCode();

        assertThat(status).isEqualTo(204);
        TaskRow resolved = taskRow(fixture.tasks().get(0));
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.resolvedBy())
                .as("the column and the audit record name the same person")
                .isEqualTo(reviewer.value());
        assertThat(resolved.reason()).contains("OPS-7731");

        // INV-KYC-04: the record naming the reviewer, carrying the justification.
        assertThat(auditOf("kyc.ReviewResolved", fixture.tasks().get(0).value().toString(),
                        reviewer))
                .contains("OPS-7731")
                .contains("case=");

        // The last open task was resolved, so the case exits review.
        assertThat(caseStatusOf(fixture.caseId())).isEqualTo("READY_FOR_DECISION");
    }

    @Test
    @DisplayName("the exit is conditional on NO open task, in the statement")
    void theCaseWaitsForItsLastTask() throws Exception {
        Fixture fixture = givenACaseInReview(2);
        String session = givenASessionFor(givenAReviewer());

        assertThat(post(resolutionOf(fixture.caseId(), fixture.tasks().get(0)), session, REASON)
                        .statusCode())
                .isEqualTo(204);
        assertThat(caseStatusOf(fixture.caseId()))
                .as("one of two tasks resolved: the case still owes a person a judgement")
                .isEqualTo("IN_REVIEW");

        assertThat(post(resolutionOf(fixture.caseId(), fixture.tasks().get(1)), session, REASON)
                        .statusCode())
                .isEqualTo(204);
        assertThat(caseStatusOf(fixture.caseId())).isEqualTo("READY_FOR_DECISION");
    }

    @Test
    @DisplayName("a second resolution is a 409, the judgement stands, and the retry heals")
    void aSecondResolutionLosesAndHeals() throws Exception {
        Fixture fixture = givenACaseInReview(1);
        IdentityId first = givenAReviewer();
        IdentityId second = givenAReviewer();
        ReviewTaskId task = fixture.tasks().get(0);

        assertThat(post(resolutionOf(fixture.caseId(), task), givenASessionFor(first), REASON)
                        .statusCode())
                .isEqualTo(204);
        int lost =
                post(
                                resolutionOf(fixture.caseId(), task),
                                givenASessionFor(second),
                                "{\"reason\":\"confirmed hit, escalating\"}")
                        .statusCode();

        assertThat(lost)
                .as("reporting success would tell the second reviewer their judgement was"
                        + " recorded when somebody else's was")
                .isEqualTo(409);
        TaskRow row = taskRow(task);
        assertThat(row.resolvedBy())
                .as("the first judgement stands; a wrong one is a new review event, never an"
                        + " edit (INV-LIFE-04)")
                .isEqualTo(first.value());
        assertThat(row.reason()).contains("OPS-7731").doesNotContain("escalating");
        assertThat(auditCountOf("kyc.ReviewResolved", task.value().toString()))
                .as("one resolution, one record")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a stranded exit is healed by the retry's 409 path")
    void aStrandedExitIsHealed() throws Exception {
        // A crash between a resolve's commit and its exit leaves exactly this state: every task
        // resolved, the case still IN_REVIEW. Simulated through the store - the same conditional
        // UPDATE production runs - with no exit afterwards.
        Fixture fixture = givenACaseInReview(1);
        IdentityId reviewer = givenAReviewer();
        try (Connection app = DatabaseRoles.application()) {
            reviewTaskStore.resolve(
                    app,
                    fixture.tasks().get(0),
                    fixture.caseId(),
                    reviewer.value(),
                    "resolved before the crash",
                    Instant.now(CLOCK));
        }
        assertThat(caseStatusOf(fixture.caseId())).isEqualTo("IN_REVIEW");

        int retried =
                post(resolutionOf(fixture.caseId(), fixture.tasks().get(0)),
                                givenASessionFor(reviewer),
                                REASON)
                        .statusCode();

        assertThat(retried).isEqualTo(409);
        assertThat(caseStatusOf(fixture.caseId()))
                .as("the 409 path re-attempts the exit - the P2-TSK-011 duplicate-heals shape")
                .isEqualTo("READY_FOR_DECISION");
    }

    @Test
    @DisplayName("ten concurrent resolutions produce one judgement and one record")
    void tenConcurrentResolutionsProduceOne() throws Exception {
        Fixture fixture = givenACaseInReview(1);
        ReviewTaskId task = fixture.tasks().get(0);
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
                    () -> post(resolutionOf(fixture.caseId(), task), token, REASON).statusCode());
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
        assertThat(auditCountOf("kyc.ReviewResolved", task.value().toString())).isEqualTo(1);
        TaskRow row = taskRow(task);
        assertThat(reviewers)
                .as("the recorded reviewer is one of the racers - the row and its record agree")
                .extracting(IdentityId::value)
                .contains(row.resolvedBy());
        assertThat(caseStatusOf(fixture.caseId())).isEqualTo("READY_FOR_DECISION");
    }

    // -----------------------------------------------------------------
    // The refusals

    @Test
    @DisplayName("another case's task is a 404, and nothing is written")
    void anotherCasesTaskIsNotFound() throws Exception {
        Fixture mine = givenACaseInReview(1);
        Fixture other = givenACaseInReview(1);
        ReviewTaskId othersTask = other.tasks().get(0);

        int status =
                post(resolutionOf(mine.caseId(), othersTask),
                                givenASessionFor(givenAReviewer()),
                                REASON)
                        .statusCode();

        assertThat(status)
                .as("the composite URL names a resource that does not exist - the"
                        + " belongs-to-case predicate is in the statement")
                .isEqualTo(404);
        assertThat(taskRow(othersTask).status()).isEqualTo("OPEN");
        assertThat(auditCountOf("kyc.ReviewResolved", othersTask.value().toString())).isZero();
    }

    @Test
    @DisplayName("a session holding no role is refused by both endpoints (INV-AUD-03)")
    void bothEndpointsRefuseASessionWithNoRole() throws Exception {
        Fixture fixture = givenACaseInReview(1);
        String session = givenASessionFor(givenAnIdentity());

        assertThat(get(caseOf(fixture.caseId()), session).statusCode())
                .as("a valid session is not KYC_REVIEW")
                .isEqualTo(403);
        assertThat(post(resolutionOf(fixture.caseId(), fixture.tasks().get(0)), session, REASON)
                        .statusCode())
                .isEqualTo(403);
        assertThat(taskRow(fixture.tasks().get(0)).status()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("a missing reason is a 422 naming the field, never a 500")
    void aMissingReasonIsRefusedAtTheBoundary() throws Exception {
        Fixture fixture = givenACaseInReview(1);

        HttpResponse<String> response =
                post(
                        resolutionOf(fixture.caseId(), fixture.tasks().get(0)),
                        givenASessionFor(givenAReviewer()),
                        "{}");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("api.ValidationFailed").contains("reason");
        assertThat(taskRow(fixture.tasks().get(0)).status()).isEqualTo("OPEN");
    }

    // -----------------------------------------------------------------
    // The audited read

    @Test
    @DisplayName("reading a case returns its file and puts the reviewer on the record")
    void readingACaseIsAudited() throws Exception {
        Fixture fixture = givenACaseInReview(1);
        IdentityId reviewer = givenAReviewer();

        HttpResponse<String> response =
                get(caseOf(fixture.caseId()), givenASessionFor(reviewer));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains(fixture.caseId().value().toString())
                .contains("IN_REVIEW")
                .contains("SANCTIONS")
                .contains(fixture.tasks().get(0).value().toString())
                // References only: no evidence bytes, no document content, ever.
                .doesNotContain("ciphertext");

        // INV-KYC-06's shape one level up: the trail of who looked is the control on the
        // person with every right to look.
        assertThat(auditCountOfActor(
                        "kyc.CaseRead", fixture.caseId().value().toString(), reviewer))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown or malformed case is a 404, and a guessed id leaves no record")
    void anUnknownCaseIsNotFoundAndUnrecorded() throws Exception {
        String session = givenASessionFor(givenAReviewer());
        UUID guessed = IDS.next();

        assertThat(get("/kyc/cases/" + guessed, session).statusCode()).isEqualTo(404);
        assertThat(get("/kyc/cases/not-a-uuid", session).statusCode())
                .as("malformed and absent are one answer")
                .isEqualTo(404);
        assertThat(auditCountOf("kyc.CaseRead", guessed.toString()))
                .as("a trail entry for a guessed identifier would put identifiers that were"
                        + " never real into the permanent record")
                .isZero();
    }

    // -----------------------------------------------------------------
    // The schema holds the record still

    @Test
    @DisplayName("a resolution cannot be rewritten: the trigger freezes RESOLVED rows")
    void aResolutionIsFrozen() throws Exception {
        Fixture fixture = givenACaseInReview(1);
        IdentityId reviewer = givenAReviewer();
        assertThat(
                        post(resolutionOf(fixture.caseId(), fixture.tasks().get(0)),
                                        givenASessionFor(reviewer),
                                        REASON)
                                .statusCode())
                .isEqualTo(204);

        try (Connection app = DatabaseRoles.application();
                PreparedStatement rewrite =
                        app.prepareStatement(
                                "UPDATE kyc.review_task SET resolution_reason = 'rewritten'"
                                        + " WHERE id = ?")) {
            rewrite.setObject(1, fixture.tasks().get(0).value());
            assertThatExceptionOfType(SQLException.class)
                    .as("the record a decision rests on cannot be edited by ANY writer")
                    .isThrownBy(rewrite::executeUpdate)
                    // The trigger raises USING ERRCODE = 'check_violation'.
                    .satisfies(failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
        }
        assertThat(taskRow(fixture.tasks().get(0)).reason()).contains("OPS-7731");
    }

    @Test
    @DisplayName("the identity columns stay unwritable by the application role")
    void identityColumnsStayUnwritable() throws Exception {
        Fixture fixture = givenACaseInReview(1);

        try (Connection app = DatabaseRoles.application();
                PreparedStatement redate =
                        app.prepareStatement(
                                "UPDATE kyc.review_task SET opened_at = now() WHERE id = ?")) {
            redate.setObject(1, fixture.tasks().get(0).value());
            assertThatExceptionOfType(SQLException.class)
                    .as("the column-level grant names status and the resolution columns, and"
                            + " nothing else (the V004 narrowing)")
                    .isThrownBy(redate::executeUpdate)
                    .withMessageContaining("permission denied");
        }
    }

    // -----------------------------------------------------------------

    private record Fixture(KycCaseId caseId, List<ReviewTaskId> tasks) {}

    private record TaskRow(String status, UUID resolvedBy, String reason) {}

    /**
     * A case in {@code IN_REVIEW} with {@code hits} HIT checks, each owing a person its task —
     * built through the same stores production writes with, walked along the machine's own
     * edges ({@code OPEN → CHECKS_IN_PROGRESS → IN_REVIEW}).
     */
    private Fixture givenACaseInReview(int hits) throws SQLException {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        CheckType[] types = {CheckType.SANCTIONS, CheckType.PEP, CheckType.ADVERSE_MEDIA};
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
            List<ReviewTaskId> tasks = new ArrayList<>();
            for (int i = 0; i < hits; i++) {
                VerificationCheck check =
                        checkStore
                                .requestOrConverge(
                                        app,
                                        VerificationCheck.request(
                                                IDS, CLOCK, opened.id(), types[i % types.length]))
                                .check();
                checkStore.dispatch(app, check.id(), Instant.now(CLOCK));
                checkStore.complete(app, check.id(), CheckOutcome.HIT, Instant.now(CLOCK));
                ReviewTask task = ReviewTask.open(IDS, CLOCK, opened.id(), check.id());
                reviewTaskStore.openForCheck(app, task);
                tasks.add(task.id());
            }
            cases.moveStatus(
                    app,
                    opened.id(),
                    KycCaseStatus.OPEN,
                    KycCaseStatus.CHECKS_IN_PROGRESS,
                    Instant.now(CLOCK));
            cases.moveStatus(
                    app,
                    opened.id(),
                    KycCaseStatus.CHECKS_IN_PROGRESS,
                    KycCaseStatus.IN_REVIEW,
                    Instant.now(CLOCK));
            app.commit();
            return new Fixture(opened.id(), List.copyOf(tasks));
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
                    // corrections, the IdentityAdministrationDatabaseTest fixture verbatim.
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

    private static String caseOf(KycCaseId caseId) {
        return "/kyc/cases/" + caseId.value();
    }

    private static String resolutionOf(KycCaseId caseId, ReviewTaskId taskId) {
        return "/kyc/cases/" + caseId.value() + "/reviews/" + taskId.value() + "/resolution";
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

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static TaskRow taskRow(ReviewTaskId taskId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT status, resolved_by, resolution_reason"
                                        + " FROM kyc.review_task WHERE id = ?")) {
            select.setObject(1, taskId.value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return new TaskRow(
                        rows.getString(1),
                        rows.getObject(2, UUID.class),
                        rows.getString(3));
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

    private static String auditOf(String operation, String targetId, IdentityId actor)
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
            select.setString(3, actor.value().toString());
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

    private static long auditCountOfActor(String operation, String targetId, IdentityId actor)
            throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.audit_record"
                                        + " WHERE operation = ? AND target_id = ? AND actor_id = ?")) {
            select.setString(1, operation);
            select.setString(2, targetId);
            select.setString(3, actor.value().toString());
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
