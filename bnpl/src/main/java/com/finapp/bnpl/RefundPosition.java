package com.finapp.bnpl;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.util.List;
import java.util.Objects;

/**
 * A purchase's position as a refund meets it - what {@link RefundAllocationEngineV1} splits a refund over
 * (`P12-TSK-009`; ADR-0105 decisions 1-3, {@code PHASE_12_PLAN.md} section 12.6; {@code INV-BNPL-06}, {@code -07}).
 * The refund (`P12-TSK-021`) derives it under the purchase's lock (B5) and then the obligation's (B4) from the born-once
 * facts - the schedule, the billings, the allocations and the earlier refunds' adjustment rows - never from a
 * projection.
 *
 * <p><strong>Consistent by construction</strong>, so a value of this type is or does not exist: an inconsistent
 * position is a defect of the code that derived it ({@link IllegalArgumentException}), never a business refusal - the
 * client's refund amount is judged by the engine, which answers typed refusals. One currency at its scale throughout;
 * {@code G} positive; {@code 0 ≤ f < G} (a sale covers its fee, ADR-0058 - the bound {@code INV-BNPL-07}'s arithmetic
 * rests on); {@code 0 ≤ refundedBefore ≤ G}; instalments numbered 1 to n whose principal sums to {@code G} exactly
 * ({@code INV-BNPL-05}: the principal financed is the gross); for each instalment {@code allocated + cancelled ≤
 * principal}, both non-negative, and nothing allocated to one not yet billed (principal settles only once due - a
 * payment beyond what is due is a credit balance, F3); and the earlier refunds cancelled no more than they refunded.
 *
 * <p><strong>The instalments are the schedule's instalments as first scheduled</strong> (version 1's principal), each
 * carrying what has been allocated to it and what refunds have cancelled from it - so its unpaid principal is
 * {@code principal − allocated − cancelled}. Amounts are RESTRICTED-FINANCIAL: {@link #toString()} names the currency
 * and the instalment count only.
 *
 * @param gross the purchase's gross {@code G} - the principal financed
 * @param fee the merchant fee {@code f} pinned on the purchase ({@code INV-MER-03})
 * @param refundedBefore Σ of the purchase's committed refunds, read under its lock
 * @param instalments the instalments in sequence order, 1 to n
 */
public record RefundPosition(Money gross, Money fee, Money refundedBefore, List<Instalment> instalments) {

    public RefundPosition {
        Objects.requireNonNull(gross, "gross");
        Objects.requireNonNull(fee, "fee");
        Objects.requireNonNull(refundedBefore, "refundedBefore");
        instalments = List.copyOf(Objects.requireNonNull(instalments, "instalments"));
        CurrencyCode currency = gross.currency();
        if (gross.scale() != currency.minorUnits() || !gross.isPositive()) {
            throw new IllegalArgumentException("a purchase's gross is positive, at its currency's scale");
        }
        requireLike(gross, fee, "the fee");
        requireLike(gross, refundedBefore, "what was refunded before");
        if (fee.isNegative() || fee.compareTo(gross) >= 0) {
            throw new IllegalArgumentException("a merchant fee is at least zero and below the gross (ADR-0058)");
        }
        if (refundedBefore.isNegative() || refundedBefore.compareTo(gross) > 0) {
            throw new IllegalArgumentException("a purchase's refunds total between zero and its gross (INV-BNPL-07)");
        }
        if (instalments.isEmpty()) {
            throw new IllegalArgumentException("a refund position has at least one instalment");
        }
        Money scheduled = Money.zero(currency);
        Money cancelled = Money.zero(currency);
        for (int index = 0; index < instalments.size(); index++) {
            Instalment instalment = instalments.get(index);
            if (instalment.sequence() != index + 1) {
                throw new IllegalArgumentException("instalment " + (index + 1) + " is numbered in order");
            }
            requireLike(gross, instalment.principal(), "instalment " + instalment.sequence() + "'s principal");
            requireLike(gross, instalment.allocated(), "instalment " + instalment.sequence() + "'s allocations");
            scheduled = scheduled.plus(instalment.principal());
            cancelled = cancelled.plus(instalment.cancelled());
        }
        if (!scheduled.equals(gross)) {
            throw new IllegalArgumentException("the instalments' principal sums to the gross exactly (INV-BNPL-05)");
        }
        if (cancelled.compareTo(refundedBefore) > 0) {
            throw new IllegalArgumentException("earlier refunds cancelled no more principal than they refunded"
                    + " (INV-BNPL-06)");
        }
    }

    /** The purchase's currency - every amount's. */
    public CurrencyCode currency() {
        return gross.currency();
    }

    /** What the purchase can still be refunded: {@code G − refundedBefore} ({@code INV-BNPL-07}). */
    public Money refundable() {
        return gross.minus(refundedBefore);
    }

    /** Σ every instalment's unpaid principal - what a refund can cancel. */
    public Money unpaid() {
        Money unpaid = Money.zero(currency());
        for (Instalment instalment : instalments) {
            unpaid = unpaid.plus(instalment.unpaid());
        }
        return unpaid;
    }

    /** The currency and the instalment count only - never an amount. */
    @Override
    public String toString() {
        return "RefundPosition[" + currency().code() + ", " + instalments.size() + " instalments]";
    }

    private static void requireLike(Money gross, Money other, String what) {
        if (!other.currency().equals(gross.currency()) || other.scale() != gross.scale()) {
            throw new IllegalArgumentException(what + " is in the purchase's currency, at its scale");
        }
    }

    /**
     * One instalment as a refund meets it. Judged in itself; how instalments number and sum is the position's judgement.
     *
     * @param sequence 1 to n
     * @param principal its principal as first scheduled - positive
     * @param billed whether it has fallen due (its principal moved to {@code BNPL_PRINCIPAL_DUE}, F2)
     * @param allocated the principal repayments and credit-balance applications settled on it - zero while unbilled
     * @param cancelled the principal earlier refunds cancelled from it
     */
    public record Instalment(int sequence, Money principal, boolean billed, Money allocated, Money cancelled) {

        public Instalment {
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(allocated, "allocated");
            Objects.requireNonNull(cancelled, "cancelled");
            if (sequence < 1) {
                throw new IllegalArgumentException("an instalment is numbered from 1");
            }
            if (!principal.isPositive()) {
                throw new IllegalArgumentException("instalment " + sequence + " was scheduled for a positive principal");
            }
            if (!allocated.currency().equals(principal.currency()) || allocated.scale() != principal.scale()
                    || !cancelled.currency().equals(principal.currency()) || cancelled.scale() != principal.scale()) {
                throw new IllegalArgumentException("instalment " + sequence + "'s figures are in one currency, at one"
                        + " scale");
            }
            if (allocated.isNegative() || cancelled.isNegative()) {
                throw new IllegalArgumentException("instalment " + sequence + "'s allocations and cancellations are"
                        + " never negative");
            }
            if (allocated.plus(cancelled).compareTo(principal) > 0) {
                throw new IllegalArgumentException("instalment " + sequence + " is never settled or cancelled beyond its"
                        + " principal");
            }
            if (!billed && !allocated.isZero()) {
                throw new IllegalArgumentException("instalment " + sequence + " is not yet billed, so nothing has been"
                        + " allocated to it");
            }
        }

        /** Its unpaid principal: {@code principal − allocated − cancelled}, never negative. */
        public Money unpaid() {
            return principal.minus(allocated).minus(cancelled);
        }

        /** The sequence and whether it is billed only - never an amount. */
        @Override
        public String toString() {
            return "Instalment[" + sequence + (billed ? ", billed]" : ", unbilled]");
        }
    }
}
