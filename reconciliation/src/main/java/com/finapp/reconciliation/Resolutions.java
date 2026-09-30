package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes resolutions (`P8-TSK-012`, ADR-0071). This task produces only the platform's
 * {@code EVIDENCED} kind — born {@code APPROVED}, no person deciding, written in the
 * transaction whose zero-residual allocation or offset explained the break
 * ({@code INV-REC-02} as amended). The person kinds, their proposal machine and their
 * doors are `P8-TSK-015`'s.
 */
public interface Resolutions {

    /**
     * One evidence-closure's facts. {@code explained} is the value the correction
     * explained (frozen as the resolution's amount); {@code journalEntryId} is the
     * offset's unpark entry, absent for a pure top-up allocation.
     *
     * <p>The evidence is named exactly once: the match decision that explained the break, or —
     * for a statement gap the missing statement filled (`P8-TSK-016`, {@code INV-SET-06}) — the
     * filling statement's batch, since no decision is made there (the database column
     * {@code decision_id} is nullable for exactly this).
     */
    record Evidence(
            UUID resolutionId,
            UUID breakId,
            Money explained,
            long residualVersion,
            Optional<UUID> decisionId,
            Optional<UUID> fillingStatementId,
            Optional<UUID> parkId,
            Optional<UUID> offsetItemId,
            Optional<UUID> journalEntryId,
            UUID ruleSetId,
            Actor actor,
            Instant at,
            CorrelationId correlation) {

        public Evidence {
            Objects.requireNonNull(resolutionId, "resolutionId must not be null");
            Objects.requireNonNull(breakId, "breakId must not be null");
            Objects.requireNonNull(explained, "explained must not be null");
            Objects.requireNonNull(decisionId, "decisionId must not be null");
            Objects.requireNonNull(fillingStatementId, "fillingStatementId must not be null");
            if (decisionId.isPresent() == fillingStatementId.isPresent()) {
                throw new IllegalArgumentException(
                        "an evidence closure names its decision or its filling statement,"
                                + " exactly one");
            }
            Objects.requireNonNull(parkId, "parkId must not be null");
            Objects.requireNonNull(offsetItemId, "offsetItemId must not be null");
            Objects.requireNonNull(journalEntryId, "journalEntryId must not be null");
            Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(at, "at must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            if (explained.minorUnits() < 0) {
                throw new IllegalArgumentException(
                        "the explained value is an absolute fact of the closure");
            }
        }

        /** A matcher's closure — its decision explained the break (`P8-TSK-012`'s shape). */
        public Evidence(
                UUID resolutionId,
                UUID breakId,
                Money explained,
                long residualVersion,
                UUID decisionId,
                Optional<UUID> parkId,
                Optional<UUID> offsetItemId,
                Optional<UUID> journalEntryId,
                UUID ruleSetId,
                Actor actor,
                Instant at,
                CorrelationId correlation) {
            this(resolutionId, breakId, explained, residualVersion,
                    Optional.of(Objects.requireNonNull(decisionId, "decisionId must not be null")),
                    Optional.empty(), parkId, offsetItemId, journalEntryId, ruleSetId, actor, at,
                    correlation);
        }
    }

    /**
     * Closes the break {@code EVIDENCED}: the conditional {@code → RESOLVED} first (the
     * lock order's break-before-resolution), then the resolution born {@code APPROVED},
     * its history, the acting-only audit record and {@code reconciliation.BreakResolved}.
     * Returns false — recording NOTHING — when the break was already {@code RESOLVED}:
     * the raise-loser precedent, for any racing or replayed writer.
     */
    boolean evidence(Connection unitOfWork, Evidence evidence);
}
