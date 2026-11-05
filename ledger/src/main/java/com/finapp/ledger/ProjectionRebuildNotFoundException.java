package com.finapp.ledger;

import java.io.Serial;

/**
 * A rebuild decision named a rebuild that does not exist (`P14-TSK-026`). The surface answers one {@code 404} for
 * unknown and malformed alike (`P1-TSK-016`'s malformed-equals-absent); this message is for the log.
 */
public final class ProjectionRebuildNotFoundException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public ProjectionRebuildNotFoundException(ProjectionRebuildId rebuild) {
        super("no projection rebuild " + rebuild);
    }
}
