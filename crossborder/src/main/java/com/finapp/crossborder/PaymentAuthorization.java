package com.finapp.crossborder;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.persistence.DatabaseTime;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The cross-border payment's authorization (`P9-TSK-019`, PHASE_9_PLAN.md section 12.8; {@code INV-XB-01},
 * {@code INV-XB-02}, {@code INV-XB-03}, {@code INV-FX-04}): Tx1, one commit (T-b) under the door's claim.
 *
 * <p>In order: a takeover by the same key converges on the committed payment; the offer is the owner's; the
 * beneficiary is read {@code FOR SHARE} and payable - {@code ACTIVE}, clear, and not lapsed (a lapsed clearance
 * is {@code 409 ScreeningRequired}); the corridor is available; the Phase 13 seams - risk, then limit - judge
 * the instruction in-lock, a {@code REFUSE} consuming nothing; fx accepts the quote and births the cover;
 * payments routes the outbound credit, reachable only by the beneficiary's issuing rail, holds the offer's total
 * debit under the wallet lock and dispatches the credit with our end-to-end reference; the payment is born
 * {@code SUBMITTED}, audited and announced. Every refusal throws and the transaction rolls back, leaving only the
 * claim's outcome. What the customer was shown is exactly what is held and instructed ({@code INV-XB-03}).
 */
@RequiredArgsConstructor
public final class PaymentAuthorization {

    public static final String INITIATED_EVENT = "crossborder.CrossBorderPaymentInitiated";
    static final String TARGET_TYPE = "crossborder_payment";

    @NonNull private final PaymentStore payments;
    @NonNull private final OfferStore offers;
    @NonNull private final BeneficiaryStore beneficiaries;
    @NonNull private final CorridorPolicyStore policies;
    @NonNull private final CorridorAvailabilityStore availability;
    @NonNull private final CounterpartyScreening screening;
    @NonNull private final CrossBorderRiskDecision<Connection> risk;
    @NonNull private final CrossBorderLimitCheck<Connection> limits;
    @NonNull private final CrossBorderFx fx;
    @NonNull private final CrossBorderExecution execution;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;

    /** The authorization's outcome; {@code takenOver} when a crashed flight's committed payment was found. */
    public record Authorized(PaymentStore.Row payment, CrossBorderExecution.Dispatched dispatched, boolean takenOver) {}

    /** A refusal with crossborder's code. */
    public static final class PaymentRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final CrossborderErrorCode code;

        PaymentRefused(CrossborderErrorCode code) {
            super(code.code());
            this.code = code;
        }

        public CrossborderErrorCode code() {
            return code;
        }
    }

    /**
     * Tx1.
     *
     * @throws PaymentRefused crossborder's refusals
     * @throws CrossBorderFx.FxRefused fx's refusals (the quote expired, already accepted, the wallet missing...)
     * @throws CrossBorderExecution.ExecutionRefused payments' and the hold's refusals
     */
    public Authorized authorize(
            Connection unitOfWork, String dispatchKey, UUID owner, UUID quoteId, Actor actor, Instant now,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(dispatchKey, "dispatchKey must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(quoteId, "quoteId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        Optional<PaymentStore.Row> committed = payments.byDispatchKey(unitOfWork, owner, dispatchKey);
        if (committed.isPresent()) {
            CrossBorderExecution.Dispatched dispatched = execution.dispatched(unitOfWork, committed.get().id())
                    .orElseThrow(() -> new IllegalStateException("a committed payment has its dispatch"));
            return new Authorized(committed.get(), dispatched, true);
        }
        OfferStore.OfferRow offer = offers.offerOwned(unitOfWork, quoteId, owner)
                .orElseThrow(() -> new PaymentRefused(CrossborderErrorCode.OFFER_NOT_FOUND));
        BeneficiaryStore.BeneficiaryRow beneficiary = beneficiaries.lockOwnedForShare(unitOfWork, offer.beneficiary(), owner)
                .filter(row -> row.status() == BeneficiaryStatus.ACTIVE)
                .orElseThrow(() -> new PaymentRefused(CrossborderErrorCode.BENEFICIARY_NOT_PAYABLE));
        CounterpartyScreening.Clearance clearance = screening.clearance(unitOfWork, beneficiary.screeningId())
                .filter(CounterpartyScreening.Clearance::clears)
                .orElseThrow(() -> new PaymentRefused(CrossborderErrorCode.BENEFICIARY_NOT_PAYABLE));
        CorridorTerms terms = policies.version(unitOfWork, offer.corridorPolicy())
                .flatMap(version -> version.corridors().stream().filter(t -> t.key().equals(offer.corridor())).findFirst())
                .orElseThrow(() -> new IllegalStateException("an offer's pinned corridor is always readable"));
        // Lapse is judged on the DATABASE clock (P9-DOC-001): an instance's skew may never lapse a clearance early or late.
        Instant judgedAt = DatabaseTime.now(unitOfWork);
        boolean lapsed = clearance.decidedAt().map(at -> !at.plus(terms.screeningValidity()).isAfter(judgedAt))
                .orElse(true);
        if (lapsed) {
            throw new PaymentRefused(CrossborderErrorCode.SCREENING_REQUIRED);
        }
        if (!availability.isAvailable(unitOfWork, offer.corridor())) {
            throw new PaymentRefused(CrossborderErrorCode.CORRIDOR_UNAVAILABLE);
        }
        CrossBorderInstruction instruction = new CrossBorderInstruction(owner, offer.corridor(), offer.totalDebit());
        if (risk.check(unitOfWork, instruction) == CrossBorderVerdict.REFUSE) {
            throw new PaymentRefused(CrossborderErrorCode.RISK_REFUSED);
        }
        if (limits.check(unitOfWork, instruction) == CrossBorderVerdict.REFUSE) {
            throw new PaymentRefused(CrossborderErrorCode.LIMIT_REFUSED);
        }
        UUID paymentId = ids.next();
        CrossBorderFx.Accepted accepted = fx.acceptWithin(unitOfWork, quoteId, owner, paymentId, actor, correlation);
        if (!accepted.customerPays().equals(offer.source()) || !accepted.customerReceives().equals(offer.destination())) {
            throw new IllegalStateException("the accepted quote differs from its frozen offer: INV-XB-03 is broken");
        }
        CrossBorderExecution.Routed routed = execution.route(unitOfWork,
                new CrossBorderExecution.RouteAsk(offer.destination(), beneficiary.country(), beneficiary.rail()));
        CrossBorderExecution.Dispatched dispatched = execution.dispatchWithin(unitOfWork, routed,
                new CrossBorderExecution.DispatchAsk(owner, paymentId, dispatchKey, accepted.sourceWallet(), offer.totalDebit(),
                        beneficiary.destinationReference(), offer.destination()));
        PaymentStore.Row payment = new PaymentStore.Row(paymentId, owner, beneficiary.id(), offer.id(), quoteId,
                offer.corridor(), dispatchKey, dispatched.outboundCredit(), accepted.coverId(), dispatched.hold(),
                PaymentStore.Status.SUBMITTED, now);
        payments.insert(unitOfWork, payment);
        payments.appendEvent(unitOfWork, ids.next(), paymentId, Optional.empty(), PaymentStore.Status.SUBMITTED, "AUTHORIZED", now);
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, now, CrossborderAuditAction.CROSSBORDER_PAYMENT_AUTHORIZED,
                TARGET_TYPE, paymentId.toString(), Optional.empty(), AuditOutcome.SUCCEEDED, correlation,
                Optional.of("payment=" + paymentId + ", quote=" + quoteId + ", corridor=" + offer.corridor().code()
                        + ", rail=" + dispatched.rail())));
        outbox.write(unitOfWork,
                new EventEnvelope(EventId.next(ids), INITIATED_EVENT, CorridorPolicyAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION, new PaymentEventId(paymentId), TARGET_TYPE, now,
                        CorridorPolicyAdministration.PRODUCER, correlation, CausationId.of(correlation.value())),
                EventPayload.of()
                        .with("payment", paymentId.toString())
                        .with("beneficiary", beneficiary.id().value().toString())
                        .with("corridor", offer.corridor().code())
                        .with("quote", quoteId.toString())
                        .with("totalDebitMinor", Long.toString(offer.totalDebit().minorUnits()))
                        .with("totalDebitScale", Integer.toString(offer.totalDebit().scale()))
                        .with("destinationMinor", Long.toString(offer.destination().minorUnits()))
                        .with("destinationScale", Integer.toString(offer.destination().scale()))
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
        return new Authorized(payment, dispatched, false);
    }

    /** {@code owner}'s payment {@code id}, if it is theirs. */
    public Optional<PaymentStore.Row> read(Connection unitOfWork, UUID id, UUID owner) {
        return payments.findOwned(unitOfWork, id, owner);
    }

    /** The payment's identity on the envelope. */
    private static final class PaymentEventId extends EntityId {
        PaymentEventId(UUID value) {
            super(value);
        }
    }
}
