package com.finapp.bnpl;

/**
 * What one automatic collection attempt did (`P12-TSK-019`; {@code bnpl V011}'s {@code bnpl_collection_attempt_outcome_is_known},
 * A7; lending's {@code CollectionOutcome}). Exactly the first two moved money - an {@code AUTO_COLLECTION} repayment; the
 * rest posted nothing.
 */
public enum CollectionOutcome {

    /** Everything the billed instalment had due, collected. */
    COLLECTED,

    /** Less than the instalment had due - the whole available balance - the pinned terms allowing a partial collection. */
    PARTIAL,

    /**
     * The wallet could not cover the instalment: nothing available, less than due where partial collection is not allowed,
     * or an older instalment still due ahead of it that day (oldest first).
     */
    INSUFFICIENT_FUNDS,

    /** The customer has no wallet in the obligation's currency, or it is not {@code ACTIVE} - nothing can be debited now. */
    WALLET_NOT_POSTABLE,

    /** The instalment had nothing left due when its due date's attempt came - settled earlier (a repayment, held credit). */
    NOTHING_DUE;

    /** Whether the attempt moved money. */
    public boolean collected() {
        return this == COLLECTED || this == PARTIAL;
    }

    /** Whether the attempt collected nothing from an instalment that had something due - {@code bnpl.BnplCollectionFailed}. */
    public boolean failed() {
        return this == INSUFFICIENT_FUNDS || this == WALLET_NOT_POSTABLE;
    }
}
