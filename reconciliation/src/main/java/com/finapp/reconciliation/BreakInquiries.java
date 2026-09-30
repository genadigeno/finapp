package com.finapp.reconciliation;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The investigator's reads over breaks and the stored identifier chain behind them
 * (`P8-TSK-014`, ADR-0069 §7) — lock-free, reconciliation's own schema only, meant to run
 * inside ONE {@code REPEATABLE READ} transaction so a trace or a case file is one snapshot.
 * Every step follows a stored identifier; nothing is ever joined by time.
 */
public interface BreakInquiries {

    /** The listing's filters; every one optional, {@code agedOver} judged on the database clock. */
    record BreakFilter(
            Optional<BreakType> type,
            Optional<BreakStatus> status,
            Optional<Severity> severity,
            Optional<UUID> sourceId,
            Optional<String> assignee,
            Optional<Integer> agedOverDays) {

        public BreakFilter {
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(severity, "severity must not be null");
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(assignee, "assignee must not be null");
            Objects.requireNonNull(agedOverDays, "agedOverDays must not be null");
        }
    }

    /** The newest breaks matching {@code filter}, at most {@code limit}. */
    List<BreakCaseStore.BreakRow> breaks(Connection unitOfWork, BreakFilter filter, int limit);

    Optional<BreakCaseStore.BreakRow> breakById(Connection unitOfWork, UUID breakId);

    record EventRow(
            long seq,
            String eventType,
            String actor,
            String actorType,
            Optional<String> reason,
            Optional<String> detail,
            Instant occurredAt) {}

    List<EventRow> history(Connection unitOfWork, UUID breakId);

    /** A note, body included — CONFIDENTIAL, served only to an authorised investigator. */
    record NoteRow(UUID id, String body, String author, String authorType, Instant addedAt) {}

    List<NoteRow> notes(Connection unitOfWork, UUID breakId);

    record LinkRow(
            UUID id,
            String targetKind,
            String targetRef,
            String addedBy,
            String addedByType,
            Instant addedAt) {}

    List<LinkRow> links(Connection unitOfWork, UUID breakId);

    record ResolutionRow(
            UUID id,
            String kind,
            String status,
            String reasonCode,
            String narrative,
            long proposedAmountMinor,
            String currency,
            int scale,
            Optional<UUID> decisionId,
            Optional<UUID> parkId,
            Optional<UUID> adjustmentProposalId,
            Optional<UUID> journalEntryId,
            String proposedBy,
            String proposedByType,
            Instant proposedAt,
            Optional<String> decidedBy,
            Optional<Instant> decidedAt) {}

    List<ResolutionRow> resolutions(Connection unitOfWork, UUID breakId);

    /** The breaks this one continues, nearest first ({@code follows_break_id}, ADR-0069 §4). */
    List<BreakCaseStore.BreakRow> predecessors(Connection unitOfWork, UUID breakId);

    // ------------------------------------------------------------------ the chain

    record ItemLink(UUID itemId, UUID runId, UUID settlementLineId) {}

    Optional<ItemLink> item(Connection unitOfWork, UUID itemId);

    /** A run's settlement batch — absent for a run no batch carries. */
    Optional<UUID> batchOfRun(Connection unitOfWork, UUID runId);

    List<UUID> decisionsOfItem(Connection unitOfWork, UUID itemId);

    /** The item a decision decided. */
    Optional<UUID> itemOfDecision(Connection unitOfWork, UUID decisionId);

    record AllocationLink(UUID allocationId, UUID decisionId, UUID itemId, UUID expectationId) {}

    List<AllocationLink> allocationsOfItem(Connection unitOfWork, UUID itemId);

    List<AllocationLink> allocationsOfDecision(Connection unitOfWork, UUID decisionId);

    List<AllocationLink> allocationsOfExpectation(
            Connection unitOfWork, UUID expectationId, int limit);

    record SuspenseLink(
            UUID suspenseItemId,
            UUID breakId,
            Optional<UUID> externalItemId,
            Optional<UUID> parkId,
            UUID entryId,
            String origin,
            String originRef) {}

    List<SuspenseLink> suspenseOfItem(Connection unitOfWork, UUID itemId);

    Optional<SuspenseLink> suspenseItem(Connection unitOfWork, UUID suspenseItemId);

    /** The parks (unparks) that released a suspense item, each with its entry. */
    record ParkLink(UUID parkId, UUID entryId) {}

    List<ParkLink> releasesOf(Connection unitOfWork, UUID suspenseItemId);

    Optional<UUID> entryOfPark(Connection unitOfWork, UUID parkId);

    record ExpectationLink(
            UUID expectationId,
            ExpectationKind kind,
            String operationRef,
            Optional<UUID> journalEntryId) {}

    Optional<ExpectationLink> expectation(Connection unitOfWork, UUID expectationId);
}
