package com.finapp.risk;

import java.util.Objects;

/**
 * Where an alert stands (`P13-TSK-015`; {@code risk V010}; {@code RISK_LIFECYCLES.md} section 4.5; ADR-0110 decision 1)
 * - {@code OPEN → ATTACHED | SUPPRESSED}, and nothing out of either: an alert is never re-opened, never deleted.
 *
 * <ul>
 *   <li>{@link #OPEN} - born, queued; awaiting a case (`-016`'s folding) or a person's suppression.</li>
 *   <li>{@link #ATTACHED} - joined to a case: <strong>the system's dedupe</strong> (an alert raised while its subject has
 *       an open case of the kind attaches to it) or an investigator's case opened from it.</li>
 *   <li>{@link #SUPPRESSED} - <strong>only</strong> an operator's reasoned four-eyes act, never the system's
 *       ({@code INV-RSK-07}): a duplicate attaches, it is never suppressed.</li>
 * </ul>
 */
public enum AlertStatus {
    OPEN,
    ATTACHED,
    SUPPRESSED;

    /** True when the machine admits the edge from this state to {@code next} - from {@code OPEN} only. */
    public boolean admits(AlertStatus next) {
        Objects.requireNonNull(next, "next");
        return this == OPEN && next != OPEN;
    }

    /** True for the two states nothing leaves. */
    public boolean terminal() {
        return this != OPEN;
    }
}
