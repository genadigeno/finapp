package com.finapp.accounting;

import com.finapp.accounting.reporting.CanonicalJson;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A month's reconciliation runs (`P14-TSK-017`; the owner's decisions of 2026-11-02 and 2026-11-03) - per source, kind, status, business date and whether its batch
 * was repudiated, over reconciliation's own {@code reconciliation_batch}, with the settlement sources they are measured against, on the caller's connection. {@code RECONCILIATION_STATUS} reports it and
 * {@code RECONCILIATION_DISCLOSURE} reads it (`P14-TSK-012`, {@link CloseChecks}), each on a {@code REPEATABLE READ READ ONLY} snapshot; {@link PeriodCloses} re-judges a month's run on it holding the period row, {@code READ COMMITTED}.
 *
 * <p><strong>Two statements, so on {@code READ COMMITTED} two instants</strong> (the independent review, I-2): the runs, whose repudiation is an {@code EXISTS} in that one statement, so a run and its repudiation are one instant; then the source register with
 * each source's rule sets, one statement, so a source and its rule sets are one instant. Only {@link CloseChecks.MonthRuns#ran} is judged there, and a register moving between them can misjudge only towards refusing: a source a migration admits between them, dated
 * in or before the month, has no line, so a refusal its run clears. A retirement between them leaves the month required, being dated on or after its first day, and a rule set activated between them is dated after any month that has ended (`P14-TSK-025`).
 * No run can be counted for a source that did not earn it, and none is hidden. Plain reads take no row lock, so holding a period row beside them closes no cycle. The application role holds {@code SELECT} on all four tables.
 *
 * <p><strong>What a month requires</strong> (`P14-TSK-025`; the owner's rulings of 2026-11-06 on `P14-TSK-012`'s (c), (e) and (k)): a run of each source {@link Source#requiredIn requiredIn} it, the one rule {@link
 * CloseChecks.MonthRuns#judge}, {@link PeriodCloses}' re-judge and {@link ClosedMonthRuns} read through {@link Coverage#required()}. Dated, so for a month that has ended the answer does not move: an admission only moves earlier, a retirement only later, and a
 * rule set's first activation keeps its instant.
 *
 * <p><strong>It judges nothing</strong> (`P14-TSK-017`'s review, I-4): it says which runs and sources exist, and which sources the month requires. What makes a month "run" is the check's, on the owner's ruling.
 *
 * <p>Moving, by nature: a batch for the month's last days is accepted in the next, a run's status moves, a late batch joins
 * the month at any time. So it is an operational read, and a FINAL body may not read it.
 */
@FunctionalInterface
public interface ReconciliationRunCoverage {

    /** The runs whose business date falls in {@code month} and the source register with each source's days; a read that fails throws, never an empty answer for it. */
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

    /** Where a settlement source's row stands today, beside this build. Which months it served is its dates' ({@link Source#requiredIn}). */
    enum SourceState {
        /** {@code settlement.source} {@code ACTIVE} and declared in this build: its evidence can arrive. */
        ACTIVE,
        /** {@code RETIRED} since {@link Source#retiredOn()}: its door is shut, and the months up to that day still require its run. */
        RETIRED,
        /** {@code ACTIVE} on its row and declared by no build: no door takes its bytes, so no month requires it. */
        UNDECLARED
    }

    /**
     * One settlement source: identity, state and the days that say which months it served, never a position.
     *
     * @param admittedOn {@code settlement.source.admitted_on}: the first day it served (settlement {@code V018}; the eight seeded sources {@code 1970-01-01}, before any month)
     * @param retiredOn {@code settlement.source.retired_on}: the day it was retired, present exactly when {@code RETIRED}
     * @param ruleSetActiveOn the UTC day its first rule set version stood {@code ACTIVE} - a person's four-eyes activation - and for reconciliation {@code V002}'s
     *     seeded versions its admission, the seed being born with its source; empty while none ever has (D26)
     */
    record Source(UUID id, String code, SourceState state, LocalDate admittedOn, Optional<LocalDate> retiredOn, Optional<LocalDate> ruleSetActiveOn) {

        /** The day before any month the platform holds - the backfill of settlement {@code V018}, and {@code PostingRuleRegister.ORIGIN}'s "since the beginning". */
        public static final LocalDate SINCE_THE_BEGINNING = LocalDate.EPOCH;

        public Source {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(admittedOn, "admittedOn");
            Objects.requireNonNull(retiredOn, "retiredOn");
            Objects.requireNonNull(ruleSetActiveOn, "ruleSetActiveOn");
            if ((state == SourceState.RETIRED) != retiredOn.isPresent()) {
                throw new IllegalArgumentException("a source names its retirement day exactly when it is RETIRED (settlement V018's CHECK)");
            }
            if (retiredOn.filter(day -> day.isBefore(admittedOn)).isPresent()) {
                throw new IllegalArgumentException("a source is retired on or after its admission");
            }
        }

        /**
         * A source required in every month: admitted and its rule set active since the beginning, never retired - for a suite whose subject is something else.
         *
         * @throws IllegalArgumentException for {@code RETIRED}, which names its day
         */
        public Source(UUID id, String code, SourceState state) {
            this(id, code, state, SINCE_THE_BEGINNING, Optional.empty(), Optional.of(SINCE_THE_BEGINNING));
        }

        /**
         * <strong>The one rule</strong> (`P14-TSK-025`): {@code month} requires a run of this source when this build declares it, it was admitted by the month's last day,
         * it was not retired before the month's first, and its rule set stood active by the month's last day. A retired source that served part of the month is required
         * for it; a source with no rule set has no run to give.
         */
        public boolean requiredIn(YearMonth month) {
            Objects.requireNonNull(month, "month");
            LocalDate first = month.atDay(1);
            LocalDate last = month.atEndOfMonth();
            return state != SourceState.UNDECLARED
                    && !admittedOn.isAfter(last)
                    && retiredOn.map(day -> !day.isBefore(first)).orElse(true)
                    && ruleSetActiveOn.map(day -> !day.isAfter(last)).orElse(false);
        }

        /** The content's member for this source in {@code month}. */
        CanonicalJson.Obj asValue(YearMonth month) {
            CanonicalJson.Obj value = CanonicalJson.object().put("id", id.toString()).put("code", code).put("state", state.name());
            putDays(value);
            return value.put("required", requiredIn(month));
        }

        /** Its three days, a day as text and an absent one as null. */
        void putDays(CanonicalJson.Obj value) {
            value.put("admittedOn", admittedOn.toString());
            retiredOn.ifPresentOrElse(day -> value.put("retiredOn", day.toString()), () -> value.putNull("retiredOn"));
            ruleSetActiveOn.ifPresentOrElse(day -> value.put("ruleSetActiveOn", day.toString()), () -> value.putNull("ruleSetActiveOn"));
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

        /** The sources this month requires a run of, in the register's order: {@link Source#requiredIn}, the one list the check, the re-judge and the gauge read. */
        public List<Source> required() {
            return sources.stream().filter(source -> source.requiredIn(month)).toList();
        }

        /** The content's {@code coverage} member. */
        public CanonicalJson.Obj asValue() {
            CanonicalJson.Arr runs = CanonicalJson.array();
            lines.forEach(line -> runs.add(line.asValue()));
            CanonicalJson.Arr known = CanonicalJson.array();
            sources.forEach(source -> known.add(source.asValue(month)));
            return CanonicalJson.object().put("month", month.toString()).put("batchRuns", batchRuns()).put("runs", runs)
                    .put("sources", known);
        }
    }
}
