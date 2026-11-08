package com.finapp.app.accounting;

import com.finapp.app.lifecycle.BoundedStop;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * The one thread on which every accounting gauge reads the database (`P14-TSK-020`'s independent review, I-1): a scrape must never wait on the pool for an accounting gauge, because a scrape that outlives Prometheus's timeout stores nothing, and the NaN an
 * unreadable gauge publishes would then never reach a rule. So {@link AccountingGaugeRead#current()} only looks at what this thread last read, and asks it for more.
 *
 * <ul>
 *   <li><strong>One daemon thread per instance</strong>, named {@value #THREAD}, closed with the context. It holds at most one connection of the pool, whatever every accounting gauge wants at once, and each gauge has at most one read queued. Process-local on purpose: a metric decides nothing.
 *   <li><strong>The first reads are asked for at start</strong> ({@link #start()}, before the web server's phase), so a healthy instance's first scrape holds numbers. A NaN in a {@code delta} window's first sample would blind {@code RegulatoryReturnOverdue} and {@code RegulatoryReturnOvertaken} for that window.
 *   <li><strong>A read that hangs holds the one thread</strong>, so every accounting gauge goes to no value within four of its floors. That is the right signal, and it is what the unreadable rules are for: nothing is being read. Only a restart frees a read the network black-holed, since the data source sets no {@code socketTimeout}.
 *   <li><strong>Its {@code finapp.db.connection} spans carry no correlation identifier</strong>, where a read on the scrape thread took the scrape's: a background reader has no ingress, and the platform does not fabricate one ({@code TracingTest#spansOutsideAFlowAreNotStamped}).
 * </ul>
 *
 * <p><strong>What this does not fix.</strong> About sixty other modules' metric classes still read on the scrape thread, so a pool with no free connection, or a database gone, still loses the whole scrape on every instance it touches: {@code CURRENT_STATE.md} section Known Architectural Debt.
 */
@Slf4j
public final class AccountingGaugeRefresher implements SmartLifecycle, BoundedStop {

    /** The thread's name: what a thread dump shows inside a read. */
    public static final String THREAD = "accounting-gauges";

    /** How long a stop waits for the read in flight: above its two-second statement timeout. */
    public static final Duration STOP_BOUND = Duration.ofSeconds(3);

    private final Executor executor;
    private final ExecutorService owned;
    private final boolean readAtStart;
    private final List<Runnable> asks = new CopyOnWriteArrayList<>();
    private volatile boolean running;

    /**
     * The production refresher: one daemon thread, started at its first read.
     *
     * @param readAtStart whether {@link #start()} asks for the first reads: {@code finapp.accounting.gauges.read-at-start}, true unless a test context turns this background reader off as it does every other
     */
    public AccountingGaugeRefresher(boolean readAtStart) {
        this.owned = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD);
            thread.setDaemon(true);
            return thread;
        });
        this.executor = owned;
        this.readAtStart = readAtStart;
    }

    /** A suite's: {@code Runnable::run}, so a metrics test reads in line. {@code AccountingGaugeWiringTest} holds that production is not wired so. */
    AccountingGaugeRefresher(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.owned = null;
        this.readAtStart = true;
    }

    /** A gauge's way to ask for its read: run at {@link #start()}, so the first reads come before the first scrape. */
    void register(Runnable ask) {
        asks.add(Objects.requireNonNull(ask, "ask"));
    }

    /**
     * Runs {@code read} on the one thread, after whatever is queued.
     *
     * @throws RejectedExecutionException once closed
     */
    void execute(Runnable read) {
        executor.execute(read);
    }

    @Override
    public void start() {
        running = true;
        if (readAtStart) {
            asks.forEach(Runnable::run);
        }
    }

    /**
     * Drops the queued reads, interrupts a wait for a connection, and lets the read in flight end within {@link #STOP_BOUND}
     * (`P15-TSK-008`): a read is bounded by its statement timeout, so the pool, closed after every lifecycle stop, does not
     * close under it. A read still running past the bound is abandoned on its daemon thread and fails with the pool.
     */
    @Override
    public void stop() {
        running = false;
        if (owned != null) {
            owned.shutdownNow();
            try {
                if (!owned.awaitTermination(STOP_BOUND.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("The accounting gauges' read did not end within {}; abandoned to the pool's close", STOP_BOUND);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        log.debug("The accounting gauges' reader stopped");
    }

    @Override
    public Duration stopBound() {
        return STOP_BOUND;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Before the web server's phase, so the first reads are asked for before anything can scrape; stopped after it. */
    @Override
    public int getPhase() {
        return 0;
    }
}
