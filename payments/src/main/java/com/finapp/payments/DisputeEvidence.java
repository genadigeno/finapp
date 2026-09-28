package com.finapp.payments;

import java.time.Instant;
import java.util.Objects;

/**
 * One document a responder attached to a dispute (`P7-TSK-014`, ADR-0061 §7) — the metadata
 * only. The content never rides this type: it is reachable solely through the store's audited
 * read paths, decrypted there and checksum-verified ({@code INV-DSP-03}, {@code INV-KYC-06}'s
 * regime restated), so no caller can hold evidence bytes without the read being on the record.
 *
 * <p>Append-only: `V022` grants the application role {@code SELECT} and {@code INSERT} on the
 * table and nothing else. A document is superseded by uploading another, never edited.
 *
 * @param uploadedById the actor's identifier — the merchant, or the operator acting for a
 *     payment with no merchant
 * @param uploadedByType the actor's type, as the audit trail names it
 */
public record DisputeEvidence(
        DisputeEvidenceId id,
        DisputeId dispute,
        DisputeEvidenceKind kind,
        DisputeEvidenceContentType contentType,
        int contentLength,
        String uploadedById,
        String uploadedByType,
        Instant uploadedAt) {

    public DisputeEvidence {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(dispute, "dispute must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(uploadedById, "uploadedById must not be null");
        Objects.requireNonNull(uploadedByType, "uploadedByType must not be null");
        Objects.requireNonNull(uploadedAt, "uploadedAt must not be null");
        if (contentLength < 1 || contentLength > DisputeEvidenceContent.MAX_BYTES) {
            throw new IllegalArgumentException(
                    "an evidence document is 1.." + DisputeEvidenceContent.MAX_BYTES + " bytes");
        }
    }

    /** Identifiers and kind only — the uploader's identity stays out of log lines. */
    @Override
    public String toString() {
        return "DisputeEvidence[" + id + ", " + kind + "]";
    }
}
