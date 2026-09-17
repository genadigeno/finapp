package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounts.AccountClosing;
import com.finapp.accounts.AccountHolderVerification;
import com.finapp.accounts.AccountNotEmptyException;
import com.finapp.accounts.AccountOpening;
import com.finapp.accounts.CustomerAccount;
import com.finapp.accounts.CustomerAccountId;
import com.finapp.accounts.CustomerAccountStatus;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.JdbcCustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.app.accounts.VerifiedAccountHolder;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcHoldStore;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountNotPostableException;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.party.JdbcPartyStore;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Closing an account without closing its history (`P3-TSK-014`), against a live PostgreSQL.
 *
 * <p>The race tests assert the <strong>coordination, not only the outcome</strong> (the
 * {@code P0-TST-004}/{@code P2-TSK-015} idiom): each observes the losing side Lock-waiting in
 * {@code pg_stat_activity} before the winner commits, because an outcome-only test passes
 * against both the right and the wrong mechanism — and the wrong mechanism here is exactly the
 * lock-free status read `P3-TSK-006` warned "passes every test and loses the race to close".
 */
@Tag("database")
@DisplayName("closing an account: zero-balance, the posting race, the surviving history (P3-TSK-014)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class AccountClosingDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 17);

    /** PostgreSQL SQLState: check_violation — V007's trigger refuses with it. */
    private static final String CHECK_VIOLATION = "23514";

    private final CustomerAccountStore<Connection> accounts = new JdbcCustomerAccountStore();
    private final LedgerAccountStore<Connection> ledgerAccounts = new JdbcLedgerAccountStore();
    private final AccountHolderVerification<Connection> holders =
            new VerifiedAccountHolder(new JdbcPartyStore());

    private AccountOpening opening() {
        return new AccountOpening(
                accounts,
                ledgerAccounts,
                holders,
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK);
    }

    private AccountClosing closing() {
        return new AccountClosing(
                accounts,
                ledgerAccounts,
                new JdbcBalanceDerivation(),
                new JdbcHoldStore(),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK);
    }

    private static PostingService postingService() {
        return new PostingService(
                new IdempotentExecutor(
                        new JdbcIdempotencyRecordStore(),
                        CLOCK,
                        Duration.ofDays(1),
                        Duration.ofMinutes(5)),
                new JdbcJournalEntryStore(IDS),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                new JdbcBalanceProjection(),
                IDS,
                CLOCK, PostingObserver.NONE);
    }

    @Test
    @DisplayName("a non-zero balance refuses the close, and the refusal writes nothing")
    void aNonZeroBalanceRefusesTheClose() throws Exception {
        Holder holder = holderWithOpenAccount();
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(holder.actor());
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            credit(app, holder, 500);
            app.commit();

            assertThatThrownBy(
                            () -> closing().close(app, holder.customer(), holder.account()))
                    .isInstanceOf(AccountNotEmptyException.class)
                    // The message names account and currency, never the amount (INV-AUD-02).
                    .hasMessageNotContaining("500")
                    .hasMessageNotContaining("5.00");
            app.rollback();

            assertThat(productStatusOf(app, holder.account()))
                    .isEqualTo(CustomerAccountStatus.ACTIVE.name());
            assertThat(walletOf(app, holder).status()).isEqualTo(LedgerAccountStatus.ACTIVE);
            assertThat(closeAuditRowsFor(app, holder.account())).isZero();
            assertThat(closeEventRowsFor(app, holder.account())).isZero();
        }
    }

    @Test
    @DisplayName("a closed account accepts no postings - through the domain and by raw SQL")
    void aClosedAccountAcceptsNoPostings() throws Exception {
        Holder holder = holderWithOpenAccount();
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(holder.actor());
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            AccountClosing.Closure closure =
                    closing().close(app, holder.customer(), holder.account()).orElseThrow();
            app.commit();
            assertThat(closure.closed()).isTrue();
            assertThat(walletOf(app, holder).status()).isEqualTo(LedgerAccountStatus.CLOSED);

            // The domain path: the refusal is a named outcome, and NOTHING commits - no
            // line lands (scoped to this wallet, so a parallel suite cannot blur the count).
            long linesBefore = lineCountFor(app, walletOf(app, holder));
            assertThatThrownBy(() -> credit(app, holder, 700))
                    .isInstanceOf(LedgerAccountNotPostableException.class)
                    .hasMessageNotContaining("700");
            app.rollback();
            assertThat(lineCountFor(app, walletOf(app, holder))).isEqualTo(linesBefore);

            // The raw-SQL path: V007 binds the writer the domain never sees. The application
            // role's own INSERT grant reaches the trigger, which refuses with the marker.
            UUID entry = IDS.next();
            execute(
                    app,
                    "INSERT INTO ledger.journal_entry (id, entry_type, posting_date,"
                            + " value_date, reference, actor_id, correlation_id, causation_id,"
                            + " idempotency_scope, created_at) VALUES (?, 'POSTING',"
                            + " '2026-09-17', '2026-09-17', 'raw-probe', 'probe', 'c-probe',"
                            + " 'z-probe', ?, now())",
                    entry,
                    "raw-" + IDS.next());
            assertThatThrownBy(
                            () ->
                                    execute(
                                            app,
                                            "INSERT INTO ledger.journal_line (id, entry_id,"
                                                    + " ledger_account_id, direction,"
                                                    + " amount_minor, currency,"
                                                    + " scale, seq) VALUES (?, ?, ?,"
                                                    + " 'CREDIT', 100, 'USD', 2, 1)",
                                            IDS.next(),
                                            entry,
                                            walletOf(app, holder).id().value()))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);
            app.rollback();
        }
    }

    @Test
    @DisplayName("the close and a posting serialize, both interleavings, deterministically")
    void theCloseAndAPostingSerializeBothWays() throws Exception {
        // Interleaving 1: a posting in flight holds FOR KEY SHARE (the FK and the trigger
        // read); the close's FOR UPDATE blocks behind it, and the fresh-statement derivation
        // then sees the committed posting and refuses.
        Holder first = holderWithOpenAccount();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection poster = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(first.actor());
                CorrelationContext.Scope flow = flow()) {
            poster.setAutoCommit(false);
            credit(poster, first, 900); // uncommitted - the locks are held

            Future<Object> close =
                    pool.submit(
                            () -> {
                                try (Connection closer = DatabaseRoles.application();
                                        SecurityContext.Scope a =
                                                SecurityContext.enter(first.actor());
                                        CorrelationContext.Scope f = flow()) {
                                    closer.setAutoCommit(false);
                                    try {
                                        closing().close(closer, first.customer(), first.account());
                                        closer.commit();
                                        return "closed";
                                    } catch (AccountNotEmptyException refused) {
                                        closer.rollback();
                                        return refused;
                                    }
                                }
                            });

            awaitBlockedOn("ledger.ledger_account", "FOR UPDATE");
            assertThat(close.isDone())
                    .as("the closer must be waiting on the poster's lock, not deciding on a"
                            + " stale snapshot")
                    .isFalse();
            poster.commit();
            assertThat(close.get(30, TimeUnit.SECONDS))
                    .as("resumed after the posting committed, the closer's fresh derivation"
                            + " sees the money and refuses")
                    .isInstanceOf(AccountNotEmptyException.class);
        } finally {
            pool.shutdownNow();
        }

        // Interleaving 2: a close in flight holds FOR UPDATE; the posting's trigger read
        // (FOR KEY SHARE) blocks behind it, and on resume re-reads the status the close
        // committed - the exact race a lock-free status read loses (P3-TSK-006's warning).
        Holder second = holderWithOpenAccount();
        ExecutorService postingPool = Executors.newFixedThreadPool(2);
        try (Connection closer = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(second.actor());
                CorrelationContext.Scope flow = flow()) {
            closer.setAutoCommit(false);
            closing().close(closer, second.customer(), second.account()); // uncommitted

            Future<Object> posting =
                    postingPool.submit(
                            () -> {
                                try (Connection poster = DatabaseRoles.application();
                                        SecurityContext.Scope a =
                                                SecurityContext.enter(second.actor());
                                        CorrelationContext.Scope f = flow()) {
                                    poster.setAutoCommit(false);
                                    try {
                                        credit(poster, second, 300);
                                        poster.commit();
                                        return "posted";
                                    } catch (LedgerAccountNotPostableException refused) {
                                        poster.rollback();
                                        return refused;
                                    }
                                }
                            });

            awaitBlockedOn("ledger.journal_line", "INSERT");
            assertThat(posting.isDone())
                    .as("the posting must be waiting on the closer's lock - a lock-free status"
                            + " read would have sailed past the uncommitted close")
                    .isFalse();
            closer.commit();
            assertThat(posting.get(30, TimeUnit.SECONDS))
                    .as("resumed after the close committed, the trigger re-reads CLOSED and"
                            + " refuses")
                    .isInstanceOf(LedgerAccountNotPostableException.class);
        } finally {
            postingPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("ten concurrent closes produce one transition, one record, one event - and the"
            + " freed slot admits a successor")
    void tenConcurrentClosesProduceOneTransition() throws Exception {
        Holder holder = holderWithOpenAccount();
        int instances = 10;
        CyclicBarrier start = new CyclicBarrier(instances);
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        try {
            List<Callable<Boolean>> closes = new ArrayList<>();
            for (int i = 0; i < instances; i++) {
                closes.add(
                        () -> {
                            try (Connection own = DatabaseRoles.application();
                                    SecurityContext.Scope actor =
                                            SecurityContext.enter(holder.actor());
                                    CorrelationContext.Scope flow = flow()) {
                                own.setAutoCommit(false);
                                start.await();
                                AccountClosing.Closure closure =
                                        closing()
                                                .close(own, holder.customer(), holder.account())
                                                .orElseThrow();
                                own.commit();
                                return closure.closed();
                            }
                        });
            }
            int closed = 0;
            for (Future<Boolean> outcome : pool.invokeAll(closes)) {
                if (outcome.get()) {
                    closed++;
                }
            }
            assertThat(closed).as("exactly one racer closed; nine converged").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(holder.actor());
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            assertThat(closeAuditRowsFor(app, holder.account())).isEqualTo(1);
            assertThat(closeEventRowsFor(app, holder.account())).isEqualTo(1);
            assertThat(productStatusOf(app, holder.account()))
                    .isEqualTo(CustomerAccountStatus.CLOSED.name());

            // Closure freed the one-live slot (INV-LIFE-04's asymmetry): a successor
            // agreement is a NEW aggregate, and it opens.
            CustomerAccountStore.Creation successor =
                    opening().open(app, holder.party(), ProductType.WALLET, USD);
            app.commit();
            assertThat(successor.created()).isTrue();
            assertThat(successor.account().id()).isNotEqualTo(holder.accountId());
        }
    }

    @Test
    @DisplayName("a standing hold blocks the close, and its release frees it (P3-TSK-015)")
    void aStandingHoldBlocksTheClose() throws Exception {
        // Postings are not gated by holds, so settled can reach zero while a reservation
        // stands - closing then would strand it. The agreement is not empty while value is
        // reserved; judged from the authoritative hold rows under the same lock.
        Holder holder = holderWithOpenAccount();
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(holder.actor());
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            credit(app, holder, 800);
            com.finapp.ledger.Hold hold =
                    new com.finapp.ledger.HoldService(
                                    ledgerAccounts,
                                    new JdbcBalanceDerivation(),
                                    new JdbcHoldStore(),
                                    new JdbcBalanceProjection(),
                                    new JdbcAuditWriter(),
                                    new JdbcOutboxWriter(),
                                    IDS,
                                    CLOCK)
                            .place(
                                    app,
                                    walletOf(app, holder).id(),
                                    Money.ofMinorUnits(800, USD));
            debitAll(app, holder, 800); // settled back to zero - the hold still stands
            app.commit();

            assertThatThrownBy(
                            () -> closing().close(app, holder.customer(), holder.account()))
                    .isInstanceOf(AccountNotEmptyException.class);
            app.rollback();
            assertThat(productStatusOf(app, holder.account()))
                    .isEqualTo(CustomerAccountStatus.ACTIVE.name());

            new com.finapp.ledger.HoldService(
                            ledgerAccounts,
                            new JdbcBalanceDerivation(),
                            new JdbcHoldStore(),
                            new JdbcBalanceProjection(),
                            new JdbcAuditWriter(),
                            new JdbcOutboxWriter(),
                            IDS,
                            CLOCK)
                    .release(app, hold.id())
                    .orElseThrow();
            app.commit();

            AccountClosing.Closure closure =
                    closing().close(app, holder.customer(), holder.account()).orElseThrow();
            app.commit();
            assertThat(closure.closed()).isTrue();
        }
    }

    @Test
    @DisplayName("the history survives the close, intact and readable")
    void theHistorySurvivesTheClose() throws Exception {
        Holder holder = holderWithOpenAccount();
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(holder.actor());
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            credit(app, holder, 1200);
            debitAll(app, holder, 1200); // back to zero, so the close can proceed
            app.commit();

            LedgerAccount wallet = walletOf(app, holder);
            long linesBefore = lineCountFor(app, wallet);
            long sumBefore = lineSumFor(app, wallet);
            assertThat(linesBefore).isEqualTo(2);

            closing().close(app, holder.customer(), holder.account()).orElseThrow();
            app.commit();

            // The agreement ended; the accounting history did not (INV-HIST-01): same rows,
            // same values, still readable through the application role's own SELECT.
            assertThat(lineCountFor(app, wallet)).isEqualTo(linesBefore);
            assertThat(lineSumFor(app, wallet)).isEqualTo(sumBefore);
        }
    }

    // -----------------------------------------------------------------
    // Fixtures

    private record Holder(UUID party, UUID customer, CustomerAccountId account) {
        Actor actor() {
            return new Actor("identity-" + customer, ActorType.CUSTOMER);
        }

        CustomerAccountId accountId() {
            return account;
        }
    }

    /** A verified holder with one open USD wallet product. */
    private Holder holderWithOpenAccount() throws SQLException {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Closing Holder', now() - interval '2"
                            + " hour')",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    customer,
                    party);
        }
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor =
                        SecurityContext.enter(new Actor("identity-" + customer, ActorType.CUSTOMER));
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            CustomerAccountStore.Creation creation =
                    opening().open(app, party, ProductType.WALLET, USD);
            app.commit();
            return new Holder(party, customer, creation.account().id());
        }
    }

    private LedgerAccount walletOf(Connection app, Holder holder) {
        return ledgerAccounts
                .findOwned(app, holder.account().value(), AccountPurpose.CUSTOMER_WALLET, USD)
                .orElseThrow();
    }

    /** A balanced credit to the holder's wallet from the operational clearing account. */
    private void credit(Connection app, Holder holder, long minorUnits) {
        post(app, holder, minorUnits, Direction.DEBIT, Direction.CREDIT);
    }

    /** The mirror image, so a history can return to zero without touching any row. */
    private void debitAll(Connection app, Holder holder, long minorUnits) {
        post(app, holder, minorUnits, Direction.CREDIT, Direction.DEBIT);
    }

    private void post(
            Connection app,
            Holder holder,
            long minorUnits,
            Direction clearingSide,
            Direction walletSide) {
        LedgerAccount wallet = walletOf(app, holder);
        LedgerAccount clearing =
                ledgerAccounts
                        .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, USD)
                        .orElseThrow();
        postingService()
                .post(
                        app,
                        new PostingCommand(
                                "closing-" + IDS.next(),
                                DATE,
                                DATE,
                                "closing-fixture",
                                List.of(
                                        new JournalLine(
                                                clearing.id(),
                                                clearingSide,
                                                Money.ofMinorUnits(minorUnits, USD)),
                                        new JournalLine(
                                                wallet.id(),
                                                walletSide,
                                                Money.ofMinorUnits(minorUnits, USD)))));
    }

    /** The {@code P0-TST-004} idiom: the losing side observed Lock-waiting, never assumed. */
    private static void awaitBlockedOn(String table, String queryMarker)
            throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try (Connection observer = DatabaseRoles.application();
                PreparedStatement select =
                        observer.prepareStatement(
                                "SELECT count(*) FROM pg_stat_activity"
                                        + " WHERE wait_event_type = 'Lock'"
                                        + " AND query LIKE '%" + table + "%'"
                                        + " AND query LIKE '%" + queryMarker + "%'")) {
            while (System.nanoTime() < deadline) {
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    if (row.getLong(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(25);
            }
        }
        throw new AssertionError(
                "the losing side never blocked on " + table + " - without the lock the race is"
                        + " decided by a stale snapshot");
    }

    // -----------------------------------------------------------------
    // Counters

    private static String productStatusOf(Connection app, CustomerAccountId account)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT status FROM accounts.customer_account WHERE id = ?")) {
            read.setObject(1, account.value());
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long lineCountFor(Connection app, LedgerAccount account) throws SQLException {
        try (PreparedStatement count =
                app.prepareStatement(
                        "SELECT count(*) FROM ledger.journal_line WHERE ledger_account_id = ?")) {
            count.setObject(1, account.id().value());
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long lineSumFor(Connection app, LedgerAccount account) throws SQLException {
        try (PreparedStatement sum =
                app.prepareStatement(
                        // A fixture checksum over stored values, not monetary arithmetic: the
                        // question is "did any row change", not "what is the balance".
                        "SELECT coalesce(sum(amount_minor), 0) FROM ledger.journal_line"
                                + " WHERE ledger_account_id = ?")) {
            sum.setObject(1, account.id().value());
            try (ResultSet row = sum.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long closeAuditRowsFor(Connection app, CustomerAccountId account)
            throws SQLException {
        try (PreparedStatement count =
                app.prepareStatement(
                        "SELECT count(*) FROM platform.audit_record"
                                + " WHERE operation = 'accounts.AccountClosed'"
                                + " AND target_id = ?")) {
            count.setString(1, account.value().toString());
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long closeEventRowsFor(Connection app, CustomerAccountId account)
            throws SQLException {
        try (PreparedStatement count =
                app.prepareStatement(
                        "SELECT count(*) FROM platform.outbox_event"
                                + " WHERE event_type = 'accounts.AccountClosed'"
                                + " AND aggregate_id = ?")) {
            count.setObject(1, account.value());
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }

    private static CorrelationContext.Scope flow() {
        return CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
    }
}
