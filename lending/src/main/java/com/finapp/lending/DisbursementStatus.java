package com.finapp.lending;

/**
 * The disbursement machine (`P11-TSK-012` births it, `P11-TSK-014` moves it; {@code LENDING_LIFECYCLES.md} section 4.6):
 * {@code PENDING -> POSTED | FAILED}, each terminal edge only beside the loan's own edge in the same transaction -
 * {@code POSTED} with {@code PENDING_DISBURSEMENT -> ACTIVE}, {@code FAILED} with {@code -> CANCELLED}.
 */
public enum DisbursementStatus {

    /** Born in the acceptance; the disbursement sweep's to act on. */
    PENDING,

    /** Posting rule 1 committed: the receivable born with the wallet credit ({@code INV-LND-07}). */
    POSTED,

    /** Nothing posted (rule 11): the loan cancelled, the commitment released. */
    FAILED
}
