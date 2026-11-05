package com.finapp.ledger;

import java.io.Serial;

/**
 * A rebuild was proposed for a ledger account that does not exist (`P14-TSK-026`) - one {@code 404} with a malformed
 * identifier; this message is for the log.
 */
public final class ProjectionRebuildAccountNotFoundException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public ProjectionRebuildAccountNotFoundException(LedgerAccountId account) {
        super("no ledger account " + account + " to rebuild");
    }
}
