package com.finapp.reconciliation;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The investigator's reads over the expectation register (`P8-TSK-014`): the bounded listing,
 * one expectation whole, and the stored references its settlement status is derived from —
 * lock-free, reconciliation's own schema, meant to run inside one {@code REPEATABLE READ}
 * transaction.
 */
public interface ExpectationInquiries {

    record ExpectationFilter(
            Optional<ExpectationStatus> status,
            Optional<Boolean> overdue,
            Optional<UUID> sourceId) {

        public ExpectationFilter {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(overdue, "overdue must not be null");
            Objects.requireNonNull(sourceId, "sourceId must not be null");
        }
    }

    record ExpectationRow(
            UUID id,
            ExpectationKind kind,
            String operationRef,
            String postingKey,
            UUID sourceId,
            String positionPurpose,
            UUID ledgerAccountId,
            ExpectationDirection direction,
            long amountMinor,
            String currency,
            int scale,
            Optional<UUID> journalEntryId,
            LocalDate postingDate,
            Optional<String> settlementCycle,
            LocalDate expectedBy,
            UUID ruleSetId,
            ExpectationStatus status,
            long allocatedMinor,
            long resolvedMinor,
            Optional<Instant> overdueSince,
            Instant openedAt,
            Instant statusChangedAt) {}

    /**
     * Where a page of the listing resumes (`P17-TSK-122`, `API_CONVENTIONS.md` section 7): after the expectation whose
     * {@code (opened_at, id)} this is, in the listing's newest-first order. Both are fixed at the expectation's opening (V002's
     * trigger refuses a changed opened_at), so a page boundary never moves while expectations open or settle around it.
     */
    record ExpectationCursor(Instant openedAt, UUID id) {

        public ExpectationCursor {
            Objects.requireNonNull(openedAt, "openedAt must not be null");
            Objects.requireNonNull(id, "id must not be null");
        }
    }

    /**
     * The expectations matching {@code filter}, at most {@code limit}: opened newest first, the id breaking the tie
     * (descending too), so the order is total; strictly after {@code after} when one is named - a keyset read, never an
     * offset.
     */
    List<ExpectationRow> expectations(
            Connection unitOfWork, ExpectationFilter filter, Optional<ExpectationCursor> after, int limit);

    Optional<ExpectationRow> expectationById(Connection unitOfWork, UUID expectationId);

    /** The one expectation tracking (kind, operation) — {@code UNIQUE (kind, operation_ref)}. */
    Optional<ExpectationRow> expectationByOperation(
            Connection unitOfWork, ExpectationKind kind, String operationRef);

    record KeyRow(String keyKind, String keyValue) {}

    List<KeyRow> keys(Connection unitOfWork, UUID expectationId);

    record EventRow(
            long seq,
            String eventType,
            Optional<String> detail,
            String actor,
            String actorType,
            Instant occurredAt) {}

    List<EventRow> events(Connection unitOfWork, UUID expectationId);

    record AllocationRow(
            UUID id,
            UUID decisionId,
            UUID externalItemId,
            long amountMinor,
            Optional<UUID> reversesAllocationId,
            Instant createdAt) {}

    List<AllocationRow> allocations(Connection unitOfWork, UUID expectationId, int limit);

    record BreakRef(UUID id, String type, String status, String severity) {}

    List<BreakRef> breaksOn(Connection unitOfWork, UUID expectationId);

    /** An external item's run and that run's report batch. */
    record ItemRun(UUID itemId, UUID runId, Optional<UUID> batchId) {}

    Optional<ItemRun> itemRun(Connection unitOfWork, UUID itemId);

    /** The {@code REMITTANCE} a report batch opened — its {@code operation_ref} is the batch id. */
    Optional<ExpectationRow> remittanceOfBatch(Connection unitOfWork, UUID batchId);
}
