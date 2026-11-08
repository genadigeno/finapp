package com.finapp.app.eventing;

import com.finapp.app.lifecycle.BoundedStop;
import com.finapp.app.lifecycle.ShutdownOrder;
import com.finapp.platform.inbox.InboxEventHandler;
import com.finapp.platform.inbox.kafka.KafkaEventReceiver;
import com.finapp.platform.inbox.kafka.ReceiverPollResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Runs one consumer loop per consuming module, on every instance (`P2-TSK-002`).
 *
 * <h2>One consumer group per consuming module, derived rather than declared</h2>
 *
 * <p>Handlers are grouped by the module segment of their {@code consumerName()}
 * ({@code "kyc.caseOpening"} → group {@code finapp.kyc}), so each module's consumption
 * progresses — and lags — independently, and extracting a module later takes its group and its
 * offsets with it. With no handlers registered there is nothing to consume and nothing starts,
 * which is this task's own state: the first production handler is {@code P2-TSK-007}'s.
 *
 * <h2>No scheduler, no lease — and why that is not the relay's exemption</h2>
 *
 * <p>Each loop is a plain thread calling {@code pollOnce()} — deliberately not a
 * {@code ScheduledExecutorService}, so {@code nothingSchedulesAmbiently} (ADR-0024) has nothing
 * to exempt: the poll's own bounded blocking is the pacing. N instances all run the loops, and
 * that needs no lease because the work-sharing is Kafka's group protocol and the
 * <em>correctness</em> is the inbox primary key in PostgreSQL — the register row in
 * {@code DISTRIBUTED_EXECUTION.md} §3 states which of those two is load-bearing. Everything
 * held here — threads, the running flag — is per-instance mechanics whose loss costs this
 * instance's consumption and nothing else; broker-held offsets are transport bookkeeping whose
 * loss costs a replay the dedupe absorbs.
 *
 * <h2>The counters are eager, and they pay a debt row</h2>
 *
 * <p>{@code finapp.inbox.consumption} by outcome, registered at construction
 * ({@code P1-TSK-029}'s rule: an alert on a rising duplicate or contention rate must have a
 * series on a freshly started instance). This pays the inbox-metrics debt row, whose recorded
 * trigger — <em>"the first live consumer"</em> — is this task.
 *
 * <h2>A stopping instance's consumers commit and leave (`P15-TSK-008`)</h2>
 *
 * <p>In {@link ShutdownOrder#CONSUMERS}, after the schedules: every loop is woken at once, so none takes a new batch; a
 * loop inside a batch finishes it and acknowledges it ({@code KafkaEventReceiver} retries the commit the wakeup
 * interrupted), then closes its receiver, which leaves the group, so the partitions move to another instance now rather
 * than at the session timeout. Every loop is awaited against one bound, {@link #SETTLE_BOUND}; past it the loops are
 * interrupted and awaited {@link #INTERRUPT_GRACE} more, and one still running is abandoned on its daemon thread - its
 * unacknowledged records redeliver into the inbox's dedupe, so correctness never depends on the drain.
 */
@Slf4j
public final class InboxConsumers implements SmartLifecycle, BoundedStop {

    /** How long a stopping instance lets its loops finish the batch they hold, all of them together. */
    public static final Duration SETTLE_BOUND = Duration.ofSeconds(10);

    /** How long it then waits for the loops it interrupted. */
    public static final Duration INTERRUPT_GRACE = Duration.ofSeconds(2);

    /** Builds the receiver for one module's group; the composition root supplies the strings. */
    @FunctionalInterface
    public interface ReceiverFactory {
        KafkaEventReceiver connect(String groupId, List<InboxEventHandler> handlers);
    }

    private final Map<String, List<InboxEventHandler>> handlersByModule;
    private final ReceiverFactory receivers;
    private final Duration failureBackoff;
    private final Counter processed;
    private final Counter duplicates;
    private final Counter contended;
    private final Counter failed;

    private final List<Loop> loops = new ArrayList<>();
    private volatile boolean running;

    public InboxConsumers(
            List<InboxEventHandler> handlers,
            ReceiverFactory receivers,
            Duration failureBackoff,
            MeterRegistry registry) {
        this.handlersByModule = byModule(Objects.requireNonNull(handlers, "handlers"));
        this.receivers = Objects.requireNonNull(receivers, "receivers must not be null");
        this.failureBackoff = Objects.requireNonNull(failureBackoff, "failureBackoff");
        if (failureBackoff.isNegative() || failureBackoff.isZero()) {
            throw new IllegalArgumentException("failureBackoff must be positive: " + failureBackoff);
        }
        this.processed = outcome(registry, "processed");
        this.duplicates = outcome(registry, "duplicate");
        this.contended = outcome(registry, "contended");
        this.failed = outcome(registry, "failed");
    }

    /** The group key: everything before the first dot, or the whole name if it has none. */
    static Map<String, List<InboxEventHandler>> byModule(List<InboxEventHandler> handlers) {
        Map<String, List<InboxEventHandler>> byModule = new LinkedHashMap<>();
        for (InboxEventHandler handler : handlers) {
            String name = handler.consumerName();
            int dot = name.indexOf('.');
            String module = dot < 0 ? name : name.substring(0, dot);
            byModule.computeIfAbsent(module, ignored -> new ArrayList<>()).add(handler);
        }
        return byModule;
    }

    static Counter outcome(MeterRegistry registry, String outcome) {
        return Counter.builder("finapp.inbox.consumption")
                .tag("outcome", outcome)
                .description("Inbox deliveries by outcome, summed over consumer polls")
                .register(registry);
    }

    @Override
    public void start() {
        running = true;
        handlersByModule.forEach(
                (module, moduleHandlers) -> {
                    Loop loop = new Loop(module, receivers.connect("finapp." + module, moduleHandlers));
                    loops.add(loop);
                    loop.thread.start();
                });
    }

    @Override
    public void stop() {
        running = false;
        if (loops.isEmpty()) {
            return;
        }
        log.info("Inbox consumers stopping: {} loops", loops.size());
        // Out of a blocking poll at once: no loop takes a new batch. A loop inside one finishes and acknowledges it.
        for (Loop loop : loops) {
            loop.receiver.wakeup();
        }
        CompletableFuture<Void> closed =
                CompletableFuture.allOf(loops.stream().map(loop -> loop.closed).toArray(CompletableFuture<?>[]::new));
        try {
            if (!awaited(closed, SETTLE_BOUND)) {
                log.warn("Inbox consumers did not settle within {}; their loops are interrupted", SETTLE_BOUND);
                for (Loop loop : loops) {
                    loop.thread.interrupt();
                }
                if (!awaited(closed, INTERRUPT_GRACE)) {
                    log.warn("Inbox consumers still running {} after their interrupt; abandoned to the process's exit",
                            INTERRUPT_GRACE);
                }
            }
            log.info("Inbox consumers stopped");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            loops.clear();
        }
    }

    private static boolean awaited(CompletableFuture<Void> closed, Duration bound) throws InterruptedException {
        try {
            closed.get(bound.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException stillRunning) {
            return false;
        } catch (ExecutionException unreachable) {
            // A loop's future is only ever completed, never failed: it completes in a finally.
            return true;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** After the schedules, before the management server closes ({@link ShutdownOrder}). */
    @Override
    public int getPhase() {
        return ShutdownOrder.CONSUMERS;
    }

    @Override
    public Duration stopBound() {
        return SETTLE_BOUND.plus(INTERRUPT_GRACE);
    }

    private final class Loop {
        private final KafkaEventReceiver receiver;
        private final Thread thread;
        /** Completed once the receiver is closed: what a stopping instance waits for. */
        private final CompletableFuture<Void> closed = new CompletableFuture<>();

        private Loop(String module, KafkaEventReceiver receiver) {
            this.receiver = receiver;
            this.thread = new Thread(this::run, "inbox-consumer-" + module);
            this.thread.setDaemon(true);
        }

        private void run() {
            try {
                while (running) {
                    tick();
                }
            } finally {
                try {
                    receiver.close();
                } finally {
                    closed.complete(null);
                }
            }
        }

        private void tick() {
            try {
                ReceiverPollResult result = receiver.pollOnce();
                processed.increment(result.processed());
                duplicates.increment(result.duplicates());
                contended.increment(result.contended());
                failed.increment(result.failed());
                if (result.failed() > 0 || result.contended() > 0) {
                    // The stalled partition will be re-polled immediately; without a pause a
                    // poison record turns the loop hot. Bounded, so a transient failure
                    // retries soon and a persistent one costs a bounded rate, loudly logged
                    // by the receiver each time.
                    pause();
                }
            } catch (RuntimeException failure) {
                // The class only: a client or JDBC message can name hosts and identifiers.
                log.warn(
                        "Inbox poll failed on {}: {}",
                        Thread.currentThread().getName(),
                        failure.getClass().getSimpleName());
                pause();
            }
        }

        private void pause() {
            try {
                Thread.sleep(failureBackoff.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
