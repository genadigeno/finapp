package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authenticator;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcMfaEnrolmentStore;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.identity.TotpParameters;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * `P1-TST-003`: MFA cannot be bypassed (`P1-TSK-019`, {@code INV-IDN-05}).
 *
 * <h2>One test per enumerated path, because that is what the invariant is about</h2>
 *
 * <p>{@code INV-IDN-05}: <em>"MFA is defeated by its weakest alternative path, not by its strongest
 * factor. Every real bypass is a path nobody enumerated."</em> So each path gets its own named test
 * — a failure says <strong>which</strong> route opened, rather than "MFA is bypassable".
 *
 * <p>{@link MfaBypassPathsAreEnumeratedTest} is the other half, and the durable one: it fails when a
 * <em>new</em> session-issuing path appears without being enumerated here. A list of tests is a
 * snapshot; a guard is what makes the enumeration survive Phase 4.
 *
 * <h2>What is cited rather than duplicated</h2>
 *
 * <p>Three properties are already proven by {@code MfaChallengeDatabaseTest} and are not restated
 * here — a second copy of a working assertion is duplication that drifts, not coverage
 * ({@code P1-TSK-012}):
 *
 * <ul>
 *   <li>a {@code PENDING} factor satisfies nothing — {@code aPendingFactorSatisfiesNothing}
 *   <li>a replayed code is refused — four tests, including across instances
 *   <li>an operation requiring {@code MULTI_FACTOR} refuses a {@code PASSWORD} session —
 *       {@code assuranceIsRequiredAndSatisfied}
 * </ul>
 *
 * <h2>Recovery is a recorded remainder, not a skipped test</h2>
 *
 * <p>The backlog names recovery as an enumerated path <em>"once M1.6 exists"</em>. It does not
 * exist, and there is no endpoint to drive — so there is nothing here to assert, and asserting
 * absence over a route that was never mapped would pass vacuously. The guard below is what forces
 * it to be enumerated when it lands.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(
        MfaCannotBeBypassedDatabaseTest.HighValueOperation.class)
@DisplayName("P1-TST-003: MFA cannot be bypassed (P1-TSK-019)")
class MfaCannotBeBypassedDatabaseTest {

    /** An operation requiring a second factor. See `MfaChallengeDatabaseTest` for why it is a probe. */
    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.web.bind.annotation.RestController
    static class HighValueOperation {

        @com.finapp.app.session.RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
        @org.springframework.web.bind.annotation.GetMapping("/probe/high-value")
        String pretendToMoveMoney() {
            return "done";
        }
    }

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    @LocalServerPort private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final MfaEnrolmentStore<Connection> enrolments = new JdbcMfaEnrolmentStore();

    // -----------------------------------------------------------------
    // Path 1 — an older PASSWORD session

    @Test
    @DisplayName("path 1: elevating one session does not elevate the identity's other sessions")
    void anOlderPasswordSessionIsNotElevated() throws Exception {
        IdentityId identity = givenAnIdentity();
        String elevatingSession = givenASessionFor(identity);
        String olderSession = givenASessionFor(identity);
        Sensitive<String> secret = givenAConfirmedFactorOn(elevatingSession);

        String elevated = tokenFrom(challenge(elevatingSession, codeNow(secret)).body());
        assertThat(get("/probe/high-value", elevated).statusCode()).isEqualTo(200);

        // The bypass this closes: if assurance lived on the IDENTITY rather than on the session,
        // proving a factor once would elevate every session that identity holds - including one an
        // attacker took with a stolen password, which they would never have to prove anything for.
        assertThat(get("/probe/high-value", olderSession).statusCode())
                .as("assurance is a property of a SESSION, not of an identity")
                .isEqualTo(403);
    }

    // -----------------------------------------------------------------
    // Path 2 — a refresh

    @Test
    @DisplayName("path 2: using a session repeatedly never raises its assurance")
    void aRefreshDoesNotRaiseAssurance() throws Exception {
        IdentityId identity = givenAnIdentity();
        String session = givenASessionFor(identity);
        Instant idleBefore = idleBoundOf(session);

        // Every authenticated request extends the idle bound (P1-TSK-016's `touch`), which is the
        // closest thing this platform has to a "refresh". It must move the CLOCK and nothing else -
        // a refresh that quietly re-issued a session at a higher level would be a bypass requiring
        // no factor at all, and it would look like ordinary traffic.
        for (int request = 0; request < 5; request++) {
            assertThat(get("/probe/high-value", session).statusCode()).isEqualTo(403);
        }

        assertThat(idleBoundOf(session))
                .as("the idle bound moves, which is what makes this a refresh at all")
                .isAfter(idleBefore);
        assertThat(assuranceOf(session))
                .as("and the level does not")
                .isEqualTo(AssuranceLevel.PASSWORD);
    }

    // -----------------------------------------------------------------
    // Path 3 — re-enrolment of a second factor

    @Test
    @DisplayName("path 3: a PASSWORD session cannot replace a confirmed factor")
    void reEnrolmentCannotReplaceAConfirmedFactor() throws Exception {
        IdentityId identity = givenAnIdentity();
        String victimSession = givenASessionFor(identity);
        givenAConfirmedFactorOn(victimSession);

        // The attack this closes, found by probing rather than by reading. An attacker holding a
        // stolen password begins a SECOND enrolment with a secret they control, confirms it, and
        // owns the second factor. Before P1-TSK-019 the request reached 201 and issued that secret;
        // only a partial unique index stopped the confirmation, and it did so as an unhandled
        // storage exception - a 500, which ERROR_CONTRACT.md 3 forbids and a client may retry for
        // ever. The property held by accident of a constraint rather than by a decision.
        String attackerSession = givenASessionFor(identity);
        HttpResponse<String> begin = postRaw("/v1/me/mfa", null, attackerSession);

        assertThat(begin.statusCode())
                .as("replacing a confirmed factor requires that factor")
                .isEqualTo(403);
        assertThat(begin.body()).contains("identity.AssuranceRequired");

        // And no secret was issued at all - refused before anything was created, so there is
        // nothing for the attacker to confirm later.
        assertThat(begin.body()).doesNotContain("otpauth://");
        try (Connection app = DatabaseRoles.application()) {
            assertThat(enrolments.findPending(app, identity, MfaFactorType.TOTP))
                    .as("no pending enrolment was created")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("path 3: the refusal is audited, and the record survives the refusal")
    void aRefusedReplacementIsAudited() throws Exception {
        IdentityId identity = givenAnIdentity();
        String session = givenASessionFor(identity);
        givenAConfirmedFactorOn(session);

        long before = refusedEnrolmentRecords(identity);
        assertThat(postRaw("/v1/me/mfa", null, session).statusCode()).isEqualTo(403);

        // Two claims, and the second is the one worth proving. `MfaEnrolmentService.auditRefusal`'s
        // javadoc says this is "the trace of somebody with a stolen password trying to swap a second
        // factor" - and a refusal is otherwise indistinguishable from a customer tapping the wrong
        // button, so without the record an attack leaves nothing behind.
        //
        // And it must SURVIVE: the refusal is written inside the transaction and the 403 is thrown
        // after it commits. Throwing inside would roll the record back, which is exactly the defect
        // P1-TSK-010 had to avoid by RETURNING its refusal rather than throwing it.
        assertThat(refusedEnrolmentRecords(identity) - before)
                .as("a refused replacement must leave a durable trace naming the identity")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("path 3 control: an elevated session CAN replace it, so the rule is conditional")
    void anElevatedSessionCanReplaceTheFactor() throws Exception {
        IdentityId identity = givenAnIdentity();
        String session = givenASessionFor(identity);
        Sensitive<String> secret = givenAConfirmedFactorOn(session);
        String elevated = tokenFrom(challenge(session, codeNow(secret)).body());

        // The positive control. Without it the test above passes against a rule that refuses every
        // replacement - which would mean a customer with a new phone can never move their factor,
        // and they would have no route at all.
        assertThat(postRaw("/v1/me/mfa", null, elevated).statusCode())
                .as("proving the factor you hold is how you replace it")
                .isEqualTo(201);
    }

    // -----------------------------------------------------------------
    // Path 5 — a direct call to an endpoint that issues a session
    // (Path 4, recovery, does not exist. See the class javadoc.)

    @Test
    @DisplayName("path 5: the only endpoint that issues a session demands a verified code")
    void theSessionIssuingEndpointDemandsAFactor() throws Exception {
        IdentityId identity = givenAnIdentity();
        String session = givenASessionFor(identity);
        givenAConfirmedFactorOn(session);

        // Every shape that is not a verified code, driven at the one endpoint that can produce a
        // MULTI_FACTOR session. None may yield one.
        for (String body :
                new String[] {
                    "{\"code\":\"000000\"}", "{\"code\":\"999999\"}", "{}", "{\"code\":null}"
                }) {
            HttpResponse<String> response =
                    postRaw("/v1/authentications/mfa", body, session);
            assertThat(response.statusCode())
                    .as("shape: %s", body)
                    .isBetween(400, 499);
            assertThat(response.body()).doesNotContain("MULTI_FACTOR");
        }

        assertThat(assuranceOf(session))
                .as("and the presented session is untouched by any of them")
                .isEqualTo(AssuranceLevel.PASSWORD);
    }

    // -----------------------------------------------------------------
    // The level is not a boolean

    @Test
    @DisplayName("a STRONG session satisfies a MULTI_FACTOR requirement, which a boolean could not")
    void assuranceIsALevelAndNotABoolean() throws Exception {
        IdentityId identity = givenAnIdentity();
        String strong = givenASessionAt(identity, AssuranceLevel.STRONG);

        // The acceptance criterion's second clause: "the suite fails if the level check is replaced
        // by a boolean". This is the assertion a boolean cannot satisfy - it needs THREE ordered
        // values, and `mfaCompleted` has two.
        //
        // STRONG has no producer yet, and that is fine: what is under test is the MECHANISM being a
        // level (ADR-0030), not a route that reaches it. Constructing one directly is how a
        // requirement gets tested before its producer exists.
        assertThat(get("/probe/high-value", strong).statusCode())
                .as("a MORE assured session must satisfy a lesser requirement - refusing it is the"
                        + " kind of rule people work around")
                .isEqualTo(200);

        assertThat(get("/probe/high-value", givenASessionAt(identity, AssuranceLevel.PASSWORD))
                        .statusCode())
                .as("and a lesser one must not")
                .isEqualTo(403);
    }

    // -----------------------------------------------------------------

    private Sensitive<String> givenAConfirmedFactorOn(String sessionToken) throws Exception {
        Sensitive<String> secret =
                Sensitive.of(secretFrom(postRaw("/v1/me/mfa", null, sessionToken).body()));
        // Confirmed with the PREVIOUS step's code, because confirmation consumes its step
        // (P1-TSK-018) and a person confirms then challenges later, not in the same thirty seconds.
        String confirming = codeAt(secret, currentStep() - 1);
        assertThat(
                        postRaw(
                                        "/v1/me/mfa/confirmation",
                                        "{\"code\":\"" + confirming + "\"}",
                                        sessionToken)
                                .statusCode())
                .isEqualTo(204);
        return secret;
    }

    private HttpResponse<String> challenge(String sessionToken, String code) throws Exception {
        return postRaw("/v1/authentications/mfa", "{\"code\":\"" + code + "\"}", sessionToken);
    }

    private static long currentStep() {
        return Instant.now(CLOCK).getEpochSecond() / TotpParameters.current().periodSeconds();
    }

    private static String codeNow(Sensitive<String> secret) {
        return Authenticator.codeNow(secret, TotpParameters.current(), CLOCK);
    }

    private static String codeAt(Sensitive<String> secret, long step) {
        return Authenticator.codeAt(
                secret,
                TotpParameters.current(),
                Instant.ofEpochSecond(step * TotpParameters.current().periodSeconds()));
    }

    private static String secretFrom(String body) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("secret=([A-Z2-7]+)").matcher(body);
        assertThat(matcher.find()).as("the response must carry a base32 secret").isTrue();
        return matcher.group(1);
    }

    private static String tokenFrom(String body) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\"sessionToken\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the elevated session must be returned").isTrue();
        return matcher.group(1);
    }

    private static long refusedEnrolmentRecords(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.audit_record"
                                        + " WHERE operation = 'identity.MfaEnrolmentStarted'"
                                        + " AND outcome = 'FAILED'"
                                        + " AND target_id = ?")) {
            count.setString(1, identity.value().toString());
            try (var rows = count.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static AssuranceLevel assuranceOf(String token) throws SQLException {
        return AssuranceLevel.valueOf(columnOf(token, "assurance"));
    }

    /**
     * Read as a {@code Timestamp} rather than assembled from text.
     *
     * <p>The first version parsed {@code idle_expires_at::text} and failed on the server's offset
     * format. Letting the driver convert is both correct and one fewer thing this test has to know
     * about PostgreSQL's rendering.
     */
    private static Instant idleBoundOf(String token) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT idle_expires_at FROM identity.session"
                                        + " WHERE token_hash = ?")) {
            read.setString(1, SessionToken.of(token).hash().expose());
            try (var rows = read.executeQuery()) {
                assertThat(rows.next()).as("the session must exist").isTrue();
                return rows.getTimestamp(1).toInstant();
            }
        }
    }

    private static String columnOf(String token, String column) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT " + column + " FROM identity.session WHERE token_hash = ?")) {
            read.setString(1, SessionToken.of(token).hash().expose());
            try (var rows = read.executeQuery()) {
                assertThat(rows.next()).as("the session must exist").isTrue();
                return rows.getString(1);
            }
        }
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        return givenASessionAt(identity, AssuranceLevel.PASSWORD);
    }

    private String givenASessionAt(IdentityId identity, AssuranceLevel assurance)
            throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session session =
                Session.issue(
                        IDS,
                        CLOCK,
                        identity,
                        SessionToken.of(plaintext),
                        assurance,
                        SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return plaintext;
    }

    private HttpResponse<String> postRaw(String path, String body, String token) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
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

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
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
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE', now(), now())",
                    identity,
                    party,
                    "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        return IdentityId.of(identity);
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
