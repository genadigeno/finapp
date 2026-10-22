package com.finapp.bnpl;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.creditmath.Schedule;
import com.finapp.creditmath.ScheduleEngineV1;
import com.finapp.creditmath.ScheduleOutcome;
import com.finapp.creditmath.SchedulePeriod;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.PostingCommand;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A merchant refund of a BNPL purchase - T-ref (`P12-TSK-021`; owner decisions B3 and B7, ADR-0105; {@code PHASE_12_PLAN.md}
 * sections 7.1, 7.2, 12.6 and 12.11 F5/F5a; {@code BNPL_LIFECYCLES.md} section 4.9; {@code INV-BNPL-06}, {@code -07},
 * {@code -09}, {@code INV-MER-07} as amended, {@code INV-HIST-04}, {@code INV-IDEM-01}). <strong>One body for every
 * door</strong>: the merchant's ({@link #atMerchant}, over its key with the BNPL refund scope, tenant-scoped in the lock's
 * statement), the operator's beside it ({@link #byOperator}) and - later - a dispute resolved as a refund (`-023`, under
 * the locks T-dsp holds). One transaction on the caller's unit of work, which the door's idempotency claim wraps: a retried
 * key replays; every refusal is thrown before any write, so it writes nothing and rolls the claim back with it.
 *
 * <h2>The order, which is the Phase 12 lock order (B5, B4, (P), L8)</h2>
 *
 * <ol>
 *   <li>the reason screened (required, bounded, no instrument shape - {@code RESTRICTED-PII});
 *   <li><strong>(B5)</strong> the purchase {@code FOR UPDATE} with its financing and obligation - the refund bound's
 *       arbiter, which a dispute takes first too; at the merchant's door {@code merchant_id = ?} in the statement, so
 *       another merchant's purchase is the same {@code 404} as an absent one ({@code INV-MER-01});
 *   <li>a refund born under the requester's key converges - the same purchase and amount replay it, another is the key
 *       reused ({@code UNIQUE (merchant_id, requested_by, refund_key)}, the arbiter behind a lapsed claim); at the
 *       operator's door an operator of the purchase's own party - or one the platform cannot place in a party - is refused
 *       ({@link SelfDealingRefused}); at the merchant's door so is a key whose issuer is the purchase's party, or cannot be
 *       placed in one (the Phase 12 -> 13 gate's A5-F1, A7-6 - both fail closed);
 *   <li>the bound, judged under B5 over the purchase's committed refunds: a {@code FULLY_REFUNDED} purchase is
 *       {@link PurchaseFullyRefunded}; {@code R > G - refunded} is {@link RefundExceedsPurchase} ({@code INV-BNPL-07});
 *   <li><strong>(B4)</strong> the obligation {@code FOR UPDATE}; the database clock read after it; pay-in-N on an
 *       {@code ACTIVE} obligation every elapsed date accrued, else {@link BehindAccrual} - nothing written, the door runs
 *       T-acc in its own transaction and comes back ({@link BnplAccruals#caughtUp}, one rule for every act);
 *   <li>the position, derived under B4 from the rows - the schedule's instalments, the billed items (billed less settled,
 *       by every payer and every earlier refund), the earlier refunds' adjustments and {@code BNPL_PRINCIPAL} - never the
 *       projection; then {@link RefundAllocationEngineV1}: latest instalment first, unbilled or billed-unpaid principal
 *       cancelled, the excess to the customer, the fee returned by merchant's cumulative arithmetic
 *       ({@link BnplMerchants#returnedFee}, the purchase's pinned version);
 *   <li>pay-in-N's releases (A11): a refund leaving no principal unpaid releases the accrued and the billed-unpaid
 *       interest; one leaving nothing more to bill releases the accrued interest no billing could make due;
 *   <li>the late fees' release (OQ-14, `P12-TSK-020`): the fees still due on every billed instalment cancelled whole (on
 *       every billed one when no principal is left unpaid) - {@code DR BNPL_FEE_INCOME / CR BNPL_FEES_DUE} in the same
 *       entry, the currency's fee income row at L8 inside the one sorted call;
 *   <li>schedule and agreement version n+1 when an unbilled instalment is cancelled or shortened ({@code INV-HIST-04}):
 *       every billed instalment kept as its billing billed it; the unbilled tail pay-in-4 shortened from the end, pay-in-N
 *       re-amortised at the same instalment by {@code ScheduleEngineV1.recalculateAtInstalment} (the least term);
 *   <li><strong>(P)</strong> the merchant's {@code MERCHANT_PAYABLE} and - when an excess is owed or a credit balance may
 *       be refunded at closure - the customer's existing {@code CUSTOMER_WALLET}, one element in {@code UUID.compareTo}
 *       order ({@link BnplAcceptances#lockAsOneElement}; payments' book-rail order), so this never closes a cycle with a
 *       wallet-paid capture or refund by the same customer at the same merchant. The excess goes to the wallet when it is
 *       {@code ACTIVE}, else to {@code BNPL_CREDIT_BALANCE} (OQ-13); after closure, nowhere - {@link RefundNotPostable};
 *   <li><strong>(L8)</strong> every projection row the entry and a closure may post to, in the ledger's one order - the
 *       currency's shared {@code BNPL_MERCHANT_FEE_INCOME} and {@code BNPL_INTEREST_INCOME} rows inside that one call,
 *       never before (P); then F5 + F5a under {@code bnpl.refund:<refund>}, posted today, value-dated
 *       {@code max(today, floor)} on an {@code ACTIVE} obligation (the Phase 11 gate's R1);
 *   <li>then only inserts: the versions, the refund and its adjustments, the purchase {@code FINANCED -> FULLY_REFUNDED}
 *       when the refunds total the gross, {@code bnpl.BnplRefundApplied}, the audit record {@code bnpl.RefundApplied};
 *   <li>the closure when every receivable is now zero ({@link BnplClosures}): {@code REFUNDED} for a fully refunded
 *       purchase, {@code REFUNDED_PARTIALLY_REPAID} otherwise - the credit balance refunded to the wallet held under (P).
 * </ol>
 *
 * <p><strong>The B7 bound needs no check</strong>: Σ over a purchase's refunds of {@code R - r_f} is {@code x - cum(x)} for
 * the cumulative refunded {@code x <= G}, which never exceeds {@code G - f} - so a refund after a payout may drive the
 * payable below zero by at most the purchase's financed net, and is never refused for a short payable (ADR-0105 decision
 * 10; {@code bnpl V012}'s trigger re-judges it as a second rank). <strong>No L0, no L6</strong>: a refund only lowers
 * exposure and restores capital headroom at its commit. No JVM state.
 */
@RequiredArgsConstructor
public final class BnplRefunds {

    /** F5's key prefix - merchant's {@code MerchantPayable.BNPL_REFUND_NAMESPACE}: {@code bnpl.refund:<refund>}. */
    public static final String POSTING_KEY_PREFIX = "bnpl.refund:";

    /** The event a refund publishes. */
    public static final String APPLIED_EVENT = "bnpl.BnplRefundApplied";

    /** The refund's audit target type. */
    public static final String REFUND_TARGET = "bnpl_refund";

    /** A refused act's audit target type - the purchase it named. */
    public static final String PURCHASE_TARGET = "purchase";

    /** The purchase history reason of its terminal edge. */
    public static final String FULLY_REFUNDED_REASON = "REFUNDS_TOTAL_THE_GROSS";

    /** The longest key a requester may refund under: the door's own bound. */
    public static final int MAX_REQUEST_KEY_LENGTH = com.finapp.platform.idempotency.IdempotencyKey.MAX_LENGTH;

    @NonNull private final BnplRefundStore store;
    @NonNull private final BnplServicingStore servicing;
    @NonNull private final BnplPurchaseStore purchases;
    @NonNull private final BnplTermsStore terms;
    @NonNull private final BnplMerchants merchants;
    @NonNull private final BnplWallets wallets;
    @NonNull private final BnplBook book;
    @NonNull private final BnplClosures closures;
    @NonNull private final BnplActingParty actingParties;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * Whether the refund refunds all that remained of the purchase - the meter's {@code kind}, and for a refund that
     * committed, whether it left the purchase {@code FULLY_REFUNDED}.
     */
    public enum Kind {
        FULL,
        PARTIAL
    }

    /**
     * What a door asks: an amount, under the requester's own key, with a reason - and, at the merchant's door, the key id
     * that acted ({@code keyId} - public, ADR-0052 - for the audit record; never the secret) and who issued that key
     * ({@code keyIssuer}, merchant {@code V003}'s {@code issued_by}: the Phase 12 -> 13 gate's A5-F1 - the merchant's door
     * refuses a key whose issuer is the purchase's own party, or one it cannot place; absent, it refuses: fail closed).
     */
    public record Request(String requestKey, Money amount, String reason, Optional<String> keyId,
            Optional<String> keyIssuer) {

        public Request {
            Objects.requireNonNull(requestKey, "requestKey must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(keyId, "keyId must not be null");
            Objects.requireNonNull(keyIssuer, "keyIssuer must not be null");
            if (requestKey.isEmpty() || requestKey.length() > MAX_REQUEST_KEY_LENGTH) {
                throw new IllegalArgumentException("a refund key is 1 to " + MAX_REQUEST_KEY_LENGTH + " characters");
            }
        }

        /** A request with no key issuer - the operator's door and a dispute's; at the merchant's door it is refused. */
        public Request(String requestKey, Money amount, String reason, Optional<String> keyId) {
            this(requestKey, amount, reason, keyId, Optional.empty());
        }

        /** Never the key, the amount or the reason ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "Request[redacted]";
        }
    }

    /** What the refund committed - or, {@code replayed}, the refund a retried key converged on - and any closure. */
    public record Refunded(BnplRefund refund, Optional<BnplClosure> closure, boolean replayed, Kind kind) {

        public Refunded {
            Objects.requireNonNull(refund, "refund must not be null");
            Objects.requireNonNull(closure, "closure must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
        }
    }

    // ------------------------------------------------------------------ the doors

    /**
     * {@code merchant} refunds its purchase {@code purchase} - the merchant's door; the caller holds the key's scope and
     * names the key's issuer in the request ({@link Request#keyIssuer}).
     *
     * @throws PurchaseNotFound no purchase of this merchant has the id (another merchant's included)
     * @throws SelfDealingRefused the key's issuer is the purchase's party, or cannot be placed in one (A5-F1)
     */
    public Refunded atMerchant(Connection unitOfWork, BnplPurchaseId purchase, UUID merchant, Request request, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(purchase, "purchase");
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reason = BnplReasons.reasoned(request.reason());
        // (B5) The purchase, tenant-scoped in the statement: another merchant's is never locked.
        BnplRefundStore.LockedPurchase locked = store.lockAtMerchant(unitOfWork, purchase, merchant)
                .orElseThrow(PurchaseNotFound::new);
        return refund(unitOfWork, locked, BnplRefund.Door.MERCHANT, "MERCHANT:" + merchant, request, reason, actor,
                correlation);
    }

    /**
     * An operator refunds the purchase {@code purchase} - the door beside the merchant's (ADR-0105 decision 9); the caller
     * holds {@code PAYMENT_REFUND}.
     *
     * @throws PurchaseNotFound no purchase has the id
     * @throws SelfDealingRefused the purchase is the operator's own party's, or the operator has no party the platform
     *     knows (fail closed - the Phase 12 -> 13 gate's A7-6)
     */
    public Refunded byOperator(Connection unitOfWork, BnplPurchaseId purchase, Request request, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(purchase, "purchase");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        // A person's session - an operator session's actor is CUSTOMER-typed today (the platform issues no EMPLOYEE
        // actors to sessions yet, CURRENT_STATE.md's Known Architectural Debt), so the door admits either; never the
        // system's or a merchant's.
        if (actor.type() != ActorType.EMPLOYEE && actor.type() != ActorType.CUSTOMER) {
            throw new IllegalStateException("the operator's refund door is a person's");
        }
        String reason = BnplReasons.reasoned(request.reason());
        BnplRefundStore.LockedPurchase locked = store.lock(unitOfWork, purchase).orElseThrow(PurchaseNotFound::new);
        return refund(unitOfWork, locked, BnplRefund.Door.OPERATOR, "OPERATOR:" + actor.id(), request, reason, actor,
                correlation);
    }

    // ------------------------------------------------------------------ the dispute's two uses (`P12-TSK-023`)

    /**
     * The hold a dispute of {@code amount} places at its opening (ADR-0106 decision 3): the instalments a refund of it would
     * cancel now - this body's position and allocator run as a dry run under the caller's B5 and B4, nothing written - each
     * with the principal it would cancel. A {@code CLOSED} obligation owes no principal: nothing is held.
     *
     * @throws RefundExceedsPurchase {@code amount > G - refundedBefore}
     * @throws AllocationAnomaly the rows do not compose a position
     */
    List<BnplDispute.Held> heldBy(Connection unitOfWork, BnplRefundStore.LockedPurchase locked,
            BnplServicingStore.Locked obligationLocked, Money amount, Money refundedBefore) {
        BnplObligation obligation = obligationLocked.obligation();
        if (obligation.status() != BnplObligationStatus.ACTIVE) {
            return List.of();
        }
        Money gross = locked.gross();
        Kind kind = amount.equals(gross.minus(refundedBefore)) ? Kind.FULL : Kind.PARTIAL;
        BnplReceivables receivables = new BnplReceivables(purchases, book);
        BnplLedgerAccounts accounts = receivables.accounts(unitOfWork, obligation.id());
        receivables.dueState(unitOfWork, obligation.id(), accounts); // refuses rows that do not explain the ledger
        BnplPurchaseStore.StoredSchedule first = purchases.schedule(unitOfWork, obligation.id(), 1)
                .orElseThrow(() -> new IllegalStateException("an obligation commits with schedule version 1"));
        BnplPurchaseStore.StoredSchedule latest = servicing.latestSchedule(unitOfWork, obligation.id())
                .orElseThrow(() -> new IllegalStateException("an obligation commits with schedule version 1"));
        List<BnplPurchaseStore.BilledItem> billed = purchases.billedItems(unitOfWork, obligation.id());
        Money principalNotDue = receivables.principalNotDue(unitOfWork, accounts);
        Derivation derived = derive(first, latest, billed, store.adjustmentsOf(unitOfWork, obligation.id()),
                principalNotDue, gross, locked.fee(), refundedBefore);
        BnplPurchaseId purchase = locked.purchase().id();
        RefundAllocationOutcome outcome = RefundAllocationEngineV1.allocate(derived.position(), amount,
                (before, refunded) -> merchants.returnedFee(unitOfWork, purchase, before, refunded));
        if (outcome instanceof RefundAllocationOutcome.Refused refused) {
            if (refused.reason() == RefundAllocationRefusal.REFUND_EXCEEDS_PURCHASE) {
                throw new RefundExceedsPurchase(kind);
            }
            throw new AmountInvalid();
        }
        RefundAllocation allocation = ((RefundAllocationOutcome.Allocated) outcome).allocation();
        List<BnplDispute.Held> held = new ArrayList<>();
        for (RefundAllocation.Cancellation cancellation : allocation.cancellations()) {
            Slot slot = derived.slots().get(cancellation.sequence() - 1);
            UUID instalment = cancellation.kind() == RefundAllocation.Cancellation.Kind.UNBILLED
                    ? slot.unbilled().orElseThrow(() -> new AllocationAnomaly("an unbilled hold is of a scheduled"
                            + " instalment (INV-BNPL-06)")).id()
                    : slot.billed().orElseThrow(() -> new AllocationAnomaly("a billed hold is of a billed instalment"
                            + " (INV-BNPL-06)")).instalment();
            held.add(new BnplDispute.Held(ids.next(), slot.sequence(), instalment, slot.dueDate(), cancellation.kind(),
                    cancellation.amount()));
        }
        return List.copyOf(held);
    }

    /**
     * The dispute's refund outcome (ADR-0106 decision 6; {@code INV-BNPL-08}): this body, door {@code DISPUTE}, requested by
     * {@code DISPUTE:<dispute>} under the dispute's id as its key, for exactly its claim - under the B5 and B4 the caller
     * (T-dsp) holds already, judged by the same bound as every refund. The reason is the act's own fixed text, never the
     * customer's.
     */
    Refunded forDispute(Connection unitOfWork, BnplRefundStore.LockedPurchase locked, BnplDispute dispute, String reason,
            Actor actor, CorrelationId correlation) {
        if (!locked.purchase().id().equals(dispute.purchase())) {
            throw new IllegalStateException("a dispute's refund refunds its own purchase");
        }
        String key = dispute.id().value().toString();
        return refund(unitOfWork, locked, BnplRefund.Door.DISPUTE, BnplRefund.DISPUTE_REQUESTER + key,
                new Request(key, dispute.amount(), reason, Optional.empty()), BnplReasons.reasoned(reason), actor,
                correlation);
    }

    /**
     * The party's existing wallet in {@code currency} - the one a refund's excess is credited to, and the only account a
     * refund on a {@code CLOSED} obligation can post to (INV-BNPL-09, OQ-15): the dispute's opening asks it there (the
     * Phase 12 to 13 gate's A7-1).
     */
    Optional<LedgerAccountId> existingWallet(Connection unitOfWork, UUID party, CurrencyCode currency) {
        return wallets.existingWallet(unitOfWork, party, currency);
    }

    // ------------------------------------------------------------------ T-ref's body

    private Refunded refund(Connection unitOfWork, BnplRefundStore.LockedPurchase locked, BnplRefund.Door door,
            String requestedBy, Request request, String reason, Actor actor, CorrelationId correlation) {
        BnplPurchase purchase = locked.purchase();
        UUID merchant = purchase.merchant();
        // A refund already born under this key: the arbiter behind a claim that lapsed.
        Optional<BnplRefund> born = store.findByKey(unitOfWork, merchant, requestedBy, request.requestKey());
        if (born.isPresent()) {
            if (!born.get().purchase().equals(purchase.id()) || !born.get().amount().equals(request.amount())) {
                throw new RefundKeyReused();
            }
            // The door's stored receipt carries any closure; the arbiter's replay answers the refund it converged on.
            Kind replayedKind = born.get().refundedBefore().plus(born.get().amount()).equals(locked.gross()) ? Kind.FULL
                    : Kind.PARTIAL;
            return new Refunded(born.get(), Optional.empty(), true, replayedKind);
        }
        if (door == BnplRefund.Door.OPERATOR) {
            // Fails CLOSED (the Phase 12 -> 13 gate's A7-6 / A5-F3): an operator the platform cannot place in a party is
            // refused as one of the purchase's own - BnplRepaymentReversals.judgeSelfDealing's shape.
            Optional<UUID> operatorsParty = actingParties.partyOf(unitOfWork, actor);
            if (operatorsParty.isEmpty() || operatorsParty.get().equals(purchase.party())) {
                throw new SelfDealingRefused(purchase.id());
            }
        }
        if (door == BnplRefund.Door.MERCHANT) {
            refuseIssuersOwn(unitOfWork, purchase.id(), purchase.party(), request.keyIssuer(), request.keyId(),
                    "refund");
        }
        Money amount = request.amount();
        Money gross = locked.gross();
        // ADR-0106 decision 7 (`P12-TSK-023`): no merchant or operator refund while a dispute is open, judged under B5 -
        // the merchant settles a disputed purchase by accepting the dispute, whose refund is this body's DISPUTE door.
        if (door != BnplRefund.Door.DISPUTE && store.disputeOpen(unitOfWork, purchase.id())) {
            throw new DisputeOpen(amount.equals(gross) ? Kind.FULL : Kind.PARTIAL);
        }
        if (!amount.isPositive() || !amount.currency().equals(gross.currency()) || amount.scale() != gross.scale()) {
            throw new AmountInvalid();
        }
        // The bound under B5, over the purchase's committed refunds (INV-BNPL-07).
        List<BnplRefund> earlier = store.ofPurchase(unitOfWork, purchase.id());
        Money zero = Money.ofPersisted(0, gross.currency(), gross.scale());
        Money refundedBefore = zero;
        for (BnplRefund refund : earlier) {
            refundedBefore = refundedBefore.plus(refund.amount());
        }
        Kind kind = amount.equals(gross.minus(refundedBefore)) ? Kind.FULL : Kind.PARTIAL;
        if (purchase.status() == BnplPurchaseStatus.FULLY_REFUNDED) {
            throw new PurchaseFullyRefunded(kind);
        }
        if (amount.compareTo(gross.minus(refundedBefore)) > 0) {
            throw new RefundExceedsPurchase(kind);
        }

        // (B4) The obligation; the database clock after it.
        BnplServicingStore.Locked obligationLocked = servicing.lock(unitOfWork, locked.obligation())
                .orElseThrow(() -> new IllegalStateException("a financed purchase has its obligation (INV-BNPL-02)"));
        BnplObligation obligation = obligationLocked.obligation();
        boolean active = obligation.status() == BnplObligationStatus.ACTIVE;
        Instant now = servicing.databaseNow(unitOfWork);
        LocalDate today = LocalDate.ofInstant(now, ZoneId.of(obligationLocked.servicingZone()));
        if (active && !BnplAccruals.caughtUp(unitOfWork, servicing, purchases, book, obligationLocked, today)) {
            throw new BehindAccrual(obligation.id(), kind);
        }

        // The position under B4 - from the rows, explained by the ledger - and the split.
        BnplReceivables receivables = new BnplReceivables(purchases, book);
        BnplLedgerAccounts accounts = receivables.accounts(unitOfWork, obligation.id());
        receivables.dueState(unitOfWork, obligation.id(), accounts); // refuses rows that do not explain the ledger
        BnplPurchaseStore.StoredSchedule first = purchases.schedule(unitOfWork, obligation.id(), 1)
                .orElseThrow(() -> new IllegalStateException("an obligation commits with schedule version 1"));
        BnplPurchaseStore.StoredSchedule latest = servicing.latestSchedule(unitOfWork, obligation.id())
                .orElseThrow(() -> new IllegalStateException("an obligation commits with schedule version 1"));
        List<BnplPurchaseStore.BilledItem> billed = purchases.billedItems(unitOfWork, obligation.id());
        Money principalNotDue = receivables.principalNotDue(unitOfWork, accounts);
        Derivation derived = derive(first, latest, billed, store.adjustmentsOf(unitOfWork, obligation.id()),
                principalNotDue, gross, locked.fee(), refundedBefore);
        RefundAllocationOutcome outcome = RefundAllocationEngineV1.allocate(derived.position(), amount,
                (before, refunded) -> merchants.returnedFee(unitOfWork, purchase.id(), before, refunded));
        if (outcome instanceof RefundAllocationOutcome.Refused refused) {
            if (refused.reason() == RefundAllocationRefusal.REFUND_EXCEEDS_PURCHASE) {
                throw new RefundExceedsPurchase(kind);
            }
            throw new AmountInvalid();
        }
        RefundAllocation allocation = ((RefundAllocationOutcome.Allocated) outcome).allocation();
        if (!active && !allocation.cancellations().isEmpty()) {
            throw new AllocationAnomaly("a CLOSED obligation owes no principal a refund could cancel (INV-BNPL-09)");
        }
        Money cancelledUnbilled = allocation.cancelled(RefundAllocation.Cancellation.Kind.UNBILLED);
        Money cancelledBilled = allocation.cancelled(RefundAllocation.Cancellation.Kind.BILLED_UNPAID);

        // Pay-in-N's releases (A11).
        boolean payInN = obligationLocked.accrualEngine().isPresent();
        Money accruedReleased = zero;
        Map<Integer, Money> dueInterestReleased = new TreeMap<>();
        if (active && payInN) {
            boolean nothingLeftToBill = principalNotDue.minus(cancelledUnbilled).isZero();
            if (allocation.leavesNoPrincipalUnpaid() || nothingLeftToBill) {
                accruedReleased = book.balanceOf(unitOfWork, accounts.interestAccrued());
            }
            if (allocation.leavesNoPrincipalUnpaid()) {
                for (Slot slot : derived.slots()) {
                    if (slot.billed().isPresent()) {
                        Money due = slot.billed().get().due(AllocationComponent.INTEREST);
                        if (due.isPositive()) {
                            dueInterestReleased.put(slot.sequence(), due);
                        }
                    }
                }
            }
        }
        // OQ-14 (`P12-TSK-020`): the unpaid late fees of every billed instalment the refund cancels whole - and of every
        // billed one when it leaves no principal unpaid - released in the same entry (F5a): a fee on an instalment that no
        // longer exists is not owed. Read from the rows under B4 - what is due now, every earlier release and payment
        // counted - so no fee is released twice, nor released beside a waiver of the same money (`-024`).
        Map<Integer, Money> lateFeeReleased = active ? lateFeesReleased(derived, allocation) : new TreeMap<>();
        List<BnplRefund.Adjustment> adjustments = adjustments(derived, allocation, dueInterestReleased, lateFeeReleased,
                zero, ids);
        Money feesReleased = zero;
        for (Money released : lateFeeReleased.values()) {
            feesReleased = feesReleased.plus(released);
        }
        Money dueReleased = zero;
        for (Money released : dueInterestReleased.values()) {
            dueReleased = dueReleased.plus(released);
        }

        // Schedule and agreement version n+1 when an unbilled instalment is cancelled or shortened (INV-HIST-04).
        Optional<BnplPurchaseStore.StoredSchedule> next = cancelledUnbilled.isPositive()
                ? Optional.of(nextSchedule(unitOfWork, obligationLocked, first, latest, billed, derived, allocation,
                        principalNotDue.minus(cancelledUnbilled)))
                : Optional.empty();

        // (P) The payable and - when an excess is owed or a credit balance may be refunded - the wallet, one element.
        CurrencyCode currency = gross.currency();
        LedgerAccountId payable = merchants.payableAccount(unitOfWork, merchant, currency)
                .orElseThrow(() -> new RefundNotPostable(kind));
        Money creditHeld = active ? receivables.creditHeld(unitOfWork, accounts) : zero;
        Optional<LedgerAccountId> wallet = allocation.excess().isPositive() || creditHeld.isPositive()
                ? wallets.existingWallet(unitOfWork, purchase.party(), currency) : Optional.empty();
        Map<LedgerAccountId, LedgerAccountStatus> pair = BnplAcceptances.lockAsOneElement(book, unitOfWork, payable,
                wallet);
        if (pair.get(payable) != LedgerAccountStatus.ACTIVE) {
            throw new RefundNotPostable(kind);
        }
        Optional<LedgerAccountId> walletHeld = wallet.filter(held -> pair.get(held) == LedgerAccountStatus.ACTIVE);
        BnplRefund.ExcessTo excessTo;
        if (!allocation.excess().isPositive()) {
            excessTo = BnplRefund.ExcessTo.NONE;
        } else if (walletHeld.isPresent()) {
            excessTo = BnplRefund.ExcessTo.WALLET;
        } else if (active) {
            excessTo = BnplRefund.ExcessTo.CREDIT_BALANCE;
        } else {
            // A CLOSED obligation holds no credit balance (INV-BNPL-09, OQ-15): refused, nothing written.
            throw new RefundNotPostable(kind);
        }

        // (L8) Every projection row the entry and a closure may post to, in the ledger's one order.
        LedgerAccountId feeIncome = book.operational(unitOfWork, AccountPurpose.BNPL_MERCHANT_FEE_INCOME, currency);
        LedgerAccountId interestIncome = book.operational(unitOfWork, AccountPurpose.BNPL_INTEREST_INCOME, currency);
        Optional<LedgerAccountId> lateFeeIncome = feesReleased.isPositive()
                ? Optional.of(book.operational(unitOfWork, AccountPurpose.BNPL_FEE_INCOME, currency)) : Optional.empty();
        List<JournalLine> lines = entryLines(allocation, payable, feeIncome, accounts,
                excessTo == BnplRefund.ExcessTo.WALLET ? walletHeld : Optional.of(accounts.creditBalance().id()),
                accruedReleased, dueReleased, interestIncome, feesReleased, lateFeeIncome);
        Set<LedgerAccountId> touched = new LinkedHashSet<>();
        lines.forEach(line -> touched.add(line.account()));
        if (active) {
            touched.add(accounts.creditBalance().id());
        }
        walletHeld.ifPresent(touched::add);
        book.lockBalancesInOrder(unitOfWork, touched);

        // F5 + F5a: posted today, value-dated never before the floor on an ACTIVE obligation (the Phase 11 gate's R1).
        LocalDate valueDate = today;
        if (active) {
            LocalDate floor = servicing.principalValueFloor(unitOfWork, obligation.id());
            valueDate = floor.isAfter(today) ? floor : today;
        }
        UUID refundId = ids.next();
        JournalEntryId entry = book.post(unitOfWork, new PostingCommand(POSTING_KEY_PREFIX + refundId, today, valueDate,
                refundId.toString(), lines));

        // Then only inserts: the versions first (the refund's row names the versions in force), the refund, the edge.
        int agreementVersion = obligationLocked.agreementVersion();
        int scheduleVersion = latest.version();
        if (next.isPresent()) {
            BnplAgreement current = purchases.agreementOwnedBy(unitOfWork, obligationLocked.agreement(), purchase.party())
                    .orElseThrow(() -> new IllegalStateException("an obligation's agreement is its party's"))
                    .agreement();
            if (current.version() != agreementVersion) {
                throw new IllegalStateException("the agreement in force is the locked obligation's");
            }
            store.insertScheduleVersion(unitOfWork, next.get(), refundId);
            agreementVersion = current.version() + 1;
            scheduleVersion = next.get().version();
            store.insertAgreementVersion(unitOfWork, nextAgreement(current, now), refundId, scheduleVersion);
        }
        BnplRefund refund = new BnplRefund(refundId, purchase.id(), obligation.id(), merchant, door, requestedBy,
                request.requestKey(), amount, refundedBefore, allocation.feeReturned(), allocation.payableDebit(),
                cancelledUnbilled, cancelledBilled, allocation.excess(), excessTo, accruedReleased, dueReleased,
                allocation.engine().name(), obligationLocked.agreement(), agreementVersion, scheduleVersion, reason,
                entry, valueDate, Optional.empty(), correlation, adjustments);
        refund = refund.stampedAt(store.insertRefund(unitOfWork, refund));
        if (refundedBefore.plus(amount).equals(gross)) {
            if (!store.fullyRefunded(unitOfWork, purchase.id())) {
                throw new IllegalStateException("the locked purchase did not move under its own row lock");
            }
            purchases.appendPurchaseEvent(unitOfWork, new BnplPurchaseStore.HistoryRow(ids.next(), purchase.id(),
                    Optional.of(BnplPurchaseStatus.FINANCED), BnplPurchaseStatus.FULLY_REFUNDED, FULLY_REFUNDED_REASON,
                    actor, correlation));
        }
        publish(unitOfWork, refund, allocation);
        audited(unitOfWork, refund, request.keyId(), actor);

        // The closure, when this refund brought the last receivable to zero - in this same transaction.
        Optional<BnplClosure> closure = Optional.empty();
        if (active) {
            closure = closures.closeIfSettled(unitOfWork, new BnplClosures.Closing(obligationLocked, accounts, today,
                    Optional.empty(), walletHeld, Optional.of(refundId)), () -> actor, correlation);
        }
        return new Refunded(refund, closure, false, kind);
    }

    // ------------------------------------------------------------------ the merchant key's issuer (A5-F1)

    /**
     * Refuses a merchant key's money-moving act on {@code purchase} when the key's issuer is the purchase's own party - the
     * operator who issued the key and saw its secret once never refunds their own purchase through it (the Phase 12 -> 13
     * gate's A5-F1: the merchant doors judged scope and tenant only, so an operator could mint a refund key and bypass the
     * operator door's self-dealing rule). Fails CLOSED: no issuer named, or one the platform cannot place in a party, is
     * refused too. One rule for both merchant doors that move money - the refund ({@link #atMerchant}) and the dispute's
     * acceptance ({@code BnplDisputes.respond}, {@code ACCEPT}); the issuer's party is a frozen fact, so it is judged
     * before anything is written, wherever the caller holds it.
     *
     * @throws SelfDealingRefused the issuer is the purchase's party, or cannot be placed in one
     */
    void refuseIssuersOwn(Connection unitOfWork, BnplPurchaseId purchase, UUID purchaseParty, Optional<String> issuer,
            Optional<String> keyId, String act) {
        Optional<UUID> issuersParty = issuer.flatMap(issuedBy -> actingParties.partyOfIssuer(unitOfWork, issuedBy));
        if (issuersParty.isEmpty() || issuersParty.get().equals(purchaseParty)) {
            throw new SelfDealingRefused(purchase, act, keyId.orElse("unknown"));
        }
    }

    // ------------------------------------------------------------------ the refusals' records

    /**
     * A merchant key without the BNPL refund scope tried to refund - recorded {@code FAILED} on the caller's unit of work,
     * a transaction of its own (nothing else was read or written): its target the purchase the path named, its summary the
     * key id.
     */
    public void recordScopeRefusal(Connection unitOfWork, String purchase, String keyId, Actor actor,
            CorrelationId correlation) {
        recordScopeRefusal(unitOfWork, PURCHASE_TARGET, purchase, "refund", keyId, actor, correlation);
    }

    /**
     * A merchant key without the BNPL refund scope tried {@code act} on {@code target} - a refund of a purchase, or the
     * acceptance of a dispute, which refunds it (the Phase 12 -> 13 gate's A5-F1/F6) - recorded {@code FAILED} on the
     * caller's unit of work, a transaction of its own: its target what the path named, its summary the act and the key id.
     */
    public void recordScopeRefusal(Connection unitOfWork, String targetType, String target, String act, String keyId,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(act, "act");
        Objects.requireNonNull(keyId, "keyId");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                BnplAuditAction.REFUND_SCOPE_REFUSED, targetType, bounded(target), Optional.empty(),
                AuditOutcome.FAILED, correlation, Optional.of(act + " refused - key=" + keyId
                        + " carries no BNPL_REFUND scope")));
    }

    /**
     * A self-dealing refund - an operator's of their own party's purchase, or a merchant key's whose issuer is the
     * purchase's party (A5-F1) - recorded {@code FAILED} in a transaction of its own after the act rolled back.
     */
    public void recordSelfDealing(Connection unitOfWork, SelfDealingRefused refused, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        String why = refused.keyId().isPresent()
                ? "the key's issuer is the purchase's own party, or one the platform cannot place in a party, key="
                        + refused.keyId().get()
                : "the operator's own party, or an operator the platform cannot place in a party";
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                BnplAuditAction.SELF_DEALING_REFUSED, PURCHASE_TARGET, refused.purchase().value().toString(),
                Optional.empty(), AuditOutcome.FAILED, correlation,
                Optional.of("purchase=" + refused.purchase().value() + ", act=" + refused.act() + " refused - " + why)));
    }

    private static String bounded(String raw) {
        return raw.length() <= 200 ? raw : raw.substring(0, 200);
    }

    // ------------------------------------------------------------------ the position

    /**
     * One instalment as the refund meets it: its sequence and due date; its billed item, if billed; its row in the latest
     * schedule, if unbilled and still scheduled; its principal, settled and cancelled in the position's terms; and, for an
     * unbilled one, the principal still to bill.
     */
    record Slot(int sequence, LocalDate dueDate, Optional<BnplPurchaseStore.BilledItem> billed,
            Optional<BnplPurchaseStore.StoredInstalment> unbilled, Money principal, Money allocated, Money cancelled,
            Money toBill) {}

    /** The position the allocator splits over and the slots its instalments are, position sequence {@code i + 1} slot {@code i}. */
    record Derivation(RefundPosition position, List<Slot> slots) {}

    /**
     * The position under B4, from the rows (ADR-0105 decision 2; {@link RefundPosition}): the instalments of version 1 by
     * sequence and due date - due dates are stable across versions - each billed one's billed principal (plus what refunds
     * cancelled of it before its billing), what payers settled of it, what refunds cancelled; each unbilled one still
     * scheduled the principal the billing rule will bill, {@code least(remaining, its principal)} from the first with the
     * last the remainder of {@code BNPL_PRINCIPAL}; each no longer scheduled what refunds cancelled of it. Slots of no
     * principal at all are left out, the rest renumbered - so every position instalment is positive.
     *
     * @throws AllocationAnomaly the rows do not compose a position - a defect, nothing written
     */
    static Derivation derive(BnplPurchaseStore.StoredSchedule first, BnplPurchaseStore.StoredSchedule latest,
            List<BnplPurchaseStore.BilledItem> billed, List<BnplRefund.Adjustment> adjustments, Money principalNotDue,
            Money gross, Money fee, Money refundedBefore) {
        Money zero = Money.ofPersisted(0, gross.currency(), gross.scale());
        Map<LocalDate, BnplPurchaseStore.BilledItem> billedOn = new HashMap<>();
        for (BnplPurchaseStore.BilledItem item : billed) {
            billedOn.put(item.dueDate(), item);
        }
        Map<Integer, Money> unbilledCancelled = new HashMap<>();
        Map<Integer, Money> billedCancelled = new HashMap<>();
        for (BnplRefund.Adjustment adjustment : adjustments) {
            Map<Integer, Money> into = adjustment.kind() == RefundAllocation.Cancellation.Kind.UNBILLED
                    ? unbilledCancelled : billedCancelled;
            into.merge(adjustment.sequence(), adjustment.principalCancelled(), Money::plus);
        }
        // The principal still to bill, the billing rule's own walk over the latest version's unbilled instalments.
        Map<Integer, BnplPurchaseStore.StoredInstalment> latestBySequence = new HashMap<>();
        List<BnplPurchaseStore.StoredInstalment> unbilledRows = new ArrayList<>();
        for (BnplPurchaseStore.StoredInstalment row : latest.instalments()) {
            latestBySequence.put(row.sequence(), row);
            if (!billedOn.containsKey(row.dueDate())) {
                unbilledRows.add(row);
            }
        }
        Map<Integer, Money> toBill = new HashMap<>();
        Money remaining = principalNotDue;
        for (int index = 0; index < unbilledRows.size(); index++) {
            BnplPurchaseStore.StoredInstalment row = unbilledRows.get(index);
            Money portion = index == unbilledRows.size() - 1 ? remaining
                    : (row.principal().compareTo(remaining) < 0 ? row.principal() : remaining);
            toBill.put(row.sequence(), portion);
            remaining = remaining.minus(portion);
        }
        if (remaining.isNegative() || (!remaining.isZero() && !unbilledRows.isEmpty())
                || (unbilledRows.isEmpty() && !principalNotDue.isZero())) {
            throw new AllocationAnomaly("the latest schedule does not explain the principal not yet due - nothing is"
                    + " refunded (INV-BNPL-05)");
        }
        List<Slot> slots = new ArrayList<>();
        List<RefundPosition.Instalment> instalments = new ArrayList<>();
        for (BnplPurchaseStore.StoredInstalment origin : first.instalments()) {
            int sequence = origin.sequence();
            Money uc = unbilledCancelled.getOrDefault(sequence, zero);
            Money bc = billedCancelled.getOrDefault(sequence, zero);
            BnplPurchaseStore.BilledItem item = billedOn.get(origin.dueDate());
            BnplPurchaseStore.StoredInstalment row = latestBySequence.get(sequence);
            if (row != null && !row.dueDate().equals(origin.dueDate())) {
                throw new AllocationAnomaly("a schedule version moved an instalment's due date - nothing is refunded"
                        + " (INV-HIST-04)");
            }
            Slot slot;
            if (item != null) {
                slot = new Slot(sequence, origin.dueDate(), Optional.of(item), Optional.empty(),
                        item.billedPrincipal().plus(uc), item.paidPrincipal().minus(bc), uc.plus(bc), zero);
            } else if (row != null) {
                Money still = toBill.get(sequence);
                slot = new Slot(sequence, origin.dueDate(), Optional.empty(), Optional.of(row), still.plus(uc), zero, uc,
                        still);
            } else {
                slot = new Slot(sequence, origin.dueDate(), Optional.empty(), Optional.empty(), uc, zero, uc, zero);
            }
            if (!slot.principal().isPositive()) {
                continue; // Nothing ever owed here - an instalment a re-amortisation removed with nothing cancelled.
            }
            slots.add(slot);
            instalments.add(new RefundPosition.Instalment(slots.size(), slot.principal(), item != null, slot.allocated(),
                    slot.cancelled()));
        }
        try {
            return new Derivation(new RefundPosition(gross, fee, refundedBefore, instalments), List.copyOf(slots));
        } catch (IllegalArgumentException inconsistent) {
            throw new AllocationAnomaly("the rows do not compose a refund position - nothing is refunded (INV-BNPL-06)",
                    inconsistent);
        }
    }

    /**
     * The refund's adjustment rows, latest instalment first: each cancellation on the slot's own instalment - the latest
     * schedule's row when unbilled, the billing's instalment when billed - with the billed interest released beside it, and
     * an interest-only row for a billed instalment whose principal was already settled.
     */
    static List<BnplRefund.Adjustment> adjustments(Derivation derived, RefundAllocation allocation,
            Map<Integer, Money> dueInterestReleased, Money zero, IdGenerator ids) {
        return adjustments(derived, allocation, dueInterestReleased, Map.of(), zero, ids);
    }

    /**
     * The late fees a refund releases (OQ-14, `P12-TSK-020`), by slot sequence: the fees still due on every billed
     * instalment whose whole unpaid principal it cancels - and, when it leaves no principal unpaid at all, on every billed
     * instalment - each exactly what is due of its {@code FEES} now (billed less every payer's and every earlier release).
     */
    static Map<Integer, Money> lateFeesReleased(Derivation derived, RefundAllocation allocation) {
        Set<Integer> wholeBilled = new java.util.HashSet<>();
        for (RefundAllocation.Cancellation cancellation : allocation.cancellations()) {
            if (cancellation.kind() == RefundAllocation.Cancellation.Kind.BILLED_UNPAID && cancellation.whole()) {
                wholeBilled.add(derived.slots().get(cancellation.sequence() - 1).sequence());
            }
        }
        Map<Integer, Money> released = new TreeMap<>();
        for (Slot slot : derived.slots()) {
            if (slot.billed().isEmpty()) {
                continue;
            }
            Money fees = slot.billed().get().due(AllocationComponent.FEES);
            if (fees.isPositive() && (wholeBilled.contains(slot.sequence()) || allocation.leavesNoPrincipalUnpaid())) {
                released.put(slot.sequence(), fees);
            }
        }
        return released;
    }

    /** The refund's adjustment rows, the late fees released beside the cancellations and the interest (OQ-14). */
    static List<BnplRefund.Adjustment> adjustments(Derivation derived, RefundAllocation allocation,
            Map<Integer, Money> dueInterestReleased, Map<Integer, Money> lateFeeReleased, Money zero, IdGenerator ids) {
        Map<Integer, RefundAllocation.Cancellation> bySequence = new TreeMap<>();
        for (RefundAllocation.Cancellation cancellation : allocation.cancellations()) {
            bySequence.put(derived.slots().get(cancellation.sequence() - 1).sequence(), cancellation);
        }
        Set<Integer> touched = new java.util.TreeSet<>(java.util.Comparator.reverseOrder());
        touched.addAll(bySequence.keySet());
        touched.addAll(dueInterestReleased.keySet());
        touched.addAll(lateFeeReleased.keySet());
        Map<Integer, Slot> slotOf = new HashMap<>();
        for (Slot slot : derived.slots()) {
            slotOf.put(slot.sequence(), slot);
        }
        List<BnplRefund.Adjustment> rows = new ArrayList<>();
        for (int sequence : touched) {
            Slot slot = slotOf.get(sequence);
            RefundAllocation.Cancellation cancellation = bySequence.get(sequence);
            Money released = dueInterestReleased.getOrDefault(sequence, zero);
            Money cancelled = cancellation == null ? zero : cancellation.amount();
            RefundAllocation.Cancellation.Kind kind = cancellation != null ? cancellation.kind()
                    : RefundAllocation.Cancellation.Kind.BILLED_UNPAID;
            UUID instalment = kind == RefundAllocation.Cancellation.Kind.UNBILLED
                    ? slot.unbilled().orElseThrow(() -> new AllocationAnomaly("an unbilled cancellation is of a scheduled"
                            + " instalment (INV-BNPL-06)")).id()
                    : slot.billed().orElseThrow(() -> new AllocationAnomaly("a billed cancellation is of a billed"
                            + " instalment (INV-BNPL-06)")).instalment();
            boolean whole = cancellation == null || cancellation.whole();
            rows.add(new BnplRefund.Adjustment(ids.next(), sequence, instalment, kind, cancelled, released, whole,
                    lateFeeReleased.getOrDefault(sequence, zero)));
        }
        return List.copyOf(rows);
    }

    // ------------------------------------------------------------------ schedule and agreement v n+1

    /**
     * Schedule version n+1 (ADR-0105 decision 2.4; {@code INV-BNPL-05}, {@code INV-HIST-04}): from the purchase's business
     * date, every billed instalment kept as its billing billed it; the unbilled tail what the refund left - pay-in-4 each
     * instalment's principal still to bill less what this refund cancelled of it, the wholly cancelled ones (a suffix -
     * latest first) dropped; pay-in-N re-amortised at the same level instalment over the principal left by
     * {@code ScheduleEngineV1.recalculateAtInstalment} (the least term, latest first for an annuity, ADR-0105 decision 6),
     * the next period starting at the last billed due date. Its principal is version n's less exactly the refund's
     * unbilled cancellation - so the principal still to bill falls by exactly that, which {@code bnpl V012} re-judges.
     */
    private BnplPurchaseStore.StoredSchedule nextSchedule(Connection unitOfWork, BnplServicingStore.Locked locked,
            BnplPurchaseStore.StoredSchedule first, BnplPurchaseStore.StoredSchedule latest,
            List<BnplPurchaseStore.BilledItem> billed, Derivation derived, RefundAllocation allocation,
            Money principalLeft) {
        return nextSchedule(ids::next, locked.obligation().id(), locked.obligation().purchaseDate(),
                () -> terms.version(unitOfWork, locked.termsVersionId())
                        .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never"
                                + " deleted"))
                        .terms(),
                first, latest, billed, derived, allocation, principalLeft);
    }

    /**
     * {@link #nextSchedule(Connection, BnplServicingStore.Locked, BnplPurchaseStore.StoredSchedule,
     * BnplPurchaseStore.StoredSchedule, List, Derivation, RefundAllocation, Money)}'s rule, pure - the one the refund writes
     * by and the replay proof re-derives by (`P12-TSK-025`): {@code pinned} read only for pay-in-N's re-amortisation.
     */
    static BnplPurchaseStore.StoredSchedule nextSchedule(java.util.function.Supplier<UUID> ids, BnplObligationId obligation,
            LocalDate purchaseDate, java.util.function.Supplier<BnplTerms> pinned, BnplPurchaseStore.StoredSchedule first,
            BnplPurchaseStore.StoredSchedule latest, List<BnplPurchaseStore.BilledItem> billed, Derivation derived,
            RefundAllocation allocation, Money principalLeft) {
        Map<LocalDate, BnplPurchaseStore.BilledItem> billedOn = new HashMap<>();
        for (BnplPurchaseStore.BilledItem item : billed) {
            billedOn.put(item.dueDate(), item);
        }
        Map<Integer, Money> cancelledNow = new HashMap<>();
        for (RefundAllocation.Cancellation cancellation : allocation.cancellations()) {
            if (cancellation.kind() == RefundAllocation.Cancellation.Kind.UNBILLED) {
                cancelledNow.put(derived.slots().get(cancellation.sequence() - 1).sequence(), cancellation.amount());
            }
        }
        Map<Integer, Slot> slotOf = new HashMap<>();
        for (Slot slot : derived.slots()) {
            slotOf.put(slot.sequence(), slot);
        }
        List<BnplPurchaseStore.StoredInstalment> rows = new ArrayList<>();
        List<BnplPurchaseStore.StoredInstalment> unbilledRows = new ArrayList<>();
        LocalDate lastBilled = purchaseDate;
        for (BnplPurchaseStore.StoredInstalment row : latest.instalments()) {
            BnplPurchaseStore.BilledItem item = billedOn.get(row.dueDate());
            if (item != null) {
                rows.add(new BnplPurchaseStore.StoredInstalment(ids.get(), row.sequence(), row.dueDate(),
                        item.billedPrincipal(), item.billedInterest(), item.billedPrincipal().plus(item.billedInterest())));
                lastBilled = row.dueDate();
            } else {
                unbilledRows.add(row);
            }
        }
        boolean payInN = "SCHEDULE_ENGINE_V1".equals(latest.engine());
        if (!payInN) {
            boolean ended = false;
            for (BnplPurchaseStore.StoredInstalment row : unbilledRows) {
                Slot slot = slotOf.get(row.sequence());
                Money left = (slot == null ? row.principal() : slot.toBill())
                        .minus(cancelledNow.getOrDefault(row.sequence(), row.principal().minus(row.principal())));
                if (left.isNegative()) {
                    throw new AllocationAnomaly("a refund cancels no more than an instalment still owes (INV-BNPL-06)");
                }
                if (!left.isPositive()) {
                    ended = true;
                    continue;
                }
                if (ended) {
                    throw new AllocationAnomaly("a pay-in-4 refund cancels from the last instalment backwards (B3)");
                }
                rows.add(new BnplPurchaseStore.StoredInstalment(ids.get(), row.sequence(), row.dueDate(), left,
                        row.interest(), left.plus(row.interest())));
            }
        } else if (principalLeft.isPositive()) {
            rows.addAll(reamortised(pinned.get(), purchaseDate, ids, first, unbilledRows, principalLeft, lastBilled));
        }
        Money principal = latest.principal().minus(allocation.cancelled(RefundAllocation.Cancellation.Kind.UNBILLED));
        Money scheduled = principal.minus(principal);
        for (BnplPurchaseStore.StoredInstalment row : rows) {
            scheduled = scheduled.plus(row.principal());
        }
        if (!scheduled.equals(principal)) {
            throw new AllocationAnomaly("a refund's schedule version schedules its predecessor's principal less exactly"
                    + " the unbilled principal cancelled (INV-BNPL-05, INV-BNPL-06)");
        }
        return new BnplPurchaseStore.StoredSchedule(ids.get(), obligation, latest.version() + 1,
                latest.engine(), principal, latest.start(), rows);
    }

    /**
     * Pay-in-N's unbilled tail re-amortised (ADR-0105 decision 6): {@code ScheduleEngineV1.recalculateAtInstalment} over the
     * principal left, the agreement's level instalment kept (version 1's first), at most as many instalments as remained,
     * the intended day the purchase's (A6), the period from the last billed due date to the first unbilled one - the same
     * due dates, fewer of them, removed from the end.
     */
    private static List<BnplPurchaseStore.StoredInstalment> reamortised(BnplTerms pinned, LocalDate purchaseDate,
            java.util.function.Supplier<UUID> ids, BnplPurchaseStore.StoredSchedule first,
            List<BnplPurchaseStore.StoredInstalment> unbilledRows, Money principalLeft, LocalDate lastBilled) {
        if (unbilledRows.isEmpty()) {
            throw new AllocationAnomaly("principal left to bill has an unbilled instalment to bill it (INV-BNPL-05)");
        }
        Money level = first.instalments().get(0).amount();
        ScheduleOutcome outcome = ScheduleEngineV1.recalculateAtInstalment(pinned.scheduleTerms().orElseThrow(),
                principalLeft, level, unbilledRows.size(), purchaseDate.getDayOfMonth(), lastBilled,
                unbilledRows.get(0).dueDate());
        if (!(outcome instanceof ScheduleOutcome.Projected projected)) {
            throw new AllocationAnomaly("the pinned schedule engine refused a refund's recalculation (INV-BNPL-05): "
                    + ((ScheduleOutcome.Refused) outcome).reason());
        }
        Schedule recalculated = projected.schedule();
        List<BnplPurchaseStore.StoredInstalment> rows = new ArrayList<>();
        for (SchedulePeriod period : recalculated.periods()) {
            BnplPurchaseStore.StoredInstalment kept = unbilledRows.get(period.sequence() - 1);
            if (!kept.dueDate().equals(period.dueDate())) {
                throw new AllocationAnomaly("a recalculation keeps the agreement's due dates (INV-HIST-04)");
            }
            rows.add(new BnplPurchaseStore.StoredInstalment(ids.get(), kept.sequence(), period.dueDate(),
                    period.principal(), period.interest(), period.amount()));
        }
        return rows;
    }

    /** Agreement version n+1 - an exact copy of version n's terms, engines, pins and template (INV-HIST-04). */
    private static BnplAgreement nextAgreement(BnplAgreement current, Instant at) {
        return new BnplAgreement(current.id(), current.version() + 1, current.obligation(), current.offer(),
                current.decision(), current.termsVersionId(), current.product(), current.plan(), current.scheduleEngine(),
                current.accrualEngine(), current.allocationEngine(), current.refundAllocationEngine(),
                current.feeScheduleVersion(), current.template(), current.canonicalFormat(), current.canonicalTerms(),
                current.termsSha256(), current.documentSha256(), at);
    }

    // ------------------------------------------------------------------ F5 and F5a

    /**
     * F5 with F5a's releases, balanced by construction: {@code DR MERCHANT_PAYABLE R; DR BNPL_MERCHANT_FEE_INCOME r_f /
     * CR BNPL_PRINCIPAL (unbilled cancelled); CR BNPL_PRINCIPAL_DUE (billed cancelled); CR CUSTOMER_WALLET or
     * BNPL_CREDIT_BALANCE E; CR MERCHANT_PAYABLE r_f}, and {@code DR BNPL_INTEREST_INCOME / CR BNPL_INTEREST_ACCRUED,
     * BNPL_INTEREST_DUE} for what pay-in-N releases. A zero amount writes no line (a journal line asserts a positive
     * amount): the payable is debited the refund's net, {@code R - r_f}.
     */
    static List<JournalLine> entryLines(RefundAllocation allocation, LedgerAccountId payable, LedgerAccountId feeIncome,
            BnplLedgerAccounts accounts, Optional<LedgerAccountId> excessTo, Money accruedReleased, Money dueReleased,
            LedgerAccountId interestIncome) {
        return entryLines(allocation, payable, feeIncome, accounts, excessTo, accruedReleased, dueReleased,
                interestIncome, dueReleased.minus(dueReleased), Optional.empty());
    }

    /**
     * F5 with F5a's releases, the late fees released on cancelled instalments among them (OQ-14, `P12-TSK-020`):
     * {@code DR BNPL_FEE_INCOME / CR BNPL_FEES_DUE} for {@code lateFeesReleased}, the currency's shared fee income row
     * {@code lateFeeIncome} - taken at L8 inside the refund's one sorted call, after (P).
     */
    static List<JournalLine> entryLines(RefundAllocation allocation, LedgerAccountId payable, LedgerAccountId feeIncome,
            BnplLedgerAccounts accounts, Optional<LedgerAccountId> excessTo, Money accruedReleased, Money dueReleased,
            LedgerAccountId interestIncome, Money lateFeesReleased, Optional<LedgerAccountId> lateFeeIncome) {
        List<JournalLine> lines = new ArrayList<>();
        lines.add(new JournalLine(payable, Direction.DEBIT, allocation.refund()));
        if (allocation.feeReturned().isPositive()) {
            lines.add(new JournalLine(feeIncome, Direction.DEBIT, allocation.feeReturned()));
        }
        Money unbilled = allocation.cancelled(RefundAllocation.Cancellation.Kind.UNBILLED);
        Money billed = allocation.cancelled(RefundAllocation.Cancellation.Kind.BILLED_UNPAID);
        if (unbilled.isPositive()) {
            lines.add(new JournalLine(accounts.principal().id(), Direction.CREDIT, unbilled));
        }
        if (billed.isPositive()) {
            lines.add(new JournalLine(accounts.principalDue().id(), Direction.CREDIT, billed));
        }
        if (allocation.excess().isPositive()) {
            lines.add(new JournalLine(excessTo.orElseThrow(), Direction.CREDIT, allocation.excess()));
        }
        if (allocation.feeReturned().isPositive()) {
            lines.add(new JournalLine(payable, Direction.CREDIT, allocation.feeReturned()));
        }
        Money released = accruedReleased.plus(dueReleased);
        if (released.isPositive()) {
            lines.add(new JournalLine(interestIncome, Direction.DEBIT, released));
            if (accruedReleased.isPositive()) {
                lines.add(new JournalLine(accounts.interestAccrued().id(), Direction.CREDIT, accruedReleased));
            }
            if (dueReleased.isPositive()) {
                lines.add(new JournalLine(accounts.interestDue().id(), Direction.CREDIT, dueReleased));
            }
        }
        if (lateFeesReleased.isPositive()) {
            lines.add(new JournalLine(lateFeeIncome.orElseThrow(), Direction.DEBIT, lateFeesReleased));
            lines.add(new JournalLine(accounts.feesDue().id(), Direction.CREDIT, lateFeesReleased));
        }
        return List.copyOf(lines);
    }

    // ------------------------------------------------------------------ the event and the audit record

    /**
     * {@code bnpl.BnplRefundApplied} - the refund, its purchase and obligation, the door, the figures and the instalments it
     * cancelled, the entry; never the reason, the key, the party or the wallet.
     */
    private void publish(Connection unitOfWork, BnplRefund refund, RefundAllocation allocation) {
        List<String> sequences = new ArrayList<>();
        for (BnplRefund.Adjustment adjustment : refund.adjustments()) {
            if (adjustment.principalCancelled().isPositive()) {
                sequences.add(Integer.toString(adjustment.sequence()));
            }
        }
        EventPayload payload = EventPayload.of()
                .with("refundId", refund.id().toString())
                .with("purchaseId", refund.purchase().value().toString())
                .with("obligationId", refund.obligation().value().toString())
                .with("door", refund.door().name())
                .with("currency", refund.amount().currency().code())
                .with("scale", Integer.toString(refund.amount().scale()))
                .with("amountMinor", Long.toString(refund.amount().minorUnits()))
                .with("feeReturnedMinor", Long.toString(refund.feeReturned().minorUnits()))
                .with("payableDebitMinor", Long.toString(refund.payableDebit().minorUnits()))
                .with("cancelledUnbilledMinor", Long.toString(refund.cancelledUnbilled().minorUnits()))
                .with("cancelledBilledMinor", Long.toString(refund.cancelledBilled().minorUnits()))
                .with("excessMinor", Long.toString(refund.excess().minorUnits()))
                .with("excessTo", refund.excessTo().name())
                .with("interestReleasedMinor", Long.toString(refund.interestReleased().minorUnits()))
                .with("lateFeeReleasedMinor", Long.toString(refund.lateFeeReleased().minorUnits()))
                .with("agreementVersion", Integer.toString(refund.agreementVersion()))
                .with("allocationEngine", allocation.engine().name())
                .with("journalEntryId", refund.entry().value().toString());
        if (!sequences.isEmpty()) {
            // The sequences latest first, '_'-joined - the payload's value alphabet admits no other separator.
            payload = payload.with("instalmentsCancelled", String.join("_", sequences));
        }
        CorrelationId correlation = refund.correlation();
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        APPLIED_EVENT,
                        BnplTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        refund.purchase(),
                        BnplAcceptances.PURCHASE_AGGREGATE_TYPE,
                        clock.instant(),
                        BnplTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    /** {@code bnpl.RefundApplied}: the actor, the refund, the reason - the summary the purchase, the door and the key id. */
    private void audited(Connection unitOfWork, BnplRefund refund, Optional<String> keyId, Actor actor) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), BnplAuditAction.REFUND_APPLIED,
                REFUND_TARGET, refund.id().toString(), Optional.of(refund.reason()), AuditOutcome.SUCCEEDED,
                refund.correlation(), Optional.of("purchase=" + refund.purchase().value() + ", door=" + refund.door()
                        + keyId.map(key -> ", key=" + key).orElse("") + ", agreement version "
                        + refund.agreementVersion())));
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** No purchase the door may refund has the id - another merchant's included. */
    public static final class PurchaseNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PurchaseNotFound() {
            super("no such BNPL purchase");
        }
    }

    /** A refund was born under this key for another purchase or amount - the key reused after its claim lapsed. */
    public static final class RefundKeyReused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RefundKeyReused() {
            super("the key was already used for a different refund");
        }
    }

    /** The amount is not positive, or not in the purchase's currency and scale. */
    public static final class AmountInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AmountInvalid() {
            super("the amount is not a positive amount in the purchase's currency");
        }
    }

    /** A refusal naming the refund's kind - for the meter; never an amount in the message. */
    public abstract static class Refused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final Kind kind;

        Refused(Kind kind, String message) {
            super(message);
            this.kind = Objects.requireNonNull(kind, "kind");
        }

        /** Whether the refund asked for all that remained - for the meter. */
        public Kind kind() {
            return kind;
        }
    }

    /** The purchase is {@code FULLY_REFUNDED} - terminal. */
    public static final class PurchaseFullyRefunded extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PurchaseFullyRefunded(Kind kind) {
            super(kind, "the BNPL purchase is already fully refunded; nothing was written");
        }
    }

    /** {@code R > G - refunded} ({@code INV-BNPL-07}). */
    public static final class RefundExceedsPurchase extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RefundExceedsPurchase(Kind kind) {
            super(kind, "the refund exceeds what remains of the BNPL purchase; nothing was written");
        }
    }

    /**
     * The purchase has an open dispute (`P12-TSK-023`; ADR-0106 decision 7) - a merchant's or an operator's refund waits
     * for its resolution; nothing was written.
     */
    public static final class DisputeOpen extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        DisputeOpen(Kind kind) {
            super(kind, "the BNPL purchase has an open dispute; nothing was written");
        }
    }

    /** The refund cannot be posted now - the payable, or a closed agreement's wallet, cannot take it. */
    public static final class RefundNotPostable extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RefundNotPostable(Kind kind) {
            super(kind, "the BNPL refund cannot be posted now; nothing was written");
        }
    }

    /**
     * An operator's refund of a purchase of their own party - or of one the platform cannot place in a party (fail closed)
     * - or a merchant key's money-moving act whose issuer is the purchase's party, or cannot be placed (A5-F1).
     */
    public static final class SelfDealingRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final transient BnplPurchaseId purchase;
        private final String act;
        private final String keyId;

        SelfDealingRefused(BnplPurchaseId purchase) {
            super("an employee never refunds a BNPL purchase of their own party");
            this.purchase = purchase;
            this.act = "refund";
            this.keyId = null;
        }

        /** A merchant key's {@code act} refused: its issuer is the purchase's party, or cannot be placed in one. */
        SelfDealingRefused(BnplPurchaseId purchase, String act, String keyId) {
            super("a merchant key never refunds a BNPL purchase of its issuer's own party");
            this.purchase = purchase;
            this.act = Objects.requireNonNull(act, "act");
            this.keyId = Objects.requireNonNull(keyId, "keyId");
        }

        /** The purchase the operator or the key named - the refusal's audit target. */
        public BnplPurchaseId purchase() {
            return purchase;
        }

        /** What was refused - {@code refund}, or a dispute's {@code acceptance}. */
        public String act() {
            return act;
        }

        /** The merchant key that acted (public, ADR-0052) - empty at the operator's door. */
        public Optional<String> keyId() {
            return Optional.ofNullable(keyId);
        }
    }

    /**
     * A pay-in-N obligation not caught up on its accrual - nothing written; the door runs T-acc for {@link #obligation()}
     * in its own transaction and refunds again (the A2-F1 rule: no shared income row held across a wallet wait).
     */
    public static final class BehindAccrual extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final transient BnplObligationId obligation;
        private final Kind kind;

        BehindAccrual(BnplObligationId obligation, Kind kind) {
            super("the BNPL agreement's interest is not yet accrued to date; nothing was written");
            this.obligation = obligation;
            this.kind = kind;
        }

        /** The obligation T-acc catches up. */
        public BnplObligationId obligation() {
            return obligation;
        }

        /** Whether the refund asked for all that remained. */
        public Kind kind() {
            return kind;
        }
    }
}
