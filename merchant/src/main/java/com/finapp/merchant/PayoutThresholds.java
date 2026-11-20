package com.finapp.merchant;

import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;

/**
 * The operator payout four-eyes threshold (`P17-TSK-031`, ADR-0154; finapp-admin-panel ADR-C135 decision 3): versioned per currency in
 * {@code merchant.payout_four_eyes_threshold}, V020. Asked by {@link MerchantPayouts} inside its dispatch transaction, under the
 * attempt lock, with the merchant's non-failed payouts and this one together - so splitting a payout under the threshold does
 * not avoid it, and ten racing payouts cannot add up past it unseen.
 */
public interface PayoutThresholds {

    /** The threshold a cumulative payout amount was judged against: its version and its value. */
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
