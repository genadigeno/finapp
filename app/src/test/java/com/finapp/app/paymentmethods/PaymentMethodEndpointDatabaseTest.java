package com.finapp.app.paymentmethods;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authenticator;
import com.finapp.identity.TotpParameters;
import com.finapp.paymentmethods.SimulatedTokenisationAdapter;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.security.Sensitive;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The payment-method surface over real HTTP (`P5-TSK-005`): attach through the tokenisation
 * exchange under the conditional step-up, list, detach — with the provider a
 * {@link SimulatedProvider} wired through the same property a deployment would use, so the
 * whole deployed chain is on the path (controller → service → exchange → store).
 *
 * <p>The step-up's positive control drives the <strong>whole</strong> MFA flow over HTTP —
 * register, log in, enrol, confirm, challenge — rather than seeding an elevated session (the
 * {@code BeneficiaryEndpointDatabaseTest} idiom, and the `P1-TSK-027` lesson that two green
 * halves compose only when something drives them together).
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the payment-method endpoints and the step-up point (P5-TSK-005)")
class PaymentMethodEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final String PASSWORD = "a-perfectly-fine-pw-7";

    private static SimulatedProvider provider;

    @LocalServerPort private int port;

    @BeforeAll
    static void startProvider() {
        // @DynamicPropertySource runs during context preparation, BEFORE @BeforeAll, so the
        // harness is usually already started there; this only covers a re-used context.
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
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    /** Stubs the exchange to tokenise onto {@code token} — the grant-idempotent provider. */
    private static void providerTokenises(String token) {
        provider.succeedsWith(
                SimulatedTokenisationAdapter.TOKENISATIONS_PATH,
                200,
                "{\"status\":\"tokenised\",\"token\":\"" + token + "\",\"brand\":\"Visa\","
                        + "\"last4\":\"4242\",\"expiryMonth\":12,\"expiryYear\":2030}");
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("the acceptance chain holds: attach 201 with provider-sourced display metadata,"
            + " listed, detached with the row surviving as evidence, the repeat converging")
    void theAttachChainHolds() throws Exception {
        providerTokenises("tok_chain-" + suffix());
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        String token = tokenFrom(authenticate(login).body());
        UUID party = partyOf(login);

        HttpResponse<String> attached = attach(token, "ctok_chain-" + suffix());
        assertThat(attached.statusCode()).isEqualTo(201);
        String methodId = field(attached.body(), "id");
        // Display metadata is the PROVIDER's answer, never the client's: the body carried only
        // the grant, and brand/last4/expiry all came back from the exchange.
        assertThat(field(attached.body(), "brand")).isEqualTo("Visa");
        assertThat(field(attached.body(), "displaySuffix")).isEqualTo("4242");
        assertThat(attached.body()).contains("\"expiryMonth\":12").contains("\"expiryYear\":2030");
        // And the token is in NO response (INV-AUD-02 at the surface).
        assertThat(attached.body()).doesNotContain("tok_chain");

        HttpResponse<String> listed = get("/v1/me/payment-methods", token);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains(methodId);

        assertThat(delete(token, methodId).statusCode()).isEqualTo(204);
        assertThat(get("/v1/me/payment-methods", token).body())
                .as("a detached instrument leaves the list")
                .doesNotContain(methodId);
        assertThat(statusOf(methodId))
                .as("the detached row survives as evidence (P5-TSK-004)")
                .isEqualTo("DETACHED");

        // The converging repeat: 204, and the detach stays ONE act.
        assertThat(delete(token, methodId).statusCode()).isEqualTo(204);
        assertThat(auditRecords("paymentmethods.PaymentMethodDetached", methodId)).hasSize(1);
        assertThat(outboxEvents("paymentmethods.PaymentMethodDetached", methodId)).isEqualTo(1);
        assertThat(paymentMethodRowsOf(party)).containsExactly("DETACHED");
    }

    @Test
    @DisplayName("an enrolled identity is refused at PASSWORD with nothing written and no"
            + " exchange, and succeeds at MULTI_FACTOR, audited as the person")
    void theStepUpGateHoldsAtAttach() throws Exception {
        providerTokenises("tok_stepup-" + suffix());
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        String passwordToken = tokenFrom(authenticate(login).body());
        Sensitive<String> secret = enrolAndConfirm(passwordToken);
        UUID party = partyOf(login);

        HttpResponse<String> refused = attach(passwordToken, "ctok_stepup-" + suffix());
        assertThat(refused.statusCode())
                .as("an MFA-enrolled identity must present a MULTI_FACTOR session")
                .isEqualTo(403);
        assertThat(refused.body())
                .as("actionable and distinct: step up and retry (P1-TSK-018)")
                .contains("identity.AssuranceRequired");
        assertThat(paymentMethodRowsOf(party)).as("the refusal writes nothing").isEmpty();
        assertThat(provider.requestCount(SimulatedTokenisationAdapter.TOKENISATIONS_PATH))
                .as("the refusal costs no exchange - the fail-fast check runs before it")
                .isZero();

        // The positive control: prove the factor, retry, created - and audited as the PERSON.
        String elevated =
                tokenFrom(
                        post(
                                        "/v1/authentications/mfa",
                                        "{\"code\":\"" + codeNow(secret) + "\"}",
                                        passwordToken)
                                .body());
        HttpResponse<String> created = attach(elevated, "ctok_stepup-" + suffix());
        assertThat(created.statusCode()).isEqualTo(201);
        String methodId = field(created.body(), "id");
        assertThat(paymentMethodRowsOf(party)).containsExactly("ACTIVE");

        List<AuditRow> added = auditRecords("paymentmethods.PaymentMethodAttached", methodId);
        assertThat(added).hasSize(1);
        assertThat(added.getFirst().actorId())
                .as("the person's own act, never the platform's")
                .isEqualTo(identityOf(login).toString());
        assertThat(added.getFirst().changeSummary())
                .as("the record carries NO summary - the id is the target and there is nothing"
                        + " else the trail may say (no token, no display metadata: INV-AUD-02)")
                .isNull();
    }

    @Test
    @DisplayName("a retried attach converges: the same grant lands on one row, one audit"
            + " record, one event")
    void aRetriedAttachConverges() throws Exception {
        // The provider is grant-idempotent (the stub answers the same token every time), so
        // the lost-response retry re-exchanges onto the same (party, token) slot.
        providerTokenises("tok_converge-" + suffix());
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        String token = tokenFrom(authenticate(login).body());
        UUID party = partyOf(login);
        String grant = "ctok_converge-" + suffix();

        HttpResponse<String> first = attach(token, grant);
        assertThat(first.statusCode()).isEqualTo(201);
        String methodId = field(first.body(), "id");

        HttpResponse<String> retried = attach(token, grant);
        assertThat(retried.statusCode()).as("201 either way - the convergence idiom").isEqualTo(201);
        assertThat(field(retried.body(), "id")).isEqualTo(methodId);

        assertThat(paymentMethodRowsOf(party)).containsExactly("ACTIVE");
        assertThat(auditRecords("paymentmethods.PaymentMethodAttached", methodId))
                .as("only the creating call is an act")
                .hasSize(1);
        assertThat(outboxEvents("paymentmethods.PaymentMethodAttached", methodId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a provider outage is the honest 503 with nothing written")
    void aProviderOutageIsA503WithNothingWritten() throws Exception {
        provider.isUnavailable(SimulatedTokenisationAdapter.TOKENISATIONS_PATH);
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        String token = tokenFrom(authenticate(login).body());

        HttpResponse<String> failed = attach(token, "ctok_outage-" + suffix());
        assertThat(failed.statusCode()).isEqualTo(503);
        assertThat(failed.body()).contains("paymentmethods.TokenisationUnavailable");
        assertThat(paymentMethodRowsOf(partyOf(login)))
                .as("a failed exchange writes nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("a refused grant is the actionable 422 with nothing written")
    void aRefusedGrantIsA422WithNothingWritten() throws Exception {
        provider.succeedsWith(
                SimulatedTokenisationAdapter.TOKENISATIONS_PATH, 200, "{\"status\":\"refused\"}");
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        String token = tokenFrom(authenticate(login).body());

        HttpResponse<String> refused = attach(token, "ctok_refused-" + suffix());
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("paymentmethods.InstrumentNotTokenised");
        assertThat(paymentMethodRowsOf(partyOf(login))).isEmpty();
    }

    @Test
    @DisplayName("a card-number-shaped clientToken is refused at the boundary, before any"
            + " exchange - INV-PAY-02 at the surface")
    void aCardNumberShapedClientTokenIsRefusedBeforeAnyExchange() throws Exception {
        providerTokenises("tok_pan-" + suffix());
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        String token = tokenFrom(authenticate(login).body());

        for (String pan : new String[] {"4111111111111111", "4111-1111-1111-1111"}) {
            HttpResponse<String> refused = attach(token, pan);
            assertThat(refused.statusCode()).isEqualTo(422);
            assertThat(refused.body())
                    .contains("api.ValidationFailed")
                    .as("the refusal names the field and never the value")
                    .contains("clientToken")
                    .doesNotContain(pan);
        }
        assertThat(provider.requestCount(SimulatedTokenisationAdapter.TOKENISATIONS_PATH))
                .as("a card number never reaches the wire")
                .isZero();
        assertThat(paymentMethodRowsOf(partyOf(login))).isEmpty();
    }

    @Test
    @DisplayName("a stranger's, an unknown and a malformed identifier are one 404 on DELETE")
    void aStrangersPaymentMethodIdIsOne404OnDelete() throws Exception {
        providerTokenises("tok_owner-" + suffix());
        String ownerLogin = someLogin();
        assertThat(register(ownerLogin).statusCode()).isEqualTo(201);
        String ownerToken = tokenFrom(authenticate(ownerLogin).body());
        String methodId = field(attach(ownerToken, "ctok_owner-" + suffix()).body(), "id");

        String strangerLogin = someLogin();
        assertThat(register(strangerLogin).statusCode()).isEqualTo(201);
        String strangerToken = tokenFrom(authenticate(strangerLogin).body());

        HttpResponse<String> strangers = delete(strangerToken, methodId);
        HttpResponse<String> unknown = delete(strangerToken, UUID.randomUUID().toString());
        HttpResponse<String> malformed = delete(strangerToken, "not-an-identifier");

        assertThat(strangers.statusCode()).isEqualTo(404);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(malformed.statusCode()).isEqualTo(404);
        // One answer, asserted as an equality between the causes - the endpoint is no oracle
        // over anybody else's instruments (INV-IDN-07's reasoning at a resource).
        assertThat(normalized(strangers.body()))
                .isEqualTo(normalized(unknown.body()))
                .isEqualTo(normalized(malformed.body()));

        assertThat(statusOf(methodId))
                .as("the stranger's attempt moved nothing")
                .isEqualTo("ACTIVE");
        assertThat(auditRecords("paymentmethods.PaymentMethodDetached", methodId)).isEmpty();
    }

    @Test
    @DisplayName("no attach body shape is our 500")
    void noBodyShapeIsOur500() throws Exception {
        providerTokenises("tok_shapes-" + suffix());
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        String token = tokenFrom(authenticate(login).body());

        String[] shapes = {
            "{}",
            "{\"clientToken\":null}",
            "{\"clientToken\":\"\"}",
            "{\"clientToken\":\"has spaces\"}",
            "{\"clientToken\":{\"nested\":true}}",
            "{\"clientToken\":[\"a\"]}",
            "{\"clientToken\":\"" + "a".repeat(200) + "\"}",
            "not json at all",
        };
        for (String shape : shapes) {
            HttpResponse<String> answer = post("/v1/me/payment-methods", shape, token);
            assertThat(answer.statusCode())
                    .as("shape %s must be the caller's 4xx, never our 500", shape)
                    .isBetween(400, 499);
        }
        assertThat(paymentMethodRowsOf(partyOf(login))).isEmpty();
    }

    // -----------------------------------------------------------------
    // MFA (the BeneficiaryEndpointDatabaseTest idiom)
    // -----------------------------------------------------------------

    /** Enrols and confirms a TOTP factor over the real endpoints; returns the secret. */
    private Sensitive<String> enrolAndConfirm(String sessionToken) throws Exception {
        Sensitive<String> secret =
                Sensitive.of(secretFrom(post("/v1/me/mfa", null, sessionToken).body()));
        // The PREVIOUS step's code: confirmation consumes its step (P1-TSK-018), and the
        // challenge that follows uses the current one.
        String confirming =
                Authenticator.codeAt(
                        secret,
                        TotpParameters.current(),
                        Instant.ofEpochSecond(
                                (currentStep() - 1) * TotpParameters.current().periodSeconds()));
        assertThat(
                        post(
                                        "/v1/me/mfa/confirmation",
                                        "{\"code\":\"" + confirming + "\"}",
                                        sessionToken)
                                .statusCode())
                .isEqualTo(204);
        return secret;
    }

    private static String codeNow(Sensitive<String> secret) {
        return Authenticator.codeNow(secret, TotpParameters.current(), CLOCK);
    }

    private static long currentStep() {
        return Instant.now(CLOCK).getEpochSecond() / TotpParameters.current().periodSeconds();
    }

    // -----------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------

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
                null);
    }

    private HttpResponse<String> attach(String token, String clientToken) throws Exception {
        return post(
                "/v1/me/payment-methods",
                "{\"clientToken\":\"" + clientToken + "\"}",
                token);
    }

    private HttpResponse<String> delete(String token, String id) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        "http://localhost:"
                                                + port
                                                + "/v1/me/payment-methods/"
                                                + id))
                        .header("Authorization", "Bearer " + token)
                        .DELETE()
                        .build();
        return send(request);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
        return send(request);
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        return post(path, body, token, false);
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
            request.header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString());
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
    // Parsing and counters
    // -----------------------------------------------------------------

    private static String field(String body, String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    private static String tokenFrom(String body) {
        return field(body, "sessionToken");
    }

    private static String secretFrom(String body) {
        Matcher matcher = Pattern.compile("secret=([A-Z2-7]+)").matcher(body);
        assertThat(matcher.find()).as("the response must carry a base32 secret").isTrue();
        return matcher.group(1);
    }

    /**
     * The correlation identifier differs per request by design, and {@code instance} is the
     * caller's own request path echoed back — a disclosure of nothing the caller did not
     * type. Everything else must be byte-identical.
     */
    private static String normalized(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"normalized\"")
                .replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"normalized\"");
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String someLogin() {
        return "card." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static UUID partyOf(String login) throws SQLException {
        return oneUuid("SELECT party_id FROM identity.identity WHERE login_identifier = ?", login);
    }

    private static UUID identityOf(String login) throws SQLException {
        return oneUuid("SELECT id FROM identity.identity WHERE login_identifier = ?", login);
    }

    private static UUID oneUuid(String sql, String argument) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            read.setString(1, argument);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("the fixture row must exist").isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private static List<String> paymentMethodRowsOf(UUID party) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT status FROM paymentmethods.payment_method"
                                        + " WHERE party_id = ?")) {
            read.setObject(1, party);
            try (ResultSet rows = read.executeQuery()) {
                List<String> statuses = new ArrayList<>();
                while (rows.next()) {
                    statuses.add(rows.getString(1));
                }
                return statuses;
            }
        }
    }

    private static String statusOf(String methodId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT status FROM paymentmethods.payment_method"
                                        + " WHERE id = ?")) {
            read.setObject(1, UUID.fromString(methodId));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private record AuditRow(String actorId, String changeSummary) {}

    private static List<AuditRow> auditRecords(String operation, String targetId)
            throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT actor_id, change_summary FROM platform.audit_record"
                                        + " WHERE operation = ? AND target_id = ?")) {
            read.setString(1, operation);
            read.setString(2, targetId);
            try (ResultSet rows = read.executeQuery()) {
                List<AuditRow> records = new ArrayList<>();
                while (rows.next()) {
                    records.add(new AuditRow(rows.getString(1), rows.getString(2)));
                }
                return records;
            }
        }
    }

    private static int outboxEvents(String eventType, String aggregateId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.outbox_event"
                                        + " WHERE event_type = ? AND aggregate_id = ?")) {
            read.setString(1, eventType);
            read.setObject(2, UUID.fromString(aggregateId));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getInt(1);
            }
        }
    }
}
