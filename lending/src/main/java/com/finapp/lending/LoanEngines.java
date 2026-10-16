package com.finapp.lending;

import com.finapp.creditmath.AccrualTerms;
import com.finapp.creditmath.AllocationRules;
import com.finapp.creditmath.ScheduleEngineV1;
import com.finapp.creditmath.ScheduleOutcome;
import com.finapp.creditmath.ScheduleRefusal;
import com.finapp.creditmath.ScheduleTerms;
import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Lending's side of the {@code creditmath} edge (`P12-TSK-002`; ADR-0101 decision 5, {@code MODULE_ARCHITECTURE.md}
 * M12): the {@link LoanTerms} entries of the three engines lending computed with since Phase 11 and which moved, unchanged,
 * into {@code creditmath} - each a mapping of the terms onto the engine's product-agnostic record and a delegation,
 * nothing else. {@code creditmath} cannot see a lending type, so these mappings live here: the
 * {@code AccrualTerms.of(LoanTerms)} and {@code AllocationRules.of(LoanTerms)} factories the records held, and
 * {@code ScheduleEngineV1}'s {@code LoanTerms} entries.
 *
 * <p><strong>Behaviour-preserving, by construction and by proof.</strong> Every figure is the engine's own; what the
 * engine once judged of a lending product - an instalment kind with instalment terms, the fixed rate, the monthly
 * frequency - is judged here, first the kind, then the engine's own {@link ScheduleEngineV1#conventions} (the engine
 * version, the day count, the roundings), then the rate type and frequency, in the order and with the words the engine
 * used, so every refusal is the one it answered before. The null checks come first, as the engine's did. The battery
 * ({@code AmortisationBatteryTest}, `P11-TST-002`) replays every Phase 11 figure through these entries with its population
 * fingerprint and two-JVM digest unchanged; {@code LoanEnginesTest} holds each entry to the engine it delegates to.
 *
 * <p>Pure and static, as the engines are: no state, no clock - the same on every instance.
 */
public final class LoanEngines {

    private LoanEngines() {}

    // ------------------------------------------------------------------ accrual and allocation

    /** The accrual conventions of {@code terms}, as declared - what {@code AccrualTerms.of(LoanTerms)} returned. */
    public static AccrualTerms accrualTerms(LoanTerms terms) {
        Objects.requireNonNull(terms, "terms");
        return new AccrualTerms(terms.currency(), terms.nominalAnnualRate(), terms.dayCount(), terms.interestRounding(),
                terms.accrualEngine());
    }

    /**
     * The allocation rule {@code terms} carry: their allocation engine, sequence, order and their kind's overpayment
     * treatment - what {@code AllocationRules.of(LoanTerms)} returned.
     */
    public static AllocationRules allocationRules(LoanTerms terms) {
        return new AllocationRules(terms.allocationEngine(), terms.allocationSequence(), terms.allocationOrder(),
                terms.overpaymentTreatment());
    }

    // ------------------------------------------------------------------ the schedule

    /** {@link ScheduleEngineV1#project}, for an instalment product's {@code terms}. */
    public static ScheduleOutcome project(
            LoanTerms terms, Money principal, int termMonths, int repaymentDay, LocalDate accrualStart) {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(accrualStart, "accrualStart");
        Optional<ScheduleOutcome.Refused> refused = scheduleConventions(terms);
        if (refused.isPresent()) {
            return refused.get();
        }
        return ScheduleEngineV1.project(scheduleTerms(terms), principal, termMonths, repaymentDay, accrualStart);
    }

    /** {@link ScheduleEngineV1#recalculate}, for an instalment product's {@code terms}. */
    public static ScheduleOutcome recalculate(
            LoanTerms terms,
            Money principal,
            int remainingMonths,
            int intendedDay,
            LocalDate effectiveFrom,
            LocalDate nextDueDate) {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(nextDueDate, "nextDueDate");
        Optional<ScheduleOutcome.Refused> refused = scheduleConventions(terms);
        if (refused.isPresent()) {
            return refused.get();
        }
        return ScheduleEngineV1.recalculate(
                scheduleTerms(terms), principal, remainingMonths, intendedDay, effectiveFrom, nextDueDate);
    }

    /** {@link ScheduleEngineV1#recalculateAtInstalment}, for an instalment product's {@code terms}. */
    public static ScheduleOutcome recalculateAtInstalment(
            LoanTerms terms,
            Money principal,
            Money instalment,
            int maximumMonths,
            int intendedDay,
            LocalDate effectiveFrom,
            LocalDate nextDueDate) {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(instalment, "instalment");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(nextDueDate, "nextDueDate");
        Optional<ScheduleOutcome.Refused> refused = scheduleConventions(terms);
        if (refused.isPresent()) {
            return refused.get();
        }
        return ScheduleEngineV1.recalculateAtInstalment(
                scheduleTerms(terms), principal, instalment, maximumMonths, intendedDay, effectiveFrom, nextDueDate);
    }

    /**
     * {@link ScheduleEngineV1#judgeAmortisation} over the product's {@code bounds} - the terms proposal door's judgement
     * ({@code 422 lending.TermsNotAmortising}): the bounds' smallest principal and longest term, and the terms' allowed
     * repayment days, passed unchanged.
     */
    public static Optional<ScheduleOutcome.Refused> judgeAmortisation(LoanTerms terms, LoanProductBounds.Bounds bounds) {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(bounds, "bounds");
        Optional<ScheduleOutcome.Refused> refused = scheduleConventions(terms);
        if (refused.isPresent()) {
            return refused;
        }
        return ScheduleEngineV1.judgeAmortisation(
                scheduleTerms(terms), terms.allowedDays(), bounds.minimumPrincipal(), bounds.maximumTermMonths());
    }

    /**
     * What {@code SCHEDULE_ENGINE_V1} computes of a lending product: an instalment product's terms - judged first - whose
     * engine version and conventions the engine implements ({@link ScheduleEngineV1#conventions}), fixed and monthly.
     * The order and the words are the engine's before the move, so every {@link ScheduleRefusal#CONVENTION_NOT_IMPLEMENTED}
     * is the one it answered.
     */
    static Optional<ScheduleOutcome.Refused> scheduleConventions(LoanTerms terms) {
        if (terms.product().kind() != LoanKind.INSTALMENT || terms.instalment().isEmpty()) {
            return convention("a schedule is computed for an instalment product's terms");
        }
        Optional<ScheduleOutcome.Refused> engine = ScheduleEngineV1.conventions(scheduleTerms(terms));
        if (engine.isPresent()) {
            return engine;
        }
        if (terms.rateType() != RateType.FIXED || terms.product().frequency() != RepaymentFrequency.MONTHLY) {
            return convention(ScheduleEngineV1.UNIMPLEMENTED_CONVENTIONS);
        }
        return Optional.empty();
    }

    /**
     * The schedule terms of an instalment product's {@code terms}: the currency, the rate, the shortest first period, the
     * day count, both roundings and the pinned schedule engine, exactly as declared.
     *
     * @throws java.util.NoSuchElementException for terms with no instalment terms - judged by
     *     {@link #scheduleConventions} first on every entry
     */
    public static ScheduleTerms scheduleTerms(LoanTerms terms) {
        Objects.requireNonNull(terms, "terms");
        LoanTerms.InstalmentTerms instalment = terms.instalment().orElseThrow();
        return new ScheduleTerms(terms.currency(), terms.nominalAnnualRate(), instalment.minFirstPeriodDays(),
                terms.dayCount(), terms.instalmentRounding(), terms.interestRounding(), instalment.scheduleEngine());
    }

    private static Optional<ScheduleOutcome.Refused> convention(String detail) {
        return Optional.of(new ScheduleOutcome.Refused(ScheduleRefusal.CONVENTION_NOT_IMPLEMENTED, detail));
    }
}
