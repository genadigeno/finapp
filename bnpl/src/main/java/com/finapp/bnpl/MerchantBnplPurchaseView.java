package com.finapp.bnpl;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A BNPL purchase as its merchant reads it (`P12-TSK-014`; {@code PHASE_12_PLAN.md} sections 9 and 11,
 * {@code DELIVERY_PLAN.md} section 9; {@code INV-MER-01}, {@code INV-MER-03}, {@code INV-BNPL-02}, {@code INV-BNPL-04},
 * {@code INV-CRD-04}) - <strong>a closed field set</strong>: the purchase, the checkout session and order the merchant
 * already holds, the product its fee schedule is assigned for, the purchase's status, the financing's gross, fee and net,
 * the fee schedule version the purchase pinned, the financing entry F1 and its instant. Nothing of the customer's credit
 * - no party, plan, decision, eligibility reason, score, amount approved, schedule, repayment, late fee or delinquency -
 * has a component to travel in, so one cannot be added without changing this type and the test that pins its components
 * ({@code MerchantBnplPurchaseViewTest#theFieldSetIsClosed}).
 *
 * <p>Born only from one statement over the purchase and its merchant financing ({@link BnplPurchaseStore#atMerchant}),
 * which the purchase's one transaction commits together ({@code INV-BNPL-02}) - so a purchase never reads without its
 * financing, and never {@code AUTHORISED}, a state no transaction commits. The figures are the financing's born-once row,
 * held to {@code gross = fee + net} here as the database's {@code CHECK} holds them ({@code INV-BNPL-04}, as read); the
 * fee and version are the purchase's pin, never the merchant's current schedule ({@code INV-MER-03}).
 *
 * @param checkoutSession checkout's session the purchase paid, by value
 * @param order checkout's order born with the financing
 * @param feeScheduleVersion the merchant fee schedule version pinned on the purchase ({@code bnpl_fee_pin}, by value)
 * @param financingEntry the financing entry F1 ({@code bnpl.financing:<purchase>}) - the entry merchant's transaction
 *     read names
 * @param financedAt the financing's instant, the database's
 */
public record MerchantBnplPurchaseView(
        BnplPurchaseId purchase,
        UUID checkoutSession,
        UUID order,
        BnplProduct product,
        BnplPurchaseStatus status,
        Money gross,
        Money fee,
        Money net,
        UUID feeScheduleVersion,
        JournalEntryId financingEntry,
        Instant financedAt) {

    public MerchantBnplPurchaseView {
        Objects.requireNonNull(purchase, "purchase must not be null");
        Objects.requireNonNull(checkoutSession, "checkoutSession must not be null");
        Objects.requireNonNull(order, "order must not be null");
        Objects.requireNonNull(product, "product must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(gross, "gross must not be null");
        Objects.requireNonNull(fee, "fee must not be null");
        Objects.requireNonNull(net, "net must not be null");
        Objects.requireNonNull(feeScheduleVersion, "feeScheduleVersion must not be null");
        Objects.requireNonNull(financingEntry, "financingEntry must not be null");
        Objects.requireNonNull(financedAt, "financedAt must not be null");
        if (status == BnplPurchaseStatus.AUTHORISED) {
            throw new IllegalStateException(
                    "a BNPL purchase is never committed AUTHORISED - it is FINANCED beside its financing (INV-BNPL-02)");
        }
        if (!fee.plus(net).equals(gross)) {
            throw new IllegalStateException(
                    "a BNPL merchant financing conserves its purchase: gross = fee + net (INV-BNPL-04)");
        }
    }

    /** The purchase and its status only - never a figure, the merchant or the order ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "MerchantBnplPurchaseView[" + purchase.value() + ", " + status + "]";
    }
}
