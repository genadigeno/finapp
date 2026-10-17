package com.finapp.bnpl;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.creditmath.AllocationSequence;
import com.finapp.creditmath.DayCountConvention;
import com.finapp.creditmath.EngineVersion;
import com.finapp.creditmath.OverpaymentTreatment;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * The BNPL terms the tests propose (`P12-TSK-004`) - the plan's defaults (A4-A7, A13, A14, A17, A22, OQ-10): a late fee
 * of EUR 5.00 on day 5 past due after 3 days' grace, capped at EUR 15.00 per agreement (tests configure one, production
 * none - A13); buckets from 1 to the default at 90; fees, interest, principal, the excess held as credit; daily partial
 * auto-collection; UTC; a 30-minute offer cap; a 14-day dispute window and a EUR 250.00 dispute four-eyes threshold; a
 * 30-day payoff horizon. And credit's bounds as {@code CreditProduct} declares them ({@code CreditBnplProductBoundsTest}
 * holds them equal): pay-in-4 EUR 50.00 to 1,000.00, pay-in-N EUR 200.00 to 5,000.00 over 3, 6 or 12 months.
 */
final class BnplTermsFixtures {

    static final CurrencyCode EUR = CurrencyCode.of("EUR");

    /** Credit's bounds (`P12-TSK-001`'s products), as the app's adapter reads them. */
    static final BnplProductBounds BOUNDS = product -> switch (product) {
        case BNPL_PAY_IN_4 -> new BnplProductBounds.Bounds(euros(50_00), euros(1_000_00), List.of());
        case BNPL_PAY_IN_N -> new BnplProductBounds.Bounds(euros(200_00), euros(5_000_00), List.of(3, 6, 12));
    };

    private BnplTermsFixtures() {}

    /** Pay-in-4's terms: four instalments every 14 days, the first at purchase, no interest. */
    static BnplTerms.BnplTermsBuilder payIn4() {
        return common(BnplProduct.BNPL_PAY_IN_4)
                .frequency(InstalmentFrequency.BIWEEKLY)
                .nominalAnnualRate(BigDecimal.ZERO)
                .instalmentRounding(RoundingMode.DOWN)
                .payIn4(Optional.of(new BnplTerms.PayIn4Terms(4, 14, BnplEngine.BNPL_SCHEDULE_ENGINE_V1)))
                .payInN(Optional.empty())
                .agreementTemplate(BnplAgreementTemplate.BNPL_PAY_IN_4_AGREEMENT_V1);
    }

    /** Pay-in-N's terms at {@code rate}: 3, 6 or 12 months, the first due a month after the purchase, ACT/365F. */
    static BnplTerms.BnplTermsBuilder payInN(String rate) {
        return common(BnplProduct.BNPL_PAY_IN_N)
                .frequency(InstalmentFrequency.MONTHLY)
                .nominalAnnualRate(new BigDecimal(rate))
                .instalmentRounding(RoundingMode.UP)
                .payIn4(Optional.empty())
                .payInN(Optional.of(payInNPart(List.of(3, 6, 12), 15)))
                .agreementTemplate(BnplAgreementTemplate.BNPL_PAY_IN_N_AGREEMENT_V1);
    }

    /** Pay-in-N's own part: {@code terms} offered, the first-period floor {@code minFirstPeriodDays}. */
    static BnplTerms.PayInNTerms payInNPart(List<Integer> terms, int minFirstPeriodDays) {
        return new BnplTerms.PayInNTerms(terms, minFirstPeriodDays, DayCountConvention.ACT_365F, RoundingMode.HALF_EVEN,
                EngineVersion.SCHEDULE_ENGINE_V1, EngineVersion.ACCRUAL_ENGINE_V1);
    }

    static Money euros(long minorUnits) {
        return Money.ofMinorUnits(minorUnits, EUR);
    }

    private static BnplTerms.BnplTermsBuilder common(BnplProduct product) {
        return BnplTerms.builder()
                .product(product)
                .lateFee(euros(5_00))
                .lateFeeDay(5)
                .lateFeeCap(euros(15_00))
                .graceDays(3)
                .delinquencyBounds(List.of(1, 30, 60, 90))
                .defaultThresholdDays(90)
                .allocationSequence(AllocationSequence.OLDEST_DUE_FIRST)
                .allocationOrder(List.of(AllocationComponent.FEES, AllocationComponent.INTEREST, AllocationComponent.PRINCIPAL))
                .overpaymentTreatment(OverpaymentTreatment.HOLD_AS_CREDIT)
                .autoCollection(true)
                .partialCollection(true)
                .collectionRetryDays(1)
                .servicingZone("UTC")
                .offerValidityMinutes(30)
                .disputeResponseDays(14)
                .disputeFourEyesThreshold(euros(250_00))
                .payoffHorizonDays(30)
                .allocationEngine(EngineVersion.ALLOCATION_ENGINE_V1)
                .refundAllocationEngine(BnplEngine.REFUND_ALLOCATION_ENGINE_V1);
    }
}
