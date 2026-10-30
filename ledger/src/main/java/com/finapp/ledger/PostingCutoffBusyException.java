package com.finapp.ledger;

import java.io.Serial;

/**
 * A posting-cutoff move could not take its exclusive key in time (`P14-TSK-004`): every bounded attempt
 * ({@code PostingCutoffs.LOCK_WAIT} × {@code LOCK_ATTEMPTS}) found a posting transaction still holding the key shared.
 * Nothing moved and the caller's transaction is still usable; the act is retried later. Transient, never a refusal of
 * the move's shape.
 *
 * <p>An {@link IllegalStateException} for the reason {@link IllegalCutoffMoveException} is: accounting's port sees JDK
 * types only (ADR-0113).
 */
public final class PostingCutoffBusyException extends IllegalStateException {

    @Serial private static final long serialVersionUID = 1L;

    public PostingCutoffBusyException(String message) {
        super(message);
    }
}
