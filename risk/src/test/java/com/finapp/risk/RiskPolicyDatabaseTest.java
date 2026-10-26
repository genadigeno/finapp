package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
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
 * Risk policy versions as data against a real database (`P13-TSK-002`; {@code risk V002}, ADR-0109; {@code INV-RSK-05},
 * {@code INV-RSK-07}, {@code INV-AUD-04}, {@code INV-HIST-04}): every race counted - ten proposers, ten activators, an
 * activation racing ten evaluations' pins (scenario 3); four eyes, immutability for every role, one {@code ACTIVE}
 * version per operation and nothing migration-activated, held for every writer by raw SQL; the retirement and the
 * activation one transaction. bnpl's {@code BnplTermsDatabaseTest}, applied. <strong>Written and owed</strong> (owner
 * decision C5: the database tiers are skipped per task; the owner-authorised database pass before the exit review runs
 * it).
 *
 * <p>The operations are shared rows in the suite's database, so every case brings an operation to the state it needs
 * through the domain itself and never assumes what an earlier case left.
 */
@Tag("database")
@DisplayName("risk policy versions as data (P13-TSK-002)")
class RiskPolicyDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final JdbcRiskPolicyStore STORE = new JdbcRiskPolicyStore();
    private static final RiskPolicyAdministration ADMINISTRATION = new RiskPolicyAdministration(STORE,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);
    private static final RiskPolicies POLICIES = new RiskPolicies(STORE);

    @Test
    @DisplayName("ten proposers on ten connections leave ten PROPOSED versions numbered 1..n without a gap - the advisory"
            + " lock serialises the numbering, UNIQUE (operation, version) the backstop")
    void tenProposalsTenVersions() throws Exception {
        long before = count("SELECT COALESCE(max(version), 0) FROM risk.risk_policy_version WHERE operation = 'TRANSFER'");
        List<Object> outcomes = race(10, () -> inOneTransaction(uow -> ADMINISTRATION.propose(uow,
                RiskPolicyFixtures.v1(RiskOperation.TRANSFER), "racing", employee(), correlation())));
        List<Integer> versions = outcomes.stream().map(RiskPolicyAdministration.Proposed.class::cast)
                .map(RiskPolicyAdministration.Proposed::version).sorted().toList();
        List<Integer> expected = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            expected.add((int) before + i);
        }
        assertThat(versions).isEqualTo(expected);
        for (Object outcome : outcomes) {
            RiskPolicyAdministration.Proposed proposed = (RiskPolicyAdministration.Proposed) outcome;
            inOneTransaction(uow -> ADMINISTRATION.reject(uow, proposed.id(), employee(), "racing done", correlation()));
        }
    }

    @Test
    @DisplayName("one ACTIVE per operation: a raw second ACTIVE by a different person is refused by the partial unique for"
            + " the owner itself; and a version commits only with its whole content")
    void oneActivePerOperation() throws Exception {
        activate(RiskOperation.CARD_PAYMENT);
        RiskPolicyAdministration.Proposed second = propose(RiskOperation.CARD_PAYMENT, employee());
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE risk.risk_policy_version SET status = 'ACTIVE', decided_by = 'someone-else',"
                    + " decided_by_type = 'EMPLOYEE', decision_reason = 'raw' WHERE id = '" + second.id().value() + "'",
                    "23505");
        }
        assertThat(count("SELECT count(*) FROM risk.risk_policy_version WHERE operation = 'CARD_PAYMENT'"
                + " AND status = 'ACTIVE'")).isEqualTo(1);
        inOneTransaction(uow -> ADMINISTRATION.reject(uow, second.id(), employee(), "done", correlation()));
        for (Connection writer : List.of(DatabaseRoles.migrator(), DatabaseRoles.application())) {
            try (writer) {
                writer.setAutoCommit(false);
                try (Statement statement = writer.createStatement()) {
                    statement.execute("INSERT INTO risk.risk_policy_version (id, operation, version, engine, status,"
                            + " proposed_by, proposed_by_type, proposed_at, proposal_reason, correlation_id) VALUES ('"
                            + UUID.randomUUID() + "', 'CARD_PAYMENT', 999999, 'RISK_ENGINE_V1', 'PROPOSED', 'raw',"
                            + " 'EMPLOYEE', clock_timestamp(), 'no content', 'raw')");
                }
                assertThatThrownBy(writer::commit).as("a version without its content never commits")
                        .isInstanceOf(SQLException.class)
                        .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("P0001"));
                writer.rollback();
            }
        }
    }

    @Test
    @DisplayName("ten activators activate once and retire the predecessor in the same transaction: one ACTIVE, nine"
            + " PolicyStale, the periods meeting, one event, one audit record; a retirement alone never commits")
    void tenActivationsOneActive() throws Exception {
        RiskPolicyVersionId predecessor = activate(RiskOperation.WALLET_PAYMENT);
        RiskPolicyAdministration.Proposed proposed = propose(RiskOperation.WALLET_PAYMENT, employee());
        List<Object> outcomes = race(10, () -> decideOrStale(
                uow -> ADMINISTRATION.activate(uow, proposed.id(), employee(), "racing", correlation())));
        assertThat(outcomes.stream().filter(RiskPolicyAdministration.Decided.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(RiskPolicyAdministration.PolicyStale.class::isInstance)).hasSize(9);
        assertThat(status(proposed.id())).isEqualTo("ACTIVE");
        assertThat(status(predecessor)).isEqualTo("RETIRED");
        assertThat(effective(predecessor, "effective_to")).as("the periods meet")
                .isEqualTo(effective(proposed.id(), "effective_from"));
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = '"
                + RiskPolicyAdministration.ACTIVATED_EVENT + "' AND aggregate_id = '" + proposed.id().value() + "'"))
                .as("one event").isEqualTo(1);
        assertThat(count(audited("risk.PolicyActivated", proposed.id()))).as("losers record nothing").isEqualTo(1);
        assertThat(count(audited("risk.PolicyRetired", predecessor))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_policy_version WHERE operation = 'WALLET_PAYMENT'"
                + " AND status = 'ACTIVE'")).isEqualTo(1);
    }

    @Test
    @DisplayName("the retirement and the activation are one transaction: a retirement alone is refused at commit for"
            + " every role, and so is an activation that leaves the predecessor ACTIVE")
    void retireAndActivateAreOneTransaction() throws Exception {
        RiskPolicyVersionId active = activate(RiskOperation.CROSS_BORDER);
        for (Connection writer : List.of(DatabaseRoles.migrator(), DatabaseRoles.application())) {
            try (writer) {
                writer.setAutoCommit(false);
                try (Statement statement = writer.createStatement()) {
                    statement.execute("UPDATE risk.risk_policy_version SET status = 'RETIRED' WHERE id = '"
                            + active.value() + "'");
                }
                assertThatThrownBy(writer::commit).as("retired with no successor")
                        .isInstanceOf(SQLException.class)
                        .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("P0001"));
                writer.rollback();
            }
        }
        assertThat(status(active)).isEqualTo("ACTIVE");
        RiskPolicyAdministration.Proposed next = propose(RiskOperation.CROSS_BORDER, employee());
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE risk.risk_policy_version SET status = 'ACTIVE', decided_by = 'someone-else',"
                    + " decided_by_type = 'EMPLOYEE', decision_reason = 'raw' WHERE id = '" + next.id().value() + "'",
                    "23505");
        }
        inOneTransaction(uow -> ADMINISTRATION.reject(uow, next.id(), employee(), "done", correlation()));
    }

    @Test
    @DisplayName("scenario 3: an activation in flight while ten evaluations pin - every pin answers exactly one version,"
            + " the predecessor or the successor, none \"neither\"; the activation never waits on anything but the pins")
    void anEvaluationBehindAnActivationPinsTheSuccessor() throws Exception {
        // The pin's half; the evaluation's - ten T-evals behind a held activation, each assessment sealed with exactly
        // one version - is RiskEvaluationDatabaseTest's case of the same name (P13-TSK-004).
        RiskPolicyVersionId predecessor = activate(RiskOperation.TRANSFER);
        RiskPolicyAdministration.Proposed successor = propose(RiskOperation.TRANSFER, employee());
        CountDownLatch retired = new CountDownLatch(1);
        ExecutorService activator = Executors.newSingleThreadExecutor();
        try {
            Future<RiskPolicyAdministration.Decided> activation = activator.submit(() -> {
                try (Connection uow = DatabaseRoles.application()) {
                    uow.setAutoCommit(false);
                    // The activation: A0, the rows, the predecessor retired and the successor activated - then held
                    // open, so every pin that arrives now meets the FOR UPDATE on the predecessor's row and the
                    // retirement in flight.
                    RiskPolicyAdministration.Decided decided = ADMINISTRATION.activate(uow, successor.id(), employee(),
                            "in flight", correlation());
                    retired.countDown();
                    Thread.sleep(1_500);
                    uow.commit();
                    return decided;
                }
            });
            retired.await(30, TimeUnit.SECONDS);
            List<Optional<RiskPolicyVersionId>> pinned = race(10, () -> {
                try (Connection uow = DatabaseRoles.application()) {
                    uow.setAutoCommit(false);
                    try {
                        return POLICIES.active(uow, RiskOperation.TRANSFER).map(RiskPolicyVersion::id);
                    } finally {
                        uow.rollback();
                    }
                }
            });
            activation.get(1, TimeUnit.MINUTES);
            assertThat(pinned).as("no pin answers neither").allMatch(Optional::isPresent);
            assertThat(pinned).extracting(Optional::get).allMatch(id -> id.equals(successor.id()) || id.equals(predecessor));
            assertThat(pinned).extracting(Optional::get).as("a pin that waited on the retirement reads the successor")
                    .contains(successor.id());
        } finally {
            activator.shutdownNow();
        }
        assertThat(status(successor.id())).isEqualTo("ACTIVE");
        assertThat(status(predecessor)).isEqualTo("RETIRED");
    }

    @Test
    @DisplayName("the proposer cannot activate: refused at the domain, its attempt recorded FAILED; and by the CHECK for a"
            + " raw-SQL writer of either role - each rank alone")
    void theProposerCannotActivate() throws SQLException {
        Actor proposer = employee();
        RiskPolicyAdministration.Proposed proposed = propose(RiskOperation.WITHDRAWAL, proposer);
        RiskPolicyAdministration.SelfApprovalRefused refusal = (RiskPolicyAdministration.SelfApprovalRefused)
                org.assertj.core.api.Assertions.catchThrowable(() -> inOneTransaction(
                        uow -> ADMINISTRATION.activate(uow, proposed.id(), proposer, "my own", correlation())));
        assertThat(refusal).isNotNull();
        inOneTransaction(uow -> {
            ADMINISTRATION.recordRefusal(uow, refusal, proposer, correlation());
            return null;
        });
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.SelfApprovalRefused'"
                + " AND outcome = 'FAILED' AND target_type = 'risk_policy_version' AND target_id = '"
                + proposed.id().value() + "'")).isEqualTo(1);
        String selfActivation = "UPDATE risk.risk_policy_version SET status = 'ACTIVE', decided_by = proposed_by,"
                + " decided_by_type = 'EMPLOYEE', decision_reason = 'raw' WHERE id = '" + proposed.id().value() + "'";
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, selfActivation, "23514");
        }
        try (Connection application = DatabaseRoles.application()) {
            refused(application, selfActivation, "23514");
        }
        assertThat(status(proposed.id())).isEqualTo("PROPOSED");
        inOneTransaction(uow -> ADMINISTRATION.reject(uow, proposed.id(), proposer, "withdrawn", correlation()));
    }

    @Test
    @DisplayName("a decided version is immutable for every role: its content, rules, parameters and bands refuse UPDATE"
            + " and DELETE, its identity is frozen, its history append-only; nothing is migration-activated")
    void aDecidedVersionIsImmutable() throws SQLException {
        RiskPolicyVersionId active = activate(RiskOperation.MONITORING);
        String id = active.value().toString();
        // The owner holds every privilege, so each refusal is the trigger's (P0001); the application holds no UPDATE or
        // DELETE of the content tables, so it is refused by privilege first (42501) wherever it lacks one and by the
        // trigger wherever it holds one (P13-TST-001's run of this owed suite: the loop had demanded P0001 of the
        // application too, contradicting the 42501 its own next block asserts).
        for (boolean owner : new boolean[] {true, false}) {
            try (Connection writer = owner ? DatabaseRoles.migrator() : DatabaseRoles.application()) {
                String bare = owner ? "P0001" : "42501";
                refused(writer, "UPDATE risk.risk_rule SET weight = 1 WHERE policy_version_id = '" + id + "'", bare);
                refused(writer, "UPDATE risk.risk_rule_parameter SET value_minor = 1 WHERE policy_version_id = '" + id
                        + "' AND type = 'MINOR_AMOUNT'", bare);
                refused(writer, "UPDATE risk.risk_policy_band SET rating = 'LOW' WHERE policy_version_id = '" + id + "'",
                        bare);
                refused(writer, "UPDATE risk.risk_policy SET currency = 'USD' WHERE policy_version_id = '" + id + "'",
                        bare);
                refused(writer, "DELETE FROM risk.risk_rule WHERE policy_version_id = '" + id + "'", bare);
                refused(writer, "DELETE FROM risk.risk_policy_version WHERE id = '" + id + "'", bare);
                refused(writer, "UPDATE risk.risk_policy_version SET version = 999 WHERE id = '" + id + "'", bare);
                // The application holds UPDATE of status (V002's column grant): refused by the trigger.
                refused(writer, "UPDATE risk.risk_policy_version SET status = 'PROPOSED' WHERE id = '" + id + "'",
                        "P0001");
                refused(writer, "UPDATE risk.risk_policy_version_event SET reason = 'edited' WHERE"
                        + " policy_version_id = '" + id + "'", bare);
                refused(writer, "INSERT INTO risk.risk_rule (policy_version_id, ordinal, kind, weight) VALUES ('" + id
                        + "', 999, 'ROUND_AMOUNT', 1)", "P0001");
            }
        }
        try (Connection application = DatabaseRoles.application()) {
            refused(application, "UPDATE risk.risk_policy SET currency = 'USD' WHERE policy_version_id = '" + id + "'",
                    "42501");
            refused(application, "DELETE FROM risk.risk_policy_version WHERE id = '" + id + "'", "42501");
        }
        assertThat(count("SELECT count(*) FROM risk.risk_policy_version WHERE proposed_by LIKE 'migration:%'"
                + " OR proposed_by = 'finapp_migrator'")).as("nothing is seeded").isZero();
        assertThat(count("SELECT count(*) FROM risk.risk_policy_version WHERE status IN ('ACTIVE', 'RETIRED')"
                + " AND (decided_by IS NULL OR decided_by = proposed_by)")).as("every activation two persons'").isZero();
        RiskPolicyVersion read = inOneTransaction(uow -> POLICIES.version(uow, active));
        assertThat(read.policy()).isEqualTo(RiskPolicyFixtures.v1(RiskOperation.MONITORING));
    }

    // -----------------------------------------------------------------

    private static RiskPolicyAdministration.Proposed propose(RiskOperation operation, Actor actor) {
        return inOneTransaction(uow -> ADMINISTRATION.propose(uow, RiskPolicyFixtures.v1(operation), "v1 for "
                + operation, actor, correlation()));
    }

    /** Proposes and activates a version of {@code operation} by two persons; the id of the new ACTIVE version. */
    private static RiskPolicyVersionId activate(RiskOperation operation) {
        RiskPolicyAdministration.Proposed proposed = propose(operation, employee());
        inOneTransaction(uow -> ADMINISTRATION.activate(uow, proposed.id(), employee(), "activated", correlation()));
        return proposed.id();
    }

    private static Object decideOrStale(Function<Connection, RiskPolicyAdministration.Decided> act) {
        try {
            return inOneTransaction(act);
        } catch (RiskPolicyAdministration.PolicyStale stale) {
            return stale;
        }
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

    private static Actor employee() {
        return new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE);
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    private static String audited(String operation, RiskPolicyVersionId target) {
        return "SELECT count(*) FROM platform.audit_record WHERE operation = '" + operation
                + "' AND target_type = 'risk_policy_version' AND target_id = '" + target.value() + "'";
    }

    private static long count(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String status(RiskPolicyVersionId id) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT status FROM risk.risk_policy_version WHERE id = '" + id.value() + "'")) {
            row.next();
            return row.getString(1);
        }
    }

    private static String effective(RiskPolicyVersionId id, String column) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery("SELECT " + column + "::text FROM risk.risk_policy_version WHERE id = '"
                        + id.value() + "'")) {
            row.next();
            return row.getString(1);
        }
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
