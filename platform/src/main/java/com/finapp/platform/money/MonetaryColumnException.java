package com.finapp.platform.money;

import java.io.Serial;

/**
 * A stored monetary value could not be read back as the amount that was written.
 *
 * <p>Unchecked, and never recovered from by substituting a default. A row whose amount,
 * currency or scale is missing or invalid is a corrupt financial record; returning zero, or
 * guessing the currency, would turn a detectable corruption into a wrong balance that
 * reconciliation may never explain.
 */
public final class MonetaryColumnException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    MonetaryColumnException(String message) {
        super(message);
    }

    MonetaryColumnException(String message, Throwable cause) {
        super(message, cause);
    }
}
