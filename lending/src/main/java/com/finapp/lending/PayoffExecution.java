package com.finapp.lending;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A payoff quote executed (`P11-TSK-026`, {@code lending V022}'s {@code payoff_execution}; {@code PHASE_11_PLAN.md}
 * section 5.4): born once per quote and once per loan, on the quote's good-through date, in the transaction that accrues,
 * bills, repays and closes the account - which it alone admits, and without which it cannot commit. The money is the
 * payoff's one repayment (kind {@link RepaymentKind#PAYOFF}, keyed by the quote) and the closure's refund.
 *
 * @param requestKey the executing requester's key
 * @param businessDate the quote's good-through date - the business date it was executed on
 */
public record PayoffExecution(UUID id, UUID quote, LoanId loan, String requestKey, LocalDate businessDate,
        Instant executedAt) {

    public PayoffExecution {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(quote, "quote must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(requestKey, "requestKey must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(executedAt, "executedAt must not be null");
    }

    /** Identifiers and the date only - never the key. */
    @Override
    public String toString() {
        return "PayoffExecution[" + id + ", quote " + quote + ", loan " + loan.value() + ", " + businessDate + "]";
    }
}
