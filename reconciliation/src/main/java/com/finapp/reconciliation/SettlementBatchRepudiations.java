package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The repudiation seam into settlement (`P8-TSK-023`, ADR-0065 §10, ADR-0064 §6): the one
 * place reconciliation reads a settlement batch's state and moves it {@code ACCEPTED →
 * REPUDIATED}. Declared here and implemented by {@code app} over settlement's own
 * {@code BatchRepudiation} — there is no build edge between the two modules. Every method joins
 * the caller's connection: the batch's transition, the reconciliation rows and the ledger's
 * reversal commit together or not at all.
 */
public interface SettlementBatchRepudiations {

    /** The batch as a repudiation sees it: identifiers and state, never an amount. */
    record Batch(
            UUID batchId,
            UUID sourceId,
            boolean accepted,
            Optional<UUID> recognitionEntryId,
            String currency) {

        public Batch {
            Objects.requireNonNull(batchId, "batchId must not be null");
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(recognitionEntryId, "recognitionEntryId must not be null");
            Objects.requireNonNull(currency, "currency must not be null");
        }
    }

    /** The batch, unlocked; empty when settlement holds no batch with this id. */
    Optional<Batch> read(Connection unitOfWork, UUID batchId);

    /**
     * For a bank STATEMENT batch: takes settlement's source row lock - the lock that serialises
     * every acceptance of the account (`INV-SET-06`) - and reads, under it, the ACCEPTED statement
     * of the same source and currency at the next sequence; empty, and no lock taken, for a
     * report batch (the Phase 8 -> 9 transition, SET-2). The repudiation's approval calls it
     * FIRST, before any advisory: an acceptance takes the source row before the namespace-4
     * advisory, and so does the approval - one order, no cycle. Holding the lock to commit, a
     * successor accepted concurrently is either seen here or sees this batch REPUDIATED (and
     * raises its own gap): never neither.
     */
    Optional<StatementChain.Link> lockChainAndReadSuccessor(Connection unitOfWork, UUID batchId);

    /**
     * The conditional {@code ACCEPTED → REPUDIATED} with its history row — settlement's
     * trigger beneath it for every writer. False when the batch was not {@code ACCEPTED}:
     * nothing written, and the caller's transaction must not commit its own rows.
     */
    boolean markRepudiated(
            Connection unitOfWork,
            UUID batchId,
            UUID resolutionId,
            Actor actor,
            Instant at,
            CorrelationId correlation);

    /** After the postings: {@code settlement.SettlementBatchRepudiated} and its audit record. */
    void announce(
            Connection unitOfWork,
            UUID batchId,
            UUID resolutionId,
            Optional<UUID> reversalEntryId,
            Actor actor,
            Instant at,
            CorrelationId correlation);
}
