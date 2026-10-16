package com.finapp.creditmath;

import com.finapp.creditmath.testing.SecondJvm;
import com.finapp.sharedkernel.money.Money;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * The accrual engine's second JVM (`P11-TSK-007`; the backlog's two-JVM comparison): every figure of a seeded battery of
 * on-time paths and varying-base periods, one line per accrual and one per period, written by a process with another
 * default charset, locale and time zone through {@link SecondJvm}. {@code AccrualMatchesSchedulePropertiesTest}
 * computes the same lines in its own JVM and requires them identical.
 */
final class AccrualInAnotherJvm {

    static final long SEED = 7_007L;
    static final int ON_TIME_PATHS = 40;
    static final int VARYING_PERIODS = 120;

    private AccrualInAnotherJvm() {}

    public static void main(String[] arguments) throws IOException {
        SecondJvm.write(Path.of(arguments[0]), lines());
    }

    /** Every figure of the battery, in generation order. */
    static List<String> lines() {
        Random random = new Random(SEED);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < ON_TIME_PATHS; i++) {
            AccrualPaths.OnTimePath path = AccrualPaths.onTimePath(random, 24);
            for (int j = 0; j < path.periods().size(); j++) {
                AccrualPeriod period = path.periods().get(j);
                Optional<InterestAccrual> previous = Optional.empty();
                for (LocalDate date = period.start(); date.isBefore(period.endExclusive()); date = date.plusDays(1)) {
                    InterestAccrual accrual = AccrualEngineV1.accrue(path.terms(), period, previous, date, path.bases().get(j));
                    lines.add(line("path " + i, accrual));
                    previous = Optional.of(accrual);
                }
                lines.add("path " + i + " period " + j + " " + AccrualEngineV1.postedThrough(previous.orElseThrow()).minorUnits());
            }
        }
        for (int i = 0; i < VARYING_PERIODS; i++) {
            AccrualPaths.VaryingPeriod varying = AccrualPaths.varyingPeriod(random);
            Optional<InterestAccrual> previous = Optional.empty();
            LocalDate date = varying.period().start();
            for (Optional<Money> base : varying.bases()) {
                if (base.isPresent()) {
                    InterestAccrual accrual = AccrualEngineV1.accrue(varying.terms(), varying.period(), previous, date, base.get());
                    lines.add(line("varying " + i, accrual));
                    previous = Optional.of(accrual);
                }
                date = date.plusDays(1);
            }
            lines.add("varying " + i + " " + previous.map(AccrualEngineV1::postedThrough).map(Money::minorUnits).orElse(0L));
        }
        return lines;
    }

    /** One accrual's every figure, locale-free. */
    static String line(String label, InterestAccrual accrual) {
        return label + " " + accrual.date() + " " + accrual.base().minorUnits() + " " + accrual.base().currency().code()
                + " " + accrual.cumulativeExact().toPlainString() + " " + accrual.posted().minorUnits() + " "
                + accrual.engine().name();
    }
}
