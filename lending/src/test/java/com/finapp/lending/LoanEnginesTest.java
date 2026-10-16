package com.finapp.lending;

import static com.finapp.lending.LoanTermsFixtures.EUR;
import static com.finapp.lending.LoanTermsFixtures.creditLine;
import static com.finapp.lending.LoanTermsFixtures.personalLoan;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.creditmath.AccrualEngineV1;
import com.finapp.creditmath.AccrualPeriod;
import com.finapp.creditmath.AccrualTerms;
import com.finapp.creditmath.AllocationComponent;
import com.finapp.creditmath.AllocationRules;
import com.finapp.creditmath.AllocationSequence;
import com.finapp.creditmath.DayCountConvention;
import com.finapp.creditmath.EngineVersion;
import com.finapp.creditmath.InterestAccrual;
import com.finapp.creditmath.OverpaymentTreatment;
import com.finapp.creditmath.Schedule;
import com.finapp.creditmath.ScheduleEngineV1;
import com.finapp.creditmath.ScheduleOutcome;
import com.finapp.creditmath.ScheduleRefusal;
import com.finapp.creditmath.ScheduleTerms;
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
 * Lending's side of the {@code creditmath} edge (`P12-TSK-002`): {@link LoanEngines} maps a {@link LoanTerms} onto the
 * engines' records exactly as the moved {@code AccrualTerms.of}, {@code AllocationRules.of} and {@code ScheduleEngineV1}
 * {@code LoanTerms} entries did, and delegates every figure.
 *
 * <ul>
 *   <li>the mappings are the declared values, field for field - the very values the moved engine suites now build
 *       through the records' canonical constructors ({@code creditmath}'s {@code EngineTermsFixtures});</li>
 *   <li>the cases of the moved suites that judged a lending product - a credit line's terms refused a schedule, both
 *       products' terms accruing alike, the terms accepting every allocation order - moved here, unedited but for the
 *       entry they call;</li>
 *   <li>every {@code LoanTerms} entry answers what the engine answers for the mapped terms, over every day of a leap year
 *       and the rates, days and terms the moved properties walk - so the path every Phase 11 agreement takes is the
 *       engine's path, and the battery ({@code AmortisationBatteryTest}) replays it at scale.</li>
 * </ul>
 */
@DisplayName("lending's LoanTerms entries onto the creditmath engines (P12-TSK-002)")
class LoanEnginesTest {

    // ------------------------------------------------------------------ the mappings

    @Test
    @DisplayName("an instalment product's schedule terms are exactly as declared: EUR, the rate, the 15-day shortest first"
            + " period, ACT_365F, UP, HALF_EVEN, SCHEDULE_ENGINE_V1 - what creditmath's suites build")
    void theScheduleTermsAreAsDeclared() {
        assertThat(LoanEngines.scheduleTerms(personalLoan("0.0899").build())).isEqualTo(new ScheduleTerms(EUR,
                new BigDecimal("0.0899"), 15, DayCountConvention.ACT_365F, RoundingMode.UP, RoundingMode.HALF_EVEN,
                EngineVersion.SCHEDULE_ENGINE_V1));
        assertThat(LoanEngines.scheduleTerms(personalLoan("0.10").build()).nominalAnnualRate())
                .as("the rate in LoanTerms' canonical spelling, as the fixtures spell it").isEqualTo(new BigDecimal("0.1"));
    }

    @Test
    @DisplayName("terms are read as declared - the accrual conventions, moved from AccrualEngineV1Test#termsAndPrinting")
    void theAccrualTermsAreAsDeclared() {
        LoanTerms loan = personalLoan("0.0899").build();
        AccrualTerms terms = LoanEngines.accrualTerms(loan);
        assertThat(terms).isEqualTo(new AccrualTerms(EUR, new BigDecimal("0.0899"), DayCountConvention.ACT_365F,
                RoundingMode.HALF_EVEN, EngineVersion.ACCRUAL_ENGINE_V1));
        assertThat(terms.toString()).doesNotContain("0.0899");
    }

    @Test
    @DisplayName("both products: a credit line's statement cycle resets exactly as an instalment period does - moved from"
            + " AccrualEngineV1Test#bothProducts")
    void bothProductsAccrueAlike() {
        AccrualPeriod february = new AccrualPeriod(LocalDate.parse("2027-02-10"), LocalDate.parse("2027-03-10"));
        AccrualTerms line = LoanEngines.accrualTerms(creditLine("0.05").build());
        AccrualTerms loan = LoanEngines.accrualTerms(personalLoan("0.05").build());
        assertThat(accrueEvery(line, february, euros(1000_00))).isEqualTo(accrueEvery(loan, february, euros(1000_00)));
        assertThat(sum(accrueEvery(line, february, euros(1000_00)))).isEqualTo(euros(3_84));
    }

    @Test
    @DisplayName("the allocation rule is the terms': the loan's held as credit, the line's paying principal down - and the"
            + " terms accept every order the vocabulary admits (moved from AllocationEngineV1Test)")
    void theAllocationRulesAreAsDeclared() {
        List<AllocationComponent> defaultOrder =
                List.of(AllocationComponent.FEES, AllocationComponent.INTEREST, AllocationComponent.PRINCIPAL);
        assertThat(LoanEngines.allocationRules(personalLoan("0.0899").build())).isEqualTo(new AllocationRules(
                EngineVersion.ALLOCATION_ENGINE_V1, AllocationSequence.OLDEST_DUE_FIRST, defaultOrder,
                OverpaymentTreatment.HOLD_AS_CREDIT));
        assertThat(LoanEngines.allocationRules(creditLine("0.1899").build())).isEqualTo(new AllocationRules(
                EngineVersion.ALLOCATION_ENGINE_V1, AllocationSequence.OLDEST_DUE_FIRST, defaultOrder,
                OverpaymentTreatment.PAY_DOWN_PRINCIPAL));
        int orders = 0;
        for (AllocationComponent a : AllocationComponent.values()) {
            for (AllocationComponent b : AllocationComponent.values()) {
                for (AllocationComponent c : AllocationComponent.values()) {
                    if (a != b && b != c && a != c) {
                        List<AllocationComponent> order = List.of(a, b, c);
                        LoanTerms terms = personalLoan("0.05").allocationOrder(order).build();
                        assertThat(LoanEngines.allocationRules(terms).order()).isEqualTo(order);
                        orders++;
                    }
                }
            }
        }
        assertThat(orders).isEqualTo(6);
    }

    // ------------------------------------------------------------------ what the engine once judged of a lending product

    @Test
    @DisplayName("terms version 1 does not compute - a credit line's - are refused CONVENTION_NOT_IMPLEMENTED, in the"
            + " engine's words, by every entry (moved from ScheduleEngineV1Test)")
    void aCreditLinesTermsAreRefused() {
        LoanTerms line = creditLine("0.15").build();
        LocalDate start = LocalDate.parse("2026-01-01");
        List<Object> refusals = List.of(
                LoanEngines.project(line, eur("1000.00"), 12, 10, start),
                LoanEngines.recalculate(line, eur("1000.00"), 12, 10, start, LocalDate.parse("2026-02-10")),
                LoanEngines.recalculateAtInstalment(line, eur("1000.00"), eur("100.00"), 12, 10, start,
                        LocalDate.parse("2026-02-10")),
                LoanEngines.judgeAmortisation(line, LoanTermsFixtures.BOUNDS.amortisationBounds(LoanProduct.PERSONAL_LOAN)
                        .orElseThrow()).orElseThrow());
        assertThat(refusals).allSatisfy(outcome -> assertThat(outcome).isEqualTo(new ScheduleOutcome.Refused(
                ScheduleRefusal.CONVENTION_NOT_IMPLEMENTED, "a schedule is computed for an instalment product's terms")));
    }

    @Test
    @DisplayName("the engine's own refusal of the conventions is the one the LoanTerms entries answered: its detail is the"
            + " engine's constant, and an instalment product's terms raise none")
    void theConventionsAreTheEngines() {
        assertThat(ScheduleEngineV1.UNIMPLEMENTED_CONVENTIONS)
                .isEqualTo("the terms name a rate type, frequency, day count or rounding mode version 1 does not compute");
        assertThat(LoanEngines.scheduleConventions(personalLoan("0.0899").build())).isEmpty();
        assertThat(LoanEngines.scheduleConventions(creditLine("0.0899").build())).isPresent();
        // RateType and RepaymentFrequency admit one member each (INV-LND-09), so the entries' rate-type and frequency arm
        // has no reachable input today: it is kept, in the engine's words, for the member a later version brings.
        assertThat(RateType.values()).containsExactly(RateType.FIXED);
        assertThat(RepaymentFrequency.values()).containsExactly(RepaymentFrequency.MONTHLY);
    }

    // ------------------------------------------------------------------ the entries are the engine's path

    @Test
    @DisplayName("the 6-month golden case through the LoanTerms entry: 1,719.29 a month, 315.71 of interest - the figure"
            + " the moved ScheduleEngineV1Test holds over ScheduleTerms")
    void theGoldenCaseThroughTheLendingEntry() {
        Schedule schedule = projected(LoanEngines.project(personalLoan("0.10").build(), eur("10000.00"), 6, 10,
                LocalDate.parse("2026-01-01")));
        assertThat(schedule.instalment()).isEqualTo(eur("1719.29"));
        assertThat(schedule.finalInstalment()).isEqualTo(eur("1719.26"));
        assertThat(schedule.totalInterest()).isEqualTo(eur("315.71"));
        assertThat(schedule.engine()).isEqualTo(EngineVersion.SCHEDULE_ENGINE_V1);
    }

    @Test
    @DisplayName("every LoanTerms entry answers exactly what the engine answers for the mapped terms - every day of a leap"
            + " year x the rates, days and terms the moved properties walk, refusals included")
    void everyEntryIsTheEnginesAnswer() {
        List<LoanTerms> terms = new ArrayList<>();
        for (String rate : List.of("0", "0.0001", "0.0499", "0.0899", "0.1249", "0.1999", "0.231", "0.9")) {
            terms.add(personalLoan(rate).build());
        }
        terms.add(personalLoan("0.5644")
                .instalment(Optional.of(new LoanTerms.InstalmentTerms(1, 24, EngineVersion.SCHEDULE_ENGINE_V1))).build());
        List<Money> principals = List.of(eur("0.05"), eur("500.00"), eur("500.19"), eur("9999.99"), eur("25000.00"));
        int compared = 0;
        int refused = 0;
        for (int offset = 0; offset < 366; offset++) {
            LocalDate start = LocalDate.of(2028, 1, 1).plusDays(offset);
            LoanTerms loan = terms.get(offset % terms.size());
            ScheduleTerms mapped = LoanEngines.scheduleTerms(loan);
            int day = 1 + offset % 31;
            int term = offset % 7 == 0 ? 60 : 1 + offset % 60;
            Money principal = principals.get(offset % principals.size());
            ScheduleOutcome viaLending = LoanEngines.project(loan, principal, term, day, start);
            assertThat(viaLending).as("project " + offset)
                    .isEqualTo(ScheduleEngineV1.project(mapped, principal, term, day, start));
            LocalDate next = LocalDate.of(start.getYear(), start.getMonth(), 1).plusMonths(1);
            next = next.withDayOfMonth(Math.min(day, next.lengthOfMonth()));
            assertThat(LoanEngines.recalculate(loan, principal, term, day, start, next)).as("recalculate " + offset)
                    .isEqualTo(ScheduleEngineV1.recalculate(mapped, principal, term, day, start, next));
            Money instalment = viaLending instanceof ScheduleOutcome.Projected projected
                    ? projected.schedule().instalment() : eur("100.00");
            assertThat(LoanEngines.recalculateAtInstalment(loan, principal, instalment, term, day, start, next))
                    .as("recalculateAtInstalment " + offset)
                    .isEqualTo(ScheduleEngineV1.recalculateAtInstalment(mapped, principal, instalment, term, day, start,
                            next));
            refused += viaLending instanceof ScheduleOutcome.Refused ? 1 : 0;
            compared++;
        }
        assertThat(compared).isEqualTo(366);
        assertThat(refused).as("refusals compared too, not only schedules").isPositive().isLessThan(compared);
        LoanProductBounds.Bounds bounds = LoanTermsFixtures.BOUNDS.amortisationBounds(LoanProduct.PERSONAL_LOAN).orElseThrow();
        for (LoanTerms loan : terms) {
            assertThat(LoanEngines.judgeAmortisation(loan, bounds)).as(loan.nominalAnnualRate().toPlainString())
                    .isEqualTo(ScheduleEngineV1.judgeAmortisation(LoanEngines.scheduleTerms(loan), loan.allowedDays(),
                            bounds.minimumPrincipal(), bounds.maximumTermMonths()));
        }
        assertThat(LoanEngines.judgeAmortisation(personalLoan("0.231").build(), bounds)).as("a refusal compared").isPresent();
    }

    // ------------------------------------------------------------------ plumbing

    private static Schedule projected(ScheduleOutcome outcome) {
        assertThat(outcome).isInstanceOf(ScheduleOutcome.Projected.class);
        return ((ScheduleOutcome.Projected) outcome).schedule();
    }

    private static List<InterestAccrual> accrueEvery(AccrualTerms terms, AccrualPeriod period, Money base) {
        List<InterestAccrual> accruals = new ArrayList<>();
        Optional<InterestAccrual> previous = Optional.empty();
        for (LocalDate date = period.start(); date.isBefore(period.endExclusive()); date = date.plusDays(1)) {
            InterestAccrual accrual = AccrualEngineV1.accrue(terms, period, previous, date, base);
            accruals.add(accrual);
            previous = Optional.of(accrual);
        }
        return accruals;
    }

    private static Money sum(List<InterestAccrual> accruals) {
        Money total = euros(0);
        for (InterestAccrual accrual : accruals) {
            total = total.plus(accrual.posted());
        }
        return total;
    }

    private static Money euros(long cents) {
        return Money.ofMinorUnits(cents, EUR);
    }

    private static Money eur(String amount) {
        return Money.of(new BigDecimal(amount), EUR);
    }
}
