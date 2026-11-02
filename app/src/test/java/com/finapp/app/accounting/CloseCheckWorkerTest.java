package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.AccrualCompleteness;
import com.finapp.accounting.CheckOutcome;
import com.finapp.accounting.CheckVerdict;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseCheckStatus;
import com.finapp.accounting.CloseChecks;
import com.finapp.accounting.CloseRunId;
import com.finapp.accounting.CloseRunStore;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.FinalTrialBalance;
import com.finapp.accounting.ReconciliationDisclosure;
import com.finapp.accounting.SourceProofs;
import com.finapp.accounting.ValuationRateSets;
import com.finapp.accounting.ValuationRates;
import com.finapp.accounting.ValuationStaleness;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.testing.InMemoryAccountingBreakStore;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryCloseRunStore;
import com.finapp.accounting.testing.InMemoryValuationRateSetStore;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
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
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One worker's T-check (`P14-TSK-011`; `P14-TSK-010`'s review, M-1; {@code PHASE_14_PLAN.md} section 8.1): the
 * judgement outside any transaction, the heartbeat renewing the lease for as long as it runs, the conclusion fenced -
 * so a long run keeps its check, a killed worker's check resumes on the next, a failed conclusion passes nothing.
 * Hermetic: stores in memory, the heartbeat driven by the test.
 */
@DisplayName("the close check worker: a long run keeps its lease, a kill resumes (P14-TSK-011)")
class CloseCheckWorkerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2097-04-05T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    private static final YearMonth MARCH = YearMonth.of(2097, 3);
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final CloseRuns.Leases LEASES = CloseRuns.Leases.STANDARD;

    private final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
    private final InMemoryCloseRunStore store = new InMemoryCloseRunStore(periods);
    private final InMemoryAccountingBreakStore breaks = new InMemoryAccountingBreakStore();
    private Runnable duringConclusion = () -> { };
    private Runnable duringRenewal = () -> { };
    /** The store, its conclusion and its renewal failing on command - an instance dying, a database flapping. */
    private final CloseRunStore failable = (CloseRunStore) Proxy.newProxyInstance(CloseRunStore.class.getClassLoader(),
            new Class<?>[] {CloseRunStore.class}, (proxy, method, args) -> {
                if (method.getName().equals("conclude")) {
                    duringConclusion.run();
                }
                if (method.getName().equals("renew")) {
                    duringRenewal.run();
                }
                try {
                    return method.invoke(store, args);
                } catch (java.lang.reflect.InvocationTargetException thrown) {
                    throw thrown.getCause();
                }
            });
    private final CloseRuns runs = new CloseRuns(failable, periods, breaks, LEASES, (uow, record) -> { }, IDS, CLOCK);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CloseRunMetrics metrics = new CloseRunMetrics(registry);

    private FinalTrialBalance trialBalance =
            (id, actor, correlation) -> new FinalTrialBalance.Verdict("run-1", 1, 1, "abc", true, true, true, true, true);
    private CloseChecks.BreakChecks breakChecks = (month, check, actor, correlation) -> new CheckOutcome(month, check,
            CheckOutcome.Result.PASSED, "posting-rules:v2", 0, 0, 0, 0, 0, List.of());

    private final List<BooleanSupplier> beats = new ArrayList<>();
    private int heartbeatsStopped;
    private CloseCheckWorker.Heartbeats heartbeats = (every, beat) -> {
        assertThat(every).isEqualTo(LEASES.renewEvery());
        beats.add(beat);
        return () -> heartbeatsStopped++;
    };
    private CloseRunId run;

    @BeforeEach
    void marchIsLockedWithARun() {
        AccountingPeriodId march = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(march, MARCH, CorrelationId.generate(IDS)));
        periods.markLocked(UOW, march, 1, ACCOUNTANT, Optional.empty());
        run = runs.startMissing(UOW, Actor.SYSTEM).get(0);
    }

    @Test
    @DisplayName("nine checks, one attempt each: every one concluded with its evidence and the versions it read, counted once; the run's"
            + " duration observed once; the next claim empty")
    void everyCheckRecordedWithEvidence() {
        CloseCheckWorker worker = worker();
        int taken = 0;
        while (worker.workOne(Actor.SYSTEM)) {
            taken++;
        }
        assertThat(taken).isEqualTo(9);
        List<CloseRunStore.AttemptRow> attempts = store.all(run);
        assertThat(attempts).hasSize(9).allSatisfy(attempt -> {
            assertThat(attempt.status().concluded()).as(attempt.check().name()).isTrue();
            assertThat(attempt.judgedBy()).as(attempt.check().name()).isPresent();
            assertThat(attempt.evidence()).as(attempt.check().name()).isPresent();
            assertThat(attempt.concludedAt()).as(attempt.check().name()).isPresent();
        });
        assertThat(attempts).extracting(CloseRunStore.AttemptRow::check).containsExactlyInAnyOrder(CloseCheckCode.values());
        assertThat(registry.get(CloseRunMetrics.DURATION).timer().count()).isEqualTo(1);
        assertThat(heartbeatsStopped).as("every heartbeat stopped with its check").isEqualTo(9);
    }

    @Test
    @DisplayName("the lease and a long run (M-1): a live worker's heartbeat holds its check across many leases - no other worker can"
            + " take it - and its conclusion stands")
    void aLiveWorkerKeepsItsLeaseWhileItJudges() {
        onlyRuleConformanceLeft();
        List<Optional<CloseRuns.ClaimedCheck>> stolen = new ArrayList<>();
        breakChecks = (month, check, actor, correlation) -> {
            Duration lived = Duration.ZERO;
            while (lived.compareTo(LEASES.lease().multipliedBy(5)) < 0) {
                store.advance(LEASES.renewEvery());
                lived = lived.plus(LEASES.renewEvery());
                assertThat(beats.get(beats.size() - 1).getAsBoolean()).as("the renewal").isTrue();
                stolen.add(runs.claim(UOW, Actor.SYSTEM));
            }
            return new CheckOutcome(month, check, CheckOutcome.Result.PASSED, "posting-rules:v2", 0, 0, 0, 0, 0, List.of());
        };

        assertThat(worker().workOne(Actor.SYSTEM)).isTrue();

        assertThat(stolen).isNotEmpty().allSatisfy(claim -> assertThat(claim).as("another worker's claim").isEmpty());
        assertThat(latest(CloseCheckCode.RULE_CONFORMANCE).status()).isEqualTo(CloseCheckStatus.PASSED);
        assertThat(latest(CloseCheckCode.RULE_CONFORMANCE).reclaims()).isZero();
    }

    @Test
    @DisplayName("past the run's bound the renewal answers false and the heartbeat ends: the next worker reclaims, the slow worker's"
            + " conclusion is refused and counted overtaken - never a second verdict")
    void aRunPastItsBoundIsOvertaken() {
        onlyRuleConformanceLeft();
        breakChecks = (month, check, actor, correlation) -> {
            store.advance(LEASES.maxRun());
            assertThat(beats.get(beats.size() - 1).getAsBoolean()).as("past the bound").isFalse();
            CloseRuns.ClaimedCheck next = runs.claim(UOW, Actor.SYSTEM).orElseThrow();
            assertThat(next.reclaims()).isEqualTo(1);
            runs.conclude(UOW, next, CheckVerdict.failed(check, "posting-rules:v2", List.of("DISCREPANCIES"),
                    CanonicalJson.object().put("by", "the next worker")));
            return new CheckOutcome(month, check, CheckOutcome.Result.PASSED, "posting-rules:v2", 0, 0, 0, 0, 0, List.of());
        };

        assertThat(worker().workOne(Actor.SYSTEM)).isTrue();

        CloseRunStore.AttemptRow attempt = latest(CloseCheckCode.RULE_CONFORMANCE);
        assertThat(attempt.status()).as("the slow worker's PASSED did not land").isEqualTo(CloseCheckStatus.FAILED);
        assertThat(counted(CloseCheckCode.RULE_CONFORMANCE, "overtaken")).isEqualTo(1);
        assertThat(counted(CloseCheckCode.RULE_CONFORMANCE, "passed")).isZero();
    }

    @Test
    @DisplayName("a report run another worker generates defers the check: nothing concluded, no thread held, no reclaim counted; claimable"
            + " after its wait, then concluded")
    void aRunInProgressDefers() {
        trialBalance = (id, actor, correlation) -> new FinalTrialBalance.InProgress();
        assertThat(worker().workOne(Actor.SYSTEM)).isTrue();
        CloseRunStore.AttemptRow deferred = latest(CloseCheckCode.LEDGER_TRIAL_BALANCE);
        assertThat(deferred.status()).isEqualTo(CloseCheckStatus.PENDING);
        assertThat(deferred.reclaims()).isZero();
        assertThat(counted(CloseCheckCode.LEDGER_TRIAL_BALANCE, "deferred")).isEqualTo(1);
        assertThat(heartbeatsStopped).isEqualTo(1);
    }

    @Test
    @DisplayName("resumable after a kill: a worker that dies at its conclusion records nothing, the leg is counted, the lease lapses, the next"
            + " worker takes the same attempt and concludes it")
    void aKilledWorkerResumes() {
        onlyRuleConformanceLeft();
        duringConclusion = () -> {
            throw new IllegalStateException("the instance died");
        };
        assertThat(worker().workOne(Actor.SYSTEM)).as("no exception escapes the leg").isTrue();
        assertThat(latest(CloseCheckCode.RULE_CONFORMANCE).status()).isEqualTo(CloseCheckStatus.PENDING);
        assertThat(registry.get(CloseRunMetrics.LEG_FAILED).tag("leg", "checks").counter().count()).isEqualTo(1);
        assertThat(heartbeatsStopped).as("the heartbeat stopped too").isEqualTo(1);

        duringConclusion = () -> { };
        assertThat(worker().workOne(Actor.SYSTEM)).as("before the lease lapses nothing is claimable").isFalse();
        store.advance(LEASES.lease());
        assertThat(worker().workOne(Actor.SYSTEM)).isTrue();

        CloseRunStore.AttemptRow attempt = latest(CloseCheckCode.RULE_CONFORMANCE);
        assertThat(attempt.status()).isEqualTo(CloseCheckStatus.PASSED);
        assertThat(attempt.attempt()).as("the same attempt, no second row").isEqualTo(1);
        assertThat(attempt.reclaims()).isEqualTo(1);
    }

    @Test
    @DisplayName("a check whose workers kept dying concludes FAILED WORKERS_EXHAUSTED, unjudged - the judge is not asked again")
    void anExhaustedCheckFailsUnjudged() {
        onlyRuleConformanceLeft();
        breakChecks = (month, check, actor, correlation) -> {
            throw new AssertionError("an exhausted check is not judged");
        };
        for (int claim = 0; claim <= LEASES.maxReclaims(); claim++) {
            assertThat(runs.claim(UOW, Actor.SYSTEM)).isPresent();
            store.advance(LEASES.lease());
        }
        assertThat(worker().workOne(Actor.SYSTEM)).isTrue();
        CloseRunStore.AttemptRow attempt = latest(CloseCheckCode.RULE_CONFORMANCE);
        assertThat(attempt.status()).isEqualTo(CloseCheckStatus.FAILED);
        assertThat(attempt.failures()).containsExactly(CloseRuns.WORKERS_EXHAUSTED);
    }

    @Test
    @DisplayName("a renewal that fails beats again: a lost transaction costs at most the lease, and the fence makes that harmless")
    void aFailedRenewalBeatsAgain() {
        onlyRuleConformanceLeft();
        breakChecks = (month, check, actor, correlation) -> {
            duringRenewal = () -> {
                throw new IllegalStateException("the database flapped");
            };
            assertThat(beats.get(beats.size() - 1).getAsBoolean()).isTrue();
            duringRenewal = () -> { };
            return new CheckOutcome(month, check, CheckOutcome.Result.PASSED, "posting-rules:v2", 0, 0, 0, 0, 0, List.of());
        };
        assertThat(worker().workOne(Actor.SYSTEM)).isTrue();
        assertThat(latest(CloseCheckCode.RULE_CONFORMANCE).status()).isEqualTo(CloseCheckStatus.PASSED);
    }

    // ------------------------------------------------------------------ plumbing

    private double counted(CloseCheckCode check, String outcome) {
        return registry.get(CloseRunMetrics.CHECK).tag("check", check.name()).tag("outcome", outcome).counter().count();
    }

    private CloseRunStore.AttemptRow latest(CloseCheckCode check) {
        return store.latestAttempt(UOW, run, check).orElseThrow();
    }

    /** Every check but {@code RULE_CONFORMANCE} (and the breaks' check, which waits for it) concluded by another worker. */
    private void onlyRuleConformanceLeft() {
        List<CloseRuns.ClaimedCheck> claimed = new ArrayList<>();
        Optional<CloseRuns.ClaimedCheck> next;
        while ((next = runs.claim(UOW, Actor.SYSTEM)).isPresent()) {
            claimed.add(next.get());
        }
        for (CloseRuns.ClaimedCheck check : claimed) {
            if (check.check() == CloseCheckCode.RULE_CONFORMANCE) {
                runs.defer(UOW, check);
            } else {
                runs.conclude(UOW, check, CheckVerdict.passed(check.check(), "source-proofs:v1",
                        CanonicalJson.object().put("read", "whole")));
            }
        }
        store.advance(LEASES.deferral());
    }

    private CloseCheckWorker worker() {
        return new CloseCheckWorker(runs, checks(), transactions, metrics, AccountingFlowScope.NONE, heartbeats);
    }

    private final AccountingTransactions transactions = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            return work.apply(UOW);
        }
    };

    private CloseChecks checks() {
        CloseChecks.BreakChecks raising = (month, check, actor, correlation) -> breakChecks.run(month, check, actor, correlation);
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
        SourceProofs clean = (snapshot, source) -> List.of(new SourceProofs.Reading(SourceProofs.Proof.PROJECTION, true, List.of()));
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
        return new CloseChecks(raising, (id, actor, correlation) -> trialBalance.read(id, actor, correlation), caughtUp, clean,
                disclosure, sets, periods, new com.finapp.accounting.AccountingChecks.Snapshots() {
                    @Override
                    public <R> R read(Function<Connection, R> work) {
                        return work.apply(UOW);
                    }
                });
    }
}
