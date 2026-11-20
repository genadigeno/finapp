package com.finapp.accounting;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The accounting desks' lists (`P17-TSK-023`, gap G-113; finapp-admin-panel B5.4): report runs, GL mapping versions and valuation
 * rate sets, each read a page at a time from the caller's snapshot, forwards from a cursor that carries the whole position - so a
 * row decided between two pages is neither served twice nor skipped, and no state is held between them. {@code SELECT} only; the
 * rows are the stores' own ({@link ReportRunStore.RunRow}, {@link GlMappingStore.VersionRow}, {@link ValuationRateSetStore.SetRow}),
 * read by the same select and mapper as their by-id reads, so a list and a read never describe one row two ways.
 */
public interface AccountingCollections {

    /** Report runs, newest claim first ({@code claimed_at}, then id, descending). */
    List<ReportRunStore.RunRow> reportRuns(Connection unitOfWork, RunFilter filter, Optional<RunCursor> after, int limit);

    /** GL mapping versions, newest version first. */
    List<GlMappingStore.VersionRow> mappingVersions(Connection unitOfWork, Optional<GlMappingVersionStatus> status,
            Optional<Integer> beforeVersion, int limit);

    /** Valuation rate sets, newest capture first ({@code captured_at}, then id, descending). */
    List<ValuationRateSetStore.SetRow> rateSets(Connection unitOfWork, SetFilter filter, Optional<SetCursor> after, int limit);

    // ------------------------------------------------------------------ the pending-decision queues (`P17-TSK-040`)

    /** GL mapping versions {@code PROPOSED}, oldest proposal first ({@code proposed_at}, then id, ascending); no subject. */
    List<PendingRow> pendingMappingVersions(Connection unitOfWork, Optional<PendingCursor> after, int limit);

    /** Valuation rate sets {@code PROPOSED}, oldest capture first ({@code captured_at}, then id); the subject the set's period. */
    List<PendingRow> pendingRateSets(Connection unitOfWork, Optional<PendingCursor> after, int limit);

    /** Close proposals {@code PROPOSED}, oldest first ({@code proposed_at}, then id); the subject the period. */
    List<PendingRow> pendingCloses(Connection unitOfWork, Optional<PendingCursor> after, int limit);

    /** Reopen proposals {@code PROPOSED}, oldest first ({@code proposed_at}, then id); the subject the period. */
    List<PendingRow> pendingReopens(Connection unitOfWork, Optional<PendingCursor> after, int limit);

    /**
     * Unlock proposals standing - a {@code LOCKED} period with an unlock proposed and not approved, {@link
     * AccountingPeriodStore.PeriodRow#unlockPending}'s predicate - oldest first ({@code unlock_proposed_at}, then the period's id). An
     * unlock has no row of its own: its id and its subject are the period's.
     */
    List<PendingRow> pendingUnlocks(Connection unitOfWork, Optional<PendingCursor> after, int limit);

    /**
     * Which runs: of one period, in one status, of some definitions - each absent for any.
     *
     * @param definitions the definition codes admitted; empty admits every definition
     */
    record RunFilter(Optional<AccountingPeriodId> period, Optional<RunStatus> status, Set<String> definitions) {
        public RunFilter {
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(status, "status");
            definitions = Set.copyOf(definitions);
        }
    }

    /** The last run a page served: the next page starts strictly before it. */
    record RunCursor(Instant claimedAt, UUID runId) {}

    /**
     * Which sets: of one period, in one status, captured by one person - each absent for any.
     *
     * @param capturedBy the capturing person's id: the proposer's own list
     */
    record SetFilter(Optional<AccountingPeriodId> period, Optional<ValuationRateSetStatus> status, Optional<String> capturedBy) {
        public SetFilter {
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(capturedBy, "capturedBy");
        }
    }

    /** The last set a page served: the next page starts strictly before it. */
    record SetCursor(Instant capturedAt, UUID setId) {}

    /** The last proposal a pending queue served: the next page starts strictly after it, {@code (proposed_at, id) > (?, ?)}. */
    record PendingCursor(Instant proposedAt, UUID id) {
        public PendingCursor {
            Objects.requireNonNull(proposedAt, "proposedAt");
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * A proposal awaiting a decision, as a queue serves it: its id, its proposer by identifier, when it was proposed and what it acts
     * on. No reason is selected: an accounting proposal's reason is {@code RESTRICTED-PII} and never served
     * ({@code DATA_CLASSIFICATION.md}).
     */
    record PendingRow(UUID id, String proposedBy, Instant proposedAt, Optional<UUID> subject) {
        public PendingRow {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(proposedBy, "proposedBy");
            Objects.requireNonNull(proposedAt, "proposedAt");
            Objects.requireNonNull(subject, "subject");
        }
    }
}
