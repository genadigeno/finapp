package com.finapp.merchant;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PositionBreakdown;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * What the platform owes a merchant, and <strong>why</strong> (`P6-TSK-010`, {@code INV-MER-02}
 * as a surface) — the payable's ledger position, broken down into the terms a merchant can
 * check it against.
 *
 * <h2>This module reads the buckets because this module wrote the shapes</h2>
 *
 * <p>{@link PositionBreakdown} hands back the payable's lines bucketed by direction and by how
 * each line's entry treated {@code SETTLEMENT_CLEARING} — the ledger's own vocabulary, nothing
 * more. What those buckets MEAN is ADR-0050 §3's entry shapes read backwards, and
 * {@link MerchantSettlement} is the component that composes those shapes. So the interpretation
 * lives here, beside the composer, rather than in a ledger that must not know what a fee is:
 *
 * <pre>
 *   payable line | entry DEBITS clearing (capture) | entry CREDITS clearing (refund) | neither
 *   CREDIT       | captured                        | fees returned                   | other
 *   DEBIT        | fees                            | refunded                        | other
 * </pre>
 *
 * <p>If a composer ever changes those shapes, this table is the thing that must change with it —
 * and it is one screen away from the code that would have changed, which is the point of putting
 * it here.
 *
 * <h2>The terms sum to the position by construction</h2>
 *
 * <p>Every bucket came from the one statement the position was folded from, so
 * {@code position = captured − fees − refunded + feesReturned + other} is an identity of the
 * breakdown, not a reconciliation this class performs. {@link Payable#terms()} restates it so a
 * caller can check it without re-deriving the sign convention.
 *
 * <h2>Why there is no payouts term yet</h2>
 *
 * <p>Payouts arrive with `P6-TSK-012` and post against {@code PAYOUT_CLEARING}, a purpose that
 * does not exist yet. A {@code paidOut} figure that is always zero with no producer is the
 * state-with-no-producer this phase has refused repeatedly, so until then a payout would land in
 * {@code other} — visibly, and still summing — and that task splits it out.
 */
@RequiredArgsConstructor
public final class MerchantPayable {

    /**
     * One currency's payable.
     *
     * @param position what the platform owes, derived; positive means owed TO the merchant
     * @param other movements that are neither a capture nor a refund, SIGNED — an operator
     *     adjustment today, a payout until `P6-TSK-012` gives it its own term
     */
    public record Payable(
            Money position,
            Money captured,
            Money fees,
            Money refunded,
            Money feesReturned,
            Money other) {

        public Payable {
            Objects.requireNonNull(position, "position must not be null");
            Objects.requireNonNull(captured, "captured must not be null");
            Objects.requireNonNull(fees, "fees must not be null");
            Objects.requireNonNull(refunded, "refunded must not be null");
            Objects.requireNonNull(feesReturned, "feesReturned must not be null");
            Objects.requireNonNull(other, "other must not be null");
        }

        /** The drill-down's own sum: always equal to {@link #position()}, by construction. */
        public Money terms() {
            return captured.minus(fees).minus(refunded).plus(feesReturned).plus(other);
        }
    }

    @NonNull private final LedgerAccountStore<Connection> accounts;
    @NonNull private final PositionBreakdown<Connection> breakdowns;

    /**
     * Every payable this merchant has, one per currency. The accounts come from the ledger's
     * owner-scoped read ({@code owner_ref = ?} in its statement), so every breakdown asked for
     * below is of an account that is provably this merchant's.
     */
    public List<Payable> payablesOf(Connection unitOfWork, MerchantId merchant) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(merchant, "merchant must not be null");
        return accounts.findAllOwned(unitOfWork, merchant.value()).stream()
                .filter(account -> account.purpose() == AccountPurpose.MERCHANT_PAYABLE)
                .map(account -> payableOf(unitOfWork, account))
                .toList();
    }

    private Payable payableOf(Connection unitOfWork, LedgerAccount account) {
        PositionBreakdown.Breakdown breakdown =
                breakdowns.breakdown(
                        unitOfWork, account.id(), AccountPurpose.SETTLEMENT_CLEARING);
        Money zero = Money.ofMinorUnits(0L, breakdown.position().currency());
        Money captured = zero;
        Money fees = zero;
        Money refunded = zero;
        Money feesReturned = zero;
        Money other = zero;
        for (PositionBreakdown.Bucket bucket : breakdown.buckets()) {
            Optional<Direction> clearing = bucket.counterparty();
            boolean credit = bucket.direction() == Direction.CREDIT;
            if (clearing.isEmpty()) {
                // Neither a capture nor a refund. Signed as the liability reads it: a credit
                // increases what is owed, a debit reduces it.
                other = credit ? other.plus(bucket.total()) : other.minus(bucket.total());
            } else if (clearing.get() == Direction.DEBIT) {
                // A capture: money arrived in clearing, the gross was credited, the fee debited.
                if (credit) {
                    captured = captured.plus(bucket.total());
                } else {
                    fees = fees.plus(bucket.total());
                }
            } else {
                // A refund: money left through clearing, the gross debited, a RETURNED policy's
                // share of the fee credited back.
                if (credit) {
                    feesReturned = feesReturned.plus(bucket.total());
                } else {
                    refunded = refunded.plus(bucket.total());
                }
            }
        }
        return new Payable(breakdown.position(), captured, fees, refunded, feesReturned, other);
    }
}
