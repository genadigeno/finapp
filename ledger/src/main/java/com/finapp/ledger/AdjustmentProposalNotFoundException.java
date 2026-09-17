package com.finapp.ledger;

import java.io.Serial;

/**
 * A decision named a proposal that does not exist (`P3-TSK-021`). The surface answers one
 * {@code 404} for unknown and malformed alike (`P1-TSK-016`'s malformed-equals-absent), so
 * this message is for the log; the client detail is the boundary's.
 */
public final class AdjustmentProposalNotFoundException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public AdjustmentProposalNotFoundException(AdjustmentProposalId proposal) {
        super("no adjustment proposal " + proposal);
    }
}
