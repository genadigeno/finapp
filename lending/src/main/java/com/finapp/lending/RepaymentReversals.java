package com.finapp.lending;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.creditmath.AllocationLine;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountNotPostableException;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PurposeBalances;
import com.finapp.ledger.ReversalCommand;
import com.finapp.ledger.ReversalService;
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
import java.time.Instant;
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
 * Repayment reversal under four eyes - T-rev (`P11-TSK-020`; {@code PHASE_11_PLAN.md} sections 7.1, 7.2, 7.4 scenario 6,
 * 11, 12.5 and 12.8 rule 10; ADR-0099 points 1, 2 and 4; {@code INV-LND-11}, {@code INV-REV-01}, {@code INV-REV-02},
 * {@code INV-AUD-04}, {@code INV-HIST-01}, {@code INV-CRD-09}, {@code INV-LND-13}). An operator's mistake in applying money
 * is undone by an EXACT reversal of the repayment's own entry - never an edit: a holder of {@code LOAN_SERVICE} proposes it
 * with a reason; a DIFFERENT holder of {@code LOAN_SERVICE_APPROVE} approves it; anyone with {@code LOAN_SERVICE}, the
 * proposer included, rejects it. Each act is ONE transaction on the caller's unit of work, which the door's idempotency
 * claim ({@code lending.servicing:EMPLOYEE:<id>}) wraps; every refusal is thrown before any write.
 *
 * <h2>The approval, in the lock order (L0, L3, L5, L6, L7, then the ledger)</h2>
 *
 * <ol>
 *   <li>the loan's party, read unlocked (a frozen column), and the actor's: an operator never services their own party's
 *       loan ({@link SelfDealingRefused} - the caller records it {@code FAILED} in a transaction of its own);
 *   <li><strong>(L0)</strong> the party's credit profile {@code FOR UPDATE} through credit's port - the reversal raises
 *       outstanding exposure by the principal it re-opens, so it and a credit decision for the party serialise
 *       ({@code INV-CRD-09}); always first;
 *   <li><strong>(L3)</strong> the loan {@code FOR UPDATE} - every repayment, servicing step and other reversal of the
 *       account waits here, and each re-derives after the other's commit;
 *   <li><strong>(L5)</strong> the proposal {@code FOR UPDATE}: {@code PROPOSED}, not the approver's own ({@link
 *       SelfApprovalRefused}; {@code lending V016}'s four-eyes {@code CHECK} beneath); the same approver's retry converges;
 *   <li>an {@code ACTIVE} loan ({@link LoanClosed} - a closed account is terminal, A12); the repayment's held credit still
 *       in the credit balance ({@link RepaymentNotReversible} - the exact swap must never drive it below zero; later
 *       allocations are never re-cut);
 *   <li><strong>(L6)</strong> when the repayment paid principal, the currency's capital row and headroom at least the
 *       principal re-instated ({@link CapitalUnavailable}, {@code INV-LND-13});
 *   <li><strong>(L7)</strong> the wallet the repayment debited - its ledger row {@code FOR UPDATE}, {@code ACTIVE}
 *       ({@link WalletUnavailable});
 *   <li>{@link ReversalService} of rule 5's entry - its lines with the directions swapped, exactly (rule 10), key
 *       {@code lending.repayment-reversal:<reversal>}, bounded by the original ({@code INV-REV-02}, namespace 2) - then
 *       only writes no other transaction waits on: the proposal {@code APPROVED} naming the entry, one negating row per
 *       allocation row, {@code lending.RepaymentReversed}, the audit record.
 * </ol>
 *
 * <p>The reversed amount simply becomes due again: the negating rows leave every later allocation standing and
 * {@code lending.billing_component_paid} subtracts them. Interest is not recomputed for the days between the repayment and
 * its reversal (ADR-0099 point 4, A10): the re-opened principal accrues from the reversal's value date.
 */
@RequiredArgsConstructor
public final class RepaymentReversals {

    /** The event an approval publishes. */
    public static final String REVERSED_EVENT = "lending.RepaymentReversed";

    /** Rule 10's key prefix: one entry per reversal. */
    public static final String REVERSAL_KEY_PREFIX = "lending.repayment-reversal:";

    /** The audit records' target type. */
    static final String TARGET_TYPE = "repayment_reversal";

    /** The newest reversals a loan's read answers. */
    public static final int HISTORY_LIMIT = 100;

    @NonNull private final LoanStore loans;
    @NonNull private final RepaymentStore repayments;
    @NonNull private final RepaymentReversalStore reversals;
    @NonNull private final InstalmentBillingStore billings;
    @NonNull private final LoanTermsStore terms;
    @NonNull private final LoanCreditDecisions credit;
    @NonNull private final LendingCapitalHeadroom headroom;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final PurposeBalances<Connection> balances;
    @NonNull private final JournalEntryStore<Connection> journal;
    @NonNull private final ReversalService reversalService;
    @NonNull private final LoanActingParty actingParties;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** A decision's outcome; {@code replayed} when the same person's retry converged on their own decision. */
    public record Decided(RepaymentReversal reversal, boolean replayed) {

        public Decided {
            Objects.requireNonNull(reversal, "reversal must not be null");
        }
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes the reversal of the loan's repayment {@code repayment}, with {@code reason}: under the loan's row lock, an
     * {@code ACTIVE} loan, the loan's own repayment, no live proposal for it, its held credit still held; born
     * {@code PROPOSED} with the reasoned audit record. Nothing is posted.
     *
     * @throws LendingCapitalAdministration.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws LoanNotFound no such loan, or no such repayment of it
     * @throws SelfDealingRefused the loan is the actor's own party's
     * @throws LoanClosed the loan is not {@code ACTIVE}
     * @throws ProposalPending a proposal for the repayment awaits a decision
     * @throws RepaymentNotReversible the repayment is already reversed, its held credit has since been applied, or it is a
     *     prepayment (`P11-TSK-028` - it moved the agreement; a correction is an amendment)
     */
    public RepaymentReversal propose(Connection unitOfWork, LoanId loan, UUID repayment, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(repayment, "repayment");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = LendingCapitalAdministration.reasoned(reason);
        notSelfDealing(unitOfWork, loan, actor, "propose");
        // (L3) The loan: every act on the account, and every proposal for any of its repayments, serialises here.
        LoanStore.Owned locked = loans.lock(unitOfWork, loan).orElseThrow(LoanNotFound::new);
        Repayment reversed = repayments.findById(unitOfWork, loan, repayment).orElseThrow(LoanNotFound::new);
        if (locked.loan().status() != LoanStatus.ACTIVE) {
            throw new LoanClosed();
        }
        if (reversed.kind() == RepaymentKind.PREPAYMENT) {
            // P11-TSK-028: a prepayment moved the agreement and the schedule - swapping its entry would leave the schedule
            // amortising less principal than the loan owes. Its correction is an amendment (lending V024 refuses it too).
            throw new RepaymentNotReversible("a prepayment moved the agreement; its correction is an amendment");
        }
        Optional<RepaymentReversal> live = reversals.live(unitOfWork, repayment);
        if (live.isPresent()) {
            if (live.get().status() == RepaymentReversalStatus.APPROVED) {
                throw new RepaymentNotReversible("the repayment is already reversed");
            }
            throw new ProposalPending();
        }
        heldCreditStillHeld(unitOfWork, locked, reversed);
        UUID id = ids.next();
        Instant proposedAt = reversals.insert(unitOfWork, new RepaymentReversalStore.NewProposal(id, loan, repayment,
                        reversed.agreementVersion(), actor, reasoned, correlation))
                .orElseThrow(ProposalPending::new);
        audit(unitOfWork, actor, LendingAuditAction.REPAYMENT_REVERSAL_PROPOSED, id, Optional.of(reasoned),
                AuditOutcome.SUCCEEDED, "loan=" + loan.value() + ", repayment=" + repayment, correlation);
        return new RepaymentReversal(id, loan, repayment, reversed.agreementVersion(), RepaymentReversalStatus.PROPOSED,
                actor.id(), proposedAt, reasoned, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    // ------------------------------------------------------------------ approve

    /**
     * Approves the proposal {@code reversal} of the loan's repayment {@code repayment} - a different person's act, posting
     * rule 10 in the lock order the class names.
     *
     * @throws LendingCapitalAdministration.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws LoanNotFound no such loan, or no such proposal for this repayment of it
     * @throws SelfDealingRefused the loan is the actor's own party's
     * @throws ProposalNotPending the proposal is decided - by somebody else, or differently
     * @throws SelfApprovalRefused the approver proposed it
     * @throws LoanClosed the loan is not {@code ACTIVE}
     * @throws RepaymentNotReversible the repayment's held credit has since been applied
     * @throws CapitalUnavailable the principal it re-instates exceeds the currency's capital headroom
     * @throws WalletUnavailable the wallet cannot be credited now
     */
    public Decided approve(Connection unitOfWork, LoanId loan, UUID repayment, UUID reversal, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(repayment, "repayment");
        Objects.requireNonNull(reversal, "reversal");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = LendingCapitalAdministration.reasoned(reason);
        UUID party = notSelfDealing(unitOfWork, loan, actor, "approve");
        // (L0) The party's credit profile, FIRST: the reversal raises outstanding exposure (INV-CRD-09).
        if (!credit.lockExposure(unitOfWork, party)) {
            throw new IllegalStateException("a loan's party has a credit profile - its decision opened the loan");
        }
        // (L3) The loan.
        LoanStore.Owned locked = loans.lock(unitOfWork, loan).orElseThrow(LoanNotFound::new);
        // (L5) The proposal, read under the loan's lock and locked itself: the decision every committed one left.
        RepaymentReversal proposal = lockedProposal(unitOfWork, loan, repayment, reversal);
        if (proposal.status() == RepaymentReversalStatus.APPROVED
                && proposal.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(proposal, true);
        }
        if (proposal.status() != RepaymentReversalStatus.PROPOSED) {
            throw new ProposalNotPending(proposal.status());
        }
        if (proposal.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused(loan, reversal);
        }
        Loan account = locked.loan();
        if (account.status() != LoanStatus.ACTIVE) {
            throw new LoanClosed();
        }
        Repayment reversed = repayments.findById(unitOfWork, loan, repayment)
                .orElseThrow(() -> new IllegalStateException("a reversal names a repayment of its loan (lending V016)"));
        heldCreditStillHeld(unitOfWork, locked, reversed);
        List<JournalLine> swapped = swapped(unitOfWork, reversed);
        LedgerAccountId wallet = walletOf(swapped);

        // The database clock, read after the locks, in the agreement's pinned zone: the reversal's value date.
        Instant now = reversals.databaseNow(unitOfWork);
        LoanTerms pinned = terms.version(unitOfWork, locked.agreement().termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"))
                .terms();
        LocalDate today = LocalDate.ofInstant(now, ZoneId.of(pinned.servicingZone()));
        // The value date: today, or - on a clock stepped back behind the record - the first date it leaves open (the Phase
        // 11 gate's R1): principal re-instated never joins a date already accrued, whose base read the principal without it.
        LocalDate valueDate = loans.principalValueDate(unitOfWork, loan, today);

        // (L6) The capital row, when principal is re-instated: headroom judged under it (INV-LND-13).
        Money reinstated = reversed.paid(AllocationComponent.PRINCIPAL)
                .plus(reversed.to(AllocationLine.Target.NOT_DUE_PRINCIPAL));
        if (reinstated.isPositive() && !headroom.underLock(unitOfWork, account.currency()).admits(reinstated)) {
            throw new CapitalUnavailable();
        }
        // (L7) The wallet the repayment debited: credited back only while it accepts postings.
        Optional<LedgerAccount> walletRow = ledgerAccounts.lockForUpdate(unitOfWork, wallet);
        if (walletRow.isEmpty() || walletRow.get().status() != LedgerAccountStatus.ACTIVE) {
            throw new WalletUnavailable();
        }

        // Rule 10: the exact swap of rule 5's entry, bounded by it (INV-REV-01, INV-REV-02).
        JournalEntryId entry = reverse(unitOfWork, reversal, reversed, swapped, loan, valueDate);
        if (!reversals.decide(unitOfWork, new RepaymentReversalStore.Decision(reversal, RepaymentReversalStatus.APPROVED,
                actor, reasoned, Optional.of(entry)))) {
            throw new IllegalStateException("a PROPOSED reversal locked under its loan did not move: the lock protocol was"
                    + " bypassed");
        }
        reversals.insertNegations(unitOfWork, reversal, reversed.allocations(), ids::next);
        RepaymentReversal approved = reversals.find(unitOfWork, reversal)
                .orElseThrow(() -> new IllegalStateException("a reversal decided in this transaction is readable in it"));
        publish(unitOfWork, account, approved, reversed, entry, valueDate, correlation);
        audit(unitOfWork, actor, LendingAuditAction.REPAYMENT_REVERSAL_APPROVED, reversal, Optional.of(reasoned),
                AuditOutcome.SUCCEEDED, "loan=" + loan.value() + ", repayment=" + repayment + ", entry=" + entry.value()
                        + ", reverses=" + reversed.journalEntry().value(), correlation);
        return new Decided(approved, false);
    }

    // ------------------------------------------------------------------ reject

    /**
     * Rejects the proposal {@code reversal} - anyone with {@code LOAN_SERVICE}, the proposer's withdrawal included; on a
     * closed loan too, so a proposal left standing can be cleared. Terminal; nothing is posted.
     *
     * @throws LendingCapitalAdministration.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws LoanNotFound no such loan, or no such proposal for this repayment of it
     * @throws SelfDealingRefused the loan is the actor's own party's
     * @throws ProposalNotPending the proposal is decided - by somebody else, or differently
     */
    public Decided reject(Connection unitOfWork, LoanId loan, UUID repayment, UUID reversal, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(repayment, "repayment");
        Objects.requireNonNull(reversal, "reversal");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = LendingCapitalAdministration.reasoned(reason);
        notSelfDealing(unitOfWork, loan, actor, "reject");
        loans.lock(unitOfWork, loan).orElseThrow(LoanNotFound::new);
        RepaymentReversal proposal = lockedProposal(unitOfWork, loan, repayment, reversal);
        if (proposal.status() == RepaymentReversalStatus.REJECTED
                && proposal.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(proposal, true);
        }
        if (proposal.status() != RepaymentReversalStatus.PROPOSED) {
            throw new ProposalNotPending(proposal.status());
        }
        if (!reversals.decide(unitOfWork, new RepaymentReversalStore.Decision(reversal, RepaymentReversalStatus.REJECTED,
                actor, reasoned, Optional.empty()))) {
            throw new IllegalStateException("a PROPOSED reversal locked under its loan did not move: the lock protocol was"
                    + " bypassed");
        }
        RepaymentReversal rejected = reversals.find(unitOfWork, reversal)
                .orElseThrow(() -> new IllegalStateException("a reversal decided in this transaction is readable in it"));
        audit(unitOfWork, actor, LendingAuditAction.REPAYMENT_REVERSAL_REJECTED, reversal, Optional.of(reasoned),
                AuditOutcome.SUCCEEDED, "loan=" + loan.value() + ", repayment=" + repayment + ", byProposer="
                        + proposal.proposedBy().equals(actor.id()), correlation);
        return new Decided(rejected, false);
    }

    /**
     * Records a refused self-dealing attempt {@code FAILED} ({@link LendingAuditAction#SELF_DEALING_REFUSED}) on
     * {@code unitOfWork} - the caller's own transaction, after the act's rolled back, so the attempt is kept.
     */
    public void recordRefusal(Connection unitOfWork, SelfDealingRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                LendingAuditAction.SELF_DEALING_REFUSED, "loan", refused.loan().value().toString(), Optional.empty(),
                AuditOutcome.FAILED, correlation, Optional.of("loan=" + refused.loan().value() + ", act=repayment-reversal:"
                        + refused.act() + " refused - the loan is the acting operator's own party's")));
    }

    /**
     * Records a refused self-approval {@code FAILED} ({@link LendingAuditAction#SELF_APPROVAL_REFUSED}) on
     * {@code unitOfWork} - the caller's own transaction, after the act rolled back, so the attempt is kept (the Phase 11
     * gate's R4).
     */
    public void recordRefusal(Connection unitOfWork, SelfApprovalRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        SelfApprovalRefusals.record(audit, ids, clock, unitOfWork, actor, TARGET_TYPE, refused.target(), refused.summary(),
                correlation);
    }

    // ------------------------------------------------------------------ the judgements

    /**
     * Refuses an act by an operator whose party holds the loan - or one the platform cannot place in a party (fail
     * closed). The loan's party is a frozen column, read unlocked so L0 can come first; answers it.
     */
    private UUID notSelfDealing(Connection unitOfWork, LoanId loan, Actor actor, String act) {
        return LoanSelfDealing.judge(loans, actingParties, unitOfWork, loan, actor, act, LoanNotFound::new);
    }

    /** The proposal {@code FOR UPDATE} (L5), only as a proposal for this repayment of this loan. */
    private RepaymentReversal lockedProposal(Connection unitOfWork, LoanId loan, UUID repayment, UUID reversal) {
        return reversals.lock(unitOfWork, reversal)
                .filter(found -> found.loan().equals(loan) && found.repayment().equals(repayment))
                .orElseThrow(LoanNotFound::new);
    }

    /**
     * What the repayment held as the loan's credit balance must still be held: the exact swap debits it back, and a
     * credit balance never stands below zero - money it held that a later application spent is not re-cut.
     */
    private void heldCreditStillHeld(Connection unitOfWork, LoanStore.Owned locked, Repayment reversed) {
        Money held = reversed.to(AllocationLine.Target.CREDIT_BALANCE);
        if (!held.isPositive()) {
            return;
        }
        LoanReceivables receivables = new LoanReceivables(billings, ledgerAccounts, balances);
        Money zero = Money.ofPersisted(0, held.currency(), held.scale());
        Accounts accounts = receivables.accounts(unitOfWork, locked.loan().id(), locked.loan().currency());
        if (receivables.creditHeld(unitOfWork, accounts, zero).compareTo(held) < 0) {
            throw new RepaymentNotReversible("what the repayment held as credit has since been applied");
        }
    }

    /**
     * Rule 5's entry with every line's direction swapped - exactly, nothing recomputed - after holding it to the
     * repayment's own rows: one debit, the amount, from the wallet; the credits the allocation's per account.
     */
    private List<JournalLine> swapped(Connection unitOfWork, Repayment reversed) {
        JournalEntryStore.PostedEntry original = journal.findById(unitOfWork, reversed.journalEntry())
                .orElseThrow(() -> new IllegalStateException("a repayment names its posted entry"));
        List<JournalLine> lines = new ArrayList<>();
        Money debited = Money.ofPersisted(0, reversed.amount().currency(), reversed.amount().scale());
        Money credited = debited;
        int debits = 0;
        for (JournalLine line : original.entry().lines()) {
            if (line.direction() == Direction.DEBIT) {
                debited = debited.plus(line.amount());
                debits++;
            } else {
                credited = credited.plus(line.amount());
            }
            lines.add(new JournalLine(line.account(), line.direction().opposite(), line.amount()));
        }
        if (debits != 1 || !debited.equals(reversed.amount()) || !credited.equals(reversed.amount())) {
            throw new AllocationAnomaly("a repayment's entry is not rule 5's one wallet debit of its amount (INV-LND-01)");
        }
        return lines;
    }

    /** The wallet: the one account the swapped entry credits ({@code CUSTOMER_WALLET}, rule 5's debit). */
    private static LedgerAccountId walletOf(List<JournalLine> swapped) {
        return swapped.stream().filter(line -> line.direction() == Direction.CREDIT).findFirst()
                .orElseThrow(() -> new IllegalStateException("rule 5 debits the wallet")).account();
    }

    private JournalEntryId reverse(Connection unitOfWork, UUID reversal, Repayment reversed, List<JournalLine> lines,
            LoanId loan, LocalDate valueDate) {
        PostingResult result;
        try {
            result = reversalService.reverse(unitOfWork, new ReversalCommand(REVERSAL_KEY_PREFIX + reversal,
                    reversed.journalEntry(), valueDate, valueDate, loan.value().toString(), lines));
        } catch (LedgerAccountNotPostableException notPostable) {
            // The wallet stopped accepting postings between its lock and the append - the ledger's trigger, beneath.
            throw new WalletUnavailable();
        }
        if (result.replayed()) {
            throw new IllegalStateException("a reversal's entry exists for a reversal still PROPOSED (INV-IDEM-01)");
        }
        return result.entryId();
    }

    // ------------------------------------------------------------------ the event and the audit

    /**
     * {@code lending.RepaymentReversed} - the reversal, the repayment, both entries, the amount and what it re-opened per
     * component and the credit it took back, the value date and the agreement version; never the wallet or a reason.
     */
    private void publish(Connection unitOfWork, Loan loan, RepaymentReversal reversal, Repayment reversed,
            JournalEntryId entry, LocalDate valueDate, CorrelationId correlation) {
        Money amount = reversed.amount();
        EventPayload payload = EventPayload.of()
                .with("loanId", loan.id().value().toString())
                .with("reversalId", reversal.id().toString())
                .with("repaymentId", reversed.id().toString())
                .with("repaymentKind", reversed.kind().name())
                .with("journalEntryId", entry.value().toString())
                .with("reversedJournalEntryId", reversed.journalEntry().value().toString())
                .with("valueDate", valueDate.toString())
                .with("amountMinor", Long.toString(amount.minorUnits()))
                .with("feesReopenedMinor", Long.toString(reversed.paid(AllocationComponent.FEES).minorUnits()))
                .with("interestReopenedMinor", Long.toString(reversed.paid(AllocationComponent.INTEREST).minorUnits()))
                .with("principalReopenedMinor", Long.toString(reversed.paid(AllocationComponent.PRINCIPAL).minorUnits()))
                .with("principalReinstatedMinor",
                        Long.toString(reversed.to(AllocationLine.Target.NOT_DUE_PRINCIPAL).minorUnits()))
                .with("creditReversedMinor", Long.toString(reversed.to(AllocationLine.Target.CREDIT_BALANCE).minorUnits()))
                .with("scale", Integer.toString(amount.scale()))
                .with("currency", amount.currency().code())
                .with("agreementVersion", Integer.toString(reversal.agreementVersion()));
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        REVERSED_EVENT,
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

    private void audit(Connection unitOfWork, Actor actor, LendingAuditAction action, UUID reversal,
            Optional<String> reason, AuditOutcome outcome, String summary, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, TARGET_TYPE,
                reversal.toString(), reason, outcome, correlation, Optional.of(summary)));
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** No such loan, or no such repayment or proposal of it. */
    public static final class LoanNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        LoanNotFound() {
            super("no such loan account, repayment or reversal");
        }
    }

    /** The operator's own party holds the loan - or the operator is no identity the platform can place. */
    public static final class SelfDealingRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final transient LoanId loan;
        private final String act;

        SelfDealingRefused(LoanId loan, String act) {
            super("an employee never services a loan of their own party");
            this.loan = loan;
            this.act = act;
        }

        /** The loan acted on. */
        public LoanId loan() {
            return loan;
        }

        /** The act refused: propose, approve or reject. */
        public String act() {
            return act;
        }
    }

    /** The loan is closed (A12): no repayment of it is reversed. */
    public static final class LoanClosed extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        LoanClosed() {
            super("the loan is closed: no repayment of it is reversed");
        }
    }

    /** A proposal for the repayment already awaits a decision. */
    public static final class ProposalPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProposalPending() {
            super("a reversal of this repayment already awaits a decision");
        }
    }

    /** The proposal is decided - by somebody else, or differently. */
    public static final class ProposalNotPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProposalNotPending(RepaymentReversalStatus status) {
            super("the reversal is no longer awaiting a decision: it is " + status.name());
        }
    }

    /** The approver proposed the reversal ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(LoanId loan, UUID reversal) {
            super("a reversal is approved by someone other than its proposer; the proposer may reject it");
            this.target = reversal.toString();
            this.summary = "loan=" + loan.value() + ", reversal=" + reversal + ", act=repayment-reversal:approve";
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

    /** The repayment is already reversed, or its held credit has since been applied. */
    public static final class RepaymentNotReversible extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RepaymentNotReversible(String detail) {
            super(detail);
        }
    }

    /** The principal the reversal re-instates exceeds the currency's capital headroom ({@code INV-LND-13}). */
    public static final class CapitalUnavailable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CapitalUnavailable() {
            super("the principal the reversal re-instates exceeds the lending capital free");
        }
    }

    /** The wallet the repayment debited cannot be credited now. */
    public static final class WalletUnavailable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        WalletUnavailable() {
            super("the wallet cannot be credited now");
        }
    }
}
