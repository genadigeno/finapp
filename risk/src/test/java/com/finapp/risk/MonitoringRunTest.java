package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.telemetry.Spans;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-mon's rule leg over one claim, over fakes (`P13-TSK-020`; {@link MonitoringRun}; {@code DESIGN-020} D1, D2, D9,
 * D10): the pin before anything, the parties the rows touch placed once and judged in ascending order, each in an
 * isolation of its own; a party that throws undone alone, counted with its cause's class, and the run going on; an
 * account no party is placed behind counted and unjudged; nothing judged without a version; a broken isolation failing
 * the claim whole. The savepoints themselves and the claim's one transaction are {@code MonitoringRulesDatabaseTest}'s.
 */
@DisplayName("the rule leg over one claim: pinned, each party isolated, a failure alone (P13-TSK-020)")
class MonitoringRunTest {

    private static final LocalDate DAY = LocalDate.parse("2026-10-20");

    private final MonitoringWorld world = new MonitoringWorld();

    @Test
    @DisplayName("nothing to judge: no pin, no read, nothing counted")
    void anEmptyBatchJudgesNothing() {
        world.monitoringActive();
        world.policies.log.clear();
        assertThat(world.judge()).isEqualTo(MonitoringRun.Judged.NOTHING);
        assertThat(world.policies.log).isEmpty();
        assertThat(world.legLog).isEmpty();
    }

    @Test
    @DisplayName("the pin (R1) first; then each touched party, payer and payee, once - in ascending order, each in an"
            + " isolation of its own released after its leg")
    void pinnedThenEachPartyAlone() {
        world.monitoringActive();
        UUID a = world.accountOfNewParty();
        UUID b = world.accountOfNewParty();
        UUID c = world.accountOfNewParty();
        world.policies.log.clear();
        MonitoringRun.Judged judged = world.judge(world.transfer(a, b, 10_00, DAY), world.withdrawal(c, 10_00, DAY),
                world.withdrawal(a, 10_00, DAY));
        assertThat(judged).isEqualTo(new MonitoringRun.Judged(3, 0, 0, 0));
        assertThat(world.policies.log).first().isEqualTo("share MONITORING");
        assertThat(world.legLog).containsExactly("savepoint", "released", "savepoint", "released", "savepoint",
                "released");
        List<UUID> order = new ArrayList<>(List.of(world.partyOf(a), world.partyOf(b), world.partyOf(c)));
        order.sort(UUID::compareTo);
        assertThat(world.profiles.log).filteredOn(line -> line.startsWith("lock ")).as("R8, ascending")
                .containsExactly("lock " + order.get(0), "lock " + order.get(1), "lock " + order.get(2));
        assertThat(world.profiles.log.subList(0, 3)).as("every head first - before any party's alert, case or version:"
                + " never R8 under R4").containsExactly("lock " + order.get(0), "lock " + order.get(1),
                "lock " + order.get(2));
        assertThat(world.outcome(MonitoringObserver.SubjectOutcome.JUDGED)).isEqualTo(3);
        assertThat(world.latencies).hasSize(3);
    }

    @Test
    @DisplayName("a party whose leg throws is rolled back alone and counted FAILED with its cause's class - the other"
            + " parties are judged, their alerts raised")
    void aFailingPartyIsSkippedAndCounted() {
        world.monitoringActive();
        UUID poisoned = world.accountOfNewParty();
        UUID healthy = world.accountOfNewParty();
        world.poisoned = world.partyOf(poisoned);
        MonitoringRun.Judged judged = world.judge(world.withdrawal(poisoned, 900_00, DAY),
                world.withdrawal(poisoned, 950_00, DAY), world.withdrawal(poisoned, 800_00, DAY),
                world.withdrawal(healthy, 900_00, DAY), world.withdrawal(healthy, 950_00, DAY),
                world.withdrawal(healthy, 800_00, DAY));
        assertThat(judged).isEqualTo(new MonitoringRun.Judged(1, 1, 0, 0));
        assertThat(world.legLog).contains("rolled back").contains("released");
        assertThat(world.failures).containsExactly(IllegalStateException.class);
        assertThat(world.outcome(MonitoringObserver.SubjectOutcome.FAILED)).isEqualTo(1);
        assertThat(world.amlAlerts(world.partyOf(healthy), RuleKind.STRUCTURING)).hasSize(1);
        assertThat(world.amlAlerts(world.partyOf(poisoned), RuleKind.STRUCTURING)).isEmpty();
    }

    @Test
    @DisplayName("an account no party is placed behind is counted and unjudged; a correction and a row with no customer"
            + " account touch no party")
    void unplacedAndUntouched() {
        world.monitoringActive();
        UUID stray = UUID.randomUUID();
        UUID account = world.accountOfNewParty();
        MonitoredTransaction reversal = world.row(MonitoredKind.REVERSAL, account, MonitoredDirection.IN,
                MonitoredCounterpartyKind.OPERATIONAL, Optional.empty(), 10_00, MonitoringWorld.EUR, DAY);
        MonitoringRun.Judged judged = world.judge(world.withdrawal(stray, 10_00, DAY), reversal);
        assertThat(judged).isEqualTo(new MonitoringRun.Judged(0, 0, 0, 1));
        assertThat(world.unplaced).isEqualTo(1);
        assertThat(world.profiles.head(world.partyOf(account))).isEmpty();
    }

    @Test
    @DisplayName("a broken isolation - a savepoint that will not roll back - fails the claim whole, never counted as"
            + " one party's failure")
    void aBrokenIsolationFailsTheClaim() {
        world.monitoringActive();
        UUID account = world.accountOfNewParty();
        MonitoringRun broken = new MonitoringRun(new RiskPolicies(world.policies), world.parties, world.recompute,
                EvaluationWorld.IDS, MonitoringWorld.CLOCK, Spans.NONE, world.observer,
                new MonitoringRun.Isolation() {
                    @Override
                    public <T> T alone(Connection unitOfWork, Supplier<T> work) {
                        throw new MonitoringRun.IsolationBroken(new RiskStorageException("SQLSTATE 08006"));
                    }
                });
        MonitoredTransaction row = world.withdrawal(account, 10_00, DAY);
        world.movements.rows.add(row);
        assertThatExceptionOfType(MonitoringRun.IsolationBroken.class)
                .isThrownBy(() -> MonitoringWorld.asSystem(() -> broken.judge(MonitoringWorld.UOW, List.of(row))));
        assertThat(world.outcome(MonitoringObserver.SubjectOutcome.FAILED)).isZero();
    }

    @Test
    @DisplayName("the production isolation is a JDBC savepoint: opened, released after the work, rolled back when it"
            + " throws - and a savepoint that will not open breaks the isolation")
    void theSavepointIsolation() throws Exception {
        List<String> calls = new ArrayList<>();
        Connection connection = (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                    calls.add(method.getName());
                    return method.getName().equals("setSavepoint")
                            ? java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                    new Class<?>[] {java.sql.Savepoint.class}, (p, m, a) -> null)
                            : null;
                });
        assertThat(MonitoringRun.Isolation.SAVEPOINTS.alone(connection, () -> "done")).isEqualTo("done");
        assertThat(calls).containsExactly("setSavepoint", "releaseSavepoint");
        calls.clear();
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> MonitoringRun.Isolation.SAVEPOINTS
                .alone(connection, () -> {
                    throw new IllegalStateException("the leg failed");
                }));
        assertThat(calls).containsExactly("setSavepoint", "rollback");

        Connection refusing = (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                    throw new java.sql.SQLException("no savepoints here", "25P02");
                });
        assertThatExceptionOfType(MonitoringRun.IsolationBroken.class)
                .isThrownBy(() -> MonitoringRun.Isolation.SAVEPOINTS.alone(refusing, () -> "never"));
    }
}
