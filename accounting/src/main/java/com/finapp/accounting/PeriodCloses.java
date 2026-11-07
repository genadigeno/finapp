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
import java.util.Set;
import java.util.UUID;
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
 * copy of what was disclosed. <strong>The month's run is judged again here</strong> (the independent review, I-2): a batch repudiated after {@code RECONCILIATION_DISCLOSURE} concluded takes its run with it, and the frozen
 * evidence would say it ran, so {@link CloseChecks.MonthRuns#ran} is read on this connection, holding the row, and {@link CloseNotReady} refuses a month with no run that counts. Only {@code ran} refuses: counts moving
 * while a counting run stands do not, or a close would chase traffic.
 *
 * <p><strong>Approve</strong> ({@link #approve}, four eyes): the body names the proposal the person read. (A1) the period {@code FOR UPDATE}, (A4) the proposal {@code FOR UPDATE}; refused to its proposer
 * ({@link AccountingPeriods.SelfApprovalRefused}; {@code V008}'s {@code CHECK} on the row and on the history); {@link CloseProposalStale} unless it is the period's present proposal and what it read still
 * stands - readiness read again on the row, every latest attempt the one frozen, the month's break counts and the approved set as they were, the month's run still counting. <strong>The approver never closes something
 * other than what they read</strong>: while {@code CLOSE_PROPOSED} a re-run, an unlock and a set's capture or approval are refused already. What can still move is read at this one moment, and <strong>its window is
 * stated</strong>: neither a break's writers nor a repudiation take the period row, so one committing after this read is not caught. A resolve or an acknowledgement then leaves the record's {@code breaks_resolved} or {@code
 * breaks_acknowledged} off by one, with no false close, since it opens nothing; an overtaken worker's raise leaves an {@code OPEN} break on a month just {@code CLOSED}, never hidden; a repudiation leaves a closed month with no
 * counting run, the way on being the reopen, and {@code RECONCILIATION_STATUS} shows it. An approval refused is rejected and proposed again, or for a run withdrawn, answered by a new batch. <strong>Two approvers, one
 * close</strong>: the second waits on the row, finds {@code CLOSED}, and converges if it was theirs, {@link AccountingPeriods.PeriodStale} otherwise. Neither a repeat of a proposal nor of an approval re-judges anything: each
 * converges on what was done.
 *
 * <p><strong>Reject</strong> ({@link #reject}): {@code CLOSE_PROPOSED -> LOCKED}, the same lock and run, the proposal kept {@code REJECTED}. May be the proposer's. A next proposal is a new row with its own reasons.
 *
 * <p><strong>Reopen</strong> ({@link #proposeReopen}, {@link #approveReopen}; {@code INV-ACC-10}): only the latest {@code CLOSED} period, the next not past {@code OPEN}. The approval, four eyes, in the unlock's order
 * - (A1) the previous {@code FOR SHARE}, the period {@code FOR UPDATE}, the next {@code FOR SHARE}, oldest first; (A4) the FINAL runs {@code SUPERSEDED} before postings pause; (A2) the cutoff's key and (A3) the
 * ledger's row, back exactly one period; the edge at {@code revision + 1} - one transaction. Nothing is deleted. A posting dated after the period is unaffected before and after (row 9). A re-close is a new lock,
 * so a new run.
 *
 * <p><strong>A reopen names the posted adjustments it read</strong> (`P14-TSK-022`, the owner's ruling (m); {@code INV-ACC-10}): a month's figures at the next revision still show what those corrected, each entry being dated in a later month, so whoever corrects the month directly
 * would correct it twice. Each act's body names them, and differing from what is posted <em>at that act</em> is {@link PriorPeriodAdjustments.AdjustmentStale}, the refusal an adjustment's own approval raises. The approval is compared with what stands, never with the proposal: a pending
 * reopen does not stop an adjustment's approval (ruling (n)), so the set may have moved between the two acts, and an approver attests to what stands when they act. Read by a plain statement after the period's row is held: no writer reaches {@code POSTED} without that row {@code FOR SHARE}, and
 * {@code POSTED} is terminal, so the set cannot move while it is held; and the read takes no lock, so it adds no edge to the plan's order. Nothing about it is stored: what was named is what was posted, on the adjustments' own rows.
 *
 * <p><strong>A reopen ended without its effect</strong> (`P14-TSK-024`, the owner's acceptance of `P14-TSK-012`'s item (d); {@code accounting V011}): a second person rejects it ({@link #rejectReopen}), or
 * its proposer withdraws it ({@link #withdrawReopen}) - {@link PeriodRequestEnd}, each its own terminal state on the reopen's row, beside a history row. Nothing else moves: the period stays {@code CLOSED} at
 * the same revision, nothing is superseded, the cutoff stands, and the month is free for a new proposal. (A1) the period {@code FOR UPDATE}, then (A4) the pending reopen {@code FOR UPDATE} - the approval's
 * order without its neighbours - so an end and the approval serialise on the period row, and the second is told by name what the first did. A pending reopen whose next month has since locked can never be
 * approved; an end is how it leaves.
 *
 * <p><strong>Every act on a pending reopen names it</strong> (`P14-TSK-024`'s fix round): an end makes a pending reopen replaceable within seconds, so the approval, the rejection and the
 * withdrawal each name the reopen read by its id ({@link #pendingOf} serves it, as the approver's read does), as the close's approval names its proposal. Another pending is
 * {@link AccountingPeriods.RequestStale}, judged holding the period's row and the reopen's, and recorded {@code FAILED}: nobody decides a reopen they never read.
 *
 * <p><strong>The approver's read</strong> ({@link #proposalOf}) serves each frozen attempt as it concluded: its evidence and {@code concludedAt}, read from the attempt's own row (`P14-TSK-024`, `-012`'s
 * (j)). Nothing re-judges {@code SOURCE_COMPLETENESS}, {@code ACCRUALS_COMPLETE}, {@code PROJECTION_VERIFIED} or most of {@code RECONCILIATION_DISCLOSURE} at the approval, so the person deciding attests to
 * what each judged no later than that instant. It names the reopen pending, if one is, for the reopen's approver.
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
    @NonNull private final ReconciliationRunCoverage coverage;
    @NonNull private final PostingCutoff cutoff;
    @NonNull private final ReportRunSupersession reportRuns;
    /** What has posted against a month, read to judge what a reopen names. Never written here. */
    @NonNull private final PriorPeriodAdjustmentStore adjustments;
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
     * What an approver reads: the proposal, its nine frozen attempts with their evidence and when each concluded, whether it still stands, and the reopen pending on the period, if one is.
     *
     * @param stillStands readiness read again, every latest attempt the one frozen, the month's break counts and the set as they were - what {@link #approve} will ask
     * @param pendingReopen the reopen pending on the {@code CLOSED} period at its present revision (`P14-TSK-024`) - what a reopen's approver decides; empty when none is
     * @param referenceGaps how many of the named set's rates a reference-gap exception admitted beyond the staleness bound (`P14-DOC-001`, `P14-TSK-026`'s owner item (c); ADR-0119 decision 2) - the
     *     set's own {@code referenceGaps}, so the close's approver is told on the close's own read, as the set's read and every translated report tell; 0 for an ordinary capture
     */
    public record ProposalView(PeriodCloseStore.CloseRow proposal, PeriodStatus periodStatus, List<CheckView> checks, boolean stillStands, Optional<PeriodCloseStore.ReopenRow> pendingReopen,
            int referenceGaps) {

        public ProposalView {
            checks = List.copyOf(checks);
            Objects.requireNonNull(pendingReopen, "pendingReopen");
            if (referenceGaps < 0) {
                throw new IllegalArgumentException("a set admits no fewer than no gaps");
            }
        }
    }

    /**
     * One check of a proposal as the approver reads it: the frozen row, and the attempt it names as that attempt concluded - its evidence and {@code concludedAt}, the database's clock as stored
     * (`P14-TSK-024`). The frozen attempt is the one served: a later attempt is never this one's, and makes {@link ProposalView#stillStands} false.
     */
    public record CheckView(PeriodCloseStore.CheckRow check, PeriodCloseStore.FrozenAttempt attempt) {

        public CheckView {
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(attempt, "attempt");
        }

        /** The evidence is {@code CONFIDENTIAL} aggregates, served to the approver and never logged: a record's generated {@code toString} would carry it into a log line. */
        @Override
        public String toString() {
            return "CheckView[" + check + "]";
        }
    }

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
     * @throws CloseNotReady when the run cannot be proposed, or the month no longer has a run that counts for each active source
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
            throw new AccountingPeriods.PeriodStale("an unlock of the period is pending; it is approved, rejected or withdrawn first");
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
        CloseChecks.MonthRuns month = monthRuns(unitOfWork, row.month());
        if (!month.ran()) {
            throw new CloseNotReady(monthNotRun(month));
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
            throw new CloseProposalStale(moved.get() + "; nothing was changed");
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
        CloseChecks.MonthRuns month = monthRuns(unitOfWork, row.month());
        if (!month.ran()) {
            return Optional.of(monthNotRun(month));
        }
        return Optional.empty();
    }

    /**
     * The owner's rule read again, on {@code unitOfWork}, the caller holding the period row: the attempt's evidence is what stood when it concluded, and a repudiation moves what it read. On the caller's own connection, because a
     * second one, taken while holding the row, could wait on a pool every holder of a period row is waiting on. {@code READ COMMITTED}, so {@link ReconciliationRunCoverage}'s two statements see two instants: its javadoc says why {@code ran} holds on them.
     */
    private CloseChecks.MonthRuns monthRuns(Connection unitOfWork, YearMonth month) {
        return CloseChecks.MonthRuns.judge(coverage.ofMonth(unitOfWork, month));
    }

    private static String monthNotRun(CloseChecks.MonthRuns month) {
        return "the month no longer has a run that counts: not run " + month.notRun();
    }

    // ------------------------------------------------------------------ reopen

    /**
     * Proposes reopening the latest {@code CLOSED} period, naming the adjustments posted against it - nothing moves. The same proposer's repeat with the same reason converges.
     *
     * @param named the posted adjustments the person read
     * @throws AccountingPeriods.PeriodNotFound when no period has this id
     * @throws AccountingPeriods.PeriodStale when the period is not {@code CLOSED}, it is not the latest closed, the next is past {@code OPEN}, or another person's proposal stands
     * @throws PriorPeriodAdjustments.AdjustmentStale when what is named is not what is posted now
     */
    public Acted proposeReopen(Connection unitOfWork, AccountingPeriodId id, Actor actor, Set<UUID> named, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(named, "named");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        AccountingPeriodStore.PeriodRow row = periods.lock(unitOfWork, id).orElseThrow(AccountingPeriods.PeriodNotFound::new);
        if (row.status() != PeriodStatus.CLOSED) {
            throw new AccountingPeriods.PeriodStale("the period is " + row.status().name() + "; only a CLOSED period reopens");
        }
        requireLatestClosed(unitOfWork, row);
        Optional<PeriodCloseStore.ReopenRow> standing = closes.pendingReopen(unitOfWork, id);
        standing.ifPresent(pending -> requireAtTheRevision(pending, row));
        if (standing.isPresent()) {
            if (standing.get().proposedBy().equals(actor.id()) && standing.get().proposalReason().equals(reasoned)) {
                return new Acted(id, row.month(), row.status(), row.revision(), Optional.empty(), Optional.empty(), true);
            }
            throw new AccountingPeriods.PeriodStale("a reopen of the period is already proposed and awaits a second person");
        }
        int posted = requireNamesWhatIsPosted(unitOfWork, id, named);
        closes.insertReopen(unitOfWork, new PeriodCloseStore.NewReopen(PeriodReopenId.next(ids), id, row.revision(), actor, reasoned, correlation));
        periods.appendEvent(unitOfWork, new AccountingPeriodStore.EventRow(ids.next(), id, AccountingPeriodStore.Act.REOPEN_PROPOSED, Optional.of(PeriodStatus.CLOSED),
                PeriodStatus.CLOSED, row.revision(), actor, Optional.empty(), Optional.empty(), Optional.of(reasoned), correlation));
        record(unitOfWork, actor, AccountingAuditAction.PERIOD_REOPEN_PROPOSED, id, Optional.of(reasoned), "period=" + row.month() + ", revision=" + row.revision() + ", postedAdjustments=" + posted, correlation);
        return new Acted(id, row.month(), PeriodStatus.CLOSED, row.revision(), Optional.empty(), Optional.empty(), false);
    }

    /**
     * Approves the pending reopen the person read, named by its id, naming the adjustments posted against the month - a different person's act: the FINAL runs superseded, the cutoff back one
     * period, {@code CLOSED -> OPEN} at {@code revision + 1}, the pin released, one transaction. The same approver's repeat converges.
     *
     * @param requested the reopen the approver read (`P14-TSK-024`'s fix round)
     * @param named the posted adjustments the person read
     * @throws AccountingPeriods.PeriodNotFound when no period has this id
     * @throws AccountingPeriods.PeriodStale when the period is not {@code CLOSED} with a reopen pending, it is not the latest closed, or the next is past {@code OPEN}
     * @throws AccountingPeriods.RequestStale when the reopen pending is not the one named
     * @throws AccountingPeriods.SelfApprovalRefused when the approver proposed it ({@code INV-AUD-04})
     * @throws PriorPeriodAdjustments.AdjustmentStale when what is named is not what is posted now - nothing changed; read them and decide again
     * @throws PostingCutoff.CutoffBusy when postings held the cutoff through the ledger's bounded attempts - nothing changed; retry
     */
    public Acted approveReopen(Connection unitOfWork, AccountingPeriodId id, PeriodReopenId requested, Actor actor, Set<UUID> named, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(named, "named");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        // A1, oldest first. The month is frozen from birth, so the unlocked read only names which row precedes it.
        YearMonth month = periods.find(unitOfWork, id).orElseThrow(AccountingPeriods.PeriodNotFound::new).month();
        Optional<AccountingPeriodStore.PeriodRow> previous = periods.lockShared(unitOfWork, month.minusMonths(1));
        AccountingPeriodStore.PeriodRow row = periods.lock(unitOfWork, id).orElseThrow(AccountingPeriods.PeriodNotFound::new);
        if (row.status() == PeriodStatus.OPEN && row.revision() > 1) {
            Optional<PeriodCloseStore.ReopenRow> last = closes.approvedReopenAt(unitOfWork, id, row.revision() - 1);
            if (last.filter(approved -> approved.id().equals(requested)).flatMap(PeriodCloseStore.ReopenRow::approvedBy).filter(actor.id()::equals).isPresent()) {
                return new Acted(id, row.month(), row.status(), row.revision(), Optional.empty(), Optional.empty(), true);
            }
        }
        if (row.status() != PeriodStatus.CLOSED) {
            throw new AccountingPeriods.PeriodStale("the period is " + row.status().name() + "; only a CLOSED period reopens");
        }
        requireLatestClosed(unitOfWork, row);
        PeriodCloseStore.ReopenRow standing = closes.lockPendingReopen(unitOfWork, id)
                .orElseThrow(() -> new AccountingPeriods.PeriodStale(noReopenPending(unitOfWork, row, requested)));
        requireAtTheRevision(standing, row);
        requireTheReopenRead(standing, row, requested, "period:reopen-approval");
        if (standing.proposedBy().equals(actor.id())) {
            throw new AccountingPeriods.SelfApprovalRefused(id, row.month(), "period:reopen");
        }
        int posted = requireNamesWhatIsPosted(unitOfWork, id, named);
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
        record(unitOfWork, actor, AccountingAuditAction.PERIOD_REOPENED, id, Optional.of(reasoned), "period=" + row.month() + ", revision=" + revision + ", supersededRuns=" + superseded + ", postedAdjustments=" + posted, correlation);
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

    // ------------------------------------------------------------------ a reopen's end

    /**
     * Rejects the pending reopen the person read, named by its id - a second person's decision against it (`P14-TSK-024`): the reopen {@code REJECTED}, the period still {@code CLOSED} at the same
     * revision, nothing superseded, the cutoff unmoved, the month free for a new proposal. The same rejecter's repeat converges.
     *
     * @param requested the reopen the person read
     * @throws AccountingPeriods.PeriodNotFound when no period has this id
     * @throws AccountingPeriods.PeriodStale when no reopen is pending - naming whether the one named was approved, rejected or withdrawn
     * @throws AccountingPeriods.RequestStale when the reopen pending is not the one named
     * @throws AccountingPeriods.SelfApprovalRefused when the rejecter proposed it ({@code INV-AUD-04}): its proposer withdraws it
     * @throws AccountingReasons.ReasonRequired for a missing, unbounded or instrument-bearing reason
     */
    public Acted rejectReopen(Connection unitOfWork, AccountingPeriodId id, PeriodReopenId requested, Actor actor, String reason, CorrelationId correlation) {
        return endReopen(unitOfWork, id, requested, PeriodRequestEnd.REJECTED, actor, reason, correlation);
    }

    /**
     * Withdraws the pending reopen the person read, named by its id - its proposer's own retraction (`P14-TSK-024`): as {@link #rejectReopen}, by the one person who may. The same proposer's repeat
     * converges.
     *
     * @param requested the reopen the person read
     * @throws AccountingPeriods.PeriodNotFound when no period has this id
     * @throws AccountingPeriods.PeriodStale when no reopen is pending - naming whether the one named was approved, rejected or withdrawn
     * @throws AccountingPeriods.RequestStale when the reopen pending is not the one named
     * @throws AccountingPeriods.NotTheProposer when someone else asks
     * @throws AccountingReasons.ReasonRequired for a missing, unbounded or instrument-bearing reason
     */
    public Acted withdrawReopen(Connection unitOfWork, AccountingPeriodId id, PeriodReopenId requested, Actor actor, String reason, CorrelationId correlation) {
        return endReopen(unitOfWork, id, requested, PeriodRequestEnd.WITHDRAWN, actor, reason, correlation);
    }

    /** (A1) the period {@code FOR UPDATE}, then (A4) the pending reopen {@code FOR UPDATE}. No neighbour, no cutoff, no report run: an end needs none of them, and is not judged by "the latest closed". */
    private Acted endReopen(Connection unitOfWork, AccountingPeriodId id, PeriodReopenId requested, PeriodRequestEnd end, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        AccountingPeriodStore.PeriodRow row = periods.lock(unitOfWork, id).orElseThrow(AccountingPeriods.PeriodNotFound::new);
        // The request named, looked up itself under the period's row (P14-DOC-001, P14-TSK-024's re-review (a)): ended by this person this way,
        // a repeat - under any key, whatever was proposed or ended since - converges and writes nothing. Until then this read the revision's
        // latest end, so a retry after another reopen ended in between was told "none is pending", and one while another was pending RequestStale.
        Optional<PeriodCloseStore.ReopenRow> named = closes.endedReopen(unitOfWork, id, requested);
        if (named.filter(ended -> ended.status() == end.reopenStatus() && ended.endedBy().filter(actor.id()::equals).isPresent()).isPresent()) {
            return new Acted(id, row.month(), row.status(), row.revision(), Optional.empty(), Optional.empty(), true);
        }
        Optional<PeriodCloseStore.ReopenRow> pending = closes.lockPendingReopen(unitOfWork, id);
        if (pending.isEmpty()) {
            if (row.status() == PeriodStatus.CLOSED) {
                throw new AccountingPeriods.PeriodStale(named.map(ended -> "the reopen named was " + ended.status().name() + "; none is pending, and nothing was changed")
                        .orElse("no reopen of the period is pending; nothing was changed"));
            }
            throw new AccountingPeriods.PeriodStale(row.status() == PeriodStatus.OPEN && row.revision() > 1
                    && closes.approvedReopenAt(unitOfWork, id, row.revision() - 1).filter(approved -> approved.id().equals(requested)).isPresent()
                    ? "the period is OPEN at revision " + row.revision() + ": the reopen named was approved; nothing was changed"
                    : "the period is " + row.status().name() + "; only a CLOSED period's pending reopen is ended");
        }
        PeriodCloseStore.ReopenRow standing = pending.get();
        requireAtTheRevision(standing, row);
        String act = end == PeriodRequestEnd.REJECTED ? "period:reopen-rejection" : "period:reopen-withdrawal";
        requireTheReopenRead(standing, row, requested, act);
        boolean own = standing.proposedBy().equals(actor.id());
        if (end == PeriodRequestEnd.REJECTED && own) {
            throw AccountingPeriods.SelfApprovalRefused.ofRejection(id, row.month(), act);
        }
        if (end == PeriodRequestEnd.WITHDRAWN && !own) {
            throw new AccountingPeriods.NotTheProposer(id, row.month(), act, "a reopen");
        }
        if (!closes.endReopen(unitOfWork, standing.id(), end, actor, reasoned)) {
            throw new IllegalStateException("the locked reopen was decided by another writer: the FOR UPDATE protocol was bypassed");
        }
        periods.appendEvent(unitOfWork, new AccountingPeriodStore.EventRow(ids.next(), id, end.reopenAct(), Optional.of(PeriodStatus.CLOSED), PeriodStatus.CLOSED, row.revision(), actor,
                Optional.of(standing.proposedBy()), Optional.empty(), Optional.of(reasoned), correlation));
        record(unitOfWork, actor, end == PeriodRequestEnd.REJECTED ? AccountingAuditAction.PERIOD_REOPEN_REJECTED : AccountingAuditAction.PERIOD_REOPEN_WITHDRAWN, id, Optional.of(reasoned),
                "period=" + row.month() + ", revision=" + row.revision() + ", reopen=" + standing.id().value(), correlation);
        return new Acted(id, row.month(), PeriodStatus.CLOSED, row.revision(), Optional.empty(), Optional.empty(), false);
    }

    /** A pending reopen stands only beside its {@code CLOSED} period at its revision: leaving {@code CLOSED} is its own approval, and ending it moves nothing. */
    private static void requireAtTheRevision(PeriodCloseStore.ReopenRow pending, AccountingPeriodStore.PeriodRow row) {
        if (row.status() != PeriodStatus.CLOSED || pending.revision() != row.revision()) {
            throw new IllegalStateException("a pending reopen stands only beside its CLOSED period at its revision");
        }
    }

    /** What a {@code CLOSED} period with no reopen pending says to an act on one: what became of the one named, when it ended (`P14-DOC-001`: looked up by its id); otherwise that none is pending. */
    private String noReopenPending(Connection unitOfWork, AccountingPeriodStore.PeriodRow row, PeriodReopenId requested) {
        return closes.endedReopen(unitOfWork, row.id(), requested).map(ended -> "the reopen named was " + ended.status().name() + "; none is pending, and nothing was changed")
                .orElse("no reopen of the period is pending; nothing was changed");
    }

    /** The reopen pending is the one the person read, named by its id; otherwise {@link AccountingPeriods.RequestStale}, judged holding the period's row and the reopen's. */
    private static void requireTheReopenRead(PeriodCloseStore.ReopenRow pending, AccountingPeriodStore.PeriodRow row, PeriodReopenId requested, String act) {
        if (!pending.id().equals(requested)) {
            throw new AccountingPeriods.RequestStale(row.id(), row.month(), act, "the reopen named, " + requested.value() + ", is not the one pending, " + pending.id().value()
                    + ": read it and decide again; nothing was changed");
        }
    }

    /** {@code INV-ACC-10}: the next period, when there is one, has not left {@code OPEN} - so periods closing in order, this is the latest closed. (A1) oldest first: the caller holds this row. */
    private void requireLatestClosed(Connection unitOfWork, AccountingPeriodStore.PeriodRow row) {
        Optional<AccountingPeriodStore.PeriodRow> next = periods.lockShared(unitOfWork, row.month().plusMonths(1));
        if (next.isPresent() && next.get().status() != PeriodStatus.OPEN) {
            throw new AccountingPeriods.PeriodStale("only the latest closed period reopens, the next not locked: " + next.get().month() + " is " + next.get().status().name());
        }
    }

    /** What the act names against what is posted now, on a plain read: the caller holds the period's row, and no writer reaches {@code POSTED} without that row {@code FOR SHARE}. The count is what is posted. */
    private int requireNamesWhatIsPosted(Connection unitOfWork, AccountingPeriodId id, Set<UUID> named) {
        Set<UUID> posted = adjustments.ofCorrected(unitOfWork, id).stream().filter(row -> row.status() == PriorPeriodAdjustmentStatus.POSTED).map(row -> row.id().value()).collect(Collectors.toSet());
        PriorPeriodAdjustments.requireNamed(posted, named);
        return posted.size();
    }

    // ------------------------------------------------------------------ reads, refusals' records

    /**
     * The period's latest proposal as an approver reads it: its nine frozen attempts with their evidence and when each concluded, whether it still stands, and the reopen pending, if one is. Unlocked:
     * {@link #approve} and {@link #approveReopen} judge again on the row.
     *
     * @throws AccountingPeriods.PeriodNotFound when no period has this id
     * @return empty when the period has had no proposal
     */
    public Optional<ProposalView> proposalOf(Connection unitOfWork, AccountingPeriodId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        AccountingPeriodStore.PeriodRow row = periods.find(unitOfWork, id).orElseThrow(AccountingPeriods.PeriodNotFound::new);
        return closes.latest(unitOfWork, id).map(proposal -> {
            Map<CloseCheckCode, PeriodCloseStore.FrozenAttempt> attempts = closes.frozenAttempts(unitOfWork, proposal.id());
            List<CheckView> checks = closes.checks(unitOfWork, proposal.id()).stream()
                    .map(check -> new CheckView(check, Optional.ofNullable(attempts.get(check.check()))
                            .orElseThrow(() -> new IllegalStateException("a frozen check's attempt row is never deleted: " + check.check().name()))))
                    .toList();
            boolean stands = row.status() == PeriodStatus.CLOSE_PROPOSED && proposal.status() == PeriodCloseStatus.PROPOSED
                    && moved(unitOfWork, row, proposal).isEmpty();
            // P14-DOC-001 (P14-TSK-026's (c)): the named set's reference gaps, read by its id - a set is immutable once decided, and the close names an approved one.
            int gaps = sets.find(unitOfWork, proposal.rateSet()).map(ValuationRateSetStore.SetRow::referenceGaps)
                    .orElseThrow(() -> new IllegalStateException("a close names a valuation rate set that exists (V008's foreign key)"));
            return new ProposalView(proposal, row.status(), checks, stands, pendingReopenOf(unitOfWork, row), gaps);
        });
    }

    /**
     * Records an approver's read of what they decide ({@code accounting.CloseProposalRead}) - before it is served.
     *
     * @param overtakenReturns on a pending reopen, how many returns the read listed it would overtake (`P14-TSK-024`); empty when no list was read
     */
    public void recordRead(Connection unitOfWork, ProposalView view, Optional<Long> overtakenReturns, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(overtakenReturns, "overtakenReturns");
        record(unitOfWork, actor, AccountingAuditAction.CLOSE_PROPOSAL_READ, view.proposal().period(), Optional.empty(), "period=" + view.proposal().month() + ", proposal="
                + view.proposal().id().value() + ", status=" + view.proposal().status().name() + ", stillStands=" + view.stillStands() + ", reopenPending=" + view.pendingReopen().isPresent()
                + overtakenReturns.map(total -> ", overtakenReturns=" + total).orElse(""), correlation);
    }

    /**
     * Records a refusal a door keeps {@code FAILED} under its own action ({@link AccountingPeriods.RecordedRefusal#action()}: a self-approval, another person's withdrawal, a request other than the
     * one pending) on {@code unitOfWork} - the caller's own transaction, after the act rolled back, so the attempt is kept.
     */
    public void recordRefusal(Connection unitOfWork, AccountingPeriods.RecordedRefusal refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), refused.action(), AccountingPeriods.TARGET_TYPE, refused.target(),
                Optional.empty(), AuditOutcome.FAILED, correlation, Optional.of(refused.summary())));
    }

    /**
     * A period's pending requests as a person reads them before deciding one (`P14-TSK-024`'s fix round): the period row - its pending unlock and when that was proposed - and the reopen pending
     * at its revision, if one is. Each act names what it decides from here: an unlock by when it was proposed, a reopen by its id. A plain read: the act judges it again holding the row.
     *
     * @throws AccountingPeriods.PeriodNotFound when no period has this id
     */
    public PendingRequests pendingOf(Connection unitOfWork, AccountingPeriodId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        AccountingPeriodStore.PeriodRow row = periods.find(unitOfWork, id).orElseThrow(AccountingPeriods.PeriodNotFound::new);
        return new PendingRequests(row, pendingReopenOf(unitOfWork, row));
    }

    /** The reopen pending on {@code row}: one stands only beside its {@code CLOSED} period at its revision. A plain read. */
    public Optional<PeriodCloseStore.ReopenRow> pendingReopenOf(Connection unitOfWork, AccountingPeriodStore.PeriodRow row) {
        Objects.requireNonNull(row, "row");
        return row.status() == PeriodStatus.CLOSED ? closes.pendingReopen(unitOfWork, row.id()).filter(pending -> pending.revision() == row.revision()) : Optional.empty();
    }

    /** A period's pending requests: its row, whose pending unlock is {@link AccountingPeriodStore.PeriodRow#unlockPending()}, and the reopen pending, if one is. */
    public record PendingRequests(AccountingPeriodStore.PeriodRow period, Optional<PeriodCloseStore.ReopenRow> reopen) {

        public PendingRequests {
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(reopen, "reopen");
        }

        /** When the pending unlock was proposed - its identity; empty when none is pending. */
        public Optional<Instant> unlockProposedAt() {
            return period.unlockPending() ? period.unlockProposedAt() : Optional.empty();
        }
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
