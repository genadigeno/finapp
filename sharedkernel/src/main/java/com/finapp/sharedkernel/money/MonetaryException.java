package com.finapp.sharedkernel.money;

/**
 * A monetary operation that could not be performed correctly.
 *
 * <p>Unchecked by design. Every subclass represents a programming error or a corrupt record,
 * not a business outcome a caller should be routinely recovering from: adding dollars to yen
 * is a bug, and so is an amount that does not fit in the representable range. Making these
 * checked would encourage catch-and-continue at the call site, which is how a monetary defect
 * becomes a silently wrong number instead of a loud failure.
 */
public abstract class MonetaryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    protected MonetaryException(String message) {
        super(message);
    }

    protected MonetaryException(String message, Throwable cause) {
        super(message, cause);
    }
}
