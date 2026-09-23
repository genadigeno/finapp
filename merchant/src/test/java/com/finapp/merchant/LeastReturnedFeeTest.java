package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The least returned fee (`P6-TSK-015`, ADR-0054) — what a refund's dispatch may count on
 * coming back, when the share it will actually return depends on which sibling refunds complete
 * before it.
 *
 * <p>The claim is a FLOOR over every completion order still possible, so the sweep below does
 * not sample orders: for small captures it enumerates every {@code before} the completion could
 * see and compares against {@link FeeCalculation#returnedFee} at each one. A reservation built
 * on a number above any of them would take a minor unit more than it held.
 */
@DisplayName("the least returned fee (P6-TSK-015)")
class LeastReturnedFeeTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode JPY = CurrencyCode.of("JPY");
    private static final CurrencyCode BHD = CurrencyCode.of("BHD");
    private static final IdGenerator IDS =
            new IdGenerator(java.time.Clock.systemUTC(), new SecureRandom());
    private static final List<RoundingPolicy> POLICIES =
            List.of(
                    RoundingPolicy.HALF_UP,
                    RoundingPolicy.HALF_EVEN,
                    RoundingPolicy.TOWARDS_ZERO,
                    RoundingPolicy.FLOOR,
                    RoundingPolicy.CEILING,
                    RoundingPolicy.AWAY_FROM_ZERO);

    @Test
    @DisplayName("NEVER ABOVE the share ANY completion order returns - every before the"
            + " completion could see, enumerated, over amounts, rates, policies and 0/2/3-minor"
            + " currencies - and never more than one minor unit below the least of them")
    void neverAboveWhatAnyOrderReturns() {
        Random seeded = new Random(20260923L);
        int cases = 0;
        int exactAtThePoint = 0;
        for (CurrencyCode currency : List.of(JPY, EUR, BHD)) {
            for (RoundingPolicy policy : POLICIES) {
                for (String rate : new String[] {"0", "0.0035", "0.029", "0.175", "0.9999"}) {
                    for (int run = 0; run < 40; run++) {
                        // Small captures, so that every completion order can be enumerated.
                        long grossMinor = 2L + seeded.nextInt(600);
                        FeeScheduleVersion version =
                                version(rate, seeded.nextInt(200), policy, currency);
                        FeeAssessment assessed =
                                FeeCalculation.assess(
                                        Money.ofMinorUnits(grossMinor, currency), version);
                        long beforeMinor = seeded.nextInt((int) grossMinor);
                        long refundedMinor =
                                1L + seeded.nextInt((int) (grossMinor - beforeMinor));
                        Money refunded = Money.ofMinorUnits(refundedMinor, currency);

                        Money least =
                                FeeCalculation.leastReturnedFee(
                                        assessed,
                                        version,
                                        Money.ofMinorUnits(beforeMinor, currency),
                                        refunded);

                        // Every before the completion could see: from what had completed at
                        // dispatch up to the capture's remainder.
                        Money lowest = null;
                        for (long seen = beforeMinor;
                                seen <= grossMinor - refundedMinor;
                                seen++) {
                            Money returned =
                                    FeeCalculation.returnedFee(
                                            assessed,
                                            version,
                                            Money.ofMinorUnits(seen, currency),
                                            refunded);
                            assertThat(least)
                                    .as(
                                            "gross=%s fee=%s policy=%s before=%s refunded=%s:"
                                                    + " completing after %s returns only %s",
                                            grossMinor, assessed.fee(), policy, beforeMinor,
                                            refundedMinor, seen, returned)
                                    .isLessThanOrEqualTo(returned);
                            lowest =
                                    lowest == null || returned.compareTo(lowest) < 0
                                            ? returned
                                            : lowest;
                        }
                        // Conservative by at most one minor unit - two under HALF_EVEN, whose
                        // odd-share exception gives one up in advance for a tie that may never
                        // be reached.
                        long allowance = policy == RoundingPolicy.HALF_EVEN ? 2L : 1L;
                        assertThat(lowest.minus(least).minorUnits())
                                .as(
                                        "gross=%s fee=%s policy=%s before=%s refunded=%s:"
                                                + " never more than %s below the least any"
                                                + " order returns",
                                        grossMinor, assessed.fee(), policy, beforeMinor,
                                        refundedMinor, allowance)
                                .isBetween(0L, allowance);
                        boolean wholeShare =
                                (assessed.fee().minorUnits() * refundedMinor) % grossMinor == 0;
                        if (wholeShare && policy != RoundingPolicy.HALF_EVEN) {
                            assertThat(least)
                                    .as("a whole share under a policy that commutes with the"
                                            + " shift is EXACT, whatever the order")
                                    .isEqualTo(lowest);
                        }
                        if (beforeMinor + refundedMinor == grossMinor) {
                            assertThat(least)
                                    .as("a refund completing the remainder is EXACT")
                                    .isEqualTo(lowest);
                            exactAtThePoint++;
                        }
                        cases++;
                    }
                }
            }
        }
        assertThat(cases).as("the sweep actually ran").isEqualTo(3 * 6 * 5 * 40);
        assertThat(exactAtThePoint).as("and the point case was actually reached").isPositive();
    }

    @Test
    @DisplayName("EVERY small capture, EVERY fee, EVERY refund and EVERY order - exhaustive, so the"
            + " exact ties that random sampling almost never lands on are all reached")
    void everyOrderOfEverySmallCaptureIsCovered() {
        // ADDED BY THE COMPLETION GATE. The random sweep above PASSED with HALF_EVEN's odd-share
        // exception removed: a tie needs fee x before / gross to land exactly on a half, which a
        // random draw almost never does, so the sweep was blind to the one rule that exists for
        // ties. Enumerating small numbers reaches every tie there is.
        int cases = 0;
        for (RoundingPolicy policy : POLICIES) {
            for (long grossMinor = 1; grossMinor <= 24; grossMinor++) {
                Money gross = Money.ofMinorUnits(grossMinor, EUR);
                for (long feeMinor = 0; feeMinor <= 30; feeMinor++) {
                    FeeScheduleVersion version = version("0", feeMinor, policy, EUR);
                    FeeAssessment assessed = FeeCalculation.assess(gross, version);
                    for (long before = 0; before < grossMinor; before++) {
                        for (long refunded = 1; before + refunded <= grossMinor; refunded++) {
                            Money refund = Money.ofMinorUnits(refunded, EUR);
                            Money least =
                                    FeeCalculation.leastReturnedFee(
                                            assessed,
                                            version,
                                            Money.ofMinorUnits(before, EUR),
                                            refund);
                            for (long seen = before; seen <= grossMinor - refunded; seen++) {
                                Money returned =
                                        FeeCalculation.returnedFee(
                                                assessed,
                                                version,
                                                Money.ofMinorUnits(seen, EUR),
                                                refund);
                                assertThat(least)
                                        .as(
                                                "gross=%s fee=%s policy=%s before=%s"
                                                        + " refunded=%s: completing after %s"
                                                        + " returns only %s",
                                                grossMinor, feeMinor, policy, before,
                                                refunded, seen, returned)
                                        .isLessThanOrEqualTo(returned);
                            }
                            cases++;
                        }
                    }
                }
            }
        }
        assertThat(cases).as("the enumeration actually ran").isEqualTo(6 * 31 * 2600);
    }

    @Test
    @DisplayName("a FULL refund counts on the WHOLE assessed fee - exact, under every policy,"
            + " which is what lets a RETURNED full refund reserve exactly the net")
    void aFullRefundCountsOnTheWholeFee() {
        for (RoundingPolicy policy : POLICIES) {
            for (long grossMinor : new long[] {1L, 7L, 100_00L, 33_33L, 999_99L}) {
                FeeScheduleVersion version = version("0.029", 30L, policy, EUR);
                Money gross = Money.ofMinorUnits(grossMinor, EUR);
                FeeAssessment assessed = FeeCalculation.assess(gross, version);

                assertThat(FeeCalculation.leastReturnedFee(assessed, version, zero(EUR), gross))
                        .as("gross=%s policy=%s", grossMinor, policy)
                        .isEqualTo(assessed.fee());
            }
        }
    }

    @Test
    @DisplayName("THE CASE A DISPATCH-TIME SHARE GETS WRONG: a one-cent fee refunded in halves"
            + " returns the cent to whichever half completes FIRST, so neither half may count"
            + " on it")
    void aOneCentFeeCannotBeCountedOnByEitherHalf() {
        FeeScheduleVersion version = version("0", 1L, RoundingPolicy.HALF_UP, EUR);
        Money gross = Money.ofMinorUnits(100_00L, EUR);
        FeeAssessment assessed = FeeCalculation.assess(gross, version);
        Money half = Money.ofMinorUnits(50_00L, EUR);

        // What pricing the share at DISPATCH would say - the probe this test exists for.
        assertThat(FeeCalculation.returnedFee(assessed, version, zero(EUR), half))
                .as("completing first, the half returns the whole cent")
                .isEqualTo(Money.ofMinorUnits(1L, EUR));
        assertThat(FeeCalculation.returnedFee(assessed, version, half, half).isZero())
                .as("completing second, it returns nothing")
                .isTrue();

        assertThat(FeeCalculation.leastReturnedFee(assessed, version, zero(EUR), half).isZero())
                .as("so a half dispatched with the other still possible counts on NOTHING")
                .isTrue();
        assertThat(FeeCalculation.leastReturnedFee(assessed, version, half, half).isZero())
                .as("and the remainder, once the first has completed, is exact")
                .isTrue();
    }

    @Test
    @DisplayName("BESIDE SIBLINGS IN FLIGHT the least is taken over what COMPLETED: the"
            + " non-failed sum names a before a failing sibling can undercut")
    void besideSiblingsInFlightTheCompletedSumIsTheFloor() {
        // 0.03 on 100.00, and a 25.00 refund dispatched while siblings of 50.00 and 25.00 are
        // in flight. Completing first or last it returns 0.01; completing after only the 50.00
        // it returns NOTHING.
        FeeScheduleVersion version = version("0", 3L, RoundingPolicy.HALF_UP, EUR);
        FeeAssessment assessed =
                FeeCalculation.assess(Money.ofMinorUnits(100_00L, EUR), version);
        Money quarter = Money.ofMinorUnits(25_00L, EUR);

        assertThat(FeeCalculation.returnedFee(assessed, version, zero(EUR), quarter))
                .isEqualTo(Money.ofMinorUnits(1L, EUR));
        assertThat(
                        FeeCalculation.returnedFee(
                                        assessed, version, Money.ofMinorUnits(50_00L, EUR), quarter)
                                .isZero())
                .as("the order in which the share is NOTHING")
                .isTrue();
        assertThat(
                        FeeCalculation.leastReturnedFee(
                                assessed, version, Money.ofMinorUnits(75_00L, EUR), quarter))
                .as("what a dispatcher reading the NON-FAILED sum (75.00) would count on:"
                        + " a point, exact at the wrong before")
                .isEqualTo(Money.ofMinorUnits(1L, EUR));
        assertThat(FeeCalculation.leastReturnedFee(assessed, version, zero(EUR), quarter).isZero())
                .as("and what the COMPLETED sum (nothing yet) makes it count on")
                .isTrue();
    }

    @Test
    @DisplayName("HALF_EVEN loses one on an ODD shift: a tie rounds to even on both sides, so"
            + " the difference can be one below the whole share")
    void halfEvenTiesLoseOneOnAnOddShare() {
        // 3.00 on 100.00: a 1.00 refund's exact share is 0.03 - three minor units, odd. After
        // 0.50 had completed, cum(0.50) = 1.5 -> 2 and cum(1.50) = 4.5 -> 4 under HALF_EVEN,
        // so the refund returns TWO. Under HALF_UP the same refund returns three.
        Money refunded = Money.ofMinorUnits(1_00L, EUR);
        Money afterHalf = Money.ofMinorUnits(50L, EUR);

        FeeScheduleVersion even = version("0", 3_00L, RoundingPolicy.HALF_EVEN, EUR);
        FeeAssessment underEven = FeeCalculation.assess(Money.ofMinorUnits(100_00L, EUR), even);
        assertThat(FeeCalculation.returnedFee(underEven, even, afterHalf, refunded))
                .as("the tie that makes the exception necessary")
                .isEqualTo(Money.ofMinorUnits(2L, EUR));
        assertThat(FeeCalculation.leastReturnedFee(underEven, even, zero(EUR), refunded))
                .isEqualTo(Money.ofMinorUnits(2L, EUR));

        FeeScheduleVersion up = version("0", 3_00L, RoundingPolicy.HALF_UP, EUR);
        FeeAssessment underUp = FeeCalculation.assess(Money.ofMinorUnits(100_00L, EUR), up);
        assertThat(FeeCalculation.returnedFee(underUp, up, afterHalf, refunded))
                .isEqualTo(Money.ofMinorUnits(3L, EUR));
        assertThat(FeeCalculation.leastReturnedFee(underUp, up, zero(EUR), refunded))
                .as("a whole share under a policy that commutes with the shift is EXACT")
                .isEqualTo(Money.ofMinorUnits(3L, EUR));
    }

    @Test
    @DisplayName("refused exactly as the completion refuses: a foreign version, or refunds"
            + " beyond the capture")
    void refusedAsTheCompletionRefuses() {
        FeeScheduleVersion pinned = version("0.029", 30L, RoundingPolicy.HALF_EVEN, EUR);
        FeeScheduleVersion today = version("0.10", 500L, RoundingPolicy.HALF_EVEN, EUR);
        Money gross = Money.ofMinorUnits(100_00L, EUR);
        FeeAssessment assessed = FeeCalculation.assess(gross, pinned);

        assertThatThrownBy(
                        () -> FeeCalculation.leastReturnedFee(assessed, today, zero(EUR), gross))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INV-MER-03");
        assertThatThrownBy(
                        () ->
                                FeeCalculation.leastReturnedFee(
                                        assessed,
                                        pinned,
                                        Money.ofMinorUnits(60_00L, EUR),
                                        Money.ofMinorUnits(60_00L, EUR)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceed");
    }

    // -----------------------------------------------------------------

    private static Money zero(CurrencyCode currency) {
        return Money.ofMinorUnits(0L, currency);
    }

    private static FeeScheduleVersion version(
            String rate, long fixedMinor, RoundingPolicy policy, CurrencyCode currency) {
        Instant now = Instant.parse("2026-09-23T00:00:00Z");
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
