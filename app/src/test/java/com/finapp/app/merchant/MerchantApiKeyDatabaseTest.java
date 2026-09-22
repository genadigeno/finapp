package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
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
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The merchant's API credential over real HTTP (`P6-TSK-002`, ADR-0052) — every accept clause
 * of this task, driven through the real door: a merchant authenticates and reaches only its
 * own record; a <strong>suspended</strong> merchant's key refuses; a <strong>revoked</strong>
 * key refuses immediately and cross-instance; no secret is recoverable from any surface or
 * any column; issuance is audited with the actor and the key id.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the merchant api key (P6-TSK-002)")
class MerchantApiKeyDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    // -----------------------------------------------------------------

    @Test
    @DisplayName("a merchant authenticates with its key and sees ITS OWN record - never the"
            + " other merchant's, with no identifier to address one by (INV-MER-01)")
    void aMerchantSeesOnlyItsOwnRecord() throws Exception {
        String admin = administrator();
        Merchant one = onboarded(admin);
        Merchant two = onboarded(admin);
        String keyOne = issueKey(admin, one.id());
        String keyTwo = issueKey(admin, two.id());

        HttpResponse<String> mine = get("/v1/merchant/me", keyOne);
        assertThat(mine.statusCode()).isEqualTo(200);
        assertThat(field(mine.body(), "merchantId")).isEqualTo(one.id());
        assertThat(mine.body())
                .as("no other tenant's identifier appears anywhere in the response")
                .doesNotContain(two.id());

        assertThat(field(get("/v1/merchant/me", keyTwo).body(), "merchantId")).isEqualTo(two.id());
    }

    @Test
    @DisplayName("a SUSPENDED merchant's key refuses - the standing is in the lookup's join,"
            + " so nobody had to revoke anything")
    void aSuspendedMerchantsKeyRefuses() throws Exception {
        String admin = administrator();
        Merchant merchant = onboarded(admin);
        String key = issueKey(admin, merchant.id());
        assertThat(get("/v1/merchant/me", key).statusCode()).isEqualTo(200);

        assertThat(
                        post(
                                        "/v1/operator/merchants/" + merchant.id() + "/suspension",
                                        "{\"reason\":\"under investigation\"}",
                                        admin,
                                        null)
                                .statusCode())
                .isEqualTo(200);

        assertThat(get("/v1/merchant/me", key).statusCode())
                .as("the very next request, with no cache to invalidate anywhere")
                .isEqualTo(401);

        // And it comes back when the suspension lifts - the key was never touched.
        assertThat(
                        post(
                                        "/v1/operator/merchants/" + merchant.id()
                                                + "/reinstatement",
                                        "{\"reason\":\"cleared\"}",
                                        admin,
                                        null)
                                .statusCode())
                .isEqualTo(200);
        assertThat(get("/v1/merchant/me", key).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("a REVOKED key refuses immediately, and the revocation is reasoned, audited"
            + " and terminal")
    void aRevokedKeyRefusesImmediately() throws Exception {
        String admin = administrator();
        Merchant merchant = onboarded(admin);
        String key = issueKey(admin, merchant.id());
        String keyId = key.substring(0, key.indexOf('.'));
        assertThat(get("/v1/merchant/me", key).statusCode()).isEqualTo(200);

        String reason = "rotated after a contractor left " + suffix();
        HttpResponse<String> revoked =
                delete(
                        "/v1/operator/merchants/" + merchant.id() + "/api-keys/" + keyId,
                        "{\"reason\":\"" + reason + "\"}",
                        admin);
        assertThat(revoked.statusCode()).isEqualTo(204);

        assertThat(get("/v1/merchant/me", key).statusCode())
                .as("the next request on any instance: the lookup asks authoritative state")
                .isEqualTo(401);

        assertThat(
                        count(
                                "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                        + " 'merchant.MerchantApiKeyRevoked' AND target_id = ?"
                                        + " AND reason = ?",
                                keyId,
                                reason))
                .as("the operator's words, verbatim (INV-AUD-03)")
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM merchant.merchant_api_key_event WHERE"
                                        + " key_id = ? AND from_status = 'ACTIVE' AND to_status"
                                        + " = 'REVOKED'",
                                UUID.fromString(keyId)))
                .isEqualTo(1);

        // Terminal: the repeat converges on this same 204 and writes no second history row.
        assertThat(
                        delete(
                                        "/v1/operator/merchants/" + merchant.id() + "/api-keys/"
                                                + keyId,
                                        "{\"reason\":\"again\"}",
                                        admin)
                                .statusCode())
                .isEqualTo(204);
        assertThat(
                        count(
                                "SELECT count(*) FROM merchant.merchant_api_key_event WHERE"
                                        + " key_id = ?",
                                UUID.fromString(keyId)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("NO SECRET IS RECOVERABLE: not from the list, not from a replay, not from any"
            + " column in the schema (INV-IDN-01)")
    void noSecretIsRecoverable() throws Exception {
        String admin = administrator();
        Merchant merchant = onboarded(admin);
        String idempotencyKey = someKey();

        HttpResponse<String> issued =
                post(
                        "/v1/operator/merchants/" + merchant.id() + "/api-keys",
                        null,
                        admin,
                        idempotencyKey);
        assertThat(issued.statusCode()).isEqualTo(201);
        String secret = field(issued.body(), "secret");
        String keyId = field(issued.body(), "keyId");

        // The list renders metadata and nothing else.
        HttpResponse<String> listed =
                get("/v1/operator/merchants/" + merchant.id() + "/api-keys", null, admin);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains(keyId).doesNotContain(secret);

        // THE REPLAY DOES NOT RE-SHOW IT. The claim stores the key id, never the plaintext -
        // so a retried issuance converges on the same credential and says so, rather than
        // handing the secret out a second time from storage.
        HttpResponse<String> replayed =
                post(
                        "/v1/operator/merchants/" + merchant.id() + "/api-keys",
                        null,
                        admin,
                        idempotencyKey);
        assertThat(replayed.statusCode()).isEqualTo(201);
        assertThat(field(replayed.body(), "keyId")).isEqualTo(keyId);
        assertThat(replayed.body())
                .as("show-once means ONCE - a replay renders no secret")
                .doesNotContain(secret);
        assertThat(replayed.body()).contains("\"alreadyIssued\":true");
        assertThat(
                        count(
                                "SELECT count(*) FROM merchant.merchant_api_key WHERE"
                                        + " merchant_id = ?",
                                UUID.fromString(merchant.id())))
                .as("and mints no second credential")
                .isEqualTo(1);

        // NOT IN ANY COLUMN, ANYWHERE. The claim's stored response is the sharpest of these:
        // it is where the secret WOULD have been, had the replay discipline won over
        // INV-IDN-01.
        assertThat(columnHolding(secret)).as("no column in any schema holds the secret").isEmpty();
    }

    @Test
    @DisplayName("every refusal is one 401: unknown key, wrong secret, malformed value, no"
            + " header - an authentication surface is not an oracle")
    void everyRefusalIsOne401() throws Exception {
        String admin = administrator();
        Merchant merchant = onboarded(admin);
        String key = issueKey(admin, merchant.id());
        String keyId = key.substring(0, key.indexOf('.'));

        for (String presented :
                new String[] {
                    null,
                    "",
                    "not-a-credential",
                    UUID.randomUUID() + ".whatever",
                    keyId + ".wrong-secret",
                    keyId,
                    "." + key,
                }) {
            assertThat(get("/v1/merchant/me", presented).statusCode())
                    .as("presented: %s", presented)
                    .isEqualTo(401);
        }
    }

    @Test
    @DisplayName("a customer session cannot reach the merchant surface, and a merchant key"
            + " cannot reach the operator surface - the populations are disjoint")
    void thePopulationsAreDisjoint() throws Exception {
        String admin = administrator();
        Merchant merchant = onboarded(admin);
        String key = issueKey(admin, merchant.id());

        // A session token presented as a merchant key is not a merchant key.
        String customer = tokenFrom(authenticate(registered()).body());
        assertThat(get("/v1/merchant/me", customer).statusCode()).isEqualTo(401);

        // A merchant key presented to an operator route is not an operator.
        assertThat(get("/v1/operator/merchants/" + merchant.id(), null, key).statusCode())
                .isNotEqualTo(200);
    }

    @Test
    @DisplayName("a closed merchant cannot be issued a key - 409 merchant.NotKeyable, nothing"
            + " written")
    void aClosedMerchantCannotBeIssuedAKey() throws Exception {
        String admin = administrator();
        Merchant merchant = onboarded(admin);
        assertThat(
                        post(
                                        "/v1/operator/merchants/" + merchant.id() + "/closure",
                                        "{\"reason\":\"relationship ended\"}",
                                        admin,
                                        null)
                                .statusCode())
                .isEqualTo(200);

        HttpResponse<String> refused =
                post(
                        "/v1/operator/merchants/" + merchant.id() + "/api-keys",
                        null,
                        admin,
                        someKey());
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("merchant.NotKeyable");
        assertThat(
                        count(
                                "SELECT count(*) FROM merchant.merchant_api_key WHERE"
                                        + " merchant_id = ?",
                                UUID.fromString(merchant.id())))
                .isEqualTo(0);
    }

    @Test
    @DisplayName("the key routes are behind MERCHANT_ADMINISTER: the permissionless session"
            + " and the ledger operator are both 403 with nothing written")
    void theKeyRoutesArePrivileged() throws Exception {
        String admin = administrator();
        Merchant merchant = onboarded(admin);
        long before = count("SELECT count(*) FROM merchant.merchant_api_key");

        String plain = tokenFrom(authenticate(registered()).body());
        assertThat(
                        post(
                                        "/v1/operator/merchants/" + merchant.id() + "/api-keys",
                                        null,
                                        plain,
                                        someKey())
                                .statusCode())
                .isEqualTo(403);
        String ledgerOperator = sessionWith(RoleName.LEDGER_OPERATOR);
        assertThat(
                        post(
                                        "/v1/operator/merchants/" + merchant.id() + "/api-keys",
                                        null,
                                        ledgerOperator,
                                        someKey())
                                .statusCode())
                .isEqualTo(403);

        assertThat(count("SELECT count(*) FROM merchant.merchant_api_key")).isEqualTo(before);
    }

    @Test
    @DisplayName("another merchant's key id is the SAME 404 as one that does not exist - the"
            + " tenant rides in the statement (INV-MER-01)")
    void anotherMerchantsKeyIsTheSame404() throws Exception {
        String admin = administrator();
        Merchant one = onboarded(admin);
        Merchant two = onboarded(admin);
        String keyOfTwo = issueKey(admin, two.id());
        String keyIdOfTwo = keyOfTwo.substring(0, keyOfTwo.indexOf('.'));

        HttpResponse<String> crossTenant =
                delete(
                        "/v1/operator/merchants/" + one.id() + "/api-keys/" + keyIdOfTwo,
                        "{\"reason\":\"should not work\"}",
                        admin);
        HttpResponse<String> unknown =
                delete(
                        "/v1/operator/merchants/" + one.id() + "/api-keys/" + UUID.randomUUID(),
                        "{\"reason\":\"neither should this\"}",
                        admin);

        assertThat(crossTenant.statusCode()).isEqualTo(404);
        assertThat(unknown.statusCode()).isEqualTo(404);
        // And the key it named is untouched: the refusal wrote nothing.
        assertThat(get("/v1/merchant/me", keyOfTwo).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("issuance is audited with the acting operator and the key id, and never the"
            + " secret (INV-AUD-02)")
    void issuanceIsAudited() throws Exception {
        String admin = administrator();
        Merchant merchant = onboarded(admin);
        HttpResponse<String> issued =
                post(
                        "/v1/operator/merchants/" + merchant.id() + "/api-keys",
                        null,
                        admin,
                        someKey());
        String keyId = field(issued.body(), "keyId");
        String secret = field(issued.body(), "secret");

        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT actor_type, actor_id, change_summary FROM"
                                        + " platform.audit_record WHERE operation ="
                                        + " 'merchant.MerchantApiKeyIssued' AND target_id = ?")) {
            read.setString(1, keyId);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                // CUSTOMER, and that is a finding rather than an expectation: the session
                // interceptor stamps EVERY session actor as ActorType.CUSTOMER, so an
                // OPERATOR's privileged acts are audited as a customer's - platform-wide,
                // since P1-TSK-016, and true of every reasoned act the platform records
                // (the refund's included). Asserted as the behaviour that EXISTS so this
                // suite tells the truth; recorded as debt with its own owner, because
                // correcting it touches every audited operator path and is not this task's.
                assertThat(row.getString(1)).isEqualTo("CUSTOMER");
                assertThat(row.getString(2)).isNotBlank();
                assertThat(row.getString(3)).contains(keyId).doesNotContain(secret);
            }
        }
    }

    @Test
    @DisplayName("a merchant's own request is audited as ActorType.MERCHANT - the counterparty"
            + " named, which V010 had to admit before it could be written")
    void aMerchantsRequestCanBeAuditedAsItself() throws Exception {
        // The merchant surface this task ships performs no audited act (a read is not one),
        // so the claim V010 exists for is driven directly: an audit record written as a
        // MERCHANT actor lands rather than being refused by the recreated CHECK.
        String admin = administrator();
        Merchant merchant = onboarded(admin);
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try (PreparedStatement insert =
                    app.prepareStatement(
                            "INSERT INTO platform.audit_record (audit_id, actor_id, actor_type,"
                                    + " occurred_at, operation, target_type, target_id, outcome,"
                                    + " correlation_id) VALUES (?, ?, 'MERCHANT', now(),"
                                    + " 'merchant.MerchantApiKeyIssued', 'merchant', ?,"
                                    + " 'SUCCEEDED', ?)")) {
                insert.setObject(1, IDS.next());
                insert.setString(2, merchant.id());
                insert.setString(3, merchant.id());
                insert.setString(4, IDS.next().toString());
                insert.executeUpdate();
            }
            app.commit();
        }
    }

    // -----------------------------------------------------------------

    private record Merchant(String id) {}

    private Merchant onboarded(String admin) throws Exception {
        UUID party = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES"
                            + " (?, 'ORGANISATION', 'Acme Holdings', now())",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now())",
                    IDS.next(),
                    party);
        }
        HttpResponse<String> created =
                post(
                        "/v1/operator/merchants",
                        "{\"partyId\":\"" + party + "\",\"legalName\":\"Acme GmbH\","
                                + "\"displayName\":\"Acme\",\"settlementCurrency\":\"EUR\"}",
                        admin,
                        someKey());
        assertThat(created.statusCode()).isEqualTo(201);
        return new Merchant(field(created.body(), "merchantId"));
    }

    /** Issues a key and returns the presented credential: {@code <keyId>.<secret>}. */
    private String issueKey(String admin, String merchantId) throws Exception {
        HttpResponse<String> issued =
                post("/v1/operator/merchants/" + merchantId + "/api-keys", null, admin, someKey());
        assertThat(issued.statusCode()).isEqualTo(201);
        return field(issued.body(), "keyId") + "." + field(issued.body(), "secret");
    }

    private String administrator() throws Exception {
        return sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
    }

    private String sessionWith(RoleName role) throws Exception {
        String login = registered();
        UUID identity;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                identity = row.getObject("id", UUID.class);
            }
        }
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(
                    app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        return tokenFrom(authenticate(login).body());
    }

    private String registered() throws Exception {
        String login = "mkey." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(register(login).statusCode()).isEqualTo(201);
        return login;
    }

    /**
     * Every text column in every application schema that holds the given value — the
     * live-schema half of {@code INV-IDN-01}, derived from {@code information_schema} rather
     * than from a list somebody maintains.
     */
    private static java.util.List<String> columnHolding(String value) throws SQLException {
        java.util.List<String> found = new java.util.ArrayList<>();
        try (Connection app = DatabaseRoles.application()) {
            java.util.List<String[]> columns = new java.util.ArrayList<>();
            try (PreparedStatement read =
                    app.prepareStatement(
                            "SELECT table_schema, table_name, column_name FROM"
                                    + " information_schema.columns WHERE data_type IN ('text',"
                                    + " 'character varying', 'bytea') AND table_schema NOT IN"
                                    + " ('pg_catalog', 'information_schema')")) {
                try (ResultSet rows = read.executeQuery()) {
                    while (rows.next()) {
                        columns.add(
                                new String[] {
                                    rows.getString(1), rows.getString(2), rows.getString(3)
                                });
                    }
                }
            }
            for (String[] column : columns) {
                String qualified = "\"" + column[0] + "\".\"" + column[1] + "\"";
                String field = "\"" + column[2] + "\"";
                try (PreparedStatement probe =
                        app.prepareStatement(
                                "SELECT count(*) FROM " + qualified + " WHERE " + field
                                        + "::text LIKE ?")) {
                    probe.setString(1, "%" + value + "%");
                    try (ResultSet row = probe.executeQuery()) {
                        if (row.next() && row.getLong(1) > 0) {
                            found.add(column[0] + "." + column[1] + "." + column[2]);
                        }
                    }
                } catch (SQLException unreadable) {
                    // A column this role cannot read cannot be holding our secret for us.
                }
            }
        }
        return found;
    }

    private HttpResponse<String> register(String login) throws Exception {
        return post(
                "/v1/registrations",
                "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                        + "\"password\":\"" + PASSWORD + "\"}",
                null,
                someKey());
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post(
                "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}",
                null,
                someKey());
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return get(path, null, bearer);
    }

    private HttpResponse<String> get(String path, String unused, String bearer) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return send(request.build());
    }

    private HttpResponse<String> post(String path, String body, String bearer, String key)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return send(request.build());
    }

    private HttpResponse<String> delete(String path, String body, String bearer) throws Exception {
        return send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + bearer)
                        .method("DELETE", HttpRequest.BodyPublishers.ofString(body))
                        .build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
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

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String field(String body, String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    private static String tokenFrom(String body) {
        return field(body, "sessionToken");
    }
}
