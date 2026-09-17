package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.AccountType;
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
import com.finapp.ledger.PostingService;
import com.finapp.ledger.ProjectionVerification;
import com.finapp.ledger.ProjectionVerification.Verdict;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * `P3-TST-001`: {@code INV-BAL-02} under sustained concurrent posting — the F2 supplement
 * criterion and M3.3's own acceptance, demonstrated rather than assembled from two green
 * halves (`P3-TSK-009` proved the race, `P3-TSK-010` proved the tolerance; the `P1-TSK-027`
 * lesson is that nothing joins two working pieces until something drives them together).
 *
 * <h2>Overlap by construction, never by timing luck</h2>
 *
 * <p>The posters run until the <em>verifier</em> ends the storm: it keeps sweeping until it
 * has completed {@link #MIN_SWEEPS_UNDER_LOAD} verdicts <strong>and</strong> observed
 * {@link #MIN_ENTRIES_UNDER_LOAD} committed entries, so the sweeps demonstrably ran while
 * postings were landing — structurally, with no sleeps (`P0-TST-004`'s rule). Every
 * mid-storm verdict must be {@code CLEAN} or {@code IN_FLIGHT} and never {@code DRIFTING}:
 * that is "replay-from-zero equals the projection <em>throughout</em>", stated through the
 * delivered verification machinery rather than reinvented beside it.
 *
 * <h2>The rebuild procedure this test records</h2>
 *
 * <p>Rebuild tooling deliberately did not arrive (`P3-TSK-009`'s gate) — a rebuild is the
 * migrator's act, and the safe procedure under live posting is <strong>lock-then-look</strong>
 * (`P2-TSK-015`'s finding): take {@code SELECT … FOR UPDATE} on the projection row, then
 * recompute and overwrite in a <em>fresh</em> statement whose snapshot postdates the lock
 * grant. A single {@code UPDATE} with recomputing subqueries is deliberately NOT the
 * procedure: a blocked update re-evaluates its subqueries against the statement's
 * <em>original</em> snapshot, so it would silently lose exactly the posting it blocked on.
 * Under the lock, a posting committed before it is in the recomputation and its delta is
 * replaced rather than double-counted; one still in flight is absent from the recomputation
 * and its delta applies after — {@code rebuilt + delta} is exact either way.
 */
@Tag("database")
@DisplayName("INV-BAL-02 under sustained concurrent posting (P3-TST-001)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class SustainedConcurrentPostingDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 16);

    private static final int POSTERS = 10;

    /**
     * The floor that makes "sustained" a fact rather than a word: the first run of this
     * test met a 10-sweep/30-entry floor in ~300ms on a warm container, which is a gust,
     * not a storm. These bounds hold the overlap open for hundreds of commits and dozens
     * of sweeps — still seconds, and every sweep is asserted drift-free.
     */
    private static final int MIN_SWEEPS_UNDER_LOAD = 25;

    private static final int MIN_ENTRIES_UNDER_LOAD = 200;

    private final LedgerAccountStore<Connection> accounts = new JdbcLedgerAccountStore();
    private final ProjectionVerification verification =
            new ProjectionVerification(new JdbcBalanceDerivation());

    @Test
    @DisplayName("replay from zero equals the projection throughout ten instances posting"
            + " continuously")
    void replayEqualsTheProjectionThroughoutSustainedPosting() throws Exception {
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

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong committedMinor = new AtomicLong();
        AtomicLong committedEntries = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(POSTERS);
        List<Future<Void>> posters = new ArrayList<>();
        for (int i = 0; i < POSTERS; i++) {
            posters.add(pool.submit(poster(
                    wallet, clearing, stop, committedMinor, committedEntries)));
        }

        Map<Verdict, Long> sweeps = new EnumMap<>(Verdict.class);
        try (Connection own = DatabaseRoles.application()) {
            long completed = 0;
            // The verifier ends the storm, so "the job ran while postings continued" is a
            // property of the loop's own exit condition rather than of scheduling luck.
            // The sweep cap is a FAILURE bound, never a pass: if the posters have died the
            // loop must end so poster.get() below can report the real cause.
            while ((completed < MIN_SWEEPS_UNDER_LOAD
                            || committedEntries.get() < MIN_ENTRIES_UNDER_LOAD)
                    && completed < 10_000) {
                Verdict verdict = verification.verdictOf(own, wallet.id());
                sweeps.merge(verdict, 1L, Long::sum);
                completed++;
                assertThat(verdict)
                        .as("mid-storm sweep %s: the projection may be mid-entry"
                                + " (IN_FLIGHT) but never wrong (DRIFTING)", completed)
                        .isNotEqualTo(Verdict.DRIFTING);
            }
        } finally {
            stop.set(true);
            pool.shutdown();
        }
        for (Future<Void> poster : posters) {
            poster.get(); // Propagates any posting failure.
        }

        assertThat(sweeps.values().stream().mapToLong(Long::longValue).sum())
                .as("the sweeps really ran under load")
                .isGreaterThanOrEqualTo(MIN_SWEEPS_UNDER_LOAD);
        assertThat(committedEntries.get())
                .as("the storm really was a storm")
                .isGreaterThanOrEqualTo(MIN_ENTRIES_UNDER_LOAD);

        try (Connection app = DatabaseRoles.application()) {
            assertThat(verification.verdictOf(app, wallet.id()))
                    .as("settled: the projection equals replay-from-zero, watermark included")
                    .isEqualTo(Verdict.CLEAN);
            assertThat(postedMinorOf(app, wallet.id()))
                    .as("no increment lost: the row equals the posters' own committed tally,"
                            + " tracked outside the kernel")
                    .isEqualTo(committedMinor.get());
        }
    }

    @Test
    @DisplayName("a lock-then-look rebuild while postings continue loses nothing")
    void aRebuildWhilePostingsContinueLosesNothing() throws Exception {
        LedgerAccount wallet;
        LedgerAccount clearing;
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            wallet = wallet(app);
            clearing = operational(app);
            post(app, entry(clearing, Direction.DEBIT, wallet, Direction.CREDIT, 1000));
            app.commit();
        }

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong committedMinor = new AtomicLong(1000);
        AtomicLong committedEntries = new AtomicLong(1);
        ExecutorService pool = Executors.newFixedThreadPool(POSTERS);
        List<Future<Void>> posters = new ArrayList<>();
        for (int i = 0; i < POSTERS; i++) {
            posters.add(pool.submit(poster(
                    wallet, clearing, stop, committedMinor, committedEntries)));
        }

        try {
            // Corrupt, then rebuild MID-STORM, three times: the corruption proves each
            // rebuild genuinely rewrites rather than no-ops, and waiting for further
            // committed entries between rounds proves each rebuild ran against live
            // traffic rather than a quiet table.
            for (int round = 1; round <= 3; round++) {
                long entriesBefore = committedEntries.get();
                corrupt(wallet.id());
                rebuildLockThenLook(wallet.id());
                // Wait on the CONDITION - three more committed entries, so the next round's
                // rebuild provably runs against live traffic - with a deadline that is a
                // failure bound, never a pass (the P1-TSK-002 rule): if the posters have
                // died, fall through so poster.get() below reports the real cause.
                long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
                while (committedEntries.get() < entriesBefore + 3
                        && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
            }
        } finally {
            stop.set(true);
            pool.shutdown();
        }
        for (Future<Void> poster : posters) {
            poster.get();
        }

        try (Connection app = DatabaseRoles.application()) {
            assertThat(verification.verdictOf(app, wallet.id()))
                    .as("after corruption, three mid-storm rebuilds and the storm's end,"
                            + " the projection equals replay-from-zero")
                    .isEqualTo(Verdict.CLEAN);
            assertThat(postedMinorOf(app, wallet.id()))
                    .as("nothing lost to the rebuild: neither a delta it raced nor one"
                            + " in flight while it held the lock")
                    .isEqualTo(committedMinor.get());
        }
    }

    // ------------------------------------------------------------------ the storm

    /** One posting instance: own connection, own scopes, posts until told to stop. */
    private Callable<Void> poster(
            LedgerAccount wallet,
            LedgerAccount clearing,
            AtomicBoolean stop,
            AtomicLong committedMinor,
            AtomicLong committedEntries) {
        return () -> {
            try (Connection own = DatabaseRoles.application();
                    SecurityContext.Scope actor = SecurityContext.enterSystem();
                    CorrelationContext.Scope flow = flow()) {
                own.setAutoCommit(false);
                while (!stop.get()) {
                    long minor = 100;
                    post(own, entry(clearing, Direction.DEBIT, wallet,
                            Direction.CREDIT, minor));
                    own.commit();
                    // Tallied only AFTER the commit: the tally is the independent record
                    // of what was durably posted, which is what the row is held to.
                    committedMinor.addAndGet(minor);
                    committedEntries.incrementAndGet();
                }
            }
            return null;
        };
    }

    /** The writer nobody wrote: the row's number bent, so a rebuild has something to fix. */
    private static void corrupt(LedgerAccountId account) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement bend =
                        app.prepareStatement(
                                "UPDATE ledger.account_balance"
                                        + " SET posted_minor = posted_minor + 7"
                                        + " WHERE ledger_account_id = ?")) {
            bend.setObject(1, account.value());
            assertThat(bend.executeUpdate()).isEqualTo(1);
        }
    }

    /**
     * The migrator's rebuild, by the only protocol that is safe under live posting: lock,
     * then look (see class doc). The recomputation is raw SQL over a single-currency,
     * single-scale account — real rebuild tooling must refuse a mixed-scale history the way
     * the derivation does; this is the operator procedure, recorded where it is proven.
     */
    private static void rebuildLockThenLook(LedgerAccountId account) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            try (PreparedStatement lock =
                    migrator.prepareStatement(
                            "SELECT posted_minor FROM ledger.account_balance"
                                    + " WHERE ledger_account_id = ? FOR UPDATE")) {
                lock.setObject(1, account.value());
                try (ResultSet row = lock.executeQuery()) {
                    assertThat(row.next()).as("the row under rebuild exists").isTrue();
                }
            }
            // The FRESH statement: its snapshot postdates the lock grant, so it sees every
            // posting that committed before the lock and none that is still in flight -
            // whose delta will apply on top after the commit below releases the row.
            try (PreparedStatement rebuild =
                    migrator.prepareStatement(
                            "UPDATE ledger.account_balance balance SET"
                                    + " posted_minor = ("
                                    + "   SELECT COALESCE(SUM(CASE WHEN line.direction ="
                                    + "     account.normal_balance THEN line.amount_minor"
                                    + "     ELSE -line.amount_minor END), 0)"
                                    + "   FROM ledger.journal_line line"
                                    + "   WHERE line.ledger_account_id ="
                                    + "     balance.ledger_account_id),"
                                    + " last_entry_seq = ("
                                    + "   SELECT COUNT(DISTINCT line.entry_id)"
                                    + "   FROM ledger.journal_line line"
                                    + "   WHERE line.ledger_account_id ="
                                    + "     balance.ledger_account_id)"
                                    + " FROM ledger.ledger_account account"
                                    + " WHERE account.id = balance.ledger_account_id"
                                    + " AND balance.ledger_account_id = ?")) {
                rebuild.setObject(1, account.value());
                assertThat(rebuild.executeUpdate()).isEqualTo(1);
            }
            migrator.commit();
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

    private void post(Connection app, PostingCommand command) {
        service().post(app, command);
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
                "sustained-" + IDS.next(), DATE, DATE, "probe-event",
                List.of(
                        new JournalLine(first.id(), firstSide,
                                Money.ofMinorUnits(minorUnits, USD)),
                        new JournalLine(second.id(), secondSide,
                                Money.ofMinorUnits(minorUnits, USD))));
    }

    private static long postedMinorOf(Connection app, LedgerAccountId account)
            throws SQLException {
        try (PreparedStatement select =
                app.prepareStatement(
                        "SELECT posted_minor FROM ledger.account_balance"
                                + " WHERE ledger_account_id = ?")) {
            select.setObject(1, account.value());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static CorrelationContext.Scope flow() {
        return CorrelationContext.enter(
                Correlation.startingWith(CorrelationId.generate(IDS)));
    }
}
