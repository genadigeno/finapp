package com.finapp.app.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.checkout.CheckoutSessions;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
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

    // ----------------------------------------------------------------- fixtures

    private record Merchant(String id, String apiKey) {}

    private record Customer(String token, String methodId) {}

    /** An onboarded, ACTIVE merchant with a fee schedule assigned and an API key. */
    private Merchant tradingMerchant(String rate, long fixedMinor) throws Exception {
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
                                                + "\"refundFeePolicy\":\"RETAINED\","
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
                    RoleName.MERCHANT_ADMINISTRATOR,
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
