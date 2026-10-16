package com.finapp.creditmath;

import static com.finapp.creditmath.EngineTermsFixtures.EUR;
import static com.finapp.creditmath.EngineTermsFixtures.PERSONAL_LOAN_BOUNDS;
import static com.finapp.creditmath.EngineTermsFixtures.judge;
import static com.finapp.creditmath.EngineTermsFixtures.scheduleTerms;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.creditmath.testing.SecondJvm;
import com.finapp.creditmath.EngineTermsFixtures.Bounds;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code SCHEDULE_ENGINE_V1}'s properties over generated loans (`P11-TSK-006`; ADR-0093 section 11, {@code INV-LND-03},
 * {@code INV-LND-09}): conservation, the final instalment never above a regular one, positive principal portions, the
 * level instalment never below the exact actual-day annuity, the due-date rule with its clamp, monotone in rate, the
 * zero rate - and the same bytes from a second JVM with another charset, locale and time zone.
 *
 * <p><strong>The grid.</strong> Every disbursement day of a non-leap year (2027) and a leap year (2028) crossed with
 * every repayment day 1-28 - 20,468 loans - the term walking 6 to 60 so that every (repayment day, term) pair recurs and
 * every day meets 28 terms, the rate and principal walking the ladders below so that each meets every repayment day and
 * every term. Every figure is re-derived here by the
 * test's own arithmetic (the interest divided straight to the minor unit, the annuity summed as {@code P / sum v_j}),
 * never read back from the engine. The battery at scale is `P11-TST-002`'s.
 *
 * <p><strong>Moved with the engine</strong> (`P12-TSK-002`): from {@code lending}, every property and figure unedited, the
 * terms built through {@link ScheduleTerms} ({@link EngineTermsFixtures}) instead of lending's {@code LoanTerms}.
 */
@DisplayName("schedule engine v1: properties over every day of two years, two JVMs (P11-TSK-006)")
class ScheduleEngineV1PropertiesTest {

    static final List<String> RATES = List.of("0", "0.0001", "0.0499", "0.0899", "0.1249", "0.1999");
    static final List<String> PRINCIPALS = List.of("500.00", "1234.56", "9999.99", "25000.00");
    private static final BigDecimal YEAR = BigDecimal.valueOf(365);

    // ------------------------------------------------------------------ the grid

    @Test
    @DisplayName("Σ principal = P and Σ amount = P + Σ interest, the final instalment positive and never above a regular"
            + " one, every principal portion positive, A at least the exact annuity - every day of 2027 and 2028 x every"
            + " repayment day 1-28 x every term 6-60")
    void conservationAcrossTheGrid() {
        int loans = 0;
        int raised = 0;
        for (Case loan : grid()) {
            Schedule schedule = project(loan);
            assertConserves(loan, schedule);
            assertDueDates(loan, schedule);
            if (!schedule.instalment().equals(annuityUp(loan, schedule))) {
                raised++;
            }
            loans++;
        }
        assertThat(loans).isEqualTo(731 * 28);
        assertThat(raised).as("the rounding residue raises A only now and then (224 of 20,468)").isLessThan(loans / 50);
    }

    @Test
    @DisplayName("an intended 29th, 30th or 31st is clamped to each short month's last day and returns to the intended"
            + " day after it")
    void theClampForTheLongDays() {
        for (int day = 29; day <= 31; day++) {
            for (int offset = 0; offset < 366; offset += 3) {
                Case loan = new Case(scheduleTerms("0.0899"), eur("4000.00"), 13, day,
                        LocalDate.of(2028, 1, 1).plusDays(offset));
                Schedule schedule = project(loan);
                assertConserves(loan, schedule);
                assertDueDates(loan, schedule);
            }
        }
    }

    @Test
    @DisplayName("monotone in rate: a higher rate never lowers the level instalment nor the total interest")
    void monotoneInRate() {
        for (int sample = 0; sample < 400; sample++) {
            LocalDate start = LocalDate.of(2027, 1, 1).plusDays(sample * 7L % 731);
            int term = 6 + sample % 55;
            int day = 1 + sample % 28;
            Money principal = eur(PRINCIPALS.get(sample % PRINCIPALS.size()));
            Money previousInstalment = null;
            Money previousInterest = null;
            for (int basisPoints = 0; basisPoints <= 2000; basisPoints += 125) {
                Schedule schedule = project(new Case(scheduleTerms(BigDecimal.valueOf(basisPoints, 4).toPlainString()), principal, term, day, start));
                if (previousInstalment != null) {
                    assertThat(schedule.instalment()).isGreaterThanOrEqualTo(previousInstalment);
                    assertThat(schedule.totalInterest()).isGreaterThanOrEqualTo(previousInterest);
                }
                previousInstalment = schedule.instalment();
                previousInterest = schedule.totalInterest();
            }
        }
    }

    @Test
    @DisplayName("r = 0: no interest, the instalment P / n rounded UP, the final instalment the remainder")
    void theZeroRate() {
        for (Case loan : grid()) {
            if (loan.terms().nominalAnnualRate().signum() != 0) {
                continue;
            }
            Schedule schedule = project(loan);
            assertThat(schedule.totalInterest().isZero()).isTrue();
            BigDecimal share = loan.principal().toBigDecimal()
                    .divide(BigDecimal.valueOf(loan.term()), 2, RoundingMode.UP);
            assertThat(schedule.instalment().toBigDecimal()).isEqualByComparingTo(share);
        }
    }

    // ------------------------------------------------------------------ the door's judgement, against real loans

    @Test
    @DisplayName("the terms door is sound and near-tight: every real 6- and 60-month loan at the bounds' principals and at"
            + " the old door's gap (EUR 500.19), disbursed any day of a leap year, amortises at each rate it admits; at"
            + " 23.10 % - which it now refuses, by its margin - a real loan cannot")
    void theJudgementIsSoundAndTight() {
        Bounds bounds = PERSONAL_LOAN_BOUNDS;
        List<ScheduleTerms> admitted = new ArrayList<>();
        for (String rate : List.of("0.10", "0.18", "0.20", "0.22", "0.23", "0.2309")) {
            admitted.add(scheduleTerms(rate));
        }
        admitted.add(ScheduleEngineV1Test.oneDayFirstPeriod("0.5644"));
        Money gap = Money.ofMinorUnits(500_19, EUR);
        for (ScheduleTerms terms : admitted) {
            String rate = terms.nominalAnnualRate().toPlainString();
            assertThat(judge(terms, bounds)).as("admitted: " + rate).isEmpty();
            for (int offset = 0; offset < 366; offset++) {
                for (int day : List.of(1, 28)) {
                    for (int term : List.of(bounds.minimumTermMonths(), bounds.maximumTermMonths())) {
                        for (Money principal : List.of(bounds.minimumPrincipal(), gap, bounds.maximumPrincipal())) {
                            assertThat(ScheduleEngineV1.project(terms, principal, term, day,
                                    LocalDate.of(2028, 1, 1).plusDays(offset)))
                                    .as("an admitted rate amortises every loan: " + rate)
                                    .isInstanceOf(ScheduleOutcome.Projected.class);
                        }
                    }
                }
            }
        }
        ScheduleTerms refused = scheduleTerms("0.231");
        assertThat(judge(refused, bounds)).as("refused: 23.10 %").isPresent();
        assertThat(ScheduleEngineV1.project(refused, gap, bounds.maximumTermMonths(), 1, LocalDate.of(2028, 12, 18)))
                .as("the refusal is not merely conservative: a real EUR 500.19 loan at 23.10 % cannot amortise")
                .isInstanceOf(ScheduleOutcome.Refused.class);
    }

    // ------------------------------------------------------------------ two JVMs

    @Test
    @DisplayName("a second JVM - another default charset, locale and time zone, other identity hash codes - produces every"
            + " schedule byte for byte")
    void aSecondJvmProducesTheSameBytes() throws Exception {
        List<String> ours = Replay.digests();
        List<String> theirs = SecondJvm.run(Replay.class);
        assertThat(ours).as("the reference set").hasSize(Replay.SCHEDULES + 1);
        assertThat(theirs).as("schedules and periods, compared byte for byte").isEqualTo(ours);
    }

    /** The second JVM's {@code main}: one SHA-256 per schedule of a fixed slice of the grid, then the totals. */
    static final class Replay {

        static final int SCHEDULES = 2_048;

        public static void main(String[] arguments) throws Exception {
            SecondJvm.write(Path.of(arguments[0]), digests());
        }

        static List<String> digests() throws Exception {
            List<String> lines = new ArrayList<>();
            List<Case> grid = grid();
            long periods = 0;
            MessageDigest all = MessageDigest.getInstance("SHA-256");
            for (int index = 0; index < SCHEDULES; index++) {
                Case loan = grid.get(index * 10 % grid.size());
                Schedule schedule = project(loan);
                String text = canonical(schedule);
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                all.update(bytes);
                periods += schedule.periods().size();
                lines.add(index + " " + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            }
            lines.add("schedules=" + SCHEDULES + " periods=" + periods + " all=" + HexFormat.of().formatHex(all.digest()));
            return lines;
        }

        /** Every figure of the schedule, in one canonical text. */
        static String canonical(Schedule schedule) {
            StringBuilder text = new StringBuilder()
                    .append(schedule.engine()).append('|').append(schedule.currency().code()).append('|')
                    .append(schedule.principal().minorUnits()).append('|').append(schedule.nominalAnnualRate().toPlainString())
                    .append('|').append(schedule.start()).append('|').append(schedule.intendedDay()).append('|')
                    .append(schedule.instalment().minorUnits()).append('\n');
            for (SchedulePeriod period : schedule.periods()) {
                text.append(period.sequence()).append('|').append(period.periodStart()).append('|')
                        .append(period.dueDate()).append('|').append(period.days()).append('|')
                        .append(period.openingBalance().minorUnits()).append('|').append(period.interest().minorUnits())
                        .append('|').append(period.principal().minorUnits()).append('|')
                        .append(period.amount().minorUnits()).append('|').append(period.closingBalance().minorUnits())
                        .append('\n');
            }
            return text.toString();
        }
    }

    // ------------------------------------------------------------------ the oracles

    record Case(ScheduleTerms terms, Money principal, int term, int day, LocalDate start) {}

    /** Every day of 2027 and 2028 x every repayment day 1-28; term, rate and principal walking their ladders. */
    static List<Case> grid() {
        List<ScheduleTerms> terms = new ArrayList<>();
        for (String rate : RATES) {
            terms.add(scheduleTerms(rate));
        }
        List<Case> cases = new ArrayList<>(731 * 28);
        LocalDate first = LocalDate.of(2027, 1, 1);
        for (int offset = 0; offset < 731; offset++) {
            for (int day = 1; day <= 28; day++) {
                // The term walks with the cell (28 and 55 coprime: every repayment day meets every term ~13 times);
                // rate and principal walk with the day and the offset apart, so each meets every day, term and rate.
                int index = offset * 28 + (day - 1);
                cases.add(new Case(terms.get((offset * 5 + day) % terms.size()),
                        eur(PRINCIPALS.get((offset * 3 + day * 7) % PRINCIPALS.size())), 6 + index % 55, day,
                        first.plusDays(offset)));
            }
        }
        return cases;
    }

    private static Schedule project(Case loan) {
        ScheduleOutcome outcome =
                ScheduleEngineV1.project(loan.terms(), loan.principal(), loan.term(), loan.day(), loan.start());
        assertThat(outcome).as(loan.toString()).isInstanceOf(ScheduleOutcome.Projected.class);
        return ((ScheduleOutcome.Projected) outcome).schedule();
    }

    /** Conservation and every period re-derived by this test's own arithmetic. */
    private static void assertConserves(Case loan, Schedule schedule) {
        BigDecimal rate = loan.terms().nominalAnnualRate();
        BigDecimal principalSum = BigDecimal.ZERO;
        BigDecimal amountSum = BigDecimal.ZERO;
        BigDecimal interestSum = BigDecimal.ZERO;
        BigDecimal balance = loan.principal().toBigDecimal();
        BigDecimal instalment = schedule.instalment().toBigDecimal();
        List<SchedulePeriod> periods = schedule.periods();
        assertThat(periods).hasSize(loan.term());
        for (SchedulePeriod period : periods) {
            BigDecimal interest = balance.multiply(rate).multiply(BigDecimal.valueOf(period.days()))
                    .divide(YEAR, 2, RoundingMode.HALF_EVEN);
            assertThat(period.interest().toBigDecimal()).isEqualByComparingTo(interest);
            assertThat(period.principal().isPositive()).isTrue();
            principalSum = principalSum.add(period.principal().toBigDecimal());
            amountSum = amountSum.add(period.amount().toBigDecimal());
            interestSum = interestSum.add(interest);
            balance = balance.subtract(period.principal().toBigDecimal());
        }
        assertThat(principalSum).as("Σ principal = P").isEqualByComparingTo(loan.principal().toBigDecimal());
        assertThat(amountSum).as("Σ amount = P + Σ interest")
                .isEqualByComparingTo(loan.principal().toBigDecimal().add(interestSum));
        assertThat(balance.signum()).isZero();
        assertThat(schedule.finalInstalment().toBigDecimal()).as("the final instalment never exceeds a regular one")
                .isLessThanOrEqualTo(instalment).isPositive();
        // A >= A* exactly: A x sum_j 365^j x prod_{m>j} f_m >= P x prod_m f_m, summed as P / sum v_j.
        BigDecimal[] factors = new BigDecimal[periods.size()];
        for (int index = 0; index < factors.length; index++) {
            factors[index] = YEAR.add(rate.multiply(BigDecimal.valueOf(periods.get(index).days())));
        }
        BigDecimal product = BigDecimal.ONE;
        for (BigDecimal factor : factors) {
            product = product.multiply(factor);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int j = 1; j <= factors.length; j++) {
            BigDecimal term = YEAR.pow(j);
            for (int m = j; m < factors.length; m++) {
                term = term.multiply(factors[m]);
            }
            sum = sum.add(term);
        }
        BigDecimal exact = loan.principal().toBigDecimal().multiply(product);
        assertThat(instalment.multiply(sum)).as("A is never below the exact actual-day annuity").isGreaterThanOrEqualTo(exact);
        assertThat(instalment.subtract(new BigDecimal("0.10")).multiply(sum)).as("and only a few cents above it")
                .isLessThan(exact);
    }

    /** D1 the first intended day at least the shortest first period on; every Dj the intended day, clamped. */
    private static void assertDueDates(Case loan, Schedule schedule) {
        int shortest = loan.terms().minFirstPeriodDays();
        LocalDate earliest = loan.start().plusDays(shortest);
        LocalDate first = schedule.periods().get(0).dueDate();
        assertThat(first).isAfterOrEqualTo(earliest);
        assertThat(ChronoUnit.DAYS.between(earliest, first)).isLessThan(31);
        YearMonth month = YearMonth.from(first);
        for (SchedulePeriod period : schedule.periods()) {
            assertThat(period.dueDate()).isEqualTo(month.atDay(Math.min(loan.day(), month.lengthOfMonth())));
            if (period.sequence() > 1) {
                assertThat(period.days()).isBetween(28L, 31L);
            }
            month = month.plusMonths(1);
        }
        Optional<LocalDate> earlier = Optional.of(YearMonth.from(first).minusMonths(1))
                .map(previous -> previous.atDay(Math.min(loan.day(), previous.lengthOfMonth())));
        assertThat(earlier.get()).as("D1 is the FIRST such date").isBefore(earliest);
    }

    private static Money annuityUp(Case loan, Schedule schedule) {
        long[] days = schedule.periods().stream().mapToLong(SchedulePeriod::days).toArray();
        return ScheduleEngineV1.annuityUp(loan.principal(), loan.terms().nominalAnnualRate(), days);
    }

    static Money eur(String amount) {
        return Money.of(new BigDecimal(amount), EUR);
    }
}
