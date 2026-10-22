package com.finapp.risk;

import java.time.Duration;
import java.util.Objects;

/**
 * The velocity windows (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 12.4; {@code INV-RSK-03}) - each a fixed length,
 * keyed by its start computed from the database clock read after the caller's lock, never from a JVM instant. A window
 * is a durable row written in the judged command's transaction (`-006`); these are its kinds.
 */
public enum WindowKind {

    /** One hour. */
    H1(Duration.ofHours(1)),

    /** One day. */
    D1(Duration.ofDays(1)),

    /** Seven days. */
    D7(Duration.ofDays(7)),

    /** Thirty days. */
    D30(Duration.ofDays(30));

    private final Duration length;

    WindowKind(Duration length) {
        this.length = Objects.requireNonNull(length, "length");
    }

    /** The window's fixed length. */
    public Duration length() {
        return length;
    }
}
