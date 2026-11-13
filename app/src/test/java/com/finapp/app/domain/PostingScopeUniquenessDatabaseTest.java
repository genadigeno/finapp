package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalEntry;
import com.finapp.ledger.JournalEntryType;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingAttribution;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingScopeTakenException;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.ReversalCommand;
import com.finapp.ledger.ReversalService;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyConflictException;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * One journal entry per posting scope, for ever, and the idempotency record's sweep, against a live PostgreSQL (`P16-TSK-011`;
 * ledger {@code V036}/{@code V037}, platform {@code V016}; ADR-0141). <strong>Written and owed</strong>: nothing runs the database tier
 * per task (`POSTPONED_ISSUES.md` PI-023's kind). Each fixture was walked against the DDL it writes: an idempotency record's
 * {@code CHECK}s ({@code expires_at > created_at}, an {@code IN_PROGRESS} claim leased and with no outcome, a terminal one timestamped
 * and with its body and type together) and the journal's (two balanced lines, a reference and scope within their bounds).
 *
 * <p>Every sweep that does not need ten connections runs inside a transaction this test rolls back, so the records other suites
 * left in the shared database are untouched; the ten sweepers commit, and delete only what production's sweep would.
 */
@Tag("database")
@DisplayName("one journal entry per posting scope, and the idempotency sweep (P16-TSK-011)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class PostingScopeUniquenessDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 13);
    private static final long A_DAY_MS = 86_400_000L;

    private final LedgerAccountStore<Connection> accounts = new JdbcLedgerAccountStore();

    @Test
    @DisplayName("the census found no duplicated scope on this database, and the unique index stands valid")
    void theCensusFoundNoDuplicateAndTheIndexStands() throws Exception {
        try (Connection owner = DatabaseRoles.bootstrap()) {
            assertThat(count(owner, "SELECT count(*) FROM ledger.posting_scope_census")).isPositive();
            assertThat(count(owner, "SELECT count(*) FROM ledger.posting_scope_census"
                    + " WHERE duplicated_scopes <> 0 OR surplus_entries <> 0 OR duplicated_scopes IS NULL")).isZero();
            assertThat(count(owner, "SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid"
                    + " WHERE c.relname = 'journal_entry_one_per_idempotency_scope' AND i.indisunique AND i.indisvalid"
                    + " AND i.indrelid = 'ledger.journal_entry'::regclass")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("ten writers that claimed nothing append one scope at once: one entry, nine told the scope is taken, none aborted")
    void tenWritersOfOneScopeLeaveOneEntry() throws Exception {
        LedgerAccount debit;
        LedgerAccount credit;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            debit = account(app);
            credit = account(app);
            app.commit();
        }
        String scope = PostingService.IDEMPOTENCY_SCOPE + ":race-" + IDS.next();
        CyclicBarrier start = new CyclicBarrier(10);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<String>> outcomes = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                outcomes.add(pool.submit(() -> {
                    try (Connection own = DatabaseRoles.application()) {
                        own.setAutoCommit(false);
                        JournalEntry entry = JournalEntry.balanced(IDS, CLOCK, DATE, DATE, lines(debit, credit, 700));
                        start.await(30, TimeUnit.SECONDS);
                        try {
                            new JdbcJournalEntryStore(IDS).append(own, entry, attribution(scope));
                            own.commit();
                            return "appended";
                        } catch (PostingScopeTakenException taken) {
                            // Nothing aborted: the transaction still answers a statement and commits.
                            assertThat(count(own, "SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?", scope))
                                    .isEqualTo(1);
                            own.commit();
                            return "taken";
                        }
                    }
                }));
            }
            List<String> results = new ArrayList<>();
            for (Future<String> outcome : outcomes) {
                results.add(outcome.get(60, TimeUnit.SECONDS));
            }
            assertThat(results).filteredOn("appended"::equals).hasSize(1);
            assertThat(results).filteredOn("taken"::equals).hasSize(9);
        } finally {
            pool.shutdownNow();
        }
        try (Connection app = DatabaseRoles.application()) {
            assertThat(count(app, "SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?", scope)).isEqualTo(1);
            assertThat(count(app, "SELECT count(*) FROM ledger.journal_line l JOIN ledger.journal_entry e ON e.id = l.entry_id"
                    + " WHERE e.idempotency_scope = ?", scope)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a posting retried after its record is swept answers the recorded entry: one entry, one audit, a fresh record")
    void aPostingRetriedAfterItsRecordIsSweptAnswersTheEntry() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount debit = account(app);
            LedgerAccount credit = account(app);
            PostingCommand command = posting("swept-" + IDS.next(), debit, credit, 1500);
            PostingResult first = postings().post(app, command);
            app.commit();

            sweepByHand(PostingService.IDEMPOTENCY_SCOPE, command.idempotencyKey());
            PostingResult again = postings().post(app, command);
            app.commit();

            assertThat(again.entryId()).isEqualTo(first.entryId());
            assertThat(again.replayed()).isTrue();
            String scope = PostingService.IDEMPOTENCY_SCOPE + ":" + command.idempotencyKey();
            assertThat(count(app, "SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?", scope)).isEqualTo(1);
            assertThat(count(app, "SELECT count(*) FROM platform.audit_record WHERE operation = 'ledger.JournalEntryPosted'"
                    + " AND target_id = ?", first.entryId().value().toString())).as("no second audit").isEqualTo(1);
            assertThat(count(app, "SELECT count(*) FROM platform.idempotency_record WHERE scope = ? AND idempotency_key = ?"
                    + " AND state = 'COMPLETED' AND convert_from(response_body, 'UTF8') = ?",
                    PostingService.IDEMPOTENCY_SCOPE, command.idempotencyKey(), first.entryId().value().toString()))
                    .as("the retry's claim completed with the same response").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a different request under a swept key is the conflict, and writes nothing")
    void aDifferentRequestUnderASweptKeyIsTheConflict() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount debit = account(app);
            LedgerAccount credit = account(app);
            String key = "swept-other-" + IDS.next();
            postings().post(app, posting(key, debit, credit, 1500));
            app.commit();

            sweepByHand(PostingService.IDEMPOTENCY_SCOPE, key);
            assertThatThrownBy(() -> postings().post(app, posting(key, debit, credit, 1600)))
                    .isInstanceOf(IdempotencyConflictException.class);
            app.rollback();

            assertThat(count(app, "SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                    PostingService.IDEMPOTENCY_SCOPE + ":" + key)).isEqualTo(1);
            assertThat(count(app, "SELECT count(*) FROM platform.idempotency_record WHERE scope = ? AND idempotency_key = ?",
                    PostingService.IDEMPOTENCY_SCOPE, key)).as("the refused claim rolled back with it").isZero();
        }
    }

    @Test
    @DisplayName("a full reversal retried, its record kept or swept, is answered by the journal before the bound reads it as over-reversing")
    void aFullReversalRetriedIsAnsweredByTheJournal() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount wallet = account(app);
            LedgerAccount clearing = accounts.findOperational(app, AccountPurpose.FEE_REVENUE, USD).orElseThrow();
            PostingResult original = postings().post(app, posting("rev-original-" + IDS.next(), clearing, wallet, 1000));
            app.commit();
            ReversalCommand full = new ReversalCommand("rev-full-" + IDS.next(), original.entryId(), DATE, DATE,
                    "reversal-probe", lines(wallet, clearing, 1000));
            PostingResult first = reversals().reverse(app, full);
            app.commit();

            PostingResult kept = reversals().reverse(app, full);
            app.commit();
            sweepByHand(ReversalService.IDEMPOTENCY_SCOPE, full.idempotencyKey());
            PostingResult swept = reversals().reverse(app, full);
            app.commit();

            assertThat(List.of(kept, swept)).allSatisfy(answer -> {
                assertThat(answer.entryId()).isEqualTo(first.entryId());
                assertThat(answer.replayed()).isTrue();
            });
            assertThat(count(app, "SELECT count(*) FROM ledger.journal_entry WHERE reverses_entry_id = ?",
                    original.entryId().value())).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the sweep takes terminal records past their expiry and the grace, and keeps a younger one and every in-flight claim")
    void theSweepTakesOnlyTerminalRecordsPastTheGrace() throws Exception {
        String scope = "p16-011:" + IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                record(app, scope, "completed-old", "COMPLETED", "2 days");
                record(app, scope, "failed-old", "FAILED", "2 days");
                record(app, scope, "completed-young", "COMPLETED", "12 hours");
                record(app, scope, "in-flight-old", "IN_PROGRESS", "3 days");
                while (sweep(app, A_DAY_MS, 1000) > 0) {
                    // Older records other suites left go first, by expiry; every one is put back by the rollback below.
                }
                assertThat(keysOf(app, scope)).containsExactlyInAnyOrder("completed-young", "in-flight-old");
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("the sweep refuses a grace under a day and a batch out of bounds (22023), and deletes nothing")
    void theSweepRefusesItsBoundsOutOfRange() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                for (long[] bounds : new long[][] {{A_DAY_MS - 1, 10}, {A_DAY_MS, 0}, {A_DAY_MS, 1001}}) {
                    assertThatThrownBy(() -> sweep(app, bounds[0], (int) bounds[1]))
                            .isInstanceOfSatisfying(SQLException.class, refused -> assertThat(refused.getSQLState()).isEqualTo("22023"));
                    app.rollback();
                }
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("with the posting scope's unique index gone the sweep refuses (55000) and deletes nothing")
    void theSweepRefusesWithoutTheUniqueness() throws Exception {
        String scope = "p16-011-guard:" + IDS.next();
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try {
                record(owner, scope, "completed-old", "COMPLETED", "2 days");
                try (Statement drop = owner.createStatement()) {
                    drop.execute("DROP INDEX ledger.journal_entry_one_per_idempotency_scope");
                }
                assertThatThrownBy(() -> sweep(owner, A_DAY_MS, 1000))
                        .isInstanceOfSatisfying(SQLException.class, refused -> assertThat(refused.getSQLState()).isEqualTo("55000"));
            } finally {
                owner.rollback(); // the index comes back with the rollback
            }
            assertThat(count(owner, "SELECT count(*) FROM pg_class WHERE relname = 'journal_entry_one_per_idempotency_scope'"))
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the application role holds no DELETE on the record (42501): the function is the only way out")
    void theApplicationRoleCannotDeleteARecord() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try (Statement delete = app.createStatement()) {
                assertThatThrownBy(() -> delete.executeUpdate("DELETE FROM platform.idempotency_record WHERE scope = 'none'"))
                        .isInstanceOfSatisfying(SQLException.class, refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("ten sweepers at once delete disjoint batches: every old record once, none twice, no sweeper failed")
    void tenSweepersDeleteDisjointBatches() throws Exception {
        String scope = "p16-011-ten:" + IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            for (int i = 0; i < 200; i++) {
                record(app, scope, "old-" + i, "COMPLETED", "2 days");
            }
            record(app, scope, "young", "COMPLETED", "1 hour");
            app.commit();
        }
        CyclicBarrier start = new CyclicBarrier(10);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<Integer>> sweepers = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                sweepers.add(pool.submit(() -> {
                    int deleted = 0;
                    try (Connection own = DatabaseRoles.application()) {
                        own.setAutoCommit(false);
                        start.await(30, TimeUnit.SECONDS);
                        int batch;
                        do {
                            batch = sweep(own, A_DAY_MS, 7);
                            own.commit();
                            deleted += batch;
                        } while (batch > 0);
                    }
                    return deleted;
                }));
            }
            int total = 0;
            for (Future<Integer> sweeper : sweepers) {
                total += sweeper.get(120, TimeUnit.SECONDS);
            }
            assertThat(total).as("every record of this test once, beside what other suites left").isGreaterThanOrEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        try (Connection app = DatabaseRoles.application()) {
            assertThat(keysOf(app, scope)).containsExactly("young");
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static PostingService postings() {
        return new PostingService(executor(), new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(),
                new JdbcBalanceProjection(), IDS, CLOCK, PostingObserver.NONE);
    }

    private static ReversalService reversals() {
        return new ReversalService(executor(), new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(),
                new JdbcBalanceProjection(), IDS, CLOCK, PostingObserver.NONE);
    }

    private static IdempotentExecutor executor() {
        return new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1), Duration.ofMinutes(5));
    }

    private LedgerAccount account(Connection app) {
        return accounts.createOrConverge(app, LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY,
                AccountPurpose.CUSTOMER_WALLET, USD, IDS.next())).account();
    }

    private static List<JournalLine> lines(LedgerAccount debit, LedgerAccount credit, long minor) {
        return List.of(
                new JournalLine(debit.id(), Direction.DEBIT, Money.ofMinorUnits(minor, USD)),
                new JournalLine(credit.id(), Direction.CREDIT, Money.ofMinorUnits(minor, USD)));
    }

    private static PostingCommand posting(String key, LedgerAccount debit, LedgerAccount credit, long minor) {
        return new PostingCommand(key, DATE, DATE, "p16-011-probe", lines(debit, credit, minor));
    }

    private static PostingAttribution attribution(String scope) {
        return new PostingAttribution(JournalEntryType.POSTING, "p16-011-race", Optional.empty(), Optional.empty(), "system",
                Correlation.startingWith(CorrelationId.generate(IDS)).causing(CausationId.generate(IDS)), scope);
    }

    private static CorrelationContext.Scope flow() {
        return CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
    }

    /** What the sweep does to one record, done by hand as the owner: the test cannot wait out a record's expiry and the grace. */
    private static void sweepByHand(String scope, String key) throws SQLException {
        try (Connection owner = DatabaseRoles.bootstrap();
                PreparedStatement delete = owner.prepareStatement(
                        "DELETE FROM platform.idempotency_record WHERE scope = ? AND idempotency_key = ?")) {
            delete.setString(1, scope);
            delete.setString(2, key);
            assertThat(delete.executeUpdate()).as("the record existed to be swept").isEqualTo(1);
        }
    }

    /**
     * One record whose expiry is {@code age} behind the database's clock, valid by every {@code CHECK} of V002 and V004: created a day
     * before its expiry; a terminal one completed at its creation with a body and its type; an in-flight one leased and with none.
     */
    private static void record(Connection connection, String scope, String key, String state, String age) throws SQLException {
        boolean terminal = !"IN_PROGRESS".equals(state);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO platform.idempotency_record (scope, idempotency_key, request_fingerprint, fingerprint_algorithm, state,"
                        + " response_body, response_media_type, correlation_id, created_at, completed_at, expires_at, lease_expires_at)"
                        + " VALUES (?, ?, decode(repeat('ab', 32), 'hex'), 'SHA-256', ?, ?, ?, 'p16-011',"
                        + " now() - ?::interval - interval '1 day', CASE WHEN ? THEN now() - ?::interval - interval '1 day' END,"
                        + " now() - ?::interval, CASE WHEN ? THEN NULL ELSE now() + interval '5 minutes' END)")) {
            insert.setString(1, scope);
            insert.setString(2, key);
            insert.setString(3, state);
            insert.setBytes(4, terminal ? "an-entry-id".getBytes(java.nio.charset.StandardCharsets.UTF_8) : null);
            insert.setString(5, terminal ? "text/plain" : null);
            insert.setString(6, age);
            insert.setBoolean(7, terminal);
            insert.setString(8, age);
            insert.setString(9, age);
            insert.setBoolean(10, terminal);
            insert.executeUpdate();
        }
    }

    private static int sweep(Connection connection, long graceMs, int batch) throws SQLException {
        try (CallableStatement sweep = connection.prepareCall("SELECT platform.sweep_expired_idempotency_records(?, ?)")) {
            sweep.setLong(1, graceMs);
            sweep.setInt(2, batch);
            try (ResultSet row = sweep.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private static List<String> keysOf(Connection connection, String scope) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT idempotency_key FROM platform.idempotency_record WHERE scope = ? ORDER BY idempotency_key")) {
            select.setString(1, scope);
            try (ResultSet rows = select.executeQuery()) {
                List<String> keys = new ArrayList<>();
                while (rows.next()) {
                    keys.add(rows.getString(1));
                }
                return keys;
            }
        }
    }

    private static long count(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }
}
