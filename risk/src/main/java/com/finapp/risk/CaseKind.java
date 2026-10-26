package com.finapp.risk;

/**
 * The kinds of case (`P13-TSK-001`; {@code PHASE_13_PLAN.md} sections 3.1, 12.8; ADR-0110; owner decision C3) - the
 * unit of a person's judgement. One case open at most per subject and kind ({@code INV-RSK-06}); the machine is
 * {@code RISK_LIFECYCLES.md} section 4.6 and `-016`'s.
 */
public enum CaseKind {

    /** A fraud pattern on a payment, transfer or withdrawal. */
    FRAUD,

    /** An account takeover. */
    ATO,

    /** A merchant's or customer's abuse of refunds, chargebacks or BNPL applications. */
    MERCHANT_ABUSE,

    /** A suspicion of money laundering from the monitoring rules - the regulatory report's source (`-021`). */
    AML,

    /**
     * A collections case opened on a loan's or a BNPL obligation's default - facts only, no operation (C3, `-025`): about
     * the agreement's PARTY, one open per party, opened only by the default's alert ({@link CollectionsCases}),
     * disposed {@code CLOSED_NO_ACTION} or {@code REFERRED} ({@link DispositionRules#COLLECTIONS}), proposing no action.
     */
    COLLECTIONS
}
