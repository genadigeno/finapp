package com.finapp.bnpl;

import com.finapp.creditmath.Schedule;
import com.finapp.creditmath.ScheduleEngineV1;
import com.finapp.creditmath.ScheduleOutcome;
import com.finapp.creditmath.SchedulePeriod;
import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An offer's illustrative schedule (`P12-TSK-011`; {@code PHASE_12_PLAN.md} sections 12.1 and 12.2, A4-A6) - what a
 * purchase of {@code amount} on {@code plan} would fall due in, projected by the PINNED engine from {@code start}, the
 * offering instant's business date: pay-in-4 through {@code BNPL_SCHEDULE_ENGINE_V1} (four interest-free instalments, the
 * first on the start date), pay-in-N through creditmath's {@code SCHEDULE_ENGINE_V1} over the pinned terms'
 * {@link BnplTerms#scheduleTerms()} (the level annuity, the first due one month after the start on its day of the month,
 * clamped - A6). One shape for both, so the canonical terms, the document and the doors read one record.
 *
 * <p><strong>A projection, never the bill.</strong> The acceptance (`P12-TSK-013`) projects the agreement's schedule
 * version 1 from the purchase's own business date; pay-in-N's bills are its accruals' (`-015`, `-017`). A pure function of
 * its inputs - the same on every instance - so the offer's read re-projects it and holds the stored canonical terms to
 * the bytes it renders ({@link BnplOffers}).
 *
 * @param engine the schedule engine version's name - {@code BNPL_SCHEDULE_ENGINE_V1} or {@code SCHEDULE_ENGINE_V1}
 * @param start the date the illustration starts on - the offering instant's business date
 * @param amount the principal - the purchase's gross
 * @param lines the instalments, in order
 */
public record BnplIllustration(String engine, LocalDate start, Money amount, List<Line> lines) {

    public BnplIllustration {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(amount, "amount");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("an illustration has at least one instalment");
        }
        Money principal = Money.zero(amount.currency());
        for (int index = 0; index < lines.size(); index++) {
            Line line = lines.get(index);
            if (line.sequence() != index + 1 || !line.amount().equals(line.principal().plus(line.interest()))) {
                throw new IllegalArgumentException("instalment " + (index + 1) + " is numbered in order and is its"
                        + " principal and interest");
            }
            principal = principal.plus(line.principal());
        }
        if (!principal.equals(amount)) {
            throw new IllegalArgumentException("an illustration conserves: its principal sums to the amount");
        }
    }

    /**
     * The illustration of {@code amount} on {@code plan} under the pinned {@code terms} from {@code start}.
     *
     * @throws Refused when the pinned engine refuses - a defect of terms the door judged whole, never a closure reason
     */
    public static BnplIllustration of(BnplTerms terms, BnplPlan plan, Money amount, LocalDate start) {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(start, "start");
        if (plan.product() != terms.product()) {
            throw new IllegalArgumentException("an illustration is of the terms' own product's plan");
        }
        return switch (terms.product().shape()) {
            case FIXED -> payIn4(terms, amount, start);
            case MONTHS -> payInN(terms, plan, amount, start);
        };
    }

    private static BnplIllustration payIn4(BnplTerms terms, Money amount, LocalDate start) {
        BnplScheduleOutcome outcome = BnplScheduleEngineV1.project(terms, amount, start);
        if (outcome instanceof BnplScheduleOutcome.Refused refused) {
            throw new Refused(refused.reason().name() + ": " + refused.detail());
        }
        BnplSchedule schedule = ((BnplScheduleOutcome.Projected) outcome).schedule();
        Money nothing = Money.zero(amount.currency());
        List<Line> lines = new ArrayList<>();
        for (BnplSchedule.Instalment instalment : schedule.instalments()) {
            lines.add(new Line(instalment.sequence(), instalment.dueDate(), instalment.principal(), nothing,
                    instalment.principal()));
        }
        return new BnplIllustration(schedule.engine().name(), start, amount, lines);
    }

    private static BnplIllustration payInN(BnplTerms terms, BnplPlan plan, Money amount, LocalDate start) {
        int months = plan.termMonths().orElseThrow();
        // A6: one month after the purchase on its day of the month - the engine clamps to the month's end.
        ScheduleOutcome outcome = ScheduleEngineV1.project(terms.scheduleTerms().orElseThrow(), amount, months,
                start.getDayOfMonth(), start);
        if (outcome instanceof ScheduleOutcome.Refused refused) {
            throw new Refused(refused.reason().name() + ": " + refused.detail());
        }
        Schedule schedule = ((ScheduleOutcome.Projected) outcome).schedule();
        List<Line> lines = new ArrayList<>();
        for (SchedulePeriod period : schedule.periods()) {
            lines.add(new Line(period.sequence(), period.dueDate(), period.principal(), period.interest(),
                    period.amount()));
        }
        return new BnplIllustration(schedule.engine().name(), start, amount, lines);
    }

    /** The number of instalments. */
    public int instalmentCount() {
        return lines.size();
    }

    /** Instalment 1's amount - pay-in-4's carries the remainder (OQ-3). */
    public Money firstInstalment() {
        return lines.get(0).amount();
    }

    /** The regular instalment - pay-in-4's instalments 2-4, pay-in-N's level instalment. */
    public Money regularInstalment() {
        return lines.get(lines.size() > 1 ? 1 : 0).amount();
    }

    /** The last instalment's amount. */
    public Money finalInstalment() {
        return lines.get(lines.size() - 1).amount();
    }

    /** Σ interest - zero for pay-in-4. */
    public Money totalInterest() {
        Money total = Money.zero(amount.currency());
        for (Line line : lines) {
            total = total.plus(line.interest());
        }
        return total;
    }

    /** Σ instalments = the amount + Σ interest. */
    public Money totalPayable() {
        return amount.plus(totalInterest());
    }

    /** The first instalment's due date. */
    public LocalDate firstDueDate() {
        return lines.get(0).dueDate();
    }

    /** The last instalment's due date. */
    public LocalDate lastDueDate() {
        return lines.get(lines.size() - 1).dueDate();
    }

    /** The engine and the count only - never an amount. */
    @Override
    public String toString() {
        return "BnplIllustration[" + engine + ", " + lines.size() + " instalments]";
    }

    /**
     * One illustrated instalment.
     *
     * @param sequence 1 to n
     * @param dueDate the day it would fall due
     * @param principal its principal
     * @param interest its interest - zero for pay-in-4
     * @param amount principal + interest
     */
    public record Line(int sequence, LocalDate dueDate, Money principal, Money interest, Money amount) {

        public Line {
            Objects.requireNonNull(dueDate, "dueDate");
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(interest, "interest");
            Objects.requireNonNull(amount, "amount");
        }

        @Override
        public String toString() {
            return "Line[" + sequence + ", due " + dueDate + "]";
        }
    }

    /** The pinned engine refused the illustration - a defect of the moment, never a business refusal. */
    public static final class Refused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        Refused(String detail) {
            super(detail);
        }
    }
}
