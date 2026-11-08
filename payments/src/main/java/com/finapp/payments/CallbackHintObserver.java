package com.finapp.payments;

/**
 * Where the hint sweep reports a break (`P15-TSK-036`): {@code payments} sees no metrics library, so the composition root
 * binds this to the webhook meter's {@code unmappable} series. The door counts its own delivery; this counts what the sweep
 * finds later, at the moment it is final, so a denial is never counted while it may still be a lagging read.
 */
@FunctionalInterface
public interface CallbackHintObserver {

    /** One {@code REFUTED}, {@code CONTRADICTED}, {@code UNREADABLE} or {@code EXPIRED} hint, as the sweep found it. */
    void broke(HintedOutcome outcome);

    /** Counts nothing: for a composition with no meters. */
    CallbackHintObserver NONE = outcome -> { };
}
