package com.finapp.app.transfers;

import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;

/**
 * The transfer door's step-up advice (`P13-TSK-008`; {@code PHASE_13_PLAN.md} sections 7.1, 12.3; ADR-0108 decision 3):
 * would the proposed transfer be challenged, and does the acting session fail to satisfy the challenge? An {@code app}
 * port, deliberately - the customer's transfer surface asks a yes-or-no question in identity's step-up vocabulary and
 * names no type of {@code risk}'s ({@code INV-RSK-08}, {@code NoCustomerSurfaceReadsRiskTest}): the answer carries
 * nothing of a decision, a score, a rule or a reason. {@code app}'s risk composition implements it over T-advise.
 *
 * <p><strong>Advice, never authority.</strong> Asked before the command, in the door's own short transaction that
 * writes nothing; the command's in-lock judgement decides. A {@code true} here is the door's
 * {@code 403 identity.AssuranceRequired} before any row exists; a judgement that disagrees later (the windows moved, the
 * session was revoked) commits the transfer's own {@code FAILED(RISK_REFUSED)} - the race's answer.
 */
@FunctionalInterface
public interface TransferStepUpAdvice {

    /** Nothing ever requires a step-up - the composition without a risk judgement. */
    TransferStepUpAdvice NEVER = (unitOfWork, proposed) -> false;

    /**
     * The transfer the caller proposes, as the door resolved it: the caller's party and identity, the source and
     * destination customer account ids (the products the caller named), and the amount.
     */
    record Proposed(UUID party, UUID identity, UUID sourceAccount, UUID destinationAccount, Money amount) {
        public Proposed {
            Objects.requireNonNull(party, "party");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(sourceAccount, "sourceAccount");
            Objects.requireNonNull(destinationAccount, "destinationAccount");
            Objects.requireNonNull(amount, "amount");
        }

        @Override
        public String toString() {
            // Never the subjects or the amount: a proposal is the caller's, and a log line is not its place.
            return "TransferStepUpAdvice.Proposed";
        }
    }

    /** True when the proposed transfer needs a step-up the acting session does not hold. Writes nothing. */
    boolean stepUpRequired(Connection unitOfWork, Proposed proposed);
}
