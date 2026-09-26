package com.finapp.payments;

/**
 * How a rail's payment is conducted (`P7-TSK-001`, ADR-0059 §2) — the property the attempt's
 * machine will key on when the second model arrives (`P7-TSK-002`).
 *
 * <p>Three models, deliberately not one: a generic "execute" with rail-neutral states is the
 * phase's first named risk, because it hides that a card capture stays reversible against the
 * platform for months while an instant payment is final in seconds. No state name is shared
 * across models, so no query, report or reconciliation can mistake one rail's completion for
 * another's.
 */
public enum InteractionModel {

    /**
     * Authorize, then capture — Phase 5's seven-state machine, plus the void edges the card
     * reversal task adds (`P7-TSK-004`). The outcome is decided by the issuer, through the
     * PSP and the network.
     */
    TWO_STEP,

    /**
     * One push executes the payment — a credit transfer, decided by the payer's PSP, the
     * scheme and the payee's PSP. Its machine arrives with `P7-TSK-002`; its first rail with
     * `P7-TSK-006`.
     */
    PUSH,

    /**
     * One book movement on the platform's own ledger, born {@code EXECUTED} or {@code FAILED}
     * inside the confirmation's transaction (ADR-0043's property): no dispatch, no unknown,
     * nothing external. The wallet rail (`P7-TSK-011`).
     */
    BOOK
}
