package com.finapp.accounting;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the accounting periods (`P14-TSK-005`, {@code accounting V003}): the machine row
 * ({@code accounting_period}, one per calendar month, contiguous; its act columns the only ones that move, each by a
 * conditional {@code UPDATE ... WHERE status = :from AND revision = :rev}) and its history ({@code period_event},
 * append-only); and an unlock's end (`P14-TSK-024`, {@code accounting V011}'s {@code period_unlock_end}, INSERT-only).
 *
 * <p>The lock order (the plan's section 8.2, A1): the previous period {@link #lockShared FOR SHARE}, then the period
 * {@link #lock FOR UPDATE} - oldest first.
 */
public interface AccountingPeriodStore {

    /** Births the month's period {@code OPEN} unless the month already has one; {@code true} when born. */
    boolean insertIfAbsent(Connection unitOfWork, NewPeriod period);

    /** The latest period's month - empty before the first. */
    Optional<YearMonth> latest(Connection unitOfWork);

    /** The period, unlocked. */
    Optional<PeriodRow> find(Connection unitOfWork, AccountingPeriodId id);

    /** The period {@code FOR UPDATE}. */
    Optional<PeriodRow> lock(Connection unitOfWork, AccountingPeriodId id);

    /** The month's period {@code FOR SHARE} - the previous period of a lock or an unlock; empty when it has none. */
    Optional<PeriodRow> lockShared(Connection unitOfWork, YearMonth month);

    /** The earliest {@code OPEN} period - where a posting dated today, or a correction, lands. Unlocked. */
    Optional<PeriodRow> firstOpen(Connection unitOfWork);

    /** The earliest period not yet {@code CLOSED} - the close's backlog. Unlocked. */
    Optional<PeriodRow> oldestUnclosed(Connection unitOfWork);

    /** Today, UTC, on the database's clock - what {@code finapp.accounting.period.open.age} measures from, so every instance reads one number (`P14-TSK-020`). */
    LocalDate today(Connection unitOfWork);

    /** The latest {@code limit} periods, newest first. Unlocked. */
    List<PeriodRow> recent(Connection unitOfWork, int limit);

    /** {@code OPEN -> LOCKED} at {@code revision}, the pin recorded; {@code false} when the row was not there. */
    boolean markLocked(Connection unitOfWork, AccountingPeriodId id, int revision, Actor actor, Optional<Pin> pin);

    /** Records the unlock proposal on a {@code LOCKED} period with none pending; {@code false} otherwise. */
    boolean proposeUnlock(Connection unitOfWork, AccountingPeriodId id, int revision, Actor actor, String reason);

    /** {@code LOCKED -> OPEN} by the pending proposal's approval; {@code false} when nothing was pending. */
    boolean approveUnlock(Connection unitOfWork, AccountingPeriodId id, int revision, Actor actor, String reason);

    /**
     * Ends the pending unlock without its effect (`P14-TSK-024`): its end's row copied from the period row - the lock instance, the revision, the proposer and the instant it was proposed, never
     * round-tripped through here - then the proposal cleared from that row, the period still {@code LOCKED} under the same lock and pin. {@code accounting V011} judges both statements for every writer.
     *
     * @return {@code false} when the row holds no pending proposal by {@code end.proposedBy()} at {@code end.revision()}, and nothing was written
     */
    boolean endUnlock(Connection unitOfWork, NewUnlockEnd end);

    /** The latest end of an unlock under the period's present lock, by the database's stamp; empty when the period is not locked or none was ended under it. Unlocked. */
    Optional<UnlockEndRow> latestUnlockEnd(Connection unitOfWork, AccountingPeriodId id);

    /** {@code LOCKED -> CLOSE_PROPOSED} at {@code revision}, no unlock pending (`P14-TSK-012`); {@code false} when the row was not there. */
    boolean markCloseProposed(Connection unitOfWork, AccountingPeriodId id, int revision);

    /** {@code CLOSE_PROPOSED -> CLOSED} at {@code revision}, the lock and its pin as they were; {@code false} when the row was not there. */
    boolean markClosed(Connection unitOfWork, AccountingPeriodId id, int revision);

    /** {@code CLOSE_PROPOSED -> LOCKED} at {@code revision}, the same lock; {@code false} when the row was not there. */
    boolean markCloseRejected(Connection unitOfWork, AccountingPeriodId id, int revision);

    /** {@code CLOSED -> OPEN}, {@code revision + 1}, the lock and its pin released; {@code false} when the row was not there. */
    boolean markReopened(Connection unitOfWork, AccountingPeriodId id, int revision);

    /** Appends one history row. */
    void appendEvent(Connection unitOfWork, EventRow row);

    // ------------------------------------------------------------------ rows

    /** The acts the history records - each on its one edge ({@code period_event_act_is_an_edge}). */
    enum Act {
        OPENED,
        LOCKED,
        UNLOCK_PROPOSED,
        UNLOCKED,
        CLOSE_PROPOSED,
        CLOSED,
        CLOSE_REJECTED,
        REOPEN_PROPOSED,
        REOPENED,
        /** A pending unlock rejected by a second person (`P14-TSK-024`): {@code LOCKED -> LOCKED}, its proposer named. */
        UNLOCK_REJECTED,
        /** A pending unlock withdrawn by its proposer (`P14-TSK-024`): {@code LOCKED -> LOCKED}, its proposer named, who is the actor. */
        UNLOCK_WITHDRAWN,
        /** A pending reopen rejected by a second person (`P14-TSK-024`): {@code CLOSED -> CLOSED}, its proposer named. */
        REOPEN_REJECTED,
        /** A pending reopen withdrawn by its proposer (`P14-TSK-024`): {@code CLOSED -> CLOSED}, its proposer named, who is the actor. */
        REOPEN_WITHDRAWN
    }

    /** A period to birth. */
    record NewPeriod(AccountingPeriodId id, YearMonth month, CorrelationId correlation) {}

    /** The GL mapping version a lock pinned: its id and number. */
    record Pin(GlMappingVersionId id, int version) {}

    /** A period's machine row - no person but the unlock's two (the four-eyes judgement), no reason. */
    record PeriodRow(
            AccountingPeriodId id,
            YearMonth month,
            PeriodStatus status,
            int revision,
            Instant openedAt,
            Optional<Instant> lockedAt,
            Optional<Pin> pinned,
            Optional<String> unlockProposedBy,
            Optional<Instant> unlockProposedAt,
            Optional<String> unlockApprovedBy,
            Optional<Instant> unlockApprovedAt) {

        /** The first day. */
        public LocalDate start() {
            return month.atDay(1);
        }

        /** The last day - where the lock puts the ledger's cutoff. */
        public LocalDate end() {
            return month.atEndOfMonth();
        }

        /** {@code LOCKED} with an unlock proposed and not yet approved. */
        public boolean unlockPending() {
            return status == PeriodStatus.LOCKED && unlockProposedBy.isPresent() && unlockApprovedBy.isEmpty();
        }
    }

    /**
     * An unlock's end to record (`P14-TSK-024`).
     *
     * @param proposedBy the proposer the act judged against - the statement ends only that person's pending proposal
     */
    record NewUnlockEnd(UUID id, AccountingPeriodId period, int revision, String proposedBy, PeriodRequestEnd outcome, Actor endedBy, String reason, CorrelationId correlation) {

        public NewUnlockEnd {
            java.util.Objects.requireNonNull(id, "id");
            java.util.Objects.requireNonNull(period, "period");
            java.util.Objects.requireNonNull(proposedBy, "proposedBy");
            java.util.Objects.requireNonNull(outcome, "outcome");
            java.util.Objects.requireNonNull(endedBy, "endedBy");
            java.util.Objects.requireNonNull(reason, "reason");
            java.util.Objects.requireNonNull(correlation, "correlation");
        }

        /** The reason never leaves in a text form: a record's generated {@code toString} would carry it into a log line. */
        @Override
        public String toString() {
            return "NewUnlockEnd[" + period.value() + ", revision=" + revision + ", " + outcome + "]";
        }
    }

    /** An unlock's end as stored - never its reason, which no reader is served. */
    record UnlockEndRow(UUID id, AccountingPeriodId period, int revision, Instant lockedAt, String proposedBy, Instant proposedAt, PeriodRequestEnd outcome, String endedBy,
            Instant endedAt) {}

    /** One act, as history. */
    record EventRow(
            UUID id,
            AccountingPeriodId periodId,
            Act act,
            Optional<PeriodStatus> from,
            PeriodStatus to,
            int revision,
            Actor actor,
            Optional<String> proposedBy,
            Optional<GlMappingVersionId> pinned,
            Optional<String> reason,
            CorrelationId correlation) {}
}
