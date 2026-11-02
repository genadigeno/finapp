package com.finapp.accounting;

import com.finapp.accounting.reporting.CanonicalJson;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A month's reconciliation runs (`P14-TSK-017`; the owner's decision of 2026-11-02: "a month with no reconciliation run must
 * fail {@code RECONCILIATION_DISCLOSURE}") - per source, kind, status and business date, over reconciliation's own
 * {@code reconciliation_batch}, on the caller's {@code REPEATABLE READ READ ONLY} snapshot. {@code RECONCILIATION_STATUS} reports it;
 * <strong>{@code P14-TSK-012}'s check reads it</strong>, on its own snapshot, by this port - the check is that task's, and
 * nothing here changes {@code -011}'s.
 *
 * <p>Moving, by nature: a batch for the month's last days is accepted in the next, a run's status moves, a late batch joins
 * the month at any time. So it is an operational read, and a FINAL body may not read it.
 */
@FunctionalInterface
public interface ReconciliationRunCoverage {

    /** The runs whose business date falls in {@code month}; a read that fails throws, never an empty answer for it. */
    Coverage ofMonth(Connection snapshot, YearMonth month);

    /**
     * The runs of one source, kind, status and business date.
     *
     * @param kind {@code BATCH} (one per accepted batch, born in the acceptance) or {@code REPROCESS} (a person's)
     * @param status {@code OPEN}, {@code IN_PROGRESS}, {@code COMPLETED} or {@code BLOCKED}
     */
    record Line(UUID sourceId, String kind, String status, LocalDate businessDate, long runs) {

        public Line {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(businessDate, "businessDate");
        }

        CanonicalJson.Obj asValue() {
            return CanonicalJson.object().put("sourceId", sourceId.toString()).put("kind", kind).put("status", status)
                    .put("businessDate", businessDate.toString()).put("runs", runs);
        }
    }

    /** A month's lines, in the read's total order. */
    record Coverage(YearMonth month, List<Line> lines) {

        /** The kind an accepted batch's run carries. */
        public static final String BATCH = "BATCH";

        public Coverage {
            Objects.requireNonNull(month, "month");
            lines = List.copyOf(lines);
        }

        /** How many runs of accepted batches the month holds. */
        public long batchRuns() {
            return lines.stream().filter(line -> BATCH.equals(line.kind())).mapToLong(Line::runs).sum();
        }

        /** True when no batch of the month was accepted - what the owner's ruling asks {@code -012}'s check to fail. */
        public boolean noRun() {
            return batchRuns() == 0;
        }

        /** The content's {@code coverage} member. */
        public CanonicalJson.Obj asValue() {
            CanonicalJson.Arr runs = CanonicalJson.array();
            lines.forEach(line -> runs.add(line.asValue()));
            return CanonicalJson.object().put("month", month.toString()).put("batchRuns", batchRuns())
                    .put("noRun", noRun()).put("runs", runs);
        }
    }
}
