package com.finapp.bnpl;

import com.finapp.creditmath.AccrualEngineV1;
import com.finapp.creditmath.AllocationComponent;
import com.finapp.creditmath.AllocationEngineV1;
import com.finapp.creditmath.ScheduleEngineV1;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The BNPL battery's own arithmetic (`P12-TST-002`; {@code PHASE_12_PLAN.md} sections 12.2, 12.5 and 12.6, ADR-0102,
 * ADR-0105, A4-A6): every figure {@link BnplBatteryWorld} stores is computed here, from the plan's and the ADRs' statements
 * of the rules - never by calling {@link BnplScheduleEngineV1}, {@link ScheduleEngineV1}, {@link AccrualEngineV1},
 * {@link AllocationEngineV1}, {@link RefundAllocationEngineV1} or {@link BnplRefunds}. The production engines then re-derive
 * those stored figures through {@link BnplReplayProof}: {@code IDENTICAL} is two independent derivations agreeing, figure by
 * figure (the Phase 11 battery's Q2 - without its own arithmetic a battery cannot see an engine edited under it).
 *
 * <p>Amounts are {@code long} minor units at EUR's scale 2; every rounding is named and happens once; no floating point
 * anywhere ({@code INV-MON-01}). Two ALTERNATE arithmetics stand beside the standard ones - pay-in-4's remainder put on the
 * last instalment, and pay-in-N's accrual rounded through a scale-3 intermediate - so the battery can write a purchase under
 * a stored engine name with arithmetic that engine does not compute, and predict exactly which purchases that changes.
 */
final class BnplBatteryOracles {

    /** ACT/365F's fixed year (ADR-0094 point 2, pinned on the BNPL terms). */
    static final BigDecimal YEAR = BigDecimal.valueOf(365);

    /** A cumulative exact figure's scale (ADR-0094 point 3). */
    static final int EXACT_SCALE = 20;

    /** EUR's minor units. */
    static final int SCALE = 2;

    /** Pay-in-4: four instalments 14 days apart, the first at purchase (A4, A5). */
    static final int PAY_IN_4_INSTALMENTS = 4;

    static final int PAY_IN_4_INTERVAL_DAYS = 14;

    private BnplBatteryOracles() {}

    // ------------------------------------------------------------------ pay-in-4 (A4, A5, OQ-3)

    /** {@code A = floor(P / 4)} for instalments 2-4, the remainder {@code P - 3A} on instalment 1. */
    static long[] payIn4(long principal) {
        long level = principal / PAY_IN_4_INSTALMENTS;
        return new long[] {principal - 3 * level, level, level, level};
    }

    /** The ALTERNATE: the remainder put on the last instalment - pay-in-4's A4 broken the obvious way. */
    static long[] payIn4RemainderLast(long principal) {
        long level = principal / PAY_IN_4_INSTALMENTS;
        return new long[] {level, level, level, principal - 3 * level};
    }

    /** Instalment k falls due {@code D1 + 14 (k - 1)} calendar days - 29 February an ordinary day. */
    static List<LocalDate> payIn4Dates(LocalDate first) {
        List<LocalDate> dates = new ArrayList<>(PAY_IN_4_INSTALMENTS);
        for (int k = 0; k < PAY_IN_4_INSTALMENTS; k++) {
            dates.add(first.plusDays((long) PAY_IN_4_INTERVAL_DAYS * k));
        }
        return List.copyOf(dates);
    }

    // ------------------------------------------------------------------ pay-in-N's calendar (A6)

    /** The intended day of {@code month}, clamped to its last day. */
    static LocalDate onDay(YearMonth month, int day) {
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }

    /**
     * The due dates of an {@code n}-instalment schedule from {@code start}: {@code D1} the first intended day (clamped) at
     * least {@code minimumFirst} days on, each later one a calendar month after it on the intended day, clamped.
     */
    static List<LocalDate> dueDates(LocalDate start, int minimumFirst, int day, int n) {
        LocalDate earliest = start.plusDays(minimumFirst);
        YearMonth first = YearMonth.from(earliest);
        if (onDay(first, day).isBefore(earliest)) {
            first = first.plusMonths(1);
        }
        return monthly(first, day, n);
    }

    /** {@code n} due dates on the intended day from {@code first}'s month, clamped. */
    static List<LocalDate> monthly(YearMonth first, int day, int n) {
        List<LocalDate> dates = new ArrayList<>(n);
        for (int j = 0; j < n; j++) {
            dates.add(onDay(first.plusMonths(j), day));
        }
        return List.copyOf(dates);
    }

    // ------------------------------------------------------------------ pay-in-N's schedule (ADR-0093 as pinned)

    /** One period of a schedule, in minor units. */
    record Period(int sequence, LocalDate start, LocalDate due, long interest, long principal, long amount) {}

    /** A schedule: its level instalment and its periods. */
    record Plan(long principal, long instalment, List<Period> periods) {

        Plan {
            periods = List.copyOf(periods);
        }
    }

    /**
     * The annuity of {@code principal} over {@code dues} from {@code start}: {@code A* = P·F_n / S_n} with
     * {@code f_j = 365 + r·days_j}, {@code F_j = F_(j-1)·f_j}, {@code S_j = S_(j-1)·f_j + 365^j} - exact - then ONE integer
     * ceiling division to the minor unit; each period's interest {@code round_HALF_EVEN(B·r·days/365)} by one division;
     * {@code A} raised by single minor units while the final instalment would exceed it.
     */
    static Plan schedule(long principal, BigDecimal rate, LocalDate start, List<LocalDate> dues) {
        long[] days = days(start, dues);
        BigDecimal growth = BigDecimal.ONE;
        BigDecimal discounted = BigDecimal.ZERO;
        BigDecimal yearPower = BigDecimal.ONE;
        for (long periodDays : days) {
            BigDecimal factor = YEAR.add(rate.multiply(BigDecimal.valueOf(periodDays)));
            yearPower = yearPower.multiply(YEAR);
            growth = growth.multiply(factor);
            discounted = discounted.multiply(factor).add(yearPower);
        }
        long instalment = BigDecimal.valueOf(principal).multiply(growth).divide(discounted, 0, RoundingMode.CEILING)
                .longValueExact();
        long annuityUp = instalment;
        while (true) {
            List<Period> periods = periods(principal, rate, start, dues, days, instalment);
            if (periods != null) {
                return new Plan(principal, instalment, periods);
            }
            instalment++;
            if (instalment - annuityUp > 1_000_000) {
                throw new IllegalStateException("no level instalment keeps the final one at most it");
            }
        }
    }

    /**
     * A refund's re-amortisation (ADR-0105 decision 6): the level instalment {@code level} kept, the least term within
     * {@code maximum} months in which what is left and its interest fit in it, from {@code from} to the first kept due date
     * {@code firstDue} on the intended day - the same due dates, fewer, removed from the end; and when no term at
     * {@code level} fits, the least level instalment over all {@code maximum} of them.
     */
    static Plan atLevel(long principal, BigDecimal rate, long level, int maximum, int day, LocalDate from,
            LocalDate firstDue) {
        List<LocalDate> dues = monthly(YearMonth.from(firstDue), day, maximum);
        long balance = principal;
        LocalDate previous = from;
        for (int index = 0; index < maximum; index++) {
            long interest = interest(balance, rate, ChronoUnit.DAYS.between(previous, dues.get(index)));
            if (balance + interest <= level) {
                List<LocalDate> term = dues.subList(0, index + 1);
                List<Period> periods = periods(principal, rate, from, term, days(from, term), level);
                if (periods == null) {
                    throw new IllegalStateException("the least term at the kept instalment fits it");
                }
                return new Plan(principal, level, periods);
            }
            long portion = level - interest;
            if (portion <= 0) {
                break;
            }
            balance -= portion;
            previous = dues.get(index);
        }
        return schedule(principal, rate, from, dues);
    }

    private static long[] days(LocalDate start, List<LocalDate> dues) {
        long[] days = new long[dues.size()];
        LocalDate previous = start;
        for (int j = 0; j < days.length; j++) {
            days[j] = ChronoUnit.DAYS.between(previous, dues.get(j));
            previous = dues.get(j);
        }
        return days;
    }

    /** The periods under {@code instalment}, or {@code null} when the final instalment would exceed it. */
    private static List<Period> periods(long principal, BigDecimal rate, LocalDate start, List<LocalDate> dues,
            long[] days, long instalment) {
        List<Period> periods = new ArrayList<>(days.length);
        long balance = principal;
        LocalDate previous = start;
        for (int j = 0; j < days.length; j++) {
            if (balance <= 0) {
                throw new IllegalStateException("the balance ran out before instalment " + (j + 1));
            }
            long interest = interest(balance, rate, days[j]);
            boolean last = j == days.length - 1;
            long portion = last ? balance : instalment - interest;
            if (portion <= 0) {
                throw new IllegalStateException("instalment " + (j + 1) + " repays no principal");
            }
            if (last && balance + interest > instalment) {
                return null;
            }
            periods.add(new Period(j + 1, previous, dues.get(j), interest, portion, interest + portion));
            balance -= portion;
            previous = dues.get(j);
        }
        return periods;
    }

    /** {@code round_HALF_EVEN(balance·r·days/365)} in minor units - the exact product, one division. */
    static long interest(long balanceMinor, BigDecimal rate, long days) {
        return BigDecimal.valueOf(balanceMinor).multiply(rate).multiply(BigDecimal.valueOf(days))
                .divide(YEAR, 0, RoundingMode.HALF_EVEN).longValueExact();
    }

    // ------------------------------------------------------------------ pay-in-N's accrual (ADR-0094 points 2-3)

    /** A day's exact interest on {@code baseMinor}: {@code base·r/365}, one division at scale 20 {@code HALF_EVEN}. */
    static BigDecimal daily(long baseMinor, BigDecimal rate) {
        return BigDecimal.valueOf(baseMinor, SCALE).multiply(rate).divide(YEAR, EXACT_SCALE, RoundingMode.HALF_EVEN);
    }

    /** {@code cumulative} rounded once, {@code HALF_EVEN}, to the minor unit. */
    static long roundedOnce(BigDecimal cumulative) {
        return cumulative.setScale(SCALE, RoundingMode.HALF_EVEN).unscaledValue().longValueExact();
    }

    /** The ALTERNATE: {@code cumulative} rounded through a scale-3 intermediate first - double rounding. */
    static long roundedTwice(BigDecimal cumulative) {
        return cumulative.setScale(SCALE + 1, RoundingMode.HALF_EVEN).setScale(SCALE, RoundingMode.HALF_EVEN)
                .unscaledValue().longValueExact();
    }

    // ------------------------------------------------------------------ the allocation (ADR-0095, L6)

    /** What one billed item still has due, per component. */
    record Due(UUID item, LocalDate dueDate, long fees, long interest, long principal) {}

    /** One allocated part: a component of an item, or credit ({@code item} null). */
    record Line(UUID item, AllocationComponent component, long amount) {

        boolean credit() {
            return item == null;
        }
    }

    /** {@code amount} over {@code items}: oldest due date first, within each fees, interest, principal; the rest held. */
    static List<Line> allocate(long amount, List<Due> items) {
        List<Due> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(Due::dueDate));
        List<Line> lines = new ArrayList<>();
        long remaining = amount;
        for (Due item : sorted) {
            for (AllocationComponent component : List.of(AllocationComponent.FEES, AllocationComponent.INTEREST,
                    AllocationComponent.PRINCIPAL)) {
                long due = switch (component) {
                    case FEES -> item.fees();
                    case INTEREST -> item.interest();
                    case PRINCIPAL -> item.principal();
                };
                long paid = Math.min(remaining, due);
                if (paid > 0) {
                    lines.add(new Line(item.item(), component, paid));
                    remaining -= paid;
                }
            }
        }
        if (remaining > 0) {
            lines.add(new Line(null, null, remaining));
        }
        return lines;
    }

    // ------------------------------------------------------------------ the refund (ADR-0105 decisions 2-3, B3)

    /** One instalment as a refund meets it: its unpaid principal, billed or not. */
    record Slot(int sequence, boolean billed, long unpaid) {}

    /** One instalment's cancellation: {@code index} is the slot's place in the position, from 0. */
    record Cancelled(int index, boolean billed, long amount, boolean whole) {}

    /** A refund's split: the cancellations latest first, the excess, and whether no principal is left unpaid. */
    record Split(List<Cancelled> cancellations, long excess, boolean leavesNothingUnpaid) {

        Split {
            cancellations = List.copyOf(cancellations);
        }

        long cancelled(boolean billed) {
            long total = 0;
            for (Cancelled cancelled : cancellations) {
                if (cancelled.billed() == billed) {
                    total += cancelled.amount();
                }
            }
            return total;
        }
    }

    /**
     * {@code refund} over {@code slots}, walked from the LAST to the first: each cancels {@code min(what is left, its unpaid
     * principal)}; what is left at the end is the excess owed to the customer (A12). {@code R = Σ cancelled + E} by
     * construction.
     */
    static Split split(List<Slot> slots, long refund) {
        List<Cancelled> cancellations = new ArrayList<>();
        long remaining = refund;
        long unpaidAfter = 0;
        for (int index = slots.size() - 1; index >= 0; index--) {
            Slot slot = slots.get(index);
            long cancelled = Math.min(remaining, slot.unpaid());
            if (cancelled > 0) {
                cancellations.add(new Cancelled(index, slot.billed(), cancelled, cancelled == slot.unpaid()));
                remaining -= cancelled;
            }
            unpaidAfter += slot.unpaid() - cancelled;
        }
        return new Split(cancellations, remaining, unpaidAfter == 0);
    }

    // ------------------------------------------------------------------ the merchant fee (section 12.5, ADR-0105 decision 3)

    /** {@code f = round_HALF_EVEN(G · bps / 10 000) + fixed}. */
    static long merchantFee(long gross, int bps, long fixed) {
        return BigDecimal.valueOf(gross).multiply(BigDecimal.valueOf(bps))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_EVEN).longValueExact() + fixed;
    }

    /** {@code cum_fee(x) = round_HALF_EVEN(f · x / G)} - exact, one division; {@code cum_fee(G) = f}. */
    static long cumulativeFee(long fee, long refunded, long gross) {
        return BigDecimal.valueOf(fee).multiply(BigDecimal.valueOf(refunded))
                .divide(BigDecimal.valueOf(gross), 0, RoundingMode.HALF_EVEN).longValueExact();
    }

    /** The fee a refund of {@code refund} returns, {@code before} refunded already: {@code cum(before + R) - cum(before)}. */
    static long feeReturned(long fee, long before, long refund, long gross) {
        return cumulativeFee(fee, before + refund, gross) - cumulativeFee(fee, before, gross);
    }
}
