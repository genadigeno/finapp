package com.finapp.lending;

import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One instalment period of a {@link Schedule} (`P11-TSK-006`; ADR-0093 section 4): interest runs over
 * {@code [periodStart, dueDate)} on the opening balance, and the instalment due on {@code dueDate} pays that interest
 * and the principal portion.
 *
 * <p>A period is judged in itself (its days, its amount the sum of its parts, its closing balance the opening less the
 * principal); how periods chain and conserve is the {@link Schedule}'s judgement. Amounts are RESTRICTED-FINANCIAL, so
 * {@link #toString()} names the sequence and the due date only.
 *
 * @param sequence 1 to n
 * @param periodStart the day the period's interest runs from - the previous due date, or the schedule's start
 * @param dueDate the day its instalment falls due - after {@code periodStart}
 * @param days the actual days of {@code [periodStart, dueDate)} ({@code ACT_365F}, 29 February an ordinary day)
 * @param openingBalance the principal outstanding through the period, {@code B(j-1)}
 * @param interest the period's interest, {@code round_HALF_EVEN(B(j-1) x r x days / 365)}, never negative
 * @param principal the principal portion - positive
 * @param amount the instalment, {@code interest + principal}
 * @param closingBalance {@code B(j) = B(j-1) - principal}, never negative
 */
public record SchedulePeriod(
        int sequence,
        LocalDate periodStart,
        LocalDate dueDate,
        long days,
        Money openingBalance,
        Money interest,
        Money principal,
        Money amount,
        Money closingBalance) {

    public SchedulePeriod {
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(dueDate, "dueDate");
        Objects.requireNonNull(openingBalance, "openingBalance");
        Objects.requireNonNull(interest, "interest");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(closingBalance, "closingBalance");
        if (sequence < 1) {
            throw new IllegalArgumentException("a period is numbered from 1");
        }
        if (!dueDate.isAfter(periodStart) || days != Act365Fixed.days(periodStart, dueDate)) {
            throw new IllegalArgumentException("period " + sequence + " runs forward, its days counted from its dates");
        }
        if (interest.isNegative() || !principal.isPositive() || closingBalance.isNegative()) {
            throw new IllegalArgumentException(
                    "period " + sequence + " charges no negative interest and repays a positive principal portion");
        }
        if (!amount.equals(interest.plus(principal)) || !closingBalance.equals(openingBalance.minus(principal))) {
            throw new IllegalArgumentException(
                    "period " + sequence + "'s instalment is its interest and principal, its closing balance the opening"
                            + " less the principal");
        }
    }

    /** The sequence and due date only - never an amount. */
    @Override
    public String toString() {
        return "SchedulePeriod[" + sequence + ", due " + dueDate + "]";
    }
}
