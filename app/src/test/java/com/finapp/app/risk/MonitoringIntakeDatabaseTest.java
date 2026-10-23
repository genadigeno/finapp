package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.JdbcMonitoredTransactionStore;
import com.finapp.risk.JdbcMonitoringWatermarkStore;
import com.finapp.risk.MonitoredKind;
import com.finapp.risk.MonitoredLedger;
import com.finapp.risk.MonitoringIntake;
import com.finapp.risk.MonitoringReprocessing;
import com.finapp.risk.MonitoringWatermark;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The monitoring intake over the real journal (`P13-TSK-019`; {@code PHASE_13_PLAN.md} section 7.4's two rows and
 * section 14; {@code INV-RSK-09}; the balance derivation suite's idiom - no Spring context, the stores composed by
 * hand on the application role): real postings through {@code PostingService}, read back through
 * {@link LedgerMonitoredLedger}, ingested by {@link MonitoringIntake} in transactions of this suite's own making.
 *
 * <p>Written and owed under C5 (the standing criterion-7 deviation): the database tier is not run per task.
 */
@Tag("database")
@DisplayName("the monitoring intake over the real journal (P13-TSK-019)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class MonitoringIntakeDatabaseTest {

    private static final int INSTANCES = 10;
    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Actor SYSTEM = new Actor("platform", ActorType.SYSTEM);
    private static final Actor OFFICER = new Actor("risk-officer-019", ActorType.EMPLOYEE);
    private static final String SOURCE = MonitoringWatermark.LEDGER_JOURNAL;

    private final LedgerAccountStore<Connection> accounts = new JdbcLedgerAccountStore();
    private final JournalEntryStore<Connection> journal = new JdbcJournalEntryStore(IDS);
    private final MonitoredLedger ledger = new LedgerMonitoredLedger(journal, accounts);
    private final JdbcMonitoringWatermarkStore watermarks = new JdbcMonitoringWatermarkStore();
    private final JdbcMonitoredTransactionStore monitored = new JdbcMonitoredTransactionStore();
    private final MonitoringReprocessing reprocessing =
            new MonitoringReprocessing(watermarks, new JdbcAuditWriter(), IDS, CLOCK);

    /** The suite's own entries, so the census and the counts are judged against what this suite posted. */
    private static final List<UUID> POSTED = new ArrayList<>();

    @Test
    @Order(1)
    @DisplayName("ten sweepers over one watermark ingest every entry once: the locked row skipped by nine, the unique"
            + " the arbiter of any overlap - each entry one row, classified by its namespace")
    void tenSweepersIngestOnce() throws Exception {
        resetToTheFloor();
        List<UUID> entries = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            entries.add(postTransfer(100_00 + i));
        }
        // Small batches, so the ten sweepers contend for the watermark many times over the suite's entries - and over
        // whatever the shared test database's journal held before them, read from the floor.
        MonitoringIntake intake = intake(50);

        List<Callable<Integer>> sweepers = new ArrayList<>();
        for (int i = 0; i < INSTANCES; i++) {
            sweepers.add(() -> drain(intake));
        }
        List<Integer> born = atOnce(sweepers);

        assertThat(count("SELECT count(*) FROM risk.monitored_transaction WHERE journal_entry_id = ANY (?)", entries))
                .as("every entry exactly once, whoever ingested it").isEqualTo(entries.size());
        assertThat(born.stream().mapToInt(Integer::intValue).sum())
                .as("the sweepers between them bore each of the suite's entries once, plus whatever the journal held"
                        + " before them")
                .isGreaterThanOrEqualTo(entries.size());
        assertThat(column("SELECT DISTINCT kind FROM risk.monitored_transaction WHERE journal_entry_id = ANY (?)",
                entries)).containsExactly(MonitoredKind.TRANSFER.name());
        assertThat(column("SELECT DISTINCT direction || ':' || subject_kind FROM risk.monitored_transaction"
                + " WHERE journal_entry_id = ANY (?)", entries)).containsExactly("OUT:ACCOUNT");
        try (Connection app = DatabaseRoles.application()) {
            MonitoringWatermark watermark = watermarks.read(app, SOURCE).orElseThrow();
            assertThat(MonitoringWatermark.sequenceOf(watermark.lastEntryId()))
                    .as("the watermark stands at or after the last entry").isGreaterThanOrEqualTo(
                            MonitoringWatermark.sequenceOf(entries.getLast()));
        }
    }

    @Test
    @Order(2)
    @DisplayName("reprocessing from the floor re-reads the whole journal and creates nothing new - born once; the order"
            + " is audited risk.ReprocessingOrdered with the reason and a summary of the positions")
    void reprocessingCreatesNothingNew() throws Exception {
        long before = count("SELECT count(*) FROM risk.monitored_transaction", null);
        assertThat(before).isGreaterThan(0);

        MonitoringReprocessing.Ordered ordered;
        CorrelationId correlation = CorrelationId.generate(IDS);
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(correlation))) {
            ordered = inTransaction(uow -> reprocessing.order(uow, SOURCE, 0, OFFICER,
                    "P13-TSK-019: scenario 8 - the whole journal re-read", correlation));
        }
        assertThat(ordered.fromSequence()).isZero();
        assertThat(ordered.previousSequence()).isGreaterThan(0);

        MonitoringIntake intake = intake(50);
        List<Callable<Integer>> sweepers = new ArrayList<>();
        for (int i = 0; i < INSTANCES; i++) {
            sweepers.add(() -> drain(intake));
        }
        List<Integer> born = atOnce(sweepers);
        assertThat(born.stream().mapToInt(Integer::intValue).sum()).as("nothing new").isZero();
        assertThat(count("SELECT count(*) FROM risk.monitored_transaction", null)).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.ReprocessingOrdered'"
                + " AND outcome = 'SUCCEEDED' AND target_type = 'monitoring_watermark' AND target_id = 'ledger.journal'"
                + " AND reason LIKE 'P13-TSK-019: scenario 8%'"
                + " AND change_summary = 'monitoring reprocessing ordered from sequence 0; the watermark stood at "
                + ordered.previousSequence() + "'", null)).isEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("the census: every journal entry up to the watermark has exactly one monitored transaction, no monitored"
            + " transaction lacks its entry, and the suite's amounts are the entries' (F8)")
    void aJournalCensusMatches() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            MonitoringWatermark watermark = watermarks.read(app, SOURCE).orElseThrow();
            try (PreparedStatement missing = app.prepareStatement("SELECT count(*) FROM ledger.journal_entry e"
                    + " WHERE e.id <= ? AND NOT EXISTS (SELECT 1 FROM risk.monitored_transaction m"
                    + " WHERE m.journal_entry_id = e.id)")) {
                missing.setObject(1, watermark.lastEntryId());
                try (ResultSet row = missing.executeQuery()) {
                    row.next();
                    assertThat(row.getLong(1)).as("an entry the monitor never saw").isZero();
                }
            }
        }
        assertThat(count("SELECT count(*) FROM risk.monitored_transaction m"
                + " WHERE NOT EXISTS (SELECT 1 FROM ledger.journal_entry e WHERE e.id = m.journal_entry_id)", null))
                .as("a monitored transaction without its entry").isZero();
        assertThat(count("SELECT count(*) FROM risk.monitored_transaction m JOIN ledger.journal_line l"
                + " ON l.entry_id = m.journal_entry_id AND l.direction = 'DEBIT'"
                + " WHERE m.journal_entry_id = ANY (?) AND m.amount_minor <> l.amount_minor", POSTED))
                .as("the suite's transfers carry the debited amount").isZero();
    }

    @Test
    @Order(4)
    @DisplayName("a crash mid-batch - the transaction rolled back after the rows and the advance - leaves the committed"
            + " watermark, and the next claim re-reads from it")
    void aCrashMidClaimLapsesAndResumes() throws Exception {
        UUID entry = postTransfer(777_00);
        MonitoringIntake intake = intake(50);
        MonitoringWatermark standing;
        try (Connection app = DatabaseRoles.application()) {
            standing = watermarks.read(app, SOURCE).orElseThrow();
        }

        // The crash: everything the batch did, undone - as a killed instance's transaction is.
        try (Connection crashing = DatabaseRoles.application(); CorrelationContext.Scope flow = CorrelationContext.enter(
                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enter(SYSTEM)) {
            crashing.setAutoCommit(false);
            MonitoringIntake.Batch batch = intake.sweepBatch(crashing, SOURCE);
            assertThat(batch.claimed()).isTrue();
            assertThat(batch.born()).isGreaterThanOrEqualTo(1);
            crashing.rollback();
        }
        try (Connection app = DatabaseRoles.application()) {
            assertThat(watermarks.read(app, SOURCE).orElseThrow().lastEntryId())
                    .as("nothing advanced").isEqualTo(standing.lastEntryId());
        }
        assertThat(count("SELECT count(*) FROM risk.monitored_transaction WHERE journal_entry_id = ?", List.of(entry)))
                .isZero();

        assertThat(drain(intake)).isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.monitored_transaction WHERE journal_entry_id = ?", List.of(entry)))
                .isEqualTo(1);
    }

    @Test
    @Order(5)
    @DisplayName("an entry under a namespace the register does not know is recorded UNCLASSIFIED with what it saw, the"
            + " sweep goes on past it, and the unclassified count rises - nothing passes unseen")
    void anUnknownNamespaceIsUnclassified() throws Exception {
        long unclassifiedBefore;
        try (Connection app = DatabaseRoles.application()) {
            unclassifiedBefore = monitored.countUnclassified(app);
        }
        UUID planted = post("crypto-swap:" + IDS.next(), 50_00);
        UUID after = postTransfer(60_00);
        drain(intake(50));

        assertThat(column("SELECT kind || ':' || posting_namespace FROM risk.monitored_transaction"
                + " WHERE journal_entry_id = ?", List.of(planted))).containsExactly("UNCLASSIFIED:crypto-swap:");
        assertThat(count("SELECT count(*) FROM risk.monitored_transaction WHERE journal_entry_id = ?", List.of(after)))
                .as("the sweep went on past it").isEqualTo(1);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(monitored.countUnclassified(app)).isEqualTo(unclassifiedBefore + 1);
        }
    }

    @Test
    @Order(6)
    @DisplayName("the hint pulls a later permit to now in one statement and skips a row a sweep holds")
    void theHint() throws Exception {
        try (Connection holder = DatabaseRoles.application(); Connection hinter = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            hinter.setAutoCommit(false);
            // A permit an hour away.
            watermarks.move(holder, SOURCE, watermarks.lock(holder, SOURCE).orElseThrow().lastEntryId(),
                    Duration.ofHours(1));
            holder.commit();
            assertThat(watermarks.hint(hinter, SOURCE)).as("pulled to now").isTrue();
            hinter.commit();
            assertThat(watermarks.hint(hinter, SOURCE)).as("already due: nothing to pull").isFalse();
            hinter.rollback();

            MonitoringWatermark held = watermarks.claimDue(holder, SOURCE).orElseThrow();
            watermarks.move(holder, SOURCE, held.lastEntryId(), Duration.ofHours(1));
            assertThat(watermarks.hint(hinter, SOURCE)).as("the row a sweep holds is skipped").isFalse();
            hinter.rollback();
            holder.rollback();
        }
    }

    // ------------------------------------------------------------------ fixtures

    private MonitoringIntake intake(int batch) {
        return new MonitoringIntake(ledger, watermarks, monitored, IDS, Duration.ZERO, batch, Duration.ofMillis(1));
    }

    /**
     * Batches until one comes back empty; how many rows this sweeper bore. The cap bounds a shared test database whose
     * journal other suites filled: two thousand claims of fifty is a hundred thousand entries.
     */
    private int drain(MonitoringIntake intake) {
        int born = 0;
        try (SecurityContext.Scope actor = SecurityContext.enter(SYSTEM)) {
            for (int i = 0; i < 2_000; i++) {
                MonitoringIntake.Batch batch = inTransaction(uow -> intake.sweepBatch(uow, SOURCE));
                if (!batch.claimed()) {
                    // Another sweeper holds it, or the idle permit (a millisecond) has not yet come: ask again shortly.
                    sleep();
                    continue;
                }
                born += batch.born();
                if (batch.read() == 0) {
                    return born;
                }
            }
        }
        return born;
    }

    private static void sleep() {
        try {
            Thread.sleep(2);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void resetToTheFloor() {
        inTransaction(uow -> {
            watermarks.lock(uow, SOURCE).orElseThrow();
            watermarks.move(uow, SOURCE, MonitoringWatermark.FLOOR, Duration.ZERO);
            return null;
        });
    }

    private UUID postTransfer(long minor) {
        return post("transfer:" + IDS.next(), minor);
    }

    /** One transfer-shaped entry: a customer wallet debited, another credited, under {@code key}. */
    private UUID post(String key, long minor) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(
                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enter(SYSTEM)) {
            UUID id = inTransaction(uow -> {
                LedgerAccount from = wallet(uow);
                LedgerAccount to = wallet(uow);
                PostingResult result = service().post(uow, new PostingCommand(key, LocalDate.now(CLOCK),
                        LocalDate.now(CLOCK), "probe-event", List.of(
                                new JournalLine(from.id(), Direction.DEBIT, Money.ofMinorUnits(minor, EUR)),
                                new JournalLine(to.id(), Direction.CREDIT, Money.ofMinorUnits(minor, EUR)))));
                return result.entryId().value();
            });
            POSTED.add(id);
            return id;
        }
    }

    private LedgerAccount wallet(Connection app) {
        return accounts.createOrConverge(app, LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY,
                AccountPurpose.CUSTOMER_WALLET, EUR, IDS.next())).account();
    }

    private PostingService service() {
        return new PostingService(
                new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1),
                        Duration.ofMinutes(5)),
                journal, new JdbcAuditWriter(), new JdbcOutboxWriter(),
                new com.finapp.ledger.JdbcBalanceProjection(), IDS, CLOCK, PostingObserver.NONE);
    }

    private static <R> R inTransaction(Function<Connection, R> work) {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                R result = work.apply(app);
                app.commit();
                return result;
            } catch (RuntimeException failure) {
                app.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static long count(String sql, List<UUID> ids) throws SQLException {
        try (Connection app = DatabaseRoles.application(); PreparedStatement select = app.prepareStatement(sql)) {
            if (ids != null) {
                if (sql.contains("ANY")) {
                    select.setArray(1, app.createArrayOf("uuid", ids.toArray()));
                } else {
                    select.setObject(1, ids.getFirst());
                }
            }
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static List<String> column(String sql, List<UUID> ids) throws SQLException {
        try (Connection app = DatabaseRoles.application(); PreparedStatement select = app.prepareStatement(sql)) {
            if (sql.contains("ANY")) {
                select.setArray(1, app.createArrayOf("uuid", ids.toArray()));
            } else {
                select.setObject(1, ids.getFirst());
            }
            List<String> values = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    values.add(row.getString(1));
                }
            }
            return values;
        }
    }

    private static <T> List<T> atOnce(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
