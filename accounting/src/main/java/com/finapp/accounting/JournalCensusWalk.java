package com.finapp.accounting;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The journal as the source-join census walks it (`P15-TSK-035`) - a port {@code app} implements over the ledger's own tables, beside
 * {@link JournalShapes}, because {@code accounting} has no ledger edge: entries after a position, in commit-horizon order, each with its
 * door, namespace, key suffix, reference and lines.
 *
 * <p><strong>The position is (txid, id), not the id.</strong> An entry's id is a UUIDv7 minted on an instance's clock, and a holder of
 * the application's credential can insert any id at all, so the id says nothing about commit order: a cursor over ids can be passed by
 * an entry minted behind it, and that entry is then never read. {@code txid} is stamped by ledger {@code V034} from the inserting
 * transaction, for every writer. {@link #page} reads only {@code txid < pg_snapshot_xmin(pg_current_snapshot())}, every one of which is
 * finished: nothing can later commit behind the position, and no clock or margin enters the argument. {@code SELECT} only.
 */
public interface JournalCensusWalk {

    /** The largest page. */
    int MAX_LIMIT = 500;

    /** A place in the walk: the inserting transaction, then the entry. Ordered by the database, in that order. */
    record Position(long txid, UUID id) {

        public Position {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * Entries after {@code after} whose inserting transaction is finished, at most {@code limit}, in {@code (txid, id)} order, each with
     * all its lines. The bound is read in the page's own statement, so its snapshot is the snapshot the page reads.
     */
    List<CensusEntry> page(Connection connection, Position after, int limit);

    /** The minting instant of the first entry after {@code position}, or empty when none waits: what the lag gauge reads. */
    Optional<Instant> oldestAfter(Connection connection, Position position);

    /**
     * One journal entry as the census judges it.
     *
     * @param position where it stands in the walk
     * @param door the ledger door that wrote it
     * @param namespace for a posting or reversal, its command key's namespace; for an adjustment its proposal's origin - empty when
     *     the ledger names none
     * @param suffix the command key past its namespace; for an adjustment the proposal id
     * @param reference the entry's {@code reference} column
     */
    record CensusEntry(Position position, EntryType door, Optional<String> namespace, String suffix, String reference,
            List<JournalShapes.ShapeLine> lines) {

        public CensusEntry {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(door, "door");
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(suffix, "suffix");
            Objects.requireNonNull(reference, "reference");
            lines = List.copyOf(lines);
        }

        /** The entry's id. */
        public UUID id() {
            return position.id();
        }
    }
}
