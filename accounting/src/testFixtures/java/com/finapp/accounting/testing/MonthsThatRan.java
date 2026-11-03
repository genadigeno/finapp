package com.finapp.accounting.testing;

import com.finapp.accounting.ReconciliationRunCoverage;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * {@link ReconciliationRunCoverage} for a suite whose subject is something else: one active source, one {@code COMPLETED} {@code BATCH} run a month. What a month's run is, and the real read, are {@code
 * CloseChecksTest}'s, {@code PeriodClosesTest}'s and {@code QuietMonthDatabaseTest}'s.
 */
public final class MonthsThatRan {

    private static final UUID SOURCE = UUID.nameUUIDFromBytes("months-that-ran".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    /** Every month ran. */
    public static final ReconciliationRunCoverage EVERY_MONTH = (connection, month) -> ran(month);

    private MonthsThatRan() {}

    /** {@code month}, run. */
    public static ReconciliationRunCoverage.Coverage ran(YearMonth month) {
        return new ReconciliationRunCoverage.Coverage(month, List.of(new ReconciliationRunCoverage.Line(SOURCE, ReconciliationRunCoverage.Coverage.BATCH, ReconciliationRunCoverage.Coverage.COMPLETED, month.atDay(1), false, 1)),
                List.of(new ReconciliationRunCoverage.Source(SOURCE, "simulated-psp.settlement", ReconciliationRunCoverage.SourceState.ACTIVE)));
    }
}
