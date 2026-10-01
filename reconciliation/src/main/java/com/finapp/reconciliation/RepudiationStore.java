package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The repudiation's persistence (`P8-TSK-023`, ADR-0065 §10): the rows a batch's repudiation
 * derives its plan from — read unlocked at the proposal, re-read LOCKED in the Phase 8 order at
 * the approval, so the same queries say what the approver must find unchanged — and the
 * append-only writes it is made of. Nothing here edits history: a counter-allocation mirrors its
 * original, an item and an expectation move along their machines, a closure is a new row.
 */
public interface RepudiationStore {

    /** The batch's own run: one per accepted batch ({@code UNIQUE (batch_id)}). */
    record RunFacts(UUID runId, UUID sourceId, UUID ruleSetId) {}

    /** An external item as the plan needs it. */
    record ItemRow(
            UUID id,
            UUID runId,
            UUID sourceId,
            ItemStatus status,
            String lineType,
            long amountMinor,
            long allocatedMinor,
            long parkedMinor,
            String currency,
            int scale) {}

    /** A standing allocation: not a counter, and not yet countered. */
    record AllocationRow(
            UUID id,
            UUID decisionId,
            UUID externalItemId,
            UUID expectationId,
            long amountMinor,
            String currency,
            int scale) {}

    record ExpectationRow(
            UUID id,
            String kind,
            ExpectationStatus status,
            long amountMinor,
            long allocatedMinor,
            long resolvedMinor,
            UUID sourceId,
            String currency,
            int scale) {

        public long remainderMinor() {
            return amountMinor - allocatedMinor - resolvedMinor;
        }
    }

    /**
     * A suspense item the repudiation reaches, with how much of its released value left
     * suspense to somewhere else ({@code RESOLUTION}, {@code OFFSET_SUSPENSE}) — value a late
     * allocation's unpark or a correction's offset released went back to the position, and
     * needs no answer.
     */
    record SuspenseRow(
            UUID id,
            UUID breakId,
            UUID breakSourceId,
            Optional<UUID> externalItemId,
            SuspenseOrigin origin,
            SuspenseSide side,
            long amountMinor,
            long releasedMinor,
            long releasedElsewhereMinor,
            String currency,
            int scale,
            Optional<UUID> positionAccountId) {

        public long unreleasedMinor() {
            return amountMinor - releasedMinor;
        }
    }

    record BreakRef(UUID id, UUID sourceId, BreakStatus status) {}

    /** A repudiation as stored. */
    record RepudiationRow(
            UUID id,
            UUID settlementBatchId,
            ResolutionStatus status,
            ResolutionReasonCode reasonCode,
            String proposedBy,
            Optional<String> decidedBy,
            byte[] subjectDigest,
            Optional<UUID> journalEntryId,
            UUID ruleSetId) {}

    record NewRepudiation(
            UUID id,
            UUID settlementBatchId,
            String narrative,
            Money proposedAmount,
            UUID ruleSetId,
            byte[] subjectDigest,
            Actor proposedBy,
            Instant at,
            CorrelationId correlation) {}

    // ------------------------------------------------------------------ the plan's reads

    Optional<RunFacts> runOf(Connection unitOfWork, UUID settlementBatchId);

    List<ItemRow> itemsOfRun(Connection unitOfWork, UUID runId, boolean lock);

    List<ItemRow> itemsById(Connection unitOfWork, Collection<UUID> itemIds, boolean lock);

    List<AllocationRow> standingAllocationsOfRun(Connection unitOfWork, UUID runId);

    /** The batch's own {@code REMITTANCE} expectation (operation_ref = the batch id). */
    Optional<UUID> remittanceOf(Connection unitOfWork, UUID settlementBatchId);

    /** Standing allocations TO {@code expectationId} by items of other runs. */
    List<AllocationRow> standingAllocationsTo(
            Connection unitOfWork, UUID expectationId, UUID excludingRunId);

    List<ExpectationRow> expectationsById(
            Connection unitOfWork, Collection<UUID> expectationIds, boolean lock);

    List<SuspenseRow> suspenseOfItems(
            Connection unitOfWork, Collection<UUID> itemIds, boolean lock);

    List<UUID> decisionsOfItems(Connection unitOfWork, Collection<UUID> itemIds);

    /**
     * The open breaks the repudiation empties: on the items, the run, the remittance, the
     * suspense items it releases (and their owners), and any break a decision on the items
     * raised.
     */
    List<BreakRef> openBreaks(
            Connection unitOfWork,
            Collection<UUID> itemIds,
            UUID runId,
            Optional<UUID> remittanceId,
            Collection<UUID> suspenseItemIds,
            Collection<UUID> ownerBreakIds,
            Collection<UUID> decisionIds);

    /** The grace a reopened item waits under: its source's active rule for its line type. */
    OptionalInt graceHours(Connection unitOfWork, UUID sourceId, String lineType);

    // ------------------------------------------------------------------ the resolution

    /**
     * @throws ResolutionStore.OneLiveProposal when the batch already carries a live proposal
     */
    void insert(Connection unitOfWork, NewRepudiation repudiation);

    /** Empty for an unknown id or a break-subject resolution. */
    Optional<RepudiationRow> byId(Connection unitOfWork, UUID resolutionId, boolean lock);

    /** Whether the resolution is a batch's repudiation (lock-free routing read). */
    boolean isRepudiation(Connection unitOfWork, UUID resolutionId);

    // ------------------------------------------------------------------ the writes

    void insertCounter(
            Connection unitOfWork,
            AllocationRow original,
            UUID counterId,
            Instant at,
            CorrelationId correlation);

    /** The counter's expectation half: the remainder restored, {@code REOPENED} recorded. */
    void reopenExpectation(
            Connection unitOfWork,
            UUID expectationId,
            long amountMinor,
            String detail,
            Actor actor,
            Instant at,
            CorrelationId correlation);

    /**
     * The item's half: allocation and parking lowered, the status moved conditionally (or left,
     * when {@code to} equals {@code from}), a fresh grace when {@code graceHours} is present.
     */
    void moveItem(
            Connection unitOfWork,
            UUID itemId,
            ItemStatus from,
            ItemStatus to,
            long counteredMinor,
            long unparkedMinor,
            OptionalInt graceHours,
            Actor actor,
            Instant at,
            CorrelationId correlation);

    void insertClosure(
            Connection unitOfWork,
            UUID breakId,
            UUID resolutionId,
            Instant at,
            CorrelationId correlation);
}
