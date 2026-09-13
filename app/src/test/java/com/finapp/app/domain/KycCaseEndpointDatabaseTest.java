package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.platform.testing.database.DatabaseRoles;
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
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The person's own case endpoints, over real HTTP (`P2-TSK-006`).
 *
 * <h2>What the security tests are</h2>
 *
 * <p>{@code DOD-SEC}'s negatives, one per control: authentication (the two 401s), the consent
 * gate (a refused POST writes nothing, and absence and withdrawal are one refusal — {@code
 * INV-CNS-01} at this surface), and the shaping (a case in review and a case in checks answer
 * <strong>byte-identically</strong>, with no screening vocabulary — the tipping-off control,
 * asserted as an equality between the causes because only the equality proves
 * indistinguishability).
 *
 * <p>The usual negative ownership test is impossible to write, deliberately: neither verb takes
 * a path variable, a query parameter or a body, so an attacker has nothing to name a victim's
 * case with. What is asserted instead is the resolution chain — each person reaches exactly
 * their own case (`P1-TSK-030`'s recorded trade: a weaker shape of test for a stronger shape of
 * control).
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the person's own case endpoints (P2-TSK-006)")
class KycCaseEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    private static final String GRANT = "{\"purpose\":\"KYC_PROCESSING\",\"textVersion\":1}";

    @LocalServerPort private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    /**
     * The acceptance criterion, whole: a registered, consented person reaches an open case, and
     * a second POST is the same case — with the created path recorded <strong>as the
     * person</strong> and announced exactly once, and the converged path silent.
     */
    @Test
    @DisplayName("a consented person reaches an open case, and a second POST is the same case")
    void aConsentedPersonReachesAnOpenCase() throws Exception {
        Person ada = givenAPerson();
        assertThat(post("/me/consents", ada.session(), GRANT).statusCode()).isEqualTo(201);

        HttpResponse<String> first = post("/me/kyc", ada.session(), null);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.body()).isEqualTo("{\"status\":\"OPEN\"}");

        HttpResponse<String> second = post("/me/kyc", ada.session(), null);
        assertThat(second.statusCode())
                .as("the convergence idiom: the retry answers the creation's own status")
                .isEqualTo(201);
        assertThat(second.body()).isEqualTo(first.body());

        assertThat(caseCount(ada)).as("one case, however many times the request arrived").isEqualTo(1);

        // The created path is the person's own act: the record names THEM, never the platform
        // (the consumer door's records say 'system'; this door's must not - the
        // AuditNamesTheActorDatabaseTest property, asserted here at its newest emitter).
        assertThat(openingAuditCount(ada, ada.identity().value().toString()))
                .as("one kyc.CaseOpened record, actor the person")
                .isEqualTo(1);
        assertThat(openingAuditCount(ada, "system"))
                .as("this door never records the platform as the opener")
                .isZero();
        assertThat(announcementCount(ada))
                .as("one kyc.KycCaseOpened announcement - the converged POST announces nothing")
                .isEqualTo(1);

        assertThat(get("/me/kyc", ada.session()).body()).isEqualTo("{\"status\":\"OPEN\"}");
    }

    /**
     * {@code INV-CNS-01} at this surface: no basis refuses with nothing written — and absence
     * and withdrawal are <strong>one</strong> refusal, compared to each other with only the
     * per-request correlation identifier excluded.
     */
    @Test
    @DisplayName("no consent basis is a 409 with nothing written, absence and withdrawal alike")
    void aConsentAbsentPostIsRefusedWithNothingWritten() throws Exception {
        Person never = givenAPerson();
        Person withdrew = givenAPerson();
        assertThat(post("/me/consents", withdrew.session(), GRANT).statusCode()).isEqualTo(201);
        assertThat(delete("/me/consents/KYC_PROCESSING", withdrew.session()).statusCode())
                .isEqualTo(204);

        HttpResponse<String> refusedNever = post("/me/kyc", never.session(), null);
        HttpResponse<String> refusedWithdrew = post("/me/kyc", withdrew.session(), null);

        assertThat(refusedNever.statusCode()).isEqualTo(409);
        assertThat(refusedNever.body()).contains("consent.ConsentRequired");
        assertThat(withoutCorrelation(refusedWithdrew.body()))
                .as("a caller cannot tell a withdrawal from an absence (INV-CNS-01)")
                .isEqualTo(withoutCorrelation(refusedNever.body()));

        for (Person person : new Person[] {never, withdrew}) {
            assertThat(caseCount(person)).as("no case").isZero();
            assertThat(openingAuditCount(person, person.identity().value().toString()))
                    .as("no act, so no record")
                    .isZero();
            assertThat(announcementCount(person)).as("no announcement").isZero();
        }
    }

    /**
     * The task's stated security half: a screening hit is indistinguishable from ordinary
     * processing in the customer-facing status. A case a person is reviewing and a case whose
     * checks are merely running answer <strong>byte-identically</strong>, and no screening
     * vocabulary appears anywhere in the body.
     */
    @Test
    @DisplayName("a case in review and a case in checks are one answer, with no review vocabulary")
    void aScreeningHitIsInvisibleInTheStatus() throws Exception {
        Person checking = givenAConsentedPersonWithACase();
        Person reviewed = givenAConsentedPersonWithACase();
        moveCase(checking, "CHECKS_IN_PROGRESS");
        moveCase(reviewed, "IN_REVIEW");

        HttpResponse<String> checkingView = get("/me/kyc", checking.session());
        HttpResponse<String> reviewedView = get("/me/kyc", reviewed.session());

        assertThat(reviewedView.body())
                .as("which of the two a case is in is exactly what tipping-off forbids disclosing")
                .isEqualTo(checkingView.body());
        assertThat(reviewedView.body()).isEqualTo("{\"status\":\"IN_PROGRESS\"}");
        assertThat(reviewedView.body()).doesNotContain("REVIEW").doesNotContain("HIT");
    }

    /**
     * The read answers for a decided case — {@code APPROVED} is the answer the person was
     * waiting for, never a 404 ({@code findLatestFor}, the {@code KybService} reasoning) — and
     * a POST after the decision opens a <em>successor</em> case: the one-open-case index frees
     * the slot on decision, and changed-circumstances re-verification is a new case by design
     * ({@code INV-LIFE-04}).
     */
    @Test
    @DisplayName("a decided case is readable, and a later POST opens its successor")
    void aDecidedCaseIsReadableAndAdmitsASuccessor() throws Exception {
        Person ada = givenAConsentedPersonWithACase();
        moveCase(ada, "APPROVED");

        assertThat(get("/me/kyc", ada.session()).body()).isEqualTo("{\"status\":\"APPROVED\"}");

        assertThat(post("/me/kyc", ada.session(), null).body())
                .as("the freed slot admits a successor case, gated like the first")
                .isEqualTo("{\"status\":\"OPEN\"}");
        assertThat(caseCount(ada)).isEqualTo(2);
        assertThat(get("/me/kyc", ada.session()).body())
                .as("the latest case is the answer once a successor exists")
                .isEqualTo("{\"status\":\"OPEN\"}");
    }

    @Test
    @DisplayName("a person who never had a case reads an honest 404")
    void neverHadACaseIsA404() throws Exception {
        Person ada = givenAPerson();
        HttpResponse<String> view = get("/me/kyc", ada.session());
        assertThat(view.statusCode()).isEqualTo(404);
        assertThat(view.body()).contains("api.NotFound");
    }

    @Test
    @DisplayName("both verbs refuse without a session")
    void bothVerbsRefuseWithoutASession() throws Exception {
        assertThat(post("/me/kyc", null, null).statusCode()).isEqualTo(401);
        assertThat(get("/me/kyc", null).statusCode()).isEqualTo(401);
    }

    /**
     * The POST declares no body, so a stray one — garbage included — changes nothing: it is
     * never read, and the answer is the same 201. The no-500 property for a surface whose only
     * inputs are the session and the world.
     */
    @Test
    @DisplayName("a stray request body changes nothing")
    void aStrayBodyChangesNothing() throws Exception {
        Person ada = givenAPerson();
        assertThat(post("/me/consents", ada.session(), GRANT).statusCode()).isEqualTo(201);

        HttpResponse<String> response = post("/me/kyc", ada.session(), "not json at all {{{");
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).isEqualTo("{\"status\":\"OPEN\"}");
        assertThat(caseCount(ada)).isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // HTTP

    private record Person(UUID party, UUID customer, IdentityId identity, String session) {}

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path, String token) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path))
                        .DELETE();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Strips the per-request member so two refusals can be compared to each other. */
    private static String withoutCorrelation(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"-\"");
    }

    // -----------------------------------------------------------------
    // Database

    private static long caseCount(Person person) throws SQLException {
        return count(
                "SELECT count(*) FROM kyc.kyc_case WHERE customer_id = ?", person.customer());
    }

    private static long openingAuditCount(Person person, String actor) throws SQLException {
        return count(
                "SELECT count(*) FROM platform.audit_record"
                        + " WHERE operation = 'kyc.CaseOpened' AND target_id = ?"
                        + " AND actor_id = ?",
                person.customer().toString(),
                actor);
    }

    /** Announcements are keyed by the case, so the customer is reached through it. */
    private static long announcementCount(Person person) throws SQLException {
        return count(
                "SELECT count(*) FROM platform.outbox_event e"
                        + " WHERE e.event_type = 'kyc.KycCaseOpened'"
                        + " AND EXISTS (SELECT 1 FROM kyc.kyc_case c"
                        + "             WHERE c.id = e.aggregate_id AND c.customer_id = ?)",
                person.customer());
    }

    /**
     * Moves the person's case directly, through the application role's column-narrowed
     * {@code UPDATE (status, status_changed_at)} grant — the shaping test needs states only the
     * check machine and the reviewer surface produce in production.
     */
    private static void moveCase(Person person, String status) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            // GREATEST(now(), opened_at): opened_at is the service's JVM clock and now() the
            // container's, which runs behind and is corrected backwards (§Local Environment) -
            // the kyc_case_status_change_is_not_before_opening constraint is right, and a bare
            // now() here is the P1-TSK-031 fixture fragility, met again.
            execute(
                    app,
                    "UPDATE kyc.kyc_case SET status = ?,"
                            + " status_changed_at = GREATEST(now(), opened_at)"
                            + " WHERE customer_id = ?",
                    status,
                    person.customer());
        }
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                select.setObject(i + 1, arguments[i]);
            }
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    // -----------------------------------------------------------------
    // Fixtures

    /** A person, their live customer, an active identity, and a session — no consent yet. */
    private Person givenAPerson() throws SQLException {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        UUID identity = IDS.next();
        String login = "k" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Kyc Case Person', now())",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'PENDING',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    customer,
                    party);
            execute(
                    app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at)"
                            + " VALUES (?, ?, ?, 'ACTIVE', now() - interval '1 hour',"
                            + " now() - interval '1 hour')",
                    identity,
                    party,
                    login);
        }
        return new Person(
                party, customer, IdentityId.of(identity), givenASessionFor(IdentityId.of(identity)));
    }

    private Person givenAConsentedPersonWithACase() throws Exception {
        Person person = givenAPerson();
        assertThat(post("/me/consents", person.session(), GRANT).statusCode()).isEqualTo(201);
        assertThat(post("/me/kyc", person.session(), null).statusCode()).isEqualTo(201);
        return person;
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
