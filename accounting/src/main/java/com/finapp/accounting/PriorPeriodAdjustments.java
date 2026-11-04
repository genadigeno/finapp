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
import java.math.BigInteger;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Prior-period adjustments - accounting's one door into the journal (`P14-TSK-013`; ADR-0113 decision 4, ADR-0114 decision 5; {@code PHASE_14_PLAN.md} sections 8.1 T-ppa, 8.2, 8.3 row 12, 13.5; {@code INV-ACC-10},
 * {@code INV-REV-04}, {@code INV-AUD-04}, {@code INV-LED-01}). An error found in a {@code CLOSED} month is corrected by a four-eyes entry dated in a month still {@code OPEN}, naming the month and the revision of the figures
 * it corrects. Its two worst failures: a correction that changes what a closed month issued, and a correction that leaves the month it lands in unable to close.
 *
 * <p><strong>The machine</strong>: {@code PROPOSED -> POSTED | REJECTED}. Two rows, this one and the ledger's proposal ({@link PriorPeriodPostings}, origin {@code ACCOUNTING}), one transaction each time.
 *
 * <p><strong>Which month, and where it lands</strong> ({@link #propose}): the corrected month must be {@code CLOSED}, at the revision the person read; a {@code LOCKED} month's way back is the unlock. The date is
 * <strong>today, chosen here and stored</strong>, the day {@link AccountingPeriods#openNext} would say; nothing in a request can name it. It lands in <em>the month holding that date</em>, which must be {@code OPEN}, never
 * {@link AccountingPeriods#firstOpen}: the earliest open month may be a reopened one, being re-closed, and dating a correction into it is the re-dating {@code INV-ACC-10} forbids. A month with no row yet is refused: a correction never
 * births a period, and the schedule will. The ledger's two dates are this one date, so nothing about the entry points into the closed month.
 *
 * <p><strong>What the approver reads is what posts</strong> ({@link #approve}): the body names the date, the revision and the month's posted corrections it read; any of them differing is {@link AdjustmentStale}. The
 * date and lines are frozen; the inputs that can move are read again under the locks: the corrected month's status and revision, the target's status and revision, the month's posted corrections, the mapping in force, the closed
 * purposes (code; a release moves them).
 *
 * <p><strong>Correcting twice</strong>: every receipt and read lists the month's corrections; the approval names the posted ones. Two approvals of one month take the same rows in id order, so the second finds the first {@code POSTED}
 * and, unless it named it, is refused. A month reopened and corrected directly leaves its {@code revision} moved, and a correction naming the old one is refused; one naming the new one reads figures that already hold the direct
 * correction. A reopen's own two acts name the posted corrections too (`P14-TSK-022`, {@link PeriodCloses}), and differing is this same refusal; a generic {@code MANUAL} adjustment dated in the reopened month still knows nothing of them, which the runbook says.
 *
 * <p><strong>A refused approval rolls back and stands {@code PROPOSED}.</strong> The way on is {@link #reject}, which asks nothing of either month, and then a new proposal, dated in a month then open, naming the revision then standing.
 * {@link #view} says whether it still stands and what blocks it; {@link #pending} finds it.
 *
 * <p><strong>The locks</strong> (A1 then A4, the plan's 8.2): the corrected period {@code FOR SHARE}, the target {@code FOR SHARE}, oldest first; the month's adjustments in id order. Then, inside the port, the ledger's proposal and namespace
 * {@code 14} shared. Every row here is locked before that key, so no transaction takes (A2) and then (A4). A lock of the target and a reopen of the corrected month each serialise with this on the period row.
 *
 * <p>A reason is never logged, never in a summary and never in an exception. The ledger's own reason is identifiers only; the person's stays on this row.
 */
@RequiredArgsConstructor
public final class PriorPeriodAdjustments {

    /** Published in the entry's transaction - ids and codes only. */
    public static final String POSTED_EVENT = "accounting.PriorPeriodAdjustmentPosted";

    /** The audit records' and the event's target type. */
    public static final String TARGET_TYPE = "prior_period_adjustment";

    /** The most lines one adjustment may carry - a bound on the proposal, not a business limit. */
    public static final int MAX_LINES = 100;

    /** The most standing proposals one read answers. */
    public static final int PENDING_LIMIT = 100;

    /**
     * The entry's {@code reference}, one constant, naming no adjustment, month or proposal. A line on a customer's wallet or a merchant's payable no longer serves it (`P14-TSK-022`: one label for every adjustment, at each rendering); it is the entry's column, read by an operator. The
     * trace is the row's {@code journal_entry_id} and {@code adjustment_proposal_id}, and the entry's scope {@code ledger.adjust.approve:<proposal>}.
     */
    public static final String ENTRY_REFERENCE = "accounting-correction";

    /** A period is born at revision 1 ({@code V003}) and only a reopen moves it, so any other is a month that was closed and reopened. */
    private static final int NEVER_REOPENED = 1;

    @NonNull private final AccountingPeriodStore periods;
    @NonNull private final PriorPeriodAdjustmentStore store;
    @NonNull private final PriorPeriodPostings postings;
    @NonNull private final GlMappingStore mappings;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    // ------------------------------------------------------------------ inputs and outcomes

    /** What an approver read and now names: the date it posts on, the revision it corrects, the month's corrections already posted. */
    public record Approval(LocalDate postingDate, int correctedRevision, Set<UUID> postedCorrections) {

        public Approval {
            Objects.requireNonNull(postingDate, "postingDate");
            postedCorrections = Set.copyOf(postedCorrections);
        }
    }

    /** One correction of a month, as a person reads it - no person, no reason, no amount. */
    public record Correction(PriorPeriodAdjustmentId id, int correctedRevision, PriorPeriodAdjustmentStatus status, LocalDate postingDate, Optional<UUID> entry) {

        static Correction of(PriorPeriodAdjustmentStore.Row row) {
            return new Correction(row.id(), row.correctedRevision(), row.status(), row.postingDate(), row.entry());
        }
    }

    /**
     * An act's outcome.
     *
     * @param earlierCorrections the month's other corrections, whatever their status and revision, as this act found them
     * @param replayed this person's act was already done and nothing was written
     */
    public record Acted(PriorPeriodAdjustmentId id, YearMonth correctedMonth, int correctedRevision, YearMonth targetMonth, LocalDate postingDate, PriorPeriodAdjustmentStatus status,
            UUID proposal, Optional<UUID> entry, List<Correction> earlierCorrections, boolean replayed) {

        public Acted {
            earlierCorrections = List.copyOf(earlierCorrections);
        }

        static Acted of(PriorPeriodAdjustmentStore.Row row, PriorPeriodAdjustmentStatus status, Optional<UUID> entry, List<Correction> earlier, boolean replayed) {
            return new Acted(row.id(), row.correctedMonth(), row.correctedRevision(), row.targetMonth(), row.postingDate(), status, row.proposal(), entry, earlier, replayed);
        }
    }

    /**
     * What an approver reads.
     *
     * @param lines the ledger's frozen lines, each with what it touches
     * @param blockedBy which month would refuse an approval now; empty when neither would - the way on otherwise is to reject and propose again. Months only: an approval that finds this empty can still be refused for a
     *     purpose a release has closed, a purpose unmapped in the version now in force, or by the ledger, and it stands {@code PROPOSED} each time
     * @param earlierCorrections the month's other corrections; the posted ones are what the approval names
     */
    public record View(PriorPeriodAdjustmentStore.Row row, List<ReadLine> lines, Optional<Blocked> blockedBy, List<Correction> earlierCorrections) {

        public View {
            lines = List.copyOf(lines);
            earlierCorrections = List.copyOf(earlierCorrections);
        }

        /** Neither month would refuse it now - no more than that: see {@code blockedBy}. */
        public boolean stillStands() {
            return blockedBy.isEmpty();
        }
    }

    /**
     * One frozen line as its approver reads it: the approver holds no ledger read, so an account's id alone would tell them nothing of what they attest. Codes only, never whose.
     *
     * @param line the ledger's frozen line
     * @param purpose the account's purpose
     * @param ownerKind the kind of party holding it - never the party
     */
    public record ReadLine(PriorPeriodPostings.Line line, String purpose, String ownerKind) {

        public ReadLine {
            Objects.requireNonNull(line, "line");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(ownerKind, "ownerKind");
        }

        /** An amount stays out of a log line. */
        @Override
        public String toString() {
            return "ReadLine[" + purpose + "/" + ownerKind + ", " + line.side() + "]";
        }
    }

    /** Why a standing proposal can no longer post - a closed vocabulary, served to the approver. */
    public enum Blocked {
        /** No longer {@code PROPOSED}. */
        DECIDED,
        /** The corrected month is no longer {@code CLOSED}: reopened, nothing it read stands. */
        CORRECTED_NOT_CLOSED,
        /** Reopened and closed again: the figures it corrects were superseded. */
        CORRECTED_REVISION_MOVED,
        /** The month it lands in is locked, closing or closed: a new proposal lands in the month then open. An unlock lifts this, since that month issued nothing and its revision has not moved. */
        TARGET_NOT_OPEN,
        /** The month it lands in was closed and reopened while it stood: {@code OPEN} again, at a revision moved. A correction never lands in a month being re-closed. */
        TARGET_REVISION_MOVED
    }

    // ------------------------------------------------------------------ refusals

    /** No prior-period adjustment has this id. */
    public static final class AdjustmentNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AdjustmentNotFound() {
            super("no prior-period adjustment answers this request");
        }
    }

    /** The proposal itself is not coherent: too few lines, a line not positive, unbalanced in a currency, an account unknown, a purpose unmapped. Nothing was written. Names codes, never a value. */
    public static final class AdjustmentInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AdjustmentInvalid(String detail) {
            super(detail);
        }
    }

    /** A line names a purpose closed to free adjustments; the detail names the owning domain's door. Nothing was written. */
    public static final class PurposeClosed extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PurposeClosed(String detail) {
            super(detail);
        }
    }

    /** What the act names is no longer what stands: another revision, another date, other corrections, a month closed and reopened under it, or it was decided. Nothing was changed. */
    public static final class AdjustmentStale extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AdjustmentStale(String detail) {
            super(detail);
        }
    }

    /** The proposer tried to approve their own ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(PriorPeriodAdjustmentStore.Row row) {
            super("a prior-period adjustment is approved by someone other than its proposer");
            this.target = row.id().value().toString();
            this.summary = "priorPeriodAdjustment=" + row.id().value() + ", corrects=" + row.correctedMonth() + ", act=prior-period-adjustment:approve";
        }

        /** The adjustment - the refusal record's target. */
        public String target() {
            return target;
        }

        /** What the refusal record names. */
        public String summary() {
            return summary;
        }
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes correcting a {@code CLOSED} month at the revision the person read, dated today in the {@code OPEN} month holding today. Nothing posts.
     *
     * @throws AccountingPeriods.PeriodNotFound when the corrected period has no row
     * @throws AccountingPeriods.PeriodStale when the corrected month is not {@code CLOSED}, or the month holding today has no period yet, is not {@code OPEN}, was reopened, or is not after the corrected one
     * @throws AdjustmentStale when the revision named is not the corrected month's
     * @throws AdjustmentInvalid when the lines are not coherent, an account is unknown, or a purpose is unmapped in the version in force for the target
     * @throws PurposeClosed when a line names a purpose closed to free adjustments
     * @throws AccountingReasons.ReasonRequired for a missing, unbounded or instrument-bearing reason
     */
    public Acted propose(Connection unitOfWork, AccountingPeriodId corrected, int correctedRevision, List<PriorPeriodPostings.Line> lines, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(corrected, "corrected");
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        requireCoherent(lines);
        // A1, oldest first. The month is frozen from birth, so the unlocked read only names which rows.
        YearMonth correctedMonth = periods.find(unitOfWork, corrected).orElseThrow(AccountingPeriods.PeriodNotFound::new).month();
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        YearMonth targetMonth = YearMonth.from(today);
        if (!targetMonth.isAfter(correctedMonth)) {
            throw new AccountingPeriods.PeriodStale("a correction lands in a month after the one it corrects; today is in " + targetMonth);
        }
        AccountingPeriodStore.PeriodRow correctedRow = periods.lockShared(unitOfWork, correctedMonth).orElseThrow(AccountingPeriods.PeriodNotFound::new);
        Optional<AccountingPeriodStore.PeriodRow> target = periods.lockShared(unitOfWork, targetMonth);
        if (correctedRow.status() != PeriodStatus.CLOSED) {
            throw new AccountingPeriods.PeriodStale("the period is " + correctedRow.status().name() + "; a prior-period adjustment corrects a CLOSED period - a LOCKED one is unlocked");
        }
        if (correctedRow.revision() != correctedRevision) {
            throw new AdjustmentStale("the corrected period stands at revision " + correctedRow.revision() + ", not the one named");
        }
        if (target.isEmpty()) {
            throw new AccountingPeriods.PeriodStale("the month holding today, " + targetMonth + ", has no period yet; the schedule opens it, nothing was written");
        }
        if (target.get().status() != PeriodStatus.OPEN) {
            throw new AccountingPeriods.PeriodStale("the month holding today is " + target.get().status().name() + "; a prior-period adjustment lands in an OPEN period");
        }
        // A month locks only past its end and its grace, so the month holding today being reopened already is a clock far out, not a race. It is refused all the same: a correction never lands in a month being re-closed.
        if (target.get().revision() != NEVER_REOPENED) {
            throw new AccountingPeriods.PeriodStale("the month holding today was closed and reopened; a prior-period adjustment never lands in a reopened month");
        }
        requireOpenAndMapped(unitOfWork, lines, targetMonth);
        List<Correction> earlier = store.ofCorrected(unitOfWork, corrected).stream().map(Correction::of).toList();
        PriorPeriodAdjustmentId id = PriorPeriodAdjustmentId.next(ids);
        UUID proposal = postings.propose(unitOfWork, today, ENTRY_REFERENCE,
                "prior-period adjustment " + id.value() + " corrects " + correctedMonth + " at revision " + correctedRevision, lines);
        store.insert(unitOfWork, new PriorPeriodAdjustmentStore.NewAdjustment(id, corrected, correctedRevision, target.get().id(), target.get().revision(), today, proposal, actor, reasoned, correlation));
        record(unitOfWork, actor, AccountingAuditAction.PRIOR_PERIOD_ADJUSTMENT_PROPOSED, id, Optional.of(reasoned), "corrects=" + correctedMonth + ", revision=" + correctedRevision + ", target=" + targetMonth
                + ", postingDate=" + today + ", proposal=" + proposal + ", lines=" + lines.size() + ", earlierCorrections=" + earlier.size(), correlation);
        return new Acted(id, correctedMonth, correctedRevision, targetMonth, today, PriorPeriodAdjustmentStatus.PROPOSED, proposal, Optional.empty(), earlier, false);
    }

    // ------------------------------------------------------------------ approve

    /**
     * Approves a standing proposal - a different person's act, on what they read: the ledger posts the entry, this row moves with it, one transaction. The same approver's repeat converges.
     *
     * @throws AdjustmentNotFound when nothing has this id
     * @throws AdjustmentStale when it was decided otherwise, what the body names is not what stands, or either month was closed and reopened while it stood
     * @throws SelfApprovalRefused when the approver proposed it ({@code INV-AUD-04})
     * @throws AccountingPeriods.PeriodStale when the corrected month is no longer {@code CLOSED}, or the target no longer {@code OPEN} - it stands; the way on is to reject it and propose again
     * @throws AdjustmentInvalid when a purpose is no longer mapped in the version in force for the target
     * @throws PurposeClosed when a release has closed a purpose its lines name
     * @throws AccountingReasons.ReasonRequired for a missing, unbounded or instrument-bearing reason
     */
    public Acted approve(Connection unitOfWork, PriorPeriodAdjustmentId id, Approval approval, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(approval, "approval");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        PriorPeriodAdjustmentStore.Row read = store.find(unitOfWork, id).orElseThrow(AdjustmentNotFound::new);
        // A1, oldest first; the months are frozen from birth, so the unlocked read only names which rows.
        Optional<AccountingPeriodStore.PeriodRow> corrected = periods.lockShared(unitOfWork, read.correctedMonth());
        Optional<AccountingPeriodStore.PeriodRow> target = periods.lockShared(unitOfWork, read.targetMonth());
        // A4: the month's live adjustments, one statement, id order. Rejected while this waited, it drops out; its own row is read again then.
        List<PriorPeriodAdjustmentStore.Row> month = store.lockCorrectionsOf(unitOfWork, read.correctedPeriod());
        PriorPeriodAdjustmentStore.Row row = month.stream().filter(candidate -> candidate.id().equals(id)).findFirst()
                .orElseGet(() -> store.find(unitOfWork, id).orElseThrow(AdjustmentNotFound::new));
        if (row.status() == PriorPeriodAdjustmentStatus.POSTED && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return Acted.of(row, row.status(), row.entry(), earlierThan(month, id), true);
        }
        if (row.status() != PriorPeriodAdjustmentStatus.PROPOSED) {
            throw new AdjustmentStale("the prior-period adjustment was " + row.status().name());
        }
        if (row.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused(row);
        }
        if (!approval.postingDate().equals(row.postingDate())) {
            throw new AdjustmentStale("it posts on " + row.postingDate() + ", not the date named; an approver posts what they read");
        }
        if (approval.correctedRevision() != row.correctedRevision()) {
            throw new AdjustmentStale("it corrects revision " + row.correctedRevision() + ", not the one named; an approver posts what they read");
        }
        Set<UUID> posted = month.stream().filter(candidate -> candidate.status() == PriorPeriodAdjustmentStatus.POSTED).map(candidate -> candidate.id().value()).collect(Collectors.toSet());
        requireNamed(posted, approval.postedCorrections());
        Optional<Blocked> blocked = blockedBy(row, corrected, target);
        if (blocked.isPresent()) {
            throw switch (blocked.get()) {
                case CORRECTED_REVISION_MOVED -> new AdjustmentStale("the corrected period was reopened and closed again: the figures this corrects were superseded; reject it and propose again");
                case TARGET_REVISION_MOVED -> new AdjustmentStale("the month it lands in was closed and reopened while it stood; a correction never lands in a reopened month: reject it and propose again");
                default -> new AccountingPeriods.PeriodStale(blockedDetail(blocked.get()));
            };
        }
        requireOpenAndMapped(unitOfWork, postings.linesOf(unitOfWork, row.proposal()), row.targetMonth());
        PriorPeriodPostings.Posted entry = postings.approve(unitOfWork, row.proposal());
        if (entry.replayed()) {
            throw new IllegalStateException("the ledger's proposal was already approved while this row stood PROPOSED: the two rows move together, or a writer bypassed this door");
        }
        if (!store.markPosted(unitOfWork, id, entry.entry(), actor, reasoned)) {
            throw new IllegalStateException("the locked adjustment moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        record(unitOfWork, actor, AccountingAuditAction.PRIOR_PERIOD_ADJUSTMENT_POSTED, id, Optional.of(reasoned), "corrects=" + row.correctedMonth() + ", revision=" + row.correctedRevision() + ", target=" + row.targetMonth()
                + ", postingDate=" + row.postingDate() + ", proposal=" + row.proposal() + ", entry=" + entry.entry() + ", earlierPosted=" + posted.size(), correlation);
        publish(unitOfWork, id, EventPayload.of()
                .with("correctedPeriod", row.correctedMonth().toString())
                .with("correctedRevision", Integer.toString(row.correctedRevision()))
                .with("targetPeriod", row.targetMonth().toString())
                .with("postingDate", row.postingDate().toString())
                .with("adjustmentProposal", row.proposal().toString())
                .with("journalEntry", entry.entry().toString()), correlation);
        return Acted.of(row, PriorPeriodAdjustmentStatus.POSTED, Optional.of(entry.entry()), earlierThan(month, id), false);
    }

    /** What an act names against what has posted against the month: one refusal, in one set of words, for an adjustment's approval and a reopen's two acts. A count, never an id or a reason. */
    static void requireNamed(Set<UUID> posted, Set<UUID> named) {
        if (!posted.equals(named)) {
            throw new AdjustmentStale("the month's posted corrections are not those named: " + posted.size() + " stand; read them and decide again");
        }
    }

    // ------------------------------------------------------------------ reject

    /**
     * Rejects a standing proposal, the ledger's with it - the way on for one that can no longer post, so it asks nothing of either month. May be the proposer's. The same rejecter's repeat converges.
     *
     * @throws AdjustmentNotFound when nothing has this id
     * @throws AdjustmentStale when it was posted, or rejected by someone else
     * @throws AccountingReasons.ReasonRequired for a missing, unbounded or instrument-bearing reason
     */
    public Acted reject(Connection unitOfWork, PriorPeriodAdjustmentId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        PriorPeriodAdjustmentStore.Row row = store.lock(unitOfWork, id).orElseThrow(AdjustmentNotFound::new);
        if (row.status() == PriorPeriodAdjustmentStatus.REJECTED && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return Acted.of(row, row.status(), Optional.empty(), List.of(), true);
        }
        if (row.status() != PriorPeriodAdjustmentStatus.PROPOSED) {
            throw new AdjustmentStale("the prior-period adjustment was " + row.status().name());
        }
        postings.reject(unitOfWork, row.proposal());
        if (!store.markRejected(unitOfWork, id, actor, reasoned)) {
            throw new IllegalStateException("the locked adjustment moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        record(unitOfWork, actor, AccountingAuditAction.PRIOR_PERIOD_ADJUSTMENT_REJECTED, id, Optional.of(reasoned), "corrects=" + row.correctedMonth() + ", revision=" + row.correctedRevision()
                + ", proposal=" + row.proposal() + ", byProposer=" + row.proposedBy().equals(actor.id()), correlation);
        return Acted.of(row, PriorPeriodAdjustmentStatus.REJECTED, Optional.empty(), List.of(), false);
    }

    // ------------------------------------------------------------------ reads, refusals' records

    /**
     * One adjustment as its approver reads it, and whether either month would refuse it now. Unlocked: {@link #approve} judges again on the rows, and judges more than the months: see {@link View#blockedBy}.
     *
     * @throws AdjustmentNotFound when nothing has this id
     */
    public View view(Connection unitOfWork, PriorPeriodAdjustmentId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        PriorPeriodAdjustmentStore.Row row = store.find(unitOfWork, id).orElseThrow(AdjustmentNotFound::new);
        Optional<Blocked> blocked = row.status() != PriorPeriodAdjustmentStatus.PROPOSED
                ? Optional.of(Blocked.DECIDED)
                : blockedBy(row, periods.find(unitOfWork, row.correctedPeriod()), periods.find(unitOfWork, row.targetPeriod()));
        return new View(row, readLines(unitOfWork, row), blocked,
                store.ofCorrected(unitOfWork, row.correctedPeriod()).stream().filter(other -> !other.id().equals(id)).map(Correction::of).toList());
    }

    /**
     * Every correction of a month, whatever its status and revision - every prior-period adjustment traced to its period.
     *
     * @throws AccountingPeriods.PeriodNotFound when no period has this id
     */
    public List<Correction> correctionsOf(Connection unitOfWork, AccountingPeriodId period) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(period, "period");
        periods.find(unitOfWork, period).orElseThrow(AccountingPeriods.PeriodNotFound::new);
        return store.ofCorrected(unitOfWork, period).stream().map(Correction::of).toList();
    }

    /** What has posted against a month: what a reopen's two people are shown, on the act's own transaction holding the period's row, and a re-close's approver at its read, unlocked. */
    public List<UUID> postedAgainst(Connection unitOfWork, AccountingPeriodId period) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(period, "period");
        return store.ofCorrected(unitOfWork, period).stream().filter(row -> row.status() == PriorPeriodAdjustmentStatus.POSTED).map(row -> row.id().value()).toList();
    }

    /** The oldest standing proposals, so one that can no longer post can be found. */
    public List<PriorPeriodAdjustmentStore.Row> pending(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return store.pending(unitOfWork, PENDING_LIMIT);
    }

    /** Records an operator's read ({@code accounting.PriorPeriodAdjustmentRead}) - before it is served. */
    public void recordRead(Connection unitOfWork, String targetType, String target, String summary, Actor actor, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), AccountingAuditAction.PRIOR_PERIOD_ADJUSTMENT_READ, targetType, target, Optional.empty(), AuditOutcome.SUCCEEDED,
                correlation, Optional.of(summary)));
    }

    /** Records a refused self-approval {@code FAILED} on {@code unitOfWork} - the caller's own transaction, after the act rolled back, so the attempt is kept. */
    public void recordRefusal(Connection unitOfWork, SelfApprovalRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), AccountingAuditAction.SELF_APPROVAL_REFUSED, TARGET_TYPE, refused.target(), Optional.empty(), AuditOutcome.FAILED,
                correlation, Optional.of(refused.summary())));
    }

    // ------------------------------------------------------------------ the rules

    /** What either month would refuse an approval for now, from the rows named; the caller reads them under its locks, or unlocked for a person to read. */
    private static Optional<Blocked> blockedBy(PriorPeriodAdjustmentStore.Row row, Optional<AccountingPeriodStore.PeriodRow> corrected, Optional<AccountingPeriodStore.PeriodRow> target) {
        if (corrected.isEmpty() || corrected.get().status() != PeriodStatus.CLOSED) {
            return Optional.of(Blocked.CORRECTED_NOT_CLOSED);
        }
        if (corrected.get().revision() != row.correctedRevision()) {
            return Optional.of(Blocked.CORRECTED_REVISION_MOVED);
        }
        if (target.isEmpty() || target.get().status() != PeriodStatus.OPEN) {
            return Optional.of(Blocked.TARGET_NOT_OPEN);
        }
        if (target.get().revision() != row.targetRevision()) {
            return Optional.of(Blocked.TARGET_REVISION_MOVED);
        }
        return Optional.empty();
    }

    private static String blockedDetail(Blocked blocked) {
        return switch (blocked) {
            case CORRECTED_NOT_CLOSED -> "the corrected period is no longer CLOSED; reject it and propose again against what stands";
            case TARGET_NOT_OPEN -> "the month it lands in is no longer OPEN; reject it and propose again in the month now open";
            default -> throw new IllegalArgumentException(blocked.name());
        };
    }

    /** Each frozen line with what it touches - the port's own classification, codes only. The ledger's foreign key holds every account a line names. */
    private List<ReadLine> readLines(Connection unitOfWork, PriorPeriodAdjustmentStore.Row row) {
        List<PriorPeriodPostings.Line> lines = postings.linesOf(unitOfWork, row.proposal());
        Map<UUID, PriorPeriodPostings.Classified> chart = postings.classify(unitOfWork, lines.stream().map(PriorPeriodPostings.Line::ledgerAccount).collect(Collectors.toSet()));
        return lines.stream().map(line -> {
            PriorPeriodPostings.Classified account = Objects.requireNonNull(chart.get(line.ledgerAccount()), "a frozen line names an account the ledger does not hold");
            return new ReadLine(line, account.purpose(), account.ownerKind());
        }).toList();
    }

    private static List<Correction> earlierThan(List<PriorPeriodAdjustmentStore.Row> month, PriorPeriodAdjustmentId id) {
        return month.stream().filter(row -> !row.id().equals(id)).map(Correction::of).toList();
    }

    /**
     * {@code INV-LED-01}, its shape: two lines at least, each positive, each currency at its scale balanced, each side's sum one the ledger can hold. Before any lock. {@code JournalEntry.balanced} folds each side through
     * {@code Money.plus}, a {@code long} of minor units, at the ledger's proposal and again at its approval; past it, that is a {@code MonetaryOverflowException} and a 500 with nothing written.
     */
    private static void requireCoherent(List<PriorPeriodPostings.Line> lines) {
        if (lines.size() < 2 || lines.size() > MAX_LINES) {
            throw new AdjustmentInvalid("a prior-period adjustment carries 2 to " + MAX_LINES + " lines");
        }
        Map<String, BigInteger> debits = new TreeMap<>();
        Map<String, BigInteger> credits = new TreeMap<>();
        for (PriorPeriodPostings.Line line : lines) {
            Objects.requireNonNull(line, "line");
            if (!line.amount().isPositive()) {
                throw new AdjustmentInvalid("a line's amount is positive");
            }
            (line.side() == Side.DEBIT ? debits : credits).merge(line.amount().currency().code() + "@" + line.amount().scale(), BigInteger.valueOf(line.amount().minorUnits()), BigInteger::add);
        }
        Set<String> beyond = new TreeSet<>();
        Set<String> present = new TreeSet<>(debits.keySet());
        present.addAll(credits.keySet());
        List.of(debits, credits).forEach(side -> side.forEach((currency, sum) -> {
            if (sum.bitLength() > Long.SIZE - 1) {
                beyond.add(currency);
            }
        }));
        if (!beyond.isEmpty()) {
            throw new AdjustmentInvalid("a side's sum is more than the ledger holds in " + beyond);
        }
        List<String> unbalanced = present.stream().filter(currency -> !debits.getOrDefault(currency, BigInteger.ZERO).equals(credits.getOrDefault(currency, BigInteger.ZERO))).toList();
        if (!unbalanced.isEmpty()) {
            throw new AdjustmentInvalid("debits and credits differ in " + unbalanced);
        }
    }

    /**
     * Each line on an account the ledger holds, on a purpose open to free adjustment, mapped in the version in force for the target - so the month it lands in can still close: a line unmapped there is {@code GL_EQUALS_LEDGER} failing at that
     * month's close. A version activated later is total over the chart, so the pin at its lock cannot lose what this read found.
     */
    private void requireOpenAndMapped(Connection unitOfWork, List<PriorPeriodPostings.Line> lines, YearMonth target) {
        Set<UUID> accounts = lines.stream().map(PriorPeriodPostings.Line::ledgerAccount).collect(Collectors.toSet());
        Map<UUID, PriorPeriodPostings.Classified> classified = postings.classify(unitOfWork, accounts);
        if (!classified.keySet().containsAll(accounts)) {
            throw new AdjustmentInvalid("a line names an account the ledger does not hold");
        }
        for (PriorPeriodPostings.Classified account : classified.values()) {
            if (!PostingRuleCatalogue.OPEN_TO_FREE_ADJUSTMENT.contains(account.purpose())) {
                throw new PurposeClosed(account.purpose() + " is closed to free adjustments; its correction is " + owningDoor(account.purpose()));
            }
        }
        GlMappingStore.VersionRow version = mappings.inForceFor(unitOfWork, target)
                .orElseThrow(() -> new AdjustmentInvalid("no GL mapping version is in force for " + target + "; the month it lands in could not close"));
        Set<String> mapped = mappings.content(unitOfWork, version.id()).rulesByKey().keySet();
        for (PriorPeriodPostings.Classified account : classified.values()) {
            if (!mapped.contains(account.key())) {
                throw new AdjustmentInvalid(account.key() + " is not mapped in GL mapping version " + version.version() + "; the month it lands in could not close");
            }
        }
    }

    /** The door a closed purpose's correction goes through ({@code INV-ACC-10}: the owning domain's own correction, a reversal of an entry dated in a closed period). */
    static String owningDoor(String purpose) {
        if (purpose.startsWith("LOAN_")) {
            return "lending's own: a waiver, a reversal or an amendment under its four eyes";
        }
        if (purpose.startsWith("BNPL_")) {
            return "bnpl's own: a fee waived or a repayment reversed under its four eyes";
        }
        if (Set.of(PostingRuleCatalogue.FX_POSITION, PostingRuleCatalogue.FX_SPREAD_REVENUE, PostingRuleCatalogue.ROUNDING_RESIDUAL, PostingRuleCatalogue.FX_REALISED_GAINS, PostingRuleCatalogue.FX_REALISED_LOSSES).contains(purpose)) {
            return "fx's own: the trade's or the cover's reversal";
        }
        return "reconciliation's own: a break's resolution under its four eyes";
    }

    // ------------------------------------------------------------------ plumbing

    /** An authenticated human - {@code CUSTOMER}-typed (every operator session) or {@code EMPLOYEE}; fail closed. */
    private static void person(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        if (actor.type() != ActorType.CUSTOMER && actor.type() != ActorType.EMPLOYEE) {
            throw new IllegalArgumentException("a prior-period adjustment is a person's - never the platform's, a service's or a merchant's");
        }
    }

    private void publish(Connection unitOfWork, PriorPeriodAdjustmentId id, EventPayload payload, CorrelationId correlation) {
        outbox.write(unitOfWork, new EventEnvelope(EventId.next(ids), POSTED_EVENT, AccountingPeriods.EVENT_VERSION, EventEnvelope.CURRENT_SCHEMA_VERSION, id, TARGET_TYPE, clock.instant(),
                AccountingPeriods.PRODUCER, correlation, CausationId.of(correlation.value())), payload.toBytes(), EventPayload.MEDIA_TYPE);
    }

    private void record(Connection unitOfWork, Actor actor, AccountingAuditAction action, PriorPeriodAdjustmentId id, Optional<String> reason, String summary, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, TARGET_TYPE, id.value().toString(), reason, AuditOutcome.SUCCEEDED, correlation, Optional.of(summary)));
    }
}
