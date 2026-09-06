package com.finapp.identity;

import java.io.Serial;

/**
 * An identity row could not be written for a reason other than the identifier being taken.
 *
 * <p>Unchecked and never swallowed: the caller's transaction must fail. An identity committed
 * without the party it names would be an orphan the schema cannot detect, because ADR-0029
 * deliberately places no foreign key across the module boundary.
 *
 * <p><strong>The message never carries the login identifier.</strong> It is {@code CONFIDENTIAL}
 * because it carries <em>existence</em>, and an exception message reaches a log line
 * ({@code INV-AUD-02}, {@code INV-IDN-07}).
 */
public final class IdentityStorageException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    IdentityStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
