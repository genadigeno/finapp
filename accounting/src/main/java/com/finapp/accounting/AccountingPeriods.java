package com.finapp.accounting;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The accounting period's machine - open, lock, unlock (`P14-TSK-005`; ADR-0114 decision 3; {@code PHASE_14_PLAN.md}
 * sections 6, 8.1 T-lock and T-unlock, 8.3 rows 2 and 8, 13.5; owner decision C9; {@code INV-ACC-03}, {@code -07},
 * {@code -08}, {@code INV-AUD-04}). A period is a calendar month of {@code posting_date}; periods are contiguous, one per
 * month, and lock in order.
 *
 * <p><strong>Open next</strong> ({@link #openNext}, the system's): every month from the one after the latest period -
 * or today's month when none exists - through today's month, each insert-if-absent, evented and audited once.
 *
 * <p><strong>Lock</strong> ({@link #lock}, a person's): the previous period {@code FOR SHARE} then the period
 * {@code FOR UPDATE} (A1, oldest first); refused unless {@code OPEN}, the previous period {@code CLOSED} (or none) and
 * the {@link LockGrace} passed; then namespace {@code 14} key {@code mapping} <strong>shared</strong> and the version in
 * force for the month read (the pin - none when none is in force); then the ledger's cutoff advanced to the period end
 * through {@link PostingCutoff} (A2 {@code cutoff} exclusive, A3 the row) - from this transaction's commit no entry dated
 * in the period commits; then the conditional edge, its history row, the audit record and
 * {@code accounting.AccountingPeriodLocked} - one transaction. <strong>Ten lockers, one edge</strong>: the others wait on
 * the row, re-read {@code LOCKED} and converge, writing nothing.
 *
 * <p><strong>Unlock</strong> (four eyes): a person proposes ({@link #proposeUnlock} - nothing moves); a
 * <strong>different</strong> person approves ({@link #approveUnlock}): the cutoff moved back exactly one period, the
 * edge {@code LOCKED -> OPEN} and the pin released - one transaction. Approver &ne; proposer is the domain's refusal and
 * {@code accounting V003}'s {@code CHECK} for every writer. The period's FINAL report runs are superseded in the same
 * transaction, under the period's row lock and before the cutoff moves (`P14-TSK-008`, {@link ReportRunSupersession};
 * {@code accounting V004} refuses the edge otherwise).
 *
 * <p><strong>An unlock ended without its effect</strong> (`P14-TSK-024`, the owner's acceptance of `P14-TSK-012`'s item (d); {@code accounting V011}): a second person rejects a pending unlock
 * ({@link #rejectUnlock}), or its proposer withdraws it ({@link #withdrawUnlock}) - {@link PeriodRequestEnd}, each its own terminal state, recorded in {@code period_unlock_end} beside a history
 * row. The period stays {@code LOCKED} under the same lock and pin, and the cutoff does not move: neither touches {@link PostingCutoff}. The proposal is cleared from the row, so the month is free
 * for a new one. <strong>The approval and an end serialise on the period row</strong>: each takes it {@code FOR UPDATE} first, so the second reads what the first committed and is told by name
 * what the first did. The proposer never rejects (refused as a self-approval, recorded {@code FAILED}); nobody but the proposer withdraws ({@link NotTheProposer}, recorded {@code FAILED}).
 *
 * <p><strong>Every act on a pending unlock names it</strong> (`P14-TSK-024`'s fix round): an end makes a pending request replaceable, so the approval, the rejection and the withdrawal each name
 * the request read by when it was proposed - an unlock request has no id, and its row's instant, stamped by the database holding the row, is its identity ({@link #pendingUnlock} serves it). Another
 * pending is {@link RequestStale}, judged holding the row and recorded {@code FAILED}: nobody decides a request they never read.
 *
 * <p><strong>Today is the database's</strong> (`P14-REM-009`; the Phase 14 exit review's A4-F1): the grace and the month the schedule opens are judged on {@link AccountingPeriodStore#today}, the clock the ledger's own
 * "never over today" (ledger {@code V028}) reads. The lock reads it <strong>holding the period's row</strong>: the date is {@code clock_timestamp()}, a statement's and not the transaction's, so however long the act waited for the row,
 * the date is that of the moment it got it. This instance's {@code Clock} stamps audit records and events and decides nothing here.
 *
 * <p>The close, its rejection and the reopen are {@link PeriodCloses}' (`P14-TSK-012`). How a back-dating poster reacts to a locked period is
 * `P14-TSK-006`'s (C3): here the ledger refuses, and nothing else is decided.
 */
@RequiredArgsConstructor
public final class AccountingPeriods {

    /** Published at every period's birth - codes only. */
    public static final String OPENED_EVENT = "accounting.AccountingPeriodOpened";

    /** Published at every lock - codes and numbers only, never a figure. */
    public static final String LOCKED_EVENT = "accounting.AccountingPeriodLocked";

    /** The audit record's and the events' target type. */
    public static final String TARGET_TYPE = "accounting_period";

    static final String PRODUCER = "accounting";
    static final int EVENT_VERSION = 1;

    @NonNull private final AccountingPeriodStore store;
    @NonNull private final GlMappingStore mappings;
    @NonNull private final PostingCutoff cutoff;
    @NonNull private final LockGrace grace;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    /** The period's FINAL report runs, superseded when it returns to {@code OPEN} (`P14-TSK-008`, {@code INV-ACC-04}). */
    @NonNull private final ReportRunSupersession runs;

    // ------------------------------------------------------------------ outcomes

    /**
     * A period act's outcome: the period as the act left it; {@code replayed} when the act found its edge already made
     * (a second locker, the same proposer's or approver's repeat) and wrote nothing.
     */
    public record Acted(
            AccountingPeriodId id,
            YearMonth month,
            PeriodStatus status,
            int revision,
            Optional<Integer> pinnedVersion,
            boolean unlockPending,
            boolean replayed) {

        static Acted of(AccountingPeriodStore.PeriodRow row, boolean replayed) {
            return new Acted(row.id(), row.month(), row.status(), row.revision(),
                    row.pinned().map(AccountingPeriodStore.Pin::version), row.unlockPending(), replayed);
        }
    }

    // ------------------------------------------------------------------ refusals

    /** No accounting period has this id. */
    public static final class PeriodNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PeriodNotFound() {
            super("no accounting period answers this request");
        }
    }

    /** The period is not in the state the act needs. */
    public static final class PeriodStale extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PeriodStale(String detail) {
            super(detail);
        }
    }

    /** The period cannot lock yet: the grace has not passed, or the previous period is not {@code CLOSED}. */
    public static final class PeriodNotLockable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PeriodNotLockable(String detail) {
            super(detail);
        }
    }

    /**
     * A refusal a door keeps (`P14-TSK-024`): recorded {@code FAILED} under {@link #action()} in a transaction of its own after the act rolled back, so the attempt is kept though nothing was
     * written - a self-approval, another person's withdrawal, an act on a request other than the one pending. It names the period and the act, never a person or a reason.
     */
    public abstract static class RecordedRefusal extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        RecordedRefusal(AccountingPeriodId id, YearMonth month, String act, String detail) {
            super(detail);
            this.target = id.value().toString();
            this.summary = "period=" + month + ", act=" + act;
        }

        /** The period - the refusal record's target. */
        public String target() {
            return target;
        }

        /** What the refusal record names: the period and the act. */
        public String summary() {
            return summary;
        }

        /** What the refusal is recorded as. */
        public abstract AccountingAuditAction action();
    }

    /** The proposer tried to approve their own unlock, close or reopen ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RecordedRefusal {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SelfApprovalRefused(AccountingPeriodId id, YearMonth month) {
            this(id, month, "period:unlock");
        }

        SelfApprovalRefused(AccountingPeriodId id, YearMonth month, String act) {
            this(id, month, act, "a period's unlock, close or reopen is approved by someone other than its proposer");
        }

        private SelfApprovalRefused(AccountingPeriodId id, YearMonth month, String act, String detail) {
            super(id, month, act, detail);
        }

        /** A proposer's rejection of their own unlock or reopen (`P14-TSK-024`): a rejection is a second person's decision; the proposer withdraws. */
        static SelfApprovalRefused ofRejection(AccountingPeriodId id, YearMonth month, String act) {
            return new SelfApprovalRefused(id, month, act, "an unlock or a reopen is rejected by someone other than its proposer; its proposer withdraws it");
        }

        @Override
        public AccountingAuditAction action() {
            return AccountingAuditAction.SELF_APPROVAL_REFUSED;
        }
    }

    /**
     * Somebody other than its proposer tried to withdraw a pending unlock or reopen (`P14-TSK-024`): a withdrawal is the proposer's own retraction, and another person's end of it is a
     * rejection, under the approver's permission. Nothing was changed; the attempt is recorded, as an accountant ending a colleague's request is what four eyes stops.
     */
    public static final class NotTheProposer extends RecordedRefusal {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NotTheProposer(AccountingPeriodId id, YearMonth month, String act, String what) {
            super(id, month, act, "only its proposer withdraws " + what + "; another person rejects it");
        }

        @Override
        public AccountingAuditAction action() {
            return AccountingAuditAction.NOT_THE_PROPOSER;
        }
    }

    /**
     * The act names an unlock or a reopen that is not the one pending (`P14-TSK-024`): another was proposed after the one the person read, the first having ended. An approval, a rejection
     * and a withdrawal each name the request they decide, as the close's approval names its proposal, so nobody decides a request they never read. Nothing was changed; the attempt is
     * recorded.
     */
    public static final class RequestStale extends RecordedRefusal {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RequestStale(AccountingPeriodId id, YearMonth month, String act, String detail) {
            super(id, month, act, detail);
        }

        @Override
        public AccountingAuditAction action() {
            return AccountingAuditAction.REQUEST_STALE;
        }
    }

    // ------------------------------------------------------------------ open next

    /**
     * Opens every month from the one after the latest period (today's month when none exists) through today's month -
     * each insert-if-absent, so ten instances open each month once; each born period evented and audited. Today is the
     * database's (`P14-REM-009`). No row exists to hold while reading it and none is needed: a reading gone stale across a
     * month's end only opens fewer months, and the next tick opens the rest.
     *
     * @return the months this call opened, oldest first - empty when every month through today's already exists
     */
    public List<YearMonth> openNext(Connection unitOfWork, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        YearMonth through = YearMonth.from(store.today(unitOfWork));
        YearMonth next = store.latest(unitOfWork).map(latest -> latest.plusMonths(1)).orElse(through);
        List<YearMonth> born = new ArrayList<>();
        for (YearMonth month = next; !month.isAfter(through); month = month.plusMonths(1)) {
            AccountingPeriodId id = AccountingPeriodId.next(ids);
            if (!store.insertIfAbsent(unitOfWork, new AccountingPeriodStore.NewPeriod(id, month, correlation))) {
                continue;
            }
            store.appendEvent(unitOfWork, new AccountingPeriodStore.EventRow(ids.next(), id,
                    AccountingPeriodStore.Act.OPENED, Optional.empty(), PeriodStatus.OPEN, 1, actor, Optional.empty(),
                    Optional.empty(), Optional.empty(), correlation));
            audit(unitOfWork, actor, AccountingAuditAction.PERIOD_OPENED, id, Optional.empty(),
                    "period=" + month + ", revision=1", correlation);
            publish(unitOfWork, OPENED_EVENT, id, EventPayload.of()
                    .with("period", month.toString())
                    .with("startDate", month.atDay(1).toString())
                    .with("endDate", month.atEndOfMonth().toString())
                    .with("revision", "1"), correlation);
            born.add(month);
        }
        return born;
    }

    // ------------------------------------------------------------------ lock

    /**
     * Locks an {@code OPEN} period: in order, after its grace; the GL mapping version in force pinned and the ledger's
     * cutoff advanced to the period end in this transaction (T-lock). A second locker converges on the edge.
     *
     * @param reason optional; screened when given
     * @throws PeriodNotFound when no period has this id
     * @throws PeriodStale when the period is past {@code LOCKED} (closing or closed)
     * @throws PeriodNotLockable when the previous period is not {@code CLOSED}, or the grace has not passed
     * @throws PostingCutoff.CutoffBusy when postings held the cutoff through the ledger's bounded attempts
     */
    public Acted lock(Connection unitOfWork, AccountingPeriodId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        Optional<String> reasoned = reason == null || reason.isBlank()
                ? Optional.empty() : Optional.of(AccountingReasons.reasoned(reason));
        Locked locked = lockWithPrevious(unitOfWork, id);
        AccountingPeriodStore.PeriodRow row = locked.period();
        if (row.status() == PeriodStatus.LOCKED) {
            return Acted.of(row, true);
        }
        if (row.status() != PeriodStatus.OPEN) {
            throw new PeriodStale("the period is " + row.status().name() + "; only an OPEN period locks");
        }
        if (locked.previous().isPresent() && locked.previous().get().status() != PeriodStatus.CLOSED) {
            throw new PeriodNotLockable("periods lock in order: " + locked.previous().get().month() + " is "
                    + locked.previous().get().status().name() + ", not CLOSED");
        }
        LocalDate lockableFrom = grace.lockableFrom(row.month());
        // The database's date, read here and no earlier: holding the row, so a lock that waited across the boundary is judged on the day it got the row (P14-REM-009).
        if (store.today(unitOfWork).isBefore(lockableFrom)) {
            throw new PeriodNotLockable("the period is lockable from " + lockableFrom + ", " + grace.businessDays()
                    + " business day(s) after its end");
        }
        // A2 shared, then the pin: an activation (A2 exclusive) committed before this read, or re-reads this period
        // LOCKED after this transaction commits (the plan's section 8.3 row 8).
        mappings.lockMappingShared(unitOfWork);
        Optional<AccountingPeriodStore.Pin> pin = mappings.inForceFor(unitOfWork, row.month())
                .map(version -> new AccountingPeriodStore.Pin(version.id(), version.version()));
        // A2 cutoff exclusive and A3: from this transaction's commit no entry dated on or before the end commits.
        cutoff.advance(unitOfWork, locked.previous().map(AccountingPeriodStore.PeriodRow::end), row.end(),
                "accounting period " + row.month() + " locked");
        if (!store.markLocked(unitOfWork, id, row.revision(), actor, pin)) {
            throw new IllegalStateException(
                    "the locked OPEN period moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        store.appendEvent(unitOfWork, new AccountingPeriodStore.EventRow(ids.next(), id, AccountingPeriodStore.Act.LOCKED,
                Optional.of(PeriodStatus.OPEN), PeriodStatus.LOCKED, row.revision(), actor, Optional.empty(),
                pin.map(AccountingPeriodStore.Pin::id), reasoned, correlation));
        Optional<Integer> version = pin.map(AccountingPeriodStore.Pin::version);
        audit(unitOfWork, actor, AccountingAuditAction.PERIOD_LOCKED, id, reasoned,
                "period=" + row.month() + ", revision=" + row.revision() + ", mapping="
                        + version.map(v -> "v" + v).orElse("none"),
                correlation);
        EventPayload payload = EventPayload.of()
                .with("period", row.month().toString())
                .with("revision", Integer.toString(row.revision()))
                .with("closedThrough", row.end().toString());
        if (version.isPresent()) {
            payload = payload.with("mappingVersion", Integer.toString(version.get()));
        }
        publish(unitOfWork, LOCKED_EVENT, id, payload, correlation);
        return new Acted(id, row.month(), PeriodStatus.LOCKED, row.revision(), version, false, false);
    }

    // ------------------------------------------------------------------ unlock

    /**
     * Proposes unlocking a {@code LOCKED} period - nothing moves; the same proposer's repeat converges.
     *
     * @throws PeriodNotFound when no period has this id
     * @throws PeriodStale when the period is not {@code LOCKED}, or another person's unlock is already pending
     * @throws AccountingReasons.ReasonRequired for a missing, unbounded or instrument-bearing reason
     */
    public Acted proposeUnlock(
            Connection unitOfWork, AccountingPeriodId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        AccountingPeriodStore.PeriodRow row = store.lock(unitOfWork, id).orElseThrow(PeriodNotFound::new);
        if (row.unlockPending() && row.unlockProposedBy().filter(actor.id()::equals).isPresent()) {
            return Acted.of(row, true);
        }
        if (row.status() != PeriodStatus.LOCKED) {
            throw new PeriodStale("the period is " + row.status().name() + "; only a LOCKED period is unlocked");
        }
        if (row.unlockPending()) {
            throw new PeriodStale("an unlock of the period is already proposed and awaits a second person");
        }
        if (!store.proposeUnlock(unitOfWork, id, row.revision(), actor, reasoned)) {
            throw new IllegalStateException(
                    "the locked period moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        store.appendEvent(unitOfWork, new AccountingPeriodStore.EventRow(ids.next(), id,
                AccountingPeriodStore.Act.UNLOCK_PROPOSED, Optional.of(PeriodStatus.LOCKED), PeriodStatus.LOCKED,
                row.revision(), actor, Optional.empty(), Optional.empty(), Optional.of(reasoned), correlation));
        audit(unitOfWork, actor, AccountingAuditAction.PERIOD_UNLOCK_PROPOSED, id, Optional.of(reasoned),
                "period=" + row.month() + ", revision=" + row.revision(), correlation);
        return new Acted(id, row.month(), PeriodStatus.LOCKED, row.revision(),
                row.pinned().map(AccountingPeriodStore.Pin::version), true, false);
    }

    /**
     * Approves the pending unlock the person read, named by when it was proposed - a different person's act: the cutoff moved back one period, {@code LOCKED -> OPEN}, the pin released
     * (T-unlock). The same approver's repeat converges.
     *
     * @param requested when the unlock the approver read was proposed ({@code unlockProposedAt}): an unlock request has no id, and its row's instant, stamped by the database under the row,
     *     is its identity
     * @throws PeriodNotFound when no period has this id
     * @throws PeriodStale when the period is not {@code LOCKED} with an unlock pending
     * @throws RequestStale when the unlock pending is not the one named (`P14-TSK-024`)
     * @throws SelfApprovalRefused when the approver proposed it ({@code INV-AUD-04})
     * @throws PostingCutoff.CutoffBusy when postings held the cutoff through the ledger's bounded attempts
     */
    public Acted approveUnlock(
            Connection unitOfWork, AccountingPeriodId id, Instant requested, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        Locked locked = lockWithPrevious(unitOfWork, id);
        AccountingPeriodStore.PeriodRow row = locked.period();
        if (row.status() == PeriodStatus.OPEN && row.unlockApprovedBy().filter(actor.id()::equals).isPresent() && row.unlockProposedAt().filter(requested::equals).isPresent()) {
            return Acted.of(row, true);
        }
        if (!row.unlockPending()) {
            throw new PeriodStale(row.status() == PeriodStatus.LOCKED
                    ? noneUnderTheLock(store.unlockEnd(unitOfWork, id, requested))
                    : "the period is " + row.status().name() + "; only a LOCKED period is unlocked");
        }
        requireTheUnlockRead(row, requested, "period:unlock-approval");
        if (row.unlockProposedBy().filter(actor.id()::equals).isPresent()) {
            throw new SelfApprovalRefused(id, row.month());
        }
        String proposer = row.unlockProposedBy().orElseThrow();
        // A4 under the period's FOR UPDATE and BEFORE the cutoff's key (`P14-TSK-008`): no FINAL report run stands over a
        // period whose range can change again - accounting V004 refuses the edge for every writer that skipped this - and
        // the supersession's row writes are done before postings pause behind the cutoff's exclusive key. No transaction
        // takes the cutoff (A2/A3) and then a report run (A4), so the unlock's A1 -> A4 -> A2 -> A3 closes no cycle.
        int superseded = runs.supersedeFinal(unitOfWork, id, actor, correlation);
        cutoff.moveBack(unitOfWork, row.end(), locked.previous().map(AccountingPeriodStore.PeriodRow::end),
                "accounting period " + row.month() + " unlocked under four eyes");
        if (!store.approveUnlock(unitOfWork, id, row.revision(), actor, reasoned)) {
            throw new IllegalStateException(
                    "the pending unlock moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        store.appendEvent(unitOfWork, new AccountingPeriodStore.EventRow(ids.next(), id,
                AccountingPeriodStore.Act.UNLOCKED, Optional.of(PeriodStatus.LOCKED), PeriodStatus.OPEN, row.revision(),
                actor, Optional.of(proposer), Optional.empty(), Optional.of(reasoned), correlation));
        audit(unitOfWork, actor, AccountingAuditAction.PERIOD_UNLOCKED, id, Optional.of(reasoned),
                "period=" + row.month() + ", revision=" + row.revision() + ", supersededRuns=" + superseded, correlation);
        return new Acted(id, row.month(), PeriodStatus.OPEN, row.revision(), Optional.empty(), false, false);
    }

    // ------------------------------------------------------------------ an unlock's end

    /**
     * Rejects the pending unlock the person read, named by when it was proposed - a second person's decision against it (`P14-TSK-024`): the period stays {@code LOCKED} under the same lock and
     * pin, the cutoff unmoved, the month free for a new proposal. The same rejecter's repeat converges.
     *
     * @param requested when the unlock the person read was proposed ({@code unlockProposedAt})
     * @throws PeriodNotFound when no period has this id
     * @throws PeriodStale when no unlock is pending - naming whether the one named was approved, rejected or withdrawn
     * @throws RequestStale when the unlock pending is not the one named
     * @throws SelfApprovalRefused when the rejecter proposed it ({@code INV-AUD-04}): its proposer withdraws it
     * @throws AccountingReasons.ReasonRequired for a missing, unbounded or instrument-bearing reason
     */
    public Acted rejectUnlock(Connection unitOfWork, AccountingPeriodId id, Instant requested, Actor actor, String reason, CorrelationId correlation) {
        return endUnlock(unitOfWork, id, requested, PeriodRequestEnd.REJECTED, actor, reason, correlation);
    }

    /**
     * Withdraws the pending unlock the person read, named by when it was proposed - its proposer's own retraction (`P14-TSK-024`): as {@link #rejectUnlock}, by the one person who may. The same
     * proposer's repeat converges.
     *
     * @param requested when the unlock the person read was proposed ({@code unlockProposedAt})
     * @throws PeriodNotFound when no period has this id
     * @throws PeriodStale when no unlock is pending - naming whether the one named was approved, rejected or withdrawn
     * @throws RequestStale when the unlock pending is not the one named
     * @throws NotTheProposer when someone else asks
     * @throws AccountingReasons.ReasonRequired for a missing, unbounded or instrument-bearing reason
     */
    public Acted withdrawUnlock(Connection unitOfWork, AccountingPeriodId id, Instant requested, Actor actor, String reason, CorrelationId correlation) {
        return endUnlock(unitOfWork, id, requested, PeriodRequestEnd.WITHDRAWN, actor, reason, correlation);
    }

    /**
     * (A1) the period alone {@code FOR UPDATE} - as {@link #proposeUnlock} takes it, and the approval takes it after the previous period's {@code FOR SHARE}, so an end and an approval serialise on
     * this row and close no cycle. Nothing here touches the cutoff, the pin or a report run.
     */
    private Acted endUnlock(Connection unitOfWork, AccountingPeriodId id, Instant requested, PeriodRequestEnd end, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        AccountingPeriodStore.PeriodRow row = store.lock(unitOfWork, id).orElseThrow(PeriodNotFound::new);
        // The request named, looked up itself under the period's row (P14-DOC-001, P14-TSK-024's re-review (a)): ended under the present lock by this
        // person this way, a repeat - under any key, whatever was proposed or ended since - converges and writes nothing. Until then this read the lock's
        // latest end, so a retry after another unlock ended in between was told "no unlock is pending", and one while another was pending RequestStale.
        Optional<AccountingPeriodStore.UnlockEndRow> named = row.status() == PeriodStatus.LOCKED ? store.unlockEnd(unitOfWork, id, requested) : Optional.empty();
        if (named.filter(ended -> ended.outcome() == end && ended.endedBy().equals(actor.id())).isPresent()) {
            return Acted.of(row, true);
        }
        if (!row.unlockPending()) {
            if (row.status() == PeriodStatus.LOCKED) {
                throw new PeriodStale(noneUnderTheLock(named));
            }
            throw new PeriodStale(row.status() == PeriodStatus.OPEN && row.unlockApprovedBy().isPresent() && row.unlockProposedAt().filter(requested::equals).isPresent()
                    ? "the period is OPEN: the unlock named was approved; nothing was changed"
                    : "the period is " + row.status().name() + "; only a LOCKED period's pending unlock is ended");
        }
        String act = end == PeriodRequestEnd.REJECTED ? "period:unlock-rejection" : "period:unlock-withdrawal";
        requireTheUnlockRead(row, requested, act);
        String proposer = row.unlockProposedBy().orElseThrow();
        boolean own = proposer.equals(actor.id());
        if (end == PeriodRequestEnd.REJECTED && own) {
            throw SelfApprovalRefused.ofRejection(id, row.month(), act);
        }
        if (end == PeriodRequestEnd.WITHDRAWN && !own) {
            throw new NotTheProposer(id, row.month(), act, "an unlock");
        }
        if (!store.endUnlock(unitOfWork, new AccountingPeriodStore.NewUnlockEnd(ids.next(), id, row.revision(), proposer, end, actor, reasoned, correlation))) {
            throw new IllegalStateException("the pending unlock moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        store.appendEvent(unitOfWork, new AccountingPeriodStore.EventRow(ids.next(), id, end.unlockAct(), Optional.of(PeriodStatus.LOCKED), PeriodStatus.LOCKED, row.revision(), actor,
                Optional.of(proposer), Optional.empty(), Optional.of(reasoned), correlation));
        audit(unitOfWork, actor, end == PeriodRequestEnd.REJECTED ? AccountingAuditAction.PERIOD_UNLOCK_REJECTED : AccountingAuditAction.PERIOD_UNLOCK_WITHDRAWN, id, Optional.of(reasoned),
                "period=" + row.month() + ", revision=" + row.revision(), correlation);
        return new Acted(id, row.month(), PeriodStatus.LOCKED, row.revision(), row.pinned().map(AccountingPeriodStore.Pin::version), false, false);
    }

    /**
     * What a {@code LOCKED} period says to an act on an unlock that is not pending: what became of the one named, when it ended under the present lock (`P14-DOC-001`: looked up by when it was
     * proposed); otherwise that none is pending.
     */
    private static String noneUnderTheLock(Optional<AccountingPeriodStore.UnlockEndRow> named) {
        return named.map(ended -> "the unlock named was " + ended.outcome().name() + "; none is pending, and nothing was changed")
                .orElse("no unlock of the period is pending; nothing was changed");
    }

    /** The unlock pending is the one the person read, named by when it was proposed; otherwise {@link RequestStale}, saying when the one pending was. */
    private static void requireTheUnlockRead(AccountingPeriodStore.PeriodRow row, Instant requested, String act) {
        Instant pending = row.unlockProposedAt().orElseThrow(() -> new IllegalStateException("a pending unlock was proposed at an instant"));
        if (!pending.equals(requested)) {
            throw new RequestStale(row.id(), row.month(), act, "the unlock named, proposed at " + requested + ", is not the one pending, proposed at " + pending
                    + ": read it and decide again; nothing was changed");
        }
    }

    /**
     * Records a refusal a door keeps {@code FAILED} under its own action ({@link RecordedRefusal#action()}) on {@code unitOfWork} - the caller's own transaction, after the act rolled back, so the
     * attempt is kept.
     */
    public void recordRefusal(Connection unitOfWork, RecordedRefusal refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                refused.action(), TARGET_TYPE, refused.target(), Optional.empty(),
                AuditOutcome.FAILED, correlation, Optional.of(refused.summary())));
    }

    /**
     * When the period's pending unlock was proposed - its identity, which an approval, a rejection and a withdrawal name (`P14-TSK-024`); empty when none is pending. A plain read: the act
     * judges it again holding the row.
     *
     * @throws PeriodNotFound when no period has this id
     */
    public Optional<Instant> pendingUnlock(Connection unitOfWork, AccountingPeriodId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        AccountingPeriodStore.PeriodRow row = store.find(unitOfWork, id).orElseThrow(PeriodNotFound::new);
        return row.unlockPending() ? row.unlockProposedAt() : Optional.empty();
    }

    // ------------------------------------------------------------------ reads

    /**
     * The earliest {@code OPEN} period. Not where a prior-period adjustment lands: it may be a reopened month (`P14-TSK-012`), and
     * {@link PriorPeriodAdjustments} dates into the month holding today (`P14-TSK-013`). Unlocked; a writer that depends on it re-reads it
     * under its own lock.
     */
    public Optional<AccountingPeriodStore.PeriodRow> firstOpen(Connection unitOfWork) {
        return store.firstOpen(unitOfWork);
    }

    /** The latest {@code limit} periods, newest first. */
    public List<AccountingPeriodStore.PeriodRow> recent(Connection unitOfWork, int limit) {
        return store.recent(unitOfWork, limit);
    }

    /** The first day {@code month} may lock - the configured grace after its end. */
    public LocalDate lockableFrom(YearMonth month) {
        return grace.lockableFrom(month);
    }

    /**
     * Whole days the oldest period not yet {@code CLOSED} has been past its lock grace - 0 when none is ({@code
     * finapp.accounting.period.open.age}).
     */
    public long overdueDays(Connection unitOfWork) {
        return store.oldestUnclosed(unitOfWork)
                .map(row -> Math.max(0L, ChronoUnit.DAYS.between(grace.lockableFrom(row.month()), store.today(unitOfWork))))
                .orElse(0L);
    }

    // ------------------------------------------------------------------ plumbing

    private record Locked(AccountingPeriodStore.PeriodRow period, Optional<AccountingPeriodStore.PeriodRow> previous) {}

    /** A1, oldest first: the previous period {@code FOR SHARE}, then the period {@code FOR UPDATE}. */
    private Locked lockWithPrevious(Connection unitOfWork, AccountingPeriodId id) {
        // The month is frozen from birth, so the unlocked read only names which row precedes it.
        YearMonth month = store.find(unitOfWork, id).orElseThrow(PeriodNotFound::new).month();
        Optional<AccountingPeriodStore.PeriodRow> previous = store.lockShared(unitOfWork, month.minusMonths(1));
        AccountingPeriodStore.PeriodRow period = store.lock(unitOfWork, id).orElseThrow(PeriodNotFound::new);
        return new Locked(period, previous);
    }

    /** An authenticated human - {@code CUSTOMER}-typed (every operator session) or {@code EMPLOYEE}; fail closed. */
    private static void person(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        if (actor.type() != com.finapp.platform.security.ActorType.CUSTOMER
                && actor.type() != com.finapp.platform.security.ActorType.EMPLOYEE) {
            throw new IllegalArgumentException("a period's lock or unlock is a person's - never the platform's, a"
                    + " service's or a merchant's");
        }
    }

    private void publish(
            Connection unitOfWork, String type, AccountingPeriodId id, EventPayload payload, CorrelationId correlation) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        type,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        id,
                        TARGET_TYPE,
                        clock.instant(),
                        PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    private void audit(
            Connection unitOfWork,
            Actor actor,
            AccountingAuditAction action,
            AccountingPeriodId id,
            Optional<String> reason,
            String summary,
            CorrelationId correlation) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        clock.instant(),
                        action,
                        TARGET_TYPE,
                        id.value().toString(),
                        reason,
                        AuditOutcome.SUCCEEDED,
                        correlation,
                        Optional.of(summary)));
    }
}
