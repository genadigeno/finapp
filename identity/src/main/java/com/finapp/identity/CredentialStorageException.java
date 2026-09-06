package com.finapp.identity;

import java.io.Serial;

/**
 * A credential row could not be read or written.
 *
 * <p>Unchecked and never swallowed: the caller's transaction must fail. A registration that
 * committed an identity without its credential produces a login nobody can use and nothing reports.
 *
 * <p><strong>No message here ever names the derivation</strong>, or anything computed from it. It
 * is not the plaintext, but it is offline-crackable material, and an exception message reaches a
 * log line ({@code INV-AUD-02}).
 */
public final class CredentialStorageException extends RuntimeException {

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
    CredentialStorageException(String message) {
        super(message);
    }
}
