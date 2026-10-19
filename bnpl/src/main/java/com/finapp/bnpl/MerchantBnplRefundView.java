package com.finapp.bnpl;

import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One refund of a BNPL purchase as its merchant reads it (`P12-TSK-021`, `-014`'s carry; {@code PHASE_12_PLAN.md}
 * sections 9 and 11; {@code INV-MER-01}, {@code INV-CRD-04}) - <strong>a closed field set</strong>: the refund, its amount,
 * the merchant fee it returned and the payable's debit (the refund's net), its instant, the door that admitted it, and the
 * merchant's own reason when the merchant asked. Never which instalments it cancelled, the customer's excess, where it went
 * or anything of the customer's credit - the closed view `-014` drew; an operator's reason is the operator's record, not
 * the merchant's to read. Born only from a statement carrying {@code merchant_id = ?}.
 *
 * @param reason the merchant's own reason - present exactly for a refund at the merchant's door (RESTRICTED-PII, its own)
 */
public record MerchantBnplRefundView(UUID refund, Money amount, Money feeReturned, Money payableDebit, Instant appliedAt,
        BnplRefund.Door door, Optional<String> reason) {

    public MerchantBnplRefundView {
        Objects.requireNonNull(refund, "refund must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(feeReturned, "feeReturned must not be null");
        Objects.requireNonNull(payableDebit, "payableDebit must not be null");
        Objects.requireNonNull(appliedAt, "appliedAt must not be null");
        Objects.requireNonNull(door, "door must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (!feeReturned.plus(payableDebit).equals(amount)) {
            throw new IllegalStateException("a refund is the fee returned plus the payable's debit (INV-BNPL-06)");
        }
        if (reason.isPresent() != (door == BnplRefund.Door.MERCHANT)) {
            throw new IllegalStateException("the merchant reads its own reason, never an operator's");
        }
    }

    /** The merchant's view of a refund as recorded. */
    static MerchantBnplRefundView of(BnplRefund refund) {
        return new MerchantBnplRefundView(refund.id(), refund.amount(), refund.feeReturned(), refund.payableDebit(),
                refund.createdAt().orElseThrow(() -> new IllegalStateException("a stored refund carries its instant")),
                refund.door(), refund.door() == BnplRefund.Door.MERCHANT ? Optional.of(refund.reason()) : Optional.empty());
    }

    /** The refund and its door only - never an amount or the reason. */
    @Override
    public String toString() {
        return "MerchantBnplRefundView[" + refund + ", " + door + "]";
    }
}
