package com.finapp.creditmath;

import static com.finapp.creditmath.EngineTermsFixtures.EUR;
import static com.finapp.creditmath.EngineTermsFixtures.PERSONAL_LOAN_BOUNDS;
import static com.finapp.creditmath.EngineTermsFixtures.judge;
import static com.finapp.creditmath.EngineTermsFixtures.scheduleTerms;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.creditmath.EngineTermsFixtures.Bounds;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code SCHEDULE_ENGINE_V1}'s golden worked cases and its refusals (`P11-TSK-006`; ADR-0093, {@code PHASE_11_PLAN.md}
 * section 12.2; {@code INV-LND-03}, {@code INV-LND-09}, {@code INV-MON-03}).
 *
 * <p><strong>Every figure here has a source that knows its answer.</strong> Each table was computed by an independent
 * oracle - exact rationals in another language, the annuity summed as {@code P / sum v_j} rather than the engine's
 * running products, the interest divided directly to the minor unit - and the 6-month case is checked by hand below.
 * A row reads {@code sequence|period start|due date|days|opening|interest|principal|amount|closing}.
 *
 * <p><strong>The 6-month case by hand</strong> (EUR 10,000.00 at 10 %, accrual start 1 January 2026, day 10, 15 days'
 * shortest first period): 1 January + 15 = 16 January, after the 10th, so D1 = 10 February - a 40-day first period,
 * then 28, 31, 30, 31, 30 days. Interest 1: 10,000.00 x 0.10 x 40 / 365 = 109.589... = 109.59; interest 2: 8,390.30 x
 * 0.10 x 28 / 365 = 64.363... = 64.36; the last: 1,705.24 x 0.10 x 30 / 365 = 14.015... = 14.02, so the final instalment
 * is 1,705.24 + 14.02 = 1,719.26, below the level 1,719.29. The principal portions 1,609.70 + 1,654.93 + 1,662.09 +
 * 1,677.59 + 1,690.45 + 1,705.24 = 10,000.00 exactly. The plan's equal-period formula would have given 1,715.62 - and a
 * final instalment of 1,738.08, above it: the reason the level instalment is the actual-day annuity.
 *
 * <p><strong>Moved with the engine</strong> (`P12-TSK-002`): from {@code lending}, every figure and assertion unedited;
 * the inputs are the same terms built through {@link ScheduleTerms} ({@link EngineTermsFixtures}) instead of lending's
 * {@code LoanTerms}, and the case that refused a credit line's terms - a lending product's kind - is lending's
 * {@code LoanEnginesTest}'s, here replaced by the conventions a {@link ScheduleTerms} itself can name.
 */
@DisplayName("schedule engine v1: golden worked cases and typed refusals (P11-TSK-006)")
class ScheduleEngineV1Test {

    private static final ScheduleTerms TEN_PERCENT = scheduleTerms("0.10");

    // ------------------------------------------------------------------ golden cases

    @Test
    @DisplayName("r = 0: the instalment is P / n rounded UP, no interest, the final instalment the remainder")
    void zeroRate() {
        Schedule schedule = projected(scheduleTerms("0"), "1000.00", 6, 1, "2026-01-15");
        assertThat(schedule.instalment()).isEqualTo(eur("166.67"));
        assertThat(rows(schedule)).containsExactly(
                "1|2026-01-15|2026-02-01|17|1000.00|0.00|166.67|166.67|833.33",
                "2|2026-02-01|2026-03-01|28|833.33|0.00|166.67|166.67|666.66",
                "3|2026-03-01|2026-04-01|31|666.66|0.00|166.67|166.67|499.99",
                "4|2026-04-01|2026-05-01|30|499.99|0.00|166.67|166.67|333.32",
                "5|2026-05-01|2026-06-01|31|333.32|0.00|166.67|166.67|166.65",
                "6|2026-06-01|2026-07-01|30|166.65|0.00|166.65|166.65|0.00");
        assertThat(schedule.totalInterest()).isEqualTo(eur("0.00"));
    }

    @Test
    @DisplayName("6 months at 10 %, a 40-day first period - checked by hand: the final instalment below the level one")
    void sixMonths() {
        Schedule schedule = projected(TEN_PERCENT, "10000.00", 6, 10, "2026-01-01");
        assertThat(schedule.instalment()).isEqualTo(eur("1719.29"));
        assertThat(rows(schedule)).containsExactly(
                "1|2026-01-01|2026-02-10|40|10000.00|109.59|1609.70|1719.29|8390.30",
                "2|2026-02-10|2026-03-10|28|8390.30|64.36|1654.93|1719.29|6735.37",
                "3|2026-03-10|2026-04-10|31|6735.37|57.20|1662.09|1719.29|5073.28",
                "4|2026-04-10|2026-05-10|30|5073.28|41.70|1677.59|1719.29|3395.69",
                "5|2026-05-10|2026-06-10|31|3395.69|28.84|1690.45|1719.29|1705.24",
                "6|2026-06-10|2026-07-10|30|1705.24|14.02|1705.24|1719.26|0.00");
        assertThat(schedule.totalInterest()).isEqualTo(eur("315.71"));
        assertThat(schedule.totalPayable()).isEqualTo(eur("10315.71"));
        assertThat(schedule.finalInstalment()).isEqualTo(eur("1719.26"));
        assertThat(schedule.engine()).isEqualTo(EngineVersion.SCHEDULE_ENGINE_V1);
    }

    @Test
    @DisplayName("60 months, repayment day 28, through two leap Februaries: 29 February a due date's month, never a day")
    void sixtyMonths() {
        Schedule schedule = projected(scheduleTerms("0.0899"), "25000.00", 60, 28, "2026-03-03");
        assertThat(schedule.instalment()).isEqualTo(eur("518.35"));
        List<String> rows = rows(schedule);
        assertThat(rows).hasSize(60);
        assertThat(rows.subList(0, 3)).containsExactly(
                "1|2026-03-03|2026-03-28|25|25000.00|153.94|364.41|518.35|24635.59",
                "2|2026-03-28|2026-04-28|31|24635.59|188.10|330.25|518.35|24305.34",
                "3|2026-04-28|2026-05-28|30|24305.34|179.59|338.76|518.35|23966.58");
        assertThat(rows.get(23)).isEqualTo("24|2028-01-28|2028-02-28|31|16695.23|127.47|390.88|518.35|16304.35");
        assertThat(rows.get(24)).as("the leap February's period has 29 days")
                .isEqualTo("25|2028-02-28|2028-03-28|29|16304.35|116.46|401.89|518.35|15902.46");
        assertThat(rows.subList(57, 60)).containsExactly(
                "58|2030-11-28|2030-12-28|30|1531.92|11.32|507.03|518.35|1024.89",
                "59|2030-12-28|2031-01-28|31|1024.89|7.83|510.52|518.35|514.37",
                "60|2031-01-28|2031-02-28|31|514.37|3.93|514.37|518.30|0.00");
        assertThat(schedule.totalInterest()).isEqualTo(eur("6100.95"));
    }

    @Test
    @DisplayName("disbursed on 31 January: the first due date the 15th of February, exactly the shortest first period")
    void disbursedOnTheThirtyFirstOfJanuary() {
        Schedule schedule = projected(scheduleTerms("0.0799"), "5000.00", 12, 15, "2026-01-31");
        assertThat(schedule.instalment()).isEqualTo(eur("433.34"));
        assertThat(rows(schedule)).containsExactly(
                "1|2026-01-31|2026-02-15|15|5000.00|16.42|416.92|433.34|4583.08",
                "2|2026-02-15|2026-03-15|28|4583.08|28.09|405.25|433.34|4177.83",
                "3|2026-03-15|2026-04-15|31|4177.83|28.35|404.99|433.34|3772.84",
                "4|2026-04-15|2026-05-15|30|3772.84|24.78|408.56|433.34|3364.28",
                "5|2026-05-15|2026-06-15|31|3364.28|22.83|410.51|433.34|2953.77",
                "6|2026-06-15|2026-07-15|30|2953.77|19.40|413.94|433.34|2539.83",
                "7|2026-07-15|2026-08-15|31|2539.83|17.24|416.10|433.34|2123.73",
                "8|2026-08-15|2026-09-15|31|2123.73|14.41|418.93|433.34|1704.80",
                "9|2026-09-15|2026-10-15|30|1704.80|11.20|422.14|433.34|1282.66",
                "10|2026-10-15|2026-11-15|31|1282.66|8.70|424.64|433.34|858.02",
                "11|2026-11-15|2026-12-15|30|858.02|5.63|427.71|433.34|430.31",
                "12|2026-12-15|2027-01-15|31|430.31|2.92|430.31|433.23|0.00");
        assertThat(schedule.totalInterest()).isEqualTo(eur("199.97"));
    }

    @Test
    @DisplayName("disbursed on 29 February of a leap year, repayment day 28: 28 days to 28 March, the leap day accruing"
            + " as an ordinary day")
    void disbursedOnTheTwentyNinthOfFebruary() {
        Schedule schedule = projected(scheduleTerms("0.1249"), "12000.00", 24, 28, "2028-02-29");
        assertThat(schedule.instalment()).isEqualTo(eur("567.37"));
        List<String> rows = rows(schedule);
        assertThat(rows.subList(0, 2)).containsExactly(
                "1|2028-02-29|2028-03-28|28|12000.00|114.98|452.39|567.37|11547.61",
                "2|2028-03-28|2028-04-28|31|11547.61|122.50|444.87|567.37|11102.74");
        assertThat(rows.get(12)).isEqualTo("13|2029-02-28|2029-03-28|28|6372.20|61.05|506.32|567.37|5865.88");
        assertThat(rows.subList(22, 24)).containsExactly(
                "23|2029-12-28|2030-01-28|31|1116.73|11.85|555.52|567.37|561.21",
                "24|2030-01-28|2030-02-28|31|561.21|5.95|561.21|567.16|0.00");
        assertThat(schedule.totalInterest()).isEqualTo(eur("1616.67"));
    }

    @Test
    @DisplayName("an intended 31st: 31 January, 28 February, 31 March, 30 April - clamped each month, never drifting")
    void theClampFromAnIntendedThirtyFirst() {
        Schedule schedule = projected(scheduleTerms("0.12"), "3000.00", 6, 31, "2026-01-01");
        assertThat(schedule.intendedDay()).isEqualTo(31);
        assertThat(rows(schedule)).containsExactly(
                "1|2026-01-01|2026-01-31|30|3000.00|29.59|487.70|517.29|2512.30",
                "2|2026-01-31|2026-02-28|28|2512.30|23.13|494.16|517.29|2018.14",
                "3|2026-02-28|2026-03-31|31|2018.14|20.57|496.72|517.29|1521.42",
                "4|2026-03-31|2026-04-30|30|1521.42|15.01|502.28|517.29|1019.14",
                "5|2026-04-30|2026-05-31|31|1019.14|10.39|506.90|517.29|512.24",
                "6|2026-05-31|2026-06-30|30|512.24|5.05|512.24|517.29|0.00");
    }

    @Test
    @DisplayName("the rounding residue raised: UP(A*) = 85.30 would leave a final 85.31, so the level instalment is"
            + " 85.31 - the least amount the final instalment does not exceed")
    void theLevelInstalmentRaisedForTheRoundingResidue() {
        Schedule schedule = projected(scheduleTerms("0.0899"), "500.00", 6, 10, "2026-01-20");
        assertThat(schedule.instalment()).isEqualTo(eur("85.31"));
        assertThat(rows(schedule)).containsExactly(
                "1|2026-01-20|2026-02-10|21|500.00|2.59|82.72|85.31|417.28",
                "2|2026-02-10|2026-03-10|28|417.28|2.88|82.43|85.31|334.85",
                "3|2026-03-10|2026-04-10|31|334.85|2.56|82.75|85.31|252.10",
                "4|2026-04-10|2026-05-10|30|252.10|1.86|83.45|85.31|168.65",
                "5|2026-05-10|2026-06-10|31|168.65|1.29|84.02|85.31|84.63",
                "6|2026-06-10|2026-07-10|30|84.63|0.63|84.63|85.26|0.00");
        assertThat(ScheduleEngineV1.annuityUp(eur("500.00"), new BigDecimal("0.0899"), new long[] {21, 28, 31, 30, 31, 30}))
                .as("the exact annuity rounded once UP").isEqualTo(eur("85.30"));
    }

    @Test
    @DisplayName("recalculation (step 7): a new principal, remaining term and next due date - the first period's interest"
            + " from the effective date")
    void recalculation() {
        ScheduleOutcome outcome = ScheduleEngineV1.recalculate(scheduleTerms("0.0899"), eur("4321.09"), 10, 20,
                LocalDate.parse("2026-06-05"), LocalDate.parse("2026-06-20"));
        Schedule schedule = ((ScheduleOutcome.Projected) outcome).schedule();
        assertThat(schedule.start()).isEqualTo(LocalDate.parse("2026-06-05"));
        assertThat(schedule.instalment()).isEqualTo(eur("448.47"));
        assertThat(rows(schedule)).containsExactly(
                "1|2026-06-05|2026-06-20|15|4321.09|15.96|432.51|448.47|3888.58",
                "2|2026-06-20|2026-07-20|30|3888.58|28.73|419.74|448.47|3468.84",
                "3|2026-07-20|2026-08-20|31|3468.84|26.49|421.98|448.47|3046.86",
                "4|2026-08-20|2026-09-20|31|3046.86|23.26|425.21|448.47|2621.65",
                "5|2026-09-20|2026-10-20|30|2621.65|19.37|429.10|448.47|2192.55",
                "6|2026-10-20|2026-11-20|31|2192.55|16.74|431.73|448.47|1760.82",
                "7|2026-11-20|2026-12-20|30|1760.82|13.01|435.46|448.47|1325.36",
                "8|2026-12-20|2027-01-20|31|1325.36|10.12|438.35|448.47|887.01",
                "9|2027-01-20|2027-02-20|31|887.01|6.77|441.70|448.47|445.31",
                "10|2027-02-20|2027-03-20|28|445.31|3.07|445.31|448.38|0.00");
        assertThat(schedule.totalInterest()).isEqualTo(eur("163.52"));
    }

    @Test
    @DisplayName("recalculation at the instalment (step 7, a partial prepayment - P11-TSK-028): the same level instalment,"
            + " the least term that amortises the lower principal - EUR 1,000.00 prepaid off the recalculation above"
            + " shortens ten instalments to eight, the last the remainder and its interest, below the level one")
    void recalculationAtTheInstalment() {
        ScheduleTerms terms = scheduleTerms("0.0899");
        LocalDate effective = LocalDate.parse("2026-06-05");
        LocalDate next = LocalDate.parse("2026-06-20");
        ScheduleOutcome outcome = ScheduleEngineV1.recalculateAtInstalment(terms, eur("3321.09"), eur("448.47"), 10, 20,
                effective, next);
        Schedule schedule = ((ScheduleOutcome.Projected) outcome).schedule();
        assertThat(schedule.instalment()).as("the instalment unchanged").isEqualTo(eur("448.47"));
        assertThat(schedule.principal()).isEqualTo(eur("3321.09"));
        // An independent oracle (exact rationals, the instalment held at 448.47 until what is left and its interest fit).
        assertThat(rows(schedule)).containsExactly(
                "1|2026-06-05|2026-06-20|15|3321.09|12.27|436.20|448.47|2884.89",
                "2|2026-06-20|2026-07-20|30|2884.89|21.32|427.15|448.47|2457.74",
                "3|2026-07-20|2026-08-20|31|2457.74|18.77|429.70|448.47|2028.04",
                "4|2026-08-20|2026-09-20|31|2028.04|15.48|432.99|448.47|1595.05",
                "5|2026-09-20|2026-10-20|30|1595.05|11.79|436.68|448.47|1158.37",
                "6|2026-10-20|2026-11-20|31|1158.37|8.84|439.63|448.47|718.74",
                "7|2026-11-20|2026-12-20|30|718.74|5.31|443.16|448.47|275.58",
                "8|2026-12-20|2027-01-20|31|275.58|2.10|275.58|277.68|0.00");
        assertThat(schedule.totalInterest()).isEqualTo(eur("95.88"));
        // The least term: seven instalments at 448.47 would leave the seventh above it.
        assertThat(schedule.periods().get(6).closingBalance().isPositive()).isTrue();
        // At zero rate: 500.00 at 166.67 is three instalments, the last 166.66.
        Schedule zero = ((ScheduleOutcome.Projected) ScheduleEngineV1.recalculateAtInstalment(scheduleTerms("0"),
                eur("500.00"), eur("166.67"), 6, 1, LocalDate.parse("2026-01-15"), LocalDate.parse("2026-02-01")))
                .schedule();
        assertThat(rows(zero)).containsExactly(
                "1|2026-01-15|2026-02-01|17|500.00|0.00|166.67|166.67|333.33",
                "2|2026-02-01|2026-03-01|28|333.33|0.00|166.67|166.67|166.66",
                "3|2026-03-01|2026-04-01|31|166.66|0.00|166.66|166.66|0.00");
        // A principal the instalment pays in one: one instalment, the whole of it.
        assertThat(((ScheduleOutcome.Projected) ScheduleEngineV1.recalculateAtInstalment(terms, eur("100.00"),
                eur("448.47"), 10, 20, effective, next)).schedule().termMonths()).isEqualTo(1);
    }

    @Test
    @DisplayName("recalculation at the instalment never lengthens the term: when the instalment cannot amortise the"
            + " principal within the remaining instalments - or never reduces it - the term is kept and the instalment is"
            + " the engine's own recalculation, above the old one; its refusals are recalculate's")
    void recalculationAtTheInstalmentNeverLengthensTheTerm() {
        ScheduleTerms terms = scheduleTerms("0.0899");
        LocalDate effective = LocalDate.parse("2026-06-05");
        LocalDate next = LocalDate.parse("2026-06-20");
        Schedule kept = ((ScheduleOutcome.Projected) ScheduleEngineV1.recalculate(terms, eur("4321.09"), 10, 20, effective,
                next)).schedule();
        for (String instalment : List.of("400.00", "10.00")) {
            ScheduleOutcome outcome = ScheduleEngineV1.recalculateAtInstalment(terms, eur("4321.09"), eur(instalment), 10,
                    20, effective, next);
            assertThat(((ScheduleOutcome.Projected) outcome).schedule()).as(instalment).isEqualTo(kept);
        }
        assertThat(kept.instalment().compareTo(eur("400.00"))).as("above the old instalment").isPositive();
        assertThat(List.of(
                ScheduleEngineV1.recalculateAtInstalment(terms, eur("100.00"), eur("0.00"), 10, 20, effective, next),
                ScheduleEngineV1.recalculateAtInstalment(terms, eur("100.00"), Money.ofMinorUnits(100_00,
                        CurrencyCode.of("USD")), 10, 20, effective, next),
                ScheduleEngineV1.recalculateAtInstalment(terms, eur("100.00"), eur("50.00"), 0, 20, effective, next),
                ScheduleEngineV1.recalculateAtInstalment(terms, eur("100.00"), eur("50.00"), 10, 20, effective, effective),
                ScheduleEngineV1.recalculateAtInstalment(terms, eur("100.00"), eur("50.00"), 10, 20, effective,
                        LocalDate.parse("2026-06-21"))))
                .allSatisfy(outcome -> assertThat(outcome).isInstanceOfSatisfying(ScheduleOutcome.Refused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(ScheduleRefusal.INVALID_REQUEST)));
    }

    // ------------------------------------------------------------------ the value

    @Test
    @DisplayName("a Schedule that does not conserve - another principal, another level instalment, a final period"
            + " leaving a balance, a period not its parts - cannot be built (INV-LND-03, asserted at construction)")
    void aScheduleThatDoesNotConserveCannotExist() {
        Schedule schedule = projected(TEN_PERCENT, "10000.00", 6, 10, "2026-01-01");
        List<SchedulePeriod> periods = new ArrayList<>(schedule.periods());
        assertThat(rebuilt(schedule, schedule.principal(), schedule.instalment(), periods)).isEqualTo(schedule);
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .as("a principal the periods do not amortise")
                .isThrownBy(() -> rebuilt(schedule, eur("10000.01"), schedule.instalment(), periods));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .as("a level instalment the periods do not pay")
                .isThrownBy(() -> rebuilt(schedule, schedule.principal(), eur("1719.25"), periods));
        SchedulePeriod last = periods.get(5);
        periods.set(5, new SchedulePeriod(6, last.periodStart(), last.dueDate(), last.days(), last.openingBalance(),
                last.interest(), eur("1705.23"), eur("1719.25"), eur("0.01")));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .as("a final period leaving a balance")
                .isThrownBy(() -> rebuilt(schedule, schedule.principal(), schedule.instalment(), periods));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .as("a period whose amount is not its parts")
                .isThrownBy(() -> new SchedulePeriod(1, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-10"), 40,
                        eur("10000.00"), eur("109.59"), eur("1609.70"), eur("1719.30"), eur("8390.30")));
        assertThat(schedule.toString()).doesNotContain("1719").doesNotContain("10000");
    }

    private static Schedule rebuilt(Schedule from, Money principal, Money instalment, List<SchedulePeriod> periods) {
        return new Schedule(from.engine(), from.currency(), principal, from.nominalAnnualRate(), from.start(),
                from.intendedDay(), instalment, periods);
    }

    // ------------------------------------------------------------------ the due dates

    @Test
    @DisplayName("D1 is the first date on the intended day at least the shortest first period after the accrual start")
    void theFirstDueDate() {
        assertThat(firstDue(16, "2026-01-01")).as("exactly 15 days: the 16th itself").isEqualTo("2026-01-16");
        assertThat(firstDue(15, "2026-01-01")).as("14 days is too short: next month").isEqualTo("2026-02-15");
        assertThat(firstDue(1, "2026-12-20")).as("4 January is past the 1st: across the year to February")
                .isEqualTo("2027-02-01");
        assertThat(firstDue(28, "2026-02-10")).isEqualTo("2026-02-28");
    }

    // ------------------------------------------------------------------ refusals

    @Test
    @DisplayName("a long first period at a high rate repays no principal in the first instalment: NOT_AMORTISING, a"
            + " typed answer - never a negatively amortising schedule")
    void aNonPositivePrincipalPortionIsRefused() {
        ScheduleOutcome outcome =
                ScheduleEngineV1.project(scheduleTerms("0.9"), eur("10000.00"), 60, 15, LocalDate.parse("2026-01-01"));
        assertThat(outcome).isInstanceOfSatisfying(ScheduleOutcome.Refused.class, refused -> {
            assertThat(refused.reason()).isEqualTo(ScheduleRefusal.NOT_AMORTISING);
            assertThat(refused.detail()).contains("instalment 1").doesNotContain("0.9").doesNotContain("10000");
        });
    }

    @Test
    @DisplayName("a principal too small for its term would be repaid before the last instalment: NOT_AMORTISING")
    void aBalanceExhaustedEarlyIsRefused() {
        ScheduleOutcome outcome =
                ScheduleEngineV1.project(scheduleTerms("0"), eur("0.05"), 6, 10, LocalDate.parse("2026-01-01"));
        assertThat(outcome).isInstanceOfSatisfying(ScheduleOutcome.Refused.class,
                refused -> assertThat(refused.reason()).isEqualTo(ScheduleRefusal.NOT_AMORTISING));
    }

    @Test
    @DisplayName("terms version 1 does not compute - another engine version, another rounding - are refused"
            + " CONVENTION_NOT_IMPLEMENTED (a credit line's terms: lending's LoanEnginesTest, P12-TSK-002)")
    void unimplementedConventionsAreRefused() {
        List<ScheduleTerms> unimplemented = List.of(
                new ScheduleTerms(EUR, new BigDecimal("0.15"), 15, DayCountConvention.ACT_365F, RoundingMode.UP,
                        RoundingMode.HALF_EVEN, EngineVersion.ACCRUAL_ENGINE_V1),
                new ScheduleTerms(EUR, new BigDecimal("0.15"), 15, DayCountConvention.ACT_365F, RoundingMode.HALF_EVEN,
                        RoundingMode.HALF_EVEN, EngineVersion.SCHEDULE_ENGINE_V1),
                new ScheduleTerms(EUR, new BigDecimal("0.15"), 15, DayCountConvention.ACT_365F, RoundingMode.UP,
                        RoundingMode.HALF_UP, EngineVersion.SCHEDULE_ENGINE_V1));
        for (ScheduleTerms terms : unimplemented) {
            assertThat(ScheduleEngineV1.project(terms, eur("1000.00"), 12, 10, LocalDate.parse("2026-01-01")))
                    .isInstanceOfSatisfying(ScheduleOutcome.Refused.class, refused ->
                            assertThat(refused.reason()).isEqualTo(ScheduleRefusal.CONVENTION_NOT_IMPLEMENTED));
            assertThat(judge(terms, PERSONAL_LOAN_BOUNDS))
                    .hasValueSatisfying(refused ->
                            assertThat(refused.reason()).isEqualTo(ScheduleRefusal.CONVENTION_NOT_IMPLEMENTED));
        }
    }

    @Test
    @DisplayName("schedule terms outside the arithmetic's range - a rate above 1 or past 8 places, a shortest first"
            + " period of 0 or above a year - are INVALID_REQUEST, never computed (P12-TSK-002: unreachable through"
            + " lending's LoanTerms, whose door admits less)")
    void termsOutsideTheRangeAreRefused() {
        LocalDate start = LocalDate.parse("2026-01-01");
        List<ScheduleTerms> outside = List.of(
                scheduleTerms("1.0001"), scheduleTerms("-0.01"), scheduleTerms("0.123456789"),
                scheduleTerms("0.10", 0), scheduleTerms("0.10", ScheduleEngineV1.MAXIMUM_RECALCULATION_FIRST_PERIOD_DAYS + 1));
        for (ScheduleTerms terms : outside) {
            assertThat(List.of(
                            ScheduleEngineV1.project(terms, eur("1000.00"), 12, 10, start),
                            ScheduleEngineV1.recalculate(terms, eur("1000.00"), 12, 10, start, LocalDate.parse("2026-02-10")),
                            ScheduleEngineV1.recalculateAtInstalment(terms, eur("1000.00"), eur("100.00"), 12, 10, start,
                                    LocalDate.parse("2026-02-10")),
                            judge(terms, PERSONAL_LOAN_BOUNDS).<ScheduleOutcome>map(refused -> refused).orElseThrow()))
                    .as(terms.nominalAnnualRate() + " / " + terms.minFirstPeriodDays())
                    .allSatisfy(outcome -> assertThat(outcome).isInstanceOfSatisfying(ScheduleOutcome.Refused.class,
                            refused -> assertThat(refused.reason()).isEqualTo(ScheduleRefusal.INVALID_REQUEST)));
        }
        assertThat(ScheduleEngineV1.project(scheduleTerms("1"), eur("1000.00"), 3, 10, start))
                .as("the range's own ends are computed").isInstanceOf(ScheduleOutcome.Projected.class);
        assertThat(ScheduleEngineV1.project(scheduleTerms("0.12345678", 1), eur("1000.00"), 12, 10, start))
                .isInstanceOf(ScheduleOutcome.Projected.class);
        assertThat(judge(scheduleTerms("0.10"), PERSONAL_LOAN_BOUNDS)).isEmpty();
        assertThat(ScheduleEngineV1.judgeAmortisation(scheduleTerms("0.10"), List.of(1, 32), eur("500.00"), 60))
                .as("an allowed day past the longest month").hasValueSatisfying(refused ->
                        assertThat(refused.reason()).isEqualTo(ScheduleRefusal.INVALID_REQUEST));
    }

    @Test
    @DisplayName("a malformed request is INVALID_REQUEST - never an exception from arithmetic")
    void malformedRequestsAreRefused() {
        LocalDate start = LocalDate.parse("2026-01-01");
        List<ScheduleOutcome> outcomes = List.of(
                ScheduleEngineV1.project(TEN_PERCENT, eur("0.00"), 12, 10, start),
                ScheduleEngineV1.project(TEN_PERCENT, eur("-1.00"), 12, 10, start),
                ScheduleEngineV1.project(TEN_PERCENT, Money.ofMinorUnits(100_00, CurrencyCode.of("USD")), 12, 10, start),
                ScheduleEngineV1.project(TEN_PERCENT, Money.ofMinorUnits(ScheduleEngineV1.MAXIMUM_PRINCIPAL_MINOR_UNITS + 1,
                        EUR), 12, 10, start),
                ScheduleEngineV1.project(TEN_PERCENT, eur("100.00"), 0, 10, start),
                ScheduleEngineV1.project(TEN_PERCENT, eur("100.00"), ScheduleEngineV1.MAXIMUM_TERM_MONTHS + 1, 10, start),
                ScheduleEngineV1.project(TEN_PERCENT, eur("100.00"), 12, 0, start),
                ScheduleEngineV1.project(TEN_PERCENT, eur("100.00"), 12, 32, start),
                ScheduleEngineV1.project(TEN_PERCENT, eur("100.00"), 12, 10, LocalDate.of(9_901, 1, 1)),
                ScheduleEngineV1.recalculate(TEN_PERCENT, eur("100.00"), 12, 10, start, start),
                ScheduleEngineV1.recalculate(TEN_PERCENT, eur("100.00"), 12, 10, start, LocalDate.parse("2025-12-10")),
                ScheduleEngineV1.recalculate(TEN_PERCENT, eur("100.00"), 12, 10, start, LocalDate.parse("2026-01-11")),
                ScheduleEngineV1.recalculate(TEN_PERCENT, eur("100.00"), 12, 31, start, LocalDate.parse("2026-02-27")),
                ScheduleEngineV1.recalculate(TEN_PERCENT, eur("100.00"), 12, 10, start, LocalDate.parse("2027-01-10")));
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOfSatisfying(
                ScheduleOutcome.Refused.class,
                refused -> assertThat(refused.reason()).isEqualTo(ScheduleRefusal.INVALID_REQUEST)));
        assertThat(ScheduleEngineV1.recalculate(TEN_PERCENT, eur("100.00"), 12, 31, start, LocalDate.parse("2026-02-28")))
                .as("a clamped next due date is on its intended day").isInstanceOf(ScheduleOutcome.Projected.class);
    }

    // ------------------------------------------------------------------ the door's judgement

    @Test
    @DisplayName("the terms door: the plan's 8.99 % amortises across 6-60 months and EUR 500-25,000; 30 % cannot - a"
            + " 60-month loan with a 45-day first period would repay nothing in its first instalment")
    void theJudgementAcrossTheProductsBounds() {
        Bounds bounds = PERSONAL_LOAN_BOUNDS;
        assertThat(judge(scheduleTerms("0.0899"), bounds)).isEmpty();
        assertThat(judge(scheduleTerms("0"), bounds)).isEmpty();
        Optional<ScheduleOutcome.Refused> refused = judge(scheduleTerms("0.30"), bounds);
        assertThat(refused).hasValueSatisfying(refusal -> {
            assertThat(refusal.reason()).isEqualTo(ScheduleRefusal.NOT_AMORTISING);
            assertThat(refusal.detail()).contains("60-month").contains("45 days").doesNotContain("0.3");
        });
        assertThat(judge(scheduleTerms("0.30"),
                new Bounds(eur("500.00"), eur("25000.00"), 6, 24)))
                .as("the same rate over at most 24 months amortises").isEmpty();
        assertThat(judge(scheduleTerms("0.30"),
                new Bounds(eur("500.00"), eur("25000.00"), 1, 1)))
                .as("a single instalment always amortises").isEmpty();
    }

    @Test
    @DisplayName("a 1-day shortest first period moves the binding bound to the later periods: 56.44 % admitted, 56.45 %"
            + " refused - no later instalment may charge a 31-day month on the whole principal within half a unit of the"
            + " instalment")
    void theLaterPeriodsBound() {
        Bounds bounds = PERSONAL_LOAN_BOUNDS;
        assertThat(judge(oneDayFirstPeriod("0.5644"), bounds)).isEmpty();
        assertThat(judge(oneDayFirstPeriod("0.5645"), bounds)).hasValueSatisfying(refusal -> {
            assertThat(refusal.reason()).isEqualTo(ScheduleRefusal.NOT_AMORTISING);
            assertThat(refusal.detail()).contains("after its first");
        });
    }

    @Test
    @DisplayName("the old door's gap (the Phase 11 gate's R5): at 23.10 % - admitted while the door compared rounded figures"
            + " at the bounds' two principals only - a real EUR 500.19 loan over 60 months on day 1 from 2028-12-18 repays no"
            + " principal in its first instalment, so its disbursement or payout conclusion had no outcome; the door now"
            + " refuses the rate, and at its highest admitted rate (23.0908 %) the same loan amortises")
    void theOldDoorsGap() {
        Bounds bounds = PERSONAL_LOAN_BOUNDS;
        Money gap = eur("500.19");
        LocalDate start = LocalDate.parse("2028-12-18");
        assertThat(ScheduleEngineV1.project(scheduleTerms("0.231"), gap, 60, 1, start))
                .isInstanceOfSatisfying(ScheduleOutcome.Refused.class, refused -> {
                    assertThat(refused.reason()).isEqualTo(ScheduleRefusal.NOT_AMORTISING);
                    assertThat(refused.detail()).contains("instalment 1");
                });
        assertThat(ScheduleEngineV1.project(scheduleTerms("0.231"), bounds.minimumPrincipal(), 60, 1, start))
                .as("at the bounds' own principal it amortises - the old door's two probes could not see the gap")
                .isInstanceOf(ScheduleOutcome.Projected.class);
        assertThat(judge(scheduleTerms("0.231"), bounds)).hasValueSatisfying(
                refusal -> assertThat(refusal.detail()).contains("first instalment"));
        assertThat(judge(scheduleTerms("0.230908"), bounds)).isEmpty();
        assertThat(judge(scheduleTerms("0.230909"), bounds)).isPresent();
        assertThat(ScheduleEngineV1.project(scheduleTerms("0.230908"), gap, 60, 1, start))
                .isInstanceOf(ScheduleOutcome.Projected.class);
    }

    @Test
    @DisplayName("a minimum principal too small for the longest term is refused at the door - a balance repaid before the"
            + " last instalment is a refusal no disbursement could answer (the Phase 11 gate's R5)")
    void theExhaustionBound() {
        assertThat(judge(scheduleTerms("0"),
                new Bounds(eur("0.05"), eur("100.00"), 1, 6))).hasValueSatisfying(refusal -> {
                    assertThat(refusal.reason()).isEqualTo(ScheduleRefusal.NOT_AMORTISING);
                    assertThat(refusal.detail()).contains("whole balance");
                });
        assertThat(ScheduleEngineV1.project(scheduleTerms("0"), eur("0.05"), 6, 10, LocalDate.parse("2026-01-01")))
                .as("the loan it protects against").isInstanceOf(ScheduleOutcome.Refused.class);
        assertThat(judge(scheduleTerms("0"),
                new Bounds(eur("1.00"), eur("100.00"), 1, 6)))
                .as("EUR 1.00 over six months: A* = 16.67 cents, above 5/2 x T(5) x g(6) = 12.5").isEmpty();
    }

    static ScheduleTerms oneDayFirstPeriod(String rate) {
        return scheduleTerms(rate, 1);
    }

    // ------------------------------------------------------------------ plumbing

    static Schedule projected(ScheduleTerms terms, String principal, int term, int day, String start) {
        ScheduleOutcome outcome = ScheduleEngineV1.project(terms, eur(principal), term, day, LocalDate.parse(start));
        assertThat(outcome).isInstanceOf(ScheduleOutcome.Projected.class);
        return ((ScheduleOutcome.Projected) outcome).schedule();
    }

    private static String firstDue(int day, String start) {
        return projected(TEN_PERCENT, "1000.00", 6, day, start).periods().get(0).dueDate().toString();
    }

    static List<String> rows(Schedule schedule) {
        List<String> rows = new ArrayList<>();
        for (SchedulePeriod period : schedule.periods()) {
            rows.add(period.sequence() + "|" + period.periodStart() + "|" + period.dueDate() + "|" + period.days() + "|"
                    + plain(period.openingBalance()) + "|" + plain(period.interest()) + "|" + plain(period.principal())
                    + "|" + plain(period.amount()) + "|" + plain(period.closingBalance()));
        }
        return rows;
    }

    static String plain(Money money) {
        return money.toBigDecimal().toPlainString();
    }

    static Money eur(String amount) {
        return Money.of(new BigDecimal(amount), EUR);
    }
}
