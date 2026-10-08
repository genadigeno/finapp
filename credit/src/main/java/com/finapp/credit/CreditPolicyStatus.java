package com.finapp.credit;

/**
 * A credit policy version's machine (`P10-TSK-012`; {@code credit V008}'s edge trigger holds it for every writer):
 * {@code PROPOSED -> ACTIVE | REJECTED}, {@code ACTIVE -> RETIRED}, the retirement only beside its successor.
 */
public enum CreditPolicyStatus {

    /** Awaiting a decision; its parameters and rules are frozen. At most one per product. */
    PROPOSED,

    /** Decides new requests from its effective start. At most one per product. */
    ACTIVE,

    /** Superseded; still decides every request that pinned it ({@code INV-HIST-04}). */
    RETIRED,

    /** Rejected or withdrawn; decided nothing. */
    REJECTED
}
