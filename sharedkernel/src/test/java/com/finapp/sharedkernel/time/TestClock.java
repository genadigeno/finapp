package com.finapp.sharedkernel.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * A clock a test moves deliberately — forwards, backwards, or not at all.
 *
 * <p><strong>Why the JDK is not enough.</strong> {@link Clock#fixed} gives determinism but
 * cannot advance, and there is no mutable {@code Clock} in the JDK. Accrual, period close,
 * value dating, hold expiry, settlement ageing and idempotency-key expiry are all behaviours
 * whose whole content is what happens as time passes; testing them against a clock that only
 * moves when the wall clock does means either not testing them or sleeping, and a test that
 * sleeps is a test that is slow and still racy.
 *
 * <p><strong>Backwards is a supported operation, not a mistake.</strong> NTP correction and
 * leap-second smearing move real clocks backwards, and financial components have to survive
 * it. {@link #rewind} exists so that survival is asserted rather than assumed — it is how
 * {@code IdGeneratorTest} proves an identifier never sorts before one already issued.
 *
 * <p><strong>Where this lives.</strong> Test sources, not production: a clock that anyone can
 * set is a correctness hazard if it ships. That means it is not yet visible to {@code platform}
 * or {@code app} tests, which would need Gradle's {@code java-test-fixtures}. Adding that is a
 * build-convention decision owned by P0-TSK-036 (test taxonomy), not one to make in passing
 * here.
 *
 * <p>Safe to read from several threads; writes are expected to come from the test thread.
 */
public final class TestClock extends Clock {

    private final ZoneId zone;
    private volatile Instant now;

    private TestClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    /** A clock stopped at {@code start}, in UTC. */
    public static TestClock at(Instant start) {
        return new TestClock(Objects.requireNonNull(start, "start must not be null"), ZoneOffset.UTC);
    }

    /** A clock stopped at {@code start}, in the given zone. */
    public static TestClock at(Instant start, ZoneId zone) {
        return new TestClock(
                Objects.requireNonNull(start, "start must not be null"),
                Objects.requireNonNull(zone, "zone must not be null"));
    }

    /** Moves time forward. Rejects a negative amount — use {@link #rewind} and mean it. */
    public TestClock advance(Duration by) {
        Objects.requireNonNull(by, "duration must not be null");
        if (by.isNegative()) {
            // Going backwards is a deliberate act with its own name, so that a test which
            // does it reads as testing clock regression rather than as arithmetic that
            // happened to come out negative.
            throw new IllegalArgumentException(
                    "advance() moves time forward; use rewind() to move it back, but was " + by);
        }
        now = now.plus(by);
        return this;
    }

    /** Moves time backward, the way an NTP correction does. */
    public TestClock rewind(Duration by) {
        Objects.requireNonNull(by, "duration must not be null");
        if (by.isNegative()) {
            throw new IllegalArgumentException(
                    "rewind() takes a positive amount to move back, but was " + by);
        }
        now = now.minus(by);
        return this;
    }

    /** Jumps to an exact instant. */
    public TestClock set(Instant instant) {
        now = Objects.requireNonNull(instant, "instant must not be null");
        return this;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId otherZone) {
        return new TestClock(now, Objects.requireNonNull(otherZone, "zone must not be null"));
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public String toString() {
        return "TestClock(" + now + " " + zone + ")";
    }
}
