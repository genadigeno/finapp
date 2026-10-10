package com.finapp.app.credit;

import static com.finapp.app.credit.CreditWorld.BALANCES;
import static com.finapp.app.credit.CreditWorld.SUSPENDED;
import static com.finapp.app.credit.CreditWorld.WITHDRAWN;
import static com.finapp.app.credit.CreditWorld.awaitDatabase;
import static com.finapp.app.credit.CreditWorld.count;
import static com.finapp.app.credit.CreditWorld.decide;
import static com.finapp.app.credit.CreditWorld.deciding;
import static com.finapp.app.credit.CreditWorld.eur;
import static com.finapp.app.credit.CreditWorld.evaluated;
import static com.finapp.app.credit.CreditWorld.scalar;
import static com.finapp.app.credit.CreditWorld.step;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditPolicy;
import com.finapp.credit.CreditPolicyAdministration;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.CreditSourceKind;
import com.finapp.credit.Decider;
import com.finapp.credit.DecisionProgress;
import com.finapp.credit.JdbcReservedExposure;
import com.finapp.credit.PolicyEffect;
import com.finapp.credit.PolicyFigure;
import com.finapp.credit.PolicyOperator;
import com.finapp.credit.ReasonCode;
import com.finapp.credit.ScorecardModelVersionId;
import com.finapp.credit.UnavailableFallback;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Recording the decision against a real database (`P10-TSK-016`; PHASE_10_PLAN.md section 12.7, {@code INV-CRD-09},
 * {@code INV-CRD-06}, {@code INV-CRD-02}, {@code INV-HIST-04}, {@code INV-CRD-01}): the deciding transaction profile-first,
 * the reservation re-read under that lock and a successor snapshot when it moved, one decision per request under every
 * race, and a decision row no role changes. Separate deciders stand for separate instances; every count is read from the
 * rows.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): the suite brings policies into force and records
 * decisions that reserve exposure.
 */
@Tag("database")
@Tag("own-container")
@SuppressWarnings("try")
@DisplayName("recording the credit decision (P10-TSK-016)")
class CreditDecisionDatabaseTest {

    private static final Duration WEEK = Duration.ofDays(7);

    @BeforeAll
    static void inForce() {
        CreditWorld.seedsInForce();
        // No score rule: the seeded scorecard's bands decide nothing here; the exposure and the ceilings do.
        CreditWorld.inForce(CreditProduct.PERSONAL_LOAN, 900, false);
        CreditWorld.inForce(CreditProduct.CREDIT_LINE, 900, false);
    }

    // ------------------------------------------------------------------ the exposure, serialised on the profile

    @Test
    @DisplayName("two products at the exposure limit, decided at once, a hundred rounds: both deciders provably in flight"
            + " together - each seen waiting at the party's profile - then serialised on it: the second always sees the"
            + " first's reservation, never both approved beyond the line's limit")
    void twoProductsAtTheExposureLimitSerialise() throws Exception {
        for (int round = 0; round < 100; round++) {
            UUID party = UUID.randomUUID();
            // 10,000.00 on the bureau: the loan (10,000.00 of 40,000.00) fits with the line's 2,500.00 approval; the
            // line (5,000.00 of 20,000.00) fits alone, not beside the loan's 10,000.00.
            BALANCES.put(party, 1_000_000L);
            UUID loan = evaluated(party, CreditProduct.PERSONAL_LOAN, eur(1_000_000), WEEK);
            UUID line = evaluated(party, CreditProduct.CREDIT_LINE, eur(500_000), WEEK);
            // The race made provable: the party's profile held while both deciders start, released only once the
            // database shows BOTH waiting on it - two deciding transactions open at one time, contending for the lock
            // that must serialise them. Without the observation a "race" may be two deciders one after the other.
            ExecutorService deciders = Executors.newFixedThreadPool(2);
            try (Connection holder = DatabaseRoles.application()) {
                holder.setAutoCommit(false);
                int holderPid;
                try (PreparedStatement hold = holder.prepareStatement("SELECT pg_backend_pid() FROM credit.credit_profile"
                        + " WHERE party_id = ? FOR UPDATE")) {
                    hold.setObject(1, party);
                    try (ResultSet row = hold.executeQuery()) {
                        assertThat(row.next()).isTrue();
                        holderPid = row.getInt(1);
                    }
                }
                Future<Decider.Decided> loanDecided = deciders.submit(() -> decide(deciding(), loan));
                Future<Decider.Decided> lineDecided = deciders.submit(() -> decide(deciding(), line));
                // The first waiter queues on the holder; the second on the first (PostgreSQL's tuple-lock queue) - so
                // both are counted as waiting behind the holder, directly or through the other.
                awaitDatabase("SELECT count(*) = 2 FROM pg_stat_activity w WHERE ? = ANY (pg_blocking_pids(w.pid))"
                        + " OR EXISTS (SELECT 1 FROM pg_stat_activity f WHERE f.pid = ANY (pg_blocking_pids(w.pid))"
                        + " AND ? = ANY (pg_blocking_pids(f.pid)))", holderPid, holderPid);
                holder.rollback();
                assertThat(List.of(loanDecided.get(1, TimeUnit.MINUTES), lineDecided.get(1, TimeUnit.MINUTES)))
                        .as("round %d: both decided", round).containsOnly(Decider.Decided.DECIDED);
            } finally {
                deciders.shutdownNow();
            }
            String loanOutcome = scalar("SELECT outcome FROM credit.credit_decision WHERE decision_request_id = ?", loan);
            String lineOutcome = scalar("SELECT outcome FROM credit.credit_decision WHERE decision_request_id = ?", line);
            int loanSequence = sequenceOf(loan);
            int lineSequence = sequenceOf(line);
            assertThat(loanOutcome).as("round %d: the loan fits beside the line", round).isEqualTo("APPROVED");
            assertThat(loanSequence + lineSequence).as("round %d: exactly one decided second, on a successor", round)
                    .isEqualTo(3);
            if ("APPROVED".equals(lineOutcome)) {
                assertThat(lineSequence).as("round %d: an approved line decided first, before the loan reserved", round)
                        .isEqualTo(1);
            } else {
                assertThat(lineSequence).as("round %d: a declined line saw the loan's reservation", round).isEqualTo(2);
                assertThat(scalar("SELECT array_to_string(array_agg(reason_code ORDER BY ordinal), ',')"
                        + " FROM credit.credit_decision_reason r JOIN credit.credit_decision d ON d.id = r.decision_id"
                        + " WHERE d.decision_request_id = ?", line)).contains("CRD-EXPOSURE-LIMIT");
            }
        }
    }

    @Test
    @DisplayName("ten deciders on one request record one decision - one reason set, one event, one audit record, one edge")
    void tenDecidersRecordOneDecision() throws Exception {
        UUID id = evaluated(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(1_000_000), WEEK);
        List<Decider.Decided> outcomes = race(10, () -> decide(deciding(), id));
        assertThat(outcomes.stream().filter(outcome -> outcome == Decider.Decided.DECIDED)).hasSize(1);
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isIn(Decider.Decided.DECIDED, Decider.Decided.NOTHING));
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", id)).isEqualTo(1);
        String decision = scalar("SELECT id::text FROM credit.credit_decision WHERE decision_request_id = ?", id);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'credit.CreditDecisionRecorded'"
                + " AND aggregate_id = ?", UUID.fromString(decision))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.DecisionRecorded'"
                + " AND target_id = ?", decision)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.decision_request_event WHERE decision_request_id = ?"
                + " AND to_status = 'DECIDED'", id)).isEqualTo(1);
        assertThat(scalar("SELECT status FROM credit.decision_request WHERE id = ?", id)).isEqualTo("DECIDED");
        assertThat(scalar("SELECT valid_until = decided_at + interval '30 days' FROM credit.credit_decision"
                + " WHERE decision_request_id = ?", id)).as("valid for the product's decision validity").isEqualTo("t");
    }

    @Test
    @DisplayName("a reservation grown since the evaluation freezes a successor snapshot - sequence 2 decides and is named;"
            + " sequence 1 kept, with its own assessment and evaluation")
    void aGrownReservationFreezesASuccessorSnapshot() throws Exception {
        UUID party = UUID.randomUUID();
        UUID loan = evaluated(party, CreditProduct.PERSONAL_LOAN, eur(1_000_000), WEEK);
        UUID line = evaluated(party, CreditProduct.CREDIT_LINE, eur(200_000), WEEK);
        assertThat(decide(deciding(), line)).isEqualTo(Decider.Decided.DECIDED);
        assertThat(decide(deciding(), loan)).isEqualTo(Decider.Decided.DECIDED);
        assertThat(sequenceOf(loan)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM credit.decision_snapshot WHERE decision_request_id = ?", loan)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM credit.credit_assessment WHERE decision_request_id = ?", loan)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM credit.policy_evaluation WHERE decision_request_id = ?", loan)).isEqualTo(2);
        assertThat(scalar("SELECT d.snapshot_sha256 = s.content_sha256 FROM credit.credit_decision d"
                + " JOIN credit.decision_snapshot s ON s.id = d.snapshot_id WHERE d.decision_request_id = ?", loan))
                .as("the decision names the snapshot it was made from, by hash").isEqualTo("t");
        assertThat(reserved(party)).as("both approvals reserve: 2,000.00 and the loan's 10,000.00").isEqualTo(eur(1_200_000));
    }

    @Test
    @DisplayName("an activation mid-decision keeps the pinned policy and model - even for a successor snapshot")
    void anActivationMidDecisionKeepsThePinnedPolicyAndModel() throws Exception {
        UUID party = UUID.randomUUID();
        UUID loan = evaluated(party, CreditProduct.PERSONAL_LOAN, eur(1_000_000), WEEK);
        UUID line = evaluated(party, CreditProduct.CREDIT_LINE, eur(200_000), WEEK);
        String pinnedPolicy = scalar("SELECT pinned_policy_version_id::text FROM credit.decision_request WHERE id = ?", loan);
        String pinnedModel = scalar("SELECT pinned_model_version_id::text FROM credit.decision_request WHERE id = ?", loan);
        CreditWorld.inForce(CreditProduct.PERSONAL_LOAN, 950, false);
        ScorecardModelVersionId newModel = CreditWorld.scorecardInForce();
        assertThat(newModel.value().toString()).isNotEqualTo(pinnedModel);
        decide(deciding(), line);
        assertThat(decide(deciding(), loan)).isEqualTo(Decider.Decided.DECIDED);
        assertThat(sequenceOf(loan)).as("the reservation grew: a successor was evaluated").isEqualTo(2);
        assertThat(scalar("SELECT policy_version_id::text FROM credit.credit_decision WHERE decision_request_id = ?", loan))
                .isEqualTo(pinnedPolicy);
        assertThat(scalar("SELECT model_version_id::text FROM credit.credit_decision WHERE decision_request_id = ?", loan))
                .isEqualTo(pinnedModel);
        assertThat(count("SELECT count(*) FROM credit.policy_evaluation WHERE decision_request_id = ?"
                + " AND policy_version_id::text <> ?", loan, pinnedPolicy)).as("every evaluation under the pin").isZero();
    }

    // ------------------------------------------------------------------ crashes and the clock

    @Test
    @DisplayName("a crash between the evaluation and the decision: another instance's progress decides it, once")
    void aCrashBetweenEvaluationAndDecisionIsDecidedOnce() throws Exception {
        UUID id = evaluated(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(1_000_000), WEEK);
        DecisionProgress another = CreditWorld.progress(CreditWorld.CLOCK, deciding());
        assertThat(step(another, id)).isEqualTo(DecisionProgress.Step.DECIDED);
        assertThat(step(another, id)).isEqualTo(DecisionProgress.Step.NOTHING);
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("expiry and decision at the boundary, ten instances five seconds fast or slow: exactly one of DECIDED and"
            + " EXPIRED - complementary conditionals on the database's clock")
    void expiryAndDecisionAtTheBoundaryLeaveExactlyOne() throws Exception {
        UUID id = evaluated(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(1_000_000), Duration.ofSeconds(8));
        long remaining = Long.parseLong(scalar("SELECT (extract(epoch FROM expires_at - statement_timestamp()) * 1000)"
                + "::bigint::text FROM credit.decision_request WHERE id = ?", id));
        Thread.sleep(Math.max(0, remaining - 10));
        List<Callable<DecisionProgress.Step>> racers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Clock skewed = Clock.offset(Clock.system(ZoneOffset.UTC), Duration.ofSeconds(i % 2 == 0 ? 5 : -5));
            racers.add(() -> step(CreditWorld.progress(skewed, deciding(skewed)), id));
        }
        race(10, racers);
        awaitDatabase("SELECT status <> 'EVALUATED' OR expires_at <= statement_timestamp() - interval '3 seconds'"
                + " FROM credit.decision_request WHERE id = ?", id);
        if ("EVALUATED".equals(scalar("SELECT status FROM credit.decision_request WHERE id = ?", id))) {
            step(CreditWorld.progress(CreditWorld.CLOCK, deciding()), id);
        }
        assertThat(count("SELECT count(*) FROM credit.decision_request_event WHERE decision_request_id = ?"
                + " AND from_status = 'EVALUATED'", id)).as("exactly one edge out of EVALUATED").isEqualTo(1);
        String status = scalar("SELECT status FROM credit.decision_request WHERE id = ?", id);
        assertThat(status).isIn("DECIDED", "EXPIRED");
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", id))
                .isEqualTo("DECIDED".equals(status) ? 1 : 0);
    }

    @Test
    @DisplayName("expiry and decision raced at the boundary over at least 200 requests, instances five seconds fast and"
            + " slow: every request exactly one of DECIDED and EXPIRED, a decision exactly where DECIDED (P10-DOC-001)")
    void expiryAndDecisionAtTheBoundaryOverTwoHundredRequests() throws Exception {
        int born = 240;
        ExecutorService builders = Executors.newFixedThreadPool(8);
        List<UUID> ids = new ArrayList<>();
        try {
            List<Future<UUID>> pending = new ArrayList<>();
            for (int i = 0; i < born; i++) {
                pending.add(builders.submit(() -> evaluated(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(1_000_000),
                        Duration.ofSeconds(75))));
            }
            for (Future<UUID> request : pending) {
                ids.add(request.get(5, TimeUnit.MINUTES));
            }
        } finally {
            builders.shutdownNow();
        }
        java.util.concurrent.ScheduledExecutorService racers = Executors.newScheduledThreadPool(48);
        java.util.concurrent.atomic.AtomicInteger atTheBoundary = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger refused = new java.util.concurrent.atomic.AtomicInteger();
        try {
            List<java.util.concurrent.ScheduledFuture<?>> races = new ArrayList<>();
            int index = 0;
            for (UUID id : ids) {
                // Each request's two racers fire together at a different offset from ITS expiry - from 200 ms before
                // it to 100 ms after (a lead of -100..200 ms), so the boundary is met from both sides. The Docker
                // VM's clock drifts against the host's (X-TSK-017), so the offset is taken from the database a second
                // before the race, never from a reading a minute old - the first version, timed once at the start,
                // saw every request land on one side.
                long lead = 10L * (index++ % 31) - 100;
                long remaining = remainingMillis(id);
                Runnable racer = () -> {
                    Clock fast = Clock.offset(Clock.system(ZoneOffset.UTC), Duration.ofSeconds(5));
                    Clock slow = Clock.offset(Clock.system(ZoneOffset.UTC), Duration.ofSeconds(-5));
                    try {
                        long close = remainingMillis(id);
                        if (close > 0) {
                            atTheBoundary.incrementAndGet();
                        }
                        Thread.sleep(Math.max(0, close - lead));
                        race(2, List.<Callable<DecisionProgress.Step>>of(
                                () -> boundaryStep(fast, id, refused), () -> boundaryStep(slow, id, refused)));
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                };
                races.add(racers.schedule(racer, Math.max(0, remaining - 1_000), TimeUnit.MILLISECONDS));
            }
            for (java.util.concurrent.ScheduledFuture<?> race : races) {
                race.get(5, TimeUnit.MINUTES);
            }
        } finally {
            racers.shutdownNow();
        }
        assertThat(atTheBoundary.get()).as("raced at its own expiry, not after it").isGreaterThanOrEqualTo(200);
        int decided = 0;
        for (UUID id : ids) {
            awaitDatabase("SELECT status <> 'EVALUATED' OR expires_at <= statement_timestamp() - interval '3 seconds'"
                    + " FROM credit.decision_request WHERE id = ?", id);
            if ("EVALUATED".equals(scalar("SELECT status FROM credit.decision_request WHERE id = ?", id))) {
                step(CreditWorld.progress(CreditWorld.CLOCK, deciding()), id);
            }
            assertThat(count("SELECT count(*) FROM credit.decision_request_event WHERE decision_request_id = ?"
                    + " AND from_status = 'EVALUATED'", id)).as("exactly one edge out of EVALUATED: " + id).isEqualTo(1);
            String status = scalar("SELECT status FROM credit.decision_request WHERE id = ?", id);
            assertThat(status).as(id.toString()).isIn("DECIDED", "EXPIRED");
            assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", id))
                    .as("a decision exactly where DECIDED: " + id).isEqualTo("DECIDED".equals(status) ? 1 : 0);
            decided += "DECIDED".equals(status) ? 1 : 0;
        }
        System.out.printf("boundary races: %d requests, %d at the boundary, %d DECIDED, %d EXPIRED, %d refused steps%n",
                ids.size(), atTheBoundary.get(), decided, ids.size() - decided, refused.get());
        assertThat(decided).as("the boundary met from both sides - some decided before it").isPositive();
        assertThat(ids.size() - decided).as("and some found it passed").isPositive();
    }

    private static long remainingMillis(UUID id) {
        return Long.parseLong(scalar("SELECT (extract(epoch FROM expires_at - statement_timestamp()) * 1000)::bigint::text"
                + " FROM credit.decision_request WHERE id = ?", id));
    }

    /**
     * One racer's step at the boundary; a deciding transaction the trigger refuses on a later statement's clock rolls
     * back whole and leaves the request EVALUATED for the next step - the recorded design, counted. ONLY that refusal:
     * the decision request trigger raising ({@code P0001}) on the request's move - storage reports it as exactly
     * "moving a decision request (SQLState P0001)", no cause and no trigger text by design (`P10-TSK-004`). Anything
     * else - a deadlock, a serialization failure, a refusal elsewhere, a bug - fails the race instead of passing as a
     * boundary.
     */
    private static DecisionProgress.Step boundaryStep(Clock clock, UUID id,
            java.util.concurrent.atomic.AtomicInteger refused) {
        try {
            return step(CreditWorld.progress(clock, deciding(clock)), id);
        } catch (RuntimeException boundary) {
            if (!isTheExpiryRefusal(boundary)) {
                throw boundary;
            }
            refused.incrementAndGet();
            return DecisionProgress.Step.NOTHING;
        }
    }

    /** The decision request trigger's refusal of the request's move - a decision or an expiry on the wrong side. */
    private static boolean isTheExpiryRefusal(RuntimeException failure) {
        return failure instanceof com.finapp.credit.CreditStorageException
                && "moving a decision request (SQLState P0001)".equals(failure.getMessage());
    }

    @Test
    @DisplayName("a skewed decider neither refuses early nor decides late: five seconds fast it decides a request still"
            + " valid; five seconds slow it decides nothing past the expiry - the database's clock, not its own")
    void aSkewedDeciderNeitherRefusesEarlyNorDecidesLate() throws Exception {
        Clock fast = Clock.offset(Clock.system(ZoneOffset.UTC), Duration.ofSeconds(5));
        Clock slow = Clock.offset(Clock.system(ZoneOffset.UTC), Duration.ofSeconds(-5));
        UUID valid = evaluated(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(1_000_000), Duration.ofSeconds(30));
        awaitDatabase("SELECT expires_at - statement_timestamp() BETWEEN interval '1 second' AND interval '5 seconds'"
                + " FROM credit.decision_request WHERE id = ?", valid);
        assertThat(decide(deciding(fast), valid)).as("still valid on the database's clock").isEqualTo(Decider.Decided.DECIDED);
        UUID lapsed = evaluated(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(1_000_000), Duration.ofSeconds(4));
        awaitDatabase("SELECT expires_at <= statement_timestamp() - interval '3 seconds' FROM credit.decision_request"
                + " WHERE id = ?", lapsed);
        assertThat(decide(deciding(slow), lapsed)).as("expired on the database's clock").isEqualTo(Decider.Decided.NOTHING);
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", lapsed)).isZero();
    }

    // ------------------------------------------------------------------ immutability and explanation

    @Test
    @DisplayName("a decision row is never updated or deleted by any role - the application refused by privilege, the owner"
            + " by the trigger; its reasons likewise, and no late reason joins it")
    void aDecisionRowIsNeverUpdatedOrDeletedByAnyRole() throws Exception {
        UUID id = evaluated(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(1_000_000), WEEK);
        decide(deciding(), id);
        String decision = scalar("SELECT id::text FROM credit.credit_decision WHERE decision_request_id = ?", id);
        try (Connection app = DatabaseRoles.application()) {
            refused(app, "UPDATE credit.credit_decision SET outcome = 'DECLINED' WHERE id = '" + decision + "'", "42501");
            refused(app, "DELETE FROM credit.credit_decision WHERE id = '" + decision + "'", "42501");
            refused(app, "UPDATE credit.credit_decision_reason SET reason_code = 'CRD-INSOLVENCY' WHERE decision_id = '"
                    + decision + "'", "42501");
            refused(app, "INSERT INTO credit.credit_decision_reason (decision_id, ordinal, reason_code) VALUES ('"
                    + decision + "', 9, 'CRD-INSOLVENCY')", "P0001");
            // P10-DOC-001: the side tables, each rank alone - the reasons and the consumption fact.
            refused(app, "DELETE FROM credit.credit_decision_reason WHERE decision_id = '" + decision + "'", "42501");
        }
        // A consumption fact for this decision (Phase 11's to write; inserted here only to be refused changing).
        CreditWorld.execute("INSERT INTO credit.credit_decision_consumption (id, decision_id, consumed_at)"
                + " VALUES (gen_random_uuid(), ?::uuid, now())", decision);
        try (Connection app = DatabaseRoles.application()) {
            refused(app, "UPDATE credit.credit_decision_consumption SET consumed_at = now() WHERE decision_id = '"
                    + decision + "'", "42501");
            refused(app, "DELETE FROM credit.credit_decision_consumption WHERE decision_id = '" + decision + "'", "42501");
        }
        // A decline carries reason rows for the owner's refusals to bite on: 35,000.00 on the bureau beside 10,000.00
        // requested is past the loan's 40,000.00 limit.
        UUID declinedParty = UUID.randomUUID();
        BALANCES.put(declinedParty, 3_500_000L);
        UUID declinedRequest = evaluated(declinedParty, CreditProduct.PERSONAL_LOAN, eur(1_000_000), WEEK);
        decide(deciding(), declinedRequest);
        String declined = scalar("SELECT id::text FROM credit.credit_decision WHERE decision_request_id = ?",
                declinedRequest);
        assertThat(count("SELECT count(*) FROM credit.credit_decision_reason WHERE decision_id = ?::uuid", declined))
                .as("the decline's reasons exist to be refused").isPositive();
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE credit.credit_decision_reason SET reason_code = 'CRD-INSOLVENCY' WHERE decision_id = '"
                    + declined + "'", "P0001");
            refused(owner, "DELETE FROM credit.credit_decision_reason WHERE decision_id = '" + declined + "'", "P0001");
            refused(owner, "UPDATE credit.credit_decision_consumption SET consumed_at = now() WHERE decision_id = '"
                    + decision + "'", "P0001");
            refused(owner, "DELETE FROM credit.credit_decision_consumption WHERE decision_id = '" + decision + "'",
                    "P0001");
            refused(owner, "UPDATE credit.credit_decision SET approved_minor = 1 WHERE id = '" + decision + "'", "P0001");
            refused(owner, "UPDATE credit.credit_decision SET valid_until = now() + interval '1 year' WHERE id = '"
                    + decision + "'", "P0001");
            refused(owner, "DELETE FROM credit.credit_decision WHERE id = '" + decision + "'", "P0001");
            refused(owner, "TRUNCATE credit.credit_decision_consumption, credit.credit_decision_reason,"
                    + " credit.credit_decision", "P0001");
        }
        assertThat(scalar("SELECT outcome FROM credit.credit_decision WHERE id = ?", UUID.fromString(decision)))
                .isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("every edge a raw writer tries out of EVALUATED backwards, and out of DECIDED and EXPIRED at all, is"
            + " refused by the trigger (P10-DOC-001)")
    void everyEdgeOutOfEvaluatedDecidedAndExpiredIsRefused() throws Exception {
        UUID evaluated = evaluated(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(1_000_000), WEEK);
        UUID decided = evaluated(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(1_000_000), WEEK);
        decide(deciding(), decided);
        assertThat(scalar("SELECT status FROM credit.decision_request WHERE id = ?", decided)).isEqualTo("DECIDED");
        UUID expiring = evaluated(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(1_000_000), Duration.ofSeconds(3));
        awaitDatabase("SELECT expires_at <= statement_timestamp() - interval '3 seconds' FROM credit.decision_request"
                + " WHERE id = ?", expiring);
        assertThat(step(CreditWorld.progress(CreditWorld.CLOCK, deciding()), expiring))
                .isEqualTo(DecisionProgress.Step.EXPIRED);
        try (Connection app = DatabaseRoles.application()) {
            for (String target : List.of("SUBMITTED", "COLLECTING", "READY", "CANCELLED")) {
                refused(app, "UPDATE credit.decision_request SET status = '" + target + "' WHERE id = '" + evaluated + "'",
                        "P0001");
            }
            for (UUID terminal : List.of(decided, expiring)) {
                for (String target : List.of("SUBMITTED", "COLLECTING", "READY", "EVALUATED", "IN_REVIEW", "DECIDED",
                        "CANCELLED", "EXPIRED")) {
                    if (target.equals(scalar("SELECT status FROM credit.decision_request WHERE id = ?", terminal))) {
                        continue;
                    }
                    refused(app, "UPDATE credit.decision_request SET status = '" + target + "' WHERE id = '" + terminal
                            + "'", "P0001");
                }
                refused(app, "UPDATE credit.decision_request SET status = 'ABANDONED', closure_reason = 'STANDING_LOST'"
                        + " WHERE id = '" + terminal + "'", "P0001");
            }
        }
        assertThat(scalar("SELECT status FROM credit.decision_request WHERE id = ?", evaluated)).isEqualTo("EVALUATED");
        assertThat(scalar("SELECT status FROM credit.decision_request WHERE id = ?", expiring)).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("a decline - or an approval below its request - without reasons cannot commit: the deferred trigger"
            + " refuses the transaction, nothing kept")
    void aDeclineWithoutReasonsCannotCommit() throws Exception {
        UUID id = evaluated(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(1_000_000), WEEK);
        for (String outcome : List.of("'DECLINED', NULL, NULL", "'APPROVED', 500000, 36")) {
            try (Connection app = DatabaseRoles.application()) {
                app.setAutoCommit(false);
                try (Statement statement = app.createStatement()) {
                    statement.executeUpdate("INSERT INTO credit.credit_decision (id, decision_request_id, party_id, profile_id,"
                            + " product, snapshot_id, snapshot_sha256, outcome, approved_minor, term_months, currency,"
                            + " requested_minor, decision_validity, decided_at, valid_until, decided_by, decided_by_type,"
                            + " policy_version_id, model_version_id, engine_version) SELECT gen_random_uuid(), r.id, r.party_id,"
                            + " r.profile_id, r.product, s.id, s.content_sha256, " + outcome + ", r.currency,"
                            + " r.requested_minor, interval '30 days', now(), now(), 'system', 'SYSTEM',"
                            + " r.pinned_policy_version_id, r.pinned_model_version_id, r.pinned_engine_version"
                            + " FROM credit.decision_request r JOIN credit.decision_snapshot s ON s.decision_request_id = r.id"
                            + " WHERE r.id = '" + id + "'");
                }
                assertThatThrownBy(app::commit).as(outcome).isInstanceOf(SQLException.class)
                        .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("P0001"));
            }
        }
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ?", id)).isZero();
    }

    @Test
    @DisplayName("a consumed approval reserves nothing: the next decision for the party sees none of it, and no successor"
            + " is frozen for it")
    void lapsedAndConsumedDecisionsReserveNothing() throws Exception {
        UUID party = UUID.randomUUID();
        UUID loan = evaluated(party, CreditProduct.PERSONAL_LOAN, eur(1_000_000), WEEK);
        UUID line = evaluated(party, CreditProduct.CREDIT_LINE, eur(200_000), WEEK);
        decide(deciding(), line);
        String decision = scalar("SELECT id::text FROM credit.credit_decision WHERE decision_request_id = ?", line);
        CreditWorld.execute("INSERT INTO credit.credit_decision_consumption (id, decision_id, consumed_at)"
                + " VALUES (gen_random_uuid(), ?::uuid, now())", decision);
        assertThat(reserved(party)).isEqualTo(eur(0));
        assertThat(decide(deciding(), loan)).isEqualTo(Decider.Decided.DECIDED);
        assertThat(sequenceOf(loan)).as("nothing reserved, nothing moved: the first snapshot decides").isEqualTo(1);
    }

    // ------------------------------------------------------------------ the event

    @Test
    @DisplayName("a decision citing every adverse code records and publishes: credit.CreditDecisionRecorded version 2"
            + " carries each code in a field of its own, in the decision's order - no reason list outgrows a payload value")
    void aDecisionCitingEveryAdverseCodeRecordsAndPublishes() throws Exception {
        UUID id;
        try {
            lineCitingEveryAdverseCode();
            id = evaluated(UUID.randomUUID(), CreditProduct.CREDIT_LINE, eur(200_000), WEEK);
        } finally {
            // The request pinned its version at submission; every other case decides under the suite's own.
            CreditWorld.inForce(CreditProduct.CREDIT_LINE, 900, false);
        }
        assertThat(decide(deciding(), id)).as("the long reason list never rolls the deciding transaction back")
                .isEqualTo(Decider.Decided.DECIDED);
        assertThat(scalar("SELECT status FROM credit.decision_request WHERE id = ?", id)).isEqualTo("DECIDED");
        String decision = scalar("SELECT id::text FROM credit.credit_decision WHERE decision_request_id = ?", id);
        assertThat(scalar("SELECT outcome FROM credit.credit_decision WHERE id = ?::uuid", decision)).isEqualTo("DECLINED");
        List<String> codes = List.of(scalar("SELECT string_agg(reason_code, ',' ORDER BY ordinal)"
                + " FROM credit.credit_decision_reason WHERE decision_id = ?::uuid", decision).split(","));
        assertThat(codes).containsExactlyInAnyOrderElementsOf(Arrays.stream(ReasonCode.values())
                .filter(ReasonCode::adverse).map(ReasonCode::code).toList());
        assertThat(String.join("_", codes)).as("version 1's one joined value could not have held them").hasSizeGreaterThan(200);
        assertThat(scalar("SELECT event_version::text FROM platform.outbox_event"
                + " WHERE event_type = 'credit.CreditDecisionRecorded' AND aggregate_id = ?::uuid", decision)).isEqualTo("2");
        String payload = scalar("SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event"
                + " WHERE event_type = 'credit.CreditDecisionRecorded' AND aggregate_id = ?::uuid", decision);
        assertThat(CreditTestClient.field(payload, "reasonCodeCount")).isEqualTo(Integer.toString(codes.size()));
        for (int i = 0; i < codes.size(); i++) {
            assertThat(CreditTestClient.field(payload, "reasonCode" + (i + 1))).as("code %d", i + 1).isEqualTo(codes.get(i));
        }
        assertThat(payload).doesNotContain("\"reasonCode" + (codes.size() + 1) + "\"").doesNotContain("\"reasonCodes\"")
                // The bureau score (740) as a VALUE - not as three hex digits a UUIDv7 or a digest may carry by chance
                // (this read doesNotContain("740") until the exit review, P10-DOC-001, saw a decision id 01a121cc-7402-...).
                .as("never an attribute").doesNotContainPattern(":\\s*\"?740\"?[,}]").doesNotContain("BUREAU_");
    }

    // ------------------------------------------------------------------ the re-reads and the lock order

    @Test
    @DisplayName("standing lost, or a consent withdrawn, at the decision: the request ABANDONED with its reason, nothing"
            + " decided")
    void standingLostOrConsentWithdrawnAtTheDecisionAbandons() throws Exception {
        UUID suspended = UUID.randomUUID();
        UUID first = evaluated(suspended, CreditProduct.PERSONAL_LOAN, eur(1_000_000), WEEK);
        SUSPENDED.add(suspended);
        assertThat(decide(deciding(), first)).isEqualTo(Decider.Decided.ABANDONED);
        UUID withdrawn = UUID.randomUUID();
        UUID second = evaluated(withdrawn, CreditProduct.PERSONAL_LOAN, eur(1_000_000), WEEK);
        WITHDRAWN.computeIfAbsent(withdrawn, ignored -> ConcurrentHashMap.newKeySet()).add(CreditSourceKind.BUREAU);
        assertThat(decide(deciding(), second)).isEqualTo(Decider.Decided.ABANDONED);
        assertThat(scalar("SELECT status || ':' || closure_reason FROM credit.decision_request WHERE id = ?", first))
                .isEqualTo("ABANDONED:STANDING_LOST");
        assertThat(scalar("SELECT status || ':' || closure_reason FROM credit.decision_request WHERE id = ?", second))
                .isEqualTo("ABANDONED:CONSENT_WITHDRAWN");
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id IN (?, ?)", first, second))
                .isZero();
    }

    @Test
    @DisplayName("the deciding transaction takes the profile first: held elsewhere, the decider waits on it holding nothing -"
            + " the request still free to lock - then decides")
    void theDecidingTransactionTakesTheProfileFirst() throws Exception {
        UUID party = UUID.randomUUID();
        UUID id = evaluated(party, CreditProduct.PERSONAL_LOAN, eur(1_000_000), WEEK);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            int holderPid;
            try (Statement statement = holder.createStatement()) {
                statement.executeQuery("SELECT id FROM credit.credit_profile WHERE party_id = '" + party + "' FOR UPDATE")
                        .close();
                try (ResultSet pid = statement.executeQuery("SELECT pg_backend_pid()")) {
                    pid.next();
                    holderPid = pid.getInt(1);
                }
            }
            Future<Decider.Decided> decided = pool.submit(() -> decide(deciding(), id));
            awaitDatabase("SELECT count(*) > 0 FROM pg_stat_activity WHERE ? = ANY (pg_blocking_pids(pid))", holderPid);
            try (Connection other = DatabaseRoles.application()) {
                other.setAutoCommit(false);
                try (PreparedStatement lock = other.prepareStatement(
                        "SELECT status FROM credit.decision_request WHERE id = ? FOR UPDATE NOWAIT")) {
                    lock.setObject(1, id);
                    try (ResultSet row = lock.executeQuery()) {
                        assertThat(row.next()).as("the waiting decider holds no lock on the request").isTrue();
                    }
                }
                other.rollback();
            }
            holder.rollback();
            assertThat(decided.get(1, TimeUnit.MINUTES)).isEqualTo(Decider.Decided.DECIDED);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ the pin's share locks (element (5))

    /*
     * Each case holds a version row the way an activation's UPDATE does - FOR NO KEY UPDATE, the row lock an UPDATE of
     * non-key columns takes - and runs the production step against it. NOT FOR UPDATE: the pin's own write (the request's
     * pinned_policy_version_id, the decision's policy_version_id) checks its foreign key with FOR KEY SHARE, which FOR
     * UPDATE blocks and FOR NO KEY UPDATE does not - so under FOR UPDATE the step would wait at its foreign key even with
     * the FOR SHARE gone, and the case would prove nothing about the share. Each case asserts the step waits AT the share
     * statement; without the FOR SHARE it finishes without waiting at all.
     */

    @Test
    @DisplayName("SUBMITTED -> COLLECTING shares the ACTIVE policy row: an activation holding it, the pin waits at the share"
            + " - and then pins that version")
    void theCollectingStepSharesTheActivePolicy() throws Exception {
        UUID id = CreditWorld.request(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(1_000_000), WEEK);
        UUID active = UUID.fromString(scalar("SELECT id::text FROM credit.credit_policy_version WHERE product ="
                + " 'PERSONAL_LOAN' AND status = 'ACTIVE'"));
        DecisionProgress.Step step = whileHeld("credit.credit_policy_version", active,
                () -> step(CreditWorld.progressToEvaluation(), id),
                "FROM credit.credit_policy_version WHERE product = ");
        assertThat(step).isEqualTo(DecisionProgress.Step.COLLECTING);
        assertThat(scalar("SELECT pinned_policy_version_id::text FROM credit.decision_request WHERE id = ?", id))
                .isEqualTo(active.toString());
    }

    @Test
    @DisplayName("SUBMITTED -> COLLECTING shares the ACTIVE scorecard row: an activation holding it, the pin waits at the"
            + " share - and then pins that version")
    void theCollectingStepSharesTheActiveScorecard() throws Exception {
        UUID id = CreditWorld.request(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(1_000_000), WEEK);
        UUID active = UUID.fromString(scalar("SELECT id::text FROM credit.scorecard_model_version WHERE family ="
                + " 'RETAIL_SCORECARD' AND status = 'ACTIVE'"));
        DecisionProgress.Step step = whileHeld("credit.scorecard_model_version", active,
                () -> step(CreditWorld.progressToEvaluation(), id),
                "FROM credit.scorecard_model_version WHERE family = ");
        assertThat(step).isEqualTo(DecisionProgress.Step.COLLECTING);
        assertThat(scalar("SELECT pinned_model_version_id::text FROM credit.decision_request WHERE id = ?", id))
                .isEqualTo(active.toString());
    }

    @Test
    @DisplayName("the deciding transaction shares the PINNED policy row: an activation or retirement holding it, the decider"
            + " waits at the share - and then decides under it")
    void theDecidingTransactionSharesThePinnedPolicy() throws Exception {
        UUID id = evaluated(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(1_000_000), WEEK);
        UUID pinned = UUID.fromString(scalar("SELECT pinned_policy_version_id::text FROM credit.decision_request"
                + " WHERE id = ?", id));
        Decider.Decided decided = whileHeld("credit.credit_policy_version", pinned, () -> decide(deciding(), id),
                "FROM credit.credit_policy_version WHERE id = ");
        assertThat(decided).isEqualTo(Decider.Decided.DECIDED);
        assertThat(scalar("SELECT policy_version_id::text FROM credit.credit_decision WHERE decision_request_id = ?", id))
                .isEqualTo(pinned.toString());
    }

    @Test
    @DisplayName("the deciding transaction shares the PINNED scorecard row: an activation or retirement holding it, the"
            + " decider waits at the share - and then decides under it")
    void theDecidingTransactionSharesThePinnedScorecard() throws Exception {
        UUID id = evaluated(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(1_000_000), WEEK);
        UUID pinned = UUID.fromString(scalar("SELECT pinned_model_version_id::text FROM credit.decision_request"
                + " WHERE id = ?", id));
        Decider.Decided decided = whileHeld("credit.scorecard_model_version", pinned, () -> decide(deciding(), id),
                "FROM credit.scorecard_model_version WHERE id = ");
        assertThat(decided).isEqualTo(Decider.Decided.DECIDED);
        assertThat(scalar("SELECT model_version_id::text FROM credit.credit_decision WHERE decision_request_id = ?", id))
                .isEqualTo(pinned.toString());
    }

    /**
     * {@code work} run while another transaction holds {@code table}'s row {@code id} FOR NO KEY UPDATE: it must wait on
     * that holder at a statement containing {@code statement} - finishing without waiting fails - and, released, its
     * answer.
     */
    private static <T> T whileHeld(String table, UUID id, Callable<T> work, String statement) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.migrator()) {
            holder.setAutoCommit(false);
            int holderPid;
            try (PreparedStatement hold = holder.prepareStatement(
                    "SELECT pg_backend_pid() FROM " + table + " WHERE id = ? FOR NO KEY UPDATE")) {
                hold.setObject(1, id);
                try (ResultSet row = hold.executeQuery()) {
                    assertThat(row.next()).as("the held row exists").isTrue();
                    holderPid = row.getInt(1);
                }
            }
            Future<T> running = pool.submit(work);
            List<String> waiting = waitingOn(holderPid, running);
            assertThat(waiting).as("the step waits on the held version row at its share statement").singleElement()
                    .satisfies(query -> assertThat(query).contains(statement));
            holder.rollback();
            return running.get(1, TimeUnit.MINUTES);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The statements of the backends {@code holderPid} blocks, read on the application role (whose sessions' text it may
     * see) once there is one; {@code running} finishing first is the failure - it took no lock the holder conflicts with.
     */
    private static List<String> waitingOn(int holderPid, Future<?> running) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try (Connection observer = DatabaseRoles.application(); PreparedStatement read = observer.prepareStatement(
                "SELECT query FROM pg_stat_activity WHERE ? = ANY (pg_blocking_pids(pid))")) {
            read.setInt(1, holderPid);
            while (true) {
                List<String> queries = new ArrayList<>();
                try (ResultSet row = read.executeQuery()) {
                    while (row.next()) {
                        queries.add(row.getString(1));
                    }
                }
                if (!queries.isEmpty()) {
                    return queries;
                }
                if (running.isDone()) {
                    throw new AssertionError("the step finished without ever waiting on the held version row - it took"
                            + " no share lock an activation conflicts with; it answered " + running.get());
                }
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("the step neither waited on the held version row nor finished");
                }
                Thread.sleep(20);
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** CREDIT_LINE v1 without its score rules, then one always-triggering decline per adverse code - in force. */
    private static void lineCitingEveryAdverseCode() {
        CreditWorld.TRANSACTIONS.inTransaction(uow -> {
            CreditPolicy v1 = CreditWorld.POLICIES.policy(uow, CreditWorld.LINE_SEED).orElseThrow().policy();
            List<CreditPolicy.PolicyRule> rules = new ArrayList<>(v1.rules());
            rules.removeIf(rule -> rule.subject() instanceof CreditPolicy.Subject.Figure figure
                    && figure.figure() == PolicyFigure.SCORE);
            for (ReasonCode reason : ReasonCode.values()) {
                if (reason.adverse()) {
                    rules.add(new CreditPolicy.PolicyRule("EVERY_" + reason.name(),
                            new CreditPolicy.Subject.Attribute(CreditAttributeCode.BUREAU_ACTIVE_ACCOUNTS), PolicyOperator.GE,
                            new CreditPolicy.Operand.IntegerOperand(0), PolicyEffect.DECLINE, Optional.empty(), reason));
                }
            }
            CreditPolicy policy = new CreditPolicy(v1.product(), 900, v1.minimumDisposable(), v1.minimumPaymentRatioBps(),
                    v1.maximumExposure(), v1.maximumDataAge(), UnavailableFallback.REFER, v1.autoApprovalCeiling(), rules);
            CreditPolicyAdministration.Proposed proposed = CreditWorld.POLICY_ADMINISTRATION.propose(uow, policy,
                    "every adverse code", new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE),
                    CorrelationId.generate(CreditWorld.IDS));
            CreditWorld.POLICY_ADMINISTRATION.approve(uow, proposed.id(),
                    new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE), "activated",
                    CorrelationId.generate(CreditWorld.IDS));
            return null;
        });
    }

    private static int sequenceOf(UUID request) {
        return Integer.parseInt(scalar("SELECT s.sequence::text FROM credit.credit_decision d"
                + " JOIN credit.decision_snapshot s ON s.id = d.snapshot_id WHERE d.decision_request_id = ?", request));
    }

    private static void refused(Connection connection, String sql, String sqlState) {
        assertThatThrownBy(() -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .as(sql)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).as(sql).isEqualTo(sqlState));
    }

    private static <T> List<T> race(int racers, Callable<T> work) throws Exception {
        List<Callable<T>> all = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            all.add(work);
        }
        return race(racers, all);
    }

    private static <T> List<T> race(int racers, List<Callable<T>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> pending = new ArrayList<>();
            for (Callable<T> racer : work) {
                pending.add(pool.submit(() -> {
                    start.await();
                    return racer.call();
                }));
            }
            start.countDown();
            List<T> outcomes = new ArrayList<>();
            for (Future<T> outcome : pending) {
                outcomes.add(outcome.get(3, TimeUnit.MINUTES));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static com.finapp.sharedkernel.money.Money reserved(UUID party) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return new JdbcReservedExposure().reservedFor(app, party, CreditWorld.EUR);
        }
    }
}
