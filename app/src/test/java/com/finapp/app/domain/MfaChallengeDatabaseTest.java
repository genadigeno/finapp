package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authenticator;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcMfaEnrolmentStore;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.LockoutPolicy;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.identity.TotpParameters;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.SimulatedInstance;
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
 * The second-factor challenge, over real HTTP (`P1-TSK-018`, {@code INV-IDN-05}).
 *
 * <h2>Replay is the property this task exists for</h2>
 *
 * <p>{@code P1-TSK-017} made confirmation replay-safe by consuming its {@code PENDING} row. A
 * challenge leaves the factor {@code ACTIVE} and has nothing to consume, so a code stays valid for
 * roughly ninety seconds under the ±1 window — and <em>"one-time password"</em> is only true because
 * the last accepted step is recorded.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(MfaChallengeDatabaseTest.HighValueOperation.class)
@DisplayName("MFA challenge (P1-TSK-018)")
class MfaChallengeDatabaseTest {

    /**
     * An operation that requires a second factor.
     *
     * <p><strong>A probe, and that is the plan rather than a shortcut.</strong>
     * {@code PHASE_1_PLAN.md} §66: <em>"Nothing in Phase 1 requires `MULTI_FACTOR` for a specific
     * action, because Phase 1 has no high-value action. Consumed by Phase 4."</em> So the mechanism
     * ships with the test that proves it and no production caller — and the alternative, inventing a
     * requirement to give the annotation something to do, would be a security control chosen to suit
     * a test.
     *
     * <p>Registered by {@code @Import}, so it belongs to this test's context and not to the
     * published one — {@code OpenApiContractTest} asserts no probe reaches the contract.
     */
    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.web.bind.annotation.RestController
    static class HighValueOperation {

        @com.finapp.app.session.RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
        @org.springframework.web.bind.annotation.GetMapping("/probe/strong")
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
    // Elevation

    @Test
    @DisplayName("a valid code elevates the session and issues a NEW identifier")
    void elevationRotatesTheIdentifier() throws Exception {
        Enrolled enrolled = givenAConfirmedFactor();

        HttpResponse<String> response = challenge(enrolled, codeFor(enrolled.secret()));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"assurance\":\"MULTI_FACTOR\"");

        String elevatedToken = tokenFrom(response.body());
        assertThat(elevatedToken)
                .as("elevating in place would let an identifier stolen BEFORE the step-up become"
                        + " elevated behind the legitimate user's back (P1-TSK-015)")
                .isNotEqualTo(enrolled.session().plaintext());

        try (Connection app = DatabaseRoles.application()) {
            // The presented session is dead. Otherwise the pre-elevation identifier would still
            // work, which is the whole defect rotation exists to prevent.
            assertThat(sessions.findLive(
                            app, SessionToken.of(enrolled.session().plaintext()), Instant.now(CLOCK)))
                    .as("the old identifier must be refused immediately")
                    .isEmpty();
            assertThat(sessions.findLive(app, SessionToken.of(elevatedToken), Instant.now(CLOCK)))
                    .get()
                    .satisfies(s -> assertThat(s.assurance()).isEqualTo(AssuranceLevel.MULTI_FACTOR));
        }
    }

    @Test
    @DisplayName("the absolute bound is not extended by a step-up")
    void elevationDoesNotExtendTheAbsoluteLifetime() throws Exception {
        Enrolled enrolled = givenAConfirmedFactor();
        Instant before = absoluteBoundOf(enrolled.session().plaintext());

        String elevated = tokenFrom(challenge(enrolled, codeFor(enrolled.secret())).body());

        // P1-TSK-015's decision, arriving at its first real caller: if a step-up reset the absolute
        // bound, anyone able to trigger one could stay logged in for ever.
        assertThat(absoluteBoundOf(elevated)).isEqualTo(before);
    }

    // -----------------------------------------------------------------
    // Replay — the task's headline

    @Test
    @DisplayName("a replayed code is refused, and the session it already bought still works")
    void aReplayedCodeIsRefused() throws Exception {
        Enrolled enrolled = givenAConfirmedFactor();
        String code = codeFor(enrolled.secret());

        String elevated = tokenFrom(challenge(enrolled, code).body());

        // The same code, presented again while it is still within its validity window. Without the
        // recorded step this would succeed - the factor is ACTIVE and the arithmetic still matches.
        HttpResponse<String> replay = challengeWith(elevated, code);
        assertThat(replay.statusCode())
                .as("one-time means one time, even inside the window")
                .isEqualTo(401);

        try (Connection app = DatabaseRoles.application()) {
            assertThat(sessions.findLive(app, SessionToken.of(elevated), Instant.now(CLOCK)))
                    .as("and the refusal must not disturb the session the first use bought")
                    .isPresent();
        }
    }

    @Test
    @DisplayName("a replay refused on one instance is refused on another")
    void aReplayIsRefusedAcrossInstances() throws Exception {
        Enrolled enrolled = givenAConfirmedFactor();
        String code = codeFor(enrolled.secret());
        String elevated = tokenFrom(challenge(enrolled, code).body());

        // Own connection - the P0-TST-009 convention. The step lives in the database precisely so
        // that a second instance refuses a code the first one spent; a process-local record would
        // make replay depend on which instance the load balancer picked.
        try (SimulatedInstance other = SimulatedInstance.inAgreementWithTheServer()) {
            assertThat(enrolments
                            .findActive(other.connection(), enrolled.identityId(), MfaFactorType.TOTP)
                            .orElseThrow()
                            .lastUsedStep())
                    .as("the spent step is visible to every instance")
                    .isPresent();
        }
        assertThat(challengeWith(elevated, code).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("an earlier code is refused too, not merely the same one")
    void anEarlierCodeIsRefused() throws Exception {
        Enrolled enrolled = givenAConfirmedFactor();
        long step = Instant.now(CLOCK).getEpochSecond() / TotpParameters.current().periodSeconds();

        String elevated = tokenFrom(challenge(enrolled, codeAt(enrolled.secret(), step)).body());

        // RFC 6238 5.2 asks for more than "not the same code": no code AT OR BEFORE the last
        // accepted step. A window that only refused exact repeats would still admit the previous
        // step's code, which is inside the +/-1 window and therefore still arithmetically valid.
        assertThat(challengeWith(elevated, codeAt(enrolled.secret(), step - 1)).statusCode())
                .as("a code from an earlier step is spent as well")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("the code that confirmed the enrolment cannot then elevate")
    void theConfirmingCodeIsAlreadySpent() throws Exception {
        // Confirmation consumes its step too (added by this task). Without it, the code that
        // confirms an enrolment at step N is still usable for a challenge at step N - a replay
        // across two operations, and RFC 6238 does not care which operation the first use was.
        Enrolled enrolled = givenAConfirmedFactor();

        assertThat(challenge(enrolled, enrolled.confirmingCode()).statusCode())
                .as("a code spent on confirmation is spent")
                .isEqualTo(401);
    }

    // -----------------------------------------------------------------
    // Every refusal is the same refusal

    @Test
    @DisplayName("no factor, a wrong code and a replay are one response")
    void everyRefusalLooksTheSame() throws Exception {
        Enrolled enrolled = givenAConfirmedFactor();
        String code = codeFor(enrolled.secret());
        String elevated = tokenFrom(challenge(enrolled, code).body());

        Issued noFactor = givenASession();

        String replayed = body(challengeWith(elevated, code));
        String wrong = body(challengeWith(elevated, "000000"));
        String absent = body(challengeWith(noFactor.plaintext(), "000000"));

        // Asserted as an EQUALITY between the causes rather than each against a remembered
        // expectation - the P1-TSK-010 form. A caller able to tell them apart learns whether MFA is
        // enrolled on an account and whether somebody is elevating it right now.
        assertThat(replayed).isEqualTo(wrong).isEqualTo(absent);
        assertThat(replayed).isNotBlank();
    }

    @Test
    @DisplayName("the refusal says nothing about the cause, not merely the same thing every time")
    void theRefusalDiscloseNoCause() throws Exception {
        Enrolled enrolled = givenAConfirmedFactor();

        HttpResponse<String> refused = challenge(enrolled, "000000");

        // Added because a mutation SURVIVED. `everyRefusalLooksTheSame` compares the causes to EACH
        // OTHER, so a detail added uniformly to all of them - "No second factor is enrolled." - kept
        // them equal and walked straight through. Equality between causes proves they are
        // indistinguishable; it says nothing about what they jointly disclose.
        //
        // So this is an equality against the CONTRACT: the detail must be the error code's own,
        // which says only that authentication failed.
        // The body carries NO `detail` member at all, which is stronger than a generic one: there
        // is nothing for a future author to make specific. `ERROR_CONTRACT.md` renders absent
        // members as absent rather than null (P0-TSK-024), so this is a real distinction.
        assertThat(refused.body())
                .as("a refusal must not explain itself to a caller")
                .doesNotContain("\"detail\"")
                .contains("\"code\":\"identity.AuthenticationFailed\"");
    }

    @Test
    @DisplayName("a PENDING factor never satisfies a challenge")
    void aPendingFactorSatisfiesNothing() throws Exception {
        Issued session = givenASession();
        String body = postRaw("/v1/me/mfa", null, session.plaintext()).body();
        Sensitive<String> secret = Sensitive.of(secretFrom(body));

        // Enrolled and NOT confirmed. Added because a mutation accepting a PENDING factor survived:
        // P1-TSK-017 asserted that `findActive` returns nothing, and nothing asserted it AT THE
        // CHALLENGE - which is the operation that would otherwise honour it. This is INV-IDN-05's
        // sharpest form: an attacker who reached a session and began an enrolment must not be able
        // to satisfy a second factor with a secret the account holder never accepted.
        assertThat(challengeWith(session.plaintext(), codeFor(secret)).statusCode())
                .as("a factor nobody confirmed must satisfy nothing")
                .isEqualTo(401);

        try (Connection app = DatabaseRoles.application()) {
            assertThat(sessions.findLive(
                            app, SessionToken.of(session.plaintext()), Instant.now(CLOCK)))
                    .get()
                    .satisfies(
                            live ->
                                    assertThat(live.assurance())
                                            .as("and assurance is unchanged")
                                            .isEqualTo(AssuranceLevel.PASSWORD));
        }
    }

    @Test
    @DisplayName("repeated wrong codes lock the account, and a replay does not count")
    void guessingIsThrottledButRetryingIsNot() throws Exception {
        Enrolled enrolled = givenAConfirmedFactor();

        // RFC 4226 §7.3 requires throttling and TOTP is not safe without it: a six-digit code with
        // a ±1 window is THREE valid values in a million per attempt, so an unthrottled challenge
        // is exhausted by automation. This was a claimed property with no test until now.
        for (int attempt = 0; attempt < LockoutPolicy.current().threshold(); attempt++) {
            assertThat(challenge(enrolled, "000000").statusCode()).isEqualTo(401);
        }

        // Even the RIGHT code is refused once locked - a lock a correct guess clears is a hint that
        // the guess was right (P1-TSK-011's rule, and it applies to a code as much as a password).
        assertThat(challenge(enrolled, codeFor(enrolled.secret())).statusCode())
                .as("a lock a correct answer clears is not a lock")
                .isEqualTo(401);
        assertThat(isLocked(enrolled.identityId())).isTrue();
    }

    @Test
    @DisplayName("a replayed code does not count toward the lock")
    void aReplayDoesNotCountTowardTheLock() throws Exception {
        Enrolled enrolled = givenAConfirmedFactor();
        String code = codeFor(enrolled.secret());
        String elevated = tokenFrom(challenge(enrolled, code).body());

        // A client retrying after a network timeout presents the SAME code. Counting that would let
        // a flaky connection lock somebody out of their own account, and the malicious version
        // requires already holding a valid code - which is not a guess.
        for (int attempt = 0; attempt < LockoutPolicy.current().threshold() + 2; attempt++) {
            assertThat(challengeWith(elevated, code).statusCode()).isEqualTo(401);
        }

        assertThat(isLocked(enrolled.identityId()))
                .as("retrying is not guessing")
                .isFalse();
    }

    // -----------------------------------------------------------------
    // The acceptance criterion

    @Test
    @DisplayName("an operation requiring MULTI_FACTOR refuses a PASSWORD session and admits an elevated one")
    void assuranceIsRequiredAndSatisfied() throws Exception {
        Enrolled enrolled = givenAConfirmedFactor();

        // A PASSWORD session is refused with a code a client can ACT on - "step up and retry" is a
        // different instruction from "you may never do this", and a bare 403 would conflate them.
        HttpResponse<String> refused = get("/probe/strong", enrolled.session().plaintext());
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.body()).contains("identity.AssuranceRequired");

        String elevated = tokenFrom(challenge(enrolled, codeFor(enrolled.secret())).body());

        // The positive control. Without it the assertion above passes against a rule that refuses
        // everything, which would be a broken endpoint rather than an enforced requirement.
        assertThat(get("/probe/strong", elevated).statusCode()).isEqualTo(200);
    }

    // -----------------------------------------------------------------

    private record Issued(IdentityId identityId, String plaintext) {}

    private record Enrolled(Issued session, Sensitive<String> secret, String confirmingCode) {
        IdentityId identityId() {
            return session.identityId();
        }
    }

    /** Enrols and confirms, reporting which code did the confirming. */
    private Enrolled givenAConfirmedFactor() throws Exception {
        Issued session = givenASession();
        String body = postRaw("/v1/me/mfa", null, session.plaintext()).body();
        Sensitive<String> secret = Sensitive.of(secretFrom(body));

        // Confirmed with the PREVIOUS step's code, still inside the +/-1 window.
        //
        // The first version used the current step and every challenge test then failed - correctly,
        // because confirmation CONSUMES its step and the challenge was replaying it. That is the
        // mechanism working and the fixture being unrealistic: a person confirms an enrolment and
        // challenges later, not in the same thirty seconds. Time cannot be moved here, so the
        // fixture moves instead.
        String confirming = codeAt(secret, currentStep() - 1);
        assertThat(
                        postRaw(
                                        "/v1/me/mfa/confirmation",
                                        "{\"code\":\"" + confirming + "\"}",
                                        session.plaintext())
                                .statusCode())
                .isEqualTo(204);
        return new Enrolled(session, secret, confirming);
    }

    private HttpResponse<String> challenge(Enrolled enrolled, String code) throws Exception {
        return challengeWith(enrolled.session().plaintext(), code);
    }

    private HttpResponse<String> challengeWith(String token, String code) throws Exception {
        return postRaw("/v1/authentications/mfa", "{\"code\":\"" + code + "\"}", token);
    }

    private static String codeFor(Sensitive<String> secret) {
        return Authenticator.codeNow(secret, TotpParameters.current(), CLOCK);
    }

    private static long currentStep() {
        return Instant.now(CLOCK).getEpochSecond() / TotpParameters.current().periodSeconds();
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
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String tokenFrom(String body) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\"sessionToken\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the elevated session must be returned").isTrue();
        return matcher.group(1);
    }

    /** Correlation differs per request; everything else the platform chose to say must match. */
    private static String body(HttpResponse<String> response) {
        return response.statusCode()
                + response
                        .body()
                        .replaceAll("\"correlationId\"\\s*:\\s*\"[^\"]*\"", "")
                        .replaceAll("\"instance\"\\s*:\\s*\"[^\"]*\"", "");
    }

    private static boolean isLocked(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT locked_until IS NOT NULL AND locked_until > now()"
                                        + " FROM identity.authentication_failure"
                                        + " WHERE identity_id = ?")) {
            read.setObject(1, identity.value());
            try (var rows = read.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private static Instant absoluteBoundOf(String token) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT absolute_expires_at FROM identity.session"
                                        + " WHERE token_hash = ?")) {
            read.setString(1, SessionToken.of(token).hash().expose());
            try (var rows = read.executeQuery()) {
                rows.next();
                return rows.getTimestamp(1).toInstant();
            }
        }
    }

    private Issued givenASession() throws SQLException {
        IdentityId identity = givenAnIdentity();
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
        return new Issued(identity, plaintext);
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
