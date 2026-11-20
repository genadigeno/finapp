package com.finapp.bnpl;

import com.finapp.sharedkernel.money.Money;
import java.io.Serial;
import java.util.Objects;

/**
 * An operator's BNPL refund above the four-eyes threshold (`P17-TSK-116`, ADR-0154 decision 4's shape): judged in T-ref
 * under B5, before anything further is locked or written, so the transaction rolls back whole - the key's claim with it.
 * The door turns it into a proposal for a second person; the refund itself is unchanged.
 */
public final class BnplRefundRequiresApprovalException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final transient BnplRefundThresholds.Threshold threshold;
    private final transient Money cumulative;

    public BnplRefundRequiresApprovalException(BnplRefundThresholds.Threshold threshold, Money cumulative) {
        super("the refund, with the purchase's committed refunds before it, is above the four-eyes threshold (version "
                + threshold.version() + ")");
        this.threshold = Objects.requireNonNull(threshold, "threshold");
        this.cumulative = Objects.requireNonNull(cumulative, "cumulative");
    }

    /** The threshold version and value the refund was judged against. */
    public BnplRefundThresholds.Threshold threshold() {
        return threshold;
    }

    /** The purchase's committed refunds, of every door, and this one together. */
    public Money cumulative() {
        return cumulative;
    }
}
