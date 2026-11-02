package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.testing.InMemoryAccountingBreakStore;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryCloseRunStore;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The close run's machine (`P14-TSK-011`; ADR-0114 decision 4; {@code PHASE_14_PLAN.md} sections 6, 8.3 rows 5-6, 13.5;
 * {@code INV-ACC-09}): one run per lock, one attempt per check under ten racing workers, a dead worker's check
 * reclaimed and its conclusion fenced, a live worker's lease renewed within its bound, a check whose workers keep dying
 * {@code FAILED}, a deferral no death, a conclusion after an unlock {@code PERIOD_MOVED}, the open breaks judged after
 * every raise, a re-run a new row with history frozen. Hermetic: the stores in memory, holding the database's ranks;
 * {@code CloseRunDatabaseTest} is the live half (owed, C5).
 */
@DisplayName("the close run: one attempt per check, reclaimed when its worker dies, never rewritten (P14-TSK-011)")
class CloseRunsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2097-04-05T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    private static final YearMonth MARCH = YearMonth.of(2097, 3);
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final Actor CONTROLLER = new Actor("controller-1", ActorType.CUSTOMER);
    private static final int WORKERS = 10;

    private final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
    private final InMemoryAccountingBreakStore breaks = new InMemoryAccountingBreakStore();
    private final InMemoryCloseRunStore store = new InMemoryCloseRunStore(periods);
    private final List<AuditRecord> audit = Collections.synchronizedList(new ArrayList<>());
    private final CloseRuns.Leases leases = CloseRuns.Leases.STANDARD;
    private final CloseRuns runs = new CloseRuns(store, periods, breaks, leases, (uow, record) -> audit.add(record), IDS, CLOCK);

    // ------------------------------------------------------------------ start

    @Test
    @DisplayName("a locked period gets one run of nine PENDING attempts, however many instances ask; an open one none")
    void aLockedPeriodGetsOneRun() throws Exception {
        AccountingPeriodId open = open();
        assertThat(runs.startMissing(UOW, Actor.SYSTEM)).as("not locked yet").isEmpty();
        periods.markLocked(UOW, open, 1, ACCOUNTANT, Optional.empty());

        List<CloseRunId> started = Collections.synchronizedList(new ArrayList<>());
        race(() -> started.addAll(runs.startMissing(UOW, Actor.SYSTEM)));

        assertThat(started).hasSize(1);
        assertThat(store.all(started.get(0))).hasSize(CloseCheckCode.values().length)
                .allSatisfy(attempt -> {
                    assertThat(attempt.status()).isEqualTo(CloseCheckStatus.PENDING);
                    assertThat(attempt.attempt()).isEqualTo(1);
                });
        assertThat(runs.startMissing(UOW, Actor.SYSTEM)).isEmpty();
    }

    // ------------------------------------------------------------------ the claim

    @Test
    @DisplayName("contention row 5: ten workers, one attempt per check - disjoint claims, no second row; the breaks' check"
            + " only after the raising checks concluded")
    void tenWorkersOneAttempt() throws Exception {
        CloseRunId run = started();

        Set<CloseCheckId> claimed = ConcurrentHashMap.newKeySet();
        List<CloseRuns.ClaimedCheck> held = Collections.synchronizedList(new ArrayList<>());
        race(() -> {
            Optional<CloseRuns.ClaimedCheck> next;
            while ((next = runs.claim(UOW, Actor.SYSTEM)).isPresent()) {
                assertThat(claimed.add(next.get().id())).as("an attempt is claimed once").isTrue();
                held.add(next.get());
            }
        });

        assertThat(held).as("eight: the breaks' check waits for the raising ones").hasSize(8)
                .extracting(CloseRuns.ClaimedCheck::check).doesNotContain(CloseCheckCode.OPEN_ACCOUNTING_BREAKS);
        assertThat(store.all(run)).as("a claim births no attempt").hasSize(9);
        assertThat(store.claimsMade()).isEqualTo(8);

        for (CloseRuns.ClaimedCheck check : held) {
            assertThat(runs.conclude(UOW, check, passed(check.check())).moved()).isTrue();
        }
        CloseRuns.ClaimedCheck breaksCheck = runs.claim(UOW, Actor.SYSTEM).orElseThrow();
        assertThat(breaksCheck.check()).isEqualTo(CloseCheckCode.OPEN_ACCOUNTING_BREAKS);
        assertThat(runs.claim(UOW, Actor.SYSTEM)).isEmpty();
    }

    @Test
    @DisplayName("contention row 6: a dead worker's check is reclaimed past its lease, one reclaim more, and the dead worker's"
            + " late conclusion moves nothing - the fence")
    void aDeadWorkersCheckIsReclaimed() {
        started();
        CloseRuns.ClaimedCheck dead = runs.claim(UOW, Actor.SYSTEM).orElseThrow();

        store.advance(leases.lease());
        CloseRuns.ClaimedCheck next = claimOf(dead.id());

        assertThat(next.holder()).isNotEqualTo(dead.holder());
        assertThat(next.reclaims()).isEqualTo(1);
        assertThat(next.attempt()).as("the same attempt: nothing of the dead worker's was recorded").isEqualTo(dead.attempt());
        assertThat(runs.conclude(UOW, dead, passed(dead.check())).moved()).as("the fence refuses the late one").isFalse();
        assertThat(runs.conclude(UOW, next, failed(next.check())).moved()).isTrue();
        assertThat(attempt(dead)).satisfies(row -> {
            assertThat(row.status()).isEqualTo(CloseCheckStatus.FAILED);
            assertThat(row.reclaims()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("before its lease lapses a held attempt is nobody else's")
    void aHeldAttemptIsNotClaimable() {
        started();
        CloseRuns.ClaimedCheck held = runs.claim(UOW, Actor.SYSTEM).orElseThrow();
        store.advance(leases.lease().minusSeconds(1));
        for (int i = 0; i < 7; i++) {
            assertThat(runs.claim(UOW, Actor.SYSTEM).orElseThrow().id()).isNotEqualTo(held.id());
        }
        assertThat(runs.claim(UOW, Actor.SYSTEM)).isEmpty();
    }

    @Test
    @DisplayName("the lease and a long run: a live worker's renewals hold the attempt past the lease; past the run's bound the"
            + " renewal is refused, the lease lapses and the slow worker is overtaken")
    void aLiveWorkerRenewsWithinItsBound() {
        started();
        CloseRuns.ClaimedCheck live = runs.claim(UOW, Actor.SYSTEM).orElseThrow();
        drainOthers(live);

        for (Duration lived = Duration.ZERO; lived.compareTo(leases.maxRun().minus(leases.lease())) < 0;
                lived = lived.plus(leases.renewEvery())) {
            store.advance(leases.renewEvery());
            assertThat(runs.renew(UOW, live)).isTrue();
            assertThat(runs.claim(UOW, Actor.SYSTEM)).as("a renewed lease holds").isEmpty();
        }

        store.advance(leases.maxRun());
        assertThat(runs.renew(UOW, live)).as("past the run's bound").isFalse();
        CloseRuns.ClaimedCheck next = runs.claim(UOW, Actor.SYSTEM).orElseThrow();
        assertThat(next.id()).isEqualTo(live.id());
        assertThat(next.reclaims()).isEqualTo(1);
        assertThat(runs.renew(UOW, live)).as("the overtaken worker no longer holds it").isFalse();
        assertThat(runs.conclude(UOW, live, passed(live.check())).moved()).isFalse();
    }

    @Test
    @DisplayName("a check whose workers keep dying comes back exhausted and concludes FAILED WORKERS_EXHAUSTED - never hung,"
            + " never passed")
    void aCheckWhoseWorkersKeepDyingFails() {
        started();
        CloseRuns.ClaimedCheck claimed = runs.claim(UOW, Actor.SYSTEM).orElseThrow();
        drainOthers(claimed);
        for (int reclaim = 1; reclaim <= leases.maxReclaims(); reclaim++) {
            store.advance(leases.lease());
            claimed = runs.claim(UOW, Actor.SYSTEM).orElseThrow();
            assertThat(claimed.reclaims()).isEqualTo(reclaim);
            assertThat(claimed.exhausted()).isFalse();
        }
        store.advance(leases.lease());
        claimed = runs.claim(UOW, Actor.SYSTEM).orElseThrow();
        assertThat(claimed.exhausted()).isTrue();

        CloseRuns.Concluded concluded = runs.concludeExhausted(UOW, claimed);

        assertThat(concluded.status()).isEqualTo(CloseCheckStatus.FAILED);
        assertThat(attempt(claimed).failures()).containsExactly(CloseRuns.WORKERS_EXHAUSTED);
    }

    @Test
    @DisplayName("a deferral gives the attempt back, no death: claimable after its wait, with no reclaim counted")
    void aDeferralIsNotADeath() {
        started();
        CloseRuns.ClaimedCheck claimed = runs.claim(UOW, Actor.SYSTEM).orElseThrow();
        drainOthers(claimed);

        assertThat(runs.defer(UOW, claimed)).isTrue();
        assertThat(runs.claim(UOW, Actor.SYSTEM)).as("not before its wait").isEmpty();
        store.advance(leases.deferral());

        CloseRuns.ClaimedCheck again = runs.claim(UOW, Actor.SYSTEM).orElseThrow();
        assertThat(again.id()).isEqualTo(claimed.id());
        assertThat(again.reclaims()).isZero();
        assertThat(runs.defer(UOW, claimed)).as("the giver holds it no more").isFalse();
    }

    // ------------------------------------------------------------------ conclude

    @Test
    @DisplayName("the unlock: a conclusion after it is FAILED PERIOD_MOVED whatever the judge found, the old run's rows stay and"
            + " are never claimed again, and a new lock is a new run")
    void aConclusionAfterAnUnlockIsPeriodMoved() {
        CloseRunId first = started();
        AccountingPeriodId period = store.runs().get(0).period();
        CloseRuns.ClaimedCheck held = runs.claim(UOW, Actor.SYSTEM).orElseThrow();

        unlock(period);

        CloseRuns.Concluded concluded = runs.conclude(UOW, held, passed(held.check()));
        assertThat(concluded.status()).isEqualTo(CloseCheckStatus.FAILED);
        assertThat(attempt(held).failures()).containsExactly(CloseRuns.PERIOD_MOVED);
        assertThat(runs.claim(UOW, Actor.SYSTEM)).as("an old lock's attempts are nobody's").isEmpty();

        periods.markLocked(UOW, period, 1, ACCOUNTANT, Optional.empty());
        List<CloseRunId> relocked = runs.startMissing(UOW, Actor.SYSTEM);
        assertThat(relocked).hasSize(1).doesNotContain(first);
        assertThat(store.all(first)).as("the old run's rows stay").hasSize(9);
    }

    @Test
    @DisplayName("a raising check's conclusion after the breaks' re-births their judgement - no stale PASSED stands")
    void aLaterRaiseBirthsANewJudgement() {
        CloseRunId run = started();
        concludeAll(run);
        assertThat(latest(run, CloseCheckCode.OPEN_ACCOUNTING_BREAKS).status()).isEqualTo(CloseCheckStatus.PASSED);

        // A person re-runs a raising check, and it raises a break the passed judgement never saw.
        runs.rerun(UOW, store.runs().get(0).period(), CloseCheckCode.RULE_CONFORMANCE, ACCOUNTANT, "a posting rule corrected",
                correlation());
        CloseRuns.ClaimedCheck rerun = runs.claim(UOW, Actor.SYSTEM).orElseThrow();
        assertThat(rerun.check()).isEqualTo(CloseCheckCode.RULE_CONFORMANCE);
        raiseOpenBreak();
        runs.conclude(UOW, rerun, failed(rerun.check()));

        CloseRunStore.AttemptRow again = latest(run, CloseCheckCode.OPEN_ACCOUNTING_BREAKS);
        assertThat(again.attempt()).isEqualTo(2);
        assertThat(again.status()).isEqualTo(CloseCheckStatus.PENDING);
        assertThat(runs.concludeOpenBreaks(UOW, runs.claim(UOW, Actor.SYSTEM).orElseThrow()).status())
                .as("judged after the raise").isEqualTo(CloseCheckStatus.FAILED);
    }

    @Test
    @DisplayName("M-1: a raise by an overtaken worker births a new judgement too: its conclusion moves nothing, and no stale PASSED stands beside"
            + " the break; an overtaken check that raises nothing births none")
    void aRaiseByAnOvertakenWorkerBirthsANewJudgement() {
        CloseRunId run = started();
        Map<CloseCheckCode, CloseRuns.ClaimedCheck> overtaken = new EnumMap<>(CloseCheckCode.class);
        Optional<CloseRuns.ClaimedCheck> next;
        while ((next = runs.claim(UOW, Actor.SYSTEM)).isPresent()) {
            overtaken.put(next.get().check(), next.get());
        }
        store.advance(leases.lease());
        concludeAll(run);
        assertThat(latest(run, CloseCheckCode.RULE_CONFORMANCE).status()).isEqualTo(CloseCheckStatus.PASSED);
        assertThat(latest(run, CloseCheckCode.OPEN_ACCOUNTING_BREAKS).status()).isEqualTo(CloseCheckStatus.PASSED);

        CloseRuns.ClaimedCheck quiet = overtaken.get(CloseCheckCode.PROJECTION_VERIFIED);
        assertThat(runs.conclude(UOW, quiet, passed(quiet.check())).moved()).isFalse();
        assertThat(latest(run, CloseCheckCode.OPEN_ACCOUNTING_BREAKS).attempt()).as("it raises nothing").isEqualTo(1);

        // The overtaken worker's judgement raised this before it reached its conclusion.
        raiseOpenBreak();
        CloseRuns.ClaimedCheck raising = overtaken.get(CloseCheckCode.RULE_CONFORMANCE);
        CloseRuns.Concluded refused = runs.conclude(UOW, raising, failed(raising.check()));

        assertThat(refused.moved()).as("the fence still refuses it").isFalse();
        assertThat(refused.tookFor()).isEmpty();
        assertThat(latest(run, CloseCheckCode.RULE_CONFORMANCE).status()).as("the reclaimer's conclusion stands").isEqualTo(CloseCheckStatus.PASSED);
        CloseRunStore.AttemptRow again = latest(run, CloseCheckCode.OPEN_ACCOUNTING_BREAKS);
        assertThat(again.attempt()).isEqualTo(2);
        assertThat(again.status()).isEqualTo(CloseCheckStatus.PENDING);
        assertThat(runs.concludeOpenBreaks(UOW, runs.claim(UOW, Actor.SYSTEM).orElseThrow()).status())
                .as("judged after the raise").isEqualTo(CloseCheckStatus.FAILED);
    }

    @Test
    @DisplayName("while a raising check is pending the breaks' judgement is simply not claimable; judged, OPEN fails it, only"
            + " ACKNOWLEDGED discloses it, none passes it")
    void theOpenBreaksVerdicts() {
        CloseRunId run = started();
        concludeAllBut(run, CloseCheckCode.OPEN_ACCOUNTING_BREAKS);
        CloseRuns.ClaimedCheck held = runs.claim(UOW, Actor.SYSTEM).orElseThrow();
        AccountingBreakId raised = raiseOpenBreak();

        assertThat(runs.concludeOpenBreaks(UOW, held).status()).isEqualTo(CloseCheckStatus.FAILED);
        assertThat(attempt(held).failures()).containsExactly(CloseRuns.OPEN_BREAKS);
        assertThat(attempt(held).evidence().orElseThrow()).contains("\"open\":\"1\"");

        breaks.proposeAcknowledgement(UOW, raised, ACCOUNTANT, "a fee line corrected");
        breaks.approveAcknowledgement(UOW, raised, CONTROLLER, "disclosed at close");
        runs.rerun(UOW, store.runs().get(0).period(), CloseCheckCode.OPEN_ACCOUNTING_BREAKS, ACCOUNTANT, "acknowledged",
                correlation());
        CloseRuns.ClaimedCheck acknowledged = runs.claim(UOW, Actor.SYSTEM).orElseThrow();
        assertThat(runs.concludeOpenBreaks(UOW, acknowledged).status()).isEqualTo(CloseCheckStatus.DISCLOSED);
        assertThat(attempt(acknowledged).evidence().orElseThrow()).contains("\"acknowledged\":\"1\"");
    }

    @Test
    @DisplayName("a verdict concludes its own check; only the breaks' check is judged in its conclusion")
    void theConclusionsAreTheirOwn() {
        started();
        CloseRuns.ClaimedCheck held = runs.claim(UOW, Actor.SYSTEM).orElseThrow();
        CloseCheckCode other = held.check() == CloseCheckCode.GL_EQUALS_LEDGER
                ? CloseCheckCode.RULE_CONFORMANCE : CloseCheckCode.GL_EQUALS_LEDGER;
        assertThatThrownBy(() -> runs.conclude(UOW, held, passed(other))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runs.concludeOpenBreaks(UOW, held)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the run's duration is observed once, when nothing of it is pending for the first time")
    void theDurationIsObservedOnce() {
        CloseRunId run = started();
        store.advance(Duration.ofMinutes(5));
        List<Optional<Duration>> tookFor = new ArrayList<>();
        Optional<CloseRuns.ClaimedCheck> next;
        while ((next = runs.claim(UOW, Actor.SYSTEM)).isPresent()) {
            CloseRuns.ClaimedCheck held = next.get();
            tookFor.add((held.check() == CloseCheckCode.OPEN_ACCOUNTING_BREAKS
                    ? runs.concludeOpenBreaks(UOW, held) : runs.conclude(UOW, held, passed(held.check()))).tookFor());
        }
        assertThat(tookFor).hasSize(9);
        assertThat(tookFor).filteredOn(Optional::isPresent).singleElement()
                .isEqualTo(Optional.of(Duration.ofMinutes(5)));
        assertThat(store.run(UOW, run).orElseThrow().checksConcludedAt()).isPresent();
    }

    // ------------------------------------------------------------------ re-run

    @Test
    @DisplayName("a re-run is a new attempt, audited and reasoned; the concluded one is frozen; a second while one is pending"
            + " is refused; a person's only")
    void aRerunIsANewAttempt() {
        CloseRunId run = started();
        concludeAll(run);
        AccountingPeriodId period = store.runs().get(0).period();
        CloseRunStore.AttemptRow first = latest(run, CloseCheckCode.ACCRUALS_COMPLETE);

        CloseRuns.Rerun rerun = runs.rerun(UOW, period, CloseCheckCode.ACCRUALS_COMPLETE, ACCOUNTANT, "accruals caught up",
                correlation());

        assertThat(rerun.attempt()).isEqualTo(2);
        assertThat(store.all(run)).as("the first attempt as it was").contains(first);
        assertThat(latest(run, CloseCheckCode.ACCRUALS_COMPLETE).status()).isEqualTo(CloseCheckStatus.PENDING);
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.operation().code()).isEqualTo("accounting.CloseCheckRerun");
            assertThat(record.reason()).contains("accruals caught up");
            assertThat(record.changeSummary().orElseThrow()).contains("period=2097-03", "check=ACCRUALS_COMPLETE", "attempt=2");
        });

        assertThatThrownBy(() -> runs.rerun(UOW, period, CloseCheckCode.ACCRUALS_COMPLETE, ACCOUNTANT, "again", correlation()))
                .isInstanceOf(CloseRuns.CheckPending.class);
        assertThatThrownBy(() -> runs.rerun(UOW, period, CloseCheckCode.PROJECTION_VERIFIED, ACCOUNTANT, null, correlation()))
                .isInstanceOf(AccountingReasons.ReasonRequired.class);
        assertThatThrownBy(() -> runs.rerun(UOW, period, CloseCheckCode.PROJECTION_VERIFIED, Actor.SYSTEM, "mine", correlation()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a re-run needs its period LOCKED, a run under that lock and a known period")
    void aRerunNeedsALockedRun() {
        AccountingPeriodId period = open();
        assertThatThrownBy(() -> runs.rerun(UOW, period, CloseCheckCode.RULE_CONFORMANCE, ACCOUNTANT, "why", correlation()))
                .isInstanceOf(AccountingPeriods.PeriodStale.class);
        periods.markLocked(UOW, period, 1, ACCOUNTANT, Optional.empty());
        assertThatThrownBy(() -> runs.rerun(UOW, period, CloseCheckCode.RULE_CONFORMANCE, ACCOUNTANT, "why", correlation()))
                .isInstanceOf(CloseRuns.RunNotStarted.class);
        assertThatThrownBy(() -> runs.rerun(UOW, AccountingPeriodId.next(IDS), CloseCheckCode.RULE_CONFORMANCE, ACCOUNTANT,
                "why", correlation())).isInstanceOf(AccountingPeriods.PeriodNotFound.class);
    }

    // ------------------------------------------------------------------ readiness

    @Test
    @DisplayName("readiness: every latest attempt PASSED or DISCLOSED and no break open, read live - a stale PASSED cannot make a"
            + " run ready; an unlocked period's run is never ready")
    void readinessIsLive() {
        CloseRunId run = started();
        AccountingPeriodId period = store.runs().get(0).period();
        assertThat(runs.readiness(UOW, period).complete()).isFalse();
        assertThat(runs.readiness(UOW, period).notReady()).hasSize(9);

        concludeAll(run);
        CloseRuns.Readiness ready = runs.readiness(UOW, period);
        assertThat(ready.complete()).isTrue();
        assertThat(ready.notReady()).isEmpty();
        assertThat(ready.run()).contains(run);

        raiseOpenBreak();
        CloseRuns.Readiness stale = runs.readiness(UOW, period);
        assertThat(stale.complete()).as("the break is open, whatever the attempt said").isFalse();
        assertThat(stale.openBreaks()).isEqualTo(1);

        unlock(period);
        assertThat(runs.readiness(UOW, period).complete()).isFalse();
        assertThat(runs.readiness(UOW, period).run()).isEmpty();
    }

    @Test
    @DisplayName("the view holds every check's latest and its newest attempts, newest first, and names the attempts in all")
    void theViewHoldsTheHistory() {
        CloseRunId run = started();
        concludeAll(run);
        AccountingPeriodId period = store.runs().get(0).period();
        runs.rerun(UOW, period, CloseCheckCode.PROJECTION_VERIFIED, ACCOUNTANT, "a repair", correlation());

        CloseRuns.RunView view = runs.view(UOW, period).orElseThrow();

        assertThat(view.current()).isTrue();
        assertThat(view.complete()).as("a pending attempt is not ready").isFalse();
        assertThat(view.checks()).hasSize(9);
        CloseRuns.CheckView projection = view.checks().stream()
                .filter(check -> check.check() == CloseCheckCode.PROJECTION_VERIFIED).findFirst().orElseThrow();
        assertThat(projection.attempts()).isEqualTo(2);
        assertThat(projection.latest().attempt()).isEqualTo(2);
        assertThat(projection.history()).extracting(CloseRunStore.AttemptRow::attempt).containsExactly(2, 1);
        assertThat(runs.view(UOW, open("2097-04"))).isEmpty();
    }

    @Test
    @DisplayName("the configuration is refused unless a lease holds three renewals and the run's bound a lease")
    void theLeasesAreJudged() {
        Duration minute = Duration.ofMinutes(1);
        assertThatThrownBy(() -> new CloseRuns.Leases(minute, Duration.ofSeconds(21), Duration.ofHours(1), 3, minute))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CloseRuns.Leases(minute, Duration.ofSeconds(20), Duration.ofSeconds(59), 3, minute))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CloseRuns.Leases(minute, Duration.ofSeconds(20), Duration.ofHours(1), -1, minute))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CloseRuns.Leases(minute, Duration.ofSeconds(20), minute, 0, minute).maxReclaims()).isZero();
    }

    @Test
    @DisplayName("a verdict that could lie refuses to exist; DISCLOSED only where the check admits it")
    void aVerdictThatCouldLieRefuses() {
        CanonicalJson.Obj evidence = CanonicalJson.object().put("period", "2097-03");
        assertThatThrownBy(() -> CheckVerdict.failed(CloseCheckCode.RULE_CONFORMANCE, "posting-rules:v2", List.of(), evidence))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckVerdict(CloseCheckCode.RULE_CONFORMANCE, CloseCheckStatus.PASSED, "posting-rules:v2",
                List.of("DISCREPANCIES"), "{}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckVerdict(CloseCheckCode.RULE_CONFORMANCE, CloseCheckStatus.PENDING, "posting-rules:v2",
                List.of(), "{}")).isInstanceOf(IllegalArgumentException.class);
        for (CloseCheckCode check : CloseCheckCode.values()) {
            if (!check.disclosable()) {
                assertThatThrownBy(() -> CheckVerdict.disclosed(check, "posting-rules:v2", CanonicalJson.object().put("a", "b")))
                        .as(check.name()).isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThatThrownBy(() -> CheckVerdict.passed(CloseCheckCode.RULE_CONFORMANCE, "No Version", evidence))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CloseCheckStatus.PASSED.next()).isEmpty();
        assertThat(CloseCheckStatus.PENDING.next()).containsExactlyInAnyOrder(CloseCheckStatus.PASSED, CloseCheckStatus.FAILED,
                CloseCheckStatus.DISCLOSED);
        assertThat(CloseCheckStatus.FAILED.readyForClose()).isFalse();
    }

    // ------------------------------------------------------------------ plumbing

    private AccountingPeriodId open() {
        return open("2097-03");
    }

    private AccountingPeriodId open(String month) {
        AccountingPeriodId id = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(id, YearMonth.parse(month), correlation()));
        return id;
    }

    private CloseRunId started() {
        AccountingPeriodId period = open();
        periods.markLocked(UOW, period, 1, ACCOUNTANT, Optional.empty());
        return runs.startMissing(UOW, Actor.SYSTEM).get(0);
    }

    private void unlock(AccountingPeriodId period) {
        periods.proposeUnlock(UOW, period, 1, ACCOUNTANT, "a correction is owed");
        periods.approveUnlock(UOW, period, 1, CONTROLLER, "agreed");
    }

    private AccountingBreakId raiseOpenBreak() {
        AccountingBreakId id = AccountingBreakId.next(IDS);
        breaks.insertIfAbsent(UOW, new AccountingBreakStore.NewBreak(id, MARCH, new BreakFinding(CloseCheckCode.RULE_CONFORMANCE,
                BreakType.SHAPE_MISMATCH, "shape:" + id.value(), "entry planted", "posting-rules:v2"), Actor.SYSTEM,
                correlation()));
        return id;
    }

    /** The next claim, which must be {@code id} once the others are out of the way. */
    private CloseRuns.ClaimedCheck claimOf(CloseCheckId id) {
        Optional<CloseRuns.ClaimedCheck> next;
        while ((next = runs.claim(UOW, Actor.SYSTEM)).isPresent()) {
            if (next.get().id().equals(id)) {
                return next.get();
            }
        }
        throw new AssertionError("the attempt was not claimable");
    }

    /** Every other claimable attempt held by a worker that outlives the test's window. */
    private void drainOthers(CloseRuns.ClaimedCheck kept) {
        Optional<CloseRuns.ClaimedCheck> next;
        while ((next = runs.claim(UOW, Actor.SYSTEM)).isPresent()) {
            assertThat(next.get().id()).isNotEqualTo(kept.id());
            runs.conclude(UOW, next.get(), passed(next.get().check()));
        }
    }

    private void concludeAll(CloseRunId run) {
        concludeAllBut(run, null);
    }

    private void concludeAllBut(CloseRunId run, CloseCheckCode kept) {
        Optional<CloseRuns.ClaimedCheck> next;
        while (true) {
            next = store.all(run).stream().anyMatch(attempt -> attempt.check() == kept && kept != null
                    && attempt.status() == CloseCheckStatus.PENDING)
                    && store.all(run).stream().filter(attempt -> attempt.status() == CloseCheckStatus.PENDING)
                            .allMatch(attempt -> attempt.check() == kept)
                    ? Optional.empty()
                    : runs.claim(UOW, Actor.SYSTEM);
            if (next.isEmpty()) {
                return;
            }
            CloseRuns.ClaimedCheck held = next.get();
            if (held.check() == CloseCheckCode.OPEN_ACCOUNTING_BREAKS) {
                runs.concludeOpenBreaks(UOW, held);
            } else {
                runs.conclude(UOW, held, passed(held.check()));
            }
        }
    }

    private CloseRunStore.AttemptRow attempt(CloseRuns.ClaimedCheck claimed) {
        return store.all(claimed.run()).stream().filter(row -> row.id().equals(claimed.id())).findFirst().orElseThrow();
    }

    private CloseRunStore.AttemptRow latest(CloseRunId run, CloseCheckCode check) {
        return store.latestAttempt(UOW, run, check).orElseThrow();
    }

    private static CheckVerdict passed(CloseCheckCode check) {
        return CheckVerdict.passed(check, "source-proofs:v1", CanonicalJson.object().put("read", "whole"));
    }

    private static CheckVerdict failed(CloseCheckCode check) {
        return CheckVerdict.failed(check, "source-proofs:v1", List.of("DISCREPANCIES"), CanonicalJson.object().put("read", "whole"));
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    /** {@code work} from {@value #WORKERS} threads at once. */
    private static void race(ThrowingRunnable work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> racing = new ArrayList<>();
        try {
            for (int i = 0; i < WORKERS; i++) {
                racing.add(pool.submit(() -> {
                    start.await();
                    work.run();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> outcome : racing) {
                outcome.get(1, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
