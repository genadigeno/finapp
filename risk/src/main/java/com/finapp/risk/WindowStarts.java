package com.finapp.risk;

import java.time.Instant;
import java.util.Objects;

/**
 * Where a velocity window starts (`P13-TSK-006`; {@code PHASE_13_PLAN.md} section 12.4; {@code INV-RSK-03}) - a pure
 * function of the window's kind and <strong>a database instant</strong>: the windows are tumbling buckets of fixed
 * length anchored at {@link #ANCHOR}, Monday 1970-01-05 00:00 UTC, so {@code H1} starts on the hour, {@code D1} at UTC
 * midnight, {@code D7} on an ISO Monday and {@code D30} every thirty days from the anchor.
 *
 * <p><strong>One rule, twice.</strong> {@code risk V005}'s {@code velocity_window_start_is_aligned} is the same rule in
 * SQL ({@code date_bin(length, start, '1970-01-05')}), so a row whose start this function would not produce is refused
 * by the database. Every length is a whole number of seconds, so floor-dividing epoch seconds is exact - no fraction, no
 * time zone, no calendar.
 *
 * <p><strong>Which instant.</strong> This function computes; it never reads a clock. The instant it is given is
 * {@link WindowCut#at()}, read on the caller's connection after its locks ({@link VelocityWindows#cut}) - so ten
 * instances whose clocks disagree by seconds cut every movement into the window the database's clock is in.
 */
public final class WindowStarts {

    /** Monday 1970-01-05 00:00:00 UTC - the anchor every window kind is a multiple of its length from. */
    public static final Instant ANCHOR = Instant.parse("1970-01-05T00:00:00Z");

    private WindowStarts() {}

    /** The start of the {@code kind} window that contains {@code databaseInstant}. */
    public static Instant start(WindowKind kind, Instant databaseInstant) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(databaseInstant, "databaseInstant");
        long length = kind.length().getSeconds();
        long sinceAnchor = databaseInstant.getEpochSecond() - ANCHOR.getEpochSecond();
        return ANCHOR.plusSeconds(Math.floorDiv(sinceAnchor, length) * length);
    }

    /** The first instant after the {@code kind} window starting at {@code start}. */
    public static Instant end(WindowKind kind, Instant start) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(start, "start");
        return start.plus(kind.length());
    }

    /** True when {@code start} is a window start of {@code kind} - the database's alignment {@code CHECK}, in Java. */
    public static boolean aligned(WindowKind kind, Instant start) {
        return start(kind, start).equals(start);
    }
}
