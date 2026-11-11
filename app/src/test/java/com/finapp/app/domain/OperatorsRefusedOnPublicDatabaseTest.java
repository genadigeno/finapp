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
import com.finapp.identity.LockoutPolicy;
import com.finapp.identity.RawPassword;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionToken;
import com.finapp.identity.SingleUseToken;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Staff refused on the public surface against a real PostgreSQL and a real Tomcat (`P15-TSK-042`; {@code THREAT_MODEL.md} T1, T3, T31).
 *
 * <p>The surface is the marker the edge would set, sent here by hand: this context's database is on loopback, so it serves a request
 * with no marker as the single port, and honours one that is present. What the hermetic suites prove of each door's decision, this
 * proves of the rows: no lockout row for staff on the public surface however many attempts and however concurrent, none of the session,
 * recovery or credential rows a door would have written, and one {@code identity.PublicSurfaceRefused} per refusal.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("staff refused on the public surface, against the database (P15-TSK-042)")
class OperatorsRefusedOnPublicDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final DerivationParameters WEAK = new DerivationParameters(1024, 1, 1);
    private static final String PASSWORD = "correct horse battery staple";

    @LocalServerPort private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("an operator's own password on the public surface is a wrong password's 401, issues no session and writes no lockout row; on the operator surface it signs in")
    void staffSignInOnTheOperatorSurfaceAlone() throws Exception {
        Person staff = givenAPerson(true);
        Person customer = givenAPerson(false);

        HttpResponse<String> refused = signIn(staff, PASSWORD, "public");
        HttpResponse<String> wrong = signIn(customer, "not the password", "public");
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(code(refused.body())).isEqualTo(code(wrong.body())).isEqualTo("identity.AuthenticationFailed");
        assertThat(sessionsOf(staff)).isZero();
        assertThat(lockoutRows(staff)).isZero();
        assertThat(summariesOf(staff, "identity.PublicSurfaceRefused")).singleElement()
                .isEqualTo("operation=POST /v1/authentications surface=public credential=proven");

        assertThat(signIn(staff, PASSWORD, "operator").statusCode()).isEqualTo(201);
        assertThat(sessionsOf(staff)).isEqualTo(1);
        assertThat(signIn(customer, PASSWORD, "public").statusCode()).as("a customer is served").isEqualTo(201);
    }

    @Test
    @DisplayName("past the threshold and beyond, concurrently, a role-holder's failures on the public surface leave no lockout row, and the operator still signs in")
    void nobodyOnTheInternetLocksOutAnOperator() throws Exception {
        Person staff = givenAPerson(true);
        int attempts = LockoutPolicy.current().threshold() * 2 + 2;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> answers = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                Callable<Integer> attempt = () -> {
                    start.await();
                    return signIn(staff, "not the password", "public").statusCode();
                };
                answers.add(pool.submit(attempt));
            }
            start.countDown();
            for (Future<Integer> answer : answers) {
                assertThat(answer.get()).isEqualTo(401);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(lockoutRows(staff)).as("not one failure counted").isZero();
        assertThat(summariesOf(staff, "identity.PublicSurfaceRefused")).hasSize(attempts)
                .allSatisfy(summary -> assertThat(summary).endsWith("credential=unproven"));
        assertThat(signIn(staff, PASSWORD, "operator").statusCode()).as("not locked").isEqualTo(201);

        Person customer = givenAPerson(false);
        for (int i = 0; i < LockoutPolicy.current().threshold(); i++) {
            signIn(customer, "not the password", "public");
        }
        assertThat(lockoutRows(customer)).as("a customer's failures still count (T1)").isEqualTo(1);
    }

    @Test
    @DisplayName("a stolen operator session cannot step up on the public surface: refused before the code counts, recorded; on the operator surface a wrong code counts as before")
    void aSessionDoorRefusesStaffBeforeItCounts() throws Exception {
        Person staff = givenAPerson(true);
        String session = givenASessionFor(staff.id());

        HttpResponse<String> refused = post("/v1/authentications/mfa", "{\"code\":\"000000\"}", session, "public");
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(code(refused.body())).isEqualTo("identity.AuthenticationFailed");
        assertThat(lockoutRows(staff)).isZero();
        assertThat(summariesOf(staff, "identity.PublicSurfaceRefused")).singleElement()
                .isEqualTo("operation=POST /v1/authentications/mfa surface=public");
        assertThat(actorTypesOf(staff, "identity.PublicSurfaceRefused")).containsExactly("EMPLOYEE");

        assertThat(post("/v1/authentications/mfa", "{\"code\":\"000000\"}", session, "operator").statusCode()).isEqualTo(401);
        assertThat(lockoutRows(staff)).as("the operator surface counts a wrong code, as before").isEqualTo(1);
    }

    @Test
    @DisplayName("recovery for staff is the operator surface's alone: refused on the public one before a request is created or a token spent")
    void staffRecoverOnTheOperatorSurfaceAlone() throws Exception {
        Person staff = givenAPerson(true);

        assertThat(post("/v1/recoveries", "{\"loginIdentifier\":\"" + staff.login() + "\"}", null, "public").statusCode()).isEqualTo(202);
        assertThat(recoveryRequestsOf(staff)).as("nothing created from the internet").isZero();
        assertThat(post("/v1/recoveries", "{\"loginIdentifier\":\"" + staff.login() + "\"}", null, "operator").statusCode()).isEqualTo(202);
        assertThat(recoveryRequestsOf(staff)).isEqualTo(1);

        Person other = givenAPerson(true);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().getBytes());
        UUID request = givenARecoveryRequest(other, token);
        String completion = "{\"token\":\"" + token + "\",\"password\":\"a brand new password here\"}";
        HttpResponse<String> refused = post("/v1/recoveries/" + request + "/completion", completion, null, "public");
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(code(refused.body())).isEqualTo("api.Forbidden");
        assertThat(statusOf(request)).as("the token is not spent").isEqualTo("INITIATED");
        assertThat(post("/v1/recoveries/" + request + "/completion", completion, null, "operator").statusCode()).isEqualTo(204);
        assertThat(statusOf(request)).isEqualTo("COMPLETED");
    }

    // -----------------------------------------------------------------

    private record Person(IdentityId id, String login, UUID credential) {}

    private HttpResponse<String> signIn(Person person, String password, String surface) throws Exception {
        return post("/v1/authentications", "{\"loginIdentifier\":\"" + person.login() + "\",\"password\":\"" + password + "\"}", null,
                surface);
    }

    private HttpResponse<String> post(String path, String body, String session, String surface) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Finapp-Surface", surface)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) {
            request.header("Authorization", "Bearer " + session);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String code(String problem) {
        java.util.regex.Matcher code = java.util.regex.Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"").matcher(problem);
        return code.find() ? code.group(1) : problem;
    }

    /** An active identity with a password credential and a verified channel, holding a live role if {@code staff}. */
    private static Person givenAPerson(boolean staff) throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        String login = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Credential credential = Credential.forPassword(IDS, CLOCK, IdentityId.of(identity), CredentialType.PASSWORD,
                new Argon2PasswordDeriver(WEAK), RawPassword.of(PASSWORD));
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'Ada Lovelace', now())",
                    party);
            execute(app, "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                    + " VALUES (?, ?, ?, 'ACTIVE', now() - interval '1 hour', now() - interval '1 hour')", identity, party, login);
            new JdbcCredentialStore().insert(app, credential);
            execute(app, "INSERT INTO identity.contact_channel (id, identity_id, kind, address, verified_at, added_at)"
                    + " VALUES (?, ?, 'EMAIL', ?, now() - interval '1 hour', now() - interval '2 hours')",
                    IDS.next(), identity, "ada" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@example.com");
            if (staff) {
                execute(app, "INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at)"
                        + " VALUES (?, ?, 'KYC_REVIEWER', ?, now() - interval '1 hour')", IDS.next(), identity, identity);
            }
        }
        return new Person(IdentityId.of(identity), login, credential.id().value());
    }

    private static String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session.Draft session = Session.issue(IDS, CLOCK, identity, SessionToken.of(plaintext), AssuranceLevel.PASSWORD,
                SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            new JdbcSessionStore().insert(app, session);
        }
        return plaintext;
    }

    /** A live request against the identity's active credential and verified channel, its token known to the test. */
    private static UUID givenARecoveryRequest(Person person, String token) throws SQLException {
        UUID request = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO identity.recovery_request (id, identity_id, channel_id, token_hash, status, credential_id,"
                    + " initiated_at, expires_at)"
                    + " SELECT ?, c.identity_id, c.id, ?, 'INITIATED', ?, now() - interval '1 minute', now() + interval '20 minutes'"
                    + " FROM identity.contact_channel c WHERE c.identity_id = ?",
                    request, SingleUseToken.of(token).hash().expose(), person.credential(), person.id().value());
        }
        return request;
    }

    private static long sessionsOf(Person person) throws SQLException {
        return count("SELECT count(*) FROM identity.session WHERE identity_id = ?", person.id().value());
    }

    private static long lockoutRows(Person person) throws SQLException {
        return count("SELECT count(*) FROM identity.authentication_failure WHERE identity_id = ?", person.id().value());
    }

    private static long recoveryRequestsOf(Person person) throws SQLException {
        return count("SELECT count(*) FROM identity.recovery_request WHERE identity_id = ?", person.id().value());
    }

    private static String statusOf(UUID request) throws SQLException {
        return strings("SELECT status FROM identity.recovery_request WHERE id = ?", request).get(0);
    }

    private static List<String> summariesOf(Person person, String action) throws SQLException {
        return strings("SELECT change_summary FROM platform.audit_record WHERE operation = ? AND target_id = ? ORDER BY occurred_at",
                action, person.id().value().toString());
    }

    private static List<String> actorTypesOf(Person person, String action) throws SQLException {
        return strings("SELECT actor_type FROM platform.audit_record WHERE operation = ? AND target_id = ?",
                action, person.id().value().toString());
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        return Long.parseLong(strings(sql, arguments).get(0));
    }

    private static List<String> strings(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application(); PreparedStatement select = app.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                select.setObject(index + 1, arguments[index]);
            }
            List<String> values = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
            return values;
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            statement.executeUpdate();
        }
    }
}
