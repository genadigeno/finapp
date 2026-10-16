package com.finapp.creditmath;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * The engines' inputs as Phase 11's terms declared them (`P12-TSK-002`): the engine suites moved here from
 * {@code lending} with their golden figures unedited, and built their inputs from lending's {@code LoanTermsFixtures}
 * there - {@code personalLoan(rate).build()} through the {@code LoanTerms} entries. {@code creditmath} cannot see a
 * lending type, so the same values are built here through the records' canonical constructors: what lending's
 * {@code LoanEngines} maps the Phase 11 personal loan's terms onto, field for field - EUR, the rate in
 * {@code LoanTerms}' canonical spelling, a 15-day shortest first period, {@code ACT_365F}, {@code UP} and
 * {@code HALF_EVEN}, the version-1 engines, the allowed repayment days and credit's bounds for the product.
 * {@code LoanEnginesTest} in {@code lending} holds the mapping to exactly these values.
 */
final class EngineTermsFixtures {

    static final CurrencyCode EUR = CurrencyCode.of("EUR");

    /** The personal loan's allowed repayment days (A6) - {@code LoanTermsFixtures}' {@code allowedDays}. */
    static final List<Integer> ALLOWED_DAYS = List.of(1, 5, 10, 15, 20, 25, 28);

    /** The shortest first period the Phase 11 personal loan pins (A6). */
    static final int PERSONAL_LOAN_FIRST_PERIOD_DAYS = 15;

    /**
     * Credit's bounds for the personal loan - EUR 500.00 to 25,000.00 over 6 to 60 months - the two figures the terms
     * door's judgement reads among them ({@code LoanTermsFixtures.BOUNDS} in lending).
     */
    record Bounds(Money minimumPrincipal, Money maximumPrincipal, int minimumTermMonths, int maximumTermMonths) {}

    static final Bounds PERSONAL_LOAN_BOUNDS =
            new Bounds(Money.ofMinorUnits(500_00, EUR), Money.ofMinorUnits(25_000_00, EUR), 6, 60);

    private EngineTermsFixtures() {}

    /** The personal loan's schedule terms at {@code rate} (a decimal fraction string). */
    static ScheduleTerms scheduleTerms(String rate) {
        return scheduleTerms(rate, PERSONAL_LOAN_FIRST_PERIOD_DAYS);
    }

    /** As {@link #scheduleTerms(String)}, with another shortest first period. */
    static ScheduleTerms scheduleTerms(String rate, int minFirstPeriodDays) {
        return new ScheduleTerms(EUR, canonical(rate), minFirstPeriodDays, DayCountConvention.ACT_365F, RoundingMode.UP,
                RoundingMode.HALF_EVEN, EngineVersion.SCHEDULE_ENGINE_V1);
    }

    /** The terms door's judgement over {@code bounds}, with the personal loan's allowed days. */
    static Optional<ScheduleOutcome.Refused> judge(ScheduleTerms terms, Bounds bounds) {
        return ScheduleEngineV1.judgeAmortisation(terms, ALLOWED_DAYS, bounds.minimumPrincipal(),
                bounds.maximumTermMonths());
    }

    /** The accrual conventions the same terms declare. */
    static AccrualTerms accrualTerms(ScheduleTerms terms) {
        return new AccrualTerms(terms.currency(), terms.nominalAnnualRate(), terms.dayCount(), terms.interestRounding(),
                EngineVersion.ACCRUAL_ENGINE_V1);
    }

    /** The loan's allocation rule: fees, interest, principal, oldest due first; the remainder held as credit. */
    static AllocationRules loanRules() {
        return rules(OverpaymentTreatment.HOLD_AS_CREDIT);
    }

    /** The line's: fees, interest, principal, oldest due first; the remainder paying down drawn principal (A26). */
    static AllocationRules lineRules() {
        return rules(OverpaymentTreatment.PAY_DOWN_PRINCIPAL);
    }

    private static AllocationRules rules(OverpaymentTreatment treatment) {
        return new AllocationRules(EngineVersion.ALLOCATION_ENGINE_V1, AllocationSequence.OLDEST_DUE_FIRST,
                List.of(AllocationComponent.FEES, AllocationComponent.INTEREST, AllocationComponent.PRINCIPAL), treatment);
    }

    /** {@code LoanTerms}' one canonical spelling of a rate: no trailing zero, no exponent. */
    private static BigDecimal canonical(String rate) {
        BigDecimal value = new BigDecimal(rate);
        BigDecimal canonical = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        return canonical.scale() < 0 ? canonical.setScale(0) : canonical;
    }
}
