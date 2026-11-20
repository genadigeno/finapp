package com.finapp.bnpl;

import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;

/**
 * The operator's BNPL refund four-eyes threshold (`P17-TSK-116`, ADR-0154 decision 4's shape; finapp-admin-panel ADR-C135
 * decision 3): versioned per currency in {@code bnpl.refund_four_eyes_threshold}, V023. Asked by {@link BnplRefunds} inside
 * T-ref under B5 - the purchase's {@code FOR UPDATE} that serialises every refund of the purchase - with the purchase's
 * committed refunds and this one together, so splitting a refund under the threshold does not avoid it, and ten racing
 * refunds cannot add up past it unseen.
 */
public interface BnplRefundThresholds {

    /** The threshold a cumulative refund amount was judged against: its version and its value. */
    record Threshold(int version, Money amount) {
        public Threshold {
            Objects.requireNonNull(amount, "amount");
        }
    }

    /**
     * The latest threshold for {@code cumulative}'s currency when {@code cumulative} is above it - present means a second
     * person decides. A currency with no threshold is above every threshold (fail closed): version 0, amount zero.
     */
    Optional<Threshold> requiresApproval(Connection unitOfWork, Money cumulative);
}
