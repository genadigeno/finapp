package com.finapp.lending;

import static com.finapp.lending.LoanTermsFixtures.EUR;
import static com.finapp.lending.LoanTermsFixtures.creditLine;
import static com.finapp.lending.LoanTermsFixtures.personalLoan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.creditmath.AccrualEngineV1;
import com.finapp.creditmath.AccrualPeriod;
import com.finapp.creditmath.EngineVersion;
import com.finapp.creditmath.InterestAccrual;
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
 * {@code STATEMENT_ENGINE_V1}'s golden worked cases and its refusals (`P11-TSK-009`; ADR-0100 point 6, A23, A24,
 * {@code PHASE_11_PLAN.md} section 12.6; {@code INV-LND-09}, {@code INV-MON-01}, the line's analogue of
 * {@code INV-LND-03}).
 *
 * <p><strong>Every figure here is worked by hand</strong>, under the plan's defaults - a EUR 25.00 floor, 3 %, payment due
 * 25 days after the statement. A cycle's interest is its exact {@code ACT_365F} interest rounded once {@code HALF_EVEN}
 * (the accruals are computed by {@code ACCRUAL_ENGINE_V1}, and their postings telescope to that); then
 * {@code principal = min(N, max(UP(3 % x D), 25.00 - I - F, 0))} and {@code minimum = I + F + principal}.
 */
@DisplayName("statement engine v1: golden worked cases and typed refusals (P11-TSK-009)")
class StatementEngineV1Test {

    private static final LoanTerms TWELVE_PERCENT = creditLine("0.12").build();

    /** Statement day 15 - the cycles run from the 15th to the 15th. */
    private static final StatementTerms DAY_15 = StatementTerms.of(TWELVE_PERCENT, 15);

    private static final AccrualPeriod FEBRUARY_2027 = cycle("2027-02-15", "2027-03-15");

    private static final AccrualPeriod MARCH_2027 = cycle("2027-03-15", "2027-04-15");

    // ------------------------------------------------------------------ the cycle

    @Test
    @DisplayName("the first cycle runs from the opening to the first statement date after it; each later one a month")
    void cyclesFromTheOpening() {
        LocalDate opened = date("2027-01-03");
        assertThat(StatementEngineV1.cycle(DAY_15, opened, opened)).isEqualTo(cycle("2027-01-03", "2027-01-15"));
        assertThat(StatementEngineV1.cycle(DAY_15, opened, date("2027-01-14"))).isEqualTo(cycle("2027-01-03", "2027-01-15"));
        assertThat(StatementEngineV1.cycle(DAY_15, opened, date("2027-01-15"))).isEqualTo(cycle("2027-01-15", "2027-02-15"));
        assertThat(StatementEngineV1.cycle(DAY_15, opened, date("2027-02-20"))).isEqualTo(FEBRUARY_2027);
        // Opened on the statement day: the first statement is a month later, never the same day.
        assertThat(StatementEngineV1.cycle(DAY_15, date("2027-01-15"), date("2027-01-15")))
                .isEqualTo(cycle("2027-01-15", "2027-02-15"));
        // Opened after the statement day: the first cycle runs into the next month, across a year end too.
        assertThat(StatementEngineV1.cycle(DAY_15, date("2027-01-20"), date("2027-02-14")))
                .isEqualTo(cycle("2027-01-20", "2027-02-15"));
        StatementTerms day5 = StatementTerms.of(TWELVE_PERCENT, 5);
        assertThat(StatementEngineV1.cycle(day5, date("2027-12-20"), date("2027-12-31")))
                .isEqualTo(cycle("2027-12-20", "2028-01-05"));
    }

    @Test
    @DisplayName("a statement day of 28 in February: 28 February is a statement date, its cycles 31 and 28 (29 in 2028) days")
    void statementDay28InFebruary() {
        StatementTerms day28 = StatementTerms.of(TWELVE_PERCENT, 28);
        LocalDate opened = date("2027-01-10");
        AccrualPeriod january = StatementEngineV1.cycle(day28, opened, date("2027-02-15"));
        assertThat(january).isEqualTo(cycle("2027-01-28", "2027-02-28"));
        assertThat(january.days()).isEqualTo(31);
        AccrualPeriod february = StatementEngineV1.cycle(day28, opened, date("2027-02-28"));
        assertThat(february).isEqualTo(cycle("2027-02-28", "2027-03-28"));
        assertThat(february.days()).isEqualTo(28);
        assertThat(StatementEngineV1.cycle(day28, opened, date("2028-02-29"))).isEqualTo(cycle("2028-02-28", "2028-03-28"));
        assertThat(StatementEngineV1.cycle(day28, opened, date("2028-02-29")).days()).isEqualTo(29);

        // EUR 1,000.00 at 12 % over 31 days: 1000 x 0.12 x 31 / 365 = 10.19178... = 10.19; 3 % of 1,000.00 = 30.00
        // above 25.00 - 10.19 = 14.81, so the principal part is 30.00 and the minimum 40.19, due 25 days later.
        Statement statement = StatementEngineV1.state(day28, january, accrued(january, "1000.00"), eur("0.00"),
                eur("1000.00"), eur("0.00"));
        assertThat(figures(statement)).isEqualTo("2027-01-28|2027-02-28|10.19|0.00|30.00|40.19|2027-03-25");
        // The 28-day cycle: 1000 x 0.12 x 28 / 365 = 9.20547... = 9.21, due 22 April.
        statement = StatementEngineV1.state(day28, february, accrued(february, "1000.00"), eur("0.00"),
                eur("1000.00"), eur("0.00"));
        assertThat(figures(statement)).isEqualTo("2027-02-28|2027-03-28|9.21|0.00|30.00|39.21|2027-04-22");
    }

    // ------------------------------------------------------------------ the minimum payment (A24)

    @Test
    @DisplayName("the floor dominating: 500.00 drawn - the principal part tops the minimum up to exactly 25.00")
    void theFloorDominates() {
        // 500 x 0.12 x 28 / 365 = 4.60273... = 4.60; 3 % = 15.00 below 25.00 - 4.60 = 20.40.
        Statement statement = StatementEngineV1.state(DAY_15, FEBRUARY_2027, accrued(FEBRUARY_2027, "500.00"),
                eur("0.00"), eur("500.00"), eur("0.00"));
        assertThat(figures(statement)).isEqualTo("2027-02-15|2027-03-15|4.60|0.00|20.40|25.00|2027-04-09");
        assertThat(statement.statementDate()).isEqualTo(date("2027-03-15"));
        assertThat(statement.engine()).isEqualTo(EngineVersion.STATEMENT_ENGINE_V1);
    }

    @Test
    @DisplayName("the ratio dominating: 3 % of 1,500.10 is 45.003, rounded UP to 45.01 - never down")
    void theRatioDominates() {
        // 1500.10 x 0.12 x 31 / 365 = 15.28869... = 15.29; 25.00 - 15.29 = 9.71 below 45.01.
        Statement statement = StatementEngineV1.state(DAY_15, MARCH_2027, accrued(MARCH_2027, "1500.10"), eur("0.00"),
                eur("1500.10"), eur("0.00"));
        assertThat(figures(statement)).isEqualTo("2027-03-15|2027-04-15|15.29|0.00|45.01|60.30|2027-05-10");
    }

    @Test
    @DisplayName("interest and fees above the floor: the floor adds nothing, the principal part is the ratio's")
    void interestAndFeesAboveTheFloor() {
        LoanTerms eighteen = creditLine("0.18").build();
        // 2000 x 0.18 x 31 / 365 = 30.57534... = 30.58; with a 15.00 fee 45.58 is above 25.00; 3 % = 60.00.
        Statement statement = StatementEngineV1.state(StatementTerms.of(eighteen, 15), MARCH_2027,
                accrued(eighteen, MARCH_2027, "2000.00"), eur("15.00"), eur("2000.00"), eur("0.00"));
        assertThat(figures(statement)).isEqualTo("2027-03-15|2027-04-15|30.58|15.00|60.00|105.58|2027-05-10");
        // 100 x 0.12 x 31 / 365 = 1.01917... = 1.02; with 30.00 of fees the floor is passed, and the principal part
        // is 3 % of 100.00 = 3.00 - not raised to anything.
        statement = StatementEngineV1.state(DAY_15, MARCH_2027, accrued(MARCH_2027, "100.00"), eur("30.00"),
                eur("100.00"), eur("0.00"));
        assertThat(figures(statement)).isEqualTo("2027-03-15|2027-04-15|1.02|30.00|3.00|34.02|2027-05-10");
    }

    @Test
    @DisplayName("drawn principal below the floor: the principal part is capped at it - the minimum the whole balance")
    void drawnPrincipalBelowTheFloorIsCapped() {
        // 10 x 0.12 x 31 / 365 = 0.10191... = 0.10; 25.00 - 0.10 = 24.90 above the 10.00 drawn: capped at 10.00, so
        // the minimum is 10.10 - everything the statement can bill, never 25.00.
        Statement statement = StatementEngineV1.state(DAY_15, MARCH_2027, accrued(MARCH_2027, "10.00"), eur("0.00"),
                eur("10.00"), eur("0.00"));
        assertThat(statement.principalBilled()).isEqualTo(eur("10.00"));
        assertThat(statement.minimumPayment()).isEqualTo(eur("10.10"));
        assertThat(figures(statement)).isEqualTo("2027-03-15|2027-04-15|0.10|0.00|10.00|10.10|2027-05-10");
    }

    @Test
    @DisplayName("an empty cycle - nothing drawn, nothing accrued - is an empty statement: every figure zero")
    void anEmptyCycle() {
        Statement statement = StatementEngineV1.state(DAY_15, MARCH_2027, List.of(), eur("0.00"), eur("0.00"),
                eur("0.00"));
        assertThat(figures(statement)).isEqualTo("2027-03-15|2027-04-15|0.00|0.00|0.00|0.00|2027-05-10");
    }

    @Test
    @DisplayName("repaid in the cycle: three days accrued, nothing drawn at its end - the minimum is the interest alone")
    void repaidWithinTheCycle() {
        // 1000 x 0.12 x 3 / 365 = 0.98630... = 0.99; N = 0, so no principal part: 0.99, below the floor - the balance.
        List<InterestAccrual> accruals = accrued(TWELVE_PERCENT, MARCH_2027, List.of(
                Optional.of(eur("1000.00")), Optional.of(eur("1000.00")), Optional.of(eur("1000.00"))));
        Statement statement = StatementEngineV1.state(DAY_15, MARCH_2027, accruals, eur("0.00"), eur("0.00"),
                eur("0.00"));
        assertThat(figures(statement)).isEqualTo("2027-03-15|2027-04-15|0.99|0.00|0.00|0.99|2027-05-10");
    }

    @Test
    @DisplayName("the ratio is of the whole drawn principal, due and not yet due; the cap is principal not yet due")
    void theRatioIsOfTheDrawnPrincipal() {
        // D = 900.00 + 100.00 already due: 3 % of 1,000.00 = 30.00 above the floor's 25.00.
        Statement statement = StatementEngineV1.state(DAY_15, MARCH_2027, List.of(), eur("0.00"), eur("900.00"),
                eur("100.00"));
        assertThat(statement.principalBilled()).isEqualTo(eur("30.00"));
        // N = 20.00 of D = 1,000.00: the 30.00 is capped at the 20.00 not yet due.
        statement = StatementEngineV1.state(DAY_15, MARCH_2027, List.of(), eur("0.00"), eur("20.00"), eur("980.00"));
        assertThat(statement.principalBilled()).isEqualTo(eur("20.00"));
        assertThat(statement.minimumPayment()).isEqualTo(eur("20.00"));
    }

    @Test
    @DisplayName("the interest billed is the cycle's posted accruals summed - its exact interest rounded once")
    void theInterestIsTheCyclesAccrualsSummed() {
        List<Optional<Money>> bases = new ArrayList<>();
        BigDecimal exact = BigDecimal.ZERO;
        for (int day = 0; day < MARCH_2027.days(); day++) {
            Optional<Money> base = day % 7 == 3 ? Optional.empty() : Optional.of(eur("1234.56").plus(eur("17.01").times(day)));
            bases.add(base);
            exact = exact.add(base.map(Money::toBigDecimal).orElse(BigDecimal.ZERO).multiply(new BigDecimal("0.12")));
        }
        List<InterestAccrual> accruals = accrued(TWELVE_PERCENT, MARCH_2027, bases);
        Money posted = eur("0.00");
        for (InterestAccrual accrual : accruals) {
            posted = posted.plus(accrual.posted());
        }
        Statement statement = StatementEngineV1.state(DAY_15, MARCH_2027, accruals, eur("0.00"), eur("1744.86"),
                eur("0.00"));
        assertThat(statement.interestBilled()).isEqualTo(posted);
        assertThat(statement.interestBilled().toBigDecimal())
                .isEqualTo(exact.divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_EVEN));
    }

    @Test
    @DisplayName("a statement whose minimum is not its parts, or whose payment falls due on its date, cannot exist")
    void aStatementThatDoesNotAddUpCannotExist() {
        assertThatThrownBy(() -> new Statement(MARCH_2027, eur("1.00"), eur("0.00"), eur("24.00"), eur("25.01"),
                date("2027-05-10"), EngineVersion.STATEMENT_ENGINE_V1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Statement(MARCH_2027, eur("1.00"), eur("0.00"), eur("-1.00"), eur("0.00"),
                date("2027-05-10"), EngineVersion.STATEMENT_ENGINE_V1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Statement(MARCH_2027, eur("1.00"), eur("0.00"), eur("24.00"), eur("25.00"),
                date("2027-04-15"), EngineVersion.STATEMENT_ENGINE_V1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Statement(MARCH_2027, eur("1.00"), eur("0.00"), eur("24.00"), eur("25.00"),
                date("2027-05-10"), EngineVersion.ACCRUAL_ENGINE_V1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Statement(MARCH_2027, eur("1.00"), eur("0.00"), eur("24.00"), eur("25.00"), date("2027-05-10"),
                EngineVersion.STATEMENT_ENGINE_V1)).hasToString("Statement[2027-04-15, STATEMENT_ENGINE_V1]");
    }

    // ------------------------------------------------------------------ the refusals

    @Test
    @DisplayName("terms naming what version 1 does not compute are refused, never computed by another rule")
    void unimplementedConventions() {
        assertRefused(() -> StatementTerms.of(personalLoan("0.10").build(), 15),
                StatementRefused.Reason.UNIMPLEMENTED_CONVENTION);
        assertRefused(() -> StatementEngineV1.cycle(terms(15, "25.00", 300, 25, RoundingMode.UP,
                EngineVersion.SCHEDULE_ENGINE_V1), date("2027-01-01"), date("2027-01-01")),
                StatementRefused.Reason.UNIMPLEMENTED_CONVENTION);
        assertRefused(() -> StatementEngineV1.state(terms(15, "25.00", 300, 25, RoundingMode.HALF_EVEN,
                EngineVersion.STATEMENT_ENGINE_V1), MARCH_2027, List.of(), eur("0.00"), eur("0.00"), eur("0.00")),
                StatementRefused.Reason.UNIMPLEMENTED_CONVENTION);
    }

    @Test
    @DisplayName("terms outside the bounds the arithmetic rests on are refused")
    void termsOutOfBounds() {
        for (StatementTerms terms : List.of(
                terms(0, "25.00", 300, 25, RoundingMode.UP, EngineVersion.STATEMENT_ENGINE_V1),
                terms(29, "25.00", 300, 25, RoundingMode.UP, EngineVersion.STATEMENT_ENGINE_V1),
                terms(15, "0.00", 300, 25, RoundingMode.UP, EngineVersion.STATEMENT_ENGINE_V1),
                terms(15, "25.00", 0, 25, RoundingMode.UP, EngineVersion.STATEMENT_ENGINE_V1),
                terms(15, "25.00", 10_001, 25, RoundingMode.UP, EngineVersion.STATEMENT_ENGINE_V1),
                terms(15, "25.00", 300, 0, RoundingMode.UP, EngineVersion.STATEMENT_ENGINE_V1),
                terms(15, "25.00", 300, 28, RoundingMode.UP, EngineVersion.STATEMENT_ENGINE_V1),
                new StatementTerms(EUR, 15, Money.ofMinorUnits(25_00, CurrencyCode.of("USD")), 300, 25, RoundingMode.UP,
                        EngineVersion.STATEMENT_ENGINE_V1))) {
            assertRefused(() -> StatementEngineV1.state(terms, MARCH_2027, List.of(), eur("0.00"), eur("0.00"),
                    eur("0.00")), StatementRefused.Reason.TERMS_OUT_OF_BOUNDS);
        }
    }

    @Test
    @DisplayName("a cycle that is not one of the statement day's, or a date before the line opened, is refused")
    void cycleInvalid() {
        assertRefused(() -> StatementEngineV1.cycle(DAY_15, date("2027-02-01"), date("2027-01-31")),
                StatementRefused.Reason.CYCLE_INVALID);
        assertRefused(() -> StatementEngineV1.cycle(DAY_15, date("9901-01-01"), date("9901-01-01")),
                StatementRefused.Reason.CYCLE_INVALID);
        for (AccrualPeriod cycle : List.of(cycle("2027-03-15", "2027-04-16"), cycle("2027-03-14", "2027-04-15"),
                cycle("2027-02-15", "2027-04-15"))) {
            assertRefused(() -> StatementEngineV1.state(DAY_15, cycle, List.of(), eur("0.00"), eur("0.00"), eur("0.00")),
                    StatementRefused.Reason.CYCLE_INVALID);
        }
    }

    @Test
    @DisplayName("an amount below zero, in another currency or scale, or beyond the engine's range, is refused")
    void amountInvalid() {
        Money usd = Money.ofMinorUnits(100, CurrencyCode.of("USD"));
        Money beyond = Money.ofMinorUnits(StatementEngineV1.MAXIMUM_MINOR_UNITS + 1, EUR);
        Money wrongScale = Money.ofPersisted(100, EUR, 3);
        for (Money bad : List.of(eur("-0.01"), usd, beyond, wrongScale)) {
            assertRefused(() -> StatementEngineV1.state(DAY_15, MARCH_2027, List.of(), bad, eur("0.00"), eur("0.00")),
                    StatementRefused.Reason.AMOUNT_INVALID);
            assertRefused(() -> StatementEngineV1.state(DAY_15, MARCH_2027, List.of(), eur("0.00"), bad, eur("0.00")),
                    StatementRefused.Reason.AMOUNT_INVALID);
            assertRefused(() -> StatementEngineV1.state(DAY_15, MARCH_2027, List.of(), eur("0.00"), eur("0.00"), bad),
                    StatementRefused.Reason.AMOUNT_INVALID);
        }
    }

    @Test
    @DisplayName("accruals that are not one chain of the cycle's own are refused: foreign, repeated, reordered, a row dropped")
    void accrualsInvalid() {
        List<InterestAccrual> march = accrued(MARCH_2027, "1000.00");
        List<InterestAccrual> february = accrued(FEBRUARY_2027, "1000.00");
        List<InterestAccrual> repeated = new ArrayList<>(march);
        repeated.add(march.get(march.size() - 1));
        List<InterestAccrual> reordered = new ArrayList<>(march);
        java.util.Collections.swap(reordered, 0, 1);
        List<InterestAccrual> dropped = new ArrayList<>(march);
        dropped.remove(10);
        InterestAccrual dollars = new InterestAccrual(date("2027-03-15"), MARCH_2027,
                Money.ofMinorUnits(100_000, CurrencyCode.of("USD")), new BigDecimal("0.32876712328767123288"),
                Money.ofMinorUnits(33, CurrencyCode.of("USD")), EngineVersion.ACCRUAL_ENGINE_V1);
        List<InterestAccrual> beyond = accrued(TWELVE_PERCENT, MARCH_2027,
                List.of(Optional.of(Money.ofMinorUnits(StatementEngineV1.MAXIMUM_MINOR_UNITS + 1, EUR))));
        for (List<InterestAccrual> bad : List.of(february, repeated, reordered, dropped, List.of(dollars), beyond)) {
            assertRefused(() -> StatementEngineV1.state(DAY_15, MARCH_2027, bad, eur("0.00"), eur("1000.00"),
                    eur("0.00")), StatementRefused.Reason.ACCRUALS_INVALID);
        }
        // A day with no accrual - the line at zero principal - is not a dropped row: its chain still telescopes.
        List<Optional<Money>> gap = new ArrayList<>();
        for (int day = 0; day < MARCH_2027.days(); day++) {
            gap.add(day == 10 ? Optional.empty() : Optional.of(eur("1000.00")));
        }
        assertThat(StatementEngineV1.state(DAY_15, MARCH_2027, accrued(TWELVE_PERCENT, MARCH_2027, gap), eur("0.00"),
                eur("1000.00"), eur("0.00")).interestBilled()).isEqualTo(eur("9.86"));
    }

    @Test
    @DisplayName("the terms and the statement print the engine and the date only - never an amount or a ratio")
    void nothingFinancialPrinted() {
        assertThat(DAY_15).hasToString("StatementTerms[STATEMENT_ENGINE_V1]");
        Statement statement = StatementEngineV1.state(DAY_15, MARCH_2027, accrued(MARCH_2027, "1500.10"), eur("0.00"),
                eur("1500.10"), eur("0.00"));
        assertThat(statement.toString()).doesNotContain("15.29", "45.01", "60.30", "1500");
    }

    // ------------------------------------------------------------------ helpers

    static Money eur(String amount) {
        return Money.of(new BigDecimal(amount), EUR);
    }

    static LocalDate date(String iso) {
        return LocalDate.parse(iso);
    }

    static AccrualPeriod cycle(String start, String endExclusive) {
        return new AccrualPeriod(date(start), date(endExclusive));
    }

    /** {@code cycle | interest | fees | principal | minimum | due date}. */
    static String figures(Statement statement) {
        return statement.cycle().start() + "|" + statement.cycle().endExclusive() + "|"
                + statement.interestBilled().toBigDecimal().toPlainString() + "|"
                + statement.feesCarried().toBigDecimal().toPlainString() + "|"
                + statement.principalBilled().toBigDecimal().toPlainString() + "|"
                + statement.minimumPayment().toBigDecimal().toPlainString() + "|" + statement.dueDate();
    }

    private static StatementTerms terms(
            int day, String floor, int ratioBps, int dueDays, RoundingMode rounding, EngineVersion engine) {
        return new StatementTerms(EUR, day, eur(floor), ratioBps, dueDays, rounding, engine);
    }

    /** Every date of {@code cycle} accrued at 12 % on a constant {@code base}. */
    private static List<InterestAccrual> accrued(AccrualPeriod cycle, String base) {
        return accrued(TWELVE_PERCENT, cycle, base);
    }

    private static List<InterestAccrual> accrued(LoanTerms terms, AccrualPeriod cycle, String base) {
        List<Optional<Money>> bases = new ArrayList<>();
        for (long day = 0; day < cycle.days(); day++) {
            bases.add(Optional.of(eur(base)));
        }
        return accrued(terms, cycle, bases);
    }

    /** The cycle's accruals from its first date, one base a date - empty for a date with no accrual. */
    static List<InterestAccrual> accrued(LoanTerms terms, AccrualPeriod cycle, List<Optional<Money>> bases) {
        List<InterestAccrual> accruals = new ArrayList<>();
        Optional<InterestAccrual> previous = Optional.empty();
        LocalDate date = cycle.start();
        for (Optional<Money> base : bases) {
            if (base.isPresent()) {
                InterestAccrual accrual = AccrualEngineV1.accrue(LoanEngines.accrualTerms(terms), cycle, previous, date, base.get());
                accruals.add(accrual);
                previous = Optional.of(accrual);
            }
            date = date.plusDays(1);
        }
        return accruals;
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            StatementRefused.Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(StatementRefused.class,
                refused -> assertThat(refused.reason()).isEqualTo(reason));
    }
}
