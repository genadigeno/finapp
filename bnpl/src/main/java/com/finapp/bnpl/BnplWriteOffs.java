package com.finapp.bnpl;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PurposeBalances;
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
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A defaulted BNPL obligation written off under four eyes (`P14-TSK-021`; owner decision C6 (A); ADR-0103 F11, ADR-0104's Follow-up;
 * {@code INV-BNPL-01}, {@code INV-REV-04}, {@code INV-AUD-04}, {@code INV-BAL-03}, {@code INV-LIFE-04}). <strong>A write-off is not
 * forgiveness.</strong> It moves an asset to expense on the platform's books and says nothing about what the customer owes: the
 * obligation goes {@code WRITTEN_OFF}, never {@code CLOSED}, the customer's read keeps naming what was owed, and credit's exposure
 * keeps counting it. The merchant is not touched: its payable has no line here.
 *
 * <p><strong>The machine and every judgement are {@code LoanWriteOffs}'s</strong>, lending's shape: {@code PROPOSED -> APPROVED |
 * REJECTED}, decided once; a holder of {@code BNPL_WRITE_OFF_PROPOSE} proposes, a DIFFERENT person holding
 * {@code BNPL_WRITE_OFF_APPROVE} on a {@code MULTI_FACTOR} session names what they read and approves, a holder of
 * {@code BNPL_WRITE_OFF_APPROVE} rejects on any status. An {@code ACTIVE}, defaulted obligation - the latest {@code DEFAULT} change
 * set and the condition derived now still holding it - with no credit balance, every elapsed date accrued, its rows explaining its
 * accounts. And one judgement of its own: <strong>no dispute open</strong>, because a dispute's outcome may be a refund (F9), and a
 * refund cannot post on a written-off obligation - so the customer's dispute comes first. Anything moved since the proposal is
 * {@link Stale}, compared by the figures, the revision and the {@code DEFAULT} change's id.
 *
 * <p><strong>The approval, in the lock order</strong>: the party judged unlocked; <strong>(B4)</strong> the obligation
 * {@code FOR UPDATE}; the proposal read under it; the judgements, the database clock after the lock; F11 under
 * {@code bnpl.write-off:<obligation>} ({@link PostingCommand.Dating#AS_STATED}: today, value date included, or refused by the cutoff,
 * never moved); then the decision, the edge, its history row, {@code bnpl.BnplWrittenOff} and the audit record. No L0: the exposure
 * does not rise. No L6: principal only falls, and the headroom's written-off term rises with it. No (P): no wallet, no payable.
 *
 * <p><strong>No way back here</strong>: an owner item ({@code BNPL_LIFECYCLES.md}).
 */
@RequiredArgsConstructor
public final class BnplWriteOffs {

    /** F11's ledger key prefix: {@code bnpl.write-off:<obligation>} - an obligation is written off once, ever. */
    public static final String WRITE_OFF_KEY_PREFIX = "bnpl.write-off:";

    /** The event an approved write-off publishes. */
    public static final String WRITTEN_OFF_EVENT = "bnpl.BnplWrittenOff";

    /** The history row's reason code on the obligation's edge. */
    static final String WRITTEN_OFF_REASON = "WRITTEN_OFF";

    /** The audit records' target type. */
    static final String TARGET_TYPE = "bnpl_write_off";

    /** The oldest pending write-offs one listing answers. */
    public static final int PENDING_LIMIT = 100;

    /** Every line of an account, whatever its date. */
    private static final LocalDate EVERY_VALUE_DATE = LocalDate.of(9999, 12, 31);

    @NonNull private final BnplCorrectionStore corrections;
    @NonNull private final BnplServicingStore servicing;
    @NonNull private final BnplPurchaseStore purchases;
    @NonNull private final BnplWriteOffStore store;
    @NonNull private final BnplConditionStore conditions;
    @NonNull private final BnplTermsStore terms;
    @NonNull private final BnplDisputeStore disputes;
    @NonNull private final BnplBook book;
    @NonNull private final BnplActingParty actingParties;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** Why a write-off cannot post now - codes the approver reads; never a figure. */
    public enum Blocker {
        /** The obligation is not {@code ACTIVE}. */
        NOT_IN_SERVICING,
        /** It is not defaulted now, or the default the proposal stood on is no longer its latest. */
        NOT_DEFAULTED,
        /** The obligation's figures or revision are no longer the proposal's. */
        MOVED,
        /** It holds a credit balance. */
        CREDIT_HELD,
        /** An elapsed date is not accrued yet, or the record is ahead of the clock. */
        BEHIND_ACCRUAL,
        /** A dispute of the purchase is open: its outcome may be a refund, which needs the receivable. */
        DISPUTE_OPEN
    }

    /** A decision's outcome; {@code replayed} when the same person's retry converged on their own decision. */
    public record Decided(BnplWriteOff writeOff, BnplProduct product, boolean replayed) {

        public Decided {
            Objects.requireNonNull(writeOff, "writeOff must not be null");
            Objects.requireNonNull(product, "product must not be null");
        }
    }

    /** A proposal as its proposer's receipt names it. */
    public record Proposed(BnplWriteOff writeOff, BnplProduct product) {

        public Proposed {
            Objects.requireNonNull(writeOff, "writeOff must not be null");
            Objects.requireNonNull(product, "product must not be null");
        }
    }

    /** What an approver reads, under their own permission, and attests - lending's {@code Attestation}. */
    public record Attestation(BnplWriteOff writeOff, BnplProduct product, BnplObligationStatus accountStatus,
            Optional<LocalDate> defaultedOn, BnplWriteOffPosition live, LocalDate postsOn, List<Blocker> blockers) {

        public Attestation {
            Objects.requireNonNull(writeOff, "writeOff must not be null");
            Objects.requireNonNull(product, "product must not be null");
            Objects.requireNonNull(accountStatus, "accountStatus must not be null");
            Objects.requireNonNull(defaultedOn, "defaultedOn must not be null");
            Objects.requireNonNull(live, "live must not be null");
            Objects.requireNonNull(postsOn, "postsOn must not be null");
            blockers = List.copyOf(blockers);
        }

        /** Never a figure or a reason. */
        @Override
        public String toString() {
            return "Attestation[" + writeOff + ", " + blockers + "]";
        }
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes writing the obligation of {@code agreement} off, with {@code reason}. Nothing is posted.
     *
     * @throws BnplReasons.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws BnplRepaymentReversals.NotFound no such agreement
     * @throws BnplRepaymentReversals.SelfDealingRefused the agreement is the actor's own party's, or the actor has none
     * @throws BnplWrittenOffRefused it is already written off
     * @throws NotInServicing the obligation is not {@code ACTIVE}
     * @throws NotDefaulted the obligation is not defaulted
     * @throws DisputeOpen a dispute of the purchase is open
     * @throws CreditHeld the obligation holds a credit balance
     * @throws BehindAccrual an elapsed date is not accrued yet
     * @throws ProposalPending a write-off of the obligation awaits a decision
     * @throws AllocationAnomaly the obligation's rows do not explain its due accounts
     */
    public Proposed propose(Connection unitOfWork, BnplAgreementId agreement, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = BnplReasons.reasoned(reason);
        BnplCorrectionStore.Addressed addressed = BnplRepaymentReversals.judgeSelfDealing(corrections, actingParties, unitOfWork,
                agreement, actor, "write-off:propose");
        // (B4) The obligation: every act on it serialises here.
        BnplServicingStore.Locked locked = servicing.lock(unitOfWork, addressed.obligation())
                .orElseThrow(BnplRepaymentReversals.NotFound::new);
        BnplWrittenOffRefused.refuseIfWrittenOff(locked.obligation());
        Assessment now = assess(unitOfWork, locked);
        BnplProduct product = locked.obligation().product();
        refuse(now.blockers(), product);
        if (store.pending(unitOfWork, addressed.obligation())) {
            throw new ProposalPending(product);
        }
        UUID id = ids.next();
        UUID stands = now.defaultEvent().orElseThrow(() -> new NotDefaulted(product));
        int version = locked.agreementVersion();
        var proposedAt = store.insert(unitOfWork, new BnplWriteOffStore.NewProposal(id, addressed.obligation(), locked.agreement(),
                now.position(), stands, version, actor, reasoned, correlation));
        audit(unitOfWork, actor, BnplAuditAction.WRITE_OFF_PROPOSED, id, reasoned,
                "agreement=" + agreement.value() + ", product=" + product.name() + ", revision=" + now.position().revision(),
                correlation);
        return new Proposed(new BnplWriteOff(id, addressed.obligation(), locked.agreement(), now.position(), stands, version,
                BnplCorrectionStatus.PROPOSED, actor.id(), proposedAt, reasoned, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty()), product);
    }

    // ------------------------------------------------------------------ approve

    /**
     * Approves {@code writeOff} - a different person's act, naming in {@code attested} what they read - posting F11.
     *
     * @throws BnplReasons.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws BnplRepaymentReversals.NotFound no such agreement, or no such write-off of it
     * @throws BnplRepaymentReversals.SelfDealingRefused the agreement is the actor's own party's, or the actor has none
     * @throws ProposalNotPending the write-off is decided - by somebody else, or differently
     * @throws SelfApprovalRefused the approver proposed it
     * @throws NotInServicing the obligation is no longer {@code ACTIVE}
     * @throws NotDefaulted the obligation is not defaulted now
     * @throws Stale what the approver named, the proposal and the ledger are not one state - nothing posted, the proposal standing
     * @throws DisputeOpen a dispute of the purchase is open
     * @throws CreditHeld the obligation holds a credit balance
     * @throws BehindAccrual an elapsed date is not accrued yet
     * @throws com.finapp.ledger.PeriodClosedException today's month is locked: nothing posted, the proposal standing
     * @throws AllocationAnomaly the obligation's rows do not explain its due accounts
     */
    public Decided approve(Connection unitOfWork, BnplAgreementId agreement, UUID writeOff, BnplWriteOffPosition attested, String reason,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(writeOff, "writeOff");
        Objects.requireNonNull(attested, "attested");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = BnplReasons.reasoned(reason);
        BnplCorrectionStore.Addressed addressed = BnplRepaymentReversals.judgeSelfDealing(corrections, actingParties, unitOfWork,
                agreement, actor, "write-off:approve");
        // (B4) The obligation.
        BnplServicingStore.Locked locked = servicing.lock(unitOfWork, addressed.obligation())
                .orElseThrow(BnplRepaymentReversals.NotFound::new);
        BnplObligation obligation = locked.obligation();
        BnplProduct product = obligation.product();
        // The proposal, read under the obligation's lock: the decision every committed act left.
        BnplWriteOff proposal = store.find(unitOfWork, obligation.id(), writeOff).orElseThrow(BnplRepaymentReversals.NotFound::new);
        if (proposal.status() == BnplCorrectionStatus.APPROVED && proposal.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(proposal, product, true);
        }
        if (proposal.status() != BnplCorrectionStatus.PROPOSED) {
            throw new ProposalNotPending(product, proposal.status());
        }
        if (proposal.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused(product, agreement, writeOff);
        }
        Assessment now = assess(unitOfWork, locked);
        if (now.blockers().contains(Blocker.NOT_IN_SERVICING)) {
            throw new NotInServicing(product);
        }
        if (now.blockers().contains(Blocker.NOT_DEFAULTED)) {
            throw new NotDefaulted(product);
        }
        // Compared by revision, never by status: a default cured and set again names a later change, a repayment reversed leaves the
        // figures and a higher revision.
        if (!attested.sameAs(proposal.position()) || !now.position().sameAs(proposal.position())
                || !now.defaultEvent().filter(proposal.defaultEvent()::equals).isPresent()) {
            throw new Stale(product);
        }
        refuse(now.blockers(), product);

        LedgerAccountId expense = book.operational(unitOfWork, AccountPurpose.BNPL_WRITE_OFF_EXPENSE, obligation.currency());
        LocalDate today = now.today();
        // Dated today by the server, value date included: AS_STATED, so a lock on today's month refuses it - never floored.
        JournalEntryId entry = book.post(unitOfWork, new PostingCommand(WRITE_OFF_KEY_PREFIX + obligation.id().value(), today, today,
                obligation.id().value().toString(), now.position().lines(expense, now.accounts())));
        if (!store.decide(unitOfWork, new BnplWriteOffStore.Decision(ids.next(), writeOff, obligation.id(), proposal.proposedBy(),
                BnplCorrectionStatus.APPROVED, actor, reasoned, Optional.of(entry), Optional.of(today), correlation))) {
            throw new IllegalStateException("a PROPOSED write-off read under its obligation's lock was decided: the lock protocol"
                    + " was bypassed");
        }
        if (!store.markWrittenOff(unitOfWork, obligation.id())) {
            throw new IllegalStateException("the locked obligation did not move WRITTEN_OFF under its own row lock");
        }
        purchases.appendObligationEvent(unitOfWork, new BnplPurchaseStore.ObligationHistoryRow(ids.next(), obligation.id(),
                Optional.of(BnplObligationStatus.ACTIVE), BnplObligationStatus.WRITTEN_OFF, WRITTEN_OFF_REASON, actor, correlation));
        publish(unitOfWork, obligation, locked.agreement(), writeOff, entry, today, locked.agreementVersion(), correlation);
        audit(unitOfWork, actor, BnplAuditAction.WRITE_OFF_APPROVED, writeOff, reasoned,
                "agreement=" + agreement.value() + ", product=" + product.name() + ", entry=" + entry.value() + ", valueDate=" + today,
                correlation);
        BnplWriteOff approved = store.find(unitOfWork, obligation.id(), writeOff)
                .orElseThrow(() -> new IllegalStateException("a write-off decided in this transaction is readable in it"));
        return new Decided(approved, product, false);
    }

    // ------------------------------------------------------------------ reject

    /**
     * Rejects {@code writeOff}, on any status - the door out of a proposal that can no longer post. Terminal; nothing is posted.
     *
     * @throws BnplReasons.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws BnplRepaymentReversals.NotFound no such agreement, or no such write-off of it
     * @throws BnplRepaymentReversals.SelfDealingRefused the agreement is the actor's own party's, or the actor has none
     * @throws ProposalNotPending the write-off is decided - by somebody else, or differently
     */
    public Decided reject(Connection unitOfWork, BnplAgreementId agreement, UUID writeOff, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(writeOff, "writeOff");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = BnplReasons.reasoned(reason);
        BnplCorrectionStore.Addressed addressed = BnplRepaymentReversals.judgeSelfDealing(corrections, actingParties, unitOfWork,
                agreement, actor, "write-off:reject");
        BnplServicingStore.Locked locked = servicing.lock(unitOfWork, addressed.obligation())
                .orElseThrow(BnplRepaymentReversals.NotFound::new);
        BnplProduct product = locked.obligation().product();
        BnplWriteOff proposal = store.find(unitOfWork, addressed.obligation(), writeOff)
                .orElseThrow(BnplRepaymentReversals.NotFound::new);
        if (proposal.status() == BnplCorrectionStatus.REJECTED && proposal.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(proposal, product, true);
        }
        if (proposal.status() != BnplCorrectionStatus.PROPOSED) {
            throw new ProposalNotPending(product, proposal.status());
        }
        if (!store.decide(unitOfWork, new BnplWriteOffStore.Decision(ids.next(), writeOff, addressed.obligation(), proposal.proposedBy(),
                BnplCorrectionStatus.REJECTED, actor, reasoned, Optional.empty(), Optional.empty(), correlation))) {
            throw new IllegalStateException("a PROPOSED write-off read under its obligation's lock was decided: the lock protocol"
                    + " was bypassed");
        }
        audit(unitOfWork, actor, BnplAuditAction.WRITE_OFF_REJECTED, writeOff, reasoned,
                "agreement=" + agreement.value() + ", product=" + product.name() + ", byProposer=" + proposal.proposedBy().equals(actor.id()),
                correlation);
        BnplWriteOff rejected = store.find(unitOfWork, addressed.obligation(), writeOff)
                .orElseThrow(() -> new IllegalStateException("a write-off decided in this transaction is readable in it"));
        return new Decided(rejected, product, false);
    }

    // ------------------------------------------------------------------ the approver's reads

    /**
     * What an approver reads and attests, under the obligation's lock so it is one state, its audit record appended. Decides nothing:
     * the approval re-judges every word of it.
     *
     * @throws BnplRepaymentReversals.NotFound no such agreement, or no such write-off of it
     * @throws BnplRepaymentReversals.SelfDealingRefused the agreement is the actor's own party's, or the actor has none
     */
    public Attestation read(Connection unitOfWork, BnplAgreementId agreement, UUID writeOff, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(writeOff, "writeOff");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        BnplCorrectionStore.Addressed addressed = BnplRepaymentReversals.judgeSelfDealing(corrections, actingParties, unitOfWork,
                agreement, actor, "write-off:read");
        BnplServicingStore.Locked locked = servicing.lock(unitOfWork, addressed.obligation())
                .orElseThrow(BnplRepaymentReversals.NotFound::new);
        BnplWriteOff found = store.find(unitOfWork, addressed.obligation(), writeOff)
                .orElseThrow(BnplRepaymentReversals.NotFound::new);
        Assessment now = assess(unitOfWork, locked);
        List<Blocker> blockers = new ArrayList<>(now.blockers());
        if (found.status() == BnplCorrectionStatus.PROPOSED && (!now.position().sameAs(found.position())
                || !now.defaultEvent().filter(found.defaultEvent()::equals).isPresent())) {
            blockers.add(Blocker.MOVED);
        }
        Optional<LocalDate> defaultedOn = conditions.latest(unitOfWork, addressed.obligation(), BnplConditionKind.DEFAULT)
                .filter(change -> change.id().equals(found.defaultEvent()))
                .map(BnplConditionEvent::businessDate);
        auditRead(unitOfWork, actor, writeOff.toString(), "agreement=" + agreement.value() + ", writeOff=" + writeOff, correlation);
        return new Attestation(found, locked.obligation().product(), locked.obligation().status(), defaultedOn, now.position(),
                now.today(), blockers);
    }

    /** The oldest write-offs awaiting a decision, audited - how one that can no longer post is found. */
    public List<BnplWriteOff> pending(Connection unitOfWork, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        List<BnplWriteOff> found = store.oldestPending(unitOfWork, PENDING_LIMIT);
        auditRead(unitOfWork, actor, "pending", "pending=" + found.size(), correlation);
        return found;
    }

    /** Records a refused self-dealing attempt {@code FAILED}, in the caller's own transaction after the act rolled back. */
    public Optional<BnplProduct> recordRefusal(Connection unitOfWork, BnplRepaymentReversals.SelfDealingRefused refused, Actor actor,
            CorrelationId correlation) {
        BnplRepaymentReversals.record(audit, ids, clock, unitOfWork, refused, actor, correlation);
        // The product its meter is tagged with: the act was refused before it read the obligation, so this transaction reads it.
        return corrections.addressed(unitOfWork, refused.agreement())
                .flatMap(addressed -> servicing.lock(unitOfWork, addressed.obligation()))
                .map(locked -> locked.obligation().product());
    }

    /** Records a refused self-approval {@code FAILED}, in the caller's own transaction after the act rolled back. */
    public void recordRefusal(Connection unitOfWork, SelfApprovalRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        SelfApprovalRefusals.record(audit, ids, clock, unitOfWork, actor, TARGET_TYPE, refused.target(), refused.summary(), correlation);
    }

    // ------------------------------------------------------------------ the judgements

    private record Assessment(BnplWriteOffPosition position, BnplLedgerAccounts accounts, Optional<UUID> defaultEvent, LocalDate today,
            List<Blocker> blockers) {}

    /** What the obligation is now, under the caller's B4. */
    private Assessment assess(Connection unitOfWork, BnplServicingStore.Locked locked) {
        BnplObligation obligation = locked.obligation();
        // The database clock, read after the lock, in the pinned zone.
        LocalDate today = LocalDate.ofInstant(servicing.databaseNow(unitOfWork), ZoneId.of(locked.servicingZone()));
        BnplReceivables receivables = new BnplReceivables(purchases, book);
        BnplLedgerAccounts accounts = receivables.accounts(unitOfWork, obligation.id());
        Figures figures = figures(unitOfWork, obligation, accounts);
        Optional<BnplConditionEvent> latest = conditions.latest(unitOfWork, obligation.id(), BnplConditionKind.DEFAULT);
        Optional<UUID> defaultEvent = latest.filter(change -> change.state().defaulted()).map(BnplConditionEvent::id);
        List<Blocker> blockers = new ArrayList<>();
        if (obligation.status() != BnplObligationStatus.ACTIVE) {
            // Its rows no longer explain the accounts on a written-off obligation, and nothing posts on a terminal one.
            blockers.add(Blocker.NOT_IN_SERVICING);
            return new Assessment(figures.position(), accounts, defaultEvent, today, blockers);
        }
        // The rows explain the due accounts, or nothing is written off over the difference.
        receivables.dueState(unitOfWork, obligation.id(), accounts);
        BnplTerms pinned = terms.version(unitOfWork, locked.termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"))
                .terms();
        boolean derived = BnplDelinquencyState.derive(purchases.billedItems(unitOfWork, obligation.id()), today, pinned,
                defaultEvent.isPresent()).defaulted();
        if (defaultEvent.isEmpty() || !derived) {
            blockers.add(Blocker.NOT_DEFAULTED);
        }
        if (figures.credit().isPositive()) {
            blockers.add(Blocker.CREDIT_HELD);
        }
        if (!BnplAccruals.caughtUp(unitOfWork, servicing, purchases, book, locked, today)
                || servicing.principalValueFloor(unitOfWork, obligation.id()).isAfter(today)) {
            blockers.add(Blocker.BEHIND_ACCRUAL);
        }
        if (disputes.openOf(unitOfWork, obligation.purchase()).isPresent()) {
            blockers.add(Blocker.DISPUTE_OPEN);
        }
        return new Assessment(figures.position(), accounts, defaultEvent, today, blockers);
    }

    /** Each blocker's own refusal, in the order a person can act on them. */
    private static void refuse(List<Blocker> blockers, BnplProduct product) {
        if (blockers.contains(Blocker.NOT_IN_SERVICING)) {
            throw new NotInServicing(product);
        }
        if (blockers.contains(Blocker.NOT_DEFAULTED)) {
            throw new NotDefaulted(product);
        }
        if (blockers.contains(Blocker.DISPUTE_OPEN)) {
            throw new DisputeOpen(product);
        }
        if (blockers.contains(Blocker.CREDIT_HELD)) {
            throw new CreditHeld(product);
        }
        if (blockers.contains(Blocker.BEHIND_ACCRUAL)) {
            throw new BehindAccrual(product);
        }
    }

    private record Figures(BnplWriteOffPosition position, Money credit) {}

    /**
     * The five receivables and the credit balance, each from zero over its journal lines - ONE statement, so a billing moving principal
     * between two of them is wholly in or wholly out - and how many lines stand on the six.
     */
    private Figures figures(Connection unitOfWork, BnplObligation obligation, BnplLedgerAccounts accounts) {
        Set<LedgerAccountId> six = new HashSet<>();
        accounts.all().forEach(account -> six.add(account.id()));
        List<PurposeBalances.DatedLine> lines = book.datedLinesOn(unitOfWork, six, EVERY_VALUE_DATE);
        Money zero = Money.ofPersisted(0, obligation.currency(), accounts.principal().currency().minorUnits());
        Map<LedgerAccountId, Money> net = new HashMap<>();
        for (PurposeBalances.DatedLine line : lines) {
            Money signed = line.direction() == Direction.DEBIT ? line.amount() : line.amount().negated();
            net.merge(line.account(), signed, (left, right) -> left.plus(right));
        }
        Money principal = net.getOrDefault(accounts.principal().id(), zero);
        Money principalDue = net.getOrDefault(accounts.principalDue().id(), zero);
        Money interestAccrued = net.getOrDefault(accounts.interestAccrued().id(), zero);
        Money interestDue = net.getOrDefault(accounts.interestDue().id(), zero);
        Money feesDue = net.getOrDefault(accounts.feesDue().id(), zero);
        Money credit = net.getOrDefault(accounts.creditBalance().id(), zero).negated();
        for (Money figure : List.of(principal, principalDue, interestAccrued, interestDue, feesDue, credit)) {
            if (figure.isNegative()) {
                throw new AllocationAnomaly("an obligation's account stands on its wrong side - nothing is written off (INV-BNPL-01)");
            }
        }
        return new Figures(new BnplWriteOffPosition(principal, principalDue, interestAccrued, interestDue, feesDue, lines.size()), credit);
    }

    // ------------------------------------------------------------------ the event and the audit

    /** {@code bnpl.BnplWrittenOff}: ids and codes only - never an amount, a reason or a party. */
    private void publish(Connection unitOfWork, BnplObligation obligation, BnplAgreementId agreement, UUID writeOff, JournalEntryId entry,
            LocalDate valueDate, int agreementVersion, CorrelationId correlation) {
        EventPayload payload = EventPayload.of()
                .with("obligationId", obligation.id().value().toString())
                .with("agreementId", agreement.value().toString())
                .with("writeOffId", writeOff.toString())
                .with("product", obligation.product().name())
                .with("journalEntryId", entry.value().toString())
                .with("valueDate", valueDate.toString())
                .with("agreementVersion", Integer.toString(agreementVersion));
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        WRITTEN_OFF_EVENT,
                        BnplTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        obligation.id(),
                        BnplAcceptances.OBLIGATION_AGGREGATE_TYPE,
                        clock.instant(),
                        BnplTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    private void audit(Connection unitOfWork, Actor actor, BnplAuditAction action, UUID writeOff, String reason, String summary,
            CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, TARGET_TYPE, writeOff.toString(),
                Optional.of(reason), AuditOutcome.SUCCEEDED, correlation, Optional.of(summary)));
    }

    private void auditRead(Connection unitOfWork, Actor actor, String target, String summary, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), BnplAuditAction.WRITE_OFF_READ, TARGET_TYPE,
                target, Optional.empty(), AuditOutcome.SUCCEEDED, correlation, Optional.of(summary)));
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** A refusal of an act on an obligation of a known product - the product tags its meter. */
    public abstract static class Refused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final BnplProduct product;

        Refused(BnplProduct product, String message) {
            super(message);
            this.product = Objects.requireNonNull(product, "product");
        }

        /** The obligation's product. */
        public BnplProduct product() {
            return product;
        }
    }

    /** The obligation is closed: nothing on it is written off (a proposal may still be rejected). */
    public static final class NotInServicing extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NotInServicing(BnplProduct product) {
            super(product, "the agreement is not in servicing: nothing on it is written off");
        }
    }

    /** The obligation is not defaulted: only a defaulted obligation is written off. */
    public static final class NotDefaulted extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NotDefaulted(BnplProduct product) {
            super(product, "the agreement is not defaulted: only a defaulted agreement is written off");
        }
    }

    /** A dispute of the purchase is open: its outcome may be a refund, so the customer's dispute comes first. */
    public static final class DisputeOpen extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        DisputeOpen(BnplProduct product) {
            super(product, "a dispute of the purchase is open: it is resolved first");
        }
    }

    /** The obligation holds a credit balance: its next servicing step applies it, which moves the figures. */
    public static final class CreditHeld extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CreditHeld(BnplProduct product) {
            super(product, "the agreement holds a credit balance: its next servicing step applies it to what is due");
        }
    }

    /** An elapsed date is not accrued yet, or the record is ahead of the clock: its next servicing step moves the figures. */
    public static final class BehindAccrual extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        BehindAccrual(BnplProduct product) {
            super(product, "the agreement's elapsed days are not accrued yet: its next servicing step brings them, then propose");
        }
    }

    /** What the approver named, the proposal and the ledger are not one state: nothing posted, the proposal standing. */
    public static final class Stale extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        Stale(BnplProduct product) {
            super(product, "the agreement has moved since the proposal, or the figures named are not the proposal's: reject it and"
                    + " propose again");
        }
    }

    /** A write-off of the obligation already awaits a decision. */
    public static final class ProposalPending extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProposalPending(BnplProduct product) {
            super(product, "a write-off of this agreement already awaits a decision");
        }
    }

    /** The write-off is decided - by somebody else, or differently. */
    public static final class ProposalNotPending extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProposalNotPending(BnplProduct product, BnplCorrectionStatus status) {
            super(product, "the write-off is no longer awaiting a decision: it is " + status.name());
        }
    }

    /** The approver proposed the write-off ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(BnplProduct product, BnplAgreementId agreement, UUID writeOff) {
            super(product, "a write-off is approved by someone other than its proposer");
            this.target = writeOff.toString();
            this.summary = "agreement=" + agreement.value() + ", writeOff=" + writeOff + ", act=write-off:approve";
        }

        /** The proposal the approver proposed - the refusal record's target. */
        public String target() {
            return target;
        }

        /** What the refusal record names: the proposal and the act - never an amount. */
        public String summary() {
            return summary;
        }
    }
}
