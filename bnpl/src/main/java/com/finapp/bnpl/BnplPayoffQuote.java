package com.finapp.bnpl;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A BNPL payoff quote as recorded (`P12-TSK-022`, {@code bnpl V014}'s {@code bnpl_payoff_quote}; {@code PHASE_12_PLAN.md}
 * section 7.1 T-pay, A21; lending's {@code PayoffQuote}): what ends the obligation on its good-through date {@code G} -
 * immutable, born under the obligation's row lock (B4) with its inputs. A quote moves no money and binds nothing: it is a
 * promise that, on {@code G}, if nothing moved, {@link #amount()} ends the obligation. RESTRICTED-FINANCIAL.
 *
 * <p>{@code amount = P + PD + IA + ID + FD - CB + projected} - every balance folded from journal lines under B4, the fees
 * counted from {@code BNPL_FEES_DUE} generically (whatever assessed them), the projection {@code ACCRUAL_ENGINE_V1.project}
 * on the current base ({@code P + PD}) for the dates {@code [businessDate, G - 1]} of the accrual span (pay-in-N; pay-in-4 is
 * interest-free and projects nothing). Negative when the credit balance exceeds everything owed: the payoff then debits
 * nothing and refunds {@link #refund()}.
 *
 * @param projectedDays the dates projected - at most {@code G - businessDate}, fewer when the accrual span ends first
 */
public record BnplPayoffQuote(
        UUID id,
        BnplObligationId obligation,
        BnplAgreementId agreement,
        int agreementVersion,
        String requestKey,
        LocalDate businessDate,
        LocalDate goodThrough,
        Money principal,
        Money principalDue,
        Money interestAccrued,
        Money interestDue,
        Money feesDue,
        Money creditBalance,
        Money projectionBase,
        int projectedDays,
        Money projectedInterest,
        Money amount,
        String scheduleEngine,
        Optional<String> accrualEngine,
        String allocationEngine,
        Instant createdAt,
        CorrelationId correlation) {

    public BnplPayoffQuote {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(obligation, "obligation must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
        Objects.requireNonNull(requestKey, "requestKey must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(goodThrough, "goodThrough must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(principalDue, "principalDue must not be null");
        Objects.requireNonNull(interestAccrued, "interestAccrued must not be null");
        Objects.requireNonNull(interestDue, "interestDue must not be null");
        Objects.requireNonNull(feesDue, "feesDue must not be null");
        Objects.requireNonNull(creditBalance, "creditBalance must not be null");
        Objects.requireNonNull(projectionBase, "projectionBase must not be null");
        Objects.requireNonNull(projectedInterest, "projectedInterest must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(scheduleEngine, "scheduleEngine must not be null");
        Objects.requireNonNull(accrualEngine, "accrualEngine must not be null");
        Objects.requireNonNull(allocationEngine, "allocationEngine must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        if (agreementVersion < 1) {
            throw new IllegalArgumentException("an agreement version is numbered from 1");
        }
        if (goodThrough.isBefore(businessDate) || projectedDays < 0
                || projectedDays > goodThrough.toEpochDay() - businessDate.toEpochDay()) {
            throw new IllegalArgumentException("a quote is good through its date or later, projecting at most those dates");
        }
        for (Money part : List.of(principal, principalDue, interestAccrued, interestDue, feesDue, creditBalance,
                projectionBase, projectedInterest)) {
            if (part.isNegative()) {
                throw new IllegalArgumentException("a quote's inputs are never negative");
            }
        }
        if (projectedDays == 0 && !projectedInterest.isZero()) {
            throw new IllegalArgumentException("nothing projected projects no interest");
        }
        if (!amount.equals(parts(principal, principalDue, interestAccrued, interestDue, feesDue, creditBalance,
                projectedInterest))) {
            throw new IllegalArgumentException("a quote's amount is exactly its parts");
        }
    }

    /** {@code P + PD + IA + ID + FD - CB + projected} - the one figure, {@code bnpl V014}'s {@code CHECK}. */
    static Money parts(Money principal, Money principalDue, Money interestAccrued, Money interestDue, Money feesDue,
            Money creditBalance, Money projectedInterest) {
        return principal.plus(principalDue).plus(interestAccrued).plus(interestDue).plus(feesDue).minus(creditBalance)
                .plus(projectedInterest);
    }

    /** What the payoff takes from the wallet on {@code G} - the amount, or zero when the credit balance exceeds it. */
    public Money payable() {
        return amount.isPositive() ? amount : amount.minus(amount);
    }

    /** What the payoff gives back on {@code G} - the credit balance beyond everything owed, or zero. */
    public Money refund() {
        return amount.isNegative() ? amount.negated() : amount.minus(amount);
    }

    /**
     * The product quoted - by its pinned schedule engine, which {@code bnpl V006}'s {@code bnpl_agreement_engines_coherent}
     * holds to the product: pay-in-4's own engine, pay-in-N lending's.
     */
    public BnplProduct product() {
        return BnplEngine.BNPL_SCHEDULE_ENGINE_V1.name().equals(scheduleEngine) ? BnplProduct.BNPL_PAY_IN_4
                : BnplProduct.BNPL_PAY_IN_N;
    }

    /** Identifiers and dates only - never the key or a figure. */
    @Override
    public String toString() {
        return "BnplPayoffQuote[" + id + ", obligation " + obligation.value() + ", good through " + goodThrough + "]";
    }
}
