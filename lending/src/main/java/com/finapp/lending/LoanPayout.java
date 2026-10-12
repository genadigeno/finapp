package com.finapp.lending;

import com.finapp.ledger.HoldId;
import com.finapp.ledger.LedgerAccountId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A path X loan's payout as recorded (`P11-TSK-015`, {@code lending V009}; {@code LENDING_LIFECYCLES.md} section 4.7): its
 * state, the borrower's verified payout method (an opaque payments reference), the wallet the hold stands on and the hold,
 * payments' withdrawal once dispatched, and the conclusion. No amount: the money is the ledger's - the hold, then payments'
 * withdrawal ({@code INV-LND-01}).
 *
 * @param permit the sweep's permit ({@code next_attempt_at}) - a claim's token: a step acts only on the permit it claimed
 */
public record LoanPayout(
        UUID id,
        LoanId loan,
        UUID paymentMethod,
        LedgerAccountId wallet,
        HoldId hold,
        PayoutStatus status,
        Optional<UUID> withdrawal,
        Optional<NotDispatchedReason> notDispatchedReason,
        Instant permit,
        Instant createdAt,
        Optional<Instant> concludedAt) {

    public LoanPayout {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(paymentMethod, "paymentMethod must not be null");
        Objects.requireNonNull(wallet, "wallet must not be null");
        Objects.requireNonNull(hold, "hold must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(withdrawal, "withdrawal must not be null");
        Objects.requireNonNull(notDispatchedReason, "notDispatchedReason must not be null");
        Objects.requireNonNull(permit, "permit must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(concludedAt, "concludedAt must not be null");
    }

    /** Never the payout method or the wallet ({@code INV-RAIL-03}). */
    @Override
    public String toString() {
        return "LoanPayout[" + loan.value() + ", " + status + "]";
    }
}
