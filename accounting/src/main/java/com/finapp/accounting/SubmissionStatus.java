package com.finapp.accounting;

import java.util.EnumSet;
import java.util.Set;

/**
 * A regulatory submission's lifecycle (`P14-TSK-001`; {@code PHASE_14_PLAN.md} sections 6, 13.7; ADR-0118) - drafted
 * only from {@code FINAL} runs of {@code CLOSED} periods, validated, approved under four eyes, submitted through
 * {@code RegulatoryReportingProvider} with {@code SUBMITTED} committed before the provider is called, then acknowledged
 * or rejected by the inquiry leg ({@code INV-ACC-13}). A rejection or a correction is a <strong>new</strong> submission
 * referencing the original - never an edit; an unknown provider answer is an inquiry on the {@code SUBMITTED} row, never
 * a re-submit.
 */
public enum SubmissionStatus {

    DRAFT,
    VALIDATED,
    APPROVED,
    SUBMITTED,
    ACKNOWLEDGED,
    REJECTED;

    /** The statuses this one may move to, by exactly one edge. */
    public Set<SubmissionStatus> next() {
        return switch (this) {
            case DRAFT -> EnumSet.of(VALIDATED);
            case VALIDATED -> EnumSet.of(APPROVED);
            case APPROVED -> EnumSet.of(SUBMITTED);
            case SUBMITTED -> EnumSet.of(ACKNOWLEDGED, REJECTED);
            case ACKNOWLEDGED, REJECTED -> EnumSet.noneOf(SubmissionStatus.class);
        };
    }

    /** True when {@code target} is one edge away. */
    public boolean canMoveTo(SubmissionStatus target) {
        return next().contains(target);
    }

    /** True from {@code SUBMITTED} on: what was sent is never edited, only answered or superseded by a new submission. */
    public boolean immutable() {
        return this == SUBMITTED || this == ACKNOWLEDGED || this == REJECTED;
    }
}
