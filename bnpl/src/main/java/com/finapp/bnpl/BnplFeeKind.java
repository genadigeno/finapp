package com.finapp.bnpl;

/**
 * The kinds of fee a BNPL obligation is assessed (`P12-TSK-020`, {@code bnpl V013}'s {@code bnpl_fee_assessment.kind};
 * A13): one assessment per billed instalment per kind, for ever ({@code UNIQUE (instalment_id, kind)}). No penalty or
 * default interest and no prepayment fee exist ({@code PHASE_12_PLAN.md} section 12.3); no customer fee on pay-in-4 beside
 * the late fee (OQ-17).
 */
public enum BnplFeeKind {

    /** The pinned late fee (A13): on the day an instalment's own age reaches the pinned late-fee day, capped per agreement. */
    LATE
}
