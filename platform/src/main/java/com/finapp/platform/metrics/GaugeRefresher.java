package com.finapp.platform.metrics;

import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;

/**
 * The one thread on which every gauge of this instance reads (`P15-TSK-024`; ADR-0124 decision 7; moved here from
 * {@code app}'s accounting package, where `P14-TSK-020` built it for accounting's nine): a scrape never waits on what a
 * gauge reads. A scrape that outlives Prometheus's timeout stores nothing at all - no NaN an unreadable rule could see,
 * and {@code up} goes to 0 - so a gauge that reads the database on the scrape thread turns a slow database into a lost
 * scrape. A {@link RefreshedReading} answers its caller from what this thread last read, and asks this thread for more.
 *
 * <ul>
 *   <li><strong>One daemon thread per instance</strong>, named {@value #THREAD}. It holds at most one database
 *       connection for every gauge together, however many scrapes arrive, and each reading has at most one read
 *       queued. Process-local on purpose: a metric decides nothing ({@code DISTRIBUTED_EXECUTION.md} section 3).
 *   <li><strong>Restartable</strong> ({@code POSTPONED_ISSUES.md} PI-014 item 2): the executor is made in {@link #start()}
 *       and shut in {@link #stop()}, and a read dropped at the stop puts its reading's in-flight flag down, so start,
 *       stop, start leaves every reading answering a number. It was made once in the constructor, and after a context
 *       stop and start every accounting gauge read no value for good.
 *   <li><strong>The first reads are asked for at start</strong> when {@code readAtStart} holds (production; a test
 *       context turns this background reader off as it does every other), so a healthy instance's first scrape
 *       usually holds numbers. A reading tracked while running is asked at once.
 *   <li><strong>A read that hangs holds the one thread</strong>, so every reading of the instance goes to no value once
 *       its ask has waited {@code staleAfter}: that is the signal, nothing is being read. Only a restart frees a read
 *       the network black-holed (the data source sets no {@code socketTimeout}).
 * </ul>
 *
 * <p>Framework-free, as the rest of {@code platform}: {@code app} binds it to Micrometer (every {@code finapp.}
 * function-backed meter is a {@link RefreshedReading}) and to the context's lifecycle.
 */
@Slf4j
public final class GaugeRefresher {

    /** The thread's name: what a thread dump shows inside a read. */
    public static final String THREAD = "gauge-refresher";

    /**
     * How long {@link #stop()} waits for the read in flight: `P15-TSK-008`'s bounded stop, carried here unchanged when the
     * merge of its branch retired {@code AccountingGaugeRefresher}. Above accounting's two-second statement timeout, so
     * the pool, closed after every lifecycle stop, does not close under such a read; a wait for a connection is
     * interrupted by the stop, and a statement with no timeout of its own is abandoned to the pool's close. Below
     * {@code spring.lifecycle.timeout-per-shutdown-phase}, and the 3 s of the lifecycle's 57 s worst case
     * ({@code ShutdownOrder}, {@code ShutdownOrderTest}).
     */
    public static final Duration STOP_BOUND = Duration.ofSeconds(3);

    private final boolean readAtStart;
    private final Clock clock;
    private final Duration floor;
    private final Duration staleAfter;
    private final AtomicReference<ExecutorService> executor = new AtomicReference<>();
    private final List<RefreshedReading> readings = new CopyOnWriteArrayList<>();

    /**
     * @param readAtStart whether {@link #start()} asks every reading for its first read
     * @param floor how long a reading stands before a look asks for another
     * @param staleAfter how long an ask may go unanswered before a reading answers no value; longer than the floor
     */
    public GaugeRefresher(boolean readAtStart, Clock clock, Duration floor, Duration staleAfter) {
        this.readAtStart = readAtStart;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.floor = Objects.requireNonNull(floor, "floor");
        this.staleAfter = Objects.requireNonNull(staleAfter, "staleAfter");
        if (floor.toMillis() < 1) {
            throw new IllegalArgumentException("a reading's floor is positive");
        }
        if (staleAfter.compareTo(floor) <= 0) {
            throw new IllegalArgumentException("an ask may wait longer than a floor before its reading is withdrawn");
        }
    }

    /**
     * A reading of {@code read}, refreshed on this thread.
     *
     * @param what what is read, for the log line - a constant, never a value read
     * @param read the read; whatever it throws is no value
     */
    public RefreshedReading track(String what, DoubleSupplier read) {
        RefreshedReading reading = new RefreshedReading(what, read, this, clock, floor, staleAfter);
        readings.add(reading);
        if (readAtStart && isRunning()) {
            reading.ask(clock.instant());
        }
        return reading;
    }

    /** Stops refreshing {@code reading}: its meter left the registry. */
    public void untrack(RefreshedReading reading) {
        readings.remove(reading);
    }

    /** Every reading this refresher holds, a view. */
    public List<RefreshedReading> tracked() {
        return Collections.unmodifiableList(readings);
    }

    /** Makes the thread anew; a second start while running does nothing. */
    public void start() {
        ExecutorService fresh = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, THREAD);
            thread.setDaemon(true);
            return thread;
        });
        if (!executor.compareAndSet(null, fresh)) {
            fresh.shutdownNow();
            return;
        }
        if (readAtStart) {
            readings.forEach(reading -> reading.ask(clock.instant()));
        }
    }

    /**
     * Shuts the thread: every queued read is dropped and its reading's in-flight flag put down, a wait for a connection
     * is interrupted, and the read in flight - a statement, which no interrupt reaches - is let end within
     * {@link #STOP_BOUND}. A read still running past the bound is abandoned on its daemon thread and fails with the pool;
     * its reading answers no value once its ask has waited {@code staleAfter}.
     */
    public void stop() {
        ExecutorService running = executor.getAndSet(null);
        if (running == null) {
            return;
        }
        for (Runnable dropped : running.shutdownNow()) {
            if (dropped instanceof Read read) {
                read.reading().dropped();
            }
        }
        try {
            if (!running.awaitTermination(STOP_BOUND.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("The gauge refresher's read did not end within {} of its stop; abandoned to the pool's close", STOP_BOUND);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        log.debug("The gauge refresher stopped");
    }

    /** The longest {@link #stop()} takes: the context's lifecycle reads it ({@code ShutdownOrderTest}). */
    public Duration stopBound() {
        return STOP_BOUND;
    }

    public boolean isRunning() {
        return executor.get() != null;
    }

    /**
     * Asks every reading for a read now, past its floor and beside any read in flight, and waits for the thread to
     * have run them all - the suites' and an operator's way to look at what the database says now. Never a scrape's.
     *
     * @return whether every read ran within {@code timeout}; false when the refresher is not running
     */
    public boolean refreshAll(Duration timeout) throws InterruptedException {
        return refresh(what -> true, timeout);
    }

    /**
     * {@link #refreshAll}, for the readings whose {@link RefreshedReading#what()} {@code which} accepts - one module's
     * gauges, say, so a suite waits on its own reads and not on every gauge's.
     */
    public boolean refresh(Predicate<String> which, Duration timeout) throws InterruptedException {
        for (RefreshedReading reading : readings) {
            if (which.test(reading.what())) {
                reading.askAgain(clock.instant());
            }
        }
        CompletableFuture<Void> behindThem = new CompletableFuture<>();
        if (!offer(() -> behindThem.complete(null))) {
            return false;
        }
        try {
            behindThem.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException | ExecutionException notInTime) {
            return false;
        }
    }

    /** Queues {@code reading}'s read; false when the refresher is not running. */
    boolean offer(RefreshedReading reading) {
        return offer(new Read(reading));
    }

    private boolean offer(Runnable task) {
        ExecutorService current = executor.get();
        if (current == null) {
            return false;
        }
        try {
            current.execute(task);
            return true;
        } catch (RejectedExecutionException closed) {
            return false;
        }
    }

    /** One queued read: a type of its own, so {@link #stop()} knows which reading a dropped one belonged to. */
    private record Read(RefreshedReading reading) implements Runnable {
        @Override
        public void run() {
            reading.refresh();
        }
    }
}
