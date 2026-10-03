package com.finapp.reconciliation;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A controller's acts on runs (`P8-TSK-022`, ADR-0068 §9; `RECONCILIATION_ADMINISTER`), each in
 * the caller's ONE transaction:
 *
 * <ul>
 *   <li><strong>Reprocessing</strong>: a {@code REPROCESS} run opened over the source's residual
 *       items, pinned to the version ACTIVE now - one open per source by
 *       {@code reconciliation_batch_one_open_reprocess}, so ten racing requests open one run and
 *       nine meet {@code ReprocessingInProgress}. The run leg's reprocess leg works it.
 *   <li><strong>Requeue</strong>: {@code BLOCKED -> IN_PROGRESS} under the run's row lock, its
 *       failures reset; any instance's next tick resumes it. Its {@code RUN_BLOCKED} break stands
 *       until the run completes, which closes it {@code EVIDENCED}; a run that blocks again is
 *       {@code BLOCKED} again with that break still open.
 * </ul>
 */
@RequiredArgsConstructor
public class RunAdministration {

    @NonNull private final MatchingStore matching;
    @NonNull private final ReconciliationRuns runs;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;

    /** No reconciliation run has this id. */
    public static final class RunNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public RunNotFound() {
            super("no reconciliation run has this identifier");
        }
    }

    /** A requeue of a run that is not {@code BLOCKED}. */
    public static final class RunNotBlocked extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public RunNotBlocked() {
            super("only a blocked reconciliation run can be requeued");
        }
    }

    /** A reprocess request while the source's one open {@code REPROCESS} run stands. */
    public static final class ReprocessingInProgress extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public ReprocessingInProgress() {
            super("a reprocessing run is already open for this source");
        }
    }

    /** A person's act without its reason, or a reason past the bound. */
    public static final class ReasonRequired extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public ReasonRequired() {
            super("a controller's act on a run carries a reason of 1..1000 characters");
        }
    }

    public record Reprocessing(UUID runId, UUID ruleSetId, int itemCount) {}

    public record Requeued(UUID runId, RunStatus status) {}

    public Reprocessing requestReprocessing(
            Connection unitOfWork,
            UUID sourceId,
            Actor actor,
            String reason,
            Instant now,
            CorrelationId correlation) {
        String checked = reason(reason);
        if (matching.openReprocessRun(unitOfWork, sourceId).isPresent()) {
            throw new ReprocessingInProgress();
        }
        UUID ruleSetId = matching.activeRuleSetId(unitOfWork, sourceId);
        UUID runId = ids.next();
        // The worklist's size when the run opens - informational: the run's progress is its
        // decisions, and residual items arriving later join it.
        int itemCount = matching.reprocessWorklistSize(unitOfWork, sourceId, runId);
        try {
            runs.birth(
                    unitOfWork,
                    new ReconciliationRuns.NewRun(
                            runId,
                            sourceId,
                            Optional.empty(),
                            RunKind.REPROCESS,
                            ruleSetId,
                            LocalDate.ofInstant(now, ZoneOffset.UTC),
                            Optional.empty(),
                            itemCount,
                            Optional.of(actor.id()),
                            Optional.of(checked),
                            actor,
                            now,
                            correlation,
                            Optional.empty()));
        } catch (ReconciliationStorageException failure) {
            if (failure.getCause() instanceof SQLException sql
                    && "23505".equals(sql.getSQLState())) {
                // A racer opened the source's one REPROCESS run between the read and the insert.
                throw new ReprocessingInProgress();
            }
            throw failure;
        }
        audit(
                unitOfWork, actor, now, ReconciliationAuditAction.REPROCESSING_REQUESTED, runId,
                checked,
                "source=" + sourceId + ", ruleSet=" + ruleSetId + ", items=" + itemCount,
                correlation);
        return new Reprocessing(runId, ruleSetId, itemCount);
    }

    public Requeued requeue(
            Connection unitOfWork,
            UUID runId,
            Actor actor,
            String reason,
            Instant now,
            CorrelationId correlation) {
        String checked = reason(reason);
        MatchingStore.RunRow run =
                matching.lockRun(unitOfWork, runId).orElseThrow(RunNotFound::new);
        if (run.status() != RunStatus.BLOCKED
                || !matching.requeueRun(unitOfWork, runId, actor, checked, now, correlation)) {
            throw new RunNotBlocked();
        }
        audit(
                unitOfWork, actor, now, ReconciliationAuditAction.RUN_REQUEUED, runId, checked,
                "source=" + run.sourceId() + ", failures=" + run.failures(), correlation);
        return new Requeued(runId, RunStatus.IN_PROGRESS);
    }

    private static String reason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new ReasonRequired();
        }
        return reason;
    }

    private void audit(
            Connection unitOfWork,
            Actor actor,
            Instant at,
            ReconciliationAuditAction action,
            UUID runId,
            String reason,
            String summary,
            CorrelationId correlation) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        at,
                        action,
                        "reconciliation_run",
                        runId.toString(),
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation,
                        Optional.of(summary)));
    }
}
