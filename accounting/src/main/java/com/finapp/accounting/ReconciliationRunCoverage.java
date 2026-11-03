package com.finapp.accounting;

import com.finapp.accounting.reporting.CanonicalJson;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A month's reconciliation runs (`P14-TSK-017`; the owner's decisions of 2026-11-02 and 2026-11-03) - per source, kind, status, business date and whether its batch
 * was repudiated, over reconciliation's own {@code reconciliation_batch}, with the settlement sources they are measured against, on the caller's connection. {@code RECONCILIATION_STATUS} reports it and
 * {@code RECONCILIATION_DISCLOSURE} reads it (`P14-TSK-012`, {@link CloseChecks}), each on a {@code REPEATABLE READ READ ONLY} snapshot; {@link PeriodCloses} re-judges a month's run on it holding the period row, {@code READ COMMITTED}.
 *
 * <p><strong>Two statements, so on {@code READ COMMITTED} two instants</strong> (the independent review, I-2): the runs, whose repudiation is an {@code EXISTS} in that one statement, so a run and its repudiation are one instant; then the source register. Only {@link
 * CloseChecks.MonthRuns#ran} is judged there, and a register moving between them can misjudge only towards refusing or towards not asking of a source whose door has just shut: a source born between them has no line, so a refusal that a retry clears, and a source retired
 * between them is no longer required, as it would not be a moment later. No run can be counted for a source that did not earn it, and none is hidden. The row-lock-free reads take no lock, so holding a period row beside them closes no cycle. The application role holds {@code SELECT} on all three tables.
 *
 * <p><strong>It judges nothing</strong> (`P14-TSK-017`'s review, I-4): it says which runs and sources exist. What makes a month "run" is the check's, on the owner's ruling.
 *
 * <p>Moving, by nature: a batch for the month's last days is accepted in the next, a run's status moves, a late batch joins
 * the month at any time. So it is an operational read, and a FINAL body may not read it.
 */
@FunctionalInterface
public interface ReconciliationRunCoverage {

    /** The runs whose business date falls in {@code month} and the source register; a read that fails throws, never an empty answer for it. */
    Coverage ofMonth(Connection connection, YearMonth month);

    /**
     * The runs of one source, kind, status, business date and repudiation.
     *
     * @param kind {@code BATCH} (one per accepted batch, born in the acceptance) or {@code REPROCESS} (a person's)
     * @param status {@code OPEN}, {@code IN_PROGRESS}, {@code COMPLETED} or {@code BLOCKED}
     * @param repudiated its batch was repudiated under four eyes: the run stands and its items say {@code REPUDIATED}, so its evidence is withdrawn
     */
    record Line(UUID sourceId, String kind, String status, LocalDate businessDate, boolean repudiated, long runs) {

        public Line {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(businessDate, "businessDate");
        }

        CanonicalJson.Obj asValue() {
            return CanonicalJson.object().put("sourceId", sourceId.toString()).put("kind", kind).put("status", status)
                    .put("businessDate", businessDate.toString()).put("repudiated", repudiated).put("runs", runs);
        }
    }

    /** Where a settlement source stands, for a month's reading. */
    enum SourceState {
        /** {@code settlement.source} {@code ACTIVE} and declared in this build: its evidence can arrive. */
        ACTIVE,
        /** {@code RETIRED}: its door is shut. No date is stored, and today only a migration retires one. */
        RETIRED,
        /** {@code ACTIVE} on its row and declared by no build: no door takes its bytes. */
        UNDECLARED
    }

    /** One settlement source: identity and state, never a position. */
    record Source(UUID id, String code, SourceState state) {

        public Source {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(state, "state");
        }

        /** The content's member for this source. */
        CanonicalJson.Obj asValue() {
            return CanonicalJson.object().put("id", id.toString()).put("code", code).put("state", state.name());
        }
    }

    /** A month's lines, in the read's total order, and the source register by code. */
    record Coverage(YearMonth month, List<Line> lines, List<Source> sources) {

        /** The kind an accepted batch's run carries. */
        public static final String BATCH = "BATCH";

        /** The status that counts: the run reached its end. */
        public static final String COMPLETED = "COMPLETED";

        public Coverage {
            Objects.requireNonNull(month, "month");
            lines = List.copyOf(lines);
            sources = List.copyOf(sources);
        }

        /**
         * How many runs of accepted batches the month holds, <strong>in any status, of any source</strong>: a {@code BLOCKED} or
         * {@code OPEN} run counts, and so does a repudiated batch's, whose run stands. A count, and no verdict on the month.
         */
        public long batchRuns() {
            return lines.stream().filter(line -> BATCH.equals(line.kind())).mapToLong(Line::runs).sum();
        }

        /** The content's {@code coverage} member. */
        public CanonicalJson.Obj asValue() {
            CanonicalJson.Arr runs = CanonicalJson.array();
            lines.forEach(line -> runs.add(line.asValue()));
            CanonicalJson.Arr known = CanonicalJson.array();
            sources.forEach(source -> known.add(source.asValue()));
            return CanonicalJson.object().put("month", month.toString()).put("batchRuns", batchRuns()).put("runs", runs)
                    .put("sources", known);
        }
    }
}
