package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The returned-fee arithmetic (`P6-TSK-014`, {@code INV-MER-04} across the refund boundary) —
 * pure, so provable by property rather than by the examples somebody happened to think of.
 *
 * <p>The whole design question here is that a proportional share of a fee, rounded once per
 * refund, does not add up. Differencing a cumulative allocation does. These tests are about
 * that difference and almost nothing else.
 */
@DisplayName("the returned fee (P6-TSK-014)")
class ReturnedFeeTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode JPY = CurrencyCode.of("JPY");
    private static final CurrencyCode BHD = CurrencyCode.of("BHD");
    private static final IdGenerator IDS =
            new IdGenerator(java.time.Clock.systemUTC(), new SecureRandom());

    @Test
    @DisplayName("a FULL refund returns EXACTLY the assessed fee - not approximately, and"
            + " without a clamp anywhere")
    void aFullRefundReturnsTheWholeFee() {
        for (long grossMinor : new long[] {1L, 7L, 100_00L, 33_33L, 999_99L}) {
            FeeScheduleVersion version = version("0.029", 31L, RoundingPolicy.HALF_UP, EUR);
            Money gross = Money.ofMinorUnits(grossMinor, EUR);
            FeeAssessment assessed = FeeCalculation.assess(gross, version);

            Money returned =
                    FeeCalculation.returnedFee(assessed, version, zero(EUR), gross);

            assertThat(returned)
                    .as("cum(gross) is round(fee x 1), which IS the fee - an identity")
                    .isEqualTo(assessed.fee());
        }
    }

    @Test
    @DisplayName("THE CASE THE NAIVE FORMULA GETS WRONG, and it is not subtle: a one-cent fee"
            + " refunded in two halves returns TWO cents naively and ONE here")
    void aOneCentFeeSplitInHalfDoesNotDouble() {
        // The sharpest instance of the defect, chosen because it is the loudest: a fee of ONE
        // minor unit, refunded in two equal halves. Each half's naive share is exactly half a
        // cent, HALF_UP rounds BOTH to a whole one, and the platform pays out twice what it
        // ever charged. At volume that is not a rounding difference; it is a second fee.
        FeeScheduleVersion version = version("0", 1L, RoundingPolicy.HALF_UP, EUR);
        Money gross = Money.ofMinorUnits(100_00L, EUR);
        FeeAssessment assessed = FeeCalculation.assess(gross, version);
        assertThat(assessed.fee().minorUnits()).as("one cent").isEqualTo(1L);

        Money half = Money.ofMinorUnits(50_00L, EUR);
        Money first = FeeCalculation.returnedFee(assessed, version, zero(EUR), half);
        Money second = FeeCalculation.returnedFee(assessed, version, half, half);

        // What the naive per-refund formula would have returned, stated so this test says
        // what it prevents rather than merely asserting the right answer.
        BigDecimal naiveShare =
                assessed.fee().toBigDecimal()
                        .multiply(half.toBigDecimal())
                        .divide(gross.toBigDecimal(), 2, java.math.RoundingMode.HALF_UP);
        assertThat(naiveShare.add(naiveShare))
                .as("the naive answer: DOUBLE the assessed fee")
                .isEqualByComparingTo(new BigDecimal("0.02"));

        assertThat(first.plus(second))
                .as("and the cumulative allocation's: the one cent that was charged")
                .isEqualTo(assessed.fee());
        assertThat(second.isZero())
                .as("the first half took the whole indivisible cent; the second takes none")
                .isTrue();
    }

    @Test
    @DisplayName("ANY sequence of partial refunds summing to the capture returns exactly the"
            + " assessed fee - swept over amounts, rates, policies and 0/2/3-minor currencies")
    void everySequenceTelescopes() {
        Random seeded = new Random(20260922L);
        List<CurrencyCode> currencies = List.of(JPY, EUR, BHD);
        List<RoundingPolicy> policies =
                List.of(
                        RoundingPolicy.HALF_UP,
                        RoundingPolicy.HALF_EVEN,
                        RoundingPolicy.TOWARDS_ZERO,
                        RoundingPolicy.FLOOR,
                        RoundingPolicy.CEILING,
                        RoundingPolicy.AWAY_FROM_ZERO);

        int cases = 0;
        for (CurrencyCode currency : currencies) {
            for (RoundingPolicy policy : policies) {
                for (String rate : new String[] {"0", "0.0035", "0.029", "0.175", "0.9999"}) {
                    for (int run = 0; run < 20; run++) {
                        long grossMinor = 3L + seeded.nextInt(500_000);
                        FeeScheduleVersion version =
                                version(rate, seeded.nextInt(200), policy, currency);
                        Money gross = Money.ofMinorUnits(grossMinor, currency);
                        FeeAssessment assessed = FeeCalculation.assess(gross, version);

                        // A random partition of the capture into two or three refunds.
                        long firstMinor = 1L + (long) seeded.nextInt((int) (grossMinor - 2));
                        long secondMinor =
                                1L
                                        + (long)
                                                seeded.nextInt(
                                                        (int) (grossMinor - firstMinor));
                        long thirdMinor = grossMinor - firstMinor - secondMinor;

                        Money before = zero(currency);
                        Money total = zero(currency);
                        for (long part : new long[] {firstMinor, secondMinor, thirdMinor}) {
                            if (part == 0L) {
                                continue;
                            }
                            Money refunded = Money.ofMinorUnits(part, currency);
                            Money returned =
                                    FeeCalculation.returnedFee(
                                            assessed, version, before, refunded);
                            assertThat(returned.isNegative())
                                    .as("a refund never takes fee BACK from the merchant")
                                    .isFalse();
                            total = total.plus(returned);
                            before = before.plus(refunded);
                        }

                        assertThat(total)
                                .as(
                                        "gross=%s rate=%s fixed=%s policy=%s parts=%s/%s/%s",
                                        grossMinor, rate, version.fixed(), policy,
                                        firstMinor, secondMinor, thirdMinor)
                                .isEqualTo(assessed.fee());
                        cases++;
                    }
                }
            }
        }
        assertThat(cases).as("the sweep actually ran").isEqualTo(3 * 6 * 5 * 20);
    }

    @Test
    @DisplayName("a PARTIAL refund never returns more than the cumulative share, and the"
            + " remainder stays with the platform until the rest is refunded")
    void aPartialRefundReturnsOnlyItsShare() {
        FeeScheduleVersion version = version("0.10", 0L, RoundingPolicy.HALF_UP, EUR);
        Money gross = Money.ofMinorUnits(100_00L, EUR);
        FeeAssessment assessed = FeeCalculation.assess(gross, version);
        assertThat(assessed.fee().minorUnits()).isEqualTo(10_00L);

        Money quarter = Money.ofMinorUnits(25_00L, EUR);
        assertThat(FeeCalculation.returnedFee(assessed, version, zero(EUR), quarter))
                .as("a quarter of the capture returns a quarter of the fee")
                .isEqualTo(Money.ofMinorUnits(2_50L, EUR));
        assertThat(FeeCalculation.returnedFee(assessed, version, quarter, quarter))
                .as("and the second quarter returns the same, because the shares difference")
                .isEqualTo(Money.ofMinorUnits(2_50L, EUR));
    }

    @Test
    @DisplayName("refunds exceeding the capture are REFUSED - the arithmetic checks the"
            + " assumption the payments module's budget bound already enforces")
    void refundsCannotExceedTheCapture() {
        FeeScheduleVersion version = version("0.029", 30L, RoundingPolicy.HALF_EVEN, EUR);
        Money gross = Money.ofMinorUnits(100_00L, EUR);
        FeeAssessment assessed = FeeCalculation.assess(gross, version);

        assertThatThrownBy(
                        () ->
                                FeeCalculation.returnedFee(
                                        assessed,
                                        version,
                                        Money.ofMinorUnits(60_00L, EUR),
                                        Money.ofMinorUnits(60_00L, EUR)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceed");
    }

    @Test
    @DisplayName("INV-MER-03: an assessment from a DIFFERENT version is refused - a refund is"
            + " priced by what the payment was priced by, and this checks it rather than"
            + " trusting the caller to have looked it up correctly")
    void anAssessmentFromAnotherVersionIsRefused() {
        FeeScheduleVersion pinned = version("0.029", 30L, RoundingPolicy.HALF_EVEN, EUR);
        FeeScheduleVersion today = version("0.10", 500L, RoundingPolicy.HALF_EVEN, EUR);
        Money gross = Money.ofMinorUnits(100_00L, EUR);
        FeeAssessment assessedUnderPin = FeeCalculation.assess(gross, pinned);

        assertThatThrownBy(
                        () ->
                                FeeCalculation.returnedFee(
                                        assessedUnderPin, today, zero(EUR), gross))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INV-MER-03");
    }

    @Test
    @DisplayName("a FREE schedule returns nothing, and says so without a zero line")
    void aFreeScheduleReturnsNothing() {
        FeeScheduleVersion free = version("0", 0L, RoundingPolicy.HALF_EVEN, EUR);
        Money gross = Money.ofMinorUnits(100_00L, EUR);
        FeeAssessment assessed = FeeCalculation.assess(gross, free);

        assertThat(FeeCalculation.returnedFee(assessed, free, zero(EUR), gross).isZero())
                .isTrue();
    }

    // -----------------------------------------------------------------

    private static Money zero(CurrencyCode currency) {
        return Money.ofMinorUnits(0L, currency);
    }

    private static FeeScheduleVersion version(
            String rate, long fixedMinor, RoundingPolicy policy, CurrencyCode currency) {
        Instant now = Instant.parse("2026-09-22T00:00:00Z");
        return FeeScheduleVersion.rehydrate(
                FeeScheduleVersionId.of(IDS.next()),
                FeeScheduleId.of(IDS.next()),
                1,
                FeeRate.of(new BigDecimal(rate)),
                Money.ofMinorUnits(fixedMinor, currency),
                policy,
                RefundFeePolicy.RETURNED,
                now,
                now,
                "test");
    }
}
