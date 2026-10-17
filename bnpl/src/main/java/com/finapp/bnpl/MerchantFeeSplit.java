package com.finapp.bnpl;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * One BNPL purchase's gross split into the merchant's fee and its net (`P12-TSK-006`; {@code INV-BNPL-04},
 * {@code INV-MER-04}, ADR-0058) - what {@link BnplMerchants#pinFee} returns: {@code G = f + net} exactly, the fee computed
 * once by the merchant's pinned BNPL fee schedule version and the net derived by subtraction, never rounded on its own;
 * {@code 0 <= f < G}, so the merchant is credited something for every sale. Re-asserted here at the boundary: the class
 * that guarantees the property (merchant's {@code FeeCalculation}) and the class bnpl posts from are different classes,
 * and a split that reached bnpl without the property is refused rather than posted.
 *
 * @param gross the purchase's gross G - the principal financed
 * @param fee the merchant discount f - {@code BNPL_MERCHANT_FEE_INCOME} at purchase (A9)
 * @param net G - f - what the merchant's payable is credited, net
 * @param feeScheduleVersionId the merchant fee schedule version pinned on the purchase, by value
 */
public record MerchantFeeSplit(Money gross, Money fee, Money net, UUID feeScheduleVersionId) {

    public MerchantFeeSplit {
        Objects.requireNonNull(gross, "gross must not be null");
        Objects.requireNonNull(fee, "fee must not be null");
        Objects.requireNonNull(net, "net must not be null");
        Objects.requireNonNull(feeScheduleVersionId, "feeScheduleVersionId must not be null");
        if (!gross.isPositive()) {
            throw new IllegalArgumentException("a financed gross is positive");
        }
        if (fee.isNegative()) {
            throw new IllegalArgumentException("a merchant fee is never negative");
        }
        if (!net.isPositive()) {
            throw new IllegalArgumentException("a sale covers its fee: the net is positive (ADR-0058)");
        }
        // plus() refuses a currency or scale mismatch, so this compares like with like or throws saying why.
        if (!fee.plus(net).equals(gross)) {
            throw new IllegalArgumentException("a fee split conserves the gross exactly (INV-BNPL-04)");
        }
    }

    /** The version only - the amounts stay out of logs ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "MerchantFeeSplit[version=" + feeScheduleVersionId + "]";
    }
}
