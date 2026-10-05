package com.finapp.app.fx;

import static com.finapp.app.fx.FxCoverFixtures.coverOf;
import static com.finapp.app.fx.FxTestClient.field;
import static com.finapp.app.fx.FxTestClient.fund;
import static com.finapp.app.fx.FxTestClient.money;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.fx.FxBooksProof;
import com.finapp.fx.FxCoverDispatch;
import com.finapp.fx.FxPlanVerification;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.TrialBalance;
import com.finapp.platform.security.Actor;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.reconciliation.RuleSets;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
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
 * {@code P9-TST-002} - the value-preservation and rounding battery (PHASE_9_PLAN.md section 13;
 * INV-ACC-01, INV-FX-01, INV-FX-03...07, INV-BAL-03): ten concurrent converters, through the real
 * doors, over all twenty directional pairs and both fixed sides, amounts from each fixed currency's
 * minimum to its maximum (0, 2 and 3 minor units); a sample of the covers executed, some of them
 * rejected and requoted at a moved rate. Then, in ONE {@code REPEATABLE READ} snapshot: the trial
 * balance is zero in all five currencies; the FX books proof holds in every book and currency; every
 * booked trade replays exactly from its stored inputs (the golden replay); and every residual of the
 * run is within one minor unit, BOTH signs present - the signature of a half rounding, which a
 * directed one would break. Every attempted conversion booked (counted).
 *
 * <p>The size is {@code FINAPP_FX_BATTERY_CONVERSIONS} (default 10,000), so a mutation probe may run
 * it small. Ordered with the FX suites, after every default suite.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Order(Integer.MAX_VALUE - 1)
@DisplayName("P9-TST-002: ten converters over every pair, side and scale - no value created or destroyed")
class FxValuePreservationBatteryDatabaseTest {

    private static final int CONVERTERS = 10;
    private static final int DEFAULT_CONVERSIONS = 10_000;
    private static final List<String> CURRENCIES = List.of("EUR", "GBP", "USD", "JPY", "BHD");
    private static SimulatedFxEngine engine;

    @LocalServerPort private int port;
    @Autowired private PricingPolicyAdministration administration;
    @Autowired private PricingPolicyStore policyStore;
    @Autowired private PostingService postings;
    @Autowired private RuleSetAdministration ruleSetAdministration;
    @Autowired private RuleSets ruleSets;
    @Autowired private DataSource dataSource;
    @Autowired private FxCoverDispatch dispatch;
    @Autowired private FxBooksProof books;
    @Autowired private FxPlanVerification plans;

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) throws Exception {
        if (engine == null) {
            engine = SimulatedFxEngine.start(new byte[32]);
            FxTestClient.rates(engine, Map.of());
        }
        registry.add("finapp.fx.provider.url", () -> engine.baseUrl().toString());
    }

    @AfterAll
    static void stopEngine() {
        engine.close();
    }

    @Test
    @DisplayName("every attempted conversion books; then in one snapshot the trial balance is zero in five currencies,"
            + " the FX books are explained, every plan replays, and every residual is within one unit with both signs")
    void noValueIsCreatedOrDestroyed() throws Exception {
        FxTestPolicy.ensure(administration, policyStore, FxTestPolicy.V1);
        FxTestClient.freshReferences();
        FxCoverFixtures.ensureFxSourceActive(ruleSetAdministration, ruleSets, dataSource);
        int total = conversions();
        List<ConversionOrder> orders = orders(total);

        FxTestClient client = new FxTestClient(port);
        List<FxTestClient.Customer> converters = new ArrayList<>();
        for (int i = 0; i < CONVERTERS; i++) {
            FxTestClient.Customer customer = client.verifiedCustomer();
            UUID product = client.openWallet(customer, "EUR");
            for (String currency : CURRENCIES) {
                if (!currency.equals("EUR")) {
                    client.addCurrency(customer, product, currency);
                }
                fund(postings, product, money(depth(currency), currency));
            }
            converters.add(customer);
        }

        // The references stay fresh for the run's whole length - a refresher appends, nothing else.
        ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor();
        refresher.scheduleWithFixedDelay(() -> {
            try {
                FxTestClient.freshReferences();
            } catch (Exception ignored) {
                // The next tick tries again; a stale reference would refuse quotes, failing the count.
            }
        }, 10, 10, TimeUnit.SECONDS);
        ConcurrentLinkedQueue<String> trades = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> refusals = new ConcurrentLinkedQueue<>();
        AtomicInteger next = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(CONVERTERS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> workers = new ArrayList<>();
            for (int i = 0; i < CONVERTERS; i++) {
                FxTestClient.Customer customer = converters.get(i);
                workers.add(pool.submit(() -> {
                    start.await();
                    for (int index = next.getAndIncrement(); index < orders.size(); index = next.getAndIncrement()) {
                        ConversionOrder order = orders.get(index);
                        HttpResponse<String> quoted = client.quote(customer, order.source(), order.destination(),
                                order.side(), order.amount());
                        if (quoted.statusCode() != 201) {
                            refusals.add(order + " quote " + quoted.statusCode() + " " + quoted.body());
                            continue;
                        }
                        HttpResponse<String> converted = client.convert(customer, field(quoted.body(), "id"),
                                FxTestClient.key());
                        if (converted.statusCode() != 201) {
                            refusals.add(order + " convert " + converted.statusCode() + " " + converted.body());
                            continue;
                        }
                        trades.add(field(converted.body(), "id"));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get(60, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
            refresher.shutdownNow();
        }
        assertThat(refusals).as("every attempted conversion books (counted)").isEmpty();
        assertThat(trades).hasSize(total);

        // A sample of the covers, sequentially (the simulator's faults are armed globally): one in five
        // rejected and requoted at a moved rate, so the realised books are exercised too.
        List<String> booked = new ArrayList<>(trades);
        Collections.sort(booked);
        int covered = 0;
        for (int i = 0; i < booked.size(); i += 20) {
            UUID cover = coverOf(booked.get(i));
            boolean slip = (i / 20) % 5 == 0;
            if (slip) {
                engine.changePriceOnNextExecution();
            }
            dispatch.dispatchNow(cover, Actor.SYSTEM);
            if (slip) {
                assertThat(FxCoverFixtures.status(cover)).isEqualTo("REJECTED");
                FxTestClient.freshReferences();
                shiftRates(engine, i % 40 == 0 ? "1.0004" : "0.9996"); // the market moved: a gain or a loss
                dispatch.dispatchNow(cover, Actor.SYSTEM);
                FxTestClient.rates(engine, Map.of());
            }
            assertThat(FxCoverFixtures.status(cover)).as("cover %s", cover).isEqualTo("EXECUTED");
            covered++;
        }
        assertThat(covered).isPositive();

        // ONE snapshot: every identity at the same instant.
        try (Connection snapshot = dataSource.getConnection()) {
            snapshot.setAutoCommit(false);
            snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            snapshot.setReadOnly(true);
            try {
                TrialBalance.Report trial = new TrialBalance().sweep(snapshot);
                assertThat(trial.outOfBalance()).as("the trial balance is zero in every currency").isEmpty();
                assertThat(trial.currenciesVerified()).isEqualTo(5);
                FxBooksProof.Report proof = books.prove(snapshot);
                assertThat(proof.clean()).as("every FX book explained: %s",
                        proof.lines().stream().filter(line -> !line.holds()).toList()).isTrue();
                FxPlanVerification.Report replay = plans.verify(snapshot);
                assertThat(replay.clean()).as("every plan replays: %s", replay.divergences()).isTrue();
                assertThat(replay.verified()).isGreaterThanOrEqualTo(total);
                Residuals residuals = residuals(snapshot, booked);
                assertThat(residuals.minimum()).as("no residual below -1").isGreaterThanOrEqualTo(-1);
                assertThat(residuals.maximum()).as("no residual above +1").isLessThanOrEqualTo(1);
                assertThat(residuals.positive()).as("both signs present: positive").isPositive();
                assertThat(residuals.negative()).as("both signs present: negative").isPositive();
            } finally {
                snapshot.rollback();
            }
        }
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new FxProofMetrics(books, plans, dataSource::getConnection, Clock.systemUTC(), registry);
        assertThat(registry.get(FxProofMetrics.PLAN_VERDICT).gauge().value()).isEqualTo(1.0);
    }

    // -----------------------------------------------------------------

    private record ConversionOrder(String source, String destination, String side, String amount) {}

    private record Residuals(long minimum, long maximum, long positive, long negative) {}

    /** The run's orders: every pair and side in turn, amounts across the fixed currency's bounds. */
    private static List<ConversionOrder> orders(int total) {
        List<String[]> pairs = new ArrayList<>();
        for (String source : CURRENCIES) {
            for (String destination : CURRENCIES) {
                if (!source.equals(destination)) {
                    pairs.add(new String[] {source, destination});
                }
            }
        }
        SplittableRandom random = new SplittableRandom(20261005L);
        List<ConversionOrder> orders = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            String[] pair = pairs.get(i % pairs.size());
            String side = (i / pairs.size()) % 2 == 0 ? "FIXED_SOURCE" : "FIXED_DESTINATION";
            String fixed = side.equals("FIXED_SOURCE") ? pair[0] : pair[1];
            int round = i / (pairs.size() * 2);
            String amount = round == 0 ? minimum(fixed) : round == 1 ? maximum(fixed) : between(fixed, random);
            orders.add(new ConversionOrder(pair[0], pair[1], side, amount));
        }
        return orders;
    }

    /** Log-uniform between the bounds, at the currency's scale - small and large amounts alike. */
    private static String between(String currency, SplittableRandom random) {
        double low = Math.log(new BigDecimal(minimum(currency)).doubleValue());
        double high = Math.log(new BigDecimal(maximum(currency)).doubleValue());
        BigDecimal amount = BigDecimal.valueOf(Math.exp(low + (high - low) * random.nextDouble()))
                .setScale(scale(currency), RoundingMode.DOWN);
        BigDecimal floor = new BigDecimal(minimum(currency));
        return (amount.compareTo(floor) < 0 ? floor : amount).toPlainString();
    }

    private static String minimum(String currency) {
        return switch (currency) {
            case "JPY" -> "100";
            case "BHD" -> "0.500";
            default -> "1.00";
        };
    }

    private static String maximum(String currency) {
        return switch (currency) {
            case "JPY" -> "7500000";
            case "BHD" -> "20000.000";
            default -> "50000.00";
        };
    }

    private static int scale(String currency) {
        return switch (currency) {
            case "JPY" -> 0;
            case "BHD" -> 3;
            default -> 2;
        };
    }

    /** Deep enough that no order of the run can exhaust a wallet. */
    private static String depth(String currency) {
        return switch (currency) {
            case "JPY" -> "100000000000";
            case "BHD" -> "500000000.000";
            default -> "1000000000.00";
        };
    }

    /** Every directional rate moved by {@code factor} - inside the band, so a requote is plausible. */
    private static void shiftRates(SimulatedFxEngine engine, String factor) {
        BigDecimal by = new BigDecimal(factor);
        for (Map.Entry<String, String> mid : FxTestClient.MIDS.entrySet()) {
            String[] pair = mid.getKey().split("/");
            BigDecimal forward = new BigDecimal(mid.getValue()).multiply(by).setScale(10, RoundingMode.HALF_EVEN);
            engine.rate(pair[0] + "/" + pair[1], forward.toPlainString());
            engine.rate(pair[1] + "/" + pair[0], BigDecimal.ONE.divide(forward, 10, RoundingMode.HALF_EVEN).toPlainString());
        }
    }

    private static int conversions() {
        String configured = System.getenv("FINAPP_FX_BATTERY_CONVERSIONS");
        return configured == null || configured.isBlank() ? DEFAULT_CONVERSIONS : Integer.parseInt(configured.trim());
    }

    private static Residuals residuals(Connection snapshot, List<String> trades) throws Exception {
        try (PreparedStatement read = snapshot.prepareStatement(
                "SELECT min(residual_minor), max(residual_minor), count(*) FILTER (WHERE residual_minor > 0),"
                        + " count(*) FILTER (WHERE residual_minor < 0) FROM fx.trade WHERE id = ANY (?::uuid[])")) {
            read.setArray(1, snapshot.createArrayOf("text", trades.toArray()));
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return new Residuals(row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4));
            }
        }
    }
}
