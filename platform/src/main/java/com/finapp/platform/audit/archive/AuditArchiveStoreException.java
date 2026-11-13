package com.finapp.platform.audit.archive;

import java.io.Serial;

/**
 * The archive's store did not answer, answered late, or refused (`P16-TSK-010`). Nothing is marked; the segment stays claimed until
 * its lease lapses and any instance takes it again. The message names the operation and a status or an exception's class, never a
 * key's content, an address or a row, and no cause is attached: a client's exception message can carry the store's address.
 */
public final class AuditArchiveStoreException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public AuditArchiveStoreException(String message) {
        super(message);
    }
}
