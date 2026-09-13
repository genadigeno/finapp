package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalEntry;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.JournalEntryType;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingAttribution;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CausationId;
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
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The journal against a live PostgreSQL (`P3-TSK-005`): balanced by constraint at COMMIT for a
 * writer that never touches the domain, immutable at the privilege level for the application
 * and by trigger for everyone else, and exact at the extremes ({@code INV-MON-05}).
 *
 * <p>Run as the <strong>application role</strong> except where the probe is explicitly about a
 * writer the grants do not bind.
 */
@Tag("database")
@DisplayName("the journal tables under direct SQL and contention (P3-TSK-005)")
class JournalPostingDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate POSTING = LocalDate.of(2026, 9, 13);

    /** PostgreSQL SQLStates: check_violation, insufficient_privilege. */
    private static final String CHECK_VIOLATION = "23514";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private final LedgerAccountStore<Connection> accounts = new JdbcLedgerAccountStore();
    private final JournalEntryStore<Connection> journal = new JdbcJournalEntryStore(IDS);

    @Test
    @DisplayName("an entry round-trips exactly, BIGINT extremes and all three scales included")
    void anEntryRoundTripsExactly() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount a = account(app, USD);
            LedgerAccount b = account(app, USD);
            LedgerAccount jpyA = account(app, CurrencyCode.of("JPY"));
            LedgerAccount jpyB = account(app, CurrencyCode.of("JPY"));
            LedgerAccount bhdA = account(app, CurrencyCode.of("BHD"));
            LedgerAccount bhdB = account(app, CurrencyCode.of("BHD"));

            // The representable extremes, per scale: a stored amount must read back as the
            // amount it was (INV-MON-05), and Long.MAX_VALUE is where a narrowing would show.
            JournalEntry entry =
                    JournalEntry.balanced(
                            IDS, CLOCK, POSTING, POSTING,
                            List.of(
                                    new JournalLine(a.id(), Direction.DEBIT,
                                            Money.ofMinorUnits(Long.MAX_VALUE, USD)),
                                    new JournalLine(b.id(), Direction.CREDIT,
                                            Money.ofMinorUnits(Long.MAX_VALUE, USD)),
                                    new JournalLine(jpyA.id(), Direction.DEBIT,
                                            Money.ofMinorUnits(1, CurrencyCode.of("JPY"))),
                                    new JournalLine(jpyB.id(), Direction.CREDIT,
                                            Money.ofMinorUnits(1, CurrencyCode.of("JPY"))),
                                    new JournalLine(bhdA.id(), Direction.DEBIT,
                                            Money.ofMinorUnits(123456789, CurrencyCode.of("BHD"))),
                                    new JournalLine(bhdB.id(), Direction.CREDIT,
                                            Money.ofMinorUnits(123456789, CurrencyCode.of("BHD")))));
            PostingAttribution attribution = attribution(JournalEntryType.POSTING, null);
            journal.append(app, entry, attribution);
            app.commit();

            Optional<JournalEntryStore.PostedEntry> read = journal.findById(app, entry.id());
            assertThat(read).isPresent();
            assertThat(read.get().entry().lines())
                    .as("every line reads back as written - amount, currency, scale, order")
                    .containsExactlyElementsOf(entry.lines());
            assertThat(read.get().entry().postingDate()).isEqualTo(POSTING);
            assertThat(read.get().entry().createdAt())
                    // timestamptz stores microseconds and the driver ROUNDS the nanos rather
                    // than truncating them - found by this assertion first expecting
                    // truncatedTo(MICROS) and failing by exactly one microsecond. Within the
                    // column's own resolution is the claim the storage actually makes.
                    .as("created_at survives to the column's microsecond resolution")
                    .isCloseTo(entry.createdAt(), within(1, ChronoUnit.MICROS));
            assertThat(read.get().attribution().reference())
                    .isEqualTo(attribution.reference());
            assertThat(read.get().attribution().actorId()).isEqualTo(attribution.actorId());
            assertThat(read.get().attribution().idempotencyScope())
                    .isEqualTo(attribution.idempotencyScope());
        }
    }

    @Test
    @DisplayName("a stored scale reads back as stored, not as the currency's current one")
    void aStoredScaleReadsBackAsStored() throws Exception {
        // INV-MON-05's sharp half, the P0-TSK-038 finding one layer up: every line the store
        // WRITES is at the currency's current scale, so a rehydrate that re-derived scale
        // from the currency would pass every other test here. The row arrives by raw SQL at
        // scale 3 in USD - the shape only history can produce, and exactly the one a
        // currency's minor-unit change would leave behind.
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount a = account(app, USD);
            LedgerAccount b = account(app, USD);
            app.commit();

            UUID entryId = insertEntryRow(app);
            insertLineRow(app, entryId, a.id().value(), "DEBIT", 1500, 3, 0);
            insertLineRow(app, entryId, b.id().value(), "CREDIT", 1500, 3, 1);
            app.commit();

            Optional<JournalEntryStore.PostedEntry> read =
                    journal.findById(app, com.finapp.ledger.JournalEntryId.of(entryId));
            assertThat(read).isPresent();
            for (JournalLine line : read.get().entry().lines()) {
                assertThat(line.amount().scale())
                        .as("the stored scale is the amount's meaning (INV-MON-05)")
                        .isEqualTo(3);
                assertThat(line.amount().minorUnits()).isEqualTo(1500);
            }
        }
    }

    @Test
    @DisplayName("ten instances posting to one account all succeed: inserts contend on nothing")
    void tenConcurrentPostingsAllSucceed() throws Exception {
        LedgerAccount a;
        LedgerAccount b;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            a = account(app, USD);
            b = account(app, USD);
            app.commit();
        }
        int instances = 10;
        CyclicBarrier start = new CyclicBarrier(instances);
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        List<JournalEntryId0> results = new ArrayList<>();
        try {
            List<Callable<JournalEntryId0>> racers = new ArrayList<>();
            for (int i = 0; i < instances; i++) {
                long amount = 100 + i;
                racers.add(
                        () -> {
                            try (Connection app = DatabaseRoles.application()) {
                                app.setAutoCommit(false);
                                start.await();
                                JournalEntry entry =
                                        JournalEntry.balanced(
                                                IDS, CLOCK, POSTING, POSTING,
                                                List.of(
                                                        new JournalLine(a.id(), Direction.DEBIT,
                                                                Money.ofMinorUnits(amount, USD)),
                                                        new JournalLine(b.id(), Direction.CREDIT,
                                                                Money.ofMinorUnits(amount, USD))));
                                journal.append(app, entry, attribution(JournalEntryType.POSTING, null));
                                app.commit();
                                return new JournalEntryId0(entry.id().value());
                            }
                        });
            }
            for (Future<JournalEntryId0> outcome : pool.invokeAll(racers)) {
                results.add(outcome.get());
            }
        } finally {
            pool.shutdownNow();
        }

        // ADR-0039's claim under real contention: postings are inserts, the account FK takes
        // FOR KEY SHARE (share-compatible), and NOBODY loses - ten entries, twenty lines,
        // nothing serialised, nothing lost because nothing was updated.
        assertThat(results.stream().map(JournalEntryId0::value).distinct()).hasSize(instances);
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count =
                        app.prepareStatement(
                                "SELECT count(*) FROM ledger.journal_line"
                                        + " WHERE ledger_account_id = ?")) {
            count.setObject(1, a.id().value());
            try (ResultSet row = count.executeQuery()) {
                row.next();
                assertThat(row.getLong(1)).isEqualTo(instances);
            }
        }
    }

    /** A local carrier so the racers' generic future type stays readable. */
    private record JournalEntryId0(UUID value) {}

    @Test
    @DisplayName("a direct unbalanced INSERT is refused at COMMIT, not at the statement")
    void aDirectUnbalancedInsertIsRefusedAtCommit() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount a = account(app, USD);
            LedgerAccount b = account(app, USD);
            app.commit();

            // Raw SQL, never the domain - the writer the constraint exists for. Every
            // statement SUCCEEDS; the deferred trigger refuses the transaction at commit,
            // which is the only moment the entry is whole enough to judge.
            UUID entryId = insertEntryRow(app);
            insertLineRow(app, entryId, a.id().value(), "DEBIT", 100, 2, 0);
            insertLineRow(app, entryId, b.id().value(), "CREDIT", 90, 2, 1);
            assertThatThrownBy(app::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("INV-LED-01")
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);
            app.rollback();
        }
    }

    @Test
    @DisplayName("an entry with no lines is refused at COMMIT: the vacuous balance, at the schema")
    void anEntryWithoutLinesIsRefusedAtCommit() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            // Zero lines balance vacuously in every currency, and the line trigger never
            // fires - P3-TSK-004's finding, which is exactly why a second trigger anchors to
            // the ENTRY row.
            insertEntryRow(app);
            assertThatThrownBy(app::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("INV-LED-02")
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);
            app.rollback();

            // And one line - the single-sided posting - is refused too.
            LedgerAccount a = account(app, USD);
            app.commit();
            UUID entryId = insertEntryRow(app);
            insertLineRow(app, entryId, a.id().value(), "DEBIT", 100, 2, 0);
            assertThatThrownBy(app::commit)
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);
            app.rollback();
        }
    }

    @Test
    @DisplayName("equal sums at mixed scales are refused: minor units across scales are meaningless")
    void mixedScalesAreRefusedEvenWhenSumsAgree() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount a = account(app, USD);
            LedgerAccount b = account(app, USD);
            app.commit();

            // 1500 at scale 2 vs 1500 at scale 3: raw sums EQUAL, values 15.00 and 1.500 -
            // the one shape only the scales clause catches, so this is its probe.
            UUID entryId = insertEntryRow(app);
            insertLineRow(app, entryId, a.id().value(), "DEBIT", 1500, 2, 0);
            insertLineRow(app, entryId, b.id().value(), "CREDIT", 1500, 3, 1);
            assertThatThrownBy(app::commit)
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);
            app.rollback();
        }
    }

    @Test
    @DisplayName("UPDATE is denied on every column of both tables, and DELETE and TRUNCATE outright")
    void theColumnSweep() throws Exception {
        // The P0-TST-007 idiom: the column lists from information_schema, so a column added
        // later is swept without anyone remembering - and the grants hold with NO update
        // grant at all, so every probe answers insufficient_privilege before any trigger.
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            for (String table : List.of("journal_entry", "journal_line")) {
                for (String column : columnsOf(app, table)) {
                    assertThatThrownBy(
                                    () -> {
                                        try (PreparedStatement update =
                                                app.prepareStatement(
                                                        "UPDATE ledger." + table + " SET "
                                                                + column + " = " + column)) {
                                            update.executeUpdate();
                                        }
                                    })
                            .as("UPDATE %s.%s must be insufficient_privilege", table, column)
                            .isInstanceOf(SQLException.class)
                            .extracting(failure -> ((SQLException) failure).getSQLState())
                            .isEqualTo(INSUFFICIENT_PRIVILEGE);
                    app.rollback();
                }
                for (String statement :
                        List.of("DELETE FROM ledger." + table, "TRUNCATE ledger." + table)) {
                    assertThatThrownBy(
                                    () -> {
                                        try (Statement sql = app.createStatement()) {
                                            sql.execute(statement);
                                        }
                                    })
                            .as("%s must be insufficient_privilege", statement)
                            .isInstanceOf(SQLException.class)
                            .extracting(failure -> ((SQLException) failure).getSQLState())
                            .isEqualTo(INSUFFICIENT_PRIVILEGE);
                    app.rollback();
                }
            }
        }
    }

    @Test
    @DisplayName("the append-only trigger binds even the role the grants cannot: nothing edits history")
    void theAppendOnlyTriggerBindsTheMigrator() throws Exception {
        LedgerAccount a;
        LedgerAccount b;
        JournalEntry entry;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            a = account(app, USD);
            b = account(app, USD);
            entry =
                    JournalEntry.balanced(
                            IDS, CLOCK, POSTING, POSTING,
                            List.of(
                                    new JournalLine(a.id(), Direction.DEBIT,
                                            Money.ofMinorUnits(100, USD)),
                                    new JournalLine(b.id(), Direction.CREDIT,
                                            Money.ofMinorUnits(100, USD))));
            journal.append(app, entry, attribution(JournalEntryType.POSTING, null));
            app.commit();
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            for (String statement :
                    List.of(
                            "UPDATE ledger.journal_entry SET reference = 'rewritten' WHERE id = '"
                                    + entry.id().value() + "'",
                            "DELETE FROM ledger.journal_line WHERE entry_id = '"
                                    + entry.id().value() + "'")) {
                assertThatThrownBy(
                                () -> {
                                    try (Statement sql = migrator.createStatement()) {
                                        sql.execute(statement);
                                    }
                                })
                        .as("the trigger refuses the table's own owner: %s", statement)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("INV-HIST-01")
                        .extracting(failure -> ((SQLException) failure).getSQLState())
                        .isEqualTo(CHECK_VIOLATION);
                migrator.rollback();
            }
        }
    }

    // ------------------------------------------------------------------ fixtures

    private LedgerAccount account(Connection app, CurrencyCode currency) {
        return accounts
                .createOrConverge(
                        app,
                        LedgerAccount.owned(
                                IDS, CLOCK, AccountType.LIABILITY,
                                AccountPurpose.CUSTOMER_WALLET, currency, IDS.next()))
                .account();
    }

    private static PostingAttribution attribution(JournalEntryType type, String reason) {
        return new PostingAttribution(
                type,
                "probe:economic-event",
                Optional.ofNullable(reason),
                "system",
                Correlation.startingWith(CorrelationId.generate(IDS))
                        .causing(CausationId.generate(IDS)),
                "probe:scope:" + IDS.next());
    }

    private static UUID insertEntryRow(Connection connection) throws SQLException {
        UUID id = IDS.next();
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO ledger.journal_entry (id, posting_date, value_date,"
                                + " entry_type, reference, reason, actor_id, correlation_id,"
                                + " causation_id, idempotency_scope, created_at) VALUES"
                                + " (?, '2026-09-13', '2026-09-13', 'POSTING', 'probe:raw',"
                                + " NULL, 'system', 'probe-correlation', 'probe-causation',"
                                + " ?, now())")) {
            insert.setObject(1, id);
            insert.setString(2, "probe:scope:" + id);
            insert.executeUpdate();
        }
        return id;
    }

    private static void insertLineRow(
            Connection connection, UUID entryId, UUID accountId, String direction, long amount,
            int scale, int seq) throws SQLException {
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO ledger.journal_line (id, entry_id, ledger_account_id,"
                                + " direction, amount_minor, currency, scale, seq) VALUES"
                                + " (?, ?, ?, ?, ?, 'USD', ?, ?)")) {
            insert.setObject(1, IDS.next());
            insert.setObject(2, entryId);
            insert.setObject(3, accountId);
            insert.setString(4, direction);
            insert.setLong(5, amount);
            insert.setInt(6, scale);
            insert.setInt(7, seq);
            insert.executeUpdate();
        }
    }

    private static List<String> columnsOf(Connection connection, String table)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_schema = 'ledger' AND table_name = ?")) {
            select.setString(1, table);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    columns.add(row.getString("column_name"));
                }
            }
        }
        assertThat(columns).as("the sweep can see %s's columns", table).isNotEmpty();
        return columns;
    }
}
