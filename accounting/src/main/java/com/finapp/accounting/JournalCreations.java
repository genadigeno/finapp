package com.finapp.accounting;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * When the journal's entries dated in a span were created (`P16-REM-003`) - a port {@code app} implements over the ledger's journal,
 * because {@code accounting} has no edge to {@code ledger}. An opening checkpoint records the latest of them, so a PROVISIONAL read
 * cut at its claim takes a checkpoint only when every entry it folds is one the cut keeps: {@code created_at} is stamped by the posting
 * instance's clock, so the lock's own instant cannot say which entries a cut keeps.
 */
@FunctionalInterface
public interface JournalCreations {

    /**
     * The latest creation instant of an entry dated after {@code after} - from inception when empty - and on or before
     * {@code through}; empty when no entry is dated in that span. One {@code SELECT}, locking nothing.
     */
    Optional<Instant> latestCreated(Connection unitOfWork, Optional<LocalDate> after, LocalDate through);
}
