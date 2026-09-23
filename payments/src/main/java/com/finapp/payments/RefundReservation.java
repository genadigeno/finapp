package com.finapp.payments;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.Money;
import java.util.Objects;

/**
 * What a refund knows about its money when it is DISPATCHED (`P6-TSK-015`, ADR-0054) — the
 * argument to {@link RefundComposition#reserve}, and {@link RefundSettlement}'s earlier sibling.
 *
 * <p><strong>Payment vocabulary only</strong>, for {@link RefundSettlement}'s reason: no
 * merchant, no fee, no policy. It says <em>a refund of this amount is about to be dispatched
 * against this account, and this much of the payment had already been returned</em>; what that
 * refund will actually take from the account is the composing flow's business
 * ({@code INV-PAY-03}).
 *
 * <h2>Why {@code refundedBefore} is the COMPLETED sum here too</h2>
 *
 * <p>The completion prices a proportional share against what had completed before it. At
 * dispatch that number is only a floor: siblings in flight may complete first, and refunds not
 * yet dispatched may too, so a composer asked what this refund will take must answer for every
 * value from this one up to the capture's remainder. The non-failed sum would name a floor that
 * a failing sibling can undercut — reserved is not returned, as {@link RefundSettlement} says.
 *
 * @param intent the refunded payment's intent — the composer's lookup key
 * @param attempt the captured attempt being refunded
 * @param debit the account the refund will take the money from — the account the dispatch
 *     places its hold on
 * @param refunded this refund's amount
 * @param refundedBefore what had already been refunded and <strong>completed</strong> when this
 *     refund was dispatched, in the same currency; zero for the first
 */
public record RefundReservation(
        PaymentIntentId intent,
        PaymentAttemptId attempt,
        LedgerAccountId debit,
        Money refunded,
        Money refundedBefore) {

    public RefundReservation {
        Objects.requireNonNull(intent, "intent must not be null");
        Objects.requireNonNull(attempt, "attempt must not be null");
        Objects.requireNonNull(debit, "debit must not be null");
        Objects.requireNonNull(refunded, "refunded must not be null");
        Objects.requireNonNull(refundedBefore, "refundedBefore must not be null");
        if (!refunded.isPositive()) {
            throw new IllegalArgumentException("A refunded amount must be positive: " + refunded);
        }
        if (refundedBefore.isNegative()) {
            throw new IllegalArgumentException(
                    "A prior refunded total cannot be negative: " + refundedBefore);
        }
    }
}
