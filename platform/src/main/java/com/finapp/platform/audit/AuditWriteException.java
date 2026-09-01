package com.finapp.platform.audit;

import java.io.Serial;

/**
 * An audit record could not be written.
 *
 * <p>Deliberately unchecked and deliberately never swallowed: the correct response is to let the
 * caller's transaction roll back. Committing the action anyway would leave a privileged or
 * financial operation with no record that it happened, and no later process can discover that —
 * an absent audit record looks exactly like an action that never occurred.
 */
public final class AuditWriteException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    AuditWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
