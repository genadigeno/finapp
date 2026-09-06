package com.finapp.party;

import java.io.Serial;

/**
 * A party or customer row could not be written.
 *
 * <p>Unchecked and never swallowed: the caller's transaction must fail. A registration that
 * committed a Customer without its Party, or an Identity without either, is exactly the partial
 * state {@code P1-TSK-006}'s acceptance criterion forbids - and, because there is no cross-schema
 * foreign key (ADR-0029), nothing at the database level would notice.
 *
 * <p><strong>The message never carries a column value.</strong> These rows hold a person's name,
 * which is {@code RESTRICTED-PII}, and an exception message reaches a log line
 * ({@code INV-AUD-02}).
 */
public final class PartyStorageException extends RuntimeException {

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
    public PartyStorageException(String message) {
        super(message);
    }
}
