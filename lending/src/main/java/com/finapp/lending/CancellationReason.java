package com.finapp.lending;

/**
 * Why an instalment loan is {@link LoanStatus#CANCELLED} before it was ever disbursed (`P11-TSK-002`;
 * {@code LENDING_LIFECYCLES.md} section 4.5, the disbursement sweep of `P11-TSK-014`). Nothing was posted, the capital
 * commitment is released, and the decision stays consumed.
 */
public enum CancellationReason {

    /** The borrower's standing was lost before the disbursement posted. */
    STANDING_LOST,

    /** The borrower's wallet was not postable when the product's disbursement deadline passed. */
    DISBURSEMENT_FAILED
}
