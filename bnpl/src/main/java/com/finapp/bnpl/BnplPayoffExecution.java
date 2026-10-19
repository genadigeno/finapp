package com.finapp.bnpl;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A BNPL payoff quote executed (`P12-TSK-022`, {@code bnpl V014}'s {@code bnpl_payoff_execution}; lending's
 * {@code PayoffExecution}): once per quote and once per obligation, on the quote's good-through date only - born first in
 * T-pay, it admits the early billing, the {@code PAYOFF} repayment and the {@code PAID_OFF_EARLY} closure in its own
 * transaction, and a deferred check holds it to its obligation closed at commit. CONFIDENTIAL.
 *
 * @param businessDate the database clock's business date - the quote's good-through date
 * @param valueDate the date every fact of the payoff takes: {@code max(businessDate, floor)}, the floor read under B4
 *     before the payoff writes anything (the Phase 11 gate's RG-M1 not repeated)
 */
public record BnplPayoffExecution(
        UUID id,
        UUID quote,
        BnplObligationId obligation,
        String requestKey,
        LocalDate businessDate,
        LocalDate valueDate,
        Instant executedAt,
        CorrelationId correlation) {

    public BnplPayoffExecution {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(quote, "quote must not be null");
        Objects.requireNonNull(obligation, "obligation must not be null");
        Objects.requireNonNull(requestKey, "requestKey must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(valueDate, "valueDate must not be null");
        Objects.requireNonNull(executedAt, "executedAt must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        if (valueDate.isBefore(businessDate)) {
            throw new IllegalArgumentException("a payoff's facts are dated never before its date");
        }
    }

    /** Identifiers and dates only - never the key. */
    @Override
    public String toString() {
        return "BnplPayoffExecution[" + id + ", quote " + quote + ", on " + businessDate + "]";
    }
}
