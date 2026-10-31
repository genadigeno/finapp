package com.finapp.accounting;

/**
 * What a report run is generated as, fixed at its claim (`P14-TSK-008`; ADR-0115 decision 3; {@code PHASE_14_PLAN.md}
 * section 13.6; {@code INV-ACC-04}): {@link #FINAL} for a period no longer {@code OPEN} - {@code LOCKED},
 * {@code CLOSE_PROPOSED} or {@code CLOSED}, whose posting-date range the ledger's cutoff makes immutable, so the run
 * reproduces - and {@link #PROVISIONAL} for an {@code OPEN} period: a labelled snapshot as of its cut, never issued.
 * {@code accounting V004} holds the basis to the period at the run's birth and to the run's completed status.
 */
public enum RunBasis {

    FINAL,
    PROVISIONAL;

    /** The basis a run of a period in {@code status} is generated as. */
    public static RunBasis of(PeriodStatus status) {
        return status == PeriodStatus.OPEN ? PROVISIONAL : FINAL;
    }

    /** The status a run of this basis completes to. */
    public RunStatus completed() {
        return this == FINAL ? RunStatus.FINAL : RunStatus.PROVISIONAL;
    }
}
