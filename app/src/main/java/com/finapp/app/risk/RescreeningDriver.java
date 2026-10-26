package com.finapp.app.risk;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.PartyRescreening;
import com.finapp.risk.RescreeningPermit;
import com.finapp.risk.RescreeningRun;
import com.finapp.risk.RiskSpans;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * The rescreening leg of {@code RiskActionSchedule} (`P13-TSK-022`; moved from {@code RiskMonitoringSchedule} with kyc's
 * re-screening door, the leg now making kyc's provider calls; {@code PHASE_13_PLAN.md} sections 7.1 T-list, 7.5,
 * 12.10; {@code DESIGN-022} D3; {@code INV-RSK-06}, {@code INV-RSK-02}) - every instance, no lease, no leader, and the
 * platform the actor ({@link #asThePlatform}, the one enumerated site): a person activated the list version; the
 * platform rescreens.
 *
 * <ol>
 *   <li><strong>The enqueue</strong>: up to {@code enqueueBatchesPerTick} batches, each one transaction -
 *       {@link RescreeningRun#enqueueNextBatch}: a version in force with its enqueue due, {@code SKIP LOCKED}; a page of
 *       {@code ACTIVE} parties after its cursor; one permit each, born once; the cursor advanced - so an activation reaches
 *       every {@code ACTIVE} party once whatever the number of sweepers, and a crash re-runs its batch into the same keys.</li>
 *   <li><strong>The permits</strong>: up to {@code permitsPerTick}, each in three steps - the claim ({@code SKIP LOCKED},
 *       a lease ahead, an attempt, committed), kyc asked on <strong>no risk transaction</strong> through
 *       {@link PartyRescreening} under the permit's key and the activation's correlation, the conclusion in a transaction
 *       of its own, conditional on the claimed attempt.</li>
 * </ol>
 *
 * <p>The leg never throws into the schedule: an enqueue batch, a claim or a conclusion that fails rolls back, is counted
 * ({@code finapp.risk.sweeper.claim.failed{kind=rescreening}}) and ends its loop for the tick with one warn line - the
 * exception's class, never a party, a list or a key. A conclusion that failed leaves the claimed permit to its lease: the
 * next claim asks again and records once - and a claim past the leg's bound fails the permit {@code UNCONCLUDED} without
 * asking, so a conclusion that always fails is bounded like an answer that never comes.
 */
@Slf4j
public final class RescreeningDriver implements RiskActionSchedule.RescreeningLeg {

    /** What one tick did. */
    public record Tick(int enqueued, int concluded) {

        /** Nothing due. */
        public static final Tick NOTHING = new Tick(0, 0);
    }

    private final RescreeningRun run;
    private final RiskTransactions transactions;
    private final RescreeningMetrics metrics;
    private final RiskFlowScope flow;
    private final Duration lease;
    private final int enqueueBatch;
    private final int enqueueBatchesPerTick;
    private final int permitsPerTick;

    /**
     * @param lease how long a claimed permit is held before another sweeper may take it - longer than kyc's slowest read
     * @param enqueueBatch the parties one enqueue transaction reaches
     * @param enqueueBatchesPerTick the enqueue batches one tick takes at most
     * @param permitsPerTick the permits one tick concludes at most
     */
    public RescreeningDriver(RescreeningRun run, RiskTransactions transactions, RescreeningMetrics metrics,
            RiskFlowScope flow, Duration lease, int enqueueBatch, int enqueueBatchesPerTick, int permitsPerTick) {
        this.run = Objects.requireNonNull(run, "run");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.flow = Objects.requireNonNull(flow, "flow");
        this.lease = Objects.requireNonNull(lease, "lease");
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("a rescreening permit's lease is positive");
        }
        if (enqueueBatch < 1 || enqueueBatchesPerTick < 1 || permitsPerTick < 1) {
            throw new IllegalArgumentException("the rescreening leg's bounds are positive");
        }
        this.enqueueBatch = enqueueBatch;
        this.enqueueBatchesPerTick = enqueueBatchesPerTick;
        this.permitsPerTick = permitsPerTick;
    }

    /** One tick of the leg, as the platform: the enqueue's batches, then the due permits. Never throws. */
    public Tick driveDue() {
        return asThePlatform(() -> new Tick(enqueue(), conclude()));
    }

    /** {@code RiskActionSchedule}'s step: one tick, how many permits it concluded. */
    @Override
    public int rescreen() {
        return driveDue().concluded();
    }

    /** A defect that escaped the leg, counted as its own: {@code claim.failed{kind=rescreening}}. */
    @Override
    public void failed() {
        metrics.claimFailed();
    }

    private int enqueue() {
        int born = 0;
        for (int taken = 0; taken < enqueueBatchesPerTick; taken++) {
            RescreeningRun.Enqueued batch;
            try {
                batch = flow.sweep(RiskSpans.RESCREEN,
                        () -> transactions.inTransaction(uow -> run.enqueueNextBatch(uow, enqueueBatch)));
            } catch (RuntimeException failure) {
                // The batch rolled back whole; the version's enqueue permit stands for a later tick. The class only.
                metrics.claimFailed();
                log.warn("Rescreening enqueue batch failed: {}", failure.getClass().getSimpleName());
                return born;
            }
            if (!batch.claimed()) {
                return born;
            }
            born += batch.born();
        }
        return born;
    }

    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    private int conclude() {
        int concluded = 0;
        for (int taken = 0; taken < permitsPerTick; taken++) {
            RescreeningRun.Claim claim;
            try {
                claim = flow.sweep(RiskSpans.RESCREEN,
                        () -> transactions.inTransaction(uow -> run.claimNextDue(uow, lease)));
            } catch (RuntimeException failure) {
                metrics.claimFailed();
                log.warn("Rescreening claim failed: {}", failure.getClass().getSimpleName());
                return concluded;
            }
            RescreeningPermit permit;
            switch (claim) {
                case RescreeningRun.Claim.Nothing nothing -> {
                    return concluded;
                }
                case RescreeningRun.Claim.Exhausted exhausted -> {
                    // Failed UNCONCLUDED by the claim itself, committed: concluded, and kyc not asked again.
                    concluded++;
                    continue;
                }
                case RescreeningRun.Claim.Claimed claimed -> permit = claimed.permit();
            }
            try (CorrelationContext.Scope scope = CorrelationContext.enter(
                    Correlation.startingWith(CorrelationId.of(permit.correlationId())))) {
                // kyc asked on no risk transaction - the claim has committed, no risk row is held.
                PartyRescreening.Answer answer = flow.sweep(RiskSpans.RESCREEN, () -> run.ask(permit, lease));
                Optional<RescreeningRun.Concluded> done;
                try {
                    done = flow.sweep(RiskSpans.RESCREEN,
                            () -> transactions.inTransaction(uow -> run.conclude(uow, permit, answer)));
                } catch (RuntimeException failure) {
                    // Nothing recorded; the permit stands to its lease and the next claim asks again. The class only.
                    metrics.claimFailed();
                    log.warn("Rescreening conclusion failed: {}", failure.getClass().getSimpleName());
                    return concluded;
                }
                if (done.isPresent()) {
                    concluded++;
                }
            }
        }
        return concluded;
    }

    /**
     * {@code work} as the platform - the leg's one acting party: a person recorded and activated the list version (risk's
     * audit names them); the platform rescreens.
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    static <T> T asThePlatform(Supplier<T> work) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            return work.get();
        }
    }
}
