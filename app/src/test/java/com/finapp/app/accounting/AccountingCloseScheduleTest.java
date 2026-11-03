package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.AccrualCompleteness;
import com.finapp.accounting.CheckOutcome;
import com.finapp.accounting.CloseCheckCode;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code AccountingCloseSchedule}'s three legs (`P14-TSK-011`; {@code PHASE_14_PLAN.md} section 8.4): the months
 * opened, every lock's run started and its checks taken, the orphans completed <strong>one run per call</strong>
 * (`P14-TSK-008`'s review, M-6); a leg's failure counted and ending only itself, each leg as the platform, the
 * lifecycle running every leg. Hermetic; {@code CloseRunDatabaseTest} is the live half (owed, C5).
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
    private CloseRuns closeRuns = runs;
    private FinalTrialBalance trialBalance = (id, actor, correlation) -> new FinalTrialBalance.Verdict("run-1", 1, 1, "abc", true,
            true, true, true, true);
    private final AtomicInteger renewals = new AtomicInteger();
    /** The store, its renewals counted - the heartbeat's own thread. */
    private final CloseRunStore counted = (CloseRunStore) Proxy.newProxyInstance(CloseRunStore.class.getClassLoader(),
            new Class<?>[] {CloseRunStore.class}, (proxy, method, args) -> {
                try {
                    Object answer = method.invoke(store, args);
                    if (method.getName().equals("renew")) {
                        renewals.incrementAndGet();
                    }
                    return answer;
                } catch (java.lang.reflect.InvocationTargetException thrown) {
                    throw thrown.getCause();
                }
            });

    @Test
    @DisplayName("the heartbeat, on the schedule's own executor: it renews while a check is judged and stops with it")
    void theHeartbeatRunsWhileACheckIsJudged() throws Exception {
        lockedMarch();
        closeRuns = new CloseRuns(counted, periods, new InMemoryAccountingBreakStore(), new CloseRuns.Leases(Duration.ofMinutes(3),
                Duration.ofMillis(10), Duration.ofHours(2), 3, Duration.ofSeconds(30)), (uow, record) -> { }, IDS, CLOCK);
        int[] seenWhileJudging = new int[1];
        FinalTrialBalance clean = trialBalance;
        trialBalance = (id, actor, correlation) -> {
            awaitRenewals(3);
            seenWhileJudging[0] = renewals.get();
            return clean.read(id, actor, correlation);
        };
        cadence = new AccountingCloseSchedule.Cadence(Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1), 18, 10);
        AccountingCloseSchedule schedule = schedule();
        schedule.start();
        try {
            awaitEveryCheckConcluded();
            assertThat(seenWhileJudging[0]).as("renewed while the judge held its check").isGreaterThanOrEqualTo(3);
            // The leg stops the beat just after its last conclusion: past that, the count stays.
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
            int after = renewals.get();
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
            assertThat(renewals.get()).as("no heartbeat outlives its check").isEqualTo(after);
        } finally {
            schedule.stop();
        }
    }

    @Test
    @DisplayName("past the run's bound the renewal is refused and the heartbeat ends, the judge still running")
    void theHeartbeatEndsAtTheRunsBound() throws Exception {
        lockedMarch();
        Duration maxRun = Duration.ofHours(2);
        closeRuns = new CloseRuns(counted, periods, new InMemoryAccountingBreakStore(), new CloseRuns.Leases(Duration.ofMinutes(3),
                Duration.ofMillis(10), maxRun, 3, Duration.ofSeconds(30)), (uow, record) -> { }, IDS, CLOCK);
        int[] stoppedAt = new int[2];
        CountDownLatch judged = new CountDownLatch(1);
        FinalTrialBalance clean = trialBalance;
        trialBalance = (id, actor, correlation) -> {
            awaitRenewals(1);
            store.advance(maxRun.plusMinutes(1));
            // The refused beat may be in flight: the count settles after it, and stays.
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
            stoppedAt[0] = renewals.get();
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
            stoppedAt[1] = renewals.get();
            judged.countDown();
            return clean.read(id, actor, correlation);
        };
        cadence = new AccountingCloseSchedule.Cadence(Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1), 1, 10);
        AccountingCloseSchedule schedule = schedule();
        schedule.start();
        try {
            assertThat(judged.await(30, TimeUnit.SECONDS)).as("the check leg judged, at start").isTrue();
            assertThat(stoppedAt[1]).as("the heartbeat ended at the bound").isEqualTo(stoppedAt[0]);
        } finally {
            schedule.stop();
        }
    }

    @Test
    @DisplayName("M-7: start runs every leg at once, not a poll later - an hour's open leg would never run on a fleet restarted within the hour")
    void everyLegRunsAtStart() throws Exception {
        lockedMarch();
        CountDownLatch orphaned = new CountDownLatch(1);
        orphans = (limit, actor) -> {
            orphaned.countDown();
            return 0;
        };
        cadence = new AccountingCloseSchedule.Cadence(Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1), 18, 10);
        AccountingCloseSchedule schedule = schedule();
        schedule.start();
        try {
            assertThat(orphaned.await(30, TimeUnit.SECONDS)).as("the orphan leg").isTrue();
            awaitEveryCheckConcluded();
            long waited = 0;
            while (periods.month(YearMonth.of(2097, 4)).isEmpty() && waited++ < 3000) {
                java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
            }
            assertThat(periods.month(YearMonth.of(2097, 4))).as("the open leg").isPresent();
        } finally {
            schedule.stop();
        }
    }

    /** The check leg's first pass, on the schedule's own executor: one run, all nine judged - eight concluded, {@code VALUATION_SET_APPROVED} waiting on a set this fixture never approves (`P14-TSK-012`, M-6). */
    private void awaitEveryCheckConcluded() {
        long waited = 0;
        while (!everyCheckConcluded() && waited++ < 6000) {
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        assertThat(everyCheckConcluded()).as("the check leg ran at start and concluded every check").isTrue();
    }

    private boolean everyCheckConcluded() {
        return !store.runs().isEmpty() && store.all(store.runs().get(0).id()).size() == 9 && store.all(store.runs().get(0).id()).stream()
                .filter(attempt -> attempt.check() != CloseCheckCode.VALUATION_SET_APPROVED)
                .noneMatch(attempt -> attempt.status() == CloseCheckStatus.PENDING);
    }

    private void awaitRenewals(int atLeast) {
        long waited = 0;
        while (renewals.get() < atLeast && waited++ < 3000) {
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        assertThat(renewals.get()).as("the heartbeat beat").isGreaterThanOrEqualTo(atLeast);
    }

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
        assertThat(store.all(store.runs().get(0).id())).allSatisfy(attempt -> {
            if (attempt.check() == CloseCheckCode.VALUATION_SET_APPROVED) {
                assertThat(attempt.status()).as("it waits for an approved set, deferred, never FAILED (P14-TSK-012, M-6)").isEqualTo(CloseCheckStatus.PENDING);
            } else {
                assertThat(attempt.status()).as(attempt.check().name()).isNotEqualTo(CloseCheckStatus.PENDING);
            }
        });
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
        return new AccountingCloseSchedule(accountingPeriods(), closeRuns, checks(), orphans, transactions, metrics,
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
                CheckOutcome.Result.PASSED, "posting-rules:v2", 0, 0, 0, 0, 0, List.of(), List.of());
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
        return new CloseChecks(clean, (id, actor, correlation) -> trialBalance.read(id, actor, correlation), caughtUp, proofs, disclosure, (snapshot, month) -> {
            java.util.UUID ranSource = java.util.UUID.nameUUIDFromBytes(new byte[] {7});
            return new com.finapp.accounting.ReconciliationRunCoverage.Coverage(month, List.of(new com.finapp.accounting.ReconciliationRunCoverage.Line(ranSource, "BATCH", "COMPLETED", month.atDay(1), false, 1)),
                    List.of(new com.finapp.accounting.ReconciliationRunCoverage.Source(ranSource, "simulated-psp.settlement", com.finapp.accounting.ReconciliationRunCoverage.SourceState.ACTIVE)));
        }, sets, periods,
                new com.finapp.accounting.AccountingChecks.Snapshots() {
                    @Override
                    public <R> R read(Function<Connection, R> work) {
                        return work.apply(UOW);
                    }
                });
    }
}
