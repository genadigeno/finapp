package com.finapp.platform.audit.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One segment from claim to mark, with no database and no store (`P16-TSK-010`): the protocol the archive's correctness rests on.
 * What the claim and the mark decide is the migration's, held by {@code AuditArchiveMigrationTest} and, on PostgreSQL,
 * {@code AuditArchivalDatabaseTest} (owed).
 *
 * <p>The rule under test above all: <strong>nothing is marked before both objects have been read back equal to the rows</strong>.
 */
@DisplayName("the audit archive's segment: claimed, written once, read back, and only then marked (P16-TSK-010)")
class AuditArchivalTest {

    private static final Duration LEASE = Duration.ofMinutes(2);

    private static final String CONTENT = "[\"a\"]\n[\"b\"]\n[\"c\"]\n";

    private static final String MANIFEST = "{\"segment\" : 7}";

    @Test
    @DisplayName("a segment is written once, read back equal, and marked with the digests of what was read back")
    void aSegmentIsArchived() {
        Segments segments = new Segments().claiming(7);
        Store store = new Store();

        AuditArchival.Result result = archival(segments, store).archiveOne();

        assertThat(result).isEqualTo(new AuditArchival.Result(AuditArchival.Outcome.ARCHIVED, 7, 3));
        assertThat(store.objects).containsOnlyKeys("audit/v1/segments/000000000007.ndjson", "audit/v1/manifests/000000000007.json");
        assertThat(store.calls).containsExactly(
                "put audit/v1/segments/000000000007.ndjson", "put audit/v1/manifests/000000000007.json",
                "get audit/v1/segments/000000000007.ndjson", "get audit/v1/manifests/000000000007.json");
        assertThat(segments.marks).containsExactly(new Mark(7, segments.holder,
                AuditArchival.sha256(bytes(CONTENT)), AuditArchival.sha256(bytes(MANIFEST))));
    }

    @Test
    @DisplayName("a segment taken over after its holder died finds its copy already there, reads it back and marks it once")
    void aSegmentTakenOverFindsItsCopyAndMarksIt() {
        Segments segments = new Segments().claiming(7);
        Store store = new Store();
        store.objects.put(AuditArchival.contentKey(7), bytes(CONTENT));
        store.objects.put(AuditArchival.manifestKey(7), bytes(MANIFEST));

        AuditArchival.Result result = archival(segments, store).archiveOne();

        assertThat(result.outcome()).isEqualTo(AuditArchival.Outcome.ARCHIVED);
        assertThat(store.written).as("nothing overwritten: both puts found the objects").isEmpty();
        assertThat(segments.marks).hasSize(1);
    }

    @Test
    @DisplayName("nothing to claim: no store call, nothing marked")
    void nothingToClaim() {
        Segments segments = new Segments();
        Store store = new Store();

        assertThat(archival(segments, store).archiveOne()).isEqualTo(new AuditArchival.Result(AuditArchival.Outcome.IDLE, 0, 0));
        assertThat(store.calls).isEmpty();
        assertThat(segments.marks).isEmpty();
    }

    @Test
    @DisplayName("the store down at the write, or at the read-back: nothing marked, the segment left to its lease, nothing lost")
    void anUnavailableStoreLosesNothing() {
        for (String failing : List.of("put", "get")) {
            Segments segments = new Segments().claiming(7);
            Store store = new Store().failingAt(failing);

            AuditArchival.Result result = archival(segments, store).archiveOne();

            assertThat(result).as(failing).isEqualTo(new AuditArchival.Result(AuditArchival.Outcome.STORE_UNAVAILABLE, 7, 0));
            assertThat(segments.marks).as("%s: nothing marked", failing).isEmpty();
        }
    }

    @Test
    @DisplayName("a write the store lost (the read-back finds nothing) is never marked: the probe's target, a mark before the copy is verified")
    void aLostWriteIsNeverMarked() {
        Segments segments = new Segments().claiming(7);
        Store store = new Store().losingWrites();

        AuditArchival.Result result = archival(segments, store).archiveOne();

        assertThat(result.outcome()).isEqualTo(AuditArchival.Outcome.MISMATCH);
        assertThat(segments.marks).as("a copy that is not there is not archived").isEmpty();
    }

    @Test
    @DisplayName("an object at the key that is not the segment's rows - one byte different, shorter, or longer - is never marked")
    void aCopyThatDiffersIsNeverMarked() {
        for (String stored : List.of(CONTENT.replace('b', 'x'), CONTENT.substring(1), CONTENT + "[\"d\"]\n")) {
            Segments segments = new Segments().claiming(7);
            Store store = new Store();
            store.objects.put(AuditArchival.contentKey(7), bytes(stored));

            AuditArchival.Result result = archival(segments, store).archiveOne();

            assertThat(result.outcome()).as(stored).isEqualTo(AuditArchival.Outcome.MISMATCH);
            assertThat(segments.marks).as(stored).isEmpty();
        }
        Segments segments = new Segments().claiming(7);
        Store store = new Store();
        store.objects.put(AuditArchival.manifestKey(7), bytes(MANIFEST + " "));
        assertThat(archival(segments, store).archiveOne().outcome()).as("a manifest that differs").isEqualTo(AuditArchival.Outcome.MISMATCH);
        assertThat(segments.marks).isEmpty();
    }

    @Test
    @DisplayName("a read is bounded by what is expected: the store is asked for no more than the rendered length")
    void aReadIsBoundedByWhatIsExpected() {
        Segments segments = new Segments().claiming(7);
        Store store = new Store();

        archival(segments, store).archiveOne();

        assertThat(store.bounds).containsExactly(bytes(CONTENT).length, bytes(MANIFEST).length);
    }

    @Test
    @DisplayName("a holder taken over in mid-copy is refused at the mark (fenced), and a refused digest is never retried as archived")
    void theMarksRefusalsAreOutcomes() {
        Segments fenced = new Segments().claiming(7).refusingWith(AuditArchiveDatabaseException.NOT_HELD);
        assertThat(archival(fenced, new Store()).archiveOne().outcome()).isEqualTo(AuditArchival.Outcome.FENCED);

        Segments refused = new Segments().claiming(7).refusingWith(AuditArchiveDatabaseException.DIGEST_REFUSED);
        assertThat(archival(refused, new Store()).archiveOne().outcome()).isEqualTo(AuditArchival.Outcome.REFUSED);

        Segments broken = new Segments().claiming(7).refusingWith("08006");
        assertThatExceptionOfType(AuditArchiveDatabaseException.class)
                .isThrownBy(() -> archival(broken, new Store()).archiveOne())
                .satisfies(failure -> assertThat(failure.sqlState()).isEqualTo("08006"));
    }

    @Test
    @DisplayName("the claim is asked with the configured lease and batch, and the lease and batch are refused outside what the claim accepts")
    void theClaimsTermsAreBounded() {
        Segments segments = new Segments();
        archival(segments, new Store()).archiveOne();
        assertThat(segments.claims).containsExactly(LEASE + "/250");

        assertThatIllegalArgumentException().isThrownBy(() -> new AuditArchival(segments, new Store(), Duration.ofMillis(999), 10));
        assertThatIllegalArgumentException().isThrownBy(() -> new AuditArchival(segments, new Store(), Duration.ofHours(2), 10));
        assertThatIllegalArgumentException().isThrownBy(() -> new AuditArchival(segments, new Store(), LEASE, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new AuditArchival(segments, new Store(), LEASE, AuditArchival.MAX_BATCH_SIZE + 1));
    }

    @Test
    @DisplayName("the digest is the lower-case hex the database's encode(sha256(...), 'hex') writes")
    void theDigestIsTheDatabases() {
        assertThat(AuditArchival.sha256(bytes("abc")))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    // ------------------------------------------------------------------------------------------------------------------

    private static AuditArchival archival(Segments segments, Store store) {
        return new AuditArchival(segments, store, LEASE, 250);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private record Mark(long segment, UUID holder, String content, String manifest) {}

    /** The database's half, scripted: a claim or none, the segment's text, and the marks it received. */
    private static final class Segments implements AuditArchiveSegments {

        final UUID holder = UUID.randomUUID();
        final Deque<Long> claimable = new ArrayDeque<>();
        final List<Mark> marks = new ArrayList<>();
        final List<String> claims = new ArrayList<>();
        String refusal;

        Segments claiming(long segment) {
            claimable.add(segment);
            return this;
        }

        Segments refusingWith(String sqlState) {
            refusal = sqlState;
            return this;
        }

        @Override
        public Optional<Claim> claim(Duration lease, int batchSize) {
            claims.add(lease + "/" + batchSize);
            Long next = claimable.poll();
            return next == null ? Optional.empty() : Optional.of(new Claim(next, holder));
        }

        @Override
        public String content(long segment) {
            return CONTENT;
        }

        @Override
        public String manifest(long segment) {
            return MANIFEST;
        }

        @Override
        public void complete(Claim claim, String contentSha256, String manifestSha256) {
            if (refusal != null) {
                throw new AuditArchiveDatabaseException("complete", refusal);
            }
            marks.add(new Mark(claim.segment(), claim.holder(), contentSha256, manifestSha256));
        }

        @Override
        public Backlog backlog(int cap) {
            return new Backlog(0, Duration.ZERO);
        }
    }

    /** A write-once store in memory, which can fail, or lose what it was given. */
    private static final class Store implements AuditArchiveStore {

        final Map<String, byte[]> objects = new HashMap<>();
        final List<String> written = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        final List<Integer> bounds = new ArrayList<>();
        String failingAt;
        boolean losing;

        Store failingAt(String call) {
            failingAt = call;
            return this;
        }

        Store losingWrites() {
            losing = true;
            return this;
        }

        @Override
        public Put putIfAbsent(String key, byte[] bytes) {
            calls.add("put " + key);
            if ("put".equals(failingAt)) {
                throw new AuditArchiveStoreException("the store answered 503 to a write");
            }
            if (objects.containsKey(key)) {
                return Put.ALREADY_PRESENT;
            }
            written.add(key);
            if (!losing) {
                objects.put(key, bytes.clone());
            }
            return Put.WRITTEN;
        }

        @Override
        public Optional<byte[]> get(String key, int maxBytes) {
            calls.add("get " + key);
            bounds.add(maxBytes);
            if ("get".equals(failingAt)) {
                throw new AuditArchiveStoreException("the store did not finish a read");
            }
            byte[] stored = objects.get(key);
            if (stored == null) {
                return Optional.empty();
            }
            return Optional.of(java.util.Arrays.copyOf(stored, Math.min(stored.length, maxBytes + 1)));
        }
    }
}
