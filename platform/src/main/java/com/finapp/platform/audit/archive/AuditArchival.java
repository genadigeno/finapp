package com.finapp.platform.audit.archive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * One segment of the audit archive, from claim to mark (`P16-TSK-010`; {@code DATA_RETENTION.md} section 3; ADR-0135).
 *
 * <ol>
 *   <li><strong>Claim</strong> a segment: the CLAIMED one if its lease lapsed, or the next cut from the queue
 *       ({@link AuditArchiveSegments#claim}).
 *   <li><strong>Render</strong> it: its file and its manifest, the database's own text, so a segment taken over renders the same bytes.
 *   <li><strong>Write once</strong>: each object at its segment's key if none is there ({@link AuditArchiveStore#putIfAbsent}).
 *   <li><strong>Verify</strong>: read both back and compare them byte for byte with what was rendered. A missing object, a shorter
 *       or a longer one, or one byte different, and nothing is marked.
 *   <li><strong>Then mark</strong>, with the digests of what was read back, which the database recomputes from the rows.
 * </ol>
 *
 * <p><strong>Nothing is ever marked before the copy is read back.</strong> A store that is down, slow or refusing ends the attempt
 * as {@link Outcome#STORE_UNAVAILABLE}: the segment stays CLAIMED under its lease, and when the lease lapses any instance takes it
 * over and writes the same bytes or finds them there. A copy that reads back other than rendered is {@link Outcome#MISMATCH}: never
 * marked, and every later attempt finds the same object, so the archive stops there and pages (runbook section 11.50).
 *
 * <p><strong>At N instances.</strong> One claimer at a time and one segment in flight, by the database; a slow holder whose lease was
 * taken over is refused at the mark ({@link Outcome#FENCED}), and the instance that took it marks it once.
 */
public final class AuditArchival {

    /** What one attempt came to. */
    public enum Outcome {
        /** Nothing claimed: no row waits, another instance is claiming, or a live lease holds the segment in flight. */
        IDLE,
        /** The segment's copy was read back equal and the segment is marked ARCHIVED. */
        ARCHIVED,
        /** The store did not answer, answered late, or refused: nothing marked, the segment left to its lease. */
        STORE_UNAVAILABLE,
        /** The copy read back is missing or differs from the segment's rows: never marked. */
        MISMATCH,
        /** Another instance took the segment over while this one copied it: this one's mark was refused, and that one marks. */
        FENCED,
        /** The database refused the copy's digests as not the rows': never marked. */
        REFUSED
    }

    /** One attempt: its outcome, the segment it held (zero when none) and the rows that segment holds (zero unless archived). */
    public record Result(Outcome outcome, long segment, int rows) {

        static Result idle() {
            return new Result(Outcome.IDLE, 0, 0);
        }
    }

    /** The largest batch the claim accepts: a segment's objects stay a few megabytes at most. */
    public static final int MAX_BATCH_SIZE = 10_000;

    private final AuditArchiveSegments segments;
    private final AuditArchiveStore store;
    private final Duration lease;
    private final int batchSize;

    /**
     * @param lease how long a claim holds its segment before another instance may take it over; longer than the store's calls
     * @param batchSize the most rows a new segment takes from the queue
     */
    public AuditArchival(AuditArchiveSegments segments, AuditArchiveStore store, Duration lease, int batchSize) {
        this.segments = Objects.requireNonNull(segments, "segments");
        this.store = Objects.requireNonNull(store, "store");
        this.lease = Objects.requireNonNull(lease, "lease");
        if (lease.compareTo(Duration.ofSeconds(1)) < 0 || lease.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("the lease must be 1 s to 1 h, as the claim accepts: " + lease);
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be 1.." + MAX_BATCH_SIZE + " but was " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /** The key of a segment's file: deterministic, so a segment taken over writes where its first holder did. */
    public static String contentKey(long segment) {
        return "audit/v1/segments/%012d.ndjson".formatted(segment);
    }

    /** The key of a segment's manifest. */
    public static String manifestKey(long segment) {
        return "audit/v1/manifests/%012d.json".formatted(segment);
    }

    /**
     * Archives one segment, or finds nothing to do.
     *
     * @throws AuditArchiveDatabaseException if the database failed in a way none of the outcomes names
     */
    public Result archiveOne() {
        Optional<AuditArchiveSegments.Claim> claimed = segments.claim(lease, batchSize);
        if (claimed.isEmpty()) {
            return Result.idle();
        }
        AuditArchiveSegments.Claim claim = claimed.get();
        long segment = claim.segment();
        byte[] content = segments.content(segment).getBytes(StandardCharsets.UTF_8);
        byte[] manifest = segments.manifest(segment).getBytes(StandardCharsets.UTF_8);
        int rows = lines(content);

        Optional<byte[]> contentBack;
        Optional<byte[]> manifestBack;
        try {
            store.putIfAbsent(contentKey(segment), content);
            store.putIfAbsent(manifestKey(segment), manifest);
            contentBack = store.get(contentKey(segment), content.length);
            manifestBack = store.get(manifestKey(segment), manifest.length);
        } catch (AuditArchiveStoreException unavailable) {
            return new Result(Outcome.STORE_UNAVAILABLE, segment, 0);
        }
        if (!readsBackAs(contentBack, content) || !readsBackAs(manifestBack, manifest)) {
            return new Result(Outcome.MISMATCH, segment, 0);
        }

        try {
            segments.complete(claim, sha256(contentBack.get()), sha256(manifestBack.get()));
        } catch (AuditArchiveDatabaseException refused) {
            if (refused.heldElsewhere()) {
                return new Result(Outcome.FENCED, segment, 0);
            }
            if (refused.digestRefused()) {
                return new Result(Outcome.REFUSED, segment, 0);
            }
            throw refused;
        }
        return new Result(Outcome.ARCHIVED, segment, rows);
    }

    private static boolean readsBackAs(Optional<byte[]> stored, byte[] expected) {
        return stored.isPresent() && Arrays.equals(stored.get(), expected);
    }

    private static int lines(byte[] content) {
        int lines = 0;
        for (byte b : content) {
            if (b == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /** Lower-case hex SHA-256, as the database's {@code encode(sha256(...), 'hex')} writes it. */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("every JDK provides SHA-256", absent);
        }
    }

    public Duration lease() {
        return lease;
    }

    public int batchSize() {
        return batchSize;
    }
}
