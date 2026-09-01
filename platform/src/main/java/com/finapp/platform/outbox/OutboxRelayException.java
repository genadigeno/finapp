package com.finapp.platform.outbox;

import java.io.Serial;

/**
 * The relay could not run a poll cycle.
 *
 * <p>Raised for infrastructure failures the relay cannot route around — it could not reach its
 * own database, or a statement failed. It is deliberately <em>not</em> raised when a publication
 * attempt fails: that is an expected condition with a designed response (record the attempt,
 * back off, retry), and turning it into an exception would make an ordinary broker hiccup
 * indistinguishable from a broken relay.
 *
 * <p>Nothing is lost when this is thrown. The row is still pending, the transaction rolled back,
 * and the next poll picks it up.
 */
public final class OutboxRelayException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    OutboxRelayException(String message, Throwable cause) {
        super(message, cause);
    }
}
