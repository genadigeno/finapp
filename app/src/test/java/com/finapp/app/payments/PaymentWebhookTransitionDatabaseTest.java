package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.paymentmethods.SimulatedTokenisationAdapter;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.WebhookSignature;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Webhook-driven transitions over the real chain (`P5-TSK-013`, ADR-0047 §4): each ordering
 * scenario driven to <strong>exactly one effect counted in the tables</strong>, the losers
 * retained as evidence ({@code INV-IDEM-04}, {@code INV-LIFE-04}) — and the headline:
 * {@code INV-LIFE-03}'s question answered by its first resolver, with the webhook-resolved
 * capture posting <strong>exactly once under a ten-way race</strong>.
 *
 * <p>The flows run end to end over HTTP — registration, wallet, attach, create, confirm with
 * the harness's outcome modes — so the healed payment is the CUSTOMER's: the same
 * {@code GET /v1/payments/'{id}'} that honestly said {@code PROCESSING} says
 * {@code SUCCEEDED} after the provider's statement lands, with the balance moved and the
 * entry counted by the attempt-id reference.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("webhook-driven transitions (P5-TSK-013)")
class PaymentWebhookTransitionDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final com.finapp.sharedkernel.id.IdGenerator IDS =
            new com.finapp.sharedkernel.id.IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final byte[] WEBHOOK_KEY =
            "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);

    private static SimulatedProvider provider;

    @LocalServerPort private int port;

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
        registry.add(
                "finapp.payments.webhook.key",
                () -> Base64.getEncoder().encodeToString(WEBHOOK_KEY));
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    // -----------------------------------------------------------------
    // The headline: INV-LIFE-03's question, answered by its first resolver
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a CAPTURE_UNKNOWN heals to the customer's SUCCEEDED with exactly one entry -"
            + " and a duplicate under a fresh event id converges with its evidence retained")
    void aCaptureUnknownHealsWithExactlyOneEntry() throws Exception {
        Flow flow = captureUnknownFlow();

        // The provider's statement: the capture happened.
        assertThat(deliverWebhook(body(someEvent(), flow.captureReference(), "approved",
                        "psp_cap-wh-" + suffix()))
                .statusCode())
                .isEqualTo(204);

        // ONE effect, counted in the tables - and visible to the customer.
        assertThat(attemptStatus(flow.intentId())).isEqualTo("CAPTURED");
        assertThat(field(get("/v1/payments/" + flow.intentId(), flow.token()).body(), "status"))
                .as("the payment that honestly said PROCESSING now says SUCCEEDED")
                .isEqualTo("SUCCEEDED");
        assertThat(get("/v1/me/accounts/" + flow.product() + "/balance", flow.token()).body())
                .contains("\"settled\":\"7.00\"");
        assertThat(entriesByReference(flow.attemptId())).isEqualTo(1);
        assertThat(transitionCount(flow.attemptId(), "CAPTURED")).isEqualTo(1);

        // Duplicate-with-a-fresh-id: past the inbox by design, absorbed by the machine - no
        // second transition, NO SECOND POSTING, the statement retained (INV-IDEM-04).
        long evidenceBefore = evidenceCount(flow.attemptId());
        assertThat(deliverWebhook(body(someEvent(), flow.captureReference(), "approved",
                        "psp_cap-wh2-" + suffix()))
                .statusCode())
                .isEqualTo(204);
        assertThat(entriesByReference(flow.attemptId())).isEqualTo(1);
        assertThat(transitionCount(flow.attemptId(), "CAPTURED")).isEqualTo(1);
        assertThat(evidenceCount(flow.attemptId())).isEqualTo(evidenceBefore + 1);
    }

    @Test
    @DisplayName("ten concurrent webhook resolvers, distinct event ids, one CAPTURE_UNKNOWN:"
            + " one entry, one transition, ten statements retained")
    void tenConcurrentResolversPostExactlyOnce() throws Exception {
        Flow flow = captureUnknownFlow();

        int resolvers = 10;
        CountDownLatch open = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(resolvers);
        try {
            List<Future<Integer>> answers =
                    java.util.stream.IntStream.range(0, resolvers)
                            .mapToObj(
                                    i ->
                                            pool.submit(
                                                    () -> {
                                                        open.await();
                                                        return deliverWebhook(
                                                                        body(
                                                                                someEvent(),
                                                                                flow
                                                                                        .captureReference(),
                                                                                "approved",
                                                                                "psp_cap-race-"
                                                                                        + i))
                                                                .statusCode();
                                                    }))
                            .toList();
            open.countDown();
            for (Future<Integer> answer : answers) {
                assertThat(answer.get()).isEqualTo(204);
            }
        } finally {
            pool.shutdownNow();
        }

        // The race's whole point: one wire fact, one financial effect - the conditional
        // transition's row lock in front, the posting's idempotency claim as the third wall.
        assertThat(entriesByReference(flow.attemptId()))
                .as("the webhook-resolved capture posts EXACTLY once (the accept)")
                .isEqualTo(1);
        assertThat(transitionCount(flow.attemptId(), "CAPTURED")).isEqualTo(1);
        assertThat(evidenceCount(flow.attemptId()))
                .as("ten genuine statements retained (INV-HIST-02)")
                .isEqualTo(10 + 1); // + the confirm's own capture-response evidence
        assertThat(field(get("/v1/payments/" + flow.intentId(), flow.token()).body(), "status"))
                .isEqualTo("SUCCEEDED");
    }

    // -----------------------------------------------------------------
    // The authorization resolutions
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an AUTH_UNKNOWN resolves to AUTHORIZED by webhook, and the client's confirm"
            + " retry converges and finishes the capture - the recovery chain whole")
    void anAuthUnknownResolvesAndTheRetryCompletes() throws Exception {
        Flow flow = authUnknownFlow();

        assertThat(deliverWebhook(body(someEvent(), flow.authReference(), "approved",
                        "psp_auth-wh-" + suffix()))
                .statusCode())
                .isEqualTo(204);
        assertThat(attemptStatus(flow.intentId())).isEqualTo("AUTHORIZED");

        // The client retries its confirm: converges on AUTHORIZED with zero auth wire calls,
        // then the surface chains the capture (P5-TSK-011's recovery meeting this task).
        providerCaptures("psp_cap-after-heal-" + suffix());
        HttpResponse<String> retried = confirm(flow.token(), flow.intentId());
        assertThat(retried.statusCode()).isEqualTo(200);
        assertThat(field(retried.body(), "status")).isEqualTo("SUCCEEDED");
        assertThat(entriesByReference(flow.attemptId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a declined webhook on AUTH_UNKNOWN fails the attempt AND the intent honestly")
    void aDeclinedWebhookFailsBothRows() throws Exception {
        Flow flow = authUnknownFlow();

        assertThat(deliverWebhook(body(someEvent(), flow.authReference(), "declined", null))
                .statusCode())
                .isEqualTo(204);

        assertThat(attemptStatus(flow.intentId())).isEqualTo("FAILED");
        String read = get("/v1/payments/" + flow.intentId(), flow.token()).body();
        assertThat(field(read, "status")).isEqualTo("FAILED");
        assertThat(field(read, "failureReason")).isEqualTo("DECLINED");
        assertThat(entriesByReference(flow.attemptId())).isZero();
    }

    @Test
    @DisplayName("a webhook heals a crash-stranded AUTH_DISPATCHED - the before-the-sync-response"
            + " arrival, the same conditional edge")
    void aWebhookHealsAStrandedDispatch() throws Exception {
        // The crash mid-call, seeded: the dispatch committed, no outcome ever applied.
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        String reference = "auth-stranded-" + UUID.randomUUID();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO payments.payment_intent (id, party_id, customer_id,"
                            + " payment_method_id, wallet_account_id, amount_minor, currency,"
                            + " scale, status, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, 500, 'USD', 2, 'PROCESSING', now())",
                    intent, IDS.next(), IDS.next(), IDS.next(), IDS.next());
            execute(app,
                    "INSERT INTO payments.payment_attempt (id, intent_id, auth_reference,"
                            + " status, created_at)"
                            + " VALUES (?, ?, ?, 'AUTH_DISPATCHED', now())",
                    attempt, intent, reference);
        }

        assertThat(deliverWebhook(body(someEvent(), reference, "approved",
                        "psp_auth-heal-" + suffix()))
                .statusCode())
                .isEqualTo(204);
        assertThat(attemptStatusOf(attempt)).isEqualTo("AUTHORIZED");
    }

    // -----------------------------------------------------------------
    // Order-blindness: the losers are evidence, never errors
    // -----------------------------------------------------------------

    @Test
    @DisplayName("out of order and late: a report on a state its operation cannot move changes"
            + " nothing - on the captured attempt and on the failed one alike (INV-LIFE-04)")
    void lateAndOutOfOrderReportsAreEvidenceOnly() throws Exception {
        // The captured attempt: a LATE authorization report (the out-of-order arrival).
        Flow captured = captureUnknownFlow();
        assertThat(deliverWebhook(body(someEvent(), captured.captureReference(), "approved",
                        "psp_cap-ooo-" + suffix()))
                .statusCode())
                .isEqualTo(204);
        long historyBefore = allTransitionCount(captured.attemptId());
        long evidenceBefore = evidenceCount(captured.attemptId());
        assertThat(deliverWebhook(body(someEvent(), captured.authReference(), "approved",
                        "psp_auth-late-" + suffix()))
                .statusCode())
                .as("acknowledged: we hold this statement - never an error")
                .isEqualTo(204);
        assertThat(attemptStatus(captured.intentId())).isEqualTo("CAPTURED");
        assertThat(allTransitionCount(captured.attemptId())).isEqualTo(historyBefore);
        assertThat(evidenceCount(captured.attemptId())).isEqualTo(evidenceBefore + 1);

        // The failed attempt: any further report is history's business, not the machine's.
        Flow failed = authUnknownFlow();
        deliverWebhook(body(someEvent(), failed.authReference(), "declined", null));
        long failedHistory = allTransitionCount(failed.attemptId());
        assertThat(deliverWebhook(body(someEvent(), failed.authReference(), "approved",
                        "psp_auth-too-late-" + suffix()))
                .statusCode())
                .isEqualTo(204);
        assertThat(attemptStatus(failed.intentId())).isEqualTo("FAILED");
        assertThat(allTransitionCount(failed.attemptId())).isEqualTo(failedHistory);
    }

    @Test
    @DisplayName("the total mapping refuses to act on an unrecognised status and on an approval"
            + " without a usable reference - retained, acknowledged, nothing transitions")
    void unrecognisedAndUnactionableTransitionNothing() throws Exception {
        Flow flow = authUnknownFlow();
        long historyBefore = allTransitionCount(flow.attemptId());

        // The provider saying something we do not map - never success (INV-PAY-03).
        assertThat(deliverWebhook(body(someEvent(), flow.authReference(),
                        "approved_pending_review", "psp-x-" + suffix()))
                .statusCode())
                .isEqualTo(204);
        // An approval the attempt row cannot store is unactionable - not knowledge.
        assertThat(deliverWebhook(body(someEvent(), flow.authReference(), "approved", null))
                .statusCode())
                .isEqualTo(204);

        assertThat(attemptStatus(flow.intentId())).isEqualTo("AUTH_UNKNOWN");
        assertThat(allTransitionCount(flow.attemptId())).isEqualTo(historyBefore);
    }

    // -----------------------------------------------------------------
    // Flows
    // -----------------------------------------------------------------

    /** The whole customer flow with its useful identifiers. */
    private record Flow(
            String token,
            String product,
            String intentId,
            UUID attemptId,
            String authReference,
            String captureReference) {}

    /** create → confirm: auth approved, capture receives-then-loses → CAPTURE_UNKNOWN. */
    private Flow captureUnknownFlow() throws Exception {
        providerAuthorises("psp_auth-" + suffix());
        provider.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.CAPTURES_PATH);
        Flow flow = confirmedFlow("7.00");
        assertThat(attemptStatus(flow.intentId())).isEqualTo("CAPTURE_UNKNOWN");
        return flow;
    }

    /** create → confirm: auth receives-then-loses → AUTH_UNKNOWN. */
    private Flow authUnknownFlow() throws Exception {
        provider.receivesTheRequestThenLosesTheResponse(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH);
        Flow flow = confirmedFlow("4.00");
        assertThat(attemptStatus(flow.intentId())).isEqualTo("AUTH_UNKNOWN");
        return flow;
    }

    private Flow confirmedFlow(String amount) throws Exception {
        String token = verifiedCustomer(someLogin());
        String product = openAccount(token);
        String methodId = attachInstrument(token);
        HttpResponse<String> created =
                post(
                        "/v1/payments",
                        "{\"paymentMethodId\":\"" + methodId + "\",\"amount\":\"" + amount
                                + "\",\"currency\":\"USD\"}",
                        token,
                        true);
        assertThat(created.statusCode()).isEqualTo(201);
        String intentId = field(created.body(), "id");
        assertThat(confirm(token, intentId).statusCode()).isEqualTo(200);
        AttemptRow row = attemptRow(intentId);
        return new Flow(
                token, product, intentId, row.id(), row.authReference(), row.captureReference());
    }

    // -----------------------------------------------------------------
    // Fixtures (the PaymentEndpointDatabaseTest ceremony)
    // -----------------------------------------------------------------

    private String verifiedCustomer(String login) throws Exception {
        assertThat(
                        post(
                                        "/v1/registrations",
                                        "{\"loginIdentifier\":\"" + login
                                                + "\",\"displayName\":\"Ada Lovelace\","
                                                + "\"password\":\"" + PASSWORD + "\"}",
                                        null,
                                        true)
                                .statusCode())
                .isEqualTo(201);
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
        HttpResponse<String> session =
                post(
                        "/v1/authentications",
                        "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD
                                + "\"}",
                        null,
                        false);
        return field(session.body(), "sessionToken");
    }

    private String openAccount(String token) throws Exception {
        HttpResponse<String> opened =
                post(
                        "/v1/me/accounts",
                        "{\"productType\":\"WALLET\",\"currency\":\"USD\"}",
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

    private static void providerAuthorises(String pspReference) {
        provider.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"" + pspReference + "\"}");
    }

    private static void providerCaptures(String pspReference) {
        provider.succeedsWith(
                SimulatedCardPspAdapter.CAPTURES_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"" + pspReference + "\"}");
    }

    // -----------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------

    private static String body(
            String eventId, String operation, String status, String reference) {
        StringBuilder json =
                new StringBuilder("{\"eventId\":\"").append(eventId)
                        .append("\",\"operation\":\"").append(operation)
                        .append("\",\"status\":\"").append(status).append("\"");
        if (reference != null) {
            json.append(",\"reference\":\"").append(reference).append("\"");
        }
        return json.append("}").toString();
    }

    /** A legitimately signed, fresh delivery — the webhook door's own scheme. */
    private HttpResponse<String> deliverWebhook(String body) throws Exception {
        String timestamp = Long.toString(Instant.now(CLOCK).getEpochSecond());
        String signature = hmacHex(timestamp + "." + body);
        HttpRequest request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:" + port
                                                + "/v1/providers/payments/webhooks"))
                        .header("Content-Type", "application/json")
                        .header(WebhookSignature.TIMESTAMP_HEADER, timestamp)
                        .header(WebhookSignature.SIGNATURE_HEADER, signature)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        return send(request);
    }

    private HttpResponse<String> confirm(String token, String paymentId) throws Exception {
        return post("/v1/payments/" + paymentId + "/confirmation", null, token, false);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build());
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

    private static String hmacHex(String signedPayload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(WEBHOOK_KEY, "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is required by every JVM", impossible);
        }
    }

    // -----------------------------------------------------------------
    // Readers
    // -----------------------------------------------------------------

    private record AttemptRow(UUID id, String authReference, String captureReference) {}

    private static AttemptRow attemptRow(String intentId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT id, auth_reference, capture_reference"
                                        + " FROM payments.payment_attempt"
                                        + " WHERE intent_id = ?")) {
            read.setObject(1, UUID.fromString(intentId));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return new AttemptRow(
                        row.getObject("id", UUID.class),
                        row.getString("auth_reference"),
                        row.getString("capture_reference"));
            }
        }
    }

    private static String attemptStatus(String intentId) throws SQLException {
        return oneString(
                "SELECT status FROM payments.payment_attempt WHERE intent_id = ?",
                UUID.fromString(intentId));
    }

    private static String attemptStatusOf(UUID attemptId) throws SQLException {
        return oneString(
                "SELECT status FROM payments.payment_attempt WHERE id = ?", attemptId);
    }

    private static long entriesByReference(UUID attemptId) throws SQLException {
        return count(
                "SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                attemptId.toString());
    }

    private static long transitionCount(UUID attemptId, String to) throws SQLException {
        return count(
                "SELECT count(*) FROM payments.payment_attempt_event"
                        + " WHERE attempt_id = ? AND to_status = ?",
                attemptId,
                to);
    }

    private static long allTransitionCount(UUID attemptId) throws SQLException {
        return count(
                "SELECT count(*) FROM payments.payment_attempt_event WHERE attempt_id = ?",
                attemptId);
    }

    private static long evidenceCount(UUID attemptId) throws SQLException {
        return count(
                "SELECT count(*) FROM payments.provider_evidence WHERE attempt_id = ?",
                attemptId);
    }

    private static String oneString(String sql, Object argument) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            read.setObject(1, argument);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("one row for: %s", sql).isTrue();
                return row.getString(1);
            }
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

    private static String someEvent() {
        return "evt_" + UUID.randomUUID();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String someLogin() {
        return "healer." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String field(String body, String name) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile(
                                "\"" + java.util.regex.Pattern.quote(name) + "\":\"([^\"]+)\"")
                        .matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }
}
