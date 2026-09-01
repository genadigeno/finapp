package com.finapp.platform.idempotency;

import java.io.Serial;

/**
 * The idempotency store could not be read or written.
 *
 * <p>Distinct from {@link IdempotencyConflictException} and
 * {@link IdempotencyInProgressException}, which are outcomes of a working mechanism. This one
 * means the mechanism itself is unavailable — and because the claim and the command share a
 * transaction, the correct response is to let that transaction roll back rather than to proceed
 * without the guarantee.
 */
public final class IdempotencyStorageException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;


    IdempotencyStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
