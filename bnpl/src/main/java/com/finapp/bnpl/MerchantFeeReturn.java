package com.finapp.bnpl;

import com.finapp.sharedkernel.money.Money;

/**
 * The merchant fee a BNPL refund returns - the one fee arithmetic, handed to {@link RefundAllocationEngineV1} as a
 * parameter rather than restated in it (`P12-TSK-009`; ADR-0105 decision 3, {@code PHASE_12_PLAN.md} section 12.5;
 * {@code INV-BNPL-06}, {@code INV-BNPL-07}, {@code INV-MER-04}).
 *
 * <p><strong>Whose arithmetic it is.</strong> The fee is the merchant's, priced by its pinned BNPL fee schedule version
 * ({@code INV-MER-03}), and its pro-rating back is merchant's {@code FeeCalculation.returnedFee} - the {@code RETURNED}
 * policy's cumulative method the card refund already uses:
 *
 * <pre>
 *   cum(x)   = round(f · x / G, the pinned version's policy)
 *   returned = cum(refundedBefore + refunded) − cum(refundedBefore)
 * </pre>
 *
 * which telescopes to exactly {@code f} over any sequence of refunds totalling {@code G}, in any order. bnpl has no
 * build edge to {@code merchant} ({@code BnplModuleIsolationTest}), so it never computes that figure: in production the
 * refund (`P12-TSK-021`) binds this to the merchant port's {@code returnedFee(uow, purchaseId, refundedBefore,
 * refunded)} (`P12-TSK-006`) for the purchase under its lock, and the engine's hermetic suites bind it to a reference
 * implementation ({@code bnpl}'s test fixtures) that {@code app}'s {@code BnplFeeArithmeticAgreesWithMerchantTest}
 * proves equal to {@code FeeCalculation.returnedFee} - never a second production version of the arithmetic.
 *
 * <p><strong>What the engine checks of the answer</strong> - not the arithmetic, which is the merchant's, but the
 * properties every refund rests on: the figure is in the purchase's currency at its scale, never negative and never
 * more than the refund (so the payable debit {@code R − r_f} is never negative); what all the purchase's refunds return
 * never exceeds {@code f}; and a refund that completes the purchase brings the total to {@code f} exactly. A binding
 * that breaks one is a defect ({@link IllegalStateException}), never a business refusal.
 */
@FunctionalInterface
public interface MerchantFeeReturn {

    /**
     * The fee a refund of {@code refunded} returns, {@code refundedBefore} of the purchase having been refunded already
     * - both committed sums, so the answer is exact (a BNPL refund has no sibling in flight under the purchase's lock).
     *
     * @param refundedBefore what earlier refunds of the purchase returned in total; zero for the first
     * @param refunded this refund's amount, positive
     * @return the merchant fee returned, in the purchase's currency
     */
    Money returnedFee(Money refundedBefore, Money refunded);
}
