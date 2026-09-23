package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.paymentmethods.SimulatedTokenisationAdapter;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * The payment surface over real HTTP (`P5-TSK-011`): the phase's acceptance chain — attach,
 * create, confirm, the simulated provider authorises and captures, the wallet balance moves,
 * the statement shows the entry, the chain walked by identifier.
 *
 * <p>The concurrency proofs are the commands' and are <strong>cited, not repeated</strong>
 * (the `P1-TSK-012` rule): the creation's claim, the confirmation's conditional dispatch and
 * the capture's counted ten-way race live in {@code PaymentAuthorizationDatabaseTest} and
 * {@code PaymentCaptureDatabaseTest}. What only this suite can prove is the surface: the
 * contract shape (honestly {@code PROCESSING} included), the disclosure folds, the
 * byte-for-byte replay <em>across a later confirmation</em>, and the chain — the surface is
 * the capture's chainer, and dropping the chain is this suite's named mutation.
 *
 * <p>One {@link SimulatedProvider} plays both externals — the tokenisation provider (attach)
 * and the card PSP (authorize/capture) — on different paths, exactly as one deployment
 * property set would name two endpoints.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the payment endpoints (P5-TSK-011)")
class PaymentEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final String PASSWORD = "a-perfectly-fine-pw-7";

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
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    // -----------------------------------------------------------------
    // The acceptance chain
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the acceptance chain holds: attach, create, confirm - the provider authorises"
            + " and captures, the balance moves, the statement shows the entry, and the chain"
            + " is walked by identifier in both directions")
    void theAcceptanceChainHolds() throws Exception {
        providerAuthorises("psp_auth-chain");
        providerCaptures("psp_cap-chain");
        String token = verifiedCustomer(someLogin());
        String product = openAccount(token);
        String methodId = attachInstrument(token);

        HttpResponse<String> created =
                payment(token, body(methodId, "5.00", "USD"), someKey());
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(field(created.body(), "status")).isEqualTo("REQUIRES_CONFIRMATION");
        assertThat(created.body()).contains("\"failureReason\":null");
        String paymentId = field(created.body(), "id");

        // The confirm answers the REAL state: authorized, then captured by the chained
        // command - SUCCEEDED, in one customer-visible call.
        HttpResponse<String> confirmed = confirm(token, paymentId);
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(field(confirmed.body(), "status")).isEqualTo("SUCCEEDED");

        // The wallet balance moves - the payment IS the wallet's funding here, so the whole
        // settled balance is the captured amount (INV-BAL-02's replay agreeing is the
        // capture suite's proof; what this surface proves is the customer can SEE it).
        assertThat(get("/v1/me/accounts/" + product + "/balance", token).body())
                .contains("\"settled\":\"5.00\"");

        // The chain walked by stored identifier, both directions (plan section 12): intent ->
        // attempt (intent_id) -> journal entry (reference = attempt id) -> statement line
        // (entryId), and the entry's reference points back at the attempt.
        String attemptId = attemptIdFor(paymentId);
        String entryId = entryIdByReference(attemptId);
        String period = "?from=" + LocalDate.now(CLOCK).minusDays(1)
                + "&to=" + LocalDate.now(CLOCK).plusDays(1);
        String statement =
                get("/v1/me/accounts/" + product + "/statement" + period, token).body();
        assertThat(statement).contains(entryId).contains(attemptId);
        assertThat(lineFor(statement, entryId))
                .contains("\"direction\":\"CREDIT\"")
                .contains("\"amount\":\"5.00\"");

        // GET answers the current state by identifier.
        String read = get("/v1/payments/" + paymentId, token).body();
        assertThat(field(read, "status")).isEqualTo("SUCCEEDED");
        assertThat(field(read, "paymentMethodId")).isEqualTo(methodId);
    }

    @Test
    @DisplayName("a retried key replays the original judgement byte for byte - even after the"
            + " payment succeeded; a changed request is the distinct 409; keyless is the"
            + " interceptor's 422")
    void theIdempotencyContractHolds() throws Exception {
        providerAuthorises("psp_auth-replay");
        providerCaptures("psp_cap-replay");
        String token = verifiedCustomer(someLogin());
        openAccount(token);
        String methodId = attachInstrument(token);
        String key = someKey();
        String request = body(methodId, "2.50", "USD");

        HttpResponse<String> first = payment(token, request, key);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(field(first.body(), "status")).isEqualTo("REQUIRES_CONFIRMATION");
        String paymentId = field(first.body(), "id");

        // Confirm - the world moves on: the intent is now SUCCEEDED.
        assertThat(field(confirm(token, paymentId).body(), "status")).isEqualTo("SUCCEEDED");

        // Byte for byte (INV-IDEM-01): the retry learns what its request DID - the original
        // REQUIRES_CONFIRMATION judgement - not what the world looks like now. Rendering from
        // a re-read of the row would leak SUCCEEDED here (the named mutation).
        HttpResponse<String> retried = payment(token, request, key);
        assertThat(retried.statusCode()).isEqualTo(201);
        assertThat(retried.body()).isEqualTo(first.body());

        // The same key with a different amount is a materially different request (INV-IDEM-03).
        HttpResponse<String> changed = payment(token, body(methodId, "2.51", "USD"), key);
        assertThat(changed.statusCode()).isEqualTo(409);
        assertThat(changed.body()).contains("api.Conflict");

        HttpResponse<String> keyless = payment(token, request, null);
        assertThat(keyless.statusCode()).isEqualTo(422);
        assertThat(keyless.body()).contains("api.IdempotencyKeyRequired");
    }

    // -----------------------------------------------------------------
    // The asynchronous-outcome contract shape
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a lost authorization response is honestly PROCESSING - a 200 whose body says"
            + " the platform does not know yet, never an HTTP error")
    void aLostAuthorizationResponseIsHonestlyProcessing() throws Exception {
        provider.receivesTheRequestThenLosesTheResponse(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH);
        String token = verifiedCustomer(someLogin());
        String product = openAccount(token);
        String methodId = attachInstrument(token);
        String paymentId =
                field(payment(token, body(methodId, "4.00", "USD"), someKey()).body(), "id");

        HttpResponse<String> confirmed = confirm(token, paymentId);
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(field(confirmed.body(), "status")).isEqualTo("PROCESSING");

        // The honest state beneath: AUTH_UNKNOWN (INV-LIFE-03) - and nothing chained, nothing
        // posted, no balance invented.
        assertThat(attemptStatusFor(paymentId)).isEqualTo("AUTH_UNKNOWN");
        assertThat(journalEntriesByReference(attemptIdFor(paymentId))).isZero();
        assertThat(get("/v1/me/accounts/" + product + "/balance", token).body())
                .contains("\"settled\":\"0.00\"");
        assertThat(field(get("/v1/payments/" + paymentId, token).body(), "status"))
                .isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("a lost capture response is honestly PROCESSING with nothing posted - the"
            + " authorization stood, the capture is CAPTURE_UNKNOWN")
    void aLostCaptureResponseIsHonestlyProcessing() throws Exception {
        providerAuthorises("psp_auth-ambig");
        provider.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.CAPTURES_PATH);
        String token = verifiedCustomer(someLogin());
        String product = openAccount(token);
        String methodId = attachInstrument(token);
        String paymentId =
                field(payment(token, body(methodId, "6.00", "USD"), someKey()).body(), "id");

        HttpResponse<String> confirmed = confirm(token, paymentId);
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(field(confirmed.body(), "status")).isEqualTo("PROCESSING");

        // Ambiguity posts NOTHING (the capture suite's proof, visible at the surface): the
        // attempt holds CAPTURE_UNKNOWN, the journal holds no entry, the balance holds still.
        assertThat(attemptStatusFor(paymentId)).isEqualTo("CAPTURE_UNKNOWN");
        assertThat(journalEntriesByReference(attemptIdFor(paymentId))).isZero();
        assertThat(get("/v1/me/accounts/" + product + "/balance", token).body())
                .contains("\"settled\":\"0.00\"");
    }

    @Test
    @DisplayName("a declined authorization is a 200 whose body says FAILED with the MAPPED"
            + " reason - and the provider's own vocabulary appears nowhere (INV-PAY-03)")
    void aDeclineIsTheMappedReasonNeverTheProvidersCode() throws Exception {
        // The provider's own decline code is planted in the stub; the contract must never
        // surface it - it lives in the retained evidence and nowhere else (the needle).
        provider.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH,
                200,
                "{\"status\":\"declined\",\"code\":\"do_not_honor_51\"}");
        String token = verifiedCustomer(someLogin());
        String methodId = attachInstrument(token);
        openAccount(token);
        String paymentId =
                field(payment(token, body(methodId, "3.00", "USD"), someKey()).body(), "id");

        HttpResponse<String> confirmed = confirm(token, paymentId);
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(field(confirmed.body(), "status")).isEqualTo("FAILED");
        assertThat(field(confirmed.body(), "failureReason")).isEqualTo("DECLINED");
        assertThat(confirmed.body()).doesNotContain("do_not_honor");

        String read = get("/v1/payments/" + paymentId, token).body();
        assertThat(field(read, "status")).isEqualTo("FAILED");
        assertThat(field(read, "failureReason")).isEqualTo("DECLINED");
        assertThat(read).doesNotContain("do_not_honor");
    }

    // -----------------------------------------------------------------
    // The cancellation window
    // -----------------------------------------------------------------

    @Test
    @DisplayName("cancellation wins only the confirmation window: cancel converges, a cancelled"
            + " payment refuses confirmation, and a succeeded one refuses cancellation")
    void theCancellationWindowHolds() throws Exception {
        providerAuthorises("psp_auth-cancel");
        providerCaptures("psp_cap-cancel");
        String token = verifiedCustomer(someLogin());
        openAccount(token);
        String methodId = attachInstrument(token);

        // Inside the window: cancelled, and a retried cancel converges on CANCELLED.
        String cancellable =
                field(payment(token, body(methodId, "1.00", "USD"), someKey()).body(), "id");
        HttpResponse<String> cancelled = delete(token, "/v1/payments/" + cancellable);
        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(field(cancelled.body(), "status")).isEqualTo("CANCELLED");
        HttpResponse<String> converged = delete(token, "/v1/payments/" + cancellable);
        assertThat(converged.statusCode()).isEqualTo(200);
        assertThat(field(converged.body(), "status")).isEqualTo("CANCELLED");

        // A cancelled payment refuses confirmation: the machine's 409, named for the check.
        HttpResponse<String> refusedConfirm = confirm(token, cancellable);
        assertThat(refusedConfirm.statusCode()).isEqualTo(409);
        assertThat(refusedConfirm.body()).contains("payments.NotConfirmable");

        // Past the window: the provider may already have acted, so cancel is the 409.
        String succeeded =
                field(payment(token, body(methodId, "2.00", "USD"), someKey()).body(), "id");
        assertThat(field(confirm(token, succeeded).body(), "status")).isEqualTo("SUCCEEDED");
        HttpResponse<String> refusedCancel = delete(token, "/v1/payments/" + succeeded);
        assertThat(refusedCancel.statusCode()).isEqualTo(409);
        assertThat(refusedCancel.body()).contains("payments.NotCancellable");
    }

    // -----------------------------------------------------------------
    // Disclosure
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a stranger's payment id, an unknown one and a malformed one are one 404 -"
            + " on the GET, the confirmation and the cancellation alike")
    void aStrangersPaymentIdIsOne404() throws Exception {
        String owner = verifiedCustomer(someLogin());
        String stranger = verifiedCustomer(someLogin());
        openAccount(owner);
        String methodId = attachInstrument(owner);
        String paymentId =
                field(payment(owner, body(methodId, "1.00", "USD"), someKey()).body(), "id");

        for (String path :
                List.of(
                        "/v1/payments/" + paymentId,
                        "/v1/payments/" + UUID.randomUUID(),
                        "/v1/payments/not-a-uuid")) {
            HttpResponse<String> viaGet = get(path, stranger);
            HttpResponse<String> viaConfirm = post(path + "/confirmation", null, stranger, false);
            HttpResponse<String> viaCancel = delete(stranger, path);
            assertThat(viaGet.statusCode()).as(path).isEqualTo(404);
            assertThat(viaConfirm.statusCode()).as(path).isEqualTo(404);
            assertThat(viaCancel.statusCode()).as(path).isEqualTo(404);
            // The equality between the causes (the P1-TSK-016 idiom): party_id = ? in the
            // statement is the ownership check, and no cause is readable from the response.
            assertThat(normalized(viaGet.body()))
                    .isEqualTo(normalized(get("/v1/payments/" + UUID.randomUUID(), stranger)
                            .body()));
        }

        assertThat(get("/v1/payments/" + paymentId, owner).statusCode())
                .as("the positive control: the owner reads their own payment")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("every instrument refusal is one byte-identical 422 - unknown, a stranger's"
            + " and malformed alike - with nothing written; no-wallet and currency mismatch"
            + " are their own named refusals")
    void createRefusalsDiscloseNothing() throws Exception {
        String token = verifiedCustomer(someLogin());
        String stranger = verifiedCustomer(someLogin());
        openAccount(token);
        String strangersMethod = attachInstrument(stranger);

        long before = intentCount();
        String reference = null;
        for (String methodId :
                List.of(UUID.randomUUID().toString(), strangersMethod, "not-a-uuid")) {
            HttpResponse<String> refusal = payment(token, body(methodId, "1.00", "USD"),
                    someKey());
            assertThat(refusal.statusCode()).isEqualTo(422);
            assertThat(refusal.body()).contains("payments.UnknownInstrument");
            if (reference == null) {
                reference = normalized(refusal.body());
            } else {
                assertThat(normalized(refusal.body())).isEqualTo(reference);
            }
        }

        // The caller's own wallet in another currency: the named mismatch.
        String ownMethod = attachInstrument(token);
        HttpResponse<String> mismatch = payment(token, body(ownMethod, "1.00", "EUR"), someKey());
        assertThat(mismatch.statusCode()).isEqualTo(422);
        assertThat(mismatch.body()).contains("payments.CurrencyMismatch");

        // No wallet at all: the caller's own standing, named.
        String walletless = verifiedCustomer(someLogin());
        String walletlessMethod = attachInstrument(walletless);
        HttpResponse<String> noWallet =
                payment(walletless, body(walletlessMethod, "1.00", "USD"), someKey());
        assertThat(noWallet.statusCode()).isEqualTo(422);
        assertThat(noWallet.body()).contains("payments.NoWallet");

        assertThat(intentCount()).as("a boundary refusal writes nothing").isEqualTo(before);
    }

    @Test
    @DisplayName("the list shows only the caller's payments, newest first")
    void theListShowsOnlyTheCallersPaymentsNewestFirst() throws Exception {
        String a = verifiedCustomer(someLogin());
        String b = verifiedCustomer(someLogin());
        openAccount(a);
        openAccount(b);
        String aMethod = attachInstrument(a);
        String bMethod = attachInstrument(b);

        String first = field(payment(a, body(aMethod, "1.00", "USD"), someKey()).body(), "id");
        String second = field(payment(a, body(aMethod, "2.00", "USD"), someKey()).body(), "id");
        String theirs = field(payment(b, body(bMethod, "1.00", "USD"), someKey()).body(), "id");

        String list = get("/v1/payments", a).body();
        assertThat(list).contains(first).contains(second).doesNotContain(theirs);
        assertThat(list.indexOf(second))
                .as("newest first (plan section 9)")
                .isLessThan(list.indexOf(first));
    }

    @Test
    @DisplayName("no body shape is a 500")
    void noBodyShapeIsA500() throws Exception {
        String token = verifiedCustomer(someLogin());
        openAccount(token);
        String methodId = attachInstrument(token);

        List<String> shapes =
                List.of(
                        "{}",
                        body(methodId, "abc", "USD"),
                        // Inexact at the currency's scale: refused, never rounded (INV-MON-03).
                        body(methodId, "1.234", "USD"),
                        body(methodId, "-5.00", "USD"),
                        body(methodId, "0", "USD"),
                        body(methodId, "1.00", "usd"),
                        // ISO but no minor unit: an amount cannot be expressed in XXX.
                        body(methodId, "1.00", "XXX"),
                        "{\"paymentMethodId\":12345,\"amount\":\"1.00\",\"currency\":\"USD\"}",
                        "{\"paymentMethodId\":{\"a\":1},\"amount\":\"1.00\","
                                + "\"currency\":\"USD\"}",
                        "[1,2,3]",
                        "not json at all");
        for (String shape : shapes) {
            HttpResponse<String> response = payment(token, shape, someKey());
            assertThat(response.statusCode())
                    .as("shape %s must be the caller's 4xx, never our 500", shape)
                    .isGreaterThanOrEqualTo(400)
                    .isLessThan(500);
        }
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    /** Registers over HTTP, promotes the customer to {@code ACTIVE}, returns a session token. */
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

    /** Opens a USD wallet over the real endpoint; returns the product identifier. */
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

    /** Attaches an instrument through the real tokenisation flow; returns the method id. */
    private String attachInstrument(String token) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
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

    private static String body(String methodId, String amount, String currency) {
        return "{\"paymentMethodId\":\"" + methodId + "\",\"amount\":\"" + amount
                + "\",\"currency\":\"" + currency + "\"}";
    }

    private HttpResponse<String> payment(String token, String body, String idempotencyKey)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/payments"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            request.header(IdempotencyKeyHeader.NAME, idempotencyKey);
        }
        return send(request.build());
    }

    private HttpResponse<String> confirm(String token, String paymentId) throws Exception {
        return post("/v1/payments/" + paymentId + "/confirmation", null, token, false);
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

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
        return send(request);
    }

    private HttpResponse<String> delete(String token, String path) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .DELETE()
                        .build();
        return send(request);
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
    // Parsing and counters
    // -----------------------------------------------------------------

    private static String someKey() {
        return UUID.randomUUID().toString();
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

    /** The statement line object carrying {@code entryId} — bounded by its braces. */
    private static String lineFor(String statementBody, String entryId) {
        int at = statementBody.indexOf(entryId);
        assertThat(at).as("the statement must carry entry %s", entryId).isNotNegative();
        int start = statementBody.lastIndexOf('{', at);
        int end = statementBody.indexOf('}', at);
        return statementBody.substring(start, end + 1);
    }

    /**
     * The correlation identifier differs per request by design, and {@code instance} is the
     * caller's own request path echoed back. Everything else must be byte-identical.
     */
    private static String normalized(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"normalized\"")
                .replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"normalized\"");
    }

    private static String attemptIdFor(String paymentId) throws SQLException {
        return oneString(
                "SELECT id::text FROM payments.payment_attempt WHERE intent_id = ?",
                UUID.fromString(paymentId));
    }

    private static String attemptStatusFor(String paymentId) throws SQLException {
        return oneString(
                "SELECT status FROM payments.payment_attempt WHERE intent_id = ?",
                UUID.fromString(paymentId));
    }

    private static String entryIdByReference(String reference) throws SQLException {
        return oneString(
                "SELECT id::text FROM ledger.journal_entry WHERE reference = ?", reference);
    }

    private static long journalEntriesByReference(String reference) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT count(*) FROM ledger.journal_entry"
                                        + " WHERE reference = ?")) {
            read.setString(1, reference);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static long intentCount() throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement("SELECT count(*) FROM payments.payment_intent")) {
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
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

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }

    private static String someLogin() {
        return "payer." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
