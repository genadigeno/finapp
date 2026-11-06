package com.finapp.ledger;

import java.io.Serial;

/**
 * The proposer tried to approve their own rebuild (`P14-TSK-026`, {@code INV-AUD-04}; {@code ledger.RebuildSelfApprovalRefused},
 * a {@code 409}): the rebuild stands for a second person, and nothing was written. The attempt itself is recorded
 * {@code FAILED} ({@link LedgerAuditAction#SELF_APPROVAL_REFUSED}) in a transaction of its own after the act rolled back -
 * {@link ProjectionRebuilds#recordRefusal}.
 */
public final class RebuildSelfApprovalRefusedException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final String target;
    private final String summary;

    public RebuildSelfApprovalRefusedException(ProjectionRebuildId rebuild, LedgerAccountId account) {
        super("projection rebuild " + rebuild + " is approved by someone other than its proposer; the proposer may withdraw it");
        this.target = rebuild.value().toString();
        this.summary = "rebuild=" + rebuild + ", account=" + account + ", act=projection-rebuild:approve";
    }

    /** The rebuild - the refusal record's target. */
    public String target() {
        return target;
    }

    /** What the refusal record names: the rebuild, its account and the act - identifiers only ({@code INV-AUD-02}). */
    public String summary() {
        return summary;
    }
}
