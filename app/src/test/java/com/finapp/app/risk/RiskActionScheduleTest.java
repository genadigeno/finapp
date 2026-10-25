package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.risk.ActionStatus;
import com.finapp.risk.RiskAction;
import com.finapp.risk.RiskCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RiskActionSchedule} (`P13-TSK-017`; {@code PHASE_13_PLAN.md} section 7.5; ADR-0112 decisions 4-5; the Phase 12 →
 * 13 gate's N5): a tick claims the due permits, one per transaction, up to its bound; a claimed action is not claimed
 * again within its lease; a failed claim is counted {@code finapp.risk.sweeper.claim.failed{kind=action}} and ends the
 * tick; the gauge {@code finapp.risk.sweeper.enabled{kind=action}} is eager either way; the lifecycle starts and stops
 * a daemon thread. The ten-sweeper race on a real database is {@code RiskActionDatabaseTest#tenSweepersDispatchOnce}
 * (written, owed - C5).
 */
@DisplayName("RiskActionSchedule: the claim per tick, the bound, the lease, claim failures counted, the gauge"
        + " (P13-TSK-017)")
class RiskActionScheduleTest {

    @Test
    @DisplayName("a tick drives the due actions up to its bound, each claimed in a transaction of its own; the next"
            + " tick the rest; a claimed action is not claimed again within its lease")
    void aTickDrivesTheDueActionsUpToItsBound() {
        RiskActionWorld world = new RiskActionWorld(2);
        List<RiskAction> approved = IntStream.range(0, 3).mapToObj(i -> approvedAlone(world)).toList();
        RiskActionSchedule schedule = new RiskActionSchedule(world.driver, world.metrics, Duration.ofSeconds(5));

        assertThat(schedule.sweepOnce()).as("the bound").isEqualTo(2);
        assertThat(schedule.sweepOnce()).isEqualTo(1);
        assertThat(schedule.sweepOnce()).as("nothing due").isZero();
        assertThat(approved).allSatisfy(action ->
                assertThat(world.actionOf(action.id().value().toString()).status()).isEqualTo(ActionStatus.DONE));
        assertThat(world.owners.calls).hasSize(3).allSatisfy(call -> assertThat(call).endsWith("|depth=0|SYSTEM"));
    }

    @Test
    @DisplayName("N5: a claim that fails is counted claim.failed{kind=action} and ends the tick, nothing claimed; the"
            + " next tick claims it")
    void aFailedClaimIsCounted() {
        RiskActionWorld world = new RiskActionWorld();
        approvedAlone(world);
        RiskActionSchedule schedule = new RiskActionSchedule(world.driver, world.metrics, Duration.ofSeconds(5));
        world.failTransactions.push(new IllegalStateException("the database is unreachable"));

        assertThat(schedule.sweepOnce()).isZero();
        assertThat(world.count(RiskActionMetrics.CLAIM_FAILED, "kind", RiskActionMetrics.KIND)).isEqualTo(1);
        assertThat(world.owners.calls).isEmpty();
        assertThat(schedule.sweepOnce()).isEqualTo(1);
    }

    @Test
    @DisplayName("the gauge is eager either way - 1 where the schedule runs, 0 where it is off; the lifecycle starts a"
            + " daemon tick and stops it; a non-positive poll is refused")
    void theGaugeAndTheLifecycle() {
        SimpleMeterRegistry on = new SimpleMeterRegistry();
        new RiskActionBeans().riskActionSweeperEnabled(true, on);
        assertThat(on.get("finapp.risk.sweeper.enabled").tag("kind", "action").gauge().value()).isEqualTo(1.0);
        SimpleMeterRegistry off = new SimpleMeterRegistry();
        new RiskActionBeans().riskActionSweeperEnabled(false, off);
        assertThat(off.get("finapp.risk.sweeper.enabled").tag("kind", "action").gauge().value()).isZero();

        RiskActionWorld world = new RiskActionWorld();
        RiskActionSchedule schedule = new RiskActionSchedule(world.driver, world.metrics, Duration.ofHours(1));
        assertThat(schedule.isRunning()).isFalse();
        schedule.start();
        assertThat(schedule.isRunning()).isTrue();
        schedule.stop();
        assertThat(schedule.isRunning()).isFalse();
        assertThatThrownBy(() -> new RiskActionSchedule(world.driver, world.metrics, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiskActionDriver(world.actions, world.transactions, world.metrics,
                RiskFlowScope.NONE, Duration.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiskActionDriver(world.actions, world.transactions, world.metrics,
                RiskFlowScope.NONE, Duration.ofMinutes(1), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** A held withdrawal's release proposed and approved in their own transactions - and never driven by the door. */
    private static RiskAction approvedAlone(RiskActionWorld world) {
        RiskActionWorld.Held held = world.heldWithdrawalCase();
        RiskActionDesk.RiskActionView proposed = world.propose(held.taken(), "RELEASE_HELD_WITHDRAWAL",
                held.movement().toString());
        var approver = world.analyst();
        RiskCase current = world.caseOf(proposed.caseId());
        return RiskActionWorld.as(approver, () -> world.transactions.inTransaction(uow -> world.actions.approve(uow,
                current.id(), world.actionOf(proposed.actionId()).id(), current.revision(), "ok", approver,
                RiskActionWorld.correlation()).action()));
    }
}
