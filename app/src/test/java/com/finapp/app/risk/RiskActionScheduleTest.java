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
        RiskActionSchedule schedule = schedule(world, Duration.ofSeconds(5));

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
        RiskActionSchedule schedule = schedule(world, Duration.ofSeconds(5));
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
        RiskActionSchedule schedule = schedule(world, Duration.ofHours(1));
        assertThat(schedule.isRunning()).isFalse();
        schedule.start();
        assertThat(schedule.isRunning()).isTrue();
        schedule.stop();
        assertThat(schedule.isRunning()).isFalse();
        assertThatThrownBy(() -> new RiskActionSchedule(world.driver, world.filings, world.metrics, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiskActionDriver(world.actions, world.transactions, world.metrics,
                RiskFlowScope.NONE, Duration.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiskActionDriver(world.actions, world.transactions, world.metrics,
                RiskFlowScope.NONE, Duration.ofMinutes(1), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the report leg (P13-TSK-021): after the action leg, a FILED report whose door never drove it is claimed,"
            + " submitted on no transaction as the platform and its acknowledgement recorded; an unknown answer is asked"
            + " again at its permit, never filed twice; a failing report claim is counted claim.failed{kind=report} and"
            + " leaves the action leg untouched")
    void theReportLeg() {
        RiskActionWorld world = new RiskActionWorld();
        RiskActionSchedule schedule = schedule(world, Duration.ofSeconds(5));
        String caseId = world.confirmedSuspicious();
        com.finapp.risk.RegulatoryReport filed = filedAlone(world, caseId);
        world.authority.behaviour = SimulatedFilingProvider.Behaviour.UNKNOWN_THEN_ACKNOWLEDGE;

        assertThat(schedule.sweepOnce()).as("the report's first answer recorded").isEqualTo(1);
        assertThat(world.reportOf(filed.id().value().toString()).status())
                .isEqualTo(com.finapp.risk.RegulatoryReportStatus.FILED);
        assertThat(schedule.sweepOnce()).as("the inquiry interval is the permit").isZero();
        world.reportStore.now = world.reportStore.now.plus(Duration.ofMinutes(16));
        assertThat(schedule.sweepOnce()).isEqualTo(1);
        assertThat(world.reportOf(filed.id().value().toString()).status())
                .isEqualTo(com.finapp.risk.RegulatoryReportStatus.ACKNOWLEDGED);
        assertThat(world.authority.calls).containsExactly(
                "file|" + filed.filingReference() + "|depth=0|SYSTEM",
                "inquire|" + filed.filingReference() + "|depth=0|SYSTEM");

        approvedAlone(world);
        String another = world.confirmedSuspicious();
        filedAlone(world, another);
        // The tick's first transaction - the action leg's claim - fails; the report leg's claim, the next one, does not.
        world.failTransactions.push(new IllegalStateException("the database is unreachable"));
        assertThat(schedule.sweepOnce()).as("the action leg's claim failed; the report leg went on").isEqualTo(1);
        assertThat(world.count(RiskActionMetrics.CLAIM_FAILED, "kind", RiskActionMetrics.KIND)).isEqualTo(1);
        assertThat(world.count(RegulatoryReportMetrics.CLAIM_FAILED, "kind", RegulatoryReportMetrics.KIND)).isZero();
        assertThat(schedule.sweepOnce()).as("the action, the next tick").isEqualTo(1);
    }

    private static RiskActionSchedule schedule(RiskActionWorld world, Duration poll) {
        return new RiskActionSchedule(world.driver, world.filings, world.metrics, poll);
    }

    /** {@code caseId} reported - drafted, approved and filed by three officers, never driven by a door. */
    private static com.finapp.risk.RegulatoryReport filedAlone(RiskActionWorld world, String caseId) {
        com.finapp.risk.RiskCaseId id = com.finapp.risk.RiskCaseId.of(java.util.UUID.fromString(caseId));
        var drafter = world.analyst();
        com.finapp.risk.RegulatoryReport drafted = RiskActionWorld.as(drafter, () -> world.transactions.inTransaction(
                uow -> world.reports.draft(uow, id, new com.finapp.risk.RegulatoryReports.Draft("EU-REF",
                        "Funds were split and moved out within a day.", List.of(), "confirmed"), drafter,
                        RiskActionWorld.correlation())));
        var approver = world.analyst();
        RiskActionWorld.as(approver, () -> world.transactions.inTransaction(uow -> world.reports.approve(uow, id,
                drafted.id(), "accurate", approver, RiskActionWorld.correlation())));
        var filer = world.analyst();
        return RiskActionWorld.as(filer, () -> world.transactions.inTransaction(uow -> world.reports.file(uow, id,
                drafted.id(), "file", filer, RiskActionWorld.correlation()).report()));
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
