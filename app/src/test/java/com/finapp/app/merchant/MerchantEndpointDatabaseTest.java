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
 * The merchant operator surface over real HTTP (`P6-TSK-003`): the privileged boundary — the
 * permissionless 403 with nothing written, the WRONG population's 403 (a ledger operator is
 * not a merchant administrator — the pairwise split over HTTP, {@code INV-AUD-03}), the
 * onboarding 201 with its replay, the refusal codes, the required reason, and the one 404.
 * The money-and-books proofs are {@code MerchantOnboardingDatabaseTest}'s and are cited, not
 * repeated.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the merchant operator endpoints (P6-TSK-003)")
class MerchantEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    // -----------------------------------------------------------------

    @Test
    @DisplayName("the permissionless session is 403 with NOTHING written; the ledger operator"
            + " is refused identically - two populations, split over HTTP")
    void theWrongPopulationsAreRefusedWithNothingWritten() throws Exception {
        long merchants = count("SELECT count(*) FROM merchant.merchant");
        String body = onboardBody(verifiedOrganisation());

        String plain = tokenFrom(authenticate(registered()).body());
        assertThat(post("/v1/operator/merchants", body, plain, someKey()).statusCode())
                .isEqualTo(403);

        String ledgerOperator = sessionWith(RoleName.LEDGER_OPERATOR);
        assertThat(post("/v1/operator/merchants", body, ledgerOperator, someKey()).statusCode())
                .as("operating the money is not administering counterparties")
                .isEqualTo(403);

        assertThat(count("SELECT count(*) FROM merchant.merchant")).isEqualTo(merchants);
    }

    @Test
    @DisplayName("the administrator onboards over HTTP - 201, and the same key replays the"
            + " same merchant with this same 201")
    void theAdministratorOnboardsAndTheKeyReplays() throws Exception {
        String admin = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        String body = onboardBody(verifiedOrganisation());
        String key = someKey();

        HttpResponse<String> created = post("/v1/operator/merchants", body, admin, key);
        assertThat(created.statusCode()).isEqualTo(201);
        String merchantId = field(created.body(), "merchantId");
        assertThat(field(created.body(), "status")).isEqualTo("ACTIVE");

        HttpResponse<String> replayed = post("/v1/operator/merchants", body, admin, key);
        assertThat(replayed.statusCode()).isEqualTo(201);
        assertThat(field(replayed.body(), "merchantId")).isEqualTo(merchantId);
        assertThat(
                        count(
                                "SELECT count(*) FROM merchant.merchant WHERE id = ?",
                                UUID.fromString(merchantId)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the ineligible party is one 422 merchant.NotEligible, causes conflated,"
            + " nothing written")
    void theIneligiblePartyIsOne422() throws Exception {
        String admin = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        long merchants = count("SELECT count(*) FROM merchant.merchant");

        for (String body :
                new String[] {
                    onboardBody(verifiedPerson()), onboardBody(UUID.randomUUID())
                }) {
            HttpResponse<String> refused =
                    post("/v1/operator/merchants", body, admin, someKey());
            assertThat(refused.statusCode()).isEqualTo(422);
            assertThat(refused.body()).contains("merchant.NotEligible");
        }
        assertThat(count("SELECT count(*) FROM merchant.merchant")).isEqualTo(merchants);
    }

    @Test
    @DisplayName("suspension demands its reason - blank is the boundary's 400; with one, the"
            + " move lands and the illegal successor is 409 merchant.IllegalTransition")
    void theStandingMovesDemandReasonsAndTheMachineRules() throws Exception {
        String admin = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        HttpResponse<String> created =
                post(
                        "/v1/operator/merchants",
                        onboardBody(verifiedOrganisation()),
                        admin,
                        someKey());
        String merchantId = field(created.body(), "merchantId");

        assertThat(
                        post(
                                        "/v1/operator/merchants/" + merchantId + "/suspension",
                                        "{\"reason\":\"  \"}",
                                        admin,
                                        null)
                                .statusCode())
                .as("a judgement without its reasoning is refused at the boundary - the"
                        + " platform's validation refusal shape")
                .isEqualTo(422);

        HttpResponse<String> suspended =
                post(
                        "/v1/operator/merchants/" + merchantId + "/suspension",
                        "{\"reason\":\"chargeback ratio breached\"}",
                        admin,
                        null);
        assertThat(suspended.statusCode()).isEqualTo(200);
        assertThat(field(suspended.body(), "status")).isEqualTo("SUSPENDED");

        HttpResponse<String> refused =
                post(
                        "/v1/operator/merchants/" + merchantId + "/closure",
                        "{\"reason\":\"tidy up\"}",
                        admin,
                        null);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("merchant.IllegalTransition");
    }

    @Test
    @DisplayName("unknown and malformed identifiers are one 404 on every route")
    void unknownAndMalformedAreOne404() throws Exception {
        String admin = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        HttpResponse<String> unknown =
                get("/v1/operator/merchants/" + UUID.randomUUID(), admin);
        HttpResponse<String> malformed = get("/v1/operator/merchants/not-a-uuid", admin);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(malformed.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("api.NotFound");
        assertThat(malformed.body()).contains("api.NotFound");
    }

    @Test
    @DisplayName("the onboarding without its idempotency key is refused before anything runs")
    void theOnboardingDemandsItsKey() throws Exception {
        String admin = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        HttpResponse<String> refused =
                post("/v1/operator/merchants", onboardBody(verifiedOrganisation()), admin, null);
        // The interceptor's refusal is the platform's validation shape, before the handler
        // runs - and the merchant count standing still in the first test proves the writes.
        assertThat(refused.statusCode()).isEqualTo(422);
    }

    // -----------------------------------------------------------------

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
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        return login;
    }

    private static String onboardBody(UUID party) {
        return "{\"partyId\":\"" + party + "\",\"legalName\":\"Acme GmbH\","
                + "\"displayName\":\"Acme\",\"settlementCurrency\":\"EUR\"}";
    }

    private static UUID verifiedOrganisation() throws SQLException {
        return partyWithCustomer("ORGANISATION", "ACTIVE");
    }

    private static UUID verifiedPerson() throws SQLException {
        return partyWithCustomer("PERSON", "ACTIVE");
    }

    private static UUID partyWithCustomer(String kind, String status) throws SQLException {
        UUID party = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES"
                            + " (?, '" + kind + "', 'Acme Holdings', now())",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, '" + status + "',"
                            + " now() - interval '1 hour', now())",
                    IDS.next(),
                    party);
        }
        return party;
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

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build());
    }

    private HttpResponse<String> post(String path, String body, String token, String key)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return send(request.build());
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

    private static String someLogin() {
        return "merop." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
