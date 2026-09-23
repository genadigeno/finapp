package com.finapp.payments;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * Everything a refund knows about how its money settles (`P6-TSK-014`, ADR-0050 §6) — the
 * argument to {@link RefundComposition}, and {@link CaptureSettlement}'s mirror.
 *
 * <p><strong>Deliberately in payment vocabulary only.</strong> No merchant, no fee, no policy
 * and no schedule: this record says <em>a refund of this amount completed, the clearing account
 * is this, the account it debits is this, and this much of the payment had already been
 * returned before it</em>. What the fee does on the way back is the composing flow's business,
 * and naming it here would put {@code payments} one field away from knowing what a fee is
 * ({@code INV-PAY-03}, the same discipline {@link CaptureSettlement} holds).
 *
 * <h2>Why {@code refundedBefore} is here, and why it is the COMPLETED sum</h2>
 *
 * <p>A composer returning a proportional share of something cannot do it one refund at a time
 * without drifting: two halves of an odd fee each round up and return a minor unit more than
 * was ever assessed. The conserving construction needs to know where this refund starts from,
 * and {@code payments} is the module that owns that number.
 *
 * <p>It is the <strong>completed</strong> sum and emphatically not the non-failed one. The
 * budget bound uses non-failed, correctly, because a refund in flight has already reserved its
 * share of what may be returned. Pricing against that number would return fee for money that
 * has not left the platform and may never leave it — a sibling refund can still fail. This
 * number is <em>excluding</em> this refund: the composer adds {@link #refunded()} itself,
 * because the difference of two cumulative allocations is the whole of the arithmetic.
 *
 * @param intent the refunded payment's intent — the composer's lookup key
 * @param attempt the captured attempt being refunded
 * @param refund the refund itself; also what the posting key is built from
 * @param clearing the {@code SETTLEMENT_CLEARING} account, already resolved for the currency
 * @param debit the account the refund takes the money from — a customer's wallet for a
 *     top-up, a merchant's payable for a checkout payment
 * @param refunded this refund's amount
 * @param refundedBefore what had already been refunded and <strong>completed</strong> before
 *     this one, in the same currency; zero for the first
 * @param correlation the flow this refund belongs to
 * @param at the completion instant, from the one injected clock
 */
public record RefundSettlement(
        PaymentIntentId intent,
        PaymentAttemptId attempt,
        RefundId refund,
        LedgerAccountId clearing,
        LedgerAccountId debit,
        Money refunded,
        Money refundedBefore,
        Correlation correlation,
        Instant at) {

    public RefundSettlement {
        Objects.requireNonNull(intent, "intent must not be null");
        Objects.requireNonNull(attempt, "attempt must not be null");
        Objects.requireNonNull(refund, "refund must not be null");
        Objects.requireNonNull(clearing, "clearing must not be null");
        Objects.requireNonNull(debit, "debit must not be null");
        Objects.requireNonNull(refunded, "refunded must not be null");
        Objects.requireNonNull(refundedBefore, "refundedBefore must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        Objects.requireNonNull(at, "at must not be null");
        if (!refunded.isPositive()) {
            throw new IllegalArgumentException("A refunded amount must be positive: " + refunded);
        }
        if (refundedBefore.isNegative()) {
            throw new IllegalArgumentException(
                    "A prior refunded total cannot be negative: " + refundedBefore);
        }
    }
}
