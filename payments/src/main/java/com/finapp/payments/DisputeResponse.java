package com.finapp.payments;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The platform's answer to a chargeback, sent through the card PSP (`P7-TSK-014`, ADR-0061 §7) —
 * a representment carrying the dispute's evidence, or an acceptance conceding it.
 *
 * <h2>A dispatch like any other</h2>
 *
 * <p>ADR-0061 §7 in so many words: the response is dispatch-before-call and keyed
 * ({@code INV-PAY-04}). Its {@link #reference()} is minted HERE, at birth, and committed with the
 * {@code DISPATCHED} row before the PSP is asked; every later send — a takeover's, a resolver's —
 * presents the same reference, so however many instances send it, the PSP records one response.
 *
 * <h2>It moves no stage and no money</h2>
 *
 * <p>{@link DisputeResponseStatus#SUBMITTED} means the PSP took the answer. What the network
 * decides — {@code REPRESENTED}, {@code ACCEPTED}, {@code WON}, {@code LOST} — arrives by
 * notification, and `P7-TSK-013` posts what each stage owes. The dispute row is never written by
 * a response (the `P7-TSK-013` design input), so the two writers cannot disagree about a stage.
 *
 * <h2>What it froze</h2>
 *
 * <p>The dispute, the kind, the reference, the evidence set (exactly the documents it transmits,
 * in order), the requester and the operator's reason are fixed at birth — `V022` freezes them for
 * every writer. A re-send therefore transmits exactly what the first send did.
 */
public final class DisputeResponse {

    /** The reference's marked prefix — a dispute response on the PSP's operations list. */
    static final String REFERENCE_PREFIX = "dsr-";

    private final DisputeResponseId id;
    private final DisputeId dispute;
    private final DisputeResponseKind kind;
    private final DisputeResponseStatus status;
    private final Optional<DisputeResponseFailure> failure;
    private final ProviderIdempotencyReference reference;
    private final Optional<ProviderReference> providerReference;
    private final List<DisputeEvidenceId> evidence;
    private final String requestedById;
    private final String requestedByType;
    private final Optional<String> reason;
    private final Instant createdAt;

    private DisputeResponse(
            DisputeResponseId id,
            DisputeId dispute,
            DisputeResponseKind kind,
            DisputeResponseStatus status,
            Optional<DisputeResponseFailure> failure,
            ProviderIdempotencyReference reference,
            Optional<ProviderReference> providerReference,
            List<DisputeEvidenceId> evidence,
            String requestedById,
            String requestedByType,
            Optional<String> reason,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.dispute = Objects.requireNonNull(dispute, "dispute must not be null");
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
        this.reference = Objects.requireNonNull(reference, "reference must not be null");
        this.providerReference =
                Objects.requireNonNull(providerReference, "providerReference must not be null");
        this.evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
        this.requestedById = Objects.requireNonNull(requestedById, "requestedById must not be null");
        this.requestedByType =
                Objects.requireNonNull(requestedByType, "requestedByType must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        // Every rule V022's CHECKs hold is held here too, so a corrupt row is refused at read
        // rather than acted on (the Withdrawal constructor's stance).
        if (failure.isPresent() != (status == DisputeResponseStatus.FAILED)) {
            throw new IllegalArgumentException(
                    "a failure reason is recorded exactly when the response FAILED");
        }
        if (providerReference.isPresent() != (status == DisputeResponseStatus.SUBMITTED)) {
            throw new IllegalArgumentException(
                    "the PSP's submission reference arrives exactly with SUBMITTED");
        }
        if (kind.carriesEvidence() == this.evidence.isEmpty()) {
            throw new IllegalArgumentException(
                    "a representment carries evidence and an acceptance carries none");
        }
        if (this.evidence.size() > DisputeEvidenceContent.MAX_PER_DISPUTE
                || new HashSet<>(this.evidence).size() != this.evidence.size()) {
            throw new IllegalArgumentException(
                    "a response carries at most " + DisputeEvidenceContent.MAX_PER_DISPUTE
                            + " distinct documents");
        }
        if (reason.isPresent() && reason.get().isBlank()) {
            throw new IllegalArgumentException("a stated reason is never blank");
        }
    }

    /**
     * A response, born {@code DISPATCHED} with its reference minted ({@code INV-PAY-04}) — the
     * requester is whoever is acting: the merchant over its key, or the operator for a payment
     * with no merchant, whose reason the record keeps.
     */
    public static DisputeResponse dispatch(
            IdGenerator ids,
            Instant at,
            DisputeId dispute,
            DisputeResponseKind kind,
            List<DisputeEvidenceId> evidence,
            Actor requester,
            Optional<String> reason) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(at, "at must not be null");
        Objects.requireNonNull(requester, "requester must not be null");
        return new DisputeResponse(
                DisputeResponseId.next(ids),
                dispute,
                kind,
                DisputeResponseStatus.DISPATCHED,
                Optional.empty(),
                new ProviderIdempotencyReference(REFERENCE_PREFIX + ids.next()),
                Optional.empty(),
                evidence,
                requester.id(),
                requester.type().name(),
                reason,
                // The column's own microsecond resolution (the P7-TSK-004 clock lesson).
                at.truncatedTo(ChronoUnit.MICROS));
    }

    /** A row read back from storage — the constructor's coherence refuses a corrupt one. */
    public static DisputeResponse rehydrate(
            DisputeResponseId id,
            DisputeId dispute,
            DisputeResponseKind kind,
            DisputeResponseStatus status,
            Optional<DisputeResponseFailure> failure,
            ProviderIdempotencyReference reference,
            Optional<ProviderReference> providerReference,
            List<DisputeEvidenceId> evidence,
            String requestedById,
            String requestedByType,
            Optional<String> reason,
            Instant createdAt) {
        return new DisputeResponse(
                id, dispute, kind, status, failure, reference, providerReference, evidence,
                requestedById, requestedByType, reason, createdAt);
    }

    /** The PSP took the response: its submission reference, the reconciliation key. */
    public DisputeResponse submitted(ProviderReference submission) {
        Objects.requireNonNull(submission, "submission must not be null");
        return moved(DisputeResponseStatus.SUBMITTED, Optional.empty(), Optional.of(submission));
    }

    /** The PSP refused it, or the first send transmitted nothing. */
    public DisputeResponse failed(DisputeResponseFailure why) {
        Objects.requireNonNull(why, "why must not be null");
        return moved(DisputeResponseStatus.FAILED, Optional.of(why), Optional.empty());
    }

    /** Sent, or possibly sent, and no answer. */
    public DisputeResponse unknown() {
        return moved(DisputeResponseStatus.UNKNOWN, Optional.empty(), Optional.empty());
    }

    private DisputeResponse moved(
            DisputeResponseStatus next,
            Optional<DisputeResponseFailure> why,
            Optional<ProviderReference> submission) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalDisputeResponseTransitionException(status, next);
        }
        return new DisputeResponse(
                id, dispute, kind, next, why, reference, submission, evidence, requestedById,
                requestedByType, reason, createdAt);
    }

    public DisputeResponseId id() {
        return id;
    }

    public DisputeId dispute() {
        return dispute;
    }

    public DisputeResponseKind kind() {
        return kind;
    }

    public DisputeResponseStatus status() {
        return status;
    }

    public Optional<DisputeResponseFailure> failure() {
        return failure;
    }

    /** OUR reference — minted at birth, presented by every send, the query's key. */
    public ProviderIdempotencyReference reference() {
        return reference;
    }

    /** The PSP's submission reference — present exactly when {@code SUBMITTED}. */
    public Optional<ProviderReference> providerReference() {
        return providerReference;
    }

    /** The documents this response transmits, in order — frozen at birth. */
    public List<DisputeEvidenceId> evidence() {
        return evidence;
    }

    public String requestedById() {
        return requestedById;
    }

    public String requestedByType() {
        return requestedByType;
    }

    /** The operator's stated reason — the merchant's own act needs none. */
    public Optional<String> reason() {
        return reason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** Identifiers, kind and status only — never a reference or a reason (INV-AUD-02). */
    @Override
    public String toString() {
        return "DisputeResponse[" + id + ", " + kind + ", " + status + "]";
    }
}
