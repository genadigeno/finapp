package com.finapp.lending;

import static com.finapp.lending.LoanTermsFixtures.EUR;
import static com.finapp.lending.LoanTermsFixtures.creditLine;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.creditmath.AccrualPeriod;
import com.finapp.creditmath.EngineVersion;
import com.finapp.creditmath.InterestAccrual;
import com.finapp.creditmath.testing.SecondJvm;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code STATEMENT_ENGINE_V1}'s properties over generated lines (`P11-TSK-009`; A23, A24, {@code INV-LND-09},
 * {@code INV-MON-01}, the line's analogue of {@code INV-LND-03}): under every floor, ratio, rate, statement day and
 * cycle, from nothing drawn through far beyond the floor, the minimum payment never exceeds what the statement can bill
 * and is at least the floor or all of it; the principal part never exceeds the drawn principal not yet due; every
 * figure equals A24's formula worked independently in integer minor units, and the interest the cycle's exact interest
 * rounded once; the cycles tile the calendar from the opening, every statement day by every opening date of a leap and
 * a common year; and the statements are a function of their inputs alone - the same in a second JVM with other
 * defaults. Seeded, so a failure reproduces.
 *
 * <p>Named {@code ...PropertiesTest}, not the backlog's {@code StatementEngineV1Properties}: every executed test class
 * ends in {@code Test} ({@code TestTaxonomyTest}; the {@code AllocationEngineV1PropertiesTest} precedent).
 */
@DisplayName("STATEMENT_ENGINE_V1's properties (P11-TSK-009)")
class StatementEngineV1PropertiesTest {

    static final int CASES = 3_000;
    static final long SEED = 11_009L;

    @Test
    @DisplayName("the minimum payment never exceeds what the statement can bill: interest + fees + principal not yet due")
    void theMinimumNeverExceedsWhatIsOwed() {
        for (Case c : cases(SEED, CASES)) {
            Statement statement = c.state();
            assertThat(statement.minimumPayment()).as(c.toString()).isLessThanOrEqualTo(c.billable(statement));
        }
    }

    @Test
    @DisplayName("the minimum payment is at least the floor - or everything the statement can bill, when that is less")
    void theMinimumIsAtLeastTheFloorOrTheBalance() {
        for (Case c : cases(SEED + 1, CASES)) {
            Statement statement = c.state();
            Money billable = c.billable(statement);
            Money least = billable.compareTo(c.terms.minimumPaymentFloor()) <= 0 ? billable : c.terms.minimumPaymentFloor();
            assertThat(statement.minimumPayment()).as(c.toString()).isGreaterThanOrEqualTo(least);
        }
    }

    @Test
    @DisplayName("the principal billed never exceeds the drawn principal not yet due, and is never negative")
    void thePrincipalPartNeverExceedsThePrincipalNotYetDue() {
        for (Case c : cases(SEED + 2, CASES)) {
            Statement statement = c.state();
            assertThat(statement.principalBilled()).as(c.toString()).isLessThanOrEqualTo(c.notDue);
            assertThat(statement.principalBilled().isNegative()).as(c.toString()).isFalse();
        }
    }

    @Test
    @DisplayName("every figure is A24's, worked independently: the interest rounded once, the ratio UP, then min/max")
    void everyFigureFollowsTheRule() {
        for (Case c : cases(SEED + 3, CASES)) {
            Statement statement = c.state();
            // The cycle's exact interest from its bases: sum(base x r) / 365, one division, rounded once HALF_EVEN.
            BigDecimal exact = BigDecimal.ZERO;
            for (InterestAccrual accrual : c.accruals) {
                exact = exact.add(accrual.base().toBigDecimal().multiply(c.rate));
            }
            long interest = exact.divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_EVEN).unscaledValue().longValueExact();
            long fees = c.fees.minorUnits();
            long notDue = c.notDue.minorUnits();
            long drawn = notDue + c.due.minorUnits();
            long ratio = Math.ceilDiv(drawn * c.terms.minimumPaymentRatioBps(), 10_000L);
            long principal = Math.min(notDue, Math.max(Math.max(ratio, c.terms.minimumPaymentFloor().minorUnits()
                    - interest - fees), 0));
            String expected = c.cycle.start() + "|" + c.cycle.endExclusive() + "|" + interest + "|" + fees + "|"
                    + principal + "|" + (interest + fees + principal) + "|"
                    + c.cycle.endExclusive().plusDays(c.terms.paymentDueDays()) + "|STATEMENT_ENGINE_V1";
            assertThat(rendered(statement)).as(c.toString()).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("the generator reaches every regime: the floor, the ratio, interest and fees above the floor, the cap, empty")
    void theGeneratorReachesEveryRegime() {
        int floor = 0;
        int ratio = 0;
        int aboveTheFloor = 0;
        int capped = 0;
        int empty = 0;
        for (Case c : cases(SEED + 3, CASES)) {
            Statement statement = c.state();
            Money charges = statement.interestBilled().plus(c.fees);
            long ratioPart = Math.ceilDiv((c.notDue.minorUnits() + c.due.minorUnits()) * c.terms.minimumPaymentRatioBps(),
                    10_000L);
            long floorPart = c.terms.minimumPaymentFloor().minorUnits() - charges.minorUnits();
            long principal = statement.principalBilled().minorUnits();
            if (statement.minimumPayment().isZero()) {
                empty++;
            } else if (principal == c.notDue.minorUnits() && principal < Math.max(ratioPart, floorPart)) {
                capped++;
            } else if (floorPart <= 0) {
                aboveTheFloor++;
            } else if (floorPart > ratioPart) {
                floor++;
            } else {
                ratio++;
            }
        }
        assertThat(List.of(floor, ratio, aboveTheFloor, capped, empty)).as("floor, ratio, above, capped, empty")
                .allSatisfy(count -> assertThat(count).isGreaterThanOrEqualTo(100));
    }

    @Test
    @DisplayName("the cycles tile the calendar: every statement day, every opening date of 2027 and 2028, fourteen cycles")
    void theCyclesTileTheCalendar() {
        int lines = 0;
        for (int day = 1; day <= LoanTerms.LAST_ALLOWED_DAY; day++) {
            StatementTerms terms = StatementTerms.of(creditLine("0.12").build(), day);
            for (LocalDate opened = LocalDate.of(2027, 1, 1); opened.getYear() < 2029; opened = opened.plusDays(1)) {
                AccrualPeriod cycle = StatementEngineV1.cycle(terms, opened, opened);
                assertThat(cycle.start()).isEqualTo(opened);
                assertThat(cycle.days()).as(opened + " day " + day).isBetween(1L, 31L);
                for (int k = 0; k < 14; k++) {
                    String label = opened + " day " + day + " cycle " + k;
                    assertThat(cycle.endExclusive().getDayOfMonth()).as(label).isEqualTo(day);
                    assertThat(cycle.start().isBefore(cycle.endExclusive().minusMonths(1))).as(label).isFalse();
                    assertThat(StatementEngineV1.cycle(terms, opened, cycle.endExclusive().minusDays(1))).as(label)
                            .isEqualTo(cycle);
                    AccrualPeriod next = StatementEngineV1.cycle(terms, opened, cycle.endExclusive());
                    assertThat(next.start()).as(label).isEqualTo(cycle.endExclusive());
                    assertThat(next.days()).as(label).isEqualTo(YearMonth.from(next.start()).lengthOfMonth());
                    cycle = next;
                }
                lines++;
            }
        }
        assertThat(lines).isEqualTo(28 * 731);
    }

    @Test
    @DisplayName("a second JVM - another default charset, locale and time zone, other hash codes - states identically")
    void aSecondJvmStatesIdentically() throws Exception {
        List<String> theirs = SecondJvm.run(InAnotherJvm.class);
        List<String> ours = renderedAll(cases(SEED + 4, CASES));
        assertThat(theirs).hasSize(CASES);
        assertThat(theirs).isEqualTo(ours);
    }

    /** The second JVM: the same seeded cases, each statement rendered as one line. */
    static final class InAnotherJvm {
        public static void main(String[] arguments) throws Exception {
            SecondJvm.write(Path.of(arguments[0]), renderedAll(cases(SEED + 4, CASES)));
        }
    }

    static List<String> renderedAll(List<Case> cases) {
        List<String> lines = new ArrayList<>();
        for (Case c : cases) {
            lines.add(rendered(c.state()));
        }
        return lines;
    }

    /** Every figure of a statement, locale-free, in minor units. */
    static String rendered(Statement statement) {
        return statement.cycle().start() + "|" + statement.cycle().endExclusive() + "|"
                + statement.interestBilled().minorUnits() + "|" + statement.feesCarried().minorUnits() + "|"
                + statement.principalBilled().minorUnits() + "|" + statement.minimumPayment().minorUnits() + "|"
                + statement.dueDate() + "|" + statement.engine().name();
    }

    // ------------------------------------------------------------------ the generator

    /** One generated statement's inputs: terms, a cycle, its accruals, fees and the principal position. */
    record Case(StatementTerms terms, BigDecimal rate, AccrualPeriod cycle, List<InterestAccrual> accruals, Money fees,
            Money notDue, Money due) {

        Statement state() {
            return StatementEngineV1.state(terms, cycle, accruals, fees, notDue, due);
        }

        /** Everything the statement can bill: its interest and fees, and all principal not yet due. */
        Money billable(Statement statement) {
            return statement.interestBilled().plus(fees).plus(notDue);
        }

        @Override
        public String toString() {
            return "day " + terms.statementDay() + " floor " + terms.minimumPaymentFloor() + " ratio "
                    + terms.minimumPaymentRatioBps() + " rate " + rate + " cycle " + cycle.start() + ".."
                    + cycle.endExclusive() + " accruals " + accruals.size() + " fees " + fees + " notDue " + notDue
                    + " due " + due;
        }
    }

    /**
     * {@code count} cases from {@code seed}: a statement day 1-28 and an opening date across 2027 and 2028, one of the
     * line's first fourteen cycles; the plan's floor and ratio or others within the terms' bounds; a rate of up to 8
     * places; principal not yet due from nothing through below the floor to far beyond it, sometimes with principal
     * already due; daily bases varying around it, some dates with no accrual; and fees, mostly none.
     */
    static List<Case> cases(long seed, int count) {
        Random random = new Random(seed);
        List<Case> cases = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            int day = 1 + random.nextInt(LoanTerms.LAST_ALLOWED_DAY);
            Money floor = random.nextInt(3) == 0 ? Money.ofMinorUnits(1 + random.nextInt(10_000), EUR)
                    : Money.ofMinorUnits(25_00, EUR);
            int ratio = random.nextInt(3) == 0 ? 1 + random.nextInt(10_000) : 300;
            int dueDays = random.nextInt(4) == 0 ? 1 + random.nextInt(LoanTerms.LAST_ALLOWED_DAY - 1) : 25;
            BigDecimal rate = switch (random.nextInt(4)) {
                case 0 -> BigDecimal.ZERO;
                case 1 -> new BigDecimal("0.1999");
                default -> BigDecimal.valueOf(random.nextInt(100_000_001), 8);
            };
            LoanTerms loanTerms = creditLine(rate.toPlainString())
                    .revolving(Optional.of(new LoanTerms.RevolvingTerms(floor, ratio, dueDays,
                            EngineVersion.STATEMENT_ENGINE_V1)))
                    .build();
            StatementTerms terms = StatementTerms.of(loanTerms, day);
            LocalDate opened = LocalDate.of(2027, 1, 1).plusDays(random.nextInt(731));
            AccrualPeriod cycle = StatementEngineV1.cycle(terms, opened, opened);
            for (int k = random.nextInt(14); k > 0; k--) {
                cycle = StatementEngineV1.cycle(terms, opened, cycle.endExclusive());
            }
            long notDue = switch (random.nextInt(5)) {
                case 0 -> 0;
                case 1 -> 1 + random.nextInt(2_500);
                case 2 -> 2_500 + random.nextInt(100_000);
                default -> random.nextInt(10_000_001);
            };
            long due = random.nextInt(4) == 0 ? random.nextInt(50_001) : 0;
            List<Optional<Money>> bases = new ArrayList<>();
            for (long d = 0; d < cycle.days(); d++) {
                long drawn = notDue + due;
                bases.add(random.nextInt(10) == 0 || (drawn == 0 && random.nextBoolean()) ? Optional.empty()
                        : Optional.of(Money.ofMinorUnits(random.nextInt(4) == 0 ? random.nextInt(2 * (int) Math.min(drawn,
                                50_000_000L) + 1) : drawn, EUR)));
            }
            List<InterestAccrual> accruals = StatementEngineV1Test.accrued(loanTerms, cycle, bases);
            Money fees = random.nextInt(4) == 0 ? Money.ofMinorUnits(random.nextBoolean() ? 15_00L * (1 + random.nextInt(4))
                    : random.nextInt(10_001), EUR) : Money.zero(EUR);
            cases.add(new Case(terms, loanTerms.nominalAnnualRate(), cycle, accruals, fees,
                    Money.ofMinorUnits(notDue, EUR), Money.ofMinorUnits(due, EUR)));
        }
        return cases;
    }
}
