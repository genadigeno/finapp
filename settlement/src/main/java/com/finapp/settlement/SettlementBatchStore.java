package com.finapp.settlement;

import com.finapp.platform.security.Actor;
import com.finapp.settlement.format.ParsedBatch;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for canonical settlement batches (`P8-TSK-008`, `V003`) — one whole batch per
 * call, because a batch that could be written in pieces could be read in pieces
 * ({@code INV-SET-07}).
 *
 * @param <T> the unit of work — the parse leg's one transaction per file
 */
public interface SettlementBatchStore<T> {

    /** A batch row as readers see it — identity, verdict and the trailer's declared words. */
    record BatchRow(
            UUID id,
            UUID fileId,
            UUID sourceId,
            String sourceCode,
            String externalBatchRef,
            CurrencyCode currency,
            BatchStatus status,
            LocalDate businessDate,
            SettlementFormatId formatId,
            int formatVersion,
            int lineCount,
            int declaredLineCount,
            long netMinor,
            int netScale,
            String remittanceReference,
            Instant createdAt,
            CorrelationId correlation) {}

    /** One control total: the `Money` fold per (type, direction), persisted for the attester. */
    record TotalRow(
            SettlementLineType lineType,
            LineDirection direction,
            long lineCount,
            long amountMinor,
            int amountScale) {}

    /** Everything one parse commits: the batch, its canonical lines and its folded totals. */
    record NewBatch(
            UUID batchId,
            UUID fileId,
            UUID sourceId,
            SettlementFormatId formatId,
            int formatVersion,
            ParsedBatch parsed,
            List<TotalRow> totals,
            Actor actor,
            Instant at,
            CorrelationId correlation) {

        public NewBatch {
            Objects.requireNonNull(batchId, "batchId must not be null");
            Objects.requireNonNull(fileId, "fileId must not be null");
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(formatId, "formatId must not be null");
            Objects.requireNonNull(parsed, "parsed must not be null");
            Objects.requireNonNull(totals, "totals must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(at, "at must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            totals = List.copyOf(totals);
        }
    }

    /**
     * Whether a live batch — one not {@code REJECTED} or {@code REPUDIATED} — already
     * declares this (source, external reference, currency). The partial unique behind it is
     * the arbiter under a race; this read is what lets the loser answer
     * {@code CONFLICTING_BATCH} instead of an exception.
     */
    boolean liveBatchStands(
            T unitOfWork, UUID sourceId, String externalBatchRef, CurrencyCode currency);

    /**
     * Writes the batch whole: row, lines, typed references, totals and the birth event, or
     * throws — {@link LiveBatchConflict} when the live unique refuses it under a race the
     * pre-check could not see.
     */
    void insertParsedBatch(T unitOfWork, NewBatch batch);

    /** The batch a file produced, if any. */
    Optional<BatchRow> batchByFileId(T unitOfWork, UUID fileId);

    /** The batch, or empty — unknown and malformed ids are one answer. */
    Optional<BatchRow> batchById(T unitOfWork, UUID batchId);

    /** The persisted control totals, by type then direction — the attester's reading. */
    List<TotalRow> totalsOf(T unitOfWork, UUID batchId);

    /**
     * The conditional {@code PARSED → REJECTED}, with its history row — the decline's half;
     * false when the batch already moved.
     */
    boolean markBatchRejected(
            T unitOfWork,
            UUID batchId,
            Actor actor,
            Optional<String> reason,
            Instant at,
            CorrelationId correlation);

    // ----------------------------------------------------------- the accept leg (P8-TSK-009)

    /** One canonical line read back whole, references included — the intake's material. */
    record LineRow(
            UUID id,
            int lineNo,
            SettlementLineType lineType,
            LineDirection direction,
            long amountMinor,
            CurrencyCode currency,
            int scale,
            LocalDate businessDate,
            Optional<LocalDate> settlementDate,
            Optional<LocalDate> valueDate,
            byte[] canonicalFingerprint,
            java.util.Map<LineReferenceKind, String> references) {

        public LineRow {
            canonicalFingerprint = canonicalFingerprint.clone();
            references = java.util.Map.copyOf(references);
        }

        @Override
        public byte[] canonicalFingerprint() {
            return canonicalFingerprint.clone();
        }
    }

    /** Every canonical line of {@code batchId}, by line number, references joined. */
    List<LineRow> linesOf(T unitOfWork, UUID batchId);

    /**
     * The conditional {@code PARSED → ACCEPTED} with the four acceptance facts in ONE
     * statement — the once-only trigger and the honesty {@code CHECK}s admit no other shape;
     * false when the batch already moved (another instance accepted or a decline won).
     */
    boolean markAccepted(
            T unitOfWork,
            UUID batchId,
            long sourceSequence,
            LocalDate acceptedOn,
            Optional<UUID> journalEntryId,
            Instant at);

    /** Appends one batch-history row — the acceptance's own edge record. */
    void appendBatchEvent(
            T unitOfWork,
            UUID batchId,
            BatchStatus from,
            BatchStatus to,
            Actor actor,
            Optional<String> reason,
            Instant occurredAt,
            CorrelationId correlation);

    /**
     * Every ACCEPTED batch's recognition entry id (`P8-TSK-009`) — the completeness
     * verifier's second known-entry class: a recognition entry's every line is explained by
     * the acceptance that posted it (ADR-0067 §9).
     */
    List<UUID> acceptedRecognitionEntries(T unitOfWork);

    /**
     * One ACCEPTED batch as the opening-position backfill re-derives its {@code REMITTANCE}
     * (ADR-0067 §8): the row keeps the validated net, both dates and the counterparty's
     * remittance reference, so the register can be emptied and rebuilt from the books alone
     * even after settlement evidence starts opening expectations of its own.
     */
    record AcceptedRow(
            UUID id,
            UUID sourceId,
            String sourceCode,
            CurrencyCode currency,
            long netMinor,
            int netScale,
            String remittanceReference,
            LocalDate businessDate,
            LocalDate acceptedOn) {}

    /** ACCEPTED batches by id, after {@code after} — the backfill's bounded page. */
    List<AcceptedRow> pageAccepted(T unitOfWork, UUID after, int limit);

    /**
     * The batch's recognition entry (`P8-TSK-014`, reconciliation's trace) — the stored
     * {@code journal_entry_id} its acceptance posted; empty when the batch is not accepted
     * or its posting was honestly omitted. Lock-free.
     */
    Optional<UUID> recognitionEntryOf(T unitOfWork, UUID batchId);

    /** The batch a canonical line belongs to (`P8-TSK-014`) — its stored {@code batch_id}. */
    Optional<UUID> batchOfLine(T unitOfWork, UUID lineId);

    /** The live unique refused an insert: another batch claimed the identity first. */
    final class LiveBatchConflict extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public LiveBatchConflict(UUID sourceId, String externalBatchRef) {
            super(
                    "a live settlement batch already declares this identity for source "
                            + sourceId
                            + " (reference "
                            + externalBatchRef
                            + "): the second declaration is CONFLICTING_BATCH");
        }
    }
}
