package com.finapp.app.accounting;

import static com.finapp.app.accounting.AccountingPeriodFixture.IDS;
import static com.finapp.app.accounting.AccountingPeriodFixture.JANUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.NOW;
import static com.finapp.app.accounting.AccountingPeriodFixture.correlation;
import static com.finapp.app.accounting.AccountingPeriodFixture.id;
import static com.finapp.app.accounting.AccountingPeriodFixture.inScope;
import static com.finapp.app.accounting.AccountingPeriodFixture.periods;
import static com.finapp.app.accounting.AccountingPeriodFixture.person;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingBreakId;
import com.finapp.accounting.AccountingBreakStore;
import com.finapp.accounting.AccountingChecks;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccrualCompleteness;
import com.finapp.accounting.BreakFinding;
import com.finapp.accounting.BreakStatus;
import com.finapp.accounting.BreakType;
import com.finapp.accounting.CheckVerdict;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseCheckStatus;
import com.finapp.accounting.CloseChecks;
import com.finapp.accounting.CloseRunId;
import com.finapp.accounting.CloseRunStore;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.FinalTrialBalance;
import com.finapp.accounting.JdbcAccountingBreakStore;
import com.finapp.accounting.JdbcCloseRunStore;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.JdbcReportRunStore;
import com.finapp.accounting.JdbcValuationRateSetStore;
import com.finapp.accounting.ReconciliationDisclosure;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.SourceProofs;
import com.finapp.accounting.ValuationRateSetStore;
import com.finapp.accounting.ValuationRateSets;
import com.finapp.accounting.ValuationRates;
import com.finapp.accounting.ValuationStaleness;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.TrialBalanceDefinition;
import com.finapp.bnpl.BnplAccruals;
import com.finapp.bnpl.JdbcBnplAccrualStore;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JdbcPurposeBalances;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.PeriodClosedException;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.lending.AccrualLags;
import com.finapp.lending.JdbcLoanServicingStore;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.JdbcReportReadings;
import com.finapp.reconciliation.JdbcSuspenseReadings;
import com.finapp.reconciliation.SuspenseReadings;
import com.finapp.reconciliation.SuspenseSide;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The close run against a live PostgreSQL (`P14-TSK-011`; {@code PHASE_14_PLAN.md} sections 8.3 rows 5-6, 13.5, 14.2
 * scenarios 8 and 9; {@code INV-ACC-05}, {@code INV-ACC-09}) - the entry's five named cases and what the design needs:
 * the claim's {@code SKIP LOCKED} over ten connections, a dead worker reclaimed and fenced, a live worker renewing past
 * its lease, postings during the run, the accruals, the disclosure, the planted imbalance, and the ranks for every
 * writer. <strong>Written and owed</strong> (owner decision C5: the database tiers are skipped per task).
 *
 * <p><strong>The books it uses.</strong> The fixture's <strong>January 1999</strong> ({@link AccountingPeriodFixture}:
 * the shared database's one lockable period - `P14-TSK-012`'s close suites close it only in databases of their own); every case returns it to {@code
 * OPEN} and each lock is a new run, so a rerun on a kept database starts clean. It approves <strong>no valuation
 * set</strong> - January ends {@code ValuationRateSetDatabaseTest} with one approved for good, so no case asserts
 * {@code VALUATION_SET_APPROVED}'s status. <strong>What cannot be committed is planted in one transaction</strong> and
 * rolled back: a real product discrepancy, or a line on {@code SUSPENSE_UNMATCHED}, would fail every other suite's
 * proof for good. <strong>The planted imbalance</strong> (gate criterion 1; `P14-TSK-009`'s review, M-6): the balance
 * trigger is deferred to commit, so an unbalanced entry is visible only on its own connection - and is therefore read
 * there: planted, locked, claimed, generated and judged on that one connection, then rolled back, nothing left behind.
 *
 * <p><strong>The independent review's cases</strong> (I-4): every statement the first nine did not execute - {@code rerun},
 * {@code view}, {@code readiness}, {@code concludeOpenBreaks}, {@code countByStatus}, {@code defer}, {@code ATTEMPTS_SQL},
 * {@code latestRun}, {@code runUnder}, {@code latestAttempt}, {@code ofPeriod}, {@code held}'s evidence - and the reads run
 * on the connection production reads on: the real {@link ServicingAccrualCompleteness} and the disclosure on a verified
 * {@code REPEATABLE READ READ ONLY} snapshot. What is planted is read on its own connection, {@code REPEATABLE READ} and
 * set {@code READ ONLY} before the read.
 */
@Tag("database")
@DisplayName("the close run against the database (P14-TSK-011)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class CloseRunDatabaseTest {

    private static final int WORKERS = 10;
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final LocalDate IN_JANUARY = JANUARY.atDay(20);
    private static final LocalDate DAY_AFTER = JANUARY.plusMonths(1).atDay(1);
    private static final TrialBalanceDefinition TRIAL_BALANCE = new TrialBalanceDefinition(
            new LedgerTrialBalanceReads(new JdbcGlMappingStore()));
    private static final ReportDefinitions DEFINITIONS = ReportDefinitions.of(TRIAL_BALANCE);
    private static final ReportRuns REPORT_RUNS = new ReportRuns(new JdbcReportRunStore(), AccountingPeriodFixture.STORE,
            new JdbcGlMappingStore(), DEFINITIONS, ReportRuns.Leases.STANDARD, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW);
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
    private static final ReportSnapshots REPORT_SNAPSHOTS = new JdbcReportSnapshots(DatabaseRoles::application,
            ReportRuns.Leases.STANDARD.generationTimeout());
    private static final GlSnapshots SNAPSHOTS = new GlSnapshots(DatabaseRoles::application, Duration.ofSeconds(30));

    private final JdbcLedgerAccountStore accounts = new JdbcLedgerAccountStore();

    @BeforeEach
    void januaryIsOpen() {
        AccountingPeriodFixture.restore();
    }

    @AfterEach
    void leaveOpen() {
        AccountingPeriodFixture.restore();
    }

    // ------------------------------------------------------------------ rows 5 and 6

    @Test
    @DisplayName("contention row 5: ten workers on ten connections, one attempt per check - disjoint claims, no second attempt row, and no claimer"
            + " ever waits on a row another holds (FOR UPDATE SKIP LOCKED: without it a claimer waits out its lock_timeout and fails)")
    void tenWorkersOneAttempt() throws Exception {
        CloseRuns runs = runs(CloseRuns.Leases.STANDARD);
        CloseRunId run = startedJanuary(runs);
        // One claimer mid-transaction, holding one row: the others must pass it by, not queue behind it.
        Connection holding = DatabaseRoles.application();
        holding.setAutoCommit(false);
        UUID held;
        try (PreparedStatement lock = holding.prepareStatement("SELECT id FROM accounting.close_check WHERE close_run_id = ?"
                + " AND check_code = 'PROJECTION_VERIFIED' FOR UPDATE")) {
            lock.setObject(1, run.value());
            try (ResultSet row = lock.executeQuery()) {
                row.next();
                held = row.getObject(1, UUID.class);
            }
        }
        Set<UUID> claimed = ConcurrentHashMap.newKeySet();
        List<String> doubles = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> racing = new ArrayList<>();
        try {
            for (int worker = 0; worker < WORKERS; worker++) {
                racing.add(pool.submit(() -> {
                    start.await();
                    while (true) {
                        Optional<CloseRuns.ClaimedCheck> next = claimUnderALockTimeout(runs);
                        if (next.isEmpty()) {
                            return null;
                        }
                        if (!claimed.add(next.get().id().value())) {
                            doubles.add(next.get().check().name());
                        }
                    }
                }));
            }
            start.countDown();
            for (Future<?> outcome : racing) {
                outcome.get(2, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
            holding.rollback();
            holding.close();
        }
        assertThat(doubles).as("an attempt claimed twice").isEmpty();
        assertThat(claimed).as("seven: the breaks' check waits for the raising ones, one row was held").hasSize(7)
                .doesNotContain(held);
        assertThat(count("SELECT count(*) FROM accounting.close_check WHERE close_run_id = '" + run.value() + "'"))
                .as("a claim births no attempt").isEqualTo(9);
        assertThat(count("SELECT count(*) FROM accounting.close_check WHERE close_run_id = '" + run.value() + "' AND attempt <> 1"))
                .isZero();
    }

    @Test
    @DisplayName("ten instances start one run: insert-if-absent on the lock instance, one run and nine attempts")
    void tenInstancesStartOneRun() throws Exception {
        CloseRuns runs = runs(CloseRuns.Leases.STANDARD);
        lockJanuary();
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<CloseRunId>>> racing = new ArrayList<>();
        try {
            for (int worker = 0; worker < WORKERS; worker++) {
                racing.add(pool.submit(() -> {
                    start.await();
                    return TRANSACTIONS.inTransaction(uow -> runs.startMissing(uow, Actor.SYSTEM));
                }));
            }
            start.countDown();
            List<CloseRunId> started = new ArrayList<>();
            for (Future<List<CloseRunId>> outcome : racing) {
                started.addAll(outcome.get(2, TimeUnit.MINUTES));
            }
            assertThat(started).hasSize(1);
            assertThat(count("SELECT count(*) FROM accounting.close_check WHERE close_run_id = '" + started.get(0).value() + "'"))
                    .isEqualTo(9);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("contention row 6: a dead worker's checks are reclaimed past their lease on the database's clock, one reclaim more, the same"
            + " attempts; its late conclusion moves nothing")
    void aDeadWorkersCheckIsReclaimed() throws Exception {
        CloseRuns runs = runs(new CloseRuns.Leases(Duration.ofSeconds(2), Duration.ofMillis(600), Duration.ofHours(1), 3, Duration.ofSeconds(1)));
        CloseRunId run = startedJanuary(runs);
        List<CloseRuns.ClaimedCheck> dead = claimAll(runs);
        assertThat(dead).hasSize(8);
        assertThat(claimAll(runs)).as("a live lease").isEmpty();

        LockSupport.parkNanos(Duration.ofMillis(2500).toNanos());
        List<CloseRuns.ClaimedCheck> next = claimAll(runs);

        assertThat(next).extracting(CloseRuns.ClaimedCheck::id).containsExactlyInAnyOrderElementsOf(
                dead.stream().map(CloseRuns.ClaimedCheck::id).toList());
        assertThat(next).allSatisfy(claimed -> assertThat(claimed.reclaims()).isEqualTo(1));
        CloseRuns.ClaimedCheck lapsed = dead.get(0);
        assertThat(TRANSACTIONS.inTransaction(uow -> runs.conclude(uow, lapsed, passed(lapsed.check()))).moved())
                .as("the fence").isFalse();
        CloseRuns.ClaimedCheck holder = next.stream().filter(claimed -> claimed.id().equals(lapsed.id())).findFirst().orElseThrow();
        assertThat(TRANSACTIONS.inTransaction(uow -> runs.conclude(uow, holder, passed(holder.check()))).moved()).isTrue();
        assertThat(count("SELECT count(*) FROM accounting.close_check WHERE close_run_id = '" + run.value() + "'"))
                .as("nothing doubled").isEqualTo(9);
    }

    @Test
    @DisplayName("the lease and a long run (M-1): renewed, a live worker's check stays its own past many leases; past the run's bound the database"
            + " refuses the renewal and the next worker reclaims")
    void aLiveWorkerOutlastsItsLease() {
        CloseRuns.Leases leases = new CloseRuns.Leases(Duration.ofSeconds(2), Duration.ofMillis(500), Duration.ofSeconds(4), 3, Duration.ofSeconds(1));
        CloseRuns runs = runs(leases);
        startedJanuary(runs);
        List<CloseRuns.ClaimedCheck> held = claimAll(runs);
        CloseRuns.ClaimedCheck live = held.get(0);
        long renewals = 0;
        boolean renewed = true;
        while (renewed) {
            LockSupport.parkNanos(leases.renewEvery().toNanos());
            renewed = TRANSACTIONS.inTransaction(uow -> runs.renew(uow, live));
            renewals += renewed ? 1 : 0;
            if (renewed && renewals > 4) {
                assertThat(claimAll(runs)).extracting(CloseRuns.ClaimedCheck::id).as("past its first lease, still its own")
                        .doesNotContain(live.id());
            }
        }
        assertThat(renewals).as("renewed past the first lease, then refused at the bound").isGreaterThan(4);
        LockSupport.parkNanos(leases.lease().plusMillis(300).toNanos());
        assertThat(claimAll(runs)).extracting(CloseRuns.ClaimedCheck::id).contains(live.id());
    }

    // ------------------------------------------------------------------ scenario 8

    @Test
    @DisplayName("scenario 8: ten posters while a check is in flight change nothing dated in the period - those dated in January refused, none"
            + " committed, those dated after it committed; the FINAL trial balance the same run, regenerating byte-identical, the check concluding")
    void postingsDuringTheRunChangeNothing() throws Exception {
        CloseRuns runs = runs(CloseRuns.Leases.STANDARD);
        startedJanuary(runs);
        AccountingPeriodId january = id(JANUARY);
        CloseRuns.ClaimedCheck held = TRANSACTIONS.inTransaction(uow -> runs.claim(uow, Actor.SYSTEM)).orElseThrow();
        assertThat(held.check()).isEqualTo(CloseCheckCode.LEDGER_TRIAL_BALANCE);
        LedgerAccount wallet = wallet();
        LedgerAccount revenue = operational(AccountPurpose.FEE_REVENUE);
        FinalTrialBalance reader = reader();
        FinalTrialBalance.Verdict before = (FinalTrialBalance.Verdict) reader.read(january, Actor.SYSTEM, correlation());
        String datedInJanuary = "SELECT count(*) FROM ledger.journal_entry WHERE posting_date BETWEEN '" + JANUARY.atDay(1) + "' AND '"
                + JANUARY.atEndOfMonth() + "'";
        long entriesBefore = count(datedInJanuary);

        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> racing = new ArrayList<>();
        try {
            for (int poster = 0; poster < WORKERS; poster++) {
                LocalDate day = poster % 2 == 0 ? IN_JANUARY : DAY_AFTER;
                racing.add(pool.submit(() -> {
                    start.await();
                    try {
                        post(day, wallet, revenue, 25, PostingCommand.Dating.AS_STATED, "close-run-scenario-8-");
                        return true;
                    } catch (PeriodClosedException refused) {
                        return false;
                    }
                }));
            }
            start.countDown();
            for (int poster = 0; poster < WORKERS; poster++) {
                assertThat(racing.get(poster).get(2, TimeUnit.MINUTES)).as("poster %d", poster).isEqualTo(poster % 2 != 0);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(count(datedInJanuary)).as("nothing dated in the period committed").isEqualTo(entriesBefore);
        FinalTrialBalance.Verdict after = (FinalTrialBalance.Verdict) reader.read(january, Actor.SYSTEM, correlation());
        assertThat(after).as("the same run, the same frozen content").isEqualTo(before);
        ReportRunStore.RunRow run = TRANSACTIONS.inTransaction(uow -> REPORT_RUNS.find(uow,
                com.finapp.accounting.ReportRunId.of(UUID.fromString(before.run())))).orElseThrow();
        assertThat(generator().regenerate(run.id()).identical()).as("the period's range is immutable under the lock").isTrue();
        CheckVerdict verdict = checks(caughtUp(), (snapshot, month) -> noDisclosure(month), reader).judge(held).orElseThrow();
        assertThat(verdict.status()).isEqualTo(CloseCheckStatus.PASSED);
        assertThat(TRANSACTIONS.inTransaction(uow -> runs.conclude(uow, held, verdict)).moved()).isTrue();
    }

    // ------------------------------------------------------------------ the accruals

    @Test
    @DisplayName("ACCRUALS_COMPLETE: a missing accrual blocks; a catch-up floored past the lock - the real ledger floor, value date kept - leaves"
            + " nothing behind and the period short: DISCLOSED with the figure, never PASSED (P14-TSK-006's I-1)")
    void aMissingAccrualBlocks() throws Exception {
        AccountingPeriodId january = lockJanuary();
        List<AccrualCompleteness.Lagging> behind = new ArrayList<>(List.of(
                new AccrualCompleteness.Lagging(AccrualCompleteness.Accruer.LENDING, "EUR", IN_JANUARY)));
        LedgerFlooredPostings floored = new LedgerFlooredPostings();
        AccrualCompleteness accruals = new AccrualCompleteness() {
            @Override
            public List<Lagging> lagging(Connection unitOfWork) {
                return behind;
            }

            @Override
            public List<Floored> flooredPast(Connection unitOfWork, LocalDate periodEnd) {
                return floored.accruals(unitOfWork, periodEnd);
            }
        };
        CloseChecks checks = checks(accruals, (snapshot, month) -> noDisclosure(month), reader());
        CheckVerdict blocked = checks.judge(claimed(CloseCheckCode.ACCRUALS_COMPLETE, january)).orElseThrow();
        assertThat(blocked.status()).isEqualTo(CloseCheckStatus.FAILED);
        assertThat(blocked.failures()).containsExactly(CloseChecks.ACCRUALS_BEHIND);

        // The catch-up across the lock: lending's own key namespace, the ledger's own floor.
        BigInteger before = flooredInterest(floored);
        post(IN_JANUARY, wallet(), operational(AccountPurpose.FEE_REVENUE), 125, PostingCommand.Dating.FLOOR_AT_FIRST_OPEN, "lending.accrual:");
        behind.clear();

        assertThat(flooredInterest(floored)).isEqualTo(before.add(BigInteger.valueOf(125)));
        CheckVerdict disclosed = checks.judge(claimed(CloseCheckCode.ACCRUALS_COMPLETE, january)).orElseThrow();
        assertThat(disclosed.status()).isEqualTo(CloseCheckStatus.DISCLOSED);
        assertThat(disclosed.evidence()).contains("\"accruer\":\"LENDING\"", "\"currency\":\"EUR\"", "interestMinor");
    }

    @Test
    @DisplayName("C-1: a BNPL sweep down 25-31 January catching up on 2 February, BNPL-F2a's own dating - posted the 2nd, valued each date: nothing"
            + " behind and the period short, DISCLOSED with the figures, never PASSED, the last day apart from anything older; a correction's"
            + " namespace and an entry valued after the end not counted; planted in one transaction and rolled back")
    void aBnplCatchUpIsDisclosed() throws Exception {
        AccountingPeriodId january = lockJanuary();
        LedgerAccount wallet = wallet();
        LedgerAccount revenue = operational(AccountPurpose.FEE_REVENUE);
        LocalDate second = JANUARY.plusMonths(1).atDay(2);
        LedgerFlooredPostings floored = new LedgerFlooredPostings();
        try (Connection plant = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            plant.setAutoCommit(false);
            plant.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            for (int day = 25; day <= 31; day++) {
                accrual(plant, BnplAccruals.KEY_PREFIX, second, JANUARY.atDay(day), wallet, revenue, 10);
            }
            accrual(plant, BnplAccruals.KEY_PREFIX, second, JANUARY.plusMonths(1).atDay(1), wallet, revenue, 99);
            accrual(plant, BnplAccruals.CORRECTION_KEY_PREFIX, second, JANUARY.atDay(25), wallet, revenue, 99);
            readOnlyFromHere(plant);

            assertThat(floored.accruals(plant, JANUARY.atEndOfMonth()))
                    .filteredOn(position -> position.accruer() == AccrualCompleteness.Accruer.BNPL)
                    .containsExactly(new AccrualCompleteness.Floored(AccrualCompleteness.Accruer.BNPL, "EUR", 2, 7, BigInteger.valueOf(70), 1,
                            BigInteger.TEN));
            AccrualCompleteness onThePlant = new AccrualCompleteness() {
                @Override
                public List<Lagging> lagging(Connection unitOfWork) {
                    return List.of();
                }

                @Override
                public List<Floored> flooredPast(Connection unitOfWork, LocalDate periodEnd) {
                    return floored.accruals(plant, periodEnd);
                }
            };
            CheckVerdict verdict = checks(onThePlant, (snapshot, month) -> noDisclosure(month), reader())
                    .judge(claimed(CloseCheckCode.ACCRUALS_COMPLETE, january)).orElseThrow();
            assertThat(verdict.status()).isEqualTo(CloseCheckStatus.DISCLOSED);
            assertThat(verdict.evidence()).contains("\"accruer\":\"BNPL\"", "\"lastDay\":{\"entries\":\"1\",\"interestMinor\":\"10\"}",
                    "\"older\":{\"entries\":\"6\",\"interestMinor\":\"60\"}");
            plant.rollback();
        }
    }

    @Test
    @DisplayName("I-4: ACCRUALS_COMPLETE over the real servicing reads, on the read-only snapshot production judges on: every read answers - a lock or"
            + " a write would be refused - and the verdict is what they say, never UNAVAILABLE")
    void theRealAccrualReadsRunOnAReadOnlySnapshot() throws Exception {
        AccountingPeriodId january = lockJanuary();
        AccrualLags lags = new AccrualLags(new JdbcLoanServicingStore(), accounts, new JdbcPurposeBalances());
        AccrualCompleteness real = new ServicingAccrualCompleteness(lags::oldestUnaccruedByCurrency,
                new JdbcBnplAccrualStore()::oldestUnaccruedByCurrency, new LedgerFlooredPostings()::accruals);
        LocalDate end = JANUARY.atEndOfMonth();

        List<AccrualCompleteness.Lagging> behind = SNAPSHOTS.read(snapshot -> real.blocking(snapshot, end));
        List<AccrualCompleteness.Floored> past = SNAPSHOTS.read(snapshot -> real.flooredPast(snapshot, end));
        CheckVerdict verdict = checks(real, (snapshot, month) -> noDisclosure(month), reader())
                .judge(claimed(CloseCheckCode.ACCRUALS_COMPLETE, january)).orElseThrow();

        assertThat(verdict.failures()).noneMatch(failure -> failure.startsWith(CloseChecks.UNAVAILABLE));
        assertThat(verdict.status()).isEqualTo(!behind.isEmpty() ? CloseCheckStatus.FAILED
                : past.isEmpty() ? CloseCheckStatus.PASSED : CloseCheckStatus.DISCLOSED);
    }

    // ------------------------------------------------------------------ the rest of the run's statements

    @Test
    @DisplayName("M-3: the trigger holds the lease for every writer: a live holder is not replaced, with reclaims + 1 or without; a lapsed one only"
            + " with reclaims + 1; nothing else moves reclaims")
    void aLiveClaimIsNotReplaced() throws Exception {
        CloseRuns runs = runs(new CloseRuns.Leases(Duration.ofSeconds(2), Duration.ofMillis(600), Duration.ofHours(1), 3, Duration.ofSeconds(1)));
        startedJanuary(runs);
        CloseRuns.ClaimedCheck held = TRANSACTIONS.inTransaction(uow -> runs.claim(uow, Actor.SYSTEM)).orElseThrow();
        String attempt = " WHERE id = '" + held.id().value() + "'";
        String other = "'" + UUID.randomUUID() + "'";
        String live = "claimed only once its lease has passed";
        String counted = "nothing else moves reclaims";

        refusedSaying("UPDATE accounting.close_check SET holder = " + other + attempt, live);
        refusedSaying("UPDATE accounting.close_check SET holder = " + other + ", reclaims = reclaims + 1" + attempt, live);
        refusedSaying("UPDATE accounting.close_check SET reclaims = reclaims + 1" + attempt, counted);
        refusedSaying("UPDATE accounting.close_check SET reclaims = reclaims + 2, holder = " + other + attempt, "one claim at a time");

        LockSupport.parkNanos(Duration.ofMillis(2500).toNanos());
        refusedSaying("UPDATE accounting.close_check SET holder = " + other + attempt, counted);
        refusedSaying("UPDATE accounting.close_check SET holder = NULL, reclaims = reclaims + 1" + attempt, counted);
        try (Connection app = DatabaseRoles.application();
                PreparedStatement replaced = app.prepareStatement("UPDATE accounting.close_check SET holder = " + other
                        + ", reclaims = reclaims + 1, lease_until = clock_timestamp() + interval '1 hour'" + attempt)) {
            assertThat(replaced.executeUpdate()).as("a lapsed holder, replaced and counted").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a deferral through the real SQL: the attempt given back, nobody's, claimable only after the wait - the trigger holds the wait for"
            + " every writer - and not a reclaim; the same attempt, and a second deferral by the old holder moves nothing")
    void aDeferralIsNotADeath() throws Exception {
        CloseRuns runs = runs(new CloseRuns.Leases(Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofHours(1), 3, Duration.ofSeconds(2)));
        startedJanuary(runs);
        CloseRuns.ClaimedCheck held = TRANSACTIONS.inTransaction(uow -> runs.claim(uow, Actor.SYSTEM)).orElseThrow();
        boolean deferred = TRANSACTIONS.inTransaction(uow -> runs.defer(uow, held));
        assertThat(deferred).isTrue();
        boolean deferredAgain = TRANSACTIONS.inTransaction(uow -> runs.defer(uow, held));
        assertThat(deferredAgain).as("no longer its holder").isFalse();
        refusedSaying("UPDATE accounting.close_check SET holder = '" + UUID.randomUUID() + "' WHERE id = '" + held.id().value() + "'",
                "claimed only once its lease has passed");
        assertThat(claimAll(runs)).extracting(CloseRuns.ClaimedCheck::id).as("within the wait").doesNotContain(held.id());

        LockSupport.parkNanos(Duration.ofMillis(2500).toNanos());
        CloseRuns.ClaimedCheck again = claimAll(runs).stream().filter(claimed -> claimed.id().equals(held.id())).findFirst().orElseThrow();
        assertThat(again.reclaims()).as("a deferral is not a death").isZero();
        assertThat(again.attempt()).isEqualTo(held.attempt());
    }

    @Test
    @DisplayName("a re-run through the real SQL (runUnder, the run FOR UPDATE, latestAttempt, the attempt's insert, its audit record): refused while"
            + " the check is pending, one new attempt, the view's history newest first (latestRun, ATTEMPTS_SQL)")
    void aRerunIsANewAttempt() throws Exception {
        CloseRuns runs = runs(CloseRuns.Leases.STANDARD);
        CloseRunId run = startedJanuary(runs);
        AccountingPeriodId january = id(JANUARY);
        Actor accountant = person();
        CloseCheckCode check = CloseCheckCode.PROJECTION_VERIFIED;
        assertThatThrownBy(() -> rerun(runs, january, check, accountant)).as("still pending").isInstanceOf(CloseRuns.CheckPending.class);

        CloseRuns.ClaimedCheck held = claimAll(runs).stream().filter(claimed -> claimed.check() == check).findFirst().orElseThrow();
        CheckVerdict drifting = CheckVerdict.failed(check, "source-proofs:v1", List.of(CloseChecks.PROJECTION_DRIFT),
                CanonicalJson.object().put("read", "whole"));
        assertThat(TRANSACTIONS.inTransaction(uow -> runs.conclude(uow, held, drifting)).moved()).isTrue();

        CloseRuns.Rerun rerun = rerun(runs, january, check, accountant);
        assertThat(rerun.run()).isEqualTo(run);
        assertThat(rerun.attempt()).isEqualTo(2);
        assertThatThrownBy(() -> rerun(runs, january, check, accountant)).as("two re-runs, one new attempt")
                .isInstanceOf(CloseRuns.CheckPending.class);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.CloseCheckRerun' AND target_id = '"
                + run.value() + "'")).isEqualTo(1);

        CloseRuns.RunView view = TRANSACTIONS.inTransaction(uow -> runs.view(uow, january)).orElseThrow();
        assertThat(view.run().id()).isEqualTo(run);
        assertThat(view.current()).isTrue();
        assertThat(view.complete()).isFalse();
        assertThat(view.checks()).hasSize(CloseCheckCode.values().length);
        CloseRuns.CheckView rerunCheck = view.checks().stream().filter(seen -> seen.check() == check).findFirst().orElseThrow();
        assertThat(rerunCheck.attempts()).isEqualTo(2);
        assertThat(rerunCheck.latest().status()).isEqualTo(CloseCheckStatus.PENDING);
        assertThat(rerunCheck.history()).extracting(CloseRunStore.AttemptRow::attempt).containsExactly(2, 1);
        CloseRunStore.AttemptRow first = rerunCheck.history().get(1);
        assertThat(first.status()).isEqualTo(CloseCheckStatus.FAILED);
        assertThat(first.failures()).containsExactly(CloseChecks.PROJECTION_DRIFT);
        assertThat(first.judgedBy()).contains("source-proofs:v1");
        assertThat(first.evidence()).contains("{\"read\":\"whole\"}");
    }

    @Test
    @DisplayName("the breaks' judgement and readiness through the real SQL (concludeOpenBreaks, countByStatus): a break planted in one transaction is"
            + " counted and fails it there, rolled back; the committed books judge it; readiness answers the present lock")
    void theBreaksJudgementAndReadinessReadTheMonth() throws Exception {
        CloseRuns runs = runs(CloseRuns.Leases.STANDARD);
        CloseRunId run = startedJanuary(runs);
        AccountingPeriodId january = id(JANUARY);
        JdbcAccountingBreakStore breaks = new JdbcAccountingBreakStore();
        for (CloseRuns.ClaimedCheck claimed : claimAll(runs)) {
            assertThat(TRANSACTIONS.inTransaction(uow -> runs.conclude(uow, claimed, passed(claimed.check()))).moved()).isTrue();
        }
        CloseRuns.Readiness pending = TRANSACTIONS.inTransaction(uow -> runs.readiness(uow, january));
        assertThat(pending.run()).contains(run);
        assertThat(pending.complete()).isFalse();
        assertThat(pending.notReady()).containsExactly(CloseCheckCode.OPEN_ACCOUNTING_BREAKS);
        CloseRuns.ClaimedCheck judged = TRANSACTIONS.inTransaction(uow -> runs.claim(uow, Actor.SYSTEM)).orElseThrow();
        assertThat(judged.check()).isEqualTo(CloseCheckCode.OPEN_ACCOUNTING_BREAKS);

        try (Connection plant = DatabaseRoles.application()) {
            plant.setAutoCommit(false);
            Map<BreakStatus, Long> before = breaks.countByStatus(plant, JANUARY);
            plantBreak(breaks, plant, "shape:" + IDS.next(), "entry planted");
            assertThat(breaks.countByStatus(plant, JANUARY).getOrDefault(BreakStatus.OPEN, 0L))
                    .isEqualTo(before.getOrDefault(BreakStatus.OPEN, 0L) + 1);
            CloseRuns.Concluded failed = runs.concludeOpenBreaks(plant, judged);
            assertThat(failed.moved()).isTrue();
            assertThat(failed.status()).isEqualTo(CloseCheckStatus.FAILED);
            plant.rollback();
        }

        String ofJanuary = "SELECT count(*) FROM accounting.accounting_break WHERE period_start = '" + JANUARY.atDay(1) + "' AND status = ";
        long open = count(ofJanuary + "'OPEN'");
        long acknowledged = count(ofJanuary + "'ACKNOWLEDGED'");
        CloseRuns.Concluded concluded = TRANSACTIONS.inTransaction(uow -> runs.concludeOpenBreaks(uow, judged));
        assertThat(concluded.status()).isEqualTo(open > 0 ? CloseCheckStatus.FAILED
                : acknowledged > 0 ? CloseCheckStatus.DISCLOSED : CloseCheckStatus.PASSED);
        assertThat(concluded.tookFor()).as("the run's last check: observed once").isPresent();
        CloseRuns.Readiness ready = TRANSACTIONS.inTransaction(uow -> runs.readiness(uow, january));
        assertThat(ready.complete()).isEqualTo(open == 0);
        assertThat(ready.openBreaks()).isEqualTo(open);
    }

    @Test
    @DisplayName("an unlock and a new lock: the read serves the old run with current=false until a poll starts the new one, readiness names none, a"
            + " re-run has none to name; the new run is the present lock's, the old rows stay and are never claimable")
    void aNewLockIsANewRun() throws Exception {
        CloseRuns runs = runs(CloseRuns.Leases.STANDARD);
        CloseRunId first = startedJanuary(runs);
        AccountingPeriodId january = id(JANUARY);
        AccountingPeriodFixture.restore();
        lockJanuary();

        CloseRuns.RunView stale = TRANSACTIONS.inTransaction(uow -> runs.view(uow, january)).orElseThrow();
        assertThat(stale.run().id()).isEqualTo(first);
        assertThat(stale.current()).isFalse();
        assertThat(stale.complete()).isFalse();
        CloseRuns.Readiness none = TRANSACTIONS.inTransaction(uow -> runs.readiness(uow, january));
        assertThat(none.run()).isEmpty();
        assertThat(none.complete()).isFalse();
        assertThat(none.notReady()).containsExactly(CloseCheckCode.values());
        assertThatThrownBy(() -> rerun(runs, january, CloseCheckCode.PROJECTION_VERIFIED, person()))
                .isInstanceOf(CloseRuns.RunNotStarted.class);
        assertThat(claimAll(runs)).as("the old lock's attempts are never claimable").isEmpty();

        List<CloseRunId> started = TRANSACTIONS.inTransaction(uow -> runs.startMissing(uow, Actor.SYSTEM));
        assertThat(started).singleElement().isNotEqualTo(first);
        CloseRuns.RunView present = TRANSACTIONS.inTransaction(uow -> runs.view(uow, january)).orElseThrow();
        assertThat(present.run().id()).isEqualTo(started.get(0));
        assertThat(present.current()).isTrue();
        assertThat(count("SELECT count(*) FROM accounting.close_check WHERE close_run_id = '" + first.value() + "'")).as("the old rows stay")
                .isEqualTo(9);
    }

    @Test
    @DisplayName("VALUATION_SET_APPROVED through the real SQL, on the read-only snapshot (ofPeriod): the period's sets read, the answer what the table"
            + " says - January ends another suite with one approved, so the answer is read off the table")
    void theValuationCheckReadsThePeriodsSets() throws Exception {
        AccountingPeriodId january = lockJanuary();
        String ofJanuary = "SELECT count(*) FROM accounting.valuation_rate_set WHERE period_id = '" + january.value() + "'";
        long all = count(ofJanuary);
        long approved = count(ofJanuary + " AND status = 'APPROVED'");
        List<ValuationRateSetStore.SetRow> sets = SNAPSHOTS.read(snapshot -> new JdbcValuationRateSetStore().ofPeriod(snapshot, january, 20));
        assertThat(sets).hasSize((int) Math.min(all, 20)).allSatisfy(set -> {
            assertThat(set.period()).isEqualTo(january);
            assertThat(set.month()).isEqualTo(JANUARY);
        });

        Optional<CheckVerdict> verdict = checks(caughtUp(), (snapshot, month) -> noDisclosure(month), reader())
                .judge(claimed(CloseCheckCode.VALUATION_SET_APPROVED, january));
        if (approved == 0) {
            assertThat(verdict).as("P14-TSK-012: no approved set is 'not yet', never FAILED").isEmpty();
        } else {
            assertThat(verdict.orElseThrow().failures()).noneMatch(failure -> failure.startsWith(CloseChecks.UNAVAILABLE));
        }
    }

    @Test
    @DisplayName("I-1 through the real SQL: held answers the evidence a break was raised with, byte for byte, beside its status, per check")
    void theHeldEvidenceIsWhatWasRaised() throws Exception {
        JdbcAccountingBreakStore breaks = new JdbcAccountingBreakStore();
        try (Connection plant = DatabaseRoles.application()) {
            plant.setAutoCommit(false);
            String subject = "shape:" + IDS.next();
            String evidence = "entry=planted, lines=[DEBIT CUSTOMER_WALLET, CREDIT FEE_REVENUE]";
            plantBreak(breaks, plant, subject, evidence);
            assertThat(breaks.held(plant, JANUARY, CloseCheckCode.RULE_CONFORMANCE))
                    .containsEntry(subject, new AccountingBreakStore.Held(BreakStatus.OPEN, evidence));
            assertThat(breaks.held(plant, JANUARY, CloseCheckCode.GL_EQUALS_LEDGER)).doesNotContainKey(subject);
            plant.rollback();
        }
    }

    // ------------------------------------------------------------------ the disclosure

    @Test
    @DisplayName("RECONCILIATION_DISCLOSURE (INV-ACC-05): the real reads agree with reconciliation's own, gross per currency; a suspense balance"
            + " planted in one transaction is on the record - DISCLOSED, never PASSED - and leaves nothing behind")
    void suspenseIsDisclosed() throws Exception {
        ReconciliationDisclosure disclosure = new ProductReconciliationDisclosure(new JdbcReportReadings(), new LedgerFlooredPostings());
        // A cutoff past everything: as of it, the reading is the books as they stand - compared with reconciliation's own reads.
        YearMonth future = YearMonth.of(2099, 12);
        // On the connection production judges on: REPEATABLE READ READ ONLY, both reads one snapshot - a lock or a write is refused.
        record Whole(ReconciliationDisclosure.Disclosure read, List<SuspenseReadings.OpenSuspenseRemainder> remainders) {}
        Whole whole = SNAPSHOTS.read(snapshot -> new Whole(disclosure.asOfCutoff(snapshot, future),
                new JdbcSuspenseReadings().openRemainders(snapshot)));
        BigInteger credit = BigInteger.ZERO;
        BigInteger debit = BigInteger.ZERO;
        for (SuspenseReadings.OpenSuspenseRemainder remainder : whole.remainders()) {
            if (remainder.remainder().currency().equals(EUR)) {
                BigInteger minor = BigInteger.valueOf(remainder.remainder().minorUnits());
                credit = remainder.side() == SuspenseSide.CREDIT ? credit.add(minor) : credit;
                debit = remainder.side() == SuspenseSide.DEBIT ? debit.add(minor) : debit;
            }
        }
        BigInteger disclosedCredit = whole.read().suspense().stream().filter(item -> item.currency().equals("EUR"))
                .map(ReconciliationDisclosure.Suspense::creditUnreleasedMinor).reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger disclosedDebit = whole.read().suspense().stream().filter(item -> item.currency().equals("EUR"))
                .map(ReconciliationDisclosure.Suspense::debitUnreleasedMinor).reduce(BigInteger.ZERO, BigInteger::add);
        assertThat(disclosedCredit).isEqualTo(credit);
        assertThat(disclosedDebit).isEqualTo(debit);
        assertThat(whole.read().breaks().stream().mapToLong(ReconciliationDisclosure.OpenBreaks::breaks).sum())
                .isEqualTo(count("SELECT count(*) FROM reconciliation.break WHERE status <> 'RESOLVED'"));
        assertThat(whole.read().raisedSinceStillOpen()).isZero();

        LedgerAccount suspenseAccount = operational(AccountPurpose.SUSPENSE_UNMATCHED);
        LedgerAccount wallet = wallet();
        try (Connection plant = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            plant.setAutoCommit(false);
            plant.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            BigInteger creditsBefore = ledgerCredits(disclosure.asOfCutoff(plant, JANUARY));
            posting().post(plant, new PostingCommand("close-run-suspense-" + IDS.next(), IN_JANUARY, IN_JANUARY, "close-run-suite",
                    List.of(new JournalLine(wallet.id(), Direction.DEBIT, Money.ofMinorUnits(700, EUR)),
                            new JournalLine(suspenseAccount.id(), Direction.CREDIT, Money.ofMinorUnits(700, EUR)))));
            readOnlyFromHere(plant);

            ReconciliationDisclosure.Disclosure planted = disclosure.asOfCutoff(plant, JANUARY);
            assertThat(ledgerCredits(planted)).isEqualTo(creditsBefore.add(BigInteger.valueOf(700)));
            assertThat(planted.nothingToDisclose()).isFalse();
            assertThat(planted.evidence().members()).containsKeys("suspense", "openBreaks");

            CheckVerdict verdict = checks(caughtUp(), (snapshot, month) -> disclosure.asOfCutoff(plant, month), reader())
                    .judge(claimed(CloseCheckCode.RECONCILIATION_DISCLOSURE, id(JANUARY))).orElseThrow();
            assertThat(verdict.status()).isEqualTo(CloseCheckStatus.DISCLOSED);
            assertThat(verdict.evidence()).contains("ledgerCreditsMinor");
            plant.rollback();
        }
    }

    // ------------------------------------------------------------------ gate criterion 1

    @Test
    @DisplayName("gate criterion 1: a planted unbalanced entry fails LEDGER_TRIAL_BALANCE, and only then - planted, locked, generated and judged on"
            + " one connection (the balance trigger is deferred to commit), rolled back; the same books without it pass")
    void aPlantedImbalanceFailsOnlyItsOwnTransaction() throws Exception {
        LedgerAccount wallet = wallet();
        AccountingPeriodId january = id(JANUARY);
        Actor accountant = person();
        try (Connection plant = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(accountant);
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            plant.setAutoCommit(false);
            rawOneSidedEntry(plant, wallet);
            periods().lock(plant, january, accountant, null, correlation());

            AccountingTransactions onThePlant = new AccountingTransactions() {
                @Override
                public <R> R inTransaction(Function<Connection, R> work) {
                    return work.apply(plant);
                }
            };
            ReportSnapshots seeingThePlant = new ReportSnapshots() {
                @Override
                public <R> R read(Function<Connection, R> work) {
                    return work.apply(plant);
                }
            };
            FinalTrialBalance planted = new FinalTrialBalanceReader(REPORT_RUNS, generator(seeingThePlant, onThePlant), onThePlant,
                    FinalTrialBalanceReader.overSnapshots(seeingThePlant));
            CheckVerdict verdict = checks(caughtUp(), (snapshot, month) -> noDisclosure(month), planted)
                    .judge(claimed(CloseCheckCode.LEDGER_TRIAL_BALANCE, january)).orElseThrow();

            assertThat(verdict.status()).isEqualTo(CloseCheckStatus.FAILED);
            assertThat(verdict.failures()).containsExactly(CloseChecks.TRIAL_BALANCE_FALSE);
            assertThat(verdict.evidence()).contains("\"passes\":false");
            plant.rollback();
        }

        lockJanuary();
        CheckVerdict clean = checks(caughtUp(), (snapshot, month) -> noDisclosure(month), reader())
                .judge(claimed(CloseCheckCode.LEDGER_TRIAL_BALANCE, january)).orElseThrow();
        assertThat(clean.status()).as("and only then").isEqualTo(CloseCheckStatus.PASSED);
    }

    // ------------------------------------------------------------------ the ranks

    @Test
    @DisplayName("the ranks for every writer: a concluded attempt frozen, DISCLOSED only where the check admits it, a run only under its period's"
            + " present lock, an attempt with no gap, one PENDING per check, nothing deleted")
    void theRanksHoldForEveryWriter() throws Exception {
        CloseRuns runs = runs(CloseRuns.Leases.STANDARD);
        CloseRunId run = startedJanuary(runs);
        CloseRuns.ClaimedCheck held = TRANSACTIONS.inTransaction(uow -> runs.claim(uow, Actor.SYSTEM)).orElseThrow();
        assertThat(held.check()).isEqualTo(CloseCheckCode.LEDGER_TRIAL_BALANCE);

        refused("UPDATE accounting.close_check SET status = 'DISCLOSED', judged_by = 'report:TRIAL_BALANCE:v1', evidence = '{}' WHERE id = '"
                + held.id().value() + "'");
        TRANSACTIONS.inTransaction(uow -> runs.conclude(uow, held, passed(held.check())));
        refused("UPDATE accounting.close_check SET status = 'FAILED', failures = 'X' WHERE id = '" + held.id().value() + "'");
        refused("UPDATE accounting.close_check SET evidence = '{\"rewritten\":\"true\"}' WHERE id = '" + held.id().value() + "'");
        refused("DELETE FROM accounting.close_check WHERE id = '" + held.id().value() + "'");
        refused("DELETE FROM accounting.close_run WHERE id = '" + run.value() + "'");
        refused("INSERT INTO accounting.close_run (id, period_id, period_locked_at, period_revision, started_at, correlation_id)"
                + " SELECT gen_random_uuid(), id, now(), revision, now(), 'c' FROM accounting.accounting_period WHERE id = '"
                + id(JANUARY).value() + "'");
        refused("INSERT INTO accounting.close_check (id, close_run_id, check_code, attempt, status, requested_by, requested_by_type, born_at,"
                + " reclaims, lease_until, correlation_id) VALUES (gen_random_uuid(), '" + run.value() + "', 'RULE_CONFORMANCE', 3, 'PENDING',"
                + " 'platform', 'SYSTEM', now(), 0, now(), 'c')");
        refused("INSERT INTO accounting.close_check (id, close_run_id, check_code, attempt, status, requested_by, requested_by_type, born_at,"
                + " reclaims, lease_until, correlation_id) VALUES (gen_random_uuid(), '" + run.value() + "', 'RULE_CONFORMANCE', 2, 'PENDING',"
                + " 'platform', 'SYSTEM', now(), 0, now(), 'c')");
    }

    // ------------------------------------------------------------------ plumbing

    private static CloseRuns runs(CloseRuns.Leases leases) {
        return new CloseRuns(new JdbcCloseRunStore(), AccountingPeriodFixture.STORE, new JdbcAccountingBreakStore(), leases,
                new JdbcAuditWriter(), IDS, NOW);
    }

    private static AccountingPeriodId lockJanuary() {
        AccountingPeriodId january = id(JANUARY);
        Actor accountant = person();
        inScope(accountant, uow -> periods().lock(uow, january, accountant, null, correlation()));
        return january;
    }

    private static CloseRunId startedJanuary(CloseRuns runs) {
        lockJanuary();
        return TRANSACTIONS.inTransaction(uow -> runs.startMissing(uow, Actor.SYSTEM)).get(0);
    }

    /** A worker's claim under a lock_timeout - a claimer that waited on a held row fails, as without SKIP LOCKED it would. */
    private static Optional<CloseRuns.ClaimedCheck> claimUnderALockTimeout(CloseRuns runs) {
        return TRANSACTIONS.inTransaction(uow -> {
            try (PreparedStatement bound = uow.prepareStatement("SET LOCAL lock_timeout = '2s'")) {
                bound.execute();
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
            return runs.claim(uow, Actor.SYSTEM);
        });
    }

    private static List<CloseRuns.ClaimedCheck> claimAll(CloseRuns runs) {
        List<CloseRuns.ClaimedCheck> claimed = new ArrayList<>();
        Optional<CloseRuns.ClaimedCheck> next;
        while ((next = TRANSACTIONS.inTransaction(uow -> runs.claim(uow, Actor.SYSTEM))).isPresent()) {
            claimed.add(next.get());
        }
        return claimed;
    }

    private static CloseRuns.Rerun rerun(CloseRuns runs, AccountingPeriodId period, CloseCheckCode check, Actor actor) {
        return TRANSACTIONS.inTransaction(uow -> runs.rerun(uow, period, check, actor, "the projection was repaired", correlation()));
    }

    /** One break of January on {@code plant}, for its transaction alone. */
    private static void plantBreak(JdbcAccountingBreakStore breaks, Connection plant, String subject, String evidence) {
        breaks.insertIfAbsent(plant, new AccountingBreakStore.NewBreak(AccountingBreakId.next(IDS), JANUARY, new BreakFinding(
                CloseCheckCode.RULE_CONFORMANCE, BreakType.SHAPE_MISMATCH, subject, evidence, "posting-rules:v2"), Actor.SYSTEM, correlation()));
    }

    /** The rest of {@code plant}'s transaction reads only, as a judge's snapshot does - its own writes still visible to it. */
    private static void readOnlyFromHere(Connection plant) throws SQLException {
        try (PreparedStatement readOnly = plant.prepareStatement("SET TRANSACTION READ ONLY")) {
            readOnly.execute();
        }
    }

    /** One accrual entry in an accruer's own dating - posted {@code posted}, valued {@code valued} - on {@code plant}. */
    private static void accrual(Connection plant, String keyPrefix, LocalDate posted, LocalDate valued, LedgerAccount debit, LedgerAccount credit,
            long minor) {
        posting().post(plant, new PostingCommand(keyPrefix + IDS.next() + ":" + valued, posted, valued, "close-run-suite",
                List.of(new JournalLine(debit.id(), Direction.DEBIT, Money.ofMinorUnits(minor, EUR)),
                        new JournalLine(credit.id(), Direction.CREDIT, Money.ofMinorUnits(minor, EUR)))));
    }

    private static CheckVerdict passed(CloseCheckCode check) {
        return CheckVerdict.passed(check, "source-proofs:v1", CanonicalJson.object().put("read", "whole"));
    }

    private static CloseRuns.ClaimedCheck claimed(CloseCheckCode check, AccountingPeriodId period) {
        return new CloseRuns.ClaimedCheck(com.finapp.accounting.CloseCheckId.next(IDS), CloseRunId.next(IDS), period, JANUARY, check, 1,
                IDS.next(), 0, false, correlation(), Actor.SYSTEM);
    }

    private static ReportRunGenerator generator() {
        return generator(REPORT_SNAPSHOTS, TRANSACTIONS);
    }

    private static ReportRunGenerator generator(ReportSnapshots snapshots, AccountingTransactions transactions) {
        return new ReportRunGenerator(REPORT_RUNS, new ReportGeneration(ReportGeneration.FROM_THE_SNAPSHOT,
                ReportRuns.Leases.STANDARD.generationTimeout(), NOW), snapshots, transactions,
                new ReportRunMetrics(new SimpleMeterRegistry(), DEFINITIONS));
    }

    private static FinalTrialBalance reader() {
        return new FinalTrialBalanceReader(REPORT_RUNS, generator(), TRANSACTIONS, FinalTrialBalanceReader.overSnapshots(REPORT_SNAPSHOTS));
    }

    private static CloseChecks checks(AccrualCompleteness accruals, ReconciliationDisclosure disclosure, FinalTrialBalance trialBalance) {
        SourceProofs clean = (snapshot, source) -> List.of(new SourceProofs.Reading(SourceProofs.Proof.PROJECTION, true, List.of()));
        ValuationRates reference = new ValuationRates() {
            @Override
            public List<CurrencyCode> valued() {
                return List.of();
            }

            @Override
            public List<Observed> lastObservedBefore(Connection unitOfWork, Instant before) {
                return List.of();
            }
        };
        ValuationRateSets sets = new ValuationRateSets(new JdbcValuationRateSetStore(), AccountingPeriodFixture.STORE, reference,
                ValuationStaleness.standard(), new JdbcAuditWriter(), IDS, NOW);
        return new CloseChecks((month, check, actor, correlation) -> {
            throw new AssertionError("this suite's cases raise no break");
        }, trialBalance, accruals, clean, disclosure, (snapshot, month) -> ran(month), sets, AccountingPeriodFixture.STORE, new AccountingChecks.Snapshots() {
            @Override
            public <R> R read(Function<Connection, R> work) {
                return SNAPSHOTS.read(work);
            }
        });
    }

    private static AccrualCompleteness caughtUp() {
        return new AccrualCompleteness() {
            @Override
            public List<Lagging> lagging(Connection unitOfWork) {
                return List.of();
            }

            @Override
            public List<Floored> flooredPast(Connection unitOfWork, LocalDate periodEnd) {
                return List.of();
            }
        };
    }

    /** The month's runs as the disclosure's check needs them, stood in: the rule itself is CloseChecksTest's and PeriodCloseDatabaseTest's. */
    private static com.finapp.accounting.ReconciliationRunCoverage.Coverage ran(YearMonth month) {
        UUID source = UUID.fromString("01a0e2bc-8200-7001-8000-000000000001");
        return new com.finapp.accounting.ReconciliationRunCoverage.Coverage(month, List.of(new com.finapp.accounting.ReconciliationRunCoverage.Line(source, "BATCH", "COMPLETED", month.atDay(1), false, 1)),
                List.of(new com.finapp.accounting.ReconciliationRunCoverage.Source(source, "simulated-psp.settlement", com.finapp.accounting.ReconciliationRunCoverage.SourceState.ACTIVE)));
    }

    private static ReconciliationDisclosure.Disclosure noDisclosure(YearMonth month) {
        return new ReconciliationDisclosure.Disclosure(month.atEndOfMonth(), Instant.EPOCH, List.of(), 0, List.of(), 0,
                new java.util.TreeMap<>());
    }

    private static BigInteger ledgerCredits(ReconciliationDisclosure.Disclosure read) {
        return read.suspense().stream().filter(item -> item.currency().equals("EUR"))
                .map(ReconciliationDisclosure.Suspense::ledgerCreditsMinor).reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static BigInteger flooredInterest(LedgerFlooredPostings floored) {
        return SNAPSHOTS.read(snapshot -> floored.accruals(snapshot, JANUARY.atEndOfMonth())).stream()
                .filter(position -> position.currency().equals("EUR")).map(AccrualCompleteness.Floored::interestMinor)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    /** An entry and one line, raw: the balance trigger is deferred to a commit that never comes. */
    private static void rawOneSidedEntry(Connection app, LedgerAccount account) throws SQLException {
        UUID entry = IDS.next();
        String flow = UUID.randomUUID().toString();
        try (PreparedStatement insert = app.prepareStatement("INSERT INTO ledger.journal_entry (id, posting_date, value_date, entry_type,"
                + " reference, actor_id, correlation_id, causation_id, idempotency_scope, created_at)"
                + " VALUES (?, ?, ?, 'POSTING', 'raw-sql', 'raw', ?, ?, ?, ?)")) {
            insert.setObject(1, entry);
            insert.setObject(2, IN_JANUARY);
            insert.setObject(3, IN_JANUARY);
            insert.setString(4, flow);
            insert.setString(5, flow);
            insert.setString(6, PostingService.IDEMPOTENCY_SCOPE + ":raw-" + IDS.next());
            insert.setTimestamp(7, Timestamp.from(NOW.instant()));
            insert.executeUpdate();
        }
        try (PreparedStatement line = app.prepareStatement("INSERT INTO ledger.journal_line (id, entry_id, ledger_account_id, direction,"
                + " amount_minor, currency, scale, seq) VALUES (?, ?, ?, 'DEBIT', 1, 'EUR', 2, 1)")) {
            line.setObject(1, IDS.next());
            line.setObject(2, entry);
            line.setObject(3, account.id().value());
            line.executeUpdate();
        }
    }

    private LedgerAccount wallet() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount account = accounts.createOrConverge(app, LedgerAccount.owned(IDS, NOW, AccountType.LIABILITY,
                    AccountPurpose.CUSTOMER_WALLET, EUR, IDS.next())).account();
            app.commit();
            return account;
        }
    }

    private LedgerAccount operational(AccountPurpose purpose) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return accounts.findOperational(app, purpose, EUR).orElseThrow();
        }
    }

    private static PostingService posting() {
        return new PostingService(new IdempotentExecutor(new JdbcIdempotencyRecordStore(), NOW, Duration.ofDays(1), Duration.ofMinutes(5)),
                new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(), new JdbcBalanceProjection(), IDS, NOW,
                PostingObserver.NONE);
    }

    /** One balanced entry through the ledger's own door, committed. */
    private static void post(LocalDate day, LedgerAccount debit, LedgerAccount credit, long minor, PostingCommand.Dating dating,
            String keyPrefix) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            posting().post(app, new PostingCommand(keyPrefix + IDS.next(), day, day, "close-run-suite",
                    List.of(new JournalLine(debit.id(), Direction.DEBIT, Money.ofMinorUnits(minor, EUR)),
                            new JournalLine(credit.id(), Direction.CREDIT, Money.ofMinorUnits(minor, EUR))), dating));
            app.commit();
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement read = migrator.prepareStatement(sql);
                ResultSet row = read.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    /** {@code sql} as the application refused by the machine's trigger, in the words given, and rolled back. */
    private static void refusedSaying(String sql, String words) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try (PreparedStatement statement = app.prepareStatement(sql)) {
                assertThatThrownBy(statement::executeUpdate).as(sql).isInstanceOf(SQLException.class).hasMessageContaining(words);
            }
            app.rollback();
        }
    }

    /** {@code sql} as the application refused - by privilege or by the machine's trigger or constraint - and rolled back. */
    private static void refused(String sql) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try (PreparedStatement statement = app.prepareStatement(sql)) {
                assertThatThrownBy(statement::executeUpdate).as(sql).isInstanceOf(SQLException.class);
            }
            app.rollback();
        }
    }

}
