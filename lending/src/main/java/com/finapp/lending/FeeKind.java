package com.finapp.lending;

/**
 * The kinds of fee an account is assessed after origination (`P11-TSK-025`, {@code lending V021}'s
 * {@code fee_assessment.kind}): one assessment per billed item per kind, for ever ({@code UNIQUE (due_item_id, kind)}). No
 * penalty or default interest and no prepayment fee exist (L8); the origination fee is rule 1's, deducted at disbursement.
 */
public enum FeeKind {

    /** The pinned late fee (A18): on the day an item's own age reaches the pinned late-fee day, capped per account. */
    LATE
}
