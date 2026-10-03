package com.finapp.settlement;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Settlement's half of a batch repudiation (`P8-TSK-023`, ADR-0065 §10,
 * `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` §5.2): the batch's {@code ACCEPTED →
 * REPUDIATED} edge, its history row, its published event and its audit record — and nothing
 * else. The judgement is not made here: an approved four-eyes {@code REPUDIATE_BATCH}
 * resolution is the reasoned act, and its approval transaction drives this class through the
 * seam by which {@code settlement} writes on the approval's connection.
 *
 * <p>Every method joins the caller's {@link Connection} and never opens, commits or rolls back
 * a transaction. The approval writes in ADR-0064 §6's order for the seam — reconciliation's
 * rows, then {@link #markRepudiated} here, then the ledger's reversal and unparks, then
 * {@link #announce} with the reversal entry those postings produced.
 *
 * <h2>What does not move</h2>
 *
 * <p>The FILE stays {@code ACCEPTED}, its bytes retained byte-identical ({@code INV-HIST-02}):
 * the evidence is what it was, only the batch's verdict on it changes. The batch keeps its
 * acceptance facts — sequence, acceptance date, recognition entry — as acceptance wrote them
 * ({@code INV-HIST-01}); `V010`'s trigger refuses any write that would move them. The live
 * uniques exclude {@code REPUDIATED}, so the genuine file is admitted as a new batch.
 *
 * <h2>Ten instances</h2>
 *
 * <p>No key: the machine is the record. The conditional {@code UPDATE ... WHERE status =
 * 'ACCEPTED'} locks the row as it writes, so ten approvals racing on one batch serialise on it
 * and exactly one sees {@code true}; the rest see the edge already taken and write nothing.
 * The transition trigger arbitrates for any writer this class never sees.
 */
@RequiredArgsConstructor
public final class BatchRepudiation {

    @NonNull private final SettlementBatchStore<Connection> batches;
    @NonNull private final SettlementFileStore<Connection> files;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;

    /** The batch as a repudiation sees it: identifiers and state only. */
    public record RepudiableBatch(
            UUID batchId,
            UUID fileId,
            UUID sourceId,
            BatchStatus status,
            Optional<UUID> recognitionEntryId,
            String currency,
            LocalDate businessDate) {}

    /**
     * The batch, or empty — no lock: its identity and acceptance facts are frozen, and its
     * status is decided by {@link #markRepudiated}'s conditional, never by this read. The
     * recognition entry is reported whatever the status.
     */
    public Optional<RepudiableBatch> read(Connection unitOfWork, UUID batchId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(batchId, "batchId must not be null");
        return batches.repudiable(unitOfWork, batchId)
                .map(
                        row ->
                                new RepudiableBatch(
                                        row.id(),
                                        row.fileId(),
                                        row.sourceId(),
                                        row.status(),
                                        row.journalEntryId(),
                                        row.currency().code(),
                                        row.businessDate()));
    }

    /**
     * For a bank statement: the source row locked {@code FOR UPDATE} - the lock that serialises
     * every acceptance of the source, the accept leg's own ({@code BatchAcceptance}) - and, read
     * under it, the ACCEPTED statement of the same source and currency at the next sequence.
     * Empty, taking no lock, for a report (the Phase 8 -> 9 transition, SET-2: a repudiated
     * statement opens a hole before its successor, and only a read under this lock can see the
     * successor an acceptance is committing concurrently).
     */
    public Optional<SettlementBatchStore.StatementLink> lockSourceAndReadSuccessor(
            Connection unitOfWork, UUID batchId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(batchId, "batchId must not be null");
        Optional<SettlementBatchStore.BatchRow> batch = batches.batchById(unitOfWork, batchId);
        if (batch.isEmpty() || batch.get().statement().isEmpty()) {
            return Optional.empty();
        }
        files.sourceByIdForUpdate(unitOfWork, batch.get().sourceId())
                .orElseThrow(
                        () ->
                                new SettlementStorageException(
                                        "source " + batch.get().sourceId() + " is seeded (V002)"));
        return batches.acceptedStatement(
                unitOfWork,
                batch.get().sourceId(),
                batch.get().currency(),
                batch.get().statement().get().sequence() + 1);
    }

    /**
     * The conditional {@code ACCEPTED → REPUDIATED} (the row locked by the {@code UPDATE}
     * itself) and its {@code batch_event}, naming the resolution; false when the batch was not
     * {@code ACCEPTED}, and then nothing is written.
     */
    public boolean markRepudiated(
            Connection unitOfWork,
            UUID batchId,
            UUID resolutionId,
            Actor actor,
            Instant at,
            Correlation correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(batchId, "batchId must not be null");
        Objects.requireNonNull(resolutionId, "resolutionId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(at, "at must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        return batches.markRepudiated(
                unitOfWork,
                batchId,
                actor,
                // The history names what drove the edge - an identifier, never a judgement
                // restated: the reason lives on the resolution (INV-AUD-03).
                Optional.of("resolution=" + resolutionId),
                at,
                correlation.correlationId());
    }

    /**
     * After the caller's postings: {@code settlement.SettlementBatchRepudiated} and the acting
     * audit record (the actor is the approver). Refused unless the batch already stands
     * {@code REPUDIATED} in this unit of work — an announcement never precedes its edge.
     */
    public void announce(
            Connection unitOfWork,
            UUID batchId,
            UUID resolutionId,
            Optional<UUID> reversalEntryId,
            Actor actor,
            Instant at,
            Correlation correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(batchId, "batchId must not be null");
        Objects.requireNonNull(resolutionId, "resolutionId must not be null");
        Objects.requireNonNull(reversalEntryId, "reversalEntryId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(at, "at must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        SettlementBatchStore.RepudiableRow batch =
                batches.repudiable(unitOfWork, batchId)
                        .orElseThrow(
                                () ->
                                        new SettlementStorageException(
                                                "no settlement batch " + batchId
                                                        + " to announce as repudiated"));
        if (batch.status() != BatchStatus.REPUDIATED) {
            throw new SettlementStorageException(
                    "batch " + batchId + " is " + batch.status()
                            + ": a repudiation is announced only after its edge");
        }
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        at,
                        SettlementAuditAction.SETTLEMENT_BATCH_REPUDIATED,
                        "settlement_batch",
                        batchId.toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers only - never an amount (INV-AUD-02).
                        Optional.of(
                                "source=" + batch.sourceId()
                                        + ", file=" + batch.fileId()
                                        + ", resolution=" + resolutionId
                                        + ", reversalEntry="
                                        + reversalEntryId.map(UUID::toString).orElse("none"))));
        SettlementFileEvents.repudiated(
                outbox,
                unitOfWork,
                ids,
                batchId,
                batch.fileId(),
                batch.sourceId(),
                resolutionId,
                reversalEntryId,
                at,
                correlation);
    }
}
