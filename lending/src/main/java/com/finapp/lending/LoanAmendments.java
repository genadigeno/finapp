package com.finapp.lending;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.PurposeBalances;
import com.finapp.lending.InstalmentBillingStore.BilledItem;
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
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Restructuring by agreement amendment - T-amd and the customer's acceptance (`P11-TSK-027`; {@code PHASE_11_PLAN.md}
 * sections 5.3, 5.4, 7.1, 7.2, 7.4 scenario 8, 11, 12.1, 12.2 step 7, 12.7 and 12.8 rule 13; ADR-0099 points 1, 2 and 5;
 * {@code INV-LND-05}, {@code INV-LND-11}, {@code INV-LND-03}, {@code INV-AUD-04}, {@code INV-HIST-04}). A restructuring is
 * not a state: it is agreement version n+1 on an {@code ACTIVE} instalment loan, made by three acts - a holder of
 * {@code LOAN_SERVICE} proposes it, a DIFFERENT holder of {@code LOAN_SERVICE_APPROVE} approves it (opening the customer's
 * offer for the pinned offer validity), the customer accepts it ({@code MULTI_FACTOR}). Each act is ONE transaction on the
 * caller's unit of work, which the door's idempotency claim wraps; every refusal is thrown before any write (or rolls the
 * whole transaction back).
 *
 * <h2>What an amendment may change - and never</h2>
 *
 * <ul>
 *   <li>the <strong>rate</strong>: equal or lower ({@code 422 lending.TermsInvalid} above - a higher rate is a new credit
 *       decision); it governs every date accrued from the acceptance's business date on;
 *   <li>the <strong>remaining term</strong>: the new schedule's instalments, never fewer than the current schedule has left
 *       and the loan's whole term (instalments billed plus these) within the product's maximum;
 *   <li>the <strong>arrears</strong>: billed principal past due re-classified as not due (posting rule 13, {@code DR
 *       LOAN_PRINCIPAL / CR LOAN_PRINCIPAL_DUE}, key {@code lending.amendment:<amendment>}) and re-scheduled into the new
 *       instalments - never overdue interest or fees, which stay due on their items (never capitalised).
 * </ul>
 *
 * <p>Never the principal, the repayment day, the terms version, the engines or the template, and never a credit line (a
 * line has no schedule to restructure; more limit is a new decision). No amount is named by any act: the principal the new
 * schedule amortises is what the rows and the ledger say is outstanding.
 *
 * <h2>The acceptance - in the lock order (L3, then the ledger), one transaction</h2>
 *
 * <ol>
 *   <li><strong>(L3)</strong> the loan owner-scoped - the serialisation point it shares with every servicing step, repayment,
 *       collection, payoff, reversal and waiver on the account;
 *   <li><strong>(L5)</strong> the amendment, read under L3 (INSERT-only - its acceptance is agreement version n+1, born once
 *       by {@code UNIQUE (amendment_id)} and {@code UNIQUE (loan_id, version)} beneath L3): accepted already - the same
 *       customer's retry converges; lapsed (the agreement moved, the loan closed, the offer expired) -
 *       {@link AmendmentLapsed}; the echoed document hash - {@link DocumentChanged};
 *   <li>the database clock after the lock; every elapsed date accrued and everything due billed, each billing followed by
 *       the credit-balance application ({@link LoanServicing#catchUp}, {@link InstalmentBillings#bill} - the servicing step's
 *       own rows, uniques and keys), so the billed instalments kept include everything due today;
 *   <li>the open period: the latest schedule's calendar's period holding today - its start (the latest billed due date)
 *       the new schedule's start, its end the new first due date - so the accruals already posted in it are billed by the
 *       new first instalment;
 *   <li>the principal outstanding from postings: {@code LOAN_PRINCIPAL} plus the arrears re-scheduled - the due state
 *       derived from the rows and proven against the ledger first ({@link LoanReceivables#dueState});
 *   <li>schedule n+1 by the pinned engine's recalculation ({@link ScheduleEngineV1#recalculate}) under the amended rate -
 *       a refusal is {@code TermsInvalid}, nothing written;
 *   <li>then the writes: agreement version n+1 (canonical terms format 3 - {@link CanonicalOfferTerms#renderAmendment}),
 *       its evidence, rule 13 and the re-scheduled items, schedule version n+1 and its instalments,
 *       {@code lending.LoanAmended}, the audit record. The version's trigger re-judges the offer's expiry on its own
 *       statement's clock, last: an offer that expired during the work above is refused there in {@code lending V027}'s
 *       {@code LNAEX} and answered {@link AmendmentLapsed}, as at L5 (`P11-TSK-035`).
 * </ol>
 *
 * <p><strong>No L0</strong>: the exposure measure ({@code LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE}, and the latest agreement's
 * principal) is unchanged by rule 13 and never raised. <strong>No L6</strong>: capital headroom reads the same sum.
 * <strong>No L7</strong>: no wallet money moves, and the acceptance never closes the account.
 */
@RequiredArgsConstructor
public final class LoanAmendments {

    /** Rule 13's key prefix: {@code lending.amendment:<amendment>} - one entry per accepted amendment. */
    public static final String POSTING_KEY_PREFIX = "lending.amendment:";

    /** The event an accepted amendment publishes. */
    public static final String AMENDED_EVENT = "lending.LoanAmended";

    /** The origin an accepted amendment's {@code lending.LoanAmended} names (`P11-TSK-028`: a prepayment's names its own). */
    public static final String ORIGIN = "AMENDMENT";

    /** The audit records' target type. */
    static final String TARGET_TYPE = "loan_amendment";

    /** The newest amendments a loan's read answers. */
    public static final int HISTORY_LIMIT = 100;

    @NonNull private final LoanStore loans;
    @NonNull private final LoanAmendmentStore store;
    @NonNull private final LoanOfferStore offers;
    @NonNull private final LoanTermsStore terms;
    @NonNull private final LoanProductBounds bounds;
    @NonNull private final LoanServicing servicing;
    @NonNull private final LoanServicingStore servicingStore;
    @NonNull private final InstalmentBillings billings;
    @NonNull private final InstalmentBillingStore billingStore;
    @NonNull private final LoanDisbursementStore schedules;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final PurposeBalances<Connection> balances;
    @NonNull private final PostingService postings;
    @NonNull private final LoanActingParty actingParties;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** An operator's decision; {@code replayed} when the same person's retry converged on their own decision. */
    public record Decided(LoanAmendment amendment, boolean replayed) {

        public Decided {
            Objects.requireNonNull(amendment, "amendment must not be null");
        }
    }

    /** What the customer reads: the amendment offered to them, and the document whose hash they echo to accept it. */
    public record Offered(LoanAmendment amendment, String document) {

        public Offered {
            Objects.requireNonNull(amendment, "amendment must not be null");
            Objects.requireNonNull(document, "document must not be null");
        }
    }

    /**
     * The acceptance: the amendment {@code ACCEPTED}, the agreement and schedule versions it made, the arrears it
     * re-scheduled (zero when none) and its effective business date; {@code replayed} when it converged on an acceptance
     * already committed.
     */
    public record Accepted(LoanAmendment amendment, int agreementVersion, int scheduleVersion, Money arrearsRescheduled,
            boolean replayed) {

        public Accepted {
            Objects.requireNonNull(amendment, "amendment must not be null");
            Objects.requireNonNull(arrearsRescheduled, "arrearsRescheduled must not be null");
        }
    }

    /** What the customer sends: the hash of the document they were shown, and how they accepted. */
    public record Acceptance(String documentSha256, AcceptanceEvidence evidence) {

        public Acceptance {
            Objects.requireNonNull(documentSha256, "documentSha256 must not be null");
            Objects.requireNonNull(evidence, "evidence must not be null");
        }

        @Override
        public String toString() {
            return "Acceptance[redacted]";
        }
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes amending {@code loan}'s governing agreement version to {@code rate} over {@code remainingInstalments}, the
     * arrears re-scheduled when {@code rescheduleArrears}, with {@code reason}: under the loan's row lock; born
     * {@code PROPOSED} with the reasoned audit record. Nothing is posted and the agreement is unchanged.
     *
     * @throws LendingCapitalAdministration.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws AmendmentNotFound no such loan
     * @throws RepaymentReversals.SelfDealingRefused the loan is the actor's own party's
     * @throws LoanClosed the loan is closed or cancelled
     * @throws LoanTerms.TermsInvalid a line, a loan not yet scheduled, a higher rate, a shorter or out-of-bounds term, a
     *     change of nothing, or a schedule the engine refuses
     * @throws ProposalPending an amendment of the loan is already open
     * @throws AllocationAnomaly the loan's rows do not explain its due accounts
     */
    public LoanAmendment propose(Connection unitOfWork, LoanId loan, BigDecimal rate, int remainingInstalments,
            boolean rescheduleArrears, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = LendingCapitalAdministration.reasoned(reason);
        LoanSelfDealing.judge(loans, actingParties, unitOfWork, loan, actor, "propose", AmendmentNotFound::new);
        // (L3) The loan: every act on the account, and every amendment of it, serialises here.
        LoanStore.Owned locked = loans.lock(unitOfWork, loan).orElseThrow(AmendmentNotFound::new);
        Loan account = locked.loan();
        LoanAgreement agreement = locked.agreement();
        amendable(account);
        BigDecimal amended = LoanTerms.canonicalRate(rate);
        if (amended.compareTo(agreement.nominalAnnualRate()) > 0) {
            throw new LoanTerms.TermsInvalid("an amendment never raises the rate - a higher rate is a new credit decision");
        }
        LoanTerms pinned = pinned(unitOfWork, agreement);
        ZoneId zone = ZoneId.of(pinned.servicingZone());
        LocalDate today = LocalDate.ofInstant(store.databaseNow(unitOfWork), zone);
        Plan plan = plan(unitOfWork, account, agreement, pinned, today, amended, remainingInstalments, rescheduleArrears);
        boolean changes = amended.compareTo(agreement.nominalAnnualRate()) < 0 || remainingInstalments > plan.unbilled()
                || (rescheduleArrears && plan.arrears().isPositive());
        if (!changes) {
            throw new LoanTerms.TermsInvalid("an amendment lowers the rate, lengthens the remaining term or re-schedules"
                    + " arrears that exist");
        }
        if (store.open(unitOfWork, loan)) {
            throw new ProposalPending();
        }
        UUID id = ids.next();
        byte[] document = CanonicalOfferTerms.sha256(AmendmentDocument.render(id, loan, agreement.version(),
                agreement.nominalAnnualRate(), amended, remainingInstalments, rescheduleArrears,
                pinned.offerValidityDays()));
        store.insert(unitOfWork, new LoanAmendmentStore.NewAmendment(id, loan, agreement.id(), agreement.version(),
                amended, remainingInstalments, rescheduleArrears, document, actor, reasoned, correlation));
        audit(unitOfWork, actor, LendingAuditAction.AMENDMENT_PROPOSED, id, Optional.of(reasoned),
                "loan=" + loan.value() + ", amends=v" + agreement.version() + ", remainingInstalments="
                        + remainingInstalments + ", rescheduleArrears=" + rescheduleArrears, correlation);
        return found(unitOfWork, loan, id);
    }

    // ------------------------------------------------------------------ approve and reject

    /**
     * Approves the proposal {@code amendment} of {@code loan} - a different person's act, opening the customer's offer until
     * its expiry; nothing is posted.
     *
     * @throws LendingCapitalAdministration.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws AmendmentNotFound no such loan, or no such amendment of it
     * @throws RepaymentReversals.SelfDealingRefused the loan is the actor's own party's
     * @throws AmendmentLapsed the agreement it amends moved, or the loan is no longer {@code ACTIVE}
     * @throws ProposalNotPending it is decided - by somebody else, or differently
     * @throws SelfApprovalRefused the approver proposed it
     */
    public Decided approve(Connection unitOfWork, LoanId loan, UUID amendment, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(amendment, "amendment");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = LendingCapitalAdministration.reasoned(reason);
        LoanSelfDealing.judge(loans, actingParties, unitOfWork, loan, actor, "approve", AmendmentNotFound::new);
        // (L3) The loan; (L5) the proposal, read under it: the decision every committed act left.
        loans.lock(unitOfWork, loan).orElseThrow(AmendmentNotFound::new);
        LoanAmendment proposal = store.find(unitOfWork, loan, amendment).orElseThrow(AmendmentNotFound::new);
        if (proposal.approved() && proposal.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(proposal, true);
        }
        if (proposal.status() == LoanAmendmentStatus.LAPSED) {
            throw new AmendmentLapsed();
        }
        if (proposal.status() != LoanAmendmentStatus.PROPOSED) {
            throw new ProposalNotPending(proposal.status());
        }
        if (proposal.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused(loan, amendment);
        }
        if (!store.decide(unitOfWork, new LoanAmendmentStore.Decision(ids.next(), amendment, proposal.proposedBy(),
                LoanAmendmentStatus.APPROVED, actor, reasoned, correlation))) {
            throw new IllegalStateException("a PROPOSED amendment read under its loan's lock was decided: the lock"
                    + " protocol was bypassed");
        }
        audit(unitOfWork, actor, LendingAuditAction.AMENDMENT_APPROVED, amendment, Optional.of(reasoned),
                "loan=" + loan.value() + ", amends=v" + proposal.amendsVersion(), correlation);
        return new Decided(found(unitOfWork, loan, amendment), false);
    }

    /**
     * Rejects the proposal {@code amendment} - anyone with {@code LOAN_SERVICE}, the proposer's withdrawal included.
     * Terminal; nothing is posted.
     *
     * @throws LendingCapitalAdministration.ReasonRequired no reason, one past its bound, or one holding an instrument shape
     * @throws AmendmentNotFound no such loan, or no such amendment of it
     * @throws RepaymentReversals.SelfDealingRefused the loan is the actor's own party's
     * @throws ProposalNotPending it is decided, or lapsed
     */
    public Decided reject(Connection unitOfWork, LoanId loan, UUID amendment, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(amendment, "amendment");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = LendingCapitalAdministration.reasoned(reason);
        LoanSelfDealing.judge(loans, actingParties, unitOfWork, loan, actor, "reject", AmendmentNotFound::new);
        loans.lock(unitOfWork, loan).orElseThrow(AmendmentNotFound::new);
        LoanAmendment proposal = store.find(unitOfWork, loan, amendment).orElseThrow(AmendmentNotFound::new);
        if (proposal.status() == LoanAmendmentStatus.REJECTED
                && proposal.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(proposal, true);
        }
        if (proposal.status() != LoanAmendmentStatus.PROPOSED) {
            throw new ProposalNotPending(proposal.status());
        }
        if (!store.decide(unitOfWork, new LoanAmendmentStore.Decision(ids.next(), amendment, proposal.proposedBy(),
                LoanAmendmentStatus.REJECTED, actor, reasoned, correlation))) {
            throw new IllegalStateException("a PROPOSED amendment read under its loan's lock was decided: the lock"
                    + " protocol was bypassed");
        }
        audit(unitOfWork, actor, LendingAuditAction.AMENDMENT_REJECTED, amendment, Optional.of(reasoned),
                "loan=" + loan.value() + ", byProposer=" + proposal.proposedBy().equals(actor.id()), correlation);
        return new Decided(found(unitOfWork, loan, amendment), false);
    }

    /**
     * Records a refused self-dealing attempt {@code FAILED} ({@link LendingAuditAction#SELF_DEALING_REFUSED}) on
     * {@code unitOfWork} - the caller's own transaction, after the act rolled back, so the attempt is kept.
     */
    public void recordRefusal(Connection unitOfWork, RepaymentReversals.SelfDealingRefused refused, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                LendingAuditAction.SELF_DEALING_REFUSED, "loan", refused.loan().value().toString(), Optional.empty(),
                AuditOutcome.FAILED, correlation, Optional.of("loan=" + refused.loan().value() + ", act=amendment:"
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

    // ------------------------------------------------------------------ the customer's read

    /**
     * {@code party}'s amendment {@code amendment} of their loan {@code loan} - only one offered to them (approved: open,
     * accepted or lapsed after its approval) - with the document they accept by its hash, re-rendered and held to the hash
     * stored at the proposal.
     *
     * @throws AmendmentNotFound no such loan of the party, or no amendment of it ever offered to them
     */
    public Offered read(Connection unitOfWork, LoanId loan, UUID party, UUID amendment) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(amendment, "amendment");
        LoanStore.Owned owned = loans.ownedBy(unitOfWork, loan, party).orElseThrow(AmendmentNotFound::new);
        LoanAmendment found = store.find(unitOfWork, loan, amendment).filter(LoanAmendment::approved)
                .orElseThrow(AmendmentNotFound::new);
        return new Offered(found, document(unitOfWork, owned.agreement(), found));
    }

    // ------------------------------------------------------------------ the customer's acceptance

    /**
     * Accepts {@code party}'s amendment {@code amendment} of their loan {@code loan}, on the caller's unit of work, inside
     * the caller's correlation and actor scopes (the catch-up accrual and rule 13 post), in the order the class names.
     *
     * @throws AmendmentNotFound no such loan of the party, or no amendment of it offered to them
     * @throws AmendmentLapsed the agreement it amends moved, the loan is no longer {@code ACTIVE}, or the offer expired
     * @throws DocumentChanged the echoed hash is not the amendment's document's
     * @throws LoanTerms.TermsInvalid nothing outstanding to restructure, the term outside the product's, or a schedule the
     *     engine refuses - nothing written
     * @throws AllocationAnomaly the loan's rows do not explain its due accounts
     */
    public Accepted accept(Connection unitOfWork, LoanId loan, UUID party, UUID amendment, Acceptance acceptance,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(amendment, "amendment");
        Objects.requireNonNull(acceptance, "acceptance");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");

        // (L3) The loan, owner-scoped: another party's is never locked, and is the same answer as an absent one.
        LoanStore.Owned locked = loans.lockOwnedBy(unitOfWork, loan, party).orElseThrow(AmendmentNotFound::new);
        Loan account = locked.loan();
        LoanAgreement agreement = locked.agreement();
        // (L5) The amendment, read under L3: only one offered to the customer.
        LoanAmendment offered = store.find(unitOfWork, loan, amendment).filter(LoanAmendment::approved)
                .orElseThrow(AmendmentNotFound::new);
        if (offered.status() == LoanAmendmentStatus.ACCEPTED) {
            // Ten acceptances, one version: every later one converges on the acceptance committed.
            int version = offered.acceptedVersion().orElseThrow();
            int scheduleVersion = store.scheduleVersionOf(unitOfWork, loan, version)
                    .orElseThrow(() -> new IllegalStateException("an amended version commits beside its schedule"));
            return new Accepted(offered, version, scheduleVersion, Money.ofPersisted(0, account.currency(),
                    agreement.amount().scale()), true);
        }
        if (offered.status() != LoanAmendmentStatus.APPROVED) {
            throw new AmendmentLapsed();
        }
        if (!offered.documentSha256Hex().equals(acceptance.documentSha256().toLowerCase(Locale.ROOT))) {
            throw new DocumentChanged();
        }

        // The database clock, read after the locks, in the agreement's pinned zone: the acceptance's business date.
        Instant now = store.databaseNow(unitOfWork);
        LoanTerms pinned = pinned(unitOfWork, agreement);
        ZoneId zone = ZoneId.of(pinned.servicingZone());
        LocalDate today = LocalDate.ofInstant(now, zone);
        // Everything up to today under the agreement in force - every elapsed date accrued at its rate, everything due
        // billed (each billing followed by the credit-balance application) - the servicing step's own rows and keys.
        LocalDate accruedThrough = servicing.catchUp(unitOfWork, account, agreement, pinned, zone, today)
                .orElseThrow(() -> new LoanTerms.TermsInvalid("a loan awaiting its payout has nothing to restructure"));
        billings.bill(unitOfWork, account, agreement, pinned, today, accruedThrough);

        BigDecimal rate = offered.nominalAnnualRate();
        Plan plan = plan(unitOfWork, account, agreement, pinned, today, rate, offered.remainingInstalments(),
                offered.rescheduleArrears());

        // Agreement version n+1: the amendment's rate and term, the same principal - its canonical terms format 3.
        LoanTermsStore.LoanTermsVersion version = terms.version(unitOfWork, agreement.termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"));
        LoanOffer origin = offers.ownedBy(unitOfWork, account.offer(), party)
                .orElseThrow(() -> new IllegalStateException("a loan is born of its own party's offer"));
        int nextVersion = agreement.version() + 1;
        String canonical = CanonicalOfferTerms.renderAmendment(
                new CanonicalOfferTerms.Content(account.offer(), origin.application(), agreement.decision(),
                        agreement.termsVersionId(), version.row().version(), plan.governing(), agreement.amount(),
                        Optional.of(plan.termMonths()), agreement.repaymentDay(), Optional.empty()),
                new CanonicalOfferTerms.Amendment(amendment, agreement.version(), agreement.termsSha256Hex(), nextVersion,
                        today, plan.schedule().start(), plan.schedule().periods().get(0).dueDate(),
                        offered.remainingInstalments(), plan.schedule().principal(), plan.arrears()));
        LoanAgreement unstamped = agreement.amended(LoanAgreementId.next(ids), amendment, rate, plan.termMonths(),
                canonical, now);
        Instant effective;
        try {
            effective = loans.insertAgreement(unitOfWork, unstamped);
        } catch (LoanStore.AmendmentExpired expired) {
            // The clock crossed the offer's expires_at while this acceptance accrued, billed and planned (waiting on the
            // loan's ledger accounts among them): the version's backstop refused it in its own SQLSTATE - the customer's
            // 409, exactly as at the L5 read (P11-TSK-035). Thrown at once: the unit of work is aborted and nothing more
            // runs on it. Any other refusal of the birth is a breach and stays a storage failure.
            throw new AmendmentLapsed();
        }
        LoanAgreement amended = agreement.amended(unstamped.id(), amendment, rate, plan.termMonths(), canonical,
                effective);
        loans.insertAcceptance(unitOfWork, ids.next(), amended, offered.documentSha256(), acceptance.evidence(),
                correlation);

        // Rule 13: the arrears re-classified as not due, one entry, then one row per item naming it.
        Optional<JournalEntryId> entry = Optional.empty();
        if (plan.arrears().isPositive()) {
            entry = Optional.of(postRule13(unitOfWork, loan, amendment, plan, today));
            for (DueState.DueItem item : plan.arrearsItems()) {
                store.insertRescheduling(unitOfWork, new LoanAmendmentStore.Rescheduling(ids.next(), amendment, loan,
                        item.id(), item.principal(), entry.get(), correlation));
            }
        }

        // Schedule version n+1, from the principal outstanding: billed instalments kept, unbilled superseded.
        RepaymentSchedule next = new RepaymentSchedule(ids.next(), loan, plan.latest().version() + 1, amended.id(),
                amended.version(), plan.schedule());
        schedules.insertSchedule(unitOfWork, next, next.instalments(ids), account.correlation());

        LoanAmendment accepted = found(unitOfWork, loan, amendment);
        publish(unitOfWork, account, accepted, amended, next, plan, entry, today, correlation);
        audit(unitOfWork, actor, LendingAuditAction.AMENDMENT_ACCEPTED, amendment, Optional.empty(),
                "loan=" + loan.value() + ", agreement=v" + amended.version() + ", schedule=v" + next.version()
                        + ", arrearsRescheduled=" + plan.arrears().isPositive()
                        + entry.map(e -> ", entry=" + e.value()).orElse(""), correlation);
        return new Accepted(accepted, amended.version(), next.version(), plan.arrears(), false);
    }

    // ------------------------------------------------------------------ the judgements

    /** An {@code ACTIVE} instalment loan: a line has no schedule to restructure; a closed loan is terminal. */
    private static void amendable(Loan account) {
        if (account.kind() != LoanKind.INSTALMENT) {
            throw new LoanTerms.TermsInvalid("a credit line has no schedule to restructure - more limit is a new"
                    + " application and decision");
        }
        if (account.status() == LoanStatus.CLOSED || account.status() == LoanStatus.CANCELLED) {
            throw new LoanClosed();
        }
        if (account.status() != LoanStatus.ACTIVE) {
            throw new LoanTerms.TermsInvalid("a loan not yet disbursed has nothing to restructure");
        }
    }

    /**
     * What an amendment would do today, under L3: the current schedule's unbilled instalments, the loan's whole term, the
     * arrears it re-schedules, the principal outstanding and the new schedule from the open period - every bound judged.
     */
    private record Plan(BillableSchedule latest, int unbilled, int termMonths, List<DueState.DueItem> arrearsItems,
            Money arrears, LoanTerms governing, Schedule schedule) {}

    private Plan plan(Connection unitOfWork, Loan account, LoanAgreement agreement, LoanTerms pinned, LocalDate today,
            BigDecimal rate, int remainingInstalments, boolean rescheduleArrears) {
        LoanId loan = account.id();
        BillableSchedule latest = billingStore.latestSchedule(unitOfWork, loan)
                .orElseThrow(() -> new LoanTerms.TermsInvalid("a loan awaiting its payout has nothing to restructure"));
        List<BilledItem> billed = billingStore.billedItems(unitOfWork, loan);
        Set<UUID> billedInstalments = new HashSet<>();
        int billedCount = 0;
        for (BilledItem item : billed) {
            if (item.billing().kind() == BillingKind.INSTALMENT) {
                billedCount++;
                item.billing().instalment().ifPresent(billedInstalments::add);
            }
        }
        int unbilled = 0;
        for (BillableSchedule.Instalment instalment : latest.instalments()) {
            if (!billedInstalments.contains(instalment.id())) {
                unbilled++;
            }
        }
        if (remainingInstalments < 1 || remainingInstalments > ScheduleEngineV1.MAXIMUM_TERM_MONTHS) {
            throw new LoanTerms.TermsInvalid("the remaining instalments are 1 to " + ScheduleEngineV1.MAXIMUM_TERM_MONTHS);
        }
        if (remainingInstalments < unbilled) {
            throw new LoanTerms.TermsInvalid("an amendment never shortens the remaining term - " + unbilled
                    + " instalments remain");
        }
        int termMonths = billedCount + remainingInstalments;
        int maximum = bounds.amortisationBounds(account.product())
                .orElseThrow(() -> new IllegalStateException("an instalment product has amortisation bounds"))
                .maximumTermMonths();
        if (termMonths > maximum) {
            throw new LoanTerms.TermsInvalid("the loan's whole term - instalments billed and remaining - is at most "
                    + maximum + " months");
        }
        // The principal outstanding from postings: the due state derived from the rows, proven against the ledger first.
        Money zero = Money.ofPersisted(0, account.currency(), agreement.amount().scale());
        LoanReceivables receivables = new LoanReceivables(billingStore, ledgerAccounts, balances);
        Accounts accounts = receivables.accounts(unitOfWork, loan, account.currency());
        DueState due = receivables.dueState(unitOfWork, loan, accounts, zero);
        List<DueState.DueItem> arrearsItems = new ArrayList<>();
        Money arrears = zero;
        if (rescheduleArrears) {
            for (DueState.DueItem item : due.items()) {
                // Past due: billed, unpaid at the end of its due date (PHASE_11_PLAN.md section 12.7) - its principal only.
                if (item.dueDate().isBefore(today) && item.principal().isPositive()) {
                    arrearsItems.add(item);
                    arrears = arrears.plus(item.principal());
                }
            }
        }
        Money principal = due.notDuePrincipal().plus(arrears);
        if (!principal.isPositive()) {
            throw new LoanTerms.TermsInvalid("no principal outstanding to restructure");
        }
        // The open period: the latest calendar's period holding today - the new schedule starts where it starts, so the
        // accruals already posted in it are billed by the new first instalment, and its first due date is that period's.
        AccrualPeriod open = servicingStore.calendarOf(unitOfWork, loan)
                .orElseThrow(() -> new IllegalStateException("a scheduled loan has its calendar"))
                .periodOf(today);
        LoanTerms governing = pinned.toBuilder().nominalAnnualRate(rate).build();
        ScheduleOutcome outcome = ScheduleEngineV1.recalculate(governing, principal, remainingInstalments,
                agreement.repaymentDay(), open.start(), open.endExclusive());
        if (!(outcome instanceof ScheduleOutcome.Projected projected)) {
            throw new LoanTerms.TermsInvalid("the schedule engine refuses the amended schedule: "
                    + ((ScheduleOutcome.Refused) outcome).reason().name());
        }
        return new Plan(latest, unbilled, termMonths, List.copyOf(arrearsItems), arrears, governing, projected.schedule());
    }

    /** The document of {@code amendment}, re-rendered and held to the hash its proposal stored. */
    private String document(Connection unitOfWork, LoanAgreement agreement, LoanAmendment amendment) {
        LoanTerms pinned = pinned(unitOfWork, agreement);
        String document = AmendmentDocument.render(amendment.id(), amendment.loan(), amendment.amendsVersion(),
                amendment.amendsRate(), amendment.nominalAnnualRate(), amendment.remainingInstalments(),
                amendment.rescheduleArrears(), pinned.offerValidityDays());
        if (!java.util.Arrays.equals(CanonicalOfferTerms.sha256(document), amendment.documentSha256())) {
            throw new IllegalStateException("an amendment's document re-renders to the bytes its proposal hashed");
        }
        return document;
    }

    private LoanAmendment found(Connection unitOfWork, LoanId loan, UUID id) {
        return store.find(unitOfWork, loan, id)
                .orElseThrow(() -> new IllegalStateException("an amendment written in this transaction is readable in it"));
    }

    private LoanTerms pinned(Connection unitOfWork, LoanAgreement agreement) {
        return terms.version(unitOfWork, agreement.termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"))
                .terms();
    }

    // ------------------------------------------------------------------ rule 13

    /** {@code DR LOAN_PRINCIPAL / CR LOAN_PRINCIPAL_DUE} for the arrears - one entry under the amendment's own key. */
    private JournalEntryId postRule13(Connection unitOfWork, LoanId loan, UUID amendment, Plan plan, LocalDate today) {
        Accounts accounts = new LoanReceivables(billingStore, ledgerAccounts, balances)
                .accounts(unitOfWork, loan, plan.arrears().currency());
        PostingResult result = postings.post(unitOfWork, new PostingCommand(POSTING_KEY_PREFIX + amendment, today, today,
                amendment.toString(), List.of(
                        new JournalLine(accounts.principal(), Direction.DEBIT, plan.arrears()),
                        new JournalLine(accounts.principalDue(), Direction.CREDIT, plan.arrears()))));
        if (result.replayed()) {
            throw new IllegalStateException("an amendment's rule 13 entry exists for an amendment not yet accepted"
                    + " (INV-IDEM-01)");
        }
        return result.entryId();
    }

    // ------------------------------------------------------------------ the event and the audit

    /**
     * {@code lending.LoanAmended} - the amendment, agreement version n+1 and its hash, the schedule version, the effective
     * date, the arrears re-scheduled and rule 13's entry; never a rate, a reason or a party.
     */
    private void publish(Connection unitOfWork, Loan loan, LoanAmendment amendment, LoanAgreement amended,
            RepaymentSchedule schedule, Plan plan, Optional<JournalEntryId> entry, LocalDate today,
            CorrelationId correlation) {
        Money arrears = plan.arrears();
        EventPayload payload = EventPayload.of()
                .with("loanId", loan.id().value().toString())
                // P11-TSK-028: every LoanAmended names its origin - an amendment's, or a prepayment's (Prepayments.ORIGIN).
                .with("origin", ORIGIN)
                .with("amendmentId", amendment.id().toString())
                .with("agreementVersion", Integer.toString(amended.version()))
                .with("amendsVersion", Integer.toString(amendment.amendsVersion()))
                .with("termsSha256", amended.termsSha256Hex())
                .with("scheduleVersion", Integer.toString(schedule.version()))
                .with("effectiveDate", today.toString())
                .with("remainingInstalments", Integer.toString(amendment.remainingInstalments()))
                .with("arrearsRescheduledMinor", Long.toString(arrears.minorUnits()))
                .with("scale", Integer.toString(arrears.scale()))
                .with("currency", arrears.currency().code());
        if (entry.isPresent()) {
            payload = payload.with("journalEntryId", entry.get().value().toString());
        }
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        AMENDED_EVENT,
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

    private void audit(Connection unitOfWork, Actor actor, LendingAuditAction action, UUID amendment,
            Optional<String> reason, String summary, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, TARGET_TYPE,
                amendment.toString(), reason, AuditOutcome.SUCCEEDED, correlation, Optional.of(summary)));
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** No such loan, or no such amendment of it (for the customer: none ever offered to them). */
    public static final class AmendmentNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AmendmentNotFound() {
            super("no such loan account or amendment");
        }
    }

    /** The agreement it amends moved, the loan is no longer {@code ACTIVE}, or its offer expired unaccepted. */
    public static final class AmendmentLapsed extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AmendmentLapsed() {
            super("the amendment has lapsed: the agreement it amends moved, the loan closed or the offer expired");
        }
    }

    /** The loan is closed or cancelled: nothing on it is amended. */
    public static final class LoanClosed extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        LoanClosed() {
            super("the loan is closed: nothing on it is amended");
        }
    }

    /** An amendment of the loan is already open. */
    public static final class ProposalPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProposalPending() {
            super("an amendment of this loan is already open");
        }
    }

    /** The amendment is decided - by somebody else, or differently - or lapsed. */
    public static final class ProposalNotPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProposalNotPending(LoanAmendmentStatus status) {
            super("the amendment is no longer awaiting a decision: it is " + status.name());
        }
    }

    /** The approver proposed the amendment ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(LoanId loan, UUID amendment) {
            super("an amendment is approved by someone other than its proposer; the proposer may reject it");
            this.target = amendment.toString();
            this.summary = "loan=" + loan.value() + ", amendment=" + amendment + ", act=amendment:approve";
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

    /** The echoed hash is not the amendment's document's: the customer accepts exactly the document shown. */
    public static final class DocumentChanged extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        DocumentChanged() {
            super("the document accepted is not the amendment's");
        }
    }
}
