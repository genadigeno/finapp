package com.finapp.app.fx;

import static com.finapp.app.fx.FxTestClient.count;
import static com.finapp.app.fx.FxTestClient.field;
import static com.finapp.app.fx.FxTestClient.fund;
import static com.finapp.app.fx.FxTestClient.money;
import static com.finapp.app.fx.FxTestClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.crossborder.CorridorPolicyV1;
import com.finapp.app.database.ServerSkewedClock;
import com.finapp.app.payments.OutboundReturnWorker;
import com.finapp.app.payments.SimulatedCorridorEngine;
import com.finapp.app.reconciliation.ClearingLineCopies;
import com.finapp.app.reconciliation.CorridorRuleSetV1;
import com.finapp.app.reconciliation.PositionProof;
import com.finapp.fx.FxBooksProof;
import com.finapp.fx.FxCoverDispatch;
import com.finapp.fx.FxPlanVerification;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.kyc.CounterpartyScreeningProvider;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Verdict;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.TrialBalance;
import com.finapp.payments.EndToEndReference;
import com.finapp.payments.OutboundCreditResolution;
import com.finapp.payments.WebhookSignature;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.Matching;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.reconciliation.RuleSets;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.SettlementSources;
import com.finapp.settlement.TransactionRunner;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
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
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * THE FX AND CROSS-BORDER STORM (`P9-TST-001`, PHASE_9_PLAN.md section 13's last paragraph; every {@code Phase: 9}
 * invariant; the ten-instance answer's capstone): financial correctness under every provider fault, crash,
 * duplication and race, at once.
 *
 * <h2>Two application instances whose clocks disagree by ten seconds</h2>
 *
 * <p>Instance A is this suite's own context, its {@code Clock} five seconds AHEAD of the database server; instance B
 * is a second {@link com.finapp.app.FinappApplication} over the same database, started in this JVM, its clock five
 * seconds BEHIND (both measured against the server - {@link ServerSkewedClock}). Both talk to one FX engine and one
 * corridor engine, so the providers' own counts span both instances. Every window that matters is the database's
 * (quotes, references, screenings, sweep bounds, {@code NEVER_RECEIVED}, every send permit since `X-TSK-013`), so
 * the skew may change nothing the books can see.
 *
 * <h2>The storm</h2>
 *
 * <ol>
 *   <li><strong>Load</strong>: five movers per instance - conversions across EUR, USD, GBP and JPY and cross-border
 *       payments to US/USD and JP/JPY beneficiaries, each over its own instance's HTTP door - a resolver per
 *       instance (cover sweeps, outbound sweeps, the return worker, quote expiry) and one network actor accepting,
 *       rejecting after receipt, returning and recalling in-flight credits, arming random faults in both engines
 *       and delivering duplicate and forged callbacks to both doors. Every round, in ONE REPEATABLE READ snapshot:
 *       the per-currency trial balance, the FX books proof, reconciliation's position proof and the holds check.
 *   <li><strong>The catalogue</strong>: each fault seeded deterministically, one at a time (both engines arm a fault
 *       in one global slot, so under load a fault lands on whatever request comes next - random placement; here
 *       every one surely happens), each followed by the snapshot.
 *   <li><strong>Drain and settle</strong>: everything to rest, then every executed leg settled from the database's
 *       own truth - the FX trade reports, the corridor reports with their fees and returns, the bank statements -
 *       with one leg off by a minor unit and one corridor file in a currency the counterparty does not settle.
 *   <li><strong>At rest</strong>: exact censuses against the providers' own counts, {@code FX_POSITION} zero, plan
 *       verification clean, every reconciliation run replayed IDENTICAL, the gauges at zero, the hot-row p99
 *       recorded and the needle walked end to end.
 * </ol>
 */
@Tag("database")
@Tag("own-container")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FxCrossBorderStormDatabaseTest.InstanceA.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("the FX and cross-border storm (P9-TST-001)")
class FxCrossBorderStormDatabaseTest {

    private static final Duration SKEW = Duration.ofSeconds(5);
    private static final int MOVERS_PER_INSTANCE = 5;
    private static final int MIN_ROUNDS = 15;
    private static final int MIN_COMMANDS = 800;
    private static final long MIN_LOAD_NANOS = TimeUnit.SECONDS.toNanos(90);
    private static final long LOAD_BOUND_NANOS = TimeUnit.MINUTES.toNanos(6);
    private static final List<String> CURRENCIES = List.of("EUR", "USD", "GBP", "JPY");

    private static final String PAYMENTS = "/v1/me/cross-border/payments";
    private static final String QUOTES = "/v1/me/cross-border/quotes";
    private static final String BENEFICIARIES = "/v1/me/cross-border/beneficiaries";
    private static final String POLICIES = "/v1/operator/cross-border/corridor-policies";
    private static final String DESK = "/v1/operator/reconciliation";
    private static final String FX_WEBHOOKS = "/v1/providers/fx/webhooks";
    private static final String CORRIDOR_WEBHOOKS = "/v1/providers/payments/corridor/webhooks";
    private static final String CORRIDOR_SOURCE = "corridor-sim-a.settlement";
    private static final String FX_SOURCE = "fx-sim-a.trade-report";
    private static final String BANK_SOURCE = "simulated-bank.statement";

    private static final byte[] FX_KEY = "an-fx-storm-callback-signing-key-of-32b".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CORRIDOR_KEY = "a-corridor-storm-webhook-key-of-32-bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FORGER_KEY = "a-forger-holds-another-key-of-32-bytes!!".getBytes(StandardCharsets.UTF_8);
    /** The beneficiary's name: absent from every column but kyc's ciphertext, every log, event and response. */
    private static final String NEEDLE = "Quorvexa Stormneedle";
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    private static final SimulatedCorridorEngine CORRIDOR = corridor();
    private static SimulatedFxEngine fxEngine;
    /** Every property this suite's context runs with - instance B is started with the same, as arguments. */
    private static final Map<String, String> PROPERTIES = new LinkedHashMap<>();
    private static ConfigurableApplicationContext instanceB;

    @LocalServerPort private int port;
    @Autowired private PricingPolicyAdministration administration;
    @Autowired private PricingPolicyStore policyStore;
    @Autowired private Authorization authorization;
    @Autowired private PostingService postings;
    @Autowired private FxCoverDispatch dispatch;
    @Autowired private com.finapp.fx.QuoteLifecycle quoteLifecycle;
    @Autowired private com.finapp.fx.TransactionRunner fxTransactionRunner;
    @Autowired private Clock clock;
    @Autowired private OutboundCreditResolution resolution;
    @Autowired private OutboundReturnWorker worker;
    @Autowired private RuleSetAdministration ruleSetAdministration;
    @Autowired private RuleSets ruleSets;
    @Autowired private DataSource dataSource;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private Matching matching;
    @Autowired private TransactionRunner settlementTransactionRunner;
    @Autowired private PositionProof positionProof;
    @Autowired private SettlementSources settlementSources;
    @Autowired private FxBooksProof booksProof;
    @Autowired private FxPlanVerification planVerification;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meters;

    private final TrialBalance trialBalance = new TrialBalance();
    private final AtomicInteger rounds = new AtomicInteger();
    /** Conversions the storm saw booked (201), by trade id - the census's own count. */
    private final List<String> conversions = new CopyOnWriteArrayList<>();
    /** Cross-border payments the storm saw accepted (202), and whose customer owns each. */
    private final Map<UUID, Mover> payments = new ConcurrentHashMap<>();
    /** Every conversion's door latency, nanoseconds - the hot rows' p99 (section 14 scenario 51). */
    private final List<Long> conversionLatencies = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();

    private static SimulatedCorridorEngine corridor() {
        try {
            return SimulatedCorridorEngine.start(CORRIDOR_KEY);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) throws Exception {
        if (fxEngine == null) {
            fxEngine = SimulatedFxEngine.start(FX_KEY);
            FxTestClient.rates(fxEngine, Map.of());
        }
        PROPERTIES.put("finapp.fx.provider.url", fxEngine.baseUrl().toString());
        PROPERTIES.put("finapp.corridor.provider.url", CORRIDOR.baseUrl().toString());
        PROPERTIES.put("finapp.fx.webhook.key", Base64.getEncoder().encodeToString(FX_KEY));
        PROPERTIES.put("finapp.corridor.webhook.key", Base64.getEncoder().encodeToString(CORRIDOR_KEY));
        PROPERTIES.put("finapp.fx.cover.resend-after", "PT1S");
        PROPERTIES.put("finapp.fx.cover.requote-base", "PT1S");
        PROPERTIES.put("finapp.payments.outbound.sweeper.received-age", "PT0.001S");
        PROPERTIES.put("finapp.payments.outbound.sweeper.unknown-age", "PT0.001S");
        PROPERTIES.put("finapp.payments.outbound.sweeper.dispatched-age", "PT1S");
        PROPERTIES.put("finapp.payments.outbound.sweeper.delivery-age", "PT0.001S");
        // The storm leaves thousands to drain: each sweep takes a wide page (the shared-database citizenship lesson).
        PROPERTIES.put("finapp.payments.outbound.sweeper.batch", "500");
        PROPERTIES.put("finapp.fx.cover.batch", "1000");
        PROPERTIES.forEach((name, value) -> registry.add(name, () -> value));
    }

    @AfterAll
    static void stop() {
        if (instanceB != null) {
            instanceB.close();
        }
        fxEngine.close();
        CORRIDOR.close();
    }

    // ================================================================== the storm

    @Test
    @DisplayName("two instances skewed +/-5 s, ten movers, every fault seeded and every crash survived: the books prove"
            + " every round, every leg settles to cash, and at rest the providers' counts are exactly ours")
    void theStormLeavesExactBooks(CapturedOutput output) throws Exception {
        prepareTheWorld();
        startInstanceB();
        Instance a = new Instance("A", port, dispatch, expirer(quoteLifecycle, fxTransactionRunner, clock), resolution, worker);
        Instance b = new Instance("B", Integer.parseInt(instanceB.getEnvironment().getProperty("local.server.port")),
                instanceB.getBean(FxCoverDispatch.class),
                expirer(instanceB.getBean(com.finapp.fx.QuoteLifecycle.class),
                        instanceB.getBean("fxTransactionRunner", com.finapp.fx.TransactionRunner.class),
                        instanceB.getBean(Clock.class)),
                instanceB.getBean(OutboundCreditResolution.class), instanceB.getBean(OutboundReturnWorker.class));
        List<Mover> movers = new ArrayList<>();
        for (Instance instance : List.of(a, b)) {
            for (int i = 0; i < MOVERS_PER_INSTANCE; i++) {
                movers.add(mover(instance));
            }
        }
        reconcile("before the storm");

        // 1. LOAD - both instances at once; the books proven every round while it blows.
        load(a, b, movers);

        // 2. THE SAME OBJECT FROM BOTH INSTANCES, then THE CATALOGUE - every fault, one at a time, each followed by
        //    the snapshot.
        twoInstanceRaces(a, b, movers.get(2));
        catalogue(a, b, movers);

        // 3. DRAIN AND SETTLE.
        drain(a, b);
        reconcile("drained");
        settle();
        reconcile("settled to cash");
        assertEveryCashVerdictExplained();

        // 4. AT REST.
        needleWalk(a, movers.get(0));
        reconcile("after the needle's walk");
        drain(a, b);
        reconcile("at rest");
        assertTheCensusesAreExact();
        assertTheBooksAtRest();
        replayEveryRun();
        assertTheNeedleReachedNoSink(output);
        recordTheHotRows();
    }

    // ================================================================== 1. load

    private void load(Instance a, Instance b, List<Mover> movers) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(movers.size() + 4);
        List<Future<?>> running = new ArrayList<>();
        try {
            for (Mover mover : movers) {
                running.add(pool.submit(() -> moverLoop(mover, stop)));
            }
            running.add(pool.submit(() -> resolverLoop(a, stop)));
            running.add(pool.submit(() -> resolverLoop(b, stop)));
            running.add(pool.submit(() -> networkLoop(List.of(a, b), stop)));
            running.add(pool.submit(() -> referenceLoop(stop)));
            long started = System.nanoTime();
            long deadline = started + LOAD_BOUND_NANOS;
            while (rounds.get() < MIN_ROUNDS || committed() < MIN_COMMANDS || System.nanoTime() - started < MIN_LOAD_NANOS) {
                assertThat(System.nanoTime()).as("the storm reached %s rounds and %s commands (%s)", rounds.get(),
                        committed(), outcomes).isLessThan(deadline);
                for (Future<?> task : running) {
                    if (task.isDone()) {
                        task.get(); // a mover that died fails the storm with its own failure
                    }
                }
                reconcile("round " + rounds.get());
            }
        } finally {
            stop.set(true);
            pool.shutdown();
            assertThat(pool.awaitTermination(3, TimeUnit.MINUTES)).as("every mover stops").isTrue();
        }
        for (Future<?> task : running) {
            task.get();
        }
        System.out.printf("P9-TST-001 LOAD: %d rounds, %d conversions, %d payments; outcomes %s%n", rounds.get(),
                conversions.size(), payments.size(), new TreeMap<>(outcomes));
        assertThat(conversions).as("conversions booked under load").hasSizeGreaterThan(20);
        assertThat(payments).as("cross-border payments accepted under load").hasSizeGreaterThan(20);
        reconcile("after the load");
    }

    private int committed() {
        return conversions.size() + payments.size();
    }

    private void moverLoop(Mover mover, AtomicBoolean stop) {
        try {
            while (!stop.get()) {
                if (RANDOMNESS.nextInt(100) < 55) {
                    convertOnce(mover);
                } else {
                    payOnce(mover);
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("mover on instance " + mover.instance().name() + " failed", failure);
        }
    }

    private void convertOnce(Mover mover) throws Exception {
        String source = CURRENCIES.get(RANDOMNESS.nextInt(CURRENCIES.size()));
        String destination;
        do {
            destination = CURRENCIES.get(RANDOMNESS.nextInt(CURRENCIES.size()));
        } while (destination.equals(source));
        boolean fixedSource = RANDOMNESS.nextBoolean();
        String amount = smallAmount(fixedSource ? source : destination);
        HttpResponse<String> quoted = mover.client().quote(mover.customer(), source, destination,
                fixedSource ? "FIXED_SOURCE" : "FIXED_DESTINATION", amount);
        tally("quote " + quoted.statusCode());
        if (quoted.statusCode() != 201) {
            assertThat(quoted.statusCode()).as(quoted.body()).isIn(409, 422, 429, 503);
            return;
        }
        long started = System.nanoTime();
        HttpResponse<String> converted = mover.client().convert(mover.customer(), field(quoted.body(), "id"),
                FxTestClient.key());
        conversionLatencies.add(System.nanoTime() - started);
        tally("convert " + converted.statusCode());
        if (converted.statusCode() == 201) {
            conversions.add(field(converted.body(), "id"));
        } else {
            assertThat(converted.statusCode()).as(converted.body()).isIn(409, 422, 503);
        }
    }

    private void payOnce(Mover mover) throws Exception {
        boolean toJapan = RANDOMNESS.nextBoolean();
        String source = toJapan ? "EUR" : (RANDOMNESS.nextBoolean() ? "EUR" : "GBP");
        HttpResponse<String> offered = mover.client().post(QUOTES, "{\"beneficiaryId\":\""
                + (toJapan ? mover.jpBeneficiary() : mover.usBeneficiary()) + "\",\"sourceCurrency\":\"" + source
                + "\",\"fixedSide\":\"FIXED_SOURCE\",\"amount\":\"" + (20 + RANDOMNESS.nextInt(60)) + ".00\"}",
                mover.customer().token(), FxTestClient.key());
        tally("offer " + offered.statusCode());
        if (offered.statusCode() != 201) {
            assertThat(offered.statusCode()).as(offered.body()).isIn(409, 422, 429, 503);
            return;
        }
        HttpResponse<String> paid = mover.client().post(PAYMENTS, "{\"quoteId\":\"" + field(offered.body(), "id") + "\"}",
                mover.customer().token(), FxTestClient.key());
        tally("pay " + paid.statusCode());
        if (paid.statusCode() == 202) {
            payments.put(UUID.fromString(field(paid.body(), "paymentId")), mover);
        } else {
            assertThat(paid.statusCode()).as(paid.body()).isIn(409, 422, 500, 503);
        }
    }

    private void resolverLoop(Instance instance, AtomicBoolean stop) {
        try {
            while (!stop.get()) {
                resolveOnce(instance);
                Thread.sleep(120);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void resolveOnce(Instance instance) {
        instance.dispatch().sweepOnce(Actor.SYSTEM);
        instance.resolution().sweep();
        instance.worker().sweep();
        instance.quoteExpiry().run();
    }

    /** What the quote-expiry schedule does each tick (its bean exists only while its sweeper is enabled). */
    private static Runnable expirer(com.finapp.fx.QuoteLifecycle lifecycle, com.finapp.fx.TransactionRunner runner,
            Clock instanceClock) {
        return () -> {
            try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
                while (runner.inTransaction(unitOfWork -> lifecycle.expirePage(unitOfWork, 50, Instant.now(instanceClock),
                        SecurityContext.require())).size() == 50) {
                    // the next page
                }
            }
        };
    }

    /** The provider network: what the far side does, and the faults and duplicates it throws. */
    private void networkLoop(List<Instance> instances, AtomicBoolean stop) {
        try {
            int tick = 0;
            while (!stop.get()) {
                tick++;
                for (String[] credit : rows("SELECT end_to_end_reference, status, subject_id::text"
                        + " FROM payments.outbound_credit WHERE status IN ('RECEIVED', 'COMPLETED') AND delivered_at IS NULL"
                        + " ORDER BY random() LIMIT 6")) {
                    int dice = RANDOMNESS.nextInt(100);
                    if (credit[1].equals("RECEIVED")) {
                        if (dice < 60) {
                            CORRIDOR.accept(credit[0]);
                        } else if (dice < 68) {
                            CORRIDOR.rejectReceived(credit[0], "beneficiary_closed");
                        } else if (dice < 78) {
                            recall(UUID.fromString(credit[2]));
                        }
                    } else if (dice < 40) {
                        CORRIDOR.deliver(credit[0]);
                    } else if (dice < 46) {
                        CORRIDOR.returnCredit(credit[0]);
                    }
                }
                if (tick % 8 == 0) {
                    armARandomFault();
                }
                if (tick % 5 == 0) {
                    duplicateAndForgeCallbacks(instances.get(RANDOMNESS.nextInt(instances.size())));
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            throw new IllegalStateException("the network actor failed", failure);
        }
    }

    private void recall(UUID payment) throws Exception {
        Mover owner = payments.get(payment);
        if (owner == null) {
            return; // a choreographed payment, recalled on its own terms
        }
        HttpResponse<String> requested = owner.client().post(PAYMENTS + "/" + payment + "/cancellation", null,
                owner.customer().token(), FxTestClient.key());
        tally("recall " + requested.statusCode());
        assertThat(requested.statusCode()).as(requested.body()).isIn(202, 409);
    }

    private static void armARandomFault() {
        switch (RANDOMNESS.nextInt(7)) {
            case 0 -> fxEngine.loseNextExecutionResponse();
            case 1 -> fxEngine.deviateNextExecution(1);
            case 2 -> fxEngine.changePriceOnNextExecution();
            case 3 -> fxEngine.serverErrorNext();
            case 4 -> CORRIDOR.loseNextResponse();
            case 5 -> CORRIDOR.serverErrorNext();
            default -> CORRIDOR.rejectNextSend("beneficiary_closed");
        }
    }

    /**
     * Each provider's recent callbacks delivered again, re-signed as of now (a provider's retry), twice - and once
     * signed with a key the platform does not hold: the inbox dedupes the first kind, the door refuses the second.
     */
    private static void duplicateAndForgeCallbacks(Instance instance) throws Exception {
        List<SimulatedCorridorEngine.SignedCallback> corridorCallbacks = CORRIDOR.callbacks();
        if (!corridorCallbacks.isEmpty()) {
            String body = corridorCallbacks.get(RANDOMNESS.nextInt(corridorCallbacks.size())).body();
            for (int i = 0; i < 2; i++) {
                assertThat(signedPost(instance.port(), CORRIDOR_WEBHOOKS, body, CORRIDOR_KEY).statusCode()).isLessThan(500);
            }
            assertThat(signedPost(instance.port(), CORRIDOR_WEBHOOKS, body, FORGER_KEY).statusCode())
                    .as("a callback signed with another key moves nothing").isIn(400, 401, 403);
        }
        List<SimulatedFxEngine.SignedCallback> fxCallbacks = fxEngine.callbacks();
        if (!fxCallbacks.isEmpty()) {
            String body = fxCallbacks.get(RANDOMNESS.nextInt(fxCallbacks.size())).body();
            for (int i = 0; i < 2; i++) {
                assertThat(signedPost(instance.port(), FX_WEBHOOKS, body, FX_KEY).statusCode()).isLessThan(500);
            }
            assertThat(signedPost(instance.port(), FX_WEBHOOKS, body, FORGER_KEY).statusCode())
                    .as("an FX callback signed with another key moves nothing").isIn(400, 401, 403);
        }
    }

    /** References observed afresh every ten seconds, as the rate sweeper would - staleness is the database's. */
    private static void referenceLoop(AtomicBoolean stop) {
        try {
            while (!stop.get()) {
                FxTestClient.freshReferences();
                for (int i = 0; i < 50 && !stop.get(); i++) {
                    Thread.sleep(200);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            throw new IllegalStateException("the reference loop failed", failure);
        }
    }

    // ================================================================== 2. the same object from both instances

    /**
     * The gate's two-instance race (PHASE_9_PLAN.md section 13 scenario 1, `TwoInstanceFxRaceTest`'s brief): the same
     * quote, cover, payment, recall and return driven from instance A (+5 s) and instance B (-5 s) at one instant -
     * one effect each, the losers answered, the providers counting one.
     */
    private void twoInstanceRaces(Instance a, Instance b, Mover owner) throws Exception {
        FxTestClient viaA = new FxTestClient(a.port());
        FxTestClient viaB = new FxTestClient(b.port());
        CORRIDOR.acceptOnReceipt(false);
        FxTestClient.freshReferences();

        // ONE QUOTE, converted on both instances under two keys: one trade, the loser 409.
        String quote = owner.client().quoteId(owner.customer(), "EUR", "USD", "FIXED_SOURCE", "100.00");
        List<HttpResponse<String>> converted = bothAtOnce(
                () -> viaA.convert(owner.customer(), quote, FxTestClient.key()),
                () -> viaB.convert(owner.customer(), quote, FxTestClient.key()));
        assertThat(converted.stream().map(HttpResponse::statusCode).toList()).as("one conversion of one quote")
                .containsExactlyInAnyOrder(201, 409);
        String trade = field(converted.stream().filter(answer -> answer.statusCode() == 201).findFirst().orElseThrow()
                .body(), "id");
        conversions.add(trade);
        assertThat(count("SELECT count(*) FROM fx.trade WHERE quote_id = ?::uuid", quote)).isEqualTo(1);

        // ONE COVER, dispatched by both at once: one execution at the provider.
        UUID cover = FxCoverFixtures.coverOf(trade);
        bothAtOnce(() -> {
            a.dispatch().dispatchNow(cover, Actor.SYSTEM);
            return null;
        }, () -> {
            b.dispatch().dispatchNow(cover, Actor.SYSTEM);
            return null;
        });
        awaitCoverExecuted(cover, a);
        assertOneExecution(cover, "one cover from both instances");

        // ONE CROSS-BORDER QUOTE, paid on both instances under two keys: one payment, one instruction.
        String offer = offer(owner, false);
        List<HttpResponse<String>> paid = bothAtOnce(
                () -> viaA.post(PAYMENTS, "{\"quoteId\":\"" + offer + "\"}", owner.customer().token(), FxTestClient.key()),
                () -> viaB.post(PAYMENTS, "{\"quoteId\":\"" + offer + "\"}", owner.customer().token(), FxTestClient.key()));
        assertThat(paid.stream().map(HttpResponse::statusCode).toList()).as("one payment of one quote")
                .containsExactlyInAnyOrder(202, 409);
        UUID payment = UUID.fromString(scalar("SELECT id::text FROM crossborder.payment WHERE quote_id = ?::uuid", offer));
        Paid once = paidOf(owner, payment);
        assertThat(count("SELECT count(*) FROM payments.outbound_credit WHERE subject_id = ?", payment)).isEqualTo(1);
        assertThat(CORRIDOR.creditsOf(once.reference())).as("one instruction at the provider").isEqualTo(1);

        // ONE RECALL, requested on both instances: one request, one recall, the hold released once.
        List<HttpResponse<String>> recalls = bothAtOnce(
                () -> viaA.post(PAYMENTS + "/" + payment + "/cancellation", null, owner.customer().token(), FxTestClient.key()),
                () -> viaB.post(PAYMENTS + "/" + payment + "/cancellation", null, owner.customer().token(), FxTestClient.key()));
        assertThat(recalls.stream().map(HttpResponse::statusCode).toList()).containsOnly(202);
        assertThat(count("SELECT count(*) FROM crossborder.cancellation_request WHERE payment_id = ?", payment)).isEqualTo(1);
        bothAtOnce(() -> resolveOn(a, once), () -> resolveOn(b, once));
        assertPayment(once, "FAILED", "RECALLED");
        assertThat(count("SELECT count(*) FROM fx.cover WHERE quote_id = ?::uuid AND kind = 'UNWIND'", offer))
                .as("one unwind, whoever concluded").isLessThanOrEqualTo(1);

        // ONE RETURN, applied by both instances' inquiries at once: one return fact, one return entry.
        Paid returned = completed(owner, true);
        CORRIDOR.returnCredit(returned.reference());
        bothAtOnce(() -> resolveOn(a, returned), () -> resolveOn(b, returned));
        assertThat(paymentStatus(returned)).isEqualTo("RETURNED");
        assertThat(count("SELECT count(*) FROM payments.outbound_credit_return WHERE outbound_credit_id = ?",
                returned.credit())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                PostingService.IDEMPOTENCY_SCOPE + ":crossborder-return:" + returned.credit())).isEqualTo(1);
        reconcile("the same quote, cover, payment, recall and return from both instances");
    }

    private static Object resolveOn(Instance instance, Paid paid) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            return instance.resolution().resolve(new EndToEndReference(paid.reference()));
        }
    }

    /** Both callables released at one instant on their own threads; their answers, in order. */
    private static <T> List<T> bothAtOnce(java.util.concurrent.Callable<T> first, java.util.concurrent.Callable<T> second)
            throws Exception {
        ExecutorService pair = Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            Future<T> one = pair.submit(() -> {
                start.await();
                return first.call();
            });
            Future<T> two = pair.submit(() -> {
                start.await();
                return second.call();
            });
            start.countDown();
            return java.util.Arrays.asList(one.get(2, TimeUnit.MINUTES), two.get(2, TimeUnit.MINUTES));
        } finally {
            pair.shutdownNow();
        }
    }

    // ================================================================== 3. the catalogue

    private void catalogue(Instance a, Instance b, List<Mover> movers) throws Exception {
        Mover onA = movers.get(1);
        Mover onB = movers.get(MOVERS_PER_INSTANCE + 1);
        CORRIDOR.acceptOnReceipt(false);
        FxTestClient.freshReferences();

        // FX: the response lost after the provider executed - UNKNOWN, then the inquiry; one execution.
        UUID lost = coverOfNewConversion(onA, "EUR", "USD");
        fxEngine.loseNextExecutionResponse(); // armed just before its call: the engine's one slot takes any request
        dispatch.dispatchNow(lost, Actor.SYSTEM);
        assertThat(FxCoverFixtures.status(lost)).isEqualTo("UNKNOWN");
        b.dispatch().dispatchNow(lost, Actor.SYSTEM);
        assertOneExecution(lost, "a lost response");
        reconcile("an FX response lost");

        // FX: the same callback ten times at both doors, and a forged one - one effect.
        duplicateAndForgeCallbacks(a);
        duplicateAndForgeCallbacks(b);
        assertOneExecution(lost, "duplicated callbacks");
        reconcile("FX callbacks duplicated and forged");

        // FX: the lock expired at the provider - a fresh firm quote, a requote, the difference realised.
        UUID expired = coverOfNewConversion(onB, "GBP", "USD");
        fxEngine.advance(Duration.ofMinutes(10));
        try {
            b.dispatch().dispatchNow(expired, Actor.SYSTEM);
        } finally {
            // Back at once: the provider's clock moved only for this one lock - every later quote it issues is stamped
            // in its own time, and a provider whose quotes read ten minutes ahead has every one refused.
            fxEngine.advance(Duration.ofMinutes(-10));
        }
        assertThat(FxCoverFixtures.status(expired)).isEqualTo("REJECTED");
        awaitCoverExecuted(expired, a);
        assertThat(count("SELECT attempts FROM fx.cover WHERE id = ?", expired)).as("T1 refused, T2 executed").isEqualTo(2);
        reconcile("a lock expired and requoted");

        // FX: the provider executes off plan - posted, flagged.
        // FIXED_DESTINATION: the deviated BOUGHT leg is the plan's fixed one (a computed leg's unit is a realised result).
        UUID offPlan = coverOfNewConversion(onA, "EUR", "USD", "FIXED_DESTINATION");
        fxEngine.deviateNextExecution(1);
        dispatch.dispatchNow(offPlan, Actor.SYSTEM);
        assertThat(scalar("SELECT executed_off_plan::text FROM fx.cover_execution WHERE cover_id = ?", offPlan)).isEqualTo("true");
        reconcile("an off-plan execution");

        // FX: a crash mid-cover - the provider executed, our record of it refused; the re-send is answered by T.
        UUID crashed = coverOfNewConversion(onB, "USD", "EUR");
        try (AutoCloseable fault = refuse("fx.cover_execution", "INSERT", "NEW.cover_id = '" + crashed + "'::uuid")) {
            assertThatThrownBy(() -> b.dispatch().dispatchNow(crashed, Actor.SYSTEM)).isInstanceOf(RuntimeException.class);
        }
        assertThat(count("SELECT count(*) FROM fx.cover_execution WHERE cover_id = ?", crashed)).isZero();
        dispatch.dispatchNow(crashed, Actor.SYSTEM);
        assertOneExecution(crashed, "a crash mid-cover");
        reconcile("a crash mid-cover");

        // CORRIDOR: declined at send - FAILED, the hold released, the quote abandoned.
        Paid declined = pay(onA, false, () -> CORRIDOR.rejectNextSend("beneficiary_closed"));
        resolve(declined);
        assertPayment(declined, "FAILED", "DECLINED");
        reconcile("a declined send");

        // CORRIDOR: RECEIVED, then rejected hours later - FAILED, the hold released, the cover unwound.
        Paid rejectedLater = pay(onB, true, null);
        resolve(rejectedLater);
        assertThat(creditStatus(rejectedLater)).isEqualTo("RECEIVED");
        CORRIDOR.rejectReceived(rejectedLater.reference(), "beneficiary_closed");
        resolve(rejectedLater);
        assertPayment(rejectedLater, "FAILED", "DECLINED");
        reconcile("RECEIVED, then rejected");

        // CORRIDOR: never received - the send's 500 left nothing at the provider; past the deadline, FAILED.
        Paid neverReceived = pay(onA, false, CORRIDOR::serverErrorNext);
        assertThat(CORRIDOR.creditsOf(neverReceived.reference())).isZero();
        agePermit(neverReceived.credit(), Duration.ofMinutes(20));
        resolve(neverReceived);
        assertPayment(neverReceived, "FAILED", "NEVER_RECEIVED");
        reconcile("never received");

        // CORRIDOR: a recall wins - FAILED(RECALLED); a recall too late - the payment stays in transit.
        Paid recalled = pay(onB, true, null);
        resolve(recalled);
        assertThat(onB.client().post(PAYMENTS + "/" + recalled.payment() + "/cancellation", null, onB.customer().token(),
                FxTestClient.key()).statusCode()).isEqualTo(202);
        resolve(recalled);
        assertPayment(recalled, "FAILED", "RECALLED");
        Paid tooLate = pay(onA, false, null);
        resolve(tooLate);
        CORRIDOR.accept(tooLate.reference());
        assertThat(onA.client().post(PAYMENTS + "/" + tooLate.payment() + "/cancellation", null, onA.customer().token(),
                FxTestClient.key()).statusCode()).isIn(202, 409);
        resolve(tooLate);
        assertThat(paymentStatus(tooLate)).isIn("IN_TRANSIT", "DELIVERED");
        reconcile("a recall that wins and one too late");

        // RETURNS: before delivery, after delivery, and before the completion is even known.
        Paid beforeDelivery = completed(onB, true);
        CORRIDOR.returnCredit(beforeDelivery.reference());
        resolve(beforeDelivery);
        assertThat(paymentStatus(beforeDelivery)).isEqualTo("RETURNED");
        Paid afterDelivery = completed(onA, false);
        CORRIDOR.deliver(afterDelivery.reference());
        resolve(afterDelivery);
        assertThat(paymentStatus(afterDelivery)).isEqualTo("DELIVERED");
        CORRIDOR.returnCredit(afterDelivery.reference());
        resolve(afterDelivery);
        assertThat(paymentStatus(afterDelivery)).isEqualTo("RETURNED");
        Paid unknownCompletion = pay(onB, false, null);
        resolve(unknownCompletion);
        CORRIDOR.accept(unknownCompletion.reference());
        CORRIDOR.returnCredit(unknownCompletion.reference());
        resolve(unknownCompletion);
        assertThat(paymentStatus(unknownCompletion)).as("the completion and the return applied in one transaction")
                .isEqualTo("RETURNED");
        reconcile("returns before, after and without a known delivery");

        // RETURNS: a partial return parks at grace; a person's four-eyes transfer resolves it.
        Paid partial = completed(onA, false);
        partialReturnParkedAndResolved(partial);
        reconcile("a partial return parked and resolved");

        // CRASH after Tx1: the second transaction refused - the request dies with the provider holding the credit;
        // a takeover under the same key re-sends E, and the provider counts ONE instruction.
        crashAfterTx1(onB, b);
        reconcile("a crash after Tx1, taken over");

        // CRASH mid-completion: the completion's edge refused once; the next inquiry completes it, once.
        Paid midCompletion = pay(onA, true, null);
        resolve(midCompletion);
        CORRIDOR.accept(midCompletion.reference());
        try (AutoCloseable fault = refuse("crossborder.payment_event", "INSERT",
                "NEW.payment_id = '" + midCompletion.payment() + "'::uuid AND NEW.to_status = 'IN_TRANSIT'")) {
            assertThatThrownBy(() -> resolve(midCompletion)).isInstanceOf(RuntimeException.class);
        }
        assertThat(paymentStatus(midCompletion)).isEqualTo("SUBMITTED");
        resolve(midCompletion);
        assertThat(paymentStatus(midCompletion)).isEqualTo("IN_TRANSIT");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                PostingService.IDEMPOTENCY_SCOPE + ":outbound-credit:" + midCompletion.credit())).isEqualTo(1);
        reconcile("a crash mid-completion");
        CORRIDOR.acceptOnReceipt(false);
    }

    private UUID coverOfNewConversion(Mover mover, String source, String destination) throws Exception {
        return coverOfNewConversion(mover, source, destination, "FIXED_SOURCE");
    }

    private UUID coverOfNewConversion(Mover mover, String source, String destination, String side) throws Exception {
        String fixed = side.equals("FIXED_SOURCE") ? source : destination;
        String trade = FxCoverFixtures.convertedTrade(mover.client(), mover.customer(), source, destination, side,
                fixed.equals("JPY") ? "5000" : "100.00");
        conversions.add(trade);
        return FxCoverFixtures.coverOf(trade);
    }

    private static void assertOneExecution(UUID cover, String context) throws Exception {
        assertThat(count("SELECT count(*) FROM fx.cover_execution WHERE cover_id = ?", cover)).as(context).isEqualTo(1);
        String reference = scalar("SELECT client_reference FROM fx.cover_execution WHERE cover_id = ?", cover);
        assertThat(fxEngine.executionsOf(reference)).as("%s: the provider executed T once", context).isEqualTo(1);
    }

    private static void awaitCoverExecuted(UUID cover, Instance instance) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!FxCoverFixtures.status(cover).equals("EXECUTED")) {
            assertThat(System.nanoTime()).as("cover %s executes (now %s)", cover, FxCoverFixtures.status(cover))
                    .isLessThan(deadline);
            Thread.sleep(500);
            instance.dispatch().dispatchNow(cover, Actor.SYSTEM);
        }
    }

    /** The return line half the credit, parked at grace; a four-eyes transfer returns it and the payment RETURNED. */
    private void partialReturnParkedAndResolved(Paid paid) throws Exception {
        String currency = scalar("SELECT destination_currency FROM crossborder.payment_offer WHERE quote_id = ?", paid.quote());
        int scale = CurrencyCode.of(currency).minorUnits();
        long destination = Long.parseLong(scalar("SELECT destination_minor::text FROM crossborder.payment_offer"
                + " WHERE quote_id = ?", paid.quote()));
        String half = amount(destination / 2, scale);
        UUID report = acceptedBatch(CORRIDOR_SOURCE, corridorReport(currency,
                List.of("D,1,BOUNCED," + half + ",," + paid.providerReference() + "," + paid.reference()), half));
        matchUntilQuiet();
        worker.sweep();
        UUID item = UUID.fromString(scalar("SELECT i.id::text FROM reconciliation.external_item i"
                + " JOIN reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE r.batch_id = ?"
                + " AND i.line_type = 'PAYOUT_RETURNED'", report));
        try (Connection app = DatabaseRoles.application();
                PreparedStatement update = app.prepareStatement(
                        "UPDATE reconciliation.external_item SET grace_until = now() - interval '1 hour' WHERE id = ?")) {
            update.setObject(1, item);
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
        matchUntilQuiet();
        assertThat(scalar("SELECT status FROM reconciliation.external_item WHERE id = ?", item)).isEqualTo("PARKED");
        UUID breakId = UUID.fromString(scalar("SELECT id::text FROM reconciliation.break WHERE external_item_id = ?"
                + " AND cause = 'RETURN_NOT_APPLICABLE'", item));
        UUID target = UUID.fromString(scalar("SELECT id::text FROM ledger.ledger_account WHERE owner_ref::text = ?"
                + " AND purpose = 'CUSTOMER_WALLET' AND currency = ?", paid.mover().product().toString(), currency));
        String operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> proposed = client().post(DESK + "/breaks/" + breakId + "/resolutions", "{\"kind\":"
                + "\"TRANSFER_TO_ACCOUNT\",\"reasonCode\":\"FUNDS_ATTRIBUTED\",\"narrative\":\"the partial return goes"
                + " back to its customer\",\"targetAccountId\":\"" + target + "\"}", operator, FxTestClient.key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        HttpResponse<String> approved = client().post(DESK + "/resolutions/" + field(proposed.body(), "resolutionId")
                + "/approval", "{}", sessionWith(RoleName.RECONCILIATION_OPERATOR), null);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(paymentStatus(paid)).as("the person's resolution returns the payment").isEqualTo("RETURNED");
        partialReturns.add(paid.credit());
    }

    /** Credits whose return line the catalogue's partial return already reported - settled with that report. */
    private final List<UUID> partialReturns = new CopyOnWriteArrayList<>();

    private void crashAfterTx1(Mover mover, Instance instance) throws Exception {
        String quote = offer(mover, true);
        String key = FxTestClient.key();
        String condition = "NEW.subject_id IN (SELECT id FROM crossborder.payment WHERE quote_id = '" + quote + "'::uuid)"
                + " AND NEW.status IS DISTINCT FROM OLD.status";
        HttpResponse<String> died;
        try (AutoCloseable fault = refuse("payments.outbound_credit", "UPDATE", condition)) {
            died = mover.client().post(PAYMENTS, "{\"quoteId\":\"" + quote + "\"}", mover.customer().token(), key);
        }
        assertThat(died.statusCode()).as("the instance died after Tx1: %s", died.body()).isGreaterThanOrEqualTo(500);
        UUID payment = UUID.fromString(scalar("SELECT id::text FROM crossborder.payment WHERE quote_id = ?::uuid", quote));
        String reference = scalar("SELECT end_to_end_reference FROM payments.outbound_credit WHERE subject_id = ?", payment);
        assertThat(scalar("SELECT status FROM payments.outbound_credit WHERE subject_id = ?", payment)).isEqualTo("DISPATCHED");
        assertThat(CORRIDOR.creditsOf(reference)).as("the provider received the first send").isEqualTo(1);
        try (Connection app = DatabaseRoles.application();
                PreparedStatement update = app.prepareStatement("UPDATE platform.idempotency_record SET lease_expires_at ="
                        + " now() - interval '1 minute' WHERE idempotency_key = ? AND state = 'IN_PROGRESS'")) {
            update.setString(1, key);
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
        HttpResponse<String> takenOver = new FxTestClient(instance.port()).post(PAYMENTS, "{\"quoteId\":\"" + quote + "\"}",
                mover.customer().token(), key);
        assertThat(takenOver.statusCode()).as(takenOver.body()).isEqualTo(202);
        assertThat(CORRIDOR.creditsOf(reference)).as("the takeover re-sent E: the provider counts ONE instruction")
                .isEqualTo(1);
        assertThat(scalar("SELECT status FROM payments.outbound_credit WHERE subject_id = ?", payment)).isIn("RECEIVED", "COMPLETED");
        payments.put(payment, mover);
    }

    // ================================================================== 4. drain and settle

    /** Everything to rest: every credit and cover terminal, every received credit accepted at the provider. */
    private void drain(Instance a, Instance b) throws Exception {
        CORRIDOR.acceptOnReceipt(true);
        // A bound on progress, not on correctness: some 4,500 covers and 2,000 credits drain at a page per instance.
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(8);
        while (true) {
            // The rate sweeper's work, as under load: an unwind's first price is judged against a FRESH reference.
            FxTestClient.freshReferences();
            for (String[] credit : rows("SELECT end_to_end_reference, status, id::text FROM payments.outbound_credit"
                    + " WHERE status IN ('DISPATCHED', 'UNKNOWN', 'RECEIVED')")) {
                if (credit[1].equals("RECEIVED")) {
                    CORRIDOR.accept(credit[0]);
                } else if (CORRIDOR.creditsOf(credit[0]) == 0) {
                    agePermit(UUID.fromString(credit[2]), Duration.ofMinutes(20));
                }
            }
            resolveOnce(a);
            resolveOnce(b);
            List<String> openCovers = rows1("SELECT id::text FROM fx.cover WHERE status IN ('DISPATCHED', 'UNKNOWN',"
                    + " 'REJECTED')");
            if (openCovers.size() <= 20) {
                for (String cover : openCovers) {
                    (RANDOMNESS.nextBoolean() ? a : b).dispatch().dispatchNow(UUID.fromString(cover), Actor.SYSTEM);
                }
            }
            long open = count("SELECT count(*) FROM payments.outbound_credit WHERE status IN ('DISPATCHED', 'UNKNOWN',"
                    + " 'RECEIVED')") + count("SELECT count(*) FROM fx.cover WHERE status IN ('DISPATCHED', 'UNKNOWN',"
                    + " 'REJECTED')");
            if (open == 0) {
                break;
            }
            System.out.println("P9-TST-001 DRAIN open " + open + " " + rows("SELECT status, count(*) FROM"
                    + " payments.outbound_credit GROUP BY status UNION ALL SELECT 'cover ' || status, count(*) FROM fx.cover"
                    + " GROUP BY status").stream().map(row -> row[0] + "=" + row[1]).toList());
            if (System.nanoTime() >= deadline) {
                for (String stuck : rows1("SELECT end_to_end_reference FROM payments.outbound_credit WHERE status IN"
                        + " ('DISPATCHED', 'UNKNOWN', 'RECEIVED') LIMIT 3")) {
                    System.out.println("P9-TST-001 STUCK " + stuck + " row " + rows("SELECT status, coalesce(recall_requested_at::text,"
                            + " '-'), last_dispatched_at::text, coalesce(provider_reference, '-') FROM payments.outbound_credit"
                            + " WHERE end_to_end_reference = '" + stuck + "'").stream().map(List::of).toList()
                            + " provider " + providerAnswer(stuck));
                    try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(
                                    CorrelationId.generate(FxTestClient.IDS)));
                            SecurityContext.Scope actor = SecurityContext.enterSystem()) {
                        resolution.resolve(new EndToEndReference(stuck));
                    } catch (RuntimeException failure) {
                        java.io.StringWriter trace = new java.io.StringWriter();
                        failure.printStackTrace(new java.io.PrintWriter(trace));
                        System.out.println("P9-TST-001 STUCK " + stuck + ": " + trace.toString().lines().limit(25)
                                .collect(java.util.stream.Collectors.joining(" | ")));
                    }
                }
            }
            assertThat(System.nanoTime()).as("the storm drains: %s still open - %s", open, rows("SELECT 'credit',"
                    + " end_to_end_reference, status FROM payments.outbound_credit WHERE status IN ('DISPATCHED',"
                    + " 'UNKNOWN', 'RECEIVED') UNION ALL SELECT 'cover', id::text, status FROM fx.cover WHERE status IN"
                    + " ('DISPATCHED', 'UNKNOWN', 'REJECTED')").stream().map(List::of).toList()).isLessThan(deadline);
            Thread.sleep(1_100);
        }
        CORRIDOR.acceptOnReceipt(false);
    }

    /**
     * Every executed leg settled from the database's own truth: per currency and value date one FX trade report (each
     * cover execution's SOLD and BOUGHT legs) and its bank statement; per corridor currency one report (each completed
     * credit CREDITED with the schedule's fee, each applied return BOUNCED) and its bank statement. One FX leg is off
     * by one minor unit; one corridor file names a currency the counterparty does not settle.
     */
    private void settle() throws Exception {
        // The FX side.
        Map<String, List<String>> fxLines = new TreeMap<>();
        Map<String, Long> fxNets = new TreeMap<>();
        for (String[] leg : rows("SELECT sold_currency, sold_minor, sold_scale, bought_currency, bought_minor, bought_scale,"
                + " provider_trade_ref, client_reference, value_date::text FROM fx.cover_execution ORDER BY recorded_at")) {
            String sold = leg[0].trim() + "|" + leg[8];
            String bought = leg[3].trim() + "|" + leg[8];
            fxLines.computeIfAbsent(sold, ignored -> new ArrayList<>()).add("SOLD,-"
                    + amount(Long.parseLong(leg[1]), Integer.parseInt(leg[2])) + ",," + leg[6] + "," + leg[7]);
            fxNets.merge(sold, -Long.parseLong(leg[1]), Long::sum);
            fxLines.computeIfAbsent(bought, ignored -> new ArrayList<>()).add("BOUGHT,"
                    + amount(Long.parseLong(leg[4]), Integer.parseInt(leg[5])) + ",," + leg[6] + "," + leg[7]);
            fxNets.merge(bought, Long.parseLong(leg[4]), Long::sum);
        }
        boolean perturbed = false;
        for (Map.Entry<String, List<String>> file : fxLines.entrySet()) {
            String currency = file.getKey().split("\\|")[0];
            String valueDate = file.getKey().split("\\|")[1];
            int scale = CurrencyCode.of(currency).minorUnits();
            List<String> lines = new ArrayList<>(file.getValue());
            long net = fxNets.get(file.getKey());
            int bought = -1;
            for (int i = 0; i < lines.size() && bought < 0; i++) {
                bought = lines.get(i).startsWith("BOUGHT,") ? i : -1;
            }
            if (!perturbed && currency.equals("USD") && bought >= 0) {
                // SETTLEMENT MISMATCH: the provider's BOUGHT leg one minor unit more than we recorded.
                String[] parts = lines.get(bought).split(",", -1);
                long minor = new BigDecimal(parts[1]).movePointRight(scale).longValueExact() + 1;
                lines.set(bought, "BOUGHT," + amount(minor, scale) + ",," + parts[3] + "," + parts[4]);
                net += 1;
                perturbed = true;
                fxMismatchReference.set(parts[4]);
            }
            List<String> numbered = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                numbered.add("D," + (i + 1) + "," + lines.get(i));
            }
            String remittance = "FXA-" + digits(8);
            acceptedBatch(FX_SOURCE, "H,SIM_FX_CSV,1,FXB-" + marker() + "-" + currency + "," + currency + "," + valueDate
                    + "\n" + String.join("\n", numbered) + "\nT," + numbered.size() + "," + amount(net, scale) + ","
                    + remittance + "\n");
            matchUntilQuiet();
            acceptedBatch(BANK_SOURCE, nextStatement(currency, valueDate, net >= 0 ? "C" : "D", Math.abs(net), remittance));
            matchUntilQuiet();
        }
        assertThat(perturbed).as("the off-by-one leg was seeded").isTrue();

        // The corridor side: completed credits CREDITED with the schedule's fee, applied returns BOUNCED.
        Map<String, List<String>> corridorLines = new TreeMap<>();
        Map<String, Long> corridorNets = new TreeMap<>();
        Map<String, Long> fees = Map.of("USD", 120L, "JPY", 180L);
        for (String[] credit : rows("SELECT c.id::text, c.end_to_end_reference, c.provider_reference, o.destination_minor,"
                + " o.destination_currency, (SELECT count(*) FROM payments.outbound_credit_return r"
                + " WHERE r.outbound_credit_id = c.id) FROM payments.outbound_credit c JOIN crossborder.payment p"
                + " ON p.id = c.subject_id JOIN crossborder.payment_offer o ON o.quote_id = p.quote_id"
                + " WHERE c.status = 'COMPLETED' ORDER BY c.created_at")) {
            String currency = credit[4].trim();
            int scale = CurrencyCode.of(currency).minorUnits();
            long destination = Long.parseLong(credit[3]);
            long fee = fees.get(currency);
            corridorLines.computeIfAbsent(currency, ignored -> new ArrayList<>()).add("CREDITED,-" + amount(destination, scale)
                    + "," + amount(fee, scale) + "," + credit[2] + "," + credit[1]);
            corridorNets.merge(currency, -(destination + fee), Long::sum);
            if (Long.parseLong(credit[5]) > 0 && !partialReturns.contains(UUID.fromString(credit[0]))) {
                corridorLines.get(currency).add("BOUNCED," + amount(destination, scale) + ",," + credit[2] + "," + credit[1]);
                corridorNets.merge(currency, destination, Long::sum);
            }
        }
        for (Map.Entry<String, List<String>> file : corridorLines.entrySet()) {
            String currency = file.getKey();
            int scale = CurrencyCode.of(currency).minorUnits();
            long net = corridorNets.get(currency);
            List<String> numbered = new ArrayList<>();
            for (int i = 0; i < file.getValue().size(); i++) {
                numbered.add("D," + (i + 1) + "," + file.getValue().get(i));
            }
            String remittance = "XBA-" + (10_000 + RANDOMNESS.nextInt(80_000));
            acceptedBatch(CORRIDOR_SOURCE, "H,SIM_CORRIDOR_CSV,1,XB-" + marker() + "," + currency + "," + today() + "\n"
                    + String.join("\n", numbered) + "\nT," + numbered.size() + "," + amount(net, scale) + "," + remittance + "\n");
            matchUntilQuiet();
            worker.sweep();
            acceptedBatch(BANK_SOURCE, nextStatement(currency, today(), net >= 0 ? "C" : "D", Math.abs(net), remittance));
            matchUntilQuiet();
        }

        // AN UNEXPECTED CURRENCY: a EUR corridor file - rejected at parse, nothing posted.
        UUID eurFile = received(CORRIDOR_SOURCE, "H,SIM_CORRIDOR_CSV,1,XB-" + marker() + ",EUR," + today()
                + "\nD,1,CREDITED,-100.00,,XP-none,none\nT,1,-100.00,XBA-" + (10_000 + RANDOMNESS.nextInt(80_000)) + "\n");
        parsing.sweep();
        assertThat(scalar("SELECT status || ':' || coalesce(rejection_code, '-') FROM settlement.file WHERE id = ?", eurFile))
                .isEqualTo("REJECTED:CURRENCY_NOT_SETTLED");
        assertThat(count("SELECT count(*) FROM settlement.batch WHERE file_id = ?", eurFile)).isZero();

        // The breaks: exactly the seeded leg's AMOUNT_MISMATCH, and no other.
        assertThat(rows1("SELECT b.type || ':' || b.cause FROM reconciliation.break b WHERE b.status <> 'RESOLVED'"
                + " AND b.type <> 'REVERSAL_MISMATCH'"))
                .as("the one seeded settlement mismatch, typed - every other leg matched to cash")
                .containsExactly("AMOUNT_MISMATCH:FX_LEG_DIFFERS");
    }

    private final java.util.concurrent.atomic.AtomicReference<String> fxMismatchReference =
            new java.util.concurrent.atomic.AtomicReference<>();

    private void assertEveryCashVerdictExplained() throws Exception {
        try (Connection snapshot = DatabaseRoles.application()) {
            snapshot.setAutoCommit(false);
            snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            PositionProof.Report report = positionProof.sweep(snapshot);
            assertThat(report.cashVerdicts()).as("cash statements were loaded").isNotEmpty();
            assertThat(report.cashVerdicts()).allSatisfy(cash -> assertThat(cash.explained())
                    .as("cash %s: ledger %s against the chain's closing %s", cash.currency(), cash.ledgerBalance(),
                            cash.chainClosing())
                    .isTrue());
            snapshot.rollback();
        }
    }

    // ================================================================== 5. at rest

    /** The beneficiary's name walked through registration, screening, quote, payment, recall and return. */
    private void needleWalk(Instance a, Mover mover) throws Exception {
        FxTestClient.freshReferences();
        String beneficiary = beneficiary(mover, "US", "USD", NEEDLE);
        HttpResponse<String> offered = client().post(QUOTES, "{\"beneficiaryId\":\"" + beneficiary + "\",\"sourceCurrency\":"
                + "\"EUR\",\"fixedSide\":\"FIXED_SOURCE\",\"amount\":\"30.00\"}", mover.customer().token(), FxTestClient.key());
        assertThat(offered.statusCode()).as(offered.body()).isEqualTo(201);
        assertThat(offered.body()).doesNotContain(NEEDLE);
        CORRIDOR.acceptOnReceipt(false);
        HttpResponse<String> first = client().post(PAYMENTS, "{\"quoteId\":\"" + field(offered.body(), "id") + "\"}",
                mover.customer().token(), FxTestClient.key());
        assertThat(first.statusCode()).as(first.body()).isEqualTo(202);
        assertThat(first.body()).doesNotContain(NEEDLE);
        Paid recalled = paidOf(mover, UUID.fromString(field(first.body(), "paymentId")));
        resolve(recalled);
        HttpResponse<String> recall = client().post(PAYMENTS + "/" + recalled.payment() + "/cancellation", null,
                mover.customer().token(), FxTestClient.key());
        assertThat(recall.statusCode()).isEqualTo(202);
        assertThat(recall.body()).doesNotContain(NEEDLE);
        resolve(recalled);
        assertPayment(recalled, "FAILED", "RECALLED");

        HttpResponse<String> again = client().post(QUOTES, "{\"beneficiaryId\":\"" + beneficiary + "\",\"sourceCurrency\":"
                + "\"EUR\",\"fixedSide\":\"FIXED_SOURCE\",\"amount\":\"31.00\"}", mover.customer().token(), FxTestClient.key());
        HttpResponse<String> second = client().post(PAYMENTS, "{\"quoteId\":\"" + field(again.body(), "id") + "\"}",
                mover.customer().token(), FxTestClient.key());
        Paid returned = paidOf(mover, UUID.fromString(field(second.body(), "paymentId")));
        resolve(returned);
        CORRIDOR.accept(returned.reference());
        resolve(returned);
        CORRIDOR.returnCredit(returned.reference());
        resolve(returned);
        assertThat(paymentStatus(returned)).isEqualTo("RETURNED");
        HttpResponse<String> trace = client().get("/v1/operator/cross-border/payments/" + returned.payment() + "/trace",
                sessionWith(RoleName.RECONCILIATION_OPERATOR));
        assertThat(trace.body()).doesNotContain(NEEDLE);
    }

    private void assertTheNeedleReachedNoSink(CapturedOutput output) throws Exception {
        assertThat(tablesHolding(NEEDLE))
                .as("the name rests nowhere but kyc's ciphertext - no column, no outbox payload, no audit body")
                .isEmpty();
        assertThat(output.getAll()).as("and no log line carries it").doesNotContain(NEEDLE);
    }

    private void assertTheCensusesAreExact() throws Exception {
        SoftAssertions softly = new SoftAssertions();
        // The FX provider: every cover attempt's T executed exactly as often as we recorded it executed - once or never.
        long executions = 0;
        for (String[] attempt : rows("SELECT a.client_reference, (SELECT count(*) FROM fx.cover_execution e"
                + " WHERE e.client_reference = a.client_reference) FROM fx.cover_attempt a")) {
            int providerSays = fxEngine.executionsOf(attempt[0]);
            softly.assertThat((long) providerSays).as("T %s: the provider's executions = our execution facts", attempt[0])
                    .isEqualTo(Long.parseLong(attempt[1]));
            executions += providerSays;
        }
        softly.assertThat(executions).as("every provider execution is one of our attempts'").isEqualTo(fxEngine.executions());
        softly.assertThat(count("SELECT count(*) FROM fx.cover_execution")).as("execution facts").isEqualTo(executions);
        softly.assertThat(count("SELECT count(*) FROM fx.cover WHERE status NOT IN ('EXECUTED', 'VOIDED')"))
                .as("every cover at rest").isZero();

        // Trades: every booked conversion the storm saw, plus every completed cross-border payment.
        long completedPayments = count("SELECT count(*) FROM crossborder.payment WHERE status IN ('IN_TRANSIT',"
                + " 'DELIVERED', 'RETURNED')");
        softly.assertThat(count("SELECT count(*) FROM fx.trade")).as("trades = conversions + completed payments")
                .isEqualTo(conversions.size() + completedPayments);
        softly.assertThat(rows1("SELECT id::text FROM fx.trade")).as("every conversion the storm saw booked is a trade")
                .containsAll(conversions);

        // The corridor provider: every E sent at most once, and every credit it holds is one of ours.
        long instructed = 0;
        for (String[] credit : rows("SELECT end_to_end_reference, status, coalesce(failure_reason, '-')"
                + " FROM payments.outbound_credit")) {
            int providerSays = CORRIDOR.creditsOf(credit[0]);
            softly.assertThat(providerSays).as("credit %s (%s %s): instructed at most once", credit[0], credit[1], credit[2])
                    .isLessThanOrEqualTo(1);
            if (!credit[1].equals("FAILED")) {
                softly.assertThat(providerSays).as("credit %s (%s): the provider holds it", credit[0], credit[1]).isEqualTo(1);
            }
            if (credit[2].equals("NEVER_RECEIVED")) {
                softly.assertThat(providerSays).as("credit %s concluded never received: it never was", credit[0]).isZero();
            }
            instructed += providerSays;
        }
        softly.assertThat(instructed).as("provider instruction count = our outbound credits instructed")
                .isEqualTo(CORRIDOR.credits());

        // Every executed COVER is accounted for: its quote booked a trade, or was abandoned and unwound exactly once;
        // every UNWIND executed. Every abandoned quote whose cover executed has its one unwind.
        softly.assertThat(count("SELECT count(*) FROM fx.cover c JOIN fx.quote q ON q.id = c.quote_id WHERE c.kind = 'COVER'"
                + " AND c.status = 'EXECUTED' AND NOT EXISTS (SELECT 1 FROM fx.trade t WHERE t.quote_id = q.id)"
                + " AND (SELECT count(*) FROM fx.cover u WHERE u.quote_id = q.id AND u.kind = 'UNWIND'"
                + " AND u.status = 'EXECUTED') <> 1"))
                .as("an executed cover whose quote booked no trade is unwound exactly once").isZero();
        softly.assertThat(count("SELECT count(*) FROM fx.cover u WHERE u.kind = 'UNWIND' AND (u.status <> 'EXECUTED'"
                + " OR EXISTS (SELECT 1 FROM fx.trade t WHERE t.quote_id = u.quote_id AND t.status = 'BOOKED'))"))
                .as("every unwind executed, and none beside a booked trade").isZero();
        softly.assertThat(count("SELECT count(*) FROM fx.cover c WHERE c.kind = 'COVER' AND c.status = 'EXECUTED'"))
                .as("executed covers = trades + unwinds")
                .isEqualTo(count("SELECT count(*) FROM fx.trade") + count("SELECT count(*) FROM fx.cover WHERE kind = 'UNWIND'"));
        // No customer line for a payment that never completed: a completion's entry exists only for those that did.
        softly.assertThat(count("SELECT count(*) FROM ledger.journal_entry e JOIN payments.outbound_credit c"
                + " ON e.idempotency_scope = 'ledger.post:outbound-credit:' || c.id::text JOIN crossborder.payment p"
                + " ON p.id = c.subject_id WHERE p.status NOT IN ('IN_TRANSIT', 'DELIVERED', 'RETURNED')"))
                .as("no completion entry for a payment that is not in transit, delivered or returned").isZero();

        // Holds: none ACTIVE at rest; every payment terminal or in transit; each quote's legs accounted.
        softly.assertThat(count("SELECT count(*) FROM payments.outbound_credit c JOIN ledger.hold h ON h.id = c.hold_id"
                + " WHERE h.status = 'ACTIVE'")).as("no cross-border hold active at rest").isZero();
        softly.assertThat(count("SELECT count(*) FROM crossborder.payment WHERE status = 'SUBMITTED'"))
                .as("no payment still submitted").isZero();
        softly.assertAll();
    }

    private void assertTheBooksAtRest() throws Exception {
        try (Connection snapshot = DatabaseRoles.application()) {
            snapshot.setAutoCommit(false);
            snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            for (String currency : CURRENCIES) {
                assertThat(balance(snapshot, "FX_POSITION", currency)).as("FX_POSITION %s at rest", currency).isZero();
            }
            com.finapp.ledger.ProjectionVerification.Report projections =
                    new com.finapp.ledger.ProjectionVerification(new com.finapp.ledger.JdbcBalanceDerivation()).verify(snapshot);
            assertThat(projections.drifting()).as("every balance projection replays from its lines (F2): %s",
                    projections.driftingAccounts()).isZero();
            assertThat(projections.verified()).isPositive();
            FxPlanVerification.Report plans = planVerification.verify(snapshot);
            assertThat(plans.clean()).as("every trade's plan replays from its pinned policy: %s", plans).isTrue();
            assertThat(plans.verified()).isPositive();
            snapshot.rollback();
        }
        assertThat(count("SELECT count(*) FROM payments.outbound_credit WHERE status IN ('DISPATCHED', 'UNKNOWN', 'RECEIVED')"))
                .as("the outbound gauges' rows: none awaiting").isZero();
        // The gauges themselves at rest, read as an operator would (a reading may be cached for a few seconds).
        for (String gauge : List.of("finapp.fx.cover.unknown.active", "finapp.payments.outbound.unknown.active",
                "finapp.payments.outbound.received.active")) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            double value;
            while (true) {
                value = meters.find(gauge).gauges().stream().mapToDouble(io.micrometer.core.instrument.Gauge::value).sum();
                if (value == 0 || System.nanoTime() > deadline) {
                    break;
                }
                Thread.sleep(500);
            }
            assertThat(meters.find(gauge).gauges()).as("%s is published", gauge).isNotEmpty();
            assertThat(value).as("%s at rest", gauge).isZero();
        }
    }

    private void replayEveryRun() throws Exception {
        String investigator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        List<String> runs = rows1("SELECT id::text FROM reconciliation.reconciliation_batch ORDER BY created_at");
        assertThat(runs).as("the storm's runs").hasSizeGreaterThan(4);
        for (String run : runs) {
            HttpResponse<String> replayed = client().post(DESK + "/runs/" + run + "/replay", null, investigator, null);
            assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(200);
            assertThat(field(replayed.body(), "verdict")).as("run %s: %s", run, replayed.body()).isEqualTo("IDENTICAL");
        }
    }

    private void recordTheHotRows() {
        List<Long> sorted = new ArrayList<>(conversionLatencies);
        Collections.sort(sorted);
        assertThat(sorted).as("conversions were timed").isNotEmpty();
        long p50 = sorted.get(sorted.size() / 2);
        long p99 = sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.99) - 1));
        System.out.printf("P9-TST-001 HOT ROWS: %d conversions over FX_POSITION and FX_SPREAD_REVENUE per currency -"
                + " door latency p50 %d ms, p99 %d ms, max %d ms; outcomes %s%n", sorted.size(),
                TimeUnit.NANOSECONDS.toMillis(p50), TimeUnit.NANOSECONDS.toMillis(p99),
                TimeUnit.NANOSECONDS.toMillis(sorted.get(sorted.size() - 1)), new TreeMap<>(outcomes));
    }

    // ================================================================== every round

    /**
     * ONE REPEATABLE READ snapshot: the per-currency trial balance, the FX books proof, reconciliation's position
     * proof (every position explained, every proven line attributed, no suspense unowned), every clearing line's
     * copy, and the holds check - every soft, so a failure names every reading that caught it.
     */
    private void reconcile(String when) throws SQLException {
        rounds.incrementAndGet();
        SoftAssertions softly = new SoftAssertions();
        try (Connection snapshot = DatabaseRoles.application()) {
            snapshot.setAutoCommit(false);
            snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                softly.assertThat(trialBalance.sweep(snapshot).outOfBalance())
                        .as("%s: the trial balance, zero per currency (INV-ACC-01)", when).isEmpty();
                FxBooksProof.Report books = booksProof.prove(snapshot);
                softly.assertThat(books.clean()).as("%s: the FX books proof (INV-FX-06): %s", when, books).isTrue();
                PositionProof.Report report = positionProof.sweep(snapshot);
                softly.assertThat(report.verdicts()).as("%s: every clearing position explained (INV-REC-06)", when)
                        .allSatisfy(verdict -> assertThat(verdict.explained())
                                .as("%s: %s %s %s balance %s = remainders %s - items %s", when, verdict.purpose(),
                                        verdict.counterparty(), verdict.currency(), verdict.ledgerBalance(),
                                        verdict.openRemainders(), verdict.openItems())
                                .isTrue());
                for (AccountPurpose purpose : PositionProof.provenPurposes(settlementSources)) {
                    softly.assertThat(report.unattributedByPurpose().getOrDefault(purpose, 0L))
                            .as("%s: every %s line known (INV-SET-02)", when, purpose).isZero();
                }
                softly.assertThat(report.suspenseUnowned()).as("%s: no suspense unowned", when).isZero();
                softly.assertThat(one(snapshot, "SELECT count(*) FROM payments.outbound_credit c LEFT JOIN ledger.hold h"
                        + " ON h.id = c.hold_id WHERE c.status IN ('DISPATCHED', 'UNKNOWN', 'RECEIVED') AND (h.id IS NULL"
                        + " OR h.status <> 'ACTIVE' OR h.amount_minor <> c.held_minor)"))
                        .as("%s: every credit awaiting its outcome holds exactly its hold (INV-LIFE-03)", when).isZero();
                softly.assertThat(one(snapshot, "SELECT count(*) FROM payments.outbound_credit c JOIN ledger.hold h"
                        + " ON h.id = c.hold_id WHERE c.status IN ('COMPLETED', 'FAILED') AND h.status = 'ACTIVE'"))
                        .as("%s: no concluded credit still holds money", when).isZero();
            } finally {
                snapshot.rollback();
            }
        }
        softly.assertAll();
        try (Connection reader = DatabaseRoles.application()) {
            // The operations' own postings: a settlement batch's entry is the external side, and reconciliation's own
            // (a parking's recon-suspense:, a resolution's) are proven through their items - copied by nothing.
            ClearingLineCopies.assertEveryClearingLineIsCopied(reader, when, "SELECT id FROM ledger.journal_entry WHERE id"
                    + " NOT IN (SELECT journal_entry_id FROM settlement.batch WHERE journal_entry_id IS NOT NULL)"
                    + " AND idempotency_scope NOT LIKE 'ledger.post:recon-%'");
        }
    }

    // ================================================================== the world

    record Instance(String name, int port, FxCoverDispatch dispatch, Runnable quoteExpiry,
            OutboundCreditResolution resolution, OutboundReturnWorker worker) {}

    record Mover(Instance instance, FxTestClient client, FxTestClient.Customer customer, UUID product,
            String usBeneficiary, String jpBeneficiary) {}

    record Paid(Mover mover, UUID quote, UUID payment, UUID credit, String reference, String providerReference) {}

    private FxTestClient client() {
        return new FxTestClient(port);
    }

    private void prepareTheWorld() throws Exception {
        FxTestPolicy.ensure(administration, policyStore, FxTestPolicy.V1);
        FxTestClient.freshReferences();
        if (count("SELECT count(*) FROM crossborder.corridor_policy_version WHERE status = 'ACTIVE'") == 0) {
            activateCorridorPolicy();
        }
        activateCorridorRuleSet();
        FxCoverFixtures.ensureFxSourceActive(ruleSetAdministration, ruleSets, dataSource);
        awaitRoutingVersionInForce();
    }

    private static void startInstanceB() throws Exception {
        Clock behind = ServerSkewedClock.of(SKEW.negated());
        List<String> arguments = new ArrayList<>(List.of("--server.port=0"));
        PROPERTIES.forEach((name, value) -> arguments.add("--" + name + "=" + value));
        instanceB = new SpringApplicationBuilder(com.finapp.app.FinappApplication.class)
                .initializers(context -> {
                    GenericApplicationContext generic = (GenericApplicationContext) context;
                    generic.registerBean("instanceBClock", Clock.class, () -> behind, definition -> definition.setPrimary(true));
                    generic.registerBean("instanceBScreeningProvider", CounterpartyScreeningProvider.class,
                            InstanceA::clearProvider, definition -> definition.setPrimary(true));
                })
                .run(arguments.toArray(String[]::new));
    }

    /** A mover on {@code instance}: a verified customer, a wallet funded in four currencies, two beneficiaries. */
    private Mover mover(Instance instance) throws Exception {
        FxTestClient on = new FxTestClient(instance.port());
        FxTestClient.Customer customer = client().verifiedCustomer();
        UUID product = client().openWallet(customer, "EUR");
        for (String currency : CURRENCIES) {
            if (!currency.equals("EUR")) {
                client().addCurrency(customer, product, currency);
            }
            fund(postings, product, money(currency.equals("JPY") ? "20000000" : "200000.00", currency));
        }
        Mover partial = new Mover(instance, on, customer, product, null, null);
        return new Mover(instance, on, customer, product, beneficiary(partial, "US", "USD", "Payee " + letters(8)),
                beneficiary(partial, "JP", "JPY", "Payee " + letters(8)));
    }

    /** Registered over instance A, whose screening provider is the clear double (instance B's is the same). */
    private String beneficiary(Mover mover, String country, String currency, String name) throws Exception {
        String grant = CORRIDOR.issueGrant(new SimulatedCorridorEngine.Beneficiary(country, currency, "individual", "match"));
        HttpResponse<String> registered = client().post(BENEFICIARIES, "{\"country\":\"" + country + "\",\"currency\":\""
                + currency + "\",\"grant\":\"" + grant + "\",\"name\":\"" + name + "\",\"nickname\":\"Payee\","
                + "\"entityType\":\"INDIVIDUAL\"}", mover.customer().token(), FxTestClient.key());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        assertThat(registered.body()).doesNotContain(name);
        return field(registered.body(), "id");
    }

    private String offer(Mover mover, boolean toJapan) throws Exception {
        HttpResponse<String> offered = mover.client().post(QUOTES, "{\"beneficiaryId\":\""
                + (toJapan ? mover.jpBeneficiary() : mover.usBeneficiary()) + "\",\"sourceCurrency\":\"EUR\","
                + "\"fixedSide\":\"FIXED_SOURCE\",\"amount\":\"40.00\"}", mover.customer().token(), FxTestClient.key());
        assertThat(offered.statusCode()).as(offered.body()).isEqualTo(201);
        return field(offered.body(), "id");
    }

    /** A choreographed payment over the mover's own instance; {@code arm} runs just before the POST. */
    private Paid pay(Mover mover, boolean toJapan, Runnable arm) throws Exception {
        String quote = offer(mover, toJapan);
        if (arm != null) {
            arm.run();
        }
        HttpResponse<String> paid = mover.client().post(PAYMENTS, "{\"quoteId\":\"" + quote + "\"}", mover.customer().token(),
                FxTestClient.key());
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(202);
        return paidOf(mover, UUID.fromString(field(paid.body(), "paymentId")));
    }

    private static Paid paidOf(Mover mover, UUID payment) throws Exception {
        UUID credit = UUID.fromString(scalar("SELECT id::text FROM payments.outbound_credit WHERE subject_id = ?", payment));
        return new Paid(mover, UUID.fromString(scalar("SELECT quote_id::text FROM crossborder.payment WHERE id = ?", payment)),
                payment, credit, scalar("SELECT end_to_end_reference FROM payments.outbound_credit WHERE id = ?", credit),
                scalar("SELECT coalesce(provider_reference, '') FROM payments.outbound_credit WHERE id = ?", credit));
    }

    /** A payment accepted by the provider and its completion applied - IN_TRANSIT, the provider's reference known. */
    private Paid completed(Mover mover, boolean toJapan) throws Exception {
        Paid sent = pay(mover, toJapan, null);
        resolve(sent);
        CORRIDOR.accept(sent.reference());
        resolve(sent);
        assertThat(paymentStatus(sent)).isEqualTo("IN_TRANSIT");
        return paidOf(mover, sent.payment());
    }

    private void resolve(Paid paid) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            resolution.resolve(new EndToEndReference(paid.reference()));
        }
    }

    private static void assertPayment(Paid paid, String status, String creditFailure) throws Exception {
        assertThat(paymentStatus(paid)).isEqualTo(status);
        assertThat(scalar("SELECT status || ':' || coalesce(failure_reason, '-') FROM payments.outbound_credit WHERE id = ?",
                paid.credit())).isEqualTo("FAILED:" + creditFailure);
        assertThat(scalar("SELECT h.status FROM ledger.hold h JOIN payments.outbound_credit c ON c.hold_id = h.id"
                + " WHERE c.id = ?", paid.credit())).as("the hold released").isEqualTo("RELEASED");
    }

    private static String paymentStatus(Paid paid) throws Exception {
        return scalar("SELECT status FROM crossborder.payment WHERE id = ?", paid.payment());
    }

    private static String creditStatus(Paid paid) throws Exception {
        return scalar("SELECT status FROM payments.outbound_credit WHERE id = ?", paid.credit());
    }

    /** Ages a credit's latest permit - the owner's act, the machine's trigger suspended for it. */
    private static void agePermit(UUID credit, Duration by) throws Exception {
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try (Statement ddl = owner.createStatement();
                    PreparedStatement age = owner.prepareStatement("UPDATE payments.outbound_credit SET last_dispatched_at ="
                            + " last_dispatched_at - ? * interval '1 second' WHERE id = ?")) {
                ddl.execute("ALTER TABLE payments.outbound_credit DISABLE TRIGGER outbound_credit_machine_is_legal");
                age.setLong(1, by.toSeconds());
                age.setObject(2, credit);
                assertThat(age.executeUpdate()).isEqualTo(1);
                ddl.execute("ALTER TABLE payments.outbound_credit ENABLE TRIGGER outbound_credit_machine_is_legal");
            }
            owner.commit();
        }
    }

    /** A fault injected beneath {@code table}'s {@code operation} where {@code condition} holds; closing lifts it. */
    private static AutoCloseable refuse(String table, String operation, String condition) throws Exception {
        String name = "storm_fault_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String schema = table.substring(0, table.indexOf('.'));
        try (Connection owner = DatabaseRoles.migrator(); Statement ddl = owner.createStatement()) {
            ddl.execute("CREATE FUNCTION " + schema + "." + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                    + " IF " + condition + " THEN RAISE EXCEPTION 'injected fault'; END IF; RETURN NEW; END $$");
            ddl.execute("CREATE TRIGGER " + name + " BEFORE " + operation + " ON " + table + " FOR EACH ROW EXECUTE FUNCTION "
                    + schema + "." + name + "()");
        }
        return () -> {
            try (Connection owner = DatabaseRoles.migrator(); Statement ddl = owner.createStatement()) {
                ddl.execute("DROP TRIGGER " + name + " ON " + table);
                ddl.execute("DROP FUNCTION " + schema + "." + name + "()");
            }
        };
    }

    /** What the corridor engine answers an inquiry for {@code reference} - the diagnostic's provider side. */
    private static String providerAnswer(String reference) {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder().uri(URI.create(CORRIDOR.baseUrl() + "/corridor/credits/" + reference))
                    .GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception failure) {
            return "unreadable: " + failure;
        }
    }

    private void tally(String outcome) {
        outcomes.computeIfAbsent(outcome, ignored -> new AtomicInteger()).incrementAndGet();
    }

    private static String smallAmount(String currency) {
        return currency.equals("JPY") ? Integer.toString(1_000 + RANDOMNESS.nextInt(7_000))
                : (10 + RANDOMNESS.nextInt(50)) + "." + String.format("%02d", RANDOMNESS.nextInt(100));
    }

    private static HttpResponse<String> signedPost(int port, String path, String body, byte[] key) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header(WebhookSignature.TIMESTAMP_HEADER, timestamp)
                .header(WebhookSignature.SIGNATURE_HEADER,
                        new WebhookSignature(key, Duration.ofMinutes(5), Clock.systemUTC()).sign(timestamp, bytes))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private UUID received(String source, String content) throws Exception {
        FileReception.Result result;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)))) {
            result = settlementTransactionRunner.inTransaction(uow -> reception.receive(uow,
                    new FileReception.Delivery(source, com.finapp.settlement.DeliveryChannel.PULL,
                            content.getBytes(StandardCharsets.UTF_8), Optional.empty(), Actor.SYSTEM,
                            com.finapp.settlement.SettlementAuditAction.SETTLEMENT_FILE_UPLOADED,
                            CorrelationContext.current().orElseThrow())));
        }
        assertThat(result).as("%s received", source).isInstanceOf(FileReception.Result.New.class);
        return ((FileReception.Result.New) result).fileId();
    }

    private UUID acceptedBatch(String source, String content) throws Exception {
        UUID fileId = received(source, content);
        parsing.sweep();
        acceptance.sweep();
        assertThat(scalar("SELECT status || ':' || coalesce(rejection_code, '-') FROM settlement.file WHERE id = ?", fileId))
                .as(content).isEqualTo("ACCEPTED:-");
        return UUID.fromString(scalar("SELECT id::text FROM settlement.batch WHERE file_id = ?", fileId));
    }

    private void matchUntilQuiet() {
        for (int i = 0; i < 4; i++) {
            matching.sweep();
        }
    }

    private static String corridorReport(String currency, List<String> lines, String net) {
        return "H,SIM_CORRIDOR_CSV,1,XB-" + marker() + "," + currency + "," + today() + "\n"
                + String.join("\n", lines) + "\n"
                + "T," + lines.size() + "," + net + ",XBA-" + (10_000 + RANDOMNESS.nextInt(80_000)) + "\n";
    }

    /** The bank account's next statement in {@code currency}, at that currency's scale. */
    private static String nextStatement(String currency, String date, String side, long minor, String remittance)
            throws Exception {
        int scale = CurrencyCode.of(currency).minorUnits();
        long sequence = 0;
        long opening = 0;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(
                        "SELECT b.statement_sequence, b.closing_minor FROM settlement.batch b JOIN settlement.source s"
                                + " ON s.id = b.source_id WHERE s.code = ? AND b.currency = ? AND b.statement_sequence"
                                + " IS NOT NULL AND b.status NOT IN ('REJECTED', 'REPUDIATED')"
                                + " ORDER BY b.statement_sequence DESC LIMIT 1")) {
            read.setString(1, BANK_SOURCE);
            read.setString(2, currency);
            try (ResultSet row = read.executeQuery()) {
                if (row.next()) {
                    sequence = row.getLong(1);
                    opening = row.getLong(2);
                }
            }
        }
        long closing = "C".equals(side) ? opening + minor : opening - minor;
        return ":20:SB-XB-" + marker() + "\n"
                + ":25:SIMBANK-" + currency + "-01\n"
                + ":28C:" + (sequence + 1) + "\n"
                + ":60F:" + statementBalance(date, currency, opening, scale) + "\n"
                + ":61:" + date + "," + side + "," + amount(minor, scale) + "," + remittance + "\n"
                + ":62F:" + statementBalance(date, currency, closing, scale) + "\n";
    }

    private static String statementBalance(String date, String currency, long minor, int scale) {
        return (minor < 0 ? "D," : "C,") + date + "," + currency + "," + amount(Math.abs(minor), scale);
    }

    private static String amount(long minor, int scale) {
        return BigDecimal.valueOf(minor, scale).toPlainString();
    }

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private static String marker() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).replaceAll("[0-9]", "q");
    }

    private static String digits(int length) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < length; i++) {
            digits.append(RANDOMNESS.nextInt(10));
        }
        return digits.toString();
    }

    private static String letters(int length) {
        StringBuilder letters = new StringBuilder();
        for (int i = 0; i < length; i++) {
            letters.append((char) ('a' + RANDOMNESS.nextInt(26)));
        }
        return letters.toString();
    }

    private static long balance(Connection connection, String purpose, String currency) throws SQLException {
        return one(connection, "SELECT COALESCE(SUM(CASE l.direction WHEN 'DEBIT' THEN l.amount_minor ELSE -l.amount_minor"
                + " END), 0) FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                + " WHERE a.purpose = '" + purpose + "' AND l.currency = '" + currency + "'");
    }

    private static long one(Connection connection, String sql) throws SQLException {
        try (Statement read = connection.createStatement(); ResultSet row = read.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static List<String[]> rows(String sql) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (Connection app = DatabaseRoles.application(); Statement read = app.createStatement();
                ResultSet row = read.executeQuery(sql)) {
            int columns = row.getMetaData().getColumnCount();
            while (row.next()) {
                String[] values = new String[columns];
                for (int i = 0; i < columns; i++) {
                    values[i] = row.getString(i + 1);
                }
                rows.add(values);
            }
        }
        return rows;
    }

    private static List<String> rows1(String sql) throws Exception {
        return rows(sql).stream().map(row -> row[0]).toList();
    }

    private static List<String> tablesHolding(String needle) throws SQLException {
        List<String> holders = new ArrayList<>();
        try (Connection app = DatabaseRoles.application()) {
            List<String> tables = new ArrayList<>();
            try (PreparedStatement list = app.prepareStatement(
                            "SELECT table_schema || '.' || table_name FROM information_schema.tables"
                                    + " WHERE table_type = 'BASE TABLE' AND table_schema NOT IN ('pg_catalog', 'information_schema')"
                                    + " AND has_table_privilege(table_schema || '.' || table_name, 'SELECT')");
                    ResultSet rows = list.executeQuery()) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                }
            }
            for (String table : tables) {
                try (PreparedStatement scan = app.prepareStatement("SELECT count(*) FROM " + table + " t WHERE t::text LIKE ?")) {
                    scan.setString(1, "%" + needle + "%");
                    try (ResultSet found = scan.executeQuery()) {
                        found.next();
                        if (found.getLong(1) > 0) {
                            holders.add(table);
                        }
                    }
                }
            }
        }
        return holders;
    }

    private void activateCorridorRuleSet() throws Exception {
        if (count("SELECT count(*) FROM reconciliation.rule_set WHERE source_id = ? AND status = 'ACTIVE'",
                CorridorRuleSetV1.SOURCE) > 0) {
            return;
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID ruleSet = ruleSetAdministration.propose(app, CorridorRuleSetV1.proposal("The corridor source's first version"),
                    new Actor("op-corridor-controller-a", ActorType.EMPLOYEE), Instant.now(),
                    CorrelationId.generate(FxTestClient.IDS)).ruleSetId();
            ruleSetAdministration.approve(app, ruleSet, new Actor("op-corridor-controller-b", ActorType.EMPLOYEE),
                    "Reviewed against O7", Instant.now(), CorrelationId.generate(FxTestClient.IDS));
            app.commit();
        }
    }

    private static void awaitRoutingVersionInForce() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        while (count("SELECT count(*) FROM payments.routing_policy_version WHERE version = 5 AND effective_from <= ?",
                java.sql.Timestamp.from(Instant.now())) == 0) {
            assertThat(System.nanoTime()).as("routing version 5 is not yet in force by the host's clock").isLessThan(deadline);
            Thread.sleep(500);
        }
    }

    private void activateCorridorPolicy() throws Exception {
        String first = sessionWith(RoleName.FX_CONTROLLER);
        String second = sessionWith(RoleName.FX_CONTROLLER);
        HttpResponse<String> proposed = client().post(POLICIES, CorridorPolicyV1.json("The storm's corridors"), first,
                FxTestClient.key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        HttpResponse<String> approved = client().post(POLICIES + "/" + field(proposed.body(), "id") + "/approval",
                "{\"reason\":\"checked\"}", second, null);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
    }

    private String sessionWith(RoleName role) throws Exception {
        String login = "storm." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(client().post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada"
                        + " Lovelace\",\"password\":\"" + FxTestClient.PASSWORD + "\"}", null, FxTestClient.key()).statusCode())
                .isEqualTo(201);
        UUID identity = UUID.fromString(scalar("SELECT id::text FROM identity.identity WHERE login_identifier = ?", login));
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        HttpResponse<String> session = client().post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + FxTestClient.PASSWORD + "\"}", null,
                FxTestClient.key());
        return field(session.body(), "sessionToken");
    }

    /** Instance A's own beans: its clock five seconds ahead of the server, and the clear screening double. */
    @TestConfiguration
    static class InstanceA {

        @Bean
        @Primary
        Clock instanceAClock() throws SQLException {
            return ServerSkewedClock.of(SKEW);
        }

        @Bean
        @Primary
        CounterpartyScreeningProvider stormScreeningProvider() {
            return clearProvider();
        }

        static CounterpartyScreeningProvider clearProvider() {
            byte[] evidence = "{\"status\":\"scripted\"}".getBytes(StandardCharsets.UTF_8);
            return (screening, subject) -> CounterpartyScreeningProvider.Answer.of(Verdict.CLEAR, evidence);
        }
    }
}
