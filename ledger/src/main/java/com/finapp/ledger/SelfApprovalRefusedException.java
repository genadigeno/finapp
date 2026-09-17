package com.finapp.ledger;

import java.io.Serial;

/**
 * An initiator tried to approve their own adjustment proposal (`P3-TSK-021`,
 * {@code INV-AUD-04}): four-eyes means a <em>second</em> authorised person, and nothing is
 * written on this path — the refusal rolls the transaction back, the proposal stays visibly
 * {@code PROPOSED}, and a different operator can still approve it.
 *
 * <p>The message names the proposal and nothing else: no reason, no amount
 * ({@code INV-AUD-02} — an exception message reaches logs).
 */
public final class SelfApprovalRefusedException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public SelfApprovalRefusedException(AdjustmentProposalId proposal) {
        super(
                "proposal "
                        + proposal
                        + " cannot be approved by its own initiator (INV-AUD-04): four-eyes"
                        + " requires a second authorised person");
    }
}
