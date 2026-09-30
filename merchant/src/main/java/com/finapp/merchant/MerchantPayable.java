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
 * each line's entry treated a SALE clearing — {@code SETTLEMENT_CLEARING} for the card rail,
 * {@code INSTANT_CLEARING} for the push rail (`P7-TSK-010`: the same capture composition and
 * the same refund composition post to both, so the shapes ARE the same and only the
 * counterparty account differs, `INV-RAIL-04`) — or, failing that, {@code PAYOUT_CLEARING} —
 * the ledger's own vocabulary, nothing more. What those buckets MEAN is ADR-0050 §3's and
 * ADR-0051 §2's entry shapes read backwards, and {@link MerchantSettlement}
 * and {@link MerchantPayoutOutcomes} are the components that compose those shapes. So the
 * interpretation lives here, beside the composers, rather than in a ledger that must not know
 * what a fee is:
 *
 * <pre>
 *   payable line | DEBITS a sale clearing | CREDITS a sale clearing | CREDITS payout clearing | none
 *                | (a capture)            | (a refund)              | (a payout)              |
 *   CREDIT       | captured               | fees returned           | other                   | other
 *   DEBIT        | fees                   | refunded                | paid out                | other
 *
 *   payable line | CREDITS the recoverable or dispute costs | DEBITS the recoverable
 *                | (a chargeback's attribution)             | (a won chargeback's restoration)
 *   DEBIT        | charged back                             | other
 *   CREDIT       | other                                    | chargebacks reversed
 * </pre>
 *
 * <p><strong>A chargeback is never a refund here</strong> (`P7-TSK-013`, ADR-0061 §4): its
 * external fact moves the rail's clearing against {@code CHARGEBACK_RECOVERABLE}, and the
 * merchant's share is charged out of the recoverable in a SEPARATE entry — so the payable's line
 * faces the recoverable (or {@code DISPUTE_COSTS}, when a failed refund's share returns after a
 * loss wrote the excess off), never a sale clearing. A one-entry chargeback with no excess would
 * have been line-for-line a retained refund, and a merchant would have read its chargebacks as
 * refunds — the `P7-TSK-010` mislabel class, prevented by the entry shape rather than found.
 *
 * <p>If a composer ever changes those shapes, this table is the thing that must change with it —
 * and it is one screen away from the code that would have changed, which is the point of putting
 * it here. The precedence (sale clearings first) is stated, not incidental: no entry this
 * platform composes touches more than one clearing, and a line is folded once whatever its
 * label. *(Until `P7-TSK-010` the sale column named only {@code SETTLEMENT_CLEARING}, so a
 * pay-by-bank sale's whole movement fell into {@code other} — summed correctly, explained
 * wrongly. The return made the mislabel visible: a merchant checking a refunded bank sale
 * would have read zeros.)*
 *
 * <h2>The terms sum to the position by construction</h2>
 *
 * <p>Every bucket came from the one statement the position was folded from, so
 * {@code position = captured − fees − refunded + feesReturned − paidOut + payoutsReturned −
 * chargedBack + chargebacksReversed ± reconciliationAttributed + other} is an identity of the breakdown, not a reconciliation this class
 * performs. {@link Payable#terms()} restates it so a caller can check it without re-deriving the
 * sign convention. A payable below zero after a chargeback is merchant debt ({@code INV-MER-07}
 * amended): never more than the sale credited, recovered from later captures before any payout.
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
     * @param payoutsReturned payouts the beneficiary bank returned (`P8-TSK-019`, ADR-0073 §6) —
     *     a payable CREDIT facing a {@code PAYOUT_CLEARING} DEBIT, which only a return's own
     *     {@code merchant-payout-return:} posting writes: a {@code MANUAL} adjustment on the
     *     reconciled position is refused at both ranks (ledger `V015`), and a reconciliation
     *     attribution is classified FIRST, by its origin
     * @param chargedBack the chargebacks attributed to this merchant (`P7-TSK-013`) — its
     *     sales' shares, charged out of {@code CHARGEBACK_RECOVERABLE}
     * @param chargebacksReversed what won chargebacks gave back (`P7-TSK-013`)
     * @param reconciliationAttributed value an approved break resolution attributed to or
     *     from the payable (`P8-TSK-015`, ADR-0071 §2), SIGNED — every payable line of a
     *     {@code RECONCILIATION}-origin {@code ADJUSTMENT} entry, whatever it faces, classified
     *     FIRST (ADR-0073 §6's origin rule)
     * @param other movements that are none of the above, SIGNED — an operator adjustment today
     */
    public record Payable(
            Money position,
            Money captured,
            Money fees,
            Money refunded,
            Money feesReturned,
            Money paidOut,
            Money payoutsReturned,
            Money chargedBack,
            Money chargebacksReversed,
            Money reconciliationAttributed,
            Money other) {

        public Payable {
            Objects.requireNonNull(position, "position must not be null");
            Objects.requireNonNull(captured, "captured must not be null");
            Objects.requireNonNull(fees, "fees must not be null");
            Objects.requireNonNull(refunded, "refunded must not be null");
            Objects.requireNonNull(feesReturned, "feesReturned must not be null");
            Objects.requireNonNull(paidOut, "paidOut must not be null");
            Objects.requireNonNull(payoutsReturned, "payoutsReturned must not be null");
            Objects.requireNonNull(chargedBack, "chargedBack must not be null");
            Objects.requireNonNull(chargebacksReversed, "chargebacksReversed must not be null");
            Objects.requireNonNull(
                    reconciliationAttributed, "reconciliationAttributed must not be null");
            Objects.requireNonNull(other, "other must not be null");
        }

        /** The drill-down's own sum: always equal to {@link #position()}, by construction. */
        public Money terms() {
            return captured.minus(fees)
                    .minus(refunded)
                    .plus(feesReturned)
                    .minus(paidOut)
                    .plus(payoutsReturned)
                    .minus(chargedBack)
                    .plus(chargebacksReversed)
                    .plus(reconciliationAttributed)
                    .plus(other);
        }
    }

    /** The counterparty purposes the drill-down reads, most significant first. */
    static final List<AccountPurpose> COUNTERPARTIES =
            List.of(
                    AccountPurpose.SETTLEMENT_CLEARING,
                    AccountPurpose.INSTANT_CLEARING,
                    // The book rail's sale counterparty (P7-TSK-011): no clearing
                    // stands between, so the payer's own wallet faces the payable -
                    // same entry shapes, same direction table.
                    AccountPurpose.CUSTOMER_WALLET,
                    AccountPurpose.PAYOUT_CLEARING,
                    // The chargeback's attribution entries (P7-TSK-013): the payable faces
                    // the recoverable, or dispute costs when a written-off share returns.
                    // After the others, so no existing entry's label moves.
                    AccountPurpose.CHARGEBACK_RECOVERABLE,
                    AccountPurpose.DISPUTE_COSTS);

    /** The clearings a SALE moves through — one per external rail, same entry shapes. */
    private static boolean saleClearing(AccountPurpose purpose) {
        return purpose == AccountPurpose.SETTLEMENT_CLEARING
                || purpose == AccountPurpose.INSTANT_CLEARING
                // The book sale's counterpart is the payer's wallet (P7-TSK-011):
                // the capture DEBITS it and the refund CREDITS it, exactly the
                // clearing shapes - and no other payable entry ever faces one.
                || purpose == AccountPurpose.CUSTOMER_WALLET;
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
                breakdowns.breakdown(unitOfWork, account.id(), COUNTERPARTIES);
        Money zero = Money.ofMinorUnits(0L, breakdown.position().currency());
        Money captured = zero;
        Money fees = zero;
        Money refunded = zero;
        Money feesReturned = zero;
        Money paidOut = zero;
        Money payoutsReturned = zero;
        Money chargedBack = zero;
        Money chargebacksReversed = zero;
        Money reconciliationAttributed = zero;
        Money other = zero;
        for (PositionBreakdown.Bucket bucket : breakdown.buckets()) {
            Optional<PositionBreakdown.Counterparty> counterparty = bucket.counterparty();
            boolean credit = bucket.direction() == Direction.CREDIT;
            if (bucket.reconciliationAttributed()) {
                // An approved break resolution's attribution (P8-TSK-015), classified by its
                // origin FIRST: a transfer of an OUTBOUND clearing remainder faces the clearing
                // and would otherwise read as a capture. Signed as the liability reads it.
                reconciliationAttributed =
                        credit
                                ? reconciliationAttributed.plus(bucket.total())
                                : reconciliationAttributed.minus(bucket.total());
            } else if (counterparty.isPresent() && saleClearing(counterparty.get().purpose())) {
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
            } else if (counterparty.isPresent()
                    && disputeSide(counterparty.get().purpose())
                    && counterparty.get().direction() == Direction.CREDIT
                    && !credit) {
                // A chargeback attributed to this merchant: the payable debited out of the
                // recoverable - or out of dispute costs, a failed refund's share returning
                // after a loss (ADR-0061 sections 3-4, P7-TSK-013).
                chargedBack = chargedBack.plus(bucket.total());
            } else if (counterparty.isPresent()
                    && counterparty.get().purpose() == AccountPurpose.CHARGEBACK_RECOVERABLE
                    && counterparty.get().direction() == Direction.DEBIT
                    && credit) {
                // A won chargeback's restoration: the recoverable debited, the payable credited
                // back - the attribution's exact inverse.
                chargebacksReversed = chargebacksReversed.plus(bucket.total());
            } else if (counterparty.isPresent()
                    && counterparty.get().purpose() == AccountPurpose.PAYOUT_CLEARING
                    && counterparty.get().direction() == Direction.DEBIT
                    && credit) {
                // A payout returned (P8-TSK-019, ADR-0073 section 6): the return's own POSTING
                // debits PAYOUT_CLEARING and credits the payable back. After the existing terms,
                // so no existing entry's label moves; confined to that entry by the ledger's
                // refusal of a MANUAL line on the reconciled position, an attribution already
                // classified above by its origin.
                payoutsReturned = payoutsReturned.plus(bucket.total());
            } else {
                // None of the shapes above. Signed as the liability reads it: a credit increases
                // what is owed, a debit reduces it.
                other = credit ? other.plus(bucket.total()) : other.minus(bucket.total());
            }
        }
        return new Payable(
                breakdown.position(), captured, fees, refunded, feesReturned, paidOut,
                payoutsReturned, chargedBack, chargebacksReversed, reconciliationAttributed,
                other);
    }

    /** The platform's side of a chargeback's attribution entries — never a sale clearing. */
    private static boolean disputeSide(AccountPurpose purpose) {
        return purpose == AccountPurpose.CHARGEBACK_RECOVERABLE
                || purpose == AccountPurpose.DISPUTE_COSTS;
    }
}
