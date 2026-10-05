package com.finapp.app.fx;

import com.finapp.fx.FxCoverDispatch;
import com.finapp.platform.security.SecurityContext;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * The cover's sweep (`P9-TSK-012`; ADR-0077 sections 3-4, PHASE_9_PLAN.md section 8): send,
 * re-send, inquire and requote. Every instance runs it with no lease: each tick claims the due
 * covers in ONE statement - the permit renewed by {@code statement_timestamp()}, rows another
 * sweeper holds skipped, re-judged on the locked row - so ten sweepers advance ten disjoint sets,
 * and a re-send another instance raced is harmless by the provider's dedupe on {@code T}. The
 * instance's own clock decides nothing. Off in test contexts
 * ({@code finapp.fx.cover.sweeper.enabled}); the suites drive {@link #sweepOnce()}.
 */
@Slf4j
public final class FxCoverSchedule implements SmartLifecycle {

    private final FxCoverDispatch dispatch;
    private final Duration pollInterval;
    private ScheduledExecutorService executor;

    public FxCoverSchedule(FxCoverDispatch dispatch, Duration pollInterval) {
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch must not be null");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive: " + pollInterval);
        }
    }

    /**
     * One sweep under the system actor.
     *
     * @return how many covers this call claimed
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public int sweepOnce() {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            return dispatch.sweepOnce(SecurityContext.require());
        }
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fx-cover-sweep");
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
            // The next tick retries; the class name only - a JDBC message can carry values.
            log.warn("FX cover sweep failed: {}", failure.getClass().getSimpleName());
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
