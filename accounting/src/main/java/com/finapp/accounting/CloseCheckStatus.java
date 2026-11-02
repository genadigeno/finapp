package com.finapp.accounting;

import java.util.EnumSet;
import java.util.Set;

/**
 * One close check attempt's lifecycle (`P14-TSK-011`; {@code PHASE_14_PLAN.md} section 6; {@code INV-ACC-09}): born
 * {@link #PENDING}, concluded once and frozen ({@code accounting V007}'s trigger); a re-run is a new attempt, never an
 * edit.
 *
 * <p>{@link #DISCLOSED} is a discrepancy on the record, not a pass: the reasoned disclosure of an {@code ACKNOWLEDGED}
 * break, or the figures of a reading that is whole and not clean - only where {@link CloseCheckCode#disclosable()}
 * admits it. The close (`P14-TSK-012`) is proposed with every check {@link #readyForClose() PASSED or DISCLOSED}.
 */
public enum CloseCheckStatus {

    PENDING,
    PASSED,
    FAILED,
    DISCLOSED;

    /** The statuses this one may move to, by exactly one edge. */
    public Set<CloseCheckStatus> next() {
        return this == PENDING ? EnumSet.of(PASSED, FAILED, DISCLOSED) : EnumSet.noneOf(CloseCheckStatus.class);
    }

    /** True once concluded - the row is frozen. */
    public boolean concluded() {
        return this != PENDING;
    }

    /** True for what a close may be proposed on ({@code INV-ACC-09}). */
    public boolean readyForClose() {
        return this == PASSED || this == DISCLOSED;
    }
}
