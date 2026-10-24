package com.finapp.risk;

/**
 * What the officer proposing an override judges should happen to the overridden movement (`P13-TSK-012`;
 * {@code DESIGN-012} D2): the request's word, mapped with the assessment's operation and decision onto the
 * {@link OverrideEffect} the approval will issue ({@link OverrideEffect#of}).
 */
public enum OverrideResolution {

    /**
     * The movement should proceed: a held withdrawal released, a held card payment captured - or, for a refused command
     * (terminal in its owner's machine), the refusal recorded as wrong and the customer's resubmission left to be judged
     * afresh.
     */
    PROCEED,

    /**
     * A held movement should be refused now rather than at its hold's expiry: a held withdrawal denied (its ledger hold
     * released by payments), a held card payment's authorisation voided. Only a {@code HOLD} admits it - a refusal has
     * nothing held to refuse.
     */
    REFUSE
}
