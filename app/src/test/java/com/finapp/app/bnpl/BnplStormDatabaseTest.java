package com.finapp.app.bnpl;

import static com.finapp.app.bnpl.BnplStormWorld.AGREEMENTS;
import static com.finapp.app.bnpl.BnplStormWorld.APPLICATIONS;
import static com.finapp.app.bnpl.BnplStormWorld.LOANS;
import static com.finapp.app.bnpl.BnplStormWorld.LOAN_APPLICATIONS;
import static com.finapp.app.bnpl.BnplStormWorld.LOAN_OFFERS;
import static com.finapp.app.bnpl.BnplStormWorld.MERCHANT_DISPUTES;
import static com.finapp.app.bnpl.BnplStormWorld.NEEDLE;
import static com.finapp.app.bnpl.BnplStormWorld.OFFERS;
import static com.finapp.app.bnpl.BnplStormWorld.OPERATOR_AGREEMENTS;
import static com.finapp.app.bnpl.BnplStormWorld.OPERATOR_DISPUTES;
import static com.finapp.app.bnpl.BnplStormWorld.PAYOUTS;
import static com.finapp.app.bnpl.BnplStormWorld.PAYOUT_PATH;
import static com.finapp.app.bnpl.BnplStormWorld.PURCHASES;
import static com.finapp.app.bnpl.BnplStormWorld.RANDOMNESS;
import static com.finapp.app.bnpl.BnplStormWorld.count;
import static com.finapp.app.bnpl.BnplStormWorld.decimal;
import static com.finapp.app.bnpl.BnplStormWorld.judged;
import static com.finapp.app.bnpl.BnplStormWorld.key;
import static com.finapp.app.bnpl.BnplStormWorld.money;
import static com.finapp.app.bnpl.BnplStormWorld.refused;
import static com.finapp.app.bnpl.BnplStormWorld.row;
import static com.finapp.app.bnpl.BnplStormWorld.rows;
import static com.finapp.app.bnpl.BnplStormWorld.scalar;
import static com.finapp.app.bnpl.BnplStormWorld.strings;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.bnpl.BnplStormWorld.Answer;
import com.finapp.app.bnpl.BnplStormWorld.Customer;
import com.finapp.app.bnpl.BnplStormWorld.Door;
import com.finapp.app.bnpl.BnplStormWorld.Merchant;
import com.finapp.app.bnpl.BnplStormWorld.Tally;
import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.credit.ExposureCensusMetrics;
import com.finapp.app.credit.LendingStormCredit;
import com.finapp.app.database.DateShifted;
import com.finapp.app.database.DateShiftedZone;
import com.finapp.app.database.ServerSkewedClock;
import com.finapp.app.reconciliation.PositionProof;
import com.finapp.bnpl.BnplAccruals;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplOrigination;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplProofs;
import com.finapp.bnpl.BnplReplayProof;
import com.finapp.bnpl.BnplServicing;
import com.finapp.bnpl.ServicingOutcome;
import com.finapp.credit.CreditBureau;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.FinancialDataProvider;
import com.finapp.identity.RoleName;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.ProjectionVerification;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LendingCapitalProof;
import com.finapp.lending.LendingProofs;
import com.finapp.merchant.MerchantPayable;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.money.CurrencyCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * THE BNPL STORM (`P12-TST-001`; {@code PHASE_12_PLAN.md} sections 13.2-13.4 and 21; every {@code INV-BNPL-*},
 * {@code INV-MER-02}, {@code -05}, {@code -07}, {@code INV-LND-13}, {@code INV-CRD-09}, {@code INV-IDEM-01}, {@code -02},
 * {@code INV-PAY-04}, {@code INV-SET-06}, {@code INV-LED-01}; the Phase 12 gate's ten-instance answer): BNPL end to end,
 * checkout to closure for both products, beside lending on shared parties, merchants and capital, on two skewed instances
 * at once - and every census exact in one {@code REPEATABLE READ} snapshot at every round's rest. The lending storm's shape
 * (`P11-TST-001`) with its gate's corrections from the first run: every census at every round (I2), every named lock wait
 * counted as the act's own backend (M3, C17), the meters over the whole run (C19), no id or amount in the logs (C1).
 *
 * <h2>Two application instances whose clocks disagree by ten seconds</h2>
 *
 * <p>Instance A is this suite's own context, its {@code Clock} five seconds AHEAD of the database server; instance B is a
 * second {@link com.finapp.app.FinappApplication} over the same database in this JVM, five seconds BEHIND. Both run every
 * BNPL schedule (origination, servicing, disputes), credit's progress and data sweeps, lending's four, checkout's expiry,
 * merchant's payout resolution and payments' card resolution as their real lifecycles, enabled here as in production.
 * Midway, B is killed - its backends terminated - and a new B started. Correctness lives in the database.
 *
 * <h2>Business dates</h2>
 *
 * <p>BNPL services in UTC alone (A22), so no BNPL cohort can be given a midnight inside the run: BNPL's date-sensitive
 * lines - due dates, the late fee's day, DPD 30 and 90, accrual dates - are crossed by ageing reserved obligations whole
 * days before the database's business date ({@link BnplStormWorld#bornInThePast}, never a day of the month - A6-06), and
 * judged on the database clock by both skewed instances; the three real midnights are the shared parties' lending cohorts,
 * fixed-offset zones whose local midnight falls in the run (the design's judgement J1).
 *
 * <h2>The storm</h2>
 *
 * <ol>
 *   <li><strong>Load</strong>, in rounds: shoppers, payers, merchants, agents, borrowers, chaos and treasury on both
 *       instances; the safety censuses read every few seconds; then rest, and every census at rest. Mid-load a BNPL terms
 *       activation and B's restart.
 *   <li><strong>The catalogue</strong>: the eighteen mandatory scenarios and the contention scenes, each choreographed on
 *       both instances at a barrier, each followed by every census ({@link BnplStormCatalogue}).
 *   <li><strong>The crash points</strong>: refused writes and killed backends at every T-pur step and in servicing,
 *       repayment, refund and reversal - each re-driven by the other instance - and the relay killed after the receive.
 *   <li><strong>At rest</strong>: the cash proof, the projections, the hot-row characterisation, the named lock waits,
 *       the needle and the coverage ledger.
 * </ol>
 */
@DateShifted
@Tag("database")
@Tag("own-container")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BnplStormDatabaseTest.InstanceA.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("the BNPL storm (P12-TST-001)")
class BnplStormDatabaseTest {

    private static final Duration SKEW = Duration.ofSeconds(5);
    private static final Duration PROVIDER_TIMEOUT = Duration.ofMillis(800);
    private static final int FIRST_BOUNDARY_MINUTES = 4;
    private static final int BOUNDARIES = 3;
    private static final int MIN_ROUNDS = 8;
    private static final Duration BURST = Duration.ofSeconds(15);
    static final Duration REST_BOUND = Duration.ofSeconds(120);
    private static final int POOL = 24;
    private static final int MERCHANTS = 6;
    private static final String B_APPLICATION_NAME = "finapp-bnpl-storm-b";
    private static final long TRANCHE_ZERO = 120_000_00;
    static final long TRANCHE = 40_000_00;

    /**
     * A development run's scene list ({@code FINAPP_BNPL_STORM_SCENES}, comma-separated catalogue method names) and round
     * count ({@code FINAPP_BNPL_STORM_ROUNDS}): never set on a counted run - a run with them fails at its end, by design.
     */
    private static final String SCENES = System.getenv("FINAPP_BNPL_STORM_SCENES");

    static boolean wanted(String scene) {
        return SCENES == null || SCENES.isBlank() || List.of(SCENES.split(",")).contains(scene);
    }

    private static boolean partial() {
        return SCENES != null && !SCENES.isBlank();
    }

    static final LendingStormCredit CREDIT = LendingStormCredit.start(NEEDLE, BnplStormWorld.SEED + 1);
    private static SimulatedProvider provider;
    private static final Map<String, String> PROPERTIES = new LinkedHashMap<>();
    private static volatile ConfigurableApplicationContext instanceB;

    @LocalServerPort private int port;
    @Autowired private ApplicationContext context;

    private final Tally tally = new Tally();
    private BnplStormWorld world;
    private BnplStormCensus census;
    private BnplStormLocks.HotRows hotRows;
    private volatile Instance a;
    private volatile Instance b;
    private final AtomicInteger rounds = new AtomicInteger();
    private final AtomicInteger restarts = new AtomicInteger();
    private volatile boolean restarting;
    private volatile long stormBegan;

    /** The coverage ledger: scenario -> evidence counted from ROWS, never from the storm's intentions. */
    private final Map<String, AtomicInteger> coverage = new ConcurrentHashMap<>();
    private final List<Customer> pool = new CopyOnWriteArrayList<>();
    private final Map<UUID, Customer> byParty = new ConcurrentHashMap<>();
    private final List<Merchant> merchants = new CopyOnWriteArrayList<>();
    /** Obligations, purchases and parties a scene owns: no mover touches them. */
    private final Set<String> reserved = ConcurrentHashMap.newKeySet();
    private final List<String[]> cohorts = new ArrayList<>();
    /** The BNPL terms version activated mid-load, and the offer made before it (I11). */
    private volatile String termsV2;
    /** Faults the storm armed beneath the instances' OWN servicing sweeps - the servicing failures' meter arm. */
    private final AtomicInteger servicingFaultsInjected = new AtomicInteger();
    /** The killed B's meters, read after its close - the whole run's meters are A's, B's and these. */
    private final Map<String, Double> killedB = new ConcurrentHashMap<>();
    /** The named lock waits seen as the act's own backend, by lock. */
    final Map<String, AtomicInteger> lockWaits = new ConcurrentHashMap<>();

    private String[] bnplOfficers;
    private String[] agents;
    private String[] lendingOfficers;
    private String[] treasurers;

    // ================================================================== the instances' configuration

    @DynamicPropertySource
    static void storm(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        // BNPL's three schedules ENABLED as real lifecycles on both instances, storm-short polls and permits.
        PROPERTIES.put("finapp.bnpl.origination.sweeper.enabled", "true");
        PROPERTIES.put("finapp.bnpl.origination.poll", "PT0.3S");
        PROPERTIES.put("finapp.bnpl.origination.permit", "PT1S");
        PROPERTIES.put("finapp.bnpl.servicing.sweeper.enabled", "true");
        PROPERTIES.put("finapp.bnpl.servicing.poll", "PT0.25S");
        PROPERTIES.put("finapp.bnpl.servicing.retry", "PT2S");
        PROPERTIES.put("finapp.bnpl.dispute.sweeper.enabled", "true");
        PROPERTIES.put("finapp.bnpl.dispute.poll", "PT0.5S");
        PROPERTIES.put("finapp.bnpl.dispute.retry", "PT2S");
        PROPERTIES.put("finapp.bnpl.proofs.refresh", "PT60S");
        // Credit decides by its own leaderless sweeps over the simulators.
        PROPERTIES.put("finapp.credit.progress.sweeper.enabled", "true");
        PROPERTIES.put("finapp.credit.progress.poll", "PT0.3S");
        PROPERTIES.put("finapp.credit.progress.permit", "PT2S");
        PROPERTIES.put("finapp.credit.data.retry.sweeper.enabled", "true");
        PROPERTIES.put("finapp.credit.data.retry.poll", "PT1S");
        PROPERTIES.put("finapp.credit.bureau.retry-cadence", "PT1S");
        PROPERTIES.put("finapp.credit.bureau.collection-window", "PT45S");
        PROPERTIES.put("finapp.credit.findata.retry-cadence", "PT1S");
        PROPERTIES.put("finapp.credit.findata.collection-window", "PT45S");
        // Lending on the shared parties: loans and lines to the wallet, serviced in their cohorts' zones.
        PROPERTIES.put("finapp.lending.origination.sweeper.enabled", "true");
        PROPERTIES.put("finapp.lending.origination.poll", "PT0.3S");
        PROPERTIES.put("finapp.lending.origination.permit", "PT1S");
        PROPERTIES.put("finapp.lending.disbursement.sweeper.enabled", "true");
        PROPERTIES.put("finapp.lending.disbursement.poll", "PT0.3S");
        PROPERTIES.put("finapp.lending.disbursement.permit", "PT2S");
        PROPERTIES.put("finapp.lending.payout.sweeper.enabled", "true");
        PROPERTIES.put("finapp.lending.payout.poll", "PT0.3S");
        PROPERTIES.put("finapp.lending.payout.permit", "PT2S");
        PROPERTIES.put("finapp.lending.servicing.sweeper.enabled", "true");
        PROPERTIES.put("finapp.lending.servicing.poll", "PT0.25S");
        PROPERTIES.put("finapp.lending.servicing.retry", "PT2S");
        PROPERTIES.put("finapp.lending.proofs.refresh", "PT60S");
        // Checkout's expiry sweep.
        PROPERTIES.put("finapp.checkout.sweeper.enabled", "true");
        PROPERTIES.put("finapp.checkout.sweeper.poll-interval", "PT0.5S");
        // The card PSP, tokenisation and the merchant payout provider - one simulated provider; their resolutions.
        PROPERTIES.put("finapp.payments.provider.url", provider.baseUrl());
        PROPERTIES.put("finapp.payments.provider.timeout", "PT0.7S");
        PROPERTIES.put("finapp.payments.sweeper.enabled", "true");
        PROPERTIES.put("finapp.payments.sweeper.poll-interval", "PT0.5S");
        PROPERTIES.put("finapp.payments.sweeper.dispatched-age", "PT1S");
        PROPERTIES.put("finapp.payments.sweeper.unknown-age", "PT0.2S");
        PROPERTIES.put("finapp.paymentmethods.tokenisation.url", provider.baseUrl());
        PROPERTIES.put("finapp.paymentmethods.tokenisation.timeout", "PT2S");
        PROPERTIES.put("finapp.merchant.payout.provider.url", provider.baseUrl());
        PROPERTIES.put("finapp.merchant.payout.provider.timeout", "PT0.7S");
        PROPERTIES.put("finapp.merchant.payout.sweeper.enabled", "true");
        PROPERTIES.put("finapp.merchant.payout.sweeper.poll-interval", "PT0.5S");
        PROPERTIES.put("finapp.merchant.payout.sweeper.dispatched-age", "PT1S");
        PROPERTIES.put("finapp.merchant.payout.sweeper.unknown-age", "PT0.3S");
        // No broker in the database tier (the kafka tier's subject): the instances' own relays could only fail every event
        // into the production backoff and dead-letter it within minutes (RetryPolicy.DEFAULT, 14 attempts), so the relay
        // crash point (A6-07) would meet heads it may not touch. Off on both instances; the crash point's relays own
        // publication, and the outbox census (C14) counts the rows, never a publication.
        PROPERTIES.put("finapp.outbox.relay.enabled", "false");
        PROPERTIES.forEach((name, value) -> registry.add(name, () -> value));
    }

    @AfterAll
    static void stop() {
        if (instanceB != null) {
            instanceB.close();
        }
        CREDIT.close();
        if (provider != null) {
            provider.close();
        }
    }

    // ================================================================== the storm

    @Test
    @DisplayName("two instances skewed +/-5 s, both BNPL products from checkout to closure beside loans and lines on shared"
            + " parties, merchants and capital, every mandatory scenario, payout and pay-in faults, crash points, killed"
            + " backends and a restart: every census exact at every round, every named lock wait counted, the hot rows"
            + " measured")
    void theStormLeavesEveryObligationProvable(CapturedOutput output) throws Exception {
        long began = System.nanoTime();
        stormBegan = began;
        System.out.printf("P12-TST-001 SEED: %d - replay the storm's choices with FINAPP_STORM_SEED=%d (date-shifted to"
                + " %s)%n", BnplStormWorld.SEED, BnplStormWorld.SEED, DateShiftedZone.zone());
        a = instance("A", port, context);
        world = new BnplStormWorld(context, CREDIT, provider, a.door());
        prepareTheWorld();
        census = new BnplStormCensus(context.getBean(BnplProofs.class), context.getBean(LendingProofs.class),
                context.getBean(LendingCapitalProof.class), new MerchantPayable(new com.finapp.ledger.JdbcLedgerAccountStore(),
                        new com.finapp.ledger.JdbcPositionBreakdown()),
                () -> merchants.stream().map(Merchant::id).toList());
        hotRows = new BnplStormLocks.HotRows(hotRowKeys());
        try {
            restAndCensus("before the storm");
            load();
            BnplStormCatalogue scenes = new BnplStormCatalogue(this);
            scenes.run();
            scenes.crashPoints();
            restAndCensus("drained");
            assertThePayoutsMatchedByTheProvidersReport();
            restAndCensus("reconciled");
            hotRows.nextRound("after");
        } finally {
            hotRows.close();
        }
        assertTheCashAndTheProjections();
        assertTheHotRows();
        assertTheNeedleReachedNoSink(output);
        if (!partial()) {
            assertTheTermsWerePinnedAcrossTheActivation();
            assertTheNamedLockWaitsWereCounted();
            assertEveryScenarioWasExercised();
        } else {
            summary(began);
        }
        assertThat(tally.unexplained).as("unexplained failures - every response in its act's closed set, every exception"
                + " an armed fault's").isEmpty();
        summary(began);
        assertThat(partial()).as("a partial storm (FINAPP_BNPL_STORM_SCENES) proves nothing - a development run only")
                .isFalse();
    }

    // ================================================================== the world

    private void prepareTheWorld() throws Exception {
        Instant now = BnplStormWorld.databaseNow();
        CREDIT.retrievedAt(now.minus(Duration.ofHours(1)));
        world.stubTheProvider();
        CreditFixtures.seedsInForce();
        for (CreditProduct product : CreditProduct.of(CreditProduct.Family.BNPL)) {
            CreditFixtures.bnplInForce(product);
        }
        bnplOfficers = new String[] {world.sessionWith(RoleName.BNPL_OFFICER), world.sessionWith(RoleName.BNPL_OFFICER)};
        agents = new String[] {world.sessionWith(RoleName.BNPL_SERVICING_AGENT),
            world.sessionWith(RoleName.BNPL_SERVICING_AGENT)};
        lendingOfficers = new String[] {world.sessionWith(RoleName.LENDING_OFFICER),
            world.sessionWith(RoleName.LENDING_OFFICER)};
        treasurers = new String[] {world.sessionWith(RoleName.LENDING_TREASURY_OFFICER),
            world.sessionWith(RoleName.LENDING_TREASURY_OFFICER)};
        startInstanceB();
        // Both BNPL products' terms under four eyes across instances.
        world.activateTerms(BnplStormWorld.BNPL_TERMS, a.door(), b.door(), bnplOfficers[0], bnplOfficers[1],
                BnplStormWorld.bnplTerms("BNPL_PAY_IN_4", Map.of()), "bnpl.SelfApprovalRefused", tally);
        world.activateTerms(BnplStormWorld.BNPL_TERMS, b.door(), a.door(), bnplOfficers[1], bnplOfficers[0],
                BnplStormWorld.bnplTerms("BNPL_PAY_IN_N", Map.of()), "bnpl.SelfApprovalRefused", tally);
        // Lending's products in UTC for the load's borrowers; the cohorts' zones below.
        world.activateTerms(BnplStormWorld.LENDING_TERMS, a.door(), b.door(), lendingOfficers[0], lendingOfficers[1],
                BnplStormWorld.lendingTerms("PERSONAL_LOAN", "UTC", "0.0899", "the storm's loans"),
                "lending.SelfApprovalRefused", tally);
        world.activateTerms(BnplStormWorld.LENDING_TERMS, b.door(), a.door(), lendingOfficers[1], lendingOfficers[0],
                BnplStormWorld.lendingTerms("CREDIT_LINE", "UTC", "0.1299", "the storm's lines"),
                "lending.SelfApprovalRefused", tally);
        // Capital tranche 0 through the bank's statement: enough for most, not all, of the load.
        world.tranche(a.door(), b.door(), treasurers[0], treasurers[1], TRANCHE_ZERO, true, tally);

        ExecutorService builders = Executors.newFixedThreadPool(6);
        try {
            List<Future<Merchant>> shops = new ArrayList<>();
            for (int i = 0; i < MERCHANTS; i++) {
                String rate = i % 2 == 0 ? "0.04" : "0.0375";
                long fixed = i % 2 == 0 ? 0 : 30;
                shops.add(builders.submit(() -> world.merchant(rate, fixed)));
            }
            for (Future<Merchant> shop : shops) {
                merchants.add(shop.get(3, TimeUnit.MINUTES));
            }
            List<Future<Customer>> made = new ArrayList<>();
            for (int i = 0; i < POOL; i++) {
                made.add(builders.submit(world::customer));
            }
            for (Future<Customer> customer : made) {
                Customer one = customer.get(3, TimeUnit.MINUTES);
                pool.add(one);
                byParty.put(one.party(), one);
            }
        } finally {
            builders.shutdown();
        }
        prepareTheCohorts();
        Instant ready = BnplStormWorld.databaseNow();
        System.out.printf("P12-TST-001 PREPARED: %d customers, %d merchants, %d lending cohorts; the first boundary in %d"
                + " s%n", pool.size(), merchants.size(), cohorts.size(),
                Duration.between(ready, Instant.parse(cohorts.get(0)[0])).toSeconds());
        assertThat(ready).as("preparation overran - the first real midnight is too close for the storm to cross it")
                .isBefore(Instant.parse(cohorts.get(0)[0]).minus(Duration.ofSeconds(30)));
    }

    /**
     * The lending cohorts (J1): each a loan's terms in a fixed-offset zone whose local midnight falls a few minutes into
     * the run, and a loan of a pool customer under it - disbursed to the wallet before its midnight, so the day it was
     * disbursed accrues once when the midnight passes, judged by both skewed instances' servicing sweeps.
     */
    private void prepareTheCohorts() throws Exception {
        Instant first = BnplStormWorld.databaseNow().truncatedTo(ChronoUnit.MINUTES)
                .plus(Duration.ofMinutes(FIRST_BOUNDARY_MINUTES));
        for (int k = 1; k <= BOUNDARIES; k++) {
            Instant boundary = first.plus(Duration.ofMinutes(k - 1L));
            ZoneOffset zone = BnplStormWorld.zoneWhoseMidnightIs(boundary);
            String zoneName = zone.getTotalSeconds() == 0 ? "UTC" : zone.getId();
            Door proposing = k % 2 == 0 ? b.door() : a.door();
            world.activateTerms(BnplStormWorld.LENDING_TERMS, proposing, other(proposing), lendingOfficers[k % 2],
                    lendingOfficers[(k + 1) % 2], BnplStormWorld.lendingTerms("PERSONAL_LOAN", zoneName, "0.0899",
                            "cohort " + k), "lending.SelfApprovalRefused", tally);
            Customer borrower = pool.get(k - 1);
            String loan = borrow(a, borrower, false, 3_000_00 + 10_00L * k);
            assertThat(loan).as("cohort %s's loan accepted", k).isNotNull();
            reserved.add(loan);
            cohorts.add(new String[] {boundary.toString(), loan, zoneName});
            // The terms back in UTC for the load's borrowers (each activation retires the last).
            world.activateTerms(BnplStormWorld.LENDING_TERMS, other(proposing), proposing, lendingOfficers[(k + 1) % 2],
                    lendingOfficers[k % 2], BnplStormWorld.lendingTerms("PERSONAL_LOAN", "UTC", "0.0899",
                            "back to UTC after cohort " + k), "lending.SelfApprovalRefused", tally);
        }
    }

    /** The hot rows (A2-F1, owner item #21): the currency's capital account row and the three income projection rows. */
    private Map<String, String[]> hotRowKeys() throws SQLException {
        Map<String, String[]> keys = new LinkedHashMap<>();
        keys.put("LENDING_CAPITAL", new String[] {"ledger.ledger_account", "id", operational("LENDING_CAPITAL")});
        for (String income : List.of("BNPL_MERCHANT_FEE_INCOME", "BNPL_FEE_INCOME", "BNPL_INTEREST_INCOME")) {
            keys.put(income, new String[] {"ledger.account_balance", "ledger_account_id", operational(income)});
        }
        return keys;
    }

    static String operational(String purpose) throws SQLException {
        String id = scalar("SELECT id::text FROM ledger.ledger_account WHERE purpose = ? AND currency = 'EUR' AND"
                + " owner_ref IS NULL", purpose);
        assertThat(id).as("the operational %s account", purpose).isNotNull();
        return id;
    }

    // ================================================================== 1. load

    private void load() throws Exception {
        ExecutorService boundaries = Executors.newSingleThreadExecutor();
        Future<?> choreography = boundaries.submit(() -> {
            for (String[] cohort : cohorts) {
                boundaryChoreography(cohort);
            }
            return null;
        });
        Instant last = Instant.parse(cohorts.get(cohorts.size() - 1)[0]);
        try {
            int least = partial() ? Integer.parseInt(System.getenv().getOrDefault("FINAPP_BNPL_STORM_ROUNDS", "1"))
                    : MIN_ROUNDS;
            while (rounds.get() < least || (!partial() && BnplStormDatabaseTest.databaseBefore(last.plusSeconds(20)))) {
                int round = rounds.incrementAndGet();
                hotRows.nextRound("round " + round);
                burst(round);
                hotRows.nextRound("rest " + round);
                restAndCensus("round " + round);
                if (choreography.isDone()) {
                    choreography.get();
                }
            }
            if (!partial()) {
                choreography.get(5, TimeUnit.MINUTES);
            }
        } finally {
            boundaries.shutdownNow();
        }
        if (!partial()) {
            assertTheBoundariesWereCrossed();
        }
    }

    private static boolean databaseBefore(Instant instant) throws SQLException {
        return BnplStormWorld.databaseNow().isBefore(instant);
    }

    private void burst(int round) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService threads = Executors.newFixedThreadPool(30);
        List<Future<?>> running = new ArrayList<>();
        try {
            for (Instance on : List.of(a, b)) {
                for (int i = 0; i < 3; i++) {
                    running.add(threads.submit(() -> forever(stop, "shopper " + on.name(), () -> shopOnce(on))));
                }
                for (int i = 0; i < 2; i++) {
                    running.add(threads.submit(() -> forever(stop, "payer " + on.name(), () -> payOnce(on))));
                }
                running.add(threads.submit(() -> forever(stop, "merchant " + on.name(), () -> merchantOnce(on))));
                running.add(threads.submit(() -> forever(stop, "agent " + on.name(), () -> agentOnce(on))));
                running.add(threads.submit(() -> forever(stop, "borrower " + on.name(), () -> borrowOnce(on))));
            }
            running.add(threads.submit(() -> forever(stop, "chaos", this::chaosOnce)));
            running.add(threads.submit(() -> forever(stop, "treasury", this::treasuryOnce)));
            if (round == 3) {
                running.add(threads.submit(this::activateTermsMidLoad));
            }
            if (round == 4) {
                running.add(threads.submit(this::restartInstanceB));
            }
            Instant end = BnplStormWorld.databaseNow().plus(BURST);
            for (String[] cohort : cohorts) {
                Instant boundary = Instant.parse(cohort[0]);
                if (boundary.isAfter(end.minus(BURST)) && boundary.isBefore(end.plus(REST_BOUND))) {
                    end = boundary.plusSeconds(12).isAfter(end) ? boundary.plusSeconds(12) : end;
                }
            }
            while (BnplStormWorld.databaseNow().isBefore(end)) {
                for (Future<?> task : running) {
                    if (task.isDone()) {
                        task.get();
                    }
                }
                census("round " + round + " while blowing", false);
                Thread.sleep(3_000);
            }
        } finally {
            stop.set(true);
            threads.shutdown();
            assertThat(threads.awaitTermination(3, TimeUnit.MINUTES)).as("every actor stops").isTrue();
        }
        for (Future<?> task : running) {
            task.get();
        }
    }

    @FunctionalInterface
    interface Act {
        void run() throws Exception;
    }

    private static void forever(AtomicBoolean stop, String who, Act act) {
        try {
            while (!stop.get()) {
                act.run();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception | AssertionError failure) {
            throw new IllegalStateException(who + " failed", failure);
        }
    }

    // ---------------------------------------------------------------- the movers

    /** The instalment 1 a pay-in-4 purchase of {@code gross} collects at its acceptance: P - 3 floor(P / 4). */
    static long firstInstalment(long gross) {
        return gross - 3 * (gross / 4);
    }

    /**
     * A shopper: a pool customer at a random merchant - pay-in-4 or pay-in-N - the application's key replayed across
     * instances, a withdrawal, a stale hash, a decline, a card or wallet confirmation racing the acceptance; else the
     * acceptance on the other instance, its key replayed, the wallet funded first with exactly instalment 1.
     */
    private void shopOnce(Instance on) throws Exception {
        Thread.sleep(300 + RANDOMNESS.nextInt(500));
        Customer customer = pool.get(RANDOMNESS.nextInt(pool.size()));
        if (reserved.contains(customer.party().toString())) {
            return;
        }
        Merchant merchant = merchants.get(RANDOMNESS.nextInt(merchants.size()));
        boolean p4 = RANDOMNESS.nextInt(10) < 6;
        long gross = p4 ? 100_00 + 7 * RANDOMNESS.nextInt(11_400) : 200_00 + 11 * RANDOMNESS.nextInt(11_800);
        String[] session = BnplStormWorld.openSession(on.door(), merchant, gross, tally);
        String body = p4 ? BnplStormWorld.payIn4(session[1]) : BnplStormWorld.payInN(session[1],
                List.of(3, 6, 12).get(RANDOMNESS.nextInt(3)));
        String applicationKey = key();
        Answer applied = on.door().post(APPLICATIONS, body, customer.token(), applicationKey);
        if (!judged(tally, "apply", applied, "409 credit.DecisionRequestOpen", "409 api.IdempotencyInProgress",
                "409 bnpl.ApplicationOpen") || applied.status() != 202) {
            return;
        }
        String application = applied.field("applicationId");
        int dice = RANDOMNESS.nextInt(100);
        if (dice < 8) {
            Answer replayed = other(on).door().post(APPLICATIONS, body, customer.token(), applicationKey);
            judged(tally, "apply replayed", replayed, "409 api.IdempotencyInProgress");
            if (replayed.status() == 202 && !replayed.body().equals(applied.body())) {
                tally.unexplained("a replayed application key answered another receipt");
            }
        } else if (dice < 12) {
            Answer withdrawn = other(on).door().post(APPLICATIONS + "/" + application + "/withdrawal", null,
                    customer.token(), key());
            judged(tally, "withdraw application", withdrawn, "409 bnpl.ApplicationNotWithdrawable");
            return;
        }
        Optional<String> offer = BnplStormWorld.awaitOffer(application, Duration.ofSeconds(25));
        if (offer.isEmpty()) {
            tally.count("application not offered within the burst");
            return;
        }
        accept(on, customer, offer.get(), p4, gross, session[1], merchant);
    }

    private void accept(Instance on, Customer customer, String offer, boolean p4, long gross, String sessionToken,
            Merchant merchant) throws Exception {
        Answer read = on.door().get(OFFERS + "/" + offer, customer.token());
        if (!judged(tally, "read offer", read) || read.status() != 200) {
            return;
        }
        String hash = read.field("termsSha256");
        int dice = RANDOMNESS.nextInt(100);
        if (dice < 5) {
            Answer stale = other(on).door().post(OFFERS + "/" + offer + "/acceptance",
                    BnplStormWorld.acceptance("ab".repeat(32)), customer.token(), key());
            refused(tally, "accept a stale hash", stale, "409 bnpl.TermsChanged", "409 bnpl.OfferNotAcceptable");
            return;
        }
        if (dice < 10) {
            Answer declined = other(on).door().post(OFFERS + "/" + offer + "/decline", null, customer.token(), key());
            judged(tally, "decline offer", declined, "409 bnpl.OfferNotAcceptable");
            return;
        }
        if (p4) {
            BnplStormWorld.payIn(on.door(), other(on).door(), customer, firstInstalment(gross), key(), tally, null);
        }
        String acceptanceKey = key();
        String body = BnplStormWorld.acceptance(hash);
        if (dice < 18) {
            // Scenario 2 under load: the session raced by the acceptance and a card or wallet confirmation.
            boolean card = dice < 14;
            if (!card) {
                BnplStormWorld.payIn(on.door(), other(on).door(), customer, gross, key(), tally, null);
            }
            List<Object> raced = bothAtOnce(
                    () -> other(on).door().post(OFFERS + "/" + offer + "/acceptance", body, customer.token(),
                            acceptanceKey),
                    () -> on.door().post(BnplStormWorld.CONFIRMATION, "{\"sessionToken\":\"" + sessionToken + "\","
                            + (card ? "\"paymentMethodId\":\"" + customer.card() + "\"" : "\"instrument\":\"WALLET\"")
                            + "}", customer.token(), null));
            Answer accepted = answer(raced.get(0));
            judged(tally, "accept racing a " + (card ? "card" : "wallet") + " confirmation", accepted,
                    "409 bnpl.SessionNotPayable", "422 bnpl.CapitalUnavailable", "422 bnpl.InsufficientFunds",
                    "409 credit.ApplicantNotEligible");
            judged(tally, (card ? "card" : "wallet") + " confirmation racing an acceptance", answer(raced.get(1)),
                    "409 checkout.NotConfirmable", "422 payments.InsufficientFunds", "409 payments.InsufficientFunds",
                    "422");
            tally.count("scenario 2 under load: " + (card ? "card" : "wallet"));
            return;
        }
        Answer accepted = other(on).door().post(OFFERS + "/" + offer + "/acceptance", body, customer.token(),
                acceptanceKey);
        judged(tally, "accept", accepted, "422 bnpl.CapitalUnavailable", "409 bnpl.OfferNotAcceptable",
                "409 credit.ApplicantNotEligible", "409 api.IdempotencyInProgress", "422 bnpl.InsufficientFunds",
                "409 bnpl.SessionNotPayable");
        if (accepted.status() == 201 && RANDOMNESS.nextInt(5) == 0) {
            Answer replayed = on.door().post(OFFERS + "/" + offer + "/acceptance", body, customer.token(),
                    acceptanceKey);
            judged(tally, "accept replayed", replayed, "409 api.IdempotencyInProgress");
            if (replayed.status() == 201 && !replayed.body().equals(accepted.body())) {
                tally.unexplained("a replayed acceptance key answered another receipt");
            }
        }
    }

    /** A live obligation of a pool customer no scene owns: {agreement, obligation, purchase, party, merchant}. */
    private Optional<String[]> liveObligation() throws SQLException {
        for (int attempt = 0; attempt < 4; attempt++) {
            String[] found = row("SELECT o.agreement_id::text, o.id::text, o.purchase_id::text, o.party_id::text,"
                    + " p.merchant_id::text FROM bnpl.bnpl_obligation o JOIN bnpl.purchase p ON p.id = o.purchase_id"
                    + " WHERE o.status = 'ACTIVE' ORDER BY random() LIMIT 1");
            if (found != null && !reserved.contains(found[1]) && !reserved.contains(found[3])
                    && byParty.containsKey(UUID.fromString(found[3]))) {
                return Optional.of(found);
            }
        }
        return Optional.empty();
    }

    /**
     * A payer: a repayment of a live obligation funded first by exactly its own card pay-in, the key replayed across
     * instances; a payoff quoted on one instance and executed on the other, funded by exactly the quote; a dispute; a read.
     */
    private void payOnce(Instance on) throws Exception {
        Thread.sleep(200 + RANDOMNESS.nextInt(400));
        Optional<String[]> picked = liveObligation();
        if (picked.isEmpty()) {
            return;
        }
        String[] obligation = picked.get();
        Customer customer = byParty.get(UUID.fromString(obligation[3]));
        String base = AGREEMENTS + "/" + obligation[0];
        int dice = RANDOMNESS.nextInt(100);
        if (dice < 60) {
            long amount = 5_00 + 13 * RANDOMNESS.nextInt(500);
            BnplStormWorld.payIn(on.door(), other(on).door(), customer, amount, key(), tally, null);
            String repaymentKey = key();
            Answer repaid = on.door().post(base + "/repayments", money(amount), customer.token(), repaymentKey);
            judged(tally, "repay", repaid, "409 bnpl.ObligationClosed", "422 bnpl.InsufficientFunds",
                    "409 api.IdempotencyInProgress", "422 bnpl.WalletUnavailable");
            if (repaid.status() == 201 && RANDOMNESS.nextInt(4) == 0) {
                Answer replayed = other(on).door().post(base + "/repayments", money(amount), customer.token(),
                        repaymentKey);
                judged(tally, "repay replayed", replayed, "409 api.IdempotencyInProgress");
                if (replayed.status() == 201 && !replayed.body().equals(repaid.body())) {
                    tally.unexplained("a replayed repayment key answered another receipt");
                }
            }
        } else if (dice < 68) {
            payOff(on, customer, obligation[0]);
        } else if (dice < 74) {
            Answer opened = on.door().post(base + "/disputes", "{\"amount\":\"" + decimal(10_00 + 100L
                    * RANDOMNESS.nextInt(60)) + "\",\"currency\":\"EUR\",\"category\":\"NOT_AS_DESCRIBED\",\"reason\":\"it"
                    + " is not as shown\"}", customer.token(), key());
            judged(tally, "open dispute", opened, "409 bnpl.DisputeOpen", "422 bnpl.RefundExceedsPurchase",
                    "409 bnpl.PurchaseFullyRefunded", "409 bnpl.ObligationClosed", "422 bnpl.AmountOutOfRange");
        } else {
            judged(tally, "read agreement", on.door().get(base, customer.token()));
        }
    }

    /** A payoff quoted on {@code on}, the wallet funded with exactly the quote, executed on the other instance. */
    Answer payOff(Instance on, Customer customer, String agreement) throws Exception {
        String base = AGREEMENTS + "/" + agreement;
        Answer quoted = on.door().post(base + "/payoff-quotes", "{}", customer.token(), key());
        judged(tally, "payoff quote", quoted, "409 bnpl.ObligationClosed");
        if (quoted.status() != 201) {
            return quoted;
        }
        long amount = BnplStormWorld.minor(quoted.field("amount"));
        BnplStormWorld.RENDERED_AMOUNTS.add(amount);
        if (amount > 0) {
            BnplStormWorld.payIn(on.door(), other(on).door(), customer, amount, key(), tally, null);
        }
        Answer paidOff = other(on).door().post(base + "/payoff", "{\"quoteId\":\"" + quoted.field("quoteId") + "\"}",
                customer.token(), key());
        judged(tally, "payoff", paidOff, "409 bnpl.PayoffQuoteStale", "422 bnpl.InsufficientFunds",
                "409 bnpl.ObligationClosed");
        return paidOff;
    }

    /**
     * A merchant: refunds of its own purchases with its refund-scoped key (sometimes the plain key - refused), the key
     * replayed across instances; payouts of what it is owed, the key replayed; answers to its disputes.
     */
    private void merchantOnce(Instance on) throws Exception {
        Thread.sleep(400 + RANDOMNESS.nextInt(600));
        Merchant merchant = merchants.get(RANDOMNESS.nextInt(merchants.size()));
        int dice = RANDOMNESS.nextInt(100);
        if (dice < 40) {
            String[] purchase = row("SELECT p.id::text, f.gross_minor - coalesce((SELECT sum(r.amount_minor) FROM"
                    + " bnpl.bnpl_refund r WHERE r.purchase_id = p.id), 0), o.id::text, p.party_id::text FROM"
                    + " bnpl.purchase p JOIN bnpl.merchant_financing f ON f.purchase_id = p.id JOIN bnpl.bnpl_obligation o"
                    + " ON o.purchase_id = p.id WHERE p.merchant_id = ?::uuid AND p.status = 'FINANCED' ORDER BY random()"
                    + " LIMIT 1", UUID.fromString(merchant.id()));
            if (purchase == null || reserved.contains(purchase[2]) || reserved.contains(purchase[3])) {
                return;
            }
            long remaining = Long.parseLong(purchase[1]);
            long amount = RANDOMNESS.nextInt(5) == 0 ? remaining : Math.max(1_00, remaining / (2 + RANDOMNESS.nextInt(4)));
            String body = "{\"amount\":\"" + decimal(amount) + "\",\"currency\":\"EUR\",\"reason\":\"goods returned\"}";
            String path = PURCHASES + "/" + purchase[0] + "/refunds";
            if (RANDOMNESS.nextInt(10) == 0) {
                Answer unscoped = on.door().post(path, body, merchant.apiKey(), key());
                if (refused(tally, "refund without the scope", unscoped, "403 bnpl.RefundScopeRequired")
                        && unscoped.is(403, "bnpl.RefundScopeRequired")) {
                    tally.count("refund scope refused");
                }
                return;
            }
            String refundKey = key();
            Answer refunded = on.door().post(path, body, merchant.refundKey(), refundKey);
            judged(tally, "refund", refunded, "422 bnpl.RefundExceedsPurchase", "409 bnpl.PurchaseFullyRefunded",
                    "409 bnpl.DisputeOpen", "409 bnpl.RefundNotPostable", "409 api.IdempotencyInProgress");
            if (refunded.status() == 201 && RANDOMNESS.nextInt(5) == 0) {
                Answer replayed = other(on).door().post(path, body, merchant.refundKey(), refundKey);
                judged(tally, "refund replayed", replayed, "409 api.IdempotencyInProgress");
                if (replayed.status() == 201 && !replayed.field("refundId").equals(refunded.field("refundId"))) {
                    tally.unexplained("a replayed refund key answered another refund");
                }
            }
        } else if (dice < 60) {
            long amount = 5_00 + 100L * RANDOMNESS.nextInt(150);
            String payoutKey = key();
            String body = money(amount);
            Answer paid = on.door().post(PAYOUTS, body, merchant.apiKey(), payoutKey);
            judged(tally, "payout", paid, "409 merchant.PayoutUnfunded", "409 api.IdempotencyInProgress");
            if (paid.status() == 201 && RANDOMNESS.nextInt(3) == 0) {
                Answer replayed = other(on).door().post(PAYOUTS, body, merchant.apiKey(), payoutKey);
                judged(tally, "payout replayed", replayed, "409 api.IdempotencyInProgress");
                if (replayed.status() == 201 && !replayed.field("id").equals(paid.field("id"))) {
                    tally.unexplained("a replayed payout key answered another payout");
                }
            }
        } else if (dice < 80) {
            String dispute = scalar("SELECT id::text FROM bnpl.bnpl_dispute WHERE merchant_id = ?::uuid AND status ="
                    + " 'OPEN' ORDER BY random() LIMIT 1", UUID.fromString(merchant.id()));
            if (dispute == null || reserved.contains(dispute)) {
                return;
            }
            boolean accept = RANDOMNESS.nextBoolean();
            Answer answered = on.door().post(MERCHANT_DISPUTES + "/" + dispute + "/response", "{\"decision\":\""
                    + (accept ? "ACCEPT" : "CONTEST") + "\",\"statement\":\"" + (accept ? "refund it" : "we shipped it")
                    + "\"}", merchant.apiKey(), key());
            judged(tally, "dispute response " + (accept ? "accept" : "contest"), answered,
                    "409 bnpl.DisputeNotActionable", "422 bnpl.RefundExceedsPurchase", "409 bnpl.PurchaseFullyRefunded",
                    "409 bnpl.RefundNotPostable");
        } else {
            judged(tally, "payable read", on.door().get("/v1/merchant/payable", merchant.apiKey()));
        }
    }

    /**
     * A servicing agent: a reversal proposed here and decided by the other agent there (now and then the proposer's own
     * approval first - refused); a dispute the merchant contested resolved, above the threshold under four eyes; an
     * officer's audited explanation or replay.
     */
    private void agentOnce(Instance on) throws Exception {
        Thread.sleep(800 + RANDOMNESS.nextInt(1_200));
        String proposer = on == a ? agents[0] : agents[1];
        String approver = on == a ? agents[1] : agents[0];
        int dice = RANDOMNESS.nextInt(100);
        if (dice < 35) {
            String[] repayment = row("SELECT o.agreement_id::text, r.id::text, o.id::text, o.party_id::text FROM"
                    + " bnpl.bnpl_repayment r JOIN bnpl.bnpl_obligation o ON o.id = r.obligation_id WHERE r.kind ="
                    + " 'CUSTOMER' AND o.status = 'ACTIVE' AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_repayment_reversal v"
                    + " WHERE v.repayment_id = r.id) ORDER BY random() LIMIT 1");
            if (repayment == null || reserved.contains(repayment[2]) || reserved.contains(repayment[3])) {
                return;
            }
            String reversal = OPERATOR_AGREEMENTS + "/" + repayment[0] + "/repayments/" + repayment[1] + "/reversal";
            Answer proposed = on.door().post(reversal, "{\"reason\":\"the wallet payment was applied in error\"}",
                    proposer, key());
            judged(tally, "propose reversal", proposed, "409 bnpl.RepaymentNotReversible", "409 bnpl.ProposalPending",
                    "409 bnpl.ObligationClosed");
            if (proposed.status() == 201) {
                String decision = "{\"reversalId\":\"" + proposed.field("reversalId") + "\",\"reason\":\"checked\"}";
                decide(other(on), reversal, decision, proposer, approver, "reversal");
            }
        } else if (dice < 60) {
            String[] dispute = row("SELECT d.id::text, (SELECT c.amount_minor FROM bnpl.bnpl_dispute_claim c WHERE"
                    + " c.dispute_id = d.id)::text FROM bnpl.bnpl_dispute d WHERE d.status = 'MERCHANT_RESPONDED' AND"
                    + " d.proposal_id IS NULL ORDER BY random() LIMIT 1");
            if (dispute == null || reserved.contains(dispute[0])) {
                return;
            }
            resolveDispute(on, dispute[0], RANDOMNESS.nextBoolean() ? "REFUND" : "UPHOLD", proposer, approver);
        } else {
            Optional<String[]> obligation = liveObligation();
            if (obligation.isEmpty()) {
                return;
            }
            String officer = on == a ? bnplOfficers[0] : bnplOfficers[1];
            if (dice < 80) {
                Answer read = on.door().get(OPERATOR_AGREEMENTS + "/" + obligation.get()[0] + "/explanation", officer);
                if (judged(tally, "explanation", read) && read.status() == 200) {
                    tally.audited("bnpl.ExplanationRead");
                }
            } else {
                Answer replayed = on.door().post(OPERATOR_AGREEMENTS + "/" + obligation.get()[0] + "/replay",
                        "{\"reason\":\"the storm's replay\"}", officer, null);
                if (judged(tally, "replay door", replayed) && replayed.status() == 200) {
                    tally.audited("bnpl.Replayed");
                    if (!replayed.body().contains("\"verdict\":\"IDENTICAL\"")) {
                        tally.unexplained("the replay door answered a verdict other than IDENTICAL");
                    }
                }
            }
        }
    }

    /** The other agent's decision - now and then the proposer's own approval first, refused (four eyes). */
    Answer decide(Instance on, String proposal, String decision, String proposer, String approver, String kind)
            throws Exception {
        if (RANDOMNESS.nextInt(4) == 0) {
            Answer self = on.door().post(proposal + "/approval", decision, proposer, key());
            if (refused(tally, kind + " self-approval", self, "403 bnpl.SelfApprovalRefused",
                    "409 bnpl.ProposalNotPending", "409 bnpl.RepaymentNotReversible", "409 bnpl.ObligationClosed")
                    && self.is(403, "bnpl.SelfApprovalRefused")) {
                tally.count("bnpl self-approval refused");
            }
        }
        boolean reject = RANDOMNESS.nextInt(6) == 0;
        Answer decided = on.door().post(proposal + (reject ? "/rejection" : "/approval"), decision, approver, key());
        judged(tally, kind + (reject ? " rejected" : " approved"), decided, "409 bnpl.ProposalNotPending",
                "409 bnpl.RepaymentNotReversible", "409 bnpl.ObligationClosed", "422 bnpl.CapitalUnavailable",
                "422 bnpl.WalletUnavailable", "422 bnpl.WaiverExceedsDue");
        return reject ? null : decided;
    }

    /** A contested dispute resolved on {@code on}: at or below the threshold at once, above it proposed then approved. */
    Answer resolveDispute(Instance on, String dispute, String outcome, String proposer, String approver)
            throws Exception {
        String path = OPERATOR_DISPUTES + "/" + dispute + "/resolution";
        Answer resolved = on.door().post(path, "{\"outcome\":\"" + outcome + "\",\"reason\":\"the evidence read\"}",
                proposer, key());
        judged(tally, "dispute resolution", resolved, "409 bnpl.DisputeNotActionable", "409 bnpl.ProposalPending",
                "422 bnpl.RefundExceedsPurchase", "409 bnpl.PurchaseFullyRefunded", "409 bnpl.RefundNotPostable");
        Optional<String> proposal = resolved.status() >= 200 && resolved.status() < 300
                ? resolved.optionalField("proposalId") : Optional.empty();
        if (proposal.isEmpty() || !"PROPOSED".equals(resolved.optionalField("proposalStatus").orElse(""))) {
            return resolved;
        }
        String decision = "{\"proposalId\":\"" + proposal.get() + "\",\"reason\":\"a second reading\"}";
        if (RANDOMNESS.nextInt(3) == 0) {
            Answer self = other(on).door().post(path + "/approval", decision, proposer, key());
            if (refused(tally, "dispute resolution self-approval", self, "403 bnpl.SelfApprovalRefused",
                    "409 bnpl.ProposalNotPending", "409 bnpl.DisputeNotActionable")
                    && self.is(403, "bnpl.SelfApprovalRefused")) {
                tally.count("bnpl self-approval refused");
            }
        }
        Answer approved = other(on).door().post(path + "/approval", decision, approver, key());
        judged(tally, "dispute resolution approved", approved, "409 bnpl.ProposalNotPending",
                "409 bnpl.DisputeNotActionable", "422 bnpl.RefundExceedsPurchase", "409 bnpl.PurchaseFullyRefunded",
                "409 bnpl.RefundNotPostable");
        return approved;
    }

    /** A borrower: a loan or a line of a pool customer against the one capital row; a draw on a line. */
    private void borrowOnce(Instance on) throws Exception {
        Thread.sleep(1_000 + RANDOMNESS.nextInt(1_500));
        Customer customer = pool.get(RANDOMNESS.nextInt(pool.size()));
        if (reserved.contains(customer.party().toString())) {
            return;
        }
        int dice = RANDOMNESS.nextInt(100);
        if (dice < 40) {
            borrow(on, customer, RANDOMNESS.nextInt(3) == 0, 2_000_00 + 100_00L * RANDOMNESS.nextInt(40));
        } else if (dice < 70) {
            String line = scalar("SELECT l.id::text FROM lending.loan l WHERE l.kind = 'REVOLVING' AND l.status ="
                    + " 'ACTIVE' AND l.party_id = ? ORDER BY random() LIMIT 1", customer.party());
            if (line == null) {
                return;
            }
            Answer drawn = on.door().post(LOANS + "/" + line + "/draws", money(50_00 + 100L * RANDOMNESS.nextInt(500)),
                    customer.token(), key());
            judged(tally, "draw", drawn, "422 lending.LimitExceeded", "409 lending.LineNotDrawable",
                    "409 lending.DrawsSuspended", "422 lending.DrawsSuspended", "409 lending.LoanClosed",
                    "422 lending.CapitalUnavailable", "409 api.IdempotencyInProgress");
        }
    }

    /** A loan or line applied for on {@code on}, decided and offered by the sweeps, accepted on the other; its id or null. */
    String borrow(Instance on, Customer customer, boolean line, long minor) throws Exception {
        String body = line ? BnplStormWorld.lineApplication(Math.min(minor, 2_500_00), 1 + RANDOMNESS.nextInt(28))
                : BnplStormWorld.loanApplication(minor, 6 + RANDOMNESS.nextInt(19), 1 + RANDOMNESS.nextInt(28));
        Answer submitted = on.door().post(LOAN_APPLICATIONS, body, customer.token(), key());
        if (!judged(tally, "loan application", submitted, "409 lending.ApplicationOpen", "409 credit.DecisionRequestOpen",
                "409 api.IdempotencyInProgress") || submitted.status() != 202) {
            return null;
        }
        Optional<String> offer = BnplStormWorld.awaitLoanOffer(submitted.field("applicationId"), Duration.ofSeconds(40));
        if (offer.isEmpty()) {
            tally.count("loan application not offered");
            return null;
        }
        Answer read = on.door().get(LOAN_OFFERS + "/" + offer.get(), customer.token());
        if (!judged(tally, "read loan offer", read) || read.status() != 200) {
            return null;
        }
        Answer accepted = other(on).door().post(LOAN_OFFERS + "/" + offer.get() + "/acceptance",
                "{\"termsSha256\":\"" + read.field("termsSha256") + "\"}", customer.token(), key());
        judged(tally, "loan acceptance", accepted, "422 lending.CapitalUnavailable", "409 lending.OfferNotAcceptable",
                "409 credit.ApplicantNotEligible");
        return accepted.status() == 201 ? accepted.field("loanId") : null;
    }

    /**
     * The weather: payout faults armed per merchant destination (a lost response, merchant then UNKNOWN until the inquiry
     * is answered), inquiries answered truthfully, duplicate sweep ticks on both instances, the origination hint twice, a
     * password-only acceptance, a credit source fault.
     */
    private void chaosOnce() throws Exception {
        Thread.sleep(400 + RANDOMNESS.nextInt(400));
        int dice = RANDOMNESS.nextInt(100);
        if (dice < 10) {
            Merchant merchant = merchants.get(RANDOMNESS.nextInt(merchants.size()));
            provider.losesTheResponseWhenTheBodyContains(PAYOUT_PATH, merchant.destination());
            tally.count("chaos: lost payout responses armed for a merchant");
        } else if (dice < 35) {
            answerInquiries(false);
        } else if (dice < 55) {
            Instance on = RANDOMNESS.nextBoolean() || restarting ? a : b;
            on.beans().getBean(BnplServicingSchedule.class).sweepOnce();
            on.beans().getBean(BnplOriginationSchedule.class).sweepOnce();
            on.beans().getBean(BnplDisputeSchedule.class).sweepOnce();
            tally.count("chaos: duplicate ticks");
        } else if (dice < 65 && !restarting) {
            String request = scalar("SELECT decision_request_id::text FROM bnpl.bnpl_application WHERE status ="
                    + " 'AWAITING_DECISION' ORDER BY random() LIMIT 1");
            if (request != null) {
                bothAtOnce(() -> hint(a, request), () -> hint(b, request));
                tally.count("chaos: the origination hint twice");
            }
        } else if (dice < 70) {
            CreditFixtures.Customer weak = world.passwordOnly();
            String offer = scalar("SELECT id::text FROM bnpl.bnpl_offer WHERE status = 'OFFERED' ORDER BY random()"
                    + " LIMIT 1");
            if (offer != null) {
                Answer attempt = a.door().post(OFFERS + "/" + offer + "/acceptance",
                        BnplStormWorld.acceptance("ab".repeat(32)), weak.token(), key());
                refused(tally, "password-only acceptance", attempt, "403", "404 bnpl.NotFound");
            }
        } else if (dice < 75) {
            CREDIT.armTransientFault(RANDOMNESS.nextBoolean());
            tally.count("chaos: a credit source fault armed");
        }
    }

    private boolean hint(Instance on, String request) {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(BnplStormWorld.flow())) {
            return on.beans().getBean(BnplTransactions.class).inTransaction(uow -> on.beans().getBean(
                    BnplOrigination.class).hintRequest(uow, UUID.fromString(request)));
        }
    }

    /** Treasury: further tranches through the statement - recognised while acceptances hold the capital row. */
    private void treasuryOnce() throws Exception {
        Thread.sleep(6_000 + RANDOMNESS.nextInt(4_000));
        if (headroom() > TRANCHE) {
            return;
        }
        Door proposing = RANDOMNESS.nextBoolean() ? a.door() : b.door();
        world.tranche(proposing, other(proposing), treasurers[0], treasurers[1], TRANCHE, true, tally);
    }

    long headroom() {
        return a.beans().getBean(BnplTransactions.class).inTransaction(uow -> a.beans().getBean(
                LendingCapitalHeadroom.class).atRest(uow, CurrencyCode.of("EUR"))).headroom().minorUnits();
    }

    /**
     * The providers answering inquiries TRUTHFULLY, by OUR reference: a payout or a card operation whose answer was lost
     * is answered as executed when its request reached the provider; one that never did (its instance died between its
     * commit and the wire) is answered unrecognised once it has been in flight longer than any send takes - never earlier,
     * so an answer can never deny a send still on its way (the lending storm's round-5 lesson).
     */
    synchronized void answerInquiries(boolean atRest) throws SQLException {
        List<String> sent = provider.headerValues(PAYOUT_PATH, "Idempotency-Key");
        for (String[] payout : rows("SELECT provider_idempotency_reference, (last_dispatched_at < now() - interval '15"
                + " seconds')::text FROM merchant.merchant_payout WHERE status IN ('DISPATCHED', 'UNKNOWN')")) {
            if (sent.contains(payout[0])) {
                if (answered.add(payout[0])) {
                    provider.succeedsWith(PAYOUT_PATH + "/" + payout[0], 200, "{\"status\":\"paid\",\"reference\":\""
                            + BnplStormWorld.providerReferenceOf(payout[0]) + "\"}");
                    tally.count("chaos: a payout inquiry answered paid");
                }
            } else if ("true".equals(payout[1]) && answered.add(payout[0])) {
                provider.succeedsWith(PAYOUT_PATH + "/" + payout[0], 200, "{\"status\":\"unrecognised\"}");
                tally.count("chaos: a payout inquiry answered unrecognised");
            }
        }
        for (String[] attempt : rows("SELECT a.status, a.auth_reference, a.capture_reference, a.void_reference,"
                + " (a.created_at < now() - interval '15 seconds')::text FROM payments.payment_attempt a WHERE a.rail ="
                + " 'card' AND a.status IN ('AUTH_DISPATCHED', 'AUTH_UNKNOWN', 'CAPTURE_DISPATCHED', 'CAPTURE_UNKNOWN',"
                + " 'VOID_DISPATCHED', 'VOID_UNKNOWN')")) {
            String reference = attempt[0].startsWith("AUTH") ? attempt[1] : attempt[0].startsWith("CAPTURE") ? attempt[2]
                    : attempt[3];
            String path = attempt[0].startsWith("AUTH") ? SimulatedCardPspAdapter.AUTHORIZATIONS_PATH
                    : attempt[0].startsWith("CAPTURE") ? SimulatedCardPspAdapter.CAPTURES_PATH
                    : SimulatedCardPspAdapter.VOIDS_PATH;
            if (reference == null || answered.contains(reference)) {
                continue;
            }
            if (provider.headerValues(path, "Idempotency-Key").contains(reference)) {
                answered.add(reference);
                provider.succeedsWith(SimulatedCardPspAdapter.OPERATIONS_PATH + reference, 200,
                        "{\"status\":\"approved\",\"reference\":\"psp_q-" + reference + "\"}");
                tally.count("chaos: a card inquiry answered approved");
            } else if ("true".equals(attempt[4]) && answered.add(reference)) {
                provider.succeedsWith(SimulatedCardPspAdapter.OPERATIONS_PATH + reference, 200,
                        "{\"status\":\"unrecognised\"}");
                tally.count("chaos: a card inquiry answered unrecognised");
            }
        }
    }

    private final Set<String> answered = ConcurrentHashMap.newKeySet();

    /**
     * BNPL pay-in-4 terms v2 mid-load, four eyes across instances: offers outstanding keep their pinned version. One offer
     * is made on purpose just before it and accepted just after it - so the run holds at least one purchase that must pin
     * its offer's version across the activation (I11; asserted at rest from the rows).
     */
    private Void activateTermsMidLoad() throws Exception {
        Customer customer = pool.get(RANDOMNESS.nextInt(pool.size()));
        Merchant merchant = merchants.get(0);
        long gross = 240_00;
        String[] session = BnplStormWorld.openSession(a.door(), merchant, gross, tally);
        Answer applied = a.door().post(APPLICATIONS, BnplStormWorld.payIn4(session[1]), customer.token(), key());
        judged(tally, "apply before v2", applied, "409 credit.DecisionRequestOpen");
        Optional<String> offer = applied.status() == 202
                ? BnplStormWorld.awaitOffer(applied.field("applicationId"), Duration.ofSeconds(60)) : Optional.empty();
        String hash = offer.isPresent() ? BnplStormWorld.termsHash(a.door(), customer.token(), offer.get()) : null;
        termsV2 = world.activateTerms(BnplStormWorld.BNPL_TERMS, b.door(), a.door(), bnplOfficers[1], bnplOfficers[0],
                BnplStormWorld.bnplTerms("BNPL_PAY_IN_4", Map.of("lateFeeMinor", "600", "reason", "\"v2 mid-storm\"")),
                "bnpl.SelfApprovalRefused", tally);
        tally.count("BNPL terms v2 activated mid-load");
        if (offer.isEmpty()) {
            tally.count("the offer made before v2 was not offered (declined or not decided)");
            return null;
        }
        BnplStormWorld.payIn(a.door(), b.door(), customer, firstInstalment(gross), key(), tally, null);
        for (int attempt = 0; attempt < 12; attempt++) {
            Answer accepted = b.door().post(OFFERS + "/" + offer.get() + "/acceptance", BnplStormWorld.acceptance(hash),
                    customer.token(), key());
            judged(tally, "accept after v2", accepted, "422 bnpl.CapitalUnavailable", "409 credit.ApplicantNotEligible");
            if (accepted.status() == 201) {
                tally.count("an offer made before v2 accepted after it");
                return null;
            }
            if (!accepted.is(422, "bnpl.CapitalUnavailable")) {
                return null;
            }
            Thread.sleep(5_000);
        }
        return null;
    }

    // ---------------------------------------------------------------- the boundaries (the lending cohorts, J1)

    /**
     * From {@code T_k - 8 s} to {@code T_k + 8 s} both instances drive the cohort's loan directly - A five seconds ahead of
     * the server, B five behind - beside their own sweeps, so the boundary is judged by both skewed instances on both sides
     * of the real midnight. Only the database clock may decide it.
     */
    private void boundaryChoreography(String[] cohort) throws Exception {
        Instant boundary = Instant.parse(cohort[0]);
        while (BnplStormWorld.databaseNow().isBefore(boundary.minusSeconds(8))) {
            Thread.sleep(200);
        }
        while (BnplStormWorld.databaseNow().isBefore(boundary.plusSeconds(8))) {
            for (Instance on : restarting ? List.of(a) : List.of(a, b)) {
                try {
                    lendingStep(on, cohort[1]);
                    tally.count("boundary: a direct lending servicing step");
                } catch (RuntimeException failure) {
                    tally.unexplained("a direct lending servicing step at the boundary failed: "
                            + failure.getClass().getSimpleName());
                }
            }
            Thread.sleep(250);
        }
        coverage("boundary crossed", 1);
    }

    private void lendingStep(Instance on, String loan) {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(BnplStormWorld.flow())) {
            com.finapp.lending.LoanServicing servicing = on.beans().getBean(com.finapp.lending.LoanServicing.class);
            on.beans().getBean(BnplTransactions.class).inTransaction(uow -> servicing.service(uow,
                    com.finapp.lending.LoanId.of(UUID.fromString(loan))));
        }
    }

    /** At each cohort's midnight the day before it accrued once on its loan - judged on the database clock in its zone. */
    private void assertTheBoundariesWereCrossed() throws Exception {
        SoftAssertions softly = new SoftAssertions();
        for (String[] cohort : cohorts) {
            ZoneOffset zone = "UTC".equals(cohort[2]) ? ZoneOffset.UTC : ZoneOffset.of(cohort[2]);
            java.time.LocalDate lastDay = Instant.parse(cohort[0]).atOffset(zone).toLocalDate().minusDays(1);
            long accrued = count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ?::uuid AND accrual_date = ?",
                    UUID.fromString(cohort[1]), lastDay);
            softly.assertThat(accrued).as("the cohort's last day before its midnight accrued once").isEqualTo(1);
            coverage("a real midnight: the day before it accrued once", (int) accrued);
        }
        softly.assertAll();
        for (String[] cohort : cohorts) {
            reserved.remove(cohort[1]);
        }
    }

    // ================================================================== rest, and every census

    /** A claim pushes the permit one retry cadence ahead (2 s) while its step runs: a permit within this may be in flight. */
    private static final String IN_FLIGHT = "interval '3 seconds'";

    /** The movers stopped; the sweeps drain to the quiet predicates on the database. */
    void rest(String when) throws Exception {
        long deadline = System.nanoTime() + REST_BOUND.toNanos();
        String busy = "SELECT 'decision request', count(*) FROM credit.decision_request WHERE status IN ('SUBMITTED',"
                + " 'COLLECTING', 'READY', 'EVALUATED') UNION ALL SELECT 'bnpl application', count(*) FROM"
                + " bnpl.bnpl_application WHERE status IN ('SUBMITTED', 'AWAITING_DECISION') UNION ALL SELECT"
                + " 'loan application', count(*) FROM lending.loan_application WHERE status IN ('SUBMITTED',"
                + " 'AWAITING_DECISION') UNION ALL SELECT 'pending loan', count(*) FROM lending.loan WHERE status ="
                + " 'PENDING_DISBURSEMENT' UNION ALL SELECT 'unserviced loan', count(*) FROM lending.loan WHERE status IN"
                + " ('ACTIVE', 'CLOSING') AND next_servicing_at <= statement_timestamp() + " + IN_FLIGHT + " UNION ALL"
                + " SELECT 'unserviced obligation', count(*) FROM bnpl.bnpl_obligation WHERE status = 'ACTIVE' AND"
                + " next_servicing_at <= statement_timestamp() + " + IN_FLIGHT + " UNION ALL SELECT 'payout in flight',"
                + " count(*) FROM merchant.merchant_payout WHERE status IN ('DISPATCHED', 'UNKNOWN') UNION ALL SELECT"
                + " 'card in flight', count(*) FROM payments.payment_attempt WHERE rail = 'card' AND status IN"
                + " ('AUTH_DISPATCHED', 'AUTH_UNKNOWN', 'CAPTURE_DISPATCHED', 'CAPTURE_UNKNOWN', 'VOID_DISPATCHED',"
                + " 'VOID_UNKNOWN') UNION ALL SELECT 'lapsed dispute', count(*) FROM bnpl.bnpl_dispute WHERE status ="
                + " 'OPEN' AND response_deadline_at <= statement_timestamp() UNION ALL SELECT 'lapsed offer', count(*)"
                + " FROM bnpl.bnpl_offer WHERE status = 'OFFERED' AND expires_at <= statement_timestamp()";
        while (true) {
            answerInquiries(true);
            List<String[]> state = rows(busy);
            if (state.stream().allMatch(line -> "0".equals(line[1]))) {
                return;
            }
            assertThat(System.nanoTime()).as("%s: the storm comes to rest - still busy: %s", when, state.stream()
                    .filter(line -> !"0".equals(line[1])).map(line -> line[0] + "=" + line[1]).toList())
                    .isLessThan(deadline);
            Thread.sleep(500);
        }
    }

    /** One reading of the censuses - safety, and at rest every census (I2): liveness, audit, meters, gauges, dispatches. */
    void census(String when, boolean atRest) throws Exception {
        SoftAssertions softly = new SoftAssertions();
        census.read(when, atRest, softly);
        if (atRest) {
            theAuditCountsEveryAct(when, softly);
            theMetersCountTheRows(when, softly);
            theGaugesAtRest(when, softly);
            theDispatchesOnBothSides(when, softly);
        }
        if (!partial()) {
            softly.assertThat(tally.unexplained).as("%s: unexplained failures", when).isEmpty();
        }
        softly.assertAll();
    }

    void restAndCensus(String when) throws Exception {
        rest(when);
        census(when, true);
        System.out.printf("P12-TST-001 SCENE: %s - every census exact (%d s into the storm)%n", when,
                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - stormBegan));
    }

    /** Every act counted against its audit rows - every rest; the refusals FAILED, exactly the storm's. */
    private void theAuditCountsEveryAct(String when, SoftAssertions softly) throws Exception {
        Map<String, String> equal = new LinkedHashMap<>();
        equal.put("bnpl.TermsVersionProposed", "SELECT count(*) FROM bnpl.bnpl_terms_version");
        equal.put("bnpl.TermsVersionActivated", "SELECT count(*) FROM bnpl.bnpl_terms_version WHERE status IN ('ACTIVE',"
                + " 'RETIRED')");
        equal.put("bnpl.ApplicationSubmitted", "SELECT count(*) FROM bnpl.bnpl_application");
        equal.put("bnpl.ApplicationWithdrawn", "SELECT count(*) FROM bnpl.bnpl_application WHERE status = 'WITHDRAWN'");
        equal.put("bnpl.OfferDeclined", "SELECT count(*) FROM bnpl.bnpl_offer WHERE status = 'DECLINED'");
        equal.put("bnpl.OfferAccepted", "SELECT count(*) FROM bnpl.purchase");
        equal.put("bnpl.MerchantFinanced", "SELECT count(*) FROM bnpl.merchant_financing");
        equal.put("bnpl.RefundApplied", "SELECT count(*) FROM bnpl.bnpl_refund");
        equal.put("bnpl.RepaymentReversalProposed", "SELECT count(*) FROM bnpl.bnpl_repayment_reversal");
        equal.put("bnpl.RepaymentReversalApproved", "SELECT count(*) FROM bnpl.bnpl_repayment_reversal WHERE status ="
                + " 'APPROVED'");
        equal.put("bnpl.RepaymentReversalRejected", "SELECT count(*) FROM bnpl.bnpl_repayment_reversal WHERE status ="
                + " 'REJECTED'");
        equal.put("bnpl.WaiverProposed", "SELECT count(*) FROM bnpl.bnpl_waiver");
        equal.put("bnpl.WaiverApproved", "SELECT count(*) FROM bnpl.bnpl_waiver_decision WHERE outcome = 'APPROVED'");
        equal.put("bnpl.WaiverRejected", "SELECT count(*) FROM bnpl.bnpl_waiver_decision WHERE outcome = 'REJECTED'");
        equal.put("bnpl.DisputeOpened", "SELECT count(*) FROM bnpl.bnpl_dispute");
        equal.put("bnpl.DisputeResponded", "SELECT count(*) FROM bnpl.bnpl_dispute WHERE merchant_responded_at IS NOT"
                + " NULL");
        equal.put("bnpl.DisputeResolved", "SELECT count(*) FROM bnpl.bnpl_dispute WHERE status IN ('RESOLVED_REFUND',"
                + " 'RESOLVED_UPHELD')");
        equal.put("bnpl.DisputeWithdrawn", "SELECT count(*) FROM bnpl.bnpl_dispute WHERE status = 'WITHDRAWN'");
        equal.put("bnpl.DisputeResolutionProposed", "SELECT count(*) FROM bnpl.bnpl_dispute_proposal");
        equal.put("bnpl.DisputeResolutionRejected", "SELECT count(*) FROM bnpl.bnpl_dispute_proposal WHERE status ="
                + " 'REJECTED'");
        Map<String, String> broken = new TreeMap<>();
        for (Map.Entry<String, String> pair : equal.entrySet()) {
            long rows = count(pair.getValue());
            long audited = count("SELECT count(*) FROM platform.audit_record WHERE operation = ? AND outcome ="
                    + " 'SUCCEEDED'", pair.getKey());
            if (rows != audited) {
                broken.put(pair.getKey(), "rows " + rows + ", audited " + audited);
            }
        }
        long selfApprovals = count("SELECT count(*) FROM platform.audit_record WHERE operation = 'bnpl.SelfApprovalRefused'"
                + " AND outcome = 'FAILED'");
        long expected = tally.of("bnpl self-approval refused") + tally.of("terms self-approval 403 bnpl.SelfApprovalRefused");
        if (selfApprovals != expected) {
            broken.put("bnpl.SelfApprovalRefused FAILED", "audited " + selfApprovals + ", refused " + expected);
        }
        long scope = count("SELECT count(*) FROM platform.audit_record WHERE operation = 'bnpl.RefundScopeRefused' AND"
                + " outcome = 'FAILED'");
        if (scope != tally.of("refund scope refused")) {
            broken.put("bnpl.RefundScopeRefused FAILED", "audited " + scope + ", refused " + tally.of("refund scope refused"));
        }
        long dealing = count("SELECT count(*) FROM platform.audit_record WHERE operation = 'bnpl.SelfDealingRefused' AND"
                + " outcome = 'FAILED'");
        if (dealing != tally.of("bnpl self-dealing refused")) {
            broken.put("bnpl.SelfDealingRefused FAILED", "audited " + dealing + ", refused "
                    + tally.of("bnpl self-dealing refused"));
        }
        for (String read : List.of("bnpl.ExplanationRead", "bnpl.Replayed")) {
            long audited = count("SELECT count(*) FROM platform.audit_record WHERE operation = ? AND outcome ="
                    + " 'SUCCEEDED'", read);
            if (audited != tally.auditedReads(read)) {
                broken.put(read, "audited " + audited + ", read " + tally.auditedReads(read));
            }
        }
        softly.assertThat(broken).as("%s: C16 every act counted against its audit rows (the refusals FAILED)", when)
                .isEmpty();
    }

    /** One meter arm: a series (both instances and the killed B summed), the rows it counts, exact or never above. */
    private record MeterArm(String name, ToDoubleFunction<MeterRegistry> meter, String rows, boolean exact) {}

    private static double counter(MeterRegistry registry, String name, String... tags) {
        var search = registry.find(name);
        for (int i = 0; i + 1 < tags.length; i += 2) {
            search = search.tag(tags[i], tags[i + 1]);
        }
        return search.counters().stream().mapToDouble(Counter::count).sum();
    }

    private static List<MeterArm> meterArms() {
        return List.of(
                new MeterArm("applications", r -> counter(r, BnplApplicationMetrics.APPLICATION, "outcome", "submitted"),
                        "SELECT count(*) FROM bnpl.bnpl_application", true),
                new MeterArm("offers", r -> counter(r, BnplOriginationMetrics.OFFER, "outcome", "offered"),
                        "SELECT count(*) FROM bnpl.bnpl_offer", true),
                new MeterArm("purchases financed", r -> counter(r, BnplPurchaseMetrics.PURCHASE, "outcome", "financed"),
                        "SELECT count(*) FROM bnpl.purchase", true),
                new MeterArm("refunds - merchant, operator and a dispute's", r -> counter(r, BnplRefundMetrics.REFUND,
                        "outcome", "applied") + counter(r, BnplRefundMetrics.REFUND, "outcome", "closed"),
                        "SELECT count(*) FROM bnpl.bnpl_refund", true),
                new MeterArm("customer repayments", r -> counter(r, BnplRepaymentMetrics.REPAYMENT, "kind", "customer",
                        "outcome", "repaid") + counter(r, BnplRepaymentMetrics.REPAYMENT, "kind", "customer", "outcome",
                        "closed"), "SELECT count(*) FROM bnpl.bnpl_repayment WHERE kind = 'CUSTOMER'", true),
                new MeterArm("auto-collections", r -> counter(r, BnplRepaymentMetrics.REPAYMENT, "kind", "auto_collection",
                        "outcome", "repaid") + counter(r, BnplRepaymentMetrics.REPAYMENT, "kind", "auto_collection",
                        "outcome", "closed"), "SELECT count(*) FROM bnpl.bnpl_repayment WHERE kind = 'AUTO_COLLECTION'",
                        true),
                new MeterArm("collection attempts", r -> counter(r, BnplServicingMetrics.COLLECTION),
                        "SELECT count(*) FROM bnpl.bnpl_collection_attempt", true),
                new MeterArm("late fees", r -> counter(r, BnplServicingMetrics.FEE),
                        "SELECT count(*) FROM bnpl.bnpl_fee_assessment", true),
                new MeterArm("payoffs", r -> counter(r, BnplPayoffMetrics.PAYOFF, "outcome", "paid_off"),
                        "SELECT count(*) FROM bnpl.bnpl_payoff_execution", true),
                new MeterArm("disputes opened", r -> counter(r, BnplDisputeMetrics.DISPUTE, "outcome", "opened"),
                        "SELECT count(*) FROM bnpl.bnpl_dispute", true),
                new MeterArm("reversals approved", r -> counter(r, BnplCorrectionMetrics.REVERSAL, "outcome", "approved"),
                        "SELECT count(*) FROM bnpl.bnpl_repayment_reversal WHERE status = 'APPROVED'", true),
                new MeterArm("accruals", r -> counter(r, BnplAccrualMetrics.ACCRUAL, "outcome", "posted")
                        + counter(r, BnplAccrualMetrics.ACCRUAL, "outcome", "zero"),
                        "SELECT count(*) FROM bnpl.bnpl_interest_accrual", false),
                new MeterArm("instalment billings", r -> counter(r, BnplServicingMetrics.BILLING, "outcome", "billed"),
                        "SELECT count(*) FROM bnpl.bnpl_billing", false));
    }

    private double metered(MeterArm arm) {
        return arm.meter().applyAsDouble(a.meters()) + (restarting ? 0 : arm.meter().applyAsDouble(b.meters()))
                + killedB.getOrDefault(arm.name(), 0.0);
    }

    /**
     * The meters over the WHOLE run (the C19 lesson - not a window): A's, the current B's and the killed B's read after its
     * close, against the committed rows - every act counted once, after its commit. The servicing step's series a door's
     * catch-up also writes (accruals, billings) are never above the rows.
     */
    private void theMetersCountTheRows(String when, SoftAssertions softly) throws Exception {
        Map<String, String> report = new LinkedHashMap<>();
        for (int attempt = 0; attempt < 20; attempt++) {
            report.clear();
            boolean still = true;
            for (MeterArm arm : meterArms()) {
                double before = metered(arm);
                long rows = count(arm.rows());
                still &= before == metered(arm);
                report.put(arm.name(), Math.round(before) + (arm.exact() ? "=" : "<=") + rows);
            }
            if (still) {
                break;
            }
            Thread.sleep(250);
        }
        Map<String, String> broken = new TreeMap<>();
        for (MeterArm arm : meterArms()) {
            long meters = Math.round(metered(arm));
            long rows = count(arm.rows());
            if (arm.exact() ? meters != rows : meters > rows) {
                broken.put(arm.name(), "meters " + meters + ", rows " + rows);
            }
        }
        lastMeters = report;
        softly.assertThat(broken).as("%s: C17 the meters count the rows over the whole run", when).isEmpty();
        double failed = servicingFailures() - servicingFaultsInjected.get();
        softly.assertThat(failed).as("%s: C17 no servicing sweep failed but by an armed fault", when).isZero();
    }

    private volatile Map<String, String> lastMeters = Map.of();

    double servicingFailures() {
        return counter(a.meters(), BnplServicingMetrics.SERVICING, "outcome", "failed")
                + (restarting ? 0 : counter(b.meters(), BnplServicingMetrics.SERVICING, "outcome", "failed"))
                + killedB.getOrDefault("servicing failures", 0.0);
    }

    AtomicInteger servicingFaultsInjected() {
        return servicingFaultsInjected;
    }

    /** On both instances the proofs read now, the ages and the lags zero, the sweepers enabled - every rest. */
    private void theGaugesAtRest(String when, SoftAssertions softly) throws Exception {
        for (Instance on : restarting ? List.of(a) : List.of(a, b)) {
            BnplProofs.Reading proofs = on.beans().getBean(BnplProofMetrics.class).readNow();
            softly.assertThat(proofs.failing()).as("%s: C18 %s subledger gauge FAILS", when, on.name()).isZero();
            softly.assertThat(proofs.replaying(BnplReplayProof.Verdict.DIVERGED)).as("%s: C18 %s replay gauge DIVERGED",
                    when, on.name()).isZero();
            softly.assertThat(proofs.currencies(false)).as("%s: C18 %s three-way gauge failing", when, on.name()).isZero();
            softly.assertThat(on.beans().getBean(ExposureCensusMetrics.class).readNow().failing())
                    .as("%s: C18 %s exposure census gauge failing", when, on.name()).isZero();
            for (String sweeper : List.of("origination", "servicing", "dispute")) {
                softly.assertThat(gauge(on, "finapp.bnpl." + sweeper + ".sweeper.enabled"))
                        .as("%s: C18 %s runs the BNPL %s sweep", when, on.name(), sweeper).isEqualTo(1.0);
            }
            for (String age : List.of(BnplAccrualMetrics.LAG, BnplAwaitingDecisionMetrics.AWAITING_AGE)) {
                softly.assertThat(awaitGauge(on, age)).as("%s: C18 %s %s at rest", when, on.name(), age).isZero();
            }
            softly.assertThat(counter(on.meters(), BnplRepaymentMetrics.ANOMALY)).as("%s: C18 %s allocation anomalies",
                    when, on.name()).isZero();
        }
    }

    private static double gauge(Instance on, String name) {
        return on.meters().find(name).gauges().stream().mapToDouble(Gauge::value).sum();
    }

    private static double awaitGauge(Instance on, String name) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        double value = gauge(on, name);
        while (value != 0 && System.nanoTime() < deadline) {
            Thread.sleep(500);
            value = gauge(on, name);
        }
        return value;
    }

    /**
     * Dispatches equal on both sides (owner item #16, the Phase 11 review's finding 16): at rest, every merchant payout
     * row's reference sent to the provider exactly once and no send the rows do not name; every card capture reference
     * captured at most once at the PSP (INV-PAY-04). A re-dispatch on UNKNOWN replays its key to the provider - the count
     * is what makes it catchable.
     */
    private void theDispatchesOnBothSides(String when, SoftAssertions softly) throws Exception {
        Map<String, Integer> sends = new TreeMap<>();
        provider.headerValues(PAYOUT_PATH, "Idempotency-Key").forEach(reference -> sends.merge(reference, 1, Integer::sum));
        List<String> payouts = strings("SELECT provider_idempotency_reference FROM merchant.merchant_payout");
        // A payout concluded never sent - its instance killed between its commit and the wire (B's restart), the inquiry
        // answering unrecognised, or the first send refused - is the one row the provider rightly never received.
        List<String> neverSent = strings("SELECT provider_idempotency_reference FROM merchant.merchant_payout WHERE"
                + " status = 'FAILED' AND failure_reason IN ('NEVER_RECEIVED', 'PROVIDER_UNAVAILABLE')");
        long resent = sends.values().stream().filter(times -> times > 1).count();
        long unsent = payouts.stream().filter(reference -> !sends.containsKey(reference) && !neverSent.contains(reference))
                .count();
        long orphans = sends.keySet().stream().filter(reference -> !payouts.contains(reference)).count();
        softly.assertThat(resent).as("%s: C13 a payout sent twice at the provider (INV-PAY-04)", when).isZero();
        softly.assertThat(unsent).as("%s: C13 a payout row the provider never received", when).isZero();
        softly.assertThat(orphans).as("%s: C13 a send at the provider no payout row names", when).isZero();
        softly.assertThat(strings("SELECT provider_idempotency_reference FROM merchant.merchant_payout WHERE status ="
                + " 'FAILED' AND failure_reason = 'NEVER_RECEIVED'").stream().filter(sends::containsKey).count())
                .as("%s: C13 a payout concluded never received that the provider received", when).isZero();
        Map<String, Integer> captures = new TreeMap<>();
        provider.headerValues(SimulatedCardPspAdapter.CAPTURES_PATH, "Idempotency-Key")
                .forEach(reference -> captures.merge(reference, 1, Integer::sum));
        softly.assertThat(captures.values().stream().filter(times -> times > 1).count())
                .as("%s: C13 a card capture sent twice at the PSP (INV-PAY-04)", when).isZero();
        lastDispatches = payouts.size() + " payouts = " + sends.size() + " sends + " + neverSent.stream()
                .filter(reference -> !sends.containsKey(reference)).count() + " concluded never sent";
    }

    private volatile String lastDispatches = "";

    // ================================================================== 4. at rest

    /** The cash proof holds (capital only from the statement) and every ledger projection replays CLEAN. */
    private void assertTheCashAndTheProjections() throws Exception {
        try (Connection snapshot = DatabaseRoles.application()) {
            snapshot.setAutoCommit(false);
            snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            snapshot.setReadOnly(true);
            try {
                PositionProof.Report position = context.getBean(PositionProof.class).sweep(snapshot);
                assertThat(position.cashCurrenciesFailing()).as("the cash proof: CASH_AT_BANK is the statement chain's"
                        + " closing (INV-SET-06) - the storm planted no money").isZero();
                ProjectionVerification.Report projections =
                        new ProjectionVerification(new JdbcBalanceDerivation()).verify(snapshot);
                assertThat(projections.drifting()).as("every ledger projection replays from zero CLEAN").isZero();
                System.out.printf("P12-TST-001 PROJECTIONS: %d verified, %d drifting, %d in flight%n",
                        projections.verified(), projections.drifting(), projections.inFlight());
            } finally {
                snapshot.rollback();
            }
        }
    }

    /**
     * The merchant payouts' expectations matched by Phase 8's evidence for every completed payout (the entry's
     * reconciliation implication): the payout provider's report naming every COMPLETED payout, accepted and matched -
     * every MERCHANT_PAYOUT expectation SETTLED by its provider reference, the report's items all matched or checked,
     * no break.
     */
    private void assertThePayoutsMatchedByTheProvidersReport() throws Exception {
        List<String[]> completed = rows("SELECT provider_reference, provider_idempotency_reference, amount_minor::text"
                + " FROM merchant.merchant_payout WHERE status = 'COMPLETED' ORDER BY created_at");
        assertThat(completed).as("the storm completed payouts").isNotEmpty();
        UUID batch = world.payoutReport(completed);
        long settled = count("SELECT count(*) FROM reconciliation.expectation e JOIN merchant.merchant_payout p ON"
                + " e.operation_ref = p.id::text WHERE e.kind = 'MERCHANT_PAYOUT' AND p.status = 'COMPLETED' AND e.status ="
                + " 'SETTLED' AND EXISTS (SELECT 1 FROM reconciliation.allocation a JOIN reconciliation.match_decision d ON"
                + " d.id = a.decision_id WHERE a.expectation_id = e.id AND d.matched_key_kind = 'PAYOUT_PROVIDER_REF')");
        long breaks = count("SELECT count(*) FROM reconciliation.break b JOIN reconciliation.external_item i ON i.id ="
                + " b.external_item_id JOIN reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE r.batch_id ="
                + " ?::uuid", batch);
        long unmatched = count("SELECT count(*) FROM reconciliation.external_item i JOIN reconciliation.reconciliation_batch"
                + " r ON r.id = i.run_id WHERE r.batch_id = ?::uuid AND i.status NOT IN ('MATCHED', 'CHECKED')", batch);
        System.out.printf("P12-TST-001 RECONCILED: %d completed payouts, %d expectations settled by the provider's report,"
                + " %d breaks, %d items unmatched%n", completed.size(), settled, breaks, unmatched);
        assertThat(settled).as("every completed payout's expectation settled by the provider's report (Phase 8's"
                + " evidence)").isEqualTo(completed.size());
        assertThat(breaks).as("the provider's report raised no break").isZero();
        assertThat(unmatched).as("every item of the provider's report matched or checked").isZero();
    }

    /** I11: every pay-in-4 offer made before terms v2 and accepted after it pins the OFFER's version, never v2. */
    private void assertTheTermsWerePinnedAcrossTheActivation() throws Exception {
        assertThat(termsV2).as("BNPL terms v2 was activated mid-load").isNotNull();
        String[] pinned = row("SELECT count(*)::text, count(*) FILTER (WHERE g.terms_version_id <> o.terms_version_id OR"
                + " g.terms_version_id = v.id)::text FROM bnpl.bnpl_offer o JOIN bnpl.purchase p ON p.offer_id = o.id"
                + " JOIN bnpl.bnpl_obligation x ON x.purchase_id = p.id JOIN bnpl.bnpl_agreement g ON g.agreement_id ="
                + " x.agreement_id AND g.version = 1 JOIN bnpl.bnpl_terms_version v ON v.id = ?::uuid WHERE o.product ="
                + " v.product AND o.offered_at < v.effective_from AND p.authorised_at > v.effective_from",
                UUID.fromString(termsV2));
        System.out.printf("P12-TST-001 PINNED: %s offers made before terms v2 and accepted after it%n", pinned[0]);
        assertThat(Long.parseLong(pinned[0])).as("I11 an offer made before v2 was accepted after it").isPositive();
        assertThat(Long.parseLong(pinned[1])).as("I11 each such agreement pins its offer's version, never v2").isZero();
    }

    /** Every named lock wait seen as the act's own backend (M3, C17): L0, B3, B5, B4, L6 and (P) in both UUID orders. */
    private void assertTheNamedLockWaitsWereCounted() {
        Map<String, Integer> counted = new TreeMap<>();
        lockWaits.forEach((lock, seen) -> counted.put(lock, seen.get()));
        System.out.println("P12-TST-001 LOCK WAITS (each the act's own backend, unanswered while held): " + counted);
        SoftAssertions softly = new SoftAssertions();
        for (String lock : List.of("L0", "B3", "B5", "B4", "L6", "(P) payable first", "(P) wallet first")) {
            softly.assertThat(counted.getOrDefault(lock, 0)).as("the named lock wait %s counted", lock).isPositive();
        }
        softly.assertAll();
    }

    /**
     * The hot-row characterisation (owner item #21, A2-F1): per round, the waits sampled on the capital row and the three
     * income rows and their length distribution - recorded; the pass is no deadlock (every census), no lock timeout (every
     * answer in its closed set) and no income row held across a wallet wait (the catalogue's A2-F1 scene).
     */
    private void assertTheHotRows() {
        synchronized (hotRows.rounds) {
            hotRows.rounds.forEach((round, byRow) -> System.out.println("P12-TST-001 HOT ROWS " + round + ": "
                    + BnplStormLocks.HotRows.describe(byRow)));
        }
        System.out.println("P12-TST-001 HOT ROWS (the whole run): " + BnplStormLocks.HotRows.describe(hotRows.totals())
                + "; " + hotRows.samples + " samples, " + hotRows.samplerErrors + " sampler errors");
        assertThat(hotRows.samples).as("the hot rows were sampled").isPositive();
    }

    private static final java.util.regex.Pattern IDENTIFIER =
            java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final java.util.regex.Pattern RENDERED_AMOUNT =
            java.util.regex.Pattern.compile("(?<![0-9.:\\-])([0-9]{3,})\\.([0-9]{2})(?![0-9])");

    /**
     * The needle and "no figure in its logs" (the C1 lesson): no response carried the needle, no log line holds it; and no
     * obligation, purchase, merchant or party id of ANY scene and no amount the storm rendered of at least 100.00 stands as
     * a whole token in the captured output.
     */
    private void assertTheNeedleReachedNoSink(CapturedOutput output) throws Exception {
        assertThat(tally.needleSightings).as("no response carried the marker").isEmpty();
        String captured = output.getAll();
        assertThat(captured).as("no log line carries the marker").doesNotContain(NEEDLE);
        Set<String> identifiers = new java.util.HashSet<>(strings("SELECT id::text FROM bnpl.bnpl_obligation UNION SELECT"
                + " id::text FROM bnpl.purchase UNION SELECT merchant_id::text FROM bnpl.purchase UNION SELECT"
                + " party_id::text FROM bnpl.bnpl_application UNION SELECT agreement_id::text FROM bnpl.bnpl_obligation"
                + " UNION SELECT id::text FROM lending.loan"));
        Set<Long> amounts = new java.util.HashSet<>();
        for (Long minor : BnplStormWorld.RENDERED_AMOUNTS) {
            if (minor >= 100_00) {
                amounts.add(minor);
            }
        }
        long leakedIds = 0;
        java.util.regex.Matcher id = IDENTIFIER.matcher(captured);
        while (id.find()) {
            leakedIds += identifiers.contains(id.group()) ? 1 : 0;
        }
        long leakedAmounts = 0;
        java.util.regex.Matcher amount = RENDERED_AMOUNT.matcher(captured);
        while (amount.find()) {
            leakedAmounts += amounts.contains(Long.parseLong(amount.group(1) + amount.group(2))) ? 1 : 0;
        }
        System.out.printf("P12-TST-001 NEEDLE: %d characters scanned for %d ids and %d amounts%n", captured.length(),
                identifiers.size(), amounts.size());
        assertThat(identifiers).as("the ids judged").isNotEmpty();
        assertThat(amounts).as("the amounts judged").isNotEmpty();
        assertThat(leakedIds).as("no obligation, purchase, merchant or party id in the captured output").isZero();
        assertThat(leakedAmounts).as("no storm amount in the captured output - no figure in the logs").isZero();
    }

    /** The coverage ledger: every scenario at or above its minimum - counted from rows. */
    private void assertEveryScenarioWasExercised() {
        SoftAssertions softly = new SoftAssertions();
        BnplStormCatalogue.MINIMUM.forEach((scenario, least) -> softly.assertThat(covered(scenario))
                .as("the coverage ledger: %s", scenario).isGreaterThanOrEqualTo(least));
        softly.assertThat(covered("boundary crossed")).as("the coverage ledger: three real midnights").isEqualTo(BOUNDARIES);
        softly.assertAll();
    }

    private void summary(long began) throws Exception {
        Map<String, Integer> covered = new TreeMap<>();
        coverage.forEach((name, value) -> covered.put(name, value.get()));
        System.out.printf("P12-TST-001 SUMMARY: %d s, %d rounds, %d census readings (%d at rest), %d restarts, %d"
                + " deadlocks; obligations %d (pay-in-4 %d), closed %d, purchases %d, refunds %d (dispute %d), disputes %d,"
                + " repayments %d, collections %d, accruals %d, billings %d, fees %d, reversals %d, waivers %d, payoffs %d,"
                + " payouts %d, loans %d%n",
                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - began), rounds.get(), census.readings.get(),
                census.restReadings.get(), restarts.get(), census.deadlocksSinceTheStormBegan(),
                count("SELECT count(*) FROM bnpl.bnpl_obligation"),
                count("SELECT count(*) FROM bnpl.bnpl_obligation WHERE product = 'BNPL_PAY_IN_4'"),
                count("SELECT count(*) FROM bnpl.bnpl_obligation WHERE status = 'CLOSED'"),
                count("SELECT count(*) FROM bnpl.purchase"), count("SELECT count(*) FROM bnpl.bnpl_refund"),
                count("SELECT count(*) FROM bnpl.bnpl_refund WHERE door = 'DISPUTE'"),
                count("SELECT count(*) FROM bnpl.bnpl_dispute"), count("SELECT count(*) FROM bnpl.bnpl_repayment"),
                count("SELECT count(*) FROM bnpl.bnpl_collection_attempt"),
                count("SELECT count(*) FROM bnpl.bnpl_interest_accrual"), count("SELECT count(*) FROM bnpl.bnpl_billing"),
                count("SELECT count(*) FROM bnpl.bnpl_fee_assessment"),
                count("SELECT count(*) FROM bnpl.bnpl_repayment_reversal WHERE status = 'APPROVED'"),
                count("SELECT count(*) FROM bnpl.bnpl_waiver"), count("SELECT count(*) FROM bnpl.bnpl_payoff_execution"),
                count("SELECT count(*) FROM merchant.merchant_payout"), count("SELECT count(*) FROM lending.loan"));
        System.out.printf("P12-TST-001 CENSUS COST: %d ms a reading on average, %d ms the slowest; %d obligations and %d"
                + " payables proven at the last%n",
                TimeUnit.NANOSECONDS.toMillis(census.readingNanos.get() / Math.max(1, census.readings.get())),
                TimeUnit.NANOSECONDS.toMillis(census.slowestReadingNanos.get()), census.obligationsProven,
                census.merchantsProven);
        System.out.println("P12-TST-001 METERS (the whole run): " + lastMeters);
        System.out.println("P12-TST-001 DISPATCHES: " + lastDispatches);
        System.out.println("P12-TST-001 COVERAGE: " + covered);
        System.out.println("P12-TST-001 ACTS: " + tally.snapshot());
    }

    // ================================================================== the instances

    /** One instance: its doors and the beans the storm drives directly. */
    record Instance(String name, Door door, ApplicationContext beans) {

        MeterRegistry meters() {
            return beans.getBean(MeterRegistry.class);
        }

        BnplTransactions transactions() {
            return beans.getBean(BnplTransactions.class);
        }
    }

    private Instance instance(String name, int port, ApplicationContext beans) {
        return new Instance(name, new Door(name, port, tally), beans);
    }

    Instance other(Instance on) {
        return on == a ? b : a;
    }

    Door other(Door on) {
        return on == a.door() ? b.door() : a.door();
    }

    private void startInstanceB() throws Exception {
        Clock behind = ServerSkewedClock.of(SKEW.negated());
        List<String> arguments = new ArrayList<>(List.of("--server.port=0",
                "--spring.datasource.hikari.data-source-properties.ApplicationName=" + B_APPLICATION_NAME,
                // The date shift reaches B's pooled sessions too (A6-04).
                "--spring.datasource.hikari.connection-init-sql=SET TIME ZONE '" + DateShiftedZone.zone() + "'"));
        PROPERTIES.forEach((name, value) -> arguments.add("--" + name + "=" + value));
        instanceB = new SpringApplicationBuilder(com.finapp.app.FinappApplication.class)
                .initializers(initializer -> {
                    GenericApplicationContext generic = (GenericApplicationContext) initializer;
                    generic.registerBean("instanceBClock", Clock.class, () -> behind,
                            definition -> definition.setPrimary(true));
                    generic.registerBean("instanceBBureau", CreditBureau.class, () -> CREDIT.bureau(PROVIDER_TIMEOUT),
                            definition -> definition.setPrimary(true));
                    generic.registerBean("instanceBFinancialData", FinancialDataProvider.class,
                            () -> CREDIT.financialData(PROVIDER_TIMEOUT), definition -> definition.setPrimary(true));
                })
                .run(arguments.toArray(String[]::new));
        b = instance("B", Integer.parseInt(instanceB.getEnvironment().getProperty("local.server.port")), instanceB);
        a.door().other = b.door();
        b.door().other = a.door();
    }

    /**
     * B killed mid-load and a new B started (the same -5 s skew): its doors routed away, its backends terminated while its
     * sweeps hold claims, its context closed - and only then its meters read, so the whole run's counts keep every act
     * the killed B committed. Every claim it held re-drives from its permit.
     */
    private Void restartInstanceB() throws Exception {
        Instance killed = b;
        // The registry held before the close - a closed context serves no bean, but its registry keeps every count.
        MeterRegistry killedMeters = killed.meters();
        b.door().down = true;
        int terminated;
        restarting = true;
        try {
            terminated = BnplStormFaults.terminateBackends(B_APPLICATION_NAME);
            instanceB.close();
            Thread.sleep(500);
            for (MeterArm arm : meterArms()) {
                killedB.merge(arm.name(), arm.meter().applyAsDouble(killedMeters), Double::sum);
            }
            killedB.merge("servicing failures", counter(killedMeters, BnplServicingMetrics.SERVICING, "outcome",
                    "failed"), Double::sum);
            startInstanceB();
        } finally {
            restarting = false;
        }
        restarts.incrementAndGet();
        tally.count("instance B killed (" + (terminated > 0 ? "backends terminated" : "no backend") + ") and restarted");
        return null;
    }

    // ================================================================== plumbing the catalogue shares

    Tally tally() {
        return tally;
    }

    BnplStormWorld world() {
        return world;
    }

    Instance a() {
        return a;
    }

    Instance b() {
        return b;
    }

    List<Customer> pool() {
        return pool;
    }

    Map<UUID, Customer> byParty() {
        return byParty;
    }

    List<Merchant> merchants() {
        return merchants;
    }

    Set<String> reserved() {
        return reserved;
    }

    SimulatedProvider provider() {
        return provider;
    }

    String[] agents() {
        return agents;
    }

    String[] bnplOfficers() {
        return bnplOfficers;
    }

    String[] treasurers() {
        return treasurers;
    }

    int covered(String scenario) {
        AtomicInteger found = coverage.get(scenario);
        return found == null ? 0 : found.get();
    }

    void coverage(String scenario, int evidence) {
        coverage.computeIfAbsent(scenario, ignored -> new AtomicInteger()).addAndGet(evidence);
    }

    void lockWait(String lock) {
        lockWaits.computeIfAbsent(lock, ignored -> new AtomicInteger()).incrementAndGet();
    }

    BnplStormLocks.HotRows hotRows() {
        return hotRows;
    }

    /**
     * One servicing step taken directly on {@code on} - the schedule's own step (T-acc first, in transactions of its own,
     * then T-svc), metered by that instance's own beans exactly as its schedule meters it.
     */
    ServicingOutcome serviceOn(Instance on, String obligation) {
        BnplObligationId id = BnplObligationId.of(UUID.fromString(obligation));
        BnplTransactions transactions = on.transactions();
        BnplAccruals accruals = on.beans().getBean(BnplAccruals.class);
        BnplAccrualMetrics accrualMetrics = on.beans().getBean(BnplAccrualMetrics.class);
        BnplServicingMetrics metrics = on.beans().getBean(BnplServicingMetrics.class);
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(BnplStormWorld.flow())) {
            for (int call = 0; call < 8; call++) {
                BnplAccruals.Outcome accrued = transactions.inTransaction(uow -> accruals.accrue(uow, id));
                accrualMetrics.record(accrued);
                if (accrued.caughtUp()) {
                    break;
                }
            }
            ServicingOutcome step = transactions.inTransaction(uow -> on.beans().getBean(BnplServicing.class)
                    .service(uow, id));
            metrics.record(step);
            return step;
        }
    }

    /** Both callables released at one instant on their own threads; their answers - or their failures - in order. */
    static <T> List<Object> bothAtOnce(Callable<T> first, Callable<T> second) throws Exception {
        return allRaw(List.of(first, second));
    }

    static <T> List<Object> allRaw(List<Callable<T>> callables) throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(callables.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> callable : callables) {
                futures.add(threads.submit(() -> {
                    start.await();
                    return callable.call();
                }));
            }
            start.countDown();
            List<Object> answers = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    answers.add(future.get(3, TimeUnit.MINUTES));
                } catch (java.util.concurrent.ExecutionException failure) {
                    answers.add(failure.getCause());
                }
            }
            return answers;
        } finally {
            threads.shutdownNow();
        }
    }

    static <T> List<T> all(List<Callable<T>> callables) throws Exception {
        List<T> answers = new ArrayList<>();
        for (Object answer : allRaw(callables)) {
            if (answer instanceof Throwable failure) {
                throw new IllegalStateException(failure);
            }
            @SuppressWarnings("unchecked")
            T typed = (T) answer;
            answers.add(typed);
        }
        return answers;
    }

    /** A raced answer - or, when the racer threw, an unexplained failure and an answer no closed set admits. */
    Answer answer(Object raced) {
        if (raced instanceof Answer response) {
            return response;
        }
        tally.unexplained("a racer failed: " + (raced instanceof Throwable failure ? failure.getClass().getSimpleName()
                : String.valueOf(raced)));
        return new Answer(0, "", "racer failed");
    }

    static List<String> shuffled(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        Collections.shuffle(copy, RANDOMNESS);
        return copy;
    }

    /** Instance A's own beans: its clock five seconds ahead of the server, and the simulated credit sources. */
    @TestConfiguration
    static class InstanceA {

        @Bean
        @Primary
        Clock instanceAClock() throws SQLException {
            return ServerSkewedClock.of(SKEW);
        }

        @Bean
        @Primary
        CreditBureau instanceABureau() {
            return CREDIT.bureau(PROVIDER_TIMEOUT);
        }

        @Bean
        @Primary
        FinancialDataProvider instanceAFinancialData() {
            return CREDIT.financialData(PROVIDER_TIMEOUT);
        }
    }
}
