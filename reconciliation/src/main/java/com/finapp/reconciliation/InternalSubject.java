package com.finapp.reconciliation;

/**
 * What an internal reference resolved to (`P8-TSK-017`) — the kind of platform operation the
 * lookup found, never its identity. Reconciliation's own closed vocabulary: the lookup in
 * {@code app} maps each store's row onto it, so this module names no sibling's type.
 */
public enum InternalSubject {
    /** A card payment attempt, or a push pay-in's attempt. */
    PAYMENT_ATTEMPT,
    /** A card refund, or a push return — both are refunds of a payment. */
    REFUND,
    /** A push withdrawal. */
    WITHDRAWAL,
    /** A dispute stage. */
    DISPUTE,
    /** A merchant payout. */
    PAYOUT,
    /** A Phase 7 parking of an execution that matched nothing (an unmatched confirmation). */
    PARKING,

    /** An FX cover, named by one of its attempts' {@code COVER_REF} (`P9-TSK-011`). */
    COVER
}
