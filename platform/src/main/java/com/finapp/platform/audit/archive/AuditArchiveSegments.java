package com.finapp.platform.audit.archive;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The database's half of the audit archive (`P16-TSK-010`, platform {@code V015}): the claim, the segment's text, its manifest and
 * the mark. Every rule that decides anything is in the migration's functions; this is how they are called. Each call is a transaction
 * of its own on a connection of its own, returned before the call returns, so nothing is held across the store's network calls.
 */
public interface AuditArchiveSegments {

    /** A segment this instance now holds: its number and the holder token every later step names. */
    record Claim(long segment, UUID holder) {

        public Claim {
            if (segment < 1) {
                throw new IllegalArgumentException("a segment is numbered from 1: " + segment);
            }
            Objects.requireNonNull(holder, "holder");
        }
    }

    /**
     * How far the archive is behind: the queued rows, counted up to {@code cap} (a count past it reads as {@code cap}), and how
     * long the oldest has waited, on the database's clock; zero when none waits.
     */
    record Backlog(long pending, Duration oldest) {}

    /**
     * The CLAIMED segment if its lease has lapsed, taken over; else the next segment cut from the queue; empty when there is nothing
     * to do, another instance is claiming, or a live lease holds the one segment in flight.
     *
     * @throws AuditArchiveDatabaseException if the claim failed, a column not reviewed or a queued row missing among the reasons
     */
    Optional<Claim> claim(Duration lease, int batchSize);

    /** The segment's file, as the database renders format 1: the text whose bytes are archived. */
    String content(long segment);

    /** The segment's manifest, chained to its predecessor's digest. */
    String manifest(long segment);

    /**
     * Marks the segment ARCHIVED with the digests of the copy read back.
     *
     * @throws AuditArchiveDatabaseException with {@link AuditArchiveDatabaseException#heldElsewhere()} when this claim no longer
     *     holds it, or {@link AuditArchiveDatabaseException#digestRefused()} when the digests are not the rows'
     */
    void complete(Claim claim, String contentSha256, String manifestSha256);

    /** The queue's depth, counted up to {@code cap}, and its oldest entry's age. */
    Backlog backlog(int cap);
}
