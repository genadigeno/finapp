package com.finapp.accounting;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Whether a repudiation has taken the latest {@code CLOSED} month's counting run (`P14-TSK-020`; `P14-TSK-012`'s item 7 and owner item (l); {@code PHASE_14_PLAN.md} section 13.5's accepted limit): a batch repudiated
 * after the approval's read, or after {@code CLOSED}, takes no period row, so the month stays closed with no run that counts, and until now only {@code RECONCILIATION_STATUS} said so, when somebody asked.
 *
 * <p><strong>A source the month requires counts only when a repudiated run stands and no run counts</strong>: the check's one rule, {@link ReconciliationRunCoverage.Coverage#required()} (`P14-TSK-025`), so a source the month does not require -
 * undeclared, admitted after its last day, retired before its first, or with no rule set active by its last day - is no gap in the month however its runs read, here as in {@link CloseChecks.MonthRuns#judge}. {@link CloseChecks.MonthRuns#ran} alone would also
 * read false for a required source with no run at all, which lost none: a month closed with it unrun, or one whose requirement a later migration widened. <strong>The latest closed month only</strong>: it is the one a reopen can reach, so the one with a way on; an older month's
 * would hold the alert for good. Two statements of reconciliation's and one of accounting's, on one snapshot, each module's own, compared here. It judges nothing and decides nothing.
 */
@RequiredArgsConstructor
public final class ClosedMonthRuns {

    /** How far back the latest {@code CLOSED} month is looked for: ten years of months, a bound and no policy. */
    static final int MONTHS_LOOKED_AT = 120;

    @NonNull private final AccountingPeriodStore periods;
    @NonNull private final ReconciliationRunCoverage coverage;

    /** How many sources the latest {@code CLOSED} month requires hold a repudiated run and no run that counts; 0 with no such month. */
    public long repudiatedWithoutARun(Connection snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return periods.recent(snapshot, MONTHS_LOOKED_AT).stream().filter(period -> period.status() == PeriodStatus.CLOSED).findFirst()
                .map(period -> lostItsRun(coverage.ofMonth(snapshot, period.month()))).orElse(0L);
    }

    private static long lostItsRun(ReconciliationRunCoverage.Coverage month) {
        return month.required().stream()
                .map(source -> month.lines().stream().filter(line -> line.sourceId().equals(source.id())).toList())
                .filter(ClosedMonthRuns::repudiatedAndNoneCounts).count();
    }

    private static boolean repudiatedAndNoneCounts(List<ReconciliationRunCoverage.Line> own) {
        return own.stream().anyMatch(ReconciliationRunCoverage.Line::repudiated) && own.stream().noneMatch(CloseChecks.MonthRuns::counts);
    }
}
