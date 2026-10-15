package com.finapp.app.lending;

import static com.finapp.app.lending.LendingStormWorld.APPLICATIONS;
import static com.finapp.app.lending.LendingStormWorld.EUR;
import static com.finapp.app.lending.LendingStormWorld.LOANS;
import static com.finapp.app.lending.LendingStormWorld.NEEDLE;
import static com.finapp.app.lending.LendingStormWorld.OFFERS;
import static com.finapp.app.lending.LendingStormWorld.OPERATOR_LOANS;
import static com.finapp.app.lending.LendingStormWorld.RANDOMNESS;
import static com.finapp.app.lending.LendingStormWorld.REPORTS;
import static com.finapp.app.lending.LendingStormWorld.count;
import static com.finapp.app.lending.LendingStormWorld.decimal;
import static com.finapp.app.lending.LendingStormWorld.judged;
import static com.finapp.app.lending.LendingStormWorld.key;
import static com.finapp.app.lending.LendingStormWorld.row;
import static com.finapp.app.lending.LendingStormWorld.rows;
import static com.finapp.app.lending.LendingStormWorld.scalar;
import static com.finapp.app.lending.LendingStormWorld.strings;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.credit.ExposureCensusMetrics;
import com.finapp.app.credit.LendingStormCredit;
import com.finapp.app.database.ServerSkewedClock;
import com.finapp.app.lending.LendingStormWorld.Answer;
import com.finapp.app.lending.LendingStormWorld.Door;
import com.finapp.app.lending.LendingStormWorld.Tally;
import com.finapp.app.reconciliation.PositionProof;
import com.finapp.credit.CreditBureau;
import com.finapp.credit.FinancialDataProvider;
import com.finapp.identity.RoleName;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.ProjectionVerification;
import com.finapp.lending.LendingCapitalProof;
import com.finapp.lending.LendingProofs;
import com.finapp.lending.LoanId;
import com.finapp.lending.LoanOrigination;
import com.finapp.lending.LoanReplayProof;
import com.finapp.lending.LoanServicing;
import com.finapp.lending.LoanTerms;
import com.finapp.lending.LoanTermsStore;
import com.finapp.lending.LoanTermsVersionId;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
 * THE LENDING STORM (`P11-TST-001`; PHASE_11_PLAN.md sections 13.2-13.4; every {@code INV-LND-*}, {@code INV-CRD-09},
 * {@code INV-IDEM-02}, {@code INV-SET-06}, {@code INV-LED-01}; the Phase 11 gate's ten-instance answer): lending end to
 * end, from application to closure, for both products, on two skewed instances at once - and every census exact in one
 * {@code REPEATABLE READ} snapshot in every round.
 *
 * <h2>Two application instances whose clocks disagree by ten seconds</h2>
 *
 * <p>Instance A is this suite's own context, its {@code Clock} five seconds AHEAD of the database server; instance B is a
 * second {@link com.finapp.app.FinappApplication} over the same database in this JVM, five seconds BEHIND
 * ({@link ServerSkewedClock}). Both run the four lending schedules as their real lifecycles - origination,
 * disbursement, payout and servicing, enabled here as in production - with credit's progress and retry sweeps and
 * payments' withdrawal and pay-in resolution, all leaderless, each with production's pool of eight connections. Midway, B
 * is killed - its backends terminated - and a new B started. Correctness lives in the database; the instances may
 * disagree about everything else.
 *
 * <h2>Real midnights</h2>
 *
 * <p>Lending's servicing is a function of the business date - the database clock in the agreement's pinned zone. Three
 * terms cohorts are activated with fixed-offset zones chosen so that each one's local midnight falls a few minutes into
 * the run ({@code T1..T3}); their accounts, some "born in the past" before any step serviced them, cross a genuine
 * business-date boundary in real time: the last day accrues, an instalment falls due, a default and a bucket change land.
 *
 * <h2>The storm</h2>
 *
 * <ol>
 *   <li><strong>Load</strong>, in rounds: a burst of every mover on both instances - applicants, acceptors, borrowers
 *       repaying, drawing, prepaying, paying off and closing, servicing agents under four eyes across instances, officers
 *       and treasury, the chaos actor's payout faults and duplicate ticks - the safety censuses read every few seconds;
 *       then rest, and the full census at rest. Mid-load a terms version is activated and B restarted.
 *   <li><strong>The catalogue</strong>: the ten mandatory scenarios and the contention scenes, each choreographed on both
 *       instances at a barrier, each followed by the census.
 *   <li><strong>The crash points</strong>: forced rollbacks and killed backends inside lending's steps, each re-driven by
 *       the other instance, each followed by the census.
 *   <li><strong>At rest</strong>: the audit, the cash proof and the projection replayed from zero, the gauges on both
 *       instances, the meters, the needle and the scheme's sends - and the coverage ledger: every scenario exercised.
 * </ol>
 */
@Tag("database")
@Tag("own-container")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LendingStormDatabaseTest.InstanceA.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("the lending storm (P11-TST-001)")
class LendingStormDatabaseTest {

    private static final Duration SKEW = Duration.ofSeconds(5);
    private static final Duration PROVIDER_TIMEOUT = Duration.ofMillis(800);
    /** The three real midnights, this many minutes after the run's first whole minute (and one a minute after). */
    private static final int FIRST_BOUNDARY_MINUTES = 7;
    private static final int BOUNDARIES = 3;
    private static final int MIN_ROUNDS = 12;
    private static final Duration BURST = Duration.ofSeconds(15);
    private static final Duration REST_BOUND = Duration.ofSeconds(90);
    private static final int POOL_PARTIES = 30;
    private static final String B_APPLICATION_NAME = "finapp-storm-b";
    private static final long TRANCHE_ZERO = 140_000_00;
    private static final long TRANCHE = 25_000_00;

    private static final LendingStormCredit CREDIT = LendingStormCredit.start(NEEDLE);
    private static SimulatedProvider scheme;
    private static final Map<String, String> PROPERTIES = new LinkedHashMap<>();
    private static volatile ConfigurableApplicationContext instanceB;

    @LocalServerPort private int port;
    @Autowired private ApplicationContext context;

    private final Tally tally = new Tally();
    private LendingStormWorld world;
    private LendingStormCensus census;
    private volatile Instance a;
    private volatile Instance b;
    private final AtomicInteger rounds = new AtomicInteger();
    private final AtomicInteger restarts = new AtomicInteger();
    /** While B is being killed and restarted, nothing drives its beans directly. */
    private volatile boolean restarting;
    private volatile long stormBegan;

    /** The coverage ledger: scenario -> evidence counted from ROWS, never from the storm's intentions. */
    private final Map<String, AtomicInteger> coverage = new ConcurrentHashMap<>();
    private final List<Borrower> pool = new CopyOnWriteArrayList<>();
    private final List<Account> accounts = new CopyOnWriteArrayList<>();
    /** Accounts a scene owns: no mover touches them. */
    private final Set<String> reserved = ConcurrentHashMap.newKeySet();
    /** The cohorts' boundaries and the terms versions pinned under them. */
    private final List<Cohort> cohorts = new ArrayList<>();
    /** Armed faults that explain an exception in a direct step: the scene's own expectation. */
    private final AtomicInteger servicingFaultsInjected = new AtomicInteger();

    private String officer;
    private String secondOfficer;
    private String treasurer;
    private String secondTreasurer;
    private String agent;
    private String secondAgent;
    private String policyOfficer;

    // ================================================================== the instances' configuration

    @DynamicPropertySource
    static void storm(DynamicPropertyRegistry registry) {
        if (scheme == null) {
            scheme = SimulatedProvider.start();
        }
        // The four lending schedules ENABLED as real lifecycles on both instances (every other context runs them off),
        // with storm-short polls and permits: a permit that lapses re-drives from rows within the round.
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
        // Credit decides by its own leaderless sweeps over the simulators; a fault costs a retry, never a decision.
        PROPERTIES.put("finapp.credit.progress.sweeper.enabled", "true");
        PROPERTIES.put("finapp.credit.progress.poll", "PT0.3S");
        PROPERTIES.put("finapp.credit.progress.permit", "PT2S");
        PROPERTIES.put("finapp.credit.data.retry.sweeper.enabled", "true");
        PROPERTIES.put("finapp.credit.data.retry.poll", "PT1S");
        PROPERTIES.put("finapp.credit.bureau.retry-cadence", "PT1S");
        PROPERTIES.put("finapp.credit.bureau.collection-window", "PT45S");
        PROPERTIES.put("finapp.credit.findata.retry-cadence", "PT1S");
        PROPERTIES.put("finapp.credit.findata.collection-window", "PT45S");
        // Payments' instant scheme, its withdrawal and pay-in resolution: a lost answer inquired promptly.
        PROPERTIES.put("finapp.payments.instant.url", scheme.baseUrl());
        PROPERTIES.put("finapp.payments.instant.timeout", "PT0.7S");
        PROPERTIES.put("finapp.payments.withdrawal.sweeper.enabled", "true");
        PROPERTIES.put("finapp.payments.withdrawal.sweeper.poll", "PT0.5S");
        PROPERTIES.put("finapp.payments.withdrawal.sweeper.dispatched-age", "PT0.5S");
        PROPERTIES.put("finapp.payments.withdrawal.sweeper.unknown-age", "PT0.2S");
        PROPERTIES.put("finapp.payments.withdrawal.sweeper.margin", "PT1S");
        PROPERTIES.put("finapp.payments.withdrawal.sweeper.batch", "200");
        PROPERTIES.put("finapp.payments.payin.sweeper.enabled", "true");
        PROPERTIES.put("finapp.payments.payin.sweeper.poll", "PT0.5S");
        PROPERTIES.put("finapp.payments.payin.sweeper.initiation-age", "PT0.5S");
        PROPERTIES.put("finapp.payments.payin.sweeper.batch", "200");
        PROPERTIES.forEach((name, value) -> registry.add(name, () -> value));
    }

    @AfterAll
    static void stop() {
        if (instanceB != null) {
            instanceB.close();
        }
        CREDIT.close();
        if (scheme != null) {
            scheme.close();
        }
    }

    // ================================================================== the storm

    @Test
    @DisplayName("two instances skewed +/-5 s, both products from application to closure, three real midnights, a terms"
            + " activation, payout faults, crash points, killed backends and a restart: every census exact every round,"
            + " every scenario exercised, and at rest the books proven, the gauges zero and every act counted")
    void theStormLeavesEveryLoanProvable(CapturedOutput output) throws Exception {
        long began = System.nanoTime();
        stormBegan = began;
        a = instance("A", port, context);
        world = new LendingStormWorld(context, CREDIT, scheme, a.door());
        census = new LendingStormCensus(context.getBean(LendingProofs.class), context.getBean(LendingCapitalProof.class));
        prepareTheWorld();
        rest("before the storm");
        census("before the storm", true);

        // 1. LOAD - both instances at once, in rounds, three real midnights inside it.
        load();

        // 2. THE CATALOGUE - the ten scenarios and the contention scenes, choreographed on both instances.
        catalogue();

        // 3. THE CRASH POINTS - forced rollbacks and killed backends, re-driven by the other instance.
        crashes();

        // 4. AT REST.
        rest("drained");
        census("drained", true);
        assertTheAuditCountsEveryAct();
        assertTheCashAndTheProjections();
        assertTheGaugesAtRest();
        assertTheMetersCountTheRows();
        assertTheSchemeSentOnce();
        assertTheNeedleReachedNoSink(output);
        assertEveryScenarioWasExercised();
        assertThat(tally.unexplained).as("C15 unexplained failures - every response in its act's closed set, every"
                + " exception an armed fault's").isEmpty();
        summary(began);
    }

    // ================================================================== the world

    private void prepareTheWorld() throws Exception {
        Instant now = LendingStormWorld.databaseNow();
        CREDIT.retrievedAt(now.minus(Duration.ofHours(1)));
        world.stubTheScheme();
        Instant first = now.truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(FIRST_BOUNDARY_MINUTES));
        officer = world.sessionWith(RoleName.LENDING_OFFICER);
        secondOfficer = world.sessionWith(RoleName.LENDING_OFFICER);
        treasurer = world.sessionWith(RoleName.LENDING_TREASURY_OFFICER);
        secondTreasurer = world.sessionWith(RoleName.LENDING_TREASURY_OFFICER);
        agent = world.sessionWith(RoleName.LOAN_SERVICING_AGENT);
        secondAgent = world.sessionWith(RoleName.LOAN_SERVICING_AGENT);
        policyOfficer = world.sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        // Credit's seeded policies and scorecard, one officer each (a migration proposed them).
        for (String seed : List.of("/v1/operator/credit/policies/0190a1b2-5c0e-7000-8000-00000000d001",
                "/v1/operator/credit/policies/0190a1b2-5c0e-7000-8000-00000000d002",
                "/v1/operator/credit/scorecards/0190a1b2-5c0e-7000-8000-00000000c001")) {
            Answer approved = a.door().post(seed + "/approval", "{\"reason\":\"v1 reviewed\"}", policyOfficer, key());
            assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        }
        startInstanceB();
        // Capital tranche 0 through the bank's statement: enough for most, not all, of the load's acceptances.
        world.tranche(a.door(), b.door(), treasurer, secondTreasurer, TRANCHE_ZERO, true, tally);

        // The pool: parties who borrow both products, some with their own bank account for path X.
        ExecutorService builders = Executors.newFixedThreadPool(8);
        try {
            List<Future<Borrower>> made = new ArrayList<>();
            for (int i = 0; i < POOL_PARTIES; i++) {
                boolean banked = i % 3 == 0;
                made.add(builders.submit(() -> borrower(banked)));
            }
            for (Future<Borrower> borrower : made) {
                pool.add(borrower.get(3, TimeUnit.MINUTES));
            }
        } finally {
            builders.shutdown();
        }

        // The cohorts: each one's terms in a zone whose local midnight is its boundary; its accounts originated under
        // them; some "born in the past" so the boundary lands a due date, a default and a bucket change.
        for (int k = 1; k <= BOUNDARIES; k++) {
            Instant boundary = first.plus(Duration.ofMinutes(k - 1L));
            ZoneOffset zone = LendingStormWorld.zoneWhoseMidnightIs(boundary);
            boolean collects = k != 2;
            Door proposing = k % 2 == 0 ? b.door() : a.door();
            Door approving = k % 2 == 0 ? a.door() : b.door();
            String loanTerms = world.activateTerms(proposing, approving, officer, secondOfficer,
                    LendingStormWorld.terms("PERSONAL_LOAN", zoneName(zone), collects, "0.0899", "cohort " + k), tally);
            world.activateTerms(approving, proposing, secondOfficer, officer,
                    LendingStormWorld.terms("CREDIT_LINE", zoneName(zone), collects, "0.1299", "cohort " + k), tally);
            Cohort cohort = new Cohort(k, boundary, zone, loanTerms, collects);
            cohorts.add(cohort);
            originateCohort(cohort);
        }
        for (Cohort cohort : cohorts) {
            ageCohort(cohort);
        }
        Instant ready = LendingStormWorld.databaseNow();
        System.out.printf("P11-TST-001 PREPARED: %d pool parties, %d accounts in %d cohorts; the first boundary in %d s%n",
                pool.size(), accounts.size(), cohorts.size(), Duration.between(ready, first).toSeconds());
        assertThat(ready).as("preparation overran - the first real midnight is too close for the storm to cross it")
                .isBefore(first.minus(Duration.ofSeconds(45)));
    }

    /** The terms door's spelling of a zone: UTC, or a fixed offset +HH:MM. */
    private static String zoneName(ZoneOffset zone) {
        return zone.getTotalSeconds() == 0 ? "UTC" : zone.getId();
    }

    /** A pool borrower - verified, consenting, MFA - and, if {@code banked}, their bank account through the exchange. */
    private Borrower borrower(boolean banked) throws Exception {
        CreditFixtures.Customer customer = world.borrower(LendingStormCredit.Profile.GOOD);
        String[] bank = banked ? world.bankAccount(customer) : new String[] {null, null};
        return new Borrower(customer, bank[0], bank[1]);
    }

    /**
     * Cohort {@code k}'s accounts under its own terms: wallet loans (one to cross the boundary plain, one to fall due on
     * it and - in the cohort that does not collect - one to default on it and one to change bucket), an external loan, a
     * line. Every offer is made while the cohort's terms are in force, accepted on alternating instances.
     */
    private void originateCohort(Cohort cohort) throws Exception {
        LocalDate dayAfter = cohort.boundary().atOffset(cohort.zone()).toLocalDate();
        List<Callable<Optional<Account>>> applications = new ArrayList<>();
        List<Borrower> plain = pool.stream().filter(borrower -> borrower.bankMethod() == null).toList();
        List<Borrower> banked = pool.stream().filter(borrower -> borrower.bankMethod() != null).toList();
        int base = (cohort.k() - 1) * 4;
        applications.add(() -> originate(plain.get(base), AccountKind.WALLET_LOAN, cohort, Role.PLAIN, 10));
        applications.add(() -> originate(plain.get(base + 1), AccountKind.WALLET_LOAN, cohort, Role.FALLS_DUE,
                dayAfter.getDayOfMonth()));
        if (!cohort.collects()) {
            applications.add(() -> originate(plain.get(base + 2), AccountKind.WALLET_LOAN, cohort, Role.DEFAULTS,
                    dayAfter.minusDays(90).getDayOfMonth()));
            applications.add(() -> originate(plain.get(base + 3), AccountKind.WALLET_LOAN, cohort, Role.BUCKET_30,
                    dayAfter.minusDays(30).getDayOfMonth()));
        }
        applications.add(() -> originate(banked.get(cohort.k() - 1), AccountKind.EXTERNAL_LOAN, cohort, Role.PLAIN, 10));
        applications.add(() -> originate(plain.get(base + 3), AccountKind.LINE, cohort, Role.STATES,
                dayAfter.getDayOfMonth()));
        for (Optional<Account> made : all(applications)) {
            made.ifPresent(accounts::add);
        }
    }

    /** One account through the doors: applied for, decided by credit's sweeps, offered, accepted. */
    private Optional<Account> originate(Borrower borrower, AccountKind kind, Cohort cohort, Role role, int day)
            throws Exception {
        if (day > 28) {
            tally.count("cohort " + cohort.k() + " " + role + " skipped: its day is past the 28th");
            return Optional.empty();
        }
        long amount = kind == AccountKind.LINE ? 2_000_00 : 3_000_00 + 100_00L * RANDOMNESS.nextInt(20);
        String body = kind == AccountKind.LINE ? LendingStormWorld.lineApplication(amount, day)
                : LendingStormWorld.loanApplication(amount, 12, day, kind == AccountKind.EXTERNAL_LOAN);
        Door on = RANDOMNESS.nextBoolean() ? a.door() : b.door();
        Answer submitted = on.post(APPLICATIONS, body, borrower.customer().token(), key());
        assertThat(submitted.status()).as("cohort %s: %s", cohort.k(), submitted.body()).isEqualTo(202);
        Optional<String> offer = LendingStormWorld.awaitOffer(submitted.field("applicationId"), Duration.ofSeconds(90));
        assertThat(offer).as("cohort %s's %s was offered", cohort.k(), kind).isPresent();
        String hash = LendingStormWorld.termsHash(on, borrower.customer().token(), offer.get());
        Answer accepted = other(on).post(OFFERS + "/" + offer.get() + "/acceptance", LendingStormWorld.acceptance(hash,
                kind == AccountKind.EXTERNAL_LOAN ? borrower.bankMethod() : null), borrower.customer().token(), key());
        assertThat(accepted.status()).as("cohort %s's acceptance: %s", cohort.k(), accepted.body()).isEqualTo(201);
        tally.count("cohort acceptance");
        String loan = accepted.field("loanId");
        if (role != Role.PLAIN) {
            reserved.add(loan);
        }
        if (role == Role.STATES) {
            // Drawn, so the cycle that ends at the boundary bills its interest.
            Answer drawn = on.post(LOANS + "/" + loan + "/draws", money(300_00), borrower.customer().token(), key());
            assertThat(drawn.status()).as("cohort %s's draw: %s", cohort.k(), drawn.body()).isEqualTo(201);
        }
        return Optional.of(new Account(loan, borrower, kind, cohort.k(), role));
    }

    /**
     * The cohort's accounts made to cross its boundary with something landing on it: each serviced once (nothing written
     * but its permit - asserted), then "born in the past" so its first instalment falls due on the boundary's date, or 90
     * or 30 days before it - the permit set to the boundary.
     */
    private void ageCohort(Cohort cohort) throws Exception {
        LocalDate dayAfter = cohort.boundary().atOffset(cohort.zone()).toLocalDate();
        LoanTerms terms = a.transactions().inTransaction(uow -> a.beans().getBean(LoanTermsStore.class)
                .version(uow, LoanTermsVersionId.of(UUID.fromString(cohort.loanTerms()))).orElseThrow().terms());
        for (Account account : accounts) {
            if (account.cohort() != cohort.k() || account.role() == Role.PLAIN || account.role() == Role.STATES) {
                continue;
            }
            LendingStormWorld.awaitDatabase(Duration.ofSeconds(90), "the cohort account is disbursed and serviced once",
                    "SELECT status = 'ACTIVE' AND next_servicing_at > statement_timestamp() FROM lending.loan"
                            + " WHERE id = ?::uuid", account.loan());
            LocalDate due = switch (account.role()) {
                case FALLS_DUE -> dayAfter;
                case DEFAULTS -> dayAfter.minusDays(90);
                case BUCKET_30 -> dayAfter.minusDays(30);
                default -> throw new IllegalStateException();
            };
            LendingStormWorld.bornInThePast(account.loan(), terms, due, cohort.boundary());
            tally.count("born in the past: " + account.role());
        }
    }

    // ================================================================== 1. load

    private void load() throws Exception {
        Instant last = cohorts.get(cohorts.size() - 1).boundary();
        ExecutorService boundaries = Executors.newSingleThreadExecutor();
        Future<?> choreography = boundaries.submit(() -> {
            for (Cohort cohort : cohorts) {
                boundaryChoreography(cohort);
            }
            return null;
        });
        try {
            while (rounds.get() < MIN_ROUNDS || LendingStormWorld.databaseNow().isBefore(last.plusSeconds(20))) {
                int round = rounds.incrementAndGet();
                burst(round);
                rest("round " + round);
                census("round " + round + " at rest", true);
                if (choreography.isDone()) {
                    choreography.get();
                }
            }
            choreography.get(5, TimeUnit.MINUTES);
        } finally {
            boundaries.shutdownNow();
        }
        assertTheBoundariesWereCrossed();
    }

    /** One burst: every mover on both instances; safety censuses while it blows; mid-load, an activation and a restart. */
    private void burst(int round) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService threads = Executors.newFixedThreadPool(40);
        List<Future<?>> running = new ArrayList<>();
        try {
            for (Instance on : List.of(a, b)) {
                for (int i = 0; i < 2; i++) {
                    running.add(threads.submit(() -> forever(stop, "applicant " + on.name(), () -> applyOnce(on))));
                }
                for (int i = 0; i < 4; i++) {
                    running.add(threads.submit(() -> forever(stop, "borrower " + on.name(), () -> borrowOnce(on))));
                }
                running.add(threads.submit(() -> forever(stop, "agent " + on.name(), () -> serviceDeskOnce(on))));
                running.add(threads.submit(() -> forever(stop, "reader " + on.name(), () -> readOnce(on))));
            }
            running.add(threads.submit(() -> forever(stop, "chaos", this::chaosOnce)));
            running.add(threads.submit(() -> forever(stop, "treasury", this::treasuryOnce)));
            Instant end = LendingStormWorld.databaseNow().plus(BURST);
            for (Cohort cohort : cohorts) {
                // A boundary inside the burst or in the coming rest is crossed under load: the burst holds past it.
                if (cohort.boundary().isAfter(end.minus(BURST)) && cohort.boundary().isBefore(end.plus(REST_BOUND))) {
                    end = later(end, cohort.boundary().plusSeconds(12));
                }
            }
            if (round == 3) {
                running.add(threads.submit(this::activateTermsMidLoad));
            }
            if (round == 5) {
                running.add(threads.submit(this::restartInstanceB));
            }
            while (LendingStormWorld.databaseNow().isBefore(end)) {
                for (Future<?> task : running) {
                    if (task.isDone()) {
                        task.get(); // an actor that died fails the storm with its own failure
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

    private static Instant later(Instant one, Instant other) {
        return one.isAfter(other) ? one : other;
    }

    @FunctionalInterface
    private interface Act {
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

    /** An applicant: either product, a shared party, the key replayed across instances, a withdrawal, then the offer. */
    private void applyOnce(Instance on) throws Exception {
        Thread.sleep(300 + RANDOMNESS.nextInt(400));
        Borrower borrower = pool.get(RANDOMNESS.nextInt(pool.size()));
        boolean line = RANDOMNESS.nextInt(3) == 0;
        boolean external = !line && borrower.bankMethod() != null && RANDOMNESS.nextBoolean();
        long amount = line ? 1_000_00 + 100_00L * RANDOMNESS.nextInt(15) : 2_000_00 + 100_00L * RANDOMNESS.nextInt(60);
        String body = line ? LendingStormWorld.lineApplication(amount, 1 + RANDOMNESS.nextInt(28))
                : LendingStormWorld.loanApplication(amount, 6 + RANDOMNESS.nextInt(19), 1 + RANDOMNESS.nextInt(28),
                        external);
        String applicationKey = key();
        Answer submitted = on.door().post(APPLICATIONS, body, borrower.customer().token(), applicationKey);
        if (!judged(tally, "apply", submitted, "409 lending.ApplicationOpen", "409 credit.DecisionRequestOpen",
                "409 api.IdempotencyInProgress") || submitted.status() != 202) {
            return;
        }
        String application = submitted.field("applicationId");
        int dice = RANDOMNESS.nextInt(100);
        if (dice < 10) {
            // The same key on the other instance: the same receipt, byte for byte.
            Answer replayed = other(on).door().post(APPLICATIONS, body, borrower.customer().token(), applicationKey);
            judged(tally, "apply replayed", replayed, "409 api.IdempotencyInProgress");
            if (replayed.status() == 202 && !replayed.body().equals(submitted.body())) {
                tally.unexplained("a replayed application key answered another receipt");
            }
        } else if (dice < 16) {
            Answer withdrawn = other(on).door().post(APPLICATIONS + "/" + application + "/withdrawal", null,
                    borrower.customer().token(), key());
            judged(tally, "withdraw application", withdrawn, "409 lending.ApplicationNotWithdrawable");
            return;
        }
        Optional<String> offer = LendingStormWorld.awaitOffer(application, Duration.ofSeconds(25));
        if (offer.isEmpty()) {
            tally.count("application not offered within the burst");
            return;
        }
        acceptOnce(on, borrower, offer.get(), external);
    }

    /** An acceptor: MFA; sometimes a stale hash (409 TermsChanged), sometimes a decline; the capital contested. */
    private void acceptOnce(Instance on, Borrower borrower, String offer, boolean external) throws Exception {
        Answer read = on.door().get(OFFERS + "/" + offer, borrower.customer().token());
        if (!judged(tally, "read offer", read) ) {
            return;
        }
        String hash = read.field("termsSha256");
        int dice = RANDOMNESS.nextInt(100);
        if (dice < 6) {
            Answer stale = other(on).door().post(OFFERS + "/" + offer + "/acceptance",
                    LendingStormWorld.acceptance("ab".repeat(32), external ? borrower.bankMethod() : null),
                    borrower.customer().token(), key());
            judged(tally, "accept stale hash", stale, "409 lending.TermsChanged", "409 lending.OfferNotAcceptable");
            if (stale.status() == 201) {
                tally.unexplained("an acceptance of another hash was accepted");
            }
            return;
        }
        if (dice < 12) {
            Answer declined = other(on).door().post(OFFERS + "/" + offer + "/decline", null,
                    borrower.customer().token(), key());
            judged(tally, "decline offer", declined, "409 lending.OfferNotAcceptable");
            return;
        }
        String acceptanceKey = key();
        String body = LendingStormWorld.acceptance(hash, external ? borrower.bankMethod() : null);
        Answer accepted = other(on).door().post(OFFERS + "/" + offer + "/acceptance", body, borrower.customer().token(),
                acceptanceKey);
        judged(tally, "accept", accepted, "422 lending.CapitalUnavailable", "409 lending.OfferNotAcceptable",
                "409 credit.ApplicantNotEligible", "409 api.IdempotencyInProgress");
        if (accepted.status() == 201) {
            String loan = accepted.field("loanId");
            accounts.add(new Account(loan, borrower, external ? AccountKind.EXTERNAL_LOAN
                    : accepted.body().contains("\"kind\":\"REVOLVING\"") ? AccountKind.LINE : AccountKind.WALLET_LOAN,
                    0, Role.PLAIN));
            if (RANDOMNESS.nextInt(5) == 0) {
                Answer replayed = on.door().post(OFFERS + "/" + offer + "/acceptance", body, borrower.customer().token(),
                        acceptanceKey);
                judged(tally, "accept replayed", replayed, "409 api.IdempotencyInProgress");
                if (replayed.status() == 201 && !replayed.body().equals(accepted.body())) {
                    tally.unexplained("a replayed acceptance key answered another receipt");
                }
            }
        }
    }

    /** A borrower: repays, draws, prepays, pays off, closes a line, reads - the same key on both instances sometimes. */
    private void borrowOnce(Instance on) throws Exception {
        Thread.sleep(150 + RANDOMNESS.nextInt(300));
        Optional<Account> picked = liveAccount();
        if (picked.isEmpty()) {
            return;
        }
        Account account = picked.get();
        String token = account.borrower().customer().token();
        String base = LOANS + "/" + account.loan();
        int dice = RANDOMNESS.nextInt(100);
        if (account.kind() == AccountKind.LINE && dice < 30) {
            String drawKey = key();
            String body = money(5_00 + 100L * RANDOMNESS.nextInt(1_500));
            Answer drawn = on.door().post(base + "/draws", body, token, drawKey);
            judged(tally, "draw", drawn, "422 lending.LimitExceeded", "409 lending.LineNotDrawable",
                    "409 lending.DrawsSuspended", "422 lending.DrawsSuspended", "409 lending.LoanClosed",
                    "409 api.IdempotencyInProgress");
            if (drawn.status() == 201 && RANDOMNESS.nextInt(4) == 0) {
                Answer replayed = other(on).door().post(base + "/draws", body, token, drawKey);
                judged(tally, "draw replayed", replayed, "409 api.IdempotencyInProgress");
            }
        } else if (account.kind() == AccountKind.EXTERNAL_LOAN && dice < 45) {
            payIn(on, account);
        } else if (dice < 70) {
            String repaymentKey = key();
            // Mostly an instalment's worth; now and then far more than is due (held as credit, or refused unfunded).
            String body = money(RANDOMNESS.nextInt(5) == 0 ? 300_00 + 100L * RANDOMNESS.nextInt(30_000)
                    : 10_00 + 100L * RANDOMNESS.nextInt(290));
            Answer repaid = on.door().post(base + "/repayments", body, token, repaymentKey);
            judged(tally, "repay", repaid, "409 lending.LoanNotRepayable", "422 lending.InsufficientFunds",
                    "409 lending.LoanClosed", "409 api.IdempotencyInProgress", "422 lending.WalletUnavailable");
            if (repaid.status() == 201 && RANDOMNESS.nextInt(4) == 0) {
                Answer replayed = other(on).door().post(base + "/repayments", body, token, repaymentKey);
                judged(tally, "repay replayed", replayed, "409 api.IdempotencyInProgress");
                if (replayed.status() == 201 && !replayed.body().equals(repaid.body())) {
                    tally.unexplained("a replayed repayment key answered another receipt");
                }
            }
        } else if (dice < 78 && account.kind() != AccountKind.LINE) {
            Answer prepaid = on.door().post(base + "/prepayments", money(50_00 + 100L * RANDOMNESS.nextInt(500)), token,
                    key());
            judged(tally, "prepay", prepaid, "422 lending.PrepaymentExceedsPrincipal", "409 lending.ArrearsFirst",
                    "422 lending.InsufficientFunds", "409 lending.LoanNotRepayable", "409 lending.LoanClosed",
                    "422 lending.ArrearsFirst");
        } else if (dice < 81) {
            payOff(on, account, true);
        } else if (dice < 87 && account.kind() == AccountKind.LINE) {
            Answer closing = on.door().post(base + "/closure", null, token, key());
            judged(tally, "close line", closing, "409 lending.LoanClosed", "409 lending.LineNotClosable");
        } else {
            Answer read = on.door().get(base, token);
            judged(tally, "read loan", read);
        }
    }

    /**
     * A borrower paid out to their bank tops their wallet up from it - payments' real instant pay-in, executed when the
     * scheme answers the inquiry (the money returning from the bank is just this: an inbound credit to the wallet, the
     * loan unaffected - the owner's ruling on "returns", design Q2). Money enters the wallet only by this real path.
     */
    private void payIn(Instance on, Account account) throws Exception {
        String token = account.borrower().customer().token();
        Answer created = on.door().post("/v1/payments", "{\"paymentMethodId\":\"" + account.borrower().bankMethod()
                + "\",\"amount\":\"" + decimal(100_00 + 100L * RANDOMNESS.nextInt(40_000)) + "\",\"currency\":\"EUR\"}",
                token, key());
        if (!judged(tally, "pay-in", created) || created.status() != 201) {
            return;
        }
        Answer confirmed = other(on).door().post("/v1/payments/" + created.field("id") + "/confirmation", null, token,
                null);
        judged(tally, "pay-in confirmation", confirmed);
    }

    /**
     * A scene's borrower funded from their own bank: payments' real pay-in, created on {@code on}, confirmed on the other,
     * executed once the scheme answers its inquiry - awaited until {@code EXECUTED}, the wallet's credit final.
     */
    void fundFromBank(Instance on, Borrower borrower, long minor) throws Exception {
        String token = borrower.customer().token();
        Answer created = on.door().post("/v1/payments", "{\"paymentMethodId\":\"" + borrower.bankMethod()
                + "\",\"amount\":\"" + decimal(minor) + "\",\"currency\":\"EUR\"}", token, key());
        judged(tally, "scene pay-in", created);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        Answer confirmed = other(on).door().post("/v1/payments/" + created.field("id") + "/confirmation", null, token,
                null);
        judged(tally, "scene pay-in confirmation", confirmed);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (count("SELECT count(*) FROM payments.payment_attempt WHERE intent_id = ?::uuid AND status = 'EXECUTED'",
                created.field("id")) == 0) {
            assertThat(System.nanoTime()).as("the scene's pay-in executed").isLessThan(deadline);
            answerInquiries();
            Thread.sleep(250);
        }
    }

    /** A payoff quoted on one instance and executed on the other - stale or closed answers are the race's own. */
    private Answer payOff(Instance on, Account account, boolean mayFail) throws Exception {
        String token = account.borrower().customer().token();
        String base = LOANS + "/" + account.loan();
        Answer quoted = on.door().post(base + "/payoff-quotes", "{}", token, key());
        judged(tally, "payoff quote", quoted, "409 lending.LoanNotRepayable", "409 lending.LoanClosed");
        if (quoted.status() != 201) {
            return quoted;
        }
        Answer paidOff = other(on).door().post(base + "/payoff", "{\"quoteId\":\"" + quoted.field("quoteId") + "\"}",
                token, key());
        judged(tally, "payoff", paidOff, "409 lending.PayoffQuoteStale", "422 lending.InsufficientFunds",
                "409 lending.LoanNotRepayable", "409 lending.LoanClosed");
        return paidOff;
    }

    /** A servicing agent: a waiver, a reversal or an amendment proposed here, decided by the other agent there. */
    private void serviceDeskOnce(Instance on) throws Exception {
        Thread.sleep(800 + RANDOMNESS.nextInt(1_200));
        String proposer = on == a ? agent : secondAgent;
        String approver = on == a ? secondAgent : agent;
        int dice = RANDOMNESS.nextInt(100);
        if (dice < 35) {
            String[] fee = row("SELECT f.loan_id::text, f.id::text, f.amount_minor::text FROM lending.fee_assessment f"
                    + " WHERE NOT EXISTS (SELECT 1 FROM lending.waiver w WHERE w.fee_assessment_id = f.id)"
                    + " ORDER BY random() LIMIT 1");
            if (fee == null || reserved.contains(fee[0])) {
                return;
            }
            String waivers = OPERATOR_LOANS + "/" + fee[0] + "/waivers";
            Answer proposed = on.door().post(waivers, "{\"kind\":\"FEE\",\"targetId\":\"" + fee[1] + "\",\"amount\":\""
                    + decimal(Long.parseLong(fee[2])) + "\",\"currency\":\"EUR\",\"reason\":\"the storm's goodwill\"}",
                    proposer, key());
            judged(tally, "propose waiver", proposed, "422 lending.WaiverExceedsDue", "409 lending.ProposalPending",
                    "409 lending.LoanClosed");
            if (proposed.status() == 201) {
                decide(other(on), waivers + "/" + proposed.field("waiverId"), "{\"reason\":\"checked\"}", proposer,
                        approver, "waiver");
            }
        } else if (dice < 65) {
            String[] repayment = row("SELECT r.loan_id::text, r.id::text FROM lending.repayment r JOIN lending.loan l"
                    + " ON l.id = r.loan_id WHERE r.kind = 'CUSTOMER' AND l.status IN ('ACTIVE', 'CLOSING')"
                    + " AND NOT EXISTS (SELECT 1 FROM lending.repayment_reversal v WHERE v.repayment_id = r.id)"
                    + " ORDER BY random() LIMIT 1");
            if (repayment == null || reserved.contains(repayment[0])) {
                return;
            }
            String reversal = OPERATOR_LOANS + "/" + repayment[0] + "/repayments/" + repayment[1] + "/reversal";
            Answer proposed = on.door().post(reversal, "{\"reason\":\"the bank recalled the transfer\"}", proposer, key());
            judged(tally, "propose reversal", proposed, "409 lending.RepaymentNotReversible",
                    "409 lending.ProposalPending", "409 lending.LoanClosed", "409 lending.LoanNotRepayable");
            if (proposed.status() == 201) {
                String decision = "{\"reversalId\":\"" + proposed.field("reversalId") + "\",\"reason\":\"matched\"}";
                decide(other(on), reversal, decision, proposer, approver, "reversal");
            }
        } else {
            String[] loan = row("SELECT l.id::text, g.nominal_annual_rate FROM lending.loan l JOIN lending.loan_agreement g"
                    + " ON g.loan_id = l.id AND g.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE"
                    + " x.loan_id = l.id) WHERE l.kind = 'INSTALMENT' AND l.status = 'ACTIVE' ORDER BY random() LIMIT 1");
            if (loan == null || reserved.contains(loan[0])) {
                return;
            }
            String amendments = OPERATOR_LOANS + "/" + loan[0] + "/amendments";
            Answer proposed = on.door().post(amendments, "{\"nominalAnnualRate\":\"0.0599\",\"remainingInstalments\":"
                    + (8 + RANDOMNESS.nextInt(10)) + ",\"rescheduleArrears\":false,\"reason\":\"hardship, documented\"}",
                    proposer, key());
            judged(tally, "propose amendment", proposed, "409 lending.ProposalPending", "409 lending.LoanClosed",
                    "422 lending.TermsNotAmortising", "409 lending.LoanNotRepayable", "422 lending.AmountOutOfRange",
                    "409 lending.ArrearsFirst", "422 lending.TermsInvalid");
            if (proposed.status() == 201) {
                String amendment = proposed.field("amendmentId");
                Answer approved = decide(other(on), amendments + "/" + amendment, "{\"reason\":\"affordability ok\"}",
                        proposer, approver, "amendment");
                if (approved != null && approved.status() == 200) {
                    acceptAmendment(on, loan[0], amendment);
                }
            }
        }
    }

    /** The other agent's decision - the proposer refused their own approval first (four eyes). */
    private Answer decide(Instance on, String proposal, String decision, String proposer, String approver, String kind)
            throws Exception {
        if (RANDOMNESS.nextInt(5) == 0) {
            Answer self = on.door().post(proposal + "/approval", decision, proposer, key());
            judged(tally, kind + " self-approval", self, "403 lending.SelfApprovalRefused");
        }
        boolean reject = RANDOMNESS.nextInt(6) == 0;
        Answer decided = on.door().post(proposal + (reject ? "/rejection" : "/approval"), decision, approver, key());
        judged(tally, kind + (reject ? " rejected" : " approved"), decided, "409 lending.ProposalNotPending",
                "422 lending.WaiverExceedsDue", "409 lending.RepaymentNotReversible", "409 lending.LoanClosed",
                "409 lending.AmendmentLapsed", "409 lending.LoanNotRepayable");
        return reject ? null : decided;
    }

    /** The borrower accepts an approved amendment - on the instance that did not approve it. */
    private void acceptAmendment(Instance on, String loan, String amendment) throws Exception {
        Optional<Account> account = accounts.stream().filter(found -> found.loan().equals(loan)).findFirst();
        if (account.isEmpty()) {
            return;
        }
        String token = account.get().borrower().customer().token();
        String path = LOANS + "/" + loan + "/amendments/" + amendment;
        Answer read = on.door().get(path, token);
        if (!judged(tally, "read amendment", read, "409 lending.AmendmentLapsed", "404 lending.NotFound")
                || read.status() != 200) {
            return;
        }
        Answer accepted = on.door().post(path + "/acceptance", "{\"documentSha256\":\"" + read.field("documentSha256")
                + "\"}", token, key());
        judged(tally, "accept amendment", accepted, "409 lending.AmendmentLapsed", "409 lending.TermsChanged",
                "409 lending.LoanClosed", "409 lending.ProposalNotPending", "409 lending.LoanNotRepayable");
    }

    /** An officer's audited reads: a report, a loan's explanation and replay; and out-of-role attempts refused. */
    private void readOnce(Instance on) throws Exception {
        Thread.sleep(1_500 + RANDOMNESS.nextInt(1_500));
        int dice = RANDOMNESS.nextInt(100);
        String reader = on == a ? officer : secondOfficer;
        if (dice < 35) {
            String report = List.of("portfolio", "delinquency", "accrual", "capital").get(RANDOMNESS.nextInt(4));
            String today = scalar("SELECT (now() AT TIME ZONE 'UTC')::date::text");
            Answer read = on.door().get(REPORTS + "/" + report + "?date=" + today, reader);
            if (judged(tally, "report " + report, read) && read.status() == 200) {
                tally.audited("lending.ReportRead");
            }
        } else if (dice < 70) {
            Optional<Account> account = liveAccount();
            if (account.isPresent()) {
                Answer read = on.door().get(OPERATOR_LOANS + "/" + account.get().loan() + "/explanation", reader);
                if (judged(tally, "explanation", read) && read.status() == 200) {
                    tally.audited("lending.ExplanationRead");
                }
            }
        } else if (dice < 90) {
            Optional<Account> account = liveAccount();
            if (account.isPresent()) {
                Answer replayed = on.door().post(OPERATOR_LOANS + "/" + account.get().loan() + "/replay",
                        "{\"reason\":\"the storm's replay\"}", reader, null);
                if (judged(tally, "replay door", replayed) && replayed.status() == 200) {
                    tally.audited("lending.LoanReplayed");
                    if (!replayed.body().contains("\"verdict\":\"IDENTICAL\"")) {
                        tally.unexplained("the replay door answered a verdict other than IDENTICAL");
                    }
                }
            }
        } else {
            // Out of role: an agent at the terms door, a treasurer at the reports - refused, nothing written.
            Answer refused = on.door().post(LendingStormWorld.TERMS, LendingStormWorld.terms("PERSONAL_LOAN", "UTC", true,
                    "0.0700", "out of role"), on == a ? agent : secondAgent, key());
            judged(tally, "out of role terms", refused, "403");
            if (refused.status() != 403) {
                tally.unexplained("an agent proposed terms: " + refused.status());
            }
            Answer report = on.door().get(REPORTS + "/portfolio?date=2026-01-01", treasurer);
            judged(tally, "out of role report", report, "403");
            if (report.status() != 403) {
                tally.unexplained("a treasurer read a lending report: " + report.status());
            }
        }
    }

    /** Treasury: further tranches through the statement - recognised while acceptances hold the capital row. */
    private void treasuryOnce() throws Exception {
        Thread.sleep(6_000 + RANDOMNESS.nextInt(4_000));
        long headroom = a.transactions().inTransaction(uow -> a.beans().getBean(
                com.finapp.lending.LendingCapitalHeadroom.class).atRest(uow, EUR)).headroom().minorUnits();
        if (headroom > 2 * TRANCHE) {
            return;
        }
        Door proposing = RANDOMNESS.nextBoolean() ? a.door() : b.door();
        world.tranche(proposing, proposing == a.door() ? b.door() : a.door(), treasurer, secondTreasurer, TRANCHE, true,
                tally);
    }

    /**
     * The weather: payout faults armed per borrower's destination (a lost response, payments then UNKNOWN until the
     * inquiry is answered), the scheme flipped to rejecting or slow for a moment (FAILED, UNKNOWN), the origination hint
     * delivered twice, duplicate sweep ticks on both instances, a standing lost before a disbursement, a password-only
     * session at the doors that need MFA.
     */
    private void chaosOnce() throws Exception {
        Thread.sleep(400 + RANDOMNESS.nextInt(400));
        int dice = RANDOMNESS.nextInt(100);
        if (dice < 15) {
            Borrower banked = pool.stream().filter(found -> found.grant() != null).toList()
                    .get(RANDOMNESS.nextInt((int) pool.stream().filter(found -> found.grant() != null).count()));
            scheme.losesTheResponseWhenTheBodyContains(SimulatedInstantSchemeAdapter.TRANSFERS_PATH,
                    "dest-" + banked.grant());
            tally.count("chaos: lost payout responses armed for a destination");
        } else if (dice < 20) {
            scheme.succeedsWith(SimulatedInstantSchemeAdapter.TRANSFERS_PATH, 200, "{\"status\":\"rejected\"}");
            Thread.sleep(600);
            world.schemeAccepts();
            tally.count("chaos: the scheme rejected for a moment");
        } else if (dice < 24) {
            scheme.respondsAfter(SimulatedInstantSchemeAdapter.TRANSFERS_PATH, Duration.ofSeconds(2), 200,
                    "{\"status\":\"accepted\",\"reference\":\"sch-slow-{{request.headers.Idempotency-Key}}\","
                            + "\"cycle\":\"C1\"}");
            Thread.sleep(600);
            world.schemeAccepts();
            tally.count("chaos: the scheme slow for a moment");
        } else if (dice < 45) {
            answerInquiries();
        } else if (dice < 60) {
            // Duplicate sweep ticks on both instances' own schedule beans, beside their lifecycles.
            Instance on = RANDOMNESS.nextBoolean() || restarting ? a : b;
            on.servicingSchedule().sweepOnce();
            on.payoutSchedule().sweepOnce();
            on.disbursementSchedule().sweepOnce();
            on.originationSchedule().sweepOnce();
            tally.count("chaos: duplicate ticks");
        } else if (dice < 70) {
            // The origination hint delivered twice, late, from both instances: a permit, never a fact.
            String request = scalar("SELECT decision_request_id::text FROM lending.loan_application"
                    + " WHERE status = 'AWAITING_DECISION' ORDER BY random() LIMIT 1");
            if (request != null && !restarting) {
                bothAtOnce(() -> hint(a, request), () -> hint(b, request));
                tally.count("chaos: the origination hint twice");
            }
        } else if (dice < 75) {
            CreditFixtures.Customer weak = world.passwordOnly();
            Optional<String> offer = Optional.ofNullable(scalar("SELECT id::text FROM lending.loan_offer"
                    + " WHERE status = 'OFFERED' ORDER BY random() LIMIT 1"));
            if (offer.isPresent()) {
                Answer refused = a.door().post(OFFERS + "/" + offer.get() + "/acceptance",
                        LendingStormWorld.acceptance("ab".repeat(32), null), weak.token(), key());
                judged(tally, "password-only acceptance", refused, "403", "404 lending.NotFound");
                if (refused.status() == 201) {
                    tally.unexplained("a password-only session accepted an offer");
                }
            }
        } else if (dice < 80) {
            CREDIT.armTransientFault(RANDOMNESS.nextBoolean());
            tally.count("chaos: a credit source fault armed");
        }
    }

    private boolean hint(Instance on, String request) {
        return on.transactions().inTransaction(uow -> on.beans().getBean(LoanOrigination.class)
                .hintRequest(uow, UUID.fromString(request)));
    }

    /**
     * The scheme answers its inquiries truthfully, at every inquiry: for every withdrawal payments holds DISPATCHED or
     * UNKNOWN, accepted once the scheme has recorded our reference (it executed it), unrecognised until then - and an
     * answer once "unrecognised" is re-armed "accepted" the moment the record shows the send; and every pay-in initiation
     * awaiting its payer, executed.
     *
     * <p><strong>Never frozen at the first sighting</strong> (the round-5 stall of P5's first performance): payments
     * commits a withdrawal DISPATCHED before its send reaches the scheme, and the scheme's record trails the wire - so a
     * sighting in that window saw no send. Armed once, "unrecognised" then outlived the send it denied: with the response
     * lost, payments held the withdrawal UNKNOWN and - correctly, ADR-0062 section 3 - would conclude the scheme's word
     * NEVER_RECEIVED only past the rail's declared 90 s outcome deadline plus the 1 s margin, beyond the rest bound; and
     * once concluded it would have been a lie about a transfer the scheme executed. Serialised, so a stale snapshot of
     * the record can never re-arm "unrecognised" over an "accepted" a fresher one armed.
     */
    private synchronized void answerInquiries() throws SQLException {
        Set<String> sent = Set.copyOf(scheme.headerValues(SimulatedInstantSchemeAdapter.TRANSFERS_PATH, "Idempotency-Key"));
        for (String reference : strings("SELECT end_to_end_reference FROM payments.withdrawal WHERE status IN"
                + " ('DISPATCHED', 'UNKNOWN') AND instruction_reference LIKE 'lending:%'")) {
            if (sent.contains(reference) ? inquiryAccepts.add(reference)
                    : !inquiryAccepts.contains(reference) && answered.add(reference)) {
                scheme.succeedsWith(SimulatedInstantSchemeAdapter.TRANSFER_STATUS_PATH + reference, 200,
                        sent.contains(reference) ? "{\"status\":\"accepted\",\"reference\":\"sch-inq-" + reference
                                + "\",\"cycle\":\"C2\"}" : "{\"status\":\"unrecognised\"}");
                tally.count("chaos: an inquiry answered");
            }
        }
        for (String[] payIn : rows("SELECT a.end_to_end_reference, i.amount_minor::text FROM payments.payment_attempt a"
                + " JOIN payments.payment_intent i ON i.id = a.intent_id WHERE a.rail = 'instant'"
                + " AND a.end_to_end_reference IS NOT NULL AND a.status IN ('AWAITING_PAYER', 'EXECUTION_UNKNOWN')")) {
            if (answered.add(payIn[0])) {
                scheme.succeedsWith(SimulatedInstantSchemeAdapter.INITIATION_STATUS_PATH + payIn[0], 200,
                        "{\"status\":\"accepted\",\"reference\":\"sch-pi-" + payIn[0] + "\",\"cycle\":\"C4\",\"amount\":\""
                                + decimal(Long.parseLong(payIn[1])) + "\",\"currency\":\"EUR\"}");
                tally.count("chaos: a pay-in executed by the payer's bank");
            }
        }
    }

    /** References whose inquiry answers "unrecognised" (withdrawals) or executed (pay-ins): armed once each. */
    private final Set<String> answered = ConcurrentHashMap.newKeySet();
    /** Withdrawal references whose inquiry answers "accepted": the scheme's record shows the send. */
    private final Set<String> inquiryAccepts = ConcurrentHashMap.newKeySet();

    /** Terms v4 mid-load, four eyes across instances: offers outstanding keep their pinned version. */
    private Void activateTermsMidLoad() throws Exception {
        world.activateTerms(a.door(), b.door(), officer, secondOfficer,
                LendingStormWorld.terms("PERSONAL_LOAN", "UTC", true, "0.0799", "v4 mid-storm"), tally);
        tally.count("terms v4 activated mid-load");
        return null;
    }

    // ---------------------------------------------------------------- the boundaries

    /**
     * From {@code T_k - 8 s} to {@code T_k + 8 s} both instances drive the cohort's accounts directly - A five seconds
     * ahead of the server, B five behind - with repayments beside them, so the accrual boundary is judged by both skewed
     * instances on both sides of the real midnight. Only the database clock under L3 may decide it.
     */
    private void boundaryChoreography(Cohort cohort) throws Exception {
        Instant from = cohort.boundary().minusSeconds(8);
        while (LendingStormWorld.databaseNow().isBefore(from)) {
            Thread.sleep(200);
        }
        List<Account> theirs = accounts.stream().filter(account -> account.cohort() == cohort.k()
                && account.kind() != AccountKind.EXTERNAL_LOAN).toList();
        while (LendingStormWorld.databaseNow().isBefore(cohort.boundary().plusSeconds(8))) {
            for (Account account : theirs) {
                for (Instance on : restarting ? List.of(a) : List.of(a, b)) {
                    try {
                        serviceOnce(on, account.loan());
                        tally.count("boundary: a direct servicing step");
                    } catch (RuntimeException failure) {
                        tally.unexplained("a direct servicing step at the boundary failed: "
                                + failure.getClass().getSimpleName());
                    }
                }
            }
            Thread.sleep(250);
        }
        coverage("boundary " + cohort.k() + " crossed", 1);
    }

    private LoanServicing.Outcome serviceOnce(Instance on, String loan) {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(LendingStormWorld.flow())) {
            return on.transactions().inTransaction(uow -> on.servicing().service(uow, LoanId.of(UUID.fromString(loan))));
        }
    }

    /** At each boundary something landed exactly on its date: the last day accrued, an instalment due, a default. */
    private void assertTheBoundariesWereCrossed() throws Exception {
        SoftAssertions softly = new SoftAssertions();
        for (Cohort cohort : cohorts) {
            LocalDate lastDay = cohort.boundary().atOffset(cohort.zone()).toLocalDate().minusDays(1);
            LocalDate dayAfter = lastDay.plusDays(1);
            for (Account account : accounts) {
                if (account.cohort() != cohort.k() || account.kind() == AccountKind.EXTERNAL_LOAN) {
                    continue;
                }
                long accrued = count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ?::uuid"
                        + " AND accrual_date = ?", UUID.fromString(account.loan()), lastDay);
                long open = count("SELECT count(*) FROM lending.loan WHERE id = ?::uuid AND status IN ('ACTIVE',"
                        + " 'CLOSING')", UUID.fromString(account.loan()));
                if (open == 1 && account.kind() != AccountKind.LINE) {
                    softly.assertThat(accrued).as("cohort %s: the last day before the boundary accrued once (%s)",
                            cohort.k(), account.role()).isEqualTo(1);
                }
                if (accrued == 1) {
                    coverage("boundary accrual of the day before T_k", 1);
                }
                if (account.role() == Role.FALLS_DUE) {
                    long billed = count("SELECT count(*) FROM lending.instalment_billing WHERE loan_id = ?::uuid"
                            + " AND due_date = ?", UUID.fromString(account.loan()), dayAfter);
                    softly.assertThat(billed).as("cohort %s: the instalment due on the boundary's date billed once",
                            cohort.k()).isEqualTo(1);
                    coverage("boundary billing at T_k", (int) billed);
                }
                if (account.role() == Role.DEFAULTS) {
                    long defaulted = count("SELECT count(*) FROM lending.loan_condition_event WHERE loan_id = ?::uuid"
                            + " AND kind = 'DEFAULT' AND defaulted AND business_date = ?", UUID.fromString(account.loan()),
                            dayAfter);
                    softly.assertThat(defaulted).as("cohort %s: the account defaulted on the boundary's date, once",
                            cohort.k()).isEqualTo(1);
                    coverage("boundary default at T_k", (int) defaulted);
                }
                if (account.role() == Role.STATES) {
                    long stated = count("SELECT count(*) FROM lending.credit_line_statement WHERE loan_id = ?::uuid"
                            + " AND cycle_end = ?", UUID.fromString(account.loan()), dayAfter);
                    softly.assertThat(stated).as("cohort %s: the line's first cycle, ending at the boundary, stated once",
                            cohort.k()).isEqualTo(1);
                    coverage("boundary statement at T_k", (int) stated);
                }
                if (account.role() == Role.BUCKET_30) {
                    long moved = count("SELECT count(*) FROM lending.loan_condition_event WHERE loan_id = ?::uuid"
                            + " AND kind = 'DELINQUENCY' AND bucket = 'B2_30_59' AND business_date = ?",
                            UUID.fromString(account.loan()), dayAfter);
                    softly.assertThat(moved).as("cohort %s: the account moved to 30+ on the boundary's date, once",
                            cohort.k()).isEqualTo(1);
                    coverage("boundary bucket change at T_k", (int) moved);
                }
            }
        }
        softly.assertAll();
        for (Account account : accounts) {
            if (account.role() != Role.PLAIN) {
                reserved.remove(account.loan());
            }
        }
    }

    // ---------------------------------------------------------------- rest

    /**
     * A servicing claim pushes the permit one retry cadence ahead (2 s here) while its step runs: a permit within this
     * window may be a step still in flight, so the storm is not at rest until every permit lies beyond it.
     */
    private static final String IN_FLIGHT = "interval '3 seconds'";

    /**
     * The movers stopped; the sweeps drain to the quiet predicates on the database: no credit request in progress, no
     * disbursement pending, no payout in flight (the scheme's inquiries answered), every account in servicing serviced
     * for its business date - no servicing step still in flight.
     */
    private void rest(String when) throws Exception {
        long deadline = System.nanoTime() + REST_BOUND.toNanos();
        while (true) {
            answerInquiries();
            long busy = count("SELECT (SELECT count(*) FROM credit.decision_request WHERE status IN ('SUBMITTED',"
                    + " 'COLLECTING', 'READY', 'EVALUATED')) + (SELECT count(*) FROM lending.loan_application WHERE status"
                    + " IN ('SUBMITTED', 'AWAITING_DECISION')) + (SELECT count(*) FROM lending.loan WHERE status ="
                    + " 'PENDING_DISBURSEMENT') + (SELECT count(*) FROM lending.loan_payout WHERE status IN ('PENDING',"
                    + " 'DISPATCHING', 'DISPATCHED')) + (SELECT count(*) FROM lending.loan WHERE status IN ('ACTIVE',"
                    + " 'CLOSING') AND next_servicing_at <= statement_timestamp() + " + IN_FLIGHT + ")");
            if (busy == 0) {
                return;
            }
            assertThat(System.nanoTime()).as("%s: the storm comes to rest - still busy: %s", when, rows("SELECT 'request'"
                    + " , count(*)::text FROM credit.decision_request WHERE status IN ('SUBMITTED', 'COLLECTING', 'READY',"
                    + " 'EVALUATED') UNION ALL SELECT 'application', count(*)::text FROM lending.loan_application WHERE"
                    + " status IN ('SUBMITTED', 'AWAITING_DECISION') UNION ALL SELECT 'pending loan', count(*)::text FROM"
                    + " lending.loan WHERE status = 'PENDING_DISBURSEMENT' UNION ALL SELECT 'payout ' || status,"
                    + " count(*)::text FROM lending.loan_payout WHERE status IN ('PENDING', 'DISPATCHING', 'DISPATCHED')"
                    + " GROUP BY status UNION ALL SELECT 'unserviced', count(*)::text FROM lending.loan WHERE status IN"
                    + " ('ACTIVE', 'CLOSING') AND next_servicing_at <= statement_timestamp() + " + IN_FLIGHT).stream()
                    .map(List::of)
                    .toList()).isLessThan(deadline);
            Thread.sleep(500);
        }
    }

    // ================================================================== 2. the catalogue (filled in below)

    private void catalogue() throws Exception {
        LendingStormCatalogue scenes = new LendingStormCatalogue(this);
        scenes.run();
    }

    // ================================================================== 3. the crash points

    private void crashes() throws Exception {
        LendingStormCatalogue scenes = new LendingStormCatalogue(this);
        scenes.crashPoints();
    }

    // ================================================================== every reading

    void census(String when, boolean atRest) throws SQLException {
        SoftAssertions softly = new SoftAssertions();
        census.read(when, atRest, softly);
        softly.assertThat(tally.unexplained).as("%s: C15 unexplained failures", when).isEmpty();
        softly.assertAll();
    }

    // ================================================================== 4. at rest

    /** C13: every act counted against its audit rows - losers record nothing; proposer never the approver. */
    private void assertTheAuditCountsEveryAct() throws Exception {
        SoftAssertions softly = new SoftAssertions();
        Map<String, String> equal = new LinkedHashMap<>();
        equal.put("lending.OfferAccepted", "SELECT count(*) FROM lending.loan");
        equal.put("lending.LoanDisbursed", "SELECT count(*) FROM lending.loan_disbursement WHERE status = 'POSTED'");
        equal.put("lending.WaiverProposed", "SELECT count(*) FROM lending.waiver");
        equal.put("lending.WaiverApproved", "SELECT count(*) FROM lending.waiver_decision WHERE outcome = 'APPROVED'");
        equal.put("lending.WaiverRejected", "SELECT count(*) FROM lending.waiver_decision WHERE outcome = 'REJECTED'");
        equal.put("lending.RepaymentReversalProposed", "SELECT count(*) FROM lending.repayment_reversal");
        equal.put("lending.RepaymentReversalApproved",
                "SELECT count(*) FROM lending.repayment_reversal WHERE status = 'APPROVED'");
        equal.put("lending.RepaymentReversalRejected",
                "SELECT count(*) FROM lending.repayment_reversal WHERE status = 'REJECTED'");
        equal.put("lending.AmendmentProposed", "SELECT count(*) FROM lending.loan_amendment");
        equal.put("lending.AmendmentApproved",
                "SELECT count(*) FROM lending.loan_amendment_decision WHERE outcome = 'APPROVED'");
        equal.put("lending.AmendmentRejected",
                "SELECT count(*) FROM lending.loan_amendment_decision WHERE outcome = 'REJECTED'");
        equal.put("lending.AmendmentAccepted", "SELECT count(*) FROM lending.loan_agreement WHERE amendment_id IS NOT NULL");
        equal.put("lending.TermsVersionProposed", "SELECT count(*) FROM lending.loan_terms_version");
        equal.put("lending.TermsVersionActivated",
                "SELECT count(*) FROM lending.loan_terms_version_event WHERE to_status = 'ACTIVE'");
        equal.put("lending.CapitalContributionProposed", "SELECT count(*) FROM lending.capital_contribution");
        equal.put("lending.CapitalContributionApproved",
                "SELECT count(*) FROM lending.capital_contribution_event WHERE to_status = 'APPROVED'");
        for (Map.Entry<String, String> pair : equal.entrySet()) {
            softly.assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = ? AND outcome ="
                    + " 'SUCCEEDED'", pair.getKey())).as("C13 %s audited once per row", pair.getKey())
                    .isEqualTo(count(pair.getValue()));
        }
        for (String read : List.of("lending.ReportRead", "lending.ExplanationRead", "lending.LoanReplayed",
                "lending.LoanRead")) {
            softly.assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = ? AND outcome ="
                    + " 'SUCCEEDED'", read)).as("C13 %s - exactly the storm's successful reads", read)
                    .isEqualTo(tally.auditedReads(read));
        }
        softly.assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'lending.SelfDealingRefused' AND outcome = 'FAILED'")).as("C13 every self-dealing attempt recorded")
                .isEqualTo(tally.of("self-dealing refused"));
        softly.assertThat(count("SELECT (SELECT count(*) FROM lending.waiver w JOIN lending.waiver_decision d"
                + " ON d.waiver_id = w.id WHERE d.decided_by = w.proposed_by) + (SELECT count(*) FROM"
                + " lending.repayment_reversal WHERE decided_by = proposed_by) + (SELECT count(*) FROM lending.loan_amendment m"
                + " JOIN lending.loan_amendment_decision d ON d.amendment_id = m.id WHERE d.decided_by = m.proposed_by)"))
                .as("C13 four eyes: no proposer decided their own proposal (INV-LND-11)").isZero();
        softly.assertAll();
    }

    /** C16: the cash proof holds (capital only from the statement) and every ledger projection replays CLEAN. */
    private void assertTheCashAndTheProjections() throws Exception {
        try (Connection snapshot = DatabaseRoles.application()) {
            snapshot.setAutoCommit(false);
            snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            snapshot.setReadOnly(true);
            try {
                PositionProof.Report position = context.getBean(PositionProof.class).sweep(snapshot);
                assertThat(position.cashCurrenciesFailing()).as("C16 the cash proof: CASH_AT_BANK is the statement"
                        + " chain's closing (INV-SET-06) - the storm planted no money").isZero();
                ProjectionVerification.Report projections =
                        new ProjectionVerification(new JdbcBalanceDerivation()).verify(snapshot);
                assertThat(projections.drifting()).as("C16 every ledger projection replays from zero CLEAN").isZero();
                System.out.printf("P11-TST-001 PROJECTIONS: %d verified, %d drifting, %d in flight%n",
                        projections.verified(), projections.drifting(), projections.inFlight());
            } finally {
                snapshot.rollback();
            }
        }
    }

    /** C18: on both instances the proofs read now (behind no floor), the ages and the lags zero, the sweepers enabled. */
    private void assertTheGaugesAtRest() throws Exception {
        SoftAssertions softly = new SoftAssertions();
        for (Instance on : List.of(a, b)) {
            LendingProofs.Reading proofs = on.beans().getBean(LendingProofMetrics.class).readNow();
            softly.assertThat(proofs.failing()).as("C18 %s subledger FAILS", on.name()).isZero();
            softly.assertThat(proofs.replaying(LoanReplayProof.Verdict.DIVERGED)).as("C18 %s replay DIVERGED", on.name())
                    .isZero();
            softly.assertThat(on.beans().getBean(LendingCapitalMetrics.class).readNow().failing())
                    .as("C18 %s capital proof failing", on.name()).isZero();
            softly.assertThat(on.beans().getBean(ExposureCensusMetrics.class).readNow().failing())
                    .as("C18 %s exposure census failing", on.name()).isZero();
            for (String sweeper : List.of("origination", "disbursement", "payout", "servicing")) {
                softly.assertThat(gauge(on, "finapp.lending." + sweeper + ".sweeper.enabled"))
                        .as("C18 %s runs the %s sweep", on.name(), sweeper).isEqualTo(1.0);
            }
            for (String age : List.of("finapp.lending.disbursement.pending.age", "finapp.lending.payout.dispatched.age",
                    "finapp.lending.accrual.lag")) {
                softly.assertThat(awaitGauge(on, age)).as("C18 %s %s at rest", on.name(), age).isZero();
            }
            softly.assertThat(counter(on, "finapp.lending.allocation.anomaly")).as("C18 %s allocation anomalies",
                    on.name()).isZero();
        }
        softly.assertAll();
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

    private static double counter(Instance on, String name, String... tags) {
        var search = on.meters().find(name);
        for (int i = 0; i + 1 < tags.length; i += 2) {
            search = search.tag(tags[i], tags[i + 1]);
        }
        return search.counters().stream().mapToDouble(Counter::count).sum();
    }

    /** C19: both instances' counters summed equal the committed rows - every act counted, once, after its commit. */
    private void assertTheMetersCountTheRows() throws Exception {
        SoftAssertions softly = new SoftAssertions();
        double accepted = 0;
        double drawn = 0;
        for (Instance on : List.of(a, b)) {
            accepted += counter(on, LoanAcceptanceMetrics.ACCEPTANCE, "outcome", "accepted");
            drawn += counter(on, CreditLineDrawMetrics.DRAW, "outcome", "drawn");
        }
        // B was restarted: its first context's meters died with it, so the rows are at least the meters, and the
        // restarted instance's acts are counted from its own start - the meters may never exceed the rows.
        softly.assertThat((long) accepted).as("C19 acceptances counted, never more than the loans")
                .isLessThanOrEqualTo(count("SELECT count(*) FROM lending.loan")).isPositive();
        softly.assertThat((long) drawn).as("C19 draws counted, never more than the draws")
                .isLessThanOrEqualTo(count("SELECT count(*) FROM lending.credit_line_draw"));
        softly.assertAll();
    }

    /** C21: one send per withdrawal reference - never re-sent while UNKNOWN; every lending withdrawal sent. */
    private void assertTheSchemeSentOnce() throws Exception {
        List<String> sends = scheme.headerValues(SimulatedInstantSchemeAdapter.TRANSFERS_PATH, "Idempotency-Key");
        Map<String, Integer> perReference = new TreeMap<>();
        sends.forEach(reference -> perReference.merge(reference, 1, Integer::sum));
        List<String> resent = new ArrayList<>();
        perReference.forEach((reference, times) -> {
            if (times > 1) {
                resent.add(reference);
            }
        });
        assertThat(resent).as("C21 one send per withdrawal reference (INV-PAY-04)").isEmpty();
        assertThat(count("SELECT count(*) FROM payments.withdrawal WHERE instruction_reference LIKE 'lending:%'"))
                .as("C21 every lending withdrawal reached the scheme once").isEqualTo(strings("SELECT end_to_end_reference"
                        + " FROM payments.withdrawal WHERE instruction_reference LIKE 'lending:%'").stream()
                        .filter(perReference::containsKey).count());
        int[] pulls = CREDIT.pulls();
        assertThat(pulls[0]).as("C21 the bureau produced reports").isPositive();
        System.out.printf("P11-TST-001 SCHEME: %d sends, %d withdrawals; credit: %d bureau and %d financial-data reports%n",
                sends.size(), perReference.size(), pulls[0], pulls[1]);
    }

    /** C20: the needle - no response carried it, it rests only in credit's evidence, no log line holds it or an id. */
    private void assertTheNeedleReachedNoSink(CapturedOutput output) throws Exception {
        assertThat(tally.needleSightings).as("C20 no response carried the marker").isEmpty();
        String captured = output.getAll();
        assertThat(captured).as("C20 no log line carries the marker").doesNotContain(NEEDLE);
        List<String> leaked = new ArrayList<>();
        for (Account account : accounts) {
            if (captured.contains(account.loan())) {
                leaked.add("a loan id");
            }
            if (captured.contains(account.borrower().customer().party().toString())) {
                leaked.add("a party id");
            }
        }
        assertThat(leaked).as("C20 no loan or party id in the captured output (route templates, never URIs)").isEmpty();
    }

    /** The coverage ledger: every scenario at or above its minimum - counted from rows. */
    private void assertEveryScenarioWasExercised() {
        Map<String, Integer> minimum = new LinkedHashMap<>();
        minimum.put("scenario 1 one disbursement on both instances", 5);
        minimum.put("scenario 1 ten dispatchers one withdrawal", 1);
        minimum.put("scenario 2 one repayment key on both instances", 5);
        minimum.put("scenario 2 auto-collection serviced on both instances", 3);
        minimum.put("scenario 3 two repayments on one instalment", 5);
        minimum.put("scenario 4 one accrual run on both instances", 5);
        minimum.put("scenario 5 a lost response concluded by inquiry", 1);
        minimum.put("scenario 6 a reversal after allocation, a later repayment untouched", 1);
        minimum.put("scenario 7 payoff stale", 1);
        minimum.put("scenario 7 closed then not repayable", 1);
        minimum.put("scenario 8 an amendment applied once", 1);
        minimum.put("scenario 8 an amendment lapsed", 1);
        minimum.put("scenario 9 killed after the posting", 1);
        minimum.put("scenario 9 the relay killed and restarted", 1);
        minimum.put("scenario 10 payoff beside the delinquency worker", 3);
        minimum.put("scenario 4 a step killed mid-run", 1);
        minimum.put("crash points", 12);
        minimum.put("a waiver approved across instances", 1);
        // The acceptance's own backend identified behind a held L0 (C17): at least one of its two held-lock sightings
        // committed, so its transaction id was matched - a refused (422) sighting has no row to match.
        minimum.put("C17 lock order observed behind a held L0", 1);
        minimum.put("boundary 1 crossed", 1);
        minimum.put("boundary 2 crossed", 1);
        minimum.put("boundary 3 crossed", 1);
        SoftAssertions softly = new SoftAssertions();
        minimum.forEach((scenario, least) -> softly.assertThat(coverage.getOrDefault(scenario, new AtomicInteger())
                .get()).as("the coverage ledger: %s", scenario).isGreaterThanOrEqualTo(least));
        softly.assertAll();
    }

    private void summary(long began) throws Exception {
        Map<String, Integer> covered = new TreeMap<>();
        coverage.forEach((name, value) -> covered.put(name, value.get()));
        System.out.printf("P11-TST-001 SUMMARY: %d s, %d rounds, %d census readings (%d at rest), %d restarts, %d deadlocks;"
                + " loans %d (lines %d), accruals %d, billings %d, statements %d, repayments %d, draws %d, reversals %d,"
                + " waivers %d, amendments %d, closures %d, payouts %d, conditions %d%n",
                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - began), rounds.get(), census.readings.get(),
                census.restReadings.get(), restarts.get(), census.deadlocksSinceTheStormBegan(),
                count("SELECT count(*) FROM lending.loan"), count("SELECT count(*) FROM lending.loan WHERE kind ="
                        + " 'REVOLVING'"), count("SELECT count(*) FROM lending.interest_accrual"),
                count("SELECT count(*) FROM lending.instalment_billing"),
                count("SELECT count(*) FROM lending.credit_line_statement"), count("SELECT count(*) FROM lending.repayment"),
                count("SELECT count(*) FROM lending.credit_line_draw"),
                count("SELECT count(*) FROM lending.repayment_reversal WHERE status = 'APPROVED'"),
                count("SELECT count(*) FROM lending.waiver"), count("SELECT count(*) FROM lending.loan_amendment"),
                count("SELECT count(*) FROM lending.loan_closure"), count("SELECT count(*) FROM lending.loan_payout"),
                count("SELECT count(*) FROM lending.loan_condition_event"));
        System.out.printf("P11-TST-001 CENSUS COST: %d ms a reading on average, %d ms the slowest%n",
                TimeUnit.NANOSECONDS.toMillis(census.readingNanos.get() / Math.max(1, census.readings.get())),
                TimeUnit.NANOSECONDS.toMillis(census.slowestReadingNanos.get()));
        System.out.println("P11-TST-001 COVERAGE: " + covered);
        System.out.println("P11-TST-001 ACTS: " + tally.snapshot());
    }

    // ================================================================== the instances

    /** One instance: its doors and the beans the storm drives directly. */
    record Instance(String name, Door door, ApplicationContext beans) {

        MeterRegistry meters() {
            return beans.getBean(MeterRegistry.class);
        }

        LendingTransactions transactions() {
            return beans.getBean(LendingTransactions.class);
        }

        LoanServicing servicing() {
            return beans.getBean(LoanServicing.class);
        }

        LoanServicingSchedule servicingSchedule() {
            return beans.getBean(LoanServicingSchedule.class);
        }

        LoanPayoutSchedule payoutSchedule() {
            return beans.getBean(LoanPayoutSchedule.class);
        }

        LoanDisbursementSchedule disbursementSchedule() {
            return beans.getBean(LoanDisbursementSchedule.class);
        }

        LoanOriginationSchedule originationSchedule() {
            return beans.getBean(LoanOriginationSchedule.class);
        }
    }

    private Instance instance(String name, int port, ApplicationContext beans) {
        return new Instance(name, new Door(name, port, tally), beans);
    }

    Instance other(Instance on) {
        return on == a ? b : a;
    }

    private Door other(Door on) {
        return on == a.door() ? b.door() : a.door();
    }

    private void startInstanceB() throws Exception {
        Clock behind = ServerSkewedClock.of(SKEW.negated());
        List<String> arguments = new ArrayList<>(List.of("--server.port=0",
                "--spring.datasource.hikari.data-source-properties.ApplicationName=" + B_APPLICATION_NAME));
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
     * B killed mid-load and a new B started (the same -5 s skew): its backends terminated while its sweeps hold claims,
     * its context closed - every claim it held re-drives from its permit; nothing it knew in memory is needed.
     */
    private Void restartInstanceB() throws Exception {
        restarting = true;
        b.door().down = true;
        int terminated;
        try {
            terminated = LendingStormFaults.terminateBackends(B_APPLICATION_NAME);
            instanceB.close();
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

    LendingStormWorld world() {
        return world;
    }

    Instance a() {
        return a;
    }

    Instance b() {
        return b;
    }

    List<Borrower> pool() {
        return pool;
    }

    List<Account> accounts() {
        return accounts;
    }

    Set<String> reserved() {
        return reserved;
    }

    SimulatedProvider scheme() {
        return scheme;
    }

    String[] agents() {
        return new String[] {agent, secondAgent};
    }

    String[] officers() {
        return new String[] {officer, secondOfficer};
    }

    String[] treasurers() {
        return new String[] {treasurer, secondTreasurer};
    }

    AtomicInteger servicingFaultsInjected() {
        return servicingFaultsInjected;
    }

    int covered(String scenario) {
        AtomicInteger found = coverage.get(scenario);
        return found == null ? 0 : found.get();
    }

    void coverage(String scenario, int evidence) {
        coverage.computeIfAbsent(scenario, ignored -> new AtomicInteger()).addAndGet(evidence);
    }

    LoanServicing.Outcome service(Instance on, String loan) {
        return serviceOnce(on, loan);
    }

    void restAndCensus(String when) throws Exception {
        rest(when);
        census(when, true);
        System.out.printf("P11-TST-001 SCENE: %s - census exact (%d s into the storm)%n", when,
                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - stormBegan));
    }

    Answer payOffOn(Instance on, Account account) throws Exception {
        return payOff(on, account, false);
    }

    /** A live account no scene owns: in servicing, chosen at random. */
    Optional<Account> liveAccount() throws SQLException {
        List<Account> candidates = new ArrayList<>(accounts);
        Collections.shuffle(candidates);
        for (Account account : candidates.subList(0, Math.min(5, candidates.size()))) {
            if (!reserved.contains(account.loan()) && "ACTIVE".equals(scalar("SELECT status FROM lending.loan"
                    + " WHERE id = ?::uuid", UUID.fromString(account.loan())))) {
                return Optional.of(account);
            }
        }
        return Optional.empty();
    }

    static String money(long minor) {
        return "{\"amount\":\"" + decimal(minor) + "\",\"currency\":\"EUR\"}";
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

    /** All callables at once; their answers in order - a failure fails the caller. */
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

    // ================================================================== the storm's value objects

    enum AccountKind { WALLET_LOAN, EXTERNAL_LOAN, LINE }

    /** What a cohort account is made to do at its boundary. */
    enum Role { PLAIN, FALLS_DUE, DEFAULTS, BUCKET_30, STATES }

    record Borrower(CreditFixtures.Customer customer, String bankMethod, String grant) {}

    record Account(String loan, Borrower borrower, AccountKind kind, int cohort, Role role) {}

    record Cohort(int k, Instant boundary, ZoneOffset zone, String loanTerms, boolean collects) {}

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
