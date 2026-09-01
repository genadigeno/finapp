package com.finapp.platform.outbox;

import java.io.Serial;

/**
 * An event could not be queued for publication.
 *
 * <p>Deliberately unchecked and deliberately not swallowed anywhere: the correct response is to
 * let the caller's transaction roll back. Committing the fact anyway would produce exactly what
 * {@code INV-EVT-01} forbids — a state change nobody is ever told about, discovered later as a
 * reconciliation break with no explanation attached.
 */
public final class OutboxWriteException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    OutboxWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
