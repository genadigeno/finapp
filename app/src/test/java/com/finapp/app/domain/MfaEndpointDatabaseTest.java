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
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The MFA endpoints, over real HTTP (`P1-TSK-017`).
 *
 * <h2>Added by the completion gate, because nothing drove the boundary</h2>
 *
 * <p>{@code MfaEnrolmentDatabaseTest} exercises the service. That leaves the boundary's own
 * decisions unasserted: whether {@code @RequiresSession} is actually wired, whether a wrong code is
 * a {@code 422} rather than a {@code 500}, whether the identity really comes from the session, and
 * whether the request bounds reject before any domain work. Each of those is a place a defect would
 * be invisible to a service-level test.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("MFA endpoints (P1-TSK-017)")
class MfaEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    @LocalServerPort private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final MfaEnrolmentStore<Connection> enrolments = new JdbcMfaEnrolmentStore();

    // -----------------------------------------------------------------
    // The session requirement

    @Test
    @DisplayName("both endpoints refuse a request with no session")
    void bothEndpointsRequireASession() throws Exception {
        // Proves @RequiresSession is wired at all. Without this, the annotation could be absent and
        // every other test here would still pass - they all present a session.
        assertThat(postRaw("/v1/me/mfa", null, null).statusCode()).isEqualTo(401);
        assertThat(postRaw("/v1/me/mfa/confirmation", "{\"code\":\"123456\"}", null).statusCode())
                .isEqualTo(401);
    }

    // -----------------------------------------------------------------
    // Enrolment

    @Test
    @DisplayName("beginning an enrolment returns a usable provisioning URI, and nothing else does")
    void beginningReturnsTheProvisioningUri() throws Exception {
        Issued session = givenASession();

        HttpResponse<String> response = postRaw("/v1/me/mfa", null, session);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("otpauth://totp/").contains("secret=");

        // The account label must not carry a login identifier: the label is rendered in the app,
        // screenshotted into support tickets and synced to whatever backs the app up, and a login
        // identifier is CONFIDENTIAL because it carries existence.
        assertThat(response.body())
                .as("the label names the identity, never the login identifier")
                .doesNotContain(loginIdentifierOf(session.identityId()));
    }

    @Test
    @DisplayName("the secret is emitted once and by no other endpoint")
    void theSecretIsEmittedOnlyOnce() throws Exception {
        Issued session = givenASession();
        String secret = secretFrom(postRaw("/v1/me/mfa", null, session).body());

        // "Never retrievable afterwards" was a javadoc claim with nothing behind it until the gate.
        // There is no GET, and beginning again issues a DIFFERENT secret rather than repeating this
        // one - so a customer who loses it re-enrols, which is the whole bound.
        assertThat(getRaw("/v1/me/mfa", session).statusCode())
                .as("no read path returns the secret")
                .isEqualTo(405);

        String second = secretFrom(postRaw("/v1/me/mfa", null, session).body());
        assertThat(second).isNotEqualTo(secret);
    }

    // -----------------------------------------------------------------
    // Confirmation

    @Test
    @DisplayName("a valid code confirms; a wrong one is 422 and leaves the factor unusable")
    void confirmationRequiresTheRightCode() throws Exception {
        Issued session = givenASession();
        String secret = secretFrom(postRaw("/v1/me/mfa", null, session).body());

        HttpResponse<String> wrong =
                postRaw("/v1/me/mfa/confirmation", "{\"code\":\"000000\"}", session);
        assertThat(wrong.statusCode())
                .as("a wrong code is the caller's mistake, never a 500")
                .isEqualTo(422);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(enrolments.findActive(app, session.identityId(), MfaFactorType.TOTP))
                    .as("assurance unchanged: this task's acceptance criterion, at the boundary")
                    .isEmpty();
        }

        HttpResponse<String> right =
                postRaw(
                        "/v1/me/mfa/confirmation",
                        "{\"code\":\"" + codeFor(secret) + "\"}",
                        session);
        assertThat(right.statusCode()).isEqualTo(204);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(enrolments.findActive(app, session.identityId(), MfaFactorType.TOTP))
                    .isPresent();
        }
    }

    @Test
    @DisplayName("one identity cannot confirm another's enrolment")
    void confirmationIsScopedToTheCaller() throws Exception {
        Issued victim = givenASession();
        Issued attacker = givenASession();
        String victimSecret = secretFrom(postRaw("/v1/me/mfa", null, victim).body());

        // The attacker holds a VALID code for the victim's enrolment - the strongest form of this
        // test, because a weaker one (a wrong code) would pass against an implementation with no
        // scoping at all. The identity comes from the session, never from the request (ADR-0031).
        HttpResponse<String> response =
                postRaw(
                        "/v1/me/mfa/confirmation",
                        "{\"code\":\"" + codeFor(victimSecret) + "\"}",
                        attacker);

        assertThat(response.statusCode()).isEqualTo(422);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(enrolments.findActive(app, victim.identityId(), MfaFactorType.TOTP))
                    .as("the victim's enrolment must be untouched")
                    .isEmpty();
            assertThat(enrolments.findPending(app, victim.identityId(), MfaFactorType.TOTP))
                    .as("and still pending, so the victim can complete it themselves")
                    .isPresent();
        }
    }

    @Test
    @DisplayName("no request shape produces a 500")
    void noRequestShapeProducesAServerError() throws Exception {
        Issued session = givenASession();
        postRaw("/v1/me/mfa", null, session);

        // A code is whatever somebody typed. Every one of these must be a 4xx: a 500 says our side
        // failed for something only the caller can fix, and a client may retry it for ever
        // (ERROR_CONTRACT.md §3). The P1-TSK-010 probe, applied to this body.
        for (String body :
                new String[] {
                    "{}",
                    "{\"code\":null}",
                    "{\"code\":\"\"}",
                    "{\"code\":\"12345\"}",
                    "{\"code\":\"123456789\"}",
                    "{\"code\":\"abcdef\"}",
                    "{\"code\":123456}",
                    "{\"code\":{}}",
                    "{\"code\":[]}",
                    "{\"code\":\"" + "9".repeat(100_000) + "\"}",
                    "not json at all"
                }) {
            assertThat(postRaw("/v1/me/mfa/confirmation", body, session).statusCode())
                    .as("shape: %s", body.length() > 60 ? body.substring(0, 60) + "…" : body)
                    .isBetween(400, 499);
        }
    }

    // -----------------------------------------------------------------

    private record Issued(IdentityId identityId, String plaintext) {}

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

    private static String secretFrom(String body) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("secret=([A-Z2-7]+)").matcher(body);
        assertThat(matcher.find()).as("the response must carry a base32 secret").isTrue();
        return matcher.group(1);
    }

    private static String codeFor(String base32Secret) {
        return Authenticator.codeNow(Sensitive.of(base32Secret), TotpParameters.current(), CLOCK);
    }

    private HttpResponse<String> postRaw(String path, String body, Issued session)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (session != null) {
            request.header("Authorization", "Bearer " + session.plaintext());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getRaw(String path, Issued session) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + session.plaintext())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String loginIdentifierOf(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT login_identifier FROM identity.identity WHERE id = ?")) {
            read.setObject(1, identity.value());
            try (var rows = read.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
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
