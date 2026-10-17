package com.finapp.bnpl;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code REFUND_ALLOCATION_ENGINE_V1} - a merchant's BNPL refund split latest instalments first into cancelled
 * principal and the customer's excess, the merchant fee pro-rated back (`P12-TSK-009`; owner decision B3, ADR-0105
 * decisions 2-6, {@code PHASE_12_PLAN.md} sections 12.5, 12.6 and 12.11's worked check; {@code INV-BNPL-06},
 * {@code INV-BNPL-07}, {@code INV-BNPL-09}, {@code INV-MER-04}, {@code INV-MER-07} as amended).
 *
 * <p><strong>A pure function.</strong> No clock, no database, no locale, no hash ordering, no floating point, no
 * division and no state: the same position, refund and fee binding give the same split, byte for byte, on every
 * instance and every JVM, years later - its whole multi-instance answer ({@code RefundAllocationEngineV1PropertiesTest}'s
 * two-JVM digest). The ordering of concurrent refunds on one purchase is the caller's: `P12-TSK-021` derives the
 * position under the purchase's lock (B5) then the obligation's (B4), so no two refunds of one purchase ever split
 * against the same {@code refundedBefore}. A semantic change is version 2 beside this one, which stays for as long as an
 * agreement pins it ({@link BnplEngine}).
 *
 * <p><strong>The split</strong> (ADR-0105 decision 2). Refused first, typed: a refund not in the purchase's currency at
 * its scale, not positive, or above what remains of the purchase ({@code R > G − refundedBefore},
 * {@code INV-BNPL-07}). Then the instalments are walked from the <strong>last</strong> to the first, each cancelling
 * {@code min(remaining R, its unpaid principal)} - unbilled principal ({@code BNPL_PRINCIPAL}) or billed-unpaid
 * ({@code BNPL_PRINCIPAL_DUE}, curing its days past due); a partly paid instalment cancels only its unpaid part, a paid
 * one nothing. What is left of {@code R} is the excess {@code E}, owed to the customer. So {@code R = Σ cancelled + E}
 * exactly ({@code INV-BNPL-06}, asserted again by {@link RefundAllocation}), no earlier instalment is cancelled while a
 * later one still owes principal, and a paid amount is never cancelled.
 *
 * <p><strong>The fee</strong> (ADR-0105 decision 3) is the merchant's arithmetic, bound in as {@link MerchantFeeReturn}
 * and asked once for this refund, {@code returnedFee(refundedBefore, R)} - the cumulative difference
 * {@code cum(refundedBefore + R) − cum(refundedBefore)} that telescopes to exactly {@code f} at a full refund in any
 * order. The engine checks the answer's properties (never its arithmetic): {@code 0 ≤ r_f ≤ R}, the purchase's refunds
 * together returning at most {@code f} ({@code returnedFee(0, refundedBefore) + r_f ≤ f}), and exactly {@code f} when
 * this refund completes the purchase. With {@code R ≤ G − refundedBefore} and {@code f < G}, that is what bounds the
 * payable: Σ over a purchase's refunds of {@code R − r_f} = {@code x − cum(x)} for the cumulative refunded {@code x},
 * which never decreases and is {@code G − f} at {@code x = G} - a purchase's refunds never take back more than its
 * financing credited ({@code INV-BNPL-07}, the amended {@code INV-MER-07}).
 *
 * <p><strong>A full refund leaves nothing owed</strong> ({@code INV-BNPL-09}): the position's principal sums to
 * {@code G} and earlier refunds cancelled no more than they refunded, so the unpaid principal is at most
 * {@code G − refundedBefore}; a refund completing the purchase is at least that, and cancels all of it.
 *
 * <p><strong>Not here.</strong> The postings, locks, versions and closure (`P12-TSK-021`); pay-in-N's principal
 * recalculation ({@code ScheduleEngineV1.recalculateAtInstalment}) - for pay-in-N `P12-TSK-021` records schedule
 * version n+1 from this split's unbilled total and the version difference; the releases themselves (the late fees of
 * {@link RefundAllocation#whollyCancelled()} and, when {@link RefundAllocation#leavesNoPrincipalUnpaid()}, pay-in-N's
 * unpaid interest) are the entry's, which reads them here.
 */
public final class RefundAllocationEngineV1 {

    /** The version every split this engine produces is named for. */
    public static final BnplEngine VERSION = BnplEngine.REFUND_ALLOCATION_ENGINE_V1;

    private RefundAllocationEngineV1() {}

    /**
     * Splits {@code refund} over {@code position}.
     *
     * @param position the purchase as the refund meets it, derived under its locks
     * @param refund the refund {@code R} - the client's amount, judged here
     * @param fees the merchant fee arithmetic, bound to this purchase's pinned version
     * @throws IllegalStateException if the fee binding answers a figure no refund can return - a wiring defect
     */
    public static RefundAllocationOutcome allocate(RefundPosition position, Money refund, MerchantFeeReturn fees) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(refund, "refund");
        Objects.requireNonNull(fees, "fees");
        CurrencyCode currency = position.currency();
        if (!refund.currency().equals(currency) || refund.scale() != position.gross().scale()) {
            return refused(RefundAllocationRefusal.CURRENCY_MISMATCH, "the refund is in the purchase's currency, at its"
                    + " scale");
        }
        if (!refund.isPositive()) {
            return refused(RefundAllocationRefusal.REFUND_NOT_POSITIVE, "the refund is positive");
        }
        if (refund.compareTo(position.refundable()) > 0) {
            return refused(RefundAllocationRefusal.REFUND_EXCEEDS_PURCHASE, "the refund exceeds what remains of the"
                    + " purchase (INV-BNPL-07)");
        }

        // The walk: latest instalment first, each its unpaid principal or what is left of the refund.
        List<RefundAllocation.Cancellation> cancellations = new ArrayList<>();
        Money remaining = refund;
        Money unpaidAfter = Money.zero(currency);
        List<RefundPosition.Instalment> instalments = position.instalments();
        for (int index = instalments.size() - 1; index >= 0; index--) {
            RefundPosition.Instalment instalment = instalments.get(index);
            Money unpaid = instalment.unpaid();
            Money cancelled = remaining.compareTo(unpaid) < 0 ? remaining : unpaid;
            if (cancelled.isPositive()) {
                cancellations.add(new RefundAllocation.Cancellation(instalment.sequence(),
                        instalment.billed() ? RefundAllocation.Cancellation.Kind.BILLED_UNPAID
                                : RefundAllocation.Cancellation.Kind.UNBILLED,
                        cancelled, cancelled.equals(unpaid)));
                remaining = remaining.minus(cancelled);
            }
            unpaidAfter = unpaidAfter.plus(unpaid.minus(cancelled));
        }
        Money excess = remaining;

        Money feeReturned = feeReturned(position, refund, fees);
        return new RefundAllocationOutcome.Allocated(new RefundAllocation(VERSION, refund, cancellations, excess,
                feeReturned, refund.minus(feeReturned), unpaidAfter.isZero()));
    }

    /** The merchant's figure for this refund, its properties checked - never its arithmetic restated. */
    private static Money feeReturned(RefundPosition position, Money refund, MerchantFeeReturn fees) {
        Money before = position.refundedBefore();
        Money returned = Objects.requireNonNull(fees.returnedFee(before, refund), "the fee returned");
        Money returnedBefore = before.isZero() ? Money.zero(position.currency())
                : Objects.requireNonNull(fees.returnedFee(Money.zero(position.currency()), before), "the fee returned");
        if (!returned.currency().equals(position.currency()) || returned.scale() != refund.scale()
                || !returnedBefore.currency().equals(position.currency()) || returnedBefore.scale() != refund.scale()) {
            throw new IllegalStateException("the fee returned is in the purchase's currency, at its scale");
        }
        if (returned.isNegative() || returned.compareTo(refund) > 0) {
            throw new IllegalStateException("the fee a refund returns is between zero and the refund");
        }
        Money returnedAfter = returnedBefore.plus(returned);
        if (returnedAfter.compareTo(position.fee()) > 0) {
            throw new IllegalStateException("a purchase's refunds return no more than its fee (INV-BNPL-07)");
        }
        if (refund.equals(position.refundable()) && !returnedAfter.equals(position.fee())) {
            throw new IllegalStateException("refunds completing the purchase return exactly its fee - the fee binding"
                    + " does not telescope (INV-BNPL-06)");
        }
        return returned;
    }

    private static RefundAllocationOutcome refused(RefundAllocationRefusal reason, String detail) {
        return new RefundAllocationOutcome.Refused(reason, detail);
    }
}
