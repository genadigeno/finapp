package com.finapp.payments;

import java.util.List;
import java.util.Objects;

/**
 * The card PSP's dispute port (`P7-TSK-014`, ADR-0061 §7): answer a chargeback — contest it with
 * evidence, or concede it — in our vocabulary, totally, idempotent at the PSP by our reference.
 *
 * <h2>Its own port, not a fifth operation on {@link PaymentProvider}</h2>
 *
 * <p>{@link PaymentProvider} is the two-step model's money-moving contract (ADR-0059 §2); a
 * dispute answer moves no money and exists only on a rail that declares card-scheme chargebacks
 * ({@code INV-RAIL-01}). The card adapter implements both, and the composition root binds this
 * one beside the card rail.
 *
 * <h2>The contract is {@link PaymentProvider}'s</h2>
 *
 * <p>Total: misbehaviour is a {@link ProviderAnswer}, never an exception — a refused connection
 * {@code NOTHING_SENT}, silence {@code INDETERMINATE}. Idempotent by signature: every request
 * carries the {@link ProviderIdempotencyReference} committed before the call, and {@link #query}
 * keys on it ({@code INV-PAY-04}). Called while no database connection is held (ADR-0046).
 */
public interface DisputeResponder {

    /** The PSP's stable name — the scope of its dispute references (`V020`). */
    String providerName();

    /** Answers the dispute {@code request} names. */
    ProviderAnswer respond(DisputeResponseRequest request);

    /**
     * What happened to the operation OUR reference names — read-only and idempotent at the PSP,
     * so every instance may ask concurrently: no lease, no leader.
     */
    QueryAnswer query(ProviderIdempotencyReference ourReference);

    /**
     * One answer: our reference, the network's dispute reference, what we answer and — for a
     * representment — the documents, in the order the response froze them.
     */
    record DisputeResponseRequest(
            ProviderIdempotencyReference reference,
            ProviderReference dispute,
            DisputeResponseKind kind,
            List<EvidenceDocument> evidence) {

        public DisputeResponseRequest {
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(dispute, "dispute must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
            if (kind.carriesEvidence() == evidence.isEmpty()) {
                throw new IllegalArgumentException(
                        "a representment carries evidence and an acceptance carries none");
            }
        }

        /** References and kind only — never the documents ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "DisputeResponseRequest[" + reference.value() + ", " + kind + ", "
                    + evidence.size() + " documents]";
        }
    }

    /** One document on the wire: what it is, its format, and its bytes. */
    record EvidenceDocument(
            DisputeEvidenceKind kind,
            DisputeEvidenceContentType contentType,
            DisputeEvidenceContent content) {

        public EvidenceDocument {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(contentType, "contentType must not be null");
            Objects.requireNonNull(content, "content must not be null");
        }

        /** Never the bytes ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "EvidenceDocument[" + kind + ", " + contentType + "]";
        }
    }
}
