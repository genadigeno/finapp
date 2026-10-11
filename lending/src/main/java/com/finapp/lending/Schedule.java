package com.finapp.lending;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * An amortisation schedule - the projection of an instalment loan's agreement, not its bill (`P11-TSK-006`; ADR-0093,
 * {@code PHASE_11_PLAN.md} section 12.2; {@code INV-LND-03}).
 *
 * <p><strong>Conservation is asserted at construction</strong>, so a value of this type conserves or does not exist:
 * the periods are numbered 1 to n and chain (each starts on the previous due date, the first on {@code start}; each
 * opens on the previous closing balance, the first on the principal; the last closes at zero); every principal portion
 * is positive; every instalment but the last is the level {@code instalment} and the last is never larger; and, stated
 * as the invariant reads, Σ principal = principal exactly and Σ amount = principal + Σ interest. A violation is a defect
 * of the engine that built it ({@link IllegalArgumentException}), never a business refusal - those are
 * {@link ScheduleOutcome.Refused}.
 *
 * <p>Named for the engine version that produced it, so a schedule is re-derivable by that version years later.
 * Amounts are RESTRICTED-FINANCIAL: {@link #toString()} names the engine and the term only.
 *
 * @param engine the schedule engine version that produced it
 * @param currency every amount's currency - the terms'
 * @param principal the amount amortised, {@code P}
 * @param nominalAnnualRate the terms' rate, a decimal fraction
 * @param start the day the first period's interest runs from - the accrual start, or a recalculation's effective date
 * @param intendedDay the repayment day every due date is computed from, before the month-end clamp
 * @param instalment the level instalment {@code A}
 * @param periods the periods, in order
 */
public record Schedule(
        EngineVersion engine,
        CurrencyCode currency,
        Money principal,
        BigDecimal nominalAnnualRate,
        LocalDate start,
        int intendedDay,
        Money instalment,
        List<SchedulePeriod> periods) {

    public Schedule {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(nominalAnnualRate, "nominalAnnualRate");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(instalment, "instalment");
        periods = List.copyOf(Objects.requireNonNull(periods, "periods"));
        if (engine.engine() != LendingEngine.SCHEDULE) {
            throw new IllegalArgumentException("a schedule is named for a schedule engine version");
        }
        if (periods.isEmpty() || intendedDay < 1 || intendedDay > 31) {
            throw new IllegalArgumentException("a schedule has at least one period and an intended day of a month");
        }
        if (!principal.isPositive() || !principal.currency().equals(currency) || !instalment.currency().equals(currency)) {
            throw new IllegalArgumentException("a schedule amortises a positive principal in its one currency");
        }
        Money zero = Money.zero(currency);
        Money principalRepaid = zero;
        Money interest = zero;
        Money payable = zero;
        LocalDate periodStart = start;
        Money opening = principal;
        for (int index = 0; index < periods.size(); index++) {
            SchedulePeriod period = periods.get(index);
            boolean last = index == periods.size() - 1;
            if (period.sequence() != index + 1 || !period.periodStart().equals(periodStart)
                    || !period.openingBalance().equals(opening)) {
                throw new IllegalArgumentException("period " + (index + 1) + " does not chain from its predecessor");
            }
            if (last ? period.amount().compareTo(instalment) > 0 || !period.closingBalance().isZero()
                    : !period.amount().equals(instalment) || !period.closingBalance().isPositive()) {
                throw new IllegalArgumentException("period " + (index + 1) + " is not the level instalment, or the"
                        + " final instalment exceeds it or leaves a balance");
            }
            principalRepaid = principalRepaid.plus(period.principal());
            interest = interest.plus(period.interest());
            payable = payable.plus(period.amount());
            periodStart = period.dueDate();
            opening = period.closingBalance();
        }
        if (!principalRepaid.equals(principal) || !payable.equals(principal.plus(interest))) {
            throw new IllegalArgumentException("a schedule conserves: its principal portions sum to its principal and"
                    + " its instalments to principal and interest (INV-LND-03)");
        }
    }

    /** The term in instalments, {@code n}. */
    public int termMonths() {
        return periods.size();
    }

    /** The final instalment - the remaining balance and its period's interest, never more than {@link #instalment()}. */
    public Money finalInstalment() {
        return periods.get(periods.size() - 1).amount();
    }

    /** Σ interest - what the offer shows as the total interest (A5). */
    public Money totalInterest() {
        Money total = Money.zero(currency);
        for (SchedulePeriod period : periods) {
            total = total.plus(period.interest());
        }
        return total;
    }

    /** Σ amount = principal + Σ interest - what the offer shows as the total payable (A5). */
    public Money totalPayable() {
        return principal.plus(totalInterest());
    }

    /** The engine and the term only - never an amount or the rate. */
    @Override
    public String toString() {
        return "Schedule[" + engine + ", " + periods.size() + " periods]";
    }
}
