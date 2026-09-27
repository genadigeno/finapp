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
 * Applies one dispute notification (`P7-TSK-012`, ADR-0061 §1, §2, §6; the money since
 * `P7-TSK-013`, §3–§5) inside the delivering webhook's own transaction: the dispute row, every
 * stage it implies, their trail rows, their audit records, their events and their postings
 * commit with the delivery's evidence and dedupe record, or not at all — one delivery, one
 * transaction.
 *
 * <h2>The attempt first (`P7-TSK-013`, the lock order `P7-TSK-012` recorded)</h2>
 *
 * <p>Every delivery takes the contested attempt's row {@code FOR UPDATE} before anything else:
 * the combined bound is judged under that lock (the one a refund's dispatch takes, so the two
 * money paths serialise on one row, {@code INV-DSP-01}), and taking it before the dispute's
 * insert avoids the key-share-then-update cycle the insert's foreign key would otherwise start
 * between two racing deliveries (the `P7-TSK-011` deadlock class).
 *
 * <h2>One row however often notified ({@code INV-IDEM-04}, two ranks)</h2>
 *
 * <p>The same event id never reaches here — the inbox absorbs it. Fresh event ids carrying the
 * same statement do, and they queue on the attempt's lock: the first opens the dispute, the rest
 * find it and judge the statement against it. The unique key on
 * {@code (provider, provider_dispute_reference)} stays the arbiter for a statement naming the
 * reference on ANOTHER attempt. Every move is a conditional transition on the locked row, and
 * every posting is keyed by the dispute and its stage: a duplicate finds both taken.
 *
 * <h2>Order-blind by the machine ({@link DisputeStage#pathTo})</h2>
 *
 * <p>A stage ahead of the dispute's applies every stage in between, in order, each its own
 * edge with its own trail row, audit record and postings — a {@code WON} heard first opens at
 * {@code CHARGED_BACK} (the chargeback posted, then its share attributed), walks through
 * {@code REPRESENTED}, and reverses both. A stage the dispute has already passed is a late
 * delivery: nothing moves, quietly. Anything else contradicts what we hold and moves nothing,
 * loudly ({@code INV-LIFE-04}): the first record stands and the evidence holds the rest.
 *
 * <h2>Recorded now, whatever the capture's state — attributed as the capture lands</h2>
 *
 * <p>The external fact first (ADR-0061 §4): a chargeback on an attempt whose capture is still
 * being resolved, or not yet dispatched, is recorded at once against what has been captured —
 * nothing — so its whole amount rests as excess; when the capture lands,
 * {@link ChargebackAccounting#captureLanded} gives the counterparty its share, exactly the split
 * a chargeback arriving then would have taken. No statement waits on a retry window.
 */
@RequiredArgsConstructor
public final class DisputeNotifications {

    /** A dispute exists: the network opened it at an entry stage. */
    public static final String OPENED_EVENT_TYPE = "payments.DisputeOpened";

    /** The network has taken the funds — the stage whose posting the chargeback is. */
    public static final String CHARGEBACK_EVENT_TYPE = "payments.ChargebackReceived";

    /** The dispute reached a terminal stage; the payload names which. */
    public static final String RESOLVED_EVENT_TYPE = "payments.DisputeResolved";

    /** The audit target and event aggregate: a dispute is its own aggregate (ADR-0061 §1). */
    static final String TARGET_TYPE = "dispute";

    @NonNull private final DisputeStore<Connection> disputes;
    @NonNull private final PaymentIntentStore<Connection> intents;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The attempts, for the lock every delivery takes first (`P7-TSK-013`). */
    @NonNull private final PaymentAttemptStore<Connection> attempts;

    /**
     * The money of each stage (`P7-TSK-013`): the split, the postings, the fee — and the
     * capability gate, whose one definition moved there with the rails it reads (the refund
     * failure's re-attribution asks the same question).
     */
    @NonNull private final ChargebackAccounting accounting;

    /** What one notification did — the door's metering and logging seam. */
    public enum Outcome {
        /** This delivery opened the dispute (and walked it to the notified stage). */
        OPENED,
        /** This delivery moved a standing dispute forward along the machine. */
        ADVANCED,
        /** The dispute's stage did not move, but the statement carried the PSP's dispute fee,
         * reported late, and this delivery recorded it (`P7-TSK-013`). */
        FEE_RECORDED,
        /** The dispute already stands at the notified stage: the network repeating itself. */
        UNCHANGED,
        /** The dispute has already passed the notified stage: a late delivery, ordering only. */
        LATE,
        /** The notified stage lies neither ahead nor behind — the network contradicting our
         * record (a second outcome, a chargeback on a closed inquiry). */
        STAGE_CONTRADICTED,
        /** The statement disagrees with what is recorded: another attempt, another chargeback
         * amount, another fee, or a currency the payment is not in. */
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
        if (!accounting.disputable(attempt)) {
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
        // than the payment is a statement we cannot attribute, never one we convert. The fee
        // shares the statement's currency by DisputeNotice's own rule.
        if (!notice.amount().currency().equals(intent.amount().currency())) {
            return Outcome.FACTS_CONTRADICTED;
        }

        // THE ATTEMPT, LOCKED, FIRST: the combined bound's serialisation point (a refund's
        // dispatch takes the same lock), taken before the dispute insert's foreign key takes
        // FOR KEY SHARE on the same row - key-share-then-update across two racing deliveries is
        // the P7-TSK-011 deadlock class.
        PaymentAttempt locked =
                attempts.lockById(unitOfWork, attempt.id())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "attempt " + attempt.id() + " attributed and"
                                                        + " then vanished"));

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        Optional<Dispute> existing =
                disputes.findForUpdate(unitOfWork, notice.provider(), notice.reference());
        if (existing.isEmpty()) {
            List<DisputeStage> opening = DisputeStage.openingPathTo(notice.stage());
            DisputeStage entry = opening.get(0);
            Dispute born =
                    Dispute.open(
                            ids,
                            now,
                            notice.provider(),
                            notice.reference(),
                            locked.id(),
                            notice.reason(),
                            entry,
                            // A dispute born CHARGED_BACK carries what the network took and who
                            // bears it, judged NOW under the lock; one born at an inquiry has
                            // taken nothing yet.
                            entry.isChargedBack()
                                    ? Optional.of(
                                            accounting.split(
                                                    unitOfWork, locked, intent, notice.amount()))
                                    : Optional.empty(),
                            entry.isChargedBack() ? notice.fee() : Optional.empty());
            if (disputes.insert(unitOfWork, born)) {
                entered(unitOfWork, born, Optional.empty(), locked, intent, correlation, now);
                if (born.fee().isPresent()) {
                    accounting.feeRecorded(unitOfWork, born, locked, correlation, now);
                }
                walk(unitOfWork, born, opening.subList(1, opening.size()), notice, locked,
                        intent, correlation, now);
                return Outcome.OPENED;
            }
            // The reference names a dispute committed on ANOTHER attempt since the read (this
            // attempt's lock serialises everything on it): the unique key waited for it.
            existing = disputes.findForUpdate(unitOfWork, notice.provider(), notice.reference());
        }

        Dispute standing =
                existing.orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "the opening conflicted on this reference, so a"
                                                + " committed dispute holds it"));
        if (!standing.attemptId().equals(locked.id())) {
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
        if (notice.fee().isPresent()
                && standing.fee().isPresent()
                && !standing.fee().get().equals(notice.fee().get())) {
            // The same guard at the fee: recorded once, never a second story.
            return Outcome.FACTS_CONTRADICTED;
        }
        boolean feeArrives = notice.fee().isPresent() && standing.fee().isEmpty();

        if (standing.stage() == notice.stage()) {
            return feeArrives
                    ? recordFee(unitOfWork, standing, notice, locked, correlation, now)
                    : Outcome.UNCHANGED;
        }
        Optional<List<DisputeStage>> ahead = standing.stage().pathTo(notice.stage());
        if (ahead.isPresent()) {
            Dispute reached =
                    walk(unitOfWork, standing, ahead.get(), notice, locked, intent, correlation,
                            now);
            if (notice.fee().isPresent() && reached.fee().isEmpty()) {
                // The dispute was charged back before this walk, and the fee arrives now.
                recordFee(unitOfWork, reached, notice, locked, correlation, now);
            }
            return Outcome.ADVANCED;
        }
        if (notice.stage().canReach(standing.stage())) {
            // A late statement still carries a first report of the fee (its stage is charged
            // back, so the dispute's is too): the fact is new even though the stage is not.
            return feeArrives
                    ? recordFee(unitOfWork, standing, notice, locked, correlation, now)
                    : Outcome.LATE;
        }
        return Outcome.STAGE_CONTRADICTED;
    }

    /**
     * The stages ahead, in order: each one edge, its trail row, its record, its facts and its
     * postings — the chargeback and its attribution judged on the edge that enters
     * {@code CHARGED_BACK}, and on no other, together with the fee when the statement carries it.
     *
     * @return the dispute as the walk left it
     */
    private Dispute walk(
            Connection unitOfWork,
            Dispute from,
            List<DisputeStage> path,
            DisputeNotice notice,
            PaymentAttempt locked,
            PaymentIntent intent,
            Correlation correlation,
            Instant now) {
        Dispute current = from;
        for (DisputeStage next : path) {
            boolean entersChargeback = next == DisputeStage.CHARGED_BACK;
            Dispute after =
                    current.advanceTo(
                            next,
                            entersChargeback
                                    ? Optional.of(
                                            accounting.split(
                                                    unitOfWork, locked, intent, notice.amount()))
                                    : Optional.empty());
            boolean feeHere = entersChargeback && notice.fee().isPresent();
            if (feeHere) {
                after = after.withFee(notice.fee().get());
            }
            if (!disputes.transition(unitOfWork, current, after, now)) {
                // The row is this transaction's - freshly inserted or locked - so no other
                // writer can have moved it: a lost conditional here is a wiring fault.
                throw new IllegalStateException(
                        "dispute " + current.id() + " moved under its own lock");
            }
            entered(unitOfWork, after, Optional.of(current.stage()), locked, intent, correlation,
                    now);
            if (feeHere) {
                accounting.feeRecorded(unitOfWork, after, locked, correlation, now);
            }
            current = after;
        }
        return current;
    }

    /** The PSP's fee, first reported on a dispute whose stage this statement does not move. */
    private Outcome recordFee(
            Connection unitOfWork,
            Dispute standing,
            DisputeNotice notice,
            PaymentAttempt locked,
            Correlation correlation,
            Instant now) {
        Dispute charged = standing.withFee(notice.fee().orElseThrow());
        if (!disputes.recordFee(unitOfWork, standing, charged)) {
            throw new IllegalStateException(
                    "dispute " + standing.id() + "'s fee moved under its own lock");
        }
        accounting.feeRecorded(unitOfWork, charged, locked, correlation, now);
        return Outcome.FEE_RECORDED;
    }

    /**
     * The dispute entered its current stage: the platform's record of the application
     * ({@code DISPUTE_STAGE_APPLIED}), the facts consumers hear, then the stage's money — cause
     * before effect on the trail. Identifiers and our own names only — never an amount, never
     * the network's reference or code ({@code INV-AUD-02}, {@code INV-PAY-03}).
     */
    private void entered(
            Connection unitOfWork,
            Dispute dispute,
            Optional<DisputeStage> from,
            PaymentAttempt attempt,
            PaymentIntent intent,
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
                    splitAccounts(
                            unitOfWork,
                            EventPayload.of().with("stage", dispute.stage().name()),
                            dispute,
                            intent),
                    correlation,
                    now);
        }
        if (dispute.stage().isTerminal()) {
            announce(
                    unitOfWork,
                    dispute,
                    attempt,
                    RESOLVED_EVENT_TYPE,
                    splitAccounts(
                            unitOfWork,
                            EventPayload.of().with("outcome", dispute.stage().name()),
                            dispute,
                            intent),
                    correlation,
                    now);
        }
        accounting.entered(unitOfWork, dispute, attempt, intent, correlation, now);
    }

    /**
     * The split's accounts on a stage fact (the backlog's events line): the counterparty's
     * account when it bears a posted share, the recoverable when part of the chargeback rests
     * there — identifiers, never amounts.
     */
    private EventPayload splitAccounts(
            Connection unitOfWork, EventPayload facts, Dispute dispute, PaymentIntent intent) {
        if (dispute.split().isEmpty()) {
            return facts;
        }
        ChargebackSplit split = dispute.split().get();
        EventPayload payload = facts;
        if (split.counterpartyShare().isPositive()) {
            payload = payload.with("counterpartyAccountId", intent.creditAccount().value().toString());
        }
        if (split.recoverable().isPositive()) {
            payload =
                    payload.with(
                            "recoverableAccountId",
                            accounting
                                    .recoverableAccount(unitOfWork, split.amount())
                                    .value()
                                    .toString());
        }
        return payload;
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
