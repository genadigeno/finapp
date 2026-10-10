package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.database.ServerSkewedClock;
import com.finapp.consent.ConsentPurpose;
import com.finapp.credit.AttributeValue;
import com.finapp.credit.CanonicalSnapshot;
import com.finapp.credit.CreditAssessments;
import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditBureau;
import com.finapp.credit.CreditConsentGate;
import com.finapp.credit.CreditDataCollection;
import com.finapp.credit.CreditDataRequestId;
import com.finapp.credit.CreditPartyStanding;
import com.finapp.credit.CreditPolicyStore;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.CreditReplayProof;
import com.finapp.credit.CreditSourceKind;
import com.finapp.credit.Decider;
import com.finapp.credit.DecisionMaking;
import com.finapp.credit.DecisionProgress;
import com.finapp.credit.DecisionReplayer;
import com.finapp.credit.DecisionRequestId;
import com.finapp.credit.EngineVersions;
import com.finapp.credit.FinancialDataProvider;
import com.finapp.credit.JdbcCreditAssessmentStore;
import com.finapp.credit.JdbcDecisionRequestStore;
import com.finapp.credit.JdbcDecisionSnapshotStore;
import com.finapp.credit.JdbcPolicyEvaluationStore;
import com.finapp.credit.JdbcUnderwritingCaseStore;
import com.finapp.credit.PolicyEvaluations;
import com.finapp.credit.ReasonCode;
import com.finapp.credit.ScorecardStore;
import com.finapp.credit.SnapshotFreezer;
import com.finapp.credit.TransactionRunner;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CountryCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
 * THE CREDIT DECISION STORM (`P10-TST-001`, PHASE_10_PLAN.md section 13's last bullet; every {@code Phase: 10}
 * invariant; the ten-instance answer's capstone): decision correctness under every provider fault, crash, duplication,
 * activation and race, at once, on two skewed instances.
 *
 * <h2>Two application instances whose clocks disagree by ten seconds</h2>
 *
 * <p>Instance A is this suite's own context, its {@code Clock} five seconds AHEAD of the database server; instance B is
 * a second {@link com.finapp.app.FinappApplication} over the same database in this JVM, five seconds BEHIND (both
 * server-anchored, {@link ServerSkewedClock}). Both pull from one simulated bureau and one simulated financial-data
 * provider ({@link SimulatedBureauEngine}), so the providers' own counts span both instances. Every window that matters is
 * the database's (expiry, permits, deadlines, data age), so the skew may change nothing the rows can see.
 *
 * <h2>The storm</h2>
 *
 * <ol>
 *   <li><strong>Load</strong>: four movers per instance submitting over their own instance's door for a shared pool of
 *       parties across both products (replayed keys, cancellations, applicants without consent); two progress sweepers and
 *       a retry sweeper per instance; two underwriters per instance taking, releasing, deciding and second-approving; two
 *       officers activating policy and scorecard versions mid-flight on both doors; a chaos actor arming every simulator
 *       fault, withdrawing consents (some while a pull is in flight, through the simulator's own hook), suspending
 *       standings, delivering answers twice and filing requests that expire at the boundary. Every round, in ONE
 *       {@code REPEATABLE READ} snapshot, every census ({@link #census}).
 *   <li><strong>The same object from both instances</strong>, then <strong>the exposure races</strong> (one party's two
 *       products decided at one instant on A and B), <strong>the catalogue</strong> (each fault and section 14 scenario
 *       seeded deterministically) and <strong>the crash points</strong> (a forced rollback or a killed backend after each
 *       step) - each followed by the census.
 *   <li><strong>Drain</strong>: everything to rest - every request closed, every case decided, every data request
 *       terminal.
 *   <li><strong>At rest</strong>: the simulators' counts exactly ours, the audit and outbox and meters exactly the rows,
 *       every decision {@code IDENTICAL} in this JVM and in a second one, the gauges at zero, the door's p99 recorded and
 *       the needle walked.
 * </ol>
 */
@Tag("database")
@Tag("own-container")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CreditDecisionStormDatabaseTest.InstanceA.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("the credit decision storm (P10-TST-001)")
class CreditDecisionStormDatabaseTest {

    private static final Duration SKEW = Duration.ofSeconds(5);
    private static final int MOVERS_PER_INSTANCE = 4;
    private static final int UNDERWRITERS_PER_INSTANCE = 2;
    private static final int MIN_ROUNDS = 15;
    private static final int MIN_DECISIONS = 300;
    private static final long MIN_LOAD_NANOS = TimeUnit.SECONDS.toNanos(90);
    private static final long LOAD_BOUND_NANOS = TimeUnit.MINUTES.toNanos(6);
    private static final Duration PROVIDER_TIMEOUT = Duration.ofMillis(800);
    private static final int RACING_PARTIES = 12;

    private static final String POLICIES = "/v1/operator/credit/policies";
    private static final String SCORECARDS = "/v1/operator/credit/scorecards";
    private static final String CASES = "/v1/operator/credit/review-cases";
    private static final String DECISIONS = "/v1/operator/credit/decisions";
    private static final String BUREAU_CODE = SimulatedBureauAdapter.CODE;
    private static final String FINDATA_CODE = SimulatedFinancialDataAdapter.CODE;
    private static final Set<String> OPEN = Set.of("SUBMITTED", "COLLECTING", "READY", "EVALUATED", "IN_REVIEW");
    private static final Set<String> PIPELINE = Set.of("SUBMITTED", "COLLECTING", "READY", "EVALUATED");

    private static final byte[] KEY = "a-credit-storm-provider-key-32b!!".getBytes(StandardCharsets.UTF_8);
    /** A marker every simulated answer carries in a field no adapter reads: it may rest only in evidence ciphertext. */
    private static final String NEEDLE = "Zorvantik Creditneedle";
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final IdGenerator IDS = CreditTestClient.IDS;

    /** One JVM-wide order: a withdrawal's commit and each simulated answer's write are stamped from it. */
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final SimulatedBureauEngine BUREAU = engine(false);
    private static final SimulatedBureauEngine FINDATA = engine(true);
    /** Each storm party's identifying facts - what the adapters send the simulators, resolved by party id. */
    private static final Map<String, com.finapp.app.credit.CreditDataSubject> FACTS = new ConcurrentHashMap<>();
    private static final CreditDataSubjectResolver SUBJECTS = reference -> Optional.ofNullable(FACTS.get(reference));
    /** {@code party|kind} -> the sequence at which that consent's withdrawal had committed (never re-granted). */
    private static final Map<String, Long> WITHDRAWALS = new ConcurrentHashMap<>();
    /** References whose answer was produced and then held while the subject's consent was withdrawn. */
    private static final Set<String> HOOKED = ConcurrentHashMap.newKeySet();
    private static final List<String> HOOK_FAILURES = new CopyOnWriteArrayList<>();
    private static final AtomicReference<UUID> HOOK_TARGET = new AtomicReference<>();
    private static final AtomicBoolean HOOK_AT_RANDOM = new AtomicBoolean();
    /** Parties no longer offered to movers: withdrawn, suspended, racing or choreographed. */
    private static final Set<UUID> RETIRED = ConcurrentHashMap.newKeySet();

    private static final Map<String, String> PROPERTIES = new LinkedHashMap<>();
    private static ConfigurableApplicationContext instanceB;

    @LocalServerPort private int port;
    @Autowired private ApplicationContext context;
    @Autowired private Authorization authorization;

    private Instance a;
    private Instance b;
    private final AtomicInteger rounds = new AtomicInteger();
    private final Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
    private final List<Long> submissionLatencies = new CopyOnWriteArrayList<>();
    /** Every person's decision at the review door, nanoseconds. */
    private final List<Long> reviewLatencies = new CopyOnWriteArrayList<>();
    private final List<String> needleSightings = new CopyOnWriteArrayList<>();
    private final AtomicInteger queueReads = new AtomicInteger();
    private final List<Party> pool = new CopyOnWriteArrayList<>();
    private final List<CreditTestClient.Customer> unconsenting = new CopyOnWriteArrayList<>();
    private final List<String> underwriterSessions = new CopyOnWriteArrayList<>();
    /** Each underwriter session's identity - the actor its acts are recorded under. */
    private final Map<String, String> identities = new ConcurrentHashMap<>();
    private String officerOne;
    private String officerTwo;
    private String officerThree;
    private final AtomicInteger policyEpoch = new AtomicInteger();

    private static SimulatedBureauEngine engine(boolean financialData) {
        try {
            SimulatedBureauEngine engine = financialData ? SimulatedBureauEngine.startFinancialData()
                    : SimulatedBureauEngine.start();
            engine.sequence(SEQUENCE::incrementAndGet);
            engine.note(NEEDLE);
            engine.slowness(Duration.ofMillis(1_500));
            return engine;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        // Retries every second inside a 45-second window: a fault costs a retry, never the storm's patience.
        PROPERTIES.put("finapp.credit.bureau.retry-cadence", "PT1S");
        PROPERTIES.put("finapp.credit.bureau.collection-window", "PT45S");
        PROPERTIES.put("finapp.credit.findata.retry-cadence", "PT1S");
        PROPERTIES.put("finapp.credit.findata.collection-window", "PT45S");
        PROPERTIES.forEach((name, value) -> registry.add(name, () -> value));
    }

    @AfterAll
    static void stop() {
        if (instanceB != null) {
            instanceB.close();
        }
        BUREAU.close();
        FINDATA.close();
    }

    // ================================================================== the storm

    @Test
    @DisplayName("two instances skewed +/-5 s, every provider fault, crash, duplicate, activation and race: every census"
            + " exact every round, and at rest every decision IDENTICAL, the providers' counts exactly ours")
    void theStormLeavesEveryDecisionProvable(CapturedOutput output) throws Exception {
        long began = System.nanoTime();
        prepareTheWorld();
        startInstanceB();
        a = instance("A", port, context);
        b = instance("B", Integer.parseInt(instanceB.getEnvironment().getProperty("local.server.port")), instanceB);
        hook(BUREAU, CreditSourceKind.BUREAU);
        hook(FINDATA, CreditSourceKind.FINANCIAL_DATA);
        populate();
        census("before the storm");

        // 1. LOAD - both instances at once; every census every round while it blows.
        load();
        drain();
        census("drained after the load");

        // 2. THE SAME OBJECT FROM BOTH INSTANCES, THE EXPOSURE RACES, THE CATALOGUE AND THE CRASH POINTS.
        twoInstanceRaces();
        exposureRaces();
        catalogue();
        crashes();

        // 3. DRAIN.
        drain();
        census("drained");

        // 4. AT REST.
        needleWalk();
        drain();
        census("at rest");
        assertTheSimulatorsCountExactly();
        assertEveryActIsCounted();
        replayCensus();
        assertTheGaugesAtRest();
        assertTheNeedleReachedNoSink(output);
        recordTheDoor(began);
    }

    // ================================================================== 1. load

    private void load() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        HOOK_AT_RANDOM.set(true);
        ExecutorService threads = Executors.newFixedThreadPool(32);
        List<Future<?>> running = new ArrayList<>();
        try {
            for (Instance instance : List.of(a, b)) {
                for (int i = 0; i < MOVERS_PER_INSTANCE; i++) {
                    running.add(threads.submit(() -> forever(stop, "mover " + instance.name(), () -> moveOnce(instance))));
                }
                for (int i = 0; i < 2; i++) {
                    running.add(threads.submit(() -> forever(stop, "progress " + instance.name(), () -> {
                        instance.sweep().sweepOnce();
                        Thread.sleep(40);
                    })));
                }
                running.add(threads.submit(() -> forever(stop, "retry " + instance.name(), () -> {
                    instance.retry().sweepOnce();
                    Thread.sleep(150);
                })));
                for (int i = 0; i < UNDERWRITERS_PER_INSTANCE; i++) {
                    String session = underwriterSessions.get(underwriterSessions.size() - 1 - i
                            - (instance == a ? 0 : UNDERWRITERS_PER_INSTANCE));
                    running.add(threads.submit(() -> forever(stop, "underwriter " + instance.name(),
                            () -> underwriteOnce(instance, session, false))));
                }
            }
            running.add(threads.submit(() -> forever(stop, "officers", this::activateOnce)));
            running.add(threads.submit(() -> forever(stop, "chaos", this::chaosOnce)));
            long started = System.nanoTime();
            long deadline = started + LOAD_BOUND_NANOS;
            while (rounds.get() < MIN_ROUNDS || decisions() < MIN_DECISIONS
                    || System.nanoTime() - started < MIN_LOAD_NANOS) {
                assertThat(System.nanoTime()).as("the storm reached %s rounds and %s decisions (%s)", rounds.get(),
                        decisions(), outcomes).isLessThan(deadline);
                for (Future<?> task : running) {
                    if (task.isDone()) {
                        task.get(); // an actor that died fails the storm with its own failure
                    }
                }
                census("round " + rounds.get());
                Thread.sleep(150);
            }
        } finally {
            stop.set(true);
            HOOK_AT_RANDOM.set(false);
            threads.shutdown();
            assertThat(threads.awaitTermination(3, TimeUnit.MINUTES)).as("every actor stops").isTrue();
        }
        for (Future<?> task : running) {
            task.get();
        }
        System.out.printf("P10-TST-001 LOAD: %d rounds, %d decisions, %d requests, %d hooked withdrawals, %d withdrawals,"
                + " %d approvals above four eyes, %d second approvals; outcomes %s%n", rounds.get(), decisions(),
                count("SELECT count(*) FROM credit.decision_request"), HOOKED.size(), WITHDRAWALS.size(),
                count("SELECT count(*) FROM credit.underwriting_case_event WHERE to_status = 'AWAITING_SECOND'"),
                count("SELECT count(*) FROM credit.underwriting_case_event WHERE from_status = 'AWAITING_SECOND'"
                        + " AND to_status = 'DECIDED'"), new TreeMap<>(outcomes));
        assertThat(HOOK_FAILURES).as("the simulators' hook never failed").isEmpty();
        assertThat(count("SELECT count(*) FROM credit.underwriting_case WHERE status = 'DECIDED'"))
                .as("persons decided under load").isPositive();
        assertThat(count("SELECT count(*) FROM credit.decision_request WHERE status = 'ABANDONED'"))
                .as("withdrawals and suspensions abandoned requests under load").isPositive();
        census("after the load");
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

    private void moveOnce(Instance on) throws Exception {
        Thread.sleep(120 + RANDOMNESS.nextInt(160));
        if (RANDOMNESS.nextInt(100) < 2) {
            // An applicant who never consented: refused at the door, nothing asked of any provider.
            CreditTestClient.Customer stranger = unconsenting.get(RANDOMNESS.nextInt(unconsenting.size()));
            HttpResponse<String> refused = tracked(on.client().submit(stranger, loanBody(100_000, 24),
                    CreditTestClient.key()));
            tally("submit without consent " + refused.statusCode());
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
            assertThat(refused.body()).contains("consent.ConsentRequired");
            return;
        }
        Party party = livePartyOrNull();
        if (party == null) {
            return;
        }
        CreditProduct product = RANDOMNESS.nextBoolean() ? CreditProduct.PERSONAL_LOAN : CreditProduct.CREDIT_LINE;
        String body = product == CreditProduct.PERSONAL_LOAN
                ? loanBody(50_000 + 5_000L * RANDOMNESS.nextInt(231), 12 + RANDOMNESS.nextInt(49))
                : lineBody(25_000 + 5_000L * RANDOMNESS.nextInt(96));
        String key = CreditTestClient.key();
        long started = System.nanoTime();
        HttpResponse<String> submitted = tracked(on.client().submit(party.customer(), body, key));
        submissionLatencies.add(System.nanoTime() - started);
        tally("submit " + submitted.statusCode());
        if (submitted.statusCode() == 202) {
            String id = CreditTestClient.field(submitted.body(), "requestId");
            int dice = RANDOMNESS.nextInt(100);
            if (dice < 8) {
                // The same key again, on the other instance: the same receipt, byte for byte.
                HttpResponse<String> replayed = tracked(other(on).client().submit(party.customer(), body, key));
                tally("replayed " + replayed.statusCode());
                assertThat(replayed.statusCode()).as(replayed.body()).isIn(202, 409);
                if (replayed.statusCode() == 202) {
                    assertThat(replayed.body()).as("a replayed key answers the first receipt").isEqualTo(submitted.body());
                }
            } else if (dice < 14) {
                HttpResponse<String> cancelled = tracked(on.client().cancel(party.customer(), id, CreditTestClient.key()));
                tally("cancel " + cancelled.statusCode());
                assertThat(cancelled.statusCode()).as(cancelled.body()).isIn(200, 409);
            }
        } else if (RETIRED.contains(party.party())) {
            assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(409);
            assertThat(submitted.body()).containsAnyOf("consent.ConsentRequired", "credit.ApplicantNotEligible",
                    "credit.DecisionRequestOpen");
        } else {
            assertThat(submitted.statusCode()).as("a live party's refusal is its open request: %s", submitted.body())
                    .isIn(409);
            assertThat(submitted.body()).contains("credit.DecisionRequestOpen");
        }
    }

    /** One underwriter's act: a second approval if one awaits, else an open case taken and worked. */
    private void underwriteOnce(Instance on, String session, boolean draining) throws Exception {
        Thread.sleep(draining ? 20 : 80 + RANDOMNESS.nextInt(120));
        if (!draining && RANDOMNESS.nextInt(12) == 0) {
            HttpResponse<String> queue = tracked(on.client().get(CASES + "?status=OPEN", session));
            assertThat(queue.statusCode()).as(queue.body()).isEqualTo(200);
            queueReads.incrementAndGet();
        }
        String[] awaiting = row("SELECT id::text FROM credit.underwriting_case WHERE status = 'AWAITING_SECOND'"
                + " ORDER BY random() LIMIT 1");
        if (awaiting != null) {
            boolean refuse = !draining && RANDOMNESS.nextInt(100) < 20;
            HttpResponse<String> second = tracked(on.client().post(CASES + "/" + awaiting[0] + "/second-approval",
                    refuse ? "{\"decision\":\"REFUSE\",\"reason\":\"the income needs a document\"}"
                            : "{\"decision\":\"APPROVE\",\"reason\":\"checked\"}", session, CreditTestClient.key()));
            tally("second " + (refuse ? "refuse " : "approve ") + second.statusCode());
            assertThat(second.statusCode()).as(second.body()).isIn(200, 403, 409, 422);
            if (second.statusCode() == 403) {
                assertThat(second.body()).contains("credit.SelfApprovalRefused");
            } else if (second.statusCode() == 422) {
                // The party's reservations moved since the first decision: the second person refuses it back.
                assertThat(second.body()).contains("credit.ExposureLimitExceeded");
                HttpResponse<String> refused = tracked(on.client().post(CASES + "/" + awaiting[0] + "/second-approval",
                        "{\"decision\":\"REFUSE\",\"reason\":\"the exposure moved since\"}", session,
                        CreditTestClient.key()));
                tally("second refuse after the limit " + refused.statusCode());
                assertThat(refused.statusCode()).as(refused.body()).isIn(200, 409);
            }
            return;
        }
        String[] mine = row("SELECT id::text FROM credit.underwriting_case WHERE status = 'ASSIGNED' AND assignee = ?"
                + " ORDER BY random() LIMIT 1", identities.get(session));
        if (mine != null) {
            // A refused second approval comes back to its first underwriter, who decides it again.
            decline(on, session, CASES + "/" + mine[0]);
            return;
        }
        String[] open = row("SELECT id::text, approvable_minor::text, four_eyes_threshold_minor::text, product"
                + " FROM credit.underwriting_case WHERE status = 'OPEN' ORDER BY random() LIMIT 1");
        if (open == null) {
            return;
        }
        String base = CASES + "/" + open[0];
        HttpResponse<String> taken = tracked(on.client().post(base + "/assignment", null, session, CreditTestClient.key()));
        tally("assign " + taken.statusCode());
        assertThat(taken.statusCode()).as(taken.body()).isIn(200, 409);
        if (taken.statusCode() != 200) {
            return;
        }
        int dice = RANDOMNESS.nextInt(100);
        if (!draining && dice < 15) {
            HttpResponse<String> released = tracked(on.client().post(base + "/release", null, session,
                    CreditTestClient.key()));
            tally("release " + released.statusCode());
            assertThat(released.statusCode()).as(released.body()).isEqualTo(200);
            return;
        }
        long approvable = Long.parseLong(open[1]);
        long minimum = CreditProduct.valueOf(open[3]).minimumAmount().minorUnits();
        if (dice < 55 || approvable < minimum) {
            decline(on, session, base);
            return;
        }
        // Above the four-eyes threshold whenever the referral allows it, mostly: a second person's turn under load.
        long amount = approvable > Long.parseLong(open[2]) && RANDOMNESS.nextInt(10) < 7 ? approvable
                : minimum + (long) (RANDOMNESS.nextDouble() * (approvable - minimum));
        long started = System.nanoTime();
        HttpResponse<String> approved = tracked(on.client().post(base + "/decision", "{\"outcome\":\"APPROVED\","
                + "\"approvedAmount\":\"" + BigDecimal.valueOf(amount, 2).toPlainString() + "\",\"currency\":\"EUR\","
                + "\"reasonCodes\":[\"CRD-RISK-REFERRAL\"],\"reason\":\"verified by phone\"}", session,
                CreditTestClient.key()));
        reviewLatencies.add(System.nanoTime() - started);
        tally("approve " + approved.statusCode());
        assertThat(approved.statusCode()).as(approved.body()).isIn(200, 422);
        if (approved.statusCode() == 422) {
            assertThat(approved.body()).containsAnyOf("credit.ExposureLimitExceeded", "credit.HardDeclineNotOverridable",
                    "credit.ExposureUnassessable");
            decline(on, session, base);
        }
    }

    private void decline(Instance on, String session, String base) throws Exception {
        long started = System.nanoTime();
        HttpResponse<String> declined = tracked(on.client().post(base + "/decision", "{\"outcome\":\"DECLINED\","
                + "\"reasonCodes\":[\"CRD-RISK-REFERRAL\"],\"reason\":\"the referral stands\"}", session,
                CreditTestClient.key()));
        reviewLatencies.add(System.nanoTime() - started);
        tally("decline " + declined.statusCode());
        assertThat(declined.statusCode()).as(declined.body()).isEqualTo(200);
    }

    /** Two officers activate a new version on two instances - a loan policy, a line policy, a scorecard, in turn. */
    private void activateOnce() throws Exception {
        Thread.sleep(9_000);
        int epoch = policyEpoch.incrementAndGet();
        Instance proposing = epoch % 2 == 0 ? a : b;
        switch (epoch % 3) {
            case 0 -> activatePolicy(proposing, CreditProduct.PERSONAL_LOAN, 1_400_000 + 100_000L * ((epoch / 3) % 3),
                    "storm epoch " + epoch);
            case 1 -> activatePolicy(proposing, CreditProduct.CREDIT_LINE, 1_100_000 + 100_000L * ((epoch / 3) % 3),
                    "storm epoch " + epoch);
            default -> activateScorecard(proposing, 500 + 10 * ((epoch / 3) % 3), "storm epoch " + epoch);
        }
    }

    /** The world's weather: faults armed, consents withdrawn, standings suspended, answers delivered twice. */
    private void chaosOnce() throws Exception {
        Thread.sleep(120);
        int dice = RANDOMNESS.nextInt(1_000);
        if (dice < 140) {
            SimulatedBureauEngine.Fault[] faults = SimulatedBureauEngine.Fault.values();
            if (RANDOMNESS.nextInt(3) == 0) {
                // The balance in another currency: never converted - a referral nothing caps, for four eyes.
                BUREAU.arm(SimulatedBureauEngine.Fault.FOREIGN_CURRENCY);
            } else {
                (RANDOMNESS.nextBoolean() ? BUREAU : FINDATA).arm(faults[1 + RANDOMNESS.nextInt(faults.length - 1)]);
            }
            tally("fault armed");
        } else if (dice < 165) {
            Party party = livePartyOrNull();
            if (party != null) {
                withdraw(party.party(), RANDOMNESS.nextBoolean() ? CreditSourceKind.BUREAU : CreditSourceKind.FINANCIAL_DATA);
                tally("consent withdrawn");
            }
        } else if (dice < 185) {
            Party party = livePartyOrNull();
            if (party != null) {
                suspend(party.party());
                tally("standing suspended");
            }
        } else if (dice < 265) {
            // A request filed by the store with a validity of seconds: it expires somewhere along its way.
            Party party = livePartyOrNull();
            if (party != null) {
                CreditProduct product = RANDOMNESS.nextBoolean() ? CreditProduct.PERSONAL_LOAN : CreditProduct.CREDIT_LINE;
                try {
                    CreditWorld.request(party.party(), product, CreditWorld.eur(product.minimumAmount().minorUnits() * 2),
                            Duration.ofMillis(800 + RANDOMNESS.nextInt(3_500)));
                    tally("short-lived request");
                } catch (RuntimeException open) {
                    tally("short-lived request refused (open)");
                }
            }
        } else if (dice < 330) {
            // An answer delivered twice: one data request asked from both instances at one instant.
            String[] requested = row("SELECT id::text FROM credit.data_request WHERE status = 'REQUESTED'"
                    + " ORDER BY random() LIMIT 1");
            if (requested != null) {
                CreditDataRequestId id = CreditDataRequestId.of(UUID.fromString(requested[0]));
                bothAtOnce(() -> a.collection().ask(id, CorrelationId.generate(IDS)),
                        () -> b.collection().ask(id, CorrelationId.generate(IDS)));
                tally("asked twice");
            }
        }
    }

    // ================================================================== 2. the same object from both instances

    private void twoInstanceRaces() throws Exception {
        // THE DOORS REFUSE whoever they are not for, on both instances: nothing taken, decided or activated.
        Party customer = newParty(Profile.GOOD);
        UUID theirs = submitted(customer, lineBody(100_000));
        Party stranger = newParty(Profile.GOOD);
        String someCase = scalar("SELECT id::text FROM credit.underwriting_case LIMIT 1");
        for (Instance on : List.of(a, b)) {
            assertThat(tracked(on.client().read(stranger.customer(), theirs.toString())).statusCode())
                    .as("another customer's request is not found").isEqualTo(404);
            assertThat(tracked(on.client().cancel(stranger.customer(), theirs.toString(), CreditTestClient.key()))
                    .statusCode()).as("nor cancelled").isEqualTo(404);
            assertThat(tracked(on.client().post(CASES + "/" + someCase + "/assignment", null, customer.customer().token(),
                    CreditTestClient.key())).statusCode()).as("a customer takes no case").isEqualTo(403);
            assertThat(tracked(on.client().post(CASES + "/" + someCase + "/assignment", null, officerOne,
                    CreditTestClient.key())).statusCode()).as("an officer takes no case").isEqualTo(403);
            assertThat(tracked(on.client().post(POLICIES, policyJson(CreditProduct.CREDIT_LINE, 1_200_000, 900,
                    "refused"), underwriterSessions.get(0), CreditTestClient.key())).statusCode())
                    .as("an underwriter proposes no policy").isEqualTo(403);
            assertThat(tracked(on.client().get(CASES, null)).statusCode()).as("no session, no queue").isEqualTo(401);
        }
        assertThat(drive(theirs, a.progress())).isIn("DECIDED", "IN_REVIEW");

        // ONE KEY on both instances: one request, the same receipt.
        Party once = newParty(Profile.GOOD);
        String key = CreditTestClient.key();
        String body = loanBody(300_000, 24);
        List<HttpResponse<String>> keyed = bothAtOnce(() -> tracked(a.client().submit(once.customer(), body, key)),
                () -> tracked(b.client().submit(once.customer(), body, key)));
        assertThat(keyed.stream().map(HttpResponse::statusCode).toList()).as("one key, both doors").contains(202)
                .allMatch(status -> status == 202 || status == 409);
        assertThat(count("SELECT count(*) FROM credit.decision_request WHERE party_id = ?", once.party())).isEqualTo(1);

        // TWO KEYS, one party and product, on both instances: one request, the other refused as open.
        Party twice = newParty(Profile.GOOD);
        List<HttpResponse<String>> twoKeys = bothAtOnce(
                () -> tracked(a.client().submit(twice.customer(), lineBody(150_000), CreditTestClient.key())),
                () -> tracked(b.client().submit(twice.customer(), lineBody(150_000), CreditTestClient.key())));
        assertThat(twoKeys.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(202, 409);
        UUID raced = UUID.fromString(scalar("SELECT id::text FROM credit.decision_request WHERE party_id = ?",
                twice.party()));

        // TEN STEPPERS on one request, five per instance: every edge taken once.
        List<Callable<Object>> steppers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Instance on = i % 2 == 0 ? a : b;
            steppers.add(() -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (PIPELINE.contains(status(raced)) && System.nanoTime() < deadline) {
                    step(on.progress(), raced);
                    on.retry().sweepOnce();
                    Thread.sleep(20);
                }
                return null;
            });
        }
        all(steppers);
        assertThat(rows("SELECT to_status, count(*)::text FROM credit.decision_request_event WHERE decision_request_id = ?"
                + " GROUP BY to_status", raced)).as("ten steppers: one edge per state").allSatisfy(edge ->
                assertThat(edge[1]).as(edge[0]).isEqualTo("1"));
        assertThat(count("SELECT count(*) FROM credit.data_request WHERE decision_request_id = ?", raced)).isEqualTo(2);
        census("one key, two keys and ten steppers from both instances");

        // ONE CASE taken on both instances; then ONE SECOND APPROVAL by two underwriters on both.
        Party referred = newParty(Profile.GOOD);
        // A line of 3,000.00: above its four-eyes threshold (2,500.00) and, beside any GOOD party's bureau balance, within
        // the line's limit - so a person may approve it (a loan above its threshold would exceed the loan's limit).
        UUID request = submitted(referred, lineBody(300_000));
        // The financial data unavailable past its deadline: the fallback refers, and the bureau's balance is present, so
        // a person may approve it (the Phase 10 to 11 transition: an absent bureau balance - the USD fault this scenario
        // used - makes the exposure unassessable, and a person may then only decline).
        FINDATA.arm(SimulatedBureauEngine.Fault.UNAVAILABLE);
        step(a.holding(), request);
        ageDeadline(UUID.fromString(scalar("SELECT id::text FROM credit.data_request WHERE decision_request_id = ?"
                + " AND source_kind = 'FINANCIAL_DATA'", request)));
        assertThat(drive(request, a.progress(), b.progress())).isEqualTo("IN_REVIEW");
        String caseId = scalar("SELECT id::text FROM credit.underwriting_case WHERE decision_request_id = ?", request);
        assertThat(scalar("SELECT approvable_minor::text FROM credit.underwriting_case WHERE id = ?::uuid", caseId))
                .as("nothing capped the referral").isEqualTo("300000");
        String first = underwriterSessions.get(0);
        String secondA = underwriterSessions.get(1);
        String secondB = underwriterSessions.get(2);
        List<HttpResponse<String>> takes = bothAtOnce(
                () -> tracked(a.client().post(CASES + "/" + caseId + "/assignment", null, first, CreditTestClient.key())),
                () -> tracked(b.client().post(CASES + "/" + caseId + "/assignment", null, secondA, CreditTestClient.key())));
        assertThat(takes.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 409);
        String holder = takes.get(0).statusCode() == 200 ? first : secondA;
        assertThat(scalar("SELECT assignee FROM credit.underwriting_case WHERE id = ?::uuid", caseId))
                .as("a case is held by its underwriter's identity").isEqualTo(identities.get(holder));
        String other = holder.equals(first) ? secondA : first;
        HttpResponse<String> aboveThreshold = tracked(a.client().post(CASES + "/" + caseId + "/decision",
                "{\"outcome\":\"APPROVED\",\"approvedAmount\":\"3000.00\",\"currency\":\"EUR\",\"reasonCodes\":"
                        + "[\"CRD-SOURCE-UNAVAILABLE\"],\"reason\":\"the income verified by payslip\"}", holder,
                CreditTestClient.key()));
        assertThat(aboveThreshold.statusCode()).as(aboveThreshold.body()).isEqualTo(200);
        assertThat(CreditTestClient.field(aboveThreshold.body(), "status")).isEqualTo("AWAITING_SECOND");
        HttpResponse<String> self = tracked(b.client().post(CASES + "/" + caseId + "/second-approval",
                "{\"decision\":\"APPROVE\"}", holder, CreditTestClient.key()));
        assertThat(self.statusCode()).as(self.body()).isEqualTo(403);
        assertThat(self.body()).contains("credit.SelfApprovalRefused");
        String third = other.equals(secondB) ? underwriterSessions.get(3) : secondB;
        List<HttpResponse<String>> seconds = bothAtOnce(
                () -> tracked(a.client().post(CASES + "/" + caseId + "/second-approval", "{\"decision\":\"APPROVE\"}",
                        other, CreditTestClient.key())),
                () -> tracked(b.client().post(CASES + "/" + caseId + "/second-approval", "{\"decision\":\"APPROVE\"}",
                        third, CreditTestClient.key())));
        assertThat(seconds.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 409);
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", request)).isEqualTo(1);
        census("one case and one second approval from both instances");

        // ONE PROPOSAL approved by two officers on both instances: one activation.
        HttpResponse<String> proposed = tracked(a.client().post(POLICIES, policyJson(CreditProduct.CREDIT_LINE, 1_200_000,
                900, "raced"), officerOne, CreditTestClient.key()));
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String version = CreditTestClient.field(proposed.body(), "id");
        List<HttpResponse<String>> approvals = bothAtOnce(
                () -> tracked(a.client().post(POLICIES + "/" + version + "/approval", "{\"reason\":\"checked\"}",
                        officerTwo, CreditTestClient.key())),
                () -> tracked(b.client().post(POLICIES + "/" + version + "/approval", "{\"reason\":\"checked\"}",
                        officerThree, CreditTestClient.key())));
        assertThat(approvals.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 409);
        assertThat(scalar("SELECT status FROM credit.credit_policy_version WHERE id = ?::uuid", version)).isEqualTo("ACTIVE");

        // A CANCELLATION racing the evaluation, four times: exactly one of CANCELLED and the decision's path.
        for (int i = 0; i < 4; i++) {
            Party racing = newParty(Profile.GOOD);
            UUID ready = submitted(racing, lineBody(100_000));
            assertThat(holdAt(ready, "READY", a)).isEqualTo("READY");
            List<Object> raced2 = bothAtOnce(
                    () -> tracked(a.client().cancel(racing.customer(), ready.toString(), CreditTestClient.key())),
                    () -> step(b.progress(), ready));
            String after = drive(ready, a.progress());
            @SuppressWarnings("unchecked")
            int cancelled = ((HttpResponse<String>) raced2.get(0)).statusCode();
            assertThat(cancelled == 200).as("cancellation answered %s, the request is %s", cancelled, after)
                    .isEqualTo(after.equals("CANCELLED"));
            assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", ready))
                    .isEqualTo(after.equals("DECIDED") ? 1 : 0);
        }
        census("a proposal and cancellations raced from both instances");
    }

    // ================================================================== the exposure races

    /**
     * One party's loan and line, each fitting its limit alone and not beside the other, decided at one instant - the
     * loan on A, the line on B - for twelve parties: the profile lock serialises them, the second sees the first's
     * reservation and declines. The census runs FIRST, before anything about the pairs is asserted.
     */
    private void exposureRaces() throws Exception {
        activatePolicy(a, CreditProduct.PERSONAL_LOAN, 1_500_000, "the exposure races' loan limit");
        activatePolicy(b, CreditProduct.CREDIT_LINE, 1_200_000, "the exposure races' line limit");
        activateScorecard(a, 500, "the exposure races' scorecard");
        List<UUID[]> pairs = new ArrayList<>();
        for (int i = 0; i < RACING_PARTIES; i++) {
            Party racer = newParty(Profile.RACER);
            UUID loan = submitted(racer, loanBody(800_000, 36));
            UUID line = submitted(racer, lineBody(240_000));
            pairs.add(new UUID[] {loan, line});
        }
        for (UUID[] pair : pairs) {
            assertThat(holdAt(pair[0], "EVALUATED", a)).isEqualTo("EVALUATED");
            assertThat(holdAt(pair[1], "EVALUATED", b)).isEqualTo("EVALUATED");
        }
        for (UUID[] pair : pairs) {
            bothAtOnce(() -> decide(a.deciding(), pair[0]), () -> decide(b.deciding(), pair[1]));
        }
        census("the exposure races");
        SoftAssertions softly = new SoftAssertions();
        for (UUID[] pair : pairs) {
            List<String> verdicts = new ArrayList<>();
            for (UUID request : pair) {
                verdicts.add(scalar("SELECT d.outcome || ':' || coalesce((SELECT string_agg(reason_code, ',' ORDER BY"
                        + " ordinal) FROM credit.credit_decision_reason WHERE decision_id = d.id), '')"
                        + " FROM credit.credit_decision d WHERE d.decision_request_id = ?", request));
            }
            softly.assertThat(verdicts).as("one party's two products at one instant").hasSize(2)
                    .anyMatch(verdict -> verdict.startsWith("APPROVED"))
                    .anyMatch(verdict -> verdict.startsWith("DECLINED") && verdict.contains("CRD-EXPOSURE-LIMIT"));
        }
        softly.assertAll();
    }

    // ================================================================== 3. the catalogue

    private void catalogue() throws Exception {
        // EVERY FAULT of each simulator, one at a time, armed just before its pull: the request still decides.
        for (SimulatedBureauEngine engine : List.of(BUREAU, FINDATA)) {
            for (SimulatedBureauEngine.Fault fault : SimulatedBureauEngine.Fault.values()) {
                if (fault == SimulatedBureauEngine.Fault.NONE) {
                    continue;
                }
                Party party = newParty(Profile.GOOD);
                UUID request = submitted(party, lineBody(120_000));
                int pulls = engine.pulls();
                engine.arm(fault);
                String reached = drive(request, b.progress(), a.progress());
                assertThat(reached).as("%s %s", engine == BUREAU ? "bureau" : "findata", fault)
                        .isIn("DECIDED", "IN_REVIEW");
                assertThat(engine.pulls() - pulls).as("%s: one report per reference", fault).isEqualTo(1);
                census((engine == BUREAU ? "bureau " : "findata ") + fault);
            }
        }

        // AN ANSWER DELIVERED TWICE: the same data request asked from both instances, both answers held until both
        // asked - one record, one duplicate evidence, one pull.
        Party twice = newParty(Profile.GOOD);
        UUID duplicated = submitted(twice, lineBody(110_000));
        BUREAU.arm(SimulatedBureauEngine.Fault.UNAVAILABLE);
        step(a.holding(), duplicated);
        String dataRequest = scalar("SELECT id::text FROM credit.data_request WHERE decision_request_id = ?"
                + " AND source_kind = 'BUREAU'", duplicated);
        String reference = scalar("SELECT request_reference FROM credit.data_request WHERE id = ?::uuid", dataRequest);
        assertThat(scalar("SELECT status FROM credit.data_request WHERE id = ?::uuid", dataRequest)).isEqualTo("UNAVAILABLE");
        awaitDatabase("SELECT next_attempt_at <= now() FROM credit.data_request WHERE id = ?::uuid", dataRequest);
        assertThat(a.collection().claimDue(50).stream().map(id -> id.value().toString())).contains(dataRequest);
        int pulls = BUREAU.pulls();
        BUREAU.gather(reference, 2);
        CreditDataRequestId twiceAsked = CreditDataRequestId.of(UUID.fromString(dataRequest));
        bothAtOnce(() -> a.collection().ask(twiceAsked, CorrelationId.generate(IDS)),
                () -> b.collection().ask(twiceAsked, CorrelationId.generate(IDS)));
        assertThat(BUREAU.pulls() - pulls).as("one report for an answer delivered twice").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.credit_record WHERE data_request_id = ?::uuid", dataRequest)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.credit_evidence WHERE data_request_id = ?::uuid AND duplicate",
                dataRequest)).as("the second answer kept as duplicate evidence").isEqualTo(1);
        assertThat(drive(duplicated, a.progress())).isEqualTo("DECIDED");
        census("an answer delivered twice");

        // CONSENT WITHDRAWN MID-PULL: the simulator produced the answer, the withdrawal committed before it was written.
        Party midPull = newParty(Profile.GOOD);
        UUID withdrawnMid = submitted(midPull, lineBody(100_000));
        HOOK_TARGET.set(midPull.party());
        try {
            step(a.holding(), withdrawnMid);
        } finally {
            HOOK_TARGET.set(null);
        }
        String hooked = scalar("SELECT id::text FROM credit.data_request WHERE decision_request_id = ?"
                + " AND source_kind = 'BUREAU'", withdrawnMid);
        assertThat(scalar("SELECT status FROM credit.data_request WHERE id = ?::uuid", hooked)).isEqualTo("CONSENT_WITHDRAWN");
        assertThat(count("SELECT count(*) FROM credit.credit_evidence WHERE data_request_id = ?::uuid"
                + " AND consent_withdrawn AND content_ciphertext IS NULL", hooked)).as("the payload discarded").isEqualTo(1);
        assertThat(drive(withdrawnMid, b.progress())).isEqualTo("ABANDONED");
        assertThat(scalar("SELECT closure_reason FROM credit.decision_request WHERE id = ?", withdrawnMid))
                .isEqualTo("CONSENT_WITHDRAWN");
        census("consent withdrawn mid-pull");

        // CONSENT WITHDRAWN AFTER A SOURCE ANSWERED: the record stays, nothing frozen or decided.
        Party answered = newParty(Profile.GOOD);
        UUID afterAnswer = submitted(answered, lineBody(100_000));
        assertThat(holdAt(afterAnswer, "READY", a)).isEqualTo("READY");
        withdraw(answered.party(), CreditSourceKind.FINANCIAL_DATA);
        assertThat(drive(afterAnswer, b.progress())).isEqualTo("ABANDONED");
        assertThat(count("SELECT count(*) FROM credit.decision_snapshot WHERE decision_request_id = ?", afterAnswer)).isZero();
        assertThat(count("SELECT count(*) FROM credit.data_request WHERE decision_request_id = ? AND status = 'RECEIVED'",
                afterAnswer)).isEqualTo(2);
        census("consent withdrawn after a source answered");

        // STANDING SUSPENDED: before the freeze, and in the deciding transaction.
        Party beforeFreeze = newParty(Profile.GOOD);
        UUID suspendedReady = submitted(beforeFreeze, lineBody(100_000));
        assertThat(holdAt(suspendedReady, "READY", b)).isEqualTo("READY");
        suspend(beforeFreeze.party());
        assertThat(drive(suspendedReady, a.progress())).isEqualTo("ABANDONED");
        Party deciding = newParty(Profile.GOOD);
        UUID suspendedEvaluated = submitted(deciding, lineBody(100_000));
        assertThat(holdAt(suspendedEvaluated, "EVALUATED", a)).isEqualTo("EVALUATED");
        suspend(deciding.party());
        assertThat(decide(b.deciding(), suspendedEvaluated)).isEqualTo(Decider.Decided.ABANDONED);
        for (UUID request : List.of(suspendedReady, suspendedEvaluated)) {
            assertThat(scalar("SELECT status || ':' || closure_reason FROM credit.decision_request WHERE id = ?", request))
                    .isEqualTo("ABANDONED:STANDING_LOST");
        }
        census("standings suspended");

        // A SOURCE UNAVAILABLE PAST ITS DEADLINE: never asked again; the policy's fallback refers.
        Party late = newParty(Profile.GOOD);
        UUID pastDeadline = submitted(late, lineBody(100_000));
        int findataPulls = FINDATA.pulls();
        FINDATA.arm(SimulatedBureauEngine.Fault.UNAVAILABLE);
        step(a.holding(), pastDeadline);
        String unavailable = scalar("SELECT id::text FROM credit.data_request WHERE decision_request_id = ?"
                + " AND source_kind = 'FINANCIAL_DATA'", pastDeadline);
        ageDeadline(UUID.fromString(unavailable));
        assertThat(drive(pastDeadline, b.progress(), a.progress())).isEqualTo("IN_REVIEW");
        assertThat(FINDATA.pulls()).as("past its deadline, never asked again").isEqualTo(findataPulls);
        assertThat(scalar("SELECT status FROM credit.data_request WHERE id = ?::uuid", unavailable)).isEqualTo("UNAVAILABLE");
        census("a source past its deadline");

        // A STALE RECORD: re-collected under a new reference, never decided stale.
        Party stale = newParty(Profile.GOOD);
        UUID staleRequest = submitted(stale, lineBody(100_000));
        Instant now = databaseNow();
        BUREAU.retrievedAt(now.minus(Duration.ofDays(40)));
        try {
            step(a.holding(), staleRequest);
        } finally {
            BUREAU.retrievedAt(now.minus(Duration.ofHours(1)));
        }
        assertThat(drive(staleRequest, b.progress(), a.progress())).isEqualTo("DECIDED");
        assertThat(count("SELECT count(*) FROM credit.data_request WHERE decision_request_id = ? AND source_kind = 'BUREAU'",
                staleRequest)).as("the stale record re-collected under a new reference: %s", rows("SELECT d.source_kind,"
                + " d.status, d.requested_at::text, coalesce(r.retrieved_at::text, '-'), now()::text FROM credit.data_request d"
                + " LEFT JOIN credit.credit_record r ON r.data_request_id = d.id WHERE d.decision_request_id = ?"
                + " ORDER BY d.requested_at", staleRequest).stream().map(List::of).toList()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM credit.decision_snapshot WHERE decision_request_id = ?", staleRequest))
                .isEqualTo(1);
        census("a stale record re-collected");

        // A POLICY AND A SCORECARD ACTIVATED MID-DECISION: the pinned versions decide.
        Party pinnedPolicy = newParty(Profile.GOOD);
        UUID pinned = submitted(pinnedPolicy, loanBody(200_000, 24));
        step(a.holding(), pinned);
        String policy = scalar("SELECT pinned_policy_version_id::text FROM credit.decision_request WHERE id = ?", pinned);
        String model = scalar("SELECT pinned_model_version_id::text FROM credit.decision_request WHERE id = ?", pinned);
        activatePolicy(b, CreditProduct.PERSONAL_LOAN, 1_500_000, "activated mid-decision");
        activateScorecard(a, 500, "activated mid-decision");
        assertThat(drive(pinned, b.progress())).isEqualTo("DECIDED");
        assertThat(scalar("SELECT policy_version_id::text || ' ' || model_version_id::text FROM credit.credit_decision"
                + " WHERE decision_request_id = ?", pinned)).as("decided under the versions it pinned")
                .isEqualTo(policy + " " + model);
        assertThat(scalar("SELECT status FROM credit.credit_policy_version WHERE id = ?::uuid", policy)).isEqualTo("RETIRED");
        census("activations mid-decision");

        // REQUESTS EXPIRING AT THE DECISION BOUNDARY: the deciding transaction on A against the expiry on B.
        Map<String, Integer> boundary = new TreeMap<>();
        for (int i = 0; i < 6; i++) {
            Party party = newParty(Profile.GOOD);
            UUID request = CreditWorld.request(party.party(), CreditProduct.CREDIT_LINE, CreditWorld.eur(100_000),
                    Duration.ofSeconds(6));
            assertThat(holdAt(request, "EVALUATED", a)).isEqualTo("EVALUATED");
            long offset = -150 + 60L * i;
            awaitDatabase("SELECT expires_at + (? * interval '1 millisecond') <= now() FROM credit.decision_request"
                    + " WHERE id = ?", offset, request);
            List<Object> raced = bothAtOnce(() -> decide(a.deciding(), request), () -> step(b.progress(), request));
            // A race that straddles the boundary may leave both conditionals refused (the trigger re-judges each on its
            // own statement's clock): still EVALUATED, and the next step settles it - never both.
            String settled = status(request);
            String end = drive(request, b.progress());
            boundary.merge(settled + (settled.equals(end) ? "" : "->" + end) + " " + raced.stream()
                    .map(answer -> answer instanceof Throwable failure ? failure.getClass().getSimpleName()
                            : String.valueOf(answer)).toList(), 1, Integer::sum);
            assertThat(end).isIn("DECIDED", "EXPIRED");
            assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", request))
                    .isEqualTo(end.equals("DECIDED") ? 1 : 0);
        }
        System.out.println("P10-TST-001 BOUNDARY: " + boundary);
        census("requests expiring at the decision boundary");

        // AN OPEN CASE'S EXPIRY against its assignment: exactly one of ASSIGNED and EXPIRED.
        for (int i = 0; i < 4; i++) {
            Party party = newParty(Profile.REFERRAL);
            UUID request = CreditWorld.request(party.party(), CreditProduct.CREDIT_LINE, CreditWorld.eur(100_000),
                    Duration.ofSeconds(6));
            assertThat(drive(request, a.progress())).isEqualTo("IN_REVIEW");
            String caseId = scalar("SELECT id::text FROM credit.underwriting_case WHERE decision_request_id = ?", request);
            awaitDatabase("SELECT expires_at + (? * interval '1 millisecond') <= now() FROM credit.decision_request"
                    + " WHERE id = ?", -100 + 70L * i, request);
            String underwriter = underwriterSessions.get(i % underwriterSessions.size());
            List<Object> raced = bothAtOnce(
                    () -> tracked(a.client().post(CASES + "/" + caseId + "/assignment", null, underwriter,
                            CreditTestClient.key())),
                    () -> step(b.progress(), request));
            @SuppressWarnings("unchecked")
            int assigned = ((HttpResponse<String>) raced.get(0)).statusCode();
            String caseStatus = scalar("SELECT status FROM credit.underwriting_case WHERE id = ?::uuid", caseId);
            assertThat(caseStatus + "/" + status(request)).as("assignment answered %s", assigned)
                    .isIn("ASSIGNED/IN_REVIEW", "CLOSED/EXPIRED");
            assertThat(assigned == 200).isEqualTo(caseStatus.equals("ASSIGNED"));
            if (caseStatus.equals("ASSIGNED")) {
                assertThat(step(b.progress(), request)).as("a taken case never expires under its underwriter")
                        .isEqualTo(DecisionProgress.Step.WAITING);
                decline(a, underwriter, CASES + "/" + caseId);
            }
        }
        census("open cases expiring against their assignment");
    }

    // ================================================================== the crash points

    private void crashes() throws Exception {
        // THE SUBMISSION refused once: nothing written - not the request, not the key's claim, which commits with it -
        // and the same key then submits once, on the other instance.
        Party submitting = newParty(Profile.GOOD);
        String key = CreditTestClient.key();
        String body = lineBody(100_000);
        try (AutoCloseable fault = refuse("credit.decision_request", "INSERT", "NEW.party_id = '" + submitting.party()
                + "'::uuid", false)) {
            HttpResponse<String> died = tracked(a.client().submit(submitting.customer(), body, key));
            assertThat(died.statusCode()).as(died.body()).isGreaterThanOrEqualTo(500);
        }
        assertThat(count("SELECT count(*) FROM credit.decision_request WHERE party_id = ?", submitting.party())).isZero();
        HttpResponse<String> again = tracked(b.client().submit(submitting.customer(), body, key));
        assertThat(again.statusCode()).as(again.body()).isEqualTo(202);
        UUID resubmitted = UUID.fromString(CreditTestClient.field(again.body(), "requestId"));
        assertThat(drive(resubmitted, a.progress())).isEqualTo("DECIDED");
        assertThat(count("SELECT count(*) FROM credit.decision_request WHERE party_id = ?", submitting.party())).isEqualTo(1);
        census("a crash in the submission");

        // SUBMITTED -> COLLECTING: the opening refused - nothing opened; the other instance opens once.
        Party opening = newParty(Profile.GOOD);
        UUID collect = submitted(opening, lineBody(100_000));
        try (AutoCloseable fault = refuse("credit.data_request", "INSERT", "NEW.decision_request_id = '" + collect
                + "'::uuid", false)) {
            assertThatThrownBy(() -> step(a.progress(), collect)).isInstanceOf(RuntimeException.class);
        }
        assertThat(status(collect)).isEqualTo("SUBMITTED");
        assertThat(count("SELECT count(*) FROM credit.data_request WHERE decision_request_id = ?", collect)).isZero();
        assertThat(drive(collect, b.progress())).isEqualTo("DECIDED");
        assertThat(count("SELECT count(*) FROM credit.data_request WHERE decision_request_id = ?", collect)).isEqualTo(2);
        census("a crash opening the collection");

        // THE RECORDING TRANSACTION KILLED after both providers answered: retried under the same references, one
        // report each at the providers.
        Party recording = newParty(Profile.GOOD);
        UUID record = submitted(recording, lineBody(100_000));
        int bureauPulls = BUREAU.pulls();
        int findataPulls = FINDATA.pulls();
        try (AutoCloseable fault = refuse("credit.credit_record", "INSERT", "NEW.data_request_id IN (SELECT id FROM"
                + " credit.data_request WHERE decision_request_id = '" + record + "'::uuid)", true)) {
            step(a.progress(), record);
        }
        assertThat(count("SELECT count(*) FROM credit.data_request WHERE decision_request_id = ? AND status = 'REQUESTED'",
                record)).as("both recordings died").isEqualTo(2);
        assertThat(drive(record, b.progress())).isEqualTo("DECIDED");
        assertThat(BUREAU.pulls() - bureauPulls).as("the bureau's one report").isEqualTo(1);
        assertThat(FINDATA.pulls() - findataPulls).as("the provider's one summary").isEqualTo(1);
        census("a killed recording transaction");

        // COLLECTING -> READY refused once.
        Party readying = newParty(Profile.GOOD);
        UUID ready = submitted(readying, lineBody(100_000));
        step(a.holding(), ready);
        awaitDatabase("SELECT count(*) = 2 FROM credit.data_request WHERE decision_request_id = ? AND status = 'RECEIVED'",
                ready);
        try (AutoCloseable fault = refuse("credit.decision_request", "UPDATE", "NEW.id = '" + ready
                + "'::uuid AND NEW.status = 'READY'", false)) {
            assertThatThrownBy(() -> step(a.progress(), ready)).isInstanceOf(RuntimeException.class);
        }
        assertThat(status(ready)).isEqualTo("COLLECTING");
        assertThat(drive(ready, b.progress())).isEqualTo("DECIDED");
        census("a crash at READY");

        // READY -> EVALUATED refused once: the freeze, the assessment and the evaluation all rolled back.
        Party freezing = newParty(Profile.GOOD);
        UUID freeze = submitted(freezing, lineBody(100_000));
        assertThat(holdAt(freeze, "READY", a)).isEqualTo("READY");
        try (AutoCloseable fault = refuse("credit.decision_request", "UPDATE", "NEW.id = '" + freeze
                + "'::uuid AND NEW.status = 'EVALUATED'", false)) {
            assertThatThrownBy(() -> step(a.progress(), freeze)).isInstanceOf(RuntimeException.class);
        }
        assertThat(count("SELECT count(*) FROM credit.decision_snapshot WHERE decision_request_id = ?", freeze)).isZero();
        assertThat(drive(freeze, b.progress())).isEqualTo("DECIDED");
        assertThat(count("SELECT count(*) FROM credit.decision_snapshot WHERE decision_request_id = ?", freeze)).isEqualTo(1);
        census("a crash at the freeze");

        // THE DECIDING TRANSACTION KILLED, then its event refused: decided once, by the sweep.
        Party decided = newParty(Profile.GOOD);
        UUID decide = submitted(decided, lineBody(100_000));
        assertThat(holdAt(decide, "EVALUATED", a)).isEqualTo("EVALUATED");
        try (AutoCloseable fault = refuse("credit.credit_decision", "INSERT", "NEW.decision_request_id = '" + decide
                + "'::uuid", true)) {
            assertThatThrownBy(() -> decide(a.deciding(), decide)).isInstanceOf(RuntimeException.class);
        }
        try (AutoCloseable fault = refuse("platform.outbox_event", "INSERT", "NEW.event_type ="
                + " 'credit.CreditDecisionRecorded' AND convert_from(NEW.payload, 'UTF8') LIKE '%" + decide + "%'", false)) {
            assertThatThrownBy(() -> decide(b.deciding(), decide)).isInstanceOf(RuntimeException.class);
        }
        assertThat(status(decide)).isEqualTo("EVALUATED");
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", decide)).isZero();
        assertThat(drive(decide, b.progress())).isEqualTo("DECIDED");
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", decide)).isEqualTo(1);
        census("a killed deciding transaction");

        // THE REFERRAL refused once: one case.
        Party referring = newParty(Profile.REFERRAL);
        UUID refer = submitted(referring, lineBody(100_000));
        assertThat(holdAt(refer, "EVALUATED", b)).isEqualTo("EVALUATED");
        try (AutoCloseable fault = refuse("credit.underwriting_case", "INSERT", "NEW.decision_request_id = '" + refer
                + "'::uuid", false)) {
            assertThatThrownBy(() -> decide(b.deciding(), refer)).isInstanceOf(RuntimeException.class);
        }
        assertThat(decide(a.deciding(), refer)).isEqualTo(Decider.Decided.REFERRED);
        assertThat(count("SELECT count(*) FROM credit.underwriting_case WHERE decision_request_id = ?", refer)).isEqualTo(1);
        census("a crash opening the case");

        // A PERSON'S DECISION refused once: answered 5xx, nothing recorded; decided again, once.
        String caseId = scalar("SELECT id::text FROM credit.underwriting_case WHERE decision_request_id = ?", refer);
        String underwriter = underwriterSessions.get(0);
        assertThat(tracked(a.client().post(CASES + "/" + caseId + "/assignment", null, underwriter,
                CreditTestClient.key())).statusCode()).isEqualTo(200);
        try (AutoCloseable fault = refuse("credit.underwriting_case", "UPDATE", "NEW.id = '" + caseId
                + "'::uuid AND NEW.status = 'DECIDED'", false)) {
            HttpResponse<String> died = tracked(b.client().post(CASES + "/" + caseId + "/decision", "{\"outcome\":"
                    + "\"DECLINED\",\"reasonCodes\":[\"CRD-RISK-REFERRAL\"],\"reason\":\"thin file\"}", underwriter,
                    CreditTestClient.key()));
            assertThat(died.statusCode()).as(died.body()).isGreaterThanOrEqualTo(500);
        }
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", refer)).isZero();
        assertThat(scalar("SELECT status FROM credit.underwriting_case WHERE id = ?::uuid", caseId)).isEqualTo("ASSIGNED");
        decline(a, underwriter, CASES + "/" + caseId);
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", refer)).isEqualTo(1);
        census("a crash in a person's decision");
    }

    // ================================================================== 4. drain

    /** Everything to rest: every request closed, every case decided, every data request terminal. */
    private void drain() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService threads = Executors.newFixedThreadPool(12);
        List<Future<?>> running = new ArrayList<>();
        try {
            for (Instance instance : List.of(a, b)) {
                running.add(threads.submit(() -> forever(stop, "drain progress " + instance.name(), () -> {
                    instance.sweep().sweepOnce();
                    Thread.sleep(30);
                })));
                running.add(threads.submit(() -> forever(stop, "drain retry " + instance.name(), () -> {
                    instance.retry().sweepOnce();
                    Thread.sleep(100);
                })));
            }
            for (int i = 0; i < underwriterSessions.size(); i++) {
                String session = underwriterSessions.get(i);
                Instance on = i % 2 == 0 ? a : b;
                running.add(threads.submit(() -> forever(stop, "drain underwriter", () -> underwriteOnce(on, session, true))));
            }
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
            while (true) {
                long open = count("SELECT count(*) FROM credit.decision_request WHERE status IN ('SUBMITTED', 'COLLECTING',"
                        + " 'READY', 'EVALUATED', 'IN_REVIEW')")
                        + count("SELECT count(*) FROM credit.data_request WHERE status = 'REQUESTED' OR (status ="
                        + " 'UNAVAILABLE' AND deadline_at > now())")
                        + count("SELECT count(*) FROM credit.underwriting_case WHERE status IN ('OPEN', 'ASSIGNED',"
                        + " 'AWAITING_SECOND')");
                if (open == 0) {
                    break;
                }
                for (Future<?> task : running) {
                    if (task.isDone()) {
                        task.get();
                    }
                }
                assertThat(System.nanoTime()).as("the storm drains: %s still open - %s", open, rows("SELECT 'request '"
                        + " || status, count(*)::text FROM credit.decision_request WHERE status IN ('SUBMITTED',"
                        + " 'COLLECTING', 'READY', 'EVALUATED', 'IN_REVIEW') GROUP BY status UNION ALL SELECT 'data ' ||"
                        + " status, count(*)::text FROM credit.data_request WHERE status IN ('REQUESTED', 'UNAVAILABLE')"
                        + " GROUP BY status").stream().map(List::of).toList()).isLessThan(deadline);
                Thread.sleep(500);
            }
        } finally {
            stop.set(true);
            threads.shutdown();
            assertThat(threads.awaitTermination(2, TimeUnit.MINUTES)).isTrue();
        }
        for (Future<?> task : running) {
            task.get();
        }
    }

    // ================================================================== every round

    /**
     * ONE REPEATABLE READ snapshot, every census soft so a failure names every reading that caught it: the exposure
     * census, the decision census, the snapshot sequences, one open request per party and product, one taker per case,
     * the records and the consent census.
     */
    private void census(String when) throws SQLException {
        rounds.incrementAndGet();
        SoftAssertions softly = new SoftAssertions();
        try (Connection snapshot = DatabaseRoles.application()) {
            snapshot.setAutoCommit(false);
            snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            snapshot.setReadOnly(true);
            try {
                exposureCensus(snapshot, when, softly);
                decisionCensus(snapshot, when, softly);
                consentCensus(snapshot, when, softly);
            } finally {
                snapshot.rollback();
            }
        }
        softly.assertAll();
    }

    /**
     * THE EXPOSURE CENSUS ({@code INV-CRD-09}, recomputed from decision rows alone): the profile lock serialises every
     * decision of a party, and each one's limit held over the reservations of those before it. So for every limit m
     * among a party's live approvals, the approvals decided under a limit at most m - with the least bureau balance their
     * snapshots read (absent: zero) - fit m: the last of them in the lock's order saw the others reserved.
     */
    private static void exposureCensus(Connection snapshot, String when, SoftAssertions softly) throws SQLException {
        Map<UUID, List<long[]>> byParty = new HashMap<>();
        try (Statement read = snapshot.createStatement(); ResultSet row = read.executeQuery("SELECT d.party_id,"
                + " d.approved_minor, p.maximum_exposure_minor, s.canonical FROM credit.credit_decision d"
                + " JOIN credit.credit_policy_version p ON p.id = d.policy_version_id"
                + " JOIN credit.decision_snapshot s ON s.id = d.snapshot_id WHERE d.outcome = 'APPROVED'"
                + " AND d.valid_until > statement_timestamp() AND NOT EXISTS (SELECT 1 FROM"
                + " credit.credit_decision_consumption c WHERE c.decision_id = d.id)")) {
            while (row.next()) {
                byParty.computeIfAbsent(row.getObject(1, UUID.class), key -> new ArrayList<>())
                        .add(new long[] {row.getLong(2), row.getLong(3), bureauBalance(row.getString(4))});
            }
        }
        List<String> exceeded = new ArrayList<>();
        for (Map.Entry<UUID, List<long[]>> party : byParty.entrySet()) {
            for (long limit : party.getValue().stream().mapToLong(approval -> approval[1]).distinct().toArray()) {
                long approved = 0;
                long bureau = Long.MAX_VALUE;
                for (long[] approval : party.getValue()) {
                    if (approval[1] <= limit) {
                        approved += approval[0];
                        bureau = Math.min(bureau, approval[2]);
                    }
                }
                if (bureau + approved > limit) {
                    exceeded.add(party.getKey() + ": bureau " + bureau + " + approvals " + approved + " > " + limit);
                }
            }
        }
        softly.assertThat(exceeded).as("%s: the exposure census - no party's live approvals beyond their limits"
                + " (INV-CRD-09)", when).isEmpty();
    }

    private static long bureauBalance(String canonical) {
        try {
            return CanonicalSnapshot.parse(canonical).attribute(CreditAttributeCode.BUREAU_TOTAL_BALANCE).value()
                    instanceof AttributeValue.MoneyValue money ? money.value().minorUnits() : 0;
        } catch (RuntimeException absent) {
            return 0;
        }
    }

    private static void decisionCensus(Connection snapshot, String when, SoftAssertions softly) throws SQLException {
        Map<String, String> zero = new LinkedHashMap<>();
        zero.put("a DECIDED request without exactly one decision",
                "SELECT count(*) FROM credit.decision_request r WHERE r.status = 'DECIDED' AND (SELECT count(*) FROM"
                        + " credit.credit_decision d WHERE d.decision_request_id = r.id) <> 1");
        zero.put("a decision on a request that is not DECIDED (ABANDONED, EXPIRED, CANCELLED or still open)",
                "SELECT count(*) FROM credit.credit_decision d JOIN credit.decision_request r"
                        + " ON r.id = d.decision_request_id WHERE r.status <> 'DECIDED'");
        zero.put("an ABANDONED request without its reason",
                "SELECT count(*) FROM credit.decision_request WHERE status = 'ABANDONED' AND closure_reason IS NULL");
        zero.put("a request's snapshot sequences not 1..n",
                "SELECT count(*) FROM (SELECT decision_request_id FROM credit.decision_snapshot GROUP BY"
                        + " decision_request_id HAVING max(sequence) <> count(*) OR min(sequence) <> 1) gaps");
        zero.put("a snapshot not assessed exactly once",
                "SELECT count(*) FROM credit.decision_snapshot s WHERE (SELECT count(*) FROM credit.credit_assessment a"
                        + " WHERE a.snapshot_id = s.id) <> 1");
        zero.put("an assessment not evaluated exactly once",
                "SELECT count(*) FROM credit.credit_assessment a WHERE (SELECT count(*) FROM credit.policy_evaluation e"
                        + " WHERE e.assessment_id = a.id) <> 1");
        zero.put("a decision not made from its request's latest snapshot",
                "SELECT count(*) FROM credit.credit_decision d JOIN credit.decision_snapshot s ON s.id = d.snapshot_id"
                        + " WHERE s.sequence <> (SELECT max(sequence) FROM credit.decision_snapshot x"
                        + " WHERE x.decision_request_id = d.decision_request_id)");
        zero.put("two open requests for one party and product",
                "SELECT count(*) FROM (SELECT party_id, product FROM credit.decision_request WHERE status IN"
                        + " ('SUBMITTED', 'COLLECTING', 'READY', 'EVALUATED', 'IN_REVIEW') GROUP BY party_id, product"
                        + " HAVING count(*) > 1) doubled");
        zero.put("a case whose takes, less its releases, are not its one taker (or none)",
                "SELECT count(*) FROM credit.underwriting_case c WHERE"
                        + " (SELECT count(*) FROM credit.underwriting_case_event e WHERE e.case_id = c.id"
                        + " AND e.from_status = 'OPEN' AND e.to_status = 'ASSIGNED')"
                        + " - (SELECT count(*) FROM credit.underwriting_case_event e WHERE e.case_id = c.id"
                        + " AND e.from_status = 'ASSIGNED' AND e.to_status = 'OPEN')"
                        + " <> CASE WHEN c.status IN ('ASSIGNED', 'AWAITING_SECOND', 'DECIDED') OR EXISTS (SELECT 1"
                        + " FROM credit.underwriting_case_event e WHERE e.case_id = c.id AND e.to_status = 'CLOSED'"
                        + " AND e.from_status IN ('ASSIGNED', 'AWAITING_SECOND')) THEN 1 ELSE 0 END");
        zero.put("an IN_REVIEW request without exactly one live case, or a live case whose request is not IN_REVIEW",
                "SELECT (SELECT count(*) FROM credit.decision_request r WHERE r.status = 'IN_REVIEW' AND (SELECT count(*)"
                        + " FROM credit.underwriting_case c WHERE c.decision_request_id = r.id AND c.status IN ('OPEN',"
                        + " 'ASSIGNED', 'AWAITING_SECOND')) <> 1) + (SELECT count(*) FROM credit.underwriting_case c JOIN"
                        + " credit.decision_request r ON r.id = c.decision_request_id WHERE c.status IN ('OPEN', 'ASSIGNED',"
                        + " 'AWAITING_SECOND') AND r.status <> 'IN_REVIEW')");
        zero.put("a person's decision without its DECIDED case, or a DECIDED case without its decision",
                "SELECT abs((SELECT count(*) FROM credit.credit_decision WHERE decided_by_type = 'EMPLOYEE')"
                        + " - (SELECT count(*) FROM credit.underwriting_case c JOIN credit.credit_decision d"
                        + " ON d.decision_request_id = c.decision_request_id WHERE c.status = 'DECIDED'))"
                        + " + (SELECT count(*) FROM credit.underwriting_case c WHERE c.status = 'DECIDED' AND NOT EXISTS"
                        + " (SELECT 1 FROM credit.credit_decision d WHERE d.decision_request_id = c.decision_request_id))");
        zero.put("received data requests and records apart",
                "SELECT abs((SELECT count(*) FROM credit.data_request WHERE status = 'RECEIVED')"
                        + " - (SELECT count(*) FROM credit.credit_record)) + (SELECT count(*) FROM credit.credit_record r"
                        + " JOIN credit.data_request d ON d.id = r.data_request_id WHERE d.status <> 'RECEIVED')");
        for (Map.Entry<String, String> census : zero.entrySet()) {
            softly.assertThat(one(snapshot, census.getValue())).as("%s: %s", when, census.getKey()).isZero();
        }
    }

    /**
     * THE CONSENT CENSUS ({@code INV-CRD-03}): a record whose every answer was written after its subject's withdrawal had
     * committed was recorded by a transaction that began after the withdrawal - its gate re-read had to refuse. Ordered by
     * one JVM sequence, never by clocks; read after the snapshot began, so every answer before a recorded record is seen.
     * An answer the simulator held while it withdrew the consent (its hook) is one such case - unless the same reference
     * was asked twice at once and the repeat was answered, from the produced report, before the withdrawal committed: then
     * a record is the platform reading in time, and the census, ordering by the earliest answer, admits it.
     */
    private static void consentCensus(Connection snapshot, String when, SoftAssertions softly) throws SQLException {
        List<String[]> records = new ArrayList<>();
        try (Statement read = snapshot.createStatement(); ResultSet row = read.executeQuery("SELECT d.party_id::text,"
                + " d.source_kind, d.provider_code, d.request_reference FROM credit.credit_record r"
                + " JOIN credit.data_request d ON d.id = r.data_request_id")) {
            while (row.next()) {
                records.add(new String[] {row.getString(1), row.getString(2), row.getString(3), row.getString(4)});
            }
        }
        Map<String, Long> bureauAnswers = BUREAU.firstAnswered();
        Map<String, Long> findataAnswers = FINDATA.firstAnswered();
        Map<String, Long> withdrawals = Map.copyOf(WITHDRAWALS);
        List<String> breaches = new ArrayList<>();
        for (String[] record : records) {
            Long withdrawn = withdrawals.get(record[0] + "|" + record[1]);
            Long answered = (record[2].equals(BUREAU_CODE) ? bureauAnswers : findataAnswers).get(record[3]);
            if (withdrawn != null && (answered == null || answered > withdrawn)) {
                SimulatedBureauEngine engine = record[2].equals(BUREAU_CODE) ? BUREAU : FINDATA;
                breaches.add(record[3] + ": every answer written after the withdrawal committed (" + answered + " > "
                        + withdrawn + "), and recorded; asked " + Collections.frequency(engine.idempotencyKeys(), record[3])
                        + " time(s), " + (HOOKED.contains(record[3]) ? "held mid-pull" : "withdrawn by the weather"));
            }
        }
        softly.assertThat(breaches).as("%s: the consent census - no payload recorded after its withdrawal (INV-CRD-03)",
                when).isEmpty();
    }

    // ================================================================== 5. at rest

    private void assertTheSimulatorsCountExactly() throws Exception {
        SoftAssertions softly = new SoftAssertions();
        for (SimulatedBureauEngine engine : List.of(BUREAU, FINDATA)) {
            String provider = engine == BUREAU ? BUREAU_CODE : FINDATA_CODE;
            Map<String, String> ours = new HashMap<>();
            Set<String> recorded = new HashSet<>();
            for (String[] row : rows("SELECT d.request_reference, d.status || CASE WHEN d.status = 'UNAVAILABLE' AND"
                    + " d.deadline_at <= now() THEN ':PAST' ELSE '' END, (SELECT count(*) FROM credit.credit_record r"
                    + " WHERE r.data_request_id = d.id)::text FROM credit.data_request d WHERE d.provider_code = ?",
                    provider)) {
                ours.put(row[0], row[1]);
                if (!row[2].equals("0")) {
                    recorded.add(row[0]);
                }
            }
            Set<String> produced = engine.producedReferences();
            Set<String> received = new TreeSet<>();
            ours.forEach((reference, status) -> {
                if (status.equals("RECEIVED")) {
                    received.add(reference);
                }
            });
            softly.assertThat(engine.pulls()).as("%s: its counted pulls, one per reference", provider)
                    .isEqualTo(produced.size());
            softly.assertThat(ours.keySet()).as("%s: every reference it produced is one of our data requests", provider)
                    .containsAll(produced);
            softly.assertThat(ours.keySet()).as("%s: no pull without a basis - every reference it was asked under is a"
                    + " data request born under a current consent", provider)
                    .containsAll(new HashSet<>(engine.idempotencyKeys()));
            softly.assertThat(received).as("%s: the references answered RECEIVED are exactly the recorded ones", provider)
                    .isEqualTo(new TreeSet<>(recorded));
            softly.assertThat(produced).as("%s: every received reference was produced by the provider", provider)
                    .containsAll(received);
            for (String reference : produced) {
                softly.assertThat(ours.get(reference)).as("%s: produced %s is received, withdrawn, or past its deadline",
                        provider, reference).isIn("RECEIVED", "CONSENT_WITHDRAWN", "UNAVAILABLE:PAST");
            }
            softly.assertThat(ours.values()).as("%s: at rest nothing is still being asked", provider)
                    .doesNotContain("REQUESTED", "UNAVAILABLE");
            System.out.printf("P10-TST-001 %s: %d references asked, %d reports produced, %d received and recorded%n",
                    provider, new HashSet<>(engine.idempotencyKeys()).size(), produced.size(), received.size());
        }
        softly.assertThat(HOOKED).as("withdrawals held mid-pull were seeded").isNotEmpty();
        Map<String, Long> answered = new HashMap<>(BUREAU.firstAnswered());
        answered.putAll(FINDATA.firstAnswered());
        long withdrawnMidPull = 0;
        for (String reference : HOOKED) {
            String[] held = row("SELECT status, party_id::text || '|' || source_kind FROM credit.data_request"
                    + " WHERE request_reference = ?", reference);
            if (held[0].equals("CONSENT_WITHDRAWN")) {
                withdrawnMidPull++;
            } else {
                softly.assertThat(answered.get(reference)).as("held mid-pull %s is %s: only if a repeat ask was answered"
                        + " before the withdrawal committed", reference, held[0]).isLessThan(WITHDRAWALS.get(held[1]));
            }
        }
        System.out.printf("P10-TST-001 HELD MID-PULL: %d answers held while the consent was withdrawn, %d of them"
                + " CONSENT_WITHDRAWN%n", HOOKED.size(), withdrawnMidPull);
        softly.assertThat(withdrawnMidPull).as("answers held mid-pull discarded").isPositive();
        softly.assertThat(unconsenting).as("applicants without consent tried the door").isNotEmpty();
        for (CreditTestClient.Customer stranger : unconsenting) {
            softly.assertThat(count("SELECT count(*) FROM credit.data_request WHERE party_id = ?", stranger.party()))
                    .as("nothing asked for a party who never consented").isZero();
        }
        softly.assertAll();
    }

    /** Every access and decision act counted against its rows: the audit, the outbox and both instances' meters. */
    private void assertEveryActIsCounted() throws Exception {
        long decisions = decisions();
        SoftAssertions softly = new SoftAssertions();
        Map<String, String> equal = new LinkedHashMap<>();
        equal.put("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.DecisionRecorded'",
                "SELECT count(*) FROM credit.credit_decision");
        equal.put("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.BureauDataRequested'",
                "SELECT count(*) FROM credit.data_request WHERE source_kind = 'BUREAU'");
        equal.put("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.FinancialDataRequested'",
                "SELECT count(*) FROM credit.data_request WHERE source_kind = 'FINANCIAL_DATA'");
        equal.put("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ReviewCaseAssigned'",
                "SELECT count(*) FROM credit.underwriting_case_event WHERE from_status = 'OPEN' AND to_status = 'ASSIGNED'");
        equal.put("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ReviewCaseReleased'",
                "SELECT count(*) FROM credit.underwriting_case_event WHERE from_status = 'ASSIGNED' AND to_status = 'OPEN'");
        equal.put("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ReviewSecondApprovalRefused'",
                "SELECT count(*) FROM credit.underwriting_case_event WHERE from_status = 'AWAITING_SECOND'"
                        + " AND to_status = 'ASSIGNED'");
        equal.put("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.DecisionRequestCancelled'",
                "SELECT count(*) FROM credit.decision_request WHERE status = 'CANCELLED'");
        equal.put("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.PolicyVersionActivated'",
                "SELECT count(*) FROM credit.credit_policy_event WHERE to_status = 'ACTIVE'");
        equal.put("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ScorecardVersionActivated'",
                "SELECT count(*) FROM credit.scorecard_model_event WHERE to_status = 'ACTIVE'");
        equal.put("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ReviewDecided'"
                + " AND outcome = 'SUCCEEDED'", "SELECT count(*) FROM credit.underwriting_case_event WHERE from_status ="
                + " 'ASSIGNED' AND to_status IN ('DECIDED', 'AWAITING_SECOND')");
        equal.put("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ReviewSecondApproval'"
                + " AND outcome = 'SUCCEEDED'", "SELECT count(*) FROM credit.underwriting_case_event WHERE from_status ="
                + " 'AWAITING_SECOND' AND to_status = 'DECIDED'");
        equal.put("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'credit.CreditDecisionRecorded'",
                "SELECT count(*) FROM credit.credit_decision");
        equal.put("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'credit.ManualReviewRequired'",
                "SELECT count(*) FROM credit.underwriting_case");
        for (Map.Entry<String, String> pair : equal.entrySet()) {
            softly.assertThat(count(pair.getKey())).as(pair.getKey()).isEqualTo(count(pair.getValue()));
        }
        softly.assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ReviewCasesRead'"))
                .as("every queue the storm read, audited once").isEqualTo(queueReads.get());
        softly.assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ExplanationRead'"))
                .as("every explanation the storm read, audited once").isEqualTo(1);
        softly.assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.DecisionReplayed'"))
                .as("every replay the storm asked a door for, audited once").isEqualTo(1);
        double decided = 0;
        double reasons = 0;
        for (Instance instance : List.of(a, b)) {
            decided += instance.meters().find(CreditDecisionMetrics.DECISION).counters().stream()
                    .mapToDouble(Counter::count).sum();
            reasons += instance.meters().find(CreditDecisionMetrics.REASON).counters().stream()
                    .mapToDouble(Counter::count).sum();
        }
        softly.assertThat((long) decided).as("both instances' decision counters: every decision counted once, after its"
                + " commit").isEqualTo(decisions);
        softly.assertThat((long) reasons).as("both instances' reason counters")
                .isEqualTo(count("SELECT count(*) FROM credit.credit_decision_reason"));
        softly.assertAll();
    }

    /**
     * THE REPLAY CENSUS ({@code INV-CRD-01}): every decision replays IDENTICAL from its sealed snapshot and pinned
     * versions in this JVM, a second JVM - other defaults, other identity hash codes - reaches every verdict this one did,
     * and every platform decision's reasons are its triggered rules' codes in rule order, recomputed from the rows.
     */
    private void replayCensus() throws Exception {
        CreditReplayProof proof = new CreditReplayProof(context.getBean(DecisionReplayer.class));
        CreditReplayProof.Report report = new CreditReadingSnapshot(DatabaseRoles::application).read(proof::prove);
        long decisions = decisions();
        assertThat(report.diverged()).as("the replay census: no decision DIVERGED").isEmpty();
        assertThat(report.count(DecisionReplayer.Verdict.IDENTICAL)).isEqualTo(decisions);
        List<String> lines = report.replays().stream().map(ReplayInAnotherJvm::line).toList();

        Path out = Files.createTempFile("storm-replay", ".txt");
        Path log = Files.createTempFile("storm-replay", ".log");
        Path coordinates = Files.createTempFile("storm-replay", ".args");
        try {
            List<String> properties = new ArrayList<>();
            for (String property : List.of("finapp.db.url", "finapp.db.app.user", "finapp.db.app.password")) {
                properties.add(quoted("-D" + property + "=" + DatabaseRoles.required(property)));
            }
            Files.write(coordinates, properties, StandardCharsets.UTF_8);
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            List<String> command = new ArrayList<>(List.of(java, "-Xmx1g", "@" + coordinates,
                    "-Dfile.encoding=UTF-16", "-Duser.language=ar", "-Duser.country=EG", "-Duser.timezone=America/Adak",
                    "-cp", System.getProperty("java.class.path"), ReplayInAnotherJvm.class.getName(), out.toString()));
            Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertThat(child.waitFor(10, TimeUnit.MINUTES)).as("the second JVM finished").isTrue();
            assertThat(child.exitValue()).as(Files.readString(log, StandardCharsets.UTF_8)).isZero();
            List<String> theirs = Files.readAllLines(out, StandardCharsets.UTF_8);
            assertThat(theirs.get(0)).isEqualTo("charset=UTF-16 locale=ar-EG zone=America/Adak");
            assertThat(theirs.subList(1, theirs.size())).as("the replay census: a second JVM reaches every verdict")
                    .isEqualTo(lines);
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(log);
            Files.deleteIfExists(coordinates);
        }
        assertThat(reasonsOutOfRuleOrder()).as("the replay census: reasons in rule order, from the rows alone").isEmpty();
        CreditReplayProof.Report gauge = context.getBean(CreditReplayMetrics.class).readNow();
        assertThat(gauge.diverged()).isEmpty();
        System.out.printf("P10-TST-001 REPLAY: %d decisions IDENTICAL in two JVMs (%d by a person)%n", decisions,
                count("SELECT count(*) FROM credit.credit_decision WHERE decided_by_type = 'EMPLOYEE'"));
    }

    private static String quoted(String argument) {
        return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Engine version 1's reasons recomputed from each platform decision's stored rule results and pinned rules. */
    private static List<String> reasonsOutOfRuleOrder() throws SQLException {
        Map<UUID, List<String[]>> rules = new HashMap<>();
        List<String> wrong = new ArrayList<>();
        try (Connection owner = DatabaseRoles.migrator(); Statement statement = owner.createStatement()) {
            try (ResultSet row = statement.executeQuery("SELECT er.evaluation_id, r.reason_code, er.triggered::text,"
                    + " coalesce(r.cap_amount_minor::text, '') FROM credit.policy_evaluation_rule er"
                    + " JOIN credit.policy_evaluation e ON e.id = er.evaluation_id JOIN credit.credit_policy_rule r"
                    + " ON r.policy_version_id = e.policy_version_id AND r.ordinal = er.ordinal"
                    + " ORDER BY er.evaluation_id, er.ordinal")) {
                while (row.next()) {
                    rules.computeIfAbsent(row.getObject(1, UUID.class), key -> new ArrayList<>())
                            .add(new String[] {row.getString(2), row.getString(3), row.getString(4)});
                }
            }
            try (ResultSet row = statement.executeQuery("SELECT d.id, e.id, e.outcome, e.fallback_applied,"
                    + " e.requested_minor, e.approved_minor, e.reason_codes, ARRAY(SELECT reason_code FROM"
                    + " credit.credit_decision_reason WHERE decision_id = d.id ORDER BY ordinal)"
                    + " FROM credit.credit_decision d JOIN credit.credit_assessment a ON a.snapshot_id = d.snapshot_id"
                    + " JOIN credit.policy_evaluation e ON e.assessment_id = a.id WHERE d.decided_by_type = 'SYSTEM'")) {
                while (row.next()) {
                    List<String[]> triggered = rules.getOrDefault(row.getObject(2, UUID.class), List.of()).stream()
                            .filter(rule -> rule[1].equals("true")).toList();
                    List<String> expected = new ArrayList<>();
                    if (row.getString(3).equals("APPROVE")) {
                        long approved = row.getLong(6);
                        if (approved < row.getLong(5)) {
                            expected.add(triggered.stream().filter(rule -> rule[2].equals(Long.toString(approved)))
                                    .map(rule -> rule[0]).findFirst().orElse(ReasonCode.AUTO_APPROVAL_CEILING.code()));
                        }
                    } else {
                        Set<String> firstOfEach = new LinkedHashSet<>();
                        triggered.forEach(rule -> firstOfEach.add(rule[0]));
                        if (row.getBoolean(4)) {
                            firstOfEach.add(ReasonCode.SOURCE_UNAVAILABLE.code());
                        }
                        expected.addAll(firstOfEach);
                    }
                    List<String> evaluation = texts(row.getArray(7));
                    List<String> decision = texts(row.getArray(8));
                    if (!evaluation.equals(expected) || !decision.equals(evaluation)) {
                        wrong.add(row.getObject(1, UUID.class) + " decided " + decision + ", evaluated " + evaluation
                                + ", expected " + expected);
                    }
                }
            }
        }
        return wrong;
    }

    private static List<String> texts(Array array) throws SQLException {
        return List.of((String[]) array.getArray());
    }

    private void assertTheGaugesAtRest() throws Exception {
        for (Instance instance : List.of(a, b)) {
            for (String gauge : List.of("finapp.credit.request.open.age", "finapp.credit.review.age")) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                double value;
                while (true) {
                    value = instance.meters().find(gauge).gauges().stream().mapToDouble(Gauge::value).sum();
                    if (value == 0 || System.nanoTime() > deadline) {
                        break;
                    }
                    Thread.sleep(500);
                }
                assertThat(instance.meters().find(gauge).gauges()).as("%s on %s is published", gauge, instance.name())
                        .isNotEmpty();
                assertThat(value).as("%s on %s at rest", gauge, instance.name()).isZero();
            }
        }
        assertThat(a.meters().find(CreditReplayMetrics.REPLAY).tag("verdict", "DIVERGED").gauge().value())
                .as("the replay gauge's DIVERGED at rest").isZero();
    }

    /** The marker walked through a submission, its collection, a decision, a referral and every door that shows them. */
    private void needleWalk() throws Exception {
        Party walker = newParty(Profile.GOOD);
        UUID decided = submitted(walker, lineBody(120_000));
        assertThat(drive(decided, a.progress(), b.progress())).isEqualTo("DECIDED");
        tracked(a.client().read(walker.customer(), decided.toString()));
        tracked(b.client().get("/v1/me/credit/profile", walker.customer().token()));
        String decision = scalar("SELECT id::text FROM credit.credit_decision WHERE decision_request_id = ?", decided);
        assertThat(tracked(a.client().get(DECISIONS + "/" + decision + "/explanation", officerOne)).statusCode())
                .isEqualTo(200);
        HttpResponse<String> replayed = tracked(b.client().post(DECISIONS + "/" + decision + "/replay",
                "{\"reason\":\"the storm's needle\"}", officerTwo, null));
        assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(200);
        assertThat(replayed.body()).contains("IDENTICAL");
        Party referred = newParty(Profile.REFERRAL);
        UUID review = submitted(referred, lineBody(100_000));
        assertThat(drive(review, b.progress())).isEqualTo("IN_REVIEW");
        HttpResponse<String> queue = tracked(a.client().get(CASES + "?status=OPEN", underwriterSessions.get(0)));
        assertThat(queue.statusCode()).isEqualTo(200);
        queueReads.incrementAndGet();
        assertThat(queue.body()).contains(review.toString());
    }

    private void assertTheNeedleReachedNoSink(CapturedOutput output) throws Exception {
        assertThat(needleSightings).as("no response carried the marker").isEmpty();
        assertThat(tablesHolding(NEEDLE)).as("the marker rests nowhere but the evidence ciphertext").isEmpty();
        assertThat(count("SELECT count(*) FROM credit.credit_evidence WHERE content_ciphertext IS NOT NULL"))
                .as("the evidence the marker travelled in is kept").isPositive();
        assertThat(output.getAll()).as("and no log line carries it").doesNotContain(NEEDLE);
    }

    private void recordTheDoor(long began) {
        List<Long> reviews = new ArrayList<>(reviewLatencies);
        Collections.sort(reviews);
        assertThat(reviews).as("persons' decisions were timed").isNotEmpty();
        System.out.printf("P10-TST-001 REVIEW DOOR: %d decisions by persons - p50 %d ms, p99 %d ms%n", reviews.size(),
                TimeUnit.NANOSECONDS.toMillis(reviews.get(reviews.size() / 2)), TimeUnit.NANOSECONDS.toMillis(
                        reviews.get(Math.min(reviews.size() - 1, (int) Math.ceil(reviews.size() * 0.99) - 1))));
        List<Long> sorted = new ArrayList<>(submissionLatencies);
        Collections.sort(sorted);
        assertThat(sorted).as("submissions were timed").isNotEmpty();
        long p50 = sorted.get(sorted.size() / 2);
        long p99 = sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.99) - 1));
        System.out.printf("P10-TST-001 DOOR: %d submissions - p50 %d ms, p99 %d ms, max %d ms; %d rounds; the storm ran"
                + " %d s; outcomes %s%n", sorted.size(), TimeUnit.NANOSECONDS.toMillis(p50),
                TimeUnit.NANOSECONDS.toMillis(p99), TimeUnit.NANOSECONDS.toMillis(sorted.get(sorted.size() - 1)),
                rounds.get(), TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - began), new TreeMap<>(outcomes));
    }

    // ================================================================== the world

    /** One instance: its door, its progress (and one that stops at evaluation), its deciding step, its sweeps. */
    record Instance(String name, CreditTestClient client, DecisionProgress progress, DecisionProgress holding,
            DecisionMaking deciding, CreditDataCollection collection, CreditDecisionProgressSchedule sweep,
            CreditDataRetrySchedule retry, MeterRegistry meters) {}

    enum Profile { GOOD, RACER, REFERRAL, BAD }

    record Party(CreditTestClient.Customer customer, Profile profile) {
        UUID party() {
            return customer.party();
        }
    }

    @SuppressWarnings("unchecked")
    private static Instance instance(String name, int port, ApplicationContext beans) {
        com.finapp.credit.TransactionRunner transactions = beans.getBean(TransactionRunner.class);
        CreditPolicyStore policies = beans.getBean(CreditPolicyStore.class);
        ScorecardStore scorecards = beans.getBean(ScorecardStore.class);
        CreditDataCollection collection = beans.getBean(CreditDataCollection.class);
        IdGenerator ids = beans.getBean(IdGenerator.class);
        Clock clock = beans.getBean(Clock.class);
        DecisionProgress holding = new DecisionProgress(transactions, new JdbcDecisionRequestStore(), policies, scorecards,
                new JdbcDecisionSnapshotStore(), collection, beans.getBean(SnapshotFreezer.class),
                new CreditAssessments(new JdbcCreditAssessmentStore(), scorecards, new JdbcOutboxWriter(), ids, clock),
                new PolicyEvaluations(new JdbcPolicyEvaluationStore(), policies, EngineVersions.STANDARD, ids, clock),
                (CreditPartyStanding<Connection>) beans.getBean(CreditPartyStanding.class),
                (CreditConsentGate<Connection>) beans.getBean(CreditConsentGate.class), new JdbcOutboxWriter(), ids, clock,
                (id, correlation) -> Decider.Decided.NOTHING, new JdbcUnderwritingCaseStore());
        DecisionProgress progress = beans.getBean(DecisionProgress.class);
        CreditFlowScope flows = beans.getBean(CreditFlowScope.class);
        return new Instance(name, new CreditTestClient(port), progress, holding, beans.getBean(DecisionMaking.class),
                collection, new CreditDecisionProgressSchedule(progress, flows, ids, Duration.ofSeconds(1), 20,
                        Duration.ofMillis(400)),
                new CreditDataRetrySchedule(collection, flows, ids, Duration.ofSeconds(1), 20),
                beans.getBean(MeterRegistry.class));
    }

    private Instance other(Instance on) {
        return on == a ? b : a;
    }

    private void prepareTheWorld() throws Exception {
        Instant recent = databaseNow().minus(Duration.ofHours(1));
        BUREAU.retrievedAt(recent);
        FINDATA.retrievedAt(recent);
        CreditTestClient client = new CreditTestClient(port);
        officerOne = sessionWith(client, RoleName.CREDIT_POLICY_OFFICER);
        officerTwo = sessionWith(client, RoleName.CREDIT_POLICY_OFFICER);
        officerThree = sessionWith(client, RoleName.CREDIT_POLICY_OFFICER);
        for (int i = 0; i < 2 * UNDERWRITERS_PER_INSTANCE; i++) {
            underwriterSessions.add(sessionWith(client, RoleName.UNDERWRITER));
        }
        // The seeds, one officer each (a migration proposed them), then the storm's own versions under four eyes.
        for (String seed : List.of(POLICIES + "/" + CreditWorld.LOAN_SEED.value(), POLICIES + "/"
                + CreditWorld.LINE_SEED.value(), SCORECARDS + "/" + CreditWorld.SCORECARD_SEED.value())) {
            HttpResponse<String> approved = client.post(seed + "/approval", "{\"reason\":\"v1 reviewed\"}", officerOne,
                    CreditTestClient.key());
            assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        }
    }

    private void populate() throws Exception {
        activatePolicy(a, CreditProduct.PERSONAL_LOAN, 1_500_000, "the storm's loan limit");
        activatePolicy(b, CreditProduct.CREDIT_LINE, 1_200_000, "the storm's line limit");
        activateScorecard(a, 500, "the storm's scorecard");
        ExecutorService builders = Executors.newFixedThreadPool(8);
        try {
            List<Future<Party>> made = new ArrayList<>();
            for (int i = 0; i < 72; i++) {
                Profile profile = i < 44 ? Profile.GOOD : i < 58 ? Profile.REFERRAL : Profile.BAD;
                made.add(builders.submit(() -> partyOf(profile)));
            }
            for (Future<Party> party : made) {
                pool.add(party.get());
            }
        } finally {
            builders.shutdown();
        }
        for (int i = 0; i < 2; i++) {
            unconsenting.add(a.client().customer(true));
        }
    }

    /** A pool party, offered to the movers. */
    private Party partyOf(Profile profile) throws Exception {
        CreditTestClient.Customer customer = new CreditTestClient(port).consentingCustomer();
        FACTS.put(customer.party().toString(), facts(profile));
        return new Party(customer, profile);
    }

    /** A choreographed party - never offered to the movers. */
    private Party newParty(Profile profile) throws Exception {
        Party party = partyOf(profile);
        RETIRED.add(party.party());
        return party;
    }

    private Party livePartyOrNull() {
        List<Party> live = pool.stream().filter(party -> !RETIRED.contains(party.party())).toList();
        return live.isEmpty() ? null : live.get(RANDOMNESS.nextInt(live.size()));
    }

    /** Identifying facts the simulators' own derivation turns into the profile's figures. */
    private static com.finapp.app.credit.CreditDataSubject facts(Profile profile) {
        while (true) {
            StringBuilder name = new StringBuilder("Storm ");
            for (int i = 0; i < 10; i++) {
                name.append((char) ('a' + RANDOMNESS.nextInt(26)));
            }
            LocalDate born = LocalDate.of(1960 + RANDOMNESS.nextInt(40), 1 + RANDOMNESS.nextInt(12), 1 + RANDOMNESS.nextInt(28));
            String subject = name + "|" + born + "|DE";
            SimulatedBureauEngine.Figures figures = SimulatedBureauEngine.Figures.of(subject);
            SimulatedBureauEngine.Summary summary = SimulatedBureauEngine.Summary.of(subject);
            boolean affordable = summary.incomeMinor() >= 450_000 && summary.expenditureMinor() <= 150_000
                    && figures.obligationsMinor() <= 60_000;
            boolean clean = !figures.insolvency() && figures.defaults() == 0 && figures.accounts() >= 1
                    && figures.accounts() <= 5;
            boolean fits = switch (profile) {
                case GOOD -> clean && affordable && figures.delinquencies() == 0 && figures.score() >= 650
                        && figures.balanceMinor() >= 200_000 && figures.balanceMinor() <= 800_000;
                case RACER -> clean && affordable && figures.delinquencies() == 0 && figures.score() >= 650
                        && figures.balanceMinor() >= 550_000 && figures.balanceMinor() <= 650_000;
                case REFERRAL -> clean && affordable && figures.delinquencies() >= 1 && figures.score() < 550
                        && figures.balanceMinor() <= 800_000;
                case BAD -> !figures.insolvency() && figures.defaults() == 1 && figures.score() < 550;
            };
            if (fits) {
                return new com.finapp.app.credit.CreditDataSubject(name.toString(), born, CountryCode.of("DE"));
            }
        }
    }

    private static void startInstanceB() throws Exception {
        Clock behind = ServerSkewedClock.of(SKEW.negated());
        List<String> arguments = new ArrayList<>(List.of("--server.port=0"));
        PROPERTIES.forEach((name, value) -> arguments.add("--" + name + "=" + value));
        instanceB = new SpringApplicationBuilder(com.finapp.app.FinappApplication.class)
                .initializers(context -> {
                    GenericApplicationContext generic = (GenericApplicationContext) context;
                    generic.registerBean("instanceBClock", Clock.class, () -> behind, definition -> definition.setPrimary(true));
                    generic.registerBean("instanceBBureau", CreditBureau.class, InstanceA::bureau,
                            definition -> definition.setPrimary(true));
                    generic.registerBean("instanceBFinancialData", FinancialDataProvider.class, InstanceA::financialData,
                            definition -> definition.setPrimary(true));
                })
                .run(arguments.toArray(String[]::new));
    }

    /** The hook each simulator runs between producing a novel answer and writing it. */
    private static void hook(SimulatedBureauEngine engine, CreditSourceKind kind) {
        engine.beforeAnswering(reference -> {
            try {
                UUID party = UUID.fromString(scalar("SELECT party_id::text FROM credit.data_request WHERE"
                        + " request_reference = ?", reference));
                boolean targeted = party.equals(HOOK_TARGET.get()) && kind == CreditSourceKind.BUREAU;
                boolean random = HOOK_AT_RANDOM.get() && !RETIRED.contains(party) && RANDOMNESS.nextInt(100) < 4;
                if (targeted || random) {
                    withdraw(party, kind);
                    HOOKED.add(reference);
                }
            } catch (RuntimeException failure) {
                HOOK_FAILURES.add(failure.toString());
            }
        });
    }

    /** {@code party}'s consent for {@code kind} withdrawn - committed, then stamped; never granted again. */
    private static void withdraw(UUID party, CreditSourceKind kind) {
        try {
            CreditTestClient.withdraw(party, kind == CreditSourceKind.BUREAU ? ConsentPurpose.CREDIT_BUREAU_ACCESS
                    : ConsentPurpose.FINANCIAL_DATA_ACCESS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        WITHDRAWALS.putIfAbsent(party + "|" + kind, SEQUENCE.incrementAndGet());
        RETIRED.add(party);
    }

    private static void suspend(UUID party) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement update = app.prepareStatement("UPDATE party.customer SET status = 'SUSPENDED',"
                        + " status_changed_at = GREATEST(now(), status_changed_at) WHERE party_id = ?")) {
            update.setObject(1, party);
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
        RETIRED.add(party);
    }

    private void activatePolicy(Instance proposing, CreditProduct product, long maximumExposure, String reason)
            throws Exception {
        HttpResponse<String> proposed = tracked(proposing.client().post(POLICIES, policyJson(product, maximumExposure,
                900, reason), officerOne, CreditTestClient.key()));
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        HttpResponse<String> approved = tracked(other(proposing).client().post(POLICIES + "/"
                + CreditTestClient.field(proposed.body(), "id") + "/approval", "{\"reason\":\"checked\"}", officerTwo,
                CreditTestClient.key()));
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        tally("policy activated");
    }

    private void activateScorecard(Instance proposing, int base, String reason) throws Exception {
        HttpResponse<String> proposed = tracked(proposing.client().post(SCORECARDS, scorecardJson(base, reason), officerOne,
                CreditTestClient.key()));
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        HttpResponse<String> approved = tracked(other(proposing).client().post(SCORECARDS + "/"
                + CreditTestClient.field(proposed.body(), "id") + "/approval", "{\"reason\":\"checked\"}", officerTwo,
                CreditTestClient.key()));
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        tally("scorecard activated");
    }

    /** The seed's eleven rules over the wire, with this version's exposure limit. */
    private static String policyJson(CreditProduct product, long maximumExposure, int rate, String reason) {
        boolean loan = product == CreditProduct.PERSONAL_LOAN;
        List<String> rules = List.of(
                rule("SOURCE_UNAVAILABLE_FALLBACK", "ATTRIBUTE", "SOURCE_UNAVAILABLE", "IS_PRESENT", "", "REFER",
                        "CRD-SOURCE-UNAVAILABLE"),
                rule("INSOLVENCY", "ATTRIBUTE", "BUREAU_INSOLVENCY_FLAG", "EQ", ",\"booleanOperand\":true",
                        "HARD_DECLINE", "CRD-INSOLVENCY"),
                rule("PRIOR_DEFAULT", "ATTRIBUTE", "BUREAU_DEFAULTS_72M", "GE", ",\"integerOperand\":1", "DECLINE",
                        "CRD-PRIOR-DEFAULT"),
                rule("RECENT_DELINQUENCY", "ATTRIBUTE", "BUREAU_DELINQUENCIES_24M", "GE", ",\"integerOperand\":3",
                        "DECLINE", "CRD-RECENT-DELINQUENCY"),
                rule("SCORE_FLOOR", "FIGURE", "SCORE", "LT", ",\"integerOperand\":450", "DECLINE",
                        "CRD-SCORE-INSUFFICIENT"),
                rule("SCORE_REFERRAL", "FIGURE", "SCORE", "LT", ",\"integerOperand\":520", "REFER", "CRD-RISK-REFERRAL"),
                rule("AFFORDABILITY", "FIGURE", "AFFORDABLE", "EQ", ",\"booleanOperand\":false", "DECLINE",
                        "CRD-AFFORDABILITY-INSUFFICIENT"),
                rule("EXPOSURE_LIMIT", "FIGURE", "EXPOSURE_HEADROOM", "LT", ",\"moneyOperandMinor\":0", "DECLINE",
                        "CRD-EXPOSURE-LIMIT"),
                rule("INCOME_UNVERIFIED", "ATTRIBUTE", "FINDATA_MONTHLY_INCOME", "IS_ABSENT", "", "REFER",
                        "CRD-INCOME-UNVERIFIED"),
                rule("FOREIGN_CURRENCY", "ATTRIBUTE", "CURRENCY_NOT_SUPPORTED", "IS_PRESENT", "", "REFER",
                        "CRD-CURRENCY-NOT-SUPPORTED"),
                rule("LOW_SCORE_CAP", "FIGURE", "SCORE", "LT", ",\"integerOperand\":600,\"capMinor\":"
                        + (loan ? 500_000 : 100_000), "CAP_AMOUNT", "CRD-SCORE-INSUFFICIENT"));
        return "{\"product\":\"" + product + "\",\"currency\":\"EUR\",\"assessmentRateBps\":" + rate
                + ",\"minimumDisposableMinor\":10000,\"minimumPaymentRatioBps\":" + (loan ? 300 : 500)
                + ",\"maximumExposureMinor\":" + maximumExposure
                + ",\"sources\":[{\"kind\":\"BUREAU\",\"maximumDataAgeSeconds\":2592000},"
                + "{\"kind\":\"FINANCIAL_DATA\",\"maximumDataAgeSeconds\":2592000}],"
                + "\"unavailableFallback\":\"REFER\",\"autoApprovalCeilingMinor\":" + (loan ? 1_000_000 : 250_000)
                + ",\"rules\":[" + String.join(",", rules) + "],\"reason\":\"" + reason + "\"}";
    }

    private static String rule(String code, String kind, String subject, String operator, String operand, String effect,
            String reason) {
        return "{\"ruleCode\":\"" + code + "\",\"subjectKind\":\"" + kind + "\",\"subject\":\"" + subject
                + "\",\"operator\":\"" + operator + "\"" + operand + ",\"effect\":\"" + effect + "\",\"reasonCode\":\""
                + reason + "\"}";
    }

    /** The seed's scorecard table over the wire, with this version's base points. */
    private static String scorecardJson(int base, String reason) {
        return "{\"family\":\"RETAIL_SCORECARD\",\"basePoints\":" + base + ",\"attributes\":["
                + "{\"code\":\"BUREAU_EXTERNAL_SCORE\",\"absentPoints\":-40,\"bands\":[{\"upper\":550,\"points\":-60},"
                + "{\"lower\":550,\"upper\":650,\"points\":0},{\"lower\":650,\"upper\":750,\"points\":40},"
                + "{\"lower\":750,\"points\":80}]},"
                + "{\"code\":\"BUREAU_DELINQUENCIES_24M\",\"absentPoints\":-20,\"bands\":[{\"upper\":1,\"points\":30},"
                + "{\"lower\":1,\"upper\":3,\"points\":-30},{\"lower\":3,\"points\":-90}]},"
                + "{\"code\":\"BUREAU_DEFAULTS_72M\",\"absentPoints\":-20,\"bands\":[{\"upper\":1,\"points\":20},"
                + "{\"lower\":1,\"points\":-120}]},"
                + "{\"code\":\"BUREAU_ACTIVE_ACCOUNTS\",\"absentPoints\":0,\"bands\":[{\"upper\":1,\"points\":-10},"
                + "{\"lower\":1,\"upper\":6,\"points\":10},{\"lower\":6,\"points\":-20}]},"
                + "{\"code\":\"BUREAU_INSOLVENCY_FLAG\",\"absentPoints\":-30,\"bands\":[{\"codes\":[\"false\"],\"points\":10},"
                + "{\"codes\":[\"true\"],\"points\":-200}]}],\"reason\":\"" + reason + "\"}";
    }

    private static String loanBody(long minor, int term) {
        return "{\"product\":\"PERSONAL_LOAN\",\"amount\":\"" + BigDecimal.valueOf(minor, 2).toPlainString()
                + "\",\"currency\":\"EUR\",\"termMonths\":" + term
                + ",\"declaredMonthlyIncome\":\"6000.00\",\"declaredMonthlyExpenditure\":\"1000.00\"}";
    }

    private static String lineBody(long minor) {
        return "{\"product\":\"CREDIT_LINE\",\"amount\":\"" + BigDecimal.valueOf(minor, 2).toPlainString()
                + "\",\"currency\":\"EUR\",\"declaredMonthlyIncome\":\"6000.00\",\"declaredMonthlyExpenditure\":\"1000.00\"}";
    }

    /** A request submitted over instance A's door. */
    private UUID submitted(Party party, String body) throws Exception {
        HttpResponse<String> submitted = tracked(a.client().submit(party.customer(), body, CreditTestClient.key()));
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        return UUID.fromString(CreditTestClient.field(submitted.body(), "requestId"));
    }

    /** Steps the request on {@code steppers} in turn, the retry sweeps between, until it leaves the pipeline. */
    private String drive(UUID request, DecisionProgress... steppers) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        int turn = 0;
        while (PIPELINE.contains(status(request))) {
            assertThat(System.nanoTime()).as("request %s leaves the pipeline (now %s)", request, status(request))
                    .isLessThan(deadline);
            step(steppers[turn++ % steppers.length], request);
            a.retry().sweepOnce();
            b.retry().sweepOnce();
            Thread.sleep(60);
        }
        return status(request);
    }

    /** Steps the request on {@code on}'s progress that never decides, until it reaches {@code target} or closes. */
    private String holdAt(UUID request, String target, Instance on) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!status(request).equals(target) && PIPELINE.contains(status(request))) {
            assertThat(System.nanoTime()).as("request %s reaches %s (now %s)", request, target, status(request))
                    .isLessThan(deadline);
            step(on.holding(), request);
            a.retry().sweepOnce();
            b.retry().sweepOnce();
            Thread.sleep(60);
        }
        return status(request);
    }

    private static DecisionProgress.Step step(DecisionProgress progress, UUID request) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope system = SecurityContext.enterSystem()) {
            return progress.step(DecisionRequestId.of(request), CorrelationId.generate(IDS));
        }
    }

    private static Decider.Decided decide(DecisionMaking deciding, UUID request) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope system = SecurityContext.enterSystem()) {
            return deciding.decide(DecisionRequestId.of(request), CorrelationId.generate(IDS));
        }
    }

    /** Ages a data request's deadline past - the owner's act, the machine's trigger suspended for it. */
    private static void ageDeadline(UUID dataRequest) throws SQLException {
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try (Statement ddl = owner.createStatement();
                    PreparedStatement age = owner.prepareStatement("UPDATE credit.data_request SET requested_at = now()"
                            + " - interval '2 minutes', deadline_at = now() - interval '1 second', next_attempt_at = now()"
                            + " - interval '1 second' WHERE id = ?")) {
                ddl.execute("ALTER TABLE credit.data_request DISABLE TRIGGER USER");
                age.setObject(1, dataRequest);
                assertThat(age.executeUpdate()).isEqualTo(1);
                ddl.execute("ALTER TABLE credit.data_request ENABLE TRIGGER USER");
            }
            owner.commit();
        }
    }

    /**
     * A fault beneath {@code table}'s {@code operation} where {@code condition} holds: the statement refused (a forced
     * rollback) or, {@code kill}, the backend terminated mid-transaction (a killed transaction). Closing lifts it.
     */
    private static AutoCloseable refuse(String table, String operation, String condition, boolean kill) throws Exception {
        String name = "storm_fault_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String schema = table.substring(0, table.indexOf('.'));
        String act = kill ? "PERFORM pg_terminate_backend(pg_backend_pid()); PERFORM pg_sleep(5);"
                : "RAISE EXCEPTION 'injected fault';";
        try (Connection owner = DatabaseRoles.migrator(); Statement ddl = owner.createStatement()) {
            ddl.execute("CREATE FUNCTION " + schema + "." + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                    + " IF " + condition + " THEN " + act + " END IF; RETURN NEW; END $$");
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

    private String sessionWith(CreditTestClient client, RoleName role) throws Exception {
        String login = "storm." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(client.post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada"
                + " Lovelace\",\"password\":\"" + CreditTestClient.PASSWORD + "\"}", null, CreditTestClient.key())
                .statusCode()).isEqualTo(201);
        UUID identity = UUID.fromString(scalar("SELECT id::text FROM identity.identity WHERE login_identifier = ?", login));
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        HttpResponse<String> session = client.post("/v1/authentications", "{\"loginIdentifier\":\"" + login
                + "\",\"password\":\"" + CreditTestClient.PASSWORD + "\"}", null, CreditTestClient.key());
        String token = CreditTestClient.field(session.body(), "sessionToken");
        identities.put(token, identity.toString());
        return token;
    }

    /** Every response the storm receives is searched for the marker as it arrives. */
    private HttpResponse<String> tracked(HttpResponse<String> response) {
        if (response.body() != null && response.body().contains(NEEDLE)) {
            needleSightings.add(response.uri().getPath());
        }
        return response;
    }

    private void tally(String outcome) {
        outcomes.computeIfAbsent(outcome, ignored -> new AtomicInteger()).incrementAndGet();
    }

    /** Both callables released at one instant on their own threads; their answers, in order. */
    private static <T> List<T> bothAtOnce(Callable<T> first, Callable<T> second) throws Exception {
        return all(List.of(first, second));
    }

    private static <T> List<T> all(List<Callable<T>> callables) throws Exception {
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
            List<T> answers = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    answers.add(future.get(2, TimeUnit.MINUTES));
                } catch (java.util.concurrent.ExecutionException failure) {
                    // A loser's refusal is an answer, not the race's failure.
                    @SuppressWarnings("unchecked")
                    T refusal = (T) failure.getCause();
                    answers.add(refusal);
                }
            }
            return answers;
        } finally {
            threads.shutdownNow();
        }
    }

    private static Instant databaseNow() {
        return java.sql.Timestamp.valueOf(scalar("SELECT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US')"))
                .toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
    }

    private static void awaitDatabase(String booleanQuery, Object... parameters) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!"t".equals(scalar(booleanQuery, parameters)) && !"true".equals(scalar(booleanQuery, parameters))) {
            assertThat(System.nanoTime()).as("the database never said: %s", booleanQuery).isLessThan(deadline);
            Thread.sleep(5);
        }
    }

    private static long decisions() {
        return count("SELECT count(*) FROM credit.credit_decision");
    }

    private static String status(UUID request) {
        return scalar("SELECT status FROM credit.decision_request WHERE id = ?", request);
    }

    private static long count(String sql, Object... parameters) {
        return CreditWorld.count(sql, parameters);
    }

    private static String scalar(String sql, Object... parameters) {
        return CreditWorld.scalar(sql, parameters);
    }

    private static long one(Connection connection, String sql) throws SQLException {
        try (Statement read = connection.createStatement(); ResultSet row = read.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String[] row(String sql, Object... parameters) throws SQLException {
        List<String[]> found = rows(sql, parameters);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<String[]> rows(String sql, Object... parameters) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Connection owner = DatabaseRoles.migrator(); PreparedStatement read = owner.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                read.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                int columns = row.getMetaData().getColumnCount();
                while (row.next()) {
                    String[] values = new String[columns];
                    for (int i = 0; i < columns; i++) {
                        values[i] = row.getString(i + 1);
                    }
                    rows.add(values);
                }
            }
        }
        return rows;
    }

    private static List<String> tablesHolding(String needle) throws SQLException {
        List<String> holders = new ArrayList<>();
        try (Connection owner = DatabaseRoles.migrator()) {
            List<String> tables = new ArrayList<>();
            try (PreparedStatement list = owner.prepareStatement("SELECT table_schema || '.' || table_name FROM"
                    + " information_schema.tables WHERE table_type = 'BASE TABLE' AND table_schema NOT IN ('pg_catalog',"
                    + " 'information_schema')"); ResultSet found = list.executeQuery()) {
                while (found.next()) {
                    tables.add(found.getString(1));
                }
            }
            for (String table : tables) {
                try (PreparedStatement scan = owner.prepareStatement("SELECT count(*) FROM " + table
                        + " t WHERE t::text LIKE ?")) {
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

    /** Instance A's own beans: its clock five seconds ahead of the server, and the two simulated sources. */
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
            return bureau();
        }

        @Bean
        @Primary
        FinancialDataProvider instanceAFinancialData() {
            return financialData();
        }

        static CreditBureau bureau() {
            return new SimulatedBureauAdapter(BUREAU.baseUrl(), PROVIDER_TIMEOUT, KEY, SUBJECTS);
        }

        static FinancialDataProvider financialData() {
            return new SimulatedFinancialDataAdapter(FINDATA.baseUrl(), PROVIDER_TIMEOUT, KEY, SUBJECTS);
        }
    }
}
