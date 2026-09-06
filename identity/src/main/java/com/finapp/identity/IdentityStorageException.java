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

    /**
     * A message that has already been made safe to log.
     *
     * <p><strong>There is deliberately no constructor taking a cause.</strong> PostgreSQL puts the
     * entire failing row in a constraint violation's {@code DETAIL}, so a {@code SQLException}
     * attached here would carry that row into every log line that prints this exception - which for
     * these tables means a password, a person's name or a login identifier ({@code INV-AUD-02}).
     * Callers pass {@code DatabaseFailure.describe(...)}, which keeps the SQLState and drops
     * everything else; the absence of the two-argument constructor is what stops the safe path being
     * the one somebody forgets.
     */
    IdentityStorageException(String message) {
        super(message);
    }
}
