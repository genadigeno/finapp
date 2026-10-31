package com.finapp.accounting;

import com.finapp.accounting.reporting.ReportInputs;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for report runs (`P14-TSK-008`, {@code accounting V004}'s {@code report_run}): the run is born by
 * insert-or-read on the plan's key ({@code UNIQUE (definition, definition_version, period_id, revision,
 * parameters_hash)}), and every later move is a conditional {@code UPDATE} the edge and freeze triggers judge again for
 * every writer - the lease's reclaim on the attempt and an expired lease, the completion and the failure on
 * {@code GENERATING} and the attempt, the supersession on {@code FINAL}. Every stamp and every lease is the database's
 * clock.
 */
public interface ReportRunStore {

    /** The run with the highest revision for {@code key}, unlocked - empty before the first. */
    Optional<RunRow> latest(Connection unitOfWork, RunKey key);

    /** Births the run {@code GENERATING} at attempt 1 unless its key is held; {@code true} when born. */
    boolean insertIfAbsent(Connection unitOfWork, NewRun run);

    /** The run at {@code key} and {@code revision} - the read-back after an insert that found its key held. */
    Optional<RunRow> at(Connection unitOfWork, RunKey key, int revision);

    /** The run, unlocked. */
    Optional<RunRow> find(Connection unitOfWork, ReportRunId id);

    /** The run's content and hash, unlocked - empty while it has none. */
    Optional<Content> content(Connection unitOfWork, ReportRunId id);

    /**
     * {@code GENERATING} at {@code attempt} and past its lease -> {@code GENERATING} at {@code attempt + 1}, leased
     * {@code lease} ahead on the database clock; {@code false} when another reclaimer moved it first, or it is not
     * expired, or not {@code GENERATING}.
     */
    boolean reclaim(Connection unitOfWork, ReportRunId id, int attempt, Duration lease);

    /** Whether the run is {@code GENERATING} and past its lease on the database's clock. */
    boolean pastLease(Connection unitOfWork, ReportRunId id);

    /** Up to {@code limit} {@code GENERATING} runs past their lease, oldest lease first, {@code FOR UPDATE SKIP LOCKED}. */
    List<RunRow> expired(Connection unitOfWork, int limit);

    /**
     * {@code GENERATING} at {@code attempt} -> {@code status} with {@code content} and {@code sha256}, in one statement;
     * {@code false} when the run is no longer {@code GENERATING} at that attempt.
     */
    boolean complete(Connection unitOfWork, ReportRunId id, int attempt, RunStatus status, String content, String sha256);

    /** {@code GENERATING} at {@code attempt} -> {@code FAILED} with {@code failure}; {@code false} otherwise. */
    boolean fail(Connection unitOfWork, ReportRunId id, int attempt, RunFailure failure);

    /** Every {@code FINAL} run of the period -> {@code SUPERSEDED}; how many. */
    int supersedeFinal(Connection unitOfWork, AccountingPeriodId period, CorrelationId correlation);

    // ------------------------------------------------------------------ rows

    /** The plan's run key less its revision: one question. */
    record RunKey(String definition, int definitionVersion, AccountingPeriodId periodId, String parametersHash) {}

    /**
     * A run to birth. {@code period} and {@code mappingVersion} are what a reader shows beside the stored references
     * (the month and the version's number) - the JDBC store joins them, never stores them twice.
     */
    record NewRun(
            ReportRunId id,
            RunKey key,
            int revision,
            YearMonth period,
            int periodRevision,
            RunParameters parameters,
            RunBasis basis,
            Optional<Instant> periodLockedAt,
            Optional<GlMappingVersionId> mappingVersionId,
            Optional<Integer> mappingVersion,
            Actor requestedBy,
            Duration lease,
            CorrelationId correlation) {}

    /** A run's issued content and its hash. */
    record Content(String text, String sha256) {}

    /** A run's row, without its content. */
    record RunRow(
            ReportRunId id,
            RunKey key,
            int revision,
            YearMonth period,
            int periodRevision,
            RunParameters parameters,
            RunBasis basis,
            RunStatus status,
            Optional<Instant> periodLockedAt,
            Optional<GlMappingVersionId> mappingVersionId,
            Optional<Integer> mappingVersion,
            Instant cutAt,
            String requestedBy,
            String requestedByType,
            Instant claimedAt,
            int attempt,
            Instant leaseUntil,
            Optional<String> contentSha256,
            Optional<Instant> completedAt,
            Optional<RunFailure> failure,
            Optional<Instant> supersededAt,
            CorrelationId correlation) {

        /** The definition's code. */
        public String definition() {
            return key.definition();
        }

        /** The definition's version. */
        public int definitionVersion() {
            return key.definitionVersion();
        }

        /** The period. */
        public AccountingPeriodId periodId() {
            return key.periodId();
        }

        /** The pinned valuation rate set, if any. */
        public Optional<UUID> valuationRateSetId() {
            return parameters.valuationRateSet();
        }

        /** What its generation reads - exactly what was pinned. */
        public ReportInputs inputs() {
            return new ReportInputs(key.periodId(), period, periodRevision, basis, periodLockedAt, mappingVersionId,
                    mappingVersion, cutAt, parameters);
        }
    }
}
