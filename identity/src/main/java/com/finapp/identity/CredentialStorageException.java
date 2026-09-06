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

    CredentialStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
