package com.finapp.accounting;

/**
 * Where a posting rule takes its posting date from (`P14-TSK-001`; {@code PHASE_14_PLAN.md} section 13.2; ADR-0117) -
 * each registered rule carries one (`P14-TSK-002`), because the posting date decides the accounting period and the
 * ledger's cutoff refuses an entry dated into a locked period ({@code INV-ACC-03}). {@link #STORED_ROW} and
 * {@link #ECONOMIC_DATE} can date an entry into the past ({@link #mayBackDate()}), which is why each such rule names its
 * {@link CutoffReaction} - floored at the first open date for a system poster, refused at approval for a person's
 * adjustment (`P14-TSK-006`, owner decision C3). *(Read "Only {@code ECONOMIC_DATE} can date an entry into the past"
 * until `P14-TSK-006`: `P14-TSK-002`'s register had already found {@code STORED_ROW} back-dating too.)*
 */
public enum DatingClass {

    /** Today by the poster's instance clock. */
    TODAY_CLOCK,
    /** Today by the database clock. */
    TODAY_DATABASE,
    /** A date stored on the source row (a settlement batch's {@code accepted_on}, a trade's recorded date). */
    STORED_ROW,
    /** The economic event's own date - may lie in the past (today: lending's daily accrual). */
    ECONOMIC_DATE;

    /** True when a poster of this class may date an entry before today - the back-dating posters. */
    public boolean mayBackDate() {
        return this == STORED_ROW || this == ECONOMIC_DATE;
    }
}
