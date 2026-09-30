package com.finapp.reconciliation;

/**
 * What a break stands on (`P8-TSK-014`, ADR-0069 §1): one of reconciliation's own rows. The
 * column the raise set names it; the partial uniques are per (type, subject column), and a
 * reclassification may only move a break onto a type that stands on the same kind
 * ({@link BreakType#admits}).
 */
public enum BreakSubjectKind {

    /** An expectation: its remainder is what the break answers for. */
    EXPECTATION,

    /** An external item: its unallocated, possibly parked, remainder. */
    EXTERNAL_ITEM,

    /** A suspense item: the parked value itself (`PARKED_ON_RECEIPT`, `P8-TSK-020`). */
    SUSPENSE_ITEM,

    /** A run: a statement-level or processing break carried by the batch's one run. */
    RUN,

    /** A decision: the zero-value observation it names ({@code TIMING_DIFFERENCE}). */
    DECISION
}
