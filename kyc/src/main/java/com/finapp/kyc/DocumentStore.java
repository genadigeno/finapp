package com.finapp.kyc;

import java.util.Objects;
import java.util.Optional;

/**
 * Stores and retrieves document content (`P2-TSK-008`, ADR-0036).
 *
 * <h2>This port is the object-storage seam</h2>
 *
 * <p>ADR-0036 keeps document bytes in PostgreSQL and defers object storage with a named trigger.
 * Documents are reached <em>only</em> through this port, so relocating the bytes later is an
 * adapter change plus a data migration, not a domain change. The port speaks plaintext; where the
 * bytes live and how they are encrypted at rest is the adapter's concern, which is what lets a
 * future object-store adapter reuse the same cipher.
 *
 * <h2>{@code readContent} is not the audited path — {@link DocumentAccess} is</h2>
 *
 * <p>The audit record belongs beside the read in one unit of work, and a store cannot write one:
 * it has no actor and no business saying why content moved. Production code reads content through
 * {@link DocumentAccess} and nothing else; this method existing separately is what the register
 * entry in {@code OwnershipIsScopedTest} classifies and what the audit mutation test holds.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface DocumentStore<T> {

    /**
     * Appends a captured document with its content, or converges on the identical one.
     *
     * <p><strong>Content-addressed convergence is the idempotency mechanism.</strong> A retry
     * after a lost response, a double-tap, and a deliberate re-upload of the same file are
     * indistinguishable intents, and one row is the right answer to all three: the unique key on
     * {@code (case_id, checksum_sha256)} arbitrates, and the loser is handed the existing
     * document rather than an error — {@code openOrConverge}'s semantics, one table over. No
     * {@code Idempotency-Key} header, because content addressing is stronger and this command
     * moves no money.
     *
     * <p>Different bytes are a different document and append freely.
     */
    Capture appendOrConverge(T unitOfWork, KycDocument document, DocumentBytes content);

    /**
     * The content of a document, decrypted and checksum-verified.
     *
     * <p>The stored SHA-256 is recomputed over the decrypted bytes on <strong>every</strong> read
     * (ADR-0036: "verified on read"), so silent corruption is a detected failure rather than a
     * mystery — and so the claim "these are the bytes received" is re-proven at the moment
     * somebody relies on it, not assumed since capture.
     *
     * @throws KycStorageException if the stored checksum does not match the decrypted bytes
     * @throws IllegalStateException if the ciphertext was tampered with or written under a
     *     different key — refused by the cipher, never returned
     */
    Optional<DocumentContent> readContent(T unitOfWork, DocumentId id);

    /** The outcome of an append: the document that now exists, and whether this call created it. */
    record Capture(KycDocument document, boolean created) {
        public Capture {
            Objects.requireNonNull(document, "document must not be null");
        }
    }

    /** Content with its metadata. A per-call value, never retained. */
    record DocumentContent(KycDocument document, byte[] content) {
        public DocumentContent {
            Objects.requireNonNull(document, "document must not be null");
            Objects.requireNonNull(content, "content must not be null");
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        /** Metadata only. Rendering document bytes is a disclosure ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "DocumentContent[" + document.id() + "]";
        }
    }
}
