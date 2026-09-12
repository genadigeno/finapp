package com.finapp.consent;

import java.io.Serial;

/**
 * A consent row could not be written or read.
 *
 * <p>The {@code PartyStorageException} shape, for its recorded reasons: unchecked and never
 * swallowed, and <strong>no constructor taking a cause</strong> — PostgreSQL puts the entire
 * failing row in a constraint violation's {@code DETAIL}, and a consent row pairs a person
 * with their consent posture ({@code CONFIDENTIAL}). Callers pass
 * {@code DatabaseFailure.describe(...)}, which keeps the SQLState and drops everything else.
 */
public final class ConsentStorageException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public ConsentStorageException(String message) {
        super(message);
    }
}
