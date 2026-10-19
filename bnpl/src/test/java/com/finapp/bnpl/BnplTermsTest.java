package com.finapp.bnpl;

import static com.finapp.bnpl.BnplTermsFixtures.euros;
import static com.finapp.bnpl.BnplTermsFixtures.payIn4;
import static com.finapp.bnpl.BnplTermsFixtures.payInN;
import static com.finapp.bnpl.BnplTermsFixtures.payInNPart;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.creditmath.DayCountConvention;
import com.finapp.creditmath.EngineVersion;
import com.finapp.creditmath.OverpaymentTreatment;
import com.finapp.creditmath.ScheduleTerms;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The BNPL term set's shape checks (`P12-TSK-004`; ADR-0102 decisions 1-4; {@code INV-LND-09} and {@code INV-LND-05} by
 * analogy, {@code INV-MON-01}): a value exists only complete, within bounds, of its own product and naming only what
 * this build's engines implement - judged whole, the detail naming the defect and never a value. {@code bnpl V002}'s
 * {@code CHECK}s hold the same for every writer ({@code BnplTermsMigrationTest}, {@code BnplTermsDatabaseTest}).
 */
@DisplayName("the BNPL term set: complete, in bounds, its own product's, only implemented conventions (P12-TSK-004)")
class BnplTermsTest {

    @Test
    @DisplayName("the plan's defaults are a valid term set for each product - the rate made canonical, the toString naming"
            + " the product only")
    void theDefaultsAreValid() {
        BnplTerms four = payIn4().build();
        assertThat(four.payIn4()).isPresent();
        assertThat(four.payInN()).isEmpty();
        assertThat(four.scheduleTerms()).as("pay-in-4's schedule is bnpl's own engine's").isEmpty();
        BnplTerms n = payInN("0.199900").build();
        assertThat(n.nominalAnnualRate()).isEqualTo(new BigDecimal("0.1999"));
        assertThat(n.payInN().orElseThrow().longestTerm()).isEqualTo(12);
        assertThat(n.toString()).isEqualTo("BnplTerms[BNPL_PAY_IN_N]").doesNotContain("0.1999");
        assertThat(four.toString()).doesNotContain("500");
    }

    @Test
    @DisplayName("pay-in-4 charges no interest: a non-zero rate is refused; its plan is four instalments every 14 days,"
            + " rounded DOWN with the remainder first, on its own schedule engine")
    void payInFourIsInterestFreeAndFixed() {
        refused(payIn4().nominalAnnualRate(new BigDecimal("0.01")), "rate is zero");
        assertThat(payIn4().nominalAnnualRate(new BigDecimal("0.000")).build().nominalAnnualRate())
                .isEqualTo(BigDecimal.ZERO);
        refused(payIn4().payIn4(Optional.of(new BnplTerms.PayIn4Terms(3, 14, BnplEngine.BNPL_SCHEDULE_ENGINE_V1))),
                "4 instalments");
        refused(payIn4().payIn4(Optional.of(new BnplTerms.PayIn4Terms(4, 15, BnplEngine.BNPL_SCHEDULE_ENGINE_V1))),
                "every 14 days");
        refused(payIn4().payIn4(Optional.of(new BnplTerms.PayIn4Terms(4, 14, BnplEngine.REFUND_ALLOCATION_ENGINE_V1))),
                "schedule engine");
        refused(payIn4().instalmentRounding(RoundingMode.UP), "rounding mode");
        refused(payIn4().frequency(InstalmentFrequency.MONTHLY), "frequency");
    }

    @Test
    @DisplayName("pay-in-N offers an ascending, non-empty choice among its 3, 6 and 12 months, with a first-period floor"
            + " of 1 to 28 days (A6) - never a plan the product does not list")
    void payInNPlans() {
        assertThat(payInN("0.2").payInN(Optional.of(payInNPart(List.of(3, 6), 1))).build().payInN().orElseThrow()
                .longestTerm()).isEqualTo(6);
        for (List<Integer> plans : List.of(List.<Integer>of(), List.of(4), List.of(6, 3), List.of(3, 3), List.of(3, 24))) {
            refused(payInN("0.2").payInN(Optional.of(payInNPart(plans, 15))), "plans offered");
        }
        refused(payInN("0.2").payInN(Optional.of(payInNPart(List.of(3, 6, 12), 0))), "first-period floor");
        refused(payInN("0.2").payInN(Optional.of(payInNPart(List.of(3, 6, 12), 29))), "first-period floor");
        assertThat(payInN("0.2").payInN(Optional.of(payInNPart(List.of(12), 28))).build()).isNotNull();
    }

    @Test
    @DisplayName("an unimplemented convention is refused (INV-LND-09): a rounding mode, a day count's engine, another"
            + " engine's version, a zone, an allocation sequence or treatment no engine implements")
    void anUnimplementedConventionIsRefused() {
        refused(payInN("0.2").instalmentRounding(RoundingMode.HALF_EVEN), "rounding mode");
        refused(payInN("0.2").payInN(Optional.of(new BnplTerms.PayInNTerms(List.of(3, 6, 12), 15,
                DayCountConvention.ACT_365F, RoundingMode.HALF_UP, EngineVersion.SCHEDULE_ENGINE_V1,
                EngineVersion.ACCRUAL_ENGINE_V1))), "rounding mode");
        refused(payInN("0.2").payInN(Optional.of(new BnplTerms.PayInNTerms(List.of(3, 6, 12), 15,
                DayCountConvention.ACT_365F, RoundingMode.HALF_EVEN, EngineVersion.STATEMENT_ENGINE_V1,
                EngineVersion.ACCRUAL_ENGINE_V1))), "schedule engine");
        refused(payInN("0.2").payInN(Optional.of(new BnplTerms.PayInNTerms(List.of(3, 6, 12), 15,
                DayCountConvention.ACT_365F, RoundingMode.HALF_EVEN, EngineVersion.SCHEDULE_ENGINE_V1,
                EngineVersion.ALLOCATION_ENGINE_V1))), "accrual engine");
        refused(payInN("0.2").allocationEngine(EngineVersion.ACCRUAL_ENGINE_V1), "allocation engine");
        refused(payInN("0.2").refundAllocationEngine(BnplEngine.BNPL_SCHEDULE_ENGINE_V1), "refund allocation engine");
        refused(payInN("0.2").servicingZone("+01:00"), "UTC");
        refused(payInN("0.2").servicingZone("Europe/Berlin"), "UTC");
        refused(payInN("0.2").overpaymentTreatment(OverpaymentTreatment.PAY_DOWN_PRINCIPAL), "held as a credit");
    }

    @Test
    @DisplayName("each product carries its own plan terms and no other's, and its own agreement template")
    void eachProductItsOwnTerms() {
        refused(payIn4().payInN(Optional.of(payInNPart(List.of(3), 15))), "own plan terms");
        refused(payIn4().payIn4(Optional.empty()), "own plan terms");
        refused(payInN("0.2").payIn4(Optional.of(new BnplTerms.PayIn4Terms(4, 14, BnplEngine.BNPL_SCHEDULE_ENGINE_V1))),
                "own plan terms");
        refused(payInN("0.2").payInN(Optional.empty()), "own plan terms");
        refused(payIn4().agreementTemplate(BnplAgreementTemplate.BNPL_PAY_IN_N_AGREEMENT_V1), "template");
        assertThat(BnplAgreementTemplate.of("BNPL_PAY_IN_4_AGREEMENT", 1))
                .contains(BnplAgreementTemplate.BNPL_PAY_IN_4_AGREEMENT_V1);
        assertThat(BnplAgreementTemplate.of("BNPL_PAY_IN_4_AGREEMENT", 2)).isEmpty();
        for (BnplAgreementTemplate template : BnplAgreementTemplate.values()) {
            assertThat(template.name()).isEqualTo(template.id() + "_V" + template.version());
        }
    }

    @Test
    @DisplayName("an allocation order names every component exactly once")
    void anAllocationOrderNamesEveryComponentOnce() {
        refused(payInN("0.2").allocationOrder(List.of(AllocationComponent.FEES, AllocationComponent.PRINCIPAL)),
                "every component");
        refused(payInN("0.2").allocationOrder(List.of(AllocationComponent.FEES, AllocationComponent.FEES,
                AllocationComponent.PRINCIPAL)), "every component");
        assertThat(payInN("0.2").allocationOrder(List.of(AllocationComponent.PRINCIPAL, AllocationComponent.INTEREST,
                AllocationComponent.FEES)).build()).isNotNull();
    }

    @Test
    @DisplayName("every bound bnpl declares is held: the rate 0..1 at most 8 places, the late fee and its cap, grace, the"
            + " default and the buckets rising from 1 to it, the retry cadence, the 30-minute offer cap, the dispute window"
            + " and threshold, the payoff horizon")
    void everyBoundIsHeld() {
        Map<String, UnaryOperator<BnplTerms.BnplTermsBuilder>> defects = Map.ofEntries(
                Map.entry("rate", b -> b.nominalAnnualRate(new BigDecimal("1.0001"))),
                Map.entry("negative rate", b -> b.nominalAnnualRate(new BigDecimal("-0.01"))),
                Map.entry("decimal places", b -> b.nominalAnnualRate(new BigDecimal("0.123456789"))),
                Map.entry("late fee", b -> b.lateFee(euros(-1))),
                Map.entry("late fee cap", b -> b.lateFee(euros(5_00)).lateFeeCap(euros(4_99))),
                Map.entry("grace", b -> b.graceDays(31)),
                Map.entry("late fee day", b -> b.lateFeeDay(3)),
                Map.entry("late fee day past default", b -> b.lateFeeDay(91)),
                Map.entry("default threshold", b -> b.defaultThresholdDays(29).delinquencyBounds(List.of(1, 10, 20, 29))),
                Map.entry("buckets", b -> b.delinquencyBounds(List.of(1, 30, 90))),
                Map.entry("bucket order", b -> b.delinquencyBounds(List.of(1, 60, 30, 90))),
                Map.entry("bucket start", b -> b.delinquencyBounds(List.of(2, 30, 60, 90))),
                Map.entry("bucket end", b -> b.delinquencyBounds(List.of(1, 30, 60, 89))),
                Map.entry("retry", b -> b.collectionRetryDays(0)),
                Map.entry("retry long", b -> b.collectionRetryDays(8)),
                Map.entry("offer", b -> b.offerValidityMinutes(31)),
                Map.entry("offer none", b -> b.offerValidityMinutes(0)),
                Map.entry("dispute window", b -> b.disputeResponseDays(0)),
                Map.entry("dispute threshold", b -> b.disputeFourEyesThreshold(euros(-1))),
                Map.entry("payoff horizon", b -> b.payoffHorizonDays(91)));
        for (Map.Entry<String, UnaryOperator<BnplTerms.BnplTermsBuilder>> defect : defects.entrySet()) {
            assertThatThrownBy(() -> defect.getValue().apply(payInN("0.2")).build())
                    .as(defect.getKey())
                    .isInstanceOf(BnplTerms.TermsInvalid.class);
        }
        assertThat(payInN("1").build().nominalAnnualRate()).isEqualTo(BigDecimal.ONE);
        assertThat(payInN("0.2").lateFee(euros(0)).lateFeeCap(euros(0)).build()).as("no late fee at all is a valid choice")
                .isNotNull();
    }

    @Test
    @DisplayName("every amount is in the product's currency at its scale; nothing is missing")
    void amountsAreTheProductsAndNothingIsMissing() {
        Money pounds = Money.ofMinorUnits(500, CurrencyCode.of("GBP"));
        refused(payInN("0.2").lateFee(pounds).lateFeeCap(pounds), "currency");
        refused(payInN("0.2").disputeFourEyesThreshold(pounds), "currency");
        refused(payInN("0.2").agreementTemplate(null), "agreement template");
        refused(payInN("0.2").lateFee(null), "late fee");
    }

    @Test
    @DisplayName("pay-in-N's schedule terms are its declared conventions, exactly - the input SCHEDULE_ENGINE_V1 reads")
    void theScheduleTermsAreAsDeclared() {
        BnplTerms terms = payInN("0.1999").payInN(Optional.of(payInNPart(List.of(3, 6), 21))).build();
        assertThat(terms.scheduleTerms()).contains(new ScheduleTerms(CurrencyCode.of("EUR"), new BigDecimal("0.1999"),
                21, DayCountConvention.ACT_365F, RoundingMode.UP, RoundingMode.HALF_EVEN, EngineVersion.SCHEDULE_ENGINE_V1));
    }

    private static void refused(BnplTerms.BnplTermsBuilder builder, String detail) {
        assertThatThrownBy(builder::build)
                .isInstanceOf(BnplTerms.TermsInvalid.class)
                .hasMessageContaining(detail);
    }
}
