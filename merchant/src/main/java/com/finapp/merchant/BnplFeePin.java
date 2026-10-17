package com.finapp.merchant;

import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Which BNPL fee schedule version prices one BNPL purchase (`P12-TSK-006`; ADR-0103 decision 7, PHASE_12_PLAN.md
 * section 12.5; {@code INV-MER-03}, {@code INV-HIST-04}) - {@link PaymentFeePin}'s twin for a purchase, which the
 * intent-keyed pin cannot hold.
 *
 * <p><strong>One per purchase, born in the purchase's transaction and never changed</strong> (`V010`: the purchase is the
 * primary key; no UPDATE or DELETE grant; V004's unconditional trigger reused). The fee and the merchant's net are not
 * stored: they are the pinned version applied to the pinned gross, recomputable to the minor unit forever, and a stored
 * copy would be a second figure that could disagree ({@code INV-MER-02}'s reasoning - this schema holds no amount owed).
 *
 * @param purchaseRef bnpl's purchase, by value - no cross-schema foreign key (ADR-0029)
 * @param merchantId whose sale it is
 * @param product the BNPL product it was financed on - its BNPL schedule is per (merchant, product)
 * @param versionId the version that priced it
 * @param gross what was financed - the amount every returned fee is judged against
 * @param pinnedAt when the version was resolved - the application clock, the fee schedules' own time basis (V004)
 * @param pinnedBy the acting principal's id
 */
public record BnplFeePin(
        UUID purchaseRef,
        MerchantId merchantId,
        MerchantBnplProduct product,
        FeeScheduleVersionId versionId,
        Money gross,
        Instant pinnedAt,
        String pinnedBy) {

    public BnplFeePin {
        Objects.requireNonNull(purchaseRef, "purchaseRef must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(product, "product must not be null");
        Objects.requireNonNull(versionId, "versionId must not be null");
        Objects.requireNonNull(gross, "gross must not be null");
        if (!gross.isPositive()) {
            throw new IllegalArgumentException("a financed gross is positive");
        }
        Objects.requireNonNull(pinnedAt, "pinnedAt must not be null");
        Objects.requireNonNull(pinnedBy, "pinnedBy must not be null");
    }

    /** Identifiers only - never the gross ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "BnplFeePin[purchase=" + purchaseRef + ", merchant=" + merchantId + ", " + product + ", version="
                + versionId + "]";
    }
}
