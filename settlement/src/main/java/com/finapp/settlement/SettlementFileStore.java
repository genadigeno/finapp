package com.finapp.settlement;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
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

    /** Every seeded source, by code — the operator's register view (`P8-TSK-003`). */
    List<SourceRow> sources(T unitOfWork);

    /** A file row as the investigator sees it: metadata only, never a byte of content. */
    record FileRow(
            UUID id,
            String sourceCode,
            UUID sourceId,
            DeliveryChannel receivedVia,
            FileStatus status,
            Optional<LocalDate> businessDate,
            SettlementFormatId formatId,
            int formatVersion,
            byte[] contentSha256,
            int contentLength,
            int lineCount,
            Optional<String> receivedBy,
            Optional<Attestation> attestation,
            Instant receivedAt,
            CorrelationId correlation) {

        public FileRow {
            contentSha256 = contentSha256.clone();
        }

        @Override
        public byte[] contentSha256() {
            return contentSha256.clone();
        }
    }

    /** The second person's `NULL → value` fact (`INV-SET-07`). */
    record Attestation(String attestedBy, Instant attestedAt) {
        public Attestation {
            Objects.requireNonNull(attestedBy, "attestedBy must not be null");
            Objects.requireNonNull(attestedAt, "attestedAt must not be null");
        }
    }

    /** A refused delivery's metadata row, read back — the reason and position, never a value. */
    record RefusalRow(
            UUID id,
            String sourceCode,
            byte[] contentSha256,
            int contentLength,
            SettlementFormatId formatId,
            int formatVersion,
            RefusalReason reason,
            Optional<Integer> lineNo,
            Optional<String> fieldName,
            DeliveryChannel channel,
            String actor,
            Instant refusedAt,
            CorrelationId correlation) {

        public RefusalRow {
            contentSha256 = contentSha256.clone();
        }

        @Override
        public byte[] contentSha256() {
            return contentSha256.clone();
        }
    }

    /** One source's non-terminal backlog: how many files, and the oldest arrival. */
    record PendingReading(String sourceCode, long pending, Optional<Instant> oldestReceivedAt) {}

    /** The file, or empty — unknown and malformed ids are one answer that records nothing. */
    Optional<FileRow> fileById(T unitOfWork, UUID fileId);

    /**
     * The file, locked {@code FOR UPDATE} — the attestation's serialisation point
     * (ADR-0066 §2). The conditional write below is the arbiter even without it.
     */
    Optional<FileRow> lockFileById(T unitOfWork, UUID fileId);

    /**
     * The conditional {@code NULL → value}: sets {@code attested_by, attested_at} exactly
     * when no attestation stands, and reports whether THIS call recorded it — under ten
     * racing instances the database admits one, whatever happened to the row lock.
     */
    boolean recordAttestation(T unitOfWork, UUID fileId, String attestedBy, Instant attestedAt);

    /** The newest {@code limit} files across every source — the investigator's list. */
    List<FileRow> newestFiles(T unitOfWork, int limit);

    /** The newest {@code limit} refused deliveries — metadata rows, never a value. */
    List<RefusalRow> newestRefusals(T unitOfWork, int limit);

    /**
     * Every source's non-terminal backlog in one read — the file gauges' query
     * (`P8-TSK-003`): a source with no waiting file is simply absent.
     */
    List<PendingReading> pendingBySource(T unitOfWork);

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
