package com.finapp.app.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.checkout.CheckoutSessions;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.checkout.CheckoutSessionId;
import com.finapp.merchant.MerchantApiKeyId;
import com.finapp.merchant.MerchantId;
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
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * <strong>The phase's first whole flow, end to end</strong> (`P6-TSK-007`): a customer pays a
 * merchant, over real HTTP, through the simulated provider, against the real ledger.
 *
 * <pre>
 *   merchant key  → POST /v1/checkout/sessions            (keyed, token shown once)
 *   customer      → POST /v1/checkout/sessions/confirmation (session + token)
 *                 → intent → authorize → capture
 *                 → ADR-0050 §3's four lines
 *                 → the merchant's payable credited GROSS MINUS FEE
 *                 → the order exists, naming the entry that paid for it
 * </pre>
 *
 * <p>Almost every part is proven elsewhere and <strong>cited rather than repeated</strong>
 * (the `P1-TSK-012` rule): the capture's ten-way race is {@code PaymentCaptureDatabaseTest}'s,
 * the four lines are {@code MerchantCaptureDatabaseTest}'s, the machine's edges are
 * {@code CheckoutSessionDatabaseTest}'s. What only this suite can prove is that the pieces are
 * wired to each other — and the failure mode of orchestration is a step silently skipped, so
 * the assertion that matters most is the <em>derived payable position</em>.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the checkout flow (P6-TSK-007)")
class CheckoutFlowDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    /** 100.00 EUR: 2.9% + 0.30 is 3.20 exactly, and the merchant's net 96.80. */
    private static final long AMOUNT_MINOR = 100_00L;

    /** The instant rail's webhook key (P7-TSK-009): the pay-by-bank confirmations' door. */
    private static final byte[] INSTANT_WEBHOOK_KEY =
            "a-checkout-instant-hook-32-byte!".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private CheckoutSessions sessions;

    // The PRODUCTION beans, assembled into a sweeper with zero bounds: what the race must
    // prove is the path production takes, so the composition seam, the outcome application
    // and the completion are all the wired ones. Only the bounds and the clock are the
    // test's, because a suite that waited ten minutes is a suite nobody runs.
    @Autowired private com.finapp.payments.TransactionRunner paymentTransactionRunner;
    @Autowired private com.finapp.payments.PaymentAttemptStore<java.sql.Connection> attempts;
    @Autowired private com.finapp.payments.PaymentIntentStore<java.sql.Connection> intents;
    @Autowired private com.finapp.payments.ProviderEvidenceStore<java.sql.Connection> evidence;
    @Autowired private com.finapp.payments.PaymentProvider paymentProvider;
    @Autowired private com.finapp.payments.PaymentOutcomes paymentOutcomes;
    @Autowired private com.finapp.payments.PaymentCapture paymentCapture;
    @Autowired private com.finapp.payments.PaymentVoid paymentVoid;
    @Autowired private com.finapp.payments.RefundStore<java.sql.Connection> refundStore;
    @Autowired private com.finapp.platform.audit.AuditWriter<java.sql.Connection> auditWriter;
    @Autowired private com.finapp.platform.outbox.OutboxWriter<java.sql.Connection> outboxWriter;
    @Autowired private javax.sql.DataSource dataSource;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;
    @Autowired private com.finapp.ledger.PostingService postingService;

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
        // P6-TSK-012: the payout provider on the same harness, so a payout can stand in flight
        // beside a merchant refund - the refund-beside-a-payout clause, driven for real.
        registry.add("finapp.merchant.payout.provider.url", () -> provider.baseUrl());
        registry.add("finapp.merchant.payout.provider.timeout", () -> "PT0.7S");
        // P7-TSK-009: the instant scheme and its signed confirmation door, so a checkout
        // can be paid by bank on the same merchant fixture.
        registry.add("finapp.payments.instant.url", () -> provider.baseUrl());
        registry.add("finapp.payments.instant.timeout", () -> "PT0.7S");
        registry.add(
                "finapp.payments.instant.webhook.key",
                () -> java.util.Base64.getEncoder().encodeToString(INSTANT_WEBHOOK_KEY));
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    // ----------------------------------------------------------------- the whole flow

    @Test
    @DisplayName("THE WHOLE FLOW: create, confirm, capture - the payable credited GROSS MINUS"
            + " FEE, the order naming the entry that paid for it")
    void theCustomerPaysTheMerchant() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer customer = payingCustomer();

        HttpResponse<String> created = createSession(merchant, AMOUNT_MINOR, someKey());
        assertThat(created.statusCode()).isEqualTo(201);
        String checkoutId = field(created.body(), "checkoutId");
        String token = field(created.body(), "sessionToken");
        assertThat(field(created.body(), "status")).isEqualTo("OPEN");

        providerAuthorises();
        providerCaptures();
        HttpResponse<String> confirmed = confirm(customer, token);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(field(confirmed.body(), "status"))
                .as("the answer is the session's REAL state after the chained capture")
                .isEqualTo("COMPLETED");
        String orderId = field(confirmed.body(), "orderId");

        try (Connection app = DatabaseRoles.application()) {
            // ADR-0050 §3's entry, reached through a real purchase for the first time.
            String attemptId = attemptIdForSession(app, checkoutId);
            assertThat(linePurposes(app, attemptId))
                    .containsExactlyInAnyOrder(
                            "DEBIT:SETTLEMENT_CLEARING",
                            "CREDIT:MERCHANT_PAYABLE",
                            "DEBIT:MERCHANT_PAYABLE",
                            "CREDIT:FEE_REVENUE");

            // THE ASSERTION THAT MATTERS MOST. Orchestration fails by silently skipping a
            // step, and every skipped step shows up here: no fee pin and the position is the
            // gross; no completion and there is no order; a wrong account and the position is
            // zero. 100.00 in, 3.20 kept, 96.80 owed.
            assertThat(payablePositionMinor(app, merchant)).isEqualTo(96_80L);

            // The order exists, and names the entry that paid for it - the traceable chain's
            // last link (order -> entry -> the four lines -> the payable).
            assertThat(orderId).isNotNull();
            assertThat(capturedEntryOfOrder(app, orderId))
                    .isEqualTo(entryIdByReference(app, attemptId));

            // Announced once, beside merchant.FeeAssessed which the same transaction wrote.
            assertThat(outboxCount(app, "checkout.OrderPaid", orderId)).isEqualTo(1);
            assertThat(outboxCount(app, "merchant.FeeAssessed", merchant.id())).isEqualTo(1);
            // And it says how the offer ended (P6-DOC-001): the payload was built from the row
            // as read, so every paid order was announced as PAYMENT_PENDING.
            assertThat(orderPaidPayload(app, checkoutId))
                    .contains("\"completedAs\":\"COMPLETED\"");
        }
    }

    @Test
    @DisplayName("a REPLAYED create converges on the same session and renders NO TOKEN -"
            + " show-once means once (INV-IDN-01)")
    void aReplayedCreateRendersNoToken() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        String key = someKey();

        HttpResponse<String> first = createSession(merchant, AMOUNT_MINOR, key);
        HttpResponse<String> replay = createSession(merchant, AMOUNT_MINOR, key);

        assertThat(field(replay.body(), "checkoutId"))
                .as("one session, however many times the key is used")
                .isEqualTo(field(first.body(), "checkoutId"));
        assertThat(replay.body())
                .as("the claim records the session id ALONE, so there is nothing to re-show")
                .contains("\"sessionToken\":null")
                .contains("\"alreadyCreated\":true");
    }

    @Test
    @DisplayName("two merchants using ONE key value each open their OWN session - the claim is"
            + " scoped to its merchant, so one tenant's key never refuses another's (ADR-0004)")
    void oneKeyValueAcrossTwoMerchantsOpensTwoSessions() throws Exception {
        Merchant one = tradingMerchant("0.029", 30L);
        Merchant two = tradingMerchant("0.029", 30L);
        String key = someKey();

        HttpResponse<String> first = createSession(one, AMOUNT_MINOR, key);
        HttpResponse<String> second = createSession(two, AMOUNT_MINOR, key);

        // P6-DOC-001. Under the constant scope the second answered 409 api.Conflict: the
        // fingerprint names the merchant, so nothing leaked - but a merchant using its order
        // numbers as keys was refused for a value another shop had already used.
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode())
                .as("another tenant's use of the value is not this tenant's replay")
                .isEqualTo(201);
        assertThat(field(second.body(), "checkoutId"))
                .isNotEqualTo(field(first.body(), "checkoutId"));
        assertThat(second.body())
                .as("a first creation for its own merchant, shown its own token")
                .contains("\"alreadyCreated\":false")
                .doesNotContain("\"sessionToken\":null");
        assertThat(sessionCount(one)).isEqualTo(1);
        assertThat(sessionCount(two)).isEqualTo(1);
    }

    @Test
    @DisplayName("the token's PLAINTEXT is in no column of any schema - not the session row,"
            + " and not the idempotency claim that replayed it (INV-IDN-01)")
    void theTokenIsStoredNowhere() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        String key = someKey();
        String token = field(createSession(merchant, AMOUNT_MINOR, key).body(), "sessionToken");
        createSession(merchant, AMOUNT_MINOR, key);

        // ITS OWN TEST, deliberately, and the split is the P6-TSK-007 gate's own finding: as
        // the last assertion of the replay test this sweep never ran under the probe that
        // makes the claim store the response, because the replay's own assertion failed first
        // and AssertJ stops there. A demonstration that cannot execute is not a demonstration.
        //
        // Derived from information_schema rather than a list of columns, so a table added
        // later is swept without anybody remembering to add it.
        assertThat(columnsHolding(token))
                .as("a credential the platform never stores cannot be stolen from it")
                .isEmpty();
    }

    @Test
    @DisplayName("a merchant reads its OWN session and nothing else - unknown, malformed and"
            + " another tenant's are one 404 (INV-MER-01)")
    void aMerchantReadsOnlyItsOwnSession() throws Exception {
        Merchant one = tradingMerchant("0.029", 30L);
        Merchant two = tradingMerchant("0.029", 30L);
        String checkoutId = field(createSession(one, AMOUNT_MINOR, someKey()).body(), "checkoutId");

        assertThat(readSession(one, checkoutId).statusCode()).isEqualTo(200);
        assertThat(readSession(two, checkoutId).statusCode())
                .as("another merchant's session is indistinguishable from one that does not exist")
                .isEqualTo(404);
        assertThat(readSession(one, UUID.randomUUID().toString()).statusCode()).isEqualTo(404);
        assertThat(readSession(one, "not-a-uuid").statusCode()).isEqualTo(404);

        assertThat(readSession(one, checkoutId).body())
                .as("and no read after creation ever renders the token")
                .doesNotContain("sessionToken");
    }

    @Test
    @DisplayName("a session for a merchant with NO FEE SCHEDULE is refused at CREATION, not"
            + " discovered at the capture")
    void anUnpricedMerchantCannotOpenASession() throws Exception {
        Merchant unpriced = merchantWithoutPricing();
        HttpResponse<String> refused = createSession(unpriced, AMOUNT_MINOR, someKey());

        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("checkout.NotPriceable");
        assertThat(sessionCount(unpriced)).as("the refusal wrote nothing").isZero();
    }

    @Test
    @DisplayName("a SUSPENDED merchant's key stops working AT AUTHENTICATION - the 401 is"
            + " P6-TSK-002's design biting one surface later, and it writes nothing")
    void aSuspendedMerchantsKeyStopsAuthenticating() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        assertThat(createSession(merchant, AMOUNT_MINOR, someKey()).statusCode()).isEqualTo(201);
        suspend(merchant);

        long before = sessionCount(merchant);
        HttpResponse<String> refused = createSession(merchant, AMOUNT_MINOR, someKey());

        // NOT checkout.NotTrading: the key lookup carries the merchant's standing IN THE JOIN
        // (`m.status = 'ACTIVE'`), so a suspension bites on the very next call with nothing to
        // invalidate anywhere. The credential resolves to no merchant, which is a 401 and not
        // a 409 - the caller is not authenticated, so there is no tenant to refuse.
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(refused.body()).doesNotContain("checkout.NotTrading");
        assertThat(sessionCount(merchant)).isEqualTo(before);
    }

    @Test
    @DisplayName("checkout.NotTrading is the RACE's refusal, not the ordinary one - driven at"
            + " the command, because HTTP can only ever answer 401 first")
    void theNotTradingRefusalGuardsTheRace() throws Exception {
        // A suspension committed BETWEEN the interceptor's authentication read and the
        // command's own authoritative read is exactly what this guard exists for, and it is
        // not producible over HTTP: authentication would simply fail. Driving the command
        // directly is the only honest way to prove the branch is reachable and refuses -
        // otherwise a named error code would have no test at all.
        Merchant merchant = tradingMerchant("0.029", 30L);
        suspend(merchant);

        assertThatThrownBy(
                        () ->
                                openDirectly(
                                        merchant,
                                        new CheckoutSessions.OpenSessionCommand(
                                                someKey(),
                                                MerchantId.of(UUID.fromString(merchant.id())),
                                                keyIdOf(merchant),
                                                Money.ofMinorUnits(AMOUNT_MINOR, CurrencyCode.of("EUR")),
                                                "Two coffees and a pastry")))
                .isInstanceOf(MerchantNotTradingException.class);
        assertThat(sessionCount(merchant))
                .as("a refusal before the claim writes nothing and consumes no key")
                .isZero();
    }

    @Test
    @DisplayName("a confirmation with a token that opens nothing is the SAME 404 as one that"
            + " does not exist")
    void anUnknownTokenIsOne404() throws Exception {
        Customer customer = payingCustomer();
        HttpResponse<String> refused =
                post(
                        "/v1/checkout/sessions/confirmation",
                        "{\"sessionToken\":\"" + UUID.randomUUID() + "\",\"paymentMethodId\":\""
                                + customer.methodId() + "\"}",
                        customer.token(),
                        null);
        assertThat(refused.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("a RETRIED confirmation converges on the payment the first one opened")
    void aRetriedConfirmationConverges() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer customer = payingCustomer();
        String token = field(createSession(merchant, AMOUNT_MINOR, someKey()).body(), "sessionToken");

        providerAuthorises();
        providerCaptures();
        HttpResponse<String> first = confirm(customer, token);
        assertThat(first.statusCode()).isEqualTo(200);

        // The machine is the idempotency: a second confirm does not refuse and does not open a
        // second payment - it answers the session's state, and would finish a stranded capture
        // on the way (PaymentController's recorded shape).
        HttpResponse<String> again = confirm(customer, token);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        assertThat(field(again.body(), "paymentIntentId"))
                .isEqualTo(field(first.body(), "paymentIntentId"));
        assertThat(field(again.body(), "orderId")).isEqualTo(field(first.body(), "orderId"));

        try (Connection app = DatabaseRoles.application()) {
            assertThat(payablePositionMinor(app, merchant))
                    .as("credited once, charged once")
                    .isEqualTo(96_80L);
        }
    }

    @Test
    @DisplayName("a SECOND holder of the token cannot converge on a PAID session - the payer is"
            + " re-established, and a stranger gets the same one 404")
    void aStrangerCannotConvergeOnSomebodyElsesPurchase() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer payer = payingCustomer();
        Customer stranger = payingCustomer();
        String token =
                field(createSession(merchant, AMOUNT_MINOR, someKey()).body(), "sessionToken");
        providerAuthorises();
        providerCaptures();
        HttpResponse<String> paid = confirm(payer, token);
        assertThat(paid.statusCode()).isEqualTo(200);

        // The convergence branch skips the payments surface, so it does the ownership check
        // itself - and gets it right: a token holder who did not pay learns neither the
        // payment intent nor the order, and cannot tell this session from one that never was.
        HttpResponse<String> refused = confirm(stranger, token);
        assertThat(refused.statusCode()).isEqualTo(404);
        assertThat(refused.body())
                .doesNotContain(field(paid.body(), "paymentIntentId"))
                .doesNotContain(field(paid.body(), "orderId"));
    }

    @Test
    @DisplayName("TEN INSTANCES confirming one session produce ONE order and ONE payable"
            + " movement - the conditional edges and the unique index")
    void tenConcurrentConfirmationsProduceOneOrder() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer customer = payingCustomer();
        // Taken BEFORE the session exists, so the conversion's whole age lies inside the span.
        java.time.Instant spanStart = java.time.Instant.now();
        String token = field(createSession(merchant, AMOUNT_MINOR, someKey()).body(), "sessionToken");
        providerAuthorises();
        providerCaptures();
        double completedBefore = sessionMeter("completed");
        long conversionsBefore = conversions("completed");
        double conversionSecondsBefore = conversionSeconds("completed");
        double feesBefore = feeAssessments();

        int racers = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            for (int i = 0; i < racers; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return confirm(customer, token);
                                }));
            }
            start.countDown();
            for (Future<HttpResponse<String>> result : results) {
                HttpResponse<String> answer = result.get(120, TimeUnit.SECONDS);
                // The Phase 6 -> 7 transition: every racer converges on the one purchase. The one
                // honest exception is the claim's bounded wait - "in progress, retry" - and no
                // racer is told the session left the flow, which the losers of the open were.
                if (answer.statusCode() != 200) {
                    assertThat(answer.body())
                            .as("every racer converges, or is told honestly that one is in flight")
                            .contains("api.IdempotencyInProgress");
                }
                assertThat(answer.body()).doesNotContain("checkout.");
            }
        } finally {
            pool.shutdownNow();
        }

        try (Connection app = DatabaseRoles.application()) {
            assertThat(orderCountFor(app, merchant))
                    .as("exactly one commercial fact from one purchase")
                    .isEqualTo(1);
            assertThat(payablePositionMinor(app, merchant))
                    .as("and the merchant is owed for one purchase, not ten")
                    .isEqualTo(96_80L);
            // P6-DOC-001: racers that reach the pin carry one intent between them, and the phase
            // review found nothing counted what they left there - one agreed price per payment,
            // the pin's primary key converging every racer after the first (merchant V005).
            assertThat(feePinsForTheSessionsIntent(app, merchant))
                    .as("one pin for the intent the session carries, however many confirmed")
                    .isEqualTo(1);
        }
        // THE METERS, THROUGH THE WIRED PATH (P6-TSK-013): one ending, one conversion timed,
        // one fee assessment - each behind the conditional the nine losers never passed.
        assertThat(sessionMeter("completed") - completedBefore).isEqualTo(1.0d);
        assertThat(conversions("completed") - conversionsBefore)
                .as("one conversion timed, from the offer's creation to its completion")
                .isEqualTo(1L);
        // The session was created before its first confirmation arrived and completed before
        // the last answer did, so its age lies inside this test's own span - a reversed or
        // zeroed age, which a count cannot see, falls outside it.
        double spanSeconds =
                java.time.Duration.between(spanStart, java.time.Instant.now()).toNanos() / 1e9;
        assertThat(conversionSeconds("completed") - conversionSecondsBefore)
                .as("the recorded age is the conversion's own: positive, and within the span")
                .isGreaterThan(0.0d)
                .isLessThanOrEqualTo(spanSeconds);
        assertThat(feeAssessments() - feesBefore)
                .as("one capture, one assessment - a count, never the 3.20")
                .isEqualTo(1.0d);
    }

    /**
     * The payments sweeper, with the least patience it accepts — the production outcome path. A
     * microsecond rather than zero: the sweep refuses a zero bound since the Phase 6 → 7
     * transition, because at zero it could conclude NEVER_RECEIVED of a request still in flight.
     */
    private com.finapp.payments.PaymentSweeper paymentSweeper() {
        return new com.finapp.payments.PaymentSweeper(
                paymentTransactionRunner,
                attempts,
                intents,
                refundStore,
                evidence,
                paymentProvider,
                paymentOutcomes,
                paymentCapture,
                paymentVoid,
                IDS,
                CLOCK,
                java.time.Duration.ofNanos(1_000),
                java.time.Duration.ofNanos(1_000),
                50);
    }

    /** The expiry sweeper at a chosen clock — the offer window is thirty minutes. */
    private com.finapp.checkout.CheckoutExpirySweeper expirySweeper(
            Clock clock, java.time.Duration grace) {
        return new com.finapp.checkout.CheckoutExpirySweeper(
                new CheckoutTransactions(
                        new org.springframework.transaction.support.TransactionTemplate(
                                transactions),
                        dataSource),
                new com.finapp.checkout.JdbcCheckoutSessionStore(),
                auditWriter,
                outboxWriter,
                IDS,
                clock,
                grace,
                50,
                // The wired port (the Phase 7 -> 8 transition): an undispatched intent ends with
                // its session, an in-flight one is untouched.
                (unitOfWork, intentRef) ->
                        paymentCancellation.cancelUndispatched(
                                unitOfWork,
                                com.finapp.payments.PaymentIntentId.of(intentRef),
                                "the checkout session expired before its payment was"
                                        + " dispatched"));
    }

    @Autowired private com.finapp.payments.PaymentCancellation paymentCancellation;

    private HttpResponse<String> abandon(Merchant merchant, String id, String reason)
            throws Exception {
        return post(
                "/v1/checkout/sessions/" + id + "/abandonment",
                "{\"reason\":\"" + reason + "\"}",
                merchant.apiKey(),
                null);
    }

    private static String sessionStatus(String checkoutId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT status FROM checkout.checkout_session WHERE id = ?")) {
            read.setObject(1, UUID.fromString(checkoutId));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static String attemptIdForSession(String checkoutId) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return attemptIdForSession(app, checkoutId);
        }
    }

    private static String captureReference(String attemptId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT capture_reference FROM payments.payment_attempt"
                                        + " WHERE id = ?")) {
            read.setObject(1, UUID.fromString(attemptId));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long historyRow(Connection app, String checkoutId, String from, String to)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT count(*) FROM checkout.checkout_session_event"
                                + " WHERE session_id = ? AND from_status = ? AND to_status = ?")) {
            read.setObject(1, UUID.fromString(checkoutId));
            read.setString(2, from);
            read.setString(3, to);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static String auditSummaryFor(Connection app, String checkoutId, String operation)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT change_summary FROM platform.audit_record WHERE target_id = ?"
                                + " AND operation = ?")) {
            read.setString(1, checkoutId);
            read.setString(2, operation);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("one %s record", operation).isTrue();
                String summary = row.getString(1);
                assertThat(row.next()).as("and only one").isFalse();
                return summary;
            }
        }
    }

    private static String auditReasonFor(Connection app, String checkoutId) throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT reason FROM platform.audit_record WHERE target_id = ?"
                                + " AND operation = 'checkout.CheckoutSessionAbandoned'")) {
            read.setString(1, checkoutId);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    /**
     * Wraps {@code real} so that, the moment the position breakdown's line statement has
     * executed, {@code sideEffect} runs and commits on ANOTHER connection. Everything else passes
     * straight through.
     */
    private static Connection commitAfterTheLineRead(Connection real, Runnable sideEffect) {
        java.util.concurrent.atomic.AtomicBoolean fired =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        return (Connection)
                java.lang.reflect.Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, args) -> {
                            Object result = invoke(real, method, args);
                            if ("prepareStatement".equals(method.getName())
                                    && args != null
                                    && args[0] instanceof String sql
                                    && sql.contains("AS counterparty")) {
                                PreparedStatement statement = (PreparedStatement) result;
                                return java.lang.reflect.Proxy.newProxyInstance(
                                        PreparedStatement.class.getClassLoader(),
                                        new Class<?>[] {PreparedStatement.class},
                                        (inner, call, callArgs) -> {
                                            Object answer = invoke(statement, call, callArgs);
                                            if ("executeQuery".equals(call.getName())
                                                    && fired.compareAndSet(false, true)) {
                                                sideEffect.run();
                                            }
                                            return answer;
                                        });
                            }
                            return result;
                        });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }

    /** DR SETTLEMENT_CLEARING / CR the merchant's payable, 1.00, committed on its own. */
    @SuppressWarnings("try") // Scopes are used for their close side effect.
    private void postOneEuroToThePayable(Merchant merchant) {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection other = DatabaseRoles.application()) {
            other.setAutoCommit(false);
            com.finapp.sharedkernel.money.CurrencyCode eur =
                    com.finapp.sharedkernel.money.CurrencyCode.of("EUR");
            com.finapp.ledger.JdbcLedgerAccountStore accounts =
                    new com.finapp.ledger.JdbcLedgerAccountStore();
            com.finapp.ledger.LedgerAccount payable =
                    accounts.findOwned(
                                    other,
                                    UUID.fromString(merchant.id()),
                                    com.finapp.ledger.AccountPurpose.MERCHANT_PAYABLE,
                                    eur)
                            .orElseThrow();
            com.finapp.ledger.LedgerAccount clearing =
                    new com.finapp.ledger.ChartOfAccounts<>(accounts)
                            .resolve(
                                    other,
                                    com.finapp.ledger.AccountPurpose.SETTLEMENT_CLEARING,
                                    eur);
            Money one = Money.ofMinorUnits(1_00L, eur);
            java.time.LocalDate today = java.time.LocalDate.now(CLOCK);
            postingService.post(
                    other,
                    new com.finapp.ledger.PostingCommand(
                            "mid-read:" + UUID.randomUUID(),
                            today,
                            today,
                            "mid-read-" + UUID.randomUUID(),
                            java.util.List.of(
                                    new com.finapp.ledger.JournalLine(
                                            clearing.id(),
                                            com.finapp.ledger.Direction.DEBIT,
                                            one),
                                    new com.finapp.ledger.JournalLine(
                                            payable.id(),
                                            com.finapp.ledger.Direction.CREDIT,
                                            one))));
            other.commit();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private HttpResponse<String> payable(Merchant merchant) throws Exception {
        return get("/v1/merchant/payable", merchant.apiKey());
    }

    /**
     * An EFFECTIVE payout destination for {@code merchant} (`P6-TSK-012`): its four-eyes flow is
     * {@code PayoutDestinationDatabaseTest}'s subject, so the row is a fixture whose timestamps
     * satisfy `V006`'s approval and cooling-off CHECKs.
     */
    private static void effectiveDestinationFor(Merchant merchant) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement insert =
                        app.prepareStatement(
                                "INSERT INTO merchant.payout_destination (id, merchant_id,"
                                        + " destination_reference, display_suffix, status,"
                                        + " proposed_by, proposed_at, proposal_reason, approved_by,"
                                        + " approved_at, cooling_off_until, effective_at) VALUES"
                                        + " (?, ?, ?, '3000', 'EFFECTIVE', 'fixture-a', now() -"
                                        + " interval '4 days', 'fixture', 'fixture-b', now() -"
                                        + " interval '4 days', now() - interval '1 day', now() -"
                                        + " interval '1 hour')")) {
            insert.setObject(1, IDS.next());
            insert.setObject(2, UUID.fromString(merchant.id()));
            insert.setString(
                    3, "pdr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            insert.executeUpdate();
        }
    }

    /** A real refund through the operator surface, approved by the simulated provider. */
    private void refund(String intentId, String amount) throws Exception {
        provider.succeedsWith(
                SimulatedCardPspAdapter.REFUNDS_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"psp_ref-" + UUID.randomUUID()
                        + "\"}");
        HttpResponse<String> refunded =
                post(
                        "/v1/payments/" + intentId + "/refund",
                        "{\"amount\":\"" + amount + "\",\"currency\":\"EUR\","
                                + "\"reason\":\"goods returned\"}",
                        operatorSession(RoleName.LEDGER_OPERATOR),
                        someKey());
        assertThat(refunded.statusCode()).as(refunded.body()).isEqualTo(201);
    }

    /** A refund through the operator surface, answered however it is answered. */
    private HttpResponse<String> refundResponse(String intentId, String amount) throws Exception {
        return refundResponse(intentId, amount, operatorSession(RoleName.LEDGER_OPERATOR));
    }

    private HttpResponse<String> refundResponse(String intentId, String amount, String operator)
            throws Exception {
        return post(
                "/v1/payments/" + intentId + "/refund",
                "{\"amount\":\"" + amount + "\",\"currency\":\"EUR\","
                        + "\"reason\":\"goods returned\"}",
                operator,
                someKey());
    }

    /** Every hold ever placed on the merchant's payable, oldest first, as amount:STATUS. */
    private static List<String> heldOn(Merchant merchant) throws SQLException {
        List<String> held = new ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT hold.amount_minor, hold.status FROM ledger.hold hold"
                                        + " JOIN ledger.ledger_account account"
                                        + "   ON account.id = hold.ledger_account_id"
                                        + " WHERE account.purpose = 'MERCHANT_PAYABLE'"
                                        + "   AND account.owner_ref = ?"
                                        + " ORDER BY hold.placed_at, hold.id")) {
            read.setObject(1, UUID.fromString(merchant.id()));
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    held.add(row.getLong(1) + ":" + row.getString(2));
                }
            }
        }
        return held;
    }

    private static long activeHoldsOn(Merchant merchant) throws SQLException {
        return heldOn(merchant).stream().filter(hold -> hold.endsWith(":ACTIVE")).count();
    }

    /** Refund rows of the payment, whatever their status. */
    private static long refundRowsFor(String intentId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT count(*) FROM payments.refund refund"
                                        + " JOIN payments.payment_attempt attempt"
                                        + "   ON attempt.id = refund.attempt_id"
                                        + " WHERE attempt.intent_id = ?")) {
            read.setObject(1, UUID.fromString(intentId));
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    /** Runs every task at once - released together by one latch - and returns their answers. */
    private static <T> List<T> concurrently(List<java.util.concurrent.Callable<T>> tasks)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> running = new ArrayList<>();
            for (java.util.concurrent.Callable<T> task : tasks) {
                running.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return task.call();
                                }));
            }
            start.countDown();
            List<T> answers = new ArrayList<>();
            for (Future<T> answer : running) {
                answers.add(answer.get(120, TimeUnit.SECONDS));
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * position == captured - fees - refunded + feesReturned - paidOut + other, read from the
     * body ({@code paidOut} its own term since `P6-TSK-012`).
     */
    private static boolean explainsItself(String body) {
        java.math.BigDecimal position = new java.math.BigDecimal(field(body, "position"));
        java.math.BigDecimal terms =
                new java.math.BigDecimal(field(body, "captured"))
                        .subtract(new java.math.BigDecimal(field(body, "fees")))
                        .subtract(new java.math.BigDecimal(field(body, "refunded")))
                        .add(new java.math.BigDecimal(field(body, "feesReturned")))
                        .subtract(new java.math.BigDecimal(field(body, "paidOut")))
                        .add(new java.math.BigDecimal(field(body, "other")));
        return position.compareTo(terms) == 0;
    }

    /** The ledger's own DEFINITION of the payable - the fold the view must also equal. */
    private static long derivedPayableMinor(Connection app, Merchant merchant) {
        com.finapp.ledger.JdbcLedgerAccountStore accounts =
                new com.finapp.ledger.JdbcLedgerAccountStore();
        com.finapp.ledger.LedgerAccount payable =
                accounts.findOwned(
                                app,
                                UUID.fromString(merchant.id()),
                                com.finapp.ledger.AccountPurpose.MERCHANT_PAYABLE,
                                com.finapp.sharedkernel.money.CurrencyCode.of("EUR"))
                        .orElseThrow();
        return new com.finapp.ledger.JdbcBalanceDerivation()
                .derive(app, payable.id(), com.finapp.ledger.AsOf.latest())
                .settled()
                .minorUnits();
    }

    /** One completed purchase: create, confirm, capture. Returns the checkout id. */
    private String purchase(Merchant merchant, Customer customer) throws Exception {
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        providerAuthorises();
        providerCaptures();
        HttpResponse<String> confirmed = confirm(customer, field(created, "sessionToken"));
        assertThat(field(confirmed.body(), "status")).isEqualTo("COMPLETED");
        return field(created, "checkoutId");
    }

    private HttpResponse<String> transactions(Merchant merchant, String from, String to)
            throws Exception {
        return get("/v1/merchant/transactions?from=" + from + "&to=" + to, merchant.apiKey());
    }

    private static String intentOfSession(String checkoutId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT payment_intent_ref FROM checkout.checkout_session"
                                        + " WHERE id = ?")) {
            read.setObject(1, UUID.fromString(checkoutId));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static int countOf(String body, String needle) {
        int count = 0;
        for (int at = body.indexOf(needle); at >= 0; at = body.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    /** Drives the withdrawal command itself, past the surface's own tenant-scoped render. */
    private boolean abandonDirectly(Merchant merchant, CheckoutSessionId id) throws Exception {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                boolean acted =
                        sessions.abandon(
                                app,
                                MerchantId.of(UUID.fromString(merchant.id())),
                                keyIdOf(merchant),
                                id,
                                "driven at the command");
                app.commit();
                return acted;
            } catch (RuntimeException refused) {
                app.rollback();
                throw refused;
            }
        }
    }

    /** Drives the command itself, past the surface that would refuse first. */
    private void openDirectly(Merchant merchant, CheckoutSessions.OpenSessionCommand command)
            throws Exception {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                sessions.open(app, command);
                app.commit();
            } catch (RuntimeException refused) {
                app.rollback();
                throw refused;
            }
        }
    }

    /**
     * The capture's completion driven at the command - what the composition seam calls inside
     * the capture's own transaction - under a correlation whose cause is set, as a capture's is.
     *
     * @return the order, when this call's own transition created it
     */
    private java.util.Optional<com.finapp.checkout.Order> completeDirectly(UUID intent)
            throws Exception {
        Correlation capture =
                Correlation.startingWith(CorrelationId.generate(IDS))
                        .causing(com.finapp.sharedkernel.correlation.CausationId.generate(IDS));
        try (CorrelationContext.Scope flow = CorrelationContext.enter(capture);
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                java.util.Optional<com.finapp.checkout.Order> created =
                        sessions.completed(app, intent, IDS.next(), capture);
                app.commit();
                return created;
            } catch (RuntimeException refused) {
                app.rollback();
                throw refused;
            }
        }
    }

    @Test
    @DisplayName("TEN INSTANCES creating with ONE key produce ONE session and ONE token - and"
            + " exactly one of the ten answers carries it")
    void tenConcurrentCreatesProduceOneSession() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        String key = someKey();

        int racers = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            for (int i = 0; i < racers; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return createSession(merchant, AMOUNT_MINOR, key).body();
                                }));
            }
            start.countDown();
            List<String> bodies = new ArrayList<>();
            for (Future<String> result : results) {
                bodies.add(result.get(120, TimeUnit.SECONDS));
            }

            assertThat(bodies.stream().map(body -> field(body, "checkoutId")).distinct())
                    .as("one offer, however many instances raced to make it")
                    .hasSize(1);
            assertThat(bodies.stream().filter(body -> field(body, "sessionToken") != null).count())
                    .as("SHOW-ONCE MEANS ONCE, under a race too: the executing caller renders"
                            + " the token and every replaying one renders null, because the"
                            + " claim records the session id alone (INV-IDN-01)")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(sessionCount(merchant)).isEqualTo(1);
    }

    @Test
    @DisplayName("THE TENANT PREDICATE IS THE COMMAND'S OWN: a stranger's merchant id cannot"
            + " withdraw somebody else's offer, driven at the command (INV-MER-01)")
    void theWithdrawalCommandIsTenantScoped() throws Exception {
        // FOUND BY THE MUTATION BATTERY, and closed where it was found. Over HTTP the stranger
        // already gets a 404 and the row stays OPEN - but BOTH of those come from the RENDER,
        // which is tenant-scoped and shares the command's transaction, so its refusal rolls
        // the withdrawal back. That made the predicate inside the command unreachable by any
        // behavioural test, and a predicate no test can reach is one a later refactor removes:
        // render outside the transaction, or answer 204, and cross-tenant withdrawal is live.
        // So the command is driven DIRECTLY, which is the only place the rule is stated at the
        // write (the P6-TSK-003 survivor's lesson, applied at a second command).
        Merchant owner = tradingMerchant("0.029", 30L);
        Merchant stranger = tradingMerchant("0.029", 30L);
        String checkoutId =
                field(createSession(owner, AMOUNT_MINOR, someKey()).body(), "checkoutId");

        assertThatThrownBy(
                        () ->
                                abandonDirectly(
                                        stranger,
                                        CheckoutSessionId.of(UUID.fromString(checkoutId))))
                .isInstanceOf(UnknownCheckoutSessionException.class);
        assertThat(sessionStatus(checkoutId)).isEqualTo("OPEN");

        assertThat(
                        abandonDirectly(
                                owner,
                                CheckoutSessionId.of(UUID.fromString(checkoutId))))
                .as("the positive control: the owner withdraws its own offer")
                .isTrue();
        assertThat(sessionStatus(checkoutId)).isEqualTo("ABANDONED");
    }

    // ----------------------------------------------------------------- the transaction report

    @Test
    @DisplayName("P6-TSK-009: the merchant's report RECONCILES TO THE JOURNAL - two captures and"
            + " a real refund, each named by purchase, and the nets sum to closing minus opening")
    void theTransactionReportReconcilesToTheJournal() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer customer = payingCustomer();
        String firstCheckout = purchase(merchant, customer);
        String secondCheckout = purchase(merchant, customer);

        // A REAL REFUND through production - the operator surface, the provider, and
        // P6-TSK-014's seam - of 40.00 of the first purchase, under the RETAINED policy.
        String firstIntent = intentOfSession(firstCheckout);
        provider.succeedsWith(
                SimulatedCardPspAdapter.REFUNDS_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"psp_ref-" + UUID.randomUUID()
                        + "\"}");
        HttpResponse<String> refunded =
                post(
                        "/v1/payments/" + firstIntent + "/refund",
                        "{\"amount\":\"40.00\",\"currency\":\"EUR\","
                                + "\"reason\":\"one item returned\"}",
                        operatorSession(RoleName.LEDGER_OPERATOR),
                        someKey());
        assertThat(refunded.statusCode()).as(refunded.body()).isEqualTo(201);

        String today = java.time.LocalDate.now(CLOCK).toString();
        HttpResponse<String> report = transactions(merchant, today, today);
        assertThat(report.statusCode()).as(report.body()).isEqualTo(200);
        String body = report.body();

        // Every movement is NAMED BY ITS PURCHASE - the drill-down the merchant needs.
        assertThat(body).contains(firstCheckout).contains(secondCheckout);
        assertThat(countOf(body, "\"kind\":\"CAPTURE\"")).isEqualTo(2);
        assertThat(countOf(body, "\"kind\":\"REFUND\"")).isEqualTo(1);
        assertThat(body)
                .as("a capture shows its gross and the fee the platform took")
                .contains("\"gross\":\"100.00\",\"fee\":\"3.20\",\"net\":\"96.80\"")
                .as("and the RETAINED refund returns the gross and no fee")
                .contains("\"gross\":\"40.00\",\"fee\":\"0.00\",\"net\":\"-40.00\"");

        // THE RECONCILIATION, against INDEPENDENT SQL over the journal - not against the
        // report's own arithmetic. 96.80 + 96.80 - 40.00.
        java.math.BigDecimal closing = new java.math.BigDecimal(field(body, "closing"));
        java.math.BigDecimal opening = new java.math.BigDecimal(field(body, "opening"));
        try (Connection app = DatabaseRoles.application()) {
            assertThat(closing.subtract(opening).movePointRight(2).longValueExact())
                    .as("closing minus opening IS the payable's movement in the journal")
                    .isEqualTo(payablePositionMinor(app, merchant))
                    .isEqualTo(153_60L);
        }
    }

    @Test
    @DisplayName("P6-TSK-009, INV-MER-01: merchant B's report contains NONE of A's rows, and A's"
            + " session id on B's session read is the one 404 - the negative per endpoint")
    void noMerchantSeesAnotherMerchantsBusiness() throws Exception {
        Merchant a = tradingMerchant("0.029", 30L);
        Merchant b = tradingMerchant("0.029", 30L);
        Customer customer = payingCustomer();
        String aCheckout = purchase(a, customer);
        String aIntent = intentOfSession(aCheckout);
        String bCheckout = purchase(b, customer);

        String today = java.time.LocalDate.now(CLOCK).toString();
        String bReport = transactions(b, today, today).body();

        assertThat(bReport)
                .as("the route takes no identifier, so the question cannot even be asked -"
                        + " and the answer contains none of A's purchase, payment or entries")
                .contains(bCheckout)
                .doesNotContain(aCheckout)
                .doesNotContain(aIntent);
        assertThat(countOf(bReport, "\"kind\":\"CAPTURE\"")).isEqualTo(1);

        HttpResponse<String> read = get("/v1/checkout/sessions/" + aCheckout, b.apiKey());
        HttpResponse<String> unknown =
                get("/v1/checkout/sessions/" + UUID.randomUUID(), b.apiKey());
        assertThat(read.statusCode()).isEqualTo(404);
        // INDISTINGUISHABLE, which is the property - not "the id is absent from the body":
        // `instance` is the caller's OWN request path echoed back, so it carries the id B
        // itself sent (the platform's problem-detail convention, P1-TSK-016's equality idiom).
        // With the per-request fields normalized, a competitor's session and a session that
        // never existed must be byte-for-byte the same answer.
        assertThat(normalized(read.body()))
                .as("the session read: a competitor's session is indistinguishable from none")
                .isEqualTo(normalized(unknown.body()));
    }

    /** The per-request fields: the correlation id differs by design, instance echoes the path. */
    private static String normalized(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"n\"")
                .replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"n\"");
    }

    @Test
    @DisplayName("P6-TSK-009, THE SECOND RANK: the report's checkout read resolves an intent only"
            + " for ITS OWN merchant - merchant_ref = ? in the statement, driven directly")
    void theEnrichmentReadIsTenantScopedInItsOwnStatement() throws Exception {
        // Driven at the STORE, because over HTTP this predicate is unreachable: the report only
        // follows references it found on the merchant's OWN payable (the first rank), so it
        // never asks about a competitor's intent. A predicate no test can reach is one a later
        // refactor removes (P6-TSK-008's survivor lesson). It was also invisible to
        // OwnershipIsScopedTest, whose detector keyed on EntityId-typed parameters while
        // checkout holds every cross-module reference by value, as a bare UUID (ADR-0029);
        // P6-TST-001 widened the detector, and this test is the register's named negative.
        Merchant a = tradingMerchant("0.029", 30L);
        Merchant b = tradingMerchant("0.029", 30L);
        String aIntent = intentOfSession(purchase(a, payingCustomer()));
        com.finapp.checkout.JdbcCheckoutSessionStore store =
                new com.finapp.checkout.JdbcCheckoutSessionStore();

        try (Connection app = DatabaseRoles.application()) {
            assertThat(
                            store.findByIntentOwnedBy(
                                    app, UUID.fromString(aIntent), UUID.fromString(b.id())))
                    .as("B asking about A's intent: nothing, from the database")
                    .isEmpty();
            assertThat(
                            store.findByIntentOwnedBy(
                                    app, UUID.fromString(aIntent), UUID.fromString(a.id())))
                    .as("the positive control: A's own intent resolves")
                    .isPresent();
        }
    }

    @Test
    @DisplayName("P6-TSK-009: an empty window is ZEROS, not an absence - and a merchant who has"
            + " never traded still gets its payable's section")
    void anEmptyWindowIsZeros() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        String today = java.time.LocalDate.now(CLOCK).toString();

        String body = transactions(merchant, today, today).body();

        assertThat(body)
                .contains("\"currency\":\"EUR\"")
                .contains("\"opening\":\"0.00\"")
                .contains("\"closing\":\"0.00\"")
                .contains("\"movements\":[]");
    }

    @Test
    @DisplayName("P6-TSK-009: the period's refusals are specific 422s - a date names nobody,"
            + " so it is the caller's own correctable value")
    void thePeriodIsBounded() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);

        assertThat(transactions(merchant, "2026-09-10", "2026-09-01").statusCode())
                .as("from after to")
                .isEqualTo(422);
        assertThat(transactions(merchant, "2026-01-01", "2026-03-01").statusCode())
                .as("longer than a month: one request stays one bounded read")
                .isEqualTo(422);
        assertThat(transactions(merchant, "yesterday", "2026-09-01").statusCode())
                .as("malformed")
                .isEqualTo(422);
        assertThat(transactions(merchant, "2026-08-01", "2026-08-31").statusCode())
                .as("the longest month is exactly the bound, and it is allowed")
                .isEqualTo(200);
    }

    // ----------------------------------------------------------------- the payable view

    @Test
    @DisplayName("P6-TSK-010: the payable is the LEDGER POSITION, explained - two captures and a"
            + " real refund, every term named, equal to independent SQL AND to the derivation")
    void thePayableReconcilesToTheLedger() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer customer = payingCustomer();
        String first = purchase(merchant, customer);
        purchase(merchant, customer);
        refund(intentOfSession(first), "40.00");

        String body = payable(merchant).body();

        assertThat(field(body, "kind"))
                .as("the figure is folded from the lines, never read from the projection")
                .isEqualTo("DERIVED");
        assertThat(body)
                .contains("\"currency\":\"EUR\"")
                .contains("\"position\":\"153.60\"")
                .contains("\"captured\":\"200.00\"")
                .contains("\"fees\":\"6.40\"")
                .contains("\"refunded\":\"40.00\"")
                .contains("\"feesReturned\":\"0.00\"")
                .contains("\"other\":\"0.00\"");

        // AGAINST TWO INDEPENDENT AUTHORITIES: plain SQL over the journal, and the ledger's
        // own definition. The view is neither of them, and must equal both.
        try (Connection app = DatabaseRoles.application()) {
            assertThat(payablePositionMinor(app, merchant)).isEqualTo(153_60L);
            assertThat(derivedPayableMinor(app, merchant)).isEqualTo(153_60L);
        }
    }

    @Test
    @DisplayName("P6-TSK-010: the FOURTH cell - a RETURNED refund shows the fee coming back, its"
            + " share computed by P6-TSK-014's cumulative allocation")
    void aReturnedRefundShowsTheFeeComingBack() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L, "RETURNED");
        String checkout = purchase(merchant, payingCustomer());
        // HALF, deliberately: a FULL refund of a merchant-bound capture is refused as unfunded
        // today, which is its own finding and its own pinned test below.
        refund(intentOfSession(checkout), "50.00");

        String body = payable(merchant).body();

        assertThat(body)
                .contains("\"captured\":\"100.00\"")
                .contains("\"fees\":\"3.20\"")
                .contains("\"refunded\":\"50.00\"")
                .as("a credit on the payable in an entry that CREDITS clearing is a fee returned:"
                        + " half the capture returns half the fee")
                .contains("\"feesReturned\":\"1.60\"")
                .as("96.80 - 50.00 + 1.60")
                .contains("\"position\":\"48.40\"");
        assertThat(explainsItself(body)).isTrue();
    }

    // ----------------------------------------------------------------- the refund's funding

    @Test
    @DisplayName("P6-TSK-015 (P6-TSK-010's pinned finding, rewritten): a FULL refund of a"
            + " merchant-bound capture is funded by its NET - RETURNED lands the payable at"
            + " exactly zero, RETAINED at exactly minus the fee it keeps")
    void aFullMerchantRefundIsFundedByItsNet() throws Exception {
        // THE ASSERTION P6-TSK-010 PINNED FOR THIS TASK TO REWRITE. The refund's hold used to
        // judge the GROSS (100.00) against a payable holding the NET (96.80), and refused both
        // policies. It now reserves what the refund will take, asked of the composition that
        // writes the lines (ADR-0054):
        //   RETURNED - the gross out and the 3.20 fee back IN THE SAME ENTRY: 96.80, exactly.
        //   RETAINED - the gross out. The merchant funds the 96.80 net, and the 3.20 fee it
        //              keeps owing takes the payable below zero: the one credit a refund
        //              extends (INV-MER-07), recovered from the merchant's next captures.
        for (String policy : new String[] {"RETURNED", "RETAINED"}) {
            Merchant merchant = tradingMerchant("0.029", 30L, policy);
            String intent = intentOfSession(purchase(merchant, payingCustomer()));

            refund(intent, "100.00");

            String body = payable(merchant).body();
            assertThat(explainsItself(body)).as("policy %s: %s", policy, body).isTrue();
            assertThat(body)
                    .contains("\"captured\":\"100.00\"")
                    .contains("\"fees\":\"3.20\"")
                    .contains("\"refunded\":\"100.00\"");
            assertThat(heldOn(merchant))
                    .as("policy %s: ONE hold, of the NET, placed and released under the"
                            + " refund's own lifecycle", policy)
                    .containsExactly("9680:RELEASED");
            try (Connection app = DatabaseRoles.application()) {
                long position = payablePositionMinor(app, merchant);
                if (policy.equals("RETURNED")) {
                    assertThat(body)
                            .contains("\"feesReturned\":\"3.20\"")
                            .contains("\"position\":\"0.00\"");
                    assertThat(position).as("the reversal complete to the minor unit").isZero();
                } else {
                    assertThat(body)
                            .contains("\"feesReturned\":\"0.00\"")
                            .contains("\"position\":\"-3.20\"");
                    assertThat(position)
                            .as("the merchant owes the platform EXACTLY the fee it retained")
                            .isEqualTo(-3_20L);
                }
                assertThat(derivedPayableMinor(app, merchant)).isEqualTo(position);
            }
        }
    }

    @Test
    @DisplayName("P6-TSK-012 (inherited from P6-TSK-015): a refund dispatched while a payout is in"
            + " flight is judged against what the payout's hold leaves")
    void aRefundBesideAPayoutInFlightIsJudgedAgainstItsHold() throws Exception {
        // Driven for real, end to end: a checkout captures 100.00 (the payable holds its 96.80
        // net), a payout of 80.00 goes out and its provider never answers - UNKNOWN, its hold
        // STANDING - and a merchant refund is then judged against the payable's available
        // position, which is the net less every standing hold, the payout's included.
        Merchant merchant = tradingMerchant("0.029", 30L, "RETURNED");
        String intent = intentOfSession(purchase(merchant, payingCustomer()));
        effectiveDestinationFor(merchant);
        provider.neverResponds(com.finapp.merchant.SimulatedPayoutProvider.PAYOUTS_PATH);
        HttpResponse<String> payout =
                post(
                        "/v1/merchant/payouts",
                        "{\"amount\":\"80.00\",\"currency\":\"EUR\"}",
                        merchant.apiKey(),
                        someKey());
        assertThat(payout.statusCode()).as(payout.body()).isEqualTo(201);
        assertThat(field(payout.body(), "status")).isEqualTo("UNKNOWN");

        // 30.00 reserves its net, 29.04 - more than the 16.80 the payout's hold leaves.
        provider.succeedsWith(
                SimulatedCardPspAdapter.REFUNDS_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"psp_ref-" + UUID.randomUUID() + "\"}");
        HttpResponse<String> refused =
                post(
                        "/v1/payments/" + intent + "/refund",
                        "{\"amount\":\"30.00\",\"currency\":\"EUR\",\"reason\":\"goods returned\"}",
                        operatorSession(RoleName.LEDGER_OPERATOR),
                        someKey());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("payments.RefundUnfunded");

        // 10.00 reserves 9.68, which fits: the payable is judged, not frozen.
        refund(intent, "10.00");
        assertThat(heldOn(merchant))
                .as("the payout's hold still stands beside the released refund's")
                .containsExactlyInAnyOrder("8000:ACTIVE", "968:RELEASED");
    }

    @Test
    @DisplayName("P6-TSK-012 (inherited from P6-TSK-015): a payable left negative by a RETAINED fee"
            + " refuses every payout until a later capture restores it")
    void aPayableLeftNegativeByARetainedFeeRefusesEveryPayout() throws Exception {
        // Driven for real: a sale of 100.00 (the payable holds its 96.80 net) refunded in full
        // under RETAINED - the gross out, the 3.20 fee kept - leaves the payable owing 3.20.
        Merchant merchant = tradingMerchant("0.029", 30L, "RETAINED");
        refund(intentOfSession(purchase(merchant, payingCustomer())), "100.00");
        effectiveDestinationFor(merchant);
        provider.succeedsWith(
                com.finapp.merchant.SimulatedPayoutProvider.PAYOUTS_PATH,
                200,
                "{\"status\":\"paid\",\"reference\":\"po-{{request.headers.Idempotency-Key}}\"}");

        HttpResponse<String> owing = payoutOf(merchant, "0.01");
        assertThat(owing.statusCode()).as(owing.body()).isEqualTo(409);
        assertThat(owing.body()).contains("merchant.PayoutUnfunded");

        // The next sale repays the debt first: 96.80 - 3.20 = 93.60 may leave, and not a cent
        // more.
        purchase(merchant, payingCustomer());
        HttpResponse<String> over = payoutOf(merchant, "93.61");
        assertThat(over.statusCode()).as(over.body()).isEqualTo(409);
        HttpResponse<String> paid = payoutOf(merchant, "93.60");
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(201);
        assertThat(field(paid.body(), "status")).isEqualTo("COMPLETED");

        String body = payable(merchant).body();
        assertThat(explainsItself(body)).as(body).isTrue();
        assertThat(body).contains("\"paidOut\":\"93.60\"").contains("\"position\":\"0.00\"");
        try (Connection app = DatabaseRoles.application()) {
            assertThat(payablePositionMinor(app, merchant)).as("paid out to exactly zero").isZero();
            assertThat(derivedPayableMinor(app, merchant)).isZero();
        }
    }

    private HttpResponse<String> payoutOf(Merchant merchant, String amount) throws Exception {
        return post(
                "/v1/merchant/payouts",
                "{\"amount\":\"" + amount + "\",\"currency\":\"EUR\"}",
                merchant.apiKey(),
                someKey());
    }

    @Test
    @DisplayName("P6-TSK-015: a DECLINED merchant refund releases its net hold and moves nothing"
            + " - the reservation lives and dies with the refund's own lifecycle")
    void aDeclinedMerchantRefundReleasesItsHold() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L, "RETURNED");
        String intent = intentOfSession(purchase(merchant, payingCustomer()));
        provider.succeedsWith(
                SimulatedCardPspAdapter.REFUNDS_PATH, 200, "{\"status\":\"declined\"}");

        HttpResponse<String> declined = refundResponse(intent, "100.00");

        assertThat(declined.statusCode()).as(declined.body()).isEqualTo(201);
        assertThat(field(declined.body(), "status")).isEqualTo("FAILED");
        assertThat(heldOn(merchant))
                .as("the net was held while the provider decided, and released with nothing"
                        + " posted")
                .containsExactly("9680:RELEASED");
        assertThat(payable(merchant).body())
                .contains("\"refunded\":\"0.00\"")
                .contains("\"position\":\"96.80\"");
    }

    @Test
    @DisplayName("P6-TSK-015, INV-MER-07: the RETAINED fee share is the ONLY credit a refund"
            + " extends - a refund needing more than the payable holds is refused with nothing"
            + " written, and one within it is admitted")
    void aRetainedFeeIsTheOnlyCreditAMerchantIsExtended() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L, "RETAINED");
        refund(intentOfSession(purchase(merchant, payingCustomer())), "100.00");
        String second = intentOfSession(purchase(merchant, payingCustomer()));
        assertThat(payable(merchant).body())
                .as("the first sale's retained fee, recovered from the second's net")
                .contains("\"position\":\"93.60\"");

        // The second sale in full needs its own 96.80 net, and 3.20 of that already paid the
        // first sale's fee. Refused - and the refusal moves nothing. The provider mints a
        // reference per refund from here, so that a refund wrongly ADMITTED would complete and
        // say so, rather than collide with the previous refund's stubbed reference.
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.REFUNDS_PATH, "psp_rfd");
        HttpResponse<String> refused = refundResponse(second, "100.00");
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("payments.RefundUnfunded");
        assertThat(refundRowsFor(second)).as("nothing written").isZero();
        assertThat(payable(merchant).body()).contains("\"position\":\"93.60\"");

        // Within what the payable can fund, admitted: 90.00 reserves 87.12, its 2.88 share
        // carried as the merchant's debt.
        HttpResponse<String> admitted = refundResponse(second, "90.00");
        assertThat(admitted.statusCode()).as(admitted.body()).isEqualTo(201);
        assertThat(field(admitted.body(), "status")).isEqualTo("COMPLETED");
        assertThat(payable(merchant).body()).contains("\"position\":\"3.60\"");

        // And the remainder is EXACT - 10.00 net of its 0.32 share is 9.68 - against 3.60.
        HttpResponse<String> remainder = refundResponse(second, "10.00");
        assertThat(remainder.statusCode()).as(remainder.body()).isEqualTo(409);
        assertThat(remainder.body()).contains("payments.RefundUnfunded");

        try (Connection app = DatabaseRoles.application()) {
            assertThat(payablePositionMinor(app, merchant)).isEqualTo(3_60L);
            assertThat(derivedPayableMinor(app, merchant)).isEqualTo(3_60L);
        }
        assertThat(activeHoldsOn(merchant)).as("no refusal left anything standing").isZero();
    }

    @Test
    @DisplayName("P6-TSK-015: a refund that cannot know its fee share reserves the WORST case -"
            + " with the other half in flight the second half is refused, and in order both"
            + " land the payable at exactly zero")
    void aRefundThatCannotKnowItsShareReservesTheWorstCase() throws Exception {
        // A ONE-CENT fee, refunded in halves under RETURNED. The cent comes back with ONE of
        // the halves, and which one depends on the order they COMPLETE - the only order in
        // which the cumulative allocation telescopes (P6-TSK-014). So while one half is in
        // flight, the other may count on nothing.
        Merchant inOrder = tradingMerchant("0", 1L, "RETURNED");
        String sale = intentOfSession(purchase(inOrder, payingCustomer()));
        refund(sale, "50.00");
        refund(sale, "50.00");
        assertThat(payable(inOrder).body())
                .as("in order, the second half is the remainder, priced exactly, and the cent"
                        + " comes back")
                .contains("\"feesReturned\":\"0.01\"")
                .contains("\"position\":\"0.00\"");

        Merchant inFlight = tradingMerchant("0", 1L, "RETURNED");
        String other = intentOfSession(purchase(inFlight, payingCustomer()));
        provider.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.REFUNDS_PATH);
        HttpResponse<String> first = refundResponse(other, "50.00");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(field(first.body(), "status")).isEqualTo("UNKNOWN");

        // 99.99 in the payable; the first half holds its whole 50.00, and the second would
        // need 50.00 too. 49.99 is left.
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.REFUNDS_PATH, "psp_rfd");
        HttpResponse<String> second = refundResponse(other, "50.00");
        assertThat(second.statusCode()).as(second.body()).isEqualTo(409);
        assertThat(second.body()).contains("payments.RefundUnfunded");
        assertThat(heldOn(inFlight))
                .as("one hold: the first half's worst case, standing while its answer is lost")
                .containsExactly("5000:ACTIVE");
    }

    @Test
    @DisplayName("P6-TSK-015: a refund dispatched beside siblings IN FLIGHT reserves by what has"
            + " COMPLETED, never by what is merely reserved - a sibling can still fail")
    void aRefundBesideSiblingsInFlightReservesByWhatCompleted() throws Exception {
        // 0.03 on 100.00. A 25.00 refund returns 0.01 completing first or last and NOTHING
        // completing after only 50.00 - which happens if, of its two siblings in flight
        // (50.00 and 25.00), the first completes and the second fails. Read by the non-failed
        // sum (75.00) it would look like the remainder and count on the cent.
        Merchant merchant = tradingMerchant("0", 3L, "RETURNED");
        Customer customer = payingCustomer();
        String sale = intentOfSession(purchase(merchant, customer));
        purchase(merchant, customer); // headroom, so every hold below is funded
        provider.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.REFUNDS_PATH);
        for (String amount : new String[] {"50.00", "25.00", "25.00"}) {
            HttpResponse<String> dispatched = refundResponse(sale, amount);
            assertThat(field(dispatched.body(), "status")).as(dispatched.body()).isEqualTo("UNKNOWN");
        }

        assertThat(heldOn(merchant))
                .as("nothing has completed, so no share is knowable and every refund holds its"
                        + " whole amount - the last one included")
                .containsExactly("5000:ACTIVE", "2500:ACTIVE", "2500:ACTIVE");
    }

    @Test
    @DisplayName("P6-TSK-015: refunds RACING each other and a capture on one payable stay"
            + " inside the bound - counted: exactly the affordable set is admitted, a refusal"
            + " writes nothing, and the merchant's next capture funds what was refused")
    void refundsRacingOnOnePayableStayInsideTheBound() throws Exception {
        // RETAINED, so the payable can be SHORT of what it owes: four sales (387.20), the
        // fourth refunded in full - 287.20, its 3.20 fee now the merchant's debt. The other
        // three in full need 96.80 each, 290.40 in all: EXACTLY TWO fit.
        Merchant merchant = tradingMerchant("0.029", 30L, "RETAINED");
        Customer customer = payingCustomer();
        List<String> sales = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            sales.add(intentOfSession(purchase(merchant, customer)));
        }
        refund(intentOfSession(purchase(merchant, customer)), "100.00");
        assertThat(payable(merchant).body()).contains("\"position\":\"287.20\"");

        String operator = operatorSession(RoleName.LEDGER_OPERATOR);
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.REFUNDS_PATH, "psp_rfd");
        List<java.util.concurrent.Callable<HttpResponse<String>>> race = new ArrayList<>();
        for (String sale : sales) {
            race.add(() -> refundResponse(sale, "100.00", operator));
        }
        List<HttpResponse<String>> answers = concurrently(race);
        List<String> refused = new ArrayList<>();
        for (int i = 0; i < sales.size(); i++) {
            HttpResponse<String> answer = answers.get(i);
            if (answer.statusCode() == 201) {
                assertThat(field(answer.body(), "status")).isEqualTo("COMPLETED");
            } else {
                assertThat(answer.statusCode()).as(answer.body()).isEqualTo(409);
                assertThat(answer.body()).contains("payments.RefundUnfunded");
                refused.add(sales.get(i));
            }
        }
        assertThat(refused)
                .as("the payable's lock admits exactly the affordable set: two of three")
                .hasSize(1);
        assertThat(refundRowsFor(refused.get(0))).as("the refusal wrote nothing").isZero();
        assertThat(activeHoldsOn(merchant)).isZero();
        assertThat(heldOn(merchant))
                .as("three holds in all, each of a full refund's net, each released")
                .containsExactly("9680:RELEASED", "9680:RELEASED", "9680:RELEASED");
        try (Connection app = DatabaseRoles.application()) {
            assertThat(payablePositionMinor(app, merchant)).isEqualTo(87_20L);
        }

        // The refused refund RACES the merchant's next capture: funded only if the capture
        // commits before the hold is judged. Both outcomes are legal; each is counted, and a
        // refusal is then funded by the capture it lost to.
        String late = refused.get(0);
        List<java.util.concurrent.Callable<HttpResponse<String>>> second = new ArrayList<>();
        second.add(() -> refundResponse(late, "100.00", operator));
        second.add(
                () -> {
                    purchase(merchant, customer);
                    return null;
                });
        HttpResponse<String> raced = concurrently(second).get(0);
        if (raced.statusCode() == 409) {
            assertThat(raced.body()).contains("payments.RefundUnfunded");
            assertThat(refundRowsFor(late)).isZero();
            HttpResponse<String> funded = refundResponse(late, "100.00", operator);
            assertThat(funded.statusCode()).as(funded.body()).isEqualTo(201);
        } else {
            assertThat(raced.statusCode()).as(raced.body()).isEqualTo(201);
        }

        assertThat(activeHoldsOn(merchant)).isZero();
        assertThat(refundRowsFor(late)).isEqualTo(1);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(payablePositionMinor(app, merchant))
                    .as("five sales, four refunded in full under RETAINED: 5 x 96.80 - 400.00")
                    .isEqualTo(84_00L)
                    .isEqualTo(derivedPayableMinor(app, merchant));
        }
        assertThat(explainsItself(payable(merchant).body())).isTrue();
    }

    @Test
    @DisplayName("P6-TST-001, ADR-0058: a sale whose fee MEETS or EXCEEDS it is refused AT THE"
            + " PRICE - checkout.SaleBelowFee, nothing written, and the key not spent")
    void aSaleThatDoesNotCoverItsFeeIsRefusedAtThePrice() throws Exception {
        // P6-TSK-015's gate pinned what accepting such a sale did - the capture left the
        // payable below zero by the excess, and the sale's refund was refused as unfunded -
        // and handed the decision here. Decided at the price: a fixed part of 100.00 and no
        // rate, so 100.00 nets the merchant nothing and 50.00 nets it minus 50.00. The
        // merchant would pay to sell, so the offer is refused before anything exists.
        Merchant merchant = tradingMerchant("0", 100_00L, "RETURNED");
        String key = someKey();

        HttpResponse<String> meets = createSession(merchant, 100_00L, key);
        HttpResponse<String> exceeds = createSession(merchant, 50_00L, someKey());

        for (HttpResponse<String> refused : List.of(meets, exceeds)) {
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
            assertThat(refused.body()).contains("checkout.SaleBelowFee");
        }
        assertThat(sessionCount(merchant)).as("the refusal wrote nothing").isZero();

        // Refused BEFORE the claim, so the key is unspent: the same key under another body is
        // a first attempt. A spent key would refuse it as a different request (INV-IDEM-03).
        HttpResponse<String> covered = createSession(merchant, 100_01L, key);
        assertThat(covered.statusCode()).as(covered.body()).isEqualTo(201);
        assertThat(sessionCount(merchant)).isEqualTo(1);
    }

    @Test
    @DisplayName("P6-TST-001, ADR-0058: the smallest sale the rule admits - a net of ONE minor"
            + " unit - captures to a positive payable, and its full RETURNED refund is funded")
    void theSmallestCoveredSaleIsRefundableInFull() throws Exception {
        // The pinned finding's other half: under the rule no capture leaves a payable below
        // zero, so the RETURNED refund of a sale - its gross out, its whole fee back, a net
        // of 0.01 - is funded by the sale it reverses, and lands the payable at exactly zero.
        Merchant merchant = tradingMerchant("0", 100_00L, "RETURNED");
        String created = createSession(merchant, 100_01L, someKey()).body();
        providerAuthorises();
        providerCaptures();
        HttpResponse<String> confirmed =
                confirm(payingCustomer(), field(created, "sessionToken"));
        assertThat(field(confirmed.body(), "status")).isEqualTo("COMPLETED");
        String intent = intentOfSession(field(created, "checkoutId"));
        assertThat(payable(merchant).body()).contains("\"position\":\"0.01\"");

        refund(intent, "100.01");

        assertThat(refundRowsFor(intent)).isEqualTo(1);
        assertThat(activeHoldsOn(merchant)).isZero();
        String after = payable(merchant).body();
        assertThat(after).contains("\"position\":\"0.00\"");
        assertThat(explainsItself(after)).isTrue();
    }

    @Test
    @DisplayName("P6-TST-001: an offer in a currency the merchant's schedule does not price is"
            + " refused at CREATION as checkout.NotPriceable - never discovered at the capture")
    void anOfferInAnotherCurrencyIsNotPriceable() throws Exception {
        // Pricing the offer at creation is what surfaces this: the schedule prices EUR, the
        // settlement currency its assignment checked, and the fee arithmetic refuses a USD
        // gross by name. Unmapped, that refusal would have been a 500 at the surface.
        Merchant merchant = tradingMerchant("0.029", 30L);

        HttpResponse<String> refused = createSession(merchant, AMOUNT_MINOR, "USD", someKey());

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("checkout.NotPriceable");
        assertThat(sessionCount(merchant)).as("the refusal wrote nothing").isZero();
    }

    @Test
    @DisplayName("P6-TST-001, ADR-0058 section 3: a session opened BEFORE the rule is refused when"
            + " its fee is pinned - checkout.SaleBelowFee - and the intent it would have priced"
            + " rolls back with it")
    void aSessionOpenedBeforeTheRuleIsRefusedAtConfirmation() throws Exception {
        // ADDED BY THE COMPLETION GATE: the confirmation's mapping of the pin's refusal had no
        // test, so a missing catch would have answered this customer 500. Opened through the
        // store rather than the surface, because the surface now refuses it: this is the offer
        // an instance still running the old code accepted a moment before the rule shipped.
        Merchant merchant = tradingMerchant("0", 100_00L, "RETURNED");
        com.finapp.checkout.CheckoutSessionToken token =
                com.finapp.checkout.CheckoutSessionToken.issue(new SecureRandom());
        com.finapp.checkout.CheckoutSession session =
                com.finapp.checkout.CheckoutSession.open(
                        IDS,
                        CLOCK,
                        UUID.fromString(merchant.id()),
                        Money.ofMinorUnits(50_00L, CurrencyCode.of("EUR")),
                        "An offer from before the rule",
                        versionOf(merchant),
                        token,
                        java.time.Instant.now(CLOCK).plus(Duration.ofMinutes(30)));
        try (Connection app = DatabaseRoles.application()) {
            new com.finapp.checkout.JdbcCheckoutSessionStore().insert(app, session);
        }

        HttpResponse<String> refused = confirm(payingCustomer(), token.presentedOnce().expose());

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("checkout.SaleBelowFee");
        assertThat(statusAndIntentOf(session.id().value()))
                .as("the offer stands, unconfirmed")
                .isEqualTo("OPEN:null");
        assertThat(intentsCrediting(merchant))
                .as("the payment intent the pin would have priced rolled back with it")
                .isZero();
    }

    @Test
    @DisplayName("P6-TSK-010: UNDER LIVE TRAFFIC every response explains itself exactly, and at"
            + " rest the view equals the journal to the minor unit")
    void thePayableHoldsUnderLiveTraffic() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer customer = payingCustomer();
        int purchases = 8;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        java.util.concurrent.atomic.AtomicBoolean trading =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.List<String> inconsistent =
                java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger reads =
                new java.util.concurrent.atomic.AtomicInteger();
        try {
            Future<?> traffic =
                    pool.submit(
                            () -> {
                                try {
                                    for (int i = 0; i < purchases; i++) {
                                        purchase(merchant, customer);
                                    }
                                } finally {
                                    trading.set(false);
                                }
                                return null;
                            });
            Future<?> reader =
                    pool.submit(
                            () -> {
                                while (trading.get()) {
                                    String body = payable(merchant).body();
                                    reads.incrementAndGet();
                                    if (!explainsItself(body)) {
                                        inconsistent.add(body);
                                    }
                                }
                                return null;
                            });
            traffic.get(300, TimeUnit.SECONDS);
            reader.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(reads.get()).as("the view was actually read while money moved").isPositive();
        assertThat(inconsistent)
                .as("THE POINT OF ONE STATEMENT: a response whose terms did not sum to its own"
                        + " position would mean the figure and its explanation came from two"
                        + " snapshots, with a capture committed between them")
                .isEmpty();

        String atRest = payable(merchant).body();
        try (Connection app = DatabaseRoles.application()) {
            assertThat(new java.math.BigDecimal(field(atRest, "position"))
                            .movePointRight(2)
                            .longValueExact())
                    .as("at rest: the view, the journal and the definition agree")
                    .isEqualTo(payablePositionMinor(app, merchant))
                    .isEqualTo(derivedPayableMinor(app, merchant))
                    .isEqualTo(purchases * 96_80L);
        }
    }

    @Test
    @DisplayName("P6-TSK-010: ONE SNAPSHOT, proved DETERMINISTICALLY - a posting committed in the"
            + " middle of the view's read cannot make its terms disagree with its position")
    void aPostingMidReadCannotSplitTheFigureFromItsTerms() throws Exception {
        // THE LIVE-TRAFFIC TEST ABOVE CANNOT PROVE THIS, and the mutation battery showed it: a
        // version that read the position in a SECOND statement survived, because a capture
        // landing in the microseconds between two statements is too improbable for traffic to
        // produce on demand. So the race is not waited for - it is MADE. The view's connection
        // is wrapped so that the instant its line read returns, another connection commits a
        // posting to the same payable. Under READ COMMITTED any LATER statement sees that
        // posting and the line read does not; a design with only one statement has no later
        // statement to see it, which is the whole claim.
        Merchant merchant = tradingMerchant("0.029", 30L);
        purchase(merchant, payingCustomer());
        com.finapp.merchant.MerchantPayable view =
                new com.finapp.merchant.MerchantPayable(
                        new com.finapp.ledger.JdbcLedgerAccountStore(),
                        new com.finapp.ledger.JdbcPositionBreakdown());

        java.util.List<com.finapp.merchant.MerchantPayable.Payable> read;
        try (Connection real = DatabaseRoles.application()) {
            real.setAutoCommit(false);
            Connection interposing =
                    commitAfterTheLineRead(real, () -> postOneEuroToThePayable(merchant));
            read =
                    view.payablesOf(
                            interposing,
                            com.finapp.merchant.MerchantId.of(UUID.fromString(merchant.id())));
            real.commit();
        }

        com.finapp.merchant.MerchantPayable.Payable payable = read.get(0);
        assertThat(payable.terms())
                .as("the figure and its explanation came from the same lines")
                .isEqualTo(payable.position());
        assertThat(payable.position().minorUnits())
                .as("and the posting that landed mid-read is in NEITHER - it is in the next read")
                .isEqualTo(96_80L);
        assertThat(new java.math.BigDecimal(field(payable(merchant).body(), "position")))
                .isEqualByComparingTo("97.80");
    }

    @Test
    @DisplayName("P6-TSK-010, INV-MER-01: one merchant's traffic moves no other merchant's"
            + " payable - the route takes no identifier, so there is nothing to ask about")
    void oneMerchantsTrafficMovesNoOtherPayable() throws Exception {
        Merchant a = tradingMerchant("0.029", 30L);
        Merchant b = tradingMerchant("0.029", 30L);
        purchase(a, payingCustomer());

        assertThat(payable(b).body()).contains("\"position\":\"0.00\"");
        assertThat(payable(a).body()).contains("\"position\":\"96.80\"");
    }

    // ----------------------------------------------------------------- suspension

    @Test
    @DisplayName("a SUSPENDED merchant's open offer cannot be PAID - the confirmation refuses as"
            + " NotTrading and writes nothing (PHASE_6_PLAN section 14, scenario 11)")
    void aSuspendedMerchantsOpenSessionCannotBePaid() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer customer = payingCustomer();
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        suspend(merchant);

        // P6-DOC-001. Only the creation asked the merchant's standing, so an offer made while
        // the merchant traded could be paid after its suspension: the plan's scenario 11 said
        // the confirmation refused, and nothing tested it.
        HttpResponse<String> refused = confirm(customer, field(created, "sessionToken"));
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("checkout.NotTrading");
        assertThat(statusAndIntentOf(UUID.fromString(field(created, "checkoutId"))))
                .as("still the unpaid offer it was, carrying no intent")
                .isEqualTo("OPEN:null");
        assertThat(intentsCrediting(merchant))
                .as("refused before anything was written: no intent, so no pin and no dispatch")
                .isZero();
    }

    /**
     * The standing check's rank beneath the close's own refusal, raced deterministically (the
     * Phase 6 -> 7 transition): a close that judged before this payment existed holds the merchant
     * row, mid-transaction, with its move made. The confirmation's FOR SHARE must wait for it, then
     * read CLOSED and refuse - before this, it read the old ACTIVE row and opened a payment that
     * would land on a merchant nothing could pay out.
     */
    @Test
    @DisplayName("a confirmation racing the merchant's close waits on the merchant row and then"
            + " refuses as NotTrading - nothing opened, nothing dispatched")
    void aConfirmationRacingTheMerchantsCloseWaitsAndRefuses() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer customer = payingCustomer();
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        providerAuthorises();
        providerCaptures();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection closer = DatabaseRoles.application()) {
            closer.setAutoCommit(false);
            try (PreparedStatement lock =
                    closer.prepareStatement(
                            "SELECT id FROM merchant.merchant WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, UUID.fromString(merchant.id()));
                lock.executeQuery().close();
            }
            try (PreparedStatement close =
                    closer.prepareStatement(
                            "UPDATE merchant.merchant SET status = 'CLOSED',"
                                    + " status_changed_at = GREATEST(now(), status_changed_at)"
                                    + " WHERE id = ?")) {
                close.setObject(1, UUID.fromString(merchant.id()));
                assertThat(close.executeUpdate()).isEqualTo(1);
            }

            Future<HttpResponse<String>> confirming =
                    pool.submit(() -> confirm(customer, field(created, "sessionToken")));
            awaitLockWaitOn("merchant.merchant", "FOR SHARE");
            closer.commit();

            HttpResponse<String> refused = confirming.get(60, TimeUnit.SECONDS);
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
            assertThat(refused.body()).contains("checkout.NotTrading");
        } finally {
            pool.shutdownNow();
        }
        assertThat(statusAndIntentOf(UUID.fromString(field(created, "checkoutId"))))
                .as("still the unpaid offer it was, carrying no intent")
                .isEqualTo("OPEN:null");
        assertThat(intentsCrediting(merchant)).isZero();
    }

    /**
     * I9 of the Phase 6 -> 7 transition: the payment a confirmation opens is claimed under a key
     * derived from the session, and a derived key is predictable. In the public command's scope a
     * stranger's own top-up could claim it first, and the payer's confirmation would then meet a
     * fingerprint it can never match for as long as the session lives.
     */
    @Test
    @DisplayName("a stranger who claims the session's derived key through POST /v1/payments FIRST"
            + " blocks nothing - the confirmation claims in a scope of its own")
    void aDerivedKeyClaimedThroughThePublicCommandBlocksNothing() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer payer = payingCustomer();
        Customer squatter = payingCustomer();
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        String checkoutId = field(created, "checkoutId");

        // The squatter's own, perfectly valid top-up - keyed with the value the confirmation
        // will derive.
        openWallet(squatter);
        HttpResponse<String> squatted =
                post(
                        "/v1/payments",
                        "{\"paymentMethodId\":\"" + squatter.methodId()
                                + "\",\"amount\":\"5.00\",\"currency\":\"EUR\"}",
                        squatter.token(),
                        "checkout:" + checkoutId);
        assertThat(squatted.statusCode()).as(squatted.body()).isEqualTo(201);

        providerAuthorises();
        providerCaptures();
        HttpResponse<String> paid = confirm(payer, field(created, "sessionToken"));
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(200);
        assertThat(field(paid.body(), "status")).isEqualTo("COMPLETED");
        assertThat(field(paid.body(), "paymentIntentId"))
                .as("the payer's own payment, not the squatter's top-up")
                .isNotEqualTo(field(squatted.body(), "id"));
        try (Connection app = DatabaseRoles.application()) {
            assertThat(payablePositionMinor(app, merchant)).isEqualTo(96_80L);
        }
    }

    /**
     * Sibling #9 of the Phase 6 -> 7 transition, raced deterministically: both confirmations read
     * the session OPEN and are held at the merchant row before either can claim. The first to
     * claim opens the payment; the second replays that intent at the claim and then finds the
     * session moved. It was answered checkout.NotConfirmable naming EXPIRED - a double-click told
     * the customer their offer was dead while their payment went through.
     */
    @Test
    @DisplayName("a confirmation that LOSES the open to a concurrent one converges on the payment"
            + " the winner opened - never told the session expired")
    void aConfirmationThatLosesTheOpenConverges() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer customer = payingCustomer();
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        String token = field(created, "sessionToken");
        providerAuthorises();
        providerCaptures();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<HttpResponse<String>>> racers = new ArrayList<>();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock =
                    holder.prepareStatement(
                            "SELECT id FROM merchant.merchant WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, UUID.fromString(merchant.id()));
                lock.executeQuery().close();
            }
            racers.add(pool.submit(() -> confirm(customer, token)));
            racers.add(pool.submit(() -> confirm(customer, token)));
            // Both have read the session OPEN and passed every gate before the standing read.
            awaitLockWaitOn("merchant.merchant", "FOR SHARE", 2);
            holder.commit();

            List<String> intents = new ArrayList<>();
            for (Future<HttpResponse<String>> racer : racers) {
                HttpResponse<String> answer = racer.get(60, TimeUnit.SECONDS);
                assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
                assertThat(answer.body()).doesNotContain("checkout.");
                intents.add(field(answer.body(), "paymentIntentId"));
            }
            assertThat(intents).as("one payment between them").doesNotContainNull();
            assertThat(intents.get(0)).isEqualTo(intents.get(1));
        } finally {
            pool.shutdownNow();
        }
        try (Connection app = DatabaseRoles.application()) {
            assertThat(orderCountFor(app, merchant)).isEqualTo(1);
            assertThat(payablePositionMinor(app, merchant)).isEqualTo(96_80L);
        }
    }

    /** S-F4 of the Phase 6 -> 7 transition: it was a 500. */
    @Test
    @DisplayName("a confirmation naming an instrument that is not the payer's is the create door's"
            + " UnknownInstrument - nothing written, and the session still pays")
    void aForeignInstrumentIsRefusedAndWritesNothing() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer payer = payingCustomer();
        Customer other = payingCustomer();
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        String token = field(created, "sessionToken");

        HttpResponse<String> refused =
                confirm(new Customer(payer.token(), other.methodId()), token);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("payments.UnknownInstrument");
        assertThat(statusAndIntentOf(UUID.fromString(field(created, "checkoutId"))))
                .as("nothing opened")
                .isEqualTo("OPEN:null");
        assertThat(intentsCrediting(merchant)).isZero();

        providerAuthorises();
        providerCaptures();
        HttpResponse<String> paid = confirm(payer, token);
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(200);
        assertThat(field(paid.body(), "status")).isEqualTo("COMPLETED");
    }

    /** S-F5 of the Phase 6 -> 7 transition: the payments surface's own words leaked the state. */
    @Test
    @DisplayName("a second holder of the token on a session MID-PAYMENT gets the one session 404 -"
            + " the same answer as a token that opens nothing")
    void aStrangerMidPaymentGetsTheOneSessionNotFound() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer payer = payingCustomer();
        Customer stranger = payingCustomer();
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        String token = field(created, "sessionToken");
        providerAuthorises();
        provider.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.CAPTURES_PATH);
        HttpResponse<String> pending = confirm(payer, token);
        assertThat(field(pending.body(), "status")).isEqualTo("PAYMENT_PENDING");

        HttpResponse<String> refused = confirm(stranger, token);
        HttpResponse<String> unknown = confirm(stranger, UUID.randomUUID().toString());
        assertThat(refused.statusCode()).isEqualTo(404);
        assertThat(field(refused.body(), "detail"))
                .as("nothing distinguishes a live session mid-payment from no session at all")
                .isEqualTo(field(unknown.body(), "detail"));
        assertThat(field(refused.body(), "code")).isEqualTo(field(unknown.body(), "code"));
        assertThat(refused.body())
                .doesNotContain(field(pending.body(), "paymentIntentId"))
                .doesNotContain("payment");
    }

    private void openWallet(Customer customer) throws Exception {
        HttpResponse<String> opened =
                post(
                        "/v1/me/accounts",
                        "{\"productType\":\"WALLET\",\"currency\":\"EUR\"}",
                        customer.token(),
                        someKey());
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
    }

    /** Until a backend waits on a lock for a statement touching {@code table} with {@code marker}. */
    private static void awaitLockWaitOn(String table, String marker)
            throws SQLException, InterruptedException {
        awaitLockWaitOn(table, marker, 1);
    }

    /** Until {@code backends} wait on a lock for a statement touching {@code table} with {@code marker}. */
    private static void awaitLockWaitOn(String table, String marker, int backends)
            throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try (Connection observer = DatabaseRoles.application();
                PreparedStatement select =
                        observer.prepareStatement(
                                "SELECT count(*) FROM pg_stat_activity"
                                        + " WHERE wait_event_type = 'Lock'"
                                        + " AND query LIKE ? AND query LIKE ?")) {
            select.setString(1, "%" + table + "%");
            select.setString(2, "%" + marker + "%");
            while (System.nanoTime() < deadline) {
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    if (row.getLong(1) >= backends) {
                        return;
                    }
                }
                Thread.sleep(25);
            }
        }
        throw new AssertionError(
                "no statement ever waited on " + table + " " + marker + " - without the lock the"
                        + " race is decided by a stale snapshot");
    }

    @Test
    @DisplayName("a payment ADMITTED before the suspension still lands - suspension gates new"
            + " dispatches, never an arrived outcome (scenario 11's second half)")
    void aPaymentInFlightAtSuspensionStillLands() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer customer = payingCustomer();
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        String checkoutId = field(created, "checkoutId");

        // Admitted while the merchant trades: the capture is dispatched and its answer lost.
        providerAuthorises();
        provider.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.CAPTURES_PATH);
        HttpResponse<String> confirmed = confirm(customer, field(created, "sessionToken"));
        assertThat(field(confirmed.body(), "status")).isEqualTo("PAYMENT_PENDING");

        suspend(merchant);

        String attemptId = attemptIdForSession(checkoutId);
        String captureRef = captureReference(attemptId);
        provider.succeedsWith(
                SimulatedCardPspAdapter.OPERATIONS_PATH + captureRef,
                200,
                "{\"status\":\"approved\",\"reference\":\"" + captureRef + "\"}");
        paymentSweeper().sweep();

        try (Connection app = DatabaseRoles.application()) {
            assertThat(sessionStatus(checkoutId)).isEqualTo("COMPLETED");
            assertThat(linePurposes(app, attemptId))
                    .as("the suspended merchant's capture posts the same four lines")
                    .containsExactlyInAnyOrder(
                            "DEBIT:SETTLEMENT_CLEARING",
                            "CREDIT:MERCHANT_PAYABLE",
                            "DEBIT:MERCHANT_PAYABLE",
                            "CREDIT:FEE_REVENUE");
            assertThat(payablePositionMinor(app, merchant))
                    .as("the money it was owed before the suspension is still owed to it")
                    .isEqualTo(96_80L);
            assertThat(orderCountFor(app, merchant)).isEqualTo(1);
        }
    }

    // ----------------------------------------------------------------- the phase's race

    @Test
    @DisplayName("INV-MER-06, THE RACE THE OTHER WAY: the offer expires while the capture is"
            + " in flight, and the money still lands - COMPLETED_LATE, the merchant credited"
            + " GROSS MINUS FEE, the order created")
    void aCaptureLandingAfterExpiryStillProducesTheOrder() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer customer = payingCustomer();
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        String checkoutId = field(created, "checkoutId");

        // THE ORDERING THAT MAKES THIS THE PHASE'S NAMED RACE, produced the way production
        // produces it rather than by editing a row: the capture is DISPATCHED and the
        // provider's answer is LOST. The customer sees the honest PROCESSING (INV-LIFE-03),
        // the session stays PAYMENT_PENDING, and nothing is posted.
        double lateBefore = sessionMeter("completed_late");
        long lateConversionsBefore = conversions("completed_late");
        providerAuthorises();
        provider.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.CAPTURES_PATH);
        HttpResponse<String> confirmed = confirm(customer, field(created, "sessionToken"));
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(field(confirmed.body(), "status")).isEqualTo("PAYMENT_PENDING");
        try (Connection app = DatabaseRoles.application()) {
            assertThat(payablePositionMinor(app, merchant))
                    .as("an ambiguous capture posts NOTHING")
                    .isZero();
        }

        // THE CLOCK RUNS OUT while the provider is still thinking. Grace ZERO, because what
        // this test is about is the edge, not the margin (the margin is
        // CheckoutExpiryDatabaseTest's).
        expirySweeper(Clock.offset(CLOCK, Duration.ofMinutes(31)), Duration.ZERO).sweep();
        assertThat(sessionStatus(checkoutId)).isEqualTo("EXPIRED");

        // AND THEN THE PROVIDER ANSWERS. The payments sweeper resolves the stranded capture
        // through the SAME PaymentOutcomes every resolver shares, which reaches checkout
        // through the composition seam - so the late completion is production's own path.
        String attemptId = attemptIdForSession(checkoutId);
        String captureRef = captureReference(attemptId);
        provider.succeedsWith(
                SimulatedCardPspAdapter.OPERATIONS_PATH + captureRef,
                200,
                "{\"status\":\"approved\",\"reference\":\"" + captureRef + "\"}");
        paymentSweeper().sweep();

        try (Connection app = DatabaseRoles.application()) {
            // LANDED MONEY IS NEVER ORPHANED BY A CLOCK - the whole of INV-MER-06, and every
            // consequence of it in one place:
            assertThat(sessionStatus(checkoutId))
                    .as("countable, not laundered into the ordinary completion")
                    .isEqualTo("COMPLETED_LATE");
            assertThat(linePurposes(app, attemptId))
                    .as("ADR-0050 section 3's four lines, unchanged by the lateness")
                    .containsExactlyInAnyOrder(
                            "DEBIT:SETTLEMENT_CLEARING",
                            "CREDIT:MERCHANT_PAYABLE",
                            "DEBIT:MERCHANT_PAYABLE",
                            "CREDIT:FEE_REVENUE");
            assertThat(payablePositionMinor(app, merchant))
                    .as("NO LANDED CENT UNEXPLAINED: the same 96.80 the timely ordering pays")
                    .isEqualTo(96_80L);
            assertThat(orderCountFor(app, merchant))
                    .as("the commercial fact exists - an order born from an expired offer")
                    .isEqualTo(1);
            assertThat(historyRow(app, checkoutId, "EXPIRED", "COMPLETED_LATE"))
                    .as("the late edge is in history, named, once")
                    .isEqualTo(1);
            assertThat(orderPaidPayload(app, checkoutId))
                    .as("and announced as the late ending it was, not as the EXPIRED it came from")
                    .contains("\"completedAs\":\"COMPLETED_LATE\"");
        }

        // THE METER, THROUGH THE WIRED PATH (PHASE_6_PLAN section 15): finapp.checkout.session
        // by outcome, one increment per ENDING, on the conditional that actually committed.
        // completed_late is the number this whole task exists to make visible - if it is not
        // rare, either the offer window is too short or the provider is too slow, and an
        // operator cannot decide either without the figure.
        assertThat(sessionMeter("completed_late") - lateBefore)
                .as("the late completion, counted once")
                .isEqualTo(1.0d);
        assertThat(conversions("completed_late") - lateConversionsBefore)
                .as("and timed in its OWN series (P6-TSK-013): late money measures the provider,"
                        + " and must not swallow the in-window mean")
                .isEqualTo(1L);
        // The `expired` counter is the SCHEDULE's to increment, from the tick's own tally
        // after each row's transaction committed, and this test drives the sweeper directly.
        // Its wiring is CheckoutExpirySweeperScheduleTest's, where the schedule is the
        // subject - asserting it here would assert a path this test does not take.
    }

    // ----------------------------------------------------------------- pay-by-bank (P7-TSK-009)

    @Test
    @DisplayName("a checkout paid by bank: the payer's handle rendered on the PAYER's answer"
            + " and never the merchant's, the executed callback completing the session with"
            + " ADR-0050's four lines on the INSTANT rail's position (INV-RAIL-04)")
    void aCheckoutIsPaidByBank() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer payer = bankPayingCustomer();
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        String checkoutId = field(created, "checkoutId");

        schemeInitiatesPayIn("https://payer-psp.example/authorize/chk-" + UUID.randomUUID());
        HttpResponse<String> confirmed = confirm(payer, field(created, "sessionToken"));
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(field(confirmed.body(), "status")).isEqualTo("PAYMENT_PENDING");
        // The handle reaches the PAYER - the one checkout rendering that may carry it.
        assertThat(field(confirmed.body(), "authorizationHandle"))
                .startsWith("https://payer-psp.example/authorize/chk-");
        // The MERCHANT's view of the same session carries NO handle: a merchant able to
        // follow it could complete or observe the payer's flow (P7-TSK-009).
        assertThat(merchantView(merchant, checkoutId).body())
                .contains("\"authorizationHandle\":null");

        // The payer executes at their PSP; the scheme confirms through the signed door.
        String attemptId = attemptIdForSession(checkoutId);
        assertThat(instantCallback(
                        "{\"eventId\":\"evt_" + UUID.randomUUID() + "\",\"reference\":\""
                                + endToEndReferenceOf(attemptId)
                                + "\",\"status\":\"executed\",\"schemeReference\":\"sch-chk-"
                                + UUID.randomUUID() + "\",\"settlementCycle\":\"C2\","
                                + "\"amount\":\"100.00\",\"currency\":\"EUR\"}"))
                .isEqualTo(204);

        try (Connection app = DatabaseRoles.application()) {
            assertThat(sessionStatus(checkoutId)).isEqualTo("COMPLETED");
            assertThat(linePurposes(app, attemptId))
                    .as("ADR-0050 section 3's four lines, on the INSTANT rail's own"
                            + " clearing position - never the card PSP's (INV-RAIL-04,"
                            + " INV-MER-06's second rail)")
                    .containsExactlyInAnyOrder(
                            "DEBIT:INSTANT_CLEARING",
                            "CREDIT:MERCHANT_PAYABLE",
                            "DEBIT:MERCHANT_PAYABLE",
                            "CREDIT:FEE_REVENUE");
            assertThat(payablePositionMinor(app, merchant))
                    .as("the merchant credited gross minus fee: 100.00 - 3.20")
                    .isEqualTo(96_80L);
            assertThat(orderCountFor(app, merchant)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("INV-MER-06'S SECOND RAIL: the offer expires while the payer deliberates at"
            + " their PSP, the execution lands anyway - COMPLETED_LATE, the merchant"
            + " credited, the order created")
    void aBankExecutionLandingAfterExpiryStillProducesTheOrder() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer payer = bankPayingCustomer();
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        String checkoutId = field(created, "checkoutId");

        double lateBefore = sessionMeter("completed_late");
        schemeInitiatesPayIn("https://payer-psp.example/authorize/late-" + UUID.randomUUID());
        HttpResponse<String> confirmed = confirm(payer, field(created, "sessionToken"));
        assertThat(field(confirmed.body(), "status")).isEqualTo("PAYMENT_PENDING");

        // THE CLOCK RUNS OUT while the payer is still at their PSP - the pay-by-bank shape
        // of the phase's named race: the payer's clock is not ours (ADR-0062 section 5).
        expirySweeper(Clock.offset(CLOCK, Duration.ofMinutes(31)), Duration.ZERO).sweep();
        assertThat(sessionStatus(checkoutId)).isEqualTo("EXPIRED");

        // AND THEN THE PAYER EXECUTES. The confirmation lands through the signed door,
        // reaches checkout through the SAME composition seam as the card's late capture -
        // so the second rail's late completion is production's own path, not a parallel one.
        String attemptId = attemptIdForSession(checkoutId);
        assertThat(instantCallback(
                        "{\"eventId\":\"evt_" + UUID.randomUUID() + "\",\"reference\":\""
                                + endToEndReferenceOf(attemptId)
                                + "\",\"status\":\"executed\",\"schemeReference\":\"sch-late-"
                                + UUID.randomUUID() + "\",\"amount\":\"100.00\","
                                + "\"currency\":\"EUR\"}"))
                .isEqualTo(204);

        try (Connection app = DatabaseRoles.application()) {
            assertThat(sessionStatus(checkoutId))
                    .as("landed money is never orphaned by a clock, on the second rail"
                            + " either (INV-MER-06)")
                    .isEqualTo("COMPLETED_LATE");
            assertThat(payablePositionMinor(app, merchant)).isEqualTo(96_80L);
            assertThat(orderCountFor(app, merchant)).isEqualTo(1);
            assertThat(historyRow(app, checkoutId, "EXPIRED", "COMPLETED_LATE")).isEqualTo(1);
            assertThat(orderPaidPayload(app, checkoutId))
                    .contains("\"completedAs\":\"COMPLETED_LATE\"");
        }
        assertThat(sessionMeter("completed_late") - lateBefore).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("PHASE_7_PLAN section 14.14: the merchant is suspended while the payer is at"
            + " their PSP - the pay-by-bank payment admitted before the suspension lands, the"
            + " merchant credited, the order created (the Phase 6 rule, on the second rail)")
    void aBankPaymentAdmittedBeforeTheSuspensionLands() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer payer = bankPayingCustomer();
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        String checkoutId = field(created, "checkoutId");

        // Admitted while the merchant trades: the initiation opened, the payer handed off.
        schemeInitiatesPayIn("https://payer-psp.example/authorize/sus-" + UUID.randomUUID());
        HttpResponse<String> confirmed = confirm(payer, field(created, "sessionToken"));
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(field(confirmed.body(), "status")).isEqualTo("PAYMENT_PENDING");

        suspend(merchant);
        // The suspension stops NEW work - the merchant's key stops authenticating, so a second
        // offer is refused - but not money already moving toward an offer made while trading.
        assertThat(createSession(merchant, AMOUNT_MINOR, someKey()).statusCode()).isEqualTo(401);

        // AND THEN THE PAYER EXECUTES: the scheme confirms through the signed door.
        String attemptId = attemptIdForSession(checkoutId);
        assertThat(instantCallback(
                        "{\"eventId\":\"evt_" + UUID.randomUUID() + "\",\"reference\":\""
                                + endToEndReferenceOf(attemptId)
                                + "\",\"status\":\"executed\",\"schemeReference\":\"sch-sus-"
                                + UUID.randomUUID() + "\",\"settlementCycle\":\"C2\","
                                + "\"amount\":\"100.00\",\"currency\":\"EUR\"}"))
                .isEqualTo(204);

        try (Connection app = DatabaseRoles.application()) {
            assertThat(sessionStatus(checkoutId)).isEqualTo("COMPLETED");
            assertThat(linePurposes(app, attemptId))
                    .as("the suspended merchant's sale posts the same four lines, on the"
                            + " INSTANT rail's own position")
                    .containsExactlyInAnyOrder(
                            "DEBIT:INSTANT_CLEARING",
                            "CREDIT:MERCHANT_PAYABLE",
                            "DEBIT:MERCHANT_PAYABLE",
                            "CREDIT:FEE_REVENUE");
            assertThat(payablePositionMinor(app, merchant))
                    .as("the money it was owed before the suspension is still owed to it")
                    .isEqualTo(96_80L);
            assertThat(orderCountFor(app, merchant)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("P7-TSK-010: a merchant-bound bank checkout returns IN FULL as a NEW push"
            + " citing the original - funded by its NET, the RETURNED fee coming back, the"
            + " payable landing at exactly zero, every line on the INSTANT rail's position")
    void aBankCheckoutReturnsInFull() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L, "RETURNED");
        Customer payer = bankPayingCustomer();
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        String checkoutId = field(created, "checkoutId");
        schemeInitiatesPayIn("https://payer-psp.example/authorize/ret-" + UUID.randomUUID());
        assertThat(confirm(payer, field(created, "sessionToken")).statusCode()).isEqualTo(200);
        String attemptId = attemptIdForSession(checkoutId);
        String originalScheme = "sch-chk-ret-" + UUID.randomUUID();
        assertThat(instantCallback(
                        "{\"eventId\":\"evt_" + UUID.randomUUID() + "\",\"reference\":\""
                                + endToEndReferenceOf(attemptId)
                                + "\",\"status\":\"executed\",\"schemeReference\":\""
                                + originalScheme + "\",\"settlementCycle\":\"C2\","
                                + "\"amount\":\"100.00\",\"currency\":\"EUR\"}"))
                .isEqualTo(204);

        // The return, through the operator surface and the REAL push adapter.
        provider.succeedsWith(
                com.finapp.payments.SimulatedInstantSchemeAdapter.RETURNS_PATH,
                200,
                "{\"status\":\"accepted\",\"reference\":\"sch-chk-ret-tx-"
                        + UUID.randomUUID() + "\",\"cycle\":\"C3\"}");
        HttpResponse<String> returned =
                refundResponse(intentOfSession(checkoutId), "100.00");
        assertThat(returned.statusCode()).as(returned.body()).isEqualTo(201);
        assertThat(field(returned.body(), "status")).isEqualTo("COMPLETED");
        String refundId = field(returned.body(), "id");

        try (Connection app = DatabaseRoles.application()) {
            // The wire cited the ORIGINAL's scheme reference (INV-RAIL-03's checkout leg).
            assertThat(provider.bodyValues(
                                    com.finapp.payments.SimulatedInstantSchemeAdapter
                                            .RETURNS_PATH)
                            .get(0))
                    .contains("\"originalReference\":\"" + originalScheme + "\"");
            // The refund entry: the capture's exact FOUR-LINE inverse (ADR-0054, the
            // RETURNED cell) - gross back onto the INSTANT rail's own position, never
            // the card PSP's (INV-RAIL-04): DR payable gross / CR clearing gross, and
            // the fee back DR FEE_REVENUE / CR payable, netting the payable -96.80.
            try (PreparedStatement read =
                    app.prepareStatement(
                            "SELECT string_agg(l.direction || ':' || a.purpose, ','"
                                    + " ORDER BY l.direction || ':' || a.purpose)"
                                    + " FROM ledger.journal_line l"
                                    + " JOIN ledger.journal_entry e ON e.id = l.entry_id"
                                    + " JOIN ledger.ledger_account a"
                                    + "   ON a.id = l.ledger_account_id"
                                    + " WHERE e.idempotency_scope ="
                                    + " 'ledger.post:payment-refund:" + refundId + "'")) {
                try (ResultSet row = read.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1))
                            .isEqualTo("CREDIT:INSTANT_CLEARING,CREDIT:MERCHANT_PAYABLE,"
                                    + "DEBIT:FEE_REVENUE,DEBIT:MERCHANT_PAYABLE");
                }
            }
            assertThat(payablePositionMinor(app, merchant))
                    .as("96.80 - 96.80: the full return of a RETURNED-fee sale lands the"
                            + " payable at exactly zero (P6-TSK-015's rule, second rail)")
                    .isZero();
        }
        assertThat(payable(merchant).body())
                .contains("\"refunded\":\"100.00\"")
                .contains("\"feesReturned\":\"3.20\"")
                .contains("\"position\":\"0.00\"");
    }

    // ----------------------------------------------------------------- pay from wallet (P7-TSK-011)

    @Test
    @DisplayName("P7-TSK-011: a checkout paid FROM THE WALLET completes in ONE transaction -"
            + " ADR-0050's four lines with the wallet as the counterpart, the order born,"
            + " the decision pinned book|4, the wallet explained to the cent")
    void aCheckoutIsPaidFromTheWallet() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        WalletCustomer payer = walletCustomer("150.00");
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        String checkoutId = field(created, "checkoutId");

        HttpResponse<String> confirmed =
                confirmFromWallet(payer.token(), field(created, "sessionToken"));
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(field(confirmed.body(), "status"))
                .as("final on posting: no PAYMENT_PENDING moment exists on the book rail")
                .isEqualTo("COMPLETED");

        String attemptId = attemptIdForSession(checkoutId);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(oneString(app,
                            "SELECT status || '|' || interaction_model || '|' || rail"
                                    + " FROM payments.payment_attempt WHERE id = ?",
                            UUID.fromString(attemptId)))
                    .isEqualTo("EXECUTED|BOOK|book");
            assertThat(linePurposes(app, attemptId))
                    .as("ADR-0050 section 3's four lines with the PAYER'S WALLET as the"
                            + " counterpart - no clearing exists to stand between"
                            + " (ADR-0059 section 6)")
                    .containsExactlyInAnyOrder(
                            "DEBIT:CUSTOMER_WALLET",
                            "CREDIT:MERCHANT_PAYABLE",
                            "DEBIT:MERCHANT_PAYABLE",
                            "CREDIT:FEE_REVENUE");
            assertThat(payablePositionMinor(app, merchant)).isEqualTo(96_80L);
            assertThat(orderCountFor(app, merchant)).isEqualTo(1);
            assertThat(count(app,
                            "SELECT count(*) FROM ledger.journal_entry WHERE"
                                    + " idempotency_scope = 'ledger.post:payment-execution:"
                                    + attemptId + "'"))
                    .isEqualTo(1);
            // The availability judgement's auditable trace: one hold placed and RELEASED
            // in the same commit (the withdrawal's protocol, compressed).
            assertThat(oneString(app,
                            "SELECT string_agg(status, ',') FROM ledger.hold WHERE"
                                    + " ledger_account_id = ?",
                            payer.walletAccountId()))
                    .isEqualTo("RELEASED");
            // The decision pinned to version 4's wallet rule (INV-HIST-04).
            assertThat(oneString(app,
                            "SELECT chosen_rail || '|' || v.version::text"
                                    + " FROM payments.routing_decision d"
                                    + " JOIN payments.routing_policy_version v"
                                    + " ON v.id = d.policy_version_id"
                                    + " WHERE d.intent_id = ? AND d.chosen_rail IS NOT NULL",
                            UUID.fromString(intentOfSession(checkoutId))))
                    .isEqualTo("book|4");
            // THE AUDIT, both halves, under the PERSON (the gate's find): the confirmation
            // says a dispatch happened, the outcome record says what it committed - the
            // same PaymentOutcomeApplied every rail writes, never a platform actor claimed
            // for a resolver the book rail does not have.
            String intentId = intentOfSession(checkoutId);
            assertThat(oneString(app,
                            "SELECT actor_type FROM platform.audit_record WHERE operation ="
                                    + " 'payments.PaymentConfirmed' AND target_id = ?",
                            intentId))
                    .isEqualTo("CUSTOMER");
            assertThat(oneString(app,
                            "SELECT actor_type || '|' || change_summary FROM"
                                    + " platform.audit_record WHERE operation ="
                                    + " 'payments.PaymentOutcomeApplied' AND target_id = ?",
                            intentId))
                    .startsWith("CUSTOMER|")
                    .contains("verdict=BOOK_POSTED")
                    .contains("attemptStatus=EXECUTED")
                    .contains("intentStatus=SUCCEEDED");
            // AND IN CAUSAL ORDER within the one transaction (the gate's second find): the
            // confirmation before the outcome, RailSelected before PaymentExecuted - keys
            // minted by the one monotonic UUIDv7 generator, so key order IS mint order.
            assertThat(oneString(app,
                            "SELECT string_agg(operation, ',' ORDER BY audit_id)"
                                    + " FROM platform.audit_record WHERE target_id = ?"
                                    + " AND operation IN ('payments.PaymentConfirmed',"
                                    + " 'payments.PaymentOutcomeApplied')",
                            intentId))
                    .isEqualTo("payments.PaymentConfirmed,payments.PaymentOutcomeApplied");
            assertThat(oneString(app,
                            "SELECT string_agg(event_type, ',' ORDER BY event_id)"
                                    + " FROM platform.outbox_event WHERE aggregate_id = ?"
                                    + " AND event_type IN ('payments.RailSelected',"
                                    + " 'payments.PaymentExecuted')",
                            UUID.fromString(intentId)))
                    .isEqualTo("payments.RailSelected,payments.PaymentExecuted");
        }
        assertThat(get("/v1/me/accounts/" + payer.product() + "/balance", payer.token())
                        .body())
                .contains("\"settled\":\"50.00\"")
                .contains("\"available\":\"50.00\"");
    }

    @Test
    @DisplayName("P7-TSK-011, INV-BAL-04: an unfunded wallet refuses with NOTHING written -"
            + " no attempt, no entry, the intent still awaiting - and the SAME confirmation succeeds"
            + " after a top-up")
    void anUnfundedWalletRefusesWithNothingWritten() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        WalletCustomer payer = walletCustomer("40.00");
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        String checkoutId = field(created, "checkoutId");
        String sessionToken = field(created, "sessionToken");

        HttpResponse<String> refused = confirmFromWallet(payer.token(), sessionToken);
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("payments.WalletPaymentUnfunded");

        try (Connection app = DatabaseRoles.application()) {
            assertThat(count(app,
                            "SELECT count(*) FROM payments.payment_attempt WHERE intent_id"
                                    + " = ?",
                            UUID.fromString(intentOfSession(checkoutId))))
                    .as("the whole transaction rolled back: no attempt exists")
                    .isZero();
            assertThat(payablePositionMinor(app, merchant)).isZero();
            assertThat(orderCountFor(app, merchant)).isZero();
        }
        assertThat(get("/v1/me/accounts/" + payer.product() + "/balance", payer.token())
                        .body())
                .as("the wallet untouched, nothing reserved")
                .contains("\"available\":\"40.00\"");

        // The top-up, then the SAME confirmation - the derived key converges on the intent
        // the refusal left awaiting confirmation.
        fundWallet(payer.token(), "60.00");
        HttpResponse<String> succeeded = confirmFromWallet(payer.token(), sessionToken);
        assertThat(succeeded.statusCode()).as(succeeded.body()).isEqualTo(200);
        assertThat(field(succeeded.body(), "status")).isEqualTo("COMPLETED");
        try (Connection app = DatabaseRoles.application()) {
            assertThat(payablePositionMinor(app, merchant)).isEqualTo(96_80L);
        }
    }

    @Test
    @DisplayName("an intent a checkout OPENED but never dispatched is a fresh open's equal (the"
            + " Phase 7 -> 8 transition): the public payment door refuses it, and the checkout"
            + " door's retry asks the merchant's standing again - a suspended merchant is never"
            + " paid, nothing written")
    void anUndispatchedCheckoutIntentIsNeverConfirmedAroundItsChecks() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        WalletCustomer payer = walletCustomer("40.00");
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        String checkoutId = field(created, "checkoutId");
        String sessionToken = field(created, "sessionToken");
        assertThat(confirmFromWallet(payer.token(), sessionToken).statusCode()).isEqualTo(422);
        String intent = intentOfSession(checkoutId);
        assertThat(sessionStatus(checkoutId)).isEqualTo("PAYMENT_PENDING");

        // The public door: a checkout's intent is confirmed through its session only.
        fundWallet(payer.token(), "60.00");
        HttpResponse<String> around =
                post("/v1/payments/" + intent + "/confirmation", null, payer.token(), null);
        assertThat(around.statusCode()).as(around.body()).isEqualTo(409);
        assertThat(around.body()).contains("payments.NotConfirmable");

        // The merchant is suspended; the payer's retry at the checkout door asks again.
        suspend(merchant);
        HttpResponse<String> retried = confirmFromWallet(payer.token(), sessionToken);
        assertThat(retried.statusCode()).as(retried.body()).isEqualTo(409);
        assertThat(retried.body()).contains("checkout.NotTrading");

        try (Connection app = DatabaseRoles.application()) {
            assertThat(count(app,
                            "SELECT count(*) FROM payments.payment_attempt WHERE intent_id = ?",
                            UUID.fromString(intent)))
                    .as("neither door dispatched anything")
                    .isZero();
            assertThat(oneString(app,
                            "SELECT status FROM payments.payment_intent WHERE id = ?",
                            UUID.fromString(intent)))
                    .isEqualTo("REQUIRES_CONFIRMATION");
            assertThat(payablePositionMinor(app, merchant)).isZero();
            assertThat(orderCountFor(app, merchant)).isZero();
        }
        assertThat(get("/v1/me/accounts/" + payer.product() + "/balance", payer.token())
                        .body())
                .contains("\"available\":\"100.00\"");
    }

    @Test
    @DisplayName("the expiry sweep ENDS an intent its session opened and never dispatched -"
            + " CANCELLED as the platform, audited - so it is confirmable no more and its"
            + " merchant can close (the Phase 7 -> 8 transition)")
    void theExpirySweepCancelsAnUndispatchedIntent() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        WalletCustomer payer = walletCustomer("10.00");
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        String checkoutId = field(created, "checkoutId");
        assertThat(confirmFromWallet(payer.token(), field(created, "sessionToken"))
                        .statusCode())
                .isEqualTo(422);
        String intent = intentOfSession(checkoutId);

        expirySweeper(Clock.offset(CLOCK, Duration.ofMinutes(45)), Duration.ZERO).sweep();

        assertThat(sessionStatus(checkoutId)).isEqualTo("EXPIRED");
        try (Connection app = DatabaseRoles.application()) {
            assertThat(oneString(app,
                            "SELECT status FROM payments.payment_intent WHERE id = ?",
                            UUID.fromString(intent)))
                    .isEqualTo("CANCELLED");
            assertThat(count(app,
                            "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                    + " 'payments.PaymentCancelled' AND target_id = ?"
                                    + " AND actor_type = 'SYSTEM'",
                            intent))
                    .isEqualTo(1);
        }
        HttpResponse<String> after =
                confirmFromWallet(payer.token(), field(created, "sessionToken"));
        assertThat(after.statusCode()).as("a dead offer is paid never").isEqualTo(409);
        assertThat(after.body()).contains("checkout.SessionExpired");
        assertThat(asOperatorClose(merchant))
                .as("nothing is on its way to the payable any more")
                .isEqualTo(com.finapp.merchant.MerchantStatus.CLOSED);
    }

    /** The merchant's close, driven at the administration as the platform. */
    private com.finapp.merchant.MerchantStatus asOperatorClose(Merchant merchant)
            throws Exception {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            com.finapp.merchant.MerchantStatus status =
                    merchantAdministration
                            .close(app, com.finapp.merchant.MerchantId.of(
                                    UUID.fromString(merchant.id())), "ended")
                            .status();
            app.commit();
            return status;
        }
    }

    @Autowired private com.finapp.merchant.MerchantAdministration merchantAdministration;

    @Test
    @DisplayName("P7-TSK-011: TEN INSTANCES confirming one session from the wallet produce"
            + " ONE entry, ONE order and ONE debit - the losers converge on the purchase"
            + " that worked")
    void tenConcurrentWalletConfirmsProduceOneEntry() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        WalletCustomer payer = walletCustomer("150.00");
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        String checkoutId = field(created, "checkoutId");
        String sessionToken = field(created, "sessionToken");
        double executedBefore =
                com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(meterRegistry, com.finapp.payments.BookRail.RAIL.id(), "payment", "executed");

        List<java.util.concurrent.Callable<String>> racers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            racers.add(
                    () -> {
                        HttpResponse<String> answer =
                                confirmFromWallet(payer.token(), sessionToken);
                        return answer.statusCode() + "|" + answer.body();
                    });
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<String> answers = new ArrayList<>();
        try {
            for (Future<String> raced : pool.invokeAll(racers)) {
                answers.add(raced.get());
            }
        } finally {
            pool.shutdown();
        }
        // The card ten-way's own contract (the Phase 6 -> 7 transition): every racer
        // converges on the one purchase, or is told honestly that one is in flight -
        // and no racer is told the session left the flow.
        assertThat(answers)
                .allSatisfy(
                        answer -> {
                            if (!answer.startsWith("200|")) {
                                assertThat(answer)
                                        .as("every racer converges, or is honestly"
                                                + " in-flight: %s", answer)
                                        .contains("api.IdempotencyInProgress");
                            }
                            assertThat(answer).doesNotContain("checkout.");
                        });
        assertThat(answers)
                .as("the purchase itself worked for at least one racer")
                .anySatisfy(answer -> assertThat(answer).startsWith("200|"));

        String attemptId = attemptIdForSession(checkoutId);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(count(app,
                            "SELECT count(*) FROM ledger.journal_entry WHERE"
                                    + " idempotency_scope = 'ledger.post:payment-execution:"
                                    + attemptId + "'"))
                    .isEqualTo(1);
            assertThat(orderCountFor(app, merchant)).isEqualTo(1);
            assertThat(payablePositionMinor(app, merchant)).isEqualTo(96_80L);
        }
        assertThat(get("/v1/me/accounts/" + payer.product() + "/balance", payer.token())
                        .body())
                .contains("\"settled\":\"50.00\"");
        // TEN racers, ONE judgement counted (P7-TSK-015): the book payment's EXECUTED is
        // reported where it is written and counted after its commit - the losers wrote none.
        assertThat(com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(meterRegistry, com.finapp.payments.BookRail.RAIL.id(), "payment", "executed")
                        - executedBefore)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("P7-TSK-011, plan scenario 13: a wallet payment and a WITHDRAWAL race one"
            + " wallet - exactly the affordable set proceeds, available never negative")
    void walletPaymentAndWithdrawalRaceAdmitsTheAffordableSet() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        WalletCustomer payer = walletCustomer("100.00");
        // The withdrawal's instrument: a bank account registered through the real exchange.
        provider.succeedsWith(
                com.finapp.payments.SimulatedInstantSchemeAdapter.EXCHANGES_PATH,
                200,
                "{\"status\":\"exchanged\",\"destination\":\"dest-w13-" + UUID.randomUUID()
                        + "\",\"suffix\":\"6819\",\"payee\":\"match\"}");
        String methodId =
                field(post("/v1/me/payment-methods/bank-accounts",
                                "{\"grant\":\"blg-" + UUID.randomUUID()
                                        + "\",\"acknowledgeNoMatch\":false}",
                                payer.token(),
                                someKey())
                                .body(),
                        "id");
        provider.succeedsWith(
                com.finapp.payments.SimulatedInstantSchemeAdapter.TRANSFERS_PATH,
                200,
                "{\"status\":\"accepted\",\"reference\":\"sch-w13-" + UUID.randomUUID()
                        + "\",\"cycle\":\"C1\"}");
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        String sessionToken = field(created, "sessionToken");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<HttpResponse<String>> paying =
                pool.submit(() -> confirmFromWallet(payer.token(), sessionToken));
        Future<HttpResponse<String>> withdrawing =
                pool.submit(
                        () ->
                                post("/v1/me/withdrawals",
                                        "{\"paymentMethodId\":\"" + methodId
                                                + "\",\"amount\":\"100.00\","
                                                + "\"currency\":\"EUR\"}",
                                        payer.token(),
                                        someKey()));
        HttpResponse<String> paid = paying.get();
        HttpResponse<String> withdrew = withdrawing.get();
        pool.shutdown();

        boolean paymentWon = paid.statusCode() == 200;
        boolean withdrawalWon =
                withdrew.statusCode() == 201
                        && field(withdrew.body(), "status").equals("COMPLETED");
        assertThat(paymentWon ^ withdrawalWon)
                .as("EXACTLY the affordable set proceeds (INV-BAL-04): payment %s,"
                                + " withdrawal %s",
                        paid.statusCode() + "/" + paid.body(),
                        withdrew.statusCode() + "/" + withdrew.body())
                .isTrue();
        if (!paymentWon) {
            assertThat(paid.body()).contains("payments.WalletPaymentUnfunded");
        }
        if (!withdrawalWon) {
            assertThat(withdrew.body()).contains("payments.WithdrawalUnfunded");
        }
        // Whoever won, the wallet explains itself and never went negative.
        assertThat(get("/v1/me/accounts/" + payer.product() + "/balance", payer.token())
                        .body())
                .contains("\"settled\":\"0.00\"")
                .contains("\"available\":\"0.00\"");
    }

    @Test
    @DisplayName("P7-TSK-011: a wallet payment refunds by BOOK MOVEMENT in one transaction -"
            + " the capture's four-line inverse with the wallet as counterpart, funded by"
            + " its net, the payable at zero, the reference a platform marker")
    void aWalletPaymentRefundsByBookMovement() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L, "RETURNED");
        WalletCustomer payer = walletCustomer("100.00");
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        String checkoutId = field(created, "checkoutId");
        assertThat(confirmFromWallet(payer.token(), field(created, "sessionToken"))
                        .statusCode())
                .isEqualTo(200);

        // Past the executed amount refuses BEFORE anything is held (INV-PAY-05).
        HttpResponse<String> past = refundResponse(intentOfSession(checkoutId), "100.01");
        assertThat(past.statusCode()).isEqualTo(422);
        assertThat(past.body()).contains("payments.RefundExceedsCaptured");

        HttpResponse<String> returned = refundResponse(intentOfSession(checkoutId), "100.00");
        assertThat(returned.statusCode()).as(returned.body()).isEqualTo(201);
        assertThat(field(returned.body(), "status")).isEqualTo("COMPLETED");
        String refundId = field(returned.body(), "id");

        try (Connection app = DatabaseRoles.application()) {
            assertThat(oneString(app,
                            "SELECT provider_reference FROM payments.refund WHERE id = ?",
                            UUID.fromString(refundId)))
                    .as("the completion's reference is a platform marker: the entry is the"
                            + " whole external record")
                    .startsWith("bke-");
            try (PreparedStatement read =
                    app.prepareStatement(
                            "SELECT string_agg(l.direction || ':' || a.purpose, ','"
                                    + " ORDER BY l.direction || ':' || a.purpose)"
                                    + " FROM ledger.journal_line l"
                                    + " JOIN ledger.journal_entry e ON e.id = l.entry_id"
                                    + " JOIN ledger.ledger_account a"
                                    + "   ON a.id = l.ledger_account_id"
                                    + " WHERE e.idempotency_scope ="
                                    + " 'ledger.post:payment-refund:" + refundId + "'")) {
                try (ResultSet row = read.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1))
                            .isEqualTo("CREDIT:CUSTOMER_WALLET,CREDIT:MERCHANT_PAYABLE,"
                                    + "DEBIT:FEE_REVENUE,DEBIT:MERCHANT_PAYABLE");
                }
            }
            assertThat(payablePositionMinor(app, merchant)).isZero();
        }
        assertThat(get("/v1/me/accounts/" + payer.product() + "/balance", payer.token())
                        .body())
                .as("the money came back where it left from")
                .contains("\"settled\":\"100.00\"");
        assertThat(payable(merchant).body())
                .contains("\"refunded\":\"100.00\"")
                .contains("\"feesReturned\":\"3.20\"")
                .contains("\"position\":\"0.00\"");
    }

    @Test
    @DisplayName("P7-TSK-011: the instrument choice is exactly one (422 for both, neither"
            + " and an unknown word), and an MFA-enrolled payer pays from the wallet only"
            + " with a MULTI_FACTOR session - refused with nothing written, elevated"
            + " succeeds on the SAME session token")
    void theInstrumentChoiceAndTheStepUp() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        WalletCustomer payer = walletCustomer("150.00");
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        String sessionToken = field(created, "sessionToken");

        assertThat(post("/v1/checkout/sessions/confirmation",
                                "{\"sessionToken\":\"" + sessionToken + "\"}",
                                payer.token(), null)
                        .statusCode())
                .as("neither instrument")
                .isEqualTo(422);
        assertThat(post("/v1/checkout/sessions/confirmation",
                                "{\"sessionToken\":\"" + sessionToken
                                        + "\",\"paymentMethodId\":\"" + UUID.randomUUID()
                                        + "\",\"instrument\":\"WALLET\"}",
                                payer.token(), null)
                        .statusCode())
                .as("both instruments")
                .isEqualTo(422);
        assertThat(post("/v1/checkout/sessions/confirmation",
                                "{\"sessionToken\":\"" + sessionToken
                                        + "\",\"instrument\":\"CASH\"}",
                                payer.token(), null)
                        .statusCode())
                .as("an unknown instrument word")
                .isEqualTo(422);

        // The step-up: enrolled at PASSWORD is refused with nothing written; the SAME
        // session token succeeds once the payer elevates (the withdrawal's proof, at
        // this door).
        com.finapp.sharedkernel.security.Sensitive<String> secret =
                enrolAndConfirm(payer.token());
        HttpResponse<String> refused = confirmFromWallet(payer.token(), sessionToken);
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.body()).contains("identity.AssuranceRequired");
        try (Connection app = DatabaseRoles.application()) {
            assertThat(orderCountFor(app, merchant)).isZero();
            assertThat(payablePositionMinor(app, merchant)).isZero();
        }
        String elevated =
                tokenFrom(
                        post("/v1/authentications/mfa",
                                        "{\"code\":\"" + codeNow(secret) + "\"}",
                                        payer.token(), null)
                                .body());
        HttpResponse<String> succeeded = confirmFromWallet(elevated, sessionToken);
        assertThat(succeeded.statusCode()).as(succeeded.body()).isEqualTo(200);
        assertThat(field(succeeded.body(), "status")).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("P7-TSK-011, INV-CON-02: a BOOK REFUND and a NEW WALLET PAYMENT race on the"
            + " same wallet and payable - the two directions of the AB/BA cycle - and every"
            + " round completes both, no deadlock (the fixed-order pair lock)")
    void aBookRefundAndAWalletPaymentRaceWithoutDeadlock() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L, "RETURNED");
        WalletCustomer payer = walletCustomer("900.00");
        String operator = operatorSession(RoleName.LEDGER_OPERATOR);
        for (int round = 0; round < 6; round++) {
            // A completed wallet payment to refund...
            String paid = createSession(merchant, AMOUNT_MINOR, someKey()).body();
            assertThat(confirmFromWallet(payer.token(), field(paid, "sessionToken"))
                            .statusCode())
                    .isEqualTo(200);
            String refundable = intentOfSession(field(paid, "checkoutId"));
            // ...and a fresh session to pay - released together.
            String fresh = createSession(merchant, AMOUNT_MINOR, someKey()).body();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<HttpResponse<String>> refunding =
                        pool.submit(
                                () -> {
                                    start.await();
                                    return refundResponse(refundable, "100.00", operator);
                                });
                Future<HttpResponse<String>> paying =
                        pool.submit(
                                () -> {
                                    start.await();
                                    return confirmFromWallet(
                                            payer.token(), field(fresh, "sessionToken"));
                                });
                start.countDown();
                HttpResponse<String> refunded = refunding.get(120, TimeUnit.SECONDS);
                HttpResponse<String> payment = paying.get(120, TimeUnit.SECONDS);
                assertThat(refunded.statusCode())
                        .as("round %d: the refund completes (%s)", round, refunded.body())
                        .isEqualTo(201);
                assertThat(field(refunded.body(), "status")).isEqualTo("COMPLETED");
                assertThat(payment.statusCode())
                        .as("round %d: the payment completes (%s)", round, payment.body())
                        .isEqualTo(200);
            } finally {
                pool.shutdownNow();
            }
        }
        // Every round explained: six payments kept, six refunded - 900 - 6 x 100 x (2 - 1).
        assertThat(get("/v1/me/accounts/" + payer.product() + "/balance", payer.token())
                        .body())
                .contains("\"settled\":\"300.00\"");
    }

    @Test
    @DisplayName("P7-TSK-011: the wallet path's own refusals are the PAYER's conditions, not a"
            + " broken database - no wallet is 422 payments.NoWallet and a wallet in another"
            + " currency is 422 payments.CurrencyMismatch, each with nothing written")
    void theWalletPathRefusesItsOwnConditions() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);

        // A payer with no wallet at all (a card customer who never opened one).
        Customer walletless = payingCustomer();
        String first = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        HttpResponse<String> noWallet =
                confirmFromWallet(walletless.token(), field(first, "sessionToken"));
        assertThat(noWallet.statusCode()).as(noWallet.body()).isEqualTo(422);
        assertThat(noWallet.body()).contains("payments.NoWallet");

        // A payer whose wallet holds another currency: FX is no part of this flow.
        Customer dollars = payingCustomer();
        assertThat(post("/v1/me/accounts",
                                "{\"productType\":\"WALLET\",\"currency\":\"USD\"}",
                                dollars.token(),
                                someKey())
                        .statusCode())
                .isEqualTo(201);
        String second = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        HttpResponse<String> mismatched =
                confirmFromWallet(dollars.token(), field(second, "sessionToken"));
        assertThat(mismatched.statusCode()).as(mismatched.body()).isEqualTo(422);
        assertThat(mismatched.body()).contains("payments.CurrencyMismatch");

        try (Connection app = DatabaseRoles.application()) {
            assertThat(orderCountFor(app, merchant)).isZero();
            assertThat(payablePositionMinor(app, merchant)).isZero();
        }
        // Both offers are still open to be paid - nothing moved them (the refusal rolled
        // the opening transaction back whole).
        assertThat(sessionStatus(field(first, "checkoutId"))).isEqualTo("OPEN");
        assertThat(sessionStatus(field(second, "checkoutId"))).isEqualTo("OPEN");
    }

    /** A verified, ACTIVE customer with an EUR wallet funded over the REAL card chain. */
    private record WalletCustomer(String token, String product, UUID walletAccountId) {}

    private WalletCustomer walletCustomer(String funding) throws Exception {
        Customer carded = payingCustomer();
        HttpResponse<String> opened =
                post("/v1/me/accounts",
                        "{\"productType\":\"WALLET\",\"currency\":\"EUR\"}",
                        carded.token(),
                        someKey());
        assertThat(opened.statusCode()).isEqualTo(201);
        String product = field(opened.body(), "id");
        walletCardMethods.put(carded.token(), carded.methodId());
        fundWallet(carded.token(), funding);
        UUID walletAccountId;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT id FROM ledger.ledger_account WHERE owner_ref = ?"
                                        + " AND purpose = 'CUSTOMER_WALLET'")) {
            read.setObject(1, UUID.fromString(product));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                walletAccountId = row.getObject("id", UUID.class);
            }
        }
        return new WalletCustomer(carded.token(), product, walletAccountId);
    }

    /** The card method each wallet customer funds through (the top-up's instrument). */
    private final java.util.Map<String, String> walletCardMethods =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Funds the wallet by a REAL card top-up through the stubbed PSP (EUR). */
    private void fundWallet(String token, String amount) throws Exception {
        providerAuthorises();
        providerCaptures();
        String method = walletCardMethods.get(token);
        String paymentId =
                field(post("/v1/payments",
                                "{\"paymentMethodId\":\"" + method + "\",\"amount\":\""
                                        + amount + "\",\"currency\":\"EUR\"}",
                                token,
                                someKey())
                                .body(),
                        "id");
        HttpResponse<String> confirmed =
                post("/v1/payments/" + paymentId + "/confirmation", null, token, null);
        assertThat(field(confirmed.body(), "status")).isEqualTo("SUCCEEDED");
    }

    private HttpResponse<String> confirmFromWallet(String token, String sessionToken)
            throws Exception {
        return post(
                "/v1/checkout/sessions/confirmation",
                "{\"sessionToken\":\"" + sessionToken + "\",\"instrument\":\"WALLET\"}",
                token,
                null);
    }

    // The wallet block's scalar reads (the PayByBank suite's idiom, connection-passed).
    private static String oneString(Connection app, String sql, Object argument)
            throws SQLException {
        try (PreparedStatement read = app.prepareStatement(sql)) {
            read.setObject(1, argument);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("expected one row: %s", sql).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long count(Connection app, String sql) throws SQLException {
        try (PreparedStatement read = app.prepareStatement(sql);
                ResultSet row = read.executeQuery()) {
            assertThat(row.next()).isTrue();
            return row.getLong(1);
        }
    }

    private static long count(Connection app, String sql, Object argument)
            throws SQLException {
        try (PreparedStatement read = app.prepareStatement(sql)) {
            read.setObject(1, argument);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    // The withdrawal suite's MFA idiom, verbatim (P7-TSK-008): enrol, confirm with the
    // PREVIOUS step's code, elevate with the current one.
    private com.finapp.sharedkernel.security.Sensitive<String> enrolAndConfirm(
            String sessionToken) throws Exception {
        com.finapp.sharedkernel.security.Sensitive<String> secret =
                com.finapp.sharedkernel.security.Sensitive.of(
                        secretFrom(post("/v1/me/mfa", null, sessionToken, null).body()));
        String confirming =
                com.finapp.identity.Authenticator.codeAt(
                        secret,
                        com.finapp.identity.TotpParameters.current(),
                        java.time.Instant.ofEpochSecond(
                                (currentStep() - 1)
                                        * com.finapp.identity.TotpParameters.current()
                                                .periodSeconds()));
        assertThat(post("/v1/me/mfa/confirmation", "{\"code\":\"" + confirming + "\"}",
                                sessionToken, null)
                        .statusCode())
                .isEqualTo(204);
        return secret;
    }

    private static String codeNow(
            com.finapp.sharedkernel.security.Sensitive<String> secret) {
        return com.finapp.identity.Authenticator.codeAt(
                secret,
                com.finapp.identity.TotpParameters.current(),
                java.time.Instant.ofEpochSecond(
                        currentStep()
                                * com.finapp.identity.TotpParameters.current()
                                        .periodSeconds()));
    }

    private static long currentStep() {
        return java.time.Instant.now().getEpochSecond()
                / com.finapp.identity.TotpParameters.current().periodSeconds();
    }

    private static String secretFrom(String body) {
        // The secret travels inside the provisioning URI (the withdrawal suite's read).
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("secret=([A-Z2-7]+)").matcher(body);
        assertThat(matcher.find()).as(body).isTrue();
        return matcher.group(1);
    }

    /** A verified customer whose instrument is a BANK account (P7-TSK-009) - no card. */
    private Customer bankPayingCustomer() throws Exception {
        String login = "chb." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(register(login).statusCode()).isEqualTo(201);
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "UPDATE party.customer SET status = 'ACTIVE', status_changed_at ="
                            + " GREATEST(now(), opened_at) WHERE party_id = (SELECT party_id"
                            + " FROM identity.identity WHERE login_identifier = ?)",
                    login);
        }
        String token = tokenFrom(authenticate(login).body());
        provider.succeedsWith(
                com.finapp.payments.SimulatedInstantSchemeAdapter.EXCHANGES_PATH,
                200,
                "{\"status\":\"exchanged\",\"destination\":\"dest-chk-" + UUID.randomUUID()
                        + "\",\"suffix\":\"6819\",\"payee\":\"match\"}");
        HttpResponse<String> registered =
                post(
                        "/v1/me/payment-methods/bank-accounts",
                        "{\"grant\":\"blg-" + UUID.randomUUID() + "\","
                                + "\"acknowledgeNoMatch\":false}",
                        token,
                        someKey());
        assertThat(registered.statusCode()).isEqualTo(201);
        return new Customer(token, field(registered.body(), "id"));
    }

    private void schemeInitiatesPayIn(String handle) {
        provider.succeedsWith(
                com.finapp.payments.SimulatedInstantSchemeAdapter.INITIATIONS_PATH,
                200,
                "{\"status\":\"initiated\",\"handle\":\"" + handle + "\"}");
    }

    private int instantCallback(String body) {
        return provider.deliverTimestampSignedCallback(
                URI.create("http://localhost:" + port
                        + "/v1/providers/payments/instant/webhooks"),
                body,
                INSTANT_WEBHOOK_KEY,
                java.time.Instant.now(CLOCK).getEpochSecond(),
                1);
    }

    private HttpResponse<String> merchantView(Merchant merchant, String checkoutId)
            throws Exception {
        return get("/v1/checkout/sessions/" + checkoutId, merchant.apiKey());
    }

    private static String endToEndReferenceOf(String attemptId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT end_to_end_reference FROM payments.payment_attempt"
                                        + " WHERE id = ?")) {
            read.setObject(1, UUID.fromString(attemptId));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    @Test
    @DisplayName("INV-MER-06 UNDER CONTENTION: five expiry sweeps racing five completions of one"
            + " overdue PAYMENT_PENDING session leave ONE order - COMPLETED, or EXPIRED then"
            + " COMPLETED_LATE, each edge once")
    void anExpiryRacingTheCompletionLeavesOneOrder() throws Exception {
        // P6-DOC-001: the phase review found the two writers of an overdue PAYMENT_PENDING row -
        // the expiry sweep and the capture's completion - proven one after the other (the test
        // above) and never against each other. Both lock the row and judge what they find under
        // the lock, so either may win; whichever does, landed money has its order, once.
        Merchant merchant = tradingMerchant("0.029", 30L);
        Clock late = Clock.offset(CLOCK, Duration.ofMinutes(31));
        // DRAINED FIRST, for the race rather than for tidiness: the sweeper walks the queue
        // oldest deadline first, fifty at a time, so other tests' leftovers could crowd this
        // session out of every racer's batch - and a race the sweeps never enter has one
        // possible winner (CheckoutExpiryDatabaseTest's drain, at this fixture's batch).
        for (int pass = 0; pass < 20; pass++) {
            if (expirySweeper(late, Duration.ZERO).sweep().candidates() == 0) {
                break;
            }
        }
        String checkoutId =
                field(createSession(merchant, AMOUNT_MINOR, someKey()).body(), "checkoutId");

        // PAYMENT_PENDING along the machine's own edge - the confirm-then-transition that
        // CheckoutSessions.paymentOpened makes - with an intent BY VALUE (ADR-0029): the
        // completion is driven at the command, so no payment has to stand behind it.
        UUID intent = IDS.next();
        com.finapp.checkout.JdbcCheckoutSessionStore store =
                new com.finapp.checkout.JdbcCheckoutSessionStore();
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            com.finapp.checkout.CheckoutSession open =
                    store.findById(app, CheckoutSessionId.of(UUID.fromString(checkoutId)))
                            .orElseThrow();
            assertThat(store.transition(app, open, open.confirm(CLOCK, intent))).isTrue();
            app.commit();
        }

        List<java.util.concurrent.Callable<Object>> racers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            racers.add(() -> expirySweeper(late, Duration.ZERO).sweep());
            racers.add(() -> completeDirectly(intent));
        }
        int expired = 0;
        int created = 0;
        for (Object answer : concurrently(racers)) {
            if (answer instanceof com.finapp.checkout.CheckoutExpirySweeper.SweepResult tick) {
                assertThat(tick.failedRows()).as("no racer errors; the losers converge").isZero();
                expired += tick.expired();
            } else if (answer instanceof java.util.Optional<?> order && order.isPresent()) {
                created++;
            }
        }
        assertThat(created)
                .as("exactly one completion's own transition created the order")
                .isEqualTo(1);

        String ended = sessionStatus(checkoutId);
        assertThat(ended)
                .as("never EXPIRED at rest: landed money is not orphaned by a clock")
                .isIn("COMPLETED", "COMPLETED_LATE");
        boolean expiredFirst = "COMPLETED_LATE".equals(ended);
        assertThat(expired)
                .as("the sweeps' own tally: one acting expiry if the row expired, else none")
                .isEqualTo(expiredFirst ? 1 : 0);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(orderCountFor(app, merchant))
                    .as("one order, whichever writer took the lock first")
                    .isEqualTo(1);
            assertThat(orderPaidPayload(app, checkoutId))
                    .as("announced once, as the ending the row reached")
                    .contains("\"completedAs\":\"" + ended + "\"");
            if (expiredFirst) {
                // A sweep took the lock first: one of the five expired the row, and the first
                // completion after it found EXPIRED and took the late edge.
                assertThat(historyRow(app, checkoutId, "PAYMENT_PENDING", "EXPIRED"))
                        .isEqualTo(1);
                assertThat(historyRow(app, checkoutId, "EXPIRED", "COMPLETED_LATE"))
                        .isEqualTo(1);
                assertThat(historyRow(app, checkoutId, "PAYMENT_PENDING", "COMPLETED"))
                        .isZero();
                assertThat(auditSummaryFor(app, checkoutId, "checkout.CheckoutSessionExpired"))
                        .as("one expiry recorded, by the one sweep whose transition fired")
                        .contains("expiredFrom=PAYMENT_PENDING");
                assertThat(outboxCount(app, "checkout.CheckoutSessionExpired", checkoutId))
                        .isEqualTo(1);
            } else {
                // A completion took the lock first: the row was terminal before any sweep held
                // it, so nothing expired at all.
                assertThat(historyRow(app, checkoutId, "PAYMENT_PENDING", "COMPLETED"))
                        .isEqualTo(1);
                assertThat(historyRow(app, checkoutId, "PAYMENT_PENDING", "EXPIRED")).isZero();
                assertThat(outboxCount(app, "checkout.CheckoutSessionExpired", checkoutId))
                        .isZero();
            }
        }
    }

    /** Conversions timed for one outcome, in the application's own registry (`P6-TSK-013`). */
    private long conversions(String outcome) {
        io.micrometer.core.instrument.Timer timer =
                meterRegistry.find("finapp.checkout.conversion.age").tag("outcome", outcome).timer();
        return timer == null ? 0L : timer.count();
    }

    /** The seconds recorded for one outcome's conversions, in total (`P6-TSK-013`). */
    private double conversionSeconds(String outcome) {
        io.micrometer.core.instrument.Timer timer =
                meterRegistry.find("finapp.checkout.conversion.age").tag("outcome", outcome).timer();
        return timer == null ? 0.0d : timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) / 1e9;
    }

    /** Fee assessments, in the application's own registry (`P6-TSK-013`). */
    private double feeAssessments() {
        io.micrometer.core.instrument.Counter counter =
                meterRegistry.find("finapp.merchant.fee.assessed").counter();
        return counter == null ? 0.0d : counter.count();
    }

    /** The counter for one outcome, read from the application's own registry. */
    private double sessionMeter(String outcome) {
        io.micrometer.core.instrument.Counter counter =
                meterRegistry.find("finapp.checkout.session").tag("outcome", outcome).counter();
        return counter == null ? 0.0d : counter.count();
    }

    @Test
    @DisplayName("a customer cannot re-drive an EXPIRED session - the late completion is the"
            + " resolver's to make, and the surface says SessionExpired")
    void anExpiredSessionRefusesAFreshConfirmation() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer customer = payingCustomer();
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        expirySweeper(Clock.offset(CLOCK, Duration.ofMinutes(31)), Duration.ZERO).sweep();
        assertThat(sessionStatus(field(created, "checkoutId"))).isEqualTo("EXPIRED");

        HttpResponse<String> refused = confirm(customer, field(created, "sessionToken"));

        // Not NotConfirmable: the two say different things to the customer looking at the
        // page - one means TOO LATE, the other means ALREADY DONE.
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("checkout.SessionExpired");
    }

    // ----------------------------------------------------------------- abandonment

    @Test
    @DisplayName("a merchant withdraws its own unpaid offer - ABANDONED, reasoned, and a"
            + " retry converges on the same 200")
    void aMerchantWithdrawsItsOwnOffer() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        String checkoutId = field(createSession(merchant, AMOUNT_MINOR, someKey()).body(),
                "checkoutId");

        HttpResponse<String> withdrawn = abandon(merchant, checkoutId, "the basket changed");
        assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(200);
        assertThat(field(withdrawn.body(), "status")).isEqualTo("ABANDONED");

        HttpResponse<String> again = abandon(merchant, checkoutId, "the basket changed");
        assertThat(again.statusCode())
                .as("a retried withdrawal is not an error - it converges")
                .isEqualTo(200);
        assertThat(field(again.body(), "status")).isEqualTo("ABANDONED");

        try (Connection app = DatabaseRoles.application()) {
            assertThat(historyRow(app, checkoutId, "OPEN", "ABANDONED"))
                    .as("converged, so exactly one transition however many calls")
                    .isEqualTo(1);
            assertThat(auditReasonFor(app, checkoutId))
                    .as("THE ONE CHECKOUT ACTION WITH A REASON (INV-AUD-03), recorded verbatim")
                    .isEqualTo("the basket changed");
        }
    }

    @Test
    @DisplayName("the session's audit records name the KEY that acted - its creation and its"
            + " withdrawal both, as the payout's do (ADR-0052 section 2)")
    void theSessionsAuditRecordsNameTheKey() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        String checkoutId = field(createSession(merchant, AMOUNT_MINOR, someKey()).body(),
                "checkoutId");
        assertThat(abandon(merchant, checkoutId, "the basket changed").statusCode())
                .isEqualTo(200);

        // P6-DOC-001. Until then both records named the merchant and not the key, while the
        // payout's named both: a merchant holding several keys could not be told which one
        // opened or withdrew an offer, and after a leak the key to revoke is that one.
        String key = "key=" + keyIdOf(merchant);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(auditSummaryFor(app, checkoutId, "checkout.CheckoutSessionCreated"))
                    .contains(key);
            assertThat(auditSummaryFor(app, checkoutId, "checkout.CheckoutSessionAbandoned"))
                    .contains(key);
        }
    }

    @Test
    @DisplayName("a merchant CANNOT withdraw an offer whose payment is in flight - the edge"
            + " the machine does not have, at the surface (INV-MER-06's neighbour)")
    void aPaymentInFlightCannotBeWithdrawn() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer customer = payingCustomer();
        String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        providerAuthorises();
        provider.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.CAPTURES_PATH);
        assertThat(field(confirm(customer, field(created, "sessionToken")).body(), "status"))
                .isEqualTo("PAYMENT_PENDING");

        HttpResponse<String> refused =
                abandon(merchant, field(created, "checkoutId"), "changed my mind");

        // Withdrawing here would leave money moving toward a purchase with no commercial
        // home. The merchant's remedy is a REFUND, after the order exists.
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("checkout.NotAbandonable");
        assertThat(sessionStatus(field(created, "checkoutId"))).isEqualTo("PAYMENT_PENDING");
    }

    /**
     * The third confirmation interleaving the Phase 6 -> 7 transition left unraced, paid by the
     * Phase 7 review. A withdrawal that holds the session row - its move made, not committed -
     * makes the confirmation's conditional open wait; the open then finds the row gone from
     * OPEN, the confirming transaction rolls back whole (no intent, no pin) and its re-read
     * answers the machine's refusal. Then both are launched together, round after round:
     * whichever lands first, each round tells exactly one story.
     */
    @Test
    @DisplayName("a withdrawal racing a confirmation: the confirmation waits on the session row,"
            + " loses the open whole - nothing opened, pinned or dispatched - and every raced"
            + " round tells exactly one story")
    void aWithdrawalRacingAConfirmationLeavesOneStory() throws Exception {
        Merchant merchant = tradingMerchant("0.029", 30L);
        Customer customer = payingCustomer();
        providerAuthorises();
        providerCaptures();

        String held = createSession(merchant, AMOUNT_MINOR, someKey()).body();
        CheckoutSessionId heldId = CheckoutSessionId.of(UUID.fromString(field(held, "checkoutId")));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection withdrawer = DatabaseRoles.application()) {
            withdrawer.setAutoCommit(false);
            assertThat(
                            sessions.abandon(
                                    withdrawer,
                                    MerchantId.of(UUID.fromString(merchant.id())),
                                    keyIdOf(merchant),
                                    heldId,
                                    "withdrawn mid-confirmation"))
                    .isTrue();
            Future<HttpResponse<String>> confirming =
                    pool.submit(() -> confirm(customer, field(held, "sessionToken")));
            awaitLockWaitOn("checkout.checkout_session", "SET status");
            withdrawer.commit();

            HttpResponse<String> refused = confirming.get(60, TimeUnit.SECONDS);
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
            assertThat(refused.body()).contains("checkout.NotConfirmable");
        } finally {
            pool.shutdownNow();
        }
        assertThat(statusAndIntentOf(heldId.value()))
                .as("withdrawn, carrying no intent: the lost open rolled back whole")
                .isEqualTo("ABANDONED:null");
        assertThat(intentsCrediting(merchant)).isZero();
        try (Connection app = DatabaseRoles.application()) {
            assertThat(
                            count(
                                    app,
                                    "SELECT count(*) FROM merchant.payment_fee_pin pin"
                                            + " WHERE pin.merchant_id = ?",
                                    UUID.fromString(merchant.id())))
                    .as("no fee pinned for the payment that never opened")
                    .isZero();
        }
        assertThat(provider.requestCount(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH)).isZero();

        // RACED: both launched together, six rounds. Whichever reaches the row first, one
        // story per round - withdrawn with nothing opened, or paid with the withdrawal refused.
        int paid = 0;
        for (int round = 0; round < 6; round++) {
            providerAuthorises();
            providerCaptures();
            String created = createSession(merchant, AMOUNT_MINOR, someKey()).body();
            String checkoutId = field(created, "checkoutId");
            long intentsBefore = intentsCrediting(merchant);
            List<HttpResponse<String>> answers =
                    concurrently(
                            List.of(
                                    () -> confirm(customer, field(created, "sessionToken")),
                                    () -> abandon(merchant, checkoutId, "raced round " + checkoutId)));
            HttpResponse<String> confirmed = answers.get(0);
            HttpResponse<String> withdrawn = answers.get(1);
            String status = sessionStatus(checkoutId);
            if ("ABANDONED".equals(status)) {
                assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(200);
                assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(409);
                assertThat(confirmed.body()).contains("checkout.NotConfirmable");
                assertThat(statusAndIntentOf(UUID.fromString(checkoutId)))
                        .isEqualTo("ABANDONED:null");
                assertThat(intentsCrediting(merchant)).isEqualTo(intentsBefore);
            } else {
                paid++;
                assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
                assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(409);
                assertThat(withdrawn.body()).contains("checkout.NotAbandonable");
                assertThat(status).isIn("PAYMENT_PENDING", "COMPLETED");
                assertThat(intentsCrediting(merchant)).isEqualTo(intentsBefore + 1);
            }
            try (Connection app = DatabaseRoles.application()) {
                assertThat(historyRow(app, checkoutId, "OPEN", "ABANDONED")
                                + historyRow(app, checkoutId, "OPEN", "PAYMENT_PENDING"))
                        .as("exactly one edge out of OPEN, whichever won")
                        .isEqualTo(1);
            }
        }
        assertThat(provider.requestCount(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH))
                .as("one authorization per round the confirmation won, none otherwise")
                .isEqualTo(paid);
    }

    @Test
    @DisplayName("withdrawal is tenant-scoped and reasoned: another merchant's session is the"
            + " same 404, and a blank reason is the boundary's 400")
    void withdrawalIsScopedAndReasoned() throws Exception {
        Merchant owner = tradingMerchant("0.029", 30L);
        Merchant stranger = tradingMerchant("0.029", 30L);
        String checkoutId = field(createSession(owner, AMOUNT_MINOR, someKey()).body(),
                "checkoutId");

        assertThat(abandon(stranger, checkoutId, "not mine").statusCode())
                .as("a competitor's offer is indistinguishable from one that does not exist")
                .isEqualTo(404);
        assertThat(abandon(owner, UUID.randomUUID().toString(), "unknown").statusCode())
                .isEqualTo(404);
        assertThat(abandon(owner, "not-a-uuid", "malformed").statusCode()).isEqualTo(404);
        assertThat(abandon(owner, checkoutId, "").statusCode())
                .as("a reason nobody wrote is worse than none: it makes the field look answered")
                .isEqualTo(422);

        assertThat(sessionStatus(checkoutId))
                .as("every refusal wrote nothing")
                .isEqualTo("OPEN");
    }

    // ----------------------------------------------------------------- fixtures

    private record Merchant(String id, String apiKey) {}

    private record Customer(String token, String methodId) {}

    /** An onboarded, ACTIVE merchant with a fee schedule assigned and an API key. */
    private Merchant tradingMerchant(String rate, long fixedMinor) throws Exception {
        return tradingMerchant(rate, fixedMinor, "RETAINED");
    }

    /** `P6-TSK-010`: the refund-fee policy is a version's term, so a suite testing it chooses. */
    private Merchant tradingMerchant(String rate, long fixedMinor, String refundFeePolicy)
            throws Exception {
        String operator = operatorSession();
        String merchantId = onboard(operator);
        String schedule =
                field(
                        post(
                                        "/v1/operator/fee-schedules",
                                        "{\"name\":\"Std " + UUID.randomUUID()
                                                + "\",\"currency\":\"EUR\"}",
                                        operator,
                                        null)
                                .body(),
                        "feeScheduleId");
        assertThat(
                        post(
                                        "/v1/operator/fee-schedules/" + schedule + "/versions",
                                        "{\"rate\":" + rate + ",\"fixedAmountMinor\":"
                                                + fixedMinor
                                                + ",\"roundingPolicy\":\"HALF_EVEN\","
                                                + "\"refundFeePolicy\":\""
                                                + refundFeePolicy + "\","
                                                + "\"reason\":\"initial pricing\"}",
                                        operator,
                                        null)
                                .statusCode())
                .isEqualTo(201);
        assertThat(
                        put(
                                        "/v1/operator/merchants/" + merchantId + "/fee-schedule",
                                        "{\"feeScheduleId\":\"" + schedule
                                                + "\",\"reason\":\"standard terms\"}",
                                        operator)
                                .statusCode())
                .isEqualTo(200);
        return new Merchant(merchantId, issueKey(operator, merchantId));
    }

    /** Onboarded and keyed, but never assigned a schedule. */
    private Merchant merchantWithoutPricing() throws Exception {
        String operator = operatorSession();
        String merchantId = onboard(operator);
        return new Merchant(merchantId, issueKey(operator, merchantId));
    }

    private void suspend(Merchant merchant) throws Exception {
        assertThat(
                        post(
                                        "/v1/operator/merchants/" + merchant.id() + "/suspension",
                                        "{\"reason\":\"under investigation\"}",
                                        operatorSession(),
                                        null)
                                .statusCode())
                .isEqualTo(200);
    }

    private String onboard(String operator) throws Exception {
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
                        operator,
                        someKey());
        assertThat(created.statusCode()).isEqualTo(201);
        return field(created.body(), "merchantId");
    }

    /** The key's public half: a presented key is {@code <keyId>.<secret>} (ADR-0052). */
    private static MerchantApiKeyId keyIdOf(Merchant merchant) {
        return MerchantApiKeyId.of(
                UUID.fromString(merchant.apiKey().substring(0, merchant.apiKey().indexOf('.'))));
    }

    private String issueKey(String operator, String merchantId) throws Exception {
        HttpResponse<String> issued =
                post(
                        "/v1/operator/merchants/" + merchantId + "/api-keys",
                        null,
                        operator,
                        someKey());
        assertThat(issued.statusCode()).isEqualTo(201);
        return field(issued.body(), "keyId") + "." + field(issued.body(), "secret");
    }

    /** A verified customer with a EUR wallet and an attached instrument. */
    private Customer payingCustomer() throws Exception {
        String login = "chk." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(register(login).statusCode()).isEqualTo(201);
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "UPDATE party.customer SET status = 'ACTIVE', status_changed_at ="
                            + " GREATEST(now(), opened_at) WHERE party_id = (SELECT party_id"
                            + " FROM identity.identity WHERE login_identifier = ?)",
                    login);
        }
        String token = tokenFrom(authenticate(login).body());

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
                        null);
        assertThat(attached.statusCode()).isEqualTo(201);
        return new Customer(token, field(attached.body(), "id"));
    }

    private String operatorSession() throws Exception {
        return operatorSession(RoleName.MERCHANT_ADMINISTRATOR);
    }

    /** A signed-in staff member holding exactly {@code role}. */
    private String operatorSession(RoleName role) throws Exception {
        String login = "ops." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
                    role,
                    IdentityId.of(identity),
                    "test fixture");
            app.commit();
        }
        return tokenFrom(authenticate(login).body());
    }

    // ----------------------------------------------------------------- calls

    private HttpResponse<String> createSession(Merchant merchant, long amountMinor, String key)
            throws Exception {
        return createSession(merchant, amountMinor, "EUR", key);
    }

    private HttpResponse<String> createSession(
            Merchant merchant, long amountMinor, String currency, String key) throws Exception {
        return post(
                "/v1/checkout/sessions",
                "{\"amountMinor\":" + amountMinor + ",\"currency\":\"" + currency + "\","
                        + "\"lineSummary\":\"Two coffees and a pastry\"}",
                merchant.apiKey(),
                key);
    }

    private HttpResponse<String> readSession(Merchant merchant, String id) throws Exception {
        return get("/v1/checkout/sessions/" + id, merchant.apiKey());
    }

    private HttpResponse<String> confirm(Customer customer, String token) throws Exception {
        return post(
                "/v1/checkout/sessions/confirmation",
                "{\"sessionToken\":\"" + token + "\",\"paymentMethodId\":\""
                        + customer.methodId() + "\"}",
                customer.token(),
                null);
    }

    private void providerAuthorises() {
        provider.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"psp_auth-" + UUID.randomUUID() + "\"}");
    }

    private void providerCaptures() {
        provider.succeedsWith(
                SimulatedCardPspAdapter.CAPTURES_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"psp_cap-" + UUID.randomUUID() + "\"}");
    }

    // ----------------------------------------------------------------- reads

    private static long payablePositionMinor(Connection app, Merchant merchant)
            throws SQLException {
        // Derived from the POSTINGS, never read from a column (INV-MER-02): credits minus
        // debits on the merchant's payable, which is the only place the figure exists.
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT COALESCE(SUM(CASE WHEN line.direction = 'CREDIT'"
                                + " THEN line.amount_minor ELSE -line.amount_minor END), 0)"
                                + " FROM ledger.journal_line line"
                                + " JOIN ledger.ledger_account account"
                                + "   ON account.id = line.ledger_account_id"
                                + " WHERE account.purpose = 'MERCHANT_PAYABLE'"
                                + "   AND account.owner_ref = ?")) {
            read.setObject(1, UUID.fromString(merchant.id()));
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static String attemptIdForSession(Connection app, String checkoutId)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT a.id FROM payments.payment_attempt a"
                                + " JOIN checkout.checkout_session s"
                                + "   ON s.payment_intent_ref = a.intent_id"
                                + " WHERE s.id = ?")) {
            read.setObject(1, UUID.fromString(checkoutId));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("the session opened an attempt").isTrue();
                return row.getString(1);
            }
        }
    }

    private static List<String> linePurposes(Connection app, String attemptId)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT line.direction, account.purpose FROM ledger.journal_line line"
                                + " JOIN ledger.journal_entry entry ON entry.id = line.entry_id"
                                + " JOIN ledger.ledger_account account"
                                + "   ON account.id = line.ledger_account_id"
                                + " WHERE entry.reference = ?")) {
            read.setString(1, attemptId);
            try (ResultSet rows = read.executeQuery()) {
                List<String> lines = new ArrayList<>();
                while (rows.next()) {
                    lines.add(rows.getString(1) + ":" + rows.getString(2));
                }
                return lines;
            }
        }
    }

    private static String entryIdByReference(Connection app, String reference)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT id FROM ledger.journal_entry WHERE reference = ?")) {
            read.setString(1, reference);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static String capturedEntryOfOrder(Connection app, String orderId)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT captured_entry_ref FROM checkout.checkout_order WHERE id = ?")) {
            read.setObject(1, UUID.fromString(orderId));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("the order exists").isTrue();
                return row.getString(1);
            }
        }
    }

    private static long orderCountFor(Connection app, Merchant merchant) throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT count(*) FROM checkout.checkout_order WHERE merchant_ref = ?")) {
            read.setObject(1, UUID.fromString(merchant.id()));
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    /**
     * The fee pins priced for the payment intent this merchant's session carries - merchant
     * V005 keys the pin by that intent, so one agreed price per payment or a defect.
     */
    private static long feePinsForTheSessionsIntent(Connection app, Merchant merchant)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT count(*) FROM merchant.payment_fee_pin pin"
                                + " JOIN checkout.checkout_session s"
                                + "   ON s.payment_intent_ref = pin.payment_intent_ref"
                                + " WHERE s.merchant_ref = ?")) {
            read.setObject(1, UUID.fromString(merchant.id()));
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    /** The one checkout.OrderPaid announcement of this session's order, as its bytes read. */
    private static String orderPaidPayload(Connection app, String checkoutId)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT event.payload FROM platform.outbox_event event"
                                + " JOIN checkout.checkout_order o ON o.id = event.aggregate_id"
                                + " WHERE event.event_type = 'checkout.OrderPaid'"
                                + " AND o.session_ref = ?")) {
            read.setObject(1, UUID.fromString(checkoutId));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("the order was announced").isTrue();
                String payload = new String(row.getBytes(1), StandardCharsets.UTF_8);
                assertThat(row.next()).as("once").isFalse();
                return payload;
            }
        }
    }

    private static long outboxCount(Connection app, String eventType, String aggregateId)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT count(*) FROM platform.outbox_event WHERE event_type = ?"
                                + " AND aggregate_id = ?")) {
            read.setString(1, eventType);
            read.setObject(2, UUID.fromString(aggregateId));
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    /** The one version the merchant's schedule holds - `tradingMerchant` adds exactly one. */
    private static UUID versionOf(Merchant merchant) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT version.id FROM merchant.fee_schedule_version version"
                                        + " JOIN merchant.merchant_fee_schedule assignment"
                                        + "   ON assignment.fee_schedule_id ="
                                        + " version.fee_schedule_id"
                                        + " WHERE assignment.merchant_id = ?")) {
            read.setObject(1, UUID.fromString(merchant.id()));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private static String statusAndIntentOf(UUID session) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT status, payment_intent_ref FROM checkout.checkout_session"
                                        + " WHERE id = ?")) {
            read.setObject(1, session);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1) + ":" + row.getString(2);
            }
        }
    }

    /** Payment intents whose capture would credit the merchant's payable. */
    private static long intentsCrediting(Merchant merchant) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT count(*) FROM payments.payment_intent intent"
                                        + " JOIN ledger.ledger_account account"
                                        + "   ON account.id = intent.credit_account_id"
                                        + " WHERE account.owner_ref = ?"
                                        + "   AND account.purpose = 'MERCHANT_PAYABLE'")) {
            read.setObject(1, UUID.fromString(merchant.id()));
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long sessionCount(Merchant merchant) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT count(*) FROM checkout.checkout_session WHERE"
                                        + " merchant_ref = ?")) {
            read.setObject(1, UUID.fromString(merchant.id()));
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    /** Every text column in every schema holding {@code value} — derived, not listed. */
    private static List<String> columnsHolding(String value) throws SQLException {
        List<String> found = new ArrayList<>();
        try (Connection app = DatabaseRoles.application()) {
            List<String[]> columns = new ArrayList<>();
            try (PreparedStatement read =
                            app.prepareStatement(
                                    "SELECT table_schema, table_name, column_name, data_type FROM"
                                            + " information_schema.columns WHERE data_type IN"
                                            + " ('text', 'character varying', 'bytea') AND"
                                            + " table_schema NOT IN ('pg_catalog',"
                                            + " 'information_schema')");
                    ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    columns.add(
                            new String[] {
                                rows.getString(1),
                                rows.getString(2),
                                rows.getString(3),
                                rows.getString(4)
                            });
                }
            }
            for (String[] column : columns) {
                String qualified = "\"" + column[0] + "\".\"" + column[1] + "\"";
                // A BYTEA COLUMN NEEDS A DIFFERENT CAST, and this is the P6-TSK-007 gate's
                // finding: `bytea::text` renders `\x7365...`, so a `LIKE` over it can never
                // match a printable needle. The one column on this platform that a credential
                // most plausibly leaks into -- platform.idempotency_record.response_body, the
                // stored response of a keyed command -- is exactly that type, so the sweep was
                // blind in precisely the place it most needed to see. `encode(col, 'escape')`
                // renders printable ASCII as itself and, unlike convert_from, never throws on
                // bytes that are not valid UTF-8.
                String readable =
                        "bytea".equals(column[3])
                                ? "encode(\"" + column[2] + "\", 'escape')"
                                : "\"" + column[2] + "\"::text";
                try (PreparedStatement probe =
                        app.prepareStatement(
                                "SELECT count(*) FROM " + qualified + " WHERE " + readable
                                        + " LIKE ?")) {
                    probe.setString(1, "%" + value + "%");
                    try (ResultSet row = probe.executeQuery()) {
                        if (row.next() && row.getLong(1) > 0) {
                            found.add(column[0] + "." + column[1] + "." + column[2]);
                        }
                    }
                } catch (SQLException unreadable) {
                    // A column this role cannot read cannot be holding our token for us.
                }
            }
        }
        return found;
    }

    // ----------------------------------------------------------------- http

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
                null);
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + bearer)
                        .GET()
                        .build());
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

    private HttpResponse<String> put(String path, String body, String bearer) throws Exception {
        return send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + bearer)
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void execute(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(String body, String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String tokenFrom(String body) {
        return field(body, "sessionToken");
    }
}
