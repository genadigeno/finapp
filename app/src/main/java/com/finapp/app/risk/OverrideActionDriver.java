package com.finapp.app.risk;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.ActionOutcome;
import com.finapp.risk.DecisionOverrideId;
import com.finapp.risk.OverrideActions;
import com.finapp.risk.RiskSpans;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * T-act for an approved override's issued action (`P13-TSK-012`; {@code PHASE_13_PLAN.md} section 7.1 T-act;
 * ADR-0112 decisions 4-6; {@code DESIGN-012} D1) - the at-least-once drive until `-017`'s {@code RiskActionSchedule}
 * takes it over. Three transactions at most, and the dispatch in none of them:
 *
 * <ol>
 *   <li>the action found ({@link #driveIfPending}: the approval door, after its approval committed, and on every retry
 *       of it - the same key replaying the approval re-drives the action) or claimed ({@link #driveDue}: the re-drive
 *       leg on {@code RiskMonitoringSchedule}, one due permit per transaction {@code SKIP LOCKED}, re-stamped a lease
 *       ahead and committed before the dispatch, so ten instances claim it once per lease);</li>
 *   <li>the dispatch - payments' keyed door, on <strong>no transaction</strong>: {@link RiskTransactions} is Spring's
 *       default propagation, and calling the port inside it would join payments' own transactions to a risk one,
 *       holding a risk row across the push rail's send;</li>
 *   <li>the outcome recorded once by its conditional edge - whoever records first, the door or the leg.</li>
 * </ol>
 *
 * <p>A dispatch that throws records nothing: counted {@code finapp.risk.action.failed{kind}}, one warn line with the
 * exception's class only, and the permit left standing for the next attempt under the same key.
 */
@Slf4j
public final class OverrideActionDriver {

    private final OverrideActions actions;
    private final RiskTransactions transactions;
    private final RiskOverrideMetrics metrics;
    private final RiskFlowScope flow;
    private final Duration lease;
    private final int perTick;

    /**
     * @param lease how long a claimed action's permit is held before another attempt may claim it - longer than the
     *     longest dispatch, so a live attempt is not raced
     * @param perTick the most due actions one leg's tick drives - the bound on how long the monitoring sweep waits on
     *     payments' doors
     */
    public OverrideActionDriver(OverrideActions actions, RiskTransactions transactions, RiskOverrideMetrics metrics,
            RiskFlowScope flow, Duration lease, int perTick) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.flow = Objects.requireNonNull(flow, "flow");
        this.lease = Objects.requireNonNull(lease, "lease");
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("an action's lease is positive");
        }
        if (perTick < 1) {
            throw new IllegalArgumentException("perTick must be positive: " + perTick);
        }
        this.perTick = perTick;
    }

    /** The lease an approval issues its action's permit with. */
    public Duration lease() {
        return lease;
    }

    /**
     * {@code id}'s issued action, when one still awaits its outcome, dispatched and recorded by {@code actor} - the
     * approval door's drive, after its approval's transaction has committed.
     *
     * @return the outcome recorded or found recorded by this drive; empty when nothing awaited or the dispatch threw
     */
    public Optional<ActionOutcome> driveIfPending(DecisionOverrideId id, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(id, "id");
        Optional<OverrideActions.Issued> issued = transactions.inTransaction(uow -> actions.pending(uow, id));
        return issued.flatMap(pending -> drive(pending, actor, correlation));
    }

    /**
     * The re-drive leg: up to the bound, the oldest due action claimed in a transaction of its own, then dispatched and
     * recorded as the acting principal - the platform, inside the monitoring sweep's system scope - each under its own
     * override's correlation. A failed claim propagates - the schedule counts it and ends the leg for the tick; a
     * failed dispatch is counted here and the leg goes on to the next due action.
     *
     * @return how many actions this tick drove to a recorded outcome
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public int driveDue() {
        Actor platform = SecurityContext.require();
        int concluded = 0;
        for (int taken = 0; taken < perTick; taken++) {
            // A failing claim propagates: the schedule counts it claim.failed{kind=monitoring} and ends the leg.
            Optional<OverrideActions.Issued> claimed =
                    transactions.inTransaction(uow -> actions.claimNextDue(uow, lease));
            if (claimed.isEmpty()) {
                return concluded;
            }
            CorrelationId correlation = CorrelationId.of(claimed.get().correlationId());
            try (CorrelationContext.Scope scope = CorrelationContext.enter(Correlation.startingWith(correlation))) {
                if (drive(claimed.get(), platform, correlation).isPresent()) {
                    concluded++;
                }
            }
        }
        return concluded;
    }

    private Optional<ActionOutcome> drive(OverrideActions.Issued issued, Actor actor, CorrelationId correlation) {
        ActionOutcome outcome;
        try {
            outcome = flow.sweep(RiskSpans.ACTION_DISPATCH, () -> actions.dispatch(issued));
        } catch (RuntimeException failure) {
            // Nothing recorded: the permit stands and the next attempt re-drives the same key. The class only -
            // never a target, a key or a reason.
            metrics.failed(issued.kind());
            log.warn("Risk override action dispatch failed: {}", failure.getClass().getSimpleName());
            return Optional.empty();
        }
        boolean recorded = transactions.inTransaction(uow -> actions.conclude(uow, issued, outcome, actor, correlation));
        if (recorded) {
            metrics.concluded(issued.kind(), outcome);
        }
        return Optional.of(outcome);
    }
}
