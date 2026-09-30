package com.finapp.reconciliation;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The trace's steps beyond this module (`P8-TSK-014`): settlement's batch and file behind a
 * run or a line, the batch's recognition entry, and the provider statements payments retained
 * about an operation. Declared here, implemented in {@code app} over settlement's and
 * payments' public read stores (ADR-0064); read-only and lock-free, identifiers and metadata
 * only — never a byte of content. Every answer follows a stored reference.
 */
public interface TraceEvidence {

    /** An accepted or parsed batch's stored facts: its file, and its recognition entry if one posted. */
    record BatchFacts(UUID fileId, Optional<UUID> recognitionEntryId) {}

    Optional<BatchFacts> batch(Connection unitOfWork, UUID batchId);

    /** The batch a canonical line belongs to. */
    Optional<UUID> batchOfLine(Connection unitOfWork, UUID lineId);

    /**
     * The provider evidence retained about the operation an expectation tracks — for a
     * Phase 7 parking through the evidence's fifth subject, {@code unmatched_confirmation_id}.
     * Empty for an operation whose statements payments does not retain (a merchant payout's
     * live in merchant's own evidence, reached through the payout's doors).
     */
    List<UUID> providerEvidence(Connection unitOfWork, ExpectationKind kind, String operationRef);
}
