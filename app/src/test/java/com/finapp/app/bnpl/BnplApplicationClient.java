package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplTerms;
import com.finapp.bnpl.BnplTermsAdministration;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.merchant.BnplEnablement;
import com.finapp.merchant.BnplEnablements;
import com.finapp.merchant.BnplPricing;
import com.finapp.merchant.FeeRate;
import com.finapp.merchant.FeeSchedule;
import com.finapp.merchant.FeeSchedules;
import com.finapp.merchant.MerchantBnplProduct;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.RefundFeePolicy;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The BNPL application suites' client and world (`P12-TSK-010`; {@code LoanApplicationClient}'s shape): the three doors
 * and their bodies over HTTP; a trading merchant with an API key, its card pricing, and - through merchant's own domain,
 * four eyes kept - its BNPL enablement and both products' returning BNPL fee schedules; a checkout session opened by the
 * merchant's key, whose token the customer holds; each BNPL product's terms in force (one officer proposes, another
 * activates); and counts read as the schema owner. Written by `P12-TSK-010` for its database suites - owed their first
 * run under the owner's no-{@code databaseTest} instruction.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
final class BnplApplicationClient {

    static final String APPLICATIONS = "/v1/me/bnpl-applications";
    static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    private final int port;

    BnplApplicationClient(int port) {
        this.port = port;
    }

    /** A merchant: its id and its API key. */
    record Merchant(String id, String apiKey) {

        UUID ref() {
            return UUID.fromString(id);
        }
    }

    /** A checkout session: its id and the token its customer holds. */
    record Opened(UUID id, String token) {}

    // ------------------------------------------------------------------ the doors

    static String body(String sessionToken, String product, String plan) {
        return "{\"sessionToken\":\"" + sessionToken + "\",\"product\":\"" + product + "\",\"plan\":\"" + plan + "\"}";
    }

    static String payIn4(String sessionToken) {
        return body(sessionToken, "BNPL_PAY_IN_4", "PAY_IN_4");
    }

    static String payInN(String sessionToken, int months) {
        return body(sessionToken, "BNPL_PAY_IN_N", "PAY_IN_" + months + "_MONTHS");
    }

    HttpResponse<String> submit(String token, String body, String key) throws Exception {
        return post(APPLICATIONS, body, token, key);
    }

    HttpResponse<String> read(String token, String id) throws Exception {
        return get(APPLICATIONS + "/" + id, token);
    }

    HttpResponse<String> withdraw(String token, String id, String key) throws Exception {
        return post(APPLICATIONS + "/" + id + "/withdrawal", null, token, key);
    }

    // ------------------------------------------------------------------ the world

    /** A trading merchant priced for cards, with an API key - checkout's own doors. */
    Merchant tradingMerchant(Authorization authorization) throws Exception {
        String operator = operatorSession(authorization, RoleName.MERCHANT_ADMINISTRATOR);
        String merchantId = onboard(operator);
        String schedule = field(post("/v1/operator/fee-schedules",
                "{\"name\":\"Std " + UUID.randomUUID() + "\",\"currency\":\"EUR\"}", operator, null).body(),
                "feeScheduleId");
        assertThat(post("/v1/operator/fee-schedules/" + schedule + "/versions",
                "{\"rate\":0.029,\"fixedAmountMinor\":30,\"roundingPolicy\":\"HALF_EVEN\","
                        + "\"refundFeePolicy\":\"RETAINED\",\"reason\":\"initial pricing\"}", operator, null)
                .statusCode()).isEqualTo(201);
        assertThat(put("/v1/operator/merchants/" + merchantId + "/fee-schedule",
                "{\"feeScheduleId\":\"" + schedule + "\",\"reason\":\"standard terms\"}", operator).statusCode())
                .isEqualTo(200);
        HttpResponse<String> issued = post("/v1/operator/merchants/" + merchantId + "/api-keys", null, operator,
                key());
        assertThat(issued.statusCode()).isEqualTo(201);
        return new Merchant(merchantId, field(issued.body(), "keyId") + "." + field(issued.body(), "secret"));
    }

    /**
     * The merchant BNPL-enabled by two persons and both products priced by a returning schedule of {@code rate} plus
     * {@code fixedMinor} - merchant's own domain, on the application role.
     */
    static void bnplEnabled(Merchant merchant, BnplEnablements enablements, BnplPricing pricing,
            FeeSchedules feeSchedules, String rate, long fixedMinor) throws Exception {
        MerchantId id = MerchantId.of(merchant.ref());
        Actor proposer = operator();
        Actor approver = operator();
        BnplEnablement proposed = act(proposer, uow -> enablements.propose(uow, id, "contract", proposer, correlation()));
        act(approver, uow -> enablements.approve(uow, id, proposed.id(), "reviewed", approver, correlation()));
        Actor pricer = operator();
        FeeSchedule schedule = act(pricer, uow -> {
            FeeSchedule created = feeSchedules.create(uow, "bnpl " + UUID.randomUUID(), EUR);
            feeSchedules.addVersion(uow, created.id(), new FeeSchedules.NewVersion(FeeRate.of(new BigDecimal(rate)),
                    Money.ofMinorUnits(fixedMinor, EUR), RoundingPolicy.HALF_EVEN, RefundFeePolicy.RETURNED,
                    Optional.empty(), "bnpl pricing"));
            return created;
        });
        for (MerchantBnplProduct product : MerchantBnplProduct.values()) {
            act(pricer, uow -> pricing.assign(uow, id, product, schedule.id(), "contract", pricer, correlation()));
        }
    }

    /** A checkout session of {@code amountMinor} EUR opened by the merchant's key. */
    Opened openSession(Merchant merchant, long amountMinor) throws Exception {
        HttpResponse<String> created = post("/v1/checkout/sessions",
                "{\"amountMinor\":" + amountMinor + ",\"currency\":\"EUR\",\"lineSummary\":\"A bicycle\"}",
                merchant.apiKey(), key());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return new Opened(UUID.fromString(field(created.body(), "checkoutId")), field(created.body(), "sessionToken"));
    }

    /** {@code terms} in force: proposed by one officer, activated by another. */
    static void termsInForce(BnplTermsAdministration administration, BnplTransactions transactions, BnplTerms terms) {
        BnplTermsAdministration.Proposed proposed = transactions.inTransaction(uow -> administration.propose(uow, terms,
                "the application suite's terms", operator(), correlation()));
        transactions.inTransaction(uow -> administration.approve(uow, proposed.id(), operator(), "in force",
                correlation()));
    }

    /** Whether {@code product} has an ACTIVE terms version. */
    static boolean termsActive(BnplProduct product) throws Exception {
        return count("SELECT count(*) FROM bnpl.bnpl_terms_version WHERE product = ? AND status = 'ACTIVE'",
                product.name()) == 1;
    }

    // ------------------------------------------------------------------ plumbing

    String operatorSession(Authorization authorization, RoleName role) throws Exception {
        String login = "ops." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                + "\"password\":\"" + PASSWORD + "\"}", null, key()).statusCode()).isEqualTo(201);
        UUID identity = UUID.fromString(scalar("SELECT id::text FROM identity.identity WHERE login_identifier = ?",
                login));
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(correlation()));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        return field(post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}", null, null).body(),
                "sessionToken");
    }

    private String onboard(String operator) throws Exception {
        UUID party = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES"
                    + " (?, 'ORGANISATION', 'Acme Holdings', now())", party);
            execute(app, "INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at) VALUES"
                    + " (?, ?, 'ACTIVE', now() - interval '1 hour', now())", IDS.next(), party);
        }
        HttpResponse<String> created = post("/v1/operator/merchants",
                "{\"partyId\":\"" + party + "\",\"legalName\":\"Acme GmbH\","
                        + "\"displayName\":\"Acme\",\"settlementCurrency\":\"EUR\"}", operator, key());
        assertThat(created.statusCode()).isEqualTo(201);
        return field(created.body(), "merchantId");
    }

    /** {@code work} as {@code actor}, in one committed transaction on the application role; a throw rolls it back. */
    static <R> R act(Actor actor, Function<Connection, R> work) throws Exception {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(correlation()));
                SecurityContext.Scope who = SecurityContext.enter(actor);
                Connection uow = DatabaseRoles.application()) {
            uow.setAutoCommit(false);
            try {
                R result = work.apply(uow);
                uow.commit();
                return result;
            } catch (RuntimeException refused) {
                uow.rollback();
                throw refused;
            }
        }
    }

    /** {@code work} as {@code actor}, inside a correlation scope - the domain's own transactions inside it. */
    static <R> R as(Actor actor, java.util.function.Supplier<R> work) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(correlation()));
                SecurityContext.Scope who = SecurityContext.enter(actor)) {
            return work.get();
        }
    }

    static Actor operator() {
        return new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
    }

    static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return send(request.build());
    }

    HttpResponse<String> post(String path, String body, String bearer, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return send(request.build());
    }

    /** {@code method} on {@code path} with an optional JSON body and bearer - the merchant doors' negatives (`P12-TSK-014`). */
    HttpResponse<String> call(String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return send(request.build());
    }

    private HttpResponse<String> put(String path, String body, String bearer) throws Exception {
        return send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + bearer)
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    static long count(String sql, Object... parameters) throws Exception {
        String value = scalar(sql, parameters);
        return value == null ? 0 : Long.parseLong(value);
    }

    static String scalar(String sql, Object... parameters) throws Exception {
        try (Connection owner = DatabaseRoles.migrator(); PreparedStatement select = owner.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        }
    }
}
