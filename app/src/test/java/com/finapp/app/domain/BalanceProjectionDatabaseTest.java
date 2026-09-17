package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.AsOf;
import com.finapp.ledger.BalanceDerivation;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.UnderivableBalanceException;
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
 * The transactional projection against a live PostgreSQL (`P3-TSK-009`, ADR-0041): after
 * every posting — sequential, replayed, concurrent, rolled back — {@code
 * ledger.account_balance} agrees with the derivation that defines the balance
 * (`P3-TSK-008`), because the two commit together or not at all.
 *
 * <p>The projection row is read here by raw SQL deliberately: production exposes no read
 * ({@code INV-BAL-05}, pinned hermetically by {@code BalanceProjectionTest}), and the test is
 * exactly the comparison the verification job (`P3-TSK-010`) will run continuously.
 */
@Tag("database")
@DisplayName("the transactional projection under posting, races and rollback (P3-TSK-009)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class BalanceProjectionDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 16);

    private final LedgerAccountStore<Connection> accounts = new JdbcLedgerAccountStore();
    private final BalanceDerivation<Connection> derivation = new JdbcBalanceDerivation();

    @Test
    @DisplayName("the projection equals the derivation after every posting, and a replay"
            + " applies nothing")
    void theProjectionEqualsTheDerivationAfterEveryPosting() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount wallet = wallet(app); // CREDIT-normal
            LedgerAccount clearing = operational(app); // DEBIT-normal

            post(app, entry(clearing, Direction.DEBIT, wallet, Direction.CREDIT, 5000));
            // Both directions on one account in ONE entry: the wallet's delta is a
            // subtraction, and its seq must advance by one, not by its line count.
            PostingCommand bothSides =
                    new PostingCommand(
                            "projection-" + IDS.next(), DATE, DATE, "probe-event",
                            List.of(
                                    new JournalLine(wallet.id(), Direction.DEBIT,
                                            Money.ofMinorUnits(1200, USD)),
                                    new JournalLine(wallet.id(), Direction.CREDIT,
                                            Money.ofMinorUnits(200, USD)),
                                    new JournalLine(clearing.id(), Direction.CREDIT,
                                            Money.ofMinorUnits(1000, USD))));
            post(app, bothSides);

            // The retry: same key, same command - replayed, and the projection untouched.
            PostingResult replay = post(app, bothSides);
            assertThat(replay.replayed()).isTrue();
            app.commit();

            ProjectionRow row = projectionOf(app, wallet.id()).orElseThrow();
            assertThat(row.settled())
                    .as("the fresh wallet's projection, pinned literally")
                    .isEqualTo(Money.ofMinorUnits(4000, USD));
            assertThat(row.lastEntrySeq())
                    .as("two entries applied - the replay applied nothing, and the"
                            + " both-sides entry counted once")
                    .isEqualTo(2);
            for (LedgerAccount account : List.of(wallet, clearing)) {
                assertProjectionCurrent(app, account.id());
            }

            // An account never posted to has NO projection row - absence, not a zero row;
            // what absence means to a reader is the arriving readers' own contract.
            assertThat(projectionOf(app, wallet(app).id())).isEmpty();
            app.rollback();
        }
    }

    @Test
    @DisplayName("ten concurrent postings to one fresh account agree with the derivation")
    void tenConcurrentPostingsToOneAccountAgreeWithTheDerivation() throws Exception {
        LedgerAccount wallet;
        LedgerAccount clearing;
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            wallet = wallet(app);
            clearing = operational(app);
            app.commit();
        }

        // Ten instances (P0-TST-009): own connection, own scopes, distinct entries, one
        // fresh account - so the race also covers the first row's INSERT .. ON CONFLICT
        // convergence, not only the increment.
        int instances = 10;
        CyclicBarrier start = new CyclicBarrier(instances);
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        try {
            List<Callable<Void>> racers = new ArrayList<>();
            for (int i = 1; i <= instances; i++) {
                long minor = 100L * i;
                LedgerAccount to = wallet;
                LedgerAccount from = clearing;
                racers.add(
                        () -> {
                            try (Connection own = DatabaseRoles.application();
                                    SecurityContext.Scope actor =
                                            SecurityContext.enterSystem();
                                    CorrelationContext.Scope flow = flow()) {
                                own.setAutoCommit(false);
                                start.await();
                                post(own, entry(from, Direction.DEBIT, to,
                                        Direction.CREDIT, minor));
                                own.commit();
                            }
                            return null;
                        });
            }
            for (Future<Void> outcome : pool.invokeAll(racers)) {
                outcome.get();
            }
        } finally {
            pool.shutdownNow();
        }

        try (Connection app = DatabaseRoles.application()) {
            ProjectionRow row = projectionOf(app, wallet.id()).orElseThrow();
            assertThat(row.settled())
                    .as("no increment lost: 100+200+..+1000")
                    .isEqualTo(Money.ofMinorUnits(5500, USD));
            assertThat(row.lastEntrySeq()).isEqualTo(10);
            assertProjectionCurrent(app, wallet.id());
            assertThat(row.settled())
                    .isEqualTo(derivation.derive(app, wallet.id(), AsOf.latest()).settled());
        }
    }

    @Test
    @DisplayName("a rolled-back posting leaves the projection unchanged")
    void aRolledBackPostingLeavesTheProjectionUnchanged() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount wallet = wallet(app);
            LedgerAccount clearing = operational(app);
            post(app, entry(clearing, Direction.DEBIT, wallet, Direction.CREDIT, 900));
            app.commit();
            ProjectionRow before = projectionOf(app, wallet.id()).orElseThrow();

            post(app, entry(clearing, Direction.DEBIT, wallet, Direction.CREDIT, 700));
            app.rollback();

            assertThat(projectionOf(app, wallet.id()).orElseThrow())
                    .as("the projection change rode the posting's transaction out")
                    .isEqualTo(before);
            assertProjectionCurrent(app, wallet.id());
        }
    }

    @Test
    @DisplayName("a posting at a diverging persisted scale is refused wholly")
    void aScaleDivergentPostingIsRefusedWholly() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount wallet = wallet(app);
            LedgerAccount clearing = operational(app);
            post(app, entry(clearing, Direction.DEBIT, wallet, Direction.CREDIT, 1500));
            app.commit();
            ProjectionRow before = projectionOf(app, wallet.id()).orElseThrow();

            // Legal at the domain (one scale per currency within the entry) and previously
            // postable; with the projection in the write path it is refused at posting time,
            // because applying it would be the implicit rescale INV-MON-03 forbids - and a
            // posting the projection cannot follow must not commit beside a projection now
            // permanently behind (ADR-0041 rule 1).
            PostingCommand atScaleThree =
                    new PostingCommand(
                            "projection-" + IDS.next(), DATE, DATE, "probe-event",
                            List.of(
                                    new JournalLine(clearing.id(), Direction.DEBIT,
                                            Money.ofPersisted(500, USD, 3)),
                                    new JournalLine(wallet.id(), Direction.CREDIT,
                                            Money.ofPersisted(500, USD, 3))));
            assertThatThrownBy(() -> post(app, atScaleThree))
                    .isInstanceOf(UnderivableBalanceException.class)
                    .hasMessageContaining("implicit rescale")
                    .hasMessageNotContaining("500");
            app.rollback();

            assertThat(projectionOf(app, wallet.id()).orElseThrow()).isEqualTo(before);
            assertThat(entriesOn(app, wallet.id()))
                    .as("nothing at all: the refused posting left no entry either")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the identity columns are unwritable and nothing deletes")
    void theIdentityColumnsAreUnwritableAndNothingDeletes() throws Exception {
        // The V004 column-narrowing, probed per column: the application role may move the
        // accumulating columns and cannot touch the row's identity or remove it. 0-row
        // predicates on purpose - the privilege check fires regardless.
        assertDenied("UPDATE ledger.account_balance SET currency = 'USD'"
                + " WHERE ledger_account_id = ?");
        assertDenied("UPDATE ledger.account_balance SET scale = 2"
                + " WHERE ledger_account_id = ?");
        assertDenied("DELETE FROM ledger.account_balance WHERE ledger_account_id = ?");
        try (Connection app = DatabaseRoles.application();
                PreparedStatement allowed =
                        app.prepareStatement(
                                "UPDATE ledger.account_balance"
                                        + " SET posted_minor = posted_minor"
                                        + " WHERE ledger_account_id = ?")) {
            allowed.setObject(1, UUID.randomUUID());
            allowed.executeUpdate(); // The positive control: the narrowed grant is real.
        }
    }

    // ------------------------------------------------------------------ fixtures

    private PostingService service() {
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

    private PostingResult post(Connection app, PostingCommand command) {
        return service().post(app, command);
    }

    private LedgerAccount wallet(Connection app) {
        return accounts
                .createOrConverge(
                        app,
                        LedgerAccount.owned(
                                IDS, CLOCK, AccountType.LIABILITY,
                                AccountPurpose.CUSTOMER_WALLET, USD, IDS.next()))
                .account();
    }

    private LedgerAccount operational(Connection app) {
        return accounts
                .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, USD)
                .orElseThrow();
    }

    private static PostingCommand entry(
            LedgerAccount first, Direction firstSide,
            LedgerAccount second, Direction secondSide, long minorUnits) {
        return new PostingCommand(
                "projection-" + IDS.next(), DATE, DATE, "probe-event",
                List.of(
                        new JournalLine(first.id(), firstSide,
                                Money.ofMinorUnits(minorUnits, USD)),
                        new JournalLine(second.id(), secondSide,
                                Money.ofMinorUnits(minorUnits, USD))));
    }

    /** The row as stored; {@code settled()} interprets it per INV-MON-05. */
    private record ProjectionRow(
            long postedMinor, long holdsMinor, short scale, String currency,
            long lastEntrySeq) {
        Money settled() {
            return Money.ofPersisted(
                    postedMinor, CurrencyCode.of(currency.stripTrailing()), scale);
        }
    }

    private static Optional<ProjectionRow> projectionOf(Connection app, LedgerAccountId account)
            throws SQLException {
        try (PreparedStatement select =
                app.prepareStatement(
                        "SELECT posted_minor, holds_minor, scale, currency, last_entry_seq"
                                + " FROM ledger.account_balance"
                                + " WHERE ledger_account_id = ?")) {
            select.setObject(1, account.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new ProjectionRow(
                                row.getLong("posted_minor"),
                                row.getLong("holds_minor"),
                                row.getShort("scale"),
                                row.getString("currency"),
                                row.getLong("last_entry_seq")));
            }
        }
    }

    /**
     * The whole agreement, the way `P3-TSK-010` will check it continuously: the settled
     * number equals the derivation's, and the watermark equals the applied-entry count —
     * a count deliberately, never an entry id ({@code AsOf}'s mint-vs-commit caveat).
     */
    private void assertProjectionCurrent(Connection app, LedgerAccountId account)
            throws SQLException {
        ProjectionRow row = projectionOf(app, account).orElseThrow();
        assertThat(row.settled())
                .as("%s: projection equals derivation", account)
                .isEqualTo(derivation.derive(app, account, AsOf.latest()).settled());
        assertThat(row.lastEntrySeq())
                .as("%s: the watermark is the applied-entry count", account)
                .isEqualTo(entriesOn(app, account));
        assertThat(row.holdsMinor())
                .as("holds stay zero until P3-TSK-015 populates them")
                .isZero();
    }

    private static long entriesOn(Connection app, LedgerAccountId account)
            throws SQLException {
        try (PreparedStatement select =
                app.prepareStatement(
                        "SELECT COUNT(DISTINCT entry_id) FROM ledger.journal_line"
                                + " WHERE ledger_account_id = ?")) {
            select.setObject(1, account.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static void assertDenied(String sql) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement denied = app.prepareStatement(sql)) {
            denied.setObject(1, UUID.randomUUID());
            assertThatThrownBy(denied::executeUpdate)
                    .asInstanceOf(
                            org.assertj.core.api.InstanceOfAssertFactories.type(
                                    SQLException.class))
                    .extracting(SQLException::getSQLState)
                    .as("denied by privilege, not by rows matched: %s", sql)
                    .isEqualTo("42501");
        }
    }

    private static CorrelationContext.Scope flow() {
        return CorrelationContext.enter(
                Correlation.startingWith(CorrelationId.generate(IDS)));
    }
}
