package com.finapp.app.risk;

import static com.finapp.app.risk.RiskStormWorld.RANDOMNESS;
import static com.finapp.app.risk.RiskStormWorld.count;
import static com.finapp.app.risk.RiskStormWorld.decimal;
import static com.finapp.app.risk.RiskStormWorld.judged;
import static com.finapp.app.risk.RiskStormWorld.key;
import static com.finapp.app.risk.RiskStormWorld.refused;
import static com.finapp.app.risk.RiskStormWorld.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.database.DateShifted;
import com.finapp.app.database.DateShiftedZone;
import com.finapp.app.fx.RiskStormCrossBorder;
import com.finapp.app.risk.RiskStormWorld.Answer;
import com.finapp.app.risk.RiskStormWorld.Audience;
import com.finapp.app.risk.RiskStormWorld.Customer;
import com.finapp.app.risk.RiskStormWorld.Door;
import com.finapp.app.risk.RiskStormWorld.Merchant;
import com.finapp.app.risk.RiskStormWorld.Operator;
import com.finapp.app.risk.RiskStormWorld.Tally;
import com.finapp.app.telemetry.RiskStormRules;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.identity.Authorization;
import com.finapp.identity.RoleName;
import com.finapp.ledger.PostingService;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicy;
import com.finapp.risk.testing.RiskPolicyFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * THE RISK STORM (`P13-TST-001`; {@code PHASE_13_PLAN.md} sections 7, 13.2, 13.3; every {@code Phase: 13} invariant by
 * name; the phase's ten-instance answer): risk judged, watched, investigated and acted on beside every money-moving
 * domain it judges, on two application instances whose clocks disagree by ten seconds - one killed mid-load and
 * restarted - with every census exact in one {@code REPEATABLE READ} snapshot at every round's rest. The BNPL storm's shape
 * (`P12-TST-001`) and its lessons: every census at every round (I2), every named lock wait counted as the act's own
 * backend (M3, C17), the coverage forced by the seed and counted from rows (A6-06), the dates shifted (A6-04).
 *
 * <h2>Two application instances whose clocks disagree by ten seconds</h2>
 *
 * <p>Instance A is this suite's own context, its {@code Clock} five seconds AHEAD of the database server; instance B is a
 * second {@link com.finapp.app.FinappApplication} over the same database in this JVM, five seconds BEHIND. Both run
 * risk's monitoring schedule (the intake, rule, profile and case legs) and its action schedule (the action, filing and
 * rescreening legs), payments' withdrawal resolution (the held withdrawal's expiry) and card sweeps, as their real
 * lifecycles. Correctness lives in the database.
 *
 * <h2>The storm</h2>
 *
 * <ol>
 *   <li><strong>No policy</strong>: a measured window before any activation - every judged movement the production
 *       default, {@code ALLOW(NO_POLICY_ACTIVE)} (scenario 9's first half); then every operation's v1 and the monitoring
 *       version activated under four eyes across the instances, the proposer's own activation refused.
 *   <li><strong>Load</strong>, in rounds: transferers, withdrawers, shoppers (card and wallet, at merchants shared across
 *       captures and refunds), cross-border payers, investigators, officers and the relay (events delivered one to three
 *       times, delayed, reordered) on both instances; the fail-safe forced by a timeout in a measured window; a policy
 *       activation mid-storm for every operation and the monitoring version; B killed and restarted; a reprocessing
 *       from an earlier watermark; every census at every rest, the rules of {@code risk.yml} read beside them.
 *   <li><strong>The catalogue</strong> ({@link RiskStormCatalogue}): the fifteen scenarios of section 13.2 by name, the
 *       named lock waits, the held withdrawals' races, then the crash points - each followed by the census.
 *   <li><strong>The latency budget</strong> ({@link RiskStormLatencyReport}): T-eval measured per operation with the
 *       monitoring sweep running beside it and stopped.
 * </ol>
 */
@DateShifted
@Tag("database")
@Tag("own-container")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RiskStormTest.InstanceA.class)
@DisplayName("the risk storm (P13-TST-001)")
class RiskStormTest {

    static final Duration SKEW = Duration.ofSeconds(5);
    private static final int POOL = 20;
    private static final int MERCHANTS = 3;
    private static final int ROUNDS = 6;
    private static final Duration BURST = Duration.ofSeconds(15);
    static final Duration REST_BOUND = Duration.ofSeconds(180);
    /** The settle horizon the storm runs with: 3x the instances' skew window (DESIGN-019 section 0; owner default 60 s). */
    static final Duration HORIZON = Duration.ofSeconds(30);
    static final long FUNDING = 200_000_00L;
    static final String B_APPLICATION_NAME = "finapp-risk-storm-b";
    static final String A_APPLICATION_NAME = "finapp-risk-storm-a";

    /** A development run's scene list ({@code FINAPP_RISK_STORM_SCENES}) - a run with it fails at its end, by design. */
    static final String SCENES = System.getenv("FINAPP_RISK_STORM_SCENES");

    static boolean wanted(String scene) {
        return SCENES == null || SCENES.isBlank() || List.of(SCENES.split(",")).contains(scene);
    }

    static boolean partial() {
        return SCENES != null && !SCENES.isBlank();
    }

    private static SimulatedProvider provider;
    private static RiskStormCrossBorder crossBorder;
    private static final Map<String, String> PROPERTIES = new LinkedHashMap<>();
    private static volatile ConfigurableApplicationContext instanceB;

    @LocalServerPort private int port;
    @Autowired private ApplicationContext context;
    @Autowired private Authorization authorization;
    @Autowired private PostingService postings;
    @Autowired private PricingPolicyAdministration pricingAdministration;
    @Autowired private PricingPolicyStore pricingStore;

    final Tally tally = new Tally();
    final List<RiskStormWorld.SubjectAnswer> subjectAnswers = new CopyOnWriteArrayList<>();
    RiskStormWorld world;
    RiskStormCensus census;
    RiskStormRelay relay;
    RiskStormRules rules;
    volatile Instance a;
    volatile Instance b;
    final AtomicInteger restarts = new AtomicInteger();
    volatile long stormBegan;
    /** The watermark when the first MONITORING version committed - the history after it was judged when first claimed. */
    volatile long judgedSince = -1;
    long deadlocksBefore;

    /** The coverage ledger: what each scene proved, counted from rows - never from the storm's intentions. */
    final Map<String, Long> coverage = new ConcurrentHashMap<>();
    final List<Customer> pool = new CopyOnWriteArrayList<>();
    final List<Merchant> merchants = new CopyOnWriteArrayList<>();
    /** Customers no mover touches: a scene owns them. */
    final Set<UUID> reserved = ConcurrentHashMap.newKeySet();
    /** Customers whose PARTY lock the fail-safe window holds - no mover is judged for them outside the window. */
    final Set<UUID> blocked = ConcurrentHashMap.newKeySet();
    /** Every party a scene forced unavailable (a held subject lock, a crash point) - the only fail-safes admitted. */
    final Set<String> forced = ConcurrentHashMap.newKeySet();
    /** Per customer party, a beneficiary per corridor (country-currency) - cross-border payees. */
    final Map<UUID, Map<String, String>> beneficiaries = new ConcurrentHashMap<>();
    Operator[] officers;
    Operator[] analysts;
    Operator[] amlOfficers;
    Operator refunds;
    Operator merchantAdministrator;
    Operator[] fxControllers;

    /** Per operation, when its first activation answered (nanos) - the "neither" census's line. */
    final Map<String, Long> firstActivation = new ConcurrentHashMap<>();
    /** Per operation, the command references the storm started after that operation's first activation answered. */
    final Map<String, Set<String>> startedAfter = new ConcurrentHashMap<>();
    /** The census readings at rest, their labels in order. */
    final List<String> readings = new CopyOnWriteArrayList<>();
    /** Rules found firing at rest, by reading. */
    final Map<String, Set<String>> firingAtRest = new LinkedHashMap<>();
    /** The rules a reading deliberately broke (each must fire at that rest - the positive control). */
    final Map<String, Set<String>> expectedFiring = new ConcurrentHashMap<>();
    /** The scrape of each instance at the start of the current round - the rules' "before". */
    private final Map<String, String> roundStartScrape = new ConcurrentHashMap<>();
    /** The killed B's registry, kept for the whole run's meter census. */
    volatile MeterRegistry killedB;
    /** Scenario 12's party - its AML case is the filing's. */
    volatile Customer structurer;

    // ================================================================== configuration

    @DynamicPropertySource
    static void storm(DynamicPropertyRegistry registry) throws Exception {
        if (provider == null) {
            provider = SimulatedProvider.start();
            crossBorder = RiskStormCrossBorder.start();
        }
        // Risk's two schedules as real lifecycles on both instances, storm-short polls, leases and permits.
        PROPERTIES.put("finapp.risk.monitoring.sweeper.enabled", "true");
        PROPERTIES.put("finapp.risk.monitoring.poll", "PT0.5S");
        PROPERTIES.put("finapp.risk.monitoring.idle", "PT1S");
        PROPERTIES.put("finapp.risk.monitoring.horizon", "PT" + HORIZON.toSeconds() + "S");
        PROPERTIES.put("finapp.risk.action.sweeper.enabled", "true");
        PROPERTIES.put("finapp.risk.action.poll", "PT0.5S");
        PROPERTIES.put("finapp.risk.action.lease", "PT4S");
        PROPERTIES.put("finapp.risk.filing.lease", "PT4S");
        PROPERTIES.put("finapp.risk.filing.inquiry-interval", "PT3S");
        PROPERTIES.put("finapp.risk.proofs.refresh", "PT5M");
        // Payments: the card PSP, tokenisation, the instant scheme - one simulated provider; the card and withdrawal
        // resolutions (the held withdrawal's expiry is a leg of the latter).
        PROPERTIES.put("finapp.payments.provider.url", provider.baseUrl());
        PROPERTIES.put("finapp.payments.provider.timeout", "PT2S");
        PROPERTIES.put("finapp.paymentmethods.tokenisation.url", provider.baseUrl());
        PROPERTIES.put("finapp.paymentmethods.tokenisation.timeout", "PT2S");
        PROPERTIES.put("finapp.payments.instant.url", provider.baseUrl());
        PROPERTIES.put("finapp.payments.instant.timeout", "PT2S");
        PROPERTIES.put("finapp.payments.sweeper.enabled", "true");
        PROPERTIES.put("finapp.payments.sweeper.poll-interval", "PT1S");
        PROPERTIES.put("finapp.payments.withdrawal.sweeper.enabled", "true");
        PROPERTIES.put("finapp.payments.withdrawal.sweeper.poll", "PT0.5S");
        // Cross-border: the FX engine and the corridor engine; a beneficiary is payable once kyc's counterparty screening
        // clears it - the simulated provider answers every counterparty screening clear.
        PROPERTIES.put("finapp.kyc.provider.url", provider.baseUrl());
        PROPERTIES.put("finapp.fx.provider.url", crossBorder.fxUrl());
        PROPERTIES.put("finapp.corridor.provider.url", crossBorder.corridorUrl());
        // No broker in the database tier: the storm's relay owns delivery (RiskStormRelay), the instances' relays off.
        PROPERTIES.put("finapp.outbox.relay.enabled", "false");
        PROPERTIES.put("spring.datasource.hikari.data-source-properties.ApplicationName", A_APPLICATION_NAME);
        PROPERTIES.forEach((name, value) -> registry.add(name, () -> value));
    }

    @AfterAll
    static void stop() {
        if (instanceB != null) {
            instanceB.close();
        }
        if (crossBorder != null) {
            crossBorder.close();
        }
        if (provider != null) {
            provider.close();
        }
    }

    // ================================================================== the storm

    @Test
    @DisplayName("two instances skewed +/-5 s, every judged operation at once beside its money, the fifteen scenarios,"
            + " the policy activated mid-storm, the fail-safe forced, faults, crash points, killed backends and a restart:"
            + " every census exact at every round, every named lock wait counted, 0 deadlocks, the latency measured")
    void theStormLeavesEveryJudgementProvable() throws Exception {
        long began = System.nanoTime();
        stormBegan = began;
        System.out.printf("P13-TST-001 SEED: %d - replay with FINAPP_STORM_SEED=%d (date-shifted to %s; %d cores)%n",
                RiskStormWorld.SEED, RiskStormWorld.SEED, DateShiftedZone.zone(),
                Runtime.getRuntime().availableProcessors());
        a = instance("A", port, context);
        world = new RiskStormWorld(a.door(), authorization, postings, provider, tally);
        relay = new RiskStormRelay(this::relayTargets);
        census = new RiskStormCensus(context, () -> new RiskStormCensus.Knowledge() {
            @Override
            public Map<String, Set<String>> startedAfterActivation() {
                return startedAfter;
            }

            @Override
            public Set<String> delivered() {
                return relay.delivered;
            }

            @Override
            public Set<String> forced() {
                return forced;
            }
        });
        rules = RiskStormRules.load();
        deadlocksBefore = RiskStormLocks.deadlocks();
        prepareTheWorld();
        RiskStormCatalogue catalogue = new RiskStormCatalogue(this);
        RiskStormLatencyReport latency = new RiskStormLatencyReport(this);
        // No version is active yet: the platform judges (risk_judging) with none - the production default's alert.
        breaks("before the storm", "RiskPolicyNoneActive");
        breaks("scenario 9 - no policy", "RiskPolicyNoneActive");
        restAndCensus("before the storm");
        if (wanted("noPolicy")) {
            catalogue.noPolicyWindow();
        }
        activateEveryOperation("v1", RiskStormTest::v1);
        // Every entry past this watermark is claimed under a MONITORING version: judged history (scenario 8's floor).
        judgedSince = Long.parseLong(RiskStormWorld.scalar("SELECT last_sequence FROM risk.monitoring_watermark WHERE"
                + " source = 'ledger.journal'"));
        restAndCensus("v1 in force");
        if (wanted("load")) {
            load(catalogue);
        }
        catalogue.run();
        catalogue.crashPoints();
        if (wanted("latency")) {
            latency.measure();
        }
        restAndCensus("drained");
        SoftAssertions softly = new SoftAssertions();
        catalogue.atTheEnd(softly);
        theTippingOffCensus(softly);
        theNeedle(softly);
        long deadlocks = RiskStormLocks.deadlocks() - deadlocksBefore;
        System.out.println("P13-TST-001 DEADLOCKS: " + deadlocks + " (pg_stat_database, the storm's whole run)");
        softly.assertThat(deadlocks).as("0 deadlocks (pg_stat_database)").isZero();
        softly.assertThat(tally.unexplained).as("unexplained failures - every answer in its act's closed set")
                .isEmpty();
        summary(began, latency);
        softly.assertAll();
        assertThat(partial()).as("a partial storm (FINAPP_RISK_STORM_SCENES) proves nothing - a development run only")
                .isFalse();
    }

    // ================================================================== the world

    private void prepareTheWorld() throws Exception {
        world.stubTheProviders();
        startInstanceB();
        RiskStormCrossBorder.pricing(pricingAdministration, pricingStore);
        officers = new Operator[] {world.operator(RoleName.RISK_OFFICER), world.operator(RoleName.RISK_OFFICER),
            world.operator(RoleName.RISK_OFFICER)};
        analysts = new Operator[] {world.operator(RoleName.RISK_ANALYST), world.operator(RoleName.RISK_ANALYST),
            world.operator(RoleName.RISK_ANALYST), world.operator(RoleName.RISK_ANALYST)};
        amlOfficers = new Operator[] {world.operator(RoleName.AML_OFFICER), world.operator(RoleName.AML_OFFICER),
            world.operator(RoleName.AML_OFFICER)};
        refunds = world.operator(RoleName.LEDGER_OPERATOR);
        merchantAdministrator = world.operator(RoleName.MERCHANT_ADMINISTRATOR);
        fxControllers = new Operator[] {world.operator(RoleName.FX_CONTROLLER), world.operator(RoleName.FX_CONTROLLER)};
        activateTheCorridors();
        for (int i = 0; i < MERCHANTS; i++) {
            merchants.add(world.merchant(merchantAdministrator));
        }
        ExecutorService builders = Executors.newFixedThreadPool(5);
        try {
            List<Future<Customer>> made = new ArrayList<>();
            for (int i = 0; i < POOL; i++) {
                made.add(builders.submit(() -> world.customer(FUNDING)));
            }
            for (Future<Customer> customer : made) {
                pool.add(customer.get(5, TimeUnit.MINUTES));
            }
        } finally {
            builders.shutdown();
        }
        for (Customer customer : pool) {
            Map<String, String> payees = new ConcurrentHashMap<>();
            payees.put("US-USD", beneficiary(customer, "US", "USD"));
            beneficiaries.put(customer.party, payees);
        }
        System.out.printf("P13-TST-001 PREPARED: %d customers, %d merchants, %d operators (%d s)%n", pool.size(),
                merchants.size(), officers.length + analysts.length + amlOfficers.length + 4,
                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - stormBegan));
    }

    private void activateTheCorridors() throws Exception {
        if (count("SELECT count(*) FROM crossborder.corridor_policy_version WHERE status = 'ACTIVE'") > 0) {
            return;
        }
        Answer proposed = a.door().post(RiskStormCrossBorder.CORRIDOR_POLICIES,
                RiskStormCrossBorder.corridorPolicy("v1 for the risk storm"), fxControllers[0].token(), key(),
                Audience.OPERATOR);
        assertThat(proposed.status()).as(proposed.body()).isEqualTo(201);
        Answer approved = b.door().post(RiskStormCrossBorder.CORRIDOR_POLICIES + "/" + proposed.field("id")
                + "/approval", "{\"reason\":\"checked\"}", fxControllers[1].token(), null, Audience.OPERATOR);
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
    }

    /** A beneficiary of {@code customer}'s in {@code country}/{@code currency}, its payee check matching - its id. */
    String beneficiary(Customer customer, String country, String currency) throws Exception {
        String grant = crossBorder.grant(country, currency);
        Answer registered = a.door().post(RiskStormCrossBorder.BENEFICIARIES, "{\"country\":\"" + country
                + "\",\"currency\":\"" + currency + "\",\"grant\":\"" + grant + "\",\"name\":\"Clear Person\","
                + "\"nickname\":\"Payee\",\"entityType\":\"INDIVIDUAL\"}", customer.mfaToken, key(), Audience.SUBJECT);
        assertThat(registered.status()).as(registered.body()).isEqualTo(201);
        String id = registered.field("id");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!"ACTIVE".equals(scalar("SELECT status FROM crossborder.beneficiary WHERE id = ?::uuid", id))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("a beneficiary never became payable: " + RiskStormWorld.rows("SELECT b.status,"
                        + " s.* FROM crossborder.beneficiary b LEFT JOIN kyc.counterparty_screening s ON s.id ="
                        + " b.screening_id WHERE b.id = ?::uuid", UUID.fromString(id)).stream()
                        .map(java.util.Arrays::toString).toList());
            }
            Thread.sleep(200);
        }
        return id;
    }

    // ================================================================== the policies

    static RiskPolicy v1(RiskOperation operation) {
        return RiskPolicyFixtures.v1(operation);
    }

    /**
     * Every operation's version activated under four eyes across the instances: proposed on A by one officer, the
     * proposer's own activation refused and recorded FAILED (scenario 15's policy third), then activated on B by a second
     * officer from a MULTI_FACTOR session. Returns the versions' ids by operation.
     */
    Map<RiskOperation, String> activateEveryOperation(String label, java.util.function.Function<RiskOperation, RiskPolicy>
            version) throws Exception {
        Map<RiskOperation, String> activated = new EnumMap<>(RiskOperation.class);
        for (RiskOperation operation : RiskPolicyFixtures.operations()) {
            activated.put(operation, activate(operation, version.apply(operation), label));
        }
        return activated;
    }

    /** One version proposed on A and activated on B (or the reverse at random), its proposer refused first. */
    String activate(RiskOperation operation, RiskPolicy policy, String label) throws Exception {
        Instance proposing = RANDOMNESS.nextBoolean() ? a : b;
        Operator proposer = officers[RANDOMNESS.nextInt(officers.length)];
        Operator activator = officers[(List.of(officers).indexOf(proposer) + 1) % officers.length];
        Answer proposed = proposing.door().post(RiskStormWorld.POLICIES, RiskStormWorld.proposal(policy,
                "the risk storm's " + label + " for " + operation), proposer.token(), key(), Audience.OPERATOR);
        assertThat(proposed.status()).as(proposed.body()).isEqualTo(201);
        String id = proposed.field("id");
        Answer own = other(proposing).door().post(RiskStormWorld.POLICIES + "/" + id + "/activation",
                "{\"reason\":\"my own\"}", proposer.token(), key(), Audience.OPERATOR);
        refused(tally, "scenario 15: a policy activation by its proposer", own, "403 risk.SelfApprovalRefused");
        Answer stepUp = proposing.door().post(RiskStormWorld.POLICIES + "/" + id + "/activation",
                "{\"reason\":\"password only\"}", activator.passwordToken(), key(), Audience.OPERATOR);
        refused(tally, "a policy activation from a password session", stepUp, "403 identity.AssuranceRequired");
        String activationKey = key();
        Answer activated = other(proposing).door().post(RiskStormWorld.POLICIES + "/" + id + "/activation",
                "{\"reason\":\"the risk storm's four eyes\"}", activator.token(), activationKey, Audience.OPERATOR);
        assertThat(activated.status()).as(activated.body()).isEqualTo(200);
        firstActivation.putIfAbsent(operation.name(), System.nanoTime());
        Answer replayed = proposing.door().post(RiskStormWorld.POLICIES + "/" + id + "/activation",
                "{\"reason\":\"the risk storm's four eyes\"}", activator.token(), activationKey, Audience.OPERATOR);
        judged(tally, "a policy activation replayed under its key", replayed);
        if (replayed.ok() && !replayed.body().equals(activated.body())) {
            tally.unexplained("a replayed activation key answered another receipt");
        }
        return id;
    }

    /** {@code operation}'s command {@code ref}, started at {@code startedNanos} - recorded when after its first activation. */
    void started(String operation, long startedNanos, String ref) {
        Long activation = firstActivation.get(operation);
        if (activation != null && startedNanos > activation && ref != null) {
            startedAfter.computeIfAbsent(operation, k -> ConcurrentHashMap.newKeySet()).add(ref);
        }
    }

    // ================================================================== 1. load

    private void load(RiskStormCatalogue catalogue) throws Exception {
        for (int round = 1; round <= ROUNDS; round++) {
            // The load's contention (two instances and the sweeps in one machine) may put more than 1% of a round's
            // evaluations past the 50 ms bucket - measured, not judged here: the latency phases are the budget's reading.
            tolerates("round " + round, "RiskEvaluationLatencyOverBudget");
            beginRound();
            burst(round, catalogue);
            restAndCensus("round " + round);
        }
    }

    private void burst(int round, RiskStormCatalogue catalogue) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService threads = Executors.newFixedThreadPool(24);
        List<Future<?>> running = new ArrayList<>();
        try {
            for (Instance on : List.of(a, b)) {
                for (int i = 0; i < 2; i++) {
                    running.add(threads.submit(() -> forever(stop, "transferer " + on.name(), () -> transferOnce(on))));
                }
                running.add(threads.submit(() -> forever(stop, "withdrawer " + on.name(), () -> withdrawOnce(on))));
                running.add(threads.submit(() -> forever(stop, "shopper " + on.name(), () -> shopOnce(on))));
                running.add(threads.submit(() -> forever(stop, "cross-border " + on.name(), () -> crossBorderOnce(on))));
                running.add(threads.submit(() -> forever(stop, "investigator " + on.name(),
                        () -> catalogue.investigateOnce(on))));
            }
            running.add(threads.submit(() -> forever(stop, "officer", catalogue::overrideOnce)));
            running.add(threads.submit(() -> forever(stop, "refunds", this::refundOnce)));
            running.add(threads.submit(() -> forever(stop, "relay", () -> {
                relay.pass(true);
                Thread.sleep(500);
            })));
            running.add(threads.submit(() -> forever(stop, "reference rates", () -> {
                RiskStormCrossBorder.references();
                Thread.sleep(5_000);
            })));
            switch (round) {
                case 2 -> running.add(threads.submit(() -> {
                    catalogue.failSafeWindow(BURST.minusSeconds(5));
                    return null;
                }));
                case 3 -> running.add(threads.submit(() -> {
                    Thread.sleep(4_000);
                    activateEveryOperation("v2", RiskStormCatalogue::v2);
                    coverage.merge("policies activated mid-load (v2, every operation and monitoring)", 6L, Long::sum);
                    return null;
                }));
                case 4 -> running.add(threads.submit(() -> {
                    Thread.sleep(5_000);
                    restartInstanceB();
                    return null;
                }));
                case 5 -> running.add(threads.submit(() -> {
                    Thread.sleep(4_000);
                    catalogue.reprocessMidLoad();
                    return null;
                }));
                default -> {
                    // a plain round
                }
            }
            long end = System.nanoTime() + BURST.toNanos();
            while (System.nanoTime() < end) {
                for (Future<?> task : running) {
                    if (task.isDone()) {
                        task.get();
                    }
                }
                Thread.sleep(500);
            }
        } finally {
            stop.set(true);
            threads.shutdown();
            assertThat(threads.awaitTermination(4, TimeUnit.MINUTES)).as("every actor stops").isTrue();
        }
        for (Future<?> task : running) {
            task.get();
        }
    }

    @FunctionalInterface
    interface Act {
        void run() throws Exception;
    }

    private static Void forever(AtomicBoolean stop, String who, Act act) {
        try {
            while (!stop.get()) {
                act.run();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception | AssertionError failure) {
            throw new IllegalStateException(who + " failed", failure);
        }
        return null;
    }

    /** A pool customer no scene owns and no fail-safe window holds. */
    Customer mover() {
        for (int attempt = 0; attempt < 40; attempt++) {
            Customer customer = pool.get(RANDOMNESS.nextInt(pool.size()));
            if (!reserved.contains(customer.party) && !blocked.contains(customer.party)) {
                return customer;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- the movers

    /**
     * A transfer between two pool wallets: mostly small from the MULTI_FACTOR session; sometimes EUR 600 from the
     * password session first (the door's step-up, nothing written) then the same key stepped up; sometimes EUR 10,000+
     * (the limit's DENY); sometimes the key replayed on the other instance.
     */
    void transferOnce(Instance on) throws Exception {
        Thread.sleep(100 + RANDOMNESS.nextInt(300));
        Customer payer = mover();
        Customer payee = mover();
        if (payer == null || payee == null || payer == payee) {
            return;
        }
        int dice = RANDOMNESS.nextInt(100);
        long amount = dice < 70 ? 100 + RANDOMNESS.nextInt(20_000) : dice < 85 ? 600_00
                : dice < 92 ? 1_000_00 + RANDOMNESS.nextInt(300_000) : 10_000_00 + RANDOMNESS.nextInt(100_000);
        String body = transferBody(payer.wallet, payee.wallet, amount);
        String transferKey = key();
        if (amount == 600_00 && RANDOMNESS.nextBoolean()) {
            Answer stepped = on.door().post(RiskStormWorld.TRANSFERS, body, payer.passwordToken, transferKey,
                    Audience.SUBJECT);
            judged(tally, "transfer from a password session", stepped, "403 identity.AssuranceRequired",
                    "409 api.IdempotencyInProgress");
        }
        long started = System.nanoTime();
        Answer posted = on.door().post(RiskStormWorld.TRANSFERS, body, payer.mfaToken, transferKey, Audience.SUBJECT);
        if (!judged(tally, "transfer", posted, "409 api.IdempotencyInProgress", "422 transfers.UnknownSource",
                "422 transfers.UnknownDestination")) {
            return;
        }
        if (posted.status() == 201) {
            String id = posted.field("id");
            started("TRANSFER", started, id);
            tally.count("transfer " + posted.field("status") + posted.optionalField("failureReason").map(r -> " " + r)
                    .orElse(""));
            if (dice % 10 == 0) {
                Answer replayed = other(on).door().post(RiskStormWorld.TRANSFERS, body, payer.mfaToken, transferKey,
                        Audience.SUBJECT);
                judged(tally, "transfer replayed", replayed, "409 api.IdempotencyInProgress");
                if (replayed.status() == 201 && !replayed.field("id").equals(id)) {
                    tally.unexplained("a replayed transfer key answered another transfer");
                }
            }
        }
    }

    static String transferBody(UUID source, UUID destination, long minor) {
        return "{\"sourceAccountId\":\"" + source + "\",\"destinationAccountId\":\"" + destination + "\",\"amount\":\""
                + decimal(minor) + "\",\"currency\":\"EUR\",\"reference\":\"storm\"}";
    }

    /** A withdrawal to the customer's bank account: mostly small; sometimes EUR 2,500+ (the HOLD, HELD with its hold). */
    void withdrawOnce(Instance on) throws Exception {
        Thread.sleep(200 + RANDOMNESS.nextInt(400));
        Customer customer = mover();
        if (customer == null) {
            return;
        }
        long amount = RANDOMNESS.nextInt(10) == 0 ? 2_500_00 + RANDOMNESS.nextInt(50_000) : 100 + RANDOMNESS.nextInt(50_000);
        String withdrawalKey = key();
        long started = System.nanoTime();
        Answer answered = on.door().post(RiskStormWorld.WITHDRAWALS, withdrawalBody(customer, amount),
                customer.mfaToken, withdrawalKey, Audience.SUBJECT);
        if (!judged(tally, "withdrawal", answered, "409 api.IdempotencyInProgress", "422 payments.WithdrawalUnfunded")) {
            return;
        }
        if (answered.ok()) {
            started("WITHDRAWAL", started, answered.field("id"));
            tally.count("withdrawal " + answered.field("status"));
            if (RANDOMNESS.nextInt(10) == 0) {
                Answer replayed = other(on).door().post(RiskStormWorld.WITHDRAWALS, withdrawalBody(customer, amount),
                        customer.mfaToken, withdrawalKey, Audience.SUBJECT);
                judged(tally, "withdrawal replayed", replayed, "409 api.IdempotencyInProgress");
                if (replayed.ok() && !replayed.field("id").equals(answered.field("id"))) {
                    tally.unexplained("a replayed withdrawal key answered another withdrawal");
                }
            }
        }
    }

    static String withdrawalBody(Customer customer, long minor) {
        return "{\"paymentMethodId\":\"" + customer.bank + "\",\"amount\":\"" + decimal(minor)
                + "\",\"currency\":\"EUR\"}";
    }

    /** A checkout at a shared merchant, by card or from the wallet; small mostly, sometimes challenged or denied. */
    void shopOnce(Instance on) throws Exception {
        Thread.sleep(200 + RANDOMNESS.nextInt(400));
        Customer customer = mover();
        if (customer == null) {
            return;
        }
        Merchant merchant = merchants.get(RANDOMNESS.nextInt(merchants.size()));
        int dice = RANDOMNESS.nextInt(100);
        long amount = dice < 85 ? 500 + RANDOMNESS.nextInt(20_000) : dice < 97 ? 600_00 : 10_000_00;
        boolean card = RANDOMNESS.nextInt(10) < 6;
        String token = checkoutSession(on, merchant, amount);
        if (token == null) {
            return;
        }
        long started = System.nanoTime();
        Answer confirmed = on.door().post(RiskStormWorld.CONFIRMATION, confirmation(token, card ? customer.card : null),
                customer.mfaToken, null, Audience.SUBJECT);
        if (!judged(tally, (card ? "card" : "wallet") + " confirmation", confirmed, "409 checkout.NotConfirmable",
                "422 payments.WalletPaymentUnfunded", "409 payments.NotConfirmable")) {
            return;
        }
        if (confirmed.ok()) {
            confirmed.optionalField("paymentIntentId").ifPresent(intent -> started(card ? "CARD_PAYMENT"
                    : "WALLET_PAYMENT", started, intent));
            tally.count((card ? "card" : "wallet") + " confirmation " + confirmed.optionalField("status").orElse("?"));
        }
    }

    /** A checkout session of {@code amount} opened by {@code merchant}'s key - its token. */
    String checkoutSession(Instance on, Merchant merchant, long amount) throws Exception {
        Answer created = on.door().post(RiskStormWorld.SESSIONS, "{\"amountMinor\":" + amount + ",\"currency\":\"EUR\","
                + "\"lineSummary\":\"storm goods\"}", merchant.apiKey(), key(), Audience.SUBJECT);
        if (!judged(tally, "checkout session", created)) {
            return null;
        }
        return created.field("sessionToken");
    }

    static String confirmation(String sessionToken, String card) {
        return card == null ? "{\"sessionToken\":\"" + sessionToken + "\",\"instrument\":\"WALLET\"}"
                : "{\"sessionToken\":\"" + sessionToken + "\",\"paymentMethodId\":\"" + card + "\"}";
    }

    /** A cross-border payment: a fixed-source EUR quote to the customer's US beneficiary, paid at once. */
    void crossBorderOnce(Instance on) throws Exception {
        Thread.sleep(400 + RANDOMNESS.nextInt(800));
        Customer customer = mover();
        if (customer == null) {
            return;
        }
        String beneficiary = beneficiaries.get(customer.party).get("US-USD");
        long amount = RANDOMNESS.nextInt(10) == 0 ? 600_00 : 1_000 + RANDOMNESS.nextInt(30_000);
        Answer quoted = on.door().post(RiskStormCrossBorder.QUOTES, "{\"beneficiaryId\":\"" + beneficiary
                + "\",\"sourceCurrency\":\"EUR\",\"fixedSide\":\"FIXED_SOURCE\",\"amount\":\"" + decimal(amount) + "\"}",
                customer.mfaToken, key(), Audience.SUBJECT);
        if (!judged(tally, "cross-border quote", quoted, "429 fx.TooManyOpenQuotes", "503 fx.RateUnavailable")) {
            return;
        }
        if (!quoted.ok()) {
            return;
        }
        String quote = quoted.field("id");
        long started = System.nanoTime();
        Answer paid = on.door().post(RiskStormCrossBorder.PAYMENTS, "{\"quoteId\":\"" + quote + "\"}",
                customer.mfaToken, key(), Audience.SUBJECT);
        if (judged(tally, "cross-border payment", paid, "422 crossborder.RiskRefused", "422 crossborder.LimitRefused",
                "409 api.IdempotencyInProgress", "422 payments.WithdrawalUnfunded", "409 crossborder.OfferNotFound")) {
            started("CROSS_BORDER", started, quote);
        }
    }

    /** An operator refunds a captured card payment at a shared merchant - the refund ratio's numerator. */
    void refundOnce() throws Exception {
        Thread.sleep(1_500 + RANDOMNESS.nextInt(1_500));
        String[] captured = RiskStormWorld.row("SELECT i.id::text, i.amount_minor FROM payments.payment_intent i JOIN"
                + " payments.payment_attempt t ON t.intent_id = i.id JOIN ledger.ledger_account a ON a.id ="
                + " i.credit_account_id WHERE i.status = 'SUCCEEDED' AND t.status = 'CAPTURED' AND a.purpose ="
                + " 'MERCHANT_PAYABLE' AND NOT EXISTS (SELECT 1 FROM payments.refund r WHERE r.attempt_id = t.id)"
                + " ORDER BY random() LIMIT 1");
        if (captured == null) {
            return;
        }
        long amount = Math.max(1, Long.parseLong(captured[1]) / 2);
        Answer refunded = (RANDOMNESS.nextBoolean() ? a : b).door().post("/v1/payments/" + captured[0] + "/refund",
                "{\"amount\":\"" + decimal(amount) + "\",\"currency\":\"EUR\",\"reason\":\"the storm's refund\"}",
                refunds.token(), key(), Audience.OPERATOR);
        judged(tally, "refund", refunded, "409 payments.NotRefundable", "422 payments.RefundExceedsCaptured",
                "409 api.IdempotencyInProgress", "422 payments.RefundUnfunded");
    }

    // ================================================================== rest and census

    /** Records the instances' scrapes at the start of a round - the rules' "before". */
    void beginRound() {
        for (Instance on : List.of(a, b)) {
            roundStartScrape.put(on.name(), scrape(on));
        }
    }

    /**
     * Rest: the relay drained (every consumed event acknowledged), the monitoring intake past every journal entry, every
     * action and filing concluded or within its permit, every alert of a folding kind folded - then the census.
     */
    void rest(String when) throws Exception {
        long deadline = System.nanoTime() + REST_BOUND.toNanos();
        Instant settleFrom = RiskStormWorld.databaseNow().plus(HORIZON).plusSeconds(3);
        while (true) {
            int left = relay.drain(3);
            boolean monitored = RiskStormWorld.databaseNow().isAfter(settleFrom) && count("SELECT count(*) FROM"
                    + " ledger.journal_entry e, risk.monitoring_watermark w WHERE w.source = 'ledger.journal' AND e.id >"
                    + " w.last_entry_id") == 0;
            long due = count("SELECT (SELECT count(*) FROM risk.risk_action WHERE status IN ('APPROVED', 'DISPATCHED'))"
                    + " + (SELECT count(*) FROM risk.regulatory_report WHERE status = 'FILED') + (SELECT count(*) FROM"
                    + " risk.risk_alert WHERE status = 'OPEN' AND suppression_proposed_by IS NULL AND kind IN ('FRAUD',"
                    + " 'ACCOUNT_TAKEOVER', 'MERCHANT_ABUSE', 'AML', 'SCREENING_HIT', 'OBLIGATION_DEFAULTED'))");
            if (left == 0 && monitored && due == 0) {
                return;
            }
            if (System.nanoTime() > deadline) {
                tally.unexplained(when + ": the storm did not come to rest in " + REST_BOUND.toSeconds() + " s (relay "
                        + left + " left, monitored " + monitored + ", " + due + " actions, filings or alerts due)");
                return;
            }
            Thread.sleep(1_000);
        }
    }

    void restAndCensus(String when) throws Exception {
        rest(when);
        census(when);
    }

    /** Every census in one snapshot, the gauges after a fresh reading, the rules at rest; red on any violation. */
    void census(String when) throws Exception {
        for (Instance on : List.of(a, b)) {
            roundStartScrape.computeIfAbsent(on.name(), name -> scrape(on));
        }
        RiskStormCensus.Reading reading = census.read(when);
        readings.add(when);
        SoftAssertions softly = new SoftAssertions();
        for (RiskStormCensus.Check failing : reading.failing()) {
            softly.fail("%s: %s - %d: %s", when, failing.name(), failing.violations(), failing.detail());
        }
        theGaugesAgree(when, softly);
        theRulesAtRest(when, softly);
        // The tipping-off census at every reading, not only the end's (the entry: "at rest, every round").
        softly.assertThat(RiskStormFaults.RiskReads.of("CUSTOMER other") + RiskStormFaults.RiskReads.of("MERCHANT other"))
                .as("%s: a risk read for a customer or a merchant outside the judgement: %s", when,
                        RiskStormFaults.RiskReads.OFFENDERS).isZero();
        if (!partial()) {
            softly.assertThat(tally.unexplained).as("%s: unexplained failures", when).isEmpty();
        }
        System.out.printf("P13-TST-001 CENSUS: %s - %d checks, %d failing (%d s into the storm)%n", when,
                reading.checks().size(), reading.failing().size(),
                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - stormBegan));
        lastFigures = reading.figures();
        softly.assertAll();
    }

    volatile Map<String, Long> lastFigures = Map.of();

    /**
     * The six census gauges and the velocity gauge on both instances, read fresh ({@code RiskProofMetrics#readNow}):
     * never DIVERGED at rest - the meters agree with the storm's census.
     */
    private void theGaugesAgree(String when, SoftAssertions softly) {
        for (Instance on : List.of(a, b)) {
            RiskProofMetrics metrics = on.beans().getBean(RiskProofMetrics.class);
            metrics.readNow();
            MeterRegistry registry = on.meters();
            registry.find(RiskProofMetrics.CENSUS).tag("verdict", "DIVERGED").gauges().forEach(gauge -> softly
                    .assertThat(gauge.value()).as("%s: %s census gauge %s on %s", when, RiskProofMetrics.CENSUS,
                            gauge.getId().getTag("kind"), on.name()).isZero());
            registry.find(RiskProofMetrics.VELOCITY).tag("verdict", "DIVERGED").gauges().forEach(gauge -> softly
                    .assertThat(gauge.value()).as("%s: the velocity proof gauge on %s", when, on.name()).isZero());
            long kinds = registry.find(RiskProofMetrics.CENSUS).gauges().stream()
                    .map(gauge -> gauge.getId().getTag("kind")).distinct().count();
            softly.assertThat(kinds).as("%s: the six census gauges on %s", when, on.name()).isEqualTo(6);
        }
    }

    /**
     * Every rule of {@code risk.yml} evaluated on each instance's two scrapes (the round's start, its rest): a rule
     * firing at rest must be one this reading deliberately broke, and each deliberately broken one must fire.
     */
    private void theRulesAtRest(String when, SoftAssertions softly) {
        Set<String> firing = new TreeSet<>();
        for (Instance on : List.of(a, b)) {
            String before = roundStartScrape.getOrDefault(on.name(), scrape(on));
            // Settled: a gauge whose reading the first scrape started (behind its floor, off the scrape thread) answers
            // NaN until it completes - a transient no alert's `for` would see; three scrapes a second and a half apart.
            String after = scrape(on);
            for (int i = 0; i < 2 && after.contains(" NaN"); i++) {
                try {
                    Thread.sleep(1_500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                after = scrape(on);
            }
            Map<String, String> target = new LinkedHashMap<>();
            target.put("job", "finapp");
            target.put("instance", on.name());
            target.put("risk_judging", "true");
            for (String alert : rules.firing(before, after, target)) {
                firing.add(alert.replaceAll("\\{.*", "") + alert.replaceAll("^[^{]*", ""));
            }
            roundStartScrape.put(on.name(), after);
        }
        Set<String> expected = expectedFiring.getOrDefault(when, Set.of());
        Set<String> admitted = new TreeSet<>(expected);
        admitted.addAll(toleratedFiring.getOrDefault(when, Set.of()));
        firingAtRest.put(when, firing);
        Set<String> names = new TreeSet<>();
        firing.forEach(alert -> names.add(alert.replaceAll("\\{.*", "")));
        softly.assertThat(names).as("%s: risk.yml rules firing at rest (%s) beyond those the reading broke", when,
                firing).isSubsetOf(admitted);
        softly.assertThat(names).as("%s: the rules this reading deliberately broke fire", when).containsAll(expected);
    }

    /** {@code when}'s reading deliberately breaks {@code alerts} - each must fire at its rest, nothing else. */
    void breaks(String when, String... alerts) {
        expectedFiring.computeIfAbsent(when, k -> ConcurrentHashMap.newKeySet()).addAll(List.of(alerts));
    }

    /**
     * {@code when}'s reading may fire {@code alerts} - not a defect but a measurement of the round (the load's latency
     * against the 50 ms budget, measured properly by {@link RiskStormLatencyReport}); recorded in the summary.
     */
    void tolerates(String when, String... alerts) {
        toleratedFiring.computeIfAbsent(when, k -> ConcurrentHashMap.newKeySet()).addAll(List.of(alerts));
    }

    /** The rules a reading may fire without having broken them on purpose. */
    final Map<String, Set<String>> toleratedFiring = new ConcurrentHashMap<>();

    String scrape(Instance on) {
        MeterRegistry registry = on.meters();
        PrometheusMeterRegistry prometheus = registry instanceof PrometheusMeterRegistry direct ? direct
                : (PrometheusMeterRegistry) ((CompositeMeterRegistry) registry).getRegistries().stream()
                        .filter(PrometheusMeterRegistry.class::isInstance).findFirst()
                        .orElseThrow(() -> new AssertionError("no Prometheus registry on " + on.name()));
        return prometheus.scrape();
    }

    // ================================================================== the end

    /**
     * The tipping-off census's runtime half (`INV-RSK-08`): no statement naming a {@code risk.} relation ran for a
     * CUSTOMER or MERCHANT principal outside the judgement - and the judgement itself was seen (the counter's positive
     * control).
     */
    private void theTippingOffCensus(SoftAssertions softly) {
        System.out.println("P13-TST-001 RISK READS BY PRINCIPAL: " + new TreeMap<>(Map.copyOf(
                RiskStormFaults.RiskReads.COUNTS)));
        softly.assertThat(RiskStormFaults.RiskReads.of("CUSTOMER other") + RiskStormFaults.RiskReads.of("MERCHANT other"))
                .as("a risk read for a customer or a merchant outside the judgement: %s",
                        RiskStormFaults.RiskReads.OFFENDERS).isZero();
        softly.assertThat(RiskStormFaults.RiskReads.of("CUSTOMER judgement")).as("the counter saw the judgement")
                .isPositive();
        softly.assertThat(RiskStormFaults.RiskReads.of("OPERATOR other")).as("the counter saw the operators' desks")
                .isPositive();
        softly.assertThat(RiskStormFaults.RiskReads.COUNTS.keySet().stream().filter(k -> k.startsWith("PLATFORM"))
                .mapToLong(RiskStormFaults.RiskReads::of).sum()).as("the counter saw risk's own sweeps and relay")
                .isPositive();
    }

    /**
     * The needle (`INV-RSK-08`'s answer half): no judged movement's answer to a subject carries a risk word, and no
     * subject's answer of any route carries the identifier of a risk record.
     */
    private void theNeedle(SoftAssertions softly) throws Exception {
        Set<String> riskIds = new java.util.HashSet<>(RiskStormWorld.strings("SELECT id::text FROM risk.risk_assessment"
                + " UNION ALL SELECT id::text FROM risk.risk_alert UNION ALL SELECT id::text FROM risk.risk_case"
                + " UNION ALL SELECT id::text FROM risk.risk_action UNION ALL SELECT id::text FROM risk.regulatory_report"
                + " UNION ALL SELECT id::text FROM risk.decision_override UNION ALL SELECT id::text FROM risk.risk_signal"));
        java.util.regex.Pattern uuid = java.util.regex.Pattern.compile(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        long words = 0;
        long ids = 0;
        List<String> named = new ArrayList<>();
        for (RiskStormWorld.SubjectAnswer answer : subjectAnswers) {
            String lower = RiskStormWorld.lower(answer.body());
            if (answer.judged()) {
                for (String word : RiskStormWorld.RISK_WORDS) {
                    if (lower.contains(word)) {
                        words++;
                        if (named.size() < 5) {
                            named.add(answer.route() + " carries '" + word + "'");
                        }
                    }
                }
            }
            java.util.regex.Matcher found = uuid.matcher(answer.body());
            while (found.find()) {
                if (riskIds.contains(found.group())) {
                    ids++;
                    if (named.size() < 5) {
                        named.add(answer.route() + " carries a risk record's id");
                    }
                }
            }
        }
        coverage.put("subject answers scanned by the needle", (long) subjectAnswers.size());
        coverage.put("risk record ids searched for", (long) riskIds.size());
        softly.assertThat(words + ids).as("risk words or risk ids in a subject's answer: %s", named).isZero();
        softly.assertThat(subjectAnswers.size()).as("the needle scanned answers").isPositive();
    }

    private void summary(long began, RiskStormLatencyReport latency) {
        System.out.printf("P13-TST-001 SUMMARY: %d s; %d readings at rest; %d restarts of B%n",
                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - began), readings.size(), restarts.get());
        System.out.println("P13-TST-001 FIGURES: " + new TreeMap<>(lastFigures));
        System.out.println("P13-TST-001 COVERAGE: " + new TreeMap<>(coverage));
        System.out.println("P13-TST-001 RELAY: " + new TreeMap<>(relay.outcomes));
        System.out.println("P13-TST-001 RELAY FAILURES (each redelivered): " + relay.failures);
        System.out.println("P13-TST-001 RULES AT REST: " + firingAtRest);
        System.out.println("P13-TST-001 ACTS: " + tally.snapshot());
        System.out.println("P13-TST-001 LATENCY: " + latency.table());
    }

    // ================================================================== the instances

    /** One instance: its doors and the beans the storm drives directly. */
    record Instance(String name, Door door, ApplicationContext beans) {

        MeterRegistry meters() {
            return beans.getBean(MeterRegistry.class);
        }

        RiskMonitoringSchedule monitoring() {
            return beans.getBean(RiskMonitoringSchedule.class);
        }

        RiskActionSchedule actions() {
            return beans.getBean(RiskActionSchedule.class);
        }
    }

    private Instance instance(String name, int port, ApplicationContext beans) {
        return new Instance(name, new Door(name, port, tally, subjectAnswers), beans);
    }

    Instance other(Instance on) {
        return on == a ? b : a;
    }

    List<RiskStormRelay.Target> relayTargets() {
        List<RiskStormRelay.Target> targets = new ArrayList<>();
        targets.add(new RiskStormRelay.Target("A", a.beans()));
        Instance current = b;
        if (current != null && !current.door().down) {
            targets.add(new RiskStormRelay.Target("B", current.beans()));
        }
        return targets;
    }

    private void startInstanceB() throws Exception {
        Clock behind = RiskStormClock.of(SKEW.negated());
        // A probe names B by its port (the R2 order skipped "in one instance" - MUTATION_TESTING.md section 2); unset, any.
        String port = System.getenv().getOrDefault("FINAPP_RISK_STORM_B_PORT", "0");
        List<String> arguments = new ArrayList<>(List.of("--server.port=" + port,
                "--spring.datasource.hikari.data-source-properties.ApplicationName=" + B_APPLICATION_NAME,
                "--spring.datasource.hikari.connection-init-sql=SET TIME ZONE '" + DateShiftedZone.zone() + "'"));
        PROPERTIES.forEach((name, value) -> {
            if (!name.endsWith("ApplicationName")) {
                arguments.add("--" + name + "=" + value);
            }
        });
        instanceB = new SpringApplicationBuilder(com.finapp.app.FinappApplication.class)
                .initializers(initializer -> {
                    GenericApplicationContext generic = (GenericApplicationContext) initializer;
                    generic.registerBean("instanceBClock", Clock.class, () -> behind,
                            definition -> definition.setPrimary(true));
                    generic.registerBean("instanceBStormFaults", BeanPostProcessor.class,
                            () -> RiskStormFaults.wrapper("B"));
                })
                .run(arguments.toArray(String[]::new));
        b = instance("B", Integer.parseInt(instanceB.getEnvironment().getProperty("local.server.port")), instanceB);
        a.door().other = b.door();
        b.door().other = a.door();
    }

    /**
     * B killed mid-load and a new B started (the same -5 s skew): its doors routed away, its backends terminated while
     * its sweeps hold claims, its context closed - every claim it held re-driven by its permit.
     */
    void restartInstanceB() throws Exception {
        Instance killed = b;
        killedB = killed.meters();
        killed.door().down = true;
        int terminated = RiskStormFaults.terminateBackends(B_APPLICATION_NAME);
        instanceB.close();
        Thread.sleep(500);
        startInstanceB();
        restarts.incrementAndGet();
        coverage.merge("instance B killed (backends terminated) and restarted", 1L, Long::sum);
        coverage.merge("B's backends terminated at the kill", (long) terminated, Long::sum);
    }

    /** Instance A's own beans: its clock five seconds ahead of the server, its ports and pool wrapped. */
    @TestConfiguration
    static class InstanceA {

        @Bean
        @Primary
        Clock instanceAClock() throws Exception {
            return RiskStormClock.of(SKEW);
        }

        @Bean
        static BeanPostProcessor instanceAStormFaults() {
            return RiskStormFaults.wrapper("A");
        }
    }

    // ================================================================== plumbing the catalogue shares

    static long since(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    static void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }

    /** A PARTY subject lock (R2a) held on an application connection - the fail-safe's lever. Closing releases it. */
    static AutoCloseable holdPartyLocks(List<UUID> parties) throws Exception {
        Connection blocker = com.finapp.platform.testing.database.DatabaseRoles.application();
        blocker.setAutoCommit(false);
        try (Statement lock = blocker.createStatement()) {
            for (UUID party : parties) {
                lock.execute("SELECT pg_advisory_xact_lock(131, " + com.finapp.risk.VelocityWindows.lockKey(
                        com.finapp.risk.VelocityDimension.PARTY, party.toString()) + ")");
            }
        }
        return () -> {
            blocker.rollback();
            blocker.close();
        };
    }

    static Optional<String> optional(String value) {
        return Optional.ofNullable(value);
    }

    static AtomicLong counter() {
        return new AtomicLong();
    }

    static <T> List<T> shuffled(List<T> values) {
        List<T> copy = new ArrayList<>(values);
        Collections.shuffle(copy, RANDOMNESS);
        return copy;
    }
}
