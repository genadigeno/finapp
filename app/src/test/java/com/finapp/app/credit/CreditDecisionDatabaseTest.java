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

import com.finapp.credit.CreditProduct;
import com.finapp.credit.CreditSourceKind;
import com.finapp.credit.Decider;
import com.finapp.credit.DecisionProgress;
import com.finapp.credit.JdbcReservedExposure;
import com.finapp.credit.ScorecardModelVersionId;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
    @DisplayName("two products at the exposure limit, decided at once, a hundred rounds: serialised on the profile - the"
            + " second always sees the first's reservation, never both approved beyond the line's limit")
    void twoProductsAtTheExposureLimitSerialise() throws Exception {
        for (int round = 0; round < 100; round++) {
            UUID party = UUID.randomUUID();
            // 10,000.00 on the bureau: the loan (10,000.00 of 40,000.00) fits with the line's 2,500.00 approval; the
            // line (5,000.00 of 20,000.00) fits alone, not beside the loan's 10,000.00.
            BALANCES.put(party, 1_000_000L);
            UUID loan = evaluated(party, CreditProduct.PERSONAL_LOAN, eur(1_000_000), WEEK);
            UUID line = evaluated(party, CreditProduct.CREDIT_LINE, eur(500_000), WEEK);
            race(2, List.<Callable<Decider.Decided>>of(() -> decide(deciding(), loan), () -> decide(deciding(), line)));
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
        }
        try (Connection owner = DatabaseRoles.migrator()) {
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

    // ------------------------------------------------------------------ plumbing

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
