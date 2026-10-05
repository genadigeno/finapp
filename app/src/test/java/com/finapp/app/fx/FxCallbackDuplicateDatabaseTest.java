package com.finapp.app.fx;

import static com.finapp.app.fx.FxCoverFixtures.convertedTrade;
import static com.finapp.app.fx.FxCoverFixtures.coverOf;
import static com.finapp.app.fx.FxCoverFixtures.reference;
import static com.finapp.app.fx.FxCoverFixtures.status;
import static com.finapp.app.fx.FxTestClient.count;
import static com.finapp.app.fx.FxTestClient.fund;
import static com.finapp.app.fx.FxTestClient.money;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.fx.FxCoverDispatch;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.ledger.PostingService;
import com.finapp.payments.WebhookSignature;
import com.finapp.platform.security.Actor;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.reconciliation.RuleSets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Scenario 6 for the FX provider - a callback arrives twice, and worse (`P9-TSK-012`; ADR-0077
 * section 9, PHASE_9_PLAN.md section 13): ten signed deliveries of one callback plus a racing sweep
 * make ONE effect, every delivery retained; an unsigned or stale delivery is refused and moves
 * nothing; a signed-but-FORGED callback - a stolen key - moves nothing without the inquiry's
 * confirmation; an authentic but unmappable one is retained and acknowledged. Over HTTP, through the
 * real door at {@code POST /v1/providers/fx/webhooks}.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Order(Integer.MAX_VALUE - 1)
@DisplayName("an FX provider callback is a hint: ten deliveries one effect, a forged one none (P9-TSK-012)")
class FxCallbackDuplicateDatabaseTest {

    private static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < KEY.length; i++) {
            KEY[i] = (byte) (31 * i + 7);
        }
    }

    private static SimulatedFxEngine engine;

    @LocalServerPort private int port;
    @Autowired private PricingPolicyAdministration administration;
    @Autowired private PricingPolicyStore policyStore;
    @Autowired private PostingService postings;
    @Autowired private RuleSetAdministration ruleSetAdministration;
    @Autowired private RuleSets ruleSets;
    @Autowired private DataSource dataSource;
    @Autowired private FxCoverDispatch dispatch;

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) throws Exception {
        if (engine == null) {
            engine = SimulatedFxEngine.start(KEY);
            FxTestClient.rates(engine, Map.of("EUR/USD", "1.0850240000"));
        }
        registry.add("finapp.fx.provider.url", () -> engine.baseUrl().toString());
        registry.add("finapp.fx.webhook.key", () -> Base64.getEncoder().encodeToString(KEY));
    }

    @AfterAll
    static void stopEngine() {
        engine.close();
    }

    @BeforeEach
    void v1AndFreshReferences() throws Exception {
        FxTestPolicy.ensure(administration, policyStore, FxTestPolicy.V1);
        FxTestClient.freshReferences();
        FxCoverFixtures.ensureFxSourceActive(ruleSetAdministration, ruleSets, dataSource);
    }

    @Test
    @DisplayName("ten signed deliveries of one callback and a racing sweep: one execution fact, one entry, one inbox"
            + " record - every delivery's bytes retained")
    void tenDeliveriesOneEffect() throws Exception {
        UUID cover = coverOf(booked());
        String t1 = reference(cover, 1);
        engine.loseNextExecutionResponse();
        dispatch.dispatchNow(cover, Actor.SYSTEM);
        assertThat(status(cover)).isEqualTo("UNKNOWN");
        String body = engine.callbacks().getLast().body();
        assertThat(body).contains(t1);
        long evidenceBefore = callbackEvidence(t1);

        ExecutorService pool = Executors.newFixedThreadPool(11);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> deliveries = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                deliveries.add(pool.submit(() -> {
                    start.await();
                    return deliver(body, Instant.now(), true).statusCode();
                }));
            }
            Future<?> sweep = pool.submit(() -> {
                start.await();
                dispatch.dispatchNow(cover, Actor.SYSTEM);
                return null;
            });
            start.countDown();
            for (Future<Integer> delivery : deliveries) {
                assertThat(delivery.get(60, TimeUnit.SECONDS)).isIn(204, 409);
            }
            sweep.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(status(cover)).isEqualTo("EXECUTED");
        assertThat(engine.executionsOf(t1)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM fx.cover_execution WHERE cover_id = ?", cover)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?", "fx-cover:" + cover)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.inbox_message WHERE consumer = 'fx.provider-webhook'"
                + " AND dedupe_key LIKE ?", "fx-sim-a:fxcb-%")).isPositive();
        assertThat(callbackEvidence(t1) - evidenceBefore).as("every delivery retained").isEqualTo(10);
    }

    @Test
    @DisplayName("unsigned or stale deliveries are one uniform 401 and move nothing")
    void unsignedOrStaleMovesNothing() throws Exception {
        UUID cover = coverOf(booked());
        String t1 = reference(cover, 1);
        String forged = executedClaim("unsigned-" + UUID.randomUUID(), t1);
        assertThat(deliver(forged, Instant.now(), false).statusCode()).isEqualTo(401);
        assertThat(deliver(forged, Instant.now().minus(Duration.ofMinutes(10)), true).statusCode()).isEqualTo(401);
        assertThat(status(cover)).isEqualTo("DISPATCHED");
        assertThat(callbackEvidence(t1)).isZero();
    }

    @Test
    @DisplayName("a signed but FORGED callback claiming an execution that never happened: retained, acknowledged,"
            + " and the inquiry it triggers finds nothing - the cover stays DISPATCHED, nothing booked")
    void aForgedSignedCallbackMovesNothing() throws Exception {
        UUID cover = coverOf(booked());
        String t1 = reference(cover, 1);

        assertThat(deliver(executedClaim("forged-" + UUID.randomUUID(), t1), Instant.now(), true).statusCode())
                .isEqualTo(204);

        assertThat(status(cover)).isEqualTo("DISPATCHED");
        assertThat(engine.executionsOf(t1)).isZero();
        assertThat(count("SELECT count(*) FROM fx.cover_execution WHERE cover_id = ?", cover)).isZero();
        assertThat(callbackEvidence(t1)).as("retained as evidence").isEqualTo(1);
    }

    @Test
    @DisplayName("an authentic callback with no usable event id or reference is retained and acknowledged")
    void anUnmappableCallbackIsRetained() throws Exception {
        long before = callbackEvidence(FxCallbackService.UNATTRIBUTED);
        assertThat(deliver("{\"status\":\"executed\"}", Instant.now(), true).statusCode()).isEqualTo(204);
        assertThat(callbackEvidence(FxCallbackService.UNATTRIBUTED) - before).isEqualTo(1);
    }

    // -----------------------------------------------------------------

    private String booked() throws Exception {
        FxTestClient client = new FxTestClient(port);
        FxTestClient.Customer customer = client.verifiedCustomer();
        UUID product = client.openWallet(customer, "EUR");
        fund(postings, product, money("3000.00", "EUR"));
        return convertedTrade(client, customer, "EUR", "USD", "FIXED_SOURCE", "1000.00");
    }

    private static String executedClaim(String eventId, String reference) {
        return "{\"eventId\":\"" + eventId + "\",\"clientRef\":\"" + reference + "\",\"status\":\"executed\","
                + "\"tradeRef\":\"FT-999999\",\"sold\":\"1000.00\",\"soldCurrency\":\"EUR\",\"bought\":\"1085.02\","
                + "\"boughtCurrency\":\"USD\",\"rate\":\"1.0850240000\",\"valueDate\":\"2026-10-07\"}";
    }

    private HttpResponse<String> deliver(String body, Instant at, boolean signed) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(at.getEpochSecond());
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/v1/providers/fx/webhooks"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        if (signed) {
            request.header(WebhookSignature.TIMESTAMP_HEADER, timestamp)
                    .header(WebhookSignature.SIGNATURE_HEADER,
                            new WebhookSignature(KEY, Duration.ofMinutes(5), Clock.systemUTC()).sign(timestamp, bytes));
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static long callbackEvidence(String reference) throws Exception {
        return count("SELECT count(*) FROM fx.fx_provider_evidence WHERE provider_code = 'fx-sim-a'"
                + " AND client_reference = ? AND kind = 'CALLBACK'", reference);
    }
}
