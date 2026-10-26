package com.finapp.app.risk;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * The risk action schedule (`P13-TSK-017`; {@code PHASE_13_PLAN.md} sections 7.1 T-act, 7.5; {@code RISK_LIFECYCLES.md}
 * section 4.7; ADR-0112 decisions 4-5) - the at-least-once drive of approved risk actions: an approval whose instance
 * died between its commit and its own drive, a dispatch that threw, a record that was lost. Every instance runs it with
 * no leader: each tick claims the oldest due permit {@code FOR UPDATE SKIP LOCKED} in a transaction of its own (R6),
 * re-stamped a lease ahead and committed before the owner's keyed door is called on no transaction under
 * {@code risk.action:<id>} as the platform ({@link RiskActionDriver}), then records the answer once - so ten instances
 * claim a due action once per lease, and any overlap past a lease presents the same key and is one effect by the
 * owner's idempotency ({@code RiskActionDatabaseTest#tenSweepersDispatchOnce}). Up to
 * {@code finapp.risk.action.actions-per-tick} actions a tick; a failed claim is counted
 * ({@code finapp.risk.sweeper.claim.failed{kind=action}}, N5) and ends the tick; a failed dispatch is counted
 * ({@code finapp.risk.action.failed{kind}}) and the tick goes on. Off in test contexts
 * ({@code finapp.risk.action.sweeper.enabled}); the suites drive {@link #sweepOnce()}.
 *
 * <p>It takes over `-012`'s override action leg on {@code RiskMonitoringSchedule}: the override's issued action is a
 * {@code risk_action} row since {@code risk V013}, under the override's own id, so its key is unchanged.
 *
 * <p><strong>The report leg</strong> (`P13-TSK-021`, {@code DESIGN-021} D15 - the second leg the plan's table gives this
 * schedule, landed with `-021` because `-017` merged first): after the action leg, each tick drives up to
 * {@code finapp.risk.filing.reports-per-tick} {@code FILED} regulatory reports whose permit has come
 * ({@link RegulatoryFilingDriver#inquireDue}: claimed {@code FOR UPDATE SKIP LOCKED} and committed, then asked of the
 * authority on no transaction as the platform - inquired by the filing reference, never filed twice - and the answer
 * recorded once). Its failed claims are counted {@code finapp.risk.sweeper.claim.failed{kind=report}} by the driver,
 * which never throws, so a failing leg ends only itself; a failing action claim ends the action leg, never the report
 * leg. No new scheduler: {@code LEASE_PROTECTED_SCHEDULERS} is unchanged.
 *
 * <p><strong>The rescreening leg</strong> (`P13-TSK-022`, moved here from {@code RiskMonitoringSchedule} when the owner's
 * decision O1 gave kyc a re-screening door: the leg now makes kyc's provider calls, and this is the schedule whose
 * legs make external calls - the monitoring schedule makes none): last in each tick, {@link RescreeningDriver#driveDue}
 * - an activated screening list version's enqueue in bounded batches (the version row {@code SKIP LOCKED}, a page of
 * the parties to rescreen, one permit each born once, the cursor advanced, one transaction a batch), then up to
 * {@code finapp.risk.rescreening.permits-per-tick} due permits in three steps: the claim committed, kyc asked on no
 * risk transaction and no connection held across the provider's bounded call, the conclusion conditional on the
 * claimed attempt. It counts its own failures ({@code claim.failed{kind=rescreening}}) and never throws; a defect that
 * escapes it is counted here and ends only it. Still no new scheduler: {@code LEASE_PROTECTED_SCHEDULERS} is unchanged.
 */
@Slf4j
public final class RiskActionSchedule implements SmartLifecycle {

    private final RiskActionDriver driver;
    private final RegulatoryFilingDriver filings;
    private final RescreeningLeg rescreening;
    private final RiskActionMetrics metrics;
    private final Duration pollInterval;
    private ScheduledExecutorService executor;

    /**
     * The rescreening leg (`P13-TSK-022`) - {@link RescreeningDriver} in production: its step, and where a defect that
     * escapes it is counted (its own {@code claim.failed{kind=rescreening}}, never the action leg's).
     */
    public interface RescreeningLeg {

        /** No rescreening leg - the earlier tasks' shape, kept for their suites. */
        RescreeningLeg NONE = new RescreeningLeg() {
            @Override
            public int rescreen() {
                return 0;
            }

            @Override
            public void failed() {
                // Nothing runs, nothing fails.
            }
        };

        /** The enqueue batches and the due permits driven, bounded per tick; how many permits were concluded. */
        int rescreen();

        /** Counts a defect that escaped {@link #rescreen} - the leg counts its own failures. */
        void failed();
    }

    public RiskActionSchedule(RiskActionDriver driver, RegulatoryFilingDriver filings, RiskActionMetrics metrics,
            Duration pollInterval) {
        this(driver, filings, RescreeningLeg.NONE, metrics, pollInterval);
    }

    /**
     * @param rescreening the rescreening leg - the {@link RescreeningDriver} in production (`P13-TSK-022`): last in each
     *     tick, after the report leg
     */
    public RiskActionSchedule(RiskActionDriver driver, RegulatoryFilingDriver filings, RescreeningLeg rescreening,
            RiskActionMetrics metrics, Duration pollInterval) {
        this.rescreening = Objects.requireNonNull(rescreening, "rescreening");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.filings = Objects.requireNonNull(filings, "filings");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("the poll interval must be positive");
        }
    }

    /**
     * One tick: the due actions driven, bounded - a failed claim counted and the action leg ended - then the due
     * {@code FILED} reports asked of the authority, bounded (the report leg, `P13-TSK-021`), then the rescreening leg
     * (`P13-TSK-022`), bounded.
     *
     * @return how many actions, report answers and rescreening conclusions this tick recorded
     */
    public int sweepOnce() {
        return actions() + filings.inquireDue() + rescreenings();
    }

    /**
     * The rescreening leg (`P13-TSK-022`): it counts its own failures ({@code claim.failed{kind=rescreening}}) and never
     * throws; a defect that escapes it is counted here and ends only it.
     */
    private int rescreenings() {
        try {
            int done = rescreening.rescreen();
            if (done > 0) {
                log.debug("Rescreening permits concluded: {}", done);
            }
            return done;
        } catch (RuntimeException failure) {
            rescreening.failed();
            log.warn("Rescreening leg failed: {}", failure.getClass().getSimpleName());
            return 0;
        }
    }

    private int actions() {
        try {
            int driven = driver.driveDue();
            if (driven > 0) {
                log.debug("Risk actions driven: {}", driven);
            }
            return driven;
        } catch (RuntimeException failure) {
            // The claim rolled back: nothing was claimed for certain and a later tick claims again. The class only.
            metrics.claimFailed();
            log.warn("Risk action claim failed: {}", failure.getClass().getSimpleName());
            return 0;
        }
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "risk-actions");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::sweepQuietly, pollInterval.toMillis(), pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void sweepQuietly() {
        try {
            sweepOnce();
        } catch (RuntimeException failure) {
            log.warn("Risk action sweep failed: {}", failure.getClass().getSimpleName());
        }
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null && !executor.isShutdown();
    }
}
