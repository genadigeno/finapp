package com.finapp.lending;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.PurposeBalances;
import com.finapp.lending.LoanReceivables.Accounts;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A defaulted loan written off under four eyes (`P14-TSK-021`; owner decision C6 (A); ADR-0096 rule 14, ADR-0104's Follow-up;
 * {@code INV-LND-01}, {@code INV-REV-04}, {@code INV-AUD-04}, {@code INV-BAL-03}, {@code INV-LIFE-04}). <strong>A write-off is not
 * forgiveness.</strong> It moves an asset to expense on the platform's books and says nothing about what the customer owes: the
 * account goes {@code WRITTEN_OFF}, never {@code CLOSED}, the customer's read keeps naming what was owed, and credit's exposure
 * keeps counting it.
 *
 * <h2>The machine</h2>
 *
 * <p>{@code PROPOSED -> APPROVED | REJECTED}, decided once, per defaulted loan or line. A holder of
 * {@code LOAN_WRITE_OFF_PROPOSE} proposes it with a reason; a DIFFERENT person holding {@code LOAN_WRITE_OFF_APPROVE}, on a
 * {@code MULTI_FACTOR} session, names what they read and approves it; a holder of {@code LOAN_WRITE_OFF_APPROVE} rejects it, on
 * any account status, so a proposal that can no longer post always has a door out. Each act is ONE transaction on the caller's
 * unit of work, which the door's idempotency claim wraps; every refusal is thrown before any write.
 *
 * <h2>What is judged, at the proposal and again under the lock at the approval</h2>
 *
 * <ul>
 *   <li>an {@code ACTIVE} or {@code CLOSING} account;
 *   <li><strong>defaulted</strong>: its latest {@code DEFAULT} change set the flag <em>and</em> the condition derived now, on the
 *       database clock, still holds it - a repayment that cured it counts at its commit, before any servicing step records it;
 *   <li>no credit balance: it is the customer's money, a liability, never written off - its servicing step applies it to what
 *       is due first ({@link InstalmentBillings#applyHeldCredit}), which moves the figures;
 *   <li>every elapsed date accrued, so no interest earned is forgone and {@code ACCRUALS_COMPLETE} sees no gap ever after; and
 *       the record not ahead of the clock, so no date it has closed is valued into (the Phase 11 gate's R1);
 *   <li>the rows explaining the due accounts ({@link AllocationAnomaly} otherwise: nothing is written off over a difference).
 * </ul>
 *
 * <p>At the approval also: <strong>nothing moved</strong>. The approver names the five figures and the revision they read
 * ({@link LoanWriteOffPosition}), and they must be the proposal's, and the ledger's under the lock, and the {@code DEFAULT} change
 * must be the very one the proposal stood on - judged by its id, because a default cures and comes back. Anything moved since
 * - a repayment, an accrual, a late fee, a billing - is {@link Stale}, never silently re-read; the proposal stands and its door
 * out is a rejection and a new proposal. An accrual moves it each day, so a proposal is decided on the business date it was made.
 *
 * <h2>The approval, in the lock order</h2>
 *
 * <ol>
 *   <li>the loan's party, read unlocked, and the actor's: never one's own party's loan;
 *   <li><strong>(L3)</strong> the loan {@code FOR UPDATE} - where a repayment, a servicing step, a reversal, a waiver and a second
 *       approver all serialise. No L0: the exposure does not rise. No L6: principal only falls, and the headroom's written-off
 *       term rises with it, read after it. No L7: no wallet is touched;
 *   <li>the proposal, read under L3 (born once; its decision {@code UNIQUE (write_off_id)} beneath L3);
 *   <li>the judgements; today in the pinned zone, the database clock read after the lock;
 *   <li>rule 14 under {@code lending.write-off:<loan>} - the ledger's projection rows in its one order, namespace 14 shared in the
 *       journal insert's trigger - {@link PostingCommand.Dating#AS_STATED}: dated today, value date included, or refused by the
 *       cutoff ({@code ledger.PeriodClosed}), a person's act never moved to another date;
 *   <li>then only writes no other transaction waits on: the decision naming the entry, the edge, its history row,
 *       {@code lending.LoanWrittenOff}, the audit record.
 * </ol>
 *
 * <p>The headroom's input is the entry itself: {@link LendingCapitalHeadroom} derives what was written off from journal lines, so
 * the entry, the row and the input are one transaction by construction. Nothing else moves: no wallet, no capital line.
 *
 * <p><strong>No way back here.</strong> A write-off approved in error stands: reversing it is a new rule and a new edge out of a
 * terminal state, an owner item ({@code LENDING_LIFECYCLES.md}). The entry changes nothing the customer owes meanwhile.
 */
@RequiredArgsConstructor
public final class LoanWriteOffs {

    /** Rule 14's ledger key prefix: {@code lending.write-off:<loan>} - a loan is written off once, ever. */
    public static final String WRITE_OFF_KEY_PREFIX = "lending.write-off:";

    /** The event an approved write-off publishes. */
    public static final String WRITTEN_OFF_EVENT = "lending.LoanWrittenOff";

    /** The history row's reason code on the loan's edge. */
    static final String WRITTEN_OFF_REASON = "WRITTEN_OFF";

    /** The audit records' target type. */
    static final String TARGET_TYPE = "loan_write_off";

    /** The oldest pending write-offs one listing answers. */
    public static final int PENDING_LIMIT = 100;

    @NonNull private final LoanStore loans;
    @NonNull private final LoanWriteOffStore store;
    @NonNull private final LoanConditionStore conditions;
    @NonNull private final LoanTermsStore terms;
    @NonNull private final InstalmentBillingStore billings;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final PurposeBalances<Connection> balances;
    @NonNull private final LoanClosures closures;
    @NonNull private final PostingService postings;
    @NonNull private final LoanActingParty actingParties;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** Why a write-off cannot post now - codes the approver reads; never a figure. */
    public enum Blocker {
        /** The account is not {@code ACTIVE} or {@code CLOSING}. */
        NOT_IN_SERVICING,
        /** It is not defaulted now, or the default the proposal stood on is no longer its latest. */
        NOT_DEFAULTED,
        /** The account's figures or revision are no longer the proposal's. */
        MOVED,
        /** It holds a credit balance. */
        CREDIT_HELD,
        /** An elapsed date is not accrued yet, or the record is ahead of the clock. */
        BEHIND_ACCRUAL
    }

    /** A decision's outcome; {@code replayed} when the same person's retry converged on their own decision. */
    public record Decided(LoanWriteOff writeOff, LoanProduct product, boolean replayed) {

        public Decided {
            Objects.requireNonNull(writeOff, "writeOff must not be null");
            Objects.requireNonNull(product, "product must not be null");
        }
    }

    /** A proposal as its proposer's receipt names it. */
    public record Proposed(LoanWriteOff writeOff, LoanProduct product) {

        public Proposed {
            Objects.requireNonNull(writeOff, "writeOff must not be null");
            Objects.requireNonNull(product, "product must not be null");
        }
    }

    /**
     * What an approver reads, under their own permission, and attests: the proposal; the account's product, status and the date
     * its default stands on; what its figures and revision are now; the date the entry would post and value on; why it could not
     * post now.
     */
    public record Attestation(LoanWriteOff writeOff, LoanProduct product, LoanStatus accountStatus, Optional<LocalDate> defaultedOn,
            LoanWriteOffPosition live, LocalDate postsOn, List<Blocker> blockers) {

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
     * Proposes writing {@code loan} off, with {@code reason}: under its row lock, every judgement the class names; born
     * {@code PROPOSED} with the figures and revision read, the {@code DEFAULT} change it stands on and the reasoned audit record.
     * Nothing is posted.
     *
     * @throws LendingCapitalAdministration.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws NotFound no such loan
     * @throws RepaymentReversals.SelfDealingRefused the loan is the actor's own party's
     * @throws LoanWrittenOffRefused the loan is already written off
     * @throws NotInServicing the account is not {@code ACTIVE} or {@code CLOSING}
     * @throws NotDefaulted the account is not defaulted
     * @throws CreditHeld the account holds a credit balance
     * @throws BehindAccrual an elapsed date is not accrued yet
     * @throws ProposalPending a write-off of the account awaits a decision
     * @throws AllocationAnomaly the loan's rows do not explain its due accounts
     */
    public Proposed propose(Connection unitOfWork, LoanId loan, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = LendingCapitalAdministration.reasoned(reason);
        LoanSelfDealing.judge(loans, actingParties, unitOfWork, loan, actor, "write-off:propose", NotFound::new);
        // (L3) The loan: every act on the account serialises here.
        LoanStore.Owned locked = loans.lock(unitOfWork, loan).orElseThrow(NotFound::new);
        LoanWrittenOffRefused.refuseIfWrittenOff(locked.loan());
        Assessment now = assess(unitOfWork, locked);
        LoanProduct product = locked.loan().product();
        refuse(now.blockers(), product);
        if (store.pending(unitOfWork, loan)) {
            throw new ProposalPending(product);
        }
        UUID id = ids.next();
        UUID stands = now.defaultEvent().orElseThrow(() -> new NotDefaulted(product));
        int version = locked.agreement().version();
        var proposedAt = store.insert(unitOfWork, new LoanWriteOffStore.NewProposal(id, loan, now.position(), stands, version,
                actor, reasoned, correlation));
        audit(unitOfWork, actor, LendingAuditAction.WRITE_OFF_PROPOSED, id, reasoned,
                "loan=" + loan.value() + ", product=" + product.name() + ", revision=" + now.position().revision(), correlation);
        return new Proposed(new LoanWriteOff(id, loan, now.position(), stands, version, LoanWriteOffStatus.PROPOSED, actor.id(),
                proposedAt, reasoned, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                product);
    }

    // ------------------------------------------------------------------ approve

    /**
     * Approves {@code writeOff} - a different person's act, naming in {@code attested} what they read - posting rule 14 in the lock
     * order the class names.
     *
     * @throws LendingCapitalAdministration.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws NotFound no such loan, or no such write-off of it
     * @throws RepaymentReversals.SelfDealingRefused the loan is the actor's own party's
     * @throws ProposalNotPending the write-off is decided - by somebody else, or differently
     * @throws SelfApprovalRefused the approver proposed it
     * @throws NotInServicing the account is no longer {@code ACTIVE} or {@code CLOSING}
     * @throws NotDefaulted the account is not defaulted now
     * @throws Stale what the approver named, the proposal and the ledger are not one state - nothing posted, the proposal standing
     * @throws CreditHeld the account holds a credit balance
     * @throws BehindAccrual an elapsed date is not accrued yet
     * @throws com.finapp.ledger.PeriodClosedException today's month is locked: nothing posted, the proposal standing
     * @throws AllocationAnomaly the loan's rows do not explain its due accounts
     */
    public Decided approve(Connection unitOfWork, LoanId loan, UUID writeOff, LoanWriteOffPosition attested, String reason,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(writeOff, "writeOff");
        Objects.requireNonNull(attested, "attested");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = LendingCapitalAdministration.reasoned(reason);
        LoanSelfDealing.judge(loans, actingParties, unitOfWork, loan, actor, "write-off:approve", NotFound::new);
        // (L3) The loan.
        LoanStore.Owned locked = loans.lock(unitOfWork, loan).orElseThrow(NotFound::new);
        Loan account = locked.loan();
        LoanProduct product = account.product();
        // The proposal, read under the loan's lock: the decision every committed act left.
        LoanWriteOff proposal = store.find(unitOfWork, loan, writeOff).orElseThrow(NotFound::new);
        if (proposal.status() == LoanWriteOffStatus.APPROVED && proposal.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(proposal, product, true);
        }
        if (proposal.status() != LoanWriteOffStatus.PROPOSED) {
            throw new ProposalNotPending(product, proposal.status());
        }
        if (proposal.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused(product, loan, writeOff);
        }
        Assessment now = assess(unitOfWork, locked);
        if (now.blockers().contains(Blocker.NOT_IN_SERVICING)) {
            throw new NotInServicing(product);
        }
        if (now.blockers().contains(Blocker.NOT_DEFAULTED)) {
            throw new NotDefaulted(product);
        }
        // Compared by revision, never by status: a default cured and set again names a later change, a repayment reversed
        // leaves the figures and a higher revision.
        if (!attested.sameAs(proposal.position()) || !now.position().sameAs(proposal.position())
                || !now.defaultEvent().filter(proposal.defaultEvent()::equals).isPresent()) {
            throw new Stale(product);
        }
        refuse(now.blockers(), product);

        LedgerAccountId expense = ledgerAccounts.findOperational(unitOfWork, AccountPurpose.LOAN_WRITE_OFF_EXPENSE, account.currency())
                .orElseThrow(() -> new IllegalStateException("ledger V026 seeds the write-off expense in every supported currency"))
                .id();
        LocalDate today = now.today();
        // Dated today by the server, value date included: AS_STATED, so a lock on today's month refuses it - never floored.
        PostingResult posted = postings.post(unitOfWork, new PostingCommand(WRITE_OFF_KEY_PREFIX + loan.value(), today, today,
                loan.value().toString(), now.position().lines(expense, now.accounts())));
        if (posted.replayed()) {
            throw new IllegalStateException("a write-off's entry exists for a loan not yet written off (INV-IDEM-01)");
        }
        JournalEntryId entry = posted.entryId();
        if (!store.decide(unitOfWork, new LoanWriteOffStore.Decision(ids.next(), writeOff, loan, proposal.proposedBy(),
                LoanWriteOffStatus.APPROVED, actor, reasoned, Optional.of(entry), Optional.of(today), correlation))) {
            throw new IllegalStateException("a PROPOSED write-off read under its loan's lock was decided: the lock protocol was"
                    + " bypassed");
        }
        if (!store.markWrittenOff(unitOfWork, loan, account.status())) {
            throw new IllegalStateException("the locked loan did not move WRITTEN_OFF under its own row lock");
        }
        loans.appendEvent(unitOfWork, new LoanStore.HistoryRow(ids.next(), loan, Optional.of(account.status()),
                LoanStatus.WRITTEN_OFF, WRITTEN_OFF_REASON, actor, correlation));
        publish(unitOfWork, account, writeOff, entry, today, locked.agreement().version(), correlation);
        audit(unitOfWork, actor, LendingAuditAction.WRITE_OFF_APPROVED, writeOff, reasoned,
                "loan=" + loan.value() + ", product=" + product.name() + ", entry=" + entry.value() + ", valueDate=" + today,
                correlation);
        LoanWriteOff approved = store.find(unitOfWork, loan, writeOff)
                .orElseThrow(() -> new IllegalStateException("a write-off decided in this transaction is readable in it"));
        return new Decided(approved, product, false);
    }

    // ------------------------------------------------------------------ reject

    /**
     * Rejects {@code writeOff}, on any account status - the door out of a proposal that can no longer post; the proposer may be the
     * rejecter when they hold the permission. Terminal; nothing is posted.
     *
     * @throws LendingCapitalAdministration.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws NotFound no such loan, or no such write-off of it
     * @throws RepaymentReversals.SelfDealingRefused the loan is the actor's own party's
     * @throws ProposalNotPending the write-off is decided - by somebody else, or differently
     */
    public Decided reject(Connection unitOfWork, LoanId loan, UUID writeOff, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(writeOff, "writeOff");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = LendingCapitalAdministration.reasoned(reason);
        LoanSelfDealing.judge(loans, actingParties, unitOfWork, loan, actor, "write-off:reject", NotFound::new);
        LoanStore.Owned locked = loans.lock(unitOfWork, loan).orElseThrow(NotFound::new);
        LoanProduct product = locked.loan().product();
        LoanWriteOff proposal = store.find(unitOfWork, loan, writeOff).orElseThrow(NotFound::new);
        if (proposal.status() == LoanWriteOffStatus.REJECTED && proposal.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(proposal, product, true);
        }
        if (proposal.status() != LoanWriteOffStatus.PROPOSED) {
            throw new ProposalNotPending(product, proposal.status());
        }
        if (!store.decide(unitOfWork, new LoanWriteOffStore.Decision(ids.next(), writeOff, loan, proposal.proposedBy(),
                LoanWriteOffStatus.REJECTED, actor, reasoned, Optional.empty(), Optional.empty(), correlation))) {
            throw new IllegalStateException("a PROPOSED write-off read under its loan's lock was decided: the lock protocol was"
                    + " bypassed");
        }
        audit(unitOfWork, actor, LendingAuditAction.WRITE_OFF_REJECTED, writeOff, reasoned,
                "loan=" + loan.value() + ", product=" + product.name() + ", byProposer=" + proposal.proposedBy().equals(actor.id()),
                correlation);
        LoanWriteOff rejected = store.find(unitOfWork, loan, writeOff)
                .orElseThrow(() -> new IllegalStateException("a write-off decided in this transaction is readable in it"));
        return new Decided(rejected, product, false);
    }

    // ------------------------------------------------------------------ the approver's reads

    /**
     * What an approver reads and attests, on the caller's snapshot, its audit record appended - the proposal, and whether it still
     * stands now. Decides nothing: the approval re-judges every word of it under the lock.
     *
     * @throws NotFound no such loan, or no such write-off of it
     * @throws RepaymentReversals.SelfDealingRefused the loan is the actor's own party's
     */
    public Attestation read(Connection unitOfWork, LoanId loan, UUID writeOff, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(writeOff, "writeOff");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        UUID party = LoanSelfDealing.judge(loans, actingParties, unitOfWork, loan, actor, "write-off:read", NotFound::new);
        LoanStore.Owned owned = loans.ownedBy(unitOfWork, loan, party).orElseThrow(NotFound::new);
        LoanWriteOff found = store.find(unitOfWork, loan, writeOff).orElseThrow(NotFound::new);
        Assessment now = assess(unitOfWork, owned);
        List<Blocker> blockers = new ArrayList<>(now.blockers());
        if (found.status() == LoanWriteOffStatus.PROPOSED && (!now.position().sameAs(found.position())
                || !now.defaultEvent().filter(found.defaultEvent()::equals).isPresent())) {
            blockers.add(Blocker.MOVED);
        }
        Optional<LocalDate> defaultedOn = conditions.latest(unitOfWork, loan, ConditionKind.DEFAULT)
                .filter(change -> change.id().equals(found.defaultEvent()))
                .map(LoanConditionEvent::businessDate);
        auditRead(unitOfWork, actor, "loan_write_off", writeOff.toString(),
                "loan=" + loan.value() + ", writeOff=" + writeOff, correlation);
        return new Attestation(found, owned.loan().product(), owned.loan().status(), defaultedOn, now.position(), now.today(),
                blockers);
    }

    /** The oldest write-offs awaiting a decision, on the caller's snapshot, audited - how one that can no longer post is found. */
    public List<LoanWriteOff> pending(Connection unitOfWork, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        List<LoanWriteOff> found = store.oldestPending(unitOfWork, PENDING_LIMIT);
        auditRead(unitOfWork, actor, "loan_write_off", "pending", "pending=" + found.size(), correlation);
        return found;
    }

    /**
     * Records a refused self-dealing attempt {@code FAILED} on {@code unitOfWork} - the caller's own transaction, after the act
     * rolled back, so the attempt is kept.
     */
    public Optional<LoanProduct> recordRefusal(Connection unitOfWork, RepaymentReversals.SelfDealingRefused refused, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                LendingAuditAction.SELF_DEALING_REFUSED, "loan", refused.loan().value().toString(), Optional.empty(),
                AuditOutcome.FAILED, correlation, Optional.of("loan=" + refused.loan().value() + ", act="
                        + refused.act() + " refused - the loan is the acting operator's own party's")));
        // The product its meter is tagged with: the act was refused before it read the account, so this transaction reads it.
        return loans.lock(unitOfWork, refused.loan()).map(owned -> owned.loan().product());
    }

    /** Records a refused self-approval {@code FAILED}, in the caller's own transaction after the act rolled back. */
    public void recordRefusal(Connection unitOfWork, SelfApprovalRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        SelfApprovalRefusals.record(audit, ids, clock, unitOfWork, actor, TARGET_TYPE, refused.target(), refused.summary(),
                correlation);
    }

    // ------------------------------------------------------------------ the judgements

    /** What the account is now: read under the caller's L3, or on a reader's snapshot. */
    private record Assessment(LoanWriteOffPosition position, Accounts accounts, Optional<UUID> defaultEvent, LocalDate today,
            List<Blocker> blockers) {}

    private Assessment assess(Connection unitOfWork, LoanStore.Owned owned) {
        Loan account = owned.loan();
        LoanAgreement agreement = owned.agreement();
        Money zero = Money.ofPersisted(0, account.currency(), agreement.amount().scale());
        LoanTerms pinned = terms.version(unitOfWork, agreement.termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"))
                .terms();
        ZoneId zone = ZoneId.of(pinned.servicingZone());
        // The database clock, read after the lock, in the agreement's pinned zone.
        LocalDate today = LocalDate.ofInstant(store.databaseNow(unitOfWork), zone);
        LoanReceivables receivables = new LoanReceivables(billings, ledgerAccounts, balances);
        Accounts accounts = receivables.accounts(unitOfWork, account.id(), account.currency());
        Figures figures = figures(unitOfWork, accounts, zero);
        Optional<LoanConditionEvent> latest = conditions.latest(unitOfWork, account.id(), ConditionKind.DEFAULT);
        Optional<UUID> defaultEvent = latest.filter(change -> change.state().defaulted()).map(LoanConditionEvent::id);
        List<Blocker> blockers = new ArrayList<>();
        if (account.status() != LoanStatus.ACTIVE && account.status() != LoanStatus.CLOSING) {
            // Its rows no longer explain the accounts on a written-off loan (the due items stand, the ledger holds zero), and
            // nothing posts on a terminal account whatever they say.
            blockers.add(Blocker.NOT_IN_SERVICING);
            return new Assessment(figures.position(), accounts, defaultEvent, today, blockers);
        }
        // The rows explain the due accounts, or nothing is written off over the difference.
        receivables.dueState(unitOfWork, account.id(), accounts, zero);
        boolean derived = DelinquencyState.derive(billings.dueItems(unitOfWork, account.id()), today, pinned,
                defaultEvent.isPresent()).defaulted();
        if (defaultEvent.isEmpty() || !derived) {
            blockers.add(Blocker.NOT_DEFAULTED);
        }
        if (figures.credit().isPositive()) {
            blockers.add(Blocker.CREDIT_HELD);
        }
        if (!closures.accruedThroughYesterday(unitOfWork, account, accounts, zone, today, zero)
                || loans.principalValueDate(unitOfWork, account.id(), today).isAfter(today)) {
            blockers.add(Blocker.BEHIND_ACCRUAL);
        }
        return new Assessment(figures.position(), accounts, defaultEvent, today, blockers);
    }

    /** Each blocker's own refusal, in the order a person can act on them. */
    private static void refuse(List<Blocker> blockers, LoanProduct product) {
        if (blockers.contains(Blocker.NOT_IN_SERVICING)) {
            throw new NotInServicing(product);
        }
        if (blockers.contains(Blocker.NOT_DEFAULTED)) {
            throw new NotDefaulted(product);
        }
        if (blockers.contains(Blocker.CREDIT_HELD)) {
            throw new CreditHeld(product);
        }
        if (blockers.contains(Blocker.BEHIND_ACCRUAL)) {
            throw new BehindAccrual(product);
        }
    }

    private record Figures(LoanWriteOffPosition position, Money credit) {}

    /** The five receivables and the credit balance, each from zero over its journal lines, and how many lines stand on the six. */
    private Figures figures(Connection unitOfWork, Accounts accounts, Money zero) {
        long[] revision = new long[1];
        Money principal = net(unitOfWork, accounts.principal(), zero, revision);
        Money principalDue = net(unitOfWork, accounts.principalDue(), zero, revision);
        Money interestAccrued = net(unitOfWork, accounts.interestAccrued(), zero, revision);
        Money interestDue = net(unitOfWork, accounts.interestDue(), zero, revision);
        Money feesDue = net(unitOfWork, accounts.feesDue(), zero, revision);
        Money credit = net(unitOfWork, accounts.creditBalance(), zero, revision).negated();
        for (Money figure : List.of(principal, principalDue, interestAccrued, interestDue, feesDue, credit)) {
            if (figure.isNegative()) {
                throw new AllocationAnomaly("a loan's account stands on its wrong side - nothing is written off (INV-LND-01)");
            }
        }
        return new Figures(new LoanWriteOffPosition(principal, principalDue, interestAccrued, interestDue, feesDue, revision[0]),
                credit);
    }

    private Money net(Connection unitOfWork, LedgerAccountId account, Money zero, long[] revision) {
        Money net = zero;
        for (PurposeBalances.EntryLine line : balances.linesOn(unitOfWork, account)) {
            net = line.direction() == Direction.DEBIT ? net.plus(line.amount()) : net.minus(line.amount());
            revision[0]++;
        }
        return net;
    }

    // ------------------------------------------------------------------ the event and the audit

    /** {@code lending.LoanWrittenOff}: ids and codes only - never an amount, a reason or a party. */
    private void publish(Connection unitOfWork, Loan loan, UUID writeOff, JournalEntryId entry, LocalDate valueDate,
            int agreementVersion, CorrelationId correlation) {
        EventPayload payload = EventPayload.of()
                .with("loanId", loan.id().value().toString())
                .with("writeOffId", writeOff.toString())
                .with("product", loan.product().name())
                .with("journalEntryId", entry.value().toString())
                .with("valueDate", valueDate.toString())
                .with("agreementVersion", Integer.toString(agreementVersion));
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        WRITTEN_OFF_EVENT,
                        LoanTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        loan.id(),
                        LoanAcceptances.LOAN_AGGREGATE_TYPE,
                        clock.instant(),
                        LoanTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    private void audit(Connection unitOfWork, Actor actor, LendingAuditAction action, UUID writeOff, String reason, String summary,
            CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, TARGET_TYPE,
                writeOff.toString(), Optional.of(reason), AuditOutcome.SUCCEEDED, correlation, Optional.of(summary)));
    }

    private void auditRead(Connection unitOfWork, Actor actor, String targetType, String target, String summary,
            CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), LendingAuditAction.WRITE_OFF_READ,
                targetType, target, Optional.empty(), AuditOutcome.SUCCEEDED, correlation, Optional.of(summary)));
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** No such loan, or no such write-off of it. */
    public static final class NotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NotFound() {
            super("no such loan account or write-off");
        }
    }

    /** A refusal of an act on a loan of a known product - the product tags its meter. */
    public abstract static class Refused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final LoanProduct product;

        Refused(LoanProduct product, String message) {
            super(message);
            this.product = Objects.requireNonNull(product, "product");
        }

        /** The loan's product. */
        public LoanProduct product() {
            return product;
        }
    }

    /** The account is closed or cancelled, or not yet disbursed: nothing on it is written off (a proposal may still be rejected). */
    public static final class NotInServicing extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NotInServicing(LoanProduct product) {
            super(product, "the account is not in servicing: nothing on it is written off");
        }
    }

    /** The account is not defaulted: only a defaulted account is written off. */
    public static final class NotDefaulted extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NotDefaulted(LoanProduct product) {
            super(product, "the account is not defaulted: only a defaulted account is written off");
        }
    }

    /** The account holds a credit balance: its next servicing step applies it, which moves the figures. */
    public static final class CreditHeld extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CreditHeld(LoanProduct product) {
            super(product, "the account holds a credit balance: its next servicing step applies it to what is due");
        }
    }

    /** An elapsed date is not accrued yet, or the record is ahead of the clock: its next servicing step moves the figures. */
    public static final class BehindAccrual extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        BehindAccrual(LoanProduct product) {
            super(product, "the account's elapsed days are not accrued yet: its next servicing step brings them, then propose");
        }
    }

    /** What the approver named, the proposal and the ledger are not one state: nothing posted, the proposal standing. */
    public static final class Stale extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        Stale(LoanProduct product) {
            super(product, "the account has moved since the proposal, or the figures named are not the proposal's: reject it and"
                    + " propose again");
        }
    }

    /** A write-off of the account already awaits a decision. */
    public static final class ProposalPending extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProposalPending(LoanProduct product) {
            super(product, "a write-off of this account already awaits a decision");
        }
    }

    /** The write-off is decided - by somebody else, or differently. */
    public static final class ProposalNotPending extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProposalNotPending(LoanProduct product, LoanWriteOffStatus status) {
            super(product, "the write-off is no longer awaiting a decision: it is " + status.name());
        }
    }

    /** The approver proposed the write-off ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(LoanProduct product, LoanId loan, UUID writeOff) {
            super(product, "a write-off is approved by someone other than its proposer");
            this.target = writeOff.toString();
            this.summary = "loan=" + loan.value() + ", writeOff=" + writeOff + ", act=write-off:approve";
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
