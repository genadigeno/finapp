package com.finapp.ledger;

import java.io.Serial;

/**
 * A decision named a rebuild already decided differently (`P14-TSK-026`, {@code INV-LIFE-01}; {@code ledger.RebuildNotOpen},
 * a {@code 409}): read it to learn its outcome; a later drift is a new proposal. The converging cases - the same approver's
 * retry, a repeated rejection - never reach this.
 */
public final class RebuildNotOpenException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public RebuildNotOpenException(ProjectionRebuildId rebuild, ProjectionRebuildStatus status) {
        super("projection rebuild " + rebuild + " is already " + status);
    }
}
