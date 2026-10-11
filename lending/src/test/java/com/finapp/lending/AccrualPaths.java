package com.finapp.lending;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Generated accrual inputs for the accrual engine's properties and its second JVM (`P11-TSK-007`): on-time paths of a
 * monthly loan - due dates as {@code PHASE_11_PLAN.md} section 12.2 step 1 derives them, the base constant within each
 * period and falling at each due date - and single periods whose base varies day by day, zero days and gaps included.
 * Seeded by the caller, so a failure reproduces and two JVMs generate the same cases.
 */
final class AccrualPaths {

    private static final List<CurrencyCode> CURRENCIES =
            List.of(CurrencyCode.of("EUR"), CurrencyCode.of("EUR"), CurrencyCode.of("JPY"), CurrencyCode.of("BHD"));

    private AccrualPaths() {}

    /** An on-time path: period {@code j} accrues on {@code bases.get(j)} every day. */
    record OnTimePath(AccrualTerms terms, List<AccrualPeriod> periods, List<Money> bases) {}

    /** One period whose base varies; an empty base is a date with no accrual (a line at zero principal). */
    record VaryingPeriod(AccrualTerms terms, AccrualPeriod period, List<Optional<Money>> bases) {}

    static OnTimePath onTimePath(Random random, int maximumTerm) {
        AccrualTerms terms = terms(random);
        int scale = terms.currency().minorUnits();
        long principal = (100L + random.nextInt(1_000_000)) * pow10(scale);
        int term = 1 + random.nextInt(maximumTerm);
        LocalDate start = LocalDate.of(2024, 1, 1).plusDays(random.nextInt(8 * 366));
        int intendedDay = 1 + random.nextInt(31);
        int minimumFirstPeriod = 1 + random.nextInt(28);
        List<AccrualPeriod> periods = new ArrayList<>();
        LocalDate first = firstDueDate(start, minimumFirstPeriod, intendedDay);
        LocalDate previous = start;
        for (int j = 0; j < term; j++) {
            LocalDate due = clamped(first.withDayOfMonth(1).plusMonths(j), intendedDay);
            periods.add(new AccrualPeriod(previous, due));
            previous = due;
        }
        List<Money> bases = new ArrayList<>();
        long outstanding = principal;
        for (int j = 0; j < term; j++) {
            bases.add(Money.ofMinorUnits(outstanding, terms.currency()));
            outstanding -= j == term - 1 ? outstanding : nextLong(random, outstanding / 2 + 1);
        }
        return new OnTimePath(terms, List.copyOf(periods), List.copyOf(bases));
    }

    static VaryingPeriod varyingPeriod(Random random) {
        AccrualTerms terms = terms(random);
        int scale = terms.currency().minorUnits();
        LocalDate start = LocalDate.of(2024, 1, 1).plusDays(random.nextInt(8 * 366));
        AccrualPeriod period = new AccrualPeriod(start, start.plusDays(1 + random.nextInt(45)));
        List<Optional<Money>> bases = new ArrayList<>();
        long base = (random.nextInt(500_000)) * pow10(scale) + random.nextInt((int) pow10(scale));
        for (long d = 0; d < period.days(); d++) {
            int roll = random.nextInt(10);
            if (roll == 0) {
                bases.add(Optional.empty());
                continue;
            }
            if (roll == 1) {
                bases.add(Optional.of(Money.zero(terms.currency())));
                continue;
            }
            if (roll < 4) {
                base = Math.max(0, base + nextLong(random, 200_000 * pow10(scale)) - 100_000 * pow10(scale));
            }
            bases.add(Optional.of(Money.ofMinorUnits(base, terms.currency())));
        }
        return new VaryingPeriod(terms, period, List.copyOf(bases));
    }

    /** Terms in a generated currency at a rate of 0 to 1 with up to 8 places - a zero rate one case in ten. */
    static AccrualTerms terms(Random random) {
        CurrencyCode currency = CURRENCIES.get(random.nextInt(CURRENCIES.size()));
        BigDecimal rate = random.nextInt(10) == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(random.nextInt(100_000_000) + 1L, 8).min(BigDecimal.ONE);
        return new AccrualTerms(currency, rate, DayCountConvention.ACT_365F, RoundingMode.HALF_EVEN,
                EngineVersion.ACCRUAL_ENGINE_V1);
    }

    /** {@code D1}: the first date on the intended day (clamped) at least {@code minimum} days after {@code start}. */
    static LocalDate firstDueDate(LocalDate start, int minimum, int intendedDay) {
        LocalDate earliest = start.plusDays(minimum);
        LocalDate candidate = clamped(earliest.withDayOfMonth(1), intendedDay);
        return candidate.isBefore(earliest) ? clamped(earliest.withDayOfMonth(1).plusMonths(1), intendedDay) : candidate;
    }

    private static LocalDate clamped(LocalDate firstOfMonth, int intendedDay) {
        return firstOfMonth.withDayOfMonth(Math.min(intendedDay, firstOfMonth.lengthOfMonth()));
    }

    private static long nextLong(Random random, long bound) {
        return bound <= 1 ? 0 : Math.floorMod(random.nextLong(), bound);
    }

    private static long pow10(int scale) {
        long value = 1;
        for (int i = 0; i < scale; i++) {
            value *= 10;
        }
        return value;
    }
}
