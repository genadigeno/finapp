package com.finapp.app.accounting;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * One accounting gauge's read of the database, bounded and off the scrape (`P14-TSK-020`; `P14-TSK-017`'s review, M-9; its own review, I-1): every instance's every scrape reaches every gauge, and a read on the scrape thread is a scrape that can outlive Prometheus's timeout, in
 * which case nothing is stored at all. Five things hold it.
 *
 * <ul>
 *   <li><strong>{@link #current()} never touches the pool.</strong> It looks at the last reading and asks {@link AccountingGaugeRefresher} for another; the read runs on that one thread. Before the first read has ended it answers no value at once.
 *   <li><strong>A floor.</strong> A reading stands for {@code floor}; a clock stepped back asks again rather than keeping a reading until time catches up.
 *   <li><strong>A single flight.</strong> At most one read of this gauge is queued or running.
 *   <li><strong>Four floors, then no value.</strong> While a read is queued or running the last reading stands for {@value #STANDS_FOR_FLOORS} floors, and after that no value, so a read that never returns ends as a gauge that says it cannot be read, never as a reassuring last number.
 *       One such read holds the one thread, so every accounting gauge goes the same way: nothing is being read, and that is the signal. Only a restart frees it, the data source setting no {@code socketTimeout}.
 *   <li><strong>A statement timeout, and no value on any failure.</strong> {@link GlSnapshots}: a {@code REPEATABLE READ READ ONLY} snapshot armed with {@code statement_timeout}, always rolled back. A read that fails publishes no value - NaN at Micrometer's edge, what the alert rules' {@code x != x} sees - never a zero.
 * </ul>
 *
 * <p>The pool's own wait, {@code spring.datasource.hikari.connection-timeout} (5 s), now falls on the refresher and not on a scrape.
 *
 * @param <T> the reading
 */
@Slf4j
public final class AccountingGaugeRead<T> {

    /** How many floors the last reading may stand while another read is queued or running. */
    static final int STANDS_FOR_FLOORS = 4;

    private record Cached<T>(Instant takenAt, T value) {}

    private final String what;
    private final GlSnapshots snapshots;
    private final AccountingGaugeRefresher refresher;
    private final Clock clock;
    private final Duration floor;
    private final Function<Connection, T> reading;
    private final AtomicReference<Cached<T>> cached = new AtomicReference<>(new Cached<>(Instant.EPOCH, null));
    private final AtomicBoolean inFlight = new AtomicBoolean();

    /**
     * @param what what is read, for the log line - a constant, never a value read
     * @param snapshots where it is read: its statement timeout is this gauge's bound
     * @param refresher the one thread that reads
     * @param floor how long a reading stands
     * @param reading the read, on the snapshot's connection; whatever it throws is no value
     */
    public AccountingGaugeRead(String what, GlSnapshots snapshots, AccountingGaugeRefresher refresher, Clock clock, Duration floor, Function<Connection, T> reading) {
        this.what = Objects.requireNonNull(what, "what");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.refresher = Objects.requireNonNull(refresher, "refresher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.floor = Objects.requireNonNull(floor, "floor");
        this.reading = Objects.requireNonNull(reading, "reading");
        if (floor.toMillis() < 1) {
            throw new IllegalArgumentException("a gauge's floor is positive");
        }
        refresher.register(this::ask);
    }

    /** The reading as it stands; {@code null} - no value - when there is none that may stand. Never blocks on the database, and never throws. */
    public T current() {
        if (!within(cached.get(), floor)) {
            ask();
        }
        Cached<T> held = cached.get();
        return within(held, floor.multipliedBy(STANDS_FOR_FLOORS)) ? held.value() : null;
    }

    private void ask() {
        if (inFlight.compareAndSet(false, true)) {
            try {
                refresher.execute(this::refresh);
            } catch (RejectedExecutionException closed) {
                inFlight.set(false);
            }
        }
    }

    private void refresh() {
        try {
            T fresh = read();
            cached.set(new Cached<>(clock.instant(), fresh));
        } finally {
            inFlight.set(false);
        }
    }

    private T read() {
        try {
            return snapshots.read(reading);
        } catch (RuntimeException unreadable) {
            // By class: a driver's message can carry data.
            log.warn("{} could not be read: {}", what, unreadable.getClass().getSimpleName());
            return null;
        }
    }

    private boolean within(Cached<T> held, Duration bound) {
        Duration age = Duration.between(held.takenAt(), clock.instant());
        return !age.isNegative() && age.compareTo(bound) < 0;
    }
}
