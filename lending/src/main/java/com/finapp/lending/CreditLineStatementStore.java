package com.finapp.lending;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for a credit line's statements (`P11-TSK-022`, {@code lending V017}): the facts the statement step reads under
 * the line's lock (the latest statement date, a cycle's accruals) and the row it writes - INSERT-only - and one owner-scoped
 * read for the statements door. What a statement still has due is the billed-item model's ({@link InstalmentBillingStore},
 * {@code lending.billing_component_paid}): a statement is a billed item beside an instalment's billing.
 */
public interface CreditLineStatementStore {

    /** The statement date of {@code line}'s latest statement - empty when it has none. */
    Optional<LocalDate> latestStatementDate(Connection unitOfWork, LoanId line);

    /**
     * Every accrual of {@code line} rounded within {@code cycle}, oldest first - exactly the rows a statement of the cycle
     * bills ({@link StatementEngineV1#state} sums them and refuses a list that is not one chain).
     */
    List<InterestAccrual> accrualsIn(Connection unitOfWork, LoanId line, AccrualPeriod cycle);

    /**
     * The fees {@code line} carries into a statement of {@code cycle} (A24, `P11-TSK-025`): its assessments dated in the cycle
     * - {@code [start, endExclusive)} - less what approved fee waivers forgave of them, in {@code zero}'s currency and scale.
     * Each fee is carried once: cycles tile the calendar, and no fee is dated inside a cycle already stated.
     * {@code lending V021}'s trigger holds the statement to the same sum.
     */
    Money feesCarriedIn(Connection unitOfWork, LoanId line, AccrualPeriod cycle, Money zero);

    /** Births one statement; the database stamps the instant. */
    void insert(Connection unitOfWork, CreditLineStatement statement, CorrelationId correlation);

    /**
     * Every statement of {@code party}'s line {@code line}, oldest first, with what has been paid of each - owner-scoped in
     * the statement: another party's line is the same empty answer as a line stated nothing.
     */
    List<StatedItem> statementsOwnedBy(Connection unitOfWork, LoanId line, UUID party);

    /**
     * One statement and what has been paid of it, per component - every payer's allocations less reversed ones. A statement
     * bills no fee itself (its carried fees are due on the items they were assessed on): its fees are the late fees assessed
     * on it less approved fee waivers, its interest its figure less approved interest waivers ({@code P11-TSK-025},
     * {@code lending.billing_component_billed}).
     *
     * @param feesBilled the fees assessed on the statement as an item, less approved fee waivers
     * @param interestWaived the interest approved waivers forgave of its billed interest
     */
    record StatedItem(CreditLineStatement statement, Money feesBilled, Money interestWaived, Money feesPaid,
            Money interestPaid, Money principalPaid) {

        public StatedItem {
            Objects.requireNonNull(statement, "statement must not be null");
            Objects.requireNonNull(feesBilled, "feesBilled must not be null");
            Objects.requireNonNull(interestWaived, "interestWaived must not be null");
            Objects.requireNonNull(feesPaid, "feesPaid must not be null");
            Objects.requireNonNull(interestPaid, "interestPaid must not be null");
            Objects.requireNonNull(principalPaid, "principalPaid must not be null");
        }

        /** What the statement's item still has due, per component - billed less paid. */
        public DueState.DueItem dueItem() {
            Statement figures = statement.statement();
            return new DueState.DueItem(DueItemId.of(statement.id()), statement.dueDate(), feesBilled.minus(feesPaid),
                    figures.interestBilled().minus(interestWaived).minus(interestPaid),
                    figures.principalBilled().minus(principalPaid));
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
            return "StatedItem[" + statement + "]";
        }
    }
}
