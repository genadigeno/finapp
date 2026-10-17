package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.bnpl.MerchantFeeReturn;
import com.finapp.bnpl.RefundAllocation;
import com.finapp.bnpl.RefundAllocationEngineV1;
import com.finapp.bnpl.RefundAllocationOutcome;
import com.finapp.bnpl.RefundPosition;
import com.finapp.bnpl.testing.ReferenceMerchantFeeReturn;
import com.finapp.merchant.FeeAssessment;
import com.finapp.merchant.FeeCalculation;
import com.finapp.merchant.FeeRate;
import com.finapp.merchant.FeeScheduleId;
import com.finapp.merchant.FeeScheduleVersion;
import com.finapp.merchant.FeeScheduleVersionId;
import com.finapp.merchant.RefundFeePolicy;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One fee arithmetic (`P12-TSK-009`; ADR-0105 decision 3, {@code PHASE_12_PLAN.md} section 12.5; {@code INV-MER-04},
 * {@code INV-BNPL-06}): the reference bnpl's refund allocator is proven against in its hermetic suites
 * ({@link ReferenceMerchantFeeReturn}, bnpl's test fixtures) equals merchant's {@link FeeCalculation#returnedFee} - the
 * production arithmetic, which bnpl cannot see - for every generated case, so the engine's properties are properties of
 * the arithmetic production runs. {@code app} is where both are visible.
 *
 * <p>The fee is merchant's own: each case prices its gross by a {@link FeeScheduleVersion} (a rate and a fixed part, a
 * rounding policy, {@code RETURNED}) through {@link FeeCalculation#assess}, and the reference is built from the
 * assessed fee - never from a fee this test computed.
 */
@DisplayName("BNPL's reference fee arithmetic agrees with merchant's FeeCalculation (P12-TSK-009)")
class BnplFeeArithmeticAgreesWithMerchantTest {

    private static final List<CurrencyCode> CURRENCIES =
            List.of(CurrencyCode.of("EUR"), CurrencyCode.of("JPY"), CurrencyCode.of("BHD"));
    private static final Instant PINNED = Instant.parse("2026-10-17T00:00:00Z");
    private static final IdGenerator IDS =
            new IdGenerator(Clock.fixed(PINNED, java.time.ZoneOffset.UTC), new SecureRandom());

    @Test
    @DisplayName("over 20,000 generated purchases and their refund sequences - three currencies, every rounding policy,"
            + " rates and fixed parts - the reference returns exactly what FeeCalculation.returnedFee returns, at every step")
    void theReferenceEqualsMerchantsArithmetic() {
        SplittableRandom random = new SplittableRandom(0xfee_ca1c_2026_1017L);
        int steps = 0;
        for (int index = 0; index < 20_000; index++) {
            Priced priced = Priced.draw(random);
            long gross = priced.assessment.gross().minorUnits();
            long refunded = 0;
            while (refunded < gross) {
                long amount = random.nextInt(3) == 0 ? gross - refunded : random.nextLong(1, gross - refunded + 1);
                Money before = Money.ofMinorUnits(refunded, priced.currency);
                Money refund = Money.ofMinorUnits(amount, priced.currency);
                assertThat(priced.reference.returnedFee(before, refund))
                        .as("%s: before %d, refund %d", priced, refunded, amount)
                        .isEqualTo(FeeCalculation.returnedFee(priced.assessment, priced.version, before, refund));
                refunded += amount;
                steps++;
            }
        }
        assertThat(steps).as("refund steps compared").isGreaterThan(40_000);
    }

    @Test
    @DisplayName("where one exact rounding would differ: G = 200,000,000.01, f = 100,000,000.01, a refund of 0.01 - f · x /"
            + " G is 0.005000000000025, merchant's twelve-place guard makes it a tie that HALF_EVEN rounds to 0.00, and"
            + " the reference agrees (an exact rational rounded once would answer 0.01)")
    void theTwelvePlaceGuardIsMirrored() {
        CurrencyCode eur = CurrencyCode.of("EUR");
        FeeScheduleVersion version = version(eur, "0", 10_000_000_001L, RoundingPolicy.HALF_EVEN);
        FeeAssessment assessment = FeeCalculation.assess(Money.ofMinorUnits(20_000_000_001L, eur), version);
        assertThat(assessment.fee()).isEqualTo(Money.ofMinorUnits(10_000_000_001L, eur));
        Money zero = Money.zero(eur);
        Money cent = Money.ofMinorUnits(1, eur);

        Money merchants = FeeCalculation.returnedFee(assessment, version, zero, cent);
        Money reference = ReferenceMerchantFeeReturn.halfEven(assessment.gross(), assessment.fee())
                .returnedFee(zero, cent);

        assertThat(merchants).as("merchant's answer").isEqualTo(zero);
        assertThat(reference).as("the reference's").isEqualTo(merchants);
        BigDecimal exact = assessment.fee().toBigDecimal().multiply(cent.toBigDecimal())
                .divide(assessment.gross().toBigDecimal(), 2, java.math.RoundingMode.HALF_EVEN);
        assertThat(exact).as("the single exact rounding a naive reference would use").isEqualByComparingTo("0.01");
    }

    @Test
    @DisplayName("the engine bound to merchant's arithmetic directly - (before, refunded) -> FeeCalculation.returnedFee -"
            + " splits every generated refund exactly as bound to the reference: the parameter's shape is the merchant"
            + " method's, no adapter arithmetic between them")
    void theEngineBoundToMerchantSplitsAsBoundToTheReference() {
        SplittableRandom random = new SplittableRandom(0xb0_0d_2026_1017L);
        int splits = 0;
        for (int index = 0; index < 2_000; index++) {
            Priced priced = Priced.draw(random);
            MerchantFeeReturn merchant = (before, refunded) ->
                    FeeCalculation.returnedFee(priced.assessment, priced.version, before, refunded);
            CurrencyCode currency = priced.currency;
            Money gross = priced.assessment.gross();
            List<RefundPosition.Instalment> paid = List.of(
                    new RefundPosition.Instalment(1, gross, true, gross, Money.zero(currency)));
            Money refunded = Money.zero(currency);
            while (refunded.compareTo(gross) < 0) {
                long left = gross.minus(refunded).minorUnits();
                Money amount = Money.ofMinorUnits(random.nextInt(3) == 0 ? left : random.nextLong(1, left + 1), currency);
                RefundPosition position = new RefundPosition(gross, priced.assessment.fee(), refunded, paid);
                RefundAllocation byMerchant = allocated(RefundAllocationEngineV1.allocate(position, amount, merchant));
                RefundAllocation byReference =
                        allocated(RefundAllocationEngineV1.allocate(position, amount, priced.reference));
                assertThat(byMerchant).as("%s", priced).isEqualTo(byReference);
                refunded = refunded.plus(amount);
                splits++;
            }
        }
        assertThat(splits).isGreaterThan(4_000);
    }

    // ------------------------------------------------------------------ the population

    /** A gross priced by a merchant fee schedule version, and the reference built from what it assessed. */
    private record Priced(CurrencyCode currency, FeeScheduleVersion version, FeeAssessment assessment,
            ReferenceMerchantFeeReturn reference) {

        static Priced draw(SplittableRandom random) {
            CurrencyCode currency = CURRENCIES.get(random.nextInt(CURRENCIES.size()));
            long unit = currency.minorUnits() == 0 ? 1 : currency.minorUnits() == 2 ? 100 : 1_000;
            RoundingPolicy policy = RoundingPolicy.values()[random.nextInt(RoundingPolicy.values().length)];
            while (true) {
                long grossMinor = switch (random.nextInt(3)) {
                    case 0 -> random.nextLong(1, 200 + 1);
                    case 1 -> random.nextLong(50 * unit, 5_000 * unit + 1);
                    default -> random.nextLong(1, 1_000_000 * unit + 1);
                };
                String rate = BigDecimal.valueOf(random.nextLong(0, 1_000 + 1), 4).toPlainString();
                long fixed = random.nextInt(4) == 0 ? 0 : random.nextLong(0, 3 * unit + 1);
                FeeScheduleVersion version = BnplFeeArithmeticAgreesWithMerchantTest.version(currency, rate, fixed, policy);
                FeeAssessment assessment = FeeCalculation.assess(Money.ofMinorUnits(grossMinor, currency), version);
                if (assessment.fee().compareTo(assessment.gross()) < 0) {
                    return new Priced(currency, version, assessment,
                            new ReferenceMerchantFeeReturn(assessment.gross(), assessment.fee(), policy));
                }
                // A sale below its fee is never financed (ADR-0058) - draw again.
            }
        }

        @Override
        public String toString() {
            return currency.code() + " G=" + assessment.gross().minorUnits() + " f=" + assessment.fee().minorUnits()
                    + " " + version.roundingPolicy();
        }
    }

    private static FeeScheduleVersion version(CurrencyCode currency, String rate, long fixedMinor, RoundingPolicy policy) {
        return FeeScheduleVersion.rehydrate(FeeScheduleVersionId.of(IDS.next()), FeeScheduleId.of(IDS.next()),
                1, FeeRate.of(new BigDecimal(rate)), Money.ofMinorUnits(fixedMinor, currency), policy,
                RefundFeePolicy.RETURNED, PINNED, PINNED, "test");
    }

    private static RefundAllocation allocated(RefundAllocationOutcome outcome) {
        assertThat(outcome).isInstanceOf(RefundAllocationOutcome.Allocated.class);
        return ((RefundAllocationOutcome.Allocated) outcome).allocation();
    }
}
