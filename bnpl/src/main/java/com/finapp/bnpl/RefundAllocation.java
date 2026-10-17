package com.finapp.bnpl;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One BNPL refund's split - what {@link RefundAllocationEngineV1} answers and what the refund's entry F5 (with F5a's
 * releases) posts (`P12-TSK-009`; ADR-0105 decisions 2-6, {@code PHASE_12_PLAN.md} sections 12.6 and 12.11;
 * {@code INV-BNPL-06}, {@code INV-BNPL-07}, {@code INV-BNPL-09}).
 *
 * <ul>
 *   <li>the {@link Cancellation}s, <strong>latest instalment first</strong> - each the unpaid principal of one
 *       instalment the refund cancels, {@link Cancellation.Kind#UNBILLED} ({@code CR BNPL_PRINCIPAL}) or
 *       {@link Cancellation.Kind#BILLED_UNPAID} ({@code CR BNPL_PRINCIPAL_DUE}, curing its days past due);
 *   <li>the excess {@code E = R − Σ cancelled}, owed to the customer ({@code CR CUSTOMER_WALLET}, or
 *       {@code BNPL_CREDIT_BALANCE} while the wallet cannot be credited - `P12-TSK-021`'s choice);
 *   <li>the merchant fee returned {@code r_f} ({@code DR BNPL_MERCHANT_FEE_INCOME / CR MERCHANT_PAYABLE}) and the
 *       payable debit {@code R − r_f} - so the merchant's payable falls by the refund's net;
 *   <li>which instalments it cancels wholly - the late fees assessed on them are released in the same entry (OQ-14) -
 *       and whether it leaves no principal unpaid at all, the fact pay-in-N's interest release reads (A11).
 * </ul>
 *
 * <p><strong>Value is preserved by construction</strong>, so a value of this type conserves or does not exist: Σ
 * cancelled + {@code E} = {@code R} exactly ({@code INV-BNPL-06}); every cancellation positive, the sequences strictly
 * descending (latest first, each instalment at most once); {@code 0 ≤ r_f ≤ R} and the payable debit {@code R − r_f};
 * one currency at its scale. A violation is a defect of the engine ({@link IllegalArgumentException}). Amounts are
 * RESTRICTED-FINANCIAL: {@link #toString()} names the engine and the cancelled instalments' sequences only.
 *
 * @param engine the refund allocation engine version that produced it - a {@link BnplEngine.Kind#REFUND_ALLOCATION}
 *     version, pinned by the agreement and re-derived by replay (`P12-TSK-025`)
 * @param refund the refund {@code R}
 * @param cancellations the instalments' cancelled unpaid principal, latest instalment first
 * @param excess {@code E}, owed to the customer
 * @param feeReturned {@code r_f}, the merchant fee the refund returns
 * @param payableDebit {@code R − r_f}, what the merchant's payable is debited net
 * @param leavesNoPrincipalUnpaid every instalment's unpaid principal is zero after the refund - true also when none
 *     was unpaid before it (a refund after every instalment was paid is all excess)
 */
public record RefundAllocation(
        BnplEngine engine,
        Money refund,
        List<Cancellation> cancellations,
        Money excess,
        Money feeReturned,
        Money payableDebit,
        boolean leavesNoPrincipalUnpaid) {

    public RefundAllocation {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(refund, "refund");
        cancellations = List.copyOf(Objects.requireNonNull(cancellations, "cancellations"));
        Objects.requireNonNull(excess, "excess");
        Objects.requireNonNull(feeReturned, "feeReturned");
        Objects.requireNonNull(payableDebit, "payableDebit");
        if (engine.kind() != BnplEngine.Kind.REFUND_ALLOCATION) {
            throw new IllegalArgumentException("a refund's split is named for a refund allocation engine version");
        }
        if (!refund.isPositive() || refund.scale() != refund.currency().minorUnits()) {
            throw new IllegalArgumentException("a refund is positive, at its currency's scale");
        }
        Money cancelled = Money.zero(refund.currency());
        int previous = Integer.MAX_VALUE;
        for (Cancellation cancellation : cancellations) {
            if (cancellation.sequence() >= previous) {
                throw new IllegalArgumentException("the cancellations run from the latest instalment to the earliest,"
                        + " each instalment once");
            }
            requireLike(refund, cancellation.amount(), "instalment " + cancellation.sequence() + "'s cancellation");
            cancelled = cancelled.plus(cancellation.amount());
            previous = cancellation.sequence();
        }
        requireLike(refund, excess, "the excess");
        requireLike(refund, feeReturned, "the fee returned");
        requireLike(refund, payableDebit, "the payable debit");
        if (excess.isNegative()) {
            throw new IllegalArgumentException("the excess owed to the customer is never negative");
        }
        if (!cancelled.plus(excess).equals(refund)) {
            throw new IllegalArgumentException("a refund preserves value: Σ cancelled + excess = the refund exactly"
                    + " (INV-BNPL-06)");
        }
        if (feeReturned.isNegative() || feeReturned.compareTo(refund) > 0) {
            throw new IllegalArgumentException("the fee returned is between zero and the refund");
        }
        if (!feeReturned.plus(payableDebit).equals(refund)) {
            throw new IllegalArgumentException("the payable is debited the refund net of the fee returned");
        }
    }

    private static void requireLike(Money refund, Money other, String what) {
        if (!other.currency().equals(refund.currency()) || other.scale() != refund.scale()) {
            throw new IllegalArgumentException(what + " is in the refund's currency, at its scale");
        }
    }

    /** The refund's currency - every amount's. */
    public CurrencyCode currency() {
        return refund.currency();
    }

    /** Σ every cancellation - the principal the refund cancels. */
    public Money cancelled() {
        Money cancelled = Money.zero(currency());
        for (Cancellation cancellation : cancellations) {
            cancelled = cancelled.plus(cancellation.amount());
        }
        return cancelled;
    }

    /** Σ the cancellations of {@code kind} - {@code BNPL_PRINCIPAL}'s credit, or {@code BNPL_PRINCIPAL_DUE}'s. */
    public Money cancelled(Cancellation.Kind kind) {
        Objects.requireNonNull(kind, "kind");
        Money cancelled = Money.zero(currency());
        for (Cancellation cancellation : cancellations) {
            if (cancellation.kind() == kind) {
                cancelled = cancelled.plus(cancellation.amount());
            }
        }
        return cancelled;
    }

    /** The sequences of the instalments whose whole unpaid principal the refund cancels, latest first (OQ-14). */
    public List<Integer> whollyCancelled() {
        List<Integer> sequences = new ArrayList<>();
        for (Cancellation cancellation : cancellations) {
            if (cancellation.whole()) {
                sequences.add(cancellation.sequence());
            }
        }
        return List.copyOf(sequences);
    }

    /** The engine and the cancelled instalments' sequences only - never an amount. */
    @Override
    public String toString() {
        List<Integer> sequences = new ArrayList<>();
        for (Cancellation cancellation : cancellations) {
            sequences.add(cancellation.sequence());
        }
        return "RefundAllocation[" + engine + ", cancels " + sequences + "]";
    }

    /**
     * The unpaid principal one instalment's refund cancels.
     *
     * @param sequence the instalment
     * @param kind whether it was billed - which receivable the cancellation credits
     * @param amount the principal cancelled - positive, never more than the instalment's unpaid principal
     * @param whole the refund cancels the instalment's whole unpaid principal - its assessed late fees are released
     *     (OQ-14); otherwise it is only shortened, and they stand
     */
    public record Cancellation(int sequence, Kind kind, Money amount, boolean whole) {

        public Cancellation {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(amount, "amount");
            if (sequence < 1) {
                throw new IllegalArgumentException("an instalment is numbered from 1");
            }
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("instalment " + sequence + " is cancelled a positive principal");
            }
        }

        /** The sequence, kind and wholeness only - never an amount. */
        @Override
        public String toString() {
            return "Cancellation[" + sequence + ", " + kind + (whole ? ", whole]" : ", part]");
        }

        /** Which of the obligation's principal receivables a cancellation credits. */
        public enum Kind {

            /** Not yet due: {@code CR BNPL_PRINCIPAL}. */
            UNBILLED,

            /** Billed and unpaid: {@code CR BNPL_PRINCIPAL_DUE} - which cures that instalment's days past due. */
            BILLED_UNPAID
        }
    }
}
