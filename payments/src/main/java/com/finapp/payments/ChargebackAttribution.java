package com.finapp.payments;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * One movement of a chargeback between the payment's counterparty and the platform's side of
 * the dispute (`P7-TSK-013`, ADR-0061 §4) — what {@link DisputeComposition} is handed, in
 * payment vocabulary only: {@link CaptureSettlement}'s shape at the dispute.
 *
 * @param intent the disputed payment
 * @param attempt the attempt the network contests
 * @param dispute the dispute this movement belongs to
 * @param counterparty the account the payment credited — the merchant's payable, or the
 *     customer's wallet for a top-up; the one account whose owner the composing flow knows
 * @param counterpart the platform's account facing it: {@code CHARGEBACK_RECOVERABLE} while the
 *     chargeback stands, {@code DISPUTE_COSTS} when a failed refund's share returns after a loss
 * @param amount the movement — never the network's statement, always the dispute's own
 *     recorded attribution
 * @param at the transaction's clock reading, for anything the composer records
 */
public record ChargebackAttribution(
        PaymentIntentId intent,
        PaymentAttemptId attempt,
        DisputeId dispute,
        LedgerAccountId counterparty,
        LedgerAccountId counterpart,
        Money amount,
        Correlation correlation,
        Instant at) {

    public ChargebackAttribution {
        Objects.requireNonNull(intent, "intent must not be null");
        Objects.requireNonNull(attempt, "attempt must not be null");
        Objects.requireNonNull(dispute, "dispute must not be null");
        Objects.requireNonNull(counterparty, "counterparty must not be null");
        Objects.requireNonNull(counterpart, "counterpart must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        Objects.requireNonNull(at, "at must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "an attribution moves a positive amount; a zero one posts nothing");
        }
    }

    /** Identifiers only — never the amount (INV-AUD-02). */
    @Override
    public String toString() {
        return "ChargebackAttribution[" + dispute + ", " + counterparty + "]";
    }
}
