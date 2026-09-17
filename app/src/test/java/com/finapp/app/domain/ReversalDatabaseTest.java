package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.AsOf;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.OverReversalException;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.ReversalCommand;
import com.finapp.ledger.ReversalService;
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
 * Reversal against a live PostgreSQL (`P3-TSK-016`): a new effect referencing the original
 * ({@code INV-REV-01}), bounded by it accounting for previous partials ({@code INV-REV-02}),
 * with the original <strong>byte-identical</strong> afterwards — captured as PostgreSQL's own
 * row rendering before and compared after, on top of the standing {@code DB-PRIVILEGE} proof.
 *
 * <p>The race tests assert the <strong>coordination</strong>: the losing reversal is observed
 * Lock-waiting (on `V009`'s advisory serializer) in {@code pg_stat_activity} before the
 * winner commits — an outcome-only test passes against both the right and the wrong
 * mechanism, and the wrong one here is a lock-free sum that cannot see an uncommitted
 * sibling (the {@code P2-TSK-015} write-skew shape).
 */
@Tag("database")
@DisplayName("reversal: a new effect, bounded, the original untouched (P3-TSK-016)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class ReversalDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 17);

    /** PostgreSQL SQLState: check_violation — V009's triggers refuse with it. */
    private static final String CHECK_VIOLATION = "23514";

    private final LedgerAccountStore<Connection> accounts = new JdbcLedgerAccountStore();

    private static IdempotentExecutor executor() {
        return new IdempotentExecutor(
                new JdbcIdempotencyRecordStore(),
                CLOCK,
                Duration.ofDays(1),
                Duration.ofMinutes(5));
    }

    private static PostingService postingService() {
        return new PostingService(
                executor(),
                new JdbcJournalEntryStore(IDS),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                new JdbcBalanceProjection(),
                IDS,
                CLOCK, PostingObserver.NONE);
    }

    private static ReversalService reversalService() {
        return new ReversalService(
                executor(),
                new JdbcJournalEntryStore(IDS),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                new JdbcBalanceProjection(),
                IDS,
                CLOCK, PostingObserver.NONE);
    }

    @Test
    @DisplayName("a full reversal restores the balance, and the original is byte-identical")
    void aFullReversalLeavesTheOriginalByteIdentical() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(actor());
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            Fixture fixture = posted(app, 1000);
            app.commit();

            String entryBefore = rowOf(app, fixture.original());
            List<String> linesBefore = lineRowsOf(app, fixture.original());
            Money settledBefore =
                    new JdbcBalanceDerivation()
                            .derive(app, fixture.wallet().id(), AsOf.latest())
                            .settled();
            assertThat(settledBefore).isEqualTo(Money.ofPersisted(1000, USD, 2));

            PostingResult reversal =
                    reversalService()
                            .reverse(
                                    app,
                                    new ReversalCommand(
                                            "rev-" + IDS.next(),
                                            fixture.original(),
                                            DATE,
                                            DATE,
                                            "reversal-probe",
                                            swapped(fixture, 1000)));
            app.commit();
            assertThat(reversal.replayed()).isFalse();

            // The new effect: a REVERSAL entry referencing the original (INV-REV-01), and
            // the settled balance back where it started - derived, and in the projection.
            assertThat(entryTypeOf(app, reversal.entryId())).isEqualTo("REVERSAL");
            assertThat(reversesOf(app, reversal.entryId())).isEqualTo(fixture.original().value());
            assertThat(
                            new JdbcBalanceDerivation()
                                    .derive(app, fixture.wallet().id(), AsOf.latest())
                                    .settled())
                    .isEqualTo(Money.ofPersisted(0, USD, 2));
            assertThat(postedMinorOf(app, fixture.wallet())).isZero();

            // INV-REV-01's headline: the original is BYTE-IDENTICAL - the whole row as
            // PostgreSQL renders it, and every line, compared after the reversal.
            assertThat(rowOf(app, fixture.original())).isEqualTo(entryBefore);
            assertThat(lineRowsOf(app, fixture.original())).isEqualTo(linesBefore);
        }
    }

    @Test
    @DisplayName("partials sum: 30 then 70 accepted, one more minor unit refused with nothing"
            + " written")
    void partialReversalsSumCorrectly() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(actor());
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            Fixture fixture = posted(app, 1000);
            app.commit();

            reversalService()
                    .reverse(app, command(fixture, "rev-a-" + IDS.next(), 300));
            reversalService()
                    .reverse(app, command(fixture, "rev-b-" + IDS.next(), 700));
            app.commit();

            long reversalsBefore = reversalRowsOf(app, fixture.original());
            assertThat(reversalsBefore).isEqualTo(2);
            assertThatThrownBy(
                            () ->
                                    reversalService()
                                            .reverse(
                                                    app,
                                                    command(
                                                            fixture,
                                                            "rev-c-" + IDS.next(),
                                                            1)))
                    .isInstanceOf(OverReversalException.class)
                    .hasMessageNotContaining("1000")
                    .hasMessageNotContaining("10.00");
            app.rollback();
            assertThat(reversalRowsOf(app, fixture.original())).isEqualTo(reversalsBefore);
        }
    }

    @Test
    @DisplayName("a replayed key is one reversal - the retry learns what its request did")
    void aReplayedKeyIsOneReversal() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(actor());
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            Fixture fixture = posted(app, 1000);
            app.commit();

            ReversalCommand command = command(fixture, "rev-replay-" + IDS.next(), 400);
            PostingResult first = reversalService().reverse(app, command);
            app.commit();
            PostingResult second = reversalService().reverse(app, command);
            app.commit();

            assertThat(first.replayed()).isFalse();
            assertThat(second.replayed()).isTrue();
            assertThat(second.entryId()).isEqualTo(first.entryId());
            assertThat(reversalRowsOf(app, fixture.original())).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("two concurrent partial reversals serialize on the advisory lock - the loser"
            + " observed blocked, then refused on the winner's committed rows")
    void theLosingReversalIsObservedBlockedThenRefused() throws Exception {
        Fixture fixture;
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(actor());
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            fixture = posted(app, 1000);
            app.commit();
        }

        ExecutorService pool = Executors.newFixedThreadPool(1);
        try (Connection winner = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(actor());
                CorrelationContext.Scope flow = flow()) {
            winner.setAutoCommit(false);
            reversalService()
                    .reverse(winner, command(fixture, "rev-w-" + IDS.next(), 600));
            // Uncommitted: V009's advisory xact lock on the original is held.

            Fixture finalFixture = fixture;
            Future<Object> loser =
                    pool.submit(
                            () -> {
                                try (Connection own = DatabaseRoles.application();
                                        SecurityContext.Scope a =
                                                SecurityContext.enter(actor());
                                        CorrelationContext.Scope f = flow()) {
                                    own.setAutoCommit(false);
                                    try {
                                        PostingResult r =
                                                reversalService()
                                                        .reverse(
                                                                own,
                                                                command(
                                                                        finalFixture,
                                                                        "rev-l-" + IDS.next(),
                                                                        600));
                                        own.commit();
                                        return r;
                                    } catch (OverReversalException refused) {
                                        own.rollback();
                                        return refused;
                                    }
                                }
                            });

            // The coordination assertion: the loser's line INSERT is Lock-waiting on the
            // advisory serializer. Its domain pre-check saw only committed reversals (none)
            // and waved it through - which is exactly why the trigger is the arbiter.
            awaitBlockedOn("journal_line", "INSERT");
            assertThat(loser.isDone())
                    .as("the loser must be queued on the advisory lock, not deciding on a"
                            + " snapshot that cannot see the winner")
                    .isFalse();
            winner.commit();
            assertThat(loser.get(30, TimeUnit.SECONDS))
                    .as("resumed after the winner committed, the trigger re-judges: 600+600"
                            + " exceeds 1000, refused as the named domain outcome")
                    .isInstanceOf(OverReversalException.class);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("ten concurrent partial reversals: what was accepted sums within the"
            + " original, counted in the table")
    void tenConcurrentPartialReversalsRespectTheBound() throws Exception {
        Fixture fixture;
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(actor());
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            fixture = posted(app, 1000);
            app.commit();
        }

        int instances = 10;
        CyclicBarrier start = new CyclicBarrier(instances);
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        Fixture finalFixture = fixture;
        try {
            List<Callable<Boolean>> reversals = new ArrayList<>();
            for (int i = 0; i < instances; i++) {
                reversals.add(
                        () -> {
                            try (Connection own = DatabaseRoles.application();
                                    SecurityContext.Scope actor =
                                            SecurityContext.enter(actor());
                                    CorrelationContext.Scope flow = flow()) {
                                own.setAutoCommit(false);
                                start.await();
                                try {
                                    reversalService()
                                            .reverse(
                                                    own,
                                                    command(
                                                            finalFixture,
                                                            "rev-race-" + IDS.next(),
                                                            400));
                                    own.commit();
                                    return true;
                                } catch (OverReversalException refused) {
                                    own.rollback();
                                    return false;
                                }
                            }
                        });
            }
            int accepted = 0;
            for (Future<Boolean> outcome : pool.invokeAll(reversals)) {
                if (outcome.get()) {
                    accepted++;
                }
            }
            assertThat(accepted)
                    .as("1000 admits exactly two partial reversals of 400 (INV-REV-02,"
                            + " INV-CON-01)")
                    .isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }

        try (Connection app = DatabaseRoles.application()) {
            assertThat(reversalRowsOf(app, fixture.original())).isEqualTo(2);
            assertThat(reversedSumOf(app, fixture.original(), fixture.wallet()))
                    .isEqualTo(800);
        }
    }

    @Test
    @DisplayName("raw SQL cannot over-reverse either - V009 binds the writer the domain never"
            + " sees")
    void rawSqlCannotOverReverse() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(actor());
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            Fixture fixture = posted(app, 1000);
            app.commit();

            UUID reversalEntry = IDS.next();
            execute(
                    app,
                    "INSERT INTO ledger.journal_entry (id, posting_date, value_date,"
                            + " entry_type, reference, reverses_entry_id, actor_id,"
                            + " correlation_id, causation_id, idempotency_scope, created_at)"
                            + " VALUES (?, '2026-09-17', '2026-09-17', 'REVERSAL',"
                            + " 'raw-probe', ?, 'probe', 'c-probe', 'z-probe', ?, now())",
                    reversalEntry,
                    fixture.original().value(),
                    "raw-" + IDS.next());
            assertThatThrownBy(
                            () ->
                                    execute(
                                            app,
                                            "INSERT INTO ledger.journal_line (id, entry_id,"
                                                    + " ledger_account_id, direction,"
                                                    + " amount_minor, currency, scale, seq)"
                                                    + " VALUES (?, ?, ?, 'DEBIT', 1001,"
                                                    + " 'USD', 2, 0)",
                                            IDS.next(),
                                            reversalEntry,
                                            fixture.wallet().id().value()))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);
            app.rollback();
        }
    }

    @Test
    @DisplayName("a reversal cannot be reversed - through the domain and by raw SQL")
    void aReversalCannotBeReversed() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(actor());
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            Fixture fixture = posted(app, 1000);
            app.commit();
            PostingResult reversal =
                    reversalService()
                            .reverse(app, command(fixture, "rev-once-" + IDS.next(), 1000));
            app.commit();

            assertThatThrownBy(
                            () ->
                                    reversalService()
                                            .reverse(
                                                    app,
                                                    new ReversalCommand(
                                                            "rev-chain-" + IDS.next(),
                                                            reversal.entryId(),
                                                            DATE,
                                                            DATE,
                                                            "chain-probe",
                                                            List.of(
                                                                    line(
                                                                            fixture.clearing(),
                                                                            Direction.DEBIT,
                                                                            100),
                                                                    line(
                                                                            fixture.wallet(),
                                                                            Direction.CREDIT,
                                                                            100)))))
                    .isInstanceOf(IllegalArgumentException.class);
            app.rollback();

            // The schema half: the entry trigger refuses the chain for raw SQL too.
            assertThatThrownBy(
                            () ->
                                    execute(
                                            app,
                                            "INSERT INTO ledger.journal_entry (id,"
                                                    + " posting_date, value_date, entry_type,"
                                                    + " reference, reverses_entry_id,"
                                                    + " actor_id, correlation_id,"
                                                    + " causation_id, idempotency_scope,"
                                                    + " created_at) VALUES (?, '2026-09-17',"
                                                    + " '2026-09-17', 'REVERSAL',"
                                                    + " 'chain-raw', ?, 'probe', 'c-probe',"
                                                    + " 'z-probe', ?, now())",
                                            IDS.next(),
                                            reversal.entryId().value(),
                                            "raw-" + IDS.next()))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);
            app.rollback();
        }
    }

    // -----------------------------------------------------------------
    // Fixtures

    private record Fixture(
            JournalEntryId original, LedgerAccount clearing, LedgerAccount wallet) {}

    /** An owned USD wallet credited {@code minor} from the operational clearing account. */
    private Fixture posted(Connection app, long minor) {
        LedgerAccount wallet =
                accounts.createOrConverge(
                                app,
                                LedgerAccount.owned(
                                        IDS,
                                        CLOCK,
                                        AccountType.LIABILITY,
                                        AccountPurpose.CUSTOMER_WALLET,
                                        USD,
                                        IDS.next()))
                        .account();
        LedgerAccount clearing =
                accounts.findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, USD)
                        .orElseThrow();
        PostingResult posted =
                postingService()
                        .post(
                                app,
                                new PostingCommand(
                                        "rev-fixture-" + IDS.next(),
                                        DATE,
                                        DATE,
                                        "rev-fixture",
                                        List.of(
                                                line(clearing, Direction.DEBIT, minor),
                                                line(wallet, Direction.CREDIT, minor))));
        return new Fixture(posted.entryId(), clearing, wallet);
    }

    /** The full-reversal lines: the original's with {@code Direction.opposite()} applied. */
    private static List<JournalLine> swapped(Fixture fixture, long minor) {
        return List.of(
                line(fixture.clearing(), Direction.CREDIT, minor),
                line(fixture.wallet(), Direction.DEBIT, minor));
    }

    private static ReversalCommand command(Fixture fixture, String key, long minor) {
        return new ReversalCommand(
                key, fixture.original(), DATE, DATE, "reversal-probe", swapped(fixture, minor));
    }

    private static JournalLine line(LedgerAccount account, Direction direction, long minor) {
        return new JournalLine(account.id(), direction, Money.ofMinorUnits(minor, USD));
    }

    private static Actor actor() {
        return new Actor("identity-reversal-probe", ActorType.CUSTOMER);
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
                "the losing side never blocked on " + table + " - without the advisory"
                        + " serializer the bound is judged on a snapshot that cannot see the"
                        + " winner");
    }

    // -----------------------------------------------------------------
    // Counters and row renderings - in the tables, never inferred

    /** The whole entry row, rendered by PostgreSQL itself — the byte-identity capture. */
    private static String rowOf(Connection app, JournalEntryId entry) throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT e::text FROM ledger.journal_entry e WHERE id = ?")) {
            read.setObject(1, entry.value());
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static List<String> lineRowsOf(Connection app, JournalEntryId entry)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT l::text FROM ledger.journal_line l WHERE entry_id = ?"
                                + " ORDER BY seq")) {
            read.setObject(1, entry.value());
            try (ResultSet rows = read.executeQuery()) {
                List<String> renderings = new ArrayList<>();
                while (rows.next()) {
                    renderings.add(rows.getString(1));
                }
                return renderings;
            }
        }
    }

    private static String entryTypeOf(Connection app, JournalEntryId entry)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT entry_type FROM ledger.journal_entry WHERE id = ?")) {
            read.setObject(1, entry.value());
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static UUID reversesOf(Connection app, JournalEntryId entry) throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT reverses_entry_id FROM ledger.journal_entry WHERE id = ?")) {
            read.setObject(1, entry.value());
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private static long reversalRowsOf(Connection app, JournalEntryId original)
            throws SQLException {
        try (PreparedStatement count =
                app.prepareStatement(
                        "SELECT count(*) FROM ledger.journal_entry"
                                + " WHERE reverses_entry_id = ?")) {
            count.setObject(1, original.value());
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long reversedSumOf(
            Connection app, JournalEntryId original, LedgerAccount account)
            throws SQLException {
        try (PreparedStatement sum =
                app.prepareStatement(
                        // A fixture checksum over stored values, not monetary arithmetic:
                        // the question is "what did the race commit".
                        "SELECT coalesce(sum(l.amount_minor), 0) FROM ledger.journal_line l"
                                + " JOIN ledger.journal_entry e ON e.id = l.entry_id"
                                + " WHERE e.reverses_entry_id = ?"
                                + " AND l.ledger_account_id = ?")) {
            sum.setObject(1, original.value());
            sum.setObject(2, account.id().value());
            try (ResultSet row = sum.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long postedMinorOf(Connection app, LedgerAccount account)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT posted_minor FROM ledger.account_balance"
                                + " WHERE ledger_account_id = ?")) {
            read.setObject(1, account.id().value());
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
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
