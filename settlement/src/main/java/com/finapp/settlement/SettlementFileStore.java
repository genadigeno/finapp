package com.finapp.settlement;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for settlement evidence (`P8-TSK-002`, ADR-0066 §6) — the port behind which the
 * bytes rest encrypted, and the seam a later move to object storage changes an adapter behind,
 * never a caller.
 *
 * <p>The adapter owns the cryptography: callers hand plaintext in and get verified plaintext
 * back, and what is at rest — AES-256-GCM chunks whose associated data binds
 * {@code file_id ‖ source_id ‖ content_sha256 ‖ seq}, the whole-plaintext SHA-256 checked on
 * every read — is the adapter's contract with `V002`, held by its own tests.
 *
 * @param <T> the unit of work every write joins — the reception's one transaction
 */
public interface SettlementFileStore<T> {

    /** A source row: compiled identity confirmed, plus the operational state only it holds. */
    record SourceRow(UUID id, String code, SourceKind kind, boolean active) {}

    /** The seeded identity a code resolves to, or empty — an unknown source stores nothing. */
    Optional<SourceRow> sourceByCode(T unitOfWork, String code);

    /**
     * Stores {@code file} with its encrypted chunks — or converges on the file already holding
     * this content address, writing nothing ({@code UNIQUE (source_id, content_sha256) WHERE
     * readmits_file_id IS NULL} is the arbiter; a concurrent winner is read after its commit).
     */
    Stored insert(T unitOfWork, SettlementFile file, byte[] content);

    /** The insert's verdict: this row landed, or the standing row it converged on. */
    sealed interface Stored {
        record New(UUID fileId) implements Stored {}

        record Duplicate(UUID existingFileId) implements Stored {}
    }

    /** Appends a receipt — every delivery leaves one, `NEW` or `DUPLICATE`. */
    void appendReceipt(
            T unitOfWork,
            UUID receiptId,
            UUID fileId,
            ReceiptOutcome outcome,
            DeliveryChannel channel,
            Actor deliveredBy,
            Instant receivedAt,
            CorrelationId correlation);

    enum ReceiptOutcome {
        NEW,
        DUPLICATE
    }

    /** Appends the birth event: {@code → RECEIVED}, with actor, time and correlation. */
    void appendBirthEvent(
            T unitOfWork, UUID fileId, Actor actor, Instant occurredAt, CorrelationId correlation);

    /** Records a refused delivery — metadata only, the row's own `CHECK`s the second rank. */
    void recordRefusal(T unitOfWork, RefusedDelivery refusal);

    /**
     * The file's bytes, decrypted and verified: every chunk's associated data must match this
     * file, source, content and position, and the whole plaintext must hash to the stored
     * address — otherwise nothing is served ({@code INV-HIST-02}).
     *
     * @throws SettlementStorageException on a tampered, transplanted or truncated chunk — one
     *     loud refusal; the audited read that reports it to a person is the door's
     *     (`P8-TSK-003`)
     */
    byte[] readContent(T unitOfWork, UUID fileId);
}
