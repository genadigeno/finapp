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
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The close and the reopen - the machine's edges past the lock (`P14-TSK-012`; ADR-0114; {@code PHASE_14_PLAN.md} sections 6, 8.1 T-close and T-reopen, 8.3 rows 3, 4 and 9, 13.5; {@code INV-ACC-03},
 * {@code INV-ACC-09}, {@code INV-ACC-10}, {@code INV-AUD-04}). This decides when a month's books become immutable, and its two worst failures pull opposite ways: a close that lands when it should not,
 * and a month that can never close. Every act is a person's, one transaction, conditional on the row it holds, audited; {@code accounting V008} judges every writer again.
 *
 * <p><strong>Propose</strong> ({@link #propose}): (A1) the period {@code FOR UPDATE}, so no conclusion, re-run or unlock lands behind the reading (`P14-TSK-011`'s review, M-4) - each takes that row first.
 * It must be {@code LOCKED}, no unlock pending. {@link CloseRuns#readinessOf} on that row: a lock with no run yet, a check not {@code PASSED} or {@code DISCLOSED}, a break of the month {@code OPEN} are
 * {@link CloseNotReady}. <strong>What the person read is what is proposed</strong>: the body names the run and, per {@code DISCLOSED} attempt, the check, the attempt and a reason ({@code INV-ACC-09},
 * unamended). A {@code DISCLOSED} latest attempt with no reason is {@link AccountingReasons.ReasonRequired}; a reason naming anything but a latest {@code DISCLOSED} attempt, or another run, is
 * {@link CloseProposalStale} - so a re-run landing while the proposal was written is refused, never covered by a reason written for something else. The nine attempts are frozen in the close record, with a
 * copy of what was disclosed.
 *
 * <p><strong>Approve</strong> ({@link #approve}, four eyes): the body names the proposal the person read. (A1) the period {@code FOR UPDATE}, (A4) the proposal {@code FOR UPDATE}; refused to its proposer
 * ({@link AccountingPeriods.SelfApprovalRefused}; {@code V008}'s {@code CHECK} on the row and on the history); {@link CloseProposalStale} unless it is the period's present proposal and what it read still
 * stands - readiness read again on the row, every latest attempt the one frozen, the month's break counts and the approved set as they were. <strong>The approver never closes something other than what they
 * read</strong>: while {@code CLOSE_PROPOSED} a re-run, an unlock and a set's capture or approval are refused already, and what can still move - an overtaken worker's raise, its re-judged {@code
 * OPEN_ACCOUNTING_BREAKS}, a person's act on a break - is caught here. The way on is to reject and propose again. <strong>Two approvers, one close</strong>: the second waits on the row, finds {@code CLOSED},
 * and converges if it was theirs, {@link AccountingPeriods.PeriodStale} otherwise.
 *
 * <p><strong>Reject</strong> ({@link #reject}): {@code CLOSE_PROPOSED -> LOCKED}, the same lock and run, the proposal kept {@code REJECTED}. May be the proposer's. A next proposal is a new row with its own reasons.
 *
 * <p><strong>Reopen</strong> ({@link #proposeReopen}, {@link #approveReopen}; {@code INV-ACC-10}): only the latest {@code CLOSED} period, the next not past {@code OPEN}. The approval, four eyes, in the unlock's order
 * - (A1) the previous {@code FOR SHARE}, the period {@code FOR UPDATE}, the next {@code FOR SHARE}, oldest first; (A4) the FINAL runs {@code SUPERSEDED} before postings pause; (A2) the cutoff's key and (A3) the
 * ledger's row, back exactly one period; the edge at {@code revision + 1} - one transaction. Nothing is deleted. A posting dated after the period is unaffected before and after (row 9). A re-close is a new lock,
 * so a new run.
 *
 * <p>Nothing here posts. A reason is never logged, never in a summary and never in an exception: the audit record's reason column and the rows alone hold it.
 */
@RequiredArgsConstructor
public final class PeriodCloses {

    /** Published at a close's proposal - ids and codes only. */
    public static final String CLOSE_PROPOSED_EVENT = "accounting.AccountingPeriodCloseProposed";

    /** Published at a close - ids and codes only. */
    public static final String CLOSED_EVENT = "accounting.AccountingPeriodClosed";

    /** Published at a reopen - ids and codes only. */
    public static final String REOPENED_EVENT = "accounting.AccountingPeriodReopened";

    @NonNull private final AccountingPeriodStore periods;
    @NonNull private final PeriodCloseStore closes;
    @NonNull private final CloseRuns runs;
    @NonNull private final AccountingBreakStore breaks;
    @NonNull private final ValuationRateSetStore sets;
    @NonNull private final PostingCutoff cutoff;
    @NonNull private final ReportRunSupersession reportRuns;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    // ------------------------------------------------------------------ inputs and outcomes

    /** A person's reason for one {@code DISCLOSED} attempt, naming the attempt they read. */
    public record Disclosure(CloseCheckCode check, int attempt, String reason) {

        public Disclosure {
            Objects.requireNonNull(check, "check");
        }

        /** A reason never leaves in a text form: a record's generated {@code toString} would carry it into a log line. */
        @Override
        public String toString() {
            return "Disclosure[" + check + "#" + attempt + "]";
        }
    }

    /** A close or reopen act's outcome; {@code replayed} when the act found its edge already made by this person and wrote nothing. */
    public record Acted(AccountingPeriodId id, YearMonth month, PeriodStatus status, int revision, Optional<PeriodCloseId> proposal,
            Optional<CloseRunId> run, boolean replayed) {}

    /**
     * What an approver reads: the proposal, its nine frozen attempts with their evidence as concluded, and whether it still stands.
     *
     * @param stillStands readiness read again, every latest attempt the one frozen, the month's break counts and the set as they were - what {@link #approve} will ask
     */
    public record ProposalView(PeriodCloseStore.CloseRow proposal, PeriodStatus periodStatus, List<CheckView> checks, boolean stillStands) {

        public ProposalView {
            checks = List.copyOf(checks);
        }
    }

    /** One check of a proposal as the approver reads it: the frozen row, and the evidence of the attempt it names. */
    public record CheckView(PeriodCloseStore.CheckRow check, Optional<String> attemptEvidence) {}

    // ------------------------------------------------------------------ refusals

    /**
     * The run cannot be proposed: no run yet under the present lock, a check not {@code PASSED} or {@code DISCLOSED}, or a break of the month {@code OPEN}. Nothing was changed. Names codes and a count, never a figure.
     */
    public static final class CloseNotReady extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CloseNotReady(String detail) {
            super(detail);
        }
    }

    /** What the act names is no longer what stands - another run, an attempt not the latest, another proposal, or what the proposal read has moved. Nothing was changed. */
    public static final class CloseProposalStale extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CloseProposalStale(String detail) {
            super(detail);
        }
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes closing a {@code LOCKED} period on the attempts the person read.
     *
     * @param run the run the person read
     * @param disclosures a reason per {@code DISCLOSED} attempt, each naming its attempt
     * @throws AccountingPeriods.PeriodNotFound when no period has this id
     * @throws AccountingPeriods.PeriodStale when the period is not {@code LOCKED}, an unlock is pending, or another person's proposal stands
     * @throws CloseNotReady when the run cannot be proposed
     * @throws CloseProposalStale when the body names anything but the present run and its latest {@code DISCLOSED} attempts
     * @throws AccountingReasons.ReasonRequired for a missing, unbounded or instrument-bearing reason - the act's, or any attempt's
     */
    public Acted propose(Connection unitOfWork, AccountingPeriodId id, CloseRunId run, List<Disclosure> disclosures, Actor actor, String reason,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(disclosures, "disclosures");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        Map<CloseCheckCode, Disclosure> stated = stated(disclosures);
        AccountingPeriodStore.PeriodRow row = periods.lock(unitOfWork, id).orElseThrow(AccountingPeriods.PeriodNotFound::new);
        if (row.status() == PeriodStatus.CLOSE_PROPOSED) {
            return repeatedProposal(unitOfWork, row, run, stated, actor, reasoned);
        }
        if (row.status() != PeriodStatus.LOCKED) {
            throw new AccountingPeriods.PeriodStale("the period is " + row.status().name() + "; a close is proposed for a LOCKED period");
        }
        if (row.unlockPending()) {
            throw new AccountingPeriods.PeriodStale("an unlock of the period is pending; it is decided first");
        }
        Optional<CloseRuns.RunView> read = runs.view(unitOfWork, id);
        CloseRuns.Readiness readiness = CloseRuns.readinessOf(read);
        if (readiness.run().isEmpty()) {
            throw new CloseNotReady("the period's present lock has no close run yet");
        }
        if (!readiness.complete()) {
            throw new CloseNotReady("the run is not ready: not ready " + readiness.notReady() + ", open breaks " + readiness.openBreaks());
        }
        if (!readiness.run().get().equals(run)) {
            throw new CloseProposalStale("the run named is not the one answering the period's present lock");
        }
        CloseRuns.RunView view = read.orElseThrow();
        List<PeriodCloseStore.CheckRow> rows = new ArrayList<>();
        PeriodCloseId proposal = PeriodCloseId.next(ids);
        for (CloseRuns.CheckView check : view.checks()) {
            rows.add(frozen(proposal, check.latest(), stated));
        }
        AccountingPeriodStore.Pin pin = row.pinned().orElseThrow(() -> new CloseNotReady("the lock pinned no GL mapping version"));
        ValuationRateSetStore.SetRow set = sets.approvedFor(unitOfWork, id).orElseThrow(() -> new CloseNotReady("the period has no approved valuation rate set"));
        Map<BreakStatus, Long> counts = counted(unitOfWork, row.month());
        if (counts.get(BreakStatus.OPEN) > 0) {
            throw new CloseNotReady("the run is not ready: open breaks " + counts.get(BreakStatus.OPEN));
        }
        closes.insert(unitOfWork, new PeriodCloseStore.NewClose(proposal, id, row.revision(), row.lockedAt().orElseThrow(), run, pin.id(), set.id(),
                counts.get(BreakStatus.ACKNOWLEDGED), counts.get(BreakStatus.RESOLVED), actor, reasoned, correlation));
        rows.forEach(frozen -> closes.insertCheck(unitOfWork, frozen));
        if (!periods.markCloseProposed(unitOfWork, id, row.revision())) {
            throw new IllegalStateException("the locked period moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        periods.appendEvent(unitOfWork, new AccountingPeriodStore.EventRow(ids.next(), id, AccountingPeriodStore.Act.CLOSE_PROPOSED,
                Optional.of(PeriodStatus.LOCKED), PeriodStatus.CLOSE_PROPOSED, row.revision(), actor, Optional.empty(), Optional.empty(),
                Optional.of(reasoned), correlation));
        record(unitOfWork, actor, AccountingAuditAction.PERIOD_CLOSE_PROPOSED, id, Optional.of(reasoned), "period=" + row.month() + ", revision="
                + row.revision() + ", run=" + run.value() + ", proposal=" + proposal.value() + ", disclosed=" + disclosedCodes(rows), correlation);
        publish(unitOfWork, CLOSE_PROPOSED_EVENT, id, EventPayload.of()
                .with("period", row.month().toString())
                .with("revision", Integer.toString(row.revision()))
                .with("proposal", proposal.value().toString())
                .with("closeRun", run.value().toString())
                .with("mappingVersion", Integer.toString(pin.version()))
                .with("valuationRateSet", set.id().value().toString()), correlation);
        return new Acted(id, row.month(), PeriodStatus.CLOSE_PROPOSED, row.revision(), Optional.of(proposal), Optional.of(run), false);
    }

    /** A proposal standing: the same person saying the same thing converges; anything else is refused while it stands. */
    private Acted repeatedProposal(Connection unitOfWork, AccountingPeriodStore.PeriodRow row, CloseRunId run, Map<CloseCheckCode, Disclosure> stated, Actor actor,
            String reasoned) {
        PeriodCloseStore.CloseRow standing = closes.latest(unitOfWork, row.id()).orElseThrow(
                () -> new IllegalStateException("a CLOSE_PROPOSED period has its proposal"));
        boolean sameAct = standing.proposedBy().equals(actor.id()) && standing.run().equals(run) && standing.proposalReason().equals(reasoned)
                && sameDisclosures(closes.checks(unitOfWork, standing.id()), stated);
        if (!sameAct) {
            throw new AccountingPeriods.PeriodStale("a close of the period is already proposed; it stands until a second person decides it");
        }
        return new Acted(row.id(), row.month(), row.status(), row.revision(), Optional.of(standing.id()), Optional.of(standing.run()), true);
    }

    private static boolean sameDisclosures(List<PeriodCloseStore.CheckRow> frozen, Map<CloseCheckCode, Disclosure> stated) {
        List<PeriodCloseStore.CheckRow> disclosed = frozen.stream().filter(check -> check.status() == CloseCheckStatus.DISCLOSED).toList();
        return disclosed.size() == stated.size() && disclosed.stream().allMatch(check -> {
            Disclosure disclosure = stated.get(check.check());
            return disclosure != null && disclosure.attempt() == check.attempt() && check.disclosureReason().filter(AccountingReasons.reasoned(disclosure.reason())::equals).isPresent();
        });
    }

    /** One latest attempt, frozen: a {@code DISCLOSED} one with the person's reason for that very attempt, or refused. */
    private static PeriodCloseStore.CheckRow frozen(PeriodCloseId proposal, CloseRunStore.AttemptRow attempt, Map<CloseCheckCode, Disclosure> stated) {
        Disclosure disclosure = stated.get(attempt.check());
        if (disclosure != null && (disclosure.attempt() != attempt.attempt() || attempt.status() != CloseCheckStatus.DISCLOSED)) {
            throw new CloseProposalStale("a reason names " + attempt.check().name() + " attempt " + disclosure.attempt() + "; its latest attempt is " + attempt.attempt()
                    + ", " + attempt.status().name());
        }
        if (attempt.status() == CloseCheckStatus.DISCLOSED && disclosure == null) {
            throw new AccountingReasons.ReasonRequired("a person's reason is required for each DISCLOSED attempt: " + attempt.check().name() + " attempt " + attempt.attempt());
        }
        String judgedBy = attempt.judgedBy().orElseThrow(() -> new IllegalStateException("a concluded attempt names what judged it"));
        return attempt.status() == CloseCheckStatus.DISCLOSED
                ? new PeriodCloseStore.CheckRow(proposal, attempt.check(), attempt.id(), attempt.attempt(), attempt.status(), judgedBy, attempt.evidence(),
                        Optional.of(disclosure.reason()))
                : new PeriodCloseStore.CheckRow(proposal, attempt.check(), attempt.id(), attempt.attempt(), attempt.status(), judgedBy, Optional.empty(), Optional.empty());
    }

    // ------------------------------------------------------------------ approve, reject

    /**
     * Approves the present proposal - a different person's act, on the proposal they read: {@code CLOSE_PROPOSED -> CLOSED}. The same approver's repeat converges.
     *
     * @throws AccountingPeriods.PeriodNotFound when no period has this id
     * @throws AccountingPeriods.PeriodStale when the period is not {@code CLOSE_PROPOSED}
     * @throws AccountingPeriods.SelfApprovalRefused when the approver proposed it ({@code INV-AUD-04})
     * @throws CloseProposalStale when it is not the present proposal, or what it read no longer stands
     */
    public Acted approve(Connection unitOfWork, AccountingPeriodId id, PeriodCloseId proposal, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        AccountingPeriodStore.PeriodRow row = periods.lock(unitOfWork, id).orElseThrow(AccountingPeriods.PeriodNotFound::new);
        PeriodCloseStore.CloseRow standing = closes.lock(unitOfWork, proposal).filter(close -> close.period().equals(id))
                .orElseThrow(() -> new CloseProposalStale("the proposal named is not this period's"));
        if (row.status() == PeriodStatus.CLOSED && standing.status() == PeriodCloseStatus.APPROVED && standing.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Acted(id, row.month(), row.status(), row.revision(), Optional.of(standing.id()), Optional.of(standing.run()), true);
        }
        if (row.status() != PeriodStatus.CLOSE_PROPOSED) {
            throw new AccountingPeriods.PeriodStale("the period is " + row.status().name() + "; a close is approved while it is CLOSE_PROPOSED");
        }
        if (standing.status() != PeriodCloseStatus.PROPOSED) {
            throw new CloseProposalStale("the proposal named was " + standing.status().name() + "; another stands");
        }
        if (standing.proposedBy().equals(actor.id())) {
            throw new AccountingPeriods.SelfApprovalRefused(id, row.month(), "period:close");
        }
        Optional<String> moved = moved(unitOfWork, row, standing);
        if (moved.isPresent()) {
            throw new CloseProposalStale(moved.get() + "; it is rejected and proposed again");
        }
        if (!closes.decide(unitOfWork, standing.id(), PeriodCloseStatus.APPROVED, actor, reasoned)) {
            throw new IllegalStateException("the locked proposal was decided by another writer: the FOR UPDATE protocol was bypassed");
        }
        if (!periods.markClosed(unitOfWork, id, row.revision())) {
            throw new IllegalStateException("the locked period moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        periods.appendEvent(unitOfWork, new AccountingPeriodStore.EventRow(ids.next(), id, AccountingPeriodStore.Act.CLOSED,
                Optional.of(PeriodStatus.CLOSE_PROPOSED), PeriodStatus.CLOSED, row.revision(), actor, Optional.of(standing.proposedBy()), Optional.empty(),
                Optional.of(reasoned), correlation));
        record(unitOfWork, actor, AccountingAuditAction.PERIOD_CLOSED, id, Optional.of(reasoned), "period=" + row.month() + ", revision=" + row.revision() + ", run="
                + standing.run().value() + ", proposal=" + standing.id().value() + ", valuationRateSet=" + standing.rateSet().value(), correlation);
        publish(unitOfWork, CLOSED_EVENT, id, EventPayload.of()
                .with("period", row.month().toString())
                .with("revision", Integer.toString(row.revision()))
                .with("proposal", standing.id().value().toString())
                .with("closeRun", standing.run().value().toString())
                .with("valuationRateSet", standing.rateSet().value().toString()), correlation);
        return new Acted(id, row.month(), PeriodStatus.CLOSED, row.revision(), Optional.of(standing.id()), Optional.of(standing.run()), false);
    }

    /**
     * Rejects the present proposal: {@code CLOSE_PROPOSED -> LOCKED}, the same lock and run; a next proposal is a new row with its own reasons. The same rejecter's repeat converges.
     *
     * @throws AccountingPeriods.PeriodNotFound when no period has this id
     * @throws AccountingPeriods.PeriodStale when the period is not {@code CLOSE_PROPOSED}
     * @throws CloseProposalStale when it is not the present proposal
     */
    public Acted reject(Connection unitOfWork, AccountingPeriodId id, PeriodCloseId proposal, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        AccountingPeriodStore.PeriodRow row = periods.lock(unitOfWork, id).orElseThrow(AccountingPeriods.PeriodNotFound::new);
        PeriodCloseStore.CloseRow standing = closes.lock(unitOfWork, proposal).filter(close -> close.period().equals(id))
                .orElseThrow(() -> new CloseProposalStale("the proposal named is not this period's"));
        if (standing.status() == PeriodCloseStatus.REJECTED && standing.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Acted(id, row.month(), row.status(), row.revision(), Optional.of(standing.id()), Optional.of(standing.run()), true);
        }
        if (row.status() != PeriodStatus.CLOSE_PROPOSED) {
            throw new AccountingPeriods.PeriodStale("the period is " + row.status().name() + "; a close is rejected while it is CLOSE_PROPOSED");
        }
        if (standing.status() != PeriodCloseStatus.PROPOSED) {
            throw new CloseProposalStale("the proposal named was " + standing.status().name() + "; another stands");
        }
        if (!closes.decide(unitOfWork, standing.id(), PeriodCloseStatus.REJECTED, actor, reasoned)) {
            throw new IllegalStateException("the locked proposal was decided by another writer: the FOR UPDATE protocol was bypassed");
        }
        if (!periods.markCloseRejected(unitOfWork, id, row.revision())) {
            throw new IllegalStateException("the locked period moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        periods.appendEvent(unitOfWork, new AccountingPeriodStore.EventRow(ids.next(), id, AccountingPeriodStore.Act.CLOSE_REJECTED,
                Optional.of(PeriodStatus.CLOSE_PROPOSED), PeriodStatus.LOCKED, row.revision(), actor, Optional.empty(), Optional.empty(), Optional.of(reasoned), correlation));
        record(unitOfWork, actor, AccountingAuditAction.PERIOD_CLOSE_REJECTED, id, Optional.of(reasoned), "period=" + row.month() + ", revision=" + row.revision()
                + ", proposal=" + standing.id().value() + ", byProposer=" + standing.proposedBy().equals(actor.id()), correlation);
        return new Acted(id, row.month(), PeriodStatus.LOCKED, row.revision(), Optional.of(standing.id()), Optional.of(standing.run()), false);
    }

    /** What moved since {@code standing} was proposed, read on the period row the caller holds; empty when it all still stands. */
    private Optional<String> moved(Connection unitOfWork, AccountingPeriodStore.PeriodRow row, PeriodCloseStore.CloseRow standing) {
        Optional<CloseRuns.RunView> read = runs.view(unitOfWork, row.id());
        CloseRuns.Readiness readiness = CloseRuns.readinessOf(read);
        if (readiness.run().filter(standing.run()::equals).isEmpty()) {
            return Optional.of("the run proposed no longer answers the period's present lock");
        }
        if (!readiness.complete()) {
            return Optional.of("the run is no longer ready: not ready " + readiness.notReady() + ", open breaks " + readiness.openBreaks());
        }
        Map<CloseCheckCode, CloseCheckId> latest = new HashMap<>();
        read.orElseThrow().checks().forEach(check -> latest.put(check.check(), check.latest().id()));
        List<PeriodCloseStore.CheckRow> frozen = closes.checks(unitOfWork, standing.id());
        if (frozen.size() != CloseCheckCode.values().length) {
            return Optional.of("the proposal does not hold every check");
        }
        for (PeriodCloseStore.CheckRow check : frozen) {
            if (!check.attemptId().equals(latest.get(check.check()))) {
                return Optional.of(check.check().name() + " has a later attempt than the one proposed");
            }
        }
        Map<BreakStatus, Long> counts = counted(unitOfWork, row.month());
        if (counts.get(BreakStatus.OPEN) != 0 || counts.get(BreakStatus.ACKNOWLEDGED) != standing.acknowledged() || counts.get(BreakStatus.RESOLVED) != standing.resolved()) {
            return Optional.of("the month's breaks have moved since the proposal");
        }
        if (sets.approvedFor(unitOfWork, row.id()).map(ValuationRateSetStore.SetRow::id).filter(standing.rateSet()::equals).isEmpty()) {
            return Optional.of("the period's approved valuation rate set is not the one proposed");
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ reopen

    /**
     * Proposes reopening the latest {@code CLOSED} period - nothing moves. The same proposer's repeat with the same reason converges.
     *
     * @throws AccountingPeriods.PeriodNotFound when no period has this id
     * @throws AccountingPeriods.PeriodStale when the period is not {@code CLOSED}, it is not the latest closed, the next is past {@code OPEN}, or another person's proposal stands
     */
    public Acted proposeReopen(Connection unitOfWork, AccountingPeriodId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        AccountingPeriodStore.PeriodRow row = periods.lock(unitOfWork, id).orElseThrow(AccountingPeriods.PeriodNotFound::new);
        if (row.status() != PeriodStatus.CLOSED) {
            throw new AccountingPeriods.PeriodStale("the period is " + row.status().name() + "; only a CLOSED period reopens");
        }
        requireLatestClosed(unitOfWork, row);
        Optional<PeriodCloseStore.ReopenRow> standing = closes.reopenAt(unitOfWork, id, row.revision());
        if (standing.isPresent()) {
            if (standing.get().proposedBy().equals(actor.id()) && standing.get().proposalReason().equals(reasoned)) {
                return new Acted(id, row.month(), row.status(), row.revision(), Optional.empty(), Optional.empty(), true);
            }
            throw new AccountingPeriods.PeriodStale("a reopen of the period is already proposed and awaits a second person");
        }
        closes.insertReopen(unitOfWork, new PeriodCloseStore.NewReopen(PeriodReopenId.next(ids), id, row.revision(), actor, reasoned, correlation));
        periods.appendEvent(unitOfWork, new AccountingPeriodStore.EventRow(ids.next(), id, AccountingPeriodStore.Act.REOPEN_PROPOSED, Optional.of(PeriodStatus.CLOSED),
                PeriodStatus.CLOSED, row.revision(), actor, Optional.empty(), Optional.empty(), Optional.of(reasoned), correlation));
        record(unitOfWork, actor, AccountingAuditAction.PERIOD_REOPEN_PROPOSED, id, Optional.of(reasoned), "period=" + row.month() + ", revision=" + row.revision(), correlation);
        return new Acted(id, row.month(), PeriodStatus.CLOSED, row.revision(), Optional.empty(), Optional.empty(), false);
    }

    /**
     * Approves a pending reopen - a different person's act: the FINAL runs superseded, the cutoff back one period, {@code CLOSED -> OPEN} at {@code revision + 1}, the pin released, one transaction. The same
     * approver's repeat converges.
     *
     * @throws AccountingPeriods.PeriodNotFound when no period has this id
     * @throws AccountingPeriods.PeriodStale when the period is not {@code CLOSED} with a reopen pending, it is not the latest closed, or the next is past {@code OPEN}
     * @throws AccountingPeriods.SelfApprovalRefused when the approver proposed it ({@code INV-AUD-04})
     * @throws PostingCutoff.CutoffBusy when postings held the cutoff through the ledger's bounded attempts - nothing changed; retry
     */
    public Acted approveReopen(Connection unitOfWork, AccountingPeriodId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        // A1, oldest first. The month is frozen from birth, so the unlocked read only names which row precedes it.
        YearMonth month = periods.find(unitOfWork, id).orElseThrow(AccountingPeriods.PeriodNotFound::new).month();
        Optional<AccountingPeriodStore.PeriodRow> previous = periods.lockShared(unitOfWork, month.minusMonths(1));
        AccountingPeriodStore.PeriodRow row = periods.lock(unitOfWork, id).orElseThrow(AccountingPeriods.PeriodNotFound::new);
        if (row.status() == PeriodStatus.OPEN && row.revision() > 1) {
            Optional<PeriodCloseStore.ReopenRow> last = closes.reopenAt(unitOfWork, id, row.revision() - 1);
            if (last.flatMap(PeriodCloseStore.ReopenRow::approvedBy).filter(actor.id()::equals).isPresent()) {
                return new Acted(id, row.month(), row.status(), row.revision(), Optional.empty(), Optional.empty(), true);
            }
        }
        if (row.status() != PeriodStatus.CLOSED) {
            throw new AccountingPeriods.PeriodStale("the period is " + row.status().name() + "; only a CLOSED period reopens");
        }
        requireLatestClosed(unitOfWork, row);
        PeriodCloseStore.ReopenRow standing = closes.lockReopenAt(unitOfWork, id, row.revision())
                .orElseThrow(() -> new AccountingPeriods.PeriodStale("no reopen of the period is proposed"));
        if (standing.status() != PeriodReopenStatus.PROPOSED) {
            throw new IllegalStateException("a CLOSED period's reopen of this revision is pending, or its revision would have moved");
        }
        if (standing.proposedBy().equals(actor.id())) {
            throw new AccountingPeriods.SelfApprovalRefused(id, row.month(), "period:reopen");
        }
        // The unlock's order: A4 under the period's FOR UPDATE and BEFORE the cutoff's key, so postings do not wait on these writes.
        int superseded = reportRuns.supersedeFinal(unitOfWork, id, actor, correlation);
        if (!closes.approveReopen(unitOfWork, standing.id(), actor, reasoned)) {
            throw new IllegalStateException("the locked reopen was decided by another writer: the FOR UPDATE protocol was bypassed");
        }
        cutoff.moveBack(unitOfWork, row.end(), previous.map(AccountingPeriodStore.PeriodRow::end),
                "accounting period " + row.month() + " reopened under four eyes");
        if (!periods.markReopened(unitOfWork, id, row.revision())) {
            throw new IllegalStateException("the locked period moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        int revision = row.revision() + 1;
        periods.appendEvent(unitOfWork, new AccountingPeriodStore.EventRow(ids.next(), id, AccountingPeriodStore.Act.REOPENED, Optional.of(PeriodStatus.CLOSED), PeriodStatus.OPEN,
                revision, actor, Optional.of(standing.proposedBy()), Optional.empty(), Optional.of(reasoned), correlation));
        record(unitOfWork, actor, AccountingAuditAction.PERIOD_REOPENED, id, Optional.of(reasoned), "period=" + row.month() + ", revision=" + revision + ", supersededRuns=" + superseded, correlation);
        EventPayload payload = EventPayload.of()
                .with("period", row.month().toString())
                .with("revision", Integer.toString(revision))
                .with("supersededRuns", Integer.toString(superseded));
        if (previous.isPresent()) {
            payload = payload.with("closedThrough", previous.get().end().toString());
        }
        publish(unitOfWork, REOPENED_EVENT, id, payload, correlation);
        return new Acted(id, row.month(), PeriodStatus.OPEN, revision, Optional.empty(), Optional.empty(), false);
    }

    /** {@code INV-ACC-10}: the next period, when there is one, has not left {@code OPEN} - so periods closing in order, this is the latest closed. (A1) oldest first: the caller holds this row. */
    private void requireLatestClosed(Connection unitOfWork, AccountingPeriodStore.PeriodRow row) {
        Optional<AccountingPeriodStore.PeriodRow> next = periods.lockShared(unitOfWork, row.month().plusMonths(1));
        if (next.isPresent() && next.get().status() != PeriodStatus.OPEN) {
            throw new AccountingPeriods.PeriodStale("only the latest closed period reopens, the next not locked: " + next.get().month() + " is " + next.get().status().name());
        }
    }

    // ------------------------------------------------------------------ reads, refusals' records

    /**
     * The period's latest proposal as an approver reads it: its nine frozen attempts with their evidence, and whether it still stands. Unlocked: {@link #approve} judges again on the row.
     *
     * @throws AccountingPeriods.PeriodNotFound when no period has this id
     * @return empty when the period has had no proposal
     */
    public Optional<ProposalView> proposalOf(Connection unitOfWork, AccountingPeriodId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        AccountingPeriodStore.PeriodRow row = periods.find(unitOfWork, id).orElseThrow(AccountingPeriods.PeriodNotFound::new);
        return closes.latest(unitOfWork, id).map(proposal -> {
            Map<CloseCheckCode, String> evidence = closes.attemptEvidence(unitOfWork, proposal.id());
            List<CheckView> checks = closes.checks(unitOfWork, proposal.id()).stream()
                    .map(check -> new CheckView(check, Optional.ofNullable(evidence.get(check.check()))))
                    .toList();
            boolean stands = row.status() == PeriodStatus.CLOSE_PROPOSED && proposal.status() == PeriodCloseStatus.PROPOSED
                    && moved(unitOfWork, row, proposal).isEmpty();
            return new ProposalView(proposal, row.status(), checks, stands);
        });
    }

    /** Records an approver's read of what they decide ({@code accounting.CloseProposalRead}) - before it is served. */
    public void recordRead(Connection unitOfWork, ProposalView view, Actor actor, CorrelationId correlation) {
        record(unitOfWork, actor, AccountingAuditAction.CLOSE_PROPOSAL_READ, view.proposal().period(), Optional.empty(), "period=" + view.proposal().month() + ", proposal="
                + view.proposal().id().value() + ", status=" + view.proposal().status().name() + ", stillStands=" + view.stillStands(), correlation);
    }

    /**
     * Records a refused self-approval {@code FAILED} ({@link AccountingAuditAction#SELF_APPROVAL_REFUSED}) on {@code unitOfWork} - the caller's own transaction, after the act rolled back, so the attempt is kept.
     */
    public void recordRefusal(Connection unitOfWork, AccountingPeriods.SelfApprovalRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), AccountingAuditAction.SELF_APPROVAL_REFUSED, AccountingPeriods.TARGET_TYPE, refused.target(),
                Optional.empty(), AuditOutcome.FAILED, correlation, Optional.of(refused.summary())));
    }

    // ------------------------------------------------------------------ plumbing

    private static Map<CloseCheckCode, Disclosure> stated(List<Disclosure> disclosures) {
        Map<CloseCheckCode, Disclosure> stated = new EnumMap<>(CloseCheckCode.class);
        for (Disclosure disclosure : disclosures) {
            Objects.requireNonNull(disclosure, "disclosure");
            AccountingReasons.reasoned(disclosure.reason());
            if (stated.putIfAbsent(disclosure.check(), disclosure) != null) {
                throw new CloseProposalStale("a check's attempt is disclosed once: " + disclosure.check().name());
            }
        }
        return stated;
    }

    private static String disclosedCodes(List<PeriodCloseStore.CheckRow> rows) {
        return rows.stream().filter(row -> row.status() == CloseCheckStatus.DISCLOSED).map(row -> row.check().name() + "#" + row.attempt()).collect(Collectors.joining("+", "[", "]"));
    }

    private Map<BreakStatus, Long> counted(Connection unitOfWork, YearMonth month) {
        Map<BreakStatus, Long> counts = new EnumMap<>(BreakStatus.class);
        for (BreakStatus status : BreakStatus.values()) {
            counts.put(status, 0L);
        }
        counts.putAll(breaks.countByStatus(unitOfWork, month));
        return counts;
    }

    /** An authenticated human - {@code CUSTOMER}-typed (every operator session) or {@code EMPLOYEE}; fail closed. */
    private static void person(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        if (actor.type() != ActorType.CUSTOMER && actor.type() != ActorType.EMPLOYEE) {
            throw new IllegalArgumentException("a period's close or reopen is a person's - never the platform's, a service's or a merchant's");
        }
    }

    private void publish(Connection unitOfWork, String type, AccountingPeriodId id, EventPayload payload, CorrelationId correlation) {
        Instant now = clock.instant();
        outbox.write(unitOfWork, new EventEnvelope(EventId.next(ids), type, AccountingPeriods.EVENT_VERSION, EventEnvelope.CURRENT_SCHEMA_VERSION, id, AccountingPeriods.TARGET_TYPE, now,
                AccountingPeriods.PRODUCER, correlation, CausationId.of(correlation.value())), payload.toBytes(), EventPayload.MEDIA_TYPE);
    }

    private void record(Connection unitOfWork, Actor actor, AccountingAuditAction action, AccountingPeriodId id, Optional<String> reason, String summary, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, AccountingPeriods.TARGET_TYPE, id.value().toString(), reason, AuditOutcome.SUCCEEDED,
                correlation, Optional.of(summary)));
    }
}
