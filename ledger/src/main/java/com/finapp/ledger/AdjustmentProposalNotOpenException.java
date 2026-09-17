package com.finapp.ledger;

import java.io.Serial;

/**
 * A decision was attempted on a proposal that is already decided (`P3-TSK-021`,
 * {@code INV-LIFE-04}): both terminal states are terminal, and a new adjustment is a new
 * proposal. The converging cases never reach this refusal — the same approver's retried
 * approval replays the recorded entry, and a repeated rejection converges — so what this
 * names is a genuine conflict: a <em>different</em> decision than the one already recorded.
 */
public final class AdjustmentProposalNotOpenException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public AdjustmentProposalNotOpenException(
            AdjustmentProposalId proposal, AdjustmentProposalStatus status) {
        super("proposal " + proposal + " is already decided: " + status + " is terminal"
                + " (INV-LIFE-04), and a new adjustment is a new proposal");
    }
}
