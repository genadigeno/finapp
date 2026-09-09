package com.finapp.kyc;

import com.finapp.sharedkernel.id.IdGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A captured document: the metadata of evidence (`P2-TSK-008`, ADR-0036).
 *
 * <h2>The content is deliberately not a field</h2>
 *
 * <p>This type carries everything <em>about</em> the bytes — type, checksum, length — and never
 * the bytes. Content exists only on the wire into and out of {@link DocumentStore}, which is what
 * makes "readable only through the audited path" ({@code INV-KYC-06}) structurally arguable: a
 * component holding a {@code KycDocument} holds nothing worth exfiltrating, and the one method
 * that returns content is the one {@link DocumentAccess} audits.
 *
 * <h2>An immutable fact, not a lifecycle</h2>
 *
 * <p>A document has no state machine: it is evidence, terminal at birth, append-only at
 * {@code DB-PRIVILEGE} ({@code INV-HIST-02}) — the {@code ConsentRecord} shape, not the
 * {@code KycCase} shape. Replacing a bad photo is uploading another document, never editing one.
 *
 * <h2>The checksum is computed here, on the bytes received</h2>
 *
 * <p>{@code INV-HIST-02}: a checksum recorded <em>where the source is a file</em>. It is computed
 * at capture — before encryption, before storage — and verified on every read, so "the bytes the
 * decision rested on are the bytes received" is a demonstrable claim years later rather than an
 * assumption about storage. It is also the identity a retry converges on: see
 * {@link DocumentStore#appendOrConverge}.
 */
public final class KycDocument {

    /** SHA-256: 32 bytes, and the constraint on the column is generated from this. */
    public static final int CHECKSUM_BYTES = 32;

    private final DocumentId id;
    private final KycCaseId caseId;
    private final DocumentType type;
    private final DocumentContentType contentType;
    private final byte[] checksum;
    private final int contentLength;
    private final Instant uploadedAt;

    private KycDocument(
            DocumentId id,
            KycCaseId caseId,
            DocumentType type,
            DocumentContentType contentType,
            byte[] checksum,
            int contentLength,
            Instant uploadedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.caseId = Objects.requireNonNull(caseId, "caseId must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.contentType = Objects.requireNonNull(contentType, "contentType must not be null");
        this.checksum = Objects.requireNonNull(checksum, "checksum must not be null");
        if (checksum.length != CHECKSUM_BYTES) {
            throw new IllegalArgumentException(
                    "a checksum must be " + CHECKSUM_BYTES + " bytes of SHA-256");
        }
        if (contentLength < 1 || contentLength > DocumentBytes.MAX_BYTES) {
            throw new IllegalArgumentException(
                    "content length must be between 1 and " + DocumentBytes.MAX_BYTES);
        }
        this.contentLength = contentLength;
        this.uploadedAt = Objects.requireNonNull(uploadedAt, "uploadedAt must not be null");
    }

    /** Captures a document onto a case, computing the checksum of the bytes received. */
    public static KycDocument capture(
            IdGenerator ids,
            Clock clock,
            KycCaseId caseId,
            DocumentType type,
            DocumentContentType contentType,
            DocumentBytes content) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(content, "content must not be null");
        return new KycDocument(
                DocumentId.next(ids),
                caseId,
                type,
                contentType,
                checksumOf(content.value()),
                content.length(),
                Instant.now(clock));
    }

    /** Reconstitutes from storage. The row was already valid. */
    public static KycDocument rehydrate(
            DocumentId id,
            KycCaseId caseId,
            DocumentType type,
            DocumentContentType contentType,
            byte[] checksum,
            int contentLength,
            Instant uploadedAt) {
        return new KycDocument(
                id, caseId, type, contentType, checksum.clone(), contentLength, uploadedAt);
    }

    /** SHA-256 of the given bytes — one definition, used at capture and re-used at read-verify. */
    static byte[] checksumOf(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every JVM");
        }
    }

    public DocumentId id() {
        return id;
    }

    public KycCaseId caseId() {
        return caseId;
    }

    public DocumentType type() {
        return type;
    }

    public DocumentContentType contentType() {
        return contentType;
    }

    public byte[] checksum() {
        return checksum.clone();
    }

    public int contentLength() {
        return contentLength;
    }

    public Instant uploadedAt() {
        return uploadedAt;
    }

    /** Hex, because the checksum's job is to be compared and quoted in an investigation. */
    public String checksumHex() {
        return HexFormat.of().formatHex(checksum);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KycDocument document && id.equals(document.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Metadata only. There is no content to leak, by construction. */
    @Override
    public String toString() {
        return "KycDocument[" + id + ", case=" + caseId + ", " + type + ", " + contentType + "]";
    }
}
