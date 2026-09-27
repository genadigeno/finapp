package com.finapp.payments;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The one place a PSP's word lands on a dispute response (`P7-TSK-014`) — the dispatching flight,
 * a takeover and the resolution sweep all apply through here, on the LOCKED row, so whoever wins
 * acts once: {@code WithdrawalOutcomes}' shape for an answer that moves no money.
 *
 * <p>It also owns what every SEND shares: the documents a response transmits, read back exactly
 * as it froze them, and the {@code payments.DisputeEvidenceTransmitted} record committed before
 * the bytes leave — so no transmission of evidence, first or re-sent, is off the record
 * ({@code INV-DSP-03}).
 */
@RequiredArgsConstructor
public final class DisputeResponseOutcomes {

    /** The fact consumers hear when the PSP took a response (`MODULE_ARCHITECTURE.md`). */
    public static final String SUBMITTED_EVENT_TYPE = "payments.DisputeResponseSubmitted";

    static final String RESPONSE_TARGET_TYPE = "dispute-response";

    @NonNull private final DisputeResponseStore<Connection> responses;
    @NonNull private final DisputeStore<Connection> disputes;
    @NonNull private final DisputeEvidenceStore<Connection> evidence;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The disputed payment's attempt, read for its stored rail (`P7-TSK-015`) - data, never a
     * name ({@code INV-RAIL-01}). Appended after the P7-TSK-014 fields (positional). */
    @NonNull private final PaymentAttemptStore<Connection> attempts;

    /** Where each acting judgement is reported (`P7-TSK-015`, {@link RailOutcomeObserver}). */
    @NonNull private final RailOutcomeObserver observer;

    /**
     * What one application did.
     *
     * @param acting this call's own conditional made the committed status
     */
    public record Applied(DisputeResponseStatus status, boolean acting) {}

    /**
     * Applies {@code verdict} to {@code current}, which the caller holds LOCKED: {@code APPROVED}
     * → {@code SUBMITTED} with the PSP's submission reference, {@code DECLINED} →
     * {@code FAILED(DECLINED)}, {@code NOTHING_SENT} (already judged a first send's by the
     * caller) → {@code FAILED(PROVIDER_UNAVAILABLE)}, {@code INDETERMINATE} → {@code UNKNOWN} from
     * {@code DISPATCHED} and nothing from {@code UNKNOWN}. A terminal row converges quietly.
     */
    public Applied apply(
            Connection unitOfWork,
            DisputeResponse current,
            ProviderAnswer.Verdict verdict,
            Optional<ProviderReference> submission,
            Correlation correlation) {
        if (!current.status().isResolvable()) {
            return new Applied(current.status(), false);
        }
        Optional<DisputeResponse> next =
                switch (verdict) {
                    case APPROVED -> submission.map(current::submitted);
                    case DECLINED -> Optional.of(current.failed(DisputeResponseFailure.DECLINED));
                    case NOTHING_SENT ->
                            Optional.of(current.failed(DisputeResponseFailure.PROVIDER_UNAVAILABLE));
                    case INDETERMINATE ->
                            current.status() == DisputeResponseStatus.DISPATCHED
                                    ? Optional.of(current.unknown())
                                    : Optional.empty();
                };
        if (next.isEmpty()) {
            return new Applied(current.status(), false);
        }
        Instant now = Instant.now(clock);
        if (!responses.transition(unitOfWork, current, next.get(), now)) {
            // The caller holds the row: a lost conditional is another resolver's commit it read
            // before locking - converge on the truth, act on nothing.
            return new Applied(current.status(), false);
        }
        DisputeResponse applied = next.get();
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        now,
                        PaymentsAuditAction.DISPUTE_RESPONSE_OUTCOME_APPLIED,
                        RESPONSE_TARGET_TYPE,
                        applied.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "dispute=" + applied.dispute()
                                        + ", status=" + applied.status()
                                        + applied.failure().map(why -> ", failure=" + why)
                                                .orElse(""))));
        if (applied.status() == DisputeResponseStatus.SUBMITTED) {
            announceSubmitted(unitOfWork, applied, correlation, now);
        }
        // The acting judgement, on the disputed payment's stored rail (P7-TSK-015): the
        // dispatching flight, a takeover and the sweep all leave through this one branch.
        observer.disputeResponseJudged(disputedRail(unitOfWork, applied), applied.status());
        return new Applied(applied.status(), true);
    }

    /** The rail the disputed payment travelled - read off its attempt row. */
    private RailId disputedRail(Connection unitOfWork, DisputeResponse response) {
        Dispute contested =
                disputes.findById(unitOfWork, response.dispute())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a response's dispute exists: V022's foreign key"
                                                        + " holds it"))
                        .dispute();
        return attempts.findById(unitOfWork, contested.attemptId())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "a dispute's attempt exists: V020's foreign key holds"
                                                + " it"))
                .rail();
    }

    /**
     * The documents {@code response} transmits, in the order it froze them — decrypted and
     * checksum-verified by the store; a document failing its integrity check throws before any
     * byte could be sent.
     */
    public List<DisputeResponder.EvidenceDocument> documentsOf(
            Connection unitOfWork, DisputeResponse response) {
        return evidence.contentsOf(unitOfWork, response.dispute(), response.evidence()).stream()
                .map(
                        content ->
                                new DisputeResponder.EvidenceDocument(
                                        content.evidence().kind(),
                                        content.evidence().contentType(),
                                        content.content()))
                .toList();
    }

    /**
     * Records that {@code response}'s evidence is about to leave for the PSP — committed in the
     * transaction that commits the send's permit, before the call ({@code INV-DSP-03}). The actor
     * is the flight's: the responder on the dispatch, the platform on a resolver's re-send. An
     * acceptance carries no evidence and records nothing here.
     */
    public void transmitted(Connection unitOfWork, DisputeResponse response, Correlation correlation) {
        if (!response.kind().carriesEvidence()) {
            return;
        }
        Actor actor = SecurityContext.require();
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        Instant.now(clock),
                        PaymentsAuditAction.DISPUTE_EVIDENCE_TRANSMITTED,
                        RESPONSE_TARGET_TYPE,
                        response.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "dispute=" + response.dispute()
                                        + ", evidence="
                                        + response.evidence().stream()
                                                .map(Object::toString)
                                                .collect(Collectors.joining(",", "[", "]")))));
    }

    /**
     * {@code DisputeResponseSubmitted}, from inside the conditional that made it true — so a
     * duplicate application emits nothing (INV-EVT-01). Identifiers and our own words only.
     */
    private void announceSubmitted(
            Connection unitOfWork,
            DisputeResponse response,
            Correlation correlation,
            Instant now) {
        DisputeStore.Found found =
                disputes.findById(unitOfWork, response.dispute())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a response's dispute exists: V022's foreign key"
                                                        + " holds it"));
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        SUBMITTED_EVENT_TYPE,
                        PaymentCreation.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        response.id(),
                        RESPONSE_TARGET_TYPE,
                        now,
                        PaymentCreation.PRODUCER,
                        correlation.correlationId(),
                        correlation
                                .cause()
                                .orElseGet(
                                        () ->
                                                CausationId.of(
                                                        correlation.correlationId().value()))),
                EventPayload.of()
                        .with("responseId", response.id().value().toString())
                        .with("kind", response.kind().name())
                        .with("disputeId", response.dispute().value().toString())
                        .with("attemptId", found.dispute().attemptId().value().toString())
                        .with("intentId", found.intentId().value().toString())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
