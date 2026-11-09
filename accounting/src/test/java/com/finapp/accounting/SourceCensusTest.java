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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The census's batch against stand-in stores (`P15-TSK-035`): one batch, one transaction; a namespace with no join, a key with no record
 * and a differing figure counted; the head moved to the last entry's whole position; a walker that finds the head taken does nothing; a
 * head ahead of the database refused; a source that cannot be read failing the batch whole; a key that cannot be judged counted instead.
 * <strong>The position's meaning is not here</strong> (a stand-in journal can only obey it): that is {@code LedgerJournalCensusWalkTest}'s
 * statement and {@code SourceCensusDatabaseTest}'s cases (written, owed to {@code P15-TST-002}).
 */
@DisplayName("the source-join census's batch (P15-TSK-035)")
class SourceCensusTest {

    private static final String TRANSFER = "transfer:";

    private static UUID id(long low) {
        return new UUID(0x0190_0000_0000_7000L, 0x8000_0000_0000_0000L | low);
    }

    private final FakeJournal journal = new FakeJournal();
    private final FakeStore store = new FakeStore();

    @Test
    @DisplayName("an entry with no record is unsourced, a differing record is mismatched, a matching one is neither")
    void judgesEachEntry() {
        UUID matched = journal.post(10, id(1), TRANSFER, "transfers-ok", "USD", 2, 100);
        UUID unsourced = journal.post(11, id(2), TRANSFER, "no-such-record", "USD", 2, 100);
        UUID mismatched = journal.post(12, id(3), TRANSFER, "transfers-short", "USD", 2, 100);
        SourceCensus census = census(Map.of("transfers-ok", SourceFigures.total("USD", 2, 100),
                "transfers-short", SourceFigures.total("USD", 2, 99)));

        SourceCensus.Batch done = census.sweepBatch();

        assertThat(done.judged()).isEqualTo(3);
        assertThat(store.findingsOf(unsourced)).singleElement().satisfies(finding ->
                assertThat(finding.kind()).isEqualTo(SourceFindingKind.UNSOURCED));
        assertThat(store.findingsOf(mismatched)).singleElement().satisfies(finding ->
                assertThat(finding.kind()).isEqualTo(SourceFindingKind.MISMATCHED));
        assertThat(store.findingsOf(matched)).isEmpty();
    }

    @Test
    @DisplayName("the head moves to the last entry's whole position: its transaction and its id, never the id alone")
    void theHeadMovesToTheLastEntrysWholePosition() {
        journal.post(10, id(9), TRANSFER, "a", "USD", 2, 1);
        journal.post(11, id(1), TRANSFER, "b", "USD", 2, 1);

        census(Map.of()).sweepBatch();

        assertThat(store.head.position()).isEqualTo(new JournalCensusWalk.Position(11, id(1)));
        assertThat(store.head.entriesJudged()).isEqualTo(2);
    }

    @Test
    @DisplayName("a head ahead of the database refuses the batch, stamped: a logical restore cannot walk on, or skip, silently")
    void aHeadAheadOfTheDatabaseRefusesTheBatch() {
        journal.post(10, id(1), TRANSFER, "a", "USD", 2, 1);
        store.ahead = true;

        assertThatThrownBy(census(Map.of())::sweepBatch).isInstanceOf(AccountingStorageException.class).hasMessageContaining("restore");
        assertThat(store.head.position()).as("nothing moved").isEqualTo(store.floor());
        assertThat(store.findings).isEmpty();
        assertThat(store.failureClasses).containsExactly("AccountingStorageException");
    }

    @Test
    @DisplayName("a namespace no join answers for is counted, and so is an entry that names none: no namespace passes silently")
    void noJoinIsCounted() {
        UUID unknown = journal.postRaw(10, id(1), EntryType.POSTING, Optional.of("not-registered:"), "whatever", "whatever", "USD", 2, 5);
        UUID unnamed = journal.postRaw(11, id(2), EntryType.POSTING, Optional.empty(), "", "whatever", "USD", 2, 5);
        census(Map.of()).sweepBatch();

        assertThat(store.findingsOf(unknown)).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(SourceFindingKind.UNSOURCED);
            assertThat(finding.evidence()).contains("no join");
        });
        assertThat(store.findingsOf(unnamed)).singleElement().satisfies(finding ->
                assertThat(finding.kind()).isEqualTo(SourceFindingKind.UNSOURCED));
    }

    @Test
    @DisplayName("a blank key is counted, not thrown: one row must not stop the census (the review's plant)")
    void aBlankKeyIsCountedNotThrown() {
        UUID blank = journal.postRaw(10, id(1), EntryType.POSTING, Optional.of(" "), "whatever", "whatever", "USD", 2, 5);
        UUID after = journal.post(11, id(2), TRANSFER, "no-such", "USD", 2, 5);

        census(Map.of()).sweepBatch();

        assertThat(store.findingsOf(blank)).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(SourceFindingKind.UNSOURCED);
            assertThat(finding.namespace()).isEqualTo(SourceCensus.UNNAMED);
        });
        assertThat(store.findingsOf(after)).as("and the batch went on past it").hasSize(1);
        assertThat(store.head.entriesJudged()).isEqualTo(2);
    }

    @Test
    @DisplayName("an oversized namespace is cut to the column's bound: a forged key cannot fail the batch by its length")
    void anOversizedNamespaceIsBounded() {
        String longNamespace = "x".repeat(150) + ":";
        UUID entry = journal.postRaw(10, id(1), EntryType.POSTING, Optional.of(longNamespace), "whatever", "whatever", "USD", 2, 5);
        census(Map.of()).sweepBatch();
        assertThat(store.findingsOf(entry)).singleElement().satisfies(finding ->
                assertThat(finding.namespace()).hasSize(SourceCensus.MAX_NAMESPACE));
    }

    @Test
    @DisplayName("a walker that finds the head taken does nothing, and judges nothing twice")
    void aTakenHeadDoesNothing() {
        journal.post(10, id(1), TRANSFER, "x", "USD", 2, 1);
        store.held = true;
        SourceCensus.Batch done = census(Map.of("x", SourceFigures.total("USD", 2, 1))).sweepBatch();
        assertThat(done.claimed()).isFalse();
        assertThat(store.head.position()).isEqualTo(store.floor());
        assertThat(store.findings).isEmpty();
    }

    @Test
    @DisplayName("an empty page moves nothing and stamps success: an idle journal is healthy")
    void anEmptyPageStampsSuccess() {
        SourceCensus.Batch done = census(Map.of()).sweepBatch();
        assertThat(done.claimed()).isTrue();
        assertThat(done.judged()).isZero();
        assertThat(store.touched).isEqualTo(1);
    }

    @Test
    @DisplayName("a source that cannot be read fails the batch whole: no finding, the head where it was, the failure stamped")
    void anUnreadableSourceFailsTheBatch() {
        journal.post(10, id(1), TRANSFER, "x", "USD", 2, 1);
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
        };
        return new SourceCensus(units, store, journal, new SourceJoinCatalogue(sources), 100, Duration.ofSeconds(5));
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

    /** A journal in (txid, id) order: just enough to feed a page. */
    private static final class FakeJournal implements JournalCensusWalk {

        private final List<CensusEntry> entries = new ArrayList<>();

        UUID post(long txid, UUID id, String namespace, String suffix, String currency, int scale, long minor) {
            return postRaw(txid, id, EntryType.POSTING, Optional.of(namespace), suffix, suffix, currency, scale, minor);
        }

        UUID postRaw(long txid, UUID id, EntryType door, Optional<String> namespace, String suffix, String reference, String currency, int scale, long minor) {
            entries.add(new CensusEntry(new Position(txid, id), door, namespace, suffix, reference, List.of(
                    new JournalShapes.ShapeLine(1, "CUSTOMER_WALLET", Side.DEBIT, currency, scale, BigInteger.valueOf(minor)),
                    new JournalShapes.ShapeLine(2, "CUSTOMER_WALLET", Side.CREDIT, currency, scale, BigInteger.valueOf(minor)))));
            return id;
        }

        @Override
        public List<CensusEntry> page(Connection connection, Position after, int limit) {
            return entries.stream()
                    .filter(entry -> entry.position().txid() > after.txid()
                            || (entry.position().txid() == after.txid() && entry.id().compareTo(after.id()) > 0))
                    .sorted(Comparator.comparingLong((CensusEntry entry) -> entry.position().txid()).thenComparing(CensusEntry::id))
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<Instant> oldestAfter(Connection connection, Position position) {
            return Optional.empty();
        }
    }

    /** The head, the findings, the failures - in memory. */
    private static final class FakeStore implements SourceCensusStore {

        Head head = new Head(new JournalCensusWalk.Position(0, new UUID(0, 0)), 0, false);
        final List<Finding> findings = new ArrayList<>();
        final List<String> failureClasses = new ArrayList<>();
        int touched;
        boolean held;
        boolean ahead;

        JournalCensusWalk.Position floor() {
            return new JournalCensusWalk.Position(0, new UUID(0, 0));
        }

        List<Finding> findingsOf(UUID entry) {
            return findings.stream().filter(finding -> finding.entryId().equals(entry)).toList();
        }

        @Override
        public Optional<Head> claimHead(Connection connection) {
            return held ? Optional.empty() : Optional.of(new Head(head.position(), head.entriesJudged(), ahead));
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
        public void advance(Connection connection, JournalCensusWalk.Position position, long entriesJudged) {
            head = new Head(position, head.entriesJudged() + entriesJudged, false);
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
        public Reading read(Connection connection, Duration standing, Duration recent, Duration staleAfter) {
            throw new UnsupportedOperationException("the gauges are SourceCensusMetricsTest's");
        }
    }
}
