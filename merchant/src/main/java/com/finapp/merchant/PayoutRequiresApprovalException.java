package com.finapp.merchant;

import com.finapp.sharedkernel.money.Money;
import java.io.Serial;
import java.util.Objects;

/**
 * An operator payout above the four-eyes threshold (`P17-TSK-031`, ADR-0154): judged inside the dispatch transaction under the attempt
 * lock, which rolls back whole - nothing held, written or sent, the key's claim with it. The door turns it into a proposal
 * for a second person; the payout itself is unchanged.
 */
public final class PayoutRequiresApprovalException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final transient PayoutThresholds.Threshold threshold;
    private final transient Money cumulative;

    public PayoutRequiresApprovalException(PayoutThresholds.Threshold threshold, Money cumulative) {
        // No amount in the message: money never reaches a text sink (MoneyReachesNoTextSinkTest); the figures are the accessors'.
        super("the operator's payout, with the merchant's payouts before it, is above the four-eyes threshold (version "
                + threshold.version() + ")");
        this.threshold = Objects.requireNonNull(threshold, "threshold");
        this.cumulative = Objects.requireNonNull(cumulative, "cumulative");
    }

    /** The threshold version and value the payout was judged against. */
    public PayoutThresholds.Threshold threshold() {
        return threshold;
    }

    /** The merchant's non-failed payouts and this one, together. */
    public Money cumulative() {
        return cumulative;
    }
}
