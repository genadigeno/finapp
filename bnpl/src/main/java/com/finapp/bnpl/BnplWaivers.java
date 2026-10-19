package com.finapp.bnpl;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Fee waivers under four eyes - T-wvr (`P12-TSK-024`; {@code PHASE_12_PLAN.md} sections 7.1, 7.2, 12.11 F7; {@code
 * BNPL_LIFECYCLES.md} section 4.11; lending's {@code Waivers}, `P11-TSK-025`, mirrored for its {@code FEE} kind;
 * {@code INV-REV-02}, {@code INV-REV-04}, {@code INV-AUD-04}, {@code INV-LND-11} by analogy). A late fee is forgone only by a
 * reasoned act that posts - never an edit: a holder of {@code BNPL_SERVICE} proposes waiving an amount of one fee with a
 * reason; a DIFFERENT holder of {@code BNPL_SERVICE_APPROVE} approves it; anyone with {@code BNPL_SERVICE}, the proposer
 * included, rejects it. Each act is ONE transaction on the caller's unit of work, which the door's idempotency claim
 * ({@code bnpl.servicing:<actor type>:<id>}) wraps; every refusal is thrown before any write.
 *
 * <h2>The bound - nothing forgiven twice (the Phase 11 gate's R3, applied forward)</h2>
 *
 * <p>A waiver of an assessment is at most the least of ({@link #bounded}, the R3 arm judged first)
 * <ol>
 *   <li>what has <strong>never been forgiven</strong> - the assessment less every approved waiver of it less what every
 *       refund released on its instalment (OQ-14) - read from those rows alone; and
 *   <li>what its fee <strong>still has due</strong> - its instalment's {@code FEES} billed (assessed less approved waivers)
 *       less settled (every payer's, less every reversal's negation, plus every refund's release), from the due state
 *       {@link BnplReceivables} proves against the obligation's ledger balances under B4 (a difference is an anomaly, never
 *       waived over).
 * </ol>
 * <p>So a fee paid, released or waived is never forgiven again; a fee re-opened by a reversal of its payment is forgivable
 * again exactly to the extent it was never forgiven. Judged at the proposal - one above it is never born - and again at the
 * approval under the same lock, so a repayment of the fee or a refund's release that committed between them makes the
 * approval {@link WaiverExceedsDue}, nothing posted, the proposal still {@code PROPOSED}. {@code bnpl V016}'s triggers
 * re-judge both arms ({@code bnpl.fee_waiver_room}) for every writer under B4.
 *
 * <h2>The approval, in the Phase 12 lock order (B4, B6, (P), L8)</h2>
 *
 * <ol>
 *   <li>the agreement's party, read unlocked, and the actor's: never one's own party's agreement
 *       ({@link BnplRepaymentReversals.SelfDealingRefused} - recorded {@code FAILED} in a transaction of its own);
 *   <li><strong>(B4)</strong> the obligation {@code FOR UPDATE}. No L0: a waiver moves no principal, so exposure does not
 *       move; no L6;
 *   <li><strong>(B6)</strong> the proposal, read under B4 - INSERT-only, so its decision is a born-once row beneath B4
 *       ({@code UNIQUE (waiver_id)}): {@code PROPOSED}, not the approver's own ({@link SelfApprovalRefused}; the four-eyes
 *       {@code CHECK} beneath); the same approver's retry converges;
 *   <li>an {@code ACTIVE} obligation ({@link ObligationClosed}); the database clock after the locks; the bound;
 *   <li><strong>(P)</strong> the customer's existing wallet row {@code FOR UPDATE} - only when the obligation holds a credit
 *       balance, which a closure this waiver brings refunds to the wallet: so the wallet is never taken after the shared
 *       {@code BNPL_FEE_INCOME} row (the Phase 11 gate's A2-F1, the Phase 12 order's "no shared income row before (P)");
 *   <li><strong>(L8)</strong> one sorted call over the fees-due row, the currency's {@code BNPL_FEE_INCOME} row and - when a
 *       credit balance stands - the credit balance's and the wallet's rows; then F7, the ledger's {@code ReversalService} of
 *       the assessment's F4 entry for the amount - {@code DR BNPL_FEE_INCOME / CR BNPL_FEES_DUE} under
 *       {@code bnpl.waiver:<waiver>}, bounded by that entry as well ({@code INV-REV-02}) - then only writes no other
 *       transaction waits on: the decision {@code APPROVED} naming the entry, the closure when the obligation is settled
 *       ({@link BnplClosures} - {@code REPAID}), {@code bnpl.BnplFeeWaived}, the audit record.
 * </ol>
 */
@RequiredArgsConstructor
public final class BnplWaivers {

    /** F7's key prefix within the ledger's reversal scope: one entry per waiver. */
    public static final String WAIVER_KEY_PREFIX = "bnpl.waiver:";

    /** The event an approved waiver publishes. */
    public static final String FEE_WAIVED_EVENT = "bnpl.BnplFeeWaived";

    /** The audit records' target type. */
    static final String TARGET_TYPE = "bnpl_waiver";

    /** The newest waivers an agreement's read answers. */
    public static final int HISTORY_LIMIT = 100;

    @NonNull private final BnplCorrectionStore store;
    @NonNull private final BnplServicingStore servicing;
    @NonNull private final BnplPurchaseStore purchases;
    @NonNull private final BnplWallets wallets;
    @NonNull private final BnplBook book;
    @NonNull private final BnplClosures closures;
    @NonNull private final BnplActingParty actingParties;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * A decision's outcome; {@code replayed} when the same person's retry converged on their own decision; the closure when
     * the approval zeroed the obligation's last receivable.
     */
    public record Decided(BnplWaiver waiver, boolean replayed, Optional<BnplClosure> closure) {

        public Decided {
            Objects.requireNonNull(waiver, "waiver must not be null");
            Objects.requireNonNull(closure, "closure must not be null");
        }
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes waiving {@code amount} of the agreement's fee assessment {@code assessment}, with {@code reason}: under the
     * obligation's row lock, an {@code ACTIVE} obligation, within what may still be forgiven, no other proposal of the fee
     * awaiting a decision; born {@code PROPOSED} with the reasoned audit record. Nothing is posted.
     *
     * @throws BnplReasons.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws BnplRepaymentReversals.NotFound no such agreement, or no such fee of it
     * @throws BnplRepaymentReversals.SelfDealingRefused the agreement is the actor's own party's, or the actor has none
     * @throws ObligationClosed the obligation is not {@code ACTIVE}
     * @throws AmountInvalid the amount is not positive, or not in the fee's currency and scale
     * @throws WaiverExceedsDue the amount exceeds what may still be forgiven
     * @throws ProposalPending a waiver of the fee awaits a decision
     * @throws AllocationAnomaly the obligation's rows do not explain its due accounts
     */
    public BnplWaiver propose(Connection unitOfWork, BnplAgreementId agreement, UUID assessment, Money amount,
            String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = BnplReasons.reasoned(reason);
        BnplCorrectionStore.Addressed addressed = BnplRepaymentReversals.judgeSelfDealing(store, actingParties, unitOfWork,
                agreement, actor, "waiver:propose");
        // (B4) The obligation: every act on it, and every proposal on any of its fees, serialises here.
        BnplServicingStore.Locked locked = servicing.lock(unitOfWork, addressed.obligation())
                .orElseThrow(BnplRepaymentReversals.NotFound::new);
        BnplFeeAssessment fee = store.assessment(unitOfWork, addressed.obligation(), assessment)
                .orElseThrow(BnplRepaymentReversals.NotFound::new);
        if (locked.obligation().status() != BnplObligationStatus.ACTIVE) {
            throw new ObligationClosed();
        }
        if (!amount.isPositive() || !amount.currency().equals(fee.amount().currency())
                || amount.scale() != fee.amount().scale()) {
            throw new AmountInvalid();
        }
        bounded(unitOfWork, locked, fee, amount);
        if (store.waiversOf(unitOfWork, assessment).stream()
                .anyMatch(waiver -> waiver.status() == BnplCorrectionStatus.PROPOSED)) {
            throw new ProposalPending();
        }
        UUID id = ids.next();
        Instant proposedAt = store.insertWaiver(unitOfWork, new BnplCorrectionStore.NewWaiver(id, addressed.obligation(),
                BnplWaiverKind.FEE, assessment, fee.instalment(), amount, locked.agreement(), locked.agreementVersion(),
                actor, reasoned, correlation));
        audit(unitOfWork, actor, BnplAuditAction.WAIVER_PROPOSED, id, reasoned,
                "agreement=" + agreement.value() + ", kind=" + BnplWaiverKind.FEE.name() + ", assessment=" + assessment,
                correlation);
        return new BnplWaiver(id, addressed.obligation(), BnplWaiverKind.FEE, assessment, fee.instalment(), amount,
                locked.agreement(), locked.agreementVersion(), BnplCorrectionStatus.PROPOSED, actor.id(), proposedAt,
                reasoned, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    // ------------------------------------------------------------------ approve

    /**
     * Approves the proposal {@code waiver} of the agreement - a different person's act, posting F7 in the lock order the
     * class names.
     *
     * @throws BnplReasons.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws BnplRepaymentReversals.NotFound no such agreement, or no such waiver of it
     * @throws BnplRepaymentReversals.SelfDealingRefused the agreement is the actor's own party's, or the actor has none
     * @throws ProposalNotPending the waiver is decided - by somebody else, or differently
     * @throws SelfApprovalRefused the approver proposed it
     * @throws ObligationClosed the obligation is not {@code ACTIVE}
     * @throws WaiverExceedsDue the amount exceeds what may still be forgiven - nothing posted, the proposal still
     *     {@code PROPOSED}
     * @throws AllocationAnomaly the obligation's rows do not explain its due accounts
     */
    public Decided approve(Connection unitOfWork, BnplAgreementId agreement, UUID waiver, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(waiver, "waiver");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = BnplReasons.reasoned(reason);
        BnplCorrectionStore.Addressed addressed = BnplRepaymentReversals.judgeSelfDealing(store, actingParties, unitOfWork,
                agreement, actor, "waiver:approve");
        // (B4) The obligation.
        BnplServicingStore.Locked locked = servicing.lock(unitOfWork, addressed.obligation())
                .orElseThrow(BnplRepaymentReversals.NotFound::new);
        // (B6) The proposal, read under the obligation's lock: the decision every committed act left.
        BnplWaiver proposal = store.waiver(unitOfWork, addressed.obligation(), waiver)
                .orElseThrow(BnplRepaymentReversals.NotFound::new);
        if (proposal.status() == BnplCorrectionStatus.APPROVED
                && proposal.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(proposal, true, Optional.empty());
        }
        if (proposal.status() != BnplCorrectionStatus.PROPOSED) {
            throw new ProposalNotPending(proposal.status());
        }
        if (proposal.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused(agreement, waiver);
        }
        BnplObligation obligation = locked.obligation();
        if (obligation.status() != BnplObligationStatus.ACTIVE) {
            throw new ObligationClosed();
        }
        BnplFeeAssessment fee = store.assessment(unitOfWork, obligation.id(), proposal.assessment())
                .orElseThrow(() -> new IllegalStateException("a waiver names an assessment of its obligation (bnpl V016)"));
        // The database clock, read after the locks, in the pinned zone: the entry's date.
        Instant now = servicing.databaseNow(unitOfWork);
        LocalDate today = LocalDate.ofInstant(now, ZoneId.of(locked.servicingZone()));
        bounded(unitOfWork, locked, fee, proposal.amount());
        BnplReceivables receivables = new BnplReceivables(purchases, book);
        BnplLedgerAccounts accounts = receivables.accounts(unitOfWork, obligation.id());
        LedgerAccountId income = book.operational(unitOfWork, AccountPurpose.BNPL_FEE_INCOME, obligation.currency());
        List<LedgerAccountId> rows = new ArrayList<>(List.of(accounts.feesDue().id(), income));
        // (P) The customer's existing wallet, before any income row - only when a credit balance stands, which the closure
        // this waiver may bring refunds to it.
        Optional<LedgerAccountId> walletHeld = Optional.empty();
        if (receivables.creditHeld(unitOfWork, accounts).isPositive()) {
            Optional<LedgerAccountId> wallet = wallets.existingWallet(unitOfWork, obligation.party(),
                    obligation.currency());
            if (wallet.isPresent() && book.lockForUpdate(unitOfWork, wallet.get())
                    .filter(LedgerAccountStatus.ACTIVE::equals).isPresent()) {
                walletHeld = wallet;
                rows.add(accounts.creditBalance().id());
                rows.add(wallet.get());
            }
        }
        // (L8) One sorted call: the fees-due row, the currency's BNPL_FEE_INCOME row and, beside a credit balance, the
        // closure refund's two rows - never the income row before the wallet.
        book.lockBalancesInOrder(unitOfWork, rows);

        // F7: the assessment's F4 entry reversed for the amount - bounded by it (INV-REV-02).
        JournalEntryId entry = book.reverse(unitOfWork, new ReversalCommand(WAIVER_KEY_PREFIX + waiver, fee.entry(), today,
                today, obligation.id().value().toString(), List.of(
                        new JournalLine(income, Direction.DEBIT, proposal.amount()),
                        new JournalLine(accounts.feesDue().id(), Direction.CREDIT, proposal.amount()))));
        if (!store.decideWaiver(unitOfWork, new BnplCorrectionStore.WaiverDecision(ids.next(), waiver,
                proposal.proposedBy(), BnplCorrectionStatus.APPROVED, actor, reasoned, Optional.of(entry), correlation))) {
            throw new IllegalStateException("a PROPOSED waiver read under its obligation's lock was decided: the lock"
                    + " protocol was bypassed");
        }
        Optional<BnplClosure> closure = closures.closeIfSettled(unitOfWork, new BnplClosures.Closing(locked, accounts,
                today, Optional.empty(), walletHeld), () -> actor, correlation);
        BnplWaiver approved = store.waiver(unitOfWork, obligation.id(), waiver)
                .orElseThrow(() -> new IllegalStateException("a waiver decided in this transaction is readable in it"));
        publish(unitOfWork, locked, approved, entry, today, correlation);
        audit(unitOfWork, actor, BnplAuditAction.WAIVER_APPROVED, waiver, reasoned,
                "agreement=" + agreement.value() + ", kind=" + approved.kind().name() + ", assessment="
                        + approved.assessment() + ", entry=" + entry.value()
                        + closure.map(c -> ", closed=" + c.reason().name()).orElse(""), correlation);
        return new Decided(approved, false, closure);
    }

    // ------------------------------------------------------------------ reject

    /**
     * Rejects the proposal {@code waiver} - anyone with {@code BNPL_SERVICE}, the proposer's withdrawal included; on a
     * closed obligation too, so a proposal left standing can be cleared. Terminal; nothing is posted.
     *
     * @throws BnplReasons.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws BnplRepaymentReversals.NotFound no such agreement, or no such waiver of it
     * @throws BnplRepaymentReversals.SelfDealingRefused the agreement is the actor's own party's, or the actor has none
     * @throws ProposalNotPending the waiver is decided - by somebody else, or differently
     */
    public Decided reject(Connection unitOfWork, BnplAgreementId agreement, UUID waiver, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(waiver, "waiver");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = BnplReasons.reasoned(reason);
        BnplCorrectionStore.Addressed addressed = BnplRepaymentReversals.judgeSelfDealing(store, actingParties, unitOfWork,
                agreement, actor, "waiver:reject");
        servicing.lock(unitOfWork, addressed.obligation()).orElseThrow(BnplRepaymentReversals.NotFound::new);
        BnplWaiver proposal = store.waiver(unitOfWork, addressed.obligation(), waiver)
                .orElseThrow(BnplRepaymentReversals.NotFound::new);
        if (proposal.status() == BnplCorrectionStatus.REJECTED
                && proposal.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(proposal, true, Optional.empty());
        }
        if (proposal.status() != BnplCorrectionStatus.PROPOSED) {
            throw new ProposalNotPending(proposal.status());
        }
        if (!store.decideWaiver(unitOfWork, new BnplCorrectionStore.WaiverDecision(ids.next(), waiver,
                proposal.proposedBy(), BnplCorrectionStatus.REJECTED, actor, reasoned, Optional.empty(), correlation))) {
            throw new IllegalStateException("a PROPOSED waiver read under its obligation's lock was decided: the lock"
                    + " protocol was bypassed");
        }
        BnplWaiver rejected = store.waiver(unitOfWork, addressed.obligation(), waiver)
                .orElseThrow(() -> new IllegalStateException("a waiver decided in this transaction is readable in it"));
        audit(unitOfWork, actor, BnplAuditAction.WAIVER_REJECTED, waiver, reasoned,
                "agreement=" + agreement.value() + ", kind=" + proposal.kind().name() + ", byProposer="
                        + proposal.proposedBy().equals(actor.id()), correlation);
        return new Decided(rejected, false, Optional.empty());
    }

    /** The agreement's waivers, newest first - empty when no such agreement (the door's uniform 404 then). */
    public Optional<List<BnplWaiver>> ofAgreement(Connection unitOfWork, BnplAgreementId agreement) {
        Objects.requireNonNull(agreement, "agreement");
        return store.addressed(unitOfWork, agreement)
                .map(addressed -> store.waiversOfObligation(unitOfWork, addressed.obligation(), HISTORY_LIMIT));
    }

    /** Records a refused self-dealing attempt {@code FAILED}, in the caller's own transaction after the act rolled back. */
    public void recordRefusal(Connection unitOfWork, BnplRepaymentReversals.SelfDealingRefused refused, Actor actor,
            CorrelationId correlation) {
        BnplRepaymentReversals.record(audit, ids, clock, unitOfWork, refused, actor, correlation);
    }

    /**
     * Records a refused self-approval {@code FAILED} ({@link BnplAuditAction#SELF_APPROVAL_REFUSED}), in the caller's own
     * transaction after the act rolled back (the Phase 11 gate's R4).
     */
    public void recordRefusal(Connection unitOfWork, SelfApprovalRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        SelfApprovalRefusals.record(audit, ids, clock, unitOfWork, actor, TARGET_TYPE, refused.target(), refused.summary(),
                correlation);
    }

    // ------------------------------------------------------------------ the bound

    /**
     * Refuses {@code amount} beyond what of {@code fee} may still be waived, under the caller's B4 - the R3 arm first, from
     * the assessment's own rows alone (assessed less approved waivers less released: what a refund released or an earlier
     * waiver forgave is never forgiven again, whatever a later reversal re-opens), then the unpaid remainder (its
     * instalment's {@code FEES} in the due state proven against the ledger).
     *
     * @throws WaiverExceedsDue the amount exceeds either
     */
    void bounded(Connection unitOfWork, BnplServicingStore.Locked locked, BnplFeeAssessment fee, Money amount) {
        Money zero = Money.ofPersisted(0, fee.amount().currency(), fee.amount().scale());
        Money waived = zero;
        for (BnplWaiver earlier : store.waiversOf(unitOfWork, fee.id())) {
            if (earlier.status() == BnplCorrectionStatus.APPROVED) {
                waived = waived.plus(earlier.amount());
            }
        }
        Money neverForgiven = fee.amount().minus(waived).minus(store.releasedOn(unitOfWork, fee.instalment(), zero));
        if (amount.compareTo(neverForgiven) > 0) {
            throw new WaiverExceedsDue();
        }
        BnplReceivables receivables = new BnplReceivables(purchases, book);
        BnplReceivables.Derived derived = receivables.dueState(unitOfWork, locked.obligation().id(),
                receivables.accounts(unitOfWork, locked.obligation().id()));
        Money unpaid = derived.dueState().items().stream()
                .filter(item -> item.id().value().equals(fee.instalment())).findFirst()
                .map(item -> item.fees()).orElse(zero);
        if (amount.compareTo(unpaid) > 0) {
            throw new WaiverExceedsDue();
        }
    }

    // ------------------------------------------------------------------ the event and the audit

    /**
     * {@code bnpl.BnplFeeWaived} - the waiver, its kind, the assessment and instalment, the amount, the entry, the value date
     * and the agreement version; never a reason or a party.
     */
    private void publish(Connection unitOfWork, BnplServicingStore.Locked locked, BnplWaiver waiver, JournalEntryId entry,
            LocalDate today, CorrelationId correlation) {
        Money amount = waiver.amount();
        EventPayload payload = EventPayload.of()
                .with("obligationId", locked.obligation().id().value().toString())
                .with("agreementId", locked.agreement().value().toString())
                .with("waiverId", waiver.id().toString())
                .with("kind", waiver.kind().name())
                .with("assessmentId", waiver.assessment().toString())
                .with("instalmentId", waiver.instalment().toString())
                .with("component", AllocationComponent.FEES.name())
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
                        FEE_WAIVED_EVENT,
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

    private void audit(Connection unitOfWork, Actor actor, BnplAuditAction action, UUID waiver, String reason,
            String summary, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, TARGET_TYPE,
                waiver.toString(), Optional.of(reason), AuditOutcome.SUCCEEDED, correlation, Optional.of(summary)));
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** The obligation is closed: nothing on it is waived (a proposal may still be rejected). */
    public static final class ObligationClosed extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ObligationClosed() {
            super("the BNPL agreement is closed: nothing on it is waived");
        }
    }

    /** The amount is not positive, or not in the fee's currency and scale. */
    public static final class AmountInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AmountInvalid() {
            super("the amount is not a positive amount in the fee's currency");
        }
    }

    /** The waiver exceeds what may still be forgiven ({@code INV-REV-02}, the Phase 11 gate's R3). */
    public static final class WaiverExceedsDue extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        WaiverExceedsDue() {
            super("the waiver exceeds what is due of the fee");
        }
    }

    /** A waiver of the fee already awaits a decision. */
    public static final class ProposalPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProposalPending() {
            super("a waiver of this fee already awaits a decision");
        }
    }

    /** The waiver is decided - by somebody else, or differently. */
    public static final class ProposalNotPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProposalNotPending(BnplCorrectionStatus status) {
            super("the waiver is no longer awaiting a decision: it is " + status.name());
        }
    }

    /** The approver proposed the waiver ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(BnplAgreementId agreement, UUID waiver) {
            super("a waiver is approved by someone other than its proposer; the proposer may reject it");
            this.target = waiver.toString();
            this.summary = "agreement=" + agreement.value() + ", waiver=" + waiver + ", act=waiver:approve";
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
