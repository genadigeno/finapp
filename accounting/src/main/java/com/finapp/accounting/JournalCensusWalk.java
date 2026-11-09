package com.finapp.accounting;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The journal as the source-join census walks it (`P15-TSK-035`) - a port {@code app} implements over the ledger's own tables, beside
 * {@link JournalShapes}, because {@code accounting} has no ledger edge: entries after a position, in id order, each with its door,
 * namespace, key suffix, reference and lines. UUIDv7 ids sort by minting millisecond (ADR-0013), so a keyset over them appends; the
 * ledger mints an entry's id inside its transaction, so an id cannot predate the transaction that wrote it by more than the
 * instances' clock skew.
 *
 * <p><strong>A position is not commit order.</strong> {@link #page} reads only entries minted before {@code mintedBefore}, which
 * {@code SourceCensus} derives from the oldest open transaction: every transaction that could still commit an earlier-minted entry
 * has started by then and is excluded by the fence. {@code SELECT} only.
 */
public interface JournalCensusWalk {

    /** The largest page. */
    int MAX_LIMIT = 500;

    /**
     * Entries with id greater than {@code after}, minted before {@code mintedBefore}, at most {@code limit}, ascending by id, each
     * with all its lines.
     */
    List<CensusEntry> page(Connection connection, UUID after, Instant mintedBefore, int limit);

    /** The minting instant of the first entry after {@code position}, or empty when the journal holds none: what the lag gauge reads. */
    Optional<Instant> oldestAfter(Connection connection, UUID position);

    /**
     * One journal entry as the census judges it.
     *
     * @param door the ledger door that wrote it
     * @param namespace for a posting or reversal, its command key's namespace; for an adjustment its proposal's origin - empty when
     *     the ledger names none
     * @param suffix the command key past its namespace; for an adjustment the proposal id
     * @param reference the entry's {@code reference} column
     */
    record CensusEntry(UUID id, EntryType door, Optional<String> namespace, String suffix, String reference, List<JournalShapes.ShapeLine> lines) {

        public CensusEntry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(door, "door");
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(suffix, "suffix");
            Objects.requireNonNull(reference, "reference");
            lines = List.copyOf(lines);
        }
    }
}
