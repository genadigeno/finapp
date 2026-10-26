package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-mon's intake leg over fakes (`P13-TSK-019`; {@code INV-RSK-09}): a batch claims, reads after the watermark within
 * the port's contract, inserts born once and advances to the last entry read - the permit now after a full batch, the
 * idle cadence after a short one; an unclaimed watermark is an empty batch; a re-run over the same entries bears nothing
 * new; a port that fails, or serves an entry at or before the position, leaves the watermark where it stood.
 */
@DisplayName("the monitoring intake (P13-TSK-019)")
class MonitoringIntakeTest {

    private static final IdGenerator IDS = new IdGenerator(
            Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC), new Random(19));
    private static final Connection NO_CONNECTION = (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes hold their state in memory");
            });
    private static final String SOURCE = MonitoringWatermark.LEDGER_JOURNAL;

    private FakeLedger ledger;
    private FakeWatermarks watermarks;
    private FakeTransactions transactions;

    @BeforeEach
    void fakes() {
        ledger = new FakeLedger();
        watermarks = new FakeWatermarks();
        transactions = new FakeTransactions();
    }

    private MonitoringIntake intake(int batch) {
        return new MonitoringIntake(ledger, watermarks, transactions, IDS, Duration.ofSeconds(60), batch,
                Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("the configuration is judged first: a negative horizon, a zero cadence or an empty batch refuse to build")
    void configurationJudged() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new MonitoringIntake(ledger,
                watermarks, transactions, IDS, Duration.ofSeconds(-1), 10, Duration.ofSeconds(5)));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new MonitoringIntake(ledger,
                watermarks, transactions, IDS, Duration.ofSeconds(60), 10, Duration.ZERO));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new MonitoringIntake(ledger,
                watermarks, transactions, IDS, Duration.ofSeconds(60), 0, Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("batches: a full batch advances to its last entry with the permit due now; the short one after it takes"
            + " the idle cadence; every entry is born exactly once")
    void batchesAdvanceTheWatermark() {
        List<MonitoredEntry> entries = ledger.plant(5);
        MonitoringIntake intake = intake(2);

        MonitoringIntake.Batch first = intake.sweepBatch(NO_CONNECTION, SOURCE);
        assertThat(first).isEqualTo(new MonitoringIntake.Batch(true, 2, 2, 0, true));
        assertThat(watermarks.row.lastEntryId()).isEqualTo(entries.get(1).journalEntryId());
        assertThat(watermarks.lastPermitDelay).isEqualTo(Duration.ZERO);

        MonitoringIntake.Batch second = intake.sweepBatch(NO_CONNECTION, SOURCE);
        assertThat(second).isEqualTo(new MonitoringIntake.Batch(true, 2, 2, 0, true));
        assertThat(watermarks.row.lastEntryId()).isEqualTo(entries.get(3).journalEntryId());

        MonitoringIntake.Batch third = intake.sweepBatch(NO_CONNECTION, SOURCE);
        assertThat(third).isEqualTo(new MonitoringIntake.Batch(true, 1, 1, 0, false));
        assertThat(watermarks.row.lastEntryId()).isEqualTo(entries.get(4).journalEntryId());
        assertThat(watermarks.lastPermitDelay).isEqualTo(Duration.ofSeconds(5));

        MonitoringIntake.Batch fourth = intake.sweepBatch(NO_CONNECTION, SOURCE);
        assertThat(fourth).isEqualTo(new MonitoringIntake.Batch(true, 0, 0, 0, false));
        assertThat(watermarks.row.lastEntryId()).as("an empty batch keeps the position")
                .isEqualTo(entries.get(4).journalEntryId());

        assertThat(transactions.born).hasSize(5);
        assertThat(transactions.born).containsExactlyElementsOf(
                entries.stream().map(MonitoredEntry::journalEntryId).toList());
    }

    @Test
    @DisplayName("a watermark another sweeper holds, or one not yet due, is an empty batch: nothing read, nothing moved")
    void anUnclaimedWatermarkIsSkipped() {
        ledger.plant(3);
        watermarks.claimable = false;
        assertThat(intake(10).sweepBatch(NO_CONNECTION, SOURCE)).isEqualTo(MonitoringIntake.Batch.SKIPPED);
        assertThat(ledger.reads).isZero();
        assertThat(watermarks.moves).isZero();
        assertThat(transactions.born).isEmpty();
    }

    @Test
    @DisplayName("a re-run from an earlier position reads the same entries and bears nothing new: born once, by the"
            + " store's absorption, and the watermark advances over them again")
    void aReRunBearsNothingNew() {
        List<MonitoredEntry> entries = ledger.plant(4);
        MonitoringIntake intake = intake(10);
        assertThat(intake.sweepBatch(NO_CONNECTION, SOURCE).born()).isEqualTo(4);

        watermarks.row = new MonitoringWatermark(SOURCE, MonitoringWatermark.FLOOR, 0, Instant.EPOCH);
        MonitoringIntake.Batch rerun = intake.sweepBatch(NO_CONNECTION, SOURCE);
        assertThat(rerun.read()).isEqualTo(4);
        assertThat(rerun.born()).isZero();
        assertThat(transactions.born).hasSize(4);
        assertThat(watermarks.row.lastEntryId()).isEqualTo(entries.get(3).journalEntryId());
    }

    @Test
    @DisplayName("an unknown namespace is counted UNCLASSIFIED and the batch goes on - nothing passes unseen")
    void unclassifiedCounted() {
        ledger.plant(2);
        ledger.plant("crypto-swap:" + UUID.randomUUID());
        ledger.plant(1);
        MonitoringIntake.Batch batch = intake(10).sweepBatch(NO_CONNECTION, SOURCE);
        assertThat(batch).isEqualTo(new MonitoringIntake.Batch(true, 4, 4, 1, false));
        assertThat(transactions.kinds).containsExactly(MonitoredKind.TRANSFER, MonitoredKind.TRANSFER,
                MonitoredKind.UNCLASSIFIED, MonitoredKind.TRANSFER);
    }

    @Test
    @DisplayName("a port that fails leaves the watermark where it stood and bears nothing")
    void aFailingPortMovesNothing() {
        ledger.plant(3);
        ledger.failing = true;
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> intake(10).sweepBatch(NO_CONNECTION, SOURCE));
        assertThat(watermarks.moves).isZero();
        assertThat(transactions.born).isEmpty();
    }

    @Test
    @DisplayName("a port serving an entry at or before the position, or out of order, is refused whole - the watermark"
            + " would otherwise advance over an entry")
    void aPortBreakingItsContractIsRefused() {
        List<MonitoredEntry> entries = ledger.plant(3);
        watermarks.row = new MonitoringWatermark(SOURCE, entries.get(1).journalEntryId(),
                entries.get(1).sequence(), Instant.EPOCH);
        ledger.ignoreThePosition = true;
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> intake(10).sweepBatch(NO_CONNECTION, SOURCE))
                .withMessageContaining("refused whole");
        assertThat(watermarks.moves).isZero();
        assertThat(transactions.born).isEmpty();

        ledger.ignoreThePosition = false;
        ledger.reversed = true;
        watermarks.row = new MonitoringWatermark(SOURCE, MonitoringWatermark.FLOOR, 0, Instant.EPOCH);
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> intake(10).sweepBatch(NO_CONNECTION, SOURCE));
        assertThat(watermarks.moves).isZero();
    }

    @Test
    @DisplayName("P13-TSK-020: the rule leg runs on the batch's rows after their inserts and before the advance; the"
            + " batch is cut before the entry whose customer accounts would pass the claim's bound - the rest due now")
    void theLegAndTheSubjectBound() {
        List<MonitoredEntry> entries = ledger.plant(5);
        MonitoringIntake bounded = new MonitoringIntake(ledger, watermarks, transactions, IDS, Duration.ofSeconds(60),
                10, Duration.ofSeconds(5), 3);
        List<String> order = new ArrayList<>();
        List<MonitoredTransaction> judged = new ArrayList<>();
        MonitoringIntake.Batch first = bounded.sweepBatch(NO_CONNECTION, SOURCE, (unitOfWork, rows) -> {
            order.add("leg after " + transactions.born.size() + " born, " + watermarks.moves + " moves");
            judged.addAll(rows);
        });
        assertThat(first).as("three entries, three accounts: the fourth would be a fourth account")
                .isEqualTo(new MonitoringIntake.Batch(true, 3, 3, 0, true));
        assertThat(order).containsExactly("leg after 3 born, 0 moves");
        assertThat(judged).extracting(MonitoredTransaction::journalEntryId).containsExactlyElementsOf(
                entries.subList(0, 3).stream().map(MonitoredEntry::journalEntryId).toList());
        assertThat(watermarks.row.lastEntryId()).isEqualTo(entries.get(2).journalEntryId());
        assertThat(watermarks.lastPermitDelay).as("cut: the rest is due now").isEqualTo(Duration.ZERO);

        MonitoringIntake.Batch second = bounded.sweepBatch(NO_CONNECTION, SOURCE, MonitoringIntake.BatchLeg.NONE);
        assertThat(second).isEqualTo(new MonitoringIntake.Batch(true, 2, 2, 0, false));
        assertThat(transactions.born).hasSize(5);

        assertThatExceptionOfType(IllegalArgumentException.class).as("one entry may name two accounts")
                .isThrownBy(() -> new MonitoringIntake(ledger, watermarks, transactions, IDS, Duration.ofSeconds(60),
                        10, Duration.ofSeconds(5), 1));
    }

    @Test
    @DisplayName("P13-TSK-020: a leg that throws fails the batch whole - the watermark where it stood")
    void aFailingLegMovesNothing() {
        ledger.plant(2);
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> intake(10).sweepBatch(NO_CONNECTION,
                SOURCE, (unitOfWork, rows) -> {
                    throw new IllegalStateException("the claim failed");
                }));
        assertThat(watermarks.moves).isZero();
    }

    @Test
    @DisplayName("uuid order is bytewise, PostgreSQL's - the floor sorts before every id, and ids by their millisecond")
    void uuidOrderIsBytewise() {
        UUID early = MonitoringWatermark.positionAt(1_000);
        UUID late = MonitoringWatermark.positionAt(2_000);
        assertThat(MonitoringIntake.compare(MonitoringWatermark.FLOOR, IDS.next())).isNegative();
        assertThat(MonitoringIntake.compare(early, late)).isNegative();
        assertThat(MonitoringIntake.compare(late, early)).isPositive();
        assertThat(MonitoringIntake.compare(early, early)).isZero();
        // A least-significant half with its top bit set against one without: signed compareTo would invert this.
        UUID highVariant = new UUID(1L, 0x8000_0000_0000_0000L);
        UUID lowVariant = new UUID(1L, 0x0000_0000_0000_0001L);
        assertThat(MonitoringIntake.compare(highVariant, lowVariant)).isPositive();
    }

    // ------------------------------------------------------------------ fakes

    private static final class FakeLedger implements MonitoredLedger {
        final List<MonitoredEntry> journal = new ArrayList<>();
        int reads;
        boolean failing;
        boolean ignoreThePosition;
        boolean reversed;

        List<MonitoredEntry> plant(int count) {
            List<MonitoredEntry> planted = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                planted.add(plant("transfer:" + UUID.randomUUID()));
            }
            return planted;
        }

        MonitoredEntry plant(String key) {
            UUID id = IDS.next();
            UUID owner = UUID.randomUUID();
            MonitoredEntry entry = new MonitoredEntry(id, MonitoringWatermark.sequenceOf(id),
                    MonitoredEntry.EntryType.POSTING, key, Optional.empty(), Instant.parse("2026-10-23T09:00:00Z"),
                    LocalDate.of(2026, 10, 23), List.of(
                            new MonitoredEntry.Line(UUID.randomUUID(), "CUSTOMER", Optional.of(owner), "CUSTOMER_WALLET",
                                    MonitoredEntry.Side.DEBIT, 10_00, 2, CurrencyCode.of("EUR")),
                            new MonitoredEntry.Line(UUID.randomUUID(), "OPERATIONAL", Optional.empty(), "CASH_AT_BANK",
                                    MonitoredEntry.Side.CREDIT, 10_00, 2, CurrencyCode.of("EUR"))));
            journal.add(entry);
            return entry;
        }

        @Override
        public List<MonitoredEntry> entriesAfter(Connection unitOfWork, UUID afterEntryId, Duration settleHorizon,
                int limit) {
            reads++;
            if (failing) {
                throw new IllegalStateException("the journal is unreadable");
            }
            List<MonitoredEntry> page = journal.stream()
                    .filter(entry -> ignoreThePosition || MonitoringIntake.compare(entry.journalEntryId(), afterEntryId) > 0)
                    .limit(limit)
                    .toList();
            return reversed ? page.reversed() : page;
        }

        @Override
        public long entriesBeyond(Connection unitOfWork, UUID afterEntryId, long cap) {
            return journal.stream().filter(e -> MonitoringIntake.compare(e.journalEntryId(), afterEntryId) > 0).count();
        }

        @Override
        public List<UUID> entryIds(Connection snapshot, UUID fromInclusive, UUID toExclusive) {
            return journal.stream().map(MonitoredEntry::journalEntryId)
                    .filter(id -> MonitoringIntake.compare(id, fromInclusive) >= 0
                            && MonitoringIntake.compare(id, toExclusive) < 0)
                    .toList();
        }
    }

    private static final class FakeWatermarks implements MonitoringWatermarkStore {
        MonitoringWatermark row = new MonitoringWatermark(SOURCE, MonitoringWatermark.FLOOR, 0, Instant.EPOCH);
        boolean claimable = true;
        int moves;
        Duration lastPermitDelay;

        @Override
        public Optional<MonitoringWatermark> claimDue(Connection unitOfWork, String source) {
            return claimable && source.equals(row.source()) ? Optional.of(row) : Optional.empty();
        }

        @Override
        public Optional<MonitoringWatermark> lock(Connection unitOfWork, String source) {
            return claimDue(unitOfWork, source);
        }

        @Override
        public Optional<MonitoringWatermark> read(Connection unitOfWork, String source) {
            return Optional.of(row);
        }

        @Override
        public void move(Connection unitOfWork, String source, UUID lastEntryId, Duration permitDelay) {
            moves++;
            lastPermitDelay = permitDelay;
            row = new MonitoringWatermark(source, lastEntryId, MonitoringWatermark.sequenceOf(lastEntryId),
                    Instant.EPOCH.plus(permitDelay));
        }

        @Override
        public boolean hint(Connection unitOfWork, String source) {
            return false;
        }
    }

    private static final class FakeTransactions implements MonitoredTransactionStore {
        final Set<UUID> born = new LinkedHashSet<>();
        final List<MonitoredKind> kinds = new ArrayList<>();

        @Override
        public int insertAbsent(Connection unitOfWork, List<MonitoredTransaction> rows) {
            int count = 0;
            for (MonitoredTransaction row : rows) {
                if (born.add(row.journalEntryId())) {
                    kinds.add(row.kind());
                    count++;
                }
            }
            return count;
        }

        @Override
        public long countUnclassified(Connection unitOfWork) {
            return kinds.stream().filter(MonitoredKind.UNCLASSIFIED::equals).count();
        }

        @Override
        public long count(Connection unitOfWork) {
            return born.size();
        }
    }
}
