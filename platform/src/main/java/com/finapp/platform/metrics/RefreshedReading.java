package com.finapp.platform.metrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * One meter's last value, read on the {@link GaugeRefresher}'s thread and served to a scrape that never waits
 * (`P15-TSK-024`). {@link #current()} looks at what was last read, asks for another read when that is older than the
 * floor, and answers at once:
 *
 * <ul>
 *   <li><strong>No value before the first read</strong> - NaN, never a zero nobody measured.
 *   <li><strong>The read's own answer after it</strong>, NaN included: a read that failed is no value, and so is one that
 *       threw (logged by its class, never its message, which can carry data).
 *   <li><strong>The last value while an ask is being answered</strong>, for at most {@code staleAfter} from the first
 *       unanswered ask; past that, no value, until a read completes. Staleness is measured from the ask, not from the
 *       value's age, so a healthy instance scraped once a minute keeps its numbers, and one whose refresher is held, or
 *       stopped, loses them whatever the scrape interval.
 *   <li><strong>One read in flight at most</strong>, however many scrapes ask (a compare-and-set flag).
 *   <li><strong>A clock stepped back</strong> makes an age negative, read as "not within": it asks again, and an
 *       outstanding ask answers no value until a read completes - never a stale number kept until time catches up.
 * </ul>
 */
@Slf4j
public final class RefreshedReading {

    private record Held(Instant takenAt, double value) {}

    private final String what;
    private final DoubleSupplier read;
    private final GaugeRefresher refresher;
    private final Clock clock;
    private final Duration floor;
    private final Duration staleAfter;
    private final AtomicReference<Held> held = new AtomicReference<>();
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicReference<Instant> unansweredSince = new AtomicReference<>();

    RefreshedReading(String what, DoubleSupplier read, GaugeRefresher refresher, Clock clock, Duration floor, Duration staleAfter) {
        this.what = Objects.requireNonNull(what, "what");
        this.read = Objects.requireNonNull(read, "read");
        this.refresher = Objects.requireNonNull(refresher, "refresher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.floor = Objects.requireNonNull(floor, "floor");
        this.staleAfter = Objects.requireNonNull(staleAfter, "staleAfter");
    }

    /** The value as it stands: NaN when nothing may stand. Never reads, never blocks, never throws. */
    public double current() {
        Instant now = clock.instant();
        Held last = held.get();
        if (last == null || !within(last.takenAt(), now, floor)) {
            ask(now);
        }
        if (last == null) {
            return Double.NaN;
        }
        Instant since = unansweredSince.get();
        if (since != null && !within(since, now, staleAfter)) {
            return Double.NaN;
        }
        return last.value();
    }

    /** Whether a read has ever completed: the guard's census. */
    public boolean hasBeenRead() {
        return held.get() != null;
    }

    /** What is read: a constant. */
    public String what() {
        return what;
    }

    /** Asks for a read unless one is in flight. */
    void ask(Instant now) {
        if (inFlight.compareAndSet(false, true)) {
            unansweredSince.compareAndSet(null, now);
            if (!refresher.offer(this)) {
                inFlight.set(false);
            }
        }
    }

    /** Asks for a read even beside one in flight: {@link GaugeRefresher#refreshAll}'s. */
    void askAgain(Instant now) {
        inFlight.set(true);
        unansweredSince.compareAndSet(null, now);
        if (!refresher.offer(this)) {
            inFlight.set(false);
        }
    }

    /** On the refresher's thread. */
    void refresh() {
        try {
            double value = readOrNaN();
            held.set(new Held(clock.instant(), value));
            unansweredSince.set(null);
        } finally {
            inFlight.set(false);
        }
    }

    /** A queued read dropped at the refresher's stop: the flag goes down, so the next start's asks are taken. The ask stays unanswered. */
    void dropped() {
        inFlight.set(false);
    }

    private double readOrNaN() {
        try {
            return read.getAsDouble();
        } catch (RuntimeException unreadable) {
            log.warn("{} could not be read: {}", what, unreadable.getClass().getSimpleName());
            return Double.NaN;
        }
    }

    private static boolean within(Instant from, Instant now, Duration bound) {
        Duration age = Duration.between(from, now);
        return !age.isNegative() && age.compareTo(bound) < 0;
    }
}
