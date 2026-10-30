package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.AdjustmentCommand;
import com.finapp.ledger.AdjustmentProposalStatus;
import com.finapp.ledger.AdjustmentService;
import com.finapp.ledger.Direction;
import com.finapp.ledger.IllegalCutoffMoveException;
import com.finapp.ledger.JdbcAdjustmentProposalStore;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.PeriodClosedException;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingCutoffs;
import com.finapp.ledger.PostingObserver;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The ledger posting cutoff against a live PostgreSQL (`P14-TSK-004`; ADR-0114; {@code PHASE_14_PLAN.md} section 8.3
 * row 1; {@code INV-ACC-03}, {@code INV-ACC-08}) - every entry counted in the tables, never inferred.
 *
 * <p><strong>The cutoff is one global row.</strong> Every other suite posts in 2026; these tests lock periods of 1999
 * only, so nothing another suite posts is refused, and each test returns the cutoff to unset before and after itself
 * ({@link #restoreUnset}) through the ledger's own one-period moves. Written by `P14-TSK-004` and owed to the next
 * database pass (C5 - the owner's standing deviation).
 */
@Tag("database")
@DisplayName("the posting cutoff: serialised with every posting, raw SQL included (P14-TSK-004)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class PostingCutoffDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate IN_PERIOD = LocalDate.of(1999, 1, 20);
    private static final LocalDate CUT = LocalDate.of(1999, 1, 31);
    private static final LocalDate AFTER = LocalDate.of(1999, 2, 1);
    private static final LocalDate FEB = LocalDate.of(1999, 2, 28);
    private static final LocalDate MAR = LocalDate.of(1999, 3, 31);
    private static final int POSTERS = 10;

    private final JdbcLedgerAccountStore accounts = new JdbcLedgerAccountStore();
    private final PostingCutoffs cutoffs = new PostingCutoffs(IDS, CLOCK);
    private final List<PostingObserver.Command> refusalsSeen = new CopyOnWriteArrayList<>();
    private final PostingObserver observer = new PostingObserver() {
        @Override
        public void observe(Outcome outcome, Duration elapsed) {}

        @Override
        public void refusedAtTheCutoff(Command command) {
            refusalsSeen.add(command);
        }
    };

    @BeforeEach
    void startUnset() throws Exception {
        restoreUnset();
    }

    @AfterEach
    void leaveUnset() throws Exception {
        restoreUnset();
    }

    /** Back to unset through the ledger's own moves - one period at a time, whichever the history allows. */
    private void restoreUnset() throws Exception {
        for (int guard = 0; guard < 36; guard++) {
            Optional<LocalDate> standing;
            try (Connection app = DatabaseRoles.application()) {
                standing = cutoffs.closedThrough(app);
            }
            if (standing.isEmpty()) {
                return;
            }
            LocalDate from = standing.get();
            if (!moveBack(from, Optional.empty())) {
                assertThat(moveBack(from, Optional.of(from.withDayOfMonth(1).minusDays(1))))
                        .as("the cutoff steps back one period").isTrue();
            }
        }
        throw new AssertionError("the cutoff did not return to unset");
    }

    // ------------------------------------------------------------------ contention row 1

    @Test
    @DisplayName("ten posters and a lock: every entry dated in the period committed before the cut, or was refused")
    void tenPostersAndALockLeaveNoEntryAfterTheCut() throws Exception {
        String reference = "cutoff-race-" + IDS.next();
        List<LedgerAccount[]> pairs = pairs(POSTERS);
        ExecutorService pool = Executors.newFixedThreadPool(POSTERS + 1);
        try {
            CountDownLatch inserted = new CountDownLatch(POSTERS / 2);
            CountDownLatch release = new CountDownLatch(1);
            List<Future<String>> early = new ArrayList<>();
            for (int i = 0; i < POSTERS / 2; i++) {
                LedgerAccount[] pair = pairs.get(i);
                early.add(pool.submit(() -> poster(pair, reference, inserted, release)));
            }
            assertThat(inserted.await(30, TimeUnit.SECONDS)).as("five posters in flight, entries inserted").isTrue();

            // The lock of January 1999; at its commit it counts what is committed in the period - the cut.
            Future<Long> atTheCut = pool.submit(() -> {
                lockJanuary();
                return committedInPeriod(reference);
            });
            awaitWaiters("ExclusiveLock", 1);

            List<Future<String>> late = new ArrayList<>();
            for (int i = POSTERS / 2; i < POSTERS; i++) {
                LedgerAccount[] pair = pairs.get(i);
                late.add(pool.submit(() -> poster(pair, reference, null, null)));
            }
            awaitWaiters("ShareLock", POSTERS / 2);
            release.countDown();

            long cut = atTheCut.get(60, TimeUnit.SECONDS);
            List<String> earlyOutcomes = outcomes(early);
            List<String> lateOutcomes = outcomes(late);
            long committed = committedInPeriod(reference);

            assertThat(committed)
                    .as("INV-ACC-08: no entry dated in the locked period committed after the lock committed")
                    .isEqualTo(cut);
            assertThat(earlyOutcomes).as("in flight before the lock: the lock waited for them").containsOnly("COMMITTED");
            assertThat(lateOutcomes).as("queued behind the lock: they re-read the cutoff").containsOnly("REFUSED");
            assertThat(committed).isEqualTo(POSTERS / 2);
            assertThat(refusalsSeen).hasSize(POSTERS / 2).containsOnly(PostingObserver.Command.POSTING);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ every writer

    @Test
    @DisplayName("raw SQL is refused too: an entry on the cutoff day, a skipped period, an unevented move, a delete")
    void rawSqlIsRefusedToo() throws Exception {
        lockJanuary();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            assertThatThrownBy(() -> rawEntry(app, CUT))
                    .as("the cutoff day itself is in the locked period")
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(PostingCutoffs.PERIOD_CLOSED_SQLSTATE);
            app.rollback();

            rawEntry(app, AFTER); // the day after is open: not refused at insert (its missing lines fail at commit)
            app.rollback();

            assertThatThrownBy(() -> update(app,
                    "UPDATE ledger.posting_cutoff SET closed_through = DATE '1999-03-31', revision = revision + 1"))
                    .as("a skipped period").hasMessageContaining("ledger_posting_cutoff_moves_one_period");
            app.rollback();

            update(app, "UPDATE ledger.posting_cutoff SET closed_through = DATE '1999-02-28', revision = revision + 1");
            assertThatThrownBy(app::commit)
                    .as("one period, but no event row").hasMessageContaining("ledger_posting_cutoff_move_is_evented");
            app.rollback();

            assertThatThrownBy(() -> update(app, "DELETE FROM ledger.posting_cutoff"))
                    .as("the row never disappears").isInstanceOf(SQLException.class);
            app.rollback();
            assertThatThrownBy(() -> update(app, "UPDATE ledger.posting_cutoff_event SET reason = 'edited'"))
                    .as("the history is append-only").isInstanceOf(SQLException.class);
            app.rollback();
        }
        try (Connection app = DatabaseRoles.application()) {
            assertThat(cutoffs.closedThrough(app)).contains(CUT);
        }
    }

    @Test
    @DisplayName("a replay of a posting dated in the locked period is answered from its record - never refused")
    void aReplayIsNeverRefused() throws Exception {
        LedgerAccount[] pair = pairs(1).get(0);
        String key = "cutoff-replay-" + IDS.next();
        PostingResult first;
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            first = postings().post(app, command(key, pair, IN_PERIOD, "replay"));
            app.commit();
        }
        lockJanuary();
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            PostingResult replay = postings().post(app, command(key, pair, IN_PERIOD, "replay"));
            app.commit();
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.entryId()).isEqualTo(first.entryId());
        }
        assertThat(refusalsSeen).isEmpty();
    }

    @Test
    @DisplayName("the cutoff moves one period only, forward or back, each move evented; a replayed move is unchanged")
    void theCutoffMovesOnePeriodOnly() throws Exception {
        long before = events();
        assertThat(move(c -> cutoffs.advance(c, Optional.empty(), CUT, "lock January 1999")))
                .isEqualTo(PostingCutoffs.Outcome.MOVED);
        assertThat(move(c -> cutoffs.advance(c, Optional.empty(), CUT, "lock January 1999")))
                .as("a replay").isEqualTo(PostingCutoffs.Outcome.UNCHANGED);
        assertThatThrownBy(() -> move(c -> cutoffs.advance(c, Optional.of(CUT), MAR, "skip February")))
                .isInstanceOf(IllegalCutoffMoveException.class);
        assertThatThrownBy(() -> move(c -> cutoffs.advance(c, Optional.of(FEB), MAR, "a stale expectation")))
                .isInstanceOf(IllegalCutoffMoveException.class).hasMessageContaining("stands at 1999-01-31");
        assertThat(move(c -> cutoffs.advance(c, Optional.of(CUT), FEB, "lock February 1999")))
                .isEqualTo(PostingCutoffs.Outcome.MOVED);
        assertThatThrownBy(() -> move(c -> cutoffs.moveBack(c, FEB, Optional.empty(), "two periods back")))
                .as("February was not the first lock: back is January, never unset")
                .isInstanceOf(IllegalCutoffMoveException.class);
        assertThat(move(c -> cutoffs.moveBack(c, FEB, Optional.of(CUT), "unlock February 1999")))
                .isEqualTo(PostingCutoffs.Outcome.MOVED);
        assertThat(move(c -> cutoffs.moveBack(c, CUT, Optional.empty(), "unlock January 1999")))
                .isEqualTo(PostingCutoffs.Outcome.MOVED);
        assertThat(events() - before).as("four moves, four events").isEqualTo(4);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(cutoffs.closedThrough(app)).isEmpty();
        }
    }

    @Test
    @DisplayName("nothing is refused while the cutoff is unset, nor after it: the period's next day posts")
    void nothingOutsideTheLockedRangeIsRefused() throws Exception {
        LedgerAccount[] pair = pairs(1).get(0);
        post(pair, LocalDate.of(1990, 1, 1));
        lockJanuary();
        post(pair, AFTER);
        assertThat(refusalsSeen).isEmpty();
    }

    @Test
    @DisplayName("reversal and adjustment under a cutoff: dated into the lock refused (the proposal stays PROPOSED), dated after it posts")
    void theReversalAndTheAdjustmentAnswerTheCutoff() throws Exception {
        LedgerAccount[] pair = pairs(1).get(0);
        PostingResult original = post(pair, IN_PERIOD);
        Actor initiator = new Actor("cutoff-initiator-" + IDS.next(), ActorType.EMPLOYEE);
        Actor approver = new Actor("cutoff-approver-" + IDS.next(), ActorType.EMPLOYEE);
        com.finapp.ledger.AdjustmentProposalId proposal;
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(initiator);
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            proposal = adjustments().propose(app, new AdjustmentCommand("cutoff-adjust-" + IDS.next(), IN_PERIOD,
                    IN_PERIOD, "cutoff-adjustment", "proposed before the lock", lines(pair))).proposalId();
            app.commit();
        }
        lockJanuary();

        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            assertThatThrownBy(() -> reversals().reverse(app, reversal(original, pair, IN_PERIOD)))
                    .isInstanceOf(PeriodClosedException.class);
            app.rollback();
            PostingResult reversed = reversals().reverse(app, reversal(original, pair, AFTER));
            app.commit();
            assertThat(reversed.replayed()).as("a reversal dated in the open period posts").isFalse();
        }
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(approver);
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            assertThatThrownBy(() -> adjustments().approve(app, proposal)).isInstanceOf(PeriodClosedException.class);
            app.rollback();
            assertThat(adjustments().find(app, proposal).orElseThrow().status())
                    .as("the refused approval wrote nothing: the proposal stands, to be rejected and re-proposed")
                    .isEqualTo(AdjustmentProposalStatus.PROPOSED);
        }
        assertThat(refusalsSeen)
                .containsExactly(PostingObserver.Command.REVERSAL, PostingObserver.Command.ADJUSTMENT);
    }

    @Test
    @DisplayName("a lock queued behind a poster that waits on another poster's row never deadlocks them")
    void aQueuedLockNeverDeadlocksAPosterWaitingOnARow() throws Exception {
        String reference = "cutoff-soft-" + IDS.next();
        LedgerAccount[] s = pairs(1).get(0);
        LedgerAccount[] t = pairs(1).get(0);
        post(t, AFTER); // the projection rows exist, so T's lockBalancesInOrder holds a real row (unset cutoff: posts)
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try (Connection sConnection = DatabaseRoles.application();
                Connection tConnection = DatabaseRoles.application()) {
            sConnection.setAutoCommit(false);
            tConnection.setAutoCommit(false);
            // T holds its money row first (the lockInOrder idiom); S inserts an entry - holding the shared key.
            Future<?> tHolds = pool.submit(scoped(() -> {
                postings().lockBalancesInOrder(tConnection, List.of(t[0].id()));
                return null;
            }));
            tHolds.get(30, TimeUnit.SECONDS);
            Future<?> sFirst = pool.submit(scoped(() -> postings().post(sConnection,
                    command("soft-s1-" + IDS.next(), s, IN_PERIOD, reference))));
            sFirst.get(30, TimeUnit.SECONDS);
            // The lock queues behind S's shared hold.
            Future<Long> atTheCut = pool.submit(() -> {
                lockJanuary();
                return committedInPeriod(reference);
            });
            awaitWaiters("ExclusiveLock", 1);
            // T asks for the shared key - queued behind the lock (a soft edge) - then commits.
            Future<?> tPosts = pool.submit(scoped(() -> {
                postings().post(tConnection, command("soft-t-" + IDS.next(), t, IN_PERIOD, reference));
                tConnection.commit();
                return null;
            }));
            awaitWaiters("ShareLock", 1);
            // S now needs T's row: S -> T (row), T -> lock (queue), lock -> S (held). PostgreSQL re-orders the queue.
            postingInScope(sConnection, command("soft-s2-" + IDS.next(),
                    new LedgerAccount[] {t[0], s[1]}, IN_PERIOD, reference));
            sConnection.commit();
            tPosts.get(60, TimeUnit.SECONDS);
            long cut = atTheCut.get(60, TimeUnit.SECONDS);
            assertThat(committedInPeriod(reference)).as("all three committed before the cut").isEqualTo(3).isEqualTo(cut);
        } catch (ExecutionException failure) {
            throw new AssertionError("a participant failed - a deadlock (40P01) is the defect this test exists for",
                    failure.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a move behind a long posting waits bounded and retries, then gives up with nothing moved and its"
            + " transaction usable")
    void aBusyLockIsBoundedAndRetried() throws Exception {
        LedgerAccount[] pair = pairs(1).get(0);
        try (Connection poster = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            poster.setAutoCommit(false);
            postings().post(poster, command("cutoff-long-" + IDS.next(), pair, AFTER, "a long posting"));
            // The poster holds the shared key, uncommitted, for longer than every bounded attempt together.
            try (Connection app = DatabaseRoles.application()) {
                app.setAutoCommit(false);
                assertThatThrownBy(() -> cutoffs.advance(app, Optional.empty(), CUT, "lock January 1999"))
                        .isInstanceOf(com.finapp.ledger.PostingCutoffBusyException.class);
                assertThat(cutoffs.closedThrough(app)).as("the caller's transaction is still usable").isEmpty();
                app.rollback();
            }
            poster.commit();
        }
        lockJanuary(); // once the poster has committed, the same move goes through
    }

    @Test
    @DisplayName("namespace 14's two keys hash apart in this database")
    void theKeysDoNotCollide() throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT hashtext(?) <> hashtext(?)")) {
            select.setString(1, PostingCutoffs.KEY);
            select.setString(2, com.finapp.accounting.JdbcGlMappingStore.KEY);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                assertThat(row.getBoolean(1)).isTrue();
            }
        }
    }

    // ------------------------------------------------------------------ fixtures

    @FunctionalInterface
    private interface Move {
        PostingCutoffs.Outcome on(Connection unitOfWork);
    }

    private PostingCutoffs.Outcome move(Move move) throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            try {
                PostingCutoffs.Outcome outcome = move.on(app);
                app.commit();
                return outcome;
            } catch (RuntimeException refused) {
                app.rollback();
                throw refused;
            }
        }
    }

    private boolean moveBack(LocalDate from, Optional<LocalDate> to) throws Exception {
        try {
            move(c -> cutoffs.moveBack(c, from, to, "the test restores the cutoff"));
            return true;
        } catch (IllegalCutoffMoveException refused) {
            return false;
        }
    }

    private void lockJanuary() throws Exception {
        move(c -> cutoffs.advance(c, Optional.empty(), CUT, "lock January 1999 (test)"));
    }

    private String poster(LedgerAccount[] pair, String reference, CountDownLatch inserted, CountDownLatch release)
            throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            try {
                postings().post(app, command("cutoff-race-" + IDS.next(), pair, IN_PERIOD, reference));
            } catch (PeriodClosedException refused) {
                app.rollback();
                return "REFUSED";
            }
            if (inserted != null) {
                inserted.countDown();
                assertThat(release.await(60, TimeUnit.SECONDS)).isTrue();
            }
            app.commit();
            return "COMMITTED";
        }
    }

    private PostingResult post(LedgerAccount[] pair, LocalDate date) throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            PostingResult result = postings().post(app, command("cutoff-post-" + IDS.next(), pair, date, "post"));
            app.commit();
            return result;
        }
    }

    private void postingInScope(Connection app, PostingCommand command) throws Exception {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            postings().post(app, command);
        }
    }

    private static <T> Callable<T> scoped(Callable<T> work) {
        return () -> {
            try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                    CorrelationContext.Scope flow = flow()) {
                return work.call();
            }
        };
    }

    /** Waits (bounded) until {@code count} requests of {@code mode} on namespace 14 are queued, not granted. */
    private static void awaitWaiters(String mode, int count) throws Exception {
        Instant deadline = CLOCK.instant().plusSeconds(10);
        while (CLOCK.instant().isBefore(deadline)) {
            try (Connection app = DatabaseRoles.application();
                    PreparedStatement select = app.prepareStatement("SELECT count(*) FROM pg_locks WHERE locktype ="
                            + " 'advisory' AND classid = 14 AND objsubid = 2 AND NOT granted AND mode = ?")) {
                select.setString(1, mode);
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    if (row.getLong(1) >= count) {
                        return;
                    }
                }
            }
            Thread.onSpinWait();
        }
        // Not an assertion: under the probe (the shared lock dropped) nothing ever queues, and the INV-ACC-08
        // assertion is the one that must fail.
    }

    private static List<String> outcomes(List<Future<String>> futures) throws Exception {
        List<String> outcomes = new ArrayList<>();
        for (Future<String> future : futures) {
            outcomes.add(future.get(60, TimeUnit.SECONDS));
        }
        return outcomes;
    }

    private static long committedInPeriod(String reference) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT count(*) FROM ledger.journal_entry"
                        + " WHERE reference = ? AND posting_date <= ?")) {
            select.setString(1, reference);
            select.setObject(2, CUT);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long events() throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT count(*) FROM ledger.posting_cutoff_event");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    private static void rawEntry(Connection app, LocalDate postingDate) throws SQLException {
        try (PreparedStatement insert = app.prepareStatement("INSERT INTO ledger.journal_entry (id, posting_date,"
                + " value_date, entry_type, reference, actor_id, correlation_id, causation_id, idempotency_scope,"
                + " created_at) VALUES (?, ?, ?, 'POSTING', 'raw-sql', 'raw', ?, ?, ?, ?)")) {
            String correlation = UUID.randomUUID().toString();
            insert.setObject(1, IDS.next());
            insert.setObject(2, postingDate);
            insert.setObject(3, postingDate);
            insert.setString(4, correlation);
            insert.setString(5, correlation);
            insert.setString(6, PostingService.IDEMPOTENCY_SCOPE + ":raw-" + IDS.next());
            insert.setTimestamp(7, Timestamp.from(CLOCK.instant()));
            insert.executeUpdate();
        }
    }

    private static void update(Connection app, String sql) throws SQLException {
        try (PreparedStatement statement = app.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private List<LedgerAccount[]> pairs(int count) throws SQLException {
        List<LedgerAccount[]> pairs = new ArrayList<>();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            for (int i = 0; i < count; i++) {
                pairs.add(new LedgerAccount[] {account(app), account(app)});
            }
            app.commit();
        }
        return pairs;
    }

    private LedgerAccount account(Connection app) {
        return accounts.createOrConverge(app, LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY,
                AccountPurpose.CUSTOMER_WALLET, USD, IDS.next())).account();
    }

    private static List<JournalLine> lines(LedgerAccount[] pair) {
        return List.of(
                new JournalLine(pair[0].id(), Direction.DEBIT, Money.ofMinorUnits(700, USD)),
                new JournalLine(pair[1].id(), Direction.CREDIT, Money.ofMinorUnits(700, USD)));
    }

    private static PostingCommand command(String key, LedgerAccount[] pair, LocalDate date, String reference) {
        return new PostingCommand(key, date, date, reference, lines(pair));
    }

    private static ReversalCommand reversal(PostingResult original, LedgerAccount[] pair, LocalDate date) {
        return new ReversalCommand("cutoff-reverse-" + IDS.next(), original.entryId(), date, date, "reversal",
                List.of(new JournalLine(pair[0].id(), Direction.CREDIT, Money.ofMinorUnits(700, USD)),
                        new JournalLine(pair[1].id(), Direction.DEBIT, Money.ofMinorUnits(700, USD))));
    }

    private static CorrelationContext.Scope flow() {
        return CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
    }

    private static IdempotentExecutor executor() {
        return new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1),
                Duration.ofMinutes(5));
    }

    private PostingService postings() {
        return new PostingService(executor(), new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(),
                new JdbcOutboxWriter(), new JdbcBalanceProjection(), IDS, CLOCK, observer);
    }

    private ReversalService reversals() {
        return new ReversalService(executor(), new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(),
                new JdbcOutboxWriter(), new JdbcBalanceProjection(), IDS, CLOCK, observer);
    }

    private AdjustmentService adjustments() {
        return new AdjustmentService(executor(), new JdbcJournalEntryStore(IDS), new JdbcAdjustmentProposalStore(),
                new JdbcAuditWriter(), new JdbcOutboxWriter(), new JdbcBalanceProjection(), IDS, CLOCK, observer,
                accounts);
    }
}
