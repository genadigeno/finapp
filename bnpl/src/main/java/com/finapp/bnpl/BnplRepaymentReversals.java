package com.finapp.bnpl;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountNotPostableException;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.ReversalCommand;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Repayment reversal under four eyes - T-rev (`P12-TSK-024`; {@code PHASE_12_PLAN.md} sections 7.1, 7.2, 7.4, 12.11 F8 and
 * 13.2 scenario 12; {@code BNPL_LIFECYCLES.md} section 4.11; ADR-0099's shape - lending's {@code RepaymentReversals},
 * `P11-TSK-020`, mirrored; {@code INV-REV-01}, {@code INV-REV-02}, {@code INV-AUD-04}, {@code INV-HIST-01},
 * {@code INV-CRD-09}, {@code INV-BNPL-10}, {@code INV-LND-13} and {@code INV-LND-11} by analogy). An operator's mistake in
 * applying a customer's money is undone by an EXACT reversal of the repayment's own F3 entry - never an edit: a holder of
 * {@code BNPL_SERVICE} proposes it with a reason; a DIFFERENT holder of {@code BNPL_SERVICE_APPROVE} approves it; anyone
 * with {@code BNPL_SERVICE}, the proposer included, rejects it. Each act is ONE transaction on the caller's unit of work,
 * which the door's idempotency claim ({@code bnpl.servicing:<actor type>:<id>}) wraps; every refusal is thrown before any
 * write.
 *
 * <p>What can be reversed: a customer's repayment ({@code CUSTOMER}, `-018`), an automatic collection
 * ({@code AUTO_COLLECTION}, `-019`) and pay-in-4's instalment 1 at purchase ({@code AT_PURCHASE}) - each an ordinary
 * repayment, reversible while its obligation is {@code ACTIVE}. Never a {@code PAYOFF} (`-022`: it closed its obligation),
 * never a repayment of a {@code CLOSED} obligation - the closing repayment included (lending's A12; {@code INV-LIFE-04}:
 * nothing leaves {@code CLOSED}).
 *
 * <h2>The approval, in the Phase 12 lock order (L0, B4, B6, L6, (P), L8)</h2>
 *
 * <ol>
 *   <li>the agreement's party, read unlocked (a frozen column), and the actor's: an operator never services their own
 *       party's agreement, nor one the platform cannot place in a party ({@link SelfDealingRefused} - the caller records it
 *       {@code FAILED} in a transaction of its own);
 *   <li><strong>(L0)</strong> the party's credit profile {@code FOR UPDATE} through credit's port - the reversal raises
 *       outstanding exposure by the principal it re-opens, so it and a credit decision for the party serialise
 *       ({@code INV-CRD-09}); always first;
 *   <li><strong>(B4)</strong> the obligation {@code FOR UPDATE} - every repayment, collection, servicing step, refund and
 *       other correction of it waits here, and each re-derives after the other's commit;
 *   <li><strong>(B6)</strong> the proposal {@code FOR UPDATE}: {@code PROPOSED}, not the approver's own
 *       ({@link SelfApprovalRefused}; {@code bnpl V016}'s four-eyes {@code CHECK} beneath); the same approver's retry
 *       converges;
 *   <li>an {@code ACTIVE} obligation ({@link ObligationClosed}); the repayment's held credit still in the credit balance
 *       ({@link RepaymentNotReversible} - the exact swap must never drive it below zero; later allocations are never
 *       re-cut); F3's entry exactly the repayment's (one wallet debit of its amount; its credits its allocation's per
 *       account) - else an {@link AllocationAnomaly};
 *   <li>the database clock after the locks: posted today, valued {@code max(today, floor, the repayment's value date)} -
 *       the floor read under B4, so the principal re-instated never joins a date already accrued (the Phase 11 gate's R1),
 *       and never before the repayment it reverses, so no date counts the amount twice (the Phase 12 transition gate's
 *       A2-1);
 *   <li><strong>(L6)</strong> when the repayment paid principal, the currency's capital row and headroom at least the
 *       principal re-instated ({@link CapitalUnavailable}, {@code INV-LND-13}, {@code INV-BNPL-10});
 *   <li><strong>(P)</strong> the wallet the repayment debited - read from F3's entry, its ledger row {@code FOR UPDATE},
 *       {@code ACTIVE} ({@link WalletUnavailable}): the money returns to the wallet, so no balance can be short;
 *   <li><strong>(L8)</strong> the projection rows of every account the swap posts to, in the ledger's one order (no shared
 *       income row among them); then the ledger's {@code ReversalService} of F3 - its lines with the directions swapped,
 *       exactly, key {@code bnpl.repayment-reversal:<reversal>}, bounded by the original ({@code INV-REV-02}) - then only
 *       writes no other transaction waits on: the proposal {@code APPROVED} naming the entry and its value date, one
 *       negating row per allocation row, {@code bnpl.BnplRepaymentReversed}, the audit record.
 * </ol>
 *
 * <p>The reversed amount simply becomes due again: the negating rows leave every later allocation standing and
 * {@code bnpl.billing_component_paid} subtracts them, so the instalment is past due again from the next derivation and the
 * next servicing step recomputes DPD (and, if its age has reached the pinned day, assesses its late fee - the payment never
 * happened). Interest is not recomputed for the days between the repayment and its reversal (ADR-0099 point 4, lending's
 * A10): the re-opened principal accrues from the reversal's value date. No JVM state.
 */
@RequiredArgsConstructor
public final class BnplRepaymentReversals {

    /** The event an approval publishes. */
    public static final String REVERSED_EVENT = "bnpl.BnplRepaymentReversed";

    /** F8's key prefix, within the ledger's reversal scope: one entry per reversal. */
    public static final String REVERSAL_KEY_PREFIX = "bnpl.repayment-reversal:";

    /**
     * The key prefix of a reversal's release of the late fees it re-opened on instalments a refund cancelled whole (the
     * Phase 12 transition gate's A7-3): one entry per reversal, never F8's.
     */
    public static final String FEE_RELEASE_KEY_PREFIX = "bnpl.reversal-fee-release:";

    /** The audit records' target type. */
    static final String TARGET_TYPE = "bnpl_repayment_reversal";

    /** The newest reversals an agreement's read answers. */
    public static final int HISTORY_LIMIT = 100;

    @NonNull private final BnplCorrectionStore store;
    @NonNull private final BnplServicingStore servicing;
    @NonNull private final BnplPurchaseStore purchases;
    @NonNull private final BnplCreditDecisions credit;
    @NonNull private final BnplCapital capital;
    @NonNull private final BnplBook book;
    @NonNull private final BnplActingParty actingParties;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** A decision's outcome; {@code replayed} when the same person's retry converged on their own decision. */
    public record Decided(BnplRepaymentReversal reversal, boolean replayed) {

        public Decided {
            Objects.requireNonNull(reversal, "reversal must not be null");
        }
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes the reversal of the agreement's repayment {@code repayment}, with {@code reason}: under the obligation's row
     * lock, an {@code ACTIVE} obligation, the obligation's own repayment and never a payoff's, no live proposal for it, its
     * held credit still held; born {@code PROPOSED} with the reasoned audit record. Nothing is posted.
     *
     * @throws BnplReasons.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws NotFound no such agreement, or no such repayment of it
     * @throws SelfDealingRefused the agreement is the actor's own party's, or the actor has no party the platform knows
     * @throws ObligationClosed the obligation is not {@code ACTIVE}
     * @throws ProposalPending a proposal for the repayment awaits a decision
     * @throws RepaymentNotReversible the repayment is already reversed, a payoff's, or its held credit has since been applied
     */
    public BnplRepaymentReversal propose(Connection unitOfWork, BnplAgreementId agreement, UUID repayment, String reason,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(repayment, "repayment");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = BnplReasons.reasoned(reason);
        BnplCorrectionStore.Addressed addressed = notSelfDealing(unitOfWork, agreement, actor, "propose");
        // (B4) The obligation: every act on it, and every proposal for any of its repayments, serialises here.
        BnplServicingStore.Locked locked = servicing.lock(unitOfWork, addressed.obligation()).orElseThrow(NotFound::new);
        BnplPurchaseStore.Repayment reversed = store.repayment(unitOfWork, addressed.obligation(), repayment)
                .orElseThrow(NotFound::new);
        if (locked.obligation().status() != BnplObligationStatus.ACTIVE) {
            throw new ObligationClosed();
        }
        notAPayoff(reversed);
        Optional<BnplRepaymentReversal> live = store.liveReversal(unitOfWork, repayment);
        if (live.isPresent()) {
            if (live.get().status() == BnplCorrectionStatus.APPROVED) {
                throw new RepaymentNotReversible("the repayment is already reversed");
            }
            throw new ProposalPending();
        }
        heldCreditStillHeld(unitOfWork, locked, reversed);
        UUID id = ids.next();
        Instant proposedAt = store.insertReversal(unitOfWork, new BnplCorrectionStore.NewReversal(id,
                        addressed.obligation(), repayment, reversed.agreement(), reversed.agreementVersion(), actor,
                        reasoned, correlation))
                .orElseThrow(ProposalPending::new);
        audit(unitOfWork, actor, BnplAuditAction.REPAYMENT_REVERSAL_PROPOSED, id, reasoned,
                "agreement=" + agreement.value() + ", repayment=" + repayment, correlation);
        return new BnplRepaymentReversal(id, addressed.obligation(), repayment, reversed.agreement(),
                reversed.agreementVersion(), BnplCorrectionStatus.PROPOSED, actor.id(), proposedAt, reasoned,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    // ------------------------------------------------------------------ approve

    /**
     * Approves the proposal {@code reversal} of the agreement's repayment {@code repayment} - a different person's act,
     * posting F8 in the lock order the class names.
     *
     * @throws BnplReasons.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws NotFound no such agreement, or no such proposal for this repayment of it
     * @throws SelfDealingRefused the agreement is the actor's own party's, or the actor has no party the platform knows
     * @throws ProposalNotPending the proposal is decided - by somebody else, or differently
     * @throws SelfApprovalRefused the approver proposed it
     * @throws ObligationClosed the obligation is not {@code ACTIVE}
     * @throws RepaymentNotReversible the repayment's held credit has since been applied
     * @throws CapitalUnavailable the principal it re-instates exceeds the currency's capital headroom
     * @throws WalletUnavailable the wallet cannot be credited now
     * @throws AllocationAnomaly F3's entry is not the repayment's exactly
     */
    public Decided approve(Connection unitOfWork, BnplAgreementId agreement, UUID repayment, UUID reversal, String reason,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(repayment, "repayment");
        Objects.requireNonNull(reversal, "reversal");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = BnplReasons.reasoned(reason);
        BnplCorrectionStore.Addressed addressed = notSelfDealing(unitOfWork, agreement, actor, "approve");
        // (L0) The party's credit profile, FIRST: the reversal raises outstanding exposure (INV-CRD-09).
        if (!credit.lockExposure(unitOfWork, addressed.party())) {
            throw new IllegalStateException("a BNPL obligation's party has a credit profile - its decision opened it");
        }
        // (B4) The obligation.
        BnplServicingStore.Locked locked = servicing.lock(unitOfWork, addressed.obligation()).orElseThrow(NotFound::new);
        // (B6) The proposal, read under the obligation's lock and locked itself: the decision every committed one left.
        BnplRepaymentReversal proposal = lockedProposal(unitOfWork, addressed.obligation(), repayment, reversal);
        if (proposal.status() == BnplCorrectionStatus.APPROVED
                && proposal.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(proposal, true);
        }
        if (proposal.status() != BnplCorrectionStatus.PROPOSED) {
            throw new ProposalNotPending(proposal.status());
        }
        if (proposal.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused(agreement, reversal);
        }
        BnplObligation obligation = locked.obligation();
        if (obligation.status() != BnplObligationStatus.ACTIVE) {
            throw new ObligationClosed();
        }
        BnplPurchaseStore.Repayment reversed = store.repayment(unitOfWork, obligation.id(), repayment)
                .orElseThrow(() -> new IllegalStateException("a reversal names a repayment of its obligation (bnpl V016)"));
        notAPayoff(reversed);
        BnplLedgerAccounts accounts = heldCreditStillHeld(unitOfWork, locked, reversed);
        List<JournalLine> swapped = swapped(unitOfWork, reversed, accounts);
        LedgerAccountId wallet = walletOf(swapped);

        // The database clock, read after the locks, in the pinned zone: posted today, valued never before the floor - and
        // never before the repayment it reverses (the Phase 12 transition gate's A2-1): on a clock stepped back, a date
        // between the two would carry the re-opened amount twice (the repayment's credit not yet valued, the reversal's
        // debit already) and over-charge its day's pay-in-N interest, a figure the replay re-derives identically.
        Instant now = servicing.databaseNow(unitOfWork);
        LocalDate today = LocalDate.ofInstant(now, ZoneId.of(locked.servicingZone()));
        LocalDate floor = servicing.principalValueFloor(unitOfWork, obligation.id());
        LocalDate valueDate = valuedOn(today, floor, reversed.valueDate());

        // (L6) The capital row, when principal is re-instated: headroom judged under it (INV-LND-13, INV-BNPL-10).
        Money reinstated = paid(reversed, AllocationComponent.PRINCIPAL);
        if (reinstated.isPositive() && !capital.underLock(unitOfWork, obligation.currency()).admits(reinstated)) {
            throw new CapitalUnavailable();
        }
        // (P) The wallet the repayment debited: credited back only while it accepts postings.
        Optional<LedgerAccountStatus> walletStatus = book.lockForUpdate(unitOfWork, wallet);
        if (walletStatus.filter(LedgerAccountStatus.ACTIVE::equals).isEmpty()) {
            throw new WalletUnavailable();
        }
        // OQ-14 kept across the two orders (the Phase 12 transition gate's A7-3): the late fees the swap re-opens on an
        // instalment a refund cancelled whole - read under B4 - are released in this transaction, as that refund would have
        // released them had the payment never happened.
        Map<UUID, Money> released = feesReleased(reversed, store.wholeCancelledInstalments(unitOfWork, obligation.id()));
        Optional<LedgerAccountId> feeIncome = released.isEmpty() ? Optional.empty()
                : Optional.of(book.operational(unitOfWork, AccountPurpose.BNPL_FEE_INCOME, obligation.currency()));
        // (L8) Every projection row the swap posts to, in the ledger's one order - the wallet and the obligation's own; and
        // with a release, the obligation's fees-due row and the currency's BNPL_FEE_INCOME row in the same one call, after
        // (P) - no shared income row is ever held while waiting on a wallet.
        Set<LedgerAccountId> touched = new LinkedHashSet<>();
        swapped.forEach(line -> touched.add(line.account()));
        feeIncome.ifPresent(income -> {
            touched.add(accounts.feesDue().id());
            touched.add(income);
        });
        book.lockBalancesInOrder(unitOfWork, touched);

        // F8: the exact swap of F3's entry, bounded by it (INV-REV-01, INV-REV-02).
        JournalEntryId entry = reverse(unitOfWork, reversal, reversed, swapped, obligation, today, valueDate);
        if (!store.decideReversal(unitOfWork, new BnplCorrectionStore.ReversalDecision(reversal,
                BnplCorrectionStatus.APPROVED, actor, reasoned, Optional.of(entry), Optional.of(valueDate)))) {
            throw new IllegalStateException("a PROPOSED reversal locked under its obligation did not move: the lock"
                    + " protocol was bypassed");
        }
        store.insertNegations(unitOfWork, reversal, reversed.allocations(), ids::next);
        // F5a's lines, under an entry of the reversal's own - never F8's, which stays the exact swap.
        Optional<JournalEntryId> releaseEntry = feeIncome.map(income -> {
            Money total = released.values().stream().reduce(Money::plus).orElseThrow();
            JournalEntryId posted = book.post(unitOfWork, new PostingCommand(FEE_RELEASE_KEY_PREFIX + reversal, today,
                    valueDate, reversal.toString(), List.of(new JournalLine(income, Direction.DEBIT, total),
                            new JournalLine(accounts.feesDue().id(), Direction.CREDIT, total))));
            store.insertFeeReleases(unitOfWork, reversal, obligation.id(), posted, released, ids::next);
            return posted;
        });
        BnplRepaymentReversal approved = store.reversal(unitOfWork, reversal)
                .orElseThrow(() -> new IllegalStateException("a reversal decided in this transaction is readable in it"));
        publish(unitOfWork, locked, approved, reversed, entry, valueDate, released, correlation);
        audit(unitOfWork, actor, BnplAuditAction.REPAYMENT_REVERSAL_APPROVED, reversal, reasoned,
                "agreement=" + agreement.value() + ", repayment=" + repayment + ", entry=" + entry.value()
                        + ", reverses=" + reversed.entry().value()
                        + releaseEntry.map(posted -> ", feeRelease=" + posted.value()).orElse(""), correlation);
        return new Decided(approved, false);
    }

    /**
     * The late fees a reversal releases (OQ-14; the Phase 12 transition gate's A7-3), by instalment: what the reversed
     * repayment paid of the {@code FEES} of an instalment in {@code wholeCancelled} - an earlier refund cancelled it whole
     * and released its unpaid fee then, so the fee this payment covered is the one the swap makes due again. Exactly what is
     * due of its {@code FEES} once the negating rows stand ({@code bnpl V020}'s trigger holds it, for every writer).
     */
    static Map<UUID, Money> feesReleased(BnplPurchaseStore.Repayment reversed, Set<UUID> wholeCancelled) {
        Map<UUID, Money> released = new java.util.TreeMap<>();
        for (BnplPurchaseStore.Allocation line : reversed.allocations()) {
            if ("DUE_ITEM".equals(line.target()) && line.component().filter("FEES"::equals).isPresent()
                    && line.instalment().filter(wholeCancelled::contains).isPresent()) {
                released.merge(line.instalment().get(), line.amount(), Money::plus);
            }
        }
        return released;
    }

    // ------------------------------------------------------------------ reject

    /**
     * Rejects the proposal {@code reversal} - anyone with {@code BNPL_SERVICE}, the proposer's withdrawal included; on a
     * closed obligation too, so a proposal left standing can be cleared. Terminal; nothing is posted.
     *
     * @throws BnplReasons.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws NotFound no such agreement, or no such proposal for this repayment of it
     * @throws SelfDealingRefused the agreement is the actor's own party's, or the actor has no party the platform knows
     * @throws ProposalNotPending the proposal is decided - by somebody else, or differently
     */
    public Decided reject(Connection unitOfWork, BnplAgreementId agreement, UUID repayment, UUID reversal, String reason,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(repayment, "repayment");
        Objects.requireNonNull(reversal, "reversal");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = BnplReasons.reasoned(reason);
        BnplCorrectionStore.Addressed addressed = notSelfDealing(unitOfWork, agreement, actor, "reject");
        servicing.lock(unitOfWork, addressed.obligation()).orElseThrow(NotFound::new);
        BnplRepaymentReversal proposal = lockedProposal(unitOfWork, addressed.obligation(), repayment, reversal);
        if (proposal.status() == BnplCorrectionStatus.REJECTED
                && proposal.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(proposal, true);
        }
        if (proposal.status() != BnplCorrectionStatus.PROPOSED) {
            throw new ProposalNotPending(proposal.status());
        }
        if (!store.decideReversal(unitOfWork, new BnplCorrectionStore.ReversalDecision(reversal,
                BnplCorrectionStatus.REJECTED, actor, reasoned, Optional.empty(), Optional.empty()))) {
            throw new IllegalStateException("a PROPOSED reversal locked under its obligation did not move: the lock"
                    + " protocol was bypassed");
        }
        BnplRepaymentReversal rejected = store.reversal(unitOfWork, reversal)
                .orElseThrow(() -> new IllegalStateException("a reversal decided in this transaction is readable in it"));
        audit(unitOfWork, actor, BnplAuditAction.REPAYMENT_REVERSAL_REJECTED, reversal, reasoned,
                "agreement=" + agreement.value() + ", repayment=" + repayment + ", byProposer="
                        + proposal.proposedBy().equals(actor.id()), correlation);
        return new Decided(rejected, false);
    }

    /** The agreement's reversals, newest first - empty when no such agreement (the door's uniform 404 then). */
    public Optional<List<BnplRepaymentReversal>> ofAgreement(Connection unitOfWork, BnplAgreementId agreement) {
        Objects.requireNonNull(agreement, "agreement");
        return store.addressed(unitOfWork, agreement)
                .map(addressed -> store.reversalsOf(unitOfWork, addressed.obligation(), HISTORY_LIMIT));
    }

    /**
     * Which of {@code repayments} an approved reversal names - their derived {@code REVERSED} status (`P12-TSK-024`), for
     * the owner's repayment history; the repayment rows are INSERT-only, so the status is never a column.
     */
    public java.util.Set<UUID> reversedAmong(Connection unitOfWork, java.util.Collection<UUID> repayments) {
        Objects.requireNonNull(repayments, "repayments");
        return store.reversedAmong(unitOfWork, repayments);
    }

    // ------------------------------------------------------------------ the refusals' records

    /**
     * Records a refused self-dealing attempt {@code FAILED} ({@link BnplAuditAction#SELF_DEALING_REFUSED}) on
     * {@code unitOfWork} - the caller's own transaction, after the act rolled back, so the attempt is kept.
     */
    public void recordRefusal(Connection unitOfWork, SelfDealingRefused refused, Actor actor, CorrelationId correlation) {
        record(audit, ids, clock, unitOfWork, refused, actor, correlation);
    }

    /**
     * Records a refused self-approval {@code FAILED} ({@link BnplAuditAction#SELF_APPROVAL_REFUSED}) on
     * {@code unitOfWork} - the caller's own transaction, after the act rolled back, so the attempt is kept (the Phase 11
     * gate's R4).
     */
    public void recordRefusal(Connection unitOfWork, SelfApprovalRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        SelfApprovalRefusals.record(audit, ids, clock, unitOfWork, actor, TARGET_TYPE, refused.target(), refused.summary(),
                correlation);
    }

    /** One self-dealing record for both corrections - their target the agreement. */
    static void record(AuditWriter<Connection> audit, IdGenerator ids, Clock clock, Connection unitOfWork,
            SelfDealingRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(refused, "refused");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                BnplAuditAction.SELF_DEALING_REFUSED, "bnpl_agreement", refused.agreement().value().toString(),
                Optional.empty(), AuditOutcome.FAILED, correlation, Optional.of("agreement=" + refused.agreement().value()
                        + ", act=" + refused.act() + " refused - the agreement is the acting operator's own party's, or"
                        + " the operator has no party the platform knows")));
    }

    // ------------------------------------------------------------------ the judgements

    /**
     * Refuses an act by an operator whose party holds the agreement - or one the platform cannot place in a party (fail
     * closed). The agreement's party is a frozen column, read unlocked so L0 can come first; answers the addressing.
     */
    static BnplCorrectionStore.Addressed judgeSelfDealing(BnplCorrectionStore store, BnplActingParty actingParties,
            Connection unitOfWork, BnplAgreementId agreement, Actor actor, String act) {
        BnplCorrectionStore.Addressed addressed = store.addressed(unitOfWork, agreement).orElseThrow(NotFound::new);
        Optional<UUID> actorsParty = actingParties.partyOf(unitOfWork, actor);
        if (actorsParty.isEmpty() || actorsParty.get().equals(addressed.party())) {
            throw new SelfDealingRefused(agreement, act);
        }
        return addressed;
    }

    private BnplCorrectionStore.Addressed notSelfDealing(Connection unitOfWork, BnplAgreementId agreement, Actor actor,
            String act) {
        return judgeSelfDealing(store, actingParties, unitOfWork, agreement, actor, "repayment-reversal:" + act);
    }

    /** The proposal {@code FOR UPDATE} (B6), only as a proposal for this repayment of this obligation. */
    private BnplRepaymentReversal lockedProposal(Connection unitOfWork, BnplObligationId obligation, UUID repayment,
            UUID reversal) {
        return store.lockReversal(unitOfWork, reversal)
                .filter(found -> found.obligation().equals(obligation) && found.repayment().equals(repayment))
                .orElseThrow(NotFound::new);
    }

    /**
     * A reversal's value date: {@code max(today, floor, the reversed repayment's value date)} - never before the floor (the
     * Phase 11 gate's R1) and never before the repayment it undoes (the Phase 12 transition gate's A2-1); {@code bnpl V020}'s
     * edge trigger holds both for every writer.
     */
    static LocalDate valuedOn(LocalDate today, LocalDate floor, LocalDate original) {
        LocalDate valued = floor.isAfter(today) ? floor : today;
        return original.isAfter(valued) ? original : valued;
    }

    private static void notAPayoff(BnplPurchaseStore.Repayment reversed) {
        if (reversed.kind().equals(BnplPurchaseStore.Repayment.PAYOFF)) {
            // `P12-TSK-022`: a payoff closed its obligation; bnpl V016's trigger refuses it too.
            throw new RepaymentNotReversible("a payoff closed its agreement and is never reversed");
        }
    }

    /**
     * What the repayment held as the obligation's credit balance must still be held: the exact swap debits it back, and a
     * credit balance never stands below zero - money it held that a later application spent is not re-cut. Answers the
     * obligation's accounts.
     */
    private BnplLedgerAccounts heldCreditStillHeld(Connection unitOfWork, BnplServicingStore.Locked locked,
            BnplPurchaseStore.Repayment reversed) {
        BnplReceivables receivables = new BnplReceivables(purchases, book);
        BnplLedgerAccounts accounts = receivables.accounts(unitOfWork, locked.obligation().id());
        Money held = held(reversed);
        if (held.isPositive() && receivables.creditHeld(unitOfWork, accounts).compareTo(held) < 0) {
            throw new RepaymentNotReversible("what the repayment held as credit has since been applied");
        }
        return accounts;
    }

    /**
     * F3's entry with every line's direction swapped - exactly, nothing recomputed - after holding it to the repayment's
     * own rows: one debit, the amount, from the wallet; the credits the allocation's per account.
     */
    private List<JournalLine> swapped(Connection unitOfWork, BnplPurchaseStore.Repayment reversed,
            BnplLedgerAccounts accounts) {
        List<JournalLine> original = book.entryLines(unitOfWork, reversed.entry());
        Map<LedgerAccountId, Money> expected = new LinkedHashMap<>();
        Money zero = Money.ofPersisted(0, reversed.amount().currency(), reversed.amount().scale());
        for (BnplPurchaseStore.Allocation line : reversed.allocations()) {
            LedgerAccountId account = line.target().equals("DUE_ITEM")
                    ? BnplReceivables.dueAccount(accounts, AllocationComponent.valueOf(line.component().orElseThrow()))
                    : accounts.creditBalance().id();
            expected.merge(account, line.amount(), Money::plus);
        }
        Map<LedgerAccountId, Money> credited = new LinkedHashMap<>();
        Money debited = zero;
        int debits = 0;
        List<JournalLine> lines = new ArrayList<>();
        for (JournalLine line : original) {
            if (line.direction() == Direction.DEBIT) {
                debited = debited.plus(line.amount());
                debits++;
            } else {
                credited.merge(line.account(), line.amount(), Money::plus);
            }
            lines.add(new JournalLine(line.account(), line.direction().opposite(), line.amount()));
        }
        if (debits != 1 || !debited.equals(reversed.amount()) || !credited.equals(expected)) {
            throw new AllocationAnomaly("a repayment's entry is not F3's one wallet debit of its amount and its"
                    + " allocation's credits (INV-BNPL-01)");
        }
        return lines;
    }

    /** The wallet: the one account the swapped entry credits ({@code CUSTOMER_WALLET}, F3's debit). */
    private static LedgerAccountId walletOf(List<JournalLine> swapped) {
        return swapped.stream().filter(line -> line.direction() == Direction.CREDIT).findFirst()
                .orElseThrow(() -> new IllegalStateException("F3 debits the wallet")).account();
    }

    private JournalEntryId reverse(Connection unitOfWork, UUID reversal, BnplPurchaseStore.Repayment reversed,
            List<JournalLine> lines, BnplObligation obligation, LocalDate today, LocalDate valueDate) {
        try {
            return book.reverse(unitOfWork, new ReversalCommand(REVERSAL_KEY_PREFIX + reversal, reversed.entry(), today,
                    valueDate, obligation.id().value().toString(), lines));
        } catch (LedgerAccountNotPostableException notPostable) {
            // The wallet stopped accepting postings between its lock and the append - the ledger's trigger, beneath.
            throw new WalletUnavailable();
        }
    }

    /** What the repayment paid of {@code component} across its due-item lines. */
    static Money paid(BnplPurchaseStore.Repayment repayment, AllocationComponent component) {
        Money paid = Money.ofPersisted(0, repayment.amount().currency(), repayment.amount().scale());
        for (BnplPurchaseStore.Allocation line : repayment.allocations()) {
            if (line.target().equals("DUE_ITEM") && line.component().filter(component.name()::equals).isPresent()) {
                paid = paid.plus(line.amount());
            }
        }
        return paid;
    }

    /** What the repayment held as credit. */
    static Money held(BnplPurchaseStore.Repayment repayment) {
        Money held = Money.ofPersisted(0, repayment.amount().currency(), repayment.amount().scale());
        for (BnplPurchaseStore.Allocation line : repayment.allocations()) {
            if (line.target().equals("CREDIT_BALANCE")) {
                held = held.plus(line.amount());
            }
        }
        return held;
    }

    // ------------------------------------------------------------------ the event and the audit

    /**
     * {@code bnpl.BnplRepaymentReversed} - the reversal, the repayment, both entries, the amount and what it re-opened per
     * component and the credit it took back, the value date and the agreement version; never the wallet, a party or a reason.
     */
    private void publish(Connection unitOfWork, BnplServicingStore.Locked locked, BnplRepaymentReversal reversal,
            BnplPurchaseStore.Repayment reversed, JournalEntryId entry, LocalDate valueDate, Map<UUID, Money> released,
            CorrelationId correlation) {
        Money amount = reversed.amount();
        EventPayload payload = EventPayload.of()
                .with("obligationId", locked.obligation().id().value().toString())
                .with("agreementId", locked.agreement().value().toString())
                .with("reversalId", reversal.id().toString())
                .with("repaymentId", reversed.id().toString())
                .with("repaymentKind", reversed.kind())
                .with("journalEntryId", entry.value().toString())
                .with("reversedJournalEntryId", reversed.entry().value().toString())
                .with("valueDate", valueDate.toString())
                .with("amountMinor", Long.toString(amount.minorUnits()))
                .with("feesReopenedMinor", Long.toString(paid(reversed, AllocationComponent.FEES).minorUnits()))
                .with("interestReopenedMinor", Long.toString(paid(reversed, AllocationComponent.INTEREST).minorUnits()))
                .with("principalReopenedMinor", Long.toString(paid(reversed, AllocationComponent.PRINCIPAL).minorUnits()))
                .with("creditReversedMinor", Long.toString(held(reversed).minorUnits()))
                // OQ-14 kept across the two orders (A7-3): the re-opened late fees released beside it.
                .with("lateFeeReleasedMinor", Long.toString(released.values().stream().mapToLong(Money::minorUnits).sum()))
                .with("scale", Integer.toString(amount.scale()))
                .with("currency", amount.currency().code())
                .with("agreementVersion", Integer.toString(reversal.agreementVersion()));
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        REVERSED_EVENT,
                        BnplTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        locked.obligation().id(),
                        BnplAcceptances.OBLIGATION_AGGREGATE_TYPE,
                        clock.instant(),
                        BnplTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    private void audit(Connection unitOfWork, Actor actor, BnplAuditAction action, UUID reversal, String reason,
            String summary, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, TARGET_TYPE,
                reversal.toString(), Optional.of(reason), AuditOutcome.SUCCEEDED, correlation, Optional.of(summary)));
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** No such agreement, or no such repayment, assessment, reversal or waiver of it - the uniform 404. */
    public static final class NotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NotFound() {
            super("no such BNPL agreement, repayment, fee or proposal");
        }
    }

    /** The operator's own party holds the agreement - or the operator is no identity the platform can place. */
    public static final class SelfDealingRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final transient BnplAgreementId agreement;
        private final String act;

        SelfDealingRefused(BnplAgreementId agreement, String act) {
            super("an employee never services a BNPL agreement of their own party");
            this.agreement = agreement;
            this.act = act;
        }

        /** The agreement acted on. */
        public BnplAgreementId agreement() {
            return agreement;
        }

        /** The act refused: {@code repayment-reversal:propose}, {@code waiver:approve}, ... */
        public String act() {
            return act;
        }
    }

    /** The obligation is closed: no repayment of it is reversed (lending's A12, {@code INV-LIFE-04}). */
    public static final class ObligationClosed extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ObligationClosed() {
            super("the BNPL agreement is closed: no repayment of it is reversed");
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

        ProposalNotPending(BnplCorrectionStatus status) {
            super("the reversal is no longer awaiting a decision: it is " + status.name());
        }
    }

    /** The approver proposed the reversal ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(BnplAgreementId agreement, UUID reversal) {
            super("a reversal is approved by someone other than its proposer; the proposer may reject it");
            this.target = reversal.toString();
            this.summary = "agreement=" + agreement.value() + ", reversal=" + reversal + ", act=repayment-reversal:approve";
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

    /** The repayment is already reversed, a payoff's, or its held credit has since been applied. */
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
