package com.finapp.app.accounting;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * One accounting gauge's read of the database, bounded (`P14-TSK-020`; `P14-TSK-017`'s review, M-9; its own review,
 * I-1). Three things hold it.
 *
 * <ul>
 *   <li><strong>A floor.</strong> A reading stands for {@code floor}; a clock stepped back reads again rather than keeping
 *       a reading until time catches up.
 *   <li><strong>A statement timeout on a read-only snapshot.</strong> {@link GlSnapshots}: a {@code REPEATABLE READ READ
 *       ONLY} snapshot armed with {@code statement_timeout}, always rolled back.
 *   <li><strong>No value on any failure.</strong> A read that fails publishes no value - NaN at Micrometer's edge, what
 *       the alert rules' {@code x != x} sees - never a zero.
 * </ul>
 *
 * <p><strong>Where it reads: never on a scrape.</strong> {@link #current()} reads in line, and its only caller is the
 * gauge's function, which the application's registry calls on the platform's gauge refresher and never on a scrape
 * (`P15-TSK-024`: {@code OffScrapeMeterRegistry}, {@code GaugeRefresher}). That refresher is the one this class had
 * of its own until then, {@code AccountingGaugeRefresher}, moved to {@code platform} for every gauge: its single
 * flight, and its "the last reading stands for four floors, then no value" - now "until an ask has waited
 * {@code finapp.gauges.stale-after}, then no value" - are the platform's. {@code AccountingGaugeWiringTest} holds the
 * real context's accounting gauges to that thread. The pool's own wait, {@code spring.datasource.hikari.connection-timeout}
 * (5 s), falls on the refresher and not on a scrape.
 *
 * @param <T> the reading
 */
@Slf4j
public final class AccountingGaugeRead<T> {

    private record Cached<T>(Instant takenAt, T value) {}

    private final String what;
    private final GlSnapshots snapshots;
    private final Clock clock;
    private final Duration floor;
    private final Function<Connection, T> reading;
    private final AtomicReference<Cached<T>> cached = new AtomicReference<>();

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

    /** The reading, read again past the floor; {@code null} - no value - when the read failed. Never throws. */
    public T current() {
        Cached<T> held = cached.get();
        if (held != null && within(held)) {
            return held.value();
        }
        T fresh = read();
        cached.set(new Cached<>(clock.instant(), fresh));
        return fresh;
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

    private boolean within(Cached<T> held) {
        Duration age = Duration.between(held.takenAt(), clock.instant());
        return !age.isNegative() && age.compareTo(floor) < 0;
    }
}
