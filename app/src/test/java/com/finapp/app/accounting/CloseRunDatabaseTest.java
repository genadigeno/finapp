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

import com.finapp.accounting.AccountingChecks;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccrualCompleteness;
import com.finapp.accounting.CheckVerdict;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseCheckStatus;
import com.finapp.accounting.CloseChecks;
import com.finapp.accounting.CloseRunId;
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
import com.finapp.accounting.ValuationRateSets;
import com.finapp.accounting.ValuationRates;
import com.finapp.accounting.ValuationStaleness;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.TrialBalanceDefinition;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.PeriodClosedException;
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
 * The close run against a live PostgreSQL (`P14-TSK-011`; {@code PHASE_14_PLAN.md} sections 8.3 rows 5-6, 13.5, 14.2 scenarios 8 and 9;
 * {@code INV-ACC-05}, {@code INV-ACC-09}) - the entry's five named cases and what the design needs: the claim's {@code SKIP LOCKED} over ten
 * connections, a dead worker reclaimed and fenced, a live worker renewing past its lease, postings during the run, the accruals, the disclosure, the
 * planted imbalance, and the ranks for every writer. <strong>Written and owed</strong> (owner decision C5: the database tiers are skipped per task).
 *
 * <p><strong>The books it uses.</strong> The fixture's <strong>January 1999</strong> ({@link AccountingPeriodFixture}: before `P14-TSK-012` builds the
 * close it is the only period the fixtures can lock); every case returns it to {@code OPEN} and each lock is a new run, so a rerun on a kept database
 * starts clean. It approves <strong>no valuation set</strong> - January ends {@code ValuationRateSetDatabaseTest} with one approved for good, so no case
 * asserts {@code VALUATION_SET_APPROVED}'s status. <strong>What cannot be committed is planted in one transaction</strong> and rolled back: a real
 * product discrepancy, or a line on {@code SUSPENSE_UNMATCHED}, would fail every other suite's proof for good. <strong>The planted imbalance</strong> (gate
 * criterion 1; `P14-TSK-009`'s review, M-6): the balance trigger is deferred to commit, so an unbalanced entry is visible only on its own connection - and
 * is therefore read there: planted, locked, claimed, generated and judged on that one connection, then rolled back, nothing left behind.
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
    @DisplayName("scenario 8: postings during the run change nothing dated in the period - one dated in January refused, one dated after it"
            + " committed; the FINAL trial balance the same run, regenerating byte-identical")
    void postingsDuringTheRunChangeNothing() throws Exception {
        AccountingPeriodId january = lockJanuary();
        LedgerAccount wallet = wallet();
        LedgerAccount revenue = operational(AccountPurpose.FEE_REVENUE);
        FinalTrialBalance reader = reader();
        FinalTrialBalance.Verdict before = (FinalTrialBalance.Verdict) reader.read(january, Actor.SYSTEM, correlation());

        assertThatThrownBy(() -> post(IN_JANUARY, wallet, revenue, 25, PostingCommand.Dating.AS_STATED, "close-run-refused-"))
                .isInstanceOf(PeriodClosedException.class);
        post(DAY_AFTER, wallet, revenue, 25, PostingCommand.Dating.AS_STATED, "close-run-after-");

        FinalTrialBalance.Verdict after = (FinalTrialBalance.Verdict) reader.read(january, Actor.SYSTEM, correlation());
        assertThat(after).as("the same run, the same frozen content").isEqualTo(before);
        ReportRunStore.RunRow run = TRANSACTIONS.inTransaction(uow -> REPORT_RUNS.find(uow,
                com.finapp.accounting.ReportRunId.of(UUID.fromString(before.run())))).orElseThrow();
        assertThat(generator().regenerate(run.id()).identical()).as("the period's range is immutable under the lock").isTrue();
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

    // ------------------------------------------------------------------ the disclosure

    @Test
    @DisplayName("RECONCILIATION_DISCLOSURE (INV-ACC-05): the real reads agree with reconciliation's own, gross per currency; a suspense balance"
            + " planted in one transaction is on the record - DISCLOSED, never PASSED - and leaves nothing behind")
    void suspenseIsDisclosed() throws Exception {
        ReconciliationDisclosure disclosure = new ProductReconciliationDisclosure(new JdbcReportReadings(), new LedgerFlooredPostings());
        // A cutoff past everything: as of it, the reading is the books as they stand - compared with reconciliation's own reads.
        YearMonth future = YearMonth.of(2099, 12);
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            ReconciliationDisclosure.Disclosure read = disclosure.asOfCutoff(app, future);
            SuspenseReadings suspense = new JdbcSuspenseReadings();
            BigInteger credit = BigInteger.ZERO;
            BigInteger debit = BigInteger.ZERO;
            for (SuspenseReadings.OpenSuspenseRemainder remainder : suspense.openRemainders(app)) {
                if (remainder.remainder().currency().equals(EUR)) {
                    BigInteger minor = BigInteger.valueOf(remainder.remainder().minorUnits());
                    credit = remainder.side() == SuspenseSide.CREDIT ? credit.add(minor) : credit;
                    debit = remainder.side() == SuspenseSide.DEBIT ? debit.add(minor) : debit;
                }
            }
            BigInteger disclosedCredit = read.suspense().stream().filter(item -> item.currency().equals("EUR"))
                    .map(ReconciliationDisclosure.Suspense::creditUnreleasedMinor).reduce(BigInteger.ZERO, BigInteger::add);
            BigInteger disclosedDebit = read.suspense().stream().filter(item -> item.currency().equals("EUR"))
                    .map(ReconciliationDisclosure.Suspense::debitUnreleasedMinor).reduce(BigInteger.ZERO, BigInteger::add);
            assertThat(disclosedCredit).isEqualTo(credit);
            assertThat(disclosedDebit).isEqualTo(debit);
            assertThat(read.breaks().stream().mapToLong(ReconciliationDisclosure.OpenBreaks::breaks).sum())
                    .isEqualTo(count("SELECT count(*) FROM reconciliation.break WHERE status <> 'RESOLVED'"));
            assertThat(read.raisedSinceStillOpen()).isZero();
            app.rollback();
        }

        LedgerAccount suspenseAccount = operational(AccountPurpose.SUSPENSE_UNMATCHED);
        LedgerAccount wallet = wallet();
        try (Connection plant = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            plant.setAutoCommit(false);
            BigInteger creditsBefore = ledgerCredits(disclosure.asOfCutoff(plant, JANUARY));
            posting().post(plant, new PostingCommand("close-run-suspense-" + IDS.next(), IN_JANUARY, IN_JANUARY, "close-run-suite",
                    List.of(new JournalLine(wallet.id(), Direction.DEBIT, Money.ofMinorUnits(700, EUR)),
                            new JournalLine(suspenseAccount.id(), Direction.CREDIT, Money.ofMinorUnits(700, EUR)))));

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
        }, trialBalance, accruals, clean, disclosure, sets, AccountingPeriodFixture.STORE, new AccountingChecks.Snapshots() {
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
