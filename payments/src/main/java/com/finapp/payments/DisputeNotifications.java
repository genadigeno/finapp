package com.finapp.payments;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Applies one dispute notification (`P7-TSK-012`, ADR-0061 §1, §2, §6) inside the delivering
 * webhook's own transaction: the dispute row, every stage it implies, their trail rows, their
 * audit records and their events commit with the delivery's evidence and dedupe record, or not
 * at all — one delivery, one transaction.
 *
 * <h2>One row however often notified ({@code INV-IDEM-04}, two ranks)</h2>
 *
 * <p>The same event id never reaches here — the inbox absorbs it. Fresh event ids carrying the
 * same statement do, and the database settles them: the opening insert's conflict on
 * {@code UNIQUE (provider, provider_dispute_reference)} makes ten racers insert one row (the
 * losers wait on the index, then lock the winner's row), and every later move is a conditional
 * transition on the row the resolver locked. A statement the dispute already reflects moves
 * nothing and records nothing.
 *
 * <h2>Order-blind by the machine ({@link DisputeStage#pathTo})</h2>
 *
 * <p>A stage ahead of the dispute's applies every stage in between, in order, each its own
 * edge with its own trail row and audit record — a {@code WON} heard first opens at
 * {@code CHARGED_BACK} and walks through {@code REPRESENTED}. A stage the dispute has already
 * passed is a late delivery: nothing moves, quietly. Anything else contradicts what we hold —
 * a {@code LOST} after a {@code WON}, a chargeback on a closed inquiry — and moves nothing,
 * loudly ({@code INV-LIFE-04}): the first record stands and the evidence holds the rest.
 *
 * <h2>The seam `P7-TSK-013` fills</h2>
 *
 * <p>{@code entered} runs once per stage applied, in order, inside this transaction — exactly
 * where each stage's posting attaches (ADR-0061 §4: the chargeback's lines, the win's inverse,
 * the loss's write-off), keyed by the dispute and its stage.
 */
@RequiredArgsConstructor
public final class DisputeNotifications {

    /** A dispute exists: the network opened it at an entry stage. */
    public static final String OPENED_EVENT_TYPE = "payments.DisputeOpened";

    /** The network has taken the funds — the stage `P7-TSK-013` posts. */
    public static final String CHARGEBACK_EVENT_TYPE = "payments.ChargebackReceived";

    /** The dispute reached a terminal stage; the payload names which. */
    public static final String RESOLVED_EVENT_TYPE = "payments.DisputeResolved";

    /** The audit target and event aggregate: a dispute is its own aggregate (ADR-0061 §1). */
    static final String TARGET_TYPE = "dispute";

    @NonNull private final DisputeStore<Connection> disputes;
    @NonNull private final PaymentIntentStore<Connection> intents;
    @NonNull private final PaymentRails rails;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** What one notification did — the door's metering and logging seam. */
    public enum Outcome {
        /** This delivery opened the dispute (and walked it to the notified stage). */
        OPENED,
        /** This delivery moved a standing dispute forward along the machine. */
        ADVANCED,
        /** The dispute already stands at the notified stage: the network repeating itself. */
        UNCHANGED,
        /** The dispute has already passed the notified stage: a late delivery, ordering only. */
        LATE,
        /** The notified stage lies neither ahead nor behind — the network contradicting our
         * record (a second outcome, a chargeback on a closed inquiry). */
        STAGE_CONTRADICTED,
        /** The statement disagrees with what is recorded: another attempt, another chargeback
         * amount, or a currency the payment is not in. */
        FACTS_CONTRADICTED,
        /** The attempt's rail declares no chargebacks (ADR-0061 §8). */
        NOT_DISPUTABLE;

        /** Moved nothing AND disagrees with what the platform holds — the operator must see it. */
        public boolean isContradiction() {
            return this == STAGE_CONTRADICTED || this == FACTS_CONTRADICTED || this == NOT_DISPUTABLE;
        }
    }

    /**
     * Applies {@code notice} to the dispute it names on {@code attempt} — the attempt the
     * signed door attributed by OUR operation reference. Runs inside the caller's platform
     * scope and transaction.
     */
    public Outcome apply(
            Connection unitOfWork,
            PaymentAttempt attempt,
            DisputeNotice notice,
            Correlation correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(attempt, "attempt must not be null");
        Objects.requireNonNull(notice, "notice must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");

        // ADR-0061 section 8 through the declaration, never a name (INV-RAIL-01): a push or
        // book rail has no chargeback to record.
        if (rails.capabilitiesOf(attempt.rail()).disputes()
                != RailCapabilities.DisputeModel.CARD_SCHEME_CHARGEBACKS) {
            return Outcome.NOT_DISPUTABLE;
        }
        PaymentIntent intent =
                intents.findById(unitOfWork, attempt.intentId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "an attempt row's intent exists: V003's foreign"
                                                        + " key holds it"));
        // Money never crosses currencies here (FX is Phase 9's): a dispute in another currency
        // than the payment is a statement we cannot attribute, never one we convert.
        if (!notice.amount().currency().equals(intent.amount().currency())) {
            return Outcome.FACTS_CONTRADICTED;
        }

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        List<DisputeStage> opening = DisputeStage.openingPathTo(notice.stage());
        DisputeStage entry = opening.get(0);
        Dispute born =
                Dispute.open(
                        ids,
                        now,
                        notice.provider(),
                        notice.reference(),
                        attempt.id(),
                        notice.reason(),
                        entry,
                        // A dispute born CHARGED_BACK carries what the network took; one born
                        // at an inquiry has taken nothing yet.
                        entry.isChargedBack() ? Optional.of(notice.amount()) : Optional.empty());
        if (disputes.insert(unitOfWork, born)) {
            entered(unitOfWork, born, Optional.empty(), attempt, correlation, now);
            walk(unitOfWork, born, opening.subList(1, opening.size()), notice, attempt,
                    correlation, now);
            return Outcome.OPENED;
        }

        // The reference names a committed dispute - the conflict waited for it - so lock the
        // standing row and judge the statement against it.
        Dispute standing =
                disputes.findForUpdate(unitOfWork, notice.provider(), notice.reference())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "the opening conflicted on this reference, so a"
                                                        + " committed dispute holds it"));
        if (!standing.attemptId().equals(attempt.id())) {
            // One dispute contests one payment: a statement naming it on another is the
            // network contradicting its own opening.
            return Outcome.FACTS_CONTRADICTED;
        }
        if (notice.stage().isChargedBack()
                && standing.chargeback().isPresent()
                && !standing.chargeback().get().equals(notice.amount())) {
            // The P7-TSK-009 amount guard at the dispute: a statement from the chargeback on
            // carries the chargeback's amount, and one that disagrees with the recorded
            // chargeback moves nothing - late or not, it is a second story about money. An
            // inquiry's figure is the transaction it asks about, never compared with it.
            return Outcome.FACTS_CONTRADICTED;
        }
        if (standing.stage() == notice.stage()) {
            return Outcome.UNCHANGED;
        }
        Optional<List<DisputeStage>> ahead = standing.stage().pathTo(notice.stage());
        if (ahead.isPresent()) {
            walk(unitOfWork, standing, ahead.get(), notice, attempt, correlation, now);
            return Outcome.ADVANCED;
        }
        return notice.stage().canReach(standing.stage())
                ? Outcome.LATE
                : Outcome.STAGE_CONTRADICTED;
    }

    /**
     * The stages ahead, in order: each one edge, its trail row, its record, its facts — the
     * statement's amount becoming the chargeback's on the edge that enters
     * {@code CHARGED_BACK}, and on no other.
     */
    private void walk(
            Connection unitOfWork,
            Dispute from,
            List<DisputeStage> path,
            DisputeNotice notice,
            PaymentAttempt attempt,
            Correlation correlation,
            Instant now) {
        Dispute current = from;
        for (DisputeStage next : path) {
            Dispute after = current.advanceTo(next, notice.amount());
            if (!disputes.transition(unitOfWork, current, after, now)) {
                // The row is this transaction's - freshly inserted or locked - so no other
                // writer can have moved it: a lost conditional here is a wiring fault.
                throw new IllegalStateException(
                        "dispute " + current.id() + " moved under its own lock");
            }
            entered(unitOfWork, after, Optional.of(current.stage()), attempt, correlation, now);
            current = after;
        }
    }

    /**
     * The dispute entered its current stage: the platform's record of the application
     * ({@code DISPUTE_STAGE_APPLIED}) and the facts consumers hear. Identifiers and our own
     * names only — never the amount, never the network's reference or code ({@code
     * INV-AUD-02}, {@code INV-PAY-03}).
     */
    private void entered(
            Connection unitOfWork,
            Dispute dispute,
            Optional<DisputeStage> from,
            PaymentAttempt attempt,
            Correlation correlation,
            Instant now) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        now,
                        PaymentsAuditAction.DISPUTE_STAGE_APPLIED,
                        TARGET_TYPE,
                        dispute.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "dispute=" + dispute.id()
                                        + ", attempt=" + attempt.id()
                                        + ", from=" + from.map(Enum::name).orElse("-")
                                        + ", to=" + dispute.stage())));
        if (from.isEmpty()) {
            announce(
                    unitOfWork,
                    dispute,
                    attempt,
                    OPENED_EVENT_TYPE,
                    EventPayload.of()
                            .with("stage", dispute.stage().name())
                            .with("reason", dispute.reason().name())
                            .with("rail", attempt.rail().value()),
                    correlation,
                    now);
        }
        if (dispute.stage() == DisputeStage.CHARGED_BACK) {
            announce(
                    unitOfWork,
                    dispute,
                    attempt,
                    CHARGEBACK_EVENT_TYPE,
                    EventPayload.of().with("stage", dispute.stage().name()),
                    correlation,
                    now);
        }
        if (dispute.stage().isTerminal()) {
            announce(
                    unitOfWork,
                    dispute,
                    attempt,
                    RESOLVED_EVENT_TYPE,
                    EventPayload.of().with("outcome", dispute.stage().name()),
                    correlation,
                    now);
        }
    }

    private void announce(
            Connection unitOfWork,
            Dispute dispute,
            PaymentAttempt attempt,
            String eventType,
            EventPayload facts,
            Correlation correlation,
            Instant now) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        eventType,
                        PaymentCreation.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        dispute.id(),
                        TARGET_TYPE,
                        now,
                        PaymentCreation.PRODUCER,
                        correlation.correlationId(),
                        correlation
                                .cause()
                                .orElseGet(
                                        () ->
                                                CausationId.of(
                                                        correlation.correlationId().value()))),
                facts.with("disputeId", dispute.id().value().toString())
                        .with("attemptId", attempt.id().value().toString())
                        .with("intentId", attempt.intentId().value().toString())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
