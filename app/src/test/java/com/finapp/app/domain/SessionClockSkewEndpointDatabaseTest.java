package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Credential;
import com.finapp.identity.CredentialType;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcCredentialStore;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.RawPassword;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.SimulatedInstance;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * An application instance whose clock is an hour off, over real HTTP (`X-TSK-007`).
 *
 * <h2>The defect, stated as the owner found it</h2>
 *
 * <p>An instance running fast ended live sessions early, and one running slow honoured expired
 * sessions late: the interceptor compared the session's bounds with its own
 * {@code Instant.now(clock)}. That is the lookup half of the defect, and this suite drives it where
 * it lived, through the real interceptor, listing and login. {@code SessionClockSkewDatabaseTest}
 * holds the stamping half at the components that still take a clock.
 *
 * <h2>How an instance is skewed</h2>
 *
 * <p>This context's {@code Clock} bean is replaced by one that runs an hour away from the
 * <strong>server's</strong> {@code now()} - the {@code P0-TST-009} rule, because a skew measured
 * against the JVM or a fixture constant can point the safe way without anyone noticing. Every test
 * first asserts that the skew would have changed the old answer, so a test that passes proves the
 * clock was ignored rather than that it happened to agree.
 *
 * <p>Its own context, by the {@code @Import}: nothing else in this JVM runs on a skewed clock.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SessionClockSkewEndpointDatabaseTest.SkewedInstance.class)
@DisplayName("an instance's clock decides no session's liveness (X-TSK-007)")
class SessionClockSkewEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    /** Cheap: this suite is about time, not about how long deriving takes. */
    private static final DerivationParameters WEAK = new DerivationParameters(1024, 1, 1);

    private static final String PASSWORD = "correct horse battery staple";

    @LocalServerPort private int port;

    @Autowired private SkewableClock clock;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    @AfterEach
    void agreeAgain() {
        clock.agreeWithTheJvm();
    }

    @Test
    @DisplayName("an instance an hour slow refuses a session the database has expired")
    void aSlowInstanceRefusesAnExpiredSession() throws Exception {
        IdentityId identity = givenAnIdentity();
        String token = givenASession(identity);
        Instant idleBound =
                moveBounds(identity, "now() - interval '2 hours'", "now() - interval '10 minutes'");

        clock.skewAgainstTheServer(Duration.ofHours(-1));
        assertThat(clock.instant())
                .as("precondition: this instance's clock is still BEFORE the idle bound, so judging"
                        + " by it - the old code - would have served the session")
                .isBefore(idleBound);

        assertThat(get("/v1/sessions", token).statusCode())
                .as("expired by the database's clock, so refused on every instance")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("an instance an hour fast serves a session the database still holds live")
    void aFastInstanceServesALiveSession() throws Exception {
        IdentityId identity = givenAnIdentity();
        String token = givenASession(identity);
        Instant idleBound =
                moveBounds(identity, "now() - interval '1 hour'", "now() + interval '10 minutes'");

        clock.skewAgainstTheServer(Duration.ofHours(1));
        assertThat(clock.instant())
                .as("precondition: this instance's clock is already PAST the idle bound, so judging"
                        + " by it - the old code - would have refused a live session")
                .isAfter(idleBound);

        assertThat(get("/v1/sessions", token).statusCode())
                .as("live by the database's clock, so served on every instance")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("a login on an instance an hour fast is timed by the database")
    void aLoginOnAFastInstanceIsTimedByTheDatabase() throws Exception {
        Login login = givenAnIdentityWithACredential();

        clock.skewAgainstTheServer(Duration.ofHours(1));
        assertThat(Duration.between(SimulatedInstance.serverNow(), clock.instant()))
                .as("precondition: this instance is really an hour fast")
                .isGreaterThan(Duration.ofMinutes(59));

        HttpResponse<String> response = authenticate(login.identifier());
        assertThat(response.statusCode()).isEqualTo(201);
        Instant expiresAt = Instant.parse(jsonString(response.body(), "expiresAt"));

        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT issued_at, live_from, idle_expires_at"
                                        + " FROM identity.session WHERE identity_id = ?")) {
            select.setObject(1, login.identityId().value());
            try (ResultSet rows = select.executeQuery()) {
                assertThat(rows.next()).as("the login issued exactly one session").isTrue();
                Instant issuedAt = rows.getTimestamp(1).toInstant();
                Instant liveFrom = rows.getTimestamp(2).toInstant();
                Instant idle = rows.getTimestamp(3).toInstant();

                assertThat(expiresAt)
                        .as("the client is told the bound the database stored and will judge")
                        .isEqualTo(idle);
                assertThat(Duration.between(liveFrom, idle))
                        .as("thirty minutes on the database's clock - the fast clock used to add"
                                + " its hour to it")
                        .isEqualTo(SessionPolicy.current().idleTimeout());
                assertThat(Duration.between(liveFrom, issuedAt))
                        .as("issued_at is this instance's business time, an hour ahead")
                        .isGreaterThan(Duration.ofMinutes(59));
                assertThat(rows.next()).isFalse();
            }
        }
    }

    // -----------------------------------------------------------------

    /**
     * The replaced {@code Clock} bean: the system clock, offset so that it runs a chosen distance
     * from the database's {@code now()}.
     *
     * <p>Ticking rather than fixed, because the whole application runs on it for the length of a
     * request. Test code only: {@code NoAmbientTimeRulesTest} sweeps production classes, and a
     * clock built from the system clock belongs nowhere else.
     */
    static final class SkewableClock extends Clock {

        private final Clock system = Clock.systemUTC();
        private volatile Duration offset = Duration.ZERO;

        /** Runs {@code skew} away from the database's clock, measured against the server now. */
        void skewAgainstTheServer(Duration skew) throws SQLException {
            offset = Duration.between(system.instant(), SimulatedInstance.serverNow()).plus(skew);
        }

        void agreeWithTheJvm() {
            offset = Duration.ZERO;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (ZoneOffset.UTC.equals(zone)) {
                return this;
            }
            throw new UnsupportedOperationException("This application runs in UTC");
        }

        @Override
        public Instant instant() {
            return system.instant().plus(offset);
        }
    }

    /** Registered by {@code @Import}, so it belongs to this suite's context alone. */
    @TestConfiguration
    static class SkewedInstance {

        @Bean
        @Primary
        SkewableClock skewableClock() {
            return new SkewableClock();
        }
    }

    private record Login(IdentityId identityId, LoginIdentifier identifier) {}

    private String givenASession(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session.Draft draft =
                Session.issue(
                        IDS,
                        CLOCK,
                        identity,
                        SessionToken.of(plaintext),
                        AssuranceLevel.PASSWORD,
                        SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, draft);
        }
        return plaintext;
    }

    /** Moves the identity's one session relative to the database's now(); returns the idle bound. */
    private static Instant moveBounds(IdentityId identity, String liveFrom, String idle)
            throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement update =
                        app.prepareStatement(
                                "UPDATE identity.session SET live_from = " + liveFrom
                                        + ", idle_expires_at = " + idle
                                        + " WHERE identity_id = ? RETURNING idle_expires_at")) {
            update.setObject(1, identity.value());
            try (ResultSet rows = update.executeQuery()) {
                assertThat(rows.next()).as("the session to move exists").isTrue();
                return rows.getTimestamp(1).toInstant();
            }
        }
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> authenticate(LoginIdentifier login) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/authentications"))
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"loginIdentifier\":\"%s\",\"password\":\"%s\"}"
                                                .formatted(login.value(), PASSWORD)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Deliberately crude: a full parser would hide a body shaped differently from what is claimed. */
    private static String jsonString(String body, String member) {
        java.util.regex.Matcher match =
                java.util.regex.Pattern.compile("\"" + member + "\"\\s*:\\s*\"([^\"]*)\"")
                        .matcher(body);
        assertThat(match.find()).as("the response carries " + member + ": " + body).isTrue();
        return match.group(1);
    }

    private static IdentityId givenAnIdentity() throws SQLException {
        return givenAnIdentityNamed(someLogin());
    }

    private Login givenAnIdentityWithACredential() throws SQLException {
        LoginIdentifier login = new LoginIdentifier(someLogin());
        IdentityId identity = givenAnIdentityNamed(login.value());
        try (Connection app = DatabaseRoles.application()) {
            new JdbcCredentialStore()
                    .insert(
                            app,
                            Credential.forPassword(
                                    IDS,
                                    CLOCK,
                                    identity,
                                    CredentialType.PASSWORD,
                                    new Argon2PasswordDeriver(WEAK),
                                    RawPassword.of(PASSWORD)));
        }
        return new Login(identity, login);
    }

    private static IdentityId givenAnIdentityNamed(String login) throws SQLException {
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
                    login);
        }
        return IdentityId.of(identity);
    }

    private static String someLogin() {
        return "ada" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            statement.executeUpdate();
        }
    }
}
