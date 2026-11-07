package com.finapp.ledger;

import java.io.Serial;

/**
 * An initiator tried to approve their own adjustment proposal (`P3-TSK-021`,
 * {@code INV-AUD-04}): four-eyes means a <em>second</em> authorised person, and nothing is
 * written on this path — the refusal rolls the transaction back, the proposal stays visibly
 * {@code PROPOSED}, and a different operator can still approve it.
 *
 * <p><strong>The attempt itself is recorded</strong> ({@code P14-DOC-001}, the exit review, as
 * `P14-TSK-026` did for the projection rebuild): the manual door's caller records it
 * {@code FAILED} ({@link LedgerAuditAction#SELF_APPROVAL_REFUSED}) in a transaction of its own
 * after the act rolled back - {@link AdjustmentService#recordRefusal}. Until then the refusal
 * answered its {@code 409} and left no trace of the attempt.
 *
 * <p>The message names the proposal and nothing else: no reason, no amount
 * ({@code INV-AUD-02} — an exception message reaches logs).
 */
public final class SelfApprovalRefusedException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final String target;
    private final String summary;

    public SelfApprovalRefusedException(AdjustmentProposalId proposal) {
        super(
                "proposal "
                        + proposal
                        + " cannot be approved by its own initiator (INV-AUD-04): four-eyes"
                        + " requires a second authorised person");
        this.target = proposal.value().toString();
        this.summary = "proposal=" + proposal + ", act=adjustment:approve";
    }

    /** The proposal - the refusal record's target. */
    public String target() {
        return target;
    }

    /** What the refusal record names: the proposal and the act - identifiers only ({@code INV-AUD-02}). */
    public String summary() {
        return summary;
    }
}
