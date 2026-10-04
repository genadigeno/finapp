package com.finapp.app.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.fx.JdbcRateSnapshotStore;
import com.finapp.fx.RateObservation;
import com.finapp.fx.ReferencePair;
import com.finapp.fx.ReferenceSourceDeclaration;
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
import com.finapp.sharedkernel.money.ExchangeRate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
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
 * The customer's FX quote doors over HTTP against the simulated provider (`P9-TSK-008`;
 * PHASE_9_PLAN.md section 9; INV-FX-02, INV-FX-04): every one of the twenty pairs quotes both
 * sides under v1; the body is closed so no rate can be supplied; ten same-key requests make one
 * provider call; an owner at the cap makes none; another owner's quote is absent.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the customer's FX quote doors (P9-TSK-008)")
class FxQuoteEndpointDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    /** The reference mid per canonical pair; the simulator quotes these and their inverses. */
    private static final Map<String, String> MIDS = Map.of(
            "EUR/GBP", "0.8543", "EUR/USD", "1.0850", "EUR/JPY", "162.21", "EUR/BHD", "0.4080",
            "GBP/USD", "1.2700", "GBP/JPY", "189.87", "GBP/BHD", "0.4776", "USD/JPY", "149.50",
            "USD/BHD", "0.3760", "BHD/JPY", "397.5121");
    private static final Map<String, String> AMOUNTS = Map.of(
            "EUR", "100.00", "GBP", "100.00", "USD", "100.00", "JPY", "15000", "BHD", "40.000");

    private static SimulatedFxEngine engine;
    private static boolean v1Active;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) throws Exception {
        if (engine == null) {
            engine = SimulatedFxEngine.start(new byte[32]);
            for (Map.Entry<String, String> mid : MIDS.entrySet()) {
                String[] pair = mid.getKey().split("/");
                BigDecimal forward = new BigDecimal(mid.getValue()).setScale(10, RoundingMode.UNNECESSARY);
                engine.rate(pair[0] + "/" + pair[1], forward.toPlainString());
                engine.rate(pair[1] + "/" + pair[0],
                        BigDecimal.ONE.divide(forward, 10, RoundingMode.HALF_EVEN).toPlainString());
            }
        }
        registry.add("finapp.fx.provider.url", () -> engine.baseUrl().toString());
    }

    @AfterAll
    static void stopEngine() {
        engine.close();
    }

    @BeforeEach
    void v1AndFreshReferences() throws Exception {
        if (!v1Active) {
            activateV1();
            v1Active = true;
        }
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            for (Map.Entry<String, String> mid : MIDS.entrySet()) {
                String[] pair = mid.getKey().split("/");
                ReferencePair canonical = ReferencePair.of(pair[0], pair[1]);
                new JdbcRateSnapshotStore().record(app, ReferenceSourceDeclaration.SOURCE,
                        new RateObservation(ExchangeRate.of(canonical.base(), canonical.quote(),
                                new BigDecimal(mid.getValue())), Instant.now()),
                        UUID.randomUUID());
            }
            app.commit();
        }
    }

    @Test
    @DisplayName("every one of the twenty pairs quotes both sides: 201 with the customer rate, never the"
            + " provider's - forty quotes, forty provider calls")
    void everyPairQuotesBothSides() throws Exception {
        String customer = verifiedCustomer();
        int before = engine.quoteRequests();
        int quoted = 0;
        for (String pair : FxQuoteMetrics.pairs()) {
            String[] currencies = pair.split("-");
            for (String side : List.of("FIXED_SOURCE", "FIXED_DESTINATION")) {
                String fixed = side.equals("FIXED_SOURCE") ? currencies[0] : currencies[1];
                HttpResponse<String> created = post("/v1/me/fx/quotes",
                        quoteBody(currencies[0], currencies[1], side, AMOUNTS.get(fixed)), customer, someKey());
                assertThat(created.statusCode()).as(pair + " " + side + ": " + created.body()).isEqualTo(201);
                assertThat(field(created.body(), "status")).isEqualTo("ISSUED");
                assertThat(created.body()).contains("\"customerRate\"", "\"disclosedMarginOverMid\"", "\"expiresAt\"")
                        .doesNotContain("providerRate", "PQ-", "referenceRate");
                HttpResponse<String> cancelled = post("/v1/me/fx/quotes/" + field(created.body(), "id") + "/cancellation",
                        null, customer, someKey());
                assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
                assertThat(field(cancelled.body(), "status")).isEqualTo("CANCELLED");
                quoted++;
            }
        }
        assertThat(quoted).isEqualTo(40);
        assertThat(engine.quoteRequests() - before).isEqualTo(40);
    }

    @Test
    @DisplayName("a closed body: a rate - or any unknown field - is 422 and nothing is asked or stored")
    void aClientCannotSupplyARate() throws Exception {
        String customer = verifiedCustomer();
        int before = engine.quoteRequests();
        HttpResponse<String> refused = post("/v1/me/fx/quotes",
                "{\"sourceCurrency\":\"EUR\",\"destinationCurrency\":\"USD\",\"fixedSide\":\"FIXED_SOURCE\","
                        + "\"amount\":\"100.00\",\"rate\":\"2.0\"}",
                customer, someKey());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("api.ValidationFailed").doesNotContain("2.0");
        assertThat(engine.quoteRequests()).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM fx.quote_request WHERE owner_party_id = "
                + "(SELECT party_id FROM identity.identity WHERE login_identifier = ?)", loginOf(customer))).isZero();
    }

    @Test
    @DisplayName("ten requests under one key: one provider call, one quote - every answer the replay or"
            + " IdempotencyInProgress; the key with another body conflicts")
    void oneKeyOneProviderCall() throws Exception {
        String customer = verifiedCustomer();
        String key = someKey();
        String body = quoteBody("EUR", "USD", "FIXED_SOURCE", "250.00");
        int before = engine.quoteRequests();
        List<HttpResponse<String>> answers = race(10, () -> post("/v1/me/fx/quotes", body, customer, key));
        assertThat(engine.quoteRequests() - before).isEqualTo(1);
        List<String> ids = new ArrayList<>();
        for (HttpResponse<String> answer : answers) {
            if (answer.statusCode() == 201) {
                ids.add(field(answer.body(), "id"));
            } else {
                assertThat(answer.statusCode()).as(answer.body()).isEqualTo(409);
                assertThat(answer.body()).contains("IdempotencyInProgress");
            }
        }
        HttpResponse<String> replay = post("/v1/me/fx/quotes", body, customer, key);
        assertThat(replay.statusCode()).isEqualTo(201);
        ids.add(field(replay.body(), "id"));
        assertThat(ids).as("every 201 is the same quote").hasSizeGreaterThanOrEqualTo(1).allMatch(ids.get(0)::equals);
        assertThat(engine.quoteRequests() - before).isEqualTo(1);
        HttpResponse<String> conflict = post("/v1/me/fx/quotes", quoteBody("EUR", "USD", "FIXED_SOURCE", "251.00"), customer, key);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("api.Conflict");
    }

    @Test
    @DisplayName("an owner at the cap of five is refused 429 before any provider call")
    void theCapSparesTheProvider() throws Exception {
        String customer = verifiedCustomer();
        for (int i = 0; i < 5; i++) {
            assertThat(post("/v1/me/fx/quotes", quoteBody("EUR", "USD", "FIXED_SOURCE", "100.00"), customer, someKey())
                            .statusCode())
                    .isEqualTo(201);
        }
        int before = engine.quoteRequests();
        HttpResponse<String> refused = post("/v1/me/fx/quotes", quoteBody("EUR", "USD", "FIXED_SOURCE", "100.00"),
                customer, someKey());
        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(refused.body()).contains("fx.TooManyOpenQuotes");
        assertThat(engine.quoteRequests()).isEqualTo(before);
    }

    @Test
    @DisplayName("another owner's quote is absent - read and cancel alike - and so is a malformed id; an"
            + " ineligible customer is refused; no session is 401")
    void theQuoteIsItsOwners() throws Exception {
        String owner = verifiedCustomer();
        String stranger = verifiedCustomer();
        HttpResponse<String> created = post("/v1/me/fx/quotes", quoteBody("GBP", "JPY", "FIXED_DESTINATION", "15000"),
                owner, someKey());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String id = field(created.body(), "id");
        assertThat(get("/v1/me/fx/quotes/" + id, owner).statusCode()).isEqualTo(200);
        assertThat(get("/v1/me/fx/quotes/" + id, stranger).statusCode()).isEqualTo(404);
        assertThat(post("/v1/me/fx/quotes/" + id + "/cancellation", null, stranger, someKey()).statusCode()).isEqualTo(404);
        assertThat(get("/v1/me/fx/quotes/not-a-quote", owner).body()).contains("fx.QuoteNotFound");
        HttpResponse<String> ineligible = post("/v1/me/fx/quotes", quoteBody("EUR", "USD", "FIXED_SOURCE", "100.00"),
                unverifiedCustomer(), someKey());
        assertThat(ineligible.statusCode()).isEqualTo(422);
        assertThat(ineligible.body()).contains("fx.CustomerNotEligible");
        assertThat(post("/v1/me/fx/quotes", quoteBody("EUR", "USD", "FIXED_SOURCE", "100.00"), null, someKey()).statusCode())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("the pairs door lists the active policy's twenty pairs with their bounds")
    void thePairs() throws Exception {
        HttpResponse<String> pairs = get("/v1/me/fx/pairs", verifiedCustomer());
        assertThat(pairs.statusCode()).isEqualTo(200);
        assertThat(Pattern.compile("\"sourceCurrency\"").matcher(pairs.body()).results().count()).isEqualTo(20);
        assertThat(pairs.body()).contains("\"marketBase\":\"EUR\"", "\"sourceMinimum\":\"0.500\"", "\"sourceMaximum\":\"7500000\"");
        assertThat(get("/v1/me/fx/pairs", null).statusCode()).isEqualTo(401);
    }

    // -----------------------------------------------------------------

    private void activateV1() throws Exception {
        String first = sessionWith(RoleName.FX_CONTROLLER);
        String second = sessionWith(RoleName.FX_CONTROLLER);
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT id FROM fx.pricing_policy_version WHERE status = 'PROPOSED'");
                ResultSet row = select.executeQuery()) {
            if (row.next()) {
                post("/v1/operator/fx/pricing-policies/" + row.getObject(1, UUID.class) + "/rejection",
                        "{\"reason\":\"cleared by the quote suite\"}", first, null);
            }
        }
        HttpResponse<String> proposed = post("/v1/operator/fx/pricing-policies",
                PricingPolicyV1.json("pricing policy v1 for the quote suite"), first, someKey());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        HttpResponse<String> approved = post("/v1/operator/fx/pricing-policies/" + field(proposed.body(), "id")
                + "/approval", "{\"reason\":\"checked against O7\"}", second, null);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
    }

    private static String quoteBody(String source, String destination, String side, String amount) {
        return "{\"sourceCurrency\":\"" + source + "\",\"destinationCurrency\":\"" + destination + "\",\"fixedSide\":\""
                + side + "\",\"amount\":\"" + amount + "\"}";
    }

    private final Map<String, String> logins = new java.util.concurrent.ConcurrentHashMap<>();

    private String loginOf(String token) {
        return logins.get(token);
    }

    private String verifiedCustomer() throws Exception {
        String login = registered();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement update = app.prepareStatement(
                        "UPDATE party.customer SET status = 'ACTIVE', status_changed_at = GREATEST(now(), opened_at)"
                                + " WHERE party_id = (SELECT party_id FROM identity.identity WHERE login_identifier = ?)")) {
            update.setString(1, login);
            update.executeUpdate();
        }
        String token = field(authenticate(login).body(), "sessionToken");
        logins.put(token, login);
        return token;
    }

    private String unverifiedCustomer() throws Exception {
        return field(authenticate(registered()).body(), "sessionToken");
    }

    private String registered() throws Exception {
        String login = "fxq." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations",
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\",\"password\":\""
                                + PASSWORD + "\"}",
                        null, someKey()).statusCode())
                .isEqualTo(201);
        return login;
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}", null, someKey());
    }

    private String sessionWith(RoleName role) throws Exception {
        String login = registered();
        UUID identity;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement("SELECT id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                identity = row.getObject("id", UUID.class);
            }
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        return field(authenticate(login).body(), "sessionToken");
    }

    private static long count(String sql, String parameter) throws Exception {
        try (Connection app = DatabaseRoles.application(); PreparedStatement select = app.prepareStatement(sql)) {
            select.setString(1, parameter);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return send(request.build());
    }

    private HttpResponse<String> post(String path, String body, String bearer, String key) throws Exception {
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

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static <T> List<T> race(int racers, java.util.concurrent.Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> pending = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                pending.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<T> outcomes = new ArrayList<>();
            for (Future<T> outcome : pending) {
                outcomes.add(outcome.get(2, TimeUnit.MINUTES));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }
}
