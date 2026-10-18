package com.finapp.bnpl;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A customer repayment obligation as recorded (`P12-TSK-013`, {@code bnpl V006}; {@code BNPL_LIFECYCLES.md} section 4.7)
 * - the machine row, no money: its purchase, its agreement, the party, the consumed decision, the product and plan, its
 * currency, its status and the purchase date its principal facts are floored at. Every figure it owes is a balance of its
 * six ledger accounts, derived from journal lines ({@code INV-BNPL-01}).
 *
 * @param closureReason present exactly when {@code CLOSED}
 */
public record BnplObligation(
        BnplObligationId id,
        BnplPurchaseId purchase,
        BnplAgreementId agreement,
        UUID party,
        UUID decision,
        BnplProduct product,
        BnplPlan plan,
        CurrencyCode currency,
        BnplObligationStatus status,
        LocalDate purchaseDate,
        Instant openedAt,
        Optional<BnplClosureReason> closureReason,
        CorrelationId correlation) {

    public BnplObligation {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(purchase, "purchase must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
        Objects.requireNonNull(party, "party must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(product, "product must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(purchaseDate, "purchaseDate must not be null");
        Objects.requireNonNull(openedAt, "openedAt must not be null");
        Objects.requireNonNull(closureReason, "closureReason must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        if ((status == BnplObligationStatus.CLOSED) != closureReason.isPresent()) {
            throw new IllegalArgumentException("a closure reason is said for a CLOSED obligation, and only there");
        }
    }

    /** Identifiers and the state only. */
    @Override
    public String toString() {
        return "BnplObligation[" + id.value() + ", " + product + ", " + status + "]";
    }
}
