package com.finapp.accounting;

import java.util.EnumSet;
import java.util.Set;

/**
 * A report run's lifecycle (`P14-TSK-001`; {@code PHASE_14_PLAN.md} sections 6, 13.6; ADR-0115) - claimed
 * {@code GENERATING}, completed {@code FINAL} for a locked or closed period or {@code PROVISIONAL} for an open one, or
 * {@code FAILED}; a {@code FINAL} run is {@code SUPERSEDED} - never deleted - when its period is
 * reopened ({@code INV-ACC-10}). Content and hash are frozen at completion, by trigger (`P14-TSK-008`); nothing outside the table
 * below is a transition.
 */
public enum RunStatus {

    GENERATING,
    FINAL,
    PROVISIONAL,
    FAILED,
    SUPERSEDED;

    /** The statuses this one may move to, by exactly one edge. */
    public Set<RunStatus> next() {
        return switch (this) {
            case GENERATING -> EnumSet.of(FINAL, PROVISIONAL, FAILED);
            case FINAL -> EnumSet.of(SUPERSEDED);
            case PROVISIONAL, FAILED, SUPERSEDED -> EnumSet.noneOf(RunStatus.class);
        };
    }

    /** True when {@code target} is one edge away. */
    public boolean canMoveTo(RunStatus target) {
        return next().contains(target);
    }

    /** True once the run has content: its content and {@code content_sha256} never change again ({@code INV-ACC-04}). */
    public boolean contentFrozen() {
        return this == FINAL || this == PROVISIONAL || this == SUPERSEDED;
    }

    /** True for the only status a regulatory submission may draw from ({@code INV-ACC-13}). */
    public boolean issuable() {
        return this == FINAL;
    }
}
