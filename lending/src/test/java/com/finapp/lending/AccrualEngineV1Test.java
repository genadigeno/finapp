package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code ACCRUAL_ENGINE_V1} on worked cases (`P11-TSK-007`; ADR-0094 points 2-3, {@code INV-LND-09}, the arithmetic
 * half of {@code INV-LND-02}): every expected figure below was worked by hand from {@code base x r / 365} a day at
 * scale 20 and one {@code HALF_EVEN} rounding of the period's cumulative figure.
 */
@DisplayName("ACCRUAL_ENGINE_V1 (P11-TSK-007)")
class AccrualEngineV1Test {

    private static final CurrencyCode EUR = LoanTermsFixtures.EUR;
    private static final AccrualPeriod JANUARY = period("2027-01-10", "2027-02-10");
    private static final AccrualPeriod FEBRUARY = period("2027-02-10", "2027-03-10");
    private static final AccrualPeriod MARCH = period("2027-03-01", "2027-03-31");

    @Test
    @DisplayName("EUR 1000.00 at 5 % for 30 days posts 0.14, 0.13, 0.14 ... and 4.11 in all - not 30 x 0.14")
    void roundsThePeriodOnce() {
        AccrualPeriod period = period("2027-03-01", "2027-03-31");
        List<InterestAccrual> accruals = accrueEvery(terms("0.05"), period, euros(1000_00));
        assertThat(accruals).hasSize(30);
        assertThat(accruals.get(0).cumulativeExact()).isEqualByComparingTo("0.13698630136986301370");
        assertThat(accruals.get(0).cumulativeExact().scale()).isEqualTo(20);
        assertThat(accruals.subList(0, 3)).extracting(InterestAccrual::posted)
                .containsExactly(euros(14), euros(13), euros(14));
        assertThat(sum(accruals)).isEqualTo(euros(4_11));
        assertThat(accruals.get(29).cumulativeExact()).isEqualByComparingTo("4.10958904109589041100");
        assertThat(AccrualEngineV1.postedThrough(accruals.get(29))).isEqualTo(euros(4_11));
        assertThat(accruals).allSatisfy(accrual -> assertThat(accrual.engine()).isEqualTo(EngineVersion.ACCRUAL_ENGINE_V1));
    }

    @Test
    @DisplayName("an exact half-cent day rounds to even, and the period's sum rounds once: 0.00, then 0.02 over three days")
    void tiesRoundHalfEven() {
        // 36.50 x 0.05 / 365 = 0.005 exactly.
        AccrualPeriod oneDay = period("2027-05-01", "2027-05-02");
        assertThat(sum(accrueEvery(terms("0.05"), oneDay, euros(36_50)))).isEqualTo(euros(0));
        List<InterestAccrual> threeDays = accrueEvery(terms("0.05"), period("2027-05-01", "2027-05-04"), euros(36_50));
        assertThat(threeDays).extracting(InterestAccrual::posted).containsExactly(euros(0), euros(1), euros(1));
        assertThat(sum(threeDays)).isEqualTo(euros(2));
    }

    @Test
    @DisplayName("a base varying within a period: 1000.00 for 10 days, 500.00 for 10, zero for 5 - 750 / 365 = 2.05")
    void varyingBase() {
        AccrualPeriod period = period("2027-04-01", "2027-04-26");
        List<InterestAccrual> accruals = new ArrayList<>();
        Optional<InterestAccrual> previous = Optional.empty();
        for (int day = 0; day < 25; day++) {
            Money base = day < 10 ? euros(1000_00) : day < 20 ? euros(500_00) : euros(0);
            InterestAccrual accrual = AccrualEngineV1.accrue(terms("0.05"), period, previous,
                    period.start().plusDays(day), base);
            accruals.add(accrual);
            previous = Optional.of(accrual);
        }
        assertThat(sum(accruals)).isEqualTo(euros(2_05));
        assertThat(accruals.subList(20, 25)).allSatisfy(accrual -> {
            assertThat(accrual.posted()).isEqualTo(euros(0));
            assertThat(accrual.cumulativeExact()).isEqualTo(accruals.get(19).cumulativeExact());
        });
    }

    @Test
    @DisplayName("a gap - dates with no accrual, a line at zero principal - contributes nothing to the period")
    void aGapContributesNothing() {
        AccrualPeriod period = period("2027-03-01", "2027-03-31");
        InterestAccrual first = AccrualEngineV1.accrue(terms("0.05"), period, Optional.empty(), period.start(), euros(1000_00));
        InterestAccrual later = AccrualEngineV1.accrue(terms("0.05"), period, Optional.of(first),
                LocalDate.parse("2027-03-20"), euros(1000_00));
        assertThat(later.cumulativeExact()).isEqualByComparingTo("0.27397260273972602740");
        assertThat(later.posted()).isEqualTo(euros(13));
    }

    @Test
    @DisplayName("29 February accrues one ordinary day at /365: 29 days of EUR 1.00 in February 2028, 28 in 2027")
    void leapDay() {
        // 10000.00 x 0.0365 / 365 = 1.00 a day, exactly.
        List<InterestAccrual> leap = accrueEvery(terms("0.0365"), period("2028-02-15", "2028-03-15"), euros(10_000_00));
        assertThat(leap).hasSize(29);
        InterestAccrual leapDay = leap.stream().filter(a -> a.date().equals(LocalDate.parse("2028-02-29"))).findFirst()
                .orElseThrow();
        assertThat(leapDay.posted()).isEqualTo(euros(1_00));
        assertThat(sum(leap)).isEqualTo(euros(29_00));
        assertThat(sum(accrueEvery(terms("0.0365"), period("2027-02-15", "2027-03-15"), euros(10_000_00))))
                .isEqualTo(euros(28_00));
    }

    @Test
    @DisplayName("a leap year accrues 366 days of 1/365 - ACT/365F, not ACT/ACT")
    void aLeapYearIs366Days() {
        assertThat(sum(accrueEvery(terms("0.0365"), period("2028-01-01", "2029-01-01"), euros(10_000_00))))
                .isEqualTo(euros(366_00));
        assertThat(Act365Fixed.days(LocalDate.parse("2028-01-01"), LocalDate.parse("2029-01-01"))).isEqualTo(366);
        assertThat(Act365Fixed.days(LocalDate.parse("2027-01-01"), LocalDate.parse("2028-01-01"))).isEqualTo(365);
    }

    @Test
    @DisplayName("the shared primitive: one product, one division at scale 20; a day count runs forwards")
    void thePrimitive() {
        assertThat(Act365Fixed.exactInterest(euros(1000_00), new BigDecimal("0.05"), 30))
                .isEqualTo(new BigDecimal("4.10958904109589041096"));
        assertThat(Act365Fixed.roundedOnce(new BigDecimal("0.00500000000000000000"), EUR)).isEqualTo(euros(0));
        assertThatThrownBy(() -> Act365Fixed.days(LocalDate.parse("2027-01-02"), LocalDate.parse("2027-01-01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Act365Fixed.exactInterest(euros(-1), BigDecimal.ONE, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a zero rate accrues every date and posts nothing")
    void zeroRate() {
        List<InterestAccrual> accruals = accrueEvery(terms("0"), period("2027-03-01", "2027-03-31"), euros(1000_00));
        assertThat(accruals).hasSize(30).allSatisfy(accrual -> {
            assertThat(accrual.posted()).isEqualTo(euros(0));
            assertThat(accrual.cumulativeExact().signum()).isZero();
        });
    }

    @Test
    @DisplayName("a zero base accrues the date and posts nothing - the row without an entry, the caller deciding")
    void zeroBase() {
        assertThat(accrueEvery(terms("0.2"), period("2027-03-01", "2027-03-11"), euros(0)))
                .hasSize(10).allSatisfy(accrual -> assertThat(accrual.posted().isZero()).isTrue());
    }

    @Test
    @DisplayName("each period rounds once on its own: 4.25 then 3.84 - not 59 days rounded together (8.08)")
    void eachPeriodRoundsOnItsOwn() {
        List<InterestAccrual> first = accrueEvery(terms("0.05"), JANUARY, euros(1000_00));
        List<InterestAccrual> second = accrueEvery(terms("0.05"), FEBRUARY, euros(1000_00));
        assertThat(sum(first)).isEqualTo(euros(4_25));
        assertThat(sum(second)).isEqualTo(euros(3_84));
        assertThat(second.get(0).cumulativeExact()).isEqualTo(first.get(0).cumulativeExact());
    }

    @Test
    @DisplayName("both products: a credit line's statement cycle resets exactly as an instalment period does")
    void bothProducts() {
        AccrualTerms line = AccrualTerms.of(LoanTermsFixtures.creditLine("0.05").build());
        AccrualTerms loan = AccrualTerms.of(LoanTermsFixtures.personalLoan("0.05").build());
        assertThat(accrueEvery(line, FEBRUARY, euros(1000_00))).isEqualTo(accrueEvery(loan, FEBRUARY, euros(1000_00)));
        assertThat(sum(accrueEvery(line, FEBRUARY, euros(1000_00)))).isEqualTo(euros(3_84));
    }

    @Test
    @DisplayName("an accrual of the previous period is no predecessor across the reset")
    void noPredecessorAcrossTheReset() {
        InterestAccrual last = accrueEvery(terms("0.05"), JANUARY, euros(1000_00)).get(30);
        assertThatThrownBy(() -> AccrualEngineV1.accrue(terms("0.05"), FEBRUARY, Optional.of(last), FEBRUARY.start(),
                euros(1000_00))).isInstanceOfSatisfying(AccrualRefused.class,
                refused -> assertThat(refused.reason()).isEqualTo(AccrualRefused.Reason.OUT_OF_ORDER));
    }

    @Test
    @DisplayName("a negative base")
    void negativeBase() {
        refused(() -> AccrualEngineV1.accrue(terms("0.05"), MARCH, Optional.empty(), MARCH.start(), euros(-1)),
                AccrualRefused.Reason.NEGATIVE_BASE);
    }

    @Test
    @DisplayName("a base in another currency or at another scale")
    void anotherCurrency() {
        refused(() -> AccrualEngineV1.accrue(terms("0.05"), MARCH, Optional.empty(), MARCH.start(),
                Money.ofMinorUnits(100, CurrencyCode.of("USD"))), AccrualRefused.Reason.CURRENCY_MISMATCH);
        refused(() -> AccrualEngineV1.accrue(terms("0.05"), MARCH, Optional.empty(), MARCH.start(),
                Money.ofPersisted(100, EUR, 3)), AccrualRefused.Reason.CURRENCY_MISMATCH);
    }

    @Test
    @DisplayName("an unimplemented convention: another engine version, another rounding mode")
    void unimplementedConvention() {
        AccrualTerms otherEngine = new AccrualTerms(EUR, new BigDecimal("0.05"), DayCountConvention.ACT_365F,
                RoundingMode.HALF_EVEN, EngineVersion.SCHEDULE_ENGINE_V1);
        AccrualTerms otherRounding = new AccrualTerms(EUR, new BigDecimal("0.05"), DayCountConvention.ACT_365F,
                RoundingMode.HALF_UP, EngineVersion.ACCRUAL_ENGINE_V1);
        refused(() -> AccrualEngineV1.accrue(otherEngine, MARCH, Optional.empty(), MARCH.start(), euros(100)),
                AccrualRefused.Reason.UNIMPLEMENTED_CONVENTION);
        refused(() -> AccrualEngineV1.accrue(otherRounding, MARCH, Optional.empty(), MARCH.start(), euros(100)),
                AccrualRefused.Reason.UNIMPLEMENTED_CONVENTION);
    }

    @Test
    @DisplayName("a rate below 0, above 1, or past 8 places")
    void rateOutOfBounds() {
        for (String rate : List.of("-0.01", "1.00000001", "0.123456789")) {
            refused(() -> AccrualEngineV1.accrue(terms(rate), MARCH, Optional.empty(), MARCH.start(), euros(100)),
                    AccrualRefused.Reason.RATE_OUT_OF_BOUNDS);
        }
        assertThat(AccrualEngineV1.accrue(terms("1.000"), MARCH, Optional.empty(), MARCH.start(), euros(365_00))
                .posted()).isEqualTo(euros(1_00));
    }

    @Test
    @DisplayName("a date outside its period; a period that does not end after it starts")
    void dateOutsidePeriod() {
        refused(() -> AccrualEngineV1.accrue(terms("0.05"), MARCH, Optional.empty(), MARCH.endExclusive(), euros(100)),
                AccrualRefused.Reason.DATE_OUTSIDE_PERIOD);
        refused(() -> period("2027-03-01", "2027-03-01"), AccrualRefused.Reason.PERIOD_INVALID);
    }

    @Test
    @DisplayName("a date already accrued, or one before its predecessor, computes nothing")
    void aDateIsAccruedOnce() {
        InterestAccrual tenth = accrueEvery(terms("0.05"), MARCH, euros(1000_00)).get(9);
        refused(() -> AccrualEngineV1.accrue(terms("0.05"), MARCH, Optional.of(tenth), tenth.date(), euros(1000_00)),
                AccrualRefused.Reason.OUT_OF_ORDER);
        refused(() -> AccrualEngineV1.accrue(terms("0.05"), MARCH, Optional.of(tenth), tenth.date().minusDays(1),
                euros(1000_00)), AccrualRefused.Reason.OUT_OF_ORDER);
    }

    @Test
    @DisplayName("projects every date after the last accrual through G - 1, across a reset, exactly as accrual posts them")
    void projectsAsAccrualPosts() {
        List<InterestAccrual> january10To20 = new ArrayList<>(accrueEvery(terms("0.05"), JANUARY, euros(1000_00))
                .subList(0, 11));
        InterestAccrual last = january10To20.get(10);
        AccrualEngineV1.Projection projection = AccrualEngineV1.project(terms("0.05"), List.of(JANUARY, FEBRUARY),
                Optional.of(last), LocalDate.parse("2027-02-14"), euros(1000_00));
        assertThat(projection.accruals()).hasSize(25);
        assertThat(projection.accruals().get(0).date()).isEqualTo(LocalDate.parse("2027-01-21"));
        List<InterestAccrual> wholeJanuary = accrueEvery(terms("0.05"), JANUARY, euros(1000_00));
        assertThat(projection.accruals().subList(0, 20)).isEqualTo(wholeJanuary.subList(11, 31));
        assertThat(projection.accruals().subList(20, 25))
                .isEqualTo(accrueEvery(terms("0.05"), FEBRUARY, euros(1000_00)).subList(0, 5));
        Money expected = sum(wholeJanuary.subList(11, 31)).plus(sum(projection.accruals().subList(20, 25)));
        assertThat(projection.interest()).isEqualTo(expected);
        assertThat(AccrualEngineV1.postedThrough(projection.accruals().get(19))).isEqualTo(euros(4_25));
    }

    @Test
    @DisplayName("from the first period's start when nothing has accrued; empty when G - 1 is already accrued")
    void boundaries() {
        AccrualEngineV1.Projection fromStart = AccrualEngineV1.project(terms("0.05"), List.of(JANUARY),
                Optional.empty(), JANUARY.endExclusive().minusDays(1), euros(1000_00));
        assertThat(fromStart.interest()).isEqualTo(euros(4_25));
        InterestAccrual last = accrueEvery(terms("0.05"), JANUARY, euros(1000_00)).get(5);
        assertThat(AccrualEngineV1.project(terms("0.05"), List.of(JANUARY), Optional.of(last), last.date(),
                euros(1000_00)).accruals()).isEmpty();
    }

    @Test
    @DisplayName("a payoff's projection from a date (P11-TSK-026): from the day after the last accrual it is the projection"
            + " itself; after dates that accrued nothing it chains onto the last accrual exactly as accrual posts them; a"
            + " start at or before the last accrual is refused")
    void projectsFromADate() {
        InterestAccrual last = accrueEvery(terms("0.05"), JANUARY, euros(1000_00)).get(10);
        assertThat(AccrualEngineV1.project(terms("0.05"), List.of(JANUARY, FEBRUARY), Optional.of(last),
                last.date().plusDays(1), LocalDate.parse("2027-02-14"), euros(1000_00)))
                .isEqualTo(AccrualEngineV1.project(terms("0.05"), List.of(JANUARY, FEBRUARY), Optional.of(last),
                        LocalDate.parse("2027-02-14"), euros(1000_00)));
        // Five dates with nothing outstanding after the last accrual (no rows), then a base again from 2027-01-26.
        LocalDate from = last.date().plusDays(6);
        AccrualEngineV1.Projection gap = AccrualEngineV1.project(terms("0.05"), List.of(JANUARY), Optional.of(last), from,
                LocalDate.parse("2027-01-29"), euros(2000_00));
        Optional<InterestAccrual> previous = Optional.of(last);
        Money expected = euros(0);
        for (LocalDate date = from; !date.isAfter(LocalDate.parse("2027-01-29")); date = date.plusDays(1)) {
            InterestAccrual accrual = AccrualEngineV1.accrue(terms("0.05"), JANUARY, previous, date, euros(2000_00));
            expected = expected.plus(accrual.posted());
            previous = Optional.of(accrual);
        }
        assertThat(gap.accruals()).hasSize(4);
        assertThat(gap.accruals().get(0).date()).isEqualTo(from);
        assertThat(gap.interest()).isEqualTo(expected);
        refused(() -> AccrualEngineV1.project(terms("0.05"), List.of(JANUARY), Optional.of(last), last.date(),
                LocalDate.parse("2027-01-29"), euros(100)), AccrualRefused.Reason.OUT_OF_ORDER);
    }

    @Test
    @DisplayName("periods that overlap, miss a date, or disagree with the last accrual are refused")
    void invalidPeriods() {
        refused(() -> AccrualEngineV1.project(terms("0.05"), List.of(FEBRUARY, JANUARY), Optional.empty(),
                LocalDate.parse("2027-02-20"), euros(100)), AccrualRefused.Reason.PERIOD_INVALID);
        refused(() -> AccrualEngineV1.project(terms("0.05"), List.of(JANUARY), Optional.empty(),
                LocalDate.parse("2027-02-20"), euros(100)), AccrualRefused.Reason.DATE_OUTSIDE_PERIOD);
        InterestAccrual last = accrueEvery(terms("0.05"), JANUARY, euros(1000_00)).get(5);
        refused(() -> AccrualEngineV1.project(terms("0.05"), List.of(period("2027-01-16", "2027-02-10")),
                Optional.of(last), LocalDate.parse("2027-01-20"), euros(100)), AccrualRefused.Reason.PERIOD_INVALID);
    }

    @Test
    @DisplayName("terms are read as declared; no rate or amount in any toString")
    void termsAndPrinting() {
        LoanTerms loan = LoanTermsFixtures.personalLoan("0.0899").build();
        AccrualTerms terms = AccrualTerms.of(loan);
        assertThat(terms).isEqualTo(new AccrualTerms(EUR, new BigDecimal("0.0899"), DayCountConvention.ACT_365F,
                RoundingMode.HALF_EVEN, EngineVersion.ACCRUAL_ENGINE_V1));
        assertThat(terms.toString()).doesNotContain("0.0899");
        InterestAccrual accrual = AccrualEngineV1.accrue(terms, period("2027-03-01", "2027-03-31"), Optional.empty(),
                LocalDate.parse("2027-03-01"), euros(123_456_78));
        assertThat(accrual.toString()).doesNotContain("123", "456", "0.0899").contains("2027-03-01");
        assertThat(new AccrualEngineV1.Projection(List.of(accrual), accrual.posted()).toString())
                .doesNotContain("123", "456");
    }

    @Test
    @DisplayName("the same inputs give the same figures - a function of its arguments only")
    void deterministic() {
        AccrualPeriod period = period("2027-03-01", "2027-03-31");
        assertThat(accrueEvery(terms("0.0899"), period, euros(9_876_54)))
                .isEqualTo(accrueEvery(terms("0.0899"), period, euros(9_876_54)));
    }

    // ------------------------------------------------------------------ helpers

    static AccrualTerms terms(String rate) {
    return new AccrualTerms(EUR, new BigDecimal(rate), DayCountConvention.ACT_365F, RoundingMode.HALF_EVEN,
            EngineVersion.ACCRUAL_ENGINE_V1);
    }

    static AccrualPeriod period(String start, String endExclusive) {
    return new AccrualPeriod(LocalDate.parse(start), LocalDate.parse(endExclusive));
    }

    static Money euros(long cents) {
    return Money.ofMinorUnits(cents, EUR);
    }

    /** Every date of {@code period} accrued on {@code base}, oldest first. */
    static List<InterestAccrual> accrueEvery(AccrualTerms terms, AccrualPeriod period, Money base) {
    List<InterestAccrual> accruals = new ArrayList<>();
    Optional<InterestAccrual> previous = Optional.empty();
    for (LocalDate date = period.start(); date.isBefore(period.endExclusive()); date = date.plusDays(1)) {
        InterestAccrual accrual = AccrualEngineV1.accrue(terms, period, previous, date, base);
        accruals.add(accrual);
        previous = Optional.of(accrual);
    }
    return accruals;
    }

    static Money sum(List<InterestAccrual> accruals) {
    Money total = euros(0);
    for (InterestAccrual accrual : accruals) {
        total = total.plus(accrual.posted());
    }
    return total;
    }

    private static void refused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, AccrualRefused.Reason reason) {
    assertThatThrownBy(call).isInstanceOfSatisfying(AccrualRefused.class,
            refused -> assertThat(refused.reason()).isEqualTo(reason));
    }
}
