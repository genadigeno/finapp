package com.finapp.lending;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * The Phase 11 terms of each product (`P11-TSK-005`; PHASE_11_PLAN.md section 12.1, A17-A26, L5-L8) - the values the
 * plan names, as a builder each case varies one field of.
 */
final class LoanTermsFixtures {

    static final CurrencyCode EUR = CurrencyCode.of("EUR");

    /**
     * Credit's bounds for each product as {@code app}'s adapter reads them (`P11-TSK-006`): the personal loan EUR 500.00
     * to 25,000.00 over 6 to 60 months, the line none - {@code CreditLoanProductBoundsTest} holds the adapter to these.
     */
    static final LoanProductBounds BOUNDS = product -> product.kind() == LoanKind.INSTALMENT
            ? Optional.of(new LoanProductBounds.Bounds(
                    Money.ofMinorUnits(500_00, EUR), Money.ofMinorUnits(25_000_00, EUR), 6, 60))
            : Optional.empty();

    private LoanTermsFixtures() {}

    /** The personal loan at {@code rate} (a decimal fraction string): every plan value, no origination or late fee. */
    static LoanTerms.LoanTermsBuilder personalLoan(String rate) {
        return common(LoanProduct.PERSONAL_LOAN, rate)
                .overpaymentTreatment(OverpaymentTreatment.HOLD_AS_CREDIT)
                .instalment(Optional.of(new LoanTerms.InstalmentTerms(15, 24, EngineVersion.SCHEDULE_ENGINE_V1)))
                .revolving(Optional.empty())
                .agreementTemplate(AgreementTemplate.PERSONAL_LOAN_AGREEMENT_V1);
    }

    /** The credit line at {@code rate}: a EUR 25.00 floor, 3 %, payment due 25 days after the statement (A23, A24). */
    static LoanTerms.LoanTermsBuilder creditLine(String rate) {
        return common(LoanProduct.CREDIT_LINE, rate)
                .overpaymentTreatment(OverpaymentTreatment.PAY_DOWN_PRINCIPAL)
                .instalment(Optional.empty())
                .revolving(Optional.of(new LoanTerms.RevolvingTerms(
                        Money.ofMinorUnits(25_00, EUR), 300, 25, EngineVersion.STATEMENT_ENGINE_V1)))
                .agreementTemplate(AgreementTemplate.CREDIT_LINE_AGREEMENT_V1);
    }

    static LoanTerms.LoanTermsBuilder of(LoanProduct product, String rate) {
        return product == LoanProduct.PERSONAL_LOAN ? personalLoan(rate) : creditLine(rate);
    }

    private static LoanTerms.LoanTermsBuilder common(LoanProduct product, String rate) {
        return LoanTerms.builder()
                .product(product)
                .nominalAnnualRate(new BigDecimal(rate))
                .rateType(RateType.FIXED)
                .originationFee(LoanTerms.OriginationFee.none(EUR))
                .originationFeeTreatment(OriginationFeeTreatment.DEDUCTED)
                .lateFee(Money.ofMinorUnits(15_00, EUR))
                .lateFeeDay(5)
                .lateFeeCap(Money.ofMinorUnits(60_00, EUR))
                .graceDays(3)
                .delinquencyBounds(List.of(1, 30, 60, 90))
                .defaultThresholdDays(90)
                .allocationSequence(AllocationSequence.OLDEST_DUE_FIRST)
                .allocationOrder(List.of(AllocationComponent.FEES, AllocationComponent.INTEREST, AllocationComponent.PRINCIPAL))
                // Auto-collection off by default (P11-TSK-019): every suite written before it bills against a wallet
                // holding the disbursed principal and expects the bill to stay due; AutoCollectionDatabaseTest activates
                // its own terms with it on (A21's plan default).
                .autoCollection(false)
                .partialCollection(true)
                .instalmentRounding(RoundingMode.UP)
                .interestRounding(RoundingMode.HALF_EVEN)
                .dayCount(DayCountConvention.ACT_365F)
                .servicingZone("UTC")
                .allowedDays(List.of(1, 5, 10, 15, 20, 25, 28))
                .offerValidityDays(14)
                .payoffHorizonDays(30)
                .accrualEngine(EngineVersion.ACCRUAL_ENGINE_V1)
                .allocationEngine(EngineVersion.ALLOCATION_ENGINE_V1);
    }
}
