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

    /** The walk's head: the position it has judged through, and how many entries it has judged. */
    record Head(UUID position, long entriesJudged) {
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

    /** What the gauges read: standing findings per kind, whether the census may be believed at all, where its head stands and the database's now. */
    record Reading(Map<SourceFindingKind, Long> standing, boolean healthy, Optional<UUID> position, Instant asOf) {
        public Reading {
            standing = Map.copyOf(standing);
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(asOf, "asOf");
        }
    }

    /**
     * The fence: the database's now, or the start of the oldest open transaction this role can see if that is earlier.
     * <strong>Read before the walk's own transaction starts</strong>, or a transaction that commits between the two is on neither side.
     */
    Instant fence(Connection connection);

    /** The head, locked for this transaction, or empty when another walker holds it (one walker at a time across every instance). */
    Optional<Head> claimHead(Connection connection);

    /** Appends findings; a re-judged entry converges on its first row. */
    void insertFindings(Connection connection, List<Finding> findings);

    /** Moves the head to {@code position} and stamps success. */
    void advance(Connection connection, UUID position, long entriesJudged);

    /** Stamps success with nothing to advance. */
    void touch(Connection connection);

    /** Stamps a failure by class, in its own transaction, without blocking if another walker holds the head. */
    void markFailure(Connection connection, String failureClass);

    /** The gauges' reading of the records, on a read-only snapshot. */
    Reading read(Connection connection, Duration standing, Duration staleAfter);
}
