package com.finapp.platform.idempotency;

import com.finapp.platform.correlation.CorrelationId;
import java.time.Instant;
import java.util.Objects;

/**
 * A claim as it exists in storage.
 *
 * @param key what the claim is on
 * @param fingerprint the request it stands for ({@code INV-IDEM-03})
 * @param state where the claim is in its lifecycle
 * @param response the stored outcome; meaningful only in a terminal state
 * @param correlationId the flow that made the claim, so a duplicate submission is traceable
 * @param createdAt when the claim was made — the basis for deciding a claim is stale
 * @param expiresAt when retention may remove it
 */
public record IdempotencyRecord(
        IdempotencyKey key,
        RequestFingerprint fingerprint,
        IdempotencyState state,
        StoredResponse response,
        CorrelationId correlationId,
        Instant createdAt,
        Instant expiresAt) {

    public IdempotencyRecord {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    // No isStaleAt(): whether a claim is abandoned is decided by the database's lease, not by
    // any instance's clock. A predicate here would invite exactly the single-instance
    // assumption that made a claim stealable under clock skew (V004).
}
