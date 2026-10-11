package com.finapp.lending;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A loan account as recorded (`P11-TSK-012`, {@code lending V006}; {@code LENDING_LIFECYCLES.md} section 4.5): either
 * kind, born at the customer's acceptance - identity, kind, status and the database's stamps. <strong>No amount, rate or
 * balance</strong>: the price is its {@link LoanAgreement}'s, and every figure the borrower owes is a ledger balance of
 * its six per-loan accounts ({@code INV-LND-01}).
 *
 * @param offer the offer it was born from
 * @param decision credit's decision it consumed, by value (ADR-0029)
 * @param openedAt the database's instant of the acceptance
 * @param closedAt the database's instant of a terminal edge, once there
 * @param correlation the acceptance's
 */
public record Loan(
        LoanId id,
        UUID party,
        LoanProduct product,
        LoanStatus status,
        CurrencyCode currency,
        LoanOfferId offer,
        UUID decision,
        Instant openedAt,
        Optional<Instant> closedAt,
        CorrelationId correlation) {

    public Loan {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(party, "party must not be null");
        Objects.requireNonNull(product, "product must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(offer, "offer must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(openedAt, "openedAt must not be null");
        Objects.requireNonNull(closedAt, "closedAt must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
    }

    /** The account kind its product decides (ADR-0090 point 3), frozen at birth. */
    public LoanKind kind() {
        return product.kind();
    }

    /** Never the party ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "Loan[" + id.value() + ", " + product + ", " + status + "]";
    }
}
