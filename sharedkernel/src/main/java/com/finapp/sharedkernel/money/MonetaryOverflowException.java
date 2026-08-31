package com.finapp.sharedkernel.money;

import java.io.Serial;

/**
 * A monetary result is outside the representable range.
 *
 * <p>{@code INV-MON-06}: overflow is rejected, never wrapped. Silent wraparound turns a large
 * credit into a large debit — a defect that produces a plausible-looking number and is found,
 * if at all, at reconciliation.
 */
public final class MonetaryOverflowException extends MonetaryException {

    @Serial
    private static final long serialVersionUID = 1L;

    MonetaryOverflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
