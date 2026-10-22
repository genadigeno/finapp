package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.bnpl.BnplAgreementTemplate;
import com.finapp.bnpl.BnplEngine;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplScheduleEngineV1;
import com.finapp.bnpl.BnplScheduleOutcome;
import com.finapp.bnpl.BnplTerms;
import com.finapp.bnpl.InstalmentFrequency;
import com.finapp.credit.AffordabilityAssessment;
import com.finapp.credit.AttributeProvenance;
import com.finapp.credit.AttributeValue;
import com.finapp.credit.CreditAttribute;
import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.CreditSourceKind;
import com.finapp.credit.PinnedVersions;
import com.finapp.credit.SnapshotContent;
import com.finapp.creditmath.AllocationComponent;
import com.finapp.creditmath.AllocationSequence;
import com.finapp.creditmath.EngineVersion;
import com.finapp.creditmath.OverpaymentTreatment;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One pay-in-4 split (the Phase 12 &rarr; 13 transition gate, A4-F6; A4, OQ-3; {@code INV-BNPL-05}): credit's
 * affordability assessment prices a pay-in-4 request by its largest instalment ({@code AffordabilityAssessment
 * .largestInstalment}, {@code P - 3 x floor(P / 4)}), and bnpl's {@link BnplScheduleEngineV1} schedules the purchase's
 * instalment 1 as {@code P - 3A}, {@code A = floor(P / 4)} - the same arithmetic written twice, in two modules that cannot
 * see each other. {@code app} sees both, so it holds them equal: over every amount in pay-in-4's purchase bounds
 * (credit's {@link CreditProduct#BNPL_PAY_IN_4}, EUR 50.00 to 1,000.00, each of the 95,001 exactly), over the engine's own
 * lower bound and amounts far beyond the product's, every residue modulo four - so a credit decision never judges an
 * instalment the schedule does not charge.
 *
 * <p>Credit's figure is read through its public door, {@link AffordabilityAssessment#assess}, under a plan stress factor
 * of exactly one: the assessment's repayment is then {@code largestInstalment x 1}, rounded once half up from an exact
 * minor-unit figure - the largest instalment itself. That is the production path pay-in-4's affordability takes, not a
 * reflective call into a package-private helper. The {@code BnplFeeArithmeticAgreesWithMerchantTest} precedent: an
 * arithmetic duplicated across a module boundary is proven equal where both are visible.
 */
@DisplayName("credit's pay-in-4 largest instalment is the schedule engine's instalment 1 (the 12 -> 13 gate, A4-F6)")
class BnplPayIn4SplitAgreesWithCreditTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final LocalDate PURCHASE = LocalDate.of(2026, 10, 22);
    private static final AffordabilityAssessment.Parameters ONE_INSTALMENT = new AffordabilityAssessment.Parameters(
            BigDecimal.ZERO, new BigDecimal("0.03"), Money.zero(EUR), Optional.of(BigDecimal.ONE));

    @Test
    @DisplayName("the plan's shape: credit's pay-in-4 is a fixed plan of the engine's four instalments, in the terms'"
            + " currency")
    void thePlansShapeIsTheEngines() {
        assertThat(CreditProduct.BNPL_PAY_IN_4.planInstalments()).hasValue(BnplScheduleEngineV1.INSTALMENTS);
        assertThat(CreditProduct.BNPL_PAY_IN_4.currency()).isEqualTo(payIn4().currency());
    }

    @Test
    @DisplayName("every amount in pay-in-4's bounds, EUR 50.00 to 1,000.00 - 95,001 of them, each residue modulo four"
            + " - credit's largest instalment equals the schedule's instalment 1, and instalment 1 is the largest")
    void everyAmountInTheProductsBoundsAgrees() {
        long minimum = CreditProduct.BNPL_PAY_IN_4.minimumAmount().minorUnits();
        long maximum = CreditProduct.BNPL_PAY_IN_4.maximumAmount().minorUnits();
        assertThat(minimum).as("credit's lower bound").isEqualTo(50_00);
        assertThat(maximum).as("credit's upper bound").isEqualTo(1_000_00);
        int[] residues = new int[4];
        for (long minor = minimum; minor <= maximum; minor++) {
            agrees(minor);
            residues[(int) (minor % 4)]++;
        }
        assertThat(java.util.Arrays.stream(residues).boxed().toList())
                .as("every residue met, the non-divisible amounts among them").allMatch(seen -> seen > 0);
    }

    @Test
    @DisplayName("beyond the product's bounds: the engine's own floor of four minor units up to 1,000, and 20,000 amounts"
            + " drawn to EUR 10,000,000,000.00 - the split agrees wherever both answer")
    void theSplitAgreesBeyondTheBounds() {
        for (long minor = BnplScheduleEngineV1.INSTALMENTS; minor <= 1_000; minor++) {
            agrees(minor);
        }
        SplittableRandom random = new SplittableRandom(0x5011_7_2026_1022L);
        for (int draw = 0; draw < 20_000; draw++) {
            agrees(random.nextLong(BnplScheduleEngineV1.INSTALMENTS, 1_000_000_000_000L));
        }
        for (long minor : new long[] {50_00, 50_01, 50_02, 50_03, 999_97, 999_98, 999_99, 1_000_00, 100_03, 999_99}) {
            agrees(minor);
        }
    }

    /** Credit's largest instalment of {@code minor} equals the schedule's instalment 1, the largest of the four. */
    private static void agrees(long minor) {
        Money principal = Money.ofMinorUnits(minor, EUR);
        BnplScheduleOutcome outcome = BnplScheduleEngineV1.project(payIn4(), principal, PURCHASE);
        assertThat(outcome).as("%d schedules", minor).isInstanceOf(BnplScheduleOutcome.Projected.class);
        List<Money> amounts = ((BnplScheduleOutcome.Projected) outcome).schedule().instalments().stream()
                .map(instalment -> instalment.principal()).toList();
        Money first = amounts.get(0);
        assertThat(credits(minor)).as("EUR %d minor units: credit's largest instalment, the schedule's first", minor)
                .isEqualTo(first);
        assertThat(amounts).as("%d: instalment 1 is the largest", minor).allMatch(amount -> amount.compareTo(first) <= 0);
    }

    /** Credit's repayment for a pay-in-4 request of {@code minor} under a stress factor of one - its largest instalment. */
    private static Money credits(long minor) {
        AffordabilityAssessment.Assessment assessment = AffordabilityAssessment.assess(snapshot(minor), ONE_INSTALMENT);
        assertThat(assessment).isInstanceOf(AffordabilityAssessment.Assessment.Assessed.class);
        return ((AffordabilityAssessment.Assessment.Assessed) assessment).repayment();
    }

    /** A pay-in-4 request of {@code minor}, carrying the five money attributes the assessment reads - nothing else. */
    private static SnapshotContent snapshot(long minor) {
        AttributeProvenance declared = new AttributeProvenance.Declared();
        AttributeProvenance notRead = new AttributeProvenance.NotRead(CreditSourceKind.FINANCIAL_DATA);
        AttributeProvenance port = new AttributeProvenance.Port("agreement-test", 1);
        List<CreditAttribute> attributes = List.of(
                new CreditAttribute(CreditAttributeCode.DECLARED_MONTHLY_INCOME,
                        new AttributeValue.MoneyValue(Money.ofMinorUnits(3_200_00, EUR)), declared),
                new CreditAttribute(CreditAttributeCode.DECLARED_MONTHLY_EXPENDITURE,
                        new AttributeValue.MoneyValue(Money.ofMinorUnits(1_400_00, EUR)), declared),
                new CreditAttribute(CreditAttributeCode.FINDATA_MONTHLY_INCOME, new AttributeValue.Absent(), notRead),
                new CreditAttribute(CreditAttributeCode.FINDATA_MONTHLY_COMMITTED_EXPENDITURE,
                        new AttributeValue.Absent(), notRead),
                new CreditAttribute(CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS,
                        new AttributeValue.MoneyValue(Money.ofMinorUnits(350_00, EUR)), port));
        return new SnapshotContent(UUID.fromString("0190a1b2-0000-7000-8000-0000000000a6"),
                UUID.fromString("0190a1b2-0000-7000-8000-0000000000f6"), CreditProduct.BNPL_PAY_IN_4,
                Money.ofMinorUnits(minor, EUR), Optional.empty(),
                new PinnedVersions(UUID.fromString("0190a1b2-0000-7000-8000-0000000000c6"),
                        UUID.fromString("0190a1b2-0000-7000-8000-0000000000d6"), AffordabilityAssessment.ENGINE_VERSION),
                attributes);
    }

    /** Pay-in-4's terms as bnpl's fixtures and the plan state them (A4-A7): four instalments every 14 days, no interest. */
    private static BnplTerms payIn4() {
        return BnplTerms.builder()
                .product(BnplProduct.BNPL_PAY_IN_4)
                .frequency(InstalmentFrequency.BIWEEKLY)
                .nominalAnnualRate(BigDecimal.ZERO)
                .instalmentRounding(RoundingMode.DOWN)
                .payIn4(Optional.of(new BnplTerms.PayIn4Terms(BnplScheduleEngineV1.INSTALMENTS,
                        BnplScheduleEngineV1.INTERVAL_DAYS, BnplEngine.BNPL_SCHEDULE_ENGINE_V1)))
                .payInN(Optional.empty())
                .agreementTemplate(BnplAgreementTemplate.BNPL_PAY_IN_4_AGREEMENT_V1)
                .lateFee(Money.ofMinorUnits(0, EUR))
                .lateFeeDay(5)
                .lateFeeCap(Money.ofMinorUnits(15_00, EUR))
                .graceDays(3)
                .delinquencyBounds(List.of(1, 30, 60, 90))
                .defaultThresholdDays(90)
                .allocationSequence(AllocationSequence.OLDEST_DUE_FIRST)
                .allocationOrder(List.of(AllocationComponent.FEES, AllocationComponent.INTEREST,
                        AllocationComponent.PRINCIPAL))
                .overpaymentTreatment(OverpaymentTreatment.HOLD_AS_CREDIT)
                .autoCollection(false)
                .partialCollection(true)
                .collectionRetryDays(1)
                .servicingZone("UTC")
                .offerValidityMinutes(30)
                .disputeResponseDays(14)
                .disputeFourEyesThreshold(Money.ofMinorUnits(250_00, EUR))
                .payoffHorizonDays(30)
                .allocationEngine(EngineVersion.ALLOCATION_ENGINE_V1)
                .refundAllocationEngine(BnplEngine.REFUND_ALLOCATION_ENGINE_V1)
                .build();
    }
}
