package com.finapp.app.payments;

import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;

/**
 * The withdrawal door's step-up advice (`P13-TSK-009`; {@code PHASE_13_PLAN.md} sections 7.1, 12.3; ADR-0108 decision
 * 3): would the proposed withdrawal be challenged, and does the acting session fail to satisfy the challenge? An
 * {@code app} port, deliberately - the transfer door's ({@code TransferStepUpAdvice}) shape: the customer's withdrawal
 * surface asks a yes-or-no question in identity's step-up vocabulary and names no type of {@code risk}'s
 * ({@code INV-RSK-08}, {@code NoCustomerSurfaceReadsRiskTest}); {@code app}'s risk composition implements it over
 * T-advise.
 *
 * <p><strong>Advice, never authority.</strong> Asked before the command, in the door's own short transaction that writes
 * nothing; the command's in-lock judgement decides. A {@code true} here is the door's
 * {@code 403 identity.AssuranceRequired} before any row exists; a judgement that disagrees later (the windows moved, the
 * session was revoked) commits the withdrawal's own {@code FAILED(RISK_REFUSED)} - the race's answer.
 */
@FunctionalInterface
public interface WithdrawalStepUpAdvice {

    /** Nothing ever requires a step-up - the composition without a risk judgement. */
    WithdrawalStepUpAdvice NEVER = (unitOfWork, proposed) -> false;

    /**
     * The withdrawal the caller proposes, as the door resolved it: the caller's party and identity, the wallet's
     * customer account id (the product the money leaves), and the amount.
     */
    record Proposed(UUID party, UUID identity, UUID sourceAccount, Money amount) {
        public Proposed {
            Objects.requireNonNull(party, "party");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(sourceAccount, "sourceAccount");
            Objects.requireNonNull(amount, "amount");
        }

        @Override
        public String toString() {
            // Never the subjects or the amount: a proposal is the caller's, and a log line is not its place.
            return "WithdrawalStepUpAdvice.Proposed";
        }
    }

    /** True when the proposed withdrawal needs a step-up the acting session does not hold. Writes nothing. */
    boolean stepUpRequired(Connection unitOfWork, Proposed proposed);
}
