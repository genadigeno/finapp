package com.finapp.lending;

import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * A payoff quote as recorded (`P11-TSK-026`, {@code lending V022}'s {@code payoff_quote}; ADR-0095 point 8,
 * {@code PHASE_11_PLAN.md} sections 5.4 and 12.5, {@code LENDING_LIFECYCLES.md} section 4.10): what ends the loan or line on
 * its good-through date {@code G} - immutable, born under the loan's row lock with its inputs, so the figure is explained
 * for ever by what it was computed from.
 *
 * <p>{@code outstanding} = {@code principal + principalDue + interestAccrued + interestDue + feesDue - creditBalance +
 * projectedInterest}: the five receivables and the credit balance read from the ledger on {@code businessDate} (every
 * elapsed date accrued first), plus the interest {@link AccrualEngineV1} projects for the {@code projectedDays} dates
 * {@code [businessDate, G - 1]} on {@code projectionBase} - the principal outstanding then. Negative when the credit balance
 * exceeds everything owed: the payoff then pays nothing and refunds the difference.
 *
 * @param requestKey the requester's key - the arbiter a retried key converges on
 * @param businessDate the business date the quote was made on, in the agreement's pinned zone
 * @param goodThrough {@code G} - the one date the quote can be executed on
 */
public record PayoffQuote(
        UUID id,
        LoanId loan,
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
        Money outstanding,
        LoanAgreementId agreementId,
        int agreementVersion,
        EngineVersion accrualEngine,
        EngineVersion allocationEngine,
        Instant createdAt) {

    public PayoffQuote {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
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
        Objects.requireNonNull(outstanding, "outstanding must not be null");
        Objects.requireNonNull(agreementId, "agreementId must not be null");
        Objects.requireNonNull(accrualEngine, "accrualEngine must not be null");
        Objects.requireNonNull(allocationEngine, "allocationEngine must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (goodThrough.isBefore(businessDate) || projectedDays != ChronoUnit.DAYS.between(businessDate, goodThrough)) {
            throw new IllegalArgumentException("a quote projects exactly the dates from its business date to G - 1");
        }
        for (Money part : new Money[] {principal, principalDue, interestAccrued, interestDue, feesDue, creditBalance,
                projectionBase, projectedInterest}) {
            if (part.isNegative()) {
                throw new IllegalArgumentException("a quote's inputs are not negative");
            }
        }
        if (!outstanding.equals(principal.plus(principalDue).plus(interestAccrued).plus(interestDue).plus(feesDue)
                .minus(creditBalance).plus(projectedInterest))) {
            throw new IllegalArgumentException("a quote's outstanding is exactly its parts");
        }
        if (agreementVersion < 1) {
            throw new IllegalArgumentException("an agreement version is numbered from 1");
        }
    }

    /** What the payoff takes from the wallet, net: the outstanding when positive, else nothing. */
    public Money amount() {
        return outstanding.isPositive() ? outstanding : outstanding.minus(outstanding);
    }

    /** What the payoff gives back, net: the excess of the credit balance over everything owed, else nothing. */
    public Money refund() {
        return outstanding.isNegative() ? outstanding.negated() : outstanding.minus(outstanding);
    }

    /** Identifiers and dates only - never a figure or the key ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "PayoffQuote[" + id + ", loan " + loan.value() + ", good through " + goodThrough + "]";
    }
}
