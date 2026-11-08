package com.finapp.app.lifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * How one schedule lets go of its executor when its instance stops (`P15-TSK-008`, ADR-0120 decision 4).
 *
 * <ol>
 *   <li><strong>No new tick starts.</strong> {@code shutdown()} on the caller's thread: a scheduled executor cancels its
 *       periodic ticks, so no tick starts after the call, on this instance. A tick that claims in a loop of its own asks
 *       {@link #stopping()} before each further claim and ends there (`P15-TSK-009`). A tick that claims once finishes what
 *       it claimed. A sweep paging inside a module, which cannot see this class, runs on to its end or to the bound: safe,
 *       each of its claims is leased or a transaction of its own.
 *   <li><strong>The tick in flight finishes, within {@link #BOUND}.</strong> A tick's batch is its unit of work - the rows it
 *       claimed under a lease, a lock or {@code SKIP LOCKED} - and letting it end is what spares the other instances a
 *       retry once that claim lapses.
 *   <li><strong>Past the bound it is interrupted</strong>, as every schedule's stop was before this task, and awaited for
 *       {@link #INTERRUPT_GRACE} more. A tick still running then is abandoned to the process's exit on its daemon thread.
 * </ol>
 *
 * <p><strong>Correctness never depends on the drain.</strong> An interrupted or abandoned tick's claim is taken by another
 * instance once its lease lapses or its transaction rolls back - the same path a killed process takes. The drain only
 * reduces retries.
 *
 * <p>{@link #stop(Runnable)} is the form Spring calls: it ends the ticks on Spring's thread and waits on a thread of its own,
 * so the schedules of one phase drain in parallel and the phase costs one bound, not one per schedule. Process-local
 * mechanics, like the executor itself: losing them costs this instance's ticks and nothing else.
 */
@Slf4j
public final class ScheduleDrain {

    /** How long a stopping schedule lets the tick already running finish. */
    public static final Duration BOUND = Duration.ofSeconds(10);

    /** How long it then waits for the tick it interrupted. */
    public static final Duration INTERRUPT_GRACE = Duration.ofSeconds(2);

    private final String name;
    private final Duration bound;
    private final Duration grace;
    private volatile ExecutorService executor;

    /**
     * @param name the schedule's thread name: what a stopping instance's log calls it
     */
    public ScheduleDrain(String name) {
        this(name, BOUND, INTERRUPT_GRACE);
    }

    /**
     * @param name the schedule's thread name
     * @param bound how long the tick in flight may take to finish
     * @param grace how long an interrupted tick is then awaited
     */
    public ScheduleDrain(String name, Duration bound, Duration grace) {
        this.name = Objects.requireNonNull(name, "name");
        this.bound = positive(bound, "bound");
        this.grace = positive(grace, "grace");
    }

    private static Duration positive(Duration duration, String what) {
        Objects.requireNonNull(duration, what);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(what + " must be positive: " + duration);
        }
        return duration;
    }

    /** The schedule's thread name. */
    public String name() {
        return name;
    }

    /**
     * Takes the executor the schedule has just started its ticks on.
     *
     * @param started the schedule's executor
     */
    public void started(ExecutorService started) {
        this.executor = Objects.requireNonNull(started, "started");
    }

    /** @return whether the schedule still claims: started, and not yet stopping */
    public boolean isRunning() {
        ExecutorService running = executor;
        return running != null && !running.isShutdown();
    }

    /**
     * Whether this schedule's stop has begun. A tick between two claims asks this and takes no further one, so the rest wait
     * for the next claim on any instance, as a failed step's do. It decides nothing else: every claim stays arbitrated in
     * PostgreSQL, and a schedule that {@link #started} was never told of is never stopping - the suites drive one directly.
     *
     * @return whether the schedule was started and its stop has begun
     */
    public boolean stopping() {
        ExecutorService running = executor;
        return running != null && running.isShutdown();
    }

    /** @return the longest {@link #stop()} takes: the bound, then the interrupt's grace */
    public Duration stopBound() {
        return bound.plus(grace);
    }

    /** No new tick starts, then this waits for the tick in flight - see the class's three steps. */
    public void stop() {
        ExecutorService running = executor;
        if (running == null) {
            return;
        }
        running.shutdown();
        drain(running);
    }

    /**
     * Ends the ticks on the caller's thread, then waits for the tick in flight on a thread of its own and calls back.
     *
     * @param callback Spring's: the phase waits for it
     */
    public void stop(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        ExecutorService running = executor;
        if (running == null) {
            callback.run();
            return;
        }
        running.shutdown();
        Thread drainer = new Thread(() -> {
            try {
                drain(running);
            } finally {
                callback.run();
            }
        }, name + "-drain");
        drainer.setDaemon(true);
        drainer.start();
    }

    private void drain(ExecutorService running) {
        try {
            if (running.awaitTermination(bound.toMillis(), TimeUnit.MILLISECONDS)) {
                log.debug("Schedule {} drained", name);
                return;
            }
            // The tick outlived its bound: interrupted, as before this task, and its claim lapses to another instance.
            log.warn("Schedule {} did not drain within {}; its tick is interrupted", name, bound);
            running.shutdownNow();
            if (!running.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Schedule {} still running {} after its interrupt; abandoned to the process's exit", name, grace);
            }
        } catch (InterruptedException interrupted) {
            running.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
