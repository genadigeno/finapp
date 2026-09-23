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
 * each line's entry treated {@code SETTLEMENT_CLEARING} or, failing that,
 * {@code PAYOUT_CLEARING} — the ledger's own vocabulary, nothing more. What those buckets MEAN
 * is ADR-0050 §3's and ADR-0051 §2's entry shapes read backwards, and {@link MerchantSettlement}
 * and {@link MerchantPayoutOutcomes} are the components that compose those shapes. So the
 * interpretation lives here, beside the composers, rather than in a ledger that must not know
 * what a fee is:
 *
 * <pre>
 *   payable line | DEBITS settlement clearing | CREDITS settlement clearing | CREDITS payout clearing | none
 *                | (a capture)                | (a refund)                  | (a payout)              |
 *   CREDIT       | captured                   | fees returned               | other                   | other
 *   DEBIT        | fees                       | refunded                    | paid out                | other
 * </pre>
 *
 * <p>If a composer ever changes those shapes, this table is the thing that must change with it —
 * and it is one screen away from the code that would have changed, which is the point of putting
 * it here. The precedence (settlement clearing first) is stated, not incidental: no entry this
 * platform composes touches both, and a line is folded once whatever its label.
 *
 * <h2>The terms sum to the position by construction</h2>
 *
 * <p>Every bucket came from the one statement the position was folded from, so
 * {@code position = captured − fees − refunded + feesReturned − paidOut + other} is an identity
 * of the breakdown, not a reconciliation this class performs. {@link Payable#terms()} restates it
 * so a caller can check it without re-deriving the sign convention.
 *
 * <h2>Paid out, not in flight</h2>
 *
 * <p>{@code paidOut} counts payouts the rail accepted — posted, keyed
 * {@code merchant-payout:<payoutId>} (`P6-TSK-012`). A payout still in flight is a hold, and a
 * hold is not a posting: it narrows what the next payout may take ({@code INV-MER-05}) without
 * moving the position, so it appears in no term here. A failed payout posted nothing and
 * appears nowhere, which is exactly its truth.
 */
@RequiredArgsConstructor
public final class MerchantPayable {

    /**
     * One currency's payable.
     *
     * @param position what the platform owes, derived; positive means owed TO the merchant
     * @param paidOut payouts the rail accepted, posted against {@code PAYOUT_CLEARING}
     * @param other movements that are none of the above, SIGNED — an operator adjustment today
     */
    public record Payable(
            Money position,
            Money captured,
            Money fees,
            Money refunded,
            Money feesReturned,
            Money paidOut,
            Money other) {

        public Payable {
            Objects.requireNonNull(position, "position must not be null");
            Objects.requireNonNull(captured, "captured must not be null");
            Objects.requireNonNull(fees, "fees must not be null");
            Objects.requireNonNull(refunded, "refunded must not be null");
            Objects.requireNonNull(feesReturned, "feesReturned must not be null");
            Objects.requireNonNull(paidOut, "paidOut must not be null");
            Objects.requireNonNull(other, "other must not be null");
        }

        /** The drill-down's own sum: always equal to {@link #position()}, by construction. */
        public Money terms() {
            return captured.minus(fees)
                    .minus(refunded)
                    .plus(feesReturned)
                    .minus(paidOut)
                    .plus(other);
        }
    }

    /** The counterparty purposes the drill-down reads, most significant first. */
    static final List<AccountPurpose> COUNTERPARTIES =
            List.of(AccountPurpose.SETTLEMENT_CLEARING, AccountPurpose.PAYOUT_CLEARING);

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
                breakdowns.breakdown(unitOfWork, account.id(), COUNTERPARTIES);
        Money zero = Money.ofMinorUnits(0L, breakdown.position().currency());
        Money captured = zero;
        Money fees = zero;
        Money refunded = zero;
        Money feesReturned = zero;
        Money paidOut = zero;
        Money other = zero;
        for (PositionBreakdown.Bucket bucket : breakdown.buckets()) {
            Optional<PositionBreakdown.Counterparty> counterparty = bucket.counterparty();
            boolean credit = bucket.direction() == Direction.CREDIT;
            if (counterparty.isPresent()
                    && counterparty.get().purpose() == AccountPurpose.SETTLEMENT_CLEARING) {
                if (counterparty.get().direction() == Direction.DEBIT) {
                    // A capture: money arrived in clearing, the gross was credited, the fee
                    // debited.
                    if (credit) {
                        captured = captured.plus(bucket.total());
                    } else {
                        fees = fees.plus(bucket.total());
                    }
                } else {
                    // A refund: money left through clearing, the gross debited, a RETURNED
                    // policy's share of the fee credited back.
                    if (credit) {
                        feesReturned = feesReturned.plus(bucket.total());
                    } else {
                        refunded = refunded.plus(bucket.total());
                    }
                }
            } else if (counterparty.isPresent()
                    && counterparty.get().purpose() == AccountPurpose.PAYOUT_CLEARING
                    && counterparty.get().direction() == Direction.CREDIT
                    && !credit) {
                // A payout the rail accepted: the payable debited, payout clearing credited
                // (ADR-0051 §2, keyed merchant-payout:<payoutId>).
                paidOut = paidOut.plus(bucket.total());
            } else {
                // None of the shapes above. Signed as the liability reads it: a credit increases
                // what is owed, a debit reduces it.
                other = credit ? other.plus(bucket.total()) : other.minus(bucket.total());
            }
        }
        return new Payable(
                breakdown.position(), captured, fees, refunded, feesReturned, paidOut, other);
    }
}
