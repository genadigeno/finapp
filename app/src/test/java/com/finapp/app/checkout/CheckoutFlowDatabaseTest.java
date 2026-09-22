package com.finapp.app.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.checkout.CheckoutSessions;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.checkout.CheckoutSessionId;
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
        String token = field(createSession(merchant, AMOUNT_MINOR, someKey()).body(), "sessionToken");
        providerAuthorises();
        providerCaptures();

        int racers = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            for (int i = 0; i < racers; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return confirm(customer, token).statusCode();
                                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get(120, TimeUnit.SECONDS))
                        .as("every racer gets an answer, none an error")
                        .isBetween(200, 409);
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
        }
    }

    /** The payments sweeper, with no patience at all — the production outcome path. */
    private com.finapp.payments.PaymentSweeper paymentSweeper() {
        return new com.finapp.payments.PaymentSweeper(
                paymentTransactionRunner,
                attempts,
                intents,
                evidence,
                paymentProvider,
                paymentOutcomes,
                IDS,
                CLOCK,
                java.time.Duration.ZERO,
                java.time.Duration.ZERO,
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
                50);
    }

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

    /** position == captured - fees - refunded + feesReturned + other, read from the body. */
    private static boolean explainsItself(String body) {
        java.math.BigDecimal position = new java.math.BigDecimal(field(body, "position"));
        java.math.BigDecimal terms =
                new java.math.BigDecimal(field(body, "captured"))
                        .subtract(new java.math.BigDecimal(field(body, "fees")))
                        .subtract(new java.math.BigDecimal(field(body, "refunded")))
                        .add(new java.math.BigDecimal(field(body, "feesReturned")))
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
    private boolean abandonDirectly(MerchantId merchant, CheckoutSessionId id) throws Exception {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                boolean acted = sessions.abandon(app, merchant, id, "driven at the command");
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
                                        MerchantId.of(UUID.fromString(stranger.id())),
                                        CheckoutSessionId.of(UUID.fromString(checkoutId))))
                .isInstanceOf(UnknownCheckoutSessionException.class);
        assertThat(sessionStatus(checkoutId)).isEqualTo("OPEN");

        assertThat(
                        abandonDirectly(
                                MerchantId.of(UUID.fromString(owner.id())),
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
        // refactor removes (P6-TSK-008's survivor lesson) - and this one is also invisible to
        // OwnershipIsScopedTest, whose detector keys on EntityId-typed parameters while
        // checkout holds every cross-module reference by value, as a bare UUID (ADR-0029).
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

    @Test
    @DisplayName("A GATE FINDING, PINNED (P6-TSK-010 -> P6-TSK-015): a FULL refund of a"
            + " merchant-bound capture is REFUSED AS UNFUNDED under either fee policy")
    void aFullMerchantRefundIsRefusedAsUnfunded() throws Exception {
        // FOUND BY THIS TASK'S END-TO-END TEST, and pinned so the task that decides it must
        // CHANGE THIS ASSERTION rather than discover the question.
        //
        // Phase 5's refund places a hold on the account it debits and judges affordability
        // against that account's available position - in PAYMENT vocabulary, on the GROSS.
        // For a merchant-bound payment that account is the PAYABLE, which after the capture
        // holds the NET (96.80 of 100.00). So a full refund is always unfunded:
        //
        //   RETURNED - the composed entry's net is -96.80 (the gross out, the 3.20 fee back),
        //              which lands the payable at EXACTLY ZERO and is refused anyway, because
        //              the bound never sees the fee coming back. This half is a defect.
        //   RETAINED - the merchant genuinely cannot fund 100.00 from 96.80; allowing it would
        //              take the payable negative, an exposure to the merchant nobody decided to
        //              carry. This half is a DECISION the platform has not made: refuse, or
        //              permit a negative payable recovered from future captures.
        //
        // P6-TSK-014's tests posted through the composition seam directly and never crossed the
        // refund command's funding bound, so they could not see this. Owned by P6-TSK-015.
        for (String policy : new String[] {"RETAINED", "RETURNED"}) {
            Merchant merchant = tradingMerchant("0.029", 30L, policy);
            String intent = intentOfSession(purchase(merchant, payingCustomer()));
            provider.succeedsWith(
                    SimulatedCardPspAdapter.REFUNDS_PATH,
                    200,
                    "{\"status\":\"approved\",\"reference\":\"psp_ref-" + UUID.randomUUID()
                            + "\"}");

            HttpResponse<String> refused =
                    post(
                            "/v1/payments/" + intent + "/refund",
                            "{\"amount\":\"100.00\",\"currency\":\"EUR\","
                                    + "\"reason\":\"order cancelled\"}",
                            operatorSession(RoleName.LEDGER_OPERATOR),
                            someKey());

            assertThat(refused.statusCode())
                    .as("policy %s: when this becomes 201, P6-TSK-015 has landed and this test is"
                            + " its assertion to rewrite", policy)
                    .isEqualTo(409);
            assertThat(refused.body()).contains("payments.RefundUnfunded");
            assertThat(payable(merchant).body())
                    .as("and the refusal moved nothing")
                    .contains("\"position\":\"96.80\"");
        }
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
        }

        // THE METER, THROUGH THE WIRED PATH (PHASE_6_PLAN section 15): finapp.checkout.session
        // by outcome, one increment per ENDING, on the conditional that actually committed.
        // completed_late is the number this whole task exists to make visible - if it is not
        // rare, either the offer window is too short or the provider is too slow, and an
        // operator cannot decide either without the figure.
        assertThat(sessionMeter("completed_late") - lateBefore)
                .as("the late completion, counted once")
                .isEqualTo(1.0d);
        // The `expired` counter is the SCHEDULE's to increment, from the tick's own tally
        // after each row's transaction committed, and this test drives the sweeper directly.
        // Its wiring is CheckoutExpirySweeperScheduleTest's, where the schedule is the
        // subject - asserting it here would assert a path this test does not take.
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
        return post(
                "/v1/checkout/sessions",
                "{\"amountMinor\":" + amountMinor + ",\"currency\":\"EUR\","
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
