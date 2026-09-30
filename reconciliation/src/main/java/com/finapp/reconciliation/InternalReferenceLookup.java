package com.finapp.reconciliation;

import java.sql.Connection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What the platform's own records say about an external subject's references
 * (`P8-TSK-010`, ADR-0069 §§1–2) — declared here, implemented in {@code app} over payments'
 * and merchant's PUBLIC read stores (ADR-0064: reconciliation compiles against no sibling
 * but {@code ledger}). Read-only, lock-free; it TYPES breaks and never allocates
 * (ADR-0068), and a lookup failure fails the caller's transaction — it is never read as
 * "unknown".
 *
 * <p>The answer is the STRONGEST knowledge across the given references
 * ({@link InternalClassification}'s order), with the strongest hit's operation reference
 * and state carried for the break's frozen internal columns. An instant-rail scheme
 * reference resolves through {@code payments.scheme_execution_claim} to exactly one
 * subject — a claim is taken only when an execution completed, so a reference no claim
 * holds names no completed execution (ADR-0069 §2).
 */
public interface InternalReferenceLookup {

    /**
     * @param rail the item's rail, where its source has one — the scheme-claim resolution
     *     needs it; empty for the card PSP's references (payments' {@code RailId} is not
     *     this module's to name, so it rides as its string value)
     * @param references the item's typed keys, as stored
     * @param scopeSourceId the item's key-scope source (`P8-TSK-017`) — what the composition
     *     reads a rail off when {@code rail} is empty: the matcher names no rail, the source
     *     register composed in {@code app} does
     */
    record LookupSubject(
            Optional<String> rail, Map<KeyKind, String> references, Optional<UUID> scopeSourceId) {

        public LookupSubject {
            Objects.requireNonNull(rail, "rail must not be null");
            Objects.requireNonNull(references, "references must not be null");
            Objects.requireNonNull(scopeSourceId, "scopeSourceId must not be null");
            references = Map.copyOf(references);
        }

        /** A subject naming its rail, or none — the `P8-TSK-010` shape. */
        public LookupSubject(Optional<String> rail, Map<KeyKind, String> references) {
            this(rail, references, Optional.empty());
        }
    }

    /**
     * The frozen answer: classification, the strongest hit's own identifiers, and — since
     * `P8-TSK-017` — WHAT it found, so a terminal refund is typed a refund's mismatch whatever
     * line named it.
     */
    record InternalReference(
            InternalClassification classification,
            Optional<String> operationRef,
            Optional<String> state,
            Optional<InternalSubject> subject) {

        public InternalReference {
            Objects.requireNonNull(classification, "classification must not be null");
            Objects.requireNonNull(operationRef, "operationRef must not be null");
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(subject, "subject must not be null");
        }

        /** An answer that does not say what it found — the `P8-TSK-010` shape. */
        public InternalReference(
                InternalClassification classification,
                Optional<String> operationRef,
                Optional<String> state) {
            this(classification, operationRef, state, Optional.empty());
        }

        public static InternalReference unknown() {
            return new InternalReference(
                    InternalClassification.UNKNOWN, Optional.empty(), Optional.empty());
        }

        /** The aggregation rule: the stronger answer wins; on a tie the first stands. */
        public InternalReference strongest(InternalReference other) {
            return other.classification().ordinal() > classification.ordinal()
                    ? other
                    : this;
        }
    }

    InternalReference classify(Connection unitOfWork, LookupSubject subject);
}
