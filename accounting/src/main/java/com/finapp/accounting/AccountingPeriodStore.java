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
 * append-only).
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

    /** The latest {@code limit} periods, newest first. Unlocked. */
    List<PeriodRow> recent(Connection unitOfWork, int limit);

    /** {@code OPEN -> LOCKED} at {@code revision}, the pin recorded; {@code false} when the row was not there. */
    boolean markLocked(Connection unitOfWork, AccountingPeriodId id, int revision, Actor actor, Optional<Pin> pin);

    /** Records the unlock proposal on a {@code LOCKED} period with none pending; {@code false} otherwise. */
    boolean proposeUnlock(Connection unitOfWork, AccountingPeriodId id, int revision, Actor actor, String reason);

    /** {@code LOCKED -> OPEN} by the pending proposal's approval; {@code false} when nothing was pending. */
    boolean approveUnlock(Connection unitOfWork, AccountingPeriodId id, int revision, Actor actor, String reason);

    /** Appends one history row. */
    void appendEvent(Connection unitOfWork, EventRow row);

    // ------------------------------------------------------------------ rows

    /** The acts the history records - each on its one edge ({@code period_event_act_is_an_edge}). */
    enum Act {
        OPENED,
        LOCKED,
        UNLOCK_PROPOSED,
        UNLOCKED
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
