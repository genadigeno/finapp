package com.finapp.ledger;

import java.io.Serial;

/**
 * A posting-cutoff move the ledger refuses (`P14-TSK-004`, {@code INV-ACC-08}): not exactly one calendar month forward
 * or back, a cutoff over today, or a stale expectation - the cutoff stands somewhere other than the move's
 * {@code from} and not already at its {@code to}.
 *
 * <p>An {@link IllegalStateException} on purpose: accounting commands the cutoff through its own port
 * ({@code PostingCutoff}) and may not see a ledger type (ADR-0113), so the one refusal it handles is a JDK one. When
 * the ledger's {@code V028} trigger refused the move, PostgreSQL has aborted the caller's transaction as well.
 */
public final class IllegalCutoffMoveException extends IllegalStateException {

    @Serial private static final long serialVersionUID = 1L;

    public IllegalCutoffMoveException(String message) {
        super(message);
    }
}
