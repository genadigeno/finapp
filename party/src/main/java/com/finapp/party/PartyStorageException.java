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

    public PartyStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
