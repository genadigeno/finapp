package com.finapp.payments;

import com.finapp.sharedkernel.money.Money;
import java.io.Serial;
import java.util.Objects;

/**
 * A refund above the four-eyes threshold (`P17-TSK-031`, ADR-0154): judged inside the dispatch transaction under the attempt
 * lock, which rolls back whole - nothing held, written or sent, the key's claim with it. The door turns it into a proposal
 * for a second person; the refund itself is unchanged.
 */
public final class RefundRequiresApprovalException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final transient RefundThresholds.Threshold threshold;
    private final transient Money cumulative;

    public RefundRequiresApprovalException(RefundThresholds.Threshold threshold, Money cumulative) {
        super("the refund, with the payment's refunds before it, is " + cumulative + " - above the four-eyes threshold of "
                + threshold.amount() + " (version " + threshold.version() + ")");
        this.threshold = Objects.requireNonNull(threshold, "threshold");
        this.cumulative = Objects.requireNonNull(cumulative, "cumulative");
    }

    /** The threshold version and value the refund was judged against. */
    public RefundThresholds.Threshold threshold() {
        return threshold;
    }

    /** The payment's non-failed refunds and this one, together. */
    public Money cumulative() {
        return cumulative;
    }
}
