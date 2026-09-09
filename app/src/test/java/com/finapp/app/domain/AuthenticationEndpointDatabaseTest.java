package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.Credential;
import com.finapp.identity.CredentialType;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcCredentialStore;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.RawPassword;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * {@code POST /v1/authentications}, over real HTTP against a real PostgreSQL (`P1-TSK-010`).
 *
 * <h2>The acceptance criterion is an <em>equality</em>, so it is asserted as one</h2>
 *
 * <p>{@code INV-IDN-07} does not say "do not say which failure it was". It says the outcomes are
 * <strong>indistinguishable</strong> - so the test collects the whole response for each of the four
 * failure causes and asserts they are equal to each other, rather than asserting each one
 * separately against a remembered expectation. The second form passes against an implementation
 * that returns four different bodies which each happen to match what its author wrote down.
 *
 * <p>Status, headers and body are all compared. Only the correlation identifiers and {@code Date}
 * are excluded, because they differ per request by design - the exclusion `P1-TSK-006` had to add
 * after a mutation injecting {@code Idempotent-Replay: false}/{@code true} walked through a
 * comparison of header <em>names</em>.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("POST /v1/authentications (P1-TSK-010)")
class AuthenticationEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    /** Cheap: this suite is about responses and records, not about how long deriving takes. */
    private static final DerivationParameters WEAK = new DerivationParameters(1024, 1, 1);

    private static final String PASSWORD = "correct horse battery staple";

    @LocalServerPort private int port;

    @Autowired private javax.sql.DataSource dataSource;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("the right password authenticates, and the audit record names the identity")
    void aCorrectPasswordAuthenticates() throws Exception {
        Fixture fixture = givenAnIdentityWithACredential();

        Response response = authenticate(fixture.login(), PASSWORD);

        assertThat(response.status())
                .as("201: a session is CREATED (`P1-TSK-027`). It was 204 while nothing issued one")
                .isEqualTo(201);
        assertThat(response.body())
                .as("and the body carries it, at PASSWORD assurance")
                .contains("\"sessionToken\"")
                .contains("\"assurance\":\"PASSWORD\"");

        assertThat(auditRows(fixture.login(), "identity.AuthenticationSucceeded"))
                .as("the success is audited")
                .isEqualTo(1);
        assertThat(auditActor(fixture.login(), "identity.AuthenticationSucceeded"))
                .as("the platform's first real actor: the identity is proven at this point")
                .isEqualTo(fixture.identityId().value().toString());
        assertThat(outboxRows("identity.AuthenticationSucceeded"))
                .as("and announced")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("EVERY failing cause produces byte-identical responses (INV-IDN-07)")
    void everyFailureLooksTheSame() throws Exception {
        // The four causes, and the middle two are the ones an implementation skips. A suspended
        // account or one with no credential answering differently tells an attacker both that the
        // account exists and what state it is in.
        Fixture wrongPassword = givenAnIdentityWithACredential();
        Fixture suspended = givenAnIdentityWithACredential();
        suspend(suspended.identityId());
        Fixture noCredential = givenAnIdentityWithoutACredential();
        LoginIdentifier absent = new LoginIdentifier(someLogin());

        Map<String, Response> byCause = new LinkedHashMap<>();
        byCause.put("no identity at all", authenticate(absent, PASSWORD));
        byCause.put("identity that cannot authenticate", authenticate(suspended.login(), PASSWORD));
        byCause.put("identity with no credential", authenticate(noCredential.login(), PASSWORD));
        byCause.put("wrong password", authenticate(wrongPassword.login(), "not the password"));

        Response first = byCause.values().iterator().next();
        assertThat(first.status()).as("precondition: a failure is a 401").isEqualTo(401);
        assertThat(first.body())
                .as("precondition: the body must exist, or comparing four empty strings proves nothing")
                .contains("identity.AuthenticationFailed");
        // The comparison EXCLUDES correlation, so its absence would be invisible here - the
        // exclusion has to be proven to be excluding something that is really there, or it is a
        // hole rather than an allowance.
        assertThat(rawCorrelationOf(first))
                .as("precondition: the response really does carry a correlation identifier, which is"
                        + " why the comparison is allowed to ignore it")
                .isNotEmpty();

        byCause.forEach(
                (cause, response) ->
                        assertThat(response)
                                .as("'%s' must be indistinguishable from '%s'",
                                        cause, byCause.keySet().iterator().next())
                                .isEqualTo(first));
    }

    @Test
    @DisplayName("a malformed password is a failure, never a distinguishable 422")
    void aPasswordThisPlatformCouldNotHaveStoredIsAnOrdinaryFailure() throws Exception {
        // RawPassword refuses anything under 8 characters. If that surfaced as a 422 this endpoint
        // would have a third response shape and a fast path that skips the derivation entirely.
        Fixture fixture = givenAnIdentityWithACredential();

        Response tooShort = authenticate(fixture.login(), "abc");
        Response wrong = authenticate(fixture.login(), "not the password");

        assertThat(tooShort)
                .as("a password below the domain minimum answers exactly as a wrong one does")
                .isEqualTo(wrong);
    }

    @Test
    @DisplayName("a failure is audited even though the request failed")
    void aFailureIsAudited() throws Exception {
        // The structural point: the audit record is written in the same transaction, so throwing
        // to produce the 401 would roll it back. A credential-stuffing campaign is visible only
        // through these rows.
        LoginIdentifier absent = new LoginIdentifier(someLogin());

        assertThat(authenticate(absent, PASSWORD).status()).isEqualTo(401);

        assertThat(auditRows(absent, "identity.AuthenticationFailed"))
                .as("an attempt against an identifier nobody registered is exactly what must be"
                        + " recorded: that is what credential stuffing looks like")
                .isEqualTo(1);
        assertThat(auditActor(absent, "identity.AuthenticationFailed"))
                .as("no established actor on this path, and possibly no identity at all")
                .isEqualTo("system");
    }

    @Test
    @DisplayName("the failure event carries no identifier of any kind")
    void theFailureEventNamesNobody() throws Exception {
        Fixture fixture = givenAnIdentityWithACredential();

        assertThat(authenticate(fixture.login(), "not the password").status()).isEqualTo(401);

        List<String> payloads = outboxPayloads("identity.AuthenticationFailed");
        assertThat(payloads).as("precondition: the event was written").isNotEmpty();
        for (String payload : payloads) {
            assertThat(payload)
                    .as("an event stream reaches systems with different access control")
                    .doesNotContain(fixture.login().value())
                    .doesNotContain(fixture.identityId().value().toString());
        }
    }

    @Test
    @DisplayName("neither the password nor the derivation appears in any response")
    void nothingEchoesTheSecret() throws Exception {
        Fixture fixture = givenAnIdentityWithACredential();

        Response success = authenticate(fixture.login(), PASSWORD);
        Response failure = authenticate(fixture.login(), "not the password");

        // Both bodies must be non-empty first. Since `P1-TSK-027` the success carries a session,
        // so a doesNotContain over it is a real assertion - but if that body ever became empty
        // again this check would pass while examining nothing, which is the vacuity this repository
        // keeps meeting.
        assertThat(success.body()).as("precondition: there is a success body to examine").isNotEmpty();
        assertThat(failure.body()).as("precondition: there is a failure body to examine").isNotEmpty();

        for (Response response : List.of(success, failure)) {
            assertThat(response.body()).doesNotContain(PASSWORD).doesNotContain("not the password");
            assertThat(response.headers().toString()).doesNotContain(PASSWORD);
        }
    }

    @Test
    @DisplayName("ten concurrent logins all succeed and upgrade the credential exactly once")
    void tenInstancesAuthenticateConcurrently() throws Exception {
        // Ten instances of the service, one identity, one weak credential. Every login must
        // succeed - authentication is not a command to deduplicate - and the upgrade-on-use must
        // happen exactly once, because `supersede` is conditional and its row count is the outcome.
        Fixture fixture = givenAnIdentityWithACredential();

        int racers = 10;
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < racers; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    go.await(30, TimeUnit.SECONDS);
                                    return authenticate(fixture.login(), PASSWORD).status();
                                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            for (Future<Integer> result : results) {
                assertThat(result.get(60, TimeUnit.SECONDS))
                        .as("every concurrent login succeeds; this is not a command to deduplicate")
                        .isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(credentialRows(fixture.identityId()))
                .as("the weak credential plus exactly one upgrade, not ten")
                .isEqualTo(2);
        assertThat(activeCredentialRows(fixture.identityId()))
                .as("and exactly one of them is active, which the partial unique index guarantees")
                .isEqualTo(1);
        assertThat(auditRows(fixture.login(), "identity.AuthenticationSucceeded"))
                .as("ten logins are ten audit records: each one happened")
                .isEqualTo(racers);
    }

    @Test
    @DisplayName("no request shape produces a 500: the caller's mistake is never our fault")
    void malformedRequestsAreTheCallersFaultAndSaySo() throws Exception {
        // Probed during the completion gate rather than reasoned about, because the shapes that
        // reach a 500 are never the ones an author thought of. `ERROR_CONTRACT.md` §3 forbids
        // reporting a caller's mistake as a platform failure - a client may retry a 500 for ever on
        // a request that can never succeed, and a spike of malformed requests then looks exactly
        // like an outage on every error-rate dashboard.
        Map<String, String> shapes = new LinkedHashMap<>();
        shapes.put("no password at all", "{\"loginIdentifier\":\"abcdefgh\"}");
        shapes.put("a null password", "{\"loginIdentifier\":\"abcdefgh\",\"password\":null}");
        shapes.put("no login identifier", "{\"password\":\"abcdefgh\"}");
        shapes.put("a login identifier the charset refuses",
                "{\"loginIdentifier\":\"ada@example.com\",\"password\":\"abcdefgh\"}");
        shapes.put("a password sent as an object",
                "{\"loginIdentifier\":\"abcdefgh\",\"password\":{\"a\":1}}");
        shapes.put("a password sent as an array",
                "{\"loginIdentifier\":\"abcdefgh\",\"password\":[1,2]}");
        shapes.put("a password sent as a number",
                "{\"loginIdentifier\":\"abcdefgh\",\"password\":12345678}");
        shapes.put("an empty password", "{\"loginIdentifier\":\"abcdefgh\",\"password\":\"\"}");

        for (Map.Entry<String, String> shape : shapes.entrySet()) {
            Response response = post(shape.getValue());
            assertThat(response.status())
                    .as("%s must not be reported as a platform failure", shape.getKey())
                    .isNotEqualTo(500);
            assertThat(response.status())
                    .as("%s is either a request-shape problem or an authentication failure",
                            shape.getKey())
                    .isIn(400, 401, 422);
            assertThat(response.body())
                    .as("%s: nothing echoes what was sent", shape.getKey())
                    .doesNotContain("12345678");
        }

        // And the two that are decided by different mechanisms, pinned so a Jackson upgrade cannot
        // move them silently: a number is COERCED and fails authentication, while an object has no
        // string form and never reaches the deserialiser at all.
        assertThat(post("{\"loginIdentifier\":\"abcdefgh\",\"password\":12345678}").status())
                .as("a scalar is coerced, so it fails authentication like any other wrong value")
                .isEqualTo(401);
        assertThat(post("{\"loginIdentifier\":\"abcdefgh\",\"password\":{\"a\":1}}").status())
                .as("an object is a body that could not be understood, which is a 400")
                .isEqualTo(400);
    }

    // -----------------------------------------------------------------

    private record Response(int status, String body, Map<String, List<String>> headers) {}

    /** The correlation identifier the response actually carried, before it was normalised away. */
    private static final Map<Response, String> RAW_CORRELATION = new java.util.concurrent.ConcurrentHashMap<>();

    private static String rawCorrelationOf(Response response) {
        return RAW_CORRELATION.getOrDefault(response, "");
    }

    private record Fixture(IdentityId identityId, LoginIdentifier login) {}

    private Response authenticate(LoginIdentifier login, String password) throws Exception {
        return post(
                "{\"loginIdentifier\":\"%s\",\"password\":\"%s\"}"
                        .formatted(login.value(), password));
    }

    private Response post(String body) throws Exception {
        HttpResponse<String> response =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://localhost:" + port + "/v1/authentications"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        // Correlation differs per request by design, and Date changes with the clock. Everything
        // else must match, values included - comparing header NAMES alone let an injected
        // `Idempotent-Replay` header through in `P1-TSK-006`, because the name is identical on both
        // and the value is the whole disclosure.
        Map<String, List<String>> headers = new LinkedHashMap<>(response.headers().map());
        headers.keySet()
                .removeIf(
                        name ->
                                name.equalsIgnoreCase("x-correlation-id")
                                        || name.equalsIgnoreCase("x-client-correlation-id")
                                        || name.equalsIgnoreCase("date"));

        String withoutCorrelation =
                response.body().replaceAll("\"correlationId\"\\s*:\\s*\"[^\"]*\"", "\"correlationId\":\"…\"");
        Response normalised = new Response(response.statusCode(), withoutCorrelation, headers);
        RAW_CORRELATION.put(
                normalised, response.headers().firstValue("X-Correlation-Id").orElse(""));
        return normalised;
    }

    private Fixture givenAnIdentityWithACredential() throws SQLException {
        Fixture fixture = givenAnIdentityWithoutACredential();
        try (Connection app = DatabaseRoles.application()) {
            new JdbcCredentialStore()
                    .insert(
                            app,
                            Credential.forPassword(
                                    IDS,
                                    CLOCK,
                                    fixture.identityId(),
                                    CredentialType.PASSWORD,
                                    new Argon2PasswordDeriver(WEAK),
                                    RawPassword.of(PASSWORD)));
        }
        return fixture;
    }

    private Fixture givenAnIdentityWithoutACredential() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        LoginIdentifier login = new LoginIdentifier(someLogin());
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Ada Lovelace', now())",
                    party);
            execute(
                    app,
                    // Back-dated: this fixture is later moved to another status by an UPDATE that
                    // reads now() again, and the local container's clock is corrected backwards
                    // between statements (P1-TSK-031; observed 225 ms). The ordering constraint is
                    // right and a fixture must not depend on two now() reads being ordered.
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    identity,
                    party,
                    login.value());
        }
        return new Fixture(IdentityId.of(identity), login);
    }

    private void suspend(IdentityId identityId) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "UPDATE identity.identity SET status = 'SUSPENDED', status_changed_at = now()"
                            + " WHERE id = ?",
                    identityId.value());
        }
    }

    private int auditRows(LoginIdentifier login, String operation) throws SQLException {
        return count(
                "SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND operation = ?",
                login.value(),
                operation);
    }

    private String auditActor(LoginIdentifier login, String operation) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT actor_id FROM platform.audit_record"
                                        + " WHERE target_id = ? AND operation = ?")) {
            select.setString(1, login.value());
            select.setString(2, operation);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private int outboxRows(String eventType) throws SQLException {
        return count("SELECT count(*) FROM platform.outbox_event WHERE event_type = ?", eventType);
    }

    private List<String> outboxPayloads(String eventType) throws SQLException {
        List<String> payloads = new ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event"
                                        + " WHERE event_type = ?")) {
            select.setString(1, eventType);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    payloads.add(rows.getString(1));
                }
            }
        }
        return payloads;
    }

    private int credentialRows(IdentityId identityId) throws SQLException {
        return count(
                "SELECT count(*) FROM identity.credential WHERE identity_id = ?",
                identityId.value());
    }

    private int activeCredentialRows(IdentityId identityId) throws SQLException {
        return count(
                "SELECT count(*) FROM identity.credential WHERE identity_id = ? AND status = 'ACTIVE'",
                identityId.value());
    }

    private int count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                select.setObject(index + 1, arguments[index]);
            }
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
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

    private static String someLogin() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
