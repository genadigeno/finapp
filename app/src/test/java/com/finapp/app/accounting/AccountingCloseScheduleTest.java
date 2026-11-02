package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.AccrualCompleteness;
import com.finapp.accounting.CheckOutcome;
import com.finapp.accounting.CloseCheckStatus;
import com.finapp.accounting.CloseChecks;
import com.finapp.accounting.CloseRunStore;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.FinalTrialBalance;
import com.finapp.accounting.LockGrace;
import com.finapp.accounting.PostingCutoff;
import com.finapp.accounting.ReconciliationDisclosure;
import com.finapp.accounting.SourceProofs;
import com.finapp.accounting.ValuationRateSets;
import com.finapp.accounting.ValuationRates;
import com.finapp.accounting.ValuationStaleness;
import com.finapp.accounting.testing.InMemoryAccountingBreakStore;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryCloseRunStore;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.InMemoryValuationRateSetStore;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code AccountingCloseSchedule}'s three legs (`P14-TSK-011`; {@code PHASE_14_PLAN.md} section 8.4): the months opened, every lock's run started
 * and its checks taken, the orphans completed <strong>one run per call</strong> (`P14-TSK-008`'s review, M-6); a leg's failure counted and ending
 * only itself, each leg as the platform, the lifecycle running every leg. Hermetic; {@code CloseRunDatabaseTest} is the live half (owed, C5).
 */
@DisplayName("the accounting close schedule: three legs, leaderless (P14-TSK-011)")
class AccountingCloseScheduleTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2097-04-05T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);

    private final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
    private final InMemoryCloseRunStore store = new InMemoryCloseRunStore(periods);
    private final CloseRuns runs = new CloseRuns(store, periods, new InMemoryAccountingBreakStore(), CloseRuns.Leases.STANDARD,
            (uow, record) -> { }, IDS, CLOCK);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CloseRunMetrics metrics = new CloseRunMetrics(registry);
    private final AccountingTransactions transactions = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            return work.apply(UOW);
        }
    };
    private AccountingCloseSchedule.Orphans orphans = (limit, actor) -> 0;
    private AccountingCloseSchedule.Cadence cadence = AccountingCloseSchedule.Cadence.STANDARD;

    @Test
    @DisplayName("the open leg opens this month and writes nothing the second time - as the platform")
    void theOpenLegOpensOnce() {
        assertThat(schedule().openOnce()).isEqualTo(1);
        assertThat(periods.month(YearMonth.of(2097, 4))).isPresent();
        assertThat(schedule().openOnce()).as("insert-if-absent").isZero();
        assertThat(periods.history()).singleElement().satisfies(row -> assertThat(row.actor()).isEqualTo(Actor.SYSTEM));
    }

    @Test
    @DisplayName("the check leg starts every lock's run and takes its checks one at a time until none is claimable: every check recorded")
    void theCheckLegRunsTheChecks() {
        lockedMarch();
        assertThat(schedule().checksOnce()).isEqualTo(9);
        assertThat(store.runs()).hasSize(1);
        assertThat(store.all(store.runs().get(0).id())).allSatisfy(attempt ->
                assertThat(attempt.status()).as(attempt.check().name()).isNotEqualTo(CloseCheckStatus.PENDING));
        assertThat(schedule().checksOnce()).as("nothing left").isZero();
        assertThat(store.runs()).as("one run per lock").hasSize(1);
    }

    @Test
    @DisplayName("the check leg stops at its batch; the rest wait for the next tick, on any instance")
    void theCheckLegStopsAtItsBatch() {
        lockedMarch();
        cadence = new AccountingCloseSchedule.Cadence(Duration.ofHours(1), Duration.ofSeconds(30), Duration.ofMinutes(1), 2, 10);
        assertThat(schedule().checksOnce()).isEqualTo(2);
        CloseRunStore.RunRow run = store.runs().get(0);
        assertThat(store.all(run.id())).filteredOn(attempt -> attempt.status() == CloseCheckStatus.PENDING).hasSize(7);
    }

    @Test
    @DisplayName("the orphan leg completes one run per call (M-6), until none is left or its batch is done: a limit above one would start"
            + " every later run's lease before its generation")
    void theOrphanLegCompletesOneRunAtATime() {
        List<Integer> limits = new ArrayList<>();
        List<Actor> actors = new ArrayList<>();
        int[] left = {3};
        orphans = (limit, actor) -> {
            limits.add(limit);
            actors.add(actor);
            return left[0]-- > 0 ? limit : 0;
        };
        assertThat(schedule().orphansOnce()).isEqualTo(3);
        assertThat(limits).containsExactly(1, 1, 1, 1);
        assertThat(actors).containsOnly(Actor.SYSTEM);

        cadence = new AccountingCloseSchedule.Cadence(Duration.ofHours(1), Duration.ofSeconds(30), Duration.ofMinutes(1), 18, 2);
        orphans = (limit, actor) -> limit;
        assertThat(schedule().orphansOnce()).as("bounded by its batch").isEqualTo(2);
    }

    @Test
    @DisplayName("a leg's failure is counted, logged by class and ends only that leg: the next still runs; nothing escapes to the executor")
    void aFailingLegEndsOnlyItself() {
        orphans = (limit, actor) -> {
            throw new IllegalStateException("the database is gone");
        };
        AccountingCloseSchedule schedule = schedule();
        assertThat(schedule.orphansOnce()).isZero();
        assertThat(registry.get(CloseRunMetrics.LEG_FAILED).tag("leg", "orphans").counter().count()).isEqualTo(1);
        assertThat(schedule.openOnce()).isEqualTo(1);
        assertThat(SecurityContext.current()).as("the system scope ended").isEmpty();
    }

    @Test
    @DisplayName("the lifecycle: start runs every leg on its own poll, stop ends them; off until started")
    void theLifecycle() throws Exception {
        CountDownLatch orphaned = new CountDownLatch(2);
        orphans = (limit, actor) -> {
            orphaned.countDown();
            return 0;
        };
        cadence = new AccountingCloseSchedule.Cadence(Duration.ofMillis(20), Duration.ofMillis(20), Duration.ofMillis(20), 18, 10);
        AccountingCloseSchedule schedule = schedule();
        assertThat(schedule.isRunning()).isFalse();
        schedule.openOnce();
        schedule.start();
        try {
            assertThat(schedule.isRunning()).isTrue();
            assertThat(orphaned.await(30, TimeUnit.SECONDS)).as("the orphan leg polled").isTrue();
            lockedLater();
            long waited = 0;
            while (store.runs().isEmpty() && waited++ < 3000) {
                java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L);
            }
            assertThat(store.runs()).as("the check leg started the run").isNotEmpty();
        } finally {
            schedule.stop();
        }
        assertThat(schedule.isRunning()).isFalse();
    }

    @Test
    @DisplayName("the cadence is refused at start-up unless every poll and batch is positive")
    void theCadenceIsJudged() {
        Duration second = Duration.ofSeconds(1);
        assertThatThrownBy(() -> new AccountingCloseSchedule.Cadence(Duration.ZERO, second, second, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountingCloseSchedule.Cadence(second, second, second, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountingCloseSchedule.Cadence(second, second, second, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ plumbing

    private void lockedMarch() {
        AccountingPeriodId march = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(march, YearMonth.of(2097, 3), CorrelationId.generate(IDS)));
        periods.markLocked(UOW, march, 1, ACCOUNTANT, Optional.empty());
    }

    /** A lock arriving while the schedule runs - the open leg may already have opened this month. */
    private void lockedLater() {
        AccountingPeriodStore.PeriodRow open = periods.month(YearMonth.of(2097, 4)).orElseThrow();
        periods.markLocked(UOW, open.id(), open.revision(), ACCOUNTANT, Optional.empty());
    }

    private AccountingCloseSchedule schedule() {
        return new AccountingCloseSchedule(accountingPeriods(), runs, checks(), orphans, transactions, metrics,
                AccountingFlowScope.NONE, IDS, cadence);
    }

    private AccountingPeriods accountingPeriods() {
        return new AccountingPeriods(periods, new InMemoryGlMappingStore(), new PostingCutoff() {
            @Override
            public Optional<LocalDate> closedThrough(Connection unitOfWork) {
                return Optional.empty();
            }

            @Override
            public boolean advance(Connection unitOfWork, Optional<LocalDate> from, LocalDate through, String reason) {
                return true;
            }

            @Override
            public boolean moveBack(Connection unitOfWork, LocalDate from, Optional<LocalDate> to, String reason) {
                return true;
            }
        }, LockGrace.standard(), (uow, record) -> { }, (uow, envelope, payload, mediaType) -> { }, IDS, CLOCK,
                (uow, period, actor, correlation) -> 0);
    }

    private CloseChecks checks() {
        CloseChecks.BreakChecks clean = (month, check, actor, correlation) -> new CheckOutcome(month, check,
                CheckOutcome.Result.PASSED, "posting-rules:v2", 0, 0, 0, 0, 0, List.of());
        FinalTrialBalance trialBalance = (id, actor, correlation) -> new FinalTrialBalance.Verdict("run-1", 1, 1, "abc", true, true,
                true, true, true);
        AccrualCompleteness caughtUp = new AccrualCompleteness() {
            @Override
            public List<Lagging> lagging(Connection unitOfWork) {
                return List.of();
            }

            @Override
            public List<Floored> flooredPast(Connection unitOfWork, LocalDate periodEnd) {
                return List.of();
            }
        };
        SourceProofs proofs = (snapshot, source) -> List.of(new SourceProofs.Reading(SourceProofs.Proof.PROJECTION, true, List.of()));
        ReconciliationDisclosure disclosure = (snapshot, month) -> new ReconciliationDisclosure.Disclosure(month.atEndOfMonth(),
                Instant.EPOCH, List.of(), 0, List.of(), 0, new TreeMap<>());
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
        ValuationRateSets sets = new ValuationRateSets(new InMemoryValuationRateSetStore().watching(periods), periods, reference,
                ValuationStaleness.standard(), (uow, record) -> { }, IDS, CLOCK);
        return new CloseChecks(clean, trialBalance, caughtUp, proofs, disclosure, sets, periods,
                new com.finapp.accounting.AccountingChecks.Snapshots() {
                    @Override
                    public <R> R read(Function<Connection, R> work) {
                        return work.apply(UOW);
                    }
                });
    }
}
