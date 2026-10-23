package com.finapp.risk;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

/**
 * The primary key of one {@code velocity_window} row (`P13-TSK-006`; {@code risk V005}): the dimension, the subject,
 * the window kind and the window's start - and, through {@link #compareTo}, <strong>the order R2 upserts in</strong>
 * ({@code PHASE_13_PLAN.md} section 7.2: dimension, subject, window kind, window start). Every writer sorts by this one
 * comparator, so two transactions upserting overlapping windows take their row locks in the same order and cannot
 * deadlock. The order is a pure function of the key - enum declaration order and the reference's characters - the same
 * in every JVM.
 */
public record VelocityWindowKey(VelocityDimension dimension, String subjectRef, WindowKind windowKind, Instant windowStart)
        implements Comparable<VelocityWindowKey> {

    private static final Comparator<VelocityWindowKey> ORDER = Comparator.comparing(VelocityWindowKey::dimension)
            .thenComparing(VelocityWindowKey::subjectRef)
            .thenComparing(VelocityWindowKey::windowKind)
            .thenComparing(VelocityWindowKey::windowStart);

    public VelocityWindowKey {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(subjectRef, "subjectRef");
        Objects.requireNonNull(windowKind, "windowKind");
        Objects.requireNonNull(windowStart, "windowStart");
        if (!dimension.referenceShape().matcher(subjectRef).matches()) {
            throw new IllegalArgumentException("a " + dimension + " window is keyed on its reference shape");
        }
        if (!WindowStarts.aligned(windowKind, windowStart)) {
            throw new IllegalArgumentException("a " + windowKind + " window starts on its boundary");
        }
    }

    /** The first instant after this window. */
    public Instant windowEnd() {
        return WindowStarts.end(windowKind, windowStart);
    }

    /** True while {@code instant} is inside this window. */
    public boolean contains(Instant instant) {
        return !instant.isBefore(windowStart) && instant.isBefore(windowEnd());
    }

    @Override
    public int compareTo(VelocityWindowKey other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        // Never the reference: which subject a window counts is CONFIDENTIAL.
        return "VelocityWindowKey[" + dimension + ", " + windowKind + ", " + windowStart + "]";
    }
}
