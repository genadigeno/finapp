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
 * {@code risk_action} row since {@code risk V013}, under the override's own id, so its key is unchanged. `-021` adds the
 * {@code FILED} reports due an inquiry.
 */
@Slf4j
public final class RiskActionSchedule implements SmartLifecycle {

    private final RiskActionDriver driver;
    private final RiskActionMetrics metrics;
    private final Duration pollInterval;
    private ScheduledExecutorService executor;

    public RiskActionSchedule(RiskActionDriver driver, RiskActionMetrics metrics, Duration pollInterval) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("the poll interval must be positive");
        }
    }

    /**
     * One tick: the due actions driven, bounded; a failed claim counted and the tick ended.
     *
     * @return how many actions this tick drove to a recorded outcome
     */
    public int sweepOnce() {
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
