package com.finapp.app.accounting;

import static com.finapp.app.accounting.AccountingPeriodFixture.FEBRUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.IDS;
import static com.finapp.app.accounting.AccountingPeriodFixture.JANUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.NOW;
import static com.finapp.app.accounting.AccountingPeriodFixture.correlation;
import static com.finapp.app.accounting.AccountingPeriodFixture.id;
import static com.finapp.app.accounting.AccountingPeriodFixture.inScope;
import static com.finapp.app.accounting.AccountingPeriodFixture.on;
import static com.finapp.app.accounting.AccountingPeriodFixture.periods;
import static com.finapp.app.accounting.AccountingPeriodFixture.person;
import static com.finapp.app.accounting.AccountingPeriodFixture.row;
import static com.finapp.app.accounting.PeriodCloseFixture.MACHINE;
import static com.finapp.app.accounting.PeriodCloseFixture.count;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.CounterpartyPositionReads;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.JdbcOpeningCheckpointStore;
import com.finapp.accounting.JdbcReportRunStore;
import com.finapp.accounting.OpeningCheckpoint;
import com.finapp.accounting.OpeningCheckpoints;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.reporting.ReadOnlySnapshot;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.TrialBalanceDefinition;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Report generation's opening bounded by a checkpoint for each locked month, against a live PostgreSQL (`P16-REM-003`;
 * {@code THREAT_MODEL.md} section 6 item 64; {@code INV-ACC-01}, {@code INV-ACC-06}) - the backlog entry's three cases and what the
 * design adds: the split equal to the whole fold over a journal crossing several locked months, read on the reporting role's snapshot
 * through the view a definition is handed, for every day an opening can be asked and every month's ledger windows, with no cut and
 * under one; a move back - an unlock, then a reopen - voiding the checkpoint, the read folding from the one before; a FINAL run's
 * regeneration reproducing the bytes it was generated with before any checkpoint existed; a cut before a checkpoint's latest creation
 * never served it; ten instances folding each lock once; the try-lock's key apart from the cutoff's and the mapping's; and the
 * application role never able to change or remove a checkpoint.
 *
 * <p><strong>Every report reads an opening through these three reads</strong> - {@code TrialBalanceReads#before}, {@code #ledger} and
 * {@code CounterpartyPositionReads#before} (the trial balance, the balance sheet, the GL activity, the purpose reports and the
 * safeguarding position: {@code StatementFigures}, {@code PurposeRows}) - so the reads equal, day for day, is every report's opening
 * equal; the FINAL run case holds one report's bytes end to end.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): it closes January and February 1999 for real, locks March, and
 * commits lines on {@code SETTLEMENT_CLEARING}, as {@code TrialBalanceRunDatabaseTest} and {@code PeriodReopenDatabaseTest} do. Each case
 * returns every month to {@code OPEN} by the machine's own doors first; the journal only grows, and every relation here holds whatever
 * an earlier case posted.
 */
@Tag("database")
@Tag("own-container") // its own JVM and database: ownContainerDatabaseTest, the javadoc says why
@DisplayName("the report run's opening bounded by a checkpoint for each locked month, against the database (P16-REM-003)")
class OpeningCheckpointDatabaseTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode BHD = CurrencyCode.of("BHD");
    private static final YearMonth MARCH = YearMonth.of(1999, 3);
    private static final YearMonth APRIL = YearMonth.of(1999, 4);
    private static final JdbcOpeningCheckpointStore STORE = new JdbcOpeningCheckpointStore();
    private static final TrialBalanceReads WHOLE = new LedgerTrialBalanceReads(new JdbcGlMappingStore());
    private static final CounterpartyPositionReads WHOLE_POSITIONS = new LedgerCounterpartyPositionReads();
    private static final TrialBalanceReads SPLIT = new LedgerTrialBalanceReads(new JdbcGlMappingStore(), STORE);
    private static final CounterpartyPositionReads SPLIT_POSITIONS = new LedgerCounterpartyPositionReads(STORE);
    private static final OpeningCheckpoints WRITER = new OpeningCheckpoints(STORE, SPLIT, SPLIT_POSITIONS, new LedgerJournalCreations(), IDS);
    private static final ReportDefinitions DEFINITIONS = ReportDefinitions.of(new TrialBalanceDefinition(SPLIT));
    /** Report generation's snapshots, as production opens them: the reporting role. */
    private static final JdbcReportSnapshots GENERATION_SNAPSHOTS = new JdbcReportSnapshots(DatabaseRoles::reporting,
            ReportRuns.Leases.STANDARD.generationTimeout());
    private static final AccountingTransactions TRANSACTIONS = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            try (Connection connection = DatabaseRoles.application()) {
                connection.setAutoCommit(false);
                try {
                    R result = work.apply(connection);
                    connection.commit();
                    return result;
                } catch (RuntimeException failure) {
                    connection.rollback();
                    throw failure;
                }
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }
    };
    private static final JdbcLedgerAccountStore ACCOUNTS = new JdbcLedgerAccountStore();

    @BeforeEach
    void everyMonthIsOpen() {
        AccountingPeriodFixture.openJanuaryAndFebruary();
        inScope(Actor.SYSTEM, uow -> periods(on("1999-04-15")).openNext(uow, Actor.SYSTEM, correlation()));
        PeriodCloseFixture.mappingInForce();
        unlock(MARCH);
        PeriodCloseFixture.everyPeriodOpen();
    }

    @Test
    @DisplayName("the split is the whole fold across three locked months: every day's opening, every month's ledger windows and the"
            + " counterparties, with no cut and under one; the latest locked month's checkpoint is the one a read after it takes")
    void theSplitIsTheWholeFold() throws Exception {
        books(NOW);
        closeJanuaryAndFebruaryLockMarch();

        assertThat(writeEveryCheckpoint()).as("one checkpoint for each month whose present lock has none").isEqualTo(3);
        Optional<AccountingPeriodId> again = TRANSACTIONS.inTransaction(uow -> WRITER.storeNext(uow));
        assertThat(again).as("and none the second time").isEmpty();

        OpeningCheckpoint latest = GENERATION_SNAPSHOTS.read(snapshot -> STORE.latest(view(snapshot), APRIL.atDay(1), Optional.empty()))
                .orElseThrow();
        assertThat(latest.through()).as("a read before April starts from March: it folds April's lines alone").isEqualTo(MARCH.atEndOfMonth());
        assertThat(latest.lockedAt()).isEqualTo(row(MARCH).lockedAt().orElseThrow());
        assertTheSplitIsTheWholeFold(Optional.empty());
        assertTheSplitIsTheWholeFold(Optional.of(Instant.now()));
    }

    @Test
    @DisplayName("the move back (the probe's target): March unlocked voids its checkpoint, a line posted into it is read, February's taken"
            + " instead; February reopened voids its own; each relocked gets a checkpoint under its new lock - the voided ones kept")
    void aMoveBackVoidsTheCheckpoint() throws Exception {
        books(NOW);
        closeJanuaryAndFebruaryLockMarch();
        writeEveryCheckpoint();

        unlock(MARCH);
        post(LocalDate.of(1999, 3, 20), wallet(EUR), operational(AccountPurpose.FEE_REVENUE, EUR), 4_321, EUR, NOW);
        assertTheSplitIsTheWholeFold(Optional.empty());
        assertThat(GENERATION_SNAPSHOTS.read(snapshot -> STORE.latest(view(snapshot), APRIL.atDay(1), Optional.empty())).orElseThrow().through())
                .as("March's checkpoint voided by its unlock").isEqualTo(FEBRUARY.atEndOfMonth());

        reopen(FEBRUARY);
        post(LocalDate.of(1999, 2, 10), wallet(EUR), operational(AccountPurpose.FEE_REVENUE, EUR), 1_234, EUR, NOW);
        assertTheSplitIsTheWholeFold(Optional.empty());
        assertThat(GENERATION_SNAPSHOTS.read(snapshot -> STORE.latest(view(snapshot), APRIL.atDay(1), Optional.empty())).orElseThrow().through())
                .as("February's voided by its reopen").isEqualTo(JANUARY.atEndOfMonth());

        PeriodCloseFixture.closed(FEBRUARY);
        PeriodCloseFixture.locked(MARCH);
        assertThat(writeEveryCheckpoint()).as("February and March, each under its new lock").isEqualTo(2);
        assertTheSplitIsTheWholeFold(Optional.empty());
        assertThat(count("SELECT count(*) FROM accounting.opening_checkpoint WHERE period_id = '" + id(MARCH).value() + "'"))
                .as("the voided checkpoint stays, as evidence of what was read under that lock").isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("a FINAL run generated before any checkpoint existed regenerates IDENTICAL once its opening is read from one: the split"
            + " reproduces the whole fold's bytes")
    void aFinalRunRegeneratesItsFigures() throws Exception {
        books(NOW);
        closeJanuaryAndFebruaryLockMarch();

        ReportRunStore.RunRow run = ask(id(MARCH));
        assertThat(run.basis()).isEqualTo(RunBasis.FINAL);
        assertThat(writeEveryCheckpoint()).isEqualTo(3);
        assertThat(GENERATION_SNAPSHOTS.read(snapshot -> STORE.latest(view(snapshot), MARCH.atDay(1), Optional.empty())).orElseThrow().through())
                .as("the regeneration's opening starts from February's checkpoint").isEqualTo(FEBRUARY.atEndOfMonth());

        ReportRunGenerator.Compared compared = generator().regenerate(run.id());
        assertThat(compared.identical()).isTrue();
        assertThat(compared.regeneratedSha256()).isEqualTo(run.contentSha256().orElseThrow());
        assertThat(generator().regenerate(run.id()).identical()).as("and again, from the same checkpoint").isTrue();
    }

    @Test
    @DisplayName("a cut before a checkpoint's latest creation never takes it: a line a skewed instance created a day ahead keeps March's"
            + " checkpoint from a read cut now, which starts from February's; a cut after it takes March's - the whole fold either way")
    void aCutBeforeTheLatestCreationSkipsTheCheckpoint() throws Exception {
        books(NOW);
        post(LocalDate.of(1999, 3, 30), wallet(EUR), operational(AccountPurpose.FEE_REVENUE, EUR), 777, EUR,
                Clock.offset(NOW, Duration.ofDays(1)));
        closeJanuaryAndFebruaryLockMarch();
        writeEveryCheckpoint();

        Instant now = Instant.now();
        assertThat(GENERATION_SNAPSHOTS.read(snapshot -> STORE.latest(view(snapshot), APRIL.atDay(1), Optional.of(now))).orElseThrow().through())
                .isEqualTo(FEBRUARY.atEndOfMonth());
        Instant ahead = now.plus(Duration.ofDays(2));
        assertThat(GENERATION_SNAPSHOTS.read(snapshot -> STORE.latest(view(snapshot), APRIL.atDay(1), Optional.of(ahead))).orElseThrow().through())
                .isEqualTo(MARCH.atEndOfMonth());
        assertTheSplitIsTheWholeFold(Optional.of(now));
        assertTheSplitIsTheWholeFold(Optional.of(ahead));
    }

    @Test
    @DisplayName("ten instances: each lock folded once - every call either stores one or skips, and every lock has exactly one checkpoint")
    void tenInstancesFoldEachLockOnce() throws Exception {
        books(NOW);
        closeJanuaryAndFebruaryLockMarch();
        ExecutorService instances = Executors.newFixedThreadPool(10);
        try {
            List<Future<Integer>> stored = new ArrayList<>();
            for (int instance = 0; instance < 10; instance++) {
                stored.add(instances.submit((Callable<Integer>) () -> {
                    int mine = 0;
                    for (int tick = 0; tick < 20; tick++) {
                        if (TRANSACTIONS.inTransaction(uow -> WRITER.storeNext(uow)).isPresent()) {
                            mine++;
                        }
                    }
                    return mine;
                }));
            }
            int total = 0;
            for (Future<Integer> one : stored) {
                total += one.get(5, TimeUnit.MINUTES);
            }
            assertThat(total).as("three locks, three checkpoints, whoever folded them").isEqualTo(3);
        } finally {
            instances.shutdownNow();
        }
        for (YearMonth month : List.of(JANUARY, FEBRUARY, MARCH)) {
            AccountingPeriodStore.PeriodRow period = row(month);
            assertThat(count("SELECT count(*) FROM accounting.opening_checkpoint WHERE period_id = '" + period.id().value()
                    + "' AND period_locked_at = (SELECT locked_at FROM accounting.accounting_period WHERE id = '" + period.id().value() + "')"))
                    .as(month.toString()).isEqualTo(1);
        }
        assertTheSplitIsTheWholeFold(Optional.empty());
    }

    @Test
    @DisplayName("a lock rolled back leaves no checkpoint: the month is OPEN, and the writer folds nothing for it")
    void aRolledBackLockLeavesNoCheckpoint() throws Exception {
        PeriodCloseFixture.closed(JANUARY);
        PeriodCloseFixture.closed(FEBRUARY);
        Actor locker = person();
        assertThatThrownBy(() -> inScope(locker, uow -> {
            periods().lock(uow, id(MARCH), locker, null, correlation());
            throw new IllegalStateException("the lock's transaction rolled back");
        })).hasMessageContaining("rolled back");

        writeEveryCheckpoint();

        assertThat(row(MARCH).status()).isEqualTo(PeriodStatus.OPEN);
        assertThat(count("SELECT count(*) FROM accounting.opening_checkpoint checkpoint JOIN accounting.accounting_period period"
                + " ON period.id = checkpoint.period_id AND period.locked_at = checkpoint.period_locked_at WHERE period.id = '"
                + id(MARCH).value() + "'")).isZero();
    }

    @Test
    @DisplayName("the writer's key is apart from the cutoff's and the mapping's on namespace 14, by hashtext")
    void theKeysDoNotCollide() throws Exception {
        assertThat(count("SELECT count(DISTINCT hashtext(key)) FROM (VALUES ('" + JdbcOpeningCheckpointStore.KEY + "'), ('cutoff'),"
                + " ('mapping')) AS keys (key)")).isEqualTo(3);
    }

    @Test
    @DisplayName("never written over: the application role cannot change or remove a checkpoint or its groups; the reporting role"
            + " cannot add one")
    void neverWrittenOver() throws Exception {
        for (String sql : List.of("UPDATE accounting.opening_checkpoint SET latest_created = NULL",
                "DELETE FROM accounting.opening_checkpoint", "UPDATE accounting.opening_checkpoint_group SET lines = lines",
                "DELETE FROM accounting.opening_checkpoint_group")) {
            assertThatThrownBy(() -> {
                try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
                    statement.execute(sql);
                }
            }).as(sql).isInstanceOfSatisfying(SQLException.class, refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
        }
        assertThatThrownBy(() -> {
            try (Connection reporting = DatabaseRoles.reporting(); Statement statement = reporting.createStatement()) {
                statement.execute("INSERT INTO accounting.opening_checkpoint (id, period_id, period_locked_at) VALUES"
                        + " (gen_random_uuid(), gen_random_uuid(), now())");
            }
        }).isInstanceOfSatisfying(SQLException.class, refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
    }

    // ------------------------------------------------------------------ the split against the whole

    /** Every opening a report can ask, read both ways on one reporting snapshot through a definition's view. */
    private static void assertTheSplitIsTheWholeFold(Optional<Instant> cut) {
        GENERATION_SNAPSHOTS.read(snapshot -> {
            Connection view = view(snapshot);
            for (LocalDate before = LocalDate.of(1998, 12, 1); !before.isAfter(LocalDate.of(1999, 5, 1)); before = before.plusDays(1)) {
                assertThat(SPLIT.before(view, before, cut)).as("the GL side before %s, cut %s", before, cut)
                        .containsExactlyInAnyOrderElementsOf(WHOLE.before(view, before, cut));
                assertThat(SPLIT_POSITIONS.before(view, before, cut)).as("the counterparties before %s, cut %s", before, cut)
                        .containsExactlyInAnyOrderElementsOf(WHOLE_POSITIONS.before(view, before, cut));
            }
            for (YearMonth month : List.of(JANUARY, FEBRUARY, MARCH, APRIL)) {
                TrialBalanceReads.Period period = WHOLE.period(view, id(month)).orElseThrow();
                assertThat(SPLIT.ledger(view, period, cut)).as("the ledger's windows of %s, cut %s", month, cut)
                        .containsExactlyInAnyOrderElementsOf(WHOLE.ledger(view, period, cut));
            }
            return null;
        });
    }

    private static int writeEveryCheckpoint() {
        int stored = 0;
        while (TRANSACTIONS.inTransaction(uow -> WRITER.storeNext(uow)).isPresent()) {
            stored++;
        }
        return stored;
    }

    private static Connection view(Connection snapshot) {
        return new ReadOnlySnapshot(snapshot, ReportRuns.Leases.STANDARD.generationTimeout(), () -> 0L).connection();
    }

    // ------------------------------------------------------------------ the months

    private static void closeJanuaryAndFebruaryLockMarch() {
        PeriodCloseFixture.closed(JANUARY);
        PeriodCloseFixture.closed(FEBRUARY);
        PeriodCloseFixture.locked(MARCH);
    }

    /** {@code month} back to {@code OPEN} by the four-eyes unlock, when it is {@code LOCKED}. */
    private static void unlock(YearMonth month) {
        AccountingPeriodStore.PeriodRow period = row(month);
        if (period.status() != PeriodStatus.LOCKED) {
            return;
        }
        if (!period.unlockPending()) {
            Actor proposer = person();
            inScope(proposer, uow -> periods().proposeUnlock(uow, period.id(), proposer, "the suite moves the month back", correlation()));
        }
        Actor approver = person();
        inScope(approver, uow -> periods().approveUnlock(uow, period.id(), periods().pendingUnlock(uow, period.id()).orElseThrow(), approver,
                "the suite moves the month back", correlation()));
    }

    /** {@code month}, {@code CLOSED} and the next {@code OPEN}, reopened by four eyes - {@code CLOSED -> OPEN}, the cutoff back a month. */
    private static void reopen(YearMonth month) {
        AccountingPeriodId period = id(month);
        Actor proposer = person();
        inScope(proposer, uow -> MACHINE.proposeReopen(uow, period, proposer, PeriodCloseFixture.posted(uow, period), "the suite moves the month back",
                correlation()));
        Actor approver = person();
        inScope(approver, uow -> MACHINE.approveReopen(uow, period, MACHINE.pendingOf(uow, period).reopen().orElseThrow().id(), approver,
                PeriodCloseFixture.posted(uow, period), "the suite moves the month back", correlation()));
    }

    // ------------------------------------------------------------------ the run

    private static ReportRunStore.RunRow ask(AccountingPeriodId period) {
        Actor accountant = person();
        ReportRuns.Claimed claimed = TRANSACTIONS.inTransaction(uow -> runs().claim(uow, TrialBalanceDefinition.CODE, period,
                RunParameters.none(), accountant, correlation()));
        return claimed.mine() ? generator().generateAndComplete(claimed.run(), accountant) : generator().drive(claimed.run().id(), accountant);
    }

    private static ReportRuns runs() {
        return new ReportRuns(new JdbcReportRunStore(), AccountingPeriodFixture.STORE, new JdbcGlMappingStore(), DEFINITIONS,
                ReportRuns.Leases.STANDARD, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW, ReportRuns.Provisional.STANDARD);
    }

    private static ReportRunGenerator generator() {
        return new ReportRunGenerator(runs(), new ReportGeneration(ReportGeneration.FROM_THE_SNAPSHOT,
                ReportRuns.Leases.STANDARD.generationTimeout(), NOW), GENERATION_SNAPSHOTS, TRANSACTIONS,
                new ReportRunMetrics(new SimpleMeterRegistry(), DEFINITIONS));
    }

    // ------------------------------------------------------------------ the journal

    /**
     * The journal across the months: the history before the books, each month's first and last days and one between, and the month after
     * the last locked one - in EUR and BHD, a customer's wallet against clearing and revenue, and EUR against two FX providers'
     * counterparty accounts.
     */
    private static void books(Clock created) throws SQLException {
        for (CurrencyCode currency : List.of(EUR, BHD)) {
            LedgerAccount wallet = wallet(currency);
            post(LocalDate.of(1998, 12, 15), operational(AccountPurpose.SETTLEMENT_CLEARING, currency), wallet, 70_000, currency, created);
            for (YearMonth month : List.of(JANUARY, FEBRUARY, MARCH, APRIL)) {
                post(month.atDay(1), operational(AccountPurpose.SETTLEMENT_CLEARING, currency), wallet, 1_000, currency, created);
                post(month.atDay(14), wallet, operational(AccountPurpose.FEE_REVENUE, currency), 40, currency, created);
                post(month.atEndOfMonth(), wallet, operational(AccountPurpose.FEE_REVENUE, currency), 25, currency, created);
            }
        }
        LedgerAccount cash = wallet(EUR);
        for (YearMonth month : List.of(JANUARY, FEBRUARY, MARCH, APRIL)) {
            post(month.atDay(10), counterparty("fx-sim-a"), cash, 900, EUR, created);
            post(month.atDay(20), cash, counterparty("fx-sim-b"), 300, EUR, created);
        }
    }

    private static LedgerAccount wallet(CurrencyCode currency) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount account = ACCOUNTS.createOrConverge(app, LedgerAccount.owned(IDS, NOW, AccountType.LIABILITY,
                    AccountPurpose.CUSTOMER_WALLET, currency, IDS.next())).account();
            app.commit();
            return account;
        }
    }

    private static LedgerAccount operational(AccountPurpose purpose, CurrencyCode currency) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return ACCOUNTS.findOperational(app, purpose, currency).orElseThrow();
        }
    }

    private static LedgerAccount counterparty(String code) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return ACCOUNTS.findCounterpartyAccount(app, AccountPurpose.FX_PROVIDER_CLEARING, code, EUR).orElseThrow();
        }
    }

    /** One balanced entry through the ledger's own door, created on {@code clock}. */
    @SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
    private static void post(LocalDate day, LedgerAccount debit, LedgerAccount credit, long minor, CurrencyCode currency, Clock clock)
            throws SQLException {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            new PostingService(new IdempotentExecutor(new JdbcIdempotencyRecordStore(), clock, Duration.ofDays(1), Duration.ofMinutes(5)),
                    new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(), new JdbcBalanceProjection(), IDS, clock,
                    PostingObserver.NONE)
                    .post(app, new PostingCommand("opening-checkpoint-" + IDS.next(), day, day, "opening-checkpoint-suite",
                            List.of(new JournalLine(debit.id(), Direction.DEBIT, Money.ofMinorUnits(minor, currency)),
                                    new JournalLine(credit.id(), Direction.CREDIT, Money.ofMinorUnits(minor, currency)))));
            app.commit();
        }
    }
}
