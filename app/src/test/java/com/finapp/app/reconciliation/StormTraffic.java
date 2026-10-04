package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantPayoutStatus;
import com.finapp.merchant.MerchantPayouts;
import com.finapp.merchant.SimulatedPayoutProvider;
import com.finapp.paymentmethods.SimulatedTokenisationAdapter;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The settlement storm's movers (`P8-TST-001`) — a purpose-built, smaller set MIRRORING the doors
 * {@code MultiRailConservationStormDatabaseTest}'s movers use (the 3,025-line Phase 7 storm is not
 * refactored; its doors are copied here): every internal record the storm reconciles is made by
 * the application's own routes and beans, so each completion posts its clearing line and opens its
 * expectation exactly as production does —
 *
 * <ul>
 *   <li>a card top-up ({@code /v1/payments} on a tokenised card) and a card checkout sale
 *       ({@code /v1/checkout/sessions}) capture on the PSP: {@code CARD_CAPTURE} on
 *       {@code SETTLEMENT_CLEARING};
 *   <li>an operator's refund of a card top-up ({@code /v1/payments/{id}/refund}):
 *       {@code CARD_REFUND};
 *   <li>a bank top-up ({@code /v1/payments} on a registered bank account), executed by the
 *       scheme's signed callback, delivered three times — twice under one event id, once under a
 *       fresh one: {@code PUSH_PAY_IN} on {@code INSTANT_CLEARING}, the cycle it ANNOUNCED on the
 *       row;
 *   <li>a withdrawal ({@code /v1/me/withdrawals}): {@code PUSH_WITHDRAWAL}, the cycle the scheme
 *       stated;
 *   <li>a merchant payout through the real {@link MerchantPayouts} against the simulated payout
 *       provider: {@code MERCHANT_PAYOUT} on {@code PAYOUT_CLEARING}.
 * </ul>
 *
 * <p>Every provider reference the stubs mint is LETTERS only, so no digit run of card length can
 * ever reach the settlement door's screen when the reports quote them back (the
 * {@code P8-TSK-020} flake's lesson). Staff sessions are seeded directly (identity, role,
 * session) as the Phase 8 suites seed them; customers register over the real door, because the
 * {@code /v1/me} routes need the whole party.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
final class StormTraffic {

    static final String PASSWORD = "a-perfectly-fine-pw-7";
    static final byte[] CARD_WEBHOOK_KEY =
            "storm-card-webhook-key-32-bytes!".getBytes(StandardCharsets.UTF_8);
    static final byte[] INSTANT_WEBHOOK_KEY =
            "storm-instant-webhook-key-32byte".getBytes(StandardCharsets.UTF_8);

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Pattern CODE = Pattern.compile("\"code\":\"([^\"]*)\"");

    /** A letters-only provider reference, minted per call by the stub's template. */
    private static String minted(String prefix) {
        return prefix + "{{randomValue length=16 type='ALPHABETIC'}}";
    }

    /** A customer: session, wallet product and its ledger account, a card and a bank account. */
    record Customer(String token, String product, UUID wallet, String card, String bank) {}

    /** An onboarded merchant: its id, its API key, and its payable once it has sold. */
    record Merchant(String id, String key, UUID payable) {}

    /** A staff member's session; the identity is the actor every record names. */
    record Staff(IdentityId identity, String token) {

        String actorId() {
            return identity.value().toString();
        }
    }

    private final int port;
    private final SimulatedProvider provider;
    private final Authorization authorization;
    private final MerchantPayouts payouts;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    StormTraffic(
            int port,
            SimulatedProvider provider,
            Authorization authorization,
            MerchantPayouts payouts) {
        this.port = port;
        this.provider = provider;
        this.authorization = authorization;
        this.payouts = payouts;
    }

    // ----------------------------------------------------------------- the rails

    /**
     * Every rail answers truthfully, each operation with a reference of its own (each
     * provider-reference column is {@code UNIQUE}); a withdrawal is executed in
     * {@code withdrawalCycle}, which the scheme states on its answer.
     */
    void stubTheRails(String withdrawalCycle) {
        approves(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, "pspa");
        approves(SimulatedCardPspAdapter.CAPTURES_PATH, "pspc");
        approves(SimulatedCardPspAdapter.REFUNDS_PATH, "pspr");
        approves(SimulatedCardPspAdapter.VOIDS_PATH, "pspv");
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.INITIATIONS_PATH,
                200,
                "{\"status\":\"initiated\",\"handle\":\"https://payer-psp.example/authorize/"
                        + "{{request.headers.Idempotency-Key}}\"}");
        withdrawalsSettleIn(withdrawalCycle);
        provider.succeedsWith(
                SimulatedPayoutProvider.PAYOUTS_PATH,
                200,
                "{\"status\":\"paid\",\"reference\":\"" + minted("po_") + "\"}");
    }

    /** The scheme's answer to a withdrawal from now on: accepted, executed in {@code cycle}. */
    void withdrawalsSettleIn(String cycle) {
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.TRANSFERS_PATH,
                200,
                "{\"status\":\"accepted\",\"reference\":\"" + minted("SCHW-")
                        + "\",\"cycle\":\"" + cycle + "\"}");
    }

    private void approves(String path, String prefix) {
        provider.succeedsWith(
                path, 200, "{\"status\":\"approved\",\"reference\":\"" + minted(prefix) + "\"}");
    }

    // ----------------------------------------------------------------- the world

    /**
     * A verified customer over the real doors: registered, activated, signed in, a EUR wallet
     * opened, a card tokenised and a bank account registered through the real exchange.
     */
    Customer customer() throws Exception {
        String login = "storm." + letters(12).toLowerCase(java.util.Locale.ROOT);
        HttpResponse<String> registered =
                post("/v1/registrations",
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                                + "\"password\":\"" + PASSWORD + "\"}",
                        null, someKey());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "UPDATE party.customer SET status = 'ACTIVE', status_changed_at ="
                            + " GREATEST(now(), opened_at) WHERE party_id = (SELECT party_id"
                            + " FROM identity.identity WHERE login_identifier = ?)",
                    login);
        }
        String token =
                field(post("/v1/authentications",
                                "{\"loginIdentifier\":\"" + login + "\",\"password\":\""
                                        + PASSWORD + "\"}",
                                null, null).body(),
                        "sessionToken");
        HttpResponse<String> opened =
                post("/v1/me/accounts", "{\"productType\":\"WALLET\",\"currency\":\"EUR\"}",
                        token, someKey());
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        String product = field(opened.body(), "id");

        String suffix = letters(8).toLowerCase(java.util.Locale.ROOT);
        provider.succeedsWith(
                SimulatedTokenisationAdapter.TOKENISATIONS_PATH,
                200,
                "{\"status\":\"tokenised\",\"token\":\"tok_" + suffix + "\",\"brand\":\"Visa\","
                        + "\"last4\":\"4242\",\"expiryMonth\":12,\"expiryYear\":2030}");
        HttpResponse<String> attached =
                post("/v1/me/payment-methods", "{\"clientToken\":\"ctok_" + suffix + "\"}",
                        token, null);
        assertThat(attached.statusCode()).as(attached.body()).isEqualTo(201);

        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.EXCHANGES_PATH,
                200,
                "{\"status\":\"exchanged\",\"destination\":\"dest-storm-" + letters(12)
                        + "\",\"suffix\":\"6819\",\"payee\":\"match\"}");
        HttpResponse<String> bank =
                post("/v1/me/payment-methods/bank-accounts",
                        "{\"grant\":\"blg-" + UUID.randomUUID() + "\",\"acknowledgeNoMatch\":false}",
                        token, someKey());
        assertThat(bank.statusCode()).as(bank.body()).isEqualTo(201);

        UUID wallet;
        try (Connection app = DatabaseRoles.application()) {
            wallet =
                    UUID.fromString(
                            one(app,
                                    "SELECT id::text FROM ledger.ledger_account WHERE owner_ref"
                                            + " = ?::uuid AND purpose = 'CUSTOMER_WALLET'",
                                    product));
        }
        return new Customer(token, product, wallet, field(attached.body(), "id"),
                field(bank.body(), "id"));
    }

    /**
     * A staff member holding exactly {@code role}: identity, role and session seeded the way the
     * Phase 8 suites seed their operators.
     */
    Staff staff(RoleName role) throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Storm Desk Person', now())",
                    party);
            execute(app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE',"
                            + " now(), now())",
                    identity, party, "st" + letters(20).toLowerCase(java.util.Locale.ROOT));
        }
        IdentityId id = IdentityId.of(identity);
        try (CorrelationContext.Scope correlation =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, id, role, id, "storm fixture");
            app.commit();
        }
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        com.finapp.identity.Session.Draft session =
                com.finapp.identity.Session.issue(
                        IDS, CLOCK, id, SessionToken.of(plaintext), AssuranceLevel.PASSWORD,
                        SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return new Staff(id, plaintext);
    }

    /**
     * An onboarded, ACTIVE merchant over the operator routes, priced at 2.9% + 0.30, keyed, with
     * an EFFECTIVE payout destination (the payout suites' seeded row - its four-eyes effectuation
     * is {@code P6-TSK-011}'s subject, not this storm's).
     */
    Merchant merchant(Staff administrator) throws Exception {
        return merchant(administrator, EUR);
    }

    /**
     * The same merchant settling in {@code currency} (`P9-TSK-003`): its settlement currency and
     * its fee schedule's, priced at 2.9% plus 30 of the currency's own minor units.
     */
    Merchant merchant(Staff administrator, CurrencyCode currency) throws Exception {
        UUID party = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES"
                            + " (?, 'ORGANISATION', 'Storm Traders', now())",
                    party);
            execute(app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now())",
                    IDS.next(), party);
        }
        HttpResponse<String> created =
                post("/v1/operator/merchants",
                        "{\"partyId\":\"" + party + "\",\"legalName\":\"Storm Traders GmbH\","
                                + "\"displayName\":\"Storm\",\"settlementCurrency\":\""
                                + currency.code() + "\"}",
                        administrator.token(), someKey());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String merchantId = field(created.body(), "merchantId");
        String schedule =
                field(post("/v1/operator/fee-schedules",
                                        "{\"name\":\"Storm " + UUID.randomUUID()
                                                + "\",\"currency\":\"" + currency.code()
                                                + "\"}",
                                        administrator.token(), null)
                                .body(),
                        "feeScheduleId");
        HttpResponse<String> version =
                post("/v1/operator/fee-schedules/" + schedule + "/versions",
                        "{\"rate\":0.029,\"fixedAmountMinor\":30,\"roundingPolicy\":\"HALF_EVEN\","
                                + "\"refundFeePolicy\":\"RETAINED\",\"reason\":\"storm pricing\"}",
                        administrator.token(), null);
        assertThat(version.statusCode()).as(version.body()).isEqualTo(201);
        HttpResponse<String> priced =
                send("PUT", "/v1/operator/merchants/" + merchantId + "/fee-schedule",
                        "{\"feeScheduleId\":\"" + schedule + "\",\"reason\":\"standard terms\"}",
                        administrator.token(), null);
        assertThat(priced.statusCode()).as(priced.body()).isEqualTo(200);
        HttpResponse<String> issued =
                post("/v1/operator/merchants/" + merchantId + "/api-keys", null,
                        administrator.token(), someKey());
        assertThat(issued.statusCode()).as(issued.body()).isEqualTo(201);
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO merchant.payout_destination (id, merchant_id,"
                            + " destination_reference, display_suffix, status, proposed_by,"
                            + " proposed_at, proposal_reason, approved_by, approved_at,"
                            + " cooling_off_until, effective_at) VALUES (?, ?::uuid, ?, '3000',"
                            + " 'EFFECTIVE', 'fixture-a', now() - interval '4 days', 'fixture',"
                            + " 'fixture-b', now() - interval '4 days', now() - interval"
                            + " '1 day', now() - interval '1 hour')",
                    IDS.next(), merchantId,
                    "pdr_" + letters(12).toLowerCase(java.util.Locale.ROOT));
        }
        return new Merchant(merchantId,
                field(issued.body(), "keyId") + "." + field(issued.body(), "secret"), null);
    }

    /** The merchant with its payable, which exists once it has sold. */
    Merchant withPayable(Merchant merchant) throws SQLException {
        return withPayable(merchant, EUR);
    }

    /** The merchant with its payable in {@code currency}, which exists once it has sold. */
    Merchant withPayable(Merchant merchant, CurrencyCode currency) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return new Merchant(merchant.id(), merchant.key(),
                    UUID.fromString(
                            one(app,
                                    "SELECT id::text FROM ledger.ledger_account WHERE owner_ref"
                                            + " = ?::uuid AND purpose = 'MERCHANT_PAYABLE' AND"
                                            + " currency = ?",
                                    merchant.id(), currency.code())));
        }
    }

    // ----------------------------------------------------------------- the movers

    /** A card top-up, captured at once: the intent's id. */
    String cardTopUp(Customer customer, long minor) throws Exception {
        HttpResponse<String> confirmed = pay(customer, customer.card(), minor);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(field(confirmed.body(), "status"))
                .as("a card top-up captures synchronously: %s", confirmed.body())
                .isEqualTo("SUCCEEDED");
        return field(confirmed.body(), "id");
    }

    /** A bank top-up, initiated and awaiting the payer: the intent's id. */
    String bankTopUp(Customer customer, long minor) throws Exception {
        HttpResponse<String> confirmed = pay(customer, customer.bank(), minor);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(field(confirmed.body(), "status"))
                .as("a pay-in waits for the scheme: %s", confirmed.body())
                .isEqualTo("PROCESSING");
        return field(confirmed.body(), "id");
    }

    /**
     * The scheme executes a pay-in: its signed callback carrying {@code schemeRef} and the
     * announced {@code cycle}, delivered three times - twice under one event id, once under a
     * fresh one - each answered 204, one effect.
     */
    void executePayIn(String intentId, long minor, String schemeRef, String cycle)
            throws SQLException {
        String endToEnd;
        try (Connection app = DatabaseRoles.application()) {
            endToEnd =
                    one(app,
                            "SELECT end_to_end_reference FROM payments.payment_attempt WHERE"
                                    + " intent_id = ?::uuid AND rail = 'instant' AND status ="
                                    + " 'AWAITING_PAYER'",
                            intentId);
        }
        assertThat(endToEnd).as("the pay-in %s awaits its payer", intentId).isNotNull();
        String eventId = "evt_" + IDS.next();
        String amount = BigDecimal.valueOf(minor, 2).toPlainString();
        for (String event : List.of(eventId, eventId, "evt_" + IDS.next())) {
            String body = "{\"eventId\":\"" + event + "\",\"reference\":\"" + endToEnd
                    + "\",\"status\":\"executed\",\"schemeReference\":\"" + schemeRef
                    + "\",\"settlementCycle\":\"" + cycle + "\",\"amount\":\"" + amount
                    + "\",\"currency\":\"EUR\"}";
            int status =
                    provider.deliverTimestampSignedCallback(
                            URI.create("http://localhost:" + port
                                    + "/v1/providers/payments/instant/webhooks"),
                            body, INSTANT_WEBHOOK_KEY, Instant.now(CLOCK).getEpochSecond(), 1);
            assertThat(status).as("an execution callback, delivered again, answers 204")
                    .isEqualTo(204);
        }
    }

    /** A withdrawal, completed at once by the scheme: its id. */
    String withdraw(Customer customer, long minor) throws Exception {
        HttpResponse<String> answer =
                post("/v1/me/withdrawals",
                        "{\"paymentMethodId\":\"" + customer.bank() + "\",\"amount\":\""
                                + decimal(minor) + "\",\"currency\":\"EUR\"}",
                        customer.token(), someKey());
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(201);
        assertThat(field(answer.body(), "status")).as(answer.body()).isEqualTo("COMPLETED");
        return field(answer.body(), "id");
    }

    /** An operator's refund of a captured top-up, completed at once by the PSP: its id. */
    String refund(Staff operator, String intentId, long minor) throws Exception {
        HttpResponse<String> answer =
                post("/v1/payments/" + intentId + "/refund",
                        "{\"amount\":\"" + decimal(minor) + "\",\"currency\":\"EUR\","
                                + "\"reason\":\"storm refund\"}",
                        operator.token(), someKey());
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(201);
        assertThat(field(answer.body(), "status")).as(answer.body()).isEqualTo("COMPLETED");
        return field(answer.body(), "id");
    }

    /** A checkout paid by card, completed: the merchant's payable credited. */
    void cardSale(Merchant merchant, Customer customer, long minor) throws Exception {
        cardSale(merchant, customer, minor, EUR);
    }

    /** A checkout in {@code currency}, paid by card, completed (`P9-TSK-003`). */
    void cardSale(Merchant merchant, Customer customer, long minor, CurrencyCode currency)
            throws Exception {
        HttpResponse<String> opened =
                post("/v1/checkout/sessions",
                        "{\"amountMinor\":" + minor + ",\"currency\":\"" + currency.code()
                                + "\","
                                + "\"lineSummary\":\"A storm of coffees\"}",
                        merchant.key(), someKey());
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        HttpResponse<String> confirmed =
                post("/v1/checkout/sessions/confirmation",
                        "{\"sessionToken\":\"" + field(opened.body(), "sessionToken")
                                + "\",\"paymentMethodId\":\"" + customer.card() + "\"}",
                        customer.token(), null);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(field(confirmed.body(), "status")).as(confirmed.body())
                .isEqualTo("COMPLETED");
    }

    /** A payout through the REAL merchant flow, paid by the simulated provider: its id. */
    UUID payout(Merchant merchant, long minor) {
        try (CorrelationContext.Scope scope =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope acting =
                        SecurityContext.enter(new Actor(merchant.id(), ActorType.MERCHANT))) {
            MerchantPayouts.Initiated initiated =
                    payouts.initiate(
                            new MerchantPayouts.InitiateCommand(
                                    MerchantId.of(UUID.fromString(merchant.id())),
                                    Money.ofPersisted(minor, EUR, 2),
                                    "storm-payout-" + UUID.randomUUID(), Optional.empty(),
                                    Optional.empty()));
            assertThat(initiated.status()).isEqualTo(MerchantPayoutStatus.COMPLETED);
            return initiated.payout().value();
        }
    }

    // ----------------------------------------------------------------- the wire

    private HttpResponse<String> pay(Customer customer, String method, long minor)
            throws Exception {
        HttpResponse<String> created =
                post("/v1/payments",
                        "{\"paymentMethodId\":\"" + method + "\",\"amount\":\""
                                + decimal(minor) + "\",\"currency\":\"EUR\"}",
                        customer.token(), someKey());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return post("/v1/payments/" + field(created.body(), "id") + "/confirmation", null,
                customer.token(), null);
    }

    HttpResponse<String> post(String path, String body, String token, String key)
            throws Exception {
        return send("POST", path, body, token, key);
    }

    HttpResponse<String> get(String path, String token) throws Exception {
        return send("GET", path, null, token, null);
    }

    HttpResponse<String> send(String method, String path, String body, String token, String key)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(90))
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    void close() {
        http.close();
    }

    static String field(String body, String name) {
        Matcher found =
                Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]*)\"").matcher(body);
        assertThat(found.find()).as("%s in %s", name, body).isTrue();
        return found.group(1);
    }

    static String code(HttpResponse<String> answer) {
        Matcher found = CODE.matcher(answer.body());
        return found.find() ? found.group(1) : "";
    }

    /** Minor units as the API's decimal string - EUR has two minor units. */
    static String decimal(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }

    static String someKey() {
        return UUID.randomUUID().toString();
    }

    /** Letters only: no digit run any screen could read as a card number. */
    static String letters(int length) {
        StringBuilder letters = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            letters.append((char) ('A' + RANDOMNESS.nextInt(26)));
        }
        return letters.toString();
    }

    private static void execute(Connection app, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }

    private static String one(Connection app, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        }
    }
}
