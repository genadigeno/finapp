package com.finapp.lending;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A credit line's closure request as recorded (`P11-TSK-023`, {@code lending V018}'s {@code line_closure_request};
 * ADR-0100 point 8): the customer's act, born once per line while it is {@code ACTIVE}, with the outcome at the request.
 * Never changed; the line's later closure is its {@link LoanClosure}.
 *
 * @param requestKey the customer's idempotency key - the one a retried key converges on after its claim has lapsed
 * @param requestedAt the database's instant of the request
 */
public record LineClosureRequest(UUID id, LoanId loan, String requestKey, LineClosureOutcome outcome, Instant requestedAt) {

    public LineClosureRequest {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(requestKey, "requestKey must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
    }

    /** Identifiers only - never the key ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "LineClosureRequest[" + id + ", loan " + loan.value() + ", " + outcome + "]";
    }
}
