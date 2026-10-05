package com.finapp.kyc;

/**
 * What a counterparty screening reports as it decides (`P9-TSK-016`) - implemented by {@code app}'s
 * meters ({@code finapp.kyc.counterparty.screening{outcome}}), told after the decision commits.
 */
@FunctionalInterface
public interface CounterpartyScreeningObserver {

    /** {@code status} was decided for one screening (committed). */
    void decided(CounterpartyScreeningStatus status);

    /** Observes nothing - the tests' and the module's default. */
    CounterpartyScreeningObserver NONE = status -> {};
}
