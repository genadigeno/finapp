package com.finapp.bnpl;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A BNPL refund as recorded (`P12-TSK-021`, {@code bnpl V012}'s {@code bnpl_refund}; owner decisions B3 and B7, ADR-0105;
 * {@code BNPL_LIFECYCLES.md} section 4.9) - born {@code APPLIED}: a refund exists only once its effects posted, in the one
 * transaction that locked the purchase (B5) and the obligation (B4), split it latest instalment first, posted F5/F5a and
 * wrote it with its {@link Adjustment}s. There is no other state: no provider, no pending, no reversal of a refund (a
 * mistaken refund's correction is a future product act, never an edit).
 *
 * <p><strong>Value is preserved by construction</strong> - a value of this type conserves or does not exist
 * ({@code INV-BNPL-06}): {@code R = feeReturned + payableDebit} and {@code R = cancelledUnbilled + cancelledBilled +
 * excess}, the adjustments summing to the two cancellations and the billed interest released; the excess goes somewhere
 * exactly when it is positive. The database holds the same at two ranks ({@code bnpl V012}'s row {@code CHECK} and its
 * deferred conservation). RESTRICTED-FINANCIAL; the reason RESTRICTED-PII - {@link #toString()} names the identifiers
 * only.
 *
 * @param requestedBy who asked, under which key namespace: {@code MERCHANT:<merchant>} at the merchant's door,
 *     {@code OPERATOR:<identity>} at the operator's - with {@code merchant} and {@code refundKey} the arbiter behind a lapsed
 *     claim ({@code UNIQUE (merchant_id, requested_by, refund_key)})
 * @param refundedBefore Σ the purchase's committed refunds read under B5 - what the fee returned is cumulative over
 * @param interestAccruedReleased pay-in-N accrued interest released ({@code CR BNPL_INTEREST_ACCRUED}, A11)
 * @param interestDueReleased pay-in-N billed interest released ({@code CR BNPL_INTEREST_DUE}), Σ the adjustments' own
 * @param agreementVersion the agreement version in force after the refund - n+1 when it cancelled unbilled principal
 * @param scheduleVersion the schedule version in force after the refund
 * @param createdAt the database's instant, stamped after the locks - empty until the row is born
 */
public record BnplRefund(
        UUID id,
        BnplPurchaseId purchase,
        BnplObligationId obligation,
        UUID merchant,
        Door door,
        String requestedBy,
        String refundKey,
        Money amount,
        Money refundedBefore,
        Money feeReturned,
        Money payableDebit,
        Money cancelledUnbilled,
        Money cancelledBilled,
        Money excess,
        ExcessTo excessTo,
        Money interestAccruedReleased,
        Money interestDueReleased,
        String allocationEngine,
        BnplAgreementId agreement,
        int agreementVersion,
        int scheduleVersion,
        String reason,
        JournalEntryId entry,
        LocalDate valueDate,
        Optional<Instant> createdAt,
        CorrelationId correlation,
        List<Adjustment> adjustments) {

    /** Which door admitted it. */
    public enum Door {

        /** The merchant, over its own API key carrying the BNPL refund scope. */
        MERCHANT,

        /** An operator, beside the merchant's door (ADR-0105 decision 9; {@code PAYMENT_REFUND}). */
        OPERATOR
    }

    /** Where the excess owed to the customer went. */
    public enum ExcessTo {

        /** There was none: the refund cancelled only principal still owed. */
        NONE,

        /** The customer's {@code CUSTOMER_WALLET} (A12). */
        WALLET,

        /** The obligation's {@code BNPL_CREDIT_BALANCE}, while the wallet cannot be credited (OQ-13). */
        CREDIT_BALANCE
    }

    public BnplRefund {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(purchase, "purchase must not be null");
        Objects.requireNonNull(obligation, "obligation must not be null");
        Objects.requireNonNull(merchant, "merchant must not be null");
        Objects.requireNonNull(door, "door must not be null");
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        Objects.requireNonNull(refundKey, "refundKey must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(refundedBefore, "refundedBefore must not be null");
        Objects.requireNonNull(feeReturned, "feeReturned must not be null");
        Objects.requireNonNull(payableDebit, "payableDebit must not be null");
        Objects.requireNonNull(cancelledUnbilled, "cancelledUnbilled must not be null");
        Objects.requireNonNull(cancelledBilled, "cancelledBilled must not be null");
        Objects.requireNonNull(excess, "excess must not be null");
        Objects.requireNonNull(excessTo, "excessTo must not be null");
        Objects.requireNonNull(interestAccruedReleased, "interestAccruedReleased must not be null");
        Objects.requireNonNull(interestDueReleased, "interestDueReleased must not be null");
        Objects.requireNonNull(allocationEngine, "allocationEngine must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        Objects.requireNonNull(valueDate, "valueDate must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        adjustments = List.copyOf(Objects.requireNonNull(adjustments, "adjustments must not be null"));
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a refund is positive");
        }
        if (!feeReturned.plus(payableDebit).equals(amount)) {
            throw new IllegalArgumentException("a refund is the fee returned plus the payable's debit");
        }
        if (!cancelledUnbilled.plus(cancelledBilled).plus(excess).equals(amount)) {
            throw new IllegalArgumentException("a refund preserves value: cancelled + excess = R (INV-BNPL-06)");
        }
        if (excess.isPositive() == (excessTo == ExcessTo.NONE)) {
            throw new IllegalArgumentException("the excess goes somewhere exactly when it is positive");
        }
        Money zero = Money.ofPersisted(0, amount.currency(), amount.scale());
        Money unbilled = zero;
        Money billed = zero;
        Money interest = zero;
        for (Adjustment adjustment : adjustments) {
            if (adjustment.kind() == RefundAllocation.Cancellation.Kind.UNBILLED) {
                unbilled = unbilled.plus(adjustment.principalCancelled());
            } else {
                billed = billed.plus(adjustment.principalCancelled());
            }
            interest = interest.plus(adjustment.interestReleased());
        }
        if (!unbilled.equals(cancelledUnbilled) || !billed.equals(cancelledBilled)
                || !interest.equals(interestDueReleased)) {
            throw new IllegalArgumentException("a refund's adjustments sum to its cancellations and release (INV-BNPL-06)");
        }
    }

    /** Σ the principal the refund cancelled. */
    public Money cancelled() {
        return cancelledUnbilled.plus(cancelledBilled);
    }

    /** Σ the pay-in-N interest it released. */
    public Money interestReleased() {
        return interestAccruedReleased.plus(interestDueReleased);
    }

    /**
     * Σ the late fees it released ({@code DR BNPL_FEE_INCOME / CR BNPL_FEES_DUE}, F5a; OQ-14, `P12-TSK-020`) - the unpaid
     * fees of the billed instalments it cancelled whole - derived from its adjustments, the one record of them.
     */
    public Money lateFeeReleased() {
        Money released = Money.ofPersisted(0, amount.currency(), amount.scale());
        for (Adjustment adjustment : adjustments) {
            released = released.plus(adjustment.lateFeeReleased());
        }
        return released;
    }

    /** The same refund, with the database's instant. */
    public BnplRefund stampedAt(Instant at) {
        return new BnplRefund(id, purchase, obligation, merchant, door, requestedBy, refundKey, amount, refundedBefore,
                feeReturned, payableDebit, cancelledUnbilled, cancelledBilled, excess, excessTo, interestAccruedReleased,
                interestDueReleased, allocationEngine, agreement, agreementVersion, scheduleVersion, reason, entry,
                valueDate, Optional.of(at), correlation, adjustments);
    }

    /** Identifiers and the door only - never an amount, the key or the reason ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "BnplRefund[" + id + ", purchase " + purchase.value() + ", " + door + "]";
    }

    /**
     * One instalment the refund touched ({@code bnpl_refund_adjustment}): the principal it cancelled - unbilled
     * ({@code CR BNPL_PRINCIPAL}) or billed-unpaid ({@code CR BNPL_PRINCIPAL_DUE}, curing its days past due) - the billed
     * interest it released, and whether the instalment owes no principal after it.
     *
     * @param instalment the latest schedule's instalment (unbilled) or the billing's own (billed-unpaid)
     * @param lateFeeReleased the unpaid late fees of a billed instalment cancelled whole, released (OQ-14,
     *     `P12-TSK-020`) - zero otherwise
     */
    public record Adjustment(UUID id, int sequence, UUID instalment, RefundAllocation.Cancellation.Kind kind,
            Money principalCancelled, Money interestReleased, boolean whole, Money lateFeeReleased) {

        public Adjustment {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(instalment, "instalment must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(principalCancelled, "principalCancelled must not be null");
            Objects.requireNonNull(interestReleased, "interestReleased must not be null");
            Objects.requireNonNull(lateFeeReleased, "lateFeeReleased must not be null");
            if (sequence < 1 || principalCancelled.isNegative() || interestReleased.isNegative()
                    || lateFeeReleased.isNegative()
                    || !principalCancelled.plus(interestReleased).plus(lateFeeReleased).isPositive()) {
                throw new IllegalArgumentException("an adjustment cancels or releases something positive");
            }
            if (kind == RefundAllocation.Cancellation.Kind.UNBILLED && !interestReleased.isZero()) {
                throw new IllegalArgumentException("an unbilled instalment has no billed interest to release");
            }
            if (lateFeeReleased.isPositive() && (kind != RefundAllocation.Cancellation.Kind.BILLED_UNPAID || !whole)) {
                throw new IllegalArgumentException("a late fee is released only on a billed instalment cancelled whole"
                        + " (OQ-14)");
            }
        }

        /** An adjustment that releases no late fee - `P12-TSK-021`'s shape. */
        public Adjustment(UUID id, int sequence, UUID instalment, RefundAllocation.Cancellation.Kind kind,
                Money principalCancelled, Money interestReleased, boolean whole) {
            this(id, sequence, instalment, kind, principalCancelled, interestReleased, whole,
                    Money.ofPersisted(0, principalCancelled.currency(), principalCancelled.scale()));
        }

        /** The sequence and kind only. */
        @Override
        public String toString() {
            return "Adjustment[" + sequence + ", " + kind + "]";
        }
    }
}
