package com.finapp.lending;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Fee and interest waivers under four eyes - T-wvr (`P11-TSK-025`; {@code PHASE_11_PLAN.md} sections 7.1, 7.2, 7.4, 11,
 * 12.3 and 12.8 rules 8, 8a and 9; A14, ADR-0099 points 1, 2 and 4; {@code INV-LND-11}, {@code INV-REV-02},
 * {@code INV-REV-04}, {@code INV-AUD-04}, {@code INV-LND-08}). Fees and interest are forgiven only by a reasoned act that
 * posts - never an edit: a holder of {@code LOAN_SERVICE} proposes the waiver of an amount with a reason; a DIFFERENT holder
 * of {@code LOAN_SERVICE_APPROVE} approves it; anyone with {@code LOAN_SERVICE}, the proposer included, rejects it. Each act
 * is ONE transaction on the caller's unit of work, which the door's idempotency claim
 * ({@code lending.servicing:<type>:<id>}) wraps; every refusal is thrown before any write.
 *
 * <h2>What each kind forgives, and its bound - derived under L3</h2>
 *
 * <ul>
 *   <li>{@link WaiverKind#FEE} - at most what the fee still owes (its item's {@code FEES}, billed less paid): rule 8,
 *       {@link ReversalService} of the assessment's rule 4 entry for the amount - {@code DR LOAN_FEE_INCOME / CR
 *       LOAN_FEES_DUE} - bounded by that entry as well ({@code INV-REV-02}, the ledger's namespace 2);
 *   <li>{@link WaiverKind#FEE_REFUND} - at most what was paid of the fee less what was refunded: rule 8a, {@code DR
 *       LOAN_FEE_INCOME / CR LOAN_CREDIT_BALANCE} under {@code lending.waiver:<waiver>} - the borrower's money, held as credit
 *       and applied or refunded like any other;
 *   <li>{@link WaiverKind#INTEREST} - at most what the billed item still owes of its interest: rule 9, {@code DR
 *       LOAN_INTEREST_INCOME / CR LOAN_INTEREST_DUE} under {@code lending.waiver:<waiver>}. Accrued interest not yet billed
 *       is never waived (a billing's interest is exactly its period's accruals, for every writer).
 * </ul>
 *
 * <p>Every bound is read from the rows {@link LoanReceivables} proves against the due accounts' ledger balances (a difference
 * is an anomaly, never waived over), at the proposal - a proposal above it is never born - and again at the approval, so a
 * repayment of the fee that committed between them makes the approval {@link WaiverExceedsDue}, nothing posted, the
 * proposal still {@code PROPOSED} (a refund of the paid part is the act that fits then). {@code lending V021}'s decision
 * trigger re-judges the bound for every writer under the same lock.
 *
 * <h2>The approval, in the lock order (L3, L5, L7, then the ledger)</h2>
 *
 * <ol>
 *   <li>the loan's party, read unlocked, and the actor's: never one's own party's loan ({@link
 *       RepaymentReversals.SelfDealingRefused} - the caller records it {@code FAILED} in a transaction of its own);
 *   <li><strong>(L3)</strong> the loan {@code FOR UPDATE}. No L0: a waiver moves no principal, so exposure does not move;
 *       no L6;
 *   <li><strong>(L5)</strong> the proposal, read under L3 - INSERT-only, so its decision is a born-once row beneath L3
 *       ({@code UNIQUE (waiver_id)}): {@code PROPOSED}, not the approver's own ({@link SelfApprovalRefused}; the four-eyes
 *       {@code CHECK} beneath); the same approver's retry converges;
 *   <li>an {@code ACTIVE} or {@code CLOSING} account ({@link LoanClosed}); the database clock after the locks; the bound;
 *   <li><strong>(L7)</strong> the borrower's existing wallet row {@code FOR UPDATE}, BEFORE any posting: a waiver that
 *       zeroes the last receivable closes the account and refunds its credit balance to the wallet in this transaction, and
 *       the posting's projection rows include a shared income account - a wallet taken after it could meet a disbursement
 *       holding the wallet and waiting on {@code LOAN_FEE_INCOME} (L7 before L8, the platform's order);
 *   <li>the posting (rule 8, 8a or 9), then only writes no other transaction waits on: the decision {@code APPROVED}
 *       naming the entry, the closure when the account is settled ({@link LoanClosures} - {@code REPAID}, or a
 *       {@code CLOSING} line {@code CLOSED_BY_CUSTOMER}), {@code lending.LoanFeeWaived} or {@code lending.LoanInterestWaived},
 *       the audit record.
 * </ol>
 */
@RequiredArgsConstructor
public final class Waivers {

    /** Rules 8a and 9's key prefix, and rule 8's within the ledger's reversal scope: one entry per waiver. */
    public static final String WAIVER_KEY_PREFIX = "lending.waiver:";

    /** The event an approved fee waiver or fee refund publishes. */
    public static final String FEE_WAIVED_EVENT = "lending.LoanFeeWaived";

    /** The event an approved interest waiver publishes. */
    public static final String INTEREST_WAIVED_EVENT = "lending.LoanInterestWaived";

    /** The audit records' target type. */
    static final String TARGET_TYPE = "waiver";

    /** The newest waivers a loan's read answers. */
    public static final int HISTORY_LIMIT = 100;

    @NonNull private final LoanStore loans;
    @NonNull private final WaiverStore waivers;
    @NonNull private final FeeAssessmentStore fees;
    @NonNull private final InstalmentBillingStore billings;
    @NonNull private final LoanTermsStore terms;
    @NonNull private final LoanWallets wallets;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final PurposeBalances<Connection> balances;
    @NonNull private final PostingService postings;
    @NonNull private final ReversalService reversalService;
    @NonNull private final LoanClosures closures;
    @NonNull private final LoanActingParty actingParties;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * A decision's outcome; {@code replayed} when the same person's retry converged on their own decision; the closure
     * when the approval zeroed the account's last receivable.
     */
    public record Decided(Waiver waiver, boolean replayed, Optional<LoanClosure> closure) {

        public Decided {
            Objects.requireNonNull(waiver, "waiver must not be null");
            Objects.requireNonNull(closure, "closure must not be null");
        }
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes waiving {@code amount} of {@code kind} on {@code target} - a fee assessment of the loan for {@code FEE} and
     * {@code FEE_REFUND}, a billed item of the loan for {@code INTEREST} - with {@code reason}: under the loan's row lock, an
     * {@code ACTIVE} or {@code CLOSING} account, within what is due now, no other proposal of the kind awaiting a decision
     * on the item; born {@code PROPOSED} with the reasoned audit record. Nothing is posted.
     *
     * @throws LendingCapitalAdministration.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws WaiverNotFound no such loan, or no such assessment or billed item of it
     * @throws RepaymentReversals.SelfDealingRefused the loan is the actor's own party's
     * @throws LoanClosed the account is not {@code ACTIVE} or {@code CLOSING}
     * @throws Repayments.AmountInvalid the amount is not positive, or not in the loan's currency and scale
     * @throws WaiverExceedsDue the amount exceeds what is due now
     * @throws ProposalPending a waiver of the kind on the item awaits a decision
     * @throws AllocationAnomaly the loan's rows do not explain its due accounts
     */
    public Waiver propose(Connection unitOfWork, LoanId loan, WaiverKind kind, UUID target, Money amount, String reason,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = LendingCapitalAdministration.reasoned(reason);
        LoanSelfDealing.judge(loans, actingParties, unitOfWork, loan, actor, "propose", WaiverNotFound::new);
        // (L3) The loan: every act on the account, and every proposal on any of its items, serialises here.
        LoanStore.Owned locked = loans.lock(unitOfWork, loan).orElseThrow(WaiverNotFound::new);
        Target resolved = target(unitOfWork, loan, kind, target);
        Loan account = locked.loan();
        if (!inServicing(account.status())) {
            throw new LoanClosed(kind);
        }
        Money principal = locked.agreement().amount();
        if (!amount.isPositive() || !amount.currency().equals(principal.currency())
                || amount.scale() != principal.scale()) {
            throw new Repayments.AmountInvalid();
        }
        if (amount.compareTo(room(unitOfWork, account, kind, resolved, principal)) > 0) {
            throw new WaiverExceedsDue(kind);
        }
        if (waivers.pending(unitOfWork, loan, kind, resolved.item())) {
            throw new ProposalPending(kind);
        }
        UUID id = ids.next();
        int version = locked.agreement().version();
        Optional<UUID> assessment = resolved.assessment().map(FeeAssessment::id);
        Instant proposedAt = waivers.insert(unitOfWork, new WaiverStore.NewWaiver(id, loan, kind, assessment,
                resolved.item(), amount, version, actor, reasoned, correlation));
        audit(unitOfWork, actor, LendingAuditAction.WAIVER_PROPOSED, id, reasoned,
                "loan=" + loan.value() + ", kind=" + kind.name() + ", item=" + resolved.item().value()
                        + assessment.map(a -> ", assessment=" + a).orElse(""), correlation);
        return new Waiver(id, loan, kind, assessment, resolved.item(), amount, version, WaiverStatus.PROPOSED, actor.id(),
                proposedAt, reasoned, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    // ------------------------------------------------------------------ approve

    /**
     * Approves the proposal {@code waiver} of the loan - a different person's act, posting rule 8, 8a or 9 in the lock order
     * the class names.
     *
     * @throws LendingCapitalAdministration.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws WaiverNotFound no such loan, or no such waiver of it
     * @throws RepaymentReversals.SelfDealingRefused the loan is the actor's own party's
     * @throws ProposalNotPending the waiver is decided - by somebody else, or differently
     * @throws SelfApprovalRefused the approver proposed it
     * @throws LoanClosed the account is not {@code ACTIVE} or {@code CLOSING}
     * @throws WaiverExceedsDue the amount exceeds what is due now - nothing posted, the proposal still {@code PROPOSED}
     * @throws AllocationAnomaly the loan's rows do not explain its due accounts
     */
    public Decided approve(Connection unitOfWork, LoanId loan, UUID waiver, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(waiver, "waiver");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = LendingCapitalAdministration.reasoned(reason);
        UUID party = LoanSelfDealing.judge(loans, actingParties, unitOfWork, loan, actor, "approve", WaiverNotFound::new);
        // (L3) The loan.
        LoanStore.Owned locked = loans.lock(unitOfWork, loan).orElseThrow(WaiverNotFound::new);
        // (L5) The proposal, read under the loan's lock: the decision every committed act left.
        Waiver proposal = waivers.find(unitOfWork, loan, waiver).orElseThrow(WaiverNotFound::new);
        if (proposal.status() == WaiverStatus.APPROVED && proposal.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(proposal, true, Optional.empty());
        }
        if (proposal.status() != WaiverStatus.PROPOSED) {
            throw new ProposalNotPending(proposal.kind(), proposal.status());
        }
        if (proposal.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused(proposal.kind());
        }
        Loan account = locked.loan();
        if (!inServicing(account.status())) {
            throw new LoanClosed(proposal.kind());
        }
        // The database clock, read after the locks, in the agreement's pinned zone: the entry's value date.
        Instant now = waivers.databaseNow(unitOfWork);
        LoanTerms pinned = terms.version(unitOfWork, locked.agreement().termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"))
                .terms();
        ZoneId zone = ZoneId.of(pinned.servicingZone());
        LocalDate today = LocalDate.ofInstant(now, zone);

        Target resolved = target(unitOfWork, loan, proposal.kind(), proposal.assessment().orElse(proposal.dueItem().value()));
        Money principal = locked.agreement().amount();
        Accounts accounts = new LoanReceivables(billings, ledgerAccounts, balances)
                .accounts(unitOfWork, loan, account.currency());
        if (proposal.amount().compareTo(room(unitOfWork, account, proposal.kind(), resolved, principal)) > 0) {
            throw new WaiverExceedsDue(proposal.kind());
        }
        // (L7) The borrower's existing wallet, before any posting: the closure's refund never takes it after L8.
        wallets.existingWallet(unitOfWork, party, account.currency())
                .ifPresent(wallet -> ledgerAccounts.lockForUpdate(unitOfWork, wallet));

        JournalEntryId entry = post(unitOfWork, account, proposal, resolved, accounts, today);
        if (!waivers.decide(unitOfWork, new WaiverStore.Decision(ids.next(), waiver, proposal.proposedBy(),
                WaiverStatus.APPROVED, actor, reasoned, Optional.of(entry), correlation))) {
            throw new IllegalStateException("a PROPOSED waiver read under its loan's lock was decided: the lock protocol was"
                    + " bypassed");
        }
        Optional<LoanClosure> closure = closures.closeIfSettled(unitOfWork, account, locked.agreement(), zone, today,
                Optional.empty(), () -> actor, correlation);
        Waiver approved = waivers.find(unitOfWork, loan, waiver)
                .orElseThrow(() -> new IllegalStateException("a waiver decided in this transaction is readable in it"));
        publish(unitOfWork, account, approved, entry, today, correlation);
        audit(unitOfWork, actor, LendingAuditAction.WAIVER_APPROVED, waiver, reasoned,
                "loan=" + loan.value() + ", kind=" + proposal.kind().name() + ", item=" + proposal.dueItem().value()
                        + ", entry=" + entry.value() + closure.map(c -> ", closed=" + c.reason().name()).orElse(""),
                correlation);
        return new Decided(approved, false, closure);
    }

    // ------------------------------------------------------------------ reject

    /**
     * Rejects the proposal {@code waiver} - anyone with {@code LOAN_SERVICE}, the proposer's withdrawal included; on a closed
     * account too, so a proposal left standing can be cleared. Terminal; nothing is posted.
     *
     * @throws LendingCapitalAdministration.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws WaiverNotFound no such loan, or no such waiver of it
     * @throws RepaymentReversals.SelfDealingRefused the loan is the actor's own party's
     * @throws ProposalNotPending the waiver is decided - by somebody else, or differently
     */
    public Decided reject(Connection unitOfWork, LoanId loan, UUID waiver, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(waiver, "waiver");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = LendingCapitalAdministration.reasoned(reason);
        LoanSelfDealing.judge(loans, actingParties, unitOfWork, loan, actor, "reject", WaiverNotFound::new);
        loans.lock(unitOfWork, loan).orElseThrow(WaiverNotFound::new);
        Waiver proposal = waivers.find(unitOfWork, loan, waiver).orElseThrow(WaiverNotFound::new);
        if (proposal.status() == WaiverStatus.REJECTED && proposal.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(proposal, true, Optional.empty());
        }
        if (proposal.status() != WaiverStatus.PROPOSED) {
            throw new ProposalNotPending(proposal.kind(), proposal.status());
        }
        if (!waivers.decide(unitOfWork, new WaiverStore.Decision(ids.next(), waiver, proposal.proposedBy(),
                WaiverStatus.REJECTED, actor, reasoned, Optional.empty(), correlation))) {
            throw new IllegalStateException("a PROPOSED waiver read under its loan's lock was decided: the lock protocol was"
                    + " bypassed");
        }
        Waiver rejected = waivers.find(unitOfWork, loan, waiver)
                .orElseThrow(() -> new IllegalStateException("a waiver decided in this transaction is readable in it"));
        audit(unitOfWork, actor, LendingAuditAction.WAIVER_REJECTED, waiver, reasoned,
                "loan=" + loan.value() + ", kind=" + proposal.kind().name() + ", byProposer="
                        + proposal.proposedBy().equals(actor.id()), correlation);
        return new Decided(rejected, false, Optional.empty());
    }

    /**
     * Records a refused self-dealing attempt {@code FAILED} ({@link LendingAuditAction#SELF_DEALING_REFUSED}) on
     * {@code unitOfWork} - the caller's own transaction, after the act's rolled back, so the attempt is kept.
     */
    public void recordRefusal(Connection unitOfWork, RepaymentReversals.SelfDealingRefused refused, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                LendingAuditAction.SELF_DEALING_REFUSED, "loan", refused.loan().value().toString(), Optional.empty(),
                AuditOutcome.FAILED, correlation, Optional.of("loan=" + refused.loan().value() + ", act=waiver:"
                        + refused.act() + " refused - the loan is the acting operator's own party's")));
    }

    // ------------------------------------------------------------------ the judgements

    private static boolean inServicing(LoanStatus status) {
        return status == LoanStatus.ACTIVE || status == LoanStatus.CLOSING;
    }

    /** What a waiver names: the assessment (a fee's kinds) and the billed item. */
    private record Target(Optional<FeeAssessment> assessment, DueItemId item) {}

    /** The target of the loan's own - an assessment for a fee's kinds, a billed item for interest - or not found. */
    private Target target(Connection unitOfWork, LoanId loan, WaiverKind kind, UUID target) {
        if (kind == WaiverKind.INTEREST) {
            // Matched by value: an identifier no billed item of the loan carries - another loan's, or not even a UUIDv7 -
            // names nothing.
            DueItemId item = billings.dueItems(unitOfWork, loan).stream().map(DueState.DueItem::id)
                    .filter(id -> id.value().equals(target)).findFirst().orElseThrow(WaiverNotFound::new);
            return new Target(Optional.empty(), item);
        }
        FeeAssessment assessment = fees.find(unitOfWork, loan, target).orElseThrow(WaiverNotFound::new);
        return new Target(Optional.of(assessment), assessment.dueItem());
    }

    /**
     * What may be waived of {@code kind} on {@code target} now - from the rows {@link LoanReceivables} proves against the due
     * accounts' ledger balances, under the caller's L3: a fee's unpaid remainder, the paid part of a fee less what was
     * refunded, or the item's unpaid billed interest.
     */
    private Money room(Connection unitOfWork, Loan account, WaiverKind kind, Target target, Money principal) {
        Money zero = Money.ofPersisted(0, principal.currency(), principal.scale());
        LoanReceivables receivables = new LoanReceivables(billings, ledgerAccounts, balances);
        DueState due = receivables.dueState(unitOfWork, account.id(),
                receivables.accounts(unitOfWork, account.id(), account.currency()), zero);
        Optional<DueState.DueItem> item = due.items().stream().filter(i -> i.id().equals(target.item())).findFirst();
        return switch (kind) {
            case FEE -> item.map(DueState.DueItem::fees).orElse(zero);
            case INTEREST -> item.map(DueState.DueItem::interest).orElse(zero);
            case FEE_REFUND -> {
                FeeAssessment assessment = target.assessment().orElseThrow();
                Money waived = zero;
                Money refunded = zero;
                for (Waiver earlier : waivers.ofAssessment(unitOfWork, assessment.id())) {
                    if (earlier.status() == WaiverStatus.APPROVED) {
                        if (earlier.kind() == WaiverKind.FEE) {
                            waived = waived.plus(earlier.amount());
                        } else if (earlier.kind() == WaiverKind.FEE_REFUND) {
                            refunded = refunded.plus(earlier.amount());
                        }
                    }
                }
                // One fee per item and kind: the item's FEES are this assessment's - billed (assessed less waived) less
                // what is still due is what was paid of it.
                Money paid = assessment.amount().minus(waived).minus(item.map(DueState.DueItem::fees).orElse(zero));
                yield paid.minus(refunded);
            }
        };
    }

    // ------------------------------------------------------------------ the postings

    /** Rule 8, 8a or 9 for the waiver's amount - one entry under the waiver's own key. */
    private JournalEntryId post(Connection unitOfWork, Loan account, Waiver waiver, Target target, Accounts accounts,
            LocalDate today) {
        Money amount = waiver.amount();
        String key = WAIVER_KEY_PREFIX + waiver.id();
        String reference = account.id().value().toString();
        PostingResult result = switch (waiver.kind()) {
            // Rule 8: the assessment's own entry reversed for the amount - bounded by it (INV-REV-02).
            case FEE -> reversalService.reverse(unitOfWork, new ReversalCommand(key,
                    target.assessment().orElseThrow().entry(), today, today, reference, List.of(
                            new JournalLine(operational(unitOfWork, AccountPurpose.LOAN_FEE_INCOME, account),
                                    Direction.DEBIT, amount),
                            new JournalLine(accounts.feesDue(), Direction.CREDIT, amount))));
            // Rule 8a: the fee already paid is owed back - held as the borrower's credit.
            case FEE_REFUND -> postings.post(unitOfWork, new PostingCommand(key, today, today, reference, List.of(
                    new JournalLine(operational(unitOfWork, AccountPurpose.LOAN_FEE_INCOME, account), Direction.DEBIT,
                            amount),
                    new JournalLine(accounts.creditBalance(), Direction.CREDIT, amount))));
            // Rule 9: income forgone, the billed claim released.
            case INTEREST -> postings.post(unitOfWork, new PostingCommand(key, today, today, reference, List.of(
                    new JournalLine(operational(unitOfWork, AccountPurpose.LOAN_INTEREST_INCOME, account),
                            Direction.DEBIT, amount),
                    new JournalLine(accounts.interestDue(), Direction.CREDIT, amount))));
        };
        if (result.replayed()) {
            throw new IllegalStateException("a waiver's entry exists for a waiver still PROPOSED (INV-IDEM-01)");
        }
        return result.entryId();
    }

    private LedgerAccountId operational(Connection unitOfWork, AccountPurpose purpose, Loan account) {
        return ledgerAccounts.findOperational(unitOfWork, purpose, account.currency())
                .orElseThrow(() -> new IllegalStateException("ledger V026 seeds the lending income accounts in every"
                        + " supported currency"))
                .id();
    }

    // ------------------------------------------------------------------ the event and the audit

    /**
     * {@code lending.LoanFeeWaived} (a fee waived or refunded) or {@code lending.LoanInterestWaived} - the waiver, its kind,
     * the assessment and item, the amount, the entry, the value date and the agreement version; never a reason or a party.
     */
    private void publish(Connection unitOfWork, Loan loan, Waiver waiver, JournalEntryId entry, LocalDate today,
            CorrelationId correlation) {
        Money amount = waiver.amount();
        EventPayload payload = EventPayload.of()
                .with("loanId", loan.id().value().toString())
                .with("waiverId", waiver.id().toString())
                .with("kind", waiver.kind().name())
                .with("dueItemId", waiver.dueItem().value().toString());
        if (waiver.assessment().isPresent()) {
            payload = payload.with("assessmentId", waiver.assessment().get().toString());
        }
        payload = payload
                .with("amountMinor", Long.toString(amount.minorUnits()))
                .with("scale", Integer.toString(amount.scale()))
                .with("currency", amount.currency().code())
                .with("journalEntryId", entry.value().toString())
                .with("valueDate", today.toString())
                .with("agreementVersion", Integer.toString(waiver.agreementVersion()));
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        waiver.kind() == WaiverKind.INTEREST ? INTEREST_WAIVED_EVENT : FEE_WAIVED_EVENT,
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

    private void audit(Connection unitOfWork, Actor actor, LendingAuditAction action, UUID waiver, String reason,
            String summary, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, TARGET_TYPE,
                waiver.toString(), Optional.of(reason), AuditOutcome.SUCCEEDED, correlation, Optional.of(summary)));
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** No such loan, or no such assessment, billed item or waiver of it. */
    public static final class WaiverNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        WaiverNotFound() {
            super("no such loan account, fee assessment, billed item or waiver");
        }
    }

    /** A refusal of an act on a waiver of a known kind - the kind tags its meter. */
    abstract static class KindedRefusal extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final WaiverKind kind;

        KindedRefusal(WaiverKind kind, String message) {
            super(message);
            this.kind = Objects.requireNonNull(kind, "kind");
        }

        /** The kind of the waiver acted on. */
        public WaiverKind kind() {
            return kind;
        }
    }

    /** The account is closed or cancelled: nothing on it is waived (a proposal may still be rejected). */
    public static final class LoanClosed extends KindedRefusal {
        @java.io.Serial private static final long serialVersionUID = 1L;

        LoanClosed(WaiverKind kind) {
            super(kind, "the account is closed: nothing on it is waived");
        }
    }

    /** The waiver exceeds what is due now ({@code INV-REV-02}). */
    public static final class WaiverExceedsDue extends KindedRefusal {
        @java.io.Serial private static final long serialVersionUID = 1L;

        WaiverExceedsDue(WaiverKind kind) {
            super(kind, "the waiver exceeds what is due now");
        }
    }

    /** A waiver of the kind on the item already awaits a decision. */
    public static final class ProposalPending extends KindedRefusal {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProposalPending(WaiverKind kind) {
            super(kind, "a waiver of this kind on this item already awaits a decision");
        }
    }

    /** The waiver is decided - by somebody else, or differently. */
    public static final class ProposalNotPending extends KindedRefusal {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProposalNotPending(WaiverKind kind, WaiverStatus status) {
            super(kind, "the waiver is no longer awaiting a decision: it is " + status.name());
        }
    }

    /** The approver proposed the waiver ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends KindedRefusal {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SelfApprovalRefused(WaiverKind kind) {
            super(kind, "a waiver is approved by someone other than its proposer; the proposer may reject it");
        }
    }
}
