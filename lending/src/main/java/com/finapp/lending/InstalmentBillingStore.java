package com.finapp.lending;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for billing and the credit-balance application (`P11-TSK-017`, {@code lending V012}): the facts the
 * billing step reads under the loan's lock (the latest schedule, a period's accruals, the billed items and what has been
 * paid of them) and the rows it writes - INSERT-only. The billed-item model the allocation engine's due state is derived
 * from: {@code P11-TSK-018}'s repayment allocations join {@link BilledItem}'s paid figures, {@code -022}'s statements
 * reuse it. One owner-scoped read serves the schedule door.
 */
public interface InstalmentBillingStore {

    /** An instalment loan's latest schedule version, with its instalments oldest first; empty when it has none. */
    Optional<BillableSchedule> latestSchedule(Connection unitOfWork, LoanId loan);

    /**
     * The accruals of {@code loan} rounded within {@code period} - their count and the sum of what they posted, in
     * {@code currency} at {@code scale}: exactly the interest a billing of the period makes due (ADR-0094 point 6).
     */
    PeriodAccruals periodAccruals(Connection unitOfWork, LoanId loan, AccrualPeriod period, Money zero);

    /** Every billed item of {@code loan}, oldest due date first, with what has been paid of each. */
    List<BilledItem> billedItems(Connection unitOfWork, LoanId loan);

    /**
     * Every billed item of {@code loan} - an instalment loan's billings or a line's statements ({@code P11-TSK-022}) - with
     * what it still has due per component (billed less every payer's allocations, through
     * {@code lending.billing_component_paid}), oldest due date first: the one derivation every payer allocates against and
     * the collection step collects ({@link LoanReceivables}, {@link LoanCollections}). A statement falls due on its minimum
     * payment's due date.
     */
    List<DueState.DueItem> dueItems(Connection unitOfWork, LoanId loan);

    /**
     * Every billed item of {@code party}'s loan {@code loan}, oldest due date first, with what has been paid of each -
     * owner-scoped in the statement: another party's loan is the same empty answer as a loan billed nothing.
     */
    List<BilledItem> billedItemsOwnedBy(Connection unitOfWork, LoanId loan, UUID party);

    /** Births one billing; the database stamps the instant. */
    void insertBilling(Connection unitOfWork, InstalmentBilling billing, CorrelationId correlation);

    /** Births one application and its allocation rows (ordinals 1..n in the lines' order); the database stamps it. */
    void insertApplication(Connection unitOfWork, CreditBalanceApplication application, CorrelationId correlation);

    /**
     * The number of {@code loan}'s latest catch-up application ({@code lending V029}), {@code 0} when it has none - read
     * under the caller's L3, so the next one's number is this plus one.
     */
    int latestCatchUpSequence(Connection unitOfWork, LoanId loan);

    /** A period's accruals: how many rows and what they posted. */
    record PeriodAccruals(long rows, Money posted) {

        public PeriodAccruals {
            Objects.requireNonNull(posted, "posted must not be null");
            if (rows < 0 || posted.isNegative()) {
                throw new IllegalArgumentException("a period's accruals are counted and summed from zero");
            }
        }

        @Override
        public String toString() {
            return "PeriodAccruals[" + rows + " rows]";
        }
    }

    /**
     * One billed item and what has been paid of it, per component - every allocation that pays a billed item (the
     * credit-balance applications, {@code P11-TSK-018}'s repayments, less what a reversal undid). A billing bills no fee
     * itself: its fees are the late fees assessed on it less the fee waivers approved of them ({@code P11-TSK-025}), and its
     * interest the billing's figure less the interest waivers approved on it - {@code lending.billing_component_billed}.
     *
     * @param feesBilled the fees assessed on the item, less approved fee waivers
     * @param interestWaived the interest approved waivers forgave of the item's billed interest
     * @param principalRescheduled the billed principal accepted amendments re-scheduled as not due (`P11-TSK-027`, rule 13)
     */
    record BilledItem(InstalmentBilling billing, Money feesBilled, Money interestWaived, Money principalRescheduled,
            Money feesPaid, Money interestPaid, Money principalPaid) {

        public BilledItem {
            Objects.requireNonNull(billing, "billing must not be null");
            Objects.requireNonNull(feesBilled, "feesBilled must not be null");
            Objects.requireNonNull(interestWaived, "interestWaived must not be null");
            Objects.requireNonNull(principalRescheduled, "principalRescheduled must not be null");
            Objects.requireNonNull(feesPaid, "feesPaid must not be null");
            Objects.requireNonNull(interestPaid, "interestPaid must not be null");
            Objects.requireNonNull(principalPaid, "principalPaid must not be null");
        }

        /** What the item still has due, per component - billed less paid; the allocation engine's input. */
        public DueState.DueItem dueItem() {
            return new DueState.DueItem(DueItemId.of(billing.id()), billing.dueDate(), feesBilled.minus(feesPaid),
                    billing.interestDue().minus(interestWaived).minus(interestPaid),
                    billing.principalDue().minus(principalRescheduled).minus(principalPaid));
        }

        /** Everything still due on it. */
        public Money outstanding() {
            return dueItem().total();
        }

        /** Everything paid of it. */
        public Money paid() {
            return feesPaid.plus(interestPaid).plus(principalPaid);
        }

        @Override
        public String toString() {
            return "BilledItem[" + billing + "]";
        }
    }
}
