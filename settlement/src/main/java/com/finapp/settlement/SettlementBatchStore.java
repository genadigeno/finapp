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
