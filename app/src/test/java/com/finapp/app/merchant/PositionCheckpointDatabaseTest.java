package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.CheckpointedPositionBreakdown;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JdbcPositionBreakdown;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.PositionBreakdown;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.merchant.MerchantPayable;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The merchant payable read's commit-order checkpoint on a real database (`P16-REM-001`; ADR-0133 decision 3): the cases its owner
 * decision requires before it merges - the split under concurrent posting, the racing writers, the version and the cluster refusals -
 * and the stamp, the role and the read-only transaction beside them.
 *
 * <p>Every comparison is made inside one {@code REPEATABLE READ} transaction, so the checkpointed read and the whole fold see one
 * snapshot and must agree exactly; a checkpoint stored by such a read takes that snapshot's {@code xmin} as its watermark, the same
 * argument as under {@code READ COMMITTED}, where the statement's snapshot is the one.
 */
@Tag("database")
@DisplayName("the merchant payable read's commit-order checkpoint on a real database (P16-REM-001)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class PositionCheckpointDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final List<AccountPurpose> PRECEDENCE =
            List.of(AccountPurpose.SETTLEMENT_CLEARING, AccountPurpose.INSTANT_CLEARING, AccountPurpose.PAYOUT_CLEARING);
    private static final int INSTANCES = 10;

    private final JdbcLedgerAccountStore accounts = new JdbcLedgerAccountStore();

    @Test
    @DisplayName("a line carries its entry's transaction, stamped whatever the writer offered")
    void aLineCarriesItsEntrysTransaction() throws Exception {
        LedgerAccount payable = payable();
        UUID entry = post(payable, Direction.CREDIT, 1_000);
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT count(*) FROM ledger.journal_line line JOIN ledger.journal_entry entry ON entry.id = line.entry_id"
                                + " WHERE line.entry_id = ? AND line.txid = entry.txid")) {
            select.setObject(1, entry);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                assertThat(row.getLong(1)).as("both lines of the entry").isEqualTo(2);
            }
        }
    }

    @Test
    @DisplayName("under ten instances posting and ten reading, every checkpointed read is the whole fold of its snapshot, and checkpoints"
            + " were stored")
    void theSplitIsTheWholeFoldUnderConcurrentPosting() throws Exception {
        LedgerAccount payable = payable();
        AtomicBoolean posting = new AtomicBoolean(true);
        AtomicInteger compared = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(INSTANCES * 2);
        try {
            List<Future<?>> posters = new ArrayList<>();
            for (int instance = 0; instance < INSTANCES; instance++) {
                posters.add(pool.submit(() -> {
                    for (int line = 0; line < 30; line++) {
                        boolean debit = ThreadLocalRandom.current().nextInt(4) == 0;
                        post(payable, debit ? Direction.DEBIT : Direction.CREDIT, 1 + ThreadLocalRandom.current().nextInt(debit ? 1_000 : 100_000));
                    }
                    return null;
                }));
            }
            List<Future<?>> readers = new ArrayList<>();
            for (int instance = 0; instance < INSTANCES; instance++) {
                readers.add(pool.submit(() -> {
                    CheckpointedPositionBreakdown checkpointed = new CheckpointedPositionBreakdown(5);
                    while (posting.get()) {
                        compareInOneSnapshot(checkpointed, payable);
                        compared.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> poster : posters) {
                poster.get();
            }
            posting.set(false);
            for (Future<?> reader : readers) {
                reader.get();
            }
        } finally {
            pool.shutdownNow();
        }
        compareInOneSnapshot(new CheckpointedPositionBreakdown(5), payable);
        assertThat(compared.get()).as("reads compared while posting").isGreaterThan(INSTANCES);
        assertThat(checkpoints(payable)).as("checkpoints stored along the way").isPositive();
        assertEveryCheckpointIsTheFoldBelowItsWatermark(payable);
    }

    @Test
    @DisplayName("ten instances racing past the threshold store one checkpoint per watermark, each the fold below its watermark")
    void racingWritersStoreOnePerWatermarkEachCorrect() throws Exception {
        LedgerAccount payable = payable();
        for (int line = 0; line < 20; line++) {
            post(payable, line % 3 == 2 ? Direction.DEBIT : Direction.CREDIT, 100 + line);
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(INSTANCES);
        try {
            List<Future<PositionBreakdown.Breakdown>> reads = new ArrayList<>();
            for (int instance = 0; instance < INSTANCES; instance++) {
                reads.add(pool.submit(() -> {
                    start.await();
                    try (Connection app = DatabaseRoles.application()) {
                        app.setAutoCommit(false);
                        PositionBreakdown.Breakdown read = new CheckpointedPositionBreakdown(5).breakdown(app, payable.id(), PRECEDENCE,
                                MerchantPayable.NAMESPACES);
                        app.commit();
                        return read;
                    }
                }));
            }
            start.countDown();
            PositionBreakdown.Breakdown whole = whole(payable);
            for (Future<PositionBreakdown.Breakdown> read : reads) {
                assertThat(read.get().position()).isEqualTo(whole.position());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(checkpoints(payable)).as("at most a few: one per watermark the racers reached").isBetween(1L, (long) INSTANCES);
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT count(*) - count(DISTINCT watermark) FROM ledger.position_checkpoint WHERE ledger_account_id = ?")) {
            select.setObject(1, payable.id().value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                assertThat(row.getLong(1)).as("no watermark stored twice").isZero();
            }
        }
        assertEveryCheckpointIsTheFoldBelowItsWatermark(payable);
    }

    @Test
    @DisplayName("a checkpoint is read only under this build's classification version and on its own cluster")
    void aCheckpointOfAnotherVersionOrClusterIsNeverRead() throws Exception {
        String cluster = cluster();

        LedgerAccount read = payable();
        post(read, Direction.CREDIT, 5_000);
        plantWrong(read, CheckpointedPositionBreakdown.CLASSIFICATION_VERSION, cluster);
        assertThat(checkpointed(read).position()).as("a valid checkpoint is read: the planted wrong one shows")
                .isNotEqualTo(whole(read).position());

        LedgerAccount restored = payable();
        post(restored, Direction.CREDIT, 5_000);
        plantWrong(restored, CheckpointedPositionBreakdown.CLASSIFICATION_VERSION, "a-cluster-restored-from");
        assertThat(checkpointed(restored).position()).as("another cluster's checkpoint is refused").isEqualTo(whole(restored).position());

        LedgerAccount retired = payable();
        post(retired, Direction.CREDIT, 5_000);
        plantWrong(retired, CheckpointedPositionBreakdown.CLASSIFICATION_VERSION + 1, cluster);
        assertThat(checkpointed(retired).position()).as("another version's checkpoint is retired").isEqualTo(whole(retired).position());
    }

    @Test
    @DisplayName("a read in a read-only transaction answers from the lines and stores nothing")
    void aReadOnlyReadAnswersAndStoresNothing() throws Exception {
        LedgerAccount payable = payable();
        for (int line = 0; line < 3; line++) {
            post(payable, Direction.CREDIT, 700 + line);
        }
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setReadOnly(true);
            PositionBreakdown.Breakdown read = new CheckpointedPositionBreakdown(1).breakdown(app, payable.id(), PRECEDENCE,
                    MerchantPayable.NAMESPACES);
            PositionBreakdown.Breakdown whole = new JdbcPositionBreakdown().breakdown(app, payable.id(), PRECEDENCE, MerchantPayable.NAMESPACES);
            app.commit();
            assertThat(read.position()).isEqualTo(whole.position());
        }
        assertThat(checkpoints(payable)).isZero();
    }

    @Test
    @DisplayName("the application role names its cluster, and can neither change nor remove a checkpoint")
    void theRoleReadsAndAddsOnly() throws Exception {
        assertThat(cluster()).isNotBlank();
        LedgerAccount payable = payable();
        post(payable, Direction.CREDIT, 5_000);
        plantWrong(payable, CheckpointedPositionBreakdown.CLASSIFICATION_VERSION, "any");
        for (String change : List.of("UPDATE ledger.position_checkpoint SET lines = 1 WHERE ledger_account_id = ?",
                "DELETE FROM ledger.position_checkpoint WHERE ledger_account_id = ?")) {
            try (Connection app = DatabaseRoles.application(); PreparedStatement statement = app.prepareStatement(change)) {
                statement.setObject(1, payable.id().value());
                assertThatThrownBy(statement::executeUpdate).isInstanceOf(SQLException.class)
                        .satisfies(refused -> assertThat(((SQLException) refused).getSQLState()).isEqualTo("42501"));
            }
        }
    }

    @Test
    @DisplayName("the census beside the source proofs names a payable whose checkpoint is wrong, on a read-only snapshot, and no other"
            + " (X-TSK-021)")
    void theCensusNamesAWrongCheckpoint() throws Exception {
        LedgerAccount wrong = payable();
        post(wrong, Direction.CREDIT, 5_000);
        plantWrong(wrong, CheckpointedPositionBreakdown.CLASSIFICATION_VERSION, cluster());
        LedgerAccount right = payable();
        post(right, Direction.CREDIT, 5_000);

        List<com.finapp.app.accounting.ProductSourceProofs.PayableTerms> terms;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            app.setReadOnly(true);
            terms = com.finapp.app.accounting.ProductSourceProofs.merchantPayables(
                    new com.finapp.app.accounting.PayableCheckpointCensus().payableReads()).apply(app);
            app.commit();
        }
        assertThat(terms).filteredOn(term -> term.merchant().equals(wrong.ownerRef().orElseThrow()))
                .singleElement().satisfies(term -> assertThat(term.checkpointAgrees()).as("the planted checkpoint is named").isFalse());
        assertThat(terms).filteredOn(term -> term.merchant().equals(right.ownerRef().orElseThrow()))
                .singleElement().satisfies(term -> assertThat(term.checkpointAgrees()).isTrue());
        assertThat(checkpoints(right)).as("the census stores nothing").isZero();
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private void compareInOneSnapshot(CheckpointedPositionBreakdown checkpointed, LedgerAccount payable) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            PositionBreakdown.Breakdown read = checkpointed.breakdown(app, payable.id(), PRECEDENCE, MerchantPayable.NAMESPACES);
            PositionBreakdown.Breakdown whole = new JdbcPositionBreakdown().breakdown(app, payable.id(), PRECEDENCE, MerchantPayable.NAMESPACES);
            app.commit();
            assertThat(read.position()).as("the position, one snapshot").isEqualTo(whole.position());
            assertThat(read.buckets()).as("the buckets, one snapshot").containsExactlyInAnyOrderElementsOf(whole.buckets());
        }
    }

    /** Each stored checkpoint's buckets, signed by side, against the lines below its watermark, counted and summed by the database. */
    private static void assertEveryCheckpointIsTheFoldBelowItsWatermark(LedgerAccount payable) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT c.lines,"
                                + " (SELECT count(*) FROM ledger.journal_line l WHERE l.ledger_account_id = c.ledger_account_id"
                                + "   AND l.txid < c.watermark) AS below,"
                                + " (SELECT coalesce(sum(CASE b.direction WHEN 'CREDIT' THEN b.amount_minor ELSE -b.amount_minor END), 0)"
                                + "    FROM ledger.position_checkpoint_bucket b WHERE b.checkpoint_id = c.id) AS stored,"
                                + " (SELECT coalesce(sum(CASE l.direction WHEN 'CREDIT' THEN l.amount_minor ELSE -l.amount_minor END), 0)"
                                + "    FROM ledger.journal_line l WHERE l.ledger_account_id = c.ledger_account_id AND l.txid < c.watermark)"
                                + "   AS folded"
                                + " FROM ledger.position_checkpoint c WHERE c.ledger_account_id = ?")) {
            select.setObject(1, payable.id().value());
            try (ResultSet row = select.executeQuery()) {
                int seen = 0;
                while (row.next()) {
                    seen++;
                    assertThat(row.getLong("lines")).as("the lines it counted are the lines below it").isEqualTo(row.getLong("below"));
                    assertThat(row.getLong("stored")).as("its buckets are the fold below it").isEqualTo(row.getLong("folded"));
                }
                assertThat(seen).isPositive();
            }
        }
    }

    private static long checkpoints(LedgerAccount payable) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT count(*) FROM ledger.position_checkpoint WHERE ledger_account_id = ?")) {
            select.setObject(1, payable.id().value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static String cluster() throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT system_identifier::text FROM pg_catalog.pg_control_system()");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getString(1);
        }
    }

    /** A checkpoint at the current watermark whose one bucket is a figure no line made: if it is read, the answer shows it. */
    private static void plantWrong(LedgerAccount payable, int version, String cluster) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID id;
            try (PreparedStatement insert = app.prepareStatement(
                    "INSERT INTO ledger.position_checkpoint (ledger_account_id, classification_version, precedence, namespaces,"
                            + " cluster_identity, watermark, lines)"
                            + " VALUES (?, ?, ?, ?, ?, pg_catalog.pg_snapshot_xmin(pg_catalog.pg_current_snapshot()), 1) RETURNING id")) {
                insert.setObject(1, payable.id().value());
                insert.setInt(2, version);
                insert.setArray(3, app.createArrayOf("text", PRECEDENCE.stream().map(AccountPurpose::name).toArray()));
                insert.setArray(4, app.createArrayOf("text", MerchantPayable.NAMESPACES.toArray()));
                insert.setString(5, cluster);
                try (ResultSet row = insert.executeQuery()) {
                    row.next();
                    id = row.getObject(1, UUID.class);
                }
            }
            try (PreparedStatement insert = app.prepareStatement(
                    "INSERT INTO ledger.position_checkpoint_bucket (checkpoint_id, bucket, direction, counterparty, reconciliation_attributed,"
                            + " accounting_origin, namespace, amount_minor, currency, scale)"
                            + " VALUES (?, 'CREDIT||NONE', 'CREDIT', NULL, false, false, NULL, 999999, 'EUR', 2)")) {
                insert.setObject(1, id);
                insert.executeUpdate();
            }
            app.commit();
        }
    }

    private PositionBreakdown.Breakdown checkpointed(LedgerAccount payable) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            PositionBreakdown.Breakdown read = new CheckpointedPositionBreakdown().breakdown(app, payable.id(), PRECEDENCE, MerchantPayable.NAMESPACES);
            app.commit();
            return read;
        }
    }

    private static PositionBreakdown.Breakdown whole(LedgerAccount payable) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return new JdbcPositionBreakdown().breakdown(app, payable.id(), PRECEDENCE, MerchantPayable.NAMESPACES);
        }
    }

    /** A fresh merchant payable, opened with a credit no fixture's debits can exceed, so no floor refuses one. */
    private LedgerAccount payable() throws SQLException {
        LedgerAccount account;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            account = accounts.createOrConverge(app, LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY, AccountPurpose.MERCHANT_PAYABLE,
                    EUR, IDS.next())).account();
            app.commit();
        }
        post(account, Direction.CREDIT, 10_000_000);
        return account;
    }

    /** One balanced entry through the ledger's own door: the payable one side, the settlement clearing the other. */
    private UUID post(LedgerAccount payable, Direction payableSide, long minor) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            LedgerAccount clearing = accounts.findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, EUR).orElseThrow();
            Direction clearingSide = payableSide == Direction.CREDIT ? Direction.DEBIT : Direction.CREDIT;
            LocalDate day = LocalDate.now(CLOCK.withZone(ZoneOffset.UTC));
            PostingCommand command = new PostingCommand("p16-rem-001:" + IDS.next(), day, day, "p16-rem-001-fixture", List.of(
                    new JournalLine(payable.id(), payableSide, Money.ofMinorUnits(minor, EUR)),
                    new JournalLine(clearing.id(), clearingSide, Money.ofMinorUnits(minor, EUR))));
            UUID entry = posting().post(app, command).entryId().value();
            app.commit();
            return entry;
        }
    }

    private static PostingService posting() {
        return new PostingService(new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1), Duration.ofMinutes(5)),
                new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(), new JdbcBalanceProjection(), IDS, CLOCK,
                PostingObserver.NONE);
    }
}
