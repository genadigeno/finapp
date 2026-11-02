package com.finapp.accounting;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The close runs' store (`P14-TSK-011`; {@code accounting V007}): the run, born insert-if-absent per lock instance; its
 * attempts, each born once per {@code (run, check, attempt)}. Every lease on the database's clock; every later move
 * conditional on the status and the {@code holder} that made the claim. No delete exists; an edge trigger judges every
 * writer again.
 */
public interface CloseRunStore {

    /** How many attempts of a check a read serves, newest first - the latest always among them. */
    int HISTORY = 20;

    /** The periods {@code LOCKED} under a lock no run answers yet. Unlocked: it only names them. */
    List<AccountingPeriodId> lockedWithoutRun(Connection unitOfWork);

    /** Births the run unless its lock instance has one; {@code true} when born. */
    boolean insertRunIfAbsent(Connection unitOfWork, NewRun run);

    /** Births one attempt {@code PENDING}, unclaimed, claimable now. */
    void insertAttempt(Connection unitOfWork, NewAttempt attempt);

    /** The run, unlocked. */
    Optional<RunRow> run(Connection unitOfWork, CloseRunId id);

    /** The run answering this lock instance, unlocked. */
    Optional<RunRow> runUnder(Connection unitOfWork, AccountingPeriodId period, Instant lockedAt);

    /** The period's latest run, unlocked - the present lock's, or the last one before an unlock. */
    Optional<RunRow> latestRun(Connection unitOfWork, AccountingPeriodId period);

    /** The run {@code FOR UPDATE} - serialises a run's conclusions and re-runs. */
    Optional<RunRow> lockRun(Connection unitOfWork, CloseRunId id);

    /**
     * One claimable attempt - {@code PENDING}, past its lease, of a run whose period is {@code LOCKED} under that run's
     * lock, and, for {@code OPEN_ACCOUNTING_BREAKS}, with no break-raising check of its run still {@code PENDING} -
     * taken {@code FOR UPDATE SKIP LOCKED} and held by {@code holder} for {@code lease} on the database's clock, in one
     * statement; {@code reclaims + 1} when another claim had lapsed.
     *
     * @return the attempt as claimed - empty when nothing is claimable for this worker
     */
    Optional<AttemptRow> claimNext(Connection unitOfWork, UUID holder, Duration lease);

    /**
     * A live holder's new lease - only while it still holds the attempt and within {@code maxRun} of its claim, on the
     * database's clock; {@code false} otherwise, and the holder stops renewing.
     */
    boolean renew(Connection unitOfWork, CloseCheckId id, UUID holder, Duration lease, Duration maxRun);

    /** Gives the attempt back: no holder, claimable after {@code wait}. {@code false} when no longer held. */
    boolean defer(Connection unitOfWork, CloseCheckId id, UUID holder, Duration wait);

    /** {@code PENDING} held by {@code holder} -> the verdict; {@code false} when it no longer holds it (overtaken). */
    boolean conclude(Connection unitOfWork, CloseCheckId id, UUID holder, CheckVerdict verdict);

    /** The check's latest attempt, unlocked. */
    Optional<AttemptRow> latestAttempt(Connection unitOfWork, CloseRunId run, CloseCheckCode check);

    /** The run's attempts: per check at most {@link #HISTORY}, newest first. */
    List<AttemptRow> attempts(Connection unitOfWork, CloseRunId run);

    /**
     * Stamps the run's checks concluded the first time no attempt of it is {@code PENDING}.
     *
     * @return the time from the run's start to that instant, on the database's clock - empty when this call stamped
     * nothing
     */
    Optional<Duration> markChecksConcluded(Connection unitOfWork, CloseRunId run);

    // ------------------------------------------------------------------ rows

    /** A run to birth. */
    record NewRun(CloseRunId id, AccountingPeriodId period, Instant lockedAt, int periodRevision, CorrelationId correlation) {

        public NewRun {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(lockedAt, "lockedAt");
            Objects.requireNonNull(correlation, "correlation");
        }
    }

    /** An attempt to birth; {@code reason} is a person's re-run's. */
    record NewAttempt(CloseCheckId id, CloseRunId run, CloseCheckCode check, int attempt, Actor requestedBy,
            Optional<String> reason, CorrelationId correlation) {

        public NewAttempt {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(requestedBy, "requestedBy");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(correlation, "correlation");
        }
    }

    /** A run as stored. */
    record RunRow(CloseRunId id, AccountingPeriodId period, YearMonth month, Instant lockedAt, int periodRevision,
            Instant startedAt, Optional<Instant> checksConcludedAt, CorrelationId correlation) {

        public RunRow {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(month, "month");
            Objects.requireNonNull(lockedAt, "lockedAt");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(checksConcludedAt, "checksConcludedAt");
            Objects.requireNonNull(correlation, "correlation");
        }
    }

    /** An attempt as stored - never its requester or reason, which no reader is served. */
    record AttemptRow(CloseCheckId id, CloseRunId run, CloseCheckCode check, int attempt, CloseCheckStatus status,
            int reclaims, Instant bornAt, Optional<Instant> claimedAt, Optional<String> judgedBy, List<String> failures,
            Optional<String> evidence, Optional<Instant> concludedAt, CorrelationId correlation) {

        public AttemptRow {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(bornAt, "bornAt");
            Objects.requireNonNull(claimedAt, "claimedAt");
            Objects.requireNonNull(judgedBy, "judgedBy");
            failures = List.copyOf(failures);
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(concludedAt, "concludedAt");
            Objects.requireNonNull(correlation, "correlation");
        }
    }
}
