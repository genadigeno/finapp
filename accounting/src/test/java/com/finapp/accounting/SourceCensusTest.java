package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.sourcejoin.EntryRef;
import com.finapp.platform.sourcejoin.JournalSources;
import com.finapp.platform.sourcejoin.SourceDoor;
import com.finapp.platform.sourcejoin.SourceFigures;
import com.finapp.platform.sourcejoin.SourceJoin;
import com.finapp.platform.sourcejoin.SourceNamespace;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The census's walk against stand-in stores (`P15-TSK-035`; the entry's multi-instance and failure lines): one batch, one
 * transaction; a namespace with no join, a key with no record and a differing figure counted; the head advancing past exactly what
 * was judged; <strong>the fence holding an open transaction's position back so a late commit is still counted</strong>; a walker
 * that finds the head taken does nothing; a source that cannot be read fails the batch whole, moves nothing, and stamps its failure.
 * The real journal and the real fence are {@code SourceCensusDatabaseTest}'s (written, owed to {@code P15-TST-002}).
 */
@DisplayName("the source-join census walks the journal behind a fence (P15-TSK-035)")
class SourceCensusTest {

    private static final String TRANSFER = "transfer:";

    /** UUIDv7-shaped ids: the high 48 bits are the minting millisecond, as ADR-0013's generator writes them. */
    private static UUID mintedAt(long millis, long counter) {
        return new UUID((millis << 16) | 0x7000L, 0x8000000000000000L | counter);
    }

    private static Instant millis(long millis) {
        return Instant.ofEpochMilli(millis);
    }

    private final FakeJournal journal = new FakeJournal();
    private final FakeStore store = new FakeStore();

    @Test
    @DisplayName("an entry with no record is unsourced, a differing record is mismatched, a matching one is neither, and the head passes them all")
    void judgesEachEntry() {
        UUID matched = journal.post(mintedAt(1_000, 1), TRANSFER, "transfers-ok", "USD", 2, 100);
        UUID unsourced = journal.post(mintedAt(1_001, 2), TRANSFER, "no-such-record", "USD", 2, 100);
        UUID mismatched = journal.post(mintedAt(1_002, 3), TRANSFER, "transfers-short", "USD", 2, 100);
        SourceCensus census = census(Map.of("transfers-ok", SourceFigures.total("USD", 2, 100),
                "transfers-short", SourceFigures.total("USD", 2, 99)));
        store.fence = millis(9_999);

        SourceCensus.Batch done = census.sweepBatch();

        assertThat(done.judged()).isEqualTo(3);
        assertThat(store.findingsOf(unsourced)).singleElement().satisfies(finding ->
                assertThat(finding.kind()).isEqualTo(SourceFindingKind.UNSOURCED));
        assertThat(store.findingsOf(mismatched)).singleElement().satisfies(finding ->
                assertThat(finding.kind()).isEqualTo(SourceFindingKind.MISMATCHED));
        assertThat(store.findingsOf(matched)).isEmpty();
        assertThat(store.head.position()).isEqualTo(mismatched);
        assertThat(store.head.entriesJudged()).isEqualTo(3);
    }

    @Test
    @DisplayName("the fence holds a late-committing entry's position: it is not passed while open, and is counted once committed (the probe)")
    void aLateCommitIsStillCounted() {
        // Transaction A opens at 950 and mints entry 1000 at t=1000, uncommitted. Transaction B mints entry 1001 at t=1001 and commits.
        UUID late = journal.postUncommitted(mintedAt(1_000, 1), TRANSFER, "late-commit", "USD", 2, 500);
        UUID later = journal.post(mintedAt(1_001, 2), TRANSFER, "no-such-record-either", "USD", 2, 7);
        SourceCensus census = census(Map.of("no-such-record-either", SourceFigures.total("USD", 2, 7)));

        // A is open: the fence is its start, 950, so nothing minted at or after 950 is yet walked.
        store.fence = millis(950);
        census.sweepBatch();
        assertThat(store.head.position()).as("nothing passed while the transaction is open").isEqualTo(store.floor());
        assertThat(store.findingsOf(later)).isEmpty();

        // A commits; the fence moves on.
        journal.commit(late);
        store.fence = millis(9_999);
        census.sweepBatch();

        assertThat(store.findingsOf(late)).as("the late commit is counted, not skipped")
                .singleElement().satisfies(finding -> assertThat(finding.kind()).isEqualTo(SourceFindingKind.UNSOURCED));
        assertThat(store.head.position()).isEqualTo(later);
    }

    @Test
    @DisplayName("the fence is read before the batch's transaction begins: a transaction that commits between them is neither side's")
    void theFenceIsReadBeforeTheBatch() {
        journal.post(mintedAt(1_000, 1), TRANSFER, "x", "USD", 2, 1);
        List<String> order = new ArrayList<>();
        store.onFence = () -> order.add("fence");
        store.onClaim = () -> order.add("claim");
        census(Map.of("x", SourceFigures.total("USD", 2, 1))).sweepBatch();
        assertThat(order).containsExactly("fence", "claim");
    }

    @Test
    @DisplayName("a namespace no join answers for is counted, and so is an entry that names no namespace: no namespace passes silently")
    void noJoinIsCounted() {
        UUID unknown = journal.postRaw(mintedAt(1_000, 1), EntryType.POSTING, Optional.of("not-registered:"), "whatever", "whatever", "USD", 2, 5);
        UUID unnamed = journal.postRaw(mintedAt(1_001, 2), EntryType.POSTING, Optional.empty(), "", "whatever", "USD", 2, 5);
        store.fence = millis(9_999);
        census(Map.of()).sweepBatch();

        assertThat(store.findingsOf(unknown)).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(SourceFindingKind.UNSOURCED);
            assertThat(finding.evidence()).contains("no join");
        });
        assertThat(store.findingsOf(unnamed)).singleElement().satisfies(finding ->
                assertThat(finding.kind()).isEqualTo(SourceFindingKind.UNSOURCED));
    }

    @Test
    @DisplayName("a walker that finds the head taken does nothing, and judges nothing twice")
    void aTakenHeadDoesNothing() {
        journal.post(mintedAt(1_000, 1), TRANSFER, "x", "USD", 2, 1);
        store.held = true;
        store.fence = millis(9_999);
        SourceCensus.Batch done = census(Map.of("x", SourceFigures.total("USD", 2, 1))).sweepBatch();
        assertThat(done.claimed()).isFalse();
        assertThat(store.head.position()).isEqualTo(store.floor());
        assertThat(store.findings).isEmpty();
    }

    @Test
    @DisplayName("a page the fence leaves empty moves nothing and stamps success: an idle journal is healthy")
    void anEmptyPageStampsSuccess() {
        store.fence = millis(9_999);
        SourceCensus.Batch done = census(Map.of()).sweepBatch();
        assertThat(done.claimed()).isTrue();
        assertThat(done.judged()).isZero();
        assertThat(store.touched).isEqualTo(1);
    }

    @Test
    @DisplayName("a source that cannot be read fails the batch whole: no finding, the head where it was, the failure stamped")
    void anUnreadableSourceFailsTheBatch() {
        journal.post(mintedAt(1_000, 1), TRANSFER, "x", "USD", 2, 1);
        store.fence = millis(9_999);
        SourceCensus census = censusOver(List.of(new JournalSources() {
            @Override
            public String module() {
                return "fake";
            }

            @Override
            public List<SourceJoin> joins() {
                return List.of(new SourceJoin() {
                    @Override
                    public SourceNamespace namespace() {
                        return new SourceNamespace(SourceDoor.POSTING, TRANSFER);
                    }

                    @Override
                    public String statement() {
                        return "a fake";
                    }

                    @Override
                    public String sql() {
                        return "SELECT 1";
                    }

                    @Override
                    public Map<UUID, SourceFigures> read(Connection connection, Collection<EntryRef> entries) throws SQLException {
                        throw new SQLException("the source is away", "08006");
                    }
                });
            }
        }));

        assertThatThrownBy(census::sweepBatch).isInstanceOf(AccountingStorageException.class);

        assertThat(store.findings).as("nothing recorded").isEmpty();
        assertThat(store.head.position()).as("the head stayed").isEqualTo(store.floor());
        assertThat(store.failureClasses).containsExactly("AccountingStorageException");
    }

    private SourceCensus census(Map<String, SourceFigures> records) {
        return censusOver(List.of(new JournalSources() {
            @Override
            public String module() {
                return "fake";
            }

            @Override
            public List<SourceJoin> joins() {
                return List.of(new FakeJoin(new SourceNamespace(SourceDoor.POSTING, TRANSFER), records));
            }
        }));
    }

    private SourceCensus censusOver(List<JournalSources> sources) {
        SourceCensus.Units units = new SourceCensus.Units() {
            private final Connection connection = connection();

            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                return work.apply(connection);
            }

            @Override
            public <R> R outsideTransaction(Function<Connection, R> work) {
                return work.apply(connection);
            }
        };
        return new SourceCensus(units, store, journal, new SourceJoinCatalogue(sources), Duration.ZERO, 100, Duration.ofSeconds(5));
    }

    /** A connection whose only job is to answer the statement timeout's {@code set_config}. */
    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("prepareStatement")) {
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class}, (p, m, a) -> switch (m.getName()) {
                    case "execute" -> Boolean.TRUE;
                    default -> null;
                });
            }
            return null;
        });
    }

    private record FakeJoin(SourceNamespace namespace, Map<String, SourceFigures> records) implements SourceJoin {

        @Override
        public String statement() {
            return "a fake join";
        }

        @Override
        public String sql() {
            return "SELECT 1";
        }

        @Override
        public Map<UUID, SourceFigures> read(Connection connection, Collection<EntryRef> entries) {
            Map<UUID, SourceFigures> found = new HashMap<>();
            for (EntryRef entry : entries) {
                SourceFigures figures = records.get(entry.suffix());
                if (figures != null) {
                    found.put(entry.entry(), figures);
                }
            }
            return found;
        }
    }

    /** A journal that knows what is committed. */
    private static final class FakeJournal implements JournalCensusWalk {

        private final List<CensusEntry> entries = new ArrayList<>();
        private final List<UUID> uncommitted = new ArrayList<>();

        UUID post(UUID id, String namespace, String suffix, String currency, int scale, long minor) {
            entries.add(entry(id, EntryType.POSTING, Optional.of(namespace), suffix, suffix, currency, scale, minor));
            return id;
        }

        UUID postUncommitted(UUID id, String namespace, String suffix, String currency, int scale, long minor) {
            post(id, namespace, suffix, currency, scale, minor);
            uncommitted.add(id);
            return id;
        }

        UUID postRaw(UUID id, EntryType door, Optional<String> namespace, String suffix, String reference, String currency, int scale, long minor) {
            entries.add(entry(id, door, namespace, suffix, reference, currency, scale, minor));
            return id;
        }

        void commit(UUID id) {
            uncommitted.remove(id);
        }

        private static CensusEntry entry(UUID id, EntryType door, Optional<String> namespace, String suffix, String reference, String currency, int scale, long minor) {
            return new CensusEntry(id, door, namespace, suffix, reference, List.of(
                    new JournalShapes.ShapeLine(1, "CUSTOMER_WALLET", Side.DEBIT, currency, scale, BigInteger.valueOf(minor)),
                    new JournalShapes.ShapeLine(2, "CUSTOMER_WALLET", Side.CREDIT, currency, scale, BigInteger.valueOf(minor))));
        }

        @Override
        public List<CensusEntry> page(Connection connection, UUID after, Instant mintedBefore, int limit) {
            // Sorted ascending by id bytes (the keyset), only what is committed and minted before the bound.
            long afterTime = after.getMostSignificantBits() >>> 16;
            long boundTime = mintedBefore.toEpochMilli();
            return entries.stream()
                    .filter(entry -> !uncommitted.contains(entry.id()))
                    .filter(entry -> (entry.id().getMostSignificantBits() >>> 16) < boundTime)
                    .filter(entry -> (entry.id().getMostSignificantBits() >>> 16) > afterTime || entry.id().compareTo(after) > 0)
                    .sorted(java.util.Comparator.comparing(CensusEntry::id))
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<Instant> oldestAfter(Connection connection, UUID position) {
            return Optional.empty();
        }
    }

    /** The head, the findings, the failures - in memory. */
    private static final class FakeStore implements SourceCensusStore {

        Head head = new Head(mintedAt(0, 0), 0);
        final List<Finding> findings = new ArrayList<>();
        final List<String> failureClasses = new ArrayList<>();
        int touched;
        boolean held;
        Instant fence = Instant.ofEpochMilli(0);
        Runnable onFence = () -> {};
        Runnable onClaim = () -> {};

        UUID floor() {
            return mintedAt(0, 0);
        }

        List<Finding> findingsOf(UUID entry) {
            return findings.stream().filter(finding -> finding.entryId().equals(entry)).toList();
        }

        @Override
        public Instant fence(Connection connection) {
            onFence.run();
            return fence;
        }

        @Override
        public Optional<Head> claimHead(Connection connection) {
            onClaim.run();
            return held ? Optional.empty() : Optional.of(head);
        }

        @Override
        public void insertFindings(Connection connection, List<Finding> found) {
            found.forEach(finding -> {
                if (findingsOf(finding.entryId()).isEmpty()) {
                    findings.add(finding);
                }
            });
        }

        @Override
        public void advance(Connection connection, UUID position, long entriesJudged) {
            head = new Head(position, head.entriesJudged() + entriesJudged);
        }

        @Override
        public void touch(Connection connection) {
            touched++;
        }

        @Override
        public void markFailure(Connection connection, String failureClass) {
            failureClasses.add(failureClass);
        }

        @Override
        public Reading read(Connection connection, Duration standing, Duration staleAfter) {
            throw new UnsupportedOperationException("the gauges are SourceCensusMetricsTest's");
        }
    }
}
