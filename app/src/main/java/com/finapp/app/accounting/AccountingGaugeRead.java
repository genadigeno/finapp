package com.finapp.app.accounting;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * One accounting gauge's read of the database, bounded (`P14-TSK-020`; `P14-TSK-017`'s review, M-9): every instance's every scrape reaches
 * every gauge, so a slow read per scrape would pile up on a pool of eight that postings need. Four things hold it.
 *
 * <ul>
 *   <li><strong>A floor.</strong> A reading stands for {@code floor}; a clock stepped back re-reads rather than keeping a reading until time
 *       catches up.
 *   <li><strong>A single flight, per instance.</strong> Past the floor one thread reads; a scrape arriving meanwhile takes no connection. It
 *       answers the last reading while that may still stand ({@value #STANDS_FOR_FLOORS} floors) and no value after it, so a read that never
 *       returns - a socket the network black-holed, which no statement timeout reaches - ends as a gauge that says it cannot be read, never as
 *       a reassuring last number. Process-local on purpose: a metric decides nothing, and N instances each hold at most one read per gauge.
 *   <li><strong>A statement timeout</strong>: {@link GlSnapshots}, a {@code REPEATABLE READ READ ONLY} snapshot armed with {@code statement_timeout}
 *       and always rolled back. The database itself refuses a write, and a statement past the bound is cancelled.
 *   <li><strong>No value on any failure.</strong> {@link #current()} answers {@code null} - NaN at Micrometer's edge, what the alert rules'
 *       {@code x != x} sees - and never throws out of a scrape, and never answers zero for a read that failed.
 * </ul>
 *
 * <p>The pool's own wait is not bounded here: on an exhausted pool a read waits {@code spring.datasource.hikari.connection-timeout} (5 s) before it
 * fails, once per floor per gauge.
 *
 * @param <T> the reading
 */
@Slf4j
public final class AccountingGaugeRead<T> {

    /** How many floors the last reading may stand while another thread's read is in flight. */
    static final int STANDS_FOR_FLOORS = 4;

    private record Cached<T>(Instant takenAt, T value) {}

    private final String what;
    private final GlSnapshots snapshots;
    private final Clock clock;
    private final Duration floor;
    private final Function<Connection, T> reading;
    private final AtomicReference<Cached<T>> cached = new AtomicReference<>(new Cached<>(Instant.EPOCH, null));
    private final AtomicBoolean inFlight = new AtomicBoolean();

    /**
     * @param what what is read, for the log line - a constant, never a value read
     * @param snapshots where it is read: its statement timeout is this gauge's bound
     * @param floor how long a reading stands
     * @param reading the read, on the snapshot's connection; whatever it throws is no value
     */
    public AccountingGaugeRead(String what, GlSnapshots snapshots, Clock clock, Duration floor, Function<Connection, T> reading) {
        this.what = Objects.requireNonNull(what, "what");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.floor = Objects.requireNonNull(floor, "floor");
        this.reading = Objects.requireNonNull(reading, "reading");
        if (floor.toMillis() < 1) {
            throw new IllegalArgumentException("a gauge's floor is positive");
        }
    }

    /** The reading as it stands; {@code null} - no value - when it cannot be read. Never throws. */
    public T current() {
        Cached<T> held = cached.get();
        if (within(held, floor)) {
            return held.value();
        }
        if (!inFlight.compareAndSet(false, true)) {
            return within(held, floor.multipliedBy(STANDS_FOR_FLOORS)) ? held.value() : null;
        }
        try {
            T fresh = read();
            cached.set(new Cached<>(clock.instant(), fresh));
            return fresh;
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
