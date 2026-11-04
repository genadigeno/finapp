package com.finapp.accounting;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A period's approved close at a revision, as a statement's note reads it (`P14-TSK-016`, on `P14-TSK-012`'s note; {@code INV-ACC-04}, {@code INV-ACC-05}, {@code INV-ACC-09}): {@code period_close}, decided once and then
 * frozen for every writer, and its nine {@code period_close_check} rows, insert-only - so under {@code CLOSED} at that revision this cannot move. It <em>does</em> move between a lock and its close, which is why a statement is
 * refused until then ({@code ReportDefinition.issuedOnlyWhenClosed}).
 *
 * <p><strong>Codes, counts and ids only.</strong> No person, no reason and no evidence is selected: {@code period_close_check.disclosure_reason} is {@code CONFIDENTIAL} and served at one door, under {@code ACCOUNTING_CLOSE_APPROVE}
 * ({@code GET .../close-proposal}), while a statement is read under {@code ACCOUNTING_READ}, so a note quoting it would hand it to a role that may not read it. Read-only, on the caller's snapshot.
 */
@FunctionalInterface
public interface CloseRecordReads {

    /** The approved close of {@code period} at {@code revision}, with what stands beside it; empty when none was approved at it. */
    Optional<CloseRecord> ofRevision(Connection snapshot, AccountingPeriodId period, int revision);

    /** One check as the close read it: the attempt, frozen. Never its reason or its evidence. */
    record Check(String check, String status, int attempt, UUID attemptId, String judgedBy) {

        public Check {
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(judgedBy, "judgedBy");
        }
    }

    /** An approved close of an earlier revision of the period: it stands beside this one, and is never rewritten. */
    record EarlierClose(int revision, UUID close, Instant decidedAt) {

        public EarlierClose {
            Objects.requireNonNull(close, "close");
            Objects.requireNonNull(decidedAt, "decidedAt");
        }
    }

    /**
     * The close record.
     *
     * @param lockedAt the lock instance it answers - a run names the same, or the record is not its
     * @param acknowledged the month's {@code ACKNOWLEDGED} accounting breaks at the proposal
     * @param resolved the month's {@code RESOLVED} accounting breaks at the proposal
     * @param checks the nine, in code order
     * @param earlier the approved closes of earlier revisions, oldest first
     */
    record CloseRecord(UUID close, int revision, Instant lockedAt, UUID closeRun, UUID pinnedMappingVersion, UUID valuationRateSet, long acknowledged, long resolved,
            Instant proposedAt, Instant decidedAt, List<Check> checks, List<EarlierClose> earlier) {

        public CloseRecord {
            Objects.requireNonNull(close, "close");
            Objects.requireNonNull(lockedAt, "lockedAt");
            Objects.requireNonNull(closeRun, "closeRun");
            Objects.requireNonNull(pinnedMappingVersion, "pinnedMappingVersion");
            Objects.requireNonNull(valuationRateSet, "valuationRateSet");
            Objects.requireNonNull(proposedAt, "proposedAt");
            Objects.requireNonNull(decidedAt, "decidedAt");
            checks = List.copyOf(checks);
            earlier = List.copyOf(earlier);
        }
    }
}
