package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
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
 * The chart of accounts against a live PostgreSQL (`P3-TSK-002`): the one-per-owner arbiter
 * under real contention, and {@code INV-LED-06}'s freeze proven per column, per role, per
 * layer.
 *
 * <p>Run as the <strong>application role</strong> except where a probe is explicitly about a
 * writer the grant does not bind — the migrator — because the trigger exists precisely for
 * writers the grant cannot see (the {@code P0-TST-007} lesson: a privilege check and a trigger
 * are blind in different directions).
 */
@Tag("database")
@DisplayName("the ledger_account table under contention and mutation (P3-TSK-002)")
class LedgerAccountDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");

    /** PostgreSQL SQLStates: check_violation, insufficient_privilege. */
    private static final String CHECK_VIOLATION = "23514";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private final LedgerAccountStore<Connection> store = new JdbcLedgerAccountStore();

    @Test
    @DisplayName("ten instances creating one owned account produce exactly one row")
    void tenConcurrentCreatesProduceOneRow() throws Exception {
        UUID ownerRef = IDS.next();
        int instances = 10;
        CyclicBarrier start = new CyclicBarrier(instances);
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        List<LedgerAccountStore.Creation> creations = new ArrayList<>();
        try {
            List<Callable<LedgerAccountStore.Creation>> racers = new ArrayList<>();
            for (int i = 0; i < instances; i++) {
                racers.add(
                        () -> {
                            // Own connection per instance (P0-TST-009): the losers BLOCK on the
                            // partial unique index until the winner commits - the database
                            // arbitrating, not this test arranging.
                            try (Connection app = DatabaseRoles.application()) {
                                app.setAutoCommit(false);
                                start.await();
                                LedgerAccountStore.Creation creation =
                                        store.createOrConverge(app, wallet(ownerRef));
                                app.commit();
                                return creation;
                            }
                        });
            }
            for (Future<LedgerAccountStore.Creation> outcome : pool.invokeAll(racers)) {
                creations.add(outcome.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(creations).hasSize(instances);
        assertThat(creations.stream().filter(LedgerAccountStore.Creation::created))
                .as("exactly one instance created the account")
                .hasSize(1);
        assertThat(creations.stream().map(creation -> creation.account().id()).distinct())
                .as("and all ten were handed the same account - convergence, not an error")
                .hasSize(1);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(rowsFor(app, ownerRef))
                    .as("counted in the table, never inferred from the outcomes")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the application role can move status and touch nothing else")
    void theGrantIsColumnNarrow() throws Exception {
        UUID ownerRef = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount account = store.createOrConverge(app, wallet(ownerRef)).account();
            app.commit();

            // Positive control first: the narrow grant is sufficient for the one legitimate
            // write, so the denials below are the grant being narrow rather than broken.
            try (PreparedStatement update =
                    app.prepareStatement(
                            "UPDATE ledger.ledger_account SET status = 'POSTING_SUSPENDED',"
                                    + " status_changed_at = status_changed_at WHERE id = ?")) {
                update.setObject(1, account.id().value());
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            app.commit();

            // Every classification and identity column refused at the PRIVILEGE level - the
            // application role never reaches the trigger. The column list is written out
            // rather than derived here because each denial names the exact column under test;
            // ColumnClassificationTest is what guarantees no column exists outside somebody's
            // decision.
            for (String column :
                    List.of(
                            "account_type", "normal_balance", "currency", "owner_kind",
                            "owner_ref", "purpose", "gl_code", "created_at")) {
                assertThatThrownBy(
                                () -> {
                                    try (PreparedStatement update =
                                            app.prepareStatement(
                                                    "UPDATE ledger.ledger_account SET "
                                                            + column + " = " + column
                                                            + " WHERE id = ?")) {
                                        update.setObject(1, account.id().value());
                                        update.executeUpdate();
                                    }
                                })
                        .as("the application role must hold no UPDATE on %s", column)
                        .isInstanceOf(SQLException.class)
                        .extracting(failure -> ((SQLException) failure).getSQLState())
                        .isEqualTo(INSUFFICIENT_PRIVILEGE);
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("identity is frozen for every writer; classification freezes once posted to")
    void theFreezeHasTwoLayersAndBothHold() throws Exception {
        UUID ownerRef = IDS.next();
        LedgerAccount account;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            account = store.createOrConverge(app, wallet(ownerRef)).account();
            app.commit();
        }

        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);

            // Layer 1: the identity fields are frozen UNCONDITIONALLY, even for the role that
            // owns the table - an unposted account with the wrong currency is corrected by
            // opening another, never by editing this one.
            for (String mutation :
                    List.of(
                            "currency = 'USD'",
                            "purpose = 'FEE_REVENUE', owner_kind = 'OPERATIONAL',"
                                    + " owner_ref = NULL",
                            "owner_ref = '" + UUID.randomUUID() + "'")) {
                assertThatThrownBy(() -> update(migrator, account.id().value(), mutation))
                        .as("identity mutation must be refused unposted or not: %s", mutation)
                        .isInstanceOf(SQLException.class)
                        .extracting(failure -> ((SQLException) failure).getSQLState())
                        .isEqualTo(CHECK_VIOLATION);
                migrator.rollback();
            }

            // Layer 2, positive control: while NO line references the account, the owner of
            // the table may still correct the classification (coherently - LIABILITY/CREDIT
            // to REVENUE/CREDIT keeps the derivation CHECK satisfied). This is what makes the
            // refusal below "frozen once posted to" rather than "frozen".
            assertThat(
                            update(
                                    migrator,
                                    account.id().value(),
                                    "account_type = 'REVENUE'"))
                    .isEqualTo(1);
            migrator.rollback();

            // The REAL posted line (P3-TSK-005) - this block proved the trigger against a
            // stand-in table until that task created ledger.journal_line, exactly as the
            // stand-in's comment promised. A balanced two-line entry on this one account
            // (DEBIT and CREDIT of the same amount) is the smallest legal posting that
            // references it.
            try (Connection app = DatabaseRoles.application()) {
                app.setAutoCommit(false);
                new com.finapp.ledger.JdbcJournalEntryStore(IDS)
                        .append(
                                app,
                                com.finapp.ledger.JournalEntry.balanced(
                                        IDS,
                                        CLOCK,
                                        java.time.LocalDate.of(2026, 9, 13),
                                        java.time.LocalDate.of(2026, 9, 13),
                                        java.util.List.of(
                                                new com.finapp.ledger.JournalLine(
                                                        account.id(),
                                                        com.finapp.ledger.Direction.DEBIT,
                                                        com.finapp.sharedkernel.money.Money
                                                                .ofMinorUnits(100, GBP)),
                                                new com.finapp.ledger.JournalLine(
                                                        account.id(),
                                                        com.finapp.ledger.Direction.CREDIT,
                                                        com.finapp.sharedkernel.money.Money
                                                                .ofMinorUnits(100, GBP)))),
                                new com.finapp.ledger.PostingAttribution(
                                        com.finapp.ledger.JournalEntryType.POSTING,
                                        "probe:freeze",
                                        java.util.Optional.empty(),
                                        java.util.Optional.empty(),
                                        "system",
                                        com.finapp.sharedkernel.correlation.Correlation
                                                .startingWith(
                                                        com.finapp.sharedkernel.correlation
                                                                .CorrelationId.generate(IDS))
                                                .causing(
                                                        com.finapp.sharedkernel.correlation
                                                                .CausationId.generate(IDS)),
                                        "probe:scope:" + account.id().value()));
                app.commit();
            }

            // The same coherent reclassification, now refused: a line references the
            // account, and INV-LED-06 says the classification is permanent.
            assertThatThrownBy(
                            () ->
                                    update(
                                            migrator,
                                            account.id().value(),
                                            "account_type = 'REVENUE'"))
                    .as("reclassifying a posted-to account must be refused (INV-LED-06)")
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);
            migrator.rollback();
        }
    }

    @Test
    @DisplayName("an unknown type and an incoherent pair are refused at the write")
    void theChecksRefuseWhatTheDomainCannotProduce() throws Exception {
        // Driven as the migrator with raw SQL, because the domain cannot construct either -
        // which is exactly why the schema carries the rule (INV-LED-06's "the domain is not
        // the only writer a schema will ever have").
        record BadRow(String type, String normalBalance, String kind, String purpose) {}
        List<BadRow> rows =
                List.of(
                        // Unknown type.
                        new BadRow("WEIRD", "DEBIT", "CUSTOMER", "CUSTOMER_WALLET"),
                        // Known values, incoherent pair: an ASSET that grows by credit.
                        new BadRow("ASSET", "CREDIT", "CUSTOMER", "CUSTOMER_WALLET"),
                        // A platform purpose owned by a customer.
                        new BadRow("REVENUE", "CREDIT", "CUSTOMER", "FEE_REVENUE"));
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            for (BadRow bad : rows) {
                assertThatThrownBy(
                                () -> {
                                    try (PreparedStatement insert =
                                            migrator.prepareStatement(
                                                    "INSERT INTO ledger.ledger_account"
                                                        + " (id, account_type, normal_balance,"
                                                        + " currency, owner_kind, owner_ref,"
                                                        + " purpose, status, created_at,"
                                                        + " status_changed_at) VALUES"
                                                        + " (?, ?, ?, 'GBP', ?, ?, ?,"
                                                        + " 'ACTIVE', now(), now())")) {
                                        insert.setObject(1, IDS.next());
                                        insert.setString(2, bad.type());
                                        insert.setString(3, bad.normalBalance());
                                        insert.setString(4, bad.kind());
                                        insert.setObject(5, UUID.randomUUID());
                                        insert.setString(6, bad.purpose());
                                        insert.executeUpdate();
                                    }
                                })
                        .as("the schema must refuse: %s", bad)
                        .isInstanceOf(SQLException.class)
                        .extracting(failure -> ((SQLException) failure).getSQLState())
                        .isEqualTo(CHECK_VIOLATION);
                migrator.rollback();
            }
        }
    }

    private static LedgerAccount wallet(UUID ownerRef) {
        return LedgerAccount.owned(
                IDS, CLOCK, AccountType.LIABILITY, AccountPurpose.CUSTOMER_WALLET, GBP, ownerRef);
    }

    private static int update(Connection connection, UUID id, String setClause)
            throws SQLException {
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE ledger.ledger_account SET " + setClause + " WHERE id = ?")) {
            update.setObject(1, id);
            return update.executeUpdate();
        }
    }

    private static int rowsFor(Connection connection, UUID ownerRef) throws SQLException {
        try (PreparedStatement count =
                connection.prepareStatement(
                        "SELECT count(*) FROM ledger.ledger_account WHERE owner_ref = ?")) {
            count.setObject(1, ownerRef);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }
}
