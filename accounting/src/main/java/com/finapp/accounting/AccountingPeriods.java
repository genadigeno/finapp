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
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
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
 * {@code accounting V003}'s {@code CHECK} for every writer.
 *
 * <p>The close, its rejection and the reopen are `P14-TSK-012`'s. How a back-dating poster reacts to a locked period is
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

    /** The proposer tried to approve their own unlock ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(AccountingPeriodId id, YearMonth month) {
            super("a period's unlock is approved by someone other than its proposer");
            this.target = id.value().toString();
            this.summary = "period=" + month + ", act=period:unlock";
        }

        /** The period - the refusal record's target. */
        public String target() {
            return target;
        }

        /** What the refusal record names: the period and the act. */
        public String summary() {
            return summary;
        }
    }

    // ------------------------------------------------------------------ open next

    /**
     * Opens every month from the one after the latest period (today's month when none exists) through today's month -
     * each insert-if-absent, so ten instances open each month once; each born period evented and audited.
     *
     * @return the months this call opened, oldest first - empty when every month through today's already exists
     */
    public List<YearMonth> openNext(Connection unitOfWork, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        YearMonth through = YearMonth.from(today());
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
        if (today().isBefore(lockableFrom)) {
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
     * Approves a pending unlock - a different person's act: the cutoff moved back one period, {@code LOCKED -> OPEN},
     * the pin released (T-unlock). The same approver's repeat converges.
     *
     * @throws PeriodNotFound when no period has this id
     * @throws PeriodStale when the period is not {@code LOCKED} with an unlock pending
     * @throws SelfApprovalRefused when the approver proposed it ({@code INV-AUD-04})
     * @throws PostingCutoff.CutoffBusy when postings held the cutoff through the ledger's bounded attempts
     */
    public Acted approveUnlock(
            Connection unitOfWork, AccountingPeriodId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        Locked locked = lockWithPrevious(unitOfWork, id);
        AccountingPeriodStore.PeriodRow row = locked.period();
        if (row.status() == PeriodStatus.OPEN && row.unlockApprovedBy().filter(actor.id()::equals).isPresent()) {
            return Acted.of(row, true);
        }
        if (!row.unlockPending()) {
            throw new PeriodStale(row.status() == PeriodStatus.LOCKED
                    ? "no unlock of the period is proposed"
                    : "the period is " + row.status().name() + "; only a LOCKED period is unlocked");
        }
        if (row.unlockProposedBy().filter(actor.id()::equals).isPresent()) {
            throw new SelfApprovalRefused(id, row.month());
        }
        String proposer = row.unlockProposedBy().orElseThrow();
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
                "period=" + row.month() + ", revision=" + row.revision(), correlation);
        return new Acted(id, row.month(), PeriodStatus.OPEN, row.revision(), Optional.empty(), false, false);
    }

    /**
     * Records a refused self-approval {@code FAILED} ({@link AccountingAuditAction#SELF_APPROVAL_REFUSED}) on
     * {@code unitOfWork} - the caller's own transaction, after the act rolled back, so the attempt is kept.
     */
    public void recordRefusal(Connection unitOfWork, SelfApprovalRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                AccountingAuditAction.SELF_APPROVAL_REFUSED, TARGET_TYPE, refused.target(), Optional.empty(),
                AuditOutcome.FAILED, correlation, Optional.of(refused.summary())));
    }

    // ------------------------------------------------------------------ reads

    /**
     * The earliest {@code OPEN} period - where a posting dated today, a correction found during a close, or a prior-period
     * adjustment lands (the read `P14-TSK-011`, `-013` use). Unlocked; a writer that depends on it re-reads it under its
     * own lock.
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
                .map(row -> Math.max(0L, ChronoUnit.DAYS.between(grace.lockableFrom(row.month()), today())))
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

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
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
