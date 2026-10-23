package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.telemetry.Spans;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-eval against a real database (`P13-TSK-004`; {@code risk V007}; {@code PHASE_13_PLAN.md} sections 7.4, 7.6,
 * scenarios 3, 9 and 14; {@code INV-RSK-01}, {@code INV-RSK-03}, {@code INV-RSK-04}, {@code INV-CON-03}): ten retries of
 * one command under the caller's lock seal one assessment and count once; a read timing out behind its savepoint
 * seals the fail-safe and the caller's transaction commits; ten evaluations behind an activation each pin exactly one
 * version; ten movements of one subject count ten, each sealing its predecessor's figure; a refused movement counts
 * nothing; an unserialised duplicate loses at the seal; the assessment is sealed for every role; the rows round-trip and
 * replay IDENTICAL; the counted assessments are the windows' evidence. <strong>Written and owed</strong> (owner decision
 * C5: the database tiers are skipped per task; the owner-authorised database pass before the exit review runs it).
 *
 * <p>Policy versions, windows and assessments are shared rows in the suite's database and never deleted, so every case
 * keys its movements on fresh subjects and command refs, activates the version it needs through the domain, and never
 * assumes what an earlier case left.
 */
@Tag("database")
@DisplayName("T-eval against the database: one assessment per command, the fail-safe behind a savepoint, the pin behind"
        + " an activation (P13-TSK-004)")
class RiskEvaluationDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final JdbcRiskPolicyStore POLICY_STORE = new JdbcRiskPolicyStore();
    private static final RiskPolicyAdministration ADMINISTRATION = new RiskPolicyAdministration(POLICY_STORE,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);
    private static final JdbcVelocityWindowStore WINDOW_STORE = new JdbcVelocityWindowStore();
    private static final JdbcRiskAssessmentStore ASSESSMENT_STORE = new JdbcRiskAssessmentStore();
    private static final RiskEvaluation EVALUATION = evaluation(new RiskPolicies(POLICY_STORE));

    @Test
    @DisplayName("ten retries of one command, each behind the caller's lock on its own connection: one assessment, the"
            + " same id answered ten times (nine replayed), the windows counted once, one RiskDecisionMade")
    void tenRetriesOneAssessment() throws Exception {
        activate(RiskOperation.TRANSFER);
        RiskEvaluationRequest request = transfer(eur(100_00), IDS.next(), IDS.next());
        List<RiskEvaluation.Assessed> answers = race(10, () -> inOneTransaction(uow -> {
            callersLock(uow, request);
            return EVALUATION.evaluate(uow, request);
        }));
        assertThat(answers).extracting(RiskEvaluation.Assessed::assessmentId).containsOnly(answers.get(0).assessmentId());
        assertThat(answers).filteredOn(RiskEvaluation.Assessed::replayed).hasSize(9);
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE operation = 'TRANSFER' AND command_ref = '"
                + request.commandRef().value() + "'")).isEqualTo(1);
        assertThat(windowCount("PARTY", request.party(), "D30")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'risk.RiskDecisionMade'"
                + " AND aggregate_id = '" + answers.get(0).assessmentId().value() + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("scenario 9: a read timing out behind its savepoint (a subject's lock held elsewhere) seals the band's"
            + " fail-safe - RISK_UNAVAILABLE, TIMEOUT, the version named - and the caller's transaction stays usable and"
            + " commits; the count attempt times out too and says UNCOUNTED")
    void aTimeoutSealsTheFailSafe() throws Exception {
        RiskPolicyVersionId pinned = activate(RiskOperation.TRANSFER);
        UUID source = IDS.next();
        RiskEvaluationRequest request = transfer(eur(100_00), IDS.next(), source);
        try (Connection blocker = DatabaseRoles.application()) {
            blocker.setAutoCommit(false);
            try (Statement hold = blocker.createStatement()) {
                hold.execute("SELECT pg_advisory_xact_lock(131, "
                        + VelocityWindows.lockKey(VelocityDimension.SOURCE_ACCOUNT, source.toString()) + ")");
            }
            RiskEvaluation.Assessed assessed = inOneTransaction(uow -> {
                RiskEvaluation.Assessed answer = EVALUATION.evaluate(uow, request);
                try (Statement after = uow.createStatement(); ResultSet one = after.executeQuery("SELECT 1")) {
                    assertThat(one.next()).as("the caller's transaction is not aborted").isTrue();
                } catch (SQLException aborted) {
                    throw new IllegalStateException(aborted);
                }
                return answer;
            });
            blocker.rollback();
            assertThat(assessed.decision()).isEqualTo(RiskDecision.ALLOW);
            assertThat(assessed.failSafe()).contains(ReasonCode.RISK_UNAVAILABLE);
        }
        assertThat(column("SELECT unavailable_cause || ',' || fail_safe_reason || ',' || velocity_outcome || ','"
                + " || policy_version_id FROM risk.risk_assessment WHERE command_ref = '" + request.commandRef().value()
                + "'")).containsExactly("TIMEOUT,RISK_UNAVAILABLE,UNCOUNTED," + pinned.value());
        assertThat(count("SELECT count(*) FROM risk.risk_reason r JOIN risk.risk_assessment a ON a.id = r.assessment_id"
                + " WHERE a.command_ref = '" + request.commandRef().value() + "' AND r.code = 'RISK_UNAVAILABLE'"))
                .isEqualTo(1);
        assertThat(windowCount("SOURCE_ACCOUNT", source, "D30")).isZero();
    }

    @Test
    @DisplayName("scenario 3, end to end: an activation held open while ten evaluations run - every assessment pins"
            + " exactly one version (the predecessor or the successor), none unpinned, none a fail-safe; the pin waits on"
            + " the activation rather than timing out")
    void anEvaluationBehindAnActivationPinsTheSuccessor() throws Exception {
        RiskPolicyVersionId predecessor = activate(RiskOperation.WITHDRAWAL);
        RiskPolicyAdministration.Proposed successor = inOneTransaction(uow -> ADMINISTRATION.propose(uow,
                RiskPolicyFixtures.v1(RiskOperation.WITHDRAWAL), "the successor", employee(), correlation()));
        CountDownLatch retired = new CountDownLatch(1);
        ExecutorService activator = Executors.newSingleThreadExecutor();
        List<RiskEvaluation.Assessed> assessed;
        try {
            Future<?> activation = activator.submit(() -> {
                try (Connection uow = DatabaseRoles.application()) {
                    uow.setAutoCommit(false);
                    ADMINISTRATION.activate(uow, successor.id(), employee(), "in flight", correlation());
                    retired.countDown();
                    Thread.sleep(1_500);
                    uow.commit();
                }
                return null;
            });
            assertThat(retired.await(30, TimeUnit.SECONDS)).isTrue();
            assessed = race(10, () -> inOneTransaction(uow -> EVALUATION.evaluate(uow,
                    RiskEvaluationRequest.builder(RiskOperation.WITHDRAWAL, CommandRef.of(IDS.next()), eur(10_00),
                            IDS.next(), correlation(), CausationId.of("cause")).sourceAccount(IDS.next()).build())));
            activation.get(1, TimeUnit.MINUTES);
        } finally {
            activator.shutdownNow();
        }
        List<String> pins = new ArrayList<>();
        for (RiskEvaluation.Assessed one : assessed) {
            assertThat(one.failSafe()).as("no pin answered neither, none timed out").isEmpty();
            pins.add(column("SELECT policy_version_id::text FROM risk.risk_assessment WHERE id = '"
                    + one.assessmentId().value() + "'").get(0));
        }
        assertThat(pins).allMatch(pin -> pin.equals(predecessor.value().toString())
                || pin.equals(successor.id().value().toString()));
        assertThat(pins).as("an evaluation that waited on the retirement pinned the successor")
                .contains(successor.id().value().toString());
    }

    @Test
    @DisplayName("scenario 14 through T-eval: ten concurrent movements of one party from one account count ten, and each"
            + " sealed the figure its predecessor left - 0 to 9, no two alike")
    void tenMovementsCountTen() throws Exception {
        activate(RiskOperation.TRANSFER);
        UUID party = IDS.next();
        UUID source = IDS.next();
        List<RiskEvaluation.Assessed> assessed = race(10, () -> inOneTransaction(
                uow -> EVALUATION.evaluate(uow, transfer(eur(1_00), party, source))));
        assertThat(assessed).allMatch(one -> one.decision() == RiskDecision.ALLOW && !one.replayed());
        assertThat(assessed).allMatch(one -> one.velocity() == VelocityOutcome.COUNTED);
        assertThat(windowCount("SOURCE_ACCOUNT", source, "D30")).isEqualTo(10);
        // A judgement that met the queue past its 250 ms budget is a recorded fail-safe (ALLOW below EUR 1,000, counted
        // by its own attempt) and sealed no figure; every judged one sealed a figure no other saw. Ten movements of one
        // subject queue on R2a from the cut to the commit - the budget's hot-subject case the storm measures.
        List<Long> sealed = new ArrayList<>();
        for (RiskEvaluation.Assessed one : assessed) {
            if (one.failSafe().isPresent()) {
                continue;
            }
            RiskAssessment row = inOneTransaction(uow -> ASSESSMENT_STORE.read(uow, one.assessmentId()).orElseThrow());
            sealed.add(CanonicalEvaluation.parseInputs(row.inputsCanonical()).velocity()
                    .get(new VelocityKey(VelocityDimension.SOURCE_ACCOUNT, WindowKind.D1)).count());
        }
        assertThat(sealed).doesNotHaveDuplicates().allMatch(figure -> figure >= 0 && figure <= 9);
        assertThat(sealed).as("the queue is short: most judged inside the budget").hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("a refused movement counts nothing: a DENY and an unsatisfied CHALLENGE leave no window row, their"
            + " assessments NOT_PERMITTED; a permitted one whose caller rolls back leaves neither row nor assessment")
    void aRefusedMovementCountsNothing() throws Exception {
        activate(RiskOperation.TRANSFER);
        UUID party = IDS.next();
        RiskEvaluation.Assessed denied = inOneTransaction(
                uow -> EVALUATION.evaluate(uow, transfer(eur(10_000_00), party, IDS.next())));
        RiskEvaluation.Assessed challenged = inOneTransaction(
                uow -> EVALUATION.evaluate(uow, transfer(eur(600_00), party, IDS.next())));
        assertThat(denied.decision()).isEqualTo(RiskDecision.DENY);
        assertThat(challenged.decision()).isEqualTo(RiskDecision.CHALLENGE);
        RiskEvaluationRequest rolledBack = transfer(eur(1_00), party, IDS.next());
        try (Connection uow = DatabaseRoles.application()) {
            uow.setAutoCommit(false);
            EVALUATION.evaluate(uow, rolledBack);
            uow.rollback();
        }
        assertThat(count("SELECT count(*) FROM risk.velocity_window WHERE subject_ref = '" + party + "'")).isZero();
        assertThat(column("SELECT velocity_outcome FROM risk.risk_assessment WHERE party_id = '" + party + "'"))
                .containsOnly("NOT_PERMITTED").hasSize(2);
    }

    @Test
    @DisplayName("no ACTIVE policy: ALLOW under NO_POLICY_ACTIVE sealed with no version named - the database's CHECKs admit"
            + " it - and the windows counted")
    void noActivePolicyRecordsAllow() throws Exception {
        RiskEvaluation unpinned = evaluation(new RiskPolicies(noActive()));
        RiskEvaluationRequest request = transfer(eur(5_000_00), IDS.next(), IDS.next());
        RiskEvaluation.Assessed assessed = inOneTransaction(uow -> unpinned.evaluate(uow, request));
        assertThat(assessed.failSafe()).contains(ReasonCode.NO_POLICY_ACTIVE);
        assertThat(column("SELECT unavailable_cause || ',' || velocity_outcome || ',' || (policy_version_id IS NULL)"
                + " FROM risk.risk_assessment WHERE id = '" + assessed.assessmentId().value() + "'"))
                .containsExactly("NO_POLICY,COUNTED,true");
        assertThat(windowCount("PARTY", request.party(), "D30")).isEqualTo(1);
    }

    @Test
    @DisplayName("an unserialised duplicate - two transactions of one command with no caller's lock - loses at the seal:"
            + " RiskEvaluation.RaceLost rolls it back with its count; one assessment, one count")
    void anUnserialisedDuplicateLosesAtTheSeal() throws Exception {
        activate(RiskOperation.TRANSFER);
        RiskEvaluationRequest request = transfer(eur(1_00), IDS.next(), IDS.next());
        List<Object> outcomes = race(2, () -> {
            try {
                return inOneTransaction(uow -> EVALUATION.evaluate(uow, request));
            } catch (RiskEvaluation.RaceLost lost) {
                return lost;
            }
        });
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE command_ref = '"
                + request.commandRef().value() + "'")).isEqualTo(1);
        assertThat(windowCount("PARTY", request.party(), "D30")).isEqualTo(1);
        assertThat(outcomes).hasSize(2);
    }

    @Test
    @DisplayName("sealed for every role: the application is refused UPDATE and DELETE by privilege, the owner by trigger,"
            + " TRUNCATE by trigger; an inputs row whose digest is not its text's is refused by CHECK")
    void theAssessmentIsInsertOnly() throws Exception {
        activate(RiskOperation.TRANSFER);
        RiskEvaluation.Assessed assessed = inOneTransaction(
                uow -> EVALUATION.evaluate(uow, transfer(eur(1_00), IDS.next(), IDS.next())));
        String id = assessed.assessmentId().value().toString();
        for (String table : List.of("risk_assessment", "risk_assessment_input", "risk_rule_hit", "risk_reason")) {
            String key = table.equals("risk_assessment") ? "id" : "assessment_id";
            try (Connection application = DatabaseRoles.application()) {
                refused(application, "UPDATE risk." + table + " SET " + key + " = " + key + " WHERE " + key + " = '"
                        + id + "'", "42501");
                refused(application, "DELETE FROM risk." + table + " WHERE " + key + " = '" + id + "'", "42501");
            }
            try (Connection owner = DatabaseRoles.migrator()) {
                refused(owner, "UPDATE risk." + table + " SET " + key + " = " + key + " WHERE " + key + " = '" + id
                        + "'", "P0001");
                refused(owner, "DELETE FROM risk." + table + " WHERE " + key + " = '" + id + "'", "P0001");
                refused(owner, "TRUNCATE risk." + table + " CASCADE", "P0001");
            }
        }
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            refused(owner, "INSERT INTO risk.risk_assessment_input (assessment_id, inputs_canonical, inputs_sha256)"
                    + " VALUES (gen_random_uuid(), '{}', '" + "0".repeat(64) + "')", "23514");
            owner.rollback();
        }
    }

    @Test
    @DisplayName("the rows round-trip whole through the store and replay IDENTICAL under the pinned version, read on one"
            + " REPEATABLE READ snapshot; the counted assessments are the windows' evidence for every dimension")
    void replayIsIdenticalAndTheCountedAreTheEvidence() throws Exception {
        activate(RiskOperation.CARD_PAYMENT);
        UUID party = IDS.next();
        RiskEvaluationRequest request = RiskEvaluationRequest.builder(RiskOperation.CARD_PAYMENT,
                CommandRef.of(IDS.next()), eur(20_00), party, correlation(), CausationId.of("cause"))
                .sourceAccount(IDS.next()).merchant(IDS.next()).device(DeviceHash.of("a phone " + party)).build();
        RiskEvaluation.Assessed assessed = inOneTransaction(uow -> EVALUATION.evaluate(uow, request));
        RiskAssessments reads = new RiskAssessments(ASSESSMENT_STORE, new RiskPolicies(POLICY_STORE),
                (uow, actor) -> Optional.of(IDS.next()), new JdbcAuditWriter(), IDS, CLOCK);
        AssessmentReplay.Replay replay = inSnapshot(uow -> reads.replay(uow, assessed.assessmentId(), employee()));
        assertThat(replay.verdict()).isEqualTo(AssessmentReplay.Outcome.IDENTICAL);
        RiskAssessment row = inOneTransaction(uow -> ASSESSMENT_STORE.read(uow, assessed.assessmentId()).orElseThrow());
        assertThat(row.sealHolds()).isTrue();
        assertThat(row.subjects()).isEqualTo(RiskAssessment.Subjects.of(request));
        assertThat(row.assessedAt()).isPresent();

        AssessmentVelocityEvidence evidence = new AssessmentVelocityEvidence(ASSESSMENT_STORE, WINDOW_STORE);
        VelocityEvidence.Record record = inSnapshot(uow -> evidence.read(uow, row.cutAt().orElseThrow()));
        assertThat(record.movements()).anySatisfy(counted -> {
            assertThat(counted.movement().subject(VelocityDimension.PARTY)).contains(party.toString());
            assertThat(counted.movement().subject(VelocityDimension.DEVICE)).isPresent();
            assertThat(counted.movement().subject(VelocityDimension.MERCHANT)).isPresent();
            assertThat(counted.at()).isEqualTo(row.cutAt().orElseThrow());
        });
    }

    // -----------------------------------------------------------------

    private static RiskEvaluation evaluation(RiskPolicies policies) {
        return new RiskEvaluation(policies, new VelocityWindows(WINDOW_STORE),
                new CustomerRiskProfiles(new JdbcCustomerRiskProfileStore(), (uow, actor) -> Optional.empty(),
                        new JdbcAuditWriter(), IDS, CLOCK),
                new SignalReadings(new JdbcRiskSignalStore()), RiskInputs.NONE, ASSESSMENT_STORE,
                new JdbcEvaluationBudget(), CaseOpener.NONE, new JdbcOutboxWriter(), RiskEvaluationObserver.NONE,
                Spans.NONE, IDS, CLOCK);
    }

    /** A policy store that answers "no version ACTIVE" and delegates nothing else T-eval reaches. */
    private static RiskPolicyStore noActive() {
        return (RiskPolicyStore) Proxy.newProxyInstance(RiskEvaluationDatabaseTest.class.getClassLoader(),
                new Class<?>[] {RiskPolicyStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "shareActiveOnce" -> Optional.empty();
                    case "lockOperationShared" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /**
     * The caller's money lock, as a seam's caller holds it: one transaction-scoped lock per command - a test-only
     * namespace (9999) no production writer registers (`DISTRIBUTED_EXECUTION.md`'s namespace register).
     */
    private static void callersLock(Connection uow, RiskEvaluationRequest request) {
        try (Statement lock = uow.createStatement()) {
            lock.execute("SELECT pg_advisory_xact_lock(9999, hashtext('" + request.commandRef().value() + "'))");
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static RiskEvaluationRequest transfer(Money amount, UUID party, UUID source) {
        return RiskEvaluationRequest.builder(RiskOperation.TRANSFER, CommandRef.of(IDS.next()), amount, party,
                correlation(), CausationId.of("cause")).sourceAccount(source).destinationAccount(IDS.next())
                .actor(new Actor("customer-" + party, ActorType.CUSTOMER)).build();
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, CurrencyCode.of("EUR"));
    }

    private static RiskPolicyVersionId activate(RiskOperation operation) {
        RiskPolicyAdministration.Proposed proposed = inOneTransaction(uow -> ADMINISTRATION.propose(uow,
                RiskPolicyFixtures.v1(operation), "v1 for " + operation, employee(), correlation()));
        inOneTransaction(uow -> ADMINISTRATION.activate(uow, proposed.id(), employee(), "activated", correlation()));
        return proposed.id();
    }

    private static <R> R inOneTransaction(Function<Connection, R> work) {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            try {
                R result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static <R> R inSnapshot(Function<Connection, R> work) {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            try {
                return work.apply(connection);
            } finally {
                connection.rollback();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Actor employee() {
        return new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE);
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    private static long windowCount(String dimension, UUID subject, String kind) throws SQLException {
        return count("SELECT COALESCE(sum(count), 0) FROM risk.velocity_window WHERE dimension = '" + dimension
                + "' AND subject_ref = '" + subject + "' AND window_kind = '" + kind + "'");
    }

    private static long count(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static List<String> column(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            while (row.next()) {
                values.add(row.getString(1));
            }
        }
        return values;
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

}
