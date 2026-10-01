package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The reconciliation runs (`P8-TSK-009`, §5.3): one {@code OPEN} run of kind {@code BATCH}
 * per accepted batch, born in the acceptance transaction through {@code AcceptedBatchIntake},
 * and — since `P8-TSK-022` — a controller's keyed {@code REPROCESS} run, born by
 * {@code RunAdministration} pinned to the source's active rule set. The run leg
 * (`P8-TSK-011`) drives a {@code BATCH} run, the reprocess leg (`P8-TSK-022`) a
 * {@code REPROCESS} run. *(Corrected 2026-10-01, `P8-DOC-001`: this read "`P8-TSK-009`
 * produces birth alone" and named no {@code REPROCESS} run.)*
 */
public interface ReconciliationRuns {

    /**
     * Births the run {@code OPEN} with its history row. The arbiters —
     * {@code UNIQUE (batch_id)} and {@code UNIQUE (source_id, source_sequence) WHERE kind =
     * 'BATCH'} — refuse a second run for one acceptance, and acceptance is once by its own
     * conditionals, so a violation here is a programming defect and throws.
     */
    void birth(Connection unitOfWork, NewRun run);

    /** One run at birth: the pinned facts every later decision replays against. */
    record NewRun(
            UUID id,
            UUID sourceId,
            Optional<UUID> batchId,
            RunKind kind,
            UUID ruleSetId,
            LocalDate businessDate,
            Optional<Long> sourceSequence,
            int itemCount,
            Optional<String> requestedBy,
            Optional<String> reason,
            Actor actor,
            Instant at,
            CorrelationId correlation,
            Optional<String> settlementCycle) {

        public NewRun {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(batchId, "batchId must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
            Objects.requireNonNull(businessDate, "businessDate must not be null");
            Objects.requireNonNull(sourceSequence, "sourceSequence must not be null");
            Objects.requireNonNull(requestedBy, "requestedBy must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(at, "at must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            Objects.requireNonNull(settlementCycle, "settlementCycle must not be null");
            settlementCycle.ifPresent(
                    cycle -> {
                        if (cycle.isEmpty() || cycle.length() > 64) {
                            throw new IllegalArgumentException(
                                    "a scheme cycle token is 1..64 characters (V009)");
                        }
                    });
            if (itemCount < 0) {
                throw new IllegalArgumentException("an item count is never negative");
            }
            // The kind decides which identity facts exist (§5.3): a BATCH run is its
            // batch's and its sequence's; a REPROCESS is a person's, reasoned.
            if ((kind == RunKind.BATCH)
                    != (batchId.isPresent() && sourceSequence.isPresent())) {
                throw new IllegalArgumentException(
                        "a BATCH run carries its batch and sequence; a REPROCESS carries"
                                + " neither (SETTLEMENT_AND_RECONCILIATION_LIFECYCLES §5.3)");
            }
            if (kind == RunKind.REPROCESS
                    && (requestedBy.isEmpty() || reason.isEmpty())) {
                throw new IllegalArgumentException(
                        "a REPROCESS run is a person's, keyed and reasoned (§5.3)");
            }
        }

        /** A run of a source with no cycles — the `P8-TSK-009` shape. */
        public NewRun(
                UUID id,
                UUID sourceId,
                Optional<UUID> batchId,
                RunKind kind,
                UUID ruleSetId,
                LocalDate businessDate,
                Optional<Long> sourceSequence,
                int itemCount,
                Optional<String> requestedBy,
                Optional<String> reason,
                Actor actor,
                Instant at,
                CorrelationId correlation) {
            this(id, sourceId, batchId, kind, ruleSetId, businessDate, sourceSequence, itemCount,
                    requestedBy, reason, actor, at, correlation, Optional.empty());
        }
    }
}
