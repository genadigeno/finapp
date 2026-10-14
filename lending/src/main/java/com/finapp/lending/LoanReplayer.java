package com.finapp.lending;

import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The pinned engines by version (`P11-TSK-029`; PHASE_11_PLAN.md section 12.1, ADR-0092 point 9; {@code INV-LND-05},
 * {@code INV-LND-09}): every computation a loan's figures came from, dispatched on the version NUMBER a row pinned - so a
 * figure is re-derived years later by exactly the engine that produced it, and a number this build does not hold is
 * {@link EngineAbsent}, never another version's arithmetic and never skipped.
 *
 * <p>Each dispatch is an exhaustive {@code switch} over {@link EngineVersion}: a new member - {@code ACCRUAL_ENGINE_V2} - is a
 * compile error here until its replay is written, and the old member's case stays for as long as an agreement pins it.
 * The canonical terms are dispatched the same way on their stored FORMAT (`P11-TSK-031`: format-aware replay - 1 and 2
 * an offer's, 3 an amendment's, 4 a prepayment's), each re-rendered byte for byte by {@link CanonicalOfferTerms}.
 *
 * <p>Pure and stateless - the same answer on every instance and in every JVM.
 */
public final class LoanReplayer {

    private LoanReplayer() {}

    /** A version number this build does not hold: the replay is {@code DIVERGED}, never skipped. */
    public static final class EngineAbsent extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        EngineAbsent(LendingEngine engine, int number) {
            super(engine + " version " + number + " is not held by this build");
        }
    }

    /** The version {@code number} of {@code engine}, or {@link EngineAbsent}. */
    static EngineVersion version(LendingEngine engine, int number) {
        return EngineVersion.of(engine, number).orElseThrow(() -> new EngineAbsent(engine, number));
    }

    /** Whether this build holds version {@code number} of {@code engine}. */
    static boolean holds(LendingEngine engine, int number) {
        return EngineVersion.of(engine, number).isPresent();
    }

    // ------------------------------------------------------------------ the schedule

    static ScheduleOutcome project(int number, LoanTerms terms, Money principal, int termMonths, int repaymentDay,
            LocalDate accrualStart) {
        return switch (version(LendingEngine.SCHEDULE, number)) {
            case SCHEDULE_ENGINE_V1 -> ScheduleEngineV1.project(terms, principal, termMonths, repaymentDay, accrualStart);
            case ACCRUAL_ENGINE_V1, ALLOCATION_ENGINE_V1, STATEMENT_ENGINE_V1 -> throw notThis(LendingEngine.SCHEDULE);
        };
    }

    static ScheduleOutcome recalculate(int number, LoanTerms terms, Money principal, int remainingMonths, int intendedDay,
            LocalDate effectiveFrom, LocalDate nextDueDate) {
        return switch (version(LendingEngine.SCHEDULE, number)) {
            case SCHEDULE_ENGINE_V1 -> ScheduleEngineV1.recalculate(terms, principal, remainingMonths, intendedDay,
                    effectiveFrom, nextDueDate);
            case ACCRUAL_ENGINE_V1, ALLOCATION_ENGINE_V1, STATEMENT_ENGINE_V1 -> throw notThis(LendingEngine.SCHEDULE);
        };
    }

    static ScheduleOutcome recalculateAtInstalment(int number, LoanTerms terms, Money principal, Money instalment,
            int maximumMonths, int intendedDay, LocalDate effectiveFrom, LocalDate nextDueDate) {
        return switch (version(LendingEngine.SCHEDULE, number)) {
            case SCHEDULE_ENGINE_V1 -> ScheduleEngineV1.recalculateAtInstalment(terms, principal, instalment, maximumMonths,
                    intendedDay, effectiveFrom, nextDueDate);
            case ACCRUAL_ENGINE_V1, ALLOCATION_ENGINE_V1, STATEMENT_ENGINE_V1 -> throw notThis(LendingEngine.SCHEDULE);
        };
    }

    // ------------------------------------------------------------------ the accrual

    static InterestAccrual accrue(AccrualTerms terms, AccrualPeriod period, Optional<InterestAccrual> previous,
            LocalDate date, Money base) {
        return switch (terms.accrualEngine()) {
            case ACCRUAL_ENGINE_V1 -> AccrualEngineV1.accrue(terms, period, previous, date, base);
            case SCHEDULE_ENGINE_V1, ALLOCATION_ENGINE_V1, STATEMENT_ENGINE_V1 -> throw notThis(LendingEngine.ACCRUAL);
        };
    }

    // ------------------------------------------------------------------ the allocation

    static List<AllocationLine> allocate(Money amount, DueState dueState, AllocationRules rules) {
        return switch (rules.engine()) {
            case ALLOCATION_ENGINE_V1 -> AllocationEngineV1.allocate(amount, dueState, rules);
            case SCHEDULE_ENGINE_V1, ACCRUAL_ENGINE_V1, STATEMENT_ENGINE_V1 -> throw notThis(LendingEngine.ALLOCATION);
        };
    }

    static List<AllocationLine> allocatePrepayment(Money amount, DueState dueState, AllocationRules rules) {
        return switch (rules.engine()) {
            case ALLOCATION_ENGINE_V1 -> AllocationEngineV1.allocatePrepayment(amount, dueState, rules);
            case SCHEDULE_ENGINE_V1, ACCRUAL_ENGINE_V1, STATEMENT_ENGINE_V1 -> throw notThis(LendingEngine.ALLOCATION);
        };
    }

    // ------------------------------------------------------------------ the statement

    static AccrualPeriod cycle(StatementTerms terms, LocalDate openedOn, LocalDate date) {
        return switch (terms.statementEngine()) {
            case STATEMENT_ENGINE_V1 -> StatementEngineV1.cycle(terms, openedOn, date);
            case SCHEDULE_ENGINE_V1, ACCRUAL_ENGINE_V1, ALLOCATION_ENGINE_V1 -> throw notThis(LendingEngine.STATEMENT);
        };
    }

    static Statement state(StatementTerms terms, AccrualPeriod cycle, List<InterestAccrual> accruals, Money feesCarried,
            Money notDuePrincipal, Money duePrincipal) {
        return switch (terms.statementEngine()) {
            case STATEMENT_ENGINE_V1 -> StatementEngineV1.state(terms, cycle, accruals, feesCarried, notDuePrincipal,
                    duePrincipal);
            case SCHEDULE_ENGINE_V1, ACCRUAL_ENGINE_V1, ALLOCATION_ENGINE_V1 -> throw notThis(LendingEngine.STATEMENT);
        };
    }

    // ------------------------------------------------------------------ the canonical terms

    /**
     * The canonical terms of an agreement version in its stored {@code format}: 1 and 2 an offer's (version 1), 3 an
     * amendment's, 4 a prepayment's. A format this build does not render is refused - the replay reports it.
     */
    static String render(int format, CanonicalOfferTerms.Content content, Optional<CanonicalOfferTerms.Amendment> amended,
            Optional<CanonicalOfferTerms.Prepayment> prepaid) {
        Objects.requireNonNull(content, "content");
        return switch (format) {
            case 1, 2 -> CanonicalOfferTerms.render(content, format);
            case CanonicalOfferTerms.AMENDED_FORMAT -> CanonicalOfferTerms.renderAmendment(content, amended.orElseThrow(
                    () -> new IllegalArgumentException("format 3 renders an amendment's version")));
            case CanonicalOfferTerms.PREPAID_FORMAT -> CanonicalOfferTerms.renderPrepayment(content, prepaid.orElseThrow(
                    () -> new IllegalArgumentException("format 4 renders a prepayment's version")));
            default -> throw new IllegalArgumentException("canonical terms format " + format + " is not rendered by this"
                    + " build");
        };
    }

    private static IllegalStateException notThis(LendingEngine engine) {
        return new IllegalStateException("a version of another engine dispatched as " + engine);
    }
}
