package com.finapp.credit;

/**
 * What a policy does when a source it reads answered nothing by its deadline ({@code INV-CRD-10}): refer or decline.
 * There is no approving member - an approval from missing data is unrepresentable here and by
 * {@code credit V008}'s {@code credit_policy_fallback_never_approves} CHECK.
 */
public enum UnavailableFallback {

    /** A person decides, with the source's attributes absent. */
    REFER(PolicyEffect.REFER),

    /** The request is declined. */
    DECLINE(PolicyEffect.DECLINE);

    private final PolicyEffect effect;

    UnavailableFallback(PolicyEffect effect) {
        this.effect = effect;
    }

    /** The effect the fallback rules carry. */
    public PolicyEffect effect() {
        return effect;
    }
}
