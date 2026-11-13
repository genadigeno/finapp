package com.finapp.platform.audit.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The audit archive on PostgreSQL, as the application role (`P16-TSK-010`). Written and <strong>owed</strong> to the database pass
 * (the owner's word of 2026-11-13; PI-023's kind): nothing in this class has run. A short container check ran the migration's
 * functions once on PostgreSQL 18.6 by hand (the claim, a live lease, a lapsed one taken over, both refusals at the mark, the chain,
 * the decoder and the grants), which is not this class.
 *
 * <p><strong>The database is shared</strong>, and the archive is one chain across it: every audit row any suite writes is queued
 * and may be cut into a segment here. So each case plants its own rows under a correlation id of its own and asserts about those
 * rows, and every segment this class cuts is completed before it ends, so the chain behind it is never left held.
 */
@Tag("database")
@DisplayName("the audit archive on PostgreSQL: each row once at ten archivers, a crash between copy and mark taken over (P16-TSK-010)")
class AuditArchivalDatabaseTest {

    private static final Duration LEASE = Duration.ofSeconds(5);

    @Test
    @DisplayName("ten archivers at once: each planted row in exactly one segment, none left queued, every copy equal to its rows")
    void tenArchiversArchiveEachRowOnce() throws Exception {
        String probe = plant(500);
        Store store = new Store();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<Integer>> archivers = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                archivers.add(pool.submit((Callable<Integer>) () -> {
                    start.await();
                    AuditArchival archival = new AuditArchival(new JdbcAuditArchiveSegments(DatabaseRoles::application), store, LEASE, 97);
                    int archived = 0;
                    for (int idle = 0; idle < 50; ) {
                        AuditArchival.Result result = archival.archiveOne();
                        if (result.outcome() == AuditArchival.Outcome.ARCHIVED) {
                            archived++;
                            idle = 0;
                        } else {
                            idle++;
                            Thread.sleep(20);
                        }
                        if (queued(probe) == 0 && claimed() == 0) {
                            break;
                        }
                    }
                    return archived;
                }));
            }
            start.countDown();
            for (Future<Integer> archiver : archivers) {
                archiver.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(queued(probe)).as("no planted row is left queued").isZero();
        assertThat(memberships(probe)).as("each planted row is in one segment, once (the member table's key)").isEqualTo(500);
        assertThat(segmentsHolding(probe)).as("the planted rows were archived, so the census below has something to read").isNotEmpty();
        for (long segment : segmentsHolding(probe)) {
            assertThat(state(segment)).isEqualTo("ARCHIVED");
            assertThat(AuditArchival.sha256(store.objects.get(AuditArchival.contentKey(segment))))
                    .as("segment %s's copy is its rows", segment).isEqualTo(digest(segment, "content_sha256"));
            assertThat(AuditArchival.sha256(store.objects.get(AuditArchival.manifestKey(segment))))
                    .isEqualTo(digest(segment, "manifest_sha256"));
        }
        assertThat(chainBreaks()).as("every archived segment's manifest names its predecessor's digest").isZero();
    }

    @Test
    @DisplayName("a holder that dies between the copy and the mark: its segment is taken over when the lease lapses, found, and marked once")
    void aCrashBetweenCopyAndMarkIsTakenOverOnce() throws Exception {
        plant(3);
        Store store = new Store();
        JdbcAuditArchiveSegments segments = new JdbcAuditArchiveSegments(DatabaseRoles::application);
        drain(segments, store);
        plant(3);
        AuditArchiveSegments.Claim dead = segments.claim(Duration.ofSeconds(1), 1000).orElseThrow();
        store.putIfAbsent(AuditArchival.contentKey(dead.segment()), segments.content(dead.segment()).getBytes(StandardCharsets.UTF_8));
        store.putIfAbsent(AuditArchival.manifestKey(dead.segment()), segments.manifest(dead.segment()).getBytes(StandardCharsets.UTF_8));
        Thread.sleep(1_500);

        AuditArchival.Result result = new AuditArchival(segments, store, LEASE, 1000).archiveOne();

        assertThat(result.outcome()).isEqualTo(AuditArchival.Outcome.ARCHIVED);
        assertThat(result.segment()).isEqualTo(dead.segment());
        assertThat(reclaims(dead.segment())).isEqualTo(1);
        assertThat(store.writes.get(AuditArchival.contentKey(dead.segment())).get()).as("the copy found, not written again").isEqualTo(1);
        assertThatExceptionOfType(AuditArchiveDatabaseException.class)
                .as("the dead holder's late mark is refused")
                .isThrownBy(() -> segments.complete(dead, "0".repeat(64), "0".repeat(64)))
                .satisfies(failure -> assertThat(failure.heldElsewhere()).isTrue());
    }

    @Test
    @DisplayName("the mark refuses digests that are not the rows' and a holder that is not the present one, and the segment stays claimed")
    void theMarkRefusesWhatIsNotTheRows() throws Exception {
        Store store = new Store();
        JdbcAuditArchiveSegments segments = new JdbcAuditArchiveSegments(DatabaseRoles::application);
        drain(segments, store);
        plant(2);
        AuditArchiveSegments.Claim claim = segments.claim(LEASE, 1000).orElseThrow();
        String content = AuditArchival.sha256(segments.content(claim.segment()).getBytes(StandardCharsets.UTF_8));
        String manifest = AuditArchival.sha256(segments.manifest(claim.segment()).getBytes(StandardCharsets.UTF_8));
        try {
            assertThatExceptionOfType(AuditArchiveDatabaseException.class)
                    .isThrownBy(() -> segments.complete(claim, "0".repeat(64), manifest))
                    .satisfies(failure -> assertThat(failure.digestRefused()).isTrue());
            assertThatExceptionOfType(AuditArchiveDatabaseException.class)
                    .isThrownBy(() -> segments.complete(new AuditArchiveSegments.Claim(claim.segment(), UUID.randomUUID()), content, manifest))
                    .satisfies(failure -> assertThat(failure.heldElsewhere()).isTrue());
            assertThat(state(claim.segment())).isEqualTo("CLAIMED");
        } finally {
            segments.complete(claim, content, manifest);
        }
        assertThat(state(claim.segment())).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("the store down: nothing marked, the rows still in their claimed segment; the store back after the lease, archived")
    void anUnavailableStoreLosesNothing() throws Exception {
        Store store = new Store();
        JdbcAuditArchiveSegments segments = new JdbcAuditArchiveSegments(DatabaseRoles::application);
        drain(segments, store);
        String probe = plant(4);
        store.down = true;

        AuditArchival.Result down = new AuditArchival(segments, store, Duration.ofSeconds(1), 1000).archiveOne();

        assertThat(down.outcome()).isEqualTo(AuditArchival.Outcome.STORE_UNAVAILABLE);
        assertThat(state(down.segment())).isEqualTo("CLAIMED");
        assertThat(memberships(probe) + queued(probe)).as("every planted row in its segment or its queue").isEqualTo(4);
        store.down = false;
        Thread.sleep(1_500);
        AuditArchival.Result back = new AuditArchival(segments, store, LEASE, 1000).archiveOne();
        assertThat(back.outcome()).isEqualTo(AuditArchival.Outcome.ARCHIVED);
        assertThat(back.segment()).isEqualTo(down.segment());
    }

    @Test
    @DisplayName("the read path: every line of an archived segment decodes to the very row it came from")
    void theReadPathRestoresEveryRow() throws Exception {
        Store store = new Store();
        JdbcAuditArchiveSegments segments = new JdbcAuditArchiveSegments(DatabaseRoles::application);
        String probe = plant(5);
        drain(segments, store);
        try (Connection application = DatabaseRoles.application();
                PreparedStatement read = application.prepareStatement(
                        "SELECT count(*), count(*) FILTER (WHERE platform.audit_archive_record(l.line) = a)"
                                + " FROM platform.audit_archive_segment s"
                                + " CROSS JOIN LATERAL unnest(string_to_array(rtrim(platform.audit_archive_content(s.segment_seq), E'\\n'), E'\\n')) AS l(line)"
                                + " JOIN platform.audit_record a ON a.audit_id = (l.line::json ->> 0)::uuid"
                                + " WHERE a.correlation_id = ?")) {
            read.setString(1, probe);
            try (ResultSet rows = read.executeQuery()) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(5);
                assertThat(rows.getLong(2)).as("decoded equal to their rows").isEqualTo(5);
            }
        }
    }

    @Test
    @DisplayName("the application role cannot write the archive's tables or the audit trail, and cannot run the review or the trigger's function")
    void theApplicationCannotAlterTheArchive() throws SQLException {
        try (Connection application = DatabaseRoles.application(); Statement statement = application.createStatement()) {
            DatabaseRoles.assertCannotBypassPrivileges(application);
            for (String sql : List.of("DELETE FROM platform.audit_record", "UPDATE platform.audit_record SET reason = 'x'",
                    "DELETE FROM platform.audit_archive_pending", "INSERT INTO platform.audit_archive_pending (audit_id) VALUES (gen_random_uuid())",
                    "UPDATE platform.audit_archive_segment SET reclaims = 0", "DELETE FROM platform.audit_archive_member",
                    "UPDATE platform.audit_archive_head SET last_segment_seq = 0", "SELECT platform.audit_archive_reviewed()")) {
                assertThatExceptionOfType(SQLException.class).as(sql).isThrownBy(() -> statement.execute(sql))
                        .satisfies(failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------

    /** Writes {@code n} audit rows as the application, under a correlation id of their own, which this returns. */
    private static String plant(int n) throws SQLException {
        String probe = "archive-probe-" + UUID.randomUUID();
        try (Connection application = DatabaseRoles.application();
                PreparedStatement insert = application.prepareStatement(
                        "INSERT INTO platform.audit_record (audit_id, actor_id, actor_type, occurred_at, operation, target_type, target_id,"
                                + " reason, outcome, correlation_id, change_summary)"
                                + " VALUES (gen_random_uuid(), 'system', 'SYSTEM', now(), 'probe.archive', 'probe', ?, NULL, 'SUCCEEDED', ?, ?)")) {
            for (int i = 0; i < n; i++) {
                insert.setString(1, "t-" + i);
                insert.setString(2, probe);
                insert.setString(3, i % 2 == 0 ? null : "a \"quoted\" line\nwith é");
                insert.executeUpdate();
            }
        }
        return probe;
    }

    /** Archives until nothing is queued and nothing claimed, so a case starts with the chain at rest. */
    private static void drain(AuditArchiveSegments segments, Store store) throws Exception {
        AuditArchival archival = new AuditArchival(segments, store, LEASE, 1000);
        for (int attempt = 0; attempt < 10_000; attempt++) {
            AuditArchival.Result result = archival.archiveOne();
            if (result.outcome() == AuditArchival.Outcome.IDLE && claimed() == 0) {
                return;
            }
            if (result.outcome() != AuditArchival.Outcome.ARCHIVED) {
                Thread.sleep(LEASE.toMillis());
            }
        }
        throw new AssertionError("the archive did not drain");
    }

    private static long queued(String probe) throws SQLException {
        return count("SELECT count(*) FROM platform.audit_archive_pending p JOIN platform.audit_record a USING (audit_id) WHERE a.correlation_id = ?", probe);
    }

    private static long memberships(String probe) throws SQLException {
        return count("SELECT count(*) FROM platform.audit_archive_member m JOIN platform.audit_record a USING (audit_id) WHERE a.correlation_id = ?", probe);
    }

    private static long claimed() throws SQLException {
        return count("SELECT count(*) FROM platform.audit_archive_segment WHERE state = 'CLAIMED' AND ? IS NOT NULL", "-");
    }

    private static long chainBreaks() throws SQLException {
        return count("SELECT count(*) FROM platform.audit_archive_segment s JOIN platform.audit_archive_segment p ON p.segment_seq = s.segment_seq - 1"
                + " WHERE s.state = 'ARCHIVED' AND s.previous_manifest_sha256 IS DISTINCT FROM p.manifest_sha256 AND ? IS NOT NULL", "-");
    }

    private static long reclaims(long segment) throws SQLException {
        return count("SELECT reclaims FROM platform.audit_archive_segment WHERE segment_seq = ?::bigint", Long.toString(segment));
    }

    private static List<Long> segmentsHolding(String probe) throws SQLException {
        List<Long> segments = new ArrayList<>();
        try (Connection application = DatabaseRoles.application();
                PreparedStatement select = application.prepareStatement(
                        "SELECT DISTINCT m.segment_seq FROM platform.audit_archive_member m JOIN platform.audit_record a USING (audit_id)"
                                + " WHERE a.correlation_id = ? ORDER BY 1")) {
            select.setString(1, probe);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    segments.add(rows.getLong(1));
                }
            }
        }
        return segments;
    }

    private static String state(long segment) throws SQLException {
        return text("SELECT state FROM platform.audit_archive_segment WHERE segment_seq = ?", segment);
    }

    private static String digest(long segment, String column) throws SQLException {
        return text("SELECT " + column + " FROM platform.audit_archive_segment WHERE segment_seq = ?", segment);
    }

    private static String text(String sql, long segment) throws SQLException {
        try (Connection application = DatabaseRoles.application(); PreparedStatement select = application.prepareStatement(sql)) {
            select.setLong(1, segment);
            try (ResultSet rows = select.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private static long count(String sql, String argument) throws SQLException {
        try (Connection application = DatabaseRoles.application(); PreparedStatement select = application.prepareStatement(sql)) {
            select.setString(1, argument);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    /** A write-once store in memory, shared by every archiver of a case, counting the writes that found no object. */
    private static final class Store implements AuditArchiveStore {

        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> writes = new ConcurrentHashMap<>();
        volatile boolean down;

        @Override
        public Put putIfAbsent(String key, byte[] bytes) {
            if (down) {
                throw new AuditArchiveStoreException("the store is down");
            }
            if (objects.putIfAbsent(key, bytes.clone()) == null) {
                writes.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                return Put.WRITTEN;
            }
            return Put.ALREADY_PRESENT;
        }

        @Override
        public Optional<byte[]> get(String key, int maxBytes) {
            if (down) {
                throw new AuditArchiveStoreException("the store is down");
            }
            return Optional.ofNullable(objects.get(key));
        }
    }
}
