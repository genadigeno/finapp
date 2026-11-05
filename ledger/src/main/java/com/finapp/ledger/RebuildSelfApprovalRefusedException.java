package com.finapp.ledger;

import java.io.Serial;

/**
 * The proposer tried to approve their own rebuild (`P14-TSK-026`, {@code INV-AUD-04}; {@code ledger.RebuildSelfApprovalRefused},
 * a {@code 409}): the rebuild stands for a second person, and nothing was written.
 */
public final class RebuildSelfApprovalRefusedException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public RebuildSelfApprovalRefusedException(ProjectionRebuildId rebuild) {
        super("projection rebuild " + rebuild + " is approved by someone other than its proposer; the proposer may withdraw it");
    }
}
