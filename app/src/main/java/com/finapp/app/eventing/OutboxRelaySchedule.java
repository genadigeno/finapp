package com.finapp.app.eventing;

import com.finapp.platform.outbox.OutboxRelay;
import com.finapp.platform.outbox.RelayPollResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Runs {@link OutboxRelay#pollOnce()} on a fixed delay, on every instance (`P2-TSK-001`).
 *
 * <h2>Every instance polls, deliberately — there is no leader</h2>
 *
 * <p>{@code nothingSchedulesAmbiently} (ADR-0024) forbids bare schedulers because <em>"a job
 * with no lease runs N times"</em>, and its stated bar is that a scheduled process be idempotent
 * per period <strong>or take an explicit database lease</strong>. The relay is the case the rule
 * carves out: each poll takes a transaction-scoped advisory lock <strong>per aggregate</strong>
 * in PostgreSQL (`P0-TSK-020`), so ten instances polling concurrently drain disjoint aggregates
 * and a poll that wins no locks does nothing. N pollers is the throughput model, not a hazard —
 * which is why this class is a named exemption in the rule with its register row in
 * {@code DISTRIBUTED_EXECUTION.md} §3, proven load-bearing rather than decorative.
 *
 * <h2>What this class must never become</h2>
 *
 * <p>A coordinator. It holds no state the relay depends on; the executor and the running flag
 * are per-instance mechanics whose loss costs this instance's polling and nothing else. Any
 * future scheduled work that is <em>not</em> lease-protected does not get to ride on this
 * exemption — the register row names this class and the lease that justifies it.
 *
 * <h2>A failed poll is logged and the schedule continues</h2>
 *
 * <p>The application starts and stays up with the database or the broker unreachable
 * (ADR-0016's reasoning): the failure class is logged — never event content or a broker address
 * ({@code INV-AUD-02}) — and the next tick tries again. Per-event failures inside a successful
 * poll are already the relay's own backoff machinery.
 */
@Slf4j
public final class OutboxRelaySchedule implements SmartLifecycle {

    private final OutboxRelay relay;
    private final Duration pollInterval;
    private final Counter published;
    private final Counter failed;
    private final Counter deadLettered;

    private ScheduledExecutorService executor;

    public OutboxRelaySchedule(OutboxRelay relay, Duration pollInterval, MeterRegistry registry) {
        this.relay = Objects.requireNonNull(relay, "relay must not be null");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive: " + pollInterval);
        }
        // Registered EAGERLY, one per outcome (P1-TSK-029's rule): an alert on a publication
        // failure rate must have a series to evaluate on a freshly started instance, which is
        // exactly the instance whose relay might be misconfigured. This pays the "throughput,
        // failure and dead-letter counts" half of the relay-metrics debt row, which was waiting
        // for a relay that actually runs.
        this.published = outcome(registry, "published");
        this.failed = outcome(registry, "failed");
        this.deadLettered = outcome(registry, "deadlettered");
    }

    private static Counter outcome(MeterRegistry registry, String outcome) {
        return Counter.builder("finapp.outbox.publication")
                .tag("outcome", outcome)
                .description("Outbox publication attempts by outcome, summed over relay polls")
                .register(registry);
    }

    @Override
    public void start() {
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "outbox-relay");
                            thread.setDaemon(true);
                            return thread;
                        });
        executor.scheduleWithFixedDelay(
                this::pollQuietly, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void pollQuietly() {
        try {
            RelayPollResult result = relay.pollOnce();
            published.increment(result.published());
            failed.increment(result.failed());
            deadLettered.increment(result.deadLettered());
        } catch (RuntimeException failure) {
            // The class only: a JDBC or broker message can name hosts and identifiers.
            log.warn("Outbox poll failed: {}", failure.getClass().getSimpleName());
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
