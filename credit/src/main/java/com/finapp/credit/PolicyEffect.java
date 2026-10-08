package com.finapp.credit;

/**
 * What a triggered rule does (ADR-0086 sections 1-2) - closed. Every effect judges against the applicant - a hard
 * decline, a decline, a referral or a reduced amount - so every rule carries an ADVERSE reason code
 * ({@code INV-CRD-02}); the one non-adverse code, {@code CRD-AUTO-APPROVAL-CEILING}, belongs to the policy's ceiling
 * parameter, never to a rule.
 */
public enum PolicyEffect {

    /** Declines, and no person may override it (ADR-0089). */
    HARD_DECLINE,

    /** Declines. */
    DECLINE,

    /** Sends the request to a person. */
    REFER,

    /** Caps the approved amount at the rule's ceiling, in the product's currency. */
    CAP_AMOUNT
}
