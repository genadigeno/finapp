package com.finapp.bnpl;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
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
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * BNPL disputes - T-dsp (`P12-TSK-023`; ADR-0106; {@code PHASE_12_PLAN.md} sections 5.8, 7.1, 7.2, 7.4, 12.7 and 12.8;
 * {@code BNPL_LIFECYCLES.md} section 4.10; {@code INV-BNPL-08}, {@code -07}, {@code -06}, {@code INV-LIFE-01}...{@code -04},
 * {@code INV-AUD-04}, {@code INV-MER-01}). A customer contests a BNPL purchase with the platform - no card network, so no
 * chargeback; the disputed amount is held until the merchant accepts, its response deadline passes (its silence is
 * acceptance, OQ-10), the customer withdraws, or - once the merchant answered - an operator resolves (four-eyes above the
 * pinned threshold). Every act is one transaction on the caller's unit of work, which the door's idempotency claim wraps;
 * every refusal is thrown before any write.
 *
 * <h2>The order, which is the Phase 12 lock order (B5, B4, B6, then (P), L8)</h2>
 *
 * <ol>
 *   <li>the texts screened (reason, statement - {@code RESTRICTED-PII});
 *   <li>a plain read of the dispute (ids frozen) to find its purchase - owner-scoped: the customer's {@code party_id}, the
 *       merchant's {@code merchant_id}; another's is the same {@link DisputeNotFound} as an absent one;
 *   <li><strong>(B5)</strong> the purchase {@code FOR UPDATE} (the merchant's tenant in the statement) - the one bound's
 *       arbiter every refund takes first;
 *   <li><strong>(B4)</strong> the obligation {@code FOR UPDATE} - so a collection, a condition step or a repayment either
 *       committed before the hold or sees it;
 *   <li>at the opening, <strong>(P)</strong> the merchant's payable alone {@code FOR UPDATE}, refused when it cannot be
 *       debited (a closed merchant) - the merchant close takes the same row and asks whether a dispute is open;
 *   <li><strong>(B6)</strong> the dispute {@code FOR UPDATE}, the act judged against its from-state and - for the merchant
 *       and the sweep - its deadline on the database clock read after the lock (complementary: exactly one wins);
 *   <li>on {@code RESOLVED_REFUND}: T-ref's body ({@link BnplRefunds#forDispute}) in the same transaction, door
 *       {@code DISPUTE}, for exactly the disputed amount - (P) and L8 inside it;
 *   <li>then only the edge (conditional on its from-state), the history row, the outbox and the audit record.
 * </ol>
 *
 * <p><strong>The hold</strong> is born at the opening: the instalments a refund of the amount would cancel now, latest
 * first ({@link BnplRefunds#heldBy} - the allocator as a dry run under B4), each with its held principal. The collection,
 * condition and late-fee steps read it ({@link BnplPurchaseStore#disputeHold}); it lifts at any resolution. <strong>Payoff
 * and voluntary repayment are not paused</strong> - the customer's own acts; what they pay on held instalments returns as
 * excess if the dispute resolves as a refund. No L0, no L6: no outcome raises exposure. No JVM state.
 *
 * <p><strong>The Phase 12 to 13 gate's repairs</strong> (R1): an opening on a {@code CLOSED} obligation takes the payable
 * and the wallet as one (P) element and refuses a customer with no {@code ACTIVE} wallet - the only account its refund could
 * post to - {@link WalletUnavailable} (A7-1); the merchant's contest publishes {@link #CONTESTED_EVENT} and the operator
 * reads the contested disputes as a queue ({@link #queue}, A7-2); the merchant's edges are conditional on the deadline in
 * their own statement, so an acceptance that waited at (P) past it is {@link DisputeNotActionable}, never a storage failure
 * (A3-M2); the four-eyes threshold is judged on the purchase's cumulative refunds by dispute ({@link #fourEyesAmount},
 * A5-F7); the self-dealing judgement fails closed on an operator the platform cannot place in a party, and covers the
 * operator's read (A7-6, A5-F4). A withdrawal's lift never restarts the clock ({@link BnplDisputeHold}, A5-F2).
 */
@RequiredArgsConstructor
public final class BnplDisputes {

    /** The event the opening publishes. */
    public static final String OPENED_EVENT = "bnpl.BnplDisputeOpened";

    /** The event every resolution and withdrawal publishes (the outcome in it). */
    public static final String RESOLVED_EVENT = "bnpl.BnplDisputeResolved";

    /**
     * The event the merchant's contest publishes - the dispute now awaits an operator, its hold standing (the Phase 12 to 13
     * gate's A7-2: a contested dispute is never silent).
     */
    public static final String CONTESTED_EVENT = "bnpl.BnplDisputeContested";

    /** The dispute's audit target type and aggregate type. */
    public static final String DISPUTE_TARGET = "bnpl_dispute";

    /** A proposal's audit target type. */
    public static final String PROPOSAL_TARGET = "bnpl_dispute_proposal";

    /** The operator queue's audit target type - its target id the status read (the gate's A7-2). */
    public static final String QUEUE_TARGET = "bnpl_dispute_queue";

    /** The most disputes one page of the operator's queue serves. */
    public static final int QUEUE_PAGE = 100;

    /** The longest key a customer may open a dispute under: the door's own bound. */
    public static final int MAX_REQUEST_KEY_LENGTH = com.finapp.platform.idempotency.IdempotencyKey.MAX_LENGTH;

    @NonNull private final BnplDisputeStore store;
    @NonNull private final BnplRefundStore purchaseLocks;
    @NonNull private final BnplServicingStore servicing;
    @NonNull private final BnplPurchaseStore purchases;
    @NonNull private final BnplTermsStore terms;
    @NonNull private final BnplRefunds refunds;
    @NonNull private final BnplMerchants merchants;
    @NonNull private final BnplBook book;
    @NonNull private final BnplActingParty actingParties;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** What an act did - the meter's outcome. */
    public enum Outcome {
        OPENED,
        RESPONDED,
        RESOLVED_REFUND,
        RESOLVED_UPHELD,
        WITHDRAWN,
        PROPOSED,
        PROPOSAL_REJECTED
    }

    /** The merchant's answer: contest with evidence, or accept liability (a refund at once). */
    public enum Decision {
        CONTEST,
        ACCEPT
    }

    /** What the customer asks: the amount, under their key, with a category and a reason. */
    public record OpenRequest(String requestKey, Money amount, BnplDisputeReason category, String reason) {

        public OpenRequest {
            Objects.requireNonNull(requestKey, "requestKey must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(category, "category must not be null");
            if (requestKey.isEmpty() || requestKey.length() > MAX_REQUEST_KEY_LENGTH) {
                throw new IllegalArgumentException("a dispute key is 1 to " + MAX_REQUEST_KEY_LENGTH + " characters");
            }
        }

        /** Never the key, the amount or the reason ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "OpenRequest[redacted]";
        }
    }

    /** The merchant's response - the key id that acted is public (ADR-0052), for the audit record. */
    public record Response(Decision decision, String statement, String keyId) {

        public Response {
            Objects.requireNonNull(decision, "decision must not be null");
            Objects.requireNonNull(keyId, "keyId must not be null");
        }

        @Override
        public String toString() {
            return "Response[" + decision + "]";
        }
    }

    /** What an act committed - or, {@code replayed}, the state a retried act converged on. */
    public record Acted(BnplDispute dispute, Optional<BnplRefunds.Refunded> refund, Optional<BnplDisputeProposal> proposal,
            Outcome outcome, boolean replayed) {

        public Acted {
            Objects.requireNonNull(dispute, "dispute must not be null");
            Objects.requireNonNull(refund, "refund must not be null");
            Objects.requireNonNull(proposal, "proposal must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
        }
    }

    /** What the operator's read shows: the dispute and its live proposal. */
    public record Investigated(BnplDispute dispute, Optional<BnplDisputeProposal> proposal) {}

    /** One page of the operator's queue, and the cursor of the next when more remain. */
    public record Queue(List<BnplDispute> disputes, Optional<BnplDisputeId> next) {

        public Queue {
            disputes = List.copyOf(Objects.requireNonNull(disputes, "disputes must not be null"));
            Objects.requireNonNull(next, "next must not be null");
        }
    }

    // ------------------------------------------------------------------ the customer's doors

    /**
     * {@code party} opens a dispute of their own agreement's purchase - the hold placed, the deadline stamped.
     *
     * @throws DisputeNotFound no agreement of {@code party} has the id
     * @throws DisputeOpen the purchase already has an open dispute
     * @throws BnplRefunds.PurchaseFullyRefunded nothing remains of the purchase to dispute
     * @throws BnplRefunds.RefundExceedsPurchase {@code D > G - refunded}
     * @throws BnplRefunds.RefundNotPostable the merchant's payable cannot be debited (a closed merchant)
     * @throws WalletUnavailable the obligation is {@code CLOSED} and the customer has no {@code ACTIVE} wallet a refund
     *     outcome could be credited to (the gate's A7-1)
     */
    public Acted open(Connection unitOfWork, BnplAgreementId agreement, UUID party, OpenRequest request, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reason = BnplReasons.reasoned(request.reason());
        BnplPurchaseId purchaseId = purchases.agreementOwnedBy(unitOfWork, agreement, party)
                .map(owned -> owned.purchase().id()).orElseThrow(DisputeNotFound::new);
        // (B5) The purchase - the bound's arbiter; its party re-read under the lock.
        BnplRefundStore.LockedPurchase locked = purchaseLocks.lock(unitOfWork, purchaseId)
                .filter(found -> found.purchase().party().equals(party)).orElseThrow(DisputeNotFound::new);
        // A dispute born under this key: the arbiter behind a claim that lapsed.
        Optional<BnplDispute> born = store.findByKey(unitOfWork, party, request.requestKey());
        if (born.isPresent()) {
            if (!born.get().purchase().equals(purchaseId) || !born.get().amount().equals(request.amount())) {
                throw new DisputeKeyReused();
            }
            return new Acted(born.get(), Optional.empty(), Optional.empty(), Outcome.OPENED, true);
        }
        Money amount = request.amount();
        Money gross = locked.gross();
        if (!amount.isPositive() || !amount.currency().equals(gross.currency()) || amount.scale() != gross.scale()) {
            throw new AmountInvalid();
        }
        Money refundedBefore = Money.ofPersisted(0, gross.currency(), gross.scale());
        for (BnplRefund refund : purchaseLocks.ofPurchase(unitOfWork, purchaseId)) {
            refundedBefore = refundedBefore.plus(refund.amount());
        }
        BnplRefunds.Kind kind = amount.equals(gross.minus(refundedBefore)) ? BnplRefunds.Kind.FULL
                : BnplRefunds.Kind.PARTIAL;
        if (locked.purchase().status() == BnplPurchaseStatus.FULLY_REFUNDED) {
            throw new BnplRefunds.PurchaseFullyRefunded(kind);
        }
        if (amount.compareTo(gross.minus(refundedBefore)) > 0) {
            throw new BnplRefunds.RefundExceedsPurchase(kind);
        }
        if (store.openOf(unitOfWork, purchaseId).isPresent()) {
            throw new DisputeOpen();
        }
        // (B4) The obligation: the hold judged on the due state no other servicing act can move meanwhile.
        BnplServicingStore.Locked obligation = servicing.lock(unitOfWork, locked.obligation())
                .orElseThrow(() -> new IllegalStateException("a financed purchase has its obligation (INV-BNPL-02)"));
        List<BnplDispute.Held> held = refunds.heldBy(unitOfWork, locked, obligation, amount, refundedBefore);
        // (P) the merchant's payable alone, FOR UPDATE (ADR-0106 decision 11): a dispute is opened only against a
        // merchant whose payable can still be debited by its refund outcome - and the merchant's close, which takes the
        // same row FOR UPDATE and asks whether a dispute is open, is ordered before or after this opening, never beside it.
        LedgerAccountId payable = merchants.payableAccount(unitOfWork, locked.purchase().merchant(), gross.currency())
                .orElseThrow(() -> new BnplRefunds.RefundNotPostable(kind));
        if (obligation.obligation().status() == BnplObligationStatus.ACTIVE) {
            if (book.lockForUpdate(unitOfWork, payable).filter(LedgerAccountStatus.ACTIVE::equals).isEmpty()) {
                throw new BnplRefunds.RefundNotPostable(kind);
            }
        } else {
            // The Phase 12 to 13 gate's A7-1: on a CLOSED obligation a refund outcome cancels nothing and holds no credit
            // balance (INV-BNPL-09), so the whole of D can only be credited to the customer's wallet - a dispute whose
            // refund could post nowhere would lapse into a retry no one sees. So the opening takes (P) as the refund will:
            // the payable and the wallet as ONE element in UUID order (BnplAcceptances.lockAsOneElement), and refuses a
            // customer with no ACTIVE wallet (409 bnpl.WalletUnavailable, nothing written). The wallet close, which takes
            // the wallet's row FOR UPDATE and asks whether a dispute may still refund to it, is ordered before or after
            // this opening, never beside it.
            Optional<LedgerAccountId> wallet = refunds.existingWallet(unitOfWork, party, gross.currency());
            Map<LedgerAccountId, LedgerAccountStatus> pair = BnplAcceptances.lockAsOneElement(book, unitOfWork, payable,
                    wallet);
            if (pair.get(payable) != LedgerAccountStatus.ACTIVE) {
                throw new BnplRefunds.RefundNotPostable(kind);
            }
            if (wallet.map(pair::get).filter(LedgerAccountStatus.ACTIVE::equals).isEmpty()) {
                throw new WalletUnavailable();
            }
        }
        BnplDispute dispute = new BnplDispute(BnplDisputeId.next(ids), purchaseId, obligation.obligation().id(),
                locked.purchase().merchant(), party, obligation.agreement(), obligation.termsVersionId(),
                BnplDisputeStatus.OPEN, request.category(), reason, request.requestKey(), amount, refundedBefore,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), correlation,
                held);
        BnplDispute opened = store.insert(unitOfWork, dispute);
        event(unitOfWork, opened, Optional.empty(), customer(party), correlation);
        publish(unitOfWork, OPENED_EVENT, opened, correlation);
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), BnplAuditAction.DISPUTE_OPENED,
                DISPUTE_TARGET, opened.id().value().toString(), Optional.of(reason), AuditOutcome.SUCCEEDED, correlation,
                Optional.of("purchase=" + purchaseId.value() + ", category=" + request.category() + ", deadline="
                        + opened.responseDeadline().map(Instant::toString).orElse("") + ", held instalments="
                        + held.size())));
        return new Acted(opened, Optional.empty(), Optional.empty(), Outcome.OPENED, false);
    }

    /**
     * {@code party} withdraws their own open dispute - the hold lifted, nothing posted.
     *
     * @throws DisputeNotFound no dispute of {@code party} has the id
     * @throws DisputeNotActionable it is already resolved
     */
    public Acted withdraw(Connection unitOfWork, BnplDisputeId id, UUID party, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        BnplDispute found = store.findOfParty(unitOfWork, id, party).orElseThrow(DisputeNotFound::new);
        BnplRefundStore.LockedPurchase locked = lockAll(unitOfWork, found, Optional.empty());
        BnplDispute dispute = lockDispute(unitOfWork, id);
        if (dispute.status() == BnplDisputeStatus.WITHDRAWN) {
            return new Acted(dispute, Optional.empty(), Optional.empty(), Outcome.WITHDRAWN, true);
        }
        if (!dispute.open()) {
            throw new DisputeNotActionable();
        }
        BnplDispute withdrawn = moved(unitOfWork, dispute.resolved(BnplDisputeStatus.WITHDRAWN, customer(party),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), dispute.status());
        event(unitOfWork, withdrawn, Optional.of(dispute.status()), customer(party), correlation);
        publish(unitOfWork, RESOLVED_EVENT, withdrawn, correlation);
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                BnplAuditAction.DISPUTE_WITHDRAWN, DISPUTE_TARGET, id.value().toString(), Optional.empty(),
                AuditOutcome.SUCCEEDED, correlation, Optional.of("purchase=" + locked.purchase().id().value()
                        + ", from=" + dispute.status())));
        return new Acted(withdrawn, Optional.empty(), Optional.empty(), Outcome.WITHDRAWN, false);
    }

    // ------------------------------------------------------------------ the merchant's door

    /**
     * {@code merchant} answers its purchase's dispute before the deadline - contesting it (an operator resolves) or
     * accepting it (a refund of the disputed amount, at once).
     *
     * @throws DisputeNotFound no dispute of {@code merchant}'s has the id (another merchant's included)
     * @throws DisputeNotActionable it is not {@code OPEN}, or its deadline has passed on the database clock - judged after
     *     the locks, and again by the edge's own statement after any wait at (P) (the gate's A3-M2: never a 5xx)
     */
    public Acted respond(Connection unitOfWork, BnplDisputeId id, UUID merchant, Response response, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(response, "response");
        String statement = BnplReasons.reasoned(response.statement());
        BnplDispute found = store.findAtMerchant(unitOfWork, id, merchant).orElseThrow(DisputeNotFound::new);
        BnplRefundStore.LockedPurchase locked = lockAll(unitOfWork, found, Optional.of(merchant));
        BnplDispute dispute = lockDispute(unitOfWork, id);
        String by = "MERCHANT:" + merchant;
        if (dispute.status() != BnplDisputeStatus.OPEN) {
            // A retried answer converges on what it committed; any other is too late.
            boolean replay = response.decision() == Decision.CONTEST
                    ? dispute.merchantStatement().filter(statement::equals).isPresent()
                            && dispute.resolvedBy().filter(by::equals).isEmpty()
                    : dispute.status() == BnplDisputeStatus.RESOLVED_REFUND && dispute.resolvedBy().filter(by::equals)
                            .isPresent();
            if (replay) {
                return new Acted(dispute, Optional.empty(), Optional.empty(),
                        response.decision() == Decision.CONTEST ? Outcome.RESPONDED : Outcome.RESOLVED_REFUND, true);
            }
            throw new DisputeNotActionable();
        }
        Instant now = servicing.databaseNow(unitOfWork);
        if (!dispute.responseDeadline().orElseThrow().isAfter(now)) {
            throw new DisputeNotActionable(); // The deadline resolves it: the merchant's silence is acceptance (OQ-10).
        }
        if (response.decision() == Decision.CONTEST) {
            BnplDispute responded = moved(unitOfWork, dispute.responded(statement), BnplDisputeStatus.OPEN);
            event(unitOfWork, responded, Optional.of(BnplDisputeStatus.OPEN), by, correlation);
            // The gate's A7-2: the contest is announced - the dispute now awaits an operator, its hold standing.
            publish(unitOfWork, CONTESTED_EVENT, responded, correlation);
            auditResponded(unitOfWork, responded, response, statement, actor, correlation);
            return new Acted(responded, Optional.empty(), Optional.empty(), Outcome.RESPONDED, false);
        }
        BnplRefunds.Refunded refunded = refunds.forDispute(unitOfWork, locked, dispute,
                "the merchant accepted the customer's dispute", actor, correlation);
        BnplDispute resolved = moved(unitOfWork, dispute.resolved(BnplDisputeStatus.RESOLVED_REFUND, by,
                Optional.of(statement), Optional.empty(), Optional.of(refunded.refund().id()), Optional.empty()),
                BnplDisputeStatus.OPEN);
        event(unitOfWork, resolved, Optional.of(BnplDisputeStatus.OPEN), by, correlation);
        publish(unitOfWork, RESOLVED_EVENT, resolved, correlation);
        auditResponded(unitOfWork, resolved, response, statement, actor, correlation);
        auditResolved(unitOfWork, resolved, "merchant", Optional.empty(), actor, correlation);
        return new Acted(resolved, Optional.of(refunded), Optional.empty(), Outcome.RESOLVED_REFUND, false);
    }

    // ------------------------------------------------------------------ the deadline sweep

    /** The sweep's claim (see {@link BnplDisputeStore#claimLapsed}). */
    public List<BnplDisputeId> claim(Connection unitOfWork, int limit, Duration cadence) {
        return store.claimLapsed(unitOfWork, limit, cadence);
    }

    /**
     * One lapsed dispute resolved as a refund - the merchant's silence is acceptance (OQ-10) - under the system actor:
     * B5, B4, B6, the deadline re-judged on the database clock read after the lock. Empty when there is nothing to do: no
     * longer {@code OPEN} (the merchant answered, or the customer withdrew, first), or the deadline not yet come.
     */
    public Optional<Acted> lapse(Connection unitOfWork, BnplDisputeId id, Actor system, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(system, "system");
        if (system.type() != ActorType.SYSTEM) {
            throw new IllegalStateException("the deadline sweep is the system's");
        }
        Optional<BnplDispute> found = store.find(unitOfWork, id);
        if (found.isEmpty() || found.get().status() != BnplDisputeStatus.OPEN) {
            return Optional.empty();
        }
        BnplRefundStore.LockedPurchase locked = lockAll(unitOfWork, found.get(), Optional.empty());
        BnplDispute dispute = lockDispute(unitOfWork, id);
        Instant now = servicing.databaseNow(unitOfWork);
        if (dispute.status() != BnplDisputeStatus.OPEN || dispute.responseDeadline().orElseThrow().isAfter(now)) {
            return Optional.empty();
        }
        BnplRefunds.Refunded refunded = refunds.forDispute(unitOfWork, locked, dispute,
                "the merchant did not answer the customer's dispute by its deadline", system, correlation);
        BnplDispute resolved = moved(unitOfWork, dispute.resolved(BnplDisputeStatus.RESOLVED_REFUND, BnplDispute.SYSTEM,
                Optional.empty(), Optional.empty(), Optional.of(refunded.refund().id()), Optional.empty()),
                BnplDisputeStatus.OPEN);
        event(unitOfWork, resolved, Optional.of(BnplDisputeStatus.OPEN), BnplDispute.SYSTEM, correlation);
        publish(unitOfWork, RESOLVED_EVENT, resolved, correlation);
        auditResolved(unitOfWork, resolved, "deadline", Optional.empty(), system, correlation);
        return Optional.of(new Acted(resolved, Optional.of(refunded), Optional.empty(), Outcome.RESOLVED_REFUND, false));
    }

    // ------------------------------------------------------------------ the operator's doors

    /**
     * An operator resolves a dispute the merchant answered - at or below the pinned four-eyes threshold at once; above it, a
     * proposal another operator approves or rejects.
     *
     * @throws DisputeNotFound no dispute has the id
     * @throws SelfDealingRefused the dispute is the operator's own party's, or the operator has no party the platform knows
     * @throws DisputeNotActionable it is not {@code MERCHANT_RESPONDED}
     * @throws ProposalPending a proposal already awaits a decision
     */
    public Acted resolve(Connection unitOfWork, BnplDisputeId id, BnplDisputeStatus outcome, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(outcome, "outcome");
        requirePerson(actor);
        if (outcome != BnplDisputeStatus.RESOLVED_REFUND && outcome != BnplDisputeStatus.RESOLVED_UPHELD) {
            throw new IllegalArgumentException("an operator resolves a dispute as a refund or upheld");
        }
        String why = BnplReasons.reasoned(reason);
        BnplDispute found = store.find(unitOfWork, id).orElseThrow(DisputeNotFound::new);
        refuseSelfDealing(unitOfWork, found, actor, "dispute resolution");
        BnplRefundStore.LockedPurchase locked = lockAll(unitOfWork, found, Optional.empty());
        BnplDispute dispute = lockDispute(unitOfWork, id);
        String by = operator(actor);
        if (dispute.status() != BnplDisputeStatus.MERCHANT_RESPONDED) {
            if (dispute.status() == outcome && dispute.resolvedBy().filter(by::equals).isPresent()) {
                return new Acted(dispute, Optional.empty(), Optional.empty(), outcomeOf(outcome), true);
            }
            throw new DisputeNotActionable();
        }
        if (aboveThreshold(unitOfWork, dispute)) {
            Optional<BnplDisputeProposal> live = store.liveProposal(unitOfWork, id);
            if (live.isPresent()) {
                if (live.get().proposedBy().equals(by) && live.get().outcome() == outcome
                        && live.get().status() == BnplDisputeProposal.Status.PROPOSED) {
                    return new Acted(dispute, Optional.empty(), live, Outcome.PROPOSED, true);
                }
                throw new ProposalPending();
            }
            BnplDisputeProposal proposal = store.insertProposal(unitOfWork, new BnplDisputeProposal(ids.next(), id,
                    outcome, BnplDisputeProposal.Status.PROPOSED, by, why, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), correlation));
            audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                    BnplAuditAction.DISPUTE_RESOLUTION_PROPOSED, PROPOSAL_TARGET, proposal.id().toString(),
                    Optional.of(why), AuditOutcome.SUCCEEDED, correlation,
                    Optional.of("dispute=" + id.value() + ", outcome=" + outcome)));
            return new Acted(dispute, Optional.empty(), Optional.of(proposal), Outcome.PROPOSED, false);
        }
        return execute(unitOfWork, locked, dispute, outcome, by, why, Optional.empty(), actor, correlation);
    }

    /**
     * A second operator approves a proposal - the resolution it proposed executed now.
     *
     * @throws SelfApprovalRefused the approver proposed it
     */
    public Acted approve(Connection unitOfWork, BnplDisputeId id, UUID proposalId, String reason, Actor actor,
            CorrelationId correlation) {
        requirePerson(actor);
        String why = BnplReasons.reasoned(reason);
        BnplDispute found = store.find(unitOfWork, id).orElseThrow(DisputeNotFound::new);
        refuseSelfDealing(unitOfWork, found, actor, "dispute resolution approval");
        BnplRefundStore.LockedPurchase locked = lockAll(unitOfWork, found, Optional.empty());
        BnplDispute dispute = lockDispute(unitOfWork, id);
        BnplDisputeProposal proposal = store.lockProposal(unitOfWork, proposalId, id).orElseThrow(DisputeNotFound::new);
        String by = operator(actor);
        if (proposal.status() != BnplDisputeProposal.Status.PROPOSED) {
            if (proposal.status() == BnplDisputeProposal.Status.APPROVED && proposal.decidedBy().filter(by::equals)
                    .isPresent()) {
                return new Acted(dispute, Optional.empty(), Optional.of(proposal), outcomeOf(proposal.outcome()), true);
            }
            throw new DisputeNotActionable();
        }
        if (proposal.proposedBy().equals(by)) {
            throw new SelfApprovalRefused(proposal.id());
        }
        if (dispute.status() != BnplDisputeStatus.MERCHANT_RESPONDED) {
            throw new DisputeNotActionable();
        }
        BnplDisputeProposal approved = store.decide(unitOfWork,
                proposal.decided(BnplDisputeProposal.Status.APPROVED, by, why)).orElseThrow(DisputeNotActionable::new);
        Acted acted = execute(unitOfWork, locked, dispute, approved.outcome(), by, why, Optional.of(approved.id()), actor,
                correlation);
        return new Acted(acted.dispute(), acted.refund(), Optional.of(approved), acted.outcome(), false);
    }

    /** An operator rejects - or its proposer withdraws - a proposal; the dispute awaits a new one. */
    public Acted reject(Connection unitOfWork, BnplDisputeId id, UUID proposalId, String reason, Actor actor,
            CorrelationId correlation) {
        requirePerson(actor);
        String why = BnplReasons.reasoned(reason);
        BnplDispute found = store.find(unitOfWork, id).orElseThrow(DisputeNotFound::new);
        refuseSelfDealing(unitOfWork, found, actor, "dispute resolution rejection");
        lockAll(unitOfWork, found, Optional.empty());
        BnplDispute dispute = lockDispute(unitOfWork, id);
        BnplDisputeProposal proposal = store.lockProposal(unitOfWork, proposalId, id).orElseThrow(DisputeNotFound::new);
        String by = operator(actor);
        if (proposal.status() != BnplDisputeProposal.Status.PROPOSED) {
            if (proposal.status() == BnplDisputeProposal.Status.REJECTED && proposal.decidedBy().filter(by::equals)
                    .isPresent()) {
                return new Acted(dispute, Optional.empty(), Optional.of(proposal), Outcome.PROPOSAL_REJECTED, true);
            }
            throw new DisputeNotActionable();
        }
        BnplDisputeProposal rejected = store.decide(unitOfWork,
                proposal.decided(BnplDisputeProposal.Status.REJECTED, by, why)).orElseThrow(DisputeNotActionable::new);
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                BnplAuditAction.DISPUTE_RESOLUTION_REJECTED, PROPOSAL_TARGET, rejected.id().toString(), Optional.of(why),
                AuditOutcome.SUCCEEDED, correlation, Optional.of("dispute=" + id.value() + ", proposer="
                        + (rejected.proposedBy().equals(by) ? "self" : "another"))));
        return new Acted(dispute, Optional.empty(), Optional.of(rejected), Outcome.PROPOSAL_REJECTED, false);
    }

    // ------------------------------------------------------------------ the reads

    /** {@code merchant}'s open disputes - its own only; never the customer's reason, schedule or condition. */
    public List<BnplDispute> openAtMerchant(Connection unitOfWork, UUID merchant) {
        return store.openAtMerchant(unitOfWork, Objects.requireNonNull(merchant, "merchant"));
    }

    /** Whether {@code merchant} has an open dispute - the merchant close's question (ADR-0106 decision 11). */
    public boolean anyOpenAtMerchant(Connection unitOfWork, UUID merchant) {
        return store.anyOpenAtMerchant(unitOfWork, Objects.requireNonNull(merchant, "merchant"));
    }

    /**
     * Whether a refund outcome of an open dispute of {@code party} may still credit its wallet in {@code currency} - the
     * wallet close's pending-credits question (the Phase 12 to 13 gate's A7-1): any {@code OPEN} or
     * {@code MERCHANT_RESPONDED} dispute of the party in the currency. Asked by the close under its {@code FOR UPDATE} on
     * the wallet's ledger row, which the opening on a {@code CLOSED} obligation and every refund outcome take at (P).
     */
    public boolean anyMayRefundToWalletOf(Connection unitOfWork, UUID party, CurrencyCode currency) {
        return store.anyOpenOfParty(unitOfWork, Objects.requireNonNull(party, "party"),
                Objects.requireNonNull(currency, "currency"));
    }

    /**
     * The operator's read - every access recorded ({@code bnpl.DisputeRead}, {@code INV-DSP-03} by analogy); never a
     * dispute of the operator's own party, nor by an operator the platform cannot place in a party (the gate's A5-F4:
     * {@link SelfDealingRefused}, nothing read, the caller recording the refusal {@code FAILED}).
     */
    public Investigated investigate(Connection unitOfWork, BnplDisputeId id, Actor actor, CorrelationId correlation) {
        requirePerson(actor);
        BnplDispute dispute = store.find(unitOfWork, id).orElseThrow(DisputeNotFound::new);
        refuseSelfDealing(unitOfWork, dispute, actor, "dispute read");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), BnplAuditAction.DISPUTE_READ,
                DISPUTE_TARGET, id.value().toString(), Optional.empty(), AuditOutcome.SUCCEEDED, correlation,
                Optional.of("status=" + dispute.status())));
        return new Investigated(dispute, store.liveProposal(unitOfWork, id));
    }

    /**
     * The operator's queue (the gate's A7-2; ADR-0106's promised dispute report, delivered as the queue): a page of at most
     * {@link #QUEUE_PAGE} disputes in {@code status} - {@code MERCHANT_RESPONDED} (contested, awaiting an operator) or
     * {@code OPEN} (awaiting the merchant or the deadline) - oldest first, after the cursor named, never one of the
     * operator's own party; every page recorded ({@code bnpl.DisputeQueueRead}). An operator the platform cannot place in a
     * party is refused (fail closed).
     *
     * @throws SelfDealingRefused the operator has no party the platform knows
     */
    public Queue queue(Connection unitOfWork, BnplDisputeStatus status, Optional<BnplDisputeId> after, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(after, "after");
        requirePerson(actor);
        if (status != BnplDisputeStatus.OPEN && status != BnplDisputeStatus.MERCHANT_RESPONDED) {
            throw new IllegalArgumentException("the queue is of the open disputes: OPEN or MERCHANT_RESPONDED");
        }
        UUID own = actingParties.partyOf(unitOfWork, actor)
                .orElseThrow(() -> new SelfDealingRefused(QUEUE_TARGET, status.name(), "dispute queue read"));
        List<BnplDispute> read = store.queue(unitOfWork, status, own, after, QUEUE_PAGE + 1);
        boolean more = read.size() > QUEUE_PAGE;
        List<BnplDispute> page = more ? read.subList(0, QUEUE_PAGE) : read;
        Optional<BnplDisputeId> next = more ? Optional.of(page.get(page.size() - 1).id()) : Optional.empty();
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                BnplAuditAction.DISPUTE_QUEUE_READ, QUEUE_TARGET, status.name(), Optional.empty(), AuditOutcome.SUCCEEDED,
                correlation, Optional.of("queue read: " + page.size() + " dispute(s), status " + status
                        + after.map(cursor -> ", after=" + cursor.value()).orElse("") + (more ? ", more remain" : ""))));
        return new Queue(page, next);
    }

    // ------------------------------------------------------------------ the refusals' records

    /** A self-approval - recorded {@code FAILED} in a transaction of its own after the act rolled back (the gate's R4). */
    public void recordSelfApproval(Connection unitOfWork, SelfApprovalRefused refused, Actor actor,
            CorrelationId correlation) {
        SelfApprovalRefusals.record(audit, ids, clock, unitOfWork, actor, PROPOSAL_TARGET, refused.proposal().toString(),
                "proposal=" + refused.proposal() + ", act=dispute resolution approval", correlation);
    }

    /**
     * A self-dealing act - an act or read on a dispute of the operator's own party, or by an operator the platform cannot
     * place in a party - recorded {@code FAILED} in a transaction of its own after the act rolled back.
     */
    public void recordSelfDealing(Connection unitOfWork, SelfDealingRefused refused, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                BnplAuditAction.SELF_DEALING_REFUSED, refused.targetType(), refused.target(), Optional.empty(),
                AuditOutcome.FAILED, correlation,
                Optional.of((DISPUTE_TARGET.equals(refused.targetType()) ? "dispute=" : "queue=") + refused.target()
                        + ", act=" + refused.act() + " refused - the operator's own party, or the operator has no party"
                        + " the platform knows")));
    }

    // ------------------------------------------------------------------ the body

    private Acted execute(Connection unitOfWork, BnplRefundStore.LockedPurchase locked, BnplDispute dispute,
            BnplDisputeStatus outcome, String by, String why, Optional<UUID> approved, Actor actor,
            CorrelationId correlation) {
        Optional<BnplRefunds.Refunded> refunded = outcome == BnplDisputeStatus.RESOLVED_REFUND
                ? Optional.of(refunds.forDispute(unitOfWork, locked, dispute,
                        "an operator resolved the customer's dispute as a refund", actor, correlation))
                : Optional.empty();
        BnplDispute resolved = moved(unitOfWork, dispute.resolved(outcome, by, Optional.empty(), Optional.of(why),
                refunded.map(done -> done.refund().id()), approved), BnplDisputeStatus.MERCHANT_RESPONDED);
        event(unitOfWork, resolved, Optional.of(BnplDisputeStatus.MERCHANT_RESPONDED), by, correlation);
        publish(unitOfWork, RESOLVED_EVENT, resolved, correlation);
        auditResolved(unitOfWork, resolved, approved.isPresent() ? "operator, four-eyes" : "operator", Optional.of(why),
                actor, correlation);
        return new Acted(resolved, refunded, Optional.empty(), outcomeOf(outcome), false);
    }

    /** (B5) then (B4) for {@code found} - the merchant's tenant in the purchase's locking statement when given. */
    private BnplRefundStore.LockedPurchase lockAll(Connection unitOfWork, BnplDispute found, Optional<UUID> merchant) {
        BnplRefundStore.LockedPurchase locked = (merchant.isPresent()
                ? purchaseLocks.lockAtMerchant(unitOfWork, found.purchase(), merchant.get())
                : purchaseLocks.lock(unitOfWork, found.purchase())).orElseThrow(DisputeNotFound::new);
        servicing.lock(unitOfWork, found.obligation())
                .orElseThrow(() -> new IllegalStateException("a financed purchase has its obligation (INV-BNPL-02)"));
        return locked;
    }

    /** (B6) the dispute itself, after B5 and B4. */
    private BnplDispute lockDispute(Connection unitOfWork, BnplDisputeId id) {
        return store.lock(unitOfWork, id).orElseThrow(DisputeNotFound::new);
    }

    private BnplDispute moved(Connection unitOfWork, BnplDispute moved, BnplDisputeStatus from) {
        // Under B6 the from-state was just read; a miss is a writer that bypassed the lock - nothing is written.
        return store.move(unitOfWork, moved, from).orElseThrow(DisputeNotActionable::new);
    }

    private boolean aboveThreshold(Connection unitOfWork, BnplDispute dispute) {
        BnplTerms pinned = terms.version(unitOfWork, dispute.termsVersion())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"))
                .terms();
        return fourEyesAmount(unitOfWork, dispute).compareTo(pinned.disputeFourEyesThreshold()) > 0;
    }

    /**
     * What the four-eyes threshold is judged against (the Phase 12 to 13 gate's A5-F7; {@code bnpl V018}'s
     * {@code bnpl.dispute_four_eyes_amount}, the same rule): the dispute's claim plus every refund another dispute of the
     * purchase already made - the purchase's cumulative refunded-by-dispute amount, this one included - so a contest split
     * into sequential disputes, each at or below the threshold, is judged as the one it is. Read under the caller's B5.
     */
    Money fourEyesAmount(Connection unitOfWork, BnplDispute dispute) {
        Money cumulative = dispute.amount();
        for (BnplRefund refund : purchaseLocks.ofPurchase(unitOfWork, dispute.purchase())) {
            if (refund.door() == BnplRefund.Door.DISPUTE
                    && !refund.dispute().equals(Optional.of(dispute.id().value()))) {
                cumulative = cumulative.plus(refund.amount());
            }
        }
        return cumulative;
    }

    /**
     * Refuses an act or read by an operator of the dispute's own party - or one the platform cannot place in a party at
     * all: fail closed, as the corrections' {@code BnplRepaymentReversals.judgeSelfDealing} (the gate's A7-6).
     */
    private void refuseSelfDealing(Connection unitOfWork, BnplDispute dispute, Actor actor, String act) {
        Optional<UUID> actorsParty = actingParties.partyOf(unitOfWork, actor);
        if (actorsParty.isEmpty() || actorsParty.get().equals(dispute.party())) {
            throw new SelfDealingRefused(dispute.id(), act);
        }
    }

    /** A person's session - an operator session's actor is {@code CUSTOMER}-typed today (the platform's known debt). */
    private static void requirePerson(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        if (actor.type() != ActorType.EMPLOYEE && actor.type() != ActorType.CUSTOMER) {
            throw new IllegalStateException("the operator's dispute doors are a person's");
        }
    }

    private static String operator(Actor actor) {
        return "OPERATOR:" + actor.id();
    }

    private static String customer(UUID party) {
        return "CUSTOMER:" + party;
    }

    private static Outcome outcomeOf(BnplDisputeStatus status) {
        return status == BnplDisputeStatus.RESOLVED_REFUND ? Outcome.RESOLVED_REFUND : Outcome.RESOLVED_UPHELD;
    }

    private void event(Connection unitOfWork, BnplDispute dispute, Optional<BnplDisputeStatus> from, String actor,
            CorrelationId correlation) {
        store.appendEvent(unitOfWork, new BnplDisputeStore.Event(ids.next(), dispute.id(), from, dispute.status(), actor,
                correlation));
    }

    private void auditResponded(Connection unitOfWork, BnplDispute dispute, Response response, String statement,
            Actor actor, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                BnplAuditAction.DISPUTE_RESPONDED, DISPUTE_TARGET, dispute.id().value().toString(),
                Optional.of(statement), AuditOutcome.SUCCEEDED, correlation,
                Optional.of("decision=" + response.decision() + ", key=" + response.keyId())));
    }

    private void auditResolved(Connection unitOfWork, BnplDispute dispute, String door, Optional<String> why, Actor actor,
            CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                BnplAuditAction.DISPUTE_RESOLVED, DISPUTE_TARGET, dispute.id().value().toString(), why,
                AuditOutcome.SUCCEEDED, correlation, Optional.of("outcome=" + dispute.status() + ", by=" + door
                        + dispute.refund().map(refund -> ", refund=" + refund).orElse(""))));
    }

    /**
     * {@code bnpl.BnplDisputeOpened} / {@code bnpl.BnplDisputeResolved} - the dispute, its purchase and obligation, its
     * status, the deadline, the held instalments' sequences and any refund; never the reason, the statement, the party or
     * an amount (the refund's own event carries the figures).
     */
    private void publish(Connection unitOfWork, String type, BnplDispute dispute, CorrelationId correlation) {
        EventPayload payload = EventPayload.of()
                .with("disputeId", dispute.id().value().toString())
                .with("purchaseId", dispute.purchase().value().toString())
                .with("obligationId", dispute.obligation().value().toString())
                .with("status", dispute.status().name())
                .with("category", dispute.category().name());
        if (dispute.responseDeadline().isPresent()) {
            payload = payload.with("responseDeadlineEpochMilli",
                    Long.toString(dispute.responseDeadline().get().toEpochMilli()));
        }
        if (!dispute.holds().isEmpty()) {
            payload = payload.with("heldInstalments", String.join("_",
                    dispute.holds().stream().map(held -> Integer.toString(held.sequence())).toList()));
        }
        if (dispute.refund().isPresent()) {
            payload = payload.with("refundId", dispute.refund().get().toString());
        }
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        type,
                        BnplTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        dispute.id(),
                        DISPUTE_TARGET,
                        clock.instant(),
                        BnplTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** No dispute (or agreement) the door may act on has the id - another party's or merchant's included. */
    public static final class DisputeNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        DisputeNotFound() {
            super("no such BNPL dispute");
        }
    }

    /** The purchase already has an open dispute. */
    public static final class DisputeOpen extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        DisputeOpen() {
            super("the BNPL purchase already has an open dispute; nothing was written");
        }
    }

    /** The dispute is not in the act's from-state, or the merchant's window has closed. */
    public static final class DisputeNotActionable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        DisputeNotActionable() {
            super("the BNPL dispute is not open to this act; nothing was written");
        }
    }

    /** A dispute was born under this key for another purchase or amount. */
    public static final class DisputeKeyReused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        DisputeKeyReused() {
            super("the key was already used for a different dispute");
        }
    }

    /** The amount is not positive, or not in the purchase's currency and scale. */
    public static final class AmountInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AmountInvalid() {
            super("the amount is not a positive amount in the purchase's currency");
        }
    }

    /** A proposal already awaits a decision. */
    public static final class ProposalPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProposalPending() {
            super("a resolution proposal already awaits a decision");
        }
    }

    /** The approver proposed it ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final UUID proposal;

        SelfApprovalRefused(UUID proposal) {
            super("an operator never approves their own resolution proposal");
            this.proposal = proposal;
        }

        /** The proposal - the refusal's audit target. */
        public UUID proposal() {
            return proposal;
        }
    }

    /**
     * The dispute is the operator's own party's - or the platform cannot place the operator in a party at all (fail closed,
     * the corrections' rule; the Phase 12 to 13 gate's A7-6). Its target the dispute, or - for the queue - the status read.
     */
    public static final class SelfDealingRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String targetType;
        private final String target;
        private final String act;

        SelfDealingRefused(BnplDisputeId dispute) {
            this(dispute, "dispute resolution");
        }

        SelfDealingRefused(BnplDisputeId dispute, String act) {
            this(DISPUTE_TARGET, dispute.value().toString(), act);
        }

        SelfDealingRefused(String targetType, String target, String act) {
            super("an employee never acts on a BNPL dispute of their own party, nor as one the platform cannot place in a"
                    + " party");
            this.targetType = Objects.requireNonNull(targetType, "targetType");
            this.target = Objects.requireNonNull(target, "target");
            this.act = Objects.requireNonNull(act, "act");
        }

        /** The dispute, when the refused act named one. */
        public Optional<BnplDisputeId> dispute() {
            return DISPUTE_TARGET.equals(targetType) ? Optional.of(BnplDisputeId.of(UUID.fromString(target)))
                    : Optional.empty();
        }

        /** The refusal's audit target type - the dispute's, or the queue's. */
        public String targetType() {
            return targetType;
        }

        /** The refusal's audit target - the dispute's id, or the queue's status. */
        public String target() {
            return target;
        }

        /** The act refused. */
        public String act() {
            return act;
        }
    }

    /**
     * A dispute of a {@code CLOSED} obligation whose refund outcome could be credited nowhere: the customer has no
     * {@code ACTIVE} wallet (the Phase 12 to 13 gate's A7-1; {@code 409 bnpl.WalletUnavailable}). Nothing was written.
     */
    public static final class WalletUnavailable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        WalletUnavailable() {
            super("the BNPL agreement is closed and the customer has no active wallet its dispute's refund could be credited"
                    + " to; nothing was written");
        }
    }
}
