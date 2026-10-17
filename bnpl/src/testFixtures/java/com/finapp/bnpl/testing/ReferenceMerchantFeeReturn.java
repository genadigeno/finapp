package com.finapp.bnpl.testing;

import com.finapp.bnpl.MerchantFeeReturn;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The merchant fee a BNPL refund returns, for tests only - the reference the refund allocation engine's hermetic suites
 * bind {@link MerchantFeeReturn} to (`P12-TSK-009`), because bnpl has no build edge to {@code merchant}, whose
 * {@code FeeCalculation.returnedFee} is the production arithmetic. <strong>Never a second production version</strong>:
 * this class ships in no jar, and {@code app}'s {@code BnplFeeArithmeticAgreesWithMerchantTest} proves it equal to
 * {@code FeeCalculation.returnedFee} - its every generated case, and the one where a single exact rounding would differ
 * from merchant's.
 *
 * <pre>
 *   cum(x)   = round(round(f · x / G, 12 places, HALF_UP), the policy)    cum(0) = 0
 *   returned = cum(refundedBefore + refunded) − cum(refundedBefore)
 * </pre>
 *
 * <p>The twelve-place intermediate is merchant's ({@code FeeCalculation}'s working scale, a truncation guard before the
 * policy's one rounding to money) and is mirrored exactly: on a gross above about 10^10 minor units an exact rational
 * rounded once can land a minor unit away from it, and a reference that differed there would prove the engine against
 * an arithmetic production does not run.
 *
 * @param gross the purchase's gross {@code G}
 * @param fee its merchant fee {@code f}
 * @param policy the pinned fee schedule version's rounding policy (A8: {@code HALF_EVEN})
 */
public record ReferenceMerchantFeeReturn(Money gross, Money fee, RoundingPolicy policy) implements MerchantFeeReturn {

    /** {@code FeeCalculation}'s working scale for the proportional share. */
    public static final int SHARE_SCALE = 12;

    public ReferenceMerchantFeeReturn {
        Objects.requireNonNull(gross, "gross");
        Objects.requireNonNull(fee, "fee");
        Objects.requireNonNull(policy, "policy");
        if (!gross.isPositive()) {
            throw new IllegalArgumentException("a pinned gross is positive");
        }
        // plus() refuses a currency or scale mismatch.
        gross.plus(fee);
    }

    /** The reference under A8's default policy, {@code HALF_EVEN}. */
    public static ReferenceMerchantFeeReturn halfEven(Money gross, Money fee) {
        return new ReferenceMerchantFeeReturn(gross, fee, RoundingPolicy.HALF_EVEN);
    }

    @Override
    public Money returnedFee(Money refundedBefore, Money refunded) {
        Objects.requireNonNull(refundedBefore, "refundedBefore");
        Objects.requireNonNull(refunded, "refunded");
        Money after = refundedBefore.plus(refunded);
        if (after.compareTo(gross) > 0) {
            throw new IllegalArgumentException("refunds cannot exceed the gross");
        }
        return cumulative(after).minus(cumulative(refundedBefore));
    }

    /** {@code cum(x)}: the fee owed back once {@code refunded} of the gross has been refunded in total. */
    public Money cumulative(Money refunded) {
        CurrencyCode currency = fee.currency();
        if (refunded.isZero()) {
            return Money.ofMinorUnits(0L, currency);
        }
        BigDecimal share = fee.toBigDecimal().multiply(refunded.toBigDecimal())
                .divide(gross.toBigDecimal(), SHARE_SCALE, RoundingMode.HALF_UP);
        return Money.of(share, currency, policy);
    }

    /** The policy only - never an amount. */
    @Override
    public String toString() {
        return "ReferenceMerchantFeeReturn[" + policy + "]";
    }
}
