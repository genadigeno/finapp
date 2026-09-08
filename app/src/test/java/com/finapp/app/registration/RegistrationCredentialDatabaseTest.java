package com.finapp.app.registration;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.api.IdempotencyKeyHeader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * A registration produces a login that can actually be used (`P1-TSK-026`).
 *
 * <h2>The property is asserted end to end, because the defect it closes was exactly the gap
 * between two things that each worked</h2>
 *
 * <p>{@code P1-TSK-006} created a Party, a Customer and an Identity and no credential, so a
 * registered person could never authenticate - and every suite passed, because each half was
 * tested against its own fixture. {@code P1-TSK-027} met the same shape one layer up: a session
 * existed and no login issued one, while every test that needed a session inserted the row itself.
 *
 * <p>So "a credential row exists" is deliberately <strong>not</strong> the headline assertion here.
 * {@link #aRegisteredPersonCanAuthenticate} registers over HTTP and then logs in over HTTP with the
 * password it registered, and the token that comes back opens a protected endpoint. Nothing in that
 * chain is inserted by the test. A row assertion would have passed against a credential stored
 * under the wrong identity, the wrong algorithm, or a status nothing can verify.
 *
 * <p>{@link #aFabricatedPasswordOpensNothing} is its negative control - without it, an
 * implementation that authenticated everybody would satisfy the headline perfectly.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegistrationCredentialDatabaseTest {

    /**
     * Distinctive enough that finding it anywhere is unambiguous, and long enough for
     * {@code RawPassword}.
     *
     * <p>A marker rather than a plausible password on purpose: {@link #thePlaintextIsInNoColumn}
     * searches every column of every table for it, and a value that could occur naturally would
     * make a hit ambiguous.
     */
    private static final String PASSWORD = "zqx-registration-plaintext-marker-4b17e9";

    /** The schemas this platform owns. Their tables are derived, never listed. */
    private static final List<String> SCHEMAS = List.of("party", "identity", "platform");

    @LocalServerPort private int port;

    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("a person who registers can then authenticate, and the session works")
    void aRegisteredPersonCanAuthenticate() throws Exception {
        String login = someLogin();

        assertThat(register(login, PASSWORD, aKey()).statusCode()).isEqualTo(201);

        HttpResponse<String> authentication = authenticate(login, PASSWORD);
        assertThat(authentication.statusCode())
                .as("the credential registration wrote is the one authentication verifies")
                .isEqualTo(201);

        String token = tokenIn(authentication.body());
        assertThat(token).isNotBlank();

        // The session is real, not merely returned. P1-TSK-027's standard: a token asserted to
        // exist proves nothing about whether anything accepts it.
        HttpResponse<String> sessions = get("/v1/sessions", token);
        assertThat(sessions.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("a password that was never registered opens nothing")
    void aFabricatedPasswordOpensNothing() throws Exception {
        String login = someLogin();
        assertThat(register(login, PASSWORD, aKey()).statusCode()).isEqualTo(201);

        assertThat(authenticate(login, "zqx-a-completely-different-password").statusCode())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("a registration produces exactly one ACTIVE credential for that identity")
    void exactlyOneActiveCredential() throws Exception {
        String login = someLogin();
        assertThat(register(login, PASSWORD, aKey()).statusCode()).isEqualTo(201);

        UUID identity = identityIdFor(login);
        assertThat(countOf("SELECT count(*) FROM identity.credential WHERE identity_id = ?", identity))
                .isEqualTo(1);
        assertThat(
                        countOf(
                                "SELECT count(*) FROM identity.credential"
                                        + " WHERE identity_id = ? AND status = 'ACTIVE'",
                                identity))
                .isEqualTo(1);
    }

    /**
     * The plaintext reaches no column of any table in any schema this platform owns.
     *
     * <p><strong>Every table, derived from {@code information_schema}</strong>, rather than the
     * credential table this task happened to write to. {@code P1-TSK-007} already proved the
     * credential row does not hold it; what {@code P1-TSK-026} adds is a password travelling
     * through an HTTP boundary, an idempotency record, an audit record and an outbox row - four
     * sinks that credential storage never touched, and three of them reach systems with different
     * access control ({@code INV-AUD-02}).
     *
     * <p>The list is derived so a table added in Phase 2 is swept without anyone remembering, which
     * is the same reason {@code CredentialNeverLeaksDatabaseTest} derives its columns.
     */
    @Test
    @DisplayName("the plaintext appears in no column of any table, in any schema")
    void thePlaintextIsInNoColumn() throws Exception {
        String login = someLogin();
        assertThat(register(login, PASSWORD, aKey()).statusCode()).isEqualTo(201);

        List<String> swept = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (String qualified : tablesIn(connection)) {
                String columns = concatenationOfEveryColumn(connection, qualified);
                if (columns.isEmpty()) {
                    continue;
                }
                swept.add(qualified);
                assertThat(rowsMatching(connection, qualified, columns))
                        .as("%s holds the plaintext", qualified)
                        .isZero();
            }
        }
        assertThat(swept)
                .as("a sweep that reached no table would pass while checking nothing")
                .contains(
                        "identity.credential",
                        "identity.identity",
                        "party.party",
                        "platform.audit_record",
                        "platform.outbox_event",
                        "platform.idempotency_record");
    }

    /**
     * The fingerprint ignores the password, asserted behaviourally.
     *
     * <p>{@code RegistrationFingerprintTest} asserts the canonical form directly; this asserts what
     * a client actually observes, which is the claim that matters and the one that would survive
     * somebody deciding the fingerprint should include more fields.
     *
     * <p>The consequence is deliberate and is recorded on {@code RegistrationService.canonicalForm}:
     * a retry with a different password <strong>replays</strong> rather than being refused as an
     * {@code INV-IDEM-03} conflict, because the alternative is storing an offline-crackable
     * derivation of a password for ever ({@code INV-IDN-01}).
     */
    @Test
    @DisplayName("a retry with a different password replays, and creates no second credential")
    void theFingerprintIgnoresThePassword() throws Exception {
        String login = someLogin();
        String key = aKey();

        assertThat(register(login, PASSWORD, key).statusCode()).isEqualTo(201);
        assertThat(register(login, "zqx-a-quite-different-password", key).statusCode())
                .as("same key, same significant fields: a replay, not a conflict")
                .isEqualTo(201);

        UUID identity = identityIdFor(login);
        assertThat(countOf("SELECT count(*) FROM identity.credential WHERE identity_id = ?", identity))
                .isEqualTo(1);

        // And the second password is not the one that works, because the second request was never
        // executed - which is what a replay means.
        assertThat(authenticate(login, PASSWORD).statusCode()).isEqualTo(201);
        assertThat(authenticate(login, "zqx-a-quite-different-password").statusCode())
                .isEqualTo(401);
    }

    /**
     * A password below {@code RawPassword}'s minimum is the caller's mistake, reported as one.
     *
     * <p>422 rather than 500: without the mapping in {@code RegistrationService}, the domain type's
     * {@code IllegalArgumentException} would surface as {@code api.InternalError} - our fault
     * reported for their input, which {@code ERROR_CONTRACT.md} §3 forbids and which a client may
     * retry for ever.
     *
     * <p>Registration answers this differently from authentication, deliberately: there a short
     * password is an ordinary failure, because a second response shape is an enumeration risk;
     * here it is a value the caller chose and must be able to correct, and the refusal is decided
     * before any lookup, so it discloses nothing about any account.
     */
    @Test
    @DisplayName("a password below the minimum is a 422 that names the field and creates nothing")
    void aShortPasswordIsRefused() throws Exception {
        String login = someLogin();

        HttpResponse<String> response = register(login, "short", aKey());

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("api.ValidationFailed").contains("password");
        assertThat(countOf("SELECT count(*) FROM identity.identity WHERE login_identifier = ?", login))
                .as("nothing is created by a request that never reached the transaction")
                .isZero();
    }

    @Test
    @DisplayName("the refusal never echoes the password")
    void theRefusalDoesNotEchoThePassword() throws Exception {
        HttpResponse<String> response = register(someLogin(), "zqx-99", aKey());

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body())
                .as("the rejected value is the caller's own secret (INV-AUD-02)")
                .doesNotContain("zqx-99");
    }

    /**
     * No password shape turns the caller's mistake into ours.
     *
     * <p>{@code P1-TSK-010}'s gate drove this for authentication and found the value of doing it:
     * a {@code 500} says our side failed for something only the caller can fix, and a client may
     * retry one for ever on a request that can never succeed ({@code ERROR_CONTRACT.md} §3). This
     * endpoint gained a secret-carrying field, so the same probe applies to it.
     *
     * <p>Two of these are decided by <strong>different mechanisms</strong>, which is why they are
     * driven rather than reasoned about: a number is <em>coerced</em> by the deserialiser and
     * succeeds; an object or an array never reaches the deserialiser at all, because Jackson raises
     * while resolving the token, so it is a {@code 400} rather than a {@code 422}.
     */
    @Test
    @DisplayName("no password shape produces a 500")
    void noRequestShapeIsOurFault() throws Exception {
        Map<String, String> shapes = new LinkedHashMap<>();
        shapes.put("absent", "");
        shapes.put("null", ",\"password\":null");
        shapes.put("empty", ",\"password\":\"\"");
        shapes.put("number", ",\"password\":12345678");
        shapes.put("object", ",\"password\":{\"a\":1}");
        shapes.put("array", ",\"password\":[1,2]");
        shapes.put("boolean", ",\"password\":true");
        shapes.put("below the minimum", ",\"password\":\"zqx-99\"");
        shapes.put("above the maximum", ",\"password\":\"" + "q".repeat(300) + "\"");
        shapes.put("at the minimum", ",\"password\":\"12345678\"");
        shapes.put("at the maximum", ",\"password\":\"" + "q".repeat(256) + "\"");

        for (Map.Entry<String, String> shape : shapes.entrySet()) {
            HttpResponse<String> response =
                    post(
                            "/v1/registrations",
                            "{\"loginIdentifier\":\""
                                    + someLogin()
                                    + "\",\"displayName\":\"Ada Lovelace\""
                                    + shape.getValue()
                                    + "}",
                            aKey());
            assertThat(response.statusCode())
                    .as("password %s", shape.getKey())
                    .isIn(200, 201, 400, 422);
        }
    }

    /**
     * A number registers and authenticates as its string form, across two endpoints.
     *
     * <p>{@code SensitiveSerialization} documents the coercion and explains why it is right - a
     * caller sending a number has made a mistake about the shape of the request, not about the
     * secret, and refusing it would add a response shape to a path {@code INV-IDN-07} wants to have
     * exactly two. What nothing asserted is that the two endpoints coerce the <strong>same
     * way</strong>, and until this task there was no registration secret to be asymmetric with.
     *
     * <p>The failure it forecloses is a customer who registers successfully and can never log in -
     * which is precisely the state {@code P1-TSK-026} exists to close, arriving through a
     * different door.
     */
    @Test
    @DisplayName("a numeric password registers and then authenticates as its string form")
    void coercionIsSymmetricAcrossTheTwoEndpoints() throws Exception {
        String login = someLogin();

        assertThat(
                        post(
                                        "/v1/registrations",
                                        "{\"loginIdentifier\":\""
                                                + login
                                                + "\",\"displayName\":\"Ada Lovelace\""
                                                + ",\"password\":12345678}",
                                        aKey())
                                .statusCode())
                .isEqualTo(201);

        assertThat(authenticate(login, "12345678").statusCode())
                .as("what registration coerced is what authentication derives against")
                .isEqualTo(201);
    }

    /**
     * The audit record and the event name the credential that was created.
     *
     * <p>Added by the completion gate, which found the claim and nothing behind it: {@code
     * P1-TSK-026} put the credential identifier into {@code IDENTITY_CREATED}'s change summary and
     * into the event payload, and no assertion looked at either. An investigator asking <em>which
     * credential did this registration produce?</em> has one row to read rather than a join by
     * timestamp - and a summary that silently stopped naming it would read exactly like one that
     * never did.
     *
     * <p>Asserted as the property rather than as a rendering: the identifier must be
     * <strong>findable</strong>, because a free-text summary is searched by substring and never by
     * equality ({@code P1-TSK-027}'s finding, where a test pinned a rendering and the test was the
     * thing that was wrong).
     */
    @Test
    @DisplayName("the audit record and the event both name the credential that was created")
    void theCredentialIsTraceable() throws Exception {
        String login = someLogin();
        assertThat(register(login, PASSWORD, aKey()).statusCode()).isEqualTo(201);

        UUID identity = identityIdFor(login);
        String credential = credentialIdFor(identity);
        assertThat(credential).isNotBlank();

        assertThat(textOf("SELECT coalesce(string_agg(change_summary, ' '), '')"
                        + " FROM platform.audit_record WHERE target_id = ?", login))
                .as("the trail says which credential the registration produced")
                .contains(credential);

        assertThat(textOf("SELECT coalesce(string_agg(convert_from(payload, 'UTF8'), ' '), '')"
                        + " FROM platform.outbox_event WHERE aggregate_id = ?::uuid", identity.toString()))
                .as("and so does the fact that is published")
                .contains(credential);
    }

    // -----------------------------------------------------------------

    private String credentialIdFor(UUID identity) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT id::text FROM identity.credential WHERE identity_id = ?")) {
            statement.setObject(1, identity);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : "";
            }
        }
    }

    private String textOf(String sql, String argument) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, argument);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private List<String> tablesIn(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT table_schema, table_name FROM information_schema.tables"
                                + " WHERE table_schema = ANY (?) AND table_type = 'BASE TABLE'"
                                + " ORDER BY table_schema, table_name")) {
            statement.setArray(1, connection.createArrayOf("text", SCHEMAS.toArray()));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    tables.add(rows.getString(1) + "." + rows.getString(2));
                }
            }
        }
        if (tables.isEmpty()) {
            throw new IllegalStateException("No tables found; the sweep would be vacuous");
        }
        return tables;
    }

    private static String concatenationOfEveryColumn(Connection connection, String qualified)
            throws SQLException {
        String[] parts = qualified.split("\\.", 2);
        StringBuilder columns = new StringBuilder();
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_schema = ? AND table_name = ?"
                                + " ORDER BY ordinal_position")) {
            statement.setString(1, parts[0]);
            statement.setString(2, parts[1]);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (columns.length() > 0) {
                        columns.append(" || ' ' || ");
                    }
                    columns.append("coalesce(").append(rows.getString(1)).append("::text, '')");
                }
            }
        }
        return columns.toString();
    }

    private static int rowsMatching(Connection connection, String qualified, String columns)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT count(*) FROM " + qualified + " WHERE (" + columns + ") LIKE ?")) {
            statement.setString(1, "%" + PASSWORD + "%");
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private int countOf(String sql, Object argument) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, argument);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private UUID identityIdFor(String login) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT id FROM identity.identity WHERE login_identifier = ?")) {
            statement.setString(1, login);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getObject(1, UUID.class) : null;
            }
        }
    }

    /** Reads {@code sessionToken} without a JSON library, as the sibling suites do. */
    private static String tokenIn(String body) {
        String marker = "\"sessionToken\":\"";
        int start = body.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        return body.substring(start, body.indexOf('"', start));
    }

    private HttpResponse<String> register(String login, String password, String key)
            throws IOException, InterruptedException {
        return post(
                "/v1/registrations",
                "{\"loginIdentifier\":\""
                        + login
                        + "\",\"displayName\":\"Ada Lovelace\",\"password\":\""
                        + password
                        + "\"}",
                key);
    }

    private HttpResponse<String> authenticate(String login, String password)
            throws IOException, InterruptedException {
        return post(
                "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + password + "\"}",
                null);
    }

    private HttpResponse<String> post(String path, String body, String key)
            throws IOException, InterruptedException {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> get(String path, String token)
            throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String someLogin() {
        return "ada." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String aKey() {
        return UUID.randomUUID().toString();
    }
}
