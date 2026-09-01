package com.finapp.platform.idempotency;

import java.io.Serial;

/**
 * A known key was presented with a materially different request ({@code INV-IDEM-03}).
 *
 * <p>Deliberately distinct from every other failure. Returning the first request's response
 * would hide a client defect and could conceal a fraud attempt — someone reusing an observed
 * key to have a different transfer accepted. Re-executing would produce a second financial
 * effect. The only safe answer is to refuse, loudly enough that the caller notices.
 *
 * <p>The stored fingerprint is not exposed: it is derived from another request's content.
 */
public final class IdempotencyConflictException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;


    private final IdempotencyKey key;

    IdempotencyConflictException(IdempotencyKey key) {
        super("Idempotency key " + key + " was already used for a different request (INV-IDEM-03)");
        this.key = key;
    }

    public IdempotencyKey key() {
        return key;
    }
}
