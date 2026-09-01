package com.finapp.platform.idempotency;

import java.io.Serial;

/**
 * The same command is already running under this key, and its outcome is not yet known.
 *
 * <p><strong>Why this is an outcome and not a wait.</strong> The alternatives are worse. Waiting
 * ties up a connection and a thread for as long as the other command takes, which under a
 * retrying client is how a hot key exhausts a pool. Re-executing assumes the first attempt
 * failed, and it may have already committed — that is the second financial effect this whole
 * mechanism exists to prevent ({@code INV-LIFE-03}: an unknown outcome is a modelled state,
 * never an assumed one).
 *
 * <p>So the caller is told, deterministically, that the answer is not available yet and the
 * request should be retried. That is honest, bounded, and safe.
 */
public final class IdempotencyInProgressException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;


    private final IdempotencyKey key;

    IdempotencyInProgressException(IdempotencyKey key) {
        super("Idempotency key " + key + " is claimed by a command still in progress; "
                + "its outcome is unknown, so retry rather than assume");
        this.key = key;
    }

    public IdempotencyKey key() {
        return key;
    }
}
