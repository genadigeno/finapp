package com.finapp.accounting;

import java.sql.Connection;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * What {@code CLOSE_STATUS} reads (`P14-TSK-017`; {@code PHASE_14_PLAN.md} section 13.6) - a port {@code app} implements,
 * because report code reaches no store ({@code ReportingNeverWritesTest}). A period's state, its latest close run with every
 * check's latest attempt, the month's breaks by status and its valuation rate sets. All of it keeps moving after the lock,
 * which is why the report is operational. Read-only, on the caller's {@code REPEATABLE READ READ ONLY} snapshot. No person, no
 * reason, no evidence, no figure.
 */
@FunctionalInterface
public interface CloseStatusReads {

    /**
     * The period's status now.
     *
     * @throws AccountingPeriods.PeriodNotFound when no period has this id
     */
    Reading read(Connection snapshot, AccountingPeriodId period);

    /** One check's latest attempt. */
    record Check(CloseCheckCode check, int attempts, CloseCheckStatus status, List<String> failures,
            Optional<String> judgedBy, Optional<Instant> concludedAt) {

        public Check {
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(status, "status");
            failures = List.copyOf(failures);
            Objects.requireNonNull(judgedBy, "judgedBy");
            Objects.requireNonNull(concludedAt, "concludedAt");
        }
    }

    /**
     * The period's latest run.
     *
     * @param current the period still holds the lock it was born under
     * @param complete a close may be proposed on it, as {@code CloseRuns.Readiness} judges
     */
    record Run(Instant lockedAt, boolean current, boolean complete, Instant startedAt,
            Optional<Instant> checksConcludedAt, List<Check> checks) {

        public Run {
            Objects.requireNonNull(lockedAt, "lockedAt");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(checksConcludedAt, "checksConcludedAt");
            checks = List.copyOf(checks);
        }
    }

    /** The period's approved valuation rate set - the one a translated run names (`P14-TSK-015`'s review, M-4). */
    record ApprovedSet(UUID id, Instant asOf, int rateCount, Optional<Instant> decidedAt) {

        public ApprovedSet {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(decidedAt, "decidedAt");
        }
    }

    /**
     * A period's status, as one snapshot saw it.
     *
     * @param proposedSets the ids of the sets awaiting a decision - {@code VALUATION_SET_APPROVED} waits for one, so no attempt says which (`P14-TSK-012`; `P14-TSK-014`'s review, M-5)
     */
    record Reading(YearMonth month, PeriodStatus status, int revision, Optional<Instant> lockedAt,
            Optional<Integer> pinnedVersion, Optional<Run> run, Map<BreakStatus, Long> breaks,
            Optional<ApprovedSet> approvedSet, SortedMap<String, Long> setsByStatus, List<UUID> proposedSets) {

        public Reading {
            Objects.requireNonNull(month, "month");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(lockedAt, "lockedAt");
            Objects.requireNonNull(pinnedVersion, "pinnedVersion");
            Objects.requireNonNull(run, "run");
            breaks = Map.copyOf(breaks);
            Objects.requireNonNull(approvedSet, "approvedSet");
            setsByStatus = new TreeMap<>(setsByStatus);
            proposedSets = List.copyOf(proposedSets);
        }
    }
}
