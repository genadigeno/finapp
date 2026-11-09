package com.finapp.accounting;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The census's own records (`P15-TSK-035`; accounting {@code V015}): the head row - the walk's position and its health - and the
 * findings. The census writes nothing else.
 */
public interface SourceCensusStore {

    /**
     * The walk's head.
     *
     * @param aheadOfTheDatabase the head's transaction is at or past the database's oldest running one, which cannot arise in normal
     *     running (a position is only ever taken below that bound): the transaction counter has gone backwards, as a logical restore does
     */
    record Head(JournalCensusWalk.Position position, long entriesJudged, boolean aheadOfTheDatabase) {
        public Head {
            Objects.requireNonNull(position, "position");
        }
    }

    /** One finding: an entry the census counted. Never an amount: the kind, the door and namespace, and a bounded reason. */
    record Finding(UUID entryId, SourceFindingKind kind, EntryType door, String namespace, String evidence) {
        public Finding {
            Objects.requireNonNull(entryId, "entryId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(door, "door");
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    /**
     * What the gauges read.
     *
     * @param standing findings within the page's window, per kind
     * @param recent findings within the ticket's longer window, however many of them have left the page's
     * @param healthy whether the census's last run read everything and is recent
     * @param position where the head stands
     * @param asOf the database's clock at the read
     * @param advancedAt when the head last moved, on the database's clock
     */
    record Reading(Map<SourceFindingKind, Long> standing, long recent, boolean healthy, Optional<JournalCensusWalk.Position> position,
            Instant asOf, Instant advancedAt) {
        public Reading {
            standing = Map.copyOf(standing);
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(advancedAt, "advancedAt");
        }
    }

    /** The head, locked for this transaction, or empty when another walker holds it (one walker at a time across every instance). */
    Optional<Head> claimHead(Connection connection);

    /** Appends findings; a re-judged entry converges on its first row. */
    void insertFindings(Connection connection, List<Finding> findings);

    /** Moves the head to {@code position} and stamps success. */
    void advance(Connection connection, JournalCensusWalk.Position position, long entriesJudged);

    /** Stamps success with nothing to advance. */
    void touch(Connection connection);

    /** Stamps a failure by class, in its own transaction, without blocking if another walker holds the head. */
    void markFailure(Connection connection, String failureClass);

    /** The gauges' reading of the records, on a read-only snapshot. */
    Reading read(Connection connection, Duration standing, Duration recent, Duration staleAfter);
}
