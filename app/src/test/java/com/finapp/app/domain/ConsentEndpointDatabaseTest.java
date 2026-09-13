package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.consent.ConsentPurpose;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The consent endpoints, over real HTTP (`P2-TSK-018`).
 *
 * <h2>What the security tests are, and why there are exactly these</h2>
 *
 * <p>{@code DOD-SEC} demands a negative test for every control, and this surface has
 * <strong>one</strong> control: authentication. So the negatives are the three 401s — and the
 * sharper security claims point the other way: that <em>nothing else</em> can refuse a
 * withdrawal ({@link #aWithdrawalIsRefusableByNothingButAuthentication}), and that the query
 * discloses nothing about the shape of a refusal's history
 * ({@link #absenceAndWithdrawalAreOneAnswer}).
 *
 * <p>The {@code DELETE}'s path variable is a closed enum naming a category of processing shared
 * by everyone — not a resource identifier — so the usual negative ownership test has no request
 * to make: there is still nothing here an attacker can point at a victim.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the consent endpoints (P2-TSK-018)")
class ConsentEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    @LocalServerPort private int port;

    @org.springframework.beans.factory.annotation.Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    /**
     * The acceptance criterion: the lifecycle over HTTP, with the audit trail naming the person.
     */
    @Test
    @DisplayName("grant, withdraw and re-grant over HTTP, each act audited against the person")
    void theLifecycleOverHttpWithTheTrailNamingThePerson() throws Exception {
        Person ada = givenAPerson();
        double grantsBefore = consentCounter("finapp.consent.grant", "kyc_processing");
        double withdrawalsBefore = consentCounter("finapp.consent.withdrawal", "kyc_processing");

        assertThat(
                        post(
                                        "/me/consents",
                                        ada.session(),
                                        "{\"purpose\":\"KYC_PROCESSING\",\"textVersion\":1}")
                                .statusCode())
                .isEqualTo(201);
        assertThat(basisOf(ada, "KYC_PROCESSING")).isTrue();

        assertThat(delete("/me/consents/KYC_PROCESSING", ada.session()).statusCode())
                .isEqualTo(204);
        assertThat(basisOf(ada, "KYC_PROCESSING")).isFalse();

        assertThat(
                        post(
                                        "/me/consents",
                                        ada.session(),
                                        "{\"purpose\":\"KYC_PROCESSING\",\"textVersion\":1}")
                                .statusCode())
                .isEqualTo(201);
        assertThat(basisOf(ada, "KYC_PROCESSING")).isTrue();

        // The trail: one record per act, the ACTOR is the person - never the platform, because
        // consent is the most personal act on the platform - and the grant's summary names the
        // pinned version (INV-CNS-04's reconstructability), never the words.
        assertThat(auditCount(ada, "consent.ConsentGranted")).isEqualTo(2);
        assertThat(auditCount(ada, "consent.ConsentWithdrawn")).isEqualTo(1);
        assertThat(auditSummaries(ada, "consent.ConsentGranted"))
                .contains("purpose=KYC_PROCESSING")
                .contains("textVersion=1");

        // Each record's target is a consent record that really exists for this party - the row
        // and its trail committed together, so neither can name the other falsely.
        assertThat(auditTargetsExistAsRecords(ada)).isTrue();

        // The meters move with the acts and only with the acts (P2-TSK-020): two grants, one
        // withdrawal, each under its purpose - deltas, because the registry is shared across
        // this class's tests.
        assertThat(consentCounter("finapp.consent.grant", "kyc_processing"))
                .isEqualTo(grantsBefore + 2.0d);
        assertThat(consentCounter("finapp.consent.withdrawal", "kyc_processing"))
                .isEqualTo(withdrawalsBefore + 1.0d);

        // And the series NOTHING in any suite ever increments exists anyway - eager, per
        // purpose (P1-TSK-029). The plan-level guard checks meter names, and a registration
        // that quietly became lazy or per-acted-purpose would keep every name alive while this
        // series vanished; meters.get throws on an absent series, which is the assertion.
        assertThat(consentCounter("finapp.consent.withdrawal", "screening"))
                .isGreaterThanOrEqualTo(0.0d);
    }

    /**
     * {@code INV-CNS-01} at the surface: a person who never granted and a person who granted and
     * withdrew receive <strong>byte-identical</strong> rows from the query — asserted as an
     * equality between the two causes rather than as two assertions against a remembered
     * expectation, because only the equality proves indistinguishability.
     */
    @Test
    @DisplayName("absence and withdrawal are one answer to the query, byte-identical")
    void absenceAndWithdrawalAreOneAnswer() throws Exception {
        Person never = givenAPerson();
        Person withdrew = givenAPerson();

        assertThat(
                        post(
                                        "/me/consents",
                                        withdrew.session(),
                                        "{\"purpose\":\"SCREENING\",\"textVersion\":1}")
                                .statusCode())
                .isEqualTo(201);
        assertThat(delete("/me/consents/SCREENING", withdrew.session()).statusCode())
                .isEqualTo(204);

        assertThat(get("/me/consents", withdrew.session()).body())
                .as("a caller of the query cannot tell a withdrawal from an absence")
                .isEqualTo(get("/me/consents", never.session()).body());
    }

    /**
     * {@code INV-CNS-04} at the boundary: a grant against a version superseded by one demanding
     * re-consent is refused with nothing written — recording it would write a "consent" the
     * derivation immediately judges basis-less. A stale version <em>not</em> superseded by a
     * re-consent-demanding one stays grantable, which is the backlog's conditional honoured
     * rather than over-tightened.
     */
    @Test
    @DisplayName("a grant against a stale version is refused exactly when re-consent is demanded")
    void aStaleGrantIsRefusedWhenReconsentIsDemanded() throws Exception {
        Person ada = givenAPerson();

        seedTextVersion(ConsentPurpose.SCREENING, 2, true);
        seedTextVersion(ConsentPurpose.KYC_PROCESSING, 2, false);
        double screeningGrantsBefore = consentCounter("finapp.consent.grant", "screening");
        try {
            HttpResponse<String> stale =
                    post(
                            "/me/consents",
                            ada.session(),
                            "{\"purpose\":\"SCREENING\",\"textVersion\":1}");
            assertThat(stale.statusCode()).isEqualTo(409);
            assertThat(stale.body()).contains("consent.ReconsentRequired");
            assertThat(recordCount(ada)).as("a refused grant writes no fact").isZero();
            assertThat(auditCount(ada, "consent.ConsentGranted"))
                    .as("no act occurred, so there is nothing to record")
                    .isZero();
            assertThat(consentCounter("finapp.consent.grant", "screening"))
                    .as("a refused grant increments nothing: no act occurred (P2-TSK-020)")
                    .isEqualTo(screeningGrantsBefore);

            // Against the current version, the same person grants - and the response's row says
            // which version is current, so the remedy the 409 names is drivable.
            HttpResponse<String> current =
                    post(
                            "/me/consents",
                            ada.session(),
                            "{\"purpose\":\"SCREENING\",\"textVersion\":2}");
            assertThat(current.statusCode()).isEqualTo(201);
            assertThat(current.body()).contains("\"granted\":true");
            assertThat(consentCounter("finapp.consent.grant", "screening"))
                    .as("the recorded act is what moves the meter")
                    .isEqualTo(screeningGrantsBefore + 1.0d);

            // A stale version whose successors never demanded re-consent stays grantable: those
            // are the words the person was shown, and every later version recorded that they
            // still suffice.
            assertThat(
                            post(
                                            "/me/consents",
                                            ada.session(),
                                            "{\"purpose\":\"KYC_PROCESSING\",\"textVersion\":1}")
                                    .statusCode())
                    .isEqualTo(201);
            assertThat(basisOf(ada, "KYC_PROCESSING")).isTrue();
        } finally {
            removeSeededState();
        }
    }

    /**
     * The task's stated security property. The refusals that could plausibly exist — no prior
     * grant, already withdrawn — are each proven not to: a withdrawal is a new fact whatever
     * the history says ({@code INV-CNS-02}), and each one is audited, because each is an act.
     */
    @Test
    @DisplayName("withdrawal is refusable by nothing but authentication")
    void aWithdrawalIsRefusableByNothingButAuthentication() throws Exception {
        Person ada = givenAPerson();

        // With no grant before it - honest history, not an error.
        assertThat(delete("/me/consents/SCREENING", ada.session()).statusCode()).isEqualTo(204);
        // And again - already-withdrawn is not a refusal either.
        assertThat(delete("/me/consents/SCREENING", ada.session()).statusCode()).isEqualTo(204);

        assertThat(recordCount(ada)).as("both withdrawals are real facts").isEqualTo(2);
        assertThat(auditCount(ada, "consent.ConsentWithdrawn")).isEqualTo(2);
    }

    @Test
    @DisplayName("repeated grants are new facts, and the history keeps both")
    void repeatedGrantsAreHonestHistory() throws Exception {
        Person ada = givenAPerson();
        String body = "{\"purpose\":\"SCREENING\",\"textVersion\":1}";

        assertThat(post("/me/consents", ada.session(), body).statusCode()).isEqualTo(201);
        assertThat(post("/me/consents", ada.session(), body).statusCode())
                .as("a retry after a lost response is safe with no key: it appends and converges")
                .isEqualTo(201);

        assertThat(recordCount(ada)).isEqualTo(2);
        assertThat(basisOf(ada, "SCREENING")).isTrue();
    }

    @Test
    @DisplayName("a version never published is the caller's 422, and nothing is written")
    void anUnknownVersionIsTheCallersMistake() throws Exception {
        Person ada = givenAPerson();

        HttpResponse<String> response =
                post("/me/consents", ada.session(), "{\"purpose\":\"SCREENING\",\"textVersion\":7}");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("consent.UnknownTextVersion");
        assertThat(recordCount(ada)).isZero();
    }

    @Test
    @DisplayName("the query carries the current text, so the grant flow is drivable from it")
    void theQueryCarriesTheCurrentText() throws Exception {
        Person ada = givenAPerson();

        String body = get("/me/consents", ada.session()).body();

        // One row per purpose, each with the words a grant would be against -
        // consent_text.body is PUBLIC, published to exactly the audience PUBLIC means.
        for (ConsentPurpose purpose : ConsentPurpose.values()) {
            assertThat(body).contains("\"purpose\":\"" + purpose.name() + "\"");
        }
        assertThat(body)
                .contains("\"currentTextVersion\":1")
                .contains("I consent to");
    }

    @Test
    @DisplayName("all three endpoints refuse without a session")
    void allThreeRefuseWithoutASession() throws Exception {
        assertThat(get("/me/consents", null).statusCode()).isEqualTo(401);
        assertThat(
                        post(
                                        "/me/consents",
                                        null,
                                        "{\"purpose\":\"SCREENING\",\"textVersion\":1}")
                                .statusCode())
                .isEqualTo(401);
        assertThat(delete("/me/consents/SCREENING", null).statusCode()).isEqualTo(401);
    }

    /**
     * No request shape turns the caller's mistake into ours (`ERROR_CONTRACT.md` §3) — one test
     * rather than nine, because the property is <em>no shape is a 500</em> and splitting it per
     * shape multiplies assertions without adding a claim. The {@code DELETE}'s garbage purpose
     * rides along: an enum conversion failure must be the caller's 4xx too.
     */
    @Test
    @DisplayName("no request shape produces a 500, and nothing is stored by any of them")
    void noRequestShapeIsOurFault() throws Exception {
        Person ada = givenAPerson();

        for (String body :
                new String[] {
                    "{}",
                    "{\"purpose\":null,\"textVersion\":1}",
                    "{\"purpose\":\"BUREAU\",\"textVersion\":1}",
                    "{\"purpose\":\"SCREENING\"}",
                    "{\"purpose\":\"SCREENING\",\"textVersion\":0}",
                    "{\"purpose\":\"SCREENING\",\"textVersion\":-3}",
                    "{\"purpose\":\"SCREENING\",\"textVersion\":\"one\"}",
                    "{\"purpose\":{\"a\":1},\"textVersion\":1}",
                    "",
                    "not json"
                }) {
            assertThat(post("/me/consents", ada.session(), body).statusCode())
                    .as("body %s", body)
                    .isIn(400, 422);
        }

        assertThat(delete("/me/consents/NOT_A_PURPOSE", ada.session()).statusCode())
                .as("a purpose outside the closed vocabulary is the caller's mistake")
                .isIn(400, 404, 422);

        assertThat(recordCount(ada)).as("nothing was stored by any of them").isZero();
    }

    // -----------------------------------------------------------------
    // HTTP

    private record Person(UUID party, IdentityId identity, String session) {}

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
                        .POST(HttpRequest.BodyPublishers.ofString(body));
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

    /** The row {@code granted} for one purpose, read from the caller's own query. */
    private boolean basisOf(Person person, String purpose) throws Exception {
        String body = get("/me/consents", person.session()).body();
        int at = body.indexOf("\"purpose\":\"" + purpose + "\"");
        assertThat(at).as("the query must carry a row for %s", purpose).isNotNegative();
        String row = body.substring(at, body.indexOf('}', at));
        return row.contains("\"granted\":true");
    }

    // -----------------------------------------------------------------
    // Database

    private static long recordCount(Person person) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT count(*) FROM consent.consent_record"
                                        + " WHERE party_id = ?")) {
            select.setObject(1, person.party());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static long auditCount(Person person, String operation) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.audit_record"
                                        + " WHERE operation = ? AND actor_id = ?")) {
            select.setString(1, operation);
            select.setString(2, person.identity().value().toString());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static String auditSummaries(Person person, String operation) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT coalesce(string_agg(change_summary, ' '), '')"
                                        + " FROM platform.audit_record"
                                        + " WHERE operation = ? AND actor_id = ?")) {
            select.setString(1, operation);
            select.setString(2, person.identity().value().toString());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    /** Every consent audit record of this person's targets a consent record that exists. */
    private static boolean auditTargetsExistAsRecords(Person person) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT NOT EXISTS ("
                                        + "  SELECT 1 FROM platform.audit_record a"
                                        + "  WHERE a.actor_id = ?"
                                        + "    AND a.operation LIKE 'consent.%'"
                                        + "    AND NOT EXISTS ("
                                        + "      SELECT 1 FROM consent.consent_record r"
                                        + "      WHERE r.id::text = a.target_id"
                                        + "        AND r.party_id = ?))")) {
            select.setString(1, person.identity().value().toString());
            select.setObject(2, person.party());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }

    // -----------------------------------------------------------------
    // Fixtures

    private Person givenAPerson() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        String login = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Consent Person', now())",
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
                party, IdentityId.of(identity), givenASessionFor(IdentityId.of(identity)));
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

    /**
     * Seeds an extra text version as the migrator — texts arrive only by migration in
     * production, and this models exactly that arrival (the {@code ConsentHistoryDatabaseTest}
     * idiom: version {@code >= 2}, the marker body, cleanup in {@code finally}, because text
     * versions are shared state every consent test reads).
     */
    /** The live value of one consent counter's series - tests assert deltas against it. */
    private double consentCounter(String name, String purpose) {
        return meters.get(name).tag("purpose", purpose).counter().count();
    }

    private static void seedTextVersion(
            ConsentPurpose purpose, int version, boolean requiresReconsent) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement insert =
                        migrator.prepareStatement(
                                "INSERT INTO consent.consent_text"
                                        + " (purpose, version, body, requires_reconsent,"
                                        + " published_at) VALUES (?, ?, 'test fixture version',"
                                        + " ?, now())")) {
            insert.setString(1, purpose.name());
            insert.setInt(2, version);
            insert.setBoolean(3, requiresReconsent);
            insert.executeUpdate();
        }
    }

    /**
     * Removes seeded text versions <strong>and the records that pinned them</strong> — this
     * suite grants against seeded versions over HTTP, so the composite FK would otherwise
     * refuse the text's deletion. As the migrator, because the application role deliberately
     * cannot delete either ({@code INV-CNS-02} — which is why a test's cleanup needs the owner).
     */
    private static void removeSeededState() throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator()) {
            execute(migrator, "DELETE FROM consent.consent_record WHERE text_version >= 2");
            execute(
                    migrator,
                    "DELETE FROM consent.consent_text WHERE version >= 2"
                            + " AND body = 'test fixture version'");
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
