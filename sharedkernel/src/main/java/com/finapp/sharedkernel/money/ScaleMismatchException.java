package com.finapp.sharedkernel.money;

import java.io.Serial;

/**
 * Two amounts in the same currency were created with different scales.
 *
 * <p>Distinct from {@link CurrencyMismatchException} because the cause is different and so is
 * the fix. The currencies agree; what differs is the minor-unit definition each amount was
 * created under. In practice this means a historical amount, rehydrated with its stored
 * scale, has met an amount created against the current definition.
 *
 * <p>Combining them would require reinterpreting one of the two, which is a decision about
 * currency redenomination — not something arithmetic should perform on its own.
 */
public final class ScaleMismatchException extends MonetaryException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final CurrencyCode currency;
    private final int leftScale;
    private final int rightScale;

    ScaleMismatchException(CurrencyCode currency, int leftScale, int rightScale, String operation) {
        super("Cannot " + operation + " two " + currency + " amounts with different scales ("
                + leftScale + " and " + rightScale + "). One of them was created under a "
                + "different minor-unit definition; reinterpreting it is a redenomination "
                + "decision, not an arithmetic one.");
        this.currency = currency;
        this.leftScale = leftScale;
        this.rightScale = rightScale;
    }

    public CurrencyCode currency() {
        return currency;
    }

    public int leftScale() {
        return leftScale;
    }

    public int rightScale() {
        return rightScale;
    }
}
