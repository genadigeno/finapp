package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.paymentmethods.SimulatedTokenisationAdapter;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The refund surface over real HTTP (`P5-TSK-015`): the privileged handler's boundary — the
 * permissionless session's 403 <strong>with nothing written</strong> (the accept), the
 * operator's 201, the disclosure fold, the refusal codes, the required key and reason. The
 * money proofs are {@code PaymentRefundDatabaseTest}'s and are cited, not repeated.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the refund endpoint (P5-TSK-015)")
class PaymentRefundEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";

    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @BeforeAll
    static void startProvider() {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.paymentmethods.tokenisation.url", () -> provider.baseUrl());
        registry.add("finapp.paymentmethods.tokenisation.timeout", () -> "PT0.7S");
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add("finapp.payments.provider.timeout", () -> "PT0.7S");
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("the permissionless session is 403 with NOTHING written - no refund row, no"
            + " hold (the accept's negative)")
    void thePermissionlessSessionIsRefusedWithNothingWritten() throws Exception {
        String customer = verifiedCustomer(someLogin());
        String paymentId = succeededPayment(customer);
        long refundsBefore = totalRefundCount();
        long holdsBefore = totalHoldCount();

        HttpResponse<String> refused =
                refund(customer, paymentId, "5.00", "EUR", "not an operator", someKey());

        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(totalRefundCount()).isEqualTo(refundsBefore);
        assertThat(totalHoldCount()).isEqualTo(holdsBefore);
    }

    @Test
    @DisplayName("the operator refunds: 201 with the honest state, the reason required, the"
            + " key required, the codes named")
    void theOperatorRefundsAndTheRefusalsAreNamed() throws Exception {
        String customer = verifiedCustomer(someLogin());
        String paymentId = succeededPayment(customer);
        Operator operator = operatorSession();
        provider.succeedsWith(
                SimulatedCardPspAdapter.REFUNDS_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"psp_ep-rfd-" + suffix() + "\"}");

        // The operator's 201, the honest state in the body.
        HttpResponse<String> refunded =
                refund(operator.token(), paymentId, "3.00", "EUR",
                        "customer complaint upheld", someKey());
        assertThat(refunded.statusCode()).isEqualTo(201);
        assertThat(field(refunded.body(), "status")).isEqualTo("COMPLETED");

        // The trail: ONE dispatch record naming the OPERATOR with the required reason
        // verbatim (INV-AUD-03, the reversal precedent) - the outcome application that
        // follows is the platform's and is not this record.
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT actor_id, reason FROM platform.audit_record"
                                        + " WHERE operation ="
                                        + " 'payments.PaymentRefundDispatched'"
                                        + " AND target_id = ?")) {
            read.setString(1, paymentId);
            try (ResultSet rows = read.executeQuery()) {
                assertThat(rows.next()).as("one dispatch record").isTrue();
                assertThat(rows.getString("actor_id"))
                        .isEqualTo(operator.identity().toString());
                assertThat(rows.getString("reason")).isEqualTo("customer complaint upheld");
                assertThat(rows.next()).as("and only one").isFalse();
            }
        }

        // The bound's 422, named.
        HttpResponse<String> over =
                refund(operator.token(), paymentId, "100.00", "EUR", "too much", someKey());
        assertThat(over.statusCode()).isEqualTo(422);
        assertThat(over.body()).contains("payments.RefundExceedsCaptured");

        // One 404 across unknown and malformed - the operator names a URL.
        assertThat(refund(operator.token(), UUID.randomUUID().toString(), "1.00", "EUR", "x", someKey())
                .statusCode())
                .isEqualTo(404);
        assertThat(refund(operator.token(), "not-a-uuid", "1.00", "EUR", "x", someKey()).statusCode())
                .isEqualTo(404);

        // The keyless 422 and the blank-reason 422.
        assertThat(refund(operator.token(), paymentId, "1.00", "EUR", "keyless", null).statusCode())
                .isEqualTo(422);
        HttpResponse<String> blank = refund(operator.token(), paymentId, "1.00", "EUR", " ", someKey());
        assertThat(blank.statusCode()).isEqualTo(422);
        assertThat(blank.body()).contains("api.ValidationFailed");
    }

    @Test
    @DisplayName("an uncaptured payment answers the named 409 - payments.NotRefundable")
    void anUncapturedPaymentAnswersNotRefundable() throws Exception {
        String customer = verifiedCustomer(someLogin());
        // Created but never confirmed: no captured attempt exists.
        String methodId = attachInstrument(customer);
        openAccount(customer);
        HttpResponse<String> created =
                post(
                        "/v1/payments",
                        "{\"paymentMethodId\":\"" + methodId
                                + "\",\"amount\":\"5.00\",\"currency\":\"EUR\"}",
                        customer,
                        true);
        String paymentId = field(created.body(), "id");
        Operator operator = operatorSession();

        HttpResponse<String> refused =
                refund(operator.token(), paymentId, "1.00", "EUR", "premature", someKey());

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("payments.NotRefundable");
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    /** An operator's session token and identity, granted through the real service. */
    private record Operator(String token, UUID identity) {}

    private Operator operatorSession() throws Exception {
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
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
                    app,
                    IdentityId.of(identity),
                    RoleName.LEDGER_OPERATOR,
                    IdentityId.of(identity),
                    "test fixture");
            app.commit();
        }
        return new Operator(tokenFrom(authenticate(login).body()), identity);
    }

    /** The full customer chain over HTTP to a SUCCEEDED payment of 12.00 EUR. */
    private String succeededPayment(String token) throws Exception {
        openAccount(token);
        String methodId = attachInstrument(token);
        provider.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"psp_ea-" + suffix() + "\"}");
        provider.succeedsWith(
                SimulatedCardPspAdapter.CAPTURES_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"psp_ec-" + suffix() + "\"}");
        HttpResponse<String> created =
                post(
                        "/v1/payments",
                        "{\"paymentMethodId\":\"" + methodId
                                + "\",\"amount\":\"12.00\",\"currency\":\"EUR\"}",
                        token,
                        true);
        String paymentId = field(created.body(), "id");
        HttpResponse<String> confirmed =
                post("/v1/payments/" + paymentId + "/confirmation", null, token, false);
        assertThat(field(confirmed.body(), "status")).isEqualTo("SUCCEEDED");
        return paymentId;
    }

    private String verifiedCustomer(String login) throws Exception {
        assertThat(register(login).statusCode()).isEqualTo(201);
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "UPDATE party.customer SET status = 'ACTIVE',"
                            + " status_changed_at = GREATEST(now(), opened_at)"
                            + " WHERE party_id ="
                            + " (SELECT party_id FROM identity.identity"
                            + "   WHERE login_identifier = ?)",
                    login);
        }
        return tokenFrom(authenticate(login).body());
    }

    private String openAccount(String token) throws Exception {
        HttpResponse<String> opened =
                post(
                        "/v1/me/accounts",
                        "{\"productType\":\"WALLET\",\"currency\":\"EUR\"}",
                        token,
                        true);
        assertThat(opened.statusCode()).isEqualTo(201);
        return field(opened.body(), "id");
    }

    private String attachInstrument(String token) throws Exception {
        String suffix = suffix();
        provider.succeedsWith(
                SimulatedTokenisationAdapter.TOKENISATIONS_PATH,
                200,
                "{\"status\":\"tokenised\",\"token\":\"tok_" + suffix + "\",\"brand\":\"Visa\","
                        + "\"last4\":\"4242\",\"expiryMonth\":12,\"expiryYear\":2030}");
        HttpResponse<String> attached =
                post(
                        "/v1/me/payment-methods",
                        "{\"clientToken\":\"ctok_" + suffix + "\"}",
                        token,
                        false);
        assertThat(attached.statusCode()).isEqualTo(201);
        return field(attached.body(), "id");
    }

    // -----------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------

    private HttpResponse<String> refund(
            String token,
            String paymentId,
            String amount,
            String currency,
            String reason,
            String idempotencyKey)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        "http://localhost:" + port + "/v1/payments/" + paymentId
                                                + "/refund"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"amount\":\"" + amount + "\",\"currency\":\""
                                                + currency + "\",\"reason\":\"" + reason
                                                + "\"}"));
        if (idempotencyKey != null) {
            request.header(IdempotencyKeyHeader.NAME, idempotencyKey);
        }
        return send(request.build());
    }

    private HttpResponse<String> register(String login) throws Exception {
        return post(
                "/v1/registrations",
                "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                        + "\"password\":\"" + PASSWORD + "\"}",
                null,
                true);
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post(
                "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}",
                null,
                false);
    }

    private HttpResponse<String> post(
            String path, String body, String token, boolean idempotencyKey) throws Exception {
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
        if (idempotencyKey) {
            request.header(IdempotencyKeyHeader.NAME, someKey());
        }
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    // -----------------------------------------------------------------

    private static long totalRefundCount() throws SQLException {
        return count("SELECT count(*) FROM payments.refund");
    }

    private static long totalHoldCount() throws SQLException {
        return count("SELECT count(*) FROM ledger.hold");
    }

    private static long count(String sql) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql);
                ResultSet row = read.executeQuery()) {
            assertThat(row.next()).isTrue();
            return row.getLong(1);
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

    private static String someLogin() {
        return "refop." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
