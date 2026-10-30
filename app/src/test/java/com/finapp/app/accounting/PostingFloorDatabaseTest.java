package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.AdjustmentCommand;
import com.finapp.ledger.AdjustmentProposalId;
import com.finapp.ledger.AdjustmentProposalStatus;
import com.finapp.ledger.AdjustmentReasonCode;
import com.finapp.ledger.AdjustmentService;
import com.finapp.ledger.Direction;
import com.finapp.ledger.IllegalCutoffMoveException;
import com.finapp.ledger.JdbcAdjustmentProposalStore;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.OwnedAdjustmentCommand;
import com.finapp.ledger.PeriodClosedException;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingCutoffs;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
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
 * C3's two reactions against a live PostgreSQL (`P14-TSK-006`; owner decision C3, ratified 2026-10-30; ADR-0114 decision 6;
 * {@code PHASE_14_PLAN.md} section 8.3 rows 1 and 11; {@code INV-ACC-03}, {@code -08}, {@code INV-IDEM-01}, {@code -03},
 * {@code INV-AUD-04}) - every entry counted in the tables, never inferred:
 *
 * <ul>
 *   <li>a system poster's {@code FLOOR_AT_FIRST_OPEN} posting dated into a locked period posts on the first open date,
 *       its value date kept and the stated date on its audit record; a timely one keeps its date;
 *   <li>its replay converges on the first entry across any later cutoff move - the fingerprint binds the stated date;
 *   <li>ten floored posters racing a lock: each committed dated in the period before the cut, or floored after it -
 *       none refused, nothing in the period after the cut;
 *   <li>a {@code MANUAL} and a {@code RECONCILIATION} adjustment dated into the locked period are refused at approval and
 *       stay {@code PROPOSED} - re-proposed in the open period, they post.
 * </ul>
 *
 * <p><strong>The cutoff is one global row.</strong> Every other suite posts in 2026; these tests lock periods of 1999
 * only and return the cutoff to unset before and after themselves ({@code PostingCutoffDatabaseTest}'s discipline).
 * Written by `P14-TSK-006` and owed to the next database pass (C5).
 */
@Tag("database")
@DisplayName("C3 against the database: system posters floored, adjustments refused at approval (P14-TSK-006)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class PostingFloorDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate IN_PERIOD = LocalDate.of(1999, 1, 20);
    private static final LocalDate CUT = LocalDate.of(1999, 1, 31);
    private static final LocalDate AFTER = LocalDate.of(1999, 2, 1);
    private static final LocalDate FEB = LocalDate.of(1999, 2, 28);
    private static final int POSTERS = 10;

    private final JdbcLedgerAccountStore accounts = new JdbcLedgerAccountStore();
    private final PostingCutoffs cutoffs = new PostingCutoffs(IDS, CLOCK);
    private final List<PostingObserver.Command> refusalsSeen = new CopyOnWriteArrayList<>();
    private final List<PostingObserver.Command> floorsSeen = new CopyOnWriteArrayList<>();
    private final PostingObserver observer = new PostingObserver() {
        @Override
        public void observe(Outcome outcome, Duration elapsed) {}

        @Override
        public void refusedAtTheCutoff(Command command) {
            refusalsSeen.add(command);
        }

        @Override
        public void flooredAtTheCutoff(Command command) {
            floorsSeen.add(command);
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

    // ------------------------------------------------------------------ the floor

    @Test
    @DisplayName("a floored posting dated into the locked period posts on the first open date: its value date kept, its"
            + " key unchanged, the stated date on its audit record - never refused")
    void aFlooredPostingDatedIntoALockPostsOnTheFirstOpenDate() throws Exception {
        LedgerAccount[] pair = pairs(1).get(0);
        lockJanuary();
        String key = "floor-post-" + IDS.next();
        PostingResult posted = post(floored(key, pair, IN_PERIOD, "floor"));

        assertThat(posted.replayed()).isFalse();
        assertThat(dates(posted)).containsExactly(AFTER, IN_PERIOD);
        assertThat(scalar("SELECT idempotency_scope FROM ledger.journal_entry WHERE id = ?", posted.entryId().value()))
                .as("the key unchanged").isEqualTo(PostingService.IDEMPOTENCY_SCOPE + ":" + key);
        assertThat(scalar("SELECT change_summary FROM platform.audit_record WHERE target_id = ?",
                posted.entryId().value().toString())).contains("flooredFrom=1999-01-20");
        assertThat(refusalsSeen).isEmpty();
        assertThat(floorsSeen).containsExactly(PostingObserver.Command.POSTING);
    }

    @Test
    @DisplayName("a timely floored posting keeps its date: no lock, or a date after it")
    void aTimelyFlooredPostingKeepsItsDate() throws Exception {
        LedgerAccount[] pair = pairs(1).get(0);
        PostingResult unset = post(floored("floor-timely-" + IDS.next(), pair, IN_PERIOD, "timely"));
        lockJanuary();
        PostingResult after = post(floored("floor-timely-" + IDS.next(), pair, AFTER, "timely"));
        assertThat(dates(unset)).containsExactly(IN_PERIOD, IN_PERIOD);
        assertThat(dates(after)).containsExactly(AFTER, IN_PERIOD);
        assertThat(floorsSeen).isEmpty();
    }

    @Test
    @DisplayName("a replay converges on the first entry across any cutoff move: posted in the period then replayed after"
            + " its lock, floored then replayed after the next lock, and an AS_STATED twin - one entry per key, never a"
            + " conflict, never re-dated")
    void aReplayAcrossALockConvergesOnTheFirstPosting() throws Exception {
        LedgerAccount[] pair = pairs(1).get(0);
        String before = "floor-replay-" + IDS.next();
        PostingResult first = post(floored(before, pair, IN_PERIOD, "replay"));
        lockJanuary();
        PostingResult replay = post(floored(before, pair, IN_PERIOD, "replay"));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.entryId()).isEqualTo(first.entryId());
        assertThat(dates(replay)).as("still dated in the period it was posted before the lock")
                .containsExactly(IN_PERIOD, IN_PERIOD);

        String after = "floor-replay-" + IDS.next();
        PostingResult floored = post(floored(after, pair, IN_PERIOD, "replay"));
        assertThat(dates(floored)).containsExactly(AFTER, IN_PERIOD);
        move(c -> cutoffs.advance(c, Optional.of(CUT), FEB, "lock February 1999"));
        PostingResult again = post(floored(after, pair, IN_PERIOD, "replay"));
        assertThat(again.replayed()).isTrue();
        assertThat(again.entryId()).isEqualTo(floored.entryId());
        assertThat(dates(again)).as("never re-floored to March").containsExactly(AFTER, IN_PERIOD);
        PostingResult twin = post(new PostingCommand(after, IN_PERIOD, IN_PERIOD, "replay", lines(pair)));
        assertThat(twin.replayed()).as("the AS_STATED twin is the same request").isTrue();
        assertThat(twin.entryId()).isEqualTo(floored.entryId());

        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope IN (?, ?)",
                PostingService.IDEMPOTENCY_SCOPE + ":" + before, PostingService.IDEMPOTENCY_SCOPE + ":" + after))
                .as("one entry per key").isEqualTo(2);
        assertThat(refusalsSeen).isEmpty();
    }

    // ------------------------------------------------------------------ contention: ten floored posters vs a lock

    @Test
    @DisplayName("ten floored posters and a lock: five in flight commit dated in the period before the cut, five queued"
            + " behind it are floored to the first open date - none refused, nothing in the period after the cut")
    void tenFlooredPostersAndALockRefuseNothing() throws Exception {
        String reference = "floor-race-" + IDS.next();
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
            assertThat(outcomes(early)).as("in flight before the lock: dated in the period").containsOnly(
                    "COMMITTED " + IN_PERIOD);
            assertThat(outcomes(late)).as("queued behind the lock: floored, not refused").containsOnly(
                    "COMMITTED " + AFTER);
            assertThat(committedInPeriod(reference))
                    .as("INV-ACC-08: no entry dated in the locked period committed after the lock committed")
                    .isEqualTo(cut).isEqualTo(POSTERS / 2);
            assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ? AND posting_date = ?::date"
                    + " AND value_date = ?::date", reference, AFTER.toString(), IN_PERIOD.toString()))
                    .as("the floored five keep their value date").isEqualTo(POSTERS / 2);
            assertThat(refusalsSeen).as("none refused").isEmpty();
            assertThat(floorsSeen).hasSize(POSTERS / 2);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ the adjustments: refused at approval

    @Test
    @DisplayName("a MANUAL adjustment dated into the locked period is refused at approval and stays PROPOSED - no entry;"
            + " rejected and re-proposed in the open period, it posts on that date")
    void aManualAdjustmentDatedIntoALockIsRefusedAtApprovalAndStaysProposed() throws Exception {
        LedgerAccount[] pair = pairs(1).get(0);
        Actor initiator = new Actor("floor-initiator-" + IDS.next(), ActorType.EMPLOYEE);
        Actor approver = new Actor("floor-approver-" + IDS.next(), ActorType.EMPLOYEE);
        AdjustmentProposalId stale = as(initiator, app -> adjustments().propose(app, new AdjustmentCommand(
                "floor-adjust-" + IDS.next(), IN_PERIOD, IN_PERIOD, "floor-adjustment", "proposed before the lock",
                lines(pair))).proposalId());
        lockJanuary();

        assertThatThrownBy(() -> as(approver, app -> adjustments().approve(app, stale)))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("refused at approval and left PROPOSED");
        assertThat(status(stale)).isEqualTo(AdjustmentProposalStatus.PROPOSED);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.adjust.approve:" + stale.value())).as("nothing posted").isZero();
        assertThat(refusalsSeen).containsExactly(PostingObserver.Command.ADJUSTMENT);

        // The operator's way out (C3): withdraw it, propose it again in the open period.
        boolean withdrawn = as(initiator, app -> adjustments().reject(app, stale));
        assertThat(withdrawn).isTrue();
        AdjustmentProposalId reproposed = as(initiator, app -> adjustments().propose(app, new AdjustmentCommand(
                "floor-adjust-" + IDS.next(), AFTER, IN_PERIOD, "floor-adjustment", "re-proposed in the open period",
                lines(pair))).proposalId());
        PostingResult posted = as(approver, app -> adjustments().approve(app, reproposed));
        assertThat(dates(posted)).as("dated as proposed - an adjustment is never floored")
                .containsExactly(AFTER, IN_PERIOD);
        assertThat(floorsSeen).isEmpty();
    }

    @Test
    @DisplayName("a reconciliation resolution's adjustment dated into the locked period is refused at approval"
            + " (approveOwned) and stays PROPOSED - no entry")
    void aReconciliationAdjustmentDatedIntoALockIsRefusedAtApprovalAndStaysProposed() throws Exception {
        LedgerAccount[] pair = pairs(1).get(0);
        Actor resolver = new Actor("floor-resolver-" + IDS.next(), ActorType.EMPLOYEE);
        Actor approver = new Actor("floor-approver-" + IDS.next(), ActorType.EMPLOYEE);
        AdjustmentProposalId owned = as(resolver, app -> adjustments().proposeOwned(app, new OwnedAdjustmentCommand(
                IN_PERIOD, IN_PERIOD, "resolution=" + IDS.next(), "a write-off proposed before the lock",
                AdjustmentReasonCode.RECONCILIATION_WRITE_OFF, lines(pair))));
        lockJanuary();

        assertThatThrownBy(() -> as(approver, app -> adjustments().approveOwned(app, owned)))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("RECONCILIATION adjustment proposal");
        assertThat(status(owned)).isEqualTo(AdjustmentProposalStatus.PROPOSED);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.adjust.approve:" + owned.value())).isZero();
        assertThat(refusalsSeen).containsExactly(PostingObserver.Command.ADJUSTMENT);
    }

    // ------------------------------------------------------------------ fixtures

    @FunctionalInterface
    private interface Work<T> {
        T on(Connection unitOfWork) throws Exception;
    }

    /** {@code work} as {@code actor} in its own transaction - rolled back when it throws. */
    private static <T> T as(Actor actor, Work<T> work) throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope scope = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            try {
                T result = work.on(app);
                app.commit();
                return result;
            } catch (Exception failure) {
                app.rollback();
                throw failure;
            }
        }
    }

    private PostingResult post(PostingCommand command) throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            PostingResult result = postings().post(app, command);
            app.commit();
            return result;
        }
    }

    /** A floored poster: holds its entry uncommitted until released, when given a latch; answers its outcome and date. */
    private String poster(LedgerAccount[] pair, String reference, CountDownLatch inserted, CountDownLatch release)
            throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            PostingResult result;
            try {
                result = postings().post(app, floored("floor-race-" + IDS.next(), pair, IN_PERIOD, reference));
            } catch (PeriodClosedException refused) {
                app.rollback();
                return "REFUSED";
            }
            if (inserted != null) {
                inserted.countDown();
                assertThat(release.await(60, TimeUnit.SECONDS)).isTrue();
            }
            app.commit();
            return "COMMITTED " + dates(result).get(0);
        }
    }

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

    private void lockJanuary() throws Exception {
        move(c -> cutoffs.advance(c, Optional.empty(), CUT, "lock January 1999 (P14-TSK-006 test)"));
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

    private boolean moveBack(LocalDate from, Optional<LocalDate> to) throws Exception {
        try {
            move(c -> cutoffs.moveBack(c, from, to, "the test restores the cutoff"));
            return true;
        } catch (IllegalCutoffMoveException refused) {
            return false;
        }
    }

    /** Waits (bounded) until {@code count} requests of {@code mode} on namespace 14 are queued, not granted. */
    private static void awaitWaiters(String mode, int count) throws Exception {
        Instant deadline = CLOCK.instant().plusSeconds(10);
        while (CLOCK.instant().isBefore(deadline)) {
            if (count("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND classid = 14 AND objsubid = 2"
                    + " AND NOT granted AND mode = ?", mode) >= count) {
                return;
            }
            Thread.onSpinWait();
        }
    }

    private static List<String> outcomes(List<Future<String>> futures) throws Exception {
        List<String> outcomes = new ArrayList<>();
        for (Future<String> future : futures) {
            outcomes.add(future.get(60, TimeUnit.SECONDS));
        }
        return outcomes;
    }

    private static long committedInPeriod(String reference) throws SQLException {
        return count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ? AND posting_date <= ?::date",
                reference, CUT.toString());
    }

    /** The entry's posting date, then its value date. */
    private static List<LocalDate> dates(PostingResult result) throws SQLException {
        return List.of(
                LocalDate.parse(scalar("SELECT posting_date::text FROM ledger.journal_entry WHERE id = ?",
                        result.entryId().value())),
                LocalDate.parse(scalar("SELECT value_date::text FROM ledger.journal_entry WHERE id = ?",
                        result.entryId().value())));
    }

    private static AdjustmentProposalStatus status(AdjustmentProposalId id) throws SQLException {
        return AdjustmentProposalStatus.valueOf(scalar("SELECT status FROM ledger.adjustment_proposal WHERE id = ?",
                id.value()));
    }

    private static long count(String sql, Object... parameters) throws SQLException {
        return Long.parseLong(scalar(sql, parameters));
    }

    /** Read as the migrator - the audit trail is not the application role's to read. */
    private static String scalar(String sql, Object... parameters) throws SQLException {
        try (Connection app = DatabaseRoles.migrator(); PreparedStatement select = app.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
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

    private static PostingCommand floored(String key, LedgerAccount[] pair, LocalDate stated, String reference) {
        return new PostingCommand(key, stated, IN_PERIOD, reference, lines(pair),
                PostingCommand.Dating.FLOOR_AT_FIRST_OPEN);
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

    private AdjustmentService adjustments() {
        return new AdjustmentService(executor(), new JdbcJournalEntryStore(IDS), new JdbcAdjustmentProposalStore(),
                new JdbcAuditWriter(), new JdbcOutboxWriter(), new JdbcBalanceProjection(), IDS, CLOCK, observer,
                accounts);
    }
}
