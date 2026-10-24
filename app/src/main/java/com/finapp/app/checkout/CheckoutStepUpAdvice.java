package com.finapp.app.checkout;

import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The checkout confirmation door's step-up advice (`P13-TSK-010`; {@code PHASE_13_PLAN.md} sections 7.1, 12.3; ADR-0108
 * decision 3): would the proposed payment be challenged, and does the acting session fail to satisfy the challenge? An
 * {@code app} port, deliberately - the transfer's ({@code TransferStepUpAdvice}) and the withdrawal's
 * ({@code WithdrawalStepUpAdvice}) shape: the customer's checkout surface asks a yes-or-no question in identity's step-up
 * vocabulary and names no type of {@code risk}'s ({@code INV-RSK-08}, {@code NoCustomerSurfaceReadsRiskTest});
 * {@code app}'s risk composition implements it over T-advise.
 *
 * <p><strong>Advice, never authority.</strong> Asked before the confirmation writes anything, in the door's own short
 * transaction that writes nothing; the confirmation's T-eval in its Tx1 decides. A {@code true} here is the door's
 * {@code 403 identity.AssuranceRequired} before any row exists; a judgement that disagrees later (the windows moved, the
 * session was revoked) commits the intent's own refusal - the race's answer.
 */
@FunctionalInterface
public interface CheckoutStepUpAdvice {

    /** Nothing ever requires a step-up - the composition without a risk judgement. */
    CheckoutStepUpAdvice NEVER = (unitOfWork, proposed) -> false;

    /**
     * The payment the caller proposes, as the door resolved it: the caller's party and identity, the merchant the offer
     * is the merchant's, the instrument - the caller's own card, or the caller's own wallet named by its customer account
     * id (the product the money leaves) - and the offer's amount.
     */
    record Proposed(UUID party, UUID identity, UUID merchant, Optional<UUID> walletAccount, Money amount) {
        public Proposed {
            Objects.requireNonNull(party, "party");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(merchant, "merchant");
            Objects.requireNonNull(walletAccount, "walletAccount");
            Objects.requireNonNull(amount, "amount");
        }

        /** Whether the caller pays from their own wallet; otherwise with their own card. */
        public boolean fromWallet() {
            return walletAccount.isPresent();
        }

        @Override
        public String toString() {
            // Never the subjects or the amount: a proposal is the caller's, and a log line is not its place.
            return "CheckoutStepUpAdvice.Proposed";
        }
    }

    /** True when the proposed payment needs a step-up the acting session does not hold. Writes nothing. */
    boolean stepUpRequired(Connection unitOfWork, Proposed proposed);
}
