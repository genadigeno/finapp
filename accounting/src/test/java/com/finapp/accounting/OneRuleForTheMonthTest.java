package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.ReconciliationRunCoverage.Coverage;
import com.finapp.accounting.ReconciliationRunCoverage.Line;
import com.finapp.accounting.ReconciliationRunCoverage.Source;
import com.finapp.accounting.ReconciliationRunCoverage.SourceState;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The check and the gauge read one rule (`P14-TSK-025`): {@link CloseChecks.MonthRuns#judge} - {@code RECONCILIATION_DISCLOSURE}'s {@code MONTH_NOT_RUN}
 * and {@link PeriodCloses}' re-judge - and {@link ClosedMonthRuns}, {@code finapp.accounting.close.month.unrun}, both through {@link Coverage#required()}.
 * Over every source a month can hold - each state, admitted before, on or after its last day, retired before, on or after its first, its rule set never,
 * long ago, on or after its last day - and every shape of its runs, the gauge counts a source exactly when the check names it not run and it holds a
 * repudiated run, and neither names a source the month does not require.
 */
@DisplayName("the check and the gauge read one rule for the month (P14-TSK-025)")
class OneRuleForTheMonthTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Connection CONNECTION = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    private static final YearMonth MAY = YearMonth.of(2097, 5);
    private static final LocalDate FIRST = MAY.atDay(1);
    private static final LocalDate LAST = MAY.atEndOfMonth();
    private static final LocalDate LONG_AGO = LocalDate.of(2001, 1, 1);

    /** What a source's own runs in the month can be. */
    private enum Runs { NONE, REPUDIATED_ONLY, REPUDIATED_AND_COUNTING, COUNTING_ONLY, UNFINISHED_ONLY }

    @Test
    @DisplayName("source by source: the gauge counts it exactly when the check names it not run and it holds a repudiated run; a source the month does not require is named by neither")
    void sourceBySource() {
        int judged = 0;
        for (Source source : everySource()) {
            for (Runs runs : Runs.values()) {
                Coverage coverage = new Coverage(MAY, lines(source, runs), List.of(source));
                CloseChecks.MonthRuns check = CloseChecks.MonthRuns.judge(coverage);
                long gauge = gauge(coverage);
                boolean named = check.notRun().contains(source.code());
                String what = source + " / " + runs;
                assertThat(gauge).as("the gauge agrees with the check: %s", what)
                        .isEqualTo(named && (runs == Runs.REPUDIATED_ONLY) ? 1L : 0L);
                assertThat(named).as("the check names exactly a required source with no counting run: %s", what)
                        .isEqualTo(source.requiredIn(MAY) && runs != Runs.REPUDIATED_AND_COUNTING && runs != Runs.COUNTING_ONLY);
                if (!source.requiredIn(MAY)) {
                    assertThat(check.ran()).as("a month requiring nothing has not run: %s", what).isFalse();
                    assertThat(gauge).as("not required, never counted: %s", what).isZero();
                }
                judged++;
            }
        }
        assertThat(judged).as("the census").isGreaterThan(200);
    }

    @Test
    @DisplayName("the whole register at once: the gauge's count is the check's not-run sources that hold a repudiated run, and the check's ran is every required source run")
    void theWholeRegister() {
        List<Source> sources = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        int index = 0;
        for (Source source : everySource()) {
            Runs runs = Runs.values()[index++ % Runs.values().length];
            sources.add(source);
            lines.addAll(lines(source, runs));
        }
        Coverage coverage = new Coverage(MAY, lines, sources);
        CloseChecks.MonthRuns check = CloseChecks.MonthRuns.judge(coverage);
        long expected = check.notRun().stream().filter(code -> coverage.lines().stream()
                .anyMatch(line -> line.repudiated() && sources.stream().anyMatch(source -> source.code().equals(code) && source.id().equals(line.sourceId()))))
                .count();
        assertThat(gauge(coverage)).isEqualTo(expected).isPositive();
        assertThat(coverage.required()).as("the one list both read").isNotEmpty().allSatisfy(source -> assertThat(source.requiredIn(MAY)).isTrue());
        assertThat(check.notRun()).allSatisfy(code -> assertThat(coverage.required().stream().map(Source::code)).contains(code));
    }

    // ------------------------------------------------------------------

    private static long gauge(Coverage coverage) {
        InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
        periods.insertIfAbsent(CONNECTION, new AccountingPeriodStore.NewPeriod(AccountingPeriodId.next(IDS), MAY, CorrelationId.generate(IDS)));
        periods.closeForTest(MAY);
        return new ClosedMonthRuns(periods, (connection, month) -> {
            assertThat(month).isEqualTo(MAY);
            return coverage;
        }).repudiatedWithoutARun(CONNECTION);
    }

    /** Every source a month can hold, the database's CHECKs respected: a retirement day exactly when RETIRED, never before the admission. */
    private static List<Source> everySource() {
        List<Source> sources = new ArrayList<>();
        List<LocalDate> admissions = List.of(LONG_AGO, LAST, LAST.plusDays(1));
        List<Optional<LocalDate>> ruleSets = List.of(Optional.empty(), Optional.of(LONG_AGO), Optional.of(LAST), Optional.of(LAST.plusDays(1)));
        for (SourceState state : SourceState.values()) {
            List<Optional<LocalDate>> retirements = state == SourceState.RETIRED
                    ? List.of(Optional.of(FIRST.minusDays(1)), Optional.of(FIRST), Optional.of(LAST.plusDays(30)))
                    : List.of(Optional.empty());
            for (LocalDate admitted : admissions) {
                for (Optional<LocalDate> retired : retirements) {
                    if (retired.filter(day -> day.isBefore(admitted)).isPresent()) {
                        continue;
                    }
                    for (Optional<LocalDate> ruleSet : ruleSets) {
                        sources.add(new Source(UUID.randomUUID(), "s" + sources.size() + ".settlement", state, admitted, retired, ruleSet));
                    }
                }
            }
        }
        return sources;
    }

    private static List<Line> lines(Source source, Runs runs) {
        LocalDate day = MAY.atDay(15);
        return switch (runs) {
            case NONE -> List.of();
            case REPUDIATED_ONLY -> List.of(new Line(source.id(), Coverage.BATCH, Coverage.COMPLETED, day, true, 1));
            case REPUDIATED_AND_COUNTING -> List.of(new Line(source.id(), Coverage.BATCH, Coverage.COMPLETED, day, true, 1),
                    new Line(source.id(), Coverage.BATCH, Coverage.COMPLETED, day, false, 1));
            case COUNTING_ONLY -> List.of(new Line(source.id(), Coverage.BATCH, Coverage.COMPLETED, day, false, 1));
            case UNFINISHED_ONLY -> List.of(new Line(source.id(), Coverage.BATCH, "OPEN", day, false, 1));
        };
    }
}
