package com.finapp.platform.inbox;

import java.io.Serial;

/**
 * The inbox record could not be read or written.
 *
 * <p>Raised only for genuine storage failures. A duplicate delivery and a contended one are
 * <em>outcomes</em>, not exceptions: they are the mechanism working, and an at-least-once
 * transport produces both routinely. Turning them into exceptions would make an ordinary
 * redelivery indistinguishable from a broken database, and consumers would learn to catch and
 * ignore the one exception that also means "the inbox is unavailable".
 */
public final class InboxStorageException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    InboxStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
