package com.finapp.accounting;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The accounting break's machine (`P14-TSK-010`; ADR-0117 decision 3; owner decision C11; {@code PHASE_14_PLAN.md}
 * sections 6, 8.3 row 10, 13.3; {@code INV-ACC-12}, {@code INV-ACC-05}, {@code INV-REC-02}, {@code INV-AUD-04}) - raise,
 * acknowledge under four eyes with a disclosure, resolve by reference. <strong>Detect, never correct</strong>: this class
 * holds no ledger door ({@code BreaksNeverPostTest}) - a break is resolved by naming the correcting entry the owning
 * domain posted through its own door, never by posting one.
 *
 * <p><strong>Raise</strong> ({@link #raise}, the check's): the break born {@code OPEN} once per
 * {@code (period, check, subject)} - {@code INSERT ... ON CONFLICT DO NOTHING}, so two checks or ten instances raising one
 * discrepancy leave one break; only the inserter writes its {@code RAISED} case-file row, its audit record and
 * {@code accounting.AccountingBreakRaised} - all in the caller's one transaction.
 *
 * <p><strong>Acknowledge</strong> (four eyes): a person proposes with a disclosure ({@link #proposeAcknowledgement} -
 * nothing moves); a <strong>different</strong> person approves ({@link #approveAcknowledgement}): {@code OPEN ->
 * ACKNOWLEDGED}. Approver &ne; proposer is the domain's refusal and {@code accounting V006}'s {@code CHECK} for every
 * writer. <strong>Resolve</strong> ({@link #resolve}): {@code OPEN | ACKNOWLEDGED -> RESOLVED}, naming a journal entry
 * that exists and is not the break's own subject. Every act locks the break row first, moves by a conditional edge,
 * appends its case-file row and is audited, in one transaction; a repeat by the same person converges.
 */
@RequiredArgsConstructor
public final class AccountingBreaks {

    /** Published at every break's birth - ids and codes only, never an amount. */
    public static final String RAISED_EVENT = "accounting.AccountingBreakRaised";

    /** The audit record's and the event's target type. */
    public static final String TARGET_TYPE = "accounting_break";

    static final String PRODUCER = "accounting";
    static final int EVENT_VERSION = 1;

    @NonNull private final AccountingBreakStore store;
    @NonNull private final JournalReads journal;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    // ------------------------------------------------------------------ outcomes and refusals

    /** An act's outcome: the break as the act left it; {@code replayed} when it found its edge already made. */
    public record Acted(AccountingBreakId id, YearMonth period, BreakStatus status, boolean acknowledgementPending,
            boolean replayed) {

        static Acted of(AccountingBreakStore.BreakRow row, boolean replayed) {
            return new Acted(row.id(), row.period(), row.status(), row.acknowledgementPending(), replayed);
        }
    }

    /** No accounting break has this id. */
    public static final class BreakNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        BreakNotFound() {
            super("no accounting break answers this request");
        }
    }

    /** The break is not in the state the act needs. */
    public static final class BreakStale extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        BreakStale(String detail) {
            super(detail);
        }
    }

    /** The correcting entry does not exist, or is the break's own subject. */
    public static final class CorrectingEntryInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CorrectingEntryInvalid(String detail) {
            super(detail);
        }
    }

    /** The proposer tried to approve their own acknowledgement ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(AccountingBreakId id, YearMonth period) {
            super("a break's acknowledgement is approved by someone other than its proposer");
            this.target = id.value().toString();
            this.summary = "period=" + period + ", act=break:acknowledgement";
        }

        /** The break - the refusal record's target. */
        public String target() {
            return target;
        }

        /** What the refusal record names: the period and the act. */
        public String summary() {
            return summary;
        }
    }

    // ------------------------------------------------------------------ raise

    /**
     * Raises {@code finding} for {@code period} - born once: when a break already holds its {@code (period, check,
     * subject)} nothing is written and the answer is false.
     *
     * @param actor the run's - the platform for the close schedule, a person for a re-run
     * @return true when this call raised it
     */
    public boolean raise(Connection unitOfWork, YearMonth period, BreakFinding finding, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(finding, "finding");
        Objects.requireNonNull(correlation, "correlation");
        if (Objects.requireNonNull(actor, "actor").type() != ActorType.SYSTEM) {
            person(actor);
        }
        AccountingBreakId id = AccountingBreakId.next(ids);
        if (!store.insertIfAbsent(unitOfWork, new AccountingBreakStore.NewBreak(id, period, finding, actor, correlation))) {
            return false;
        }
        store.appendEvent(unitOfWork, new AccountingBreakStore.EventRow(ids.next(), id, AccountingBreakStore.Act.RAISED,
                Optional.empty(), BreakStatus.OPEN, actor, Optional.empty(), Optional.empty(), Optional.empty(),
                correlation));
        record(unitOfWork, actor, AccountingAuditAction.BREAK_RAISED, id, Optional.empty(), summary(period, finding.check(),
                finding.type()) + ", judgedBy=" + finding.judgedBy(), correlation);
        outbox.write(unitOfWork, new EventEnvelope(EventId.next(ids), RAISED_EVENT, EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION, id, TARGET_TYPE, clock.instant(), PRODUCER, correlation,
                        CausationId.of(correlation.value())),
                // Identifiers and enumerated names only (EventPayload's rule, INV-AUD-02): the subject is the break's to
                // read by id; the register version split into its name and number.
                EventPayload.of()
                        .with("breakId", id.value().toString())
                        .with("period", period.toString())
                        .with("check", finding.check().name())
                        .with("type", finding.type().name())
                        .with("register", finding.judgedBy().substring(0, finding.judgedBy().indexOf(':')))
                        .with("registerVersion", finding.judgedBy().substring(finding.judgedBy().indexOf(":v") + 2))
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
        return true;
    }

    // ------------------------------------------------------------------ acknowledge (four eyes)

    /**
     * Proposes acknowledging an {@code OPEN} break with {@code disclosure} - nothing moves; the same proposer's repeat
     * converges.
     *
     * @throws BreakNotFound when no break has this id
     * @throws BreakStale when the break is not {@code OPEN}, or another person's acknowledgement is pending
     * @throws AccountingReasons.ReasonRequired for a missing, unbounded or instrument-bearing disclosure
     */
    public Acted proposeAcknowledgement(Connection unitOfWork, AccountingBreakId id, Actor actor, String disclosure,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String disclosed = AccountingReasons.reasoned(disclosure);
        AccountingBreakStore.BreakRow row = store.lock(unitOfWork, id).orElseThrow(BreakNotFound::new);
        if (row.acknowledgementPending() && row.ackProposedBy().filter(actor.id()::equals).isPresent()) {
            return Acted.of(row, true);
        }
        if (row.status() != BreakStatus.OPEN) {
            throw new BreakStale("the break is " + row.status().name() + "; only an OPEN break is acknowledged");
        }
        if (row.acknowledgementPending()) {
            throw new BreakStale("an acknowledgement of the break is already proposed and awaits a second person");
        }
        if (!store.proposeAcknowledgement(unitOfWork, id, actor, disclosed)) {
            throw new IllegalStateException("the locked break moved under its own row lock: the FOR UPDATE protocol was"
                    + " bypassed");
        }
        store.appendEvent(unitOfWork, new AccountingBreakStore.EventRow(ids.next(), id,
                AccountingBreakStore.Act.ACKNOWLEDGEMENT_PROPOSED, Optional.of(BreakStatus.OPEN), BreakStatus.OPEN, actor,
                Optional.empty(), Optional.empty(), Optional.of(disclosed), correlation));
        record(unitOfWork, actor, AccountingAuditAction.BREAK_ACKNOWLEDGEMENT_PROPOSED, id, Optional.of(disclosed),
                summary(row.period(), row.check(), row.type()), correlation);
        return new Acted(id, row.period(), BreakStatus.OPEN, true, false);
    }

    /**
     * Approves a pending acknowledgement - a different person's act: {@code OPEN -> ACKNOWLEDGED}. The same approver's
     * repeat converges.
     *
     * @throws BreakNotFound when no break has this id
     * @throws BreakStale when no acknowledgement is pending
     * @throws SelfApprovalRefused when the approver proposed it ({@code INV-AUD-04})
     */
    public Acted approveAcknowledgement(Connection unitOfWork, AccountingBreakId id, Actor actor, String reason,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        AccountingBreakStore.BreakRow row = store.lock(unitOfWork, id).orElseThrow(BreakNotFound::new);
        if (row.status() == BreakStatus.ACKNOWLEDGED && row.ackApprovedBy().filter(actor.id()::equals).isPresent()) {
            return Acted.of(row, true);
        }
        if (!row.acknowledgementPending()) {
            throw new BreakStale(row.status() == BreakStatus.OPEN
                    ? "no acknowledgement of the break is proposed"
                    : "the break is " + row.status().name() + "; only an OPEN break is acknowledged");
        }
        if (row.ackProposedBy().filter(actor.id()::equals).isPresent()) {
            throw new SelfApprovalRefused(id, row.period());
        }
        String proposer = row.ackProposedBy().orElseThrow();
        if (!store.approveAcknowledgement(unitOfWork, id, actor, reasoned)) {
            throw new IllegalStateException("the pending acknowledgement moved under its own row lock: the FOR UPDATE"
                    + " protocol was bypassed");
        }
        store.appendEvent(unitOfWork, new AccountingBreakStore.EventRow(ids.next(), id,
                AccountingBreakStore.Act.ACKNOWLEDGED, Optional.of(BreakStatus.OPEN), BreakStatus.ACKNOWLEDGED, actor,
                Optional.of(proposer), Optional.empty(), Optional.of(reasoned), correlation));
        record(unitOfWork, actor, AccountingAuditAction.BREAK_ACKNOWLEDGED, id, Optional.of(reasoned),
                summary(row.period(), row.check(), row.type()), correlation);
        return new Acted(id, row.period(), BreakStatus.ACKNOWLEDGED, false, false);
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

    // ------------------------------------------------------------------ resolve (by reference)

    /**
     * Resolves an {@code OPEN} or {@code ACKNOWLEDGED} break by reference to {@code correctingEntry} - a journal entry
     * the owning domain posted, which must exist and must not be the break's own subject. Posts nothing. The same
     * person's repeat with the same entry converges.
     *
     * @throws BreakNotFound when no break has this id
     * @throws BreakStale when the break is already {@code RESOLVED} (otherwise)
     * @throws CorrectingEntryInvalid when the entry does not exist or is the subject
     */
    public Acted resolve(Connection unitOfWork, AccountingBreakId id, Actor actor, UUID correctingEntry, String reason,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correctingEntry, "correctingEntry");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        AccountingBreakStore.BreakRow row = store.lock(unitOfWork, id).orElseThrow(BreakNotFound::new);
        if (row.status() == BreakStatus.RESOLVED) {
            if (row.resolvedBy().filter(actor.id()::equals).isPresent()
                    && row.resolvingEntry().filter(correctingEntry::equals).isPresent()) {
                return Acted.of(row, true);
            }
            throw new BreakStale("the break is already RESOLVED");
        }
        if (row.subject().endsWith(":" + correctingEntry)) {
            throw new CorrectingEntryInvalid("a break is resolved by another entry than the one it is about");
        }
        if (journal.entry(unitOfWork, correctingEntry).isEmpty()) {
            throw new CorrectingEntryInvalid("no journal entry has the correcting entry's id");
        }
        if (!store.resolve(unitOfWork, id, row.status(), actor, correctingEntry, reasoned)) {
            throw new IllegalStateException("the locked break moved under its own row lock: the FOR UPDATE protocol was"
                    + " bypassed");
        }
        store.appendEvent(unitOfWork, new AccountingBreakStore.EventRow(ids.next(), id, AccountingBreakStore.Act.RESOLVED,
                Optional.of(row.status()), BreakStatus.RESOLVED, actor, Optional.empty(), Optional.of(correctingEntry),
                Optional.of(reasoned), correlation));
        record(unitOfWork, actor, AccountingAuditAction.BREAK_RESOLVED, id, Optional.of(reasoned),
                summary(row.period(), row.check(), row.type()) + ", from=" + row.status().name() + ", correctingEntry="
                        + correctingEntry,
                correlation);
        return new Acted(id, row.period(), BreakStatus.RESOLVED, false, false);
    }

    // ------------------------------------------------------------------ reads

    public Optional<AccountingBreakStore.BreakRow> find(Connection unitOfWork, AccountingBreakId id) {
        return store.find(unitOfWork, id);
    }

    /** A period's breaks, oldest first. */
    public List<AccountingBreakStore.BreakRow> list(Connection unitOfWork, YearMonth period, Optional<BreakStatus> status,
            int limit) {
        return store.list(unitOfWork, period, status, limit);
    }

    /** The subjects already raised for a period's check. */
    public Set<String> subjects(Connection unitOfWork, YearMonth period, CloseCheckCode check) {
        return store.subjects(unitOfWork, period, check);
    }

    /** Breaks not {@code RESOLVED}, per type. */
    public Map<BreakType, Long> openByType(Connection unitOfWork) {
        return store.openByType(unitOfWork);
    }

    // ------------------------------------------------------------------ plumbing

    /** An authenticated human - {@code CUSTOMER}-typed (every operator session) or {@code EMPLOYEE}; fail closed. */
    private static void person(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        if (actor.type() != ActorType.CUSTOMER && actor.type() != ActorType.EMPLOYEE) {
            throw new IllegalArgumentException("a break's acknowledgement or resolution is a person's - never the"
                    + " platform's, a service's or a merchant's");
        }
    }

    private static String summary(YearMonth period, CloseCheckCode check, BreakType type) {
        return "period=" + period + ", check=" + check.name() + ", type=" + type.name();
    }

    private void record(Connection unitOfWork, Actor actor, AccountingAuditAction action, AccountingBreakId id,
            Optional<String> reason, String summary, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, TARGET_TYPE,
                id.value().toString(), reason, AuditOutcome.SUCCEEDED, correlation, Optional.of(summary)));
    }
}
