package com.finapp.app.eventing;

import com.finapp.app.lifecycle.ScheduleDrain;
import com.finapp.app.lifecycle.TickPhase;
import com.finapp.app.lifecycle.ShutdownOrder;
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
 * Runs {@link OutboxRelay#pollOnce()} on a fixed delay, on every instance (`P2-TSK-001`), and again at once while a cycle
 * comes back with its batch full and nothing failed (`P16-TSK-013`, ADR-0136: {@code tick()}).
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

    private final Poll relay;
    private final Duration pollInterval;
    private final Counter published;
    private final Counter failed;
    private final Counter deadLettered;

    private final ScheduleDrain drain = new ScheduleDrain("outbox-relay");

    /** One relay cycle: {@link OutboxRelay#pollOnce()} in production, a scripted one in the schedule's own test. */
    @FunctionalInterface
    interface Poll {
        RelayPollResult pollOnce();
    }

    public OutboxRelaySchedule(OutboxRelay relay, Duration pollInterval, MeterRegistry registry) {
        this(Objects.requireNonNull(relay, "relay must not be null")::pollOnce, pollInterval, registry);
    }

    OutboxRelaySchedule(Poll relay, Duration pollInterval, MeterRegistry registry) {
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
        ScheduledExecutorService executor =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, drain.name());
                            thread.setDaemon(true);
                            return thread;
                        });
        executor.scheduleWithFixedDelay(
                this::tick, TickPhase.firstDelayMillis(pollInterval), pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        drain.started(executor);
    }

    /**
     * One tick: a relay cycle, and another at once for as long as each comes back with its batch full and nothing failed
     * (`P16-TSK-013`, ADR-0136). A fixed delay after every cycle capped each instance at one batch an interval, behind the
     * target's checkout peak with no resource near its limit ({@code CAPACITY_MODEL.md} §3.4). A short cycle, a failed attempt
     * or a thrown cycle ends the tick, so an idle outbox is polled once an interval and a broker in trouble is not hammered:
     * each row keeps its own backoff. Before each further cycle the tick asks whether its stop has begun or its hold come on,
     * and ends there ({@link ScheduleDrain}); the cycle in flight finishes as any tick's does. Nothing here touches what makes
     * ten instances safe - the per-aggregate lock, the read inside it, the order and the conditional mark are the relay's.
     */
    void tick() {
        try {
            RelayPollResult result;
            do {
                result = relay.pollOnce();
                published.increment(result.published());
                failed.increment(result.failed());
                deadLettered.increment(result.deadLettered());
            } while (result.batchFull() && !drain.stopping() && !drain.held());
        } catch (RuntimeException failure) {
            // The class only: a JDBC or broker message can name hosts and identifiers.
            log.warn("Outbox poll failed: {}", failure.getClass().getSimpleName());
        }
    }

    /** This schedule's drain: its name is what a stopping instance's log calls it. */
    public ScheduleDrain drain() {
        return drain;
    }

    /** No new tick starts, and the tick in flight finishes within its bound ({@link ScheduleDrain}). */
    @Override
    public void stop() {
        drain.stop();
    }

    /** Spring's form: no new tick starts, and it calls back once the tick in flight has drained, so a phase drains in parallel. */
    @Override
    public void stop(Runnable callback) {
        drain.stop(callback);
    }

    @Override
    public boolean isRunning() {
        return drain.isRunning();
    }

    /** After the HTTP drain, before the consumers ({@link ShutdownOrder}). */
    @Override
    public int getPhase() {
        return ShutdownOrder.SCHEDULES;
    }

    /** The longest {@link #stop()} takes, below {@code spring.lifecycle.timeout-per-shutdown-phase} ({@code ShutdownOrderTest}). */
    public Duration stopBound() {
        return drain.stopBound();
    }
}
