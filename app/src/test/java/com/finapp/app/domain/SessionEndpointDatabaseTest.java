package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.DeviceDescription;
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
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The session endpoints, over real HTTP (`P1-TSK-016`).
 *
 * <h2>What this proves that {@code SessionOwnershipDatabaseTest} cannot</h2>
 *
 * <p>That one asserts the ownership rule where it lives, in the domain. This asserts the
 * <strong>boundary</strong>: that a request carrying somebody's session reaches the handler as that
 * person, that every way of failing to present one produces the same refusal, and that the
 * identifier in the path is not trusted for anything.
 *
 * <p>Both are needed, and neither substitutes for the other. An HTTP-only suite would pass against
 * an implementation that checked ownership in the controller — the arrangement ADR-0031 forbids —
 * and a domain-only suite would pass against a boundary that never established an owner at all.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("session endpoints (P1-TSK-016)")
class SessionEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    @LocalServerPort private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    @org.springframework.beans.factory.annotation.Autowired
    private com.finapp.app.session.SessionAuthenticationInterceptor interceptor;

    /**
     * The real controller bean, because {@code @RequiresSession} is declared on the CLASS.
     *
     * <p>The first version built {@code new HandlerMethod(new Object(), method)} and the
     * interceptor correctly skipped it — {@code getBeanType()} was {@code Object}, which carries no
     * annotation. The guard was right and the fixture was wrong.
     */
    @org.springframework.beans.factory.annotation.Autowired
    private com.finapp.app.session.SessionController controller;

    // -----------------------------------------------------------------
    // Listing

    @Test
    @DisplayName("a listing returns the caller's own sessions, and marks the current one")
    void aListingReturnsOwnSessions() throws Exception {
        IdentityId mine = givenAnIdentity();
        Issued current = givenALiveSession(mine, "Chrome on Windows");
        Issued other = givenALiveSession(mine, "Safari on iOS");

        HttpResponse<String> response = get("/v1/sessions", current);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains(current.session().id().value().toString())
                .contains(other.session().id().value().toString())
                .contains("Chrome on Windows")
                .contains("Safari on iOS");

        // Exactly one entry may claim to be the current one, and it must be the presented session.
        assertThat(currentIdsIn(response.body()))
                .as("the caller must be able to tell which session is the one they are using -"
                        + " otherwise 'log out my other devices' is guesswork")
                .containsExactly(current.session().id().value().toString());
    }

    @Test
    @DisplayName("a listing never contains another identity's session")
    void aListingIsScopedToTheCaller() throws Exception {
        IdentityId mine = givenAnIdentity();
        IdentityId theirs = givenAnIdentity();
        Issued current = givenALiveSession(mine, null);
        Issued notMine = givenALiveSession(theirs, null);

        HttpResponse<String> response = get("/v1/sessions", current);

        assertThat(response.body())
                .as("the boundary must not be able to widen what the domain scoped")
                .doesNotContain(notMine.session().id().value().toString());
    }

    @Test
    @DisplayName("no response carries the token or its hash")
    void noResponseCarriesTheCredential() throws Exception {
        IdentityId mine = givenAnIdentity();
        Issued current = givenALiveSession(mine, null);

        HttpResponse<String> response = get("/v1/sessions", current);

        // The token is a BEARER credential: whoever holds it is the customer. Returning it - or the
        // hash, which is what a database leak would be worth something for - would put it into the
        // client's own logs for no purpose (INV-AUD-02).
        assertThat(response.body())
                .doesNotContain(current.plaintext())
                .doesNotContain(current.session().tokenHash().expose());
    }

    // -----------------------------------------------------------------
    // Every refusal is the same refusal

    @Test
    @DisplayName("every way of not presenting a live session produces an identical 401")
    void everyRefusalLooksTheSame() throws Exception {
        IdentityId mine = givenAnIdentity();
        Issued revoked = givenALiveSession(mine, null);
        try (Connection app = DatabaseRoles.application()) {
            sessions.revoke(app, revoked.session().id(), Instant.now(CLOCK));
        }
        Issued expired = givenAnExpiredSession(mine);
        Issued live = givenALiveSession(mine, null);

        List<HttpResponse<String>> refusals =
                List.of(
                        getWithRawHeader("/v1/sessions", null),
                        getWithRawHeader("/v1/sessions", "Bearer "),
                        getWithRawHeader("/v1/sessions", "Bearer not-a-real-token"),
                        getWithRawHeader("/v1/sessions", "Basic abcdef"),
                        // A LIVE session's plaintext, with no scheme. Live is what makes this
                        // load-bearing: sending a revoked one is refused whether or not the scheme
                        // is enforced, so it proves nothing. A mutation accepting a bare header
                        // survived the first version of this list for exactly that reason.
                        getWithRawHeader("/v1/sessions", live.plaintext()),
                        getWithRawHeader("/v1/sessions", "Basic " + live.plaintext()),
                        getWithRawHeader("/v1/sessions", "bearer " + live.plaintext()),
                        get("/v1/sessions", revoked),
                        get("/v1/sessions", expired));

        // Asserted as an EQUALITY between the causes rather than each against a remembered
        // expectation - the P1-TSK-010 form. The second shape passes against an implementation
        // that returns several different bodies which each happen to match what its author wrote.
        assertThat(refusals).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(401));

        List<String> bodies = refusals.stream().map(SessionEndpointDatabaseTest::withoutCorrelation).toList();
        assertThat(bodies)
                .as("a caller must not be able to tell 'no session' from 'revoked' from 'expired':"
                        + " the difference is information about somebody's account")
                .containsOnly(bodies.getFirst());
        assertThat(bodies.getFirst()).isNotBlank();
    }

    @Test
    @DisplayName("an authenticated request extends the idle bound")
    void aRequestExtendsTheIdleBound() throws Exception {
        IdentityId mine = givenAnIdentity();
        Issued current = givenALiveSession(mine, null);

        // Back-date the idle bound so an extension is observable without waiting. The absolute
        // bound is untouched, so this stays a live session under a policy that has not changed.
        Instant before = Instant.now(CLOCK).plus(Duration.ofMinutes(1));
        try (Connection app = DatabaseRoles.application();
                PreparedStatement age =
                        app.prepareStatement(
                                "UPDATE identity.session SET idle_expires_at = ? WHERE id = ?")) {
            age.setTimestamp(1, java.sql.Timestamp.from(before));
            age.setObject(2, current.session().id().value());
            age.executeUpdate();
        }

        assertThat(get("/v1/sessions", current).statusCode()).isEqualTo(200);

        // Without this the idle bound never moves, so a session dies at the idle timeout no matter
        // how actively it is used - which collapses the two bounds into one and makes the idle
        // timeout an absolute one wearing another name.
        assertThat(idleBoundOf(current))
                .as("using a session must extend how long it may then sit unused")
                .isAfter(before);
    }

    // -----------------------------------------------------------------
    // The actor, and the scope that must not outlive the request

    @Test
    @DisplayName("the request runs as the proven identity, and the thread is clean afterwards")
    void theScopeIsOpenedAndClosedOnTheSameThread() throws Exception {
        IdentityId mine = givenAnIdentity();
        Issued current = givenALiveSession(mine, null);

        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/v1/sessions");
        request.addHeader("Authorization", "Bearer " + current.plaintext());
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var handler =
                new org.springframework.web.method.HandlerMethod(
                        controller,
                        com.finapp.app.session.SessionController.class.getMethod(
                                "listSessions", jakarta.servlet.http.HttpServletRequest.class));

        assertThat(com.finapp.platform.security.SecurityContext.current())
                .as("precondition: nothing established before the interceptor runs")
                .isEmpty();

        interceptor.preHandle(request, response, handler);

        // This is the platform's FIRST real inbound actor. ADR-0021 called enterSystem() "the
        // greppable list of places Phase 1 must revisit"; this is a request revisiting it.
        assertThat(com.finapp.platform.security.SecurityContext.current())
                .as("the handler must run as the identity the session proved, not as the platform")
                .hasValueSatisfying(
                        actor -> {
                            assertThat(actor.id()).isEqualTo(mine.value().toString());
                            assertThat(actor.type())
                                    .isEqualTo(com.finapp.platform.security.ActorType.CUSTOMER);
                        });

        interceptor.afterCompletion(request, response, handler, null);

        // A scope left open on a pooled worker means the NEXT unrelated request on that thread runs
        // as this customer - the leak P0-TSK-032 built SecurityContext to prevent, and one that
        // would write audit records naming the wrong person, permanently (INV-HIST-03).
        assertThat(com.finapp.platform.security.SecurityContext.current())
                .as("the scope must not outlive the request that opened it")
                .isEmpty();
    }

    @Test
    @DisplayName("a handler that throws still leaves the thread clean")
    void theScopeIsClosedEvenWhenTheHandlerThrows() throws Exception {
        IdentityId mine = givenAnIdentity();
        Issued current = givenALiveSession(mine, null);

        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/v1/sessions");
        request.addHeader("Authorization", "Bearer " + current.plaintext());
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var handler =
                new org.springframework.web.method.HandlerMethod(
                        controller,
                        com.finapp.app.session.SessionController.class.getMethod(
                                "listSessions", jakarta.servlet.http.HttpServletRequest.class));

        interceptor.preHandle(request, response, handler);
        // afterCompletion rather than postHandle is the whole point: postHandle is SKIPPED when the
        // handler throws, so a scope closed there would leak on exactly the path that fails.
        interceptor.afterCompletion(request, response, handler, new IllegalStateException("boom"));

        assertThat(com.finapp.platform.security.SecurityContext.current()).isEmpty();
    }

    // -----------------------------------------------------------------
    // Revocation at the boundary

    @Test
    @DisplayName("revoking another identity's session is 404, and leaves it live")
    void revokingSomebodyElsesSessionIsNotFound() throws Exception {
        IdentityId mine = givenAnIdentity();
        IdentityId theirs = givenAnIdentity();
        Issued current = givenALiveSession(mine, null);
        Issued notMine = givenALiveSession(theirs, null);

        HttpResponse<String> response =
                delete("/v1/sessions/" + notMine.session().id().value(), current);

        assertThat(response.statusCode())
                .as("403 would confirm the identifier belongs to somebody, which is an oracle over"
                        + " other people's sessions")
                .isEqualTo(404);

        try (Connection app = DatabaseRoles.application()) {
            assertThat(sessions.findLive(app, notMine.token(), Instant.now(CLOCK)))
                    .as("and it must still work for its owner")
                    .isPresent();
        }
    }

    @Test
    @DisplayName("an unknown and a malformed identifier are refused exactly as another's is")
    void unknownAndMalformedAreRefusedIdentically() throws Exception {
        IdentityId mine = givenAnIdentity();
        IdentityId theirs = givenAnIdentity();
        Issued current = givenALiveSession(mine, null);
        Issued notMine = givenALiveSession(theirs, null);

        HttpResponse<String> unknown =
                delete("/v1/sessions/" + UUID.randomUUID(), current);
        HttpResponse<String> malformed = delete("/v1/sessions/not-a-uuid", current);
        HttpResponse<String> somebodyElses =
                delete("/v1/sessions/" + notMine.session().id().value(), current);

        assertThat(withoutCorrelation(malformed))
                .as("400 for a non-UUID and 404 for a UUID tells a caller which shapes are worth"
                        + " trying")
                .isEqualTo(withoutCorrelation(unknown))
                .isEqualTo(withoutCorrelation(somebodyElses));
    }

    @Test
    @DisplayName("revoking your own session works, and the token stops working immediately")
    void revokingYourOwnSessionWorks() throws Exception {
        IdentityId mine = givenAnIdentity();
        Issued current = givenALiveSession(mine, null);
        Issued doomed = givenALiveSession(mine, null);

        assertThat(delete("/v1/sessions/" + doomed.session().id().value(), current).statusCode())
                .isEqualTo(204);

        // INV-IDN-03 at the boundary: the very next request with that token is refused. Not
        // "eventually", not "once a cache expires".
        assertThat(get("/v1/sessions", doomed).statusCode())
                .as("a revoked session must be refused on the NEXT request")
                .isEqualTo(401);
        assertThat(get("/v1/sessions", current).statusCode())
                .as("and the caller's own session must be untouched")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("logout ends the presenting session")
    void logoutEndsTheCurrentSession() throws Exception {
        IdentityId mine = givenAnIdentity();
        Issued current = givenALiveSession(mine, null);

        assertThat(delete("/v1/sessions/current", current).statusCode()).isEqualTo(204);
        assertThat(get("/v1/sessions", current).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("logging out twice is not an error the second time")
    void logoutIsIdempotentInEffect() throws Exception {
        IdentityId mine = givenAnIdentity();
        Issued current = givenALiveSession(mine, null);

        assertThat(delete("/v1/sessions/current", current).statusCode()).isEqualTo(204);

        // The second attempt cannot even authenticate, which is the honest answer: you are logged
        // out. Reporting a failure for a logout that already happened would be telling somebody
        // their logout failed when it did not.
        assertThat(delete("/v1/sessions/current", current).statusCode()).isEqualTo(401);
    }

    // -----------------------------------------------------------------

    private record Issued(Session session, SessionToken token, String plaintext) {}

    private static Instant idleBoundOf(Issued issued) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT idle_expires_at FROM identity.session WHERE id = ?")) {
            read.setObject(1, issued.session().id().value());
            try (var rows = read.executeQuery()) {
                rows.next();
                return rows.getTimestamp(1).toInstant();
            }
        }
    }

    private Issued givenALiveSession(IdentityId identityId, String device) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        SessionToken token = SessionToken.of(plaintext);

        Session session =
                Session.issue(
                        IDS,
                        CLOCK,
                        identityId,
                        token,
                        AssuranceLevel.PASSWORD,
                        SessionPolicy.current(),
                        device == null ? null : new DeviceDescription(device));
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return new Issued(session, token, plaintext);
    }

    /**
     * A session whose idle bound has already passed.
     *
     * <p><strong>Issue time is back-dated too, and the schema is what insisted.</strong> The first
     * version moved only {@code idle_expires_at} and was refused by
     * {@code session_bounds_follow_issue} — a session cannot be written already expired. The
     * constraint was right and the fixture was wrong, which is the constraint doing exactly the job
     * {@code P1-TSK-013} added it for.
     */
    private Issued givenAnExpiredSession(IdentityId identityId) throws SQLException {
        Issued issued = givenALiveSession(identityId, null);
        Instant now = Instant.now(CLOCK);
        try (Connection app = DatabaseRoles.application();
                PreparedStatement expire =
                        app.prepareStatement(
                                "UPDATE identity.session SET issued_at = ?, idle_expires_at = ?"
                                        + " WHERE id = ?")) {
            expire.setTimestamp(1, java.sql.Timestamp.from(now.minus(Duration.ofHours(2))));
            expire.setTimestamp(2, java.sql.Timestamp.from(now.minus(Duration.ofHours(1))));
            expire.setObject(3, issued.session().id().value());
            expire.executeUpdate();
        }
        return issued;
    }

    private HttpResponse<String> get(String path, Issued issued) throws Exception {
        return getWithRawHeader(path, "Bearer " + issued.plaintext());
    }

    private HttpResponse<String> getWithRawHeader(String path, String authorization)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path, Issued issued) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + issued.plaintext())
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Everything the platform chose to say, with the two members that cannot be constant removed.
     *
     * <p>Correlation differs per request by design. {@code instance} echoes the request path, which
     * is the caller's <strong>own input</strong> — it discloses nothing they did not already send,
     * and the property under test is that our <em>state</em> is never described differently. The
     * first version compared it and failed on {@code /v1/sessions/not-a-uuid} against a UUID, which
     * was the test being wrong about where the disclosure would be.
     */
    private static String withoutCorrelation(HttpResponse<String> response) {
        return response
                .body()
                .replaceAll("\"correlationId\"\\s*:\\s*\"[^\"]*\"", "")
                .replaceAll("\"instance\"\\s*:\\s*\"[^\"]*\"", "");
    }

    private static List<String> currentIdsIn(String body) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\{[^{}]*\\}").matcher(body);
        List<String> current = new java.util.ArrayList<>();
        while (matcher.find()) {
            String entry = matcher.group();
            if (entry.contains("\"current\":true")) {
                java.util.regex.Matcher id =
                        java.util.regex.Pattern.compile("\"id\":\"([^\"]+)\"").matcher(entry);
                if (id.find()) {
                    current.add(id.group(1));
                }
            }
        }
        return current;
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
