package com.finapp.lending;

import static com.finapp.lending.LoanTermsFixtures.EUR;
import static com.finapp.lending.LoanTermsFixtures.creditLine;
import static com.finapp.lending.LoanTermsFixtures.personalLoan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The term set judged whole before anything is written (`P11-TSK-005`; ADR-0092 sections 2-3, {@code INV-LND-09}):
 * both products' plan terms admitted; every unimplemented convention, incomplete allocation order, other product's
 * term and out-of-bounds value refused {@code lending.TermsInvalid}, the detail naming the defect and never the value.
 * {@code lending V003}'s every-writer ranks are {@code LoanTermsDatabaseTest}'s.
 */
@DisplayName("loan terms: judged whole, only implemented conventions (P11-TSK-005, INV-LND-09)")
class LoanTermsTest {

    @Test
    @DisplayName("the origination fee on a principal (P11-TSK-014, A17): an amount as stated; basis points of the principal"
            + " at its scale, one division rounded once HALF_EVEN to the minor unit")
    void theOriginationFeeOnAPrincipal() {
        Money principal = Money.ofMinorUnits(4_000_00, EUR);
        assertThat(LoanTerms.OriginationFee.ofBasisPoints(150).on(principal)).isEqualTo(Money.ofMinorUnits(60_00, EUR));
        assertThat(LoanTerms.OriginationFee.none(EUR).on(principal).isZero()).isTrue();
        assertThat(LoanTerms.OriginationFee.ofAmount(Money.ofMinorUnits(25_00, EUR)).on(principal))
                .isEqualTo(Money.ofMinorUnits(25_00, EUR));
        // 1,234.57 x 1.25 % = 15.432125 -> 15.43; 1,000.02 x 0.25 % = 2.50005 -> 2.50; the half: 1.00 x 50 bps = 0.005 -> 0.00
        assertThat(LoanTerms.OriginationFee.ofBasisPoints(125).on(Money.ofMinorUnits(1_234_57, EUR)))
                .isEqualTo(Money.ofMinorUnits(15_43, EUR));
        assertThat(LoanTerms.OriginationFee.ofBasisPoints(25).on(Money.ofMinorUnits(1_000_02, EUR)))
                .isEqualTo(Money.ofMinorUnits(2_50, EUR));
        assertThat(LoanTerms.OriginationFee.ofBasisPoints(50).on(Money.ofMinorUnits(1_00, EUR)).isZero()).isTrue();
        assertThat(LoanTerms.OriginationFee.ofBasisPoints(150).on(Money.ofMinorUnits(1_00, EUR)))
                .as("0.015 -> 0.02, the even neighbour").isEqualTo(Money.ofMinorUnits(2, EUR));
    }

    @Test
    @DisplayName("both products' plan terms are admitted, the rate canonical and the zone spelled once")
    void thePlanTermsAreAdmitted() {
        LoanTerms loan = personalLoan("0.08990").servicingZone("Z").build();
        assertThat(loan.nominalAnnualRate()).isEqualByComparingTo("0.0899");
        assertThat(loan.nominalAnnualRate().toPlainString()).as("no trailing zero").isEqualTo("0.0899");
        assertThat(loan.servicingZone()).as("a zero offset is UTC").isEqualTo("UTC");
        assertThat(personalLoan("1.00").build().nominalAnnualRate().toPlainString()).isEqualTo("1");
        assertThat(personalLoan("0.000").build().nominalAnnualRate().toPlainString()).as("r = 0 (ADR-0093)").isEqualTo("0");
        assertThat(personalLoan("0.12").servicingZone("+02:00").build().servicingZone()).isEqualTo("+02:00");
        // The widest offsets, east and west - each offerable end to end (P11-TSK-031, canonical offer terms format 2).
        assertThat(personalLoan("0.12").servicingZone("+14:00").build().servicingZone()).isEqualTo("+14:00");
        assertThat(personalLoan("0.12").servicingZone("-14:00").build().servicingZone()).isEqualTo("-14:00");
        assertThat(personalLoan("0.12").servicingZone("+05:30").build().servicingZone()).isEqualTo("+05:30");
        LoanTerms line = creditLine("0.1499").originationFee(LoanTerms.OriginationFee.ofBasisPoints(150)).build();
        assertThat(line.revolving()).isPresent();
        assertThat(line.instalment()).isEmpty();
        assertThat(line.toString()).as("never a rate or an amount").isEqualTo("LoanTerms[CREDIT_LINE]")
                .doesNotContain("0.1499").doesNotContain("150");
    }

    @Test
    @DisplayName("a convention no engine version implements is refused: a rounding mode, a day count beyond the accrual"
            + " engine named, a zone with daylight saving or seconds, an engine version of another engine")
    void anUnimplementedConventionIsRefused() {
        refused(terms -> terms.instalmentRounding(RoundingMode.HALF_UP), "rounding mode");
        refused(terms -> terms.interestRounding(RoundingMode.DOWN), "rounding mode");
        refused(terms -> terms.servicingZone("Europe/London"), "fixed offset");
        refused(terms -> terms.servicingZone("+02:00:30"), "whole minutes");
        refused(terms -> terms.servicingZone("+15:00"), "zone");
        refused(terms -> terms.servicingZone("+14:01"), "zone");
        refused(terms -> terms.servicingZone("Mars/Olympus"), "zone");
        refused(terms -> terms.accrualEngine(EngineVersion.ALLOCATION_ENGINE_V1), "accrual engine");
        refused(terms -> terms.allocationEngine(EngineVersion.ACCRUAL_ENGINE_V1), "allocation engine");
        refused(terms -> terms.instalment(Optional.of(new LoanTerms.InstalmentTerms(15, 24, EngineVersion.STATEMENT_ENGINE_V1))),
                "schedule engine");
        assertThat(DayCountConvention.values()).as("ACT_360 is not even nameable (INV-LND-09)")
                .containsExactly(DayCountConvention.ACT_365F);
        assertThat(RateType.values()).as("a variable rate is not nameable").containsExactly(RateType.FIXED);
    }

    @Test
    @DisplayName("an allocation order omitting or repeating a component is refused; any complete order is admitted")
    void anAllocationOrderNamesEveryComponentOnce() {
        refused(terms -> terms.allocationOrder(List.of(AllocationComponent.FEES, AllocationComponent.INTEREST)),
                "every component exactly once");
        refused(terms -> terms.allocationOrder(List.of(AllocationComponent.FEES, AllocationComponent.FEES,
                AllocationComponent.PRINCIPAL)), "every component exactly once");
        assertThat(personalLoan("0.1").allocationOrder(List.of(AllocationComponent.INTEREST, AllocationComponent.FEES,
                AllocationComponent.PRINCIPAL)).build().allocationOrder()).startsWith(AllocationComponent.INTEREST);
    }

    @Test
    @DisplayName("each product carries its kind's own terms and no other's, its own overpayment treatment and template")
    void eachProductCarriesItsOwnTerms() {
        refused(terms -> terms.instalment(Optional.empty()), "own terms");
        refused(terms -> terms.revolving(creditLine("0.1").build().revolving()), "own terms");
        refused(terms -> terms.overpaymentTreatment(OverpaymentTreatment.PAY_DOWN_PRINCIPAL), "overpayment");
        refused(terms -> terms.agreementTemplate(AgreementTemplate.CREDIT_LINE_AGREEMENT_V1), "template");
        assertThatThrownBy(() -> creditLine("0.1").overpaymentTreatment(OverpaymentTreatment.HOLD_AS_CREDIT).build())
                .isInstanceOf(LoanTerms.TermsInvalid.class);
        assertThatThrownBy(() -> creditLine("0.1").instalment(personalLoan("0.1").build().instalment()).build())
                .isInstanceOf(LoanTerms.TermsInvalid.class);
    }

    @Test
    @DisplayName("every value outside the bounds lending or the product declares is refused, the detail never the value")
    void outOfBoundsValuesAreRefused() {
        refused(terms -> terms.nominalAnnualRate(new BigDecimal("1.0001")), "nominal annual rate");
        refused(terms -> terms.nominalAnnualRate(new BigDecimal("-0.01")), "nominal annual rate");
        refused(terms -> terms.nominalAnnualRate(new BigDecimal("0.123456789")), "decimal places");
        refused(terms -> terms.originationFee(LoanTerms.OriginationFee.ofBasisPoints(10_000)), "basis points");
        refused(terms -> terms.originationFee(LoanTerms.OriginationFee.ofAmount(Money.ofMinorUnits(-1, EUR))), "origination fee");
        refused(terms -> terms.originationFee(LoanTerms.OriginationFee.ofAmount(Money.ofMinorUnits(1, CurrencyCode.of("GBP")))),
                "currency");
        assertThatThrownBy(() -> new LoanTerms.OriginationFee(Optional.of(Money.zero(EUR)), Optional.of(10)))
                .isInstanceOf(LoanTerms.TermsInvalid.class).hasMessageContaining("exactly one");
        refused(terms -> terms.lateFee(Money.ofMinorUnits(-1, EUR)), "late fee");
        refused(terms -> terms.lateFeeCap(Money.ofMinorUnits(14_99, EUR)), "cap");
        refused(terms -> terms.lateFeeDay(3), "beyond grace");
        refused(terms -> terms.lateFeeDay(91), "beyond grace");
        refused(terms -> terms.graceDays(31), "grace");
        refused(terms -> terms.defaultThresholdDays(29).delinquencyBounds(List.of(1, 10, 20, 29)), "default threshold");
        refused(terms -> terms.delinquencyBounds(List.of(1, 30, 60)), "delinquency bounds");
        refused(terms -> terms.delinquencyBounds(List.of(2, 30, 60, 90)), "delinquency bounds");
        refused(terms -> terms.delinquencyBounds(List.of(1, 60, 30, 90)), "delinquency bounds");
        refused(terms -> terms.delinquencyBounds(List.of(1, 30, 60, 89)), "delinquency bounds");
        refused(terms -> terms.allowedDays(List.of()), "allowed days");
        refused(terms -> terms.allowedDays(List.of(0, 5)), "allowed days");
        refused(terms -> terms.allowedDays(List.of(5, 29)), "allowed days");
        refused(terms -> terms.allowedDays(List.of(10, 5)), "allowed days");
        refused(terms -> terms.offerValidityDays(15), "product");
        refused(terms -> terms.offerValidityDays(0), "validity");
        refused(terms -> terms.payoffHorizonDays(91), "payoff horizon");
        refused(terms -> terms.instalment(Optional.of(new LoanTerms.InstalmentTerms(29, 24, EngineVersion.SCHEDULE_ENGINE_V1))),
                "first period");
        refused(terms -> terms.instalment(Optional.of(new LoanTerms.InstalmentTerms(15, 25, EngineVersion.SCHEDULE_ENGINE_V1))),
                "product");
        refusedLine(terms -> terms.revolving(Optional.of(new LoanTerms.RevolvingTerms(Money.zero(EUR), 300, 25,
                EngineVersion.STATEMENT_ENGINE_V1))), "floor");
        refusedLine(terms -> terms.revolving(Optional.of(new LoanTerms.RevolvingTerms(Money.ofMinorUnits(25_00, EUR), 0, 25,
                EngineVersion.STATEMENT_ENGINE_V1))), "ratio");
        refusedLine(terms -> terms.revolving(Optional.of(new LoanTerms.RevolvingTerms(Money.ofMinorUnits(25_00, EUR), 300, 28,
                EngineVersion.STATEMENT_ENGINE_V1))), "payment falls due");
        refused(terms -> terms.product(null), "product");
    }

    @Test
    @DisplayName("the product's declared ceilings bound the terms: offer validity and disbursement deadline")
    void theProductsCeilingsBoundTheTerms() {
        for (LoanProduct product : LoanProduct.values()) {
            assertThat(LoanTermsFixtures.of(product, "0.1").offerValidityDays((int) product.offerValidity().toDays())
                    .build().offerValidityDays()).isEqualTo(14);
        }
        assertThat(personalLoan("0.1").build().instalment().orElseThrow().disbursementDeadlineHours())
                .isEqualTo((int) LoanProduct.PERSONAL_LOAN.disbursementDeadline().toHours());
    }

    private static void refused(UnaryOperator<LoanTerms.LoanTermsBuilder> change, String detail) {
        assertThatThrownBy(() -> change.apply(personalLoan("0.0899")).build())
                .as(detail)
                .isInstanceOf(LoanTerms.TermsInvalid.class)
                .hasMessageContaining(detail)
                .hasMessageNotContaining("0.0899");
    }

    private static void refusedLine(UnaryOperator<LoanTerms.LoanTermsBuilder> change, String detail) {
        assertThatThrownBy(() -> change.apply(creditLine("0.1499")).build())
                .as(detail)
                .isInstanceOf(LoanTerms.TermsInvalid.class)
                .hasMessageContaining(detail);
    }
}
