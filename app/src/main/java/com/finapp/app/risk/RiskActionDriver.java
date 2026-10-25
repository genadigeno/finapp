package com.finapp.app.risk;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.ActionOutcome;
import com.finapp.risk.RiskAction;
import com.finapp.risk.RiskActionId;
import com.finapp.risk.RiskActions;
import com.finapp.risk.RiskSpans;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * T-act (`P13-TSK-017`; {@code PHASE_13_PLAN.md} sections 7.1 T-act, 7.2 (R6), 7.5; ADR-0112 decisions 4-6;
 * {@code INV-RSK-02}, {@code INV-IDEM-01}) - the drive of an approved risk action through its owning domain, in three
 * steps and the owner's command in none of risk's transactions:
 *
 * <ol>
 *   <li><strong>the claim</strong> - {@link RiskActions#claim} for the approval door's own action, after its approval
 *       committed (and again on every replay of its key), or {@link RiskActions#claimNextDue} for
 *       {@link RiskActionSchedule}: (R6) {@code FOR UPDATE SKIP LOCKED} on a permit that has come, moved a lease ahead,
 *       the attempt counted, <strong>committed</strong> - so ten instances claim it once per lease;</li>
 *   <li><strong>the dispatch</strong> - the owner's keyed door ({@code risk.action:<id>}, the action's reason code) on
 *       <strong>no transaction</strong> ({@link RiskTransactions} is Spring's default propagation: calling the port
 *       inside it would join the owner's transactions to a risk one), holding no risk row;</li>
 *   <li><strong>the record</strong> - the owner's answer by its conditional edge, once, whoever records first.</li>
 * </ol>
 *
 * <p><strong>The platform executes; a person decided</strong> - every drive runs under
 * {@link SecurityContext#enterSystem()} ({@link #asThePlatform}, the one enumerated site): the owners' audit names the
 * system as the executor, with the action's reason code and correlation, while risk's own records name the proposer and
 * the approver ({@code risk.ActionProposed}, {@code risk.ActionApproved}). This is the fix of the defect `-016`'s gate
 * routed here: `-012`'s approval door dispatched payments' held door under the operator's session actor, which the
 * session interceptor types {@code CUSTOMER} and payments refuses (its held doors admit the platform or an employee), so
 * the first attempt always threw and only the leg's re-drive completed it. Payments' door is not widened.
 *
 * <p>A dispatch that throws records nothing: counted {@code finapp.risk.action.failed{kind}}, one warn line with the
 * exception's class only, and the permit left standing for the next claim under the same key. A record that fails to
 * commit after the owner answered is the same: counted, nothing recorded, re-driven under the same key - the owner
 * replays its answer, so the effect is still one.
 */
@Slf4j
public final class RiskActionDriver {

    private final RiskActions actions;
    private final RiskTransactions transactions;
    private final RiskActionMetrics metrics;
    private final RiskFlowScope flow;
    private final Duration lease;
    private final int perTick;

    /**
     * @param lease how long a claimed action's permit is held before another claimer may take it - longer than the
     *     slowest owner's door, so a live attempt is not raced
     * @param perTick the most due actions one schedule tick drives
     */
    public RiskActionDriver(RiskActions actions, RiskTransactions transactions, RiskActionMetrics metrics,
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

    /**
     * {@code id} driven when its permit has come - the approval door's drive, after its approval's transaction has
     * committed, under the door's correlation. A failed claim is counted and left to the schedule: the approval stands.
     *
     * @return the outcome this drive recorded; empty when nothing was due, another claimer holds it, or the dispatch threw
     */
    public Optional<ActionOutcome> driveIfDue(RiskActionId id) {
        Objects.requireNonNull(id, "id");
        return asThePlatform(() -> {
            Optional<RiskAction> claimed;
            try {
                claimed = transactions.inTransaction(uow -> actions.claim(uow, id, lease));
            } catch (RuntimeException failure) {
                metrics.claimFailed();
                log.warn("Risk action claim failed: {}", failure.getClass().getSimpleName());
                return Optional.empty();
            }
            return claimed.flatMap(this::drive);
        });
    }

    /**
     * The schedule's tick: up to the bound, the oldest due action claimed in a transaction of its own, then dispatched
     * and recorded - each under its own action's correlation. A failed claim propagates (the schedule counts it and ends
     * the tick); a failed dispatch is counted here and the tick goes on.
     *
     * @return how many actions this tick drove to a recorded outcome
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public int driveDue() {
        return asThePlatform(() -> {
            int concluded = 0;
            for (int taken = 0; taken < perTick; taken++) {
                Optional<RiskAction> claimed = transactions.inTransaction(uow -> actions.claimNextDue(uow, lease));
                if (claimed.isEmpty()) {
                    return concluded;
                }
                CorrelationId correlation = CorrelationId.of(claimed.get().correlationId());
                try (CorrelationContext.Scope scope = CorrelationContext.enter(Correlation.startingWith(correlation))) {
                    if (drive(claimed.get()).isPresent()) {
                        concluded++;
                    }
                }
            }
            return concluded;
        });
    }

    /** The lease a claim moves the permit by. */
    public Duration lease() {
        return lease;
    }

    private Optional<ActionOutcome> drive(RiskAction claimed) {
        ActionOutcome outcome;
        try {
            outcome = flow.sweep(RiskSpans.ACTION_DISPATCH, () -> actions.dispatch(claimed));
        } catch (RuntimeException failure) {
            // Nothing recorded: the permit stands and the next claim re-drives the same key. The class only - never a
            // target, a key or a reason.
            metrics.failed(claimed.kind());
            log.warn("Risk action dispatch failed: {}", failure.getClass().getSimpleName());
            return Optional.empty();
        }
        CorrelationId correlation = CorrelationContext.current().map(Correlation::correlationId)
                .orElseGet(() -> CorrelationId.of(claimed.correlationId()));
        Optional<RiskAction> recorded;
        try {
            recorded = transactions.inTransaction(uow -> actions.conclude(uow, claimed, outcome, correlation));
        } catch (RuntimeException failure) {
            // The owner answered but the record did not commit: the same as a dispatch that threw - nothing recorded,
            // the permit stands, and the next claim re-drives the same key, the owner replaying its answer. Never a
            // failed approval door (the approval committed) nor a schedule tick ended as if its claim had failed.
            metrics.failed(claimed.kind());
            log.warn("Risk action record failed: {}", failure.getClass().getSimpleName());
            return Optional.empty();
        }
        recorded.ifPresent(concluded -> metrics.concluded(concluded.kind(), outcome));
        return recorded.map(concluded -> outcome);
    }

    /**
     * {@code work} as the platform - the one place the action machine claims to be the acting party: a person decided
     * the action (risk's records name them); the platform executes it through the owner's door.
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    private static <T> T asThePlatform(Supplier<T> work) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            return work.get();
        }
    }
}
