package com.finapp.settlement;

import com.finapp.platform.security.Actor;
import com.finapp.settlement.format.FormatDefect;
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

    // ------------------------------------------------------------ the parse leg (P8-TSK-008)

    /**
     * Candidate files for the parse leg: {@code RECEIVED}, due ({@code next_parse_at} unset
     * or passed), oldest first — ids only and NO lock, because the claim that matters is
     * {@link #lockDueById} inside each file's own transaction.
     */
    List<UUID> dueForParse(T unitOfWork, Instant now, int limit);

    /**
     * The per-file claim: the row, {@code FOR UPDATE SKIP LOCKED}, and only while it is still
     * {@code RECEIVED} and due — empty when another instance holds or already handled it.
     * The conditional transition behind it is the arbiter even without the lock.
     */
    Optional<FileRow> lockDueById(T unitOfWork, UUID fileId, Instant now);

    /** The conditional {@code RECEIVED → PARSED}; false when the row already moved. */
    boolean markParsed(T unitOfWork, UUID fileId, Instant at);

    /**
     * The conditional edge into {@code REJECTED} from {@code from}, recording the verdict;
     * false when the row already moved. {@code detail} names codes, lines and fields — never
     * a value.
     */
    boolean markRejected(
            T unitOfWork,
            UUID fileId,
            FileStatus from,
            RejectionCode code,
            Optional<String> detail,
            Instant at);

    /**
     * Our own failure's tally ({@code ADR-0066} §9): {@code parse_failures + 1}, returning
     * the new count so the caller can back off — never a status move, never a rejection.
     */
    int bumpParseFailures(T unitOfWork, UUID fileId);

    /** Schedules the next attempt after a failure — the stuck-file gauge's visibility. */
    void scheduleNextParse(T unitOfWork, UUID fileId, Instant nextParseAt);

    /**
     * Appends one machine-history row. A {@code from == to} row records the platform's
     * processing of a file that did not move — the parse leg's failure note — which is the
     * file history's own kind of audit (`P8-TSK-008`'s ruling).
     */
    void appendFileEvent(
            T unitOfWork,
            UUID fileId,
            FileStatus from,
            FileStatus to,
            Actor actor,
            Optional<String> reason,
            Instant occurredAt,
            CorrelationId correlation);

    /**
     * The rejection's substantiation: up to 100 rows of (line, code, field) — the value
     * columns do not exist (`V003`).
     */
    void recordIngestionErrors(T unitOfWork, UUID fileId, List<FormatDefect> defects);

    // ----------------------------------------------------------- the accept leg (P8-TSK-009)

    /**
     * Candidate files for the accept leg: {@code PARSED} and eligible by channel — a pull
     * always; an upload only once a second person attested it; a readmission when its
     * original was pulled or attested, or once the readmission itself is (`P8-TSK-022`
     * widens the database's cross-row rule). Oldest first, ids only, NO lock — the claim
     * that matters is {@link #lockEligibleById} inside each file's own transaction.
     */
    List<UUID> dueForAccept(T unitOfWork, int limit);

    /**
     * The per-file claim: the row, {@code FOR UPDATE SKIP LOCKED}, only while still
     * {@code PARSED} and eligible — the upload-authentication predicate re-checked under
     * the lock (its other two ranks: the domain's read of this row, and `V002`'s
     * {@code CHECK}s).
     */
    Optional<FileRow> lockEligibleById(T unitOfWork, UUID fileId);

    /** The conditional {@code PARSED → ACCEPTED}; false when the row already moved. */
    boolean markFileAccepted(T unitOfWork, UUID fileId, Instant at);

    /** The source's operational state, locked — retirement is judged under the same lock. */
    Optional<SourceRow> sourceByIdForUpdate(T unitOfWork, UUID sourceId);

    /**
     * Claims the source's next statement sequence under the row lock taken by
     * {@link #sourceByIdForUpdate} and advances it: gapless because a rolled-back
     * acceptance releases its number with the transaction, and
     * {@code UNIQUE (source_id, source_sequence)} arbitrates any writer the lock misses.
     */
    long claimNextSequence(T unitOfWork, UUID sourceId);
}
