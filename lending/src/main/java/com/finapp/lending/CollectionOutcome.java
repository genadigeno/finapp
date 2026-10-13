package com.finapp.lending;

/**
 * What one automatic collection attempt did (`P11-TSK-019`; {@code lending V015}'s {@code collection_attempt_outcome_is_known},
 * ADR-0095 point 6, A21). Exactly the first two moved money - an {@code AUTO_COLLECTION} repayment; the rest posted nothing.
 */
public enum CollectionOutcome {

    /** Everything the billed item had due, collected. */
    COLLECTED,

    /** Less than the item had due - the whole available balance - the pinned terms allowing a partial collection. */
    PARTIAL,

    /** The wallet could not cover the item (nothing available, or less than due where partial collection is not allowed). */
    INSUFFICIENT_FUNDS,

    /** The borrower has no wallet in the loan's currency, or it is not {@code ACTIVE} - nothing can be debited now. */
    WALLET_NOT_POSTABLE,

    /** The item had nothing left due when the attempt came - settled earlier that day (its due date's attempt only). */
    NOTHING_DUE;

    /** Whether the attempt moved money. */
    public boolean collected() {
        return this == COLLECTED || this == PARTIAL;
    }

    /** Whether the attempt collected nothing from an item that had something due - {@code lending.CollectionFailed}. */
    public boolean failed() {
        return this == INSUFFICIENT_FUNDS || this == WALLET_NOT_POSTABLE;
    }
}
