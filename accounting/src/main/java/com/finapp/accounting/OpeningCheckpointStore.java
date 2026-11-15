package com.finapp.accounting;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * accounting's opening checkpoints (`P16-REM-003`; {@code accounting V017}): the reads' {@link #latest} and the writer's
 * {@link #claim}, {@link #pending} and {@link #store}.
 *
 * <p>A checkpoint is valid while its month's row is not {@code OPEN} and its {@code locked_at} is the one the checkpoint was folded
 * under: the posting cutoff has stood over the month since that lock committed, so no line dated in or before it has committed since
 * (ADR-0114). A move back - an unlock or a reopen - clears the row's {@code locked_at}, and a relock stamps another, so a voided
 * checkpoint is never read again and never needs touching.
 */
public interface OpeningCheckpointStore {

    /**
     * The latest valid checkpoint a read dated before {@code before} may start from: its month ends before that day, and, under a
     * {@code cut}, every entry it folds was created at or before the cut. Empty when none is - the read then folds from inception.
     * One {@code SELECT} per table, on the caller's snapshot.
     */
    Optional<OpeningCheckpoint> latest(Connection snapshot, LocalDate before, Optional<Instant> cut);

    /**
     * Claims the writing for this transaction - a try-lock, never a wait: {@code false} when another instance's transaction holds it,
     * and that instance is folding the month this one would.
     */
    boolean claim(Connection unitOfWork);

    /** The oldest month whose present lock has no checkpoint - locked or past it, never {@code OPEN}. */
    Optional<Pending> pending(Connection unitOfWork);

    /**
     * Stores {@code checkpoint} under {@code id} - only while the lock it was folded under still stands, read again by the insert: a
     * lock that has moved since gets nothing, and a second checkpoint of the same lock is the unique's no-op.
     *
     * @return whether this call stored it
     */
    boolean store(Connection unitOfWork, UUID id, OpeningCheckpoint checkpoint);

    /** A month whose present lock has no checkpoint: the period, its last day and the lock's instant. */
    record Pending(AccountingPeriodId period, LocalDate through, Instant lockedAt) {

        public Pending {
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(through, "through");
            Objects.requireNonNull(lockedAt, "lockedAt");
        }
    }
}
