package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.InMemoryReportRunStore;
import com.finapp.accounting.testing.TestReportDefinition;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The report run's machine (`P14-TSK-008`; ADR-0115 decisions 2-3; {@code PHASE_14_PLAN.md} sections 6, 8.1 T-report,
 * 8.3 row 7, 8.4 (c); {@code INV-ACC-04}): one run per question however many claim it, the basis from the period, one
 * completion edge with one event and one audit record, a failure allowing a new revision, a dead generator's lease
 * reclaimed by one attempt more and its late completion refused, the attempts bounded, and the unlock superseding the
 * period's FINAL runs - a FINAL run claimed before an unlock failing {@code PERIOD_MOVED}. Hermetic: the stores are in
 * memory, holding V004's ranks; the database's own proof is {@code ReportRunDatabaseTest} (owed, C5).
 */
@DisplayName("the report run's machine (P14-TSK-008)")
class ReportRunsTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException("the in-memory stores never touch the connection");
            });
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final Actor CONTROLLER = new Actor("controller-1", ActorType.CUSTOMER);

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-10T09:00:00Z"));
    private final InMemoryAccountingPeriodStore periodStore = new InMemoryAccountingPeriodStore(clock);
    private final InMemoryGlMappingStore mappings = new InMemoryGlMappingStore();
    private final InMemoryReportRunStore store = new InMemoryReportRunStore(clock, periodStore);
    private final List<AuditRecord> audit = Collections.synchronizedList(new ArrayList<>());
    private final List<EventEnvelope> events = Collections.synchronizedList(new ArrayList<>());
    private final List<byte[]> payloads = Collections.synchronizedList(new ArrayList<>());
    private final ReportGeneration generation = new ReportGeneration((snapshot, id) -> periodStore.find(snapshot, id)
            .map(row -> new ReportGeneration.PeriodState(row.status(), row.lockedAt())), Duration.ofMinutes(4),
            () -> 0L);

    @Test
    @DisplayName("a claim is born once and read back: the second claimer answers the same run, neither born nor held")
    void aClaimIsBornOnceAndReadBack() {
        AccountingPeriodId september = lockedSeptember();
        ReportRuns.Claimed first = runs().claim(UOW, TestReportDefinition.CODE, september, probe("a"), ACCOUNTANT,
                correlation());
        assertThat(first.born()).isTrue();
        assertThat(first.mine()).isTrue();
        assertThat(first.run().status()).isEqualTo(RunStatus.GENERATING);
        assertThat(first.run().revision()).isEqualTo(1);
        assertThat(first.run().attempt()).isEqualTo(1);
        ReportRuns.Claimed second = runs().claim(UOW, TestReportDefinition.CODE, september, probe("a"), CONTROLLER,
                correlation());
        assertThat(second.run().id()).isEqualTo(first.run().id());
        assertThat(second.born()).isFalse();
        assertThat(second.mine()).isFalse();
        assertThat(store.all()).hasSize(1);
        // Another question - other parameters - is another run.
        assertThat(runs().claim(UOW, TestReportDefinition.CODE, september, probe("b"), ACCOUNTANT, correlation())
                .run().id()).isNotEqualTo(first.run().id());
    }

    @Test
    @DisplayName("contention row 7: ten claimers racing on ten threads - one run born, one holder, nine read-backs; ten"
            + " completers - one edge, one event, one audit record")
    void tenClaimersOneRun() throws Exception {
        AccountingPeriodId september = lockedSeptember();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<ReportRuns.Claimed> claims = new ArrayList<>();
        try {
            List<Future<ReportRuns.Claimed>> racing = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                racing.add(pool.submit(() -> {
                    start.await();
                    return runs().claim(UOW, TestReportDefinition.CODE, september, probe("race"), ACCOUNTANT,
                            correlation());
                }));
            }
            start.countDown();
            for (Future<ReportRuns.Claimed> claim : racing) {
                claims.add(claim.get(1, TimeUnit.MINUTES));
            }
            assertThat(claims).extracting(claim -> claim.run().id()).containsOnly(claims.get(0).run().id());
            assertThat(claims).filteredOn(ReportRuns.Claimed::born).hasSize(1);
            assertThat(claims).filteredOn(ReportRuns.Claimed::mine).hasSize(1);
            assertThat(store.all()).hasSize(1);
            ReportRunStore.RunRow run = claims.get(0).run();
            ReportGeneration.Generated content = generation.generate(UOW, TestReportDefinition.echo(), run.inputs());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<ReportRuns.Concluded>> completing = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                completing.add(pool.submit(() -> {
                    go.await();
                    return runs().complete(UOW, run.id(), 1, content, ACCOUNTANT);
                }));
            }
            go.countDown();
            List<ReportRuns.Concluded> outcomes = new ArrayList<>();
            for (Future<ReportRuns.Concluded> outcome : completing) {
                outcomes.add(outcome.get(1, TimeUnit.MINUTES));
            }
            assertThat(outcomes).filteredOn(ReportRuns.Concluded::moved).hasSize(1);
            assertThat(outcomes).extracting(outcome -> outcome.run().status()).containsOnly(RunStatus.FINAL);
        } finally {
            pool.shutdownNow();
        }
        assertThat(store.completions()).isEqualTo(1);
        assertThat(events).extracting(EventEnvelope::eventType).containsExactly(ReportRuns.COMPLETED_EVENT);
        assertThat(audit).extracting(record -> record.operation().code()).containsExactly("accounting.ReportGenerated");
    }

    @Test
    @DisplayName("FINAL for a locked period - the lock's instant and its pinned mapping version recorded; PROVISIONAL for"
            + " an open one, no lock; a FINAL run completes FINAL, a PROVISIONAL one PROVISIONAL")
    void theBasisComesFromThePeriod() {
        AccountingPeriodId september = lockedSeptember();
        AccountingPeriodId october = periodStore.month(OCTOBER).orElseThrow().id();
        ReportRunStore.RunRow fin = runs().claim(UOW, TestReportDefinition.CODE, september, probe("f"), ACCOUNTANT,
                correlation()).run();
        assertThat(fin.basis()).isEqualTo(RunBasis.FINAL);
        assertThat(fin.periodLockedAt()).isEqualTo(periodStore.month(SEPTEMBER).orElseThrow().lockedAt()).isPresent();
        assertThat(fin.mappingVersionId()).isEqualTo(periodStore.month(SEPTEMBER).orElseThrow().pinned()
                .map(AccountingPeriodStore.Pin::id));
        ReportRunStore.RunRow prov = runs().claim(UOW, TestReportDefinition.CODE, october, probe("p"), ACCOUNTANT,
                correlation()).run();
        assertThat(prov.basis()).isEqualTo(RunBasis.PROVISIONAL);
        assertThat(prov.periodLockedAt()).isEmpty();
        assertThat(complete(fin).status()).isEqualTo(RunStatus.FINAL);
        assertThat(complete(prov).status()).isEqualTo(RunStatus.PROVISIONAL);
        assertThat(store.content(UOW, prov.id()).orElseThrow().text()).contains("\"basis\":\"PROVISIONAL\"")
                .contains("\"cut\":\"" + prov.cutAt() + "\"");
    }

    @Test
    @DisplayName("the completion is one conditional edge: a second completer at the same attempt converges writing nothing;"
            + " the event names codes, numbers and the hash, never a figure")
    void theCompletionIsOneEdge() {
        AccountingPeriodId september = lockedSeptember();
        ReportRunStore.RunRow run = runs().claim(UOW, TestReportDefinition.CODE, september, probe("e"), ACCOUNTANT,
                correlation()).run();
        ReportGeneration.Generated content = generation.generate(UOW, TestReportDefinition.echo(), run.inputs());
        ReportRuns.Concluded done = runs().complete(UOW, run.id(), 1, content, ACCOUNTANT);
        assertThat(done.moved()).isTrue();
        assertThat(done.run().contentSha256()).contains(content.sha256());
        ReportRuns.Concluded again = runs().complete(UOW, run.id(), 1, content, CONTROLLER);
        assertThat(again.moved()).isFalse();
        assertThat(again.run().status()).isEqualTo(RunStatus.FINAL);
        assertThat(events).hasSize(1);
        Map<String, String> payload = EventPayload.fields(payloads.get(0));
        assertThat(payload).containsOnlyKeys("definition", "definitionVersion", "period", "periodRevision", "revision",
                "status", "contentSha256");
        assertThat(payload).containsEntry("status", "FINAL").containsEntry("period", "2026-09")
                .containsEntry("contentSha256", content.sha256());
        EventEnvelope envelope = events.get(0);
        assertThat(envelope.aggregateId()).isEqualTo(run.id());
        assertThat(envelope.aggregateType()).isEqualTo(ReportRuns.TARGET_TYPE);
        assertThat(envelope.producer()).isEqualTo("accounting");
        assertThat(envelope.correlationId()).isEqualTo(run.correlation());
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.changeSummary()).hasValueSatisfying(summary -> assertThat(summary)
                    .contains("definition=TEST_PROBE").contains("status=FINAL").doesNotContain("echo"));
        });
        // The content is frozen: the store's rank refuses an overwrite.
        assertThatThrownBy(() -> store.overwriteContentForTest(run.id(), "{}"))
                .hasMessageContaining("report_run_content_is_frozen");
    }

    @Test
    @DisplayName("a generation failure is FAILED, audited FAILED with its code, and a new revision is allowed")
    void aFailureAllowsANewRevision() {
        AccountingPeriodId september = lockedSeptember();
        ReportRunStore.RunRow run = runs().claim(UOW, TestReportDefinition.CODE, september, probe("x"), ACCOUNTANT,
                correlation()).run();
        ReportRuns.Concluded failed = runs().fail(UOW, run.id(), 1, RunFailure.DEFINITION_FAILED, ACCOUNTANT,
                run.correlation());
        assertThat(failed.moved()).isTrue();
        assertThat(failed.run().status()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.run().failure()).contains(RunFailure.DEFINITION_FAILED);
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.changeSummary()).hasValueSatisfying(s -> assertThat(s).contains("failure=DEFINITION_FAILED"));
        });
        ReportRuns.Claimed next = runs().claim(UOW, TestReportDefinition.CODE, september, probe("x"), ACCOUNTANT,
                correlation());
        assertThat(next.born()).isTrue();
        assertThat(next.run().revision()).isEqualTo(2);
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("a dead generator's lease is reclaimed by one attempt more - never a second run; the dead attempt's late"
            + " completion is refused; the holder completes once")
    void aDeadGeneratorIsReclaimed() {
        AccountingPeriodId september = lockedSeptember();
        ReportRunStore.RunRow dead = runs().claim(UOW, TestReportDefinition.CODE, september, probe("d"), ACCOUNTANT,
                correlation()).run();
        assertThat(runs().reclaimExpired(UOW, 10, Actor.SYSTEM)).as("a live lease is not reclaimed").isEmpty();
        clock.advance(ReportRuns.Leases.STANDARD.lease().plusSeconds(1));
        // A claim of the same question now takes the expired run over rather than racing it with a new revision.
        ReportRuns.Claimed retaken = runs().claim(UOW, TestReportDefinition.CODE, september, probe("d"), CONTROLLER,
                correlation());
        assertThat(retaken.mine()).isTrue();
        assertThat(retaken.born()).isFalse();
        assertThat(retaken.run().id()).isEqualTo(dead.id());
        assertThat(retaken.run().attempt()).isEqualTo(2);
        clock.advance(ReportRuns.Leases.STANDARD.lease().plusSeconds(1));
        List<ReportRunStore.RunRow> held = runs().reclaimExpired(UOW, 10, Actor.SYSTEM);
        assertThat(held).singleElement().satisfies(run -> {
            assertThat(run.id()).isEqualTo(dead.id());
            assertThat(run.attempt()).isEqualTo(3);
        });
        ReportGeneration.Generated content = generation.generate(UOW, TestReportDefinition.echo(), dead.inputs());
        assertThat(runs().complete(UOW, dead.id(), 1, content, ACCOUNTANT).moved()).as("the dead attempt").isFalse();
        assertThat(runs().complete(UOW, dead.id(), 2, content, CONTROLLER).moved()).as("the overtaken").isFalse();
        assertThat(runs().complete(UOW, dead.id(), 3, content, Actor.SYSTEM).moved()).as("the holder").isTrue();
        assertThat(store.all()).hasSize(1);
        assertThat(events).hasSize(1);
    }

    @Test
    @DisplayName("the attempts are bounded: past the bound an expired run is failed ATTEMPTS_EXHAUSTED, not reclaimed")
    void theAttemptsAreBounded() {
        AccountingPeriodId september = lockedSeptember();
        ReportRuns bounded = runs(new ReportRuns.Leases(Duration.ofMinutes(1), 2, Duration.ofSeconds(48)));
        ReportRunStore.RunRow run = bounded.claim(UOW, TestReportDefinition.CODE, september, probe("b"), ACCOUNTANT,
                correlation()).run();
        clock.advance(Duration.ofMinutes(2));
        assertThat(bounded.reclaimExpired(UOW, 5, Actor.SYSTEM)).singleElement().extracting(ReportRunStore.RunRow::attempt)
                .isEqualTo(2);
        clock.advance(Duration.ofMinutes(2));
        assertThat(bounded.reclaimExpired(UOW, 5, Actor.SYSTEM)).isEmpty();
        assertThat(store.find(UOW, run.id()).orElseThrow().failure()).contains(RunFailure.ATTEMPTS_EXHAUSTED);
        assertThat(audit).singleElement().satisfies(record -> assertThat(record.actor()).isEqualTo(Actor.SYSTEM));
    }

    @Test
    @DisplayName("the unlock supersedes the period's FINAL runs in its own transaction - never deleted; the next lock's"
            + " claim is a new revision under the new lock")
    void anUnlockSupersedesTheFinalRuns() {
        AccountingPeriodId september = lockedSeptember();
        ReportRunStore.RunRow fin = runs().claim(UOW, TestReportDefinition.CODE, september, probe("s"), ACCOUNTANT,
                correlation()).run();
        complete(fin);
        unlock(september);
        ReportRunStore.RunRow superseded = store.find(UOW, fin.id()).orElseThrow();
        assertThat(superseded.status()).isEqualTo(RunStatus.SUPERSEDED);
        assertThat(store.content(UOW, fin.id())).as("retained, never deleted").isPresent();
        assertThat(audit).filteredOn(record -> record.operation() == AccountingAuditAction.PERIOD_UNLOCKED)
                .singleElement().satisfies(record -> assertThat(record.changeSummary())
                        .hasValueSatisfying(s -> assertThat(s).endsWith("supersededRuns=1")));
        // While OPEN, the same question is a PROVISIONAL run; locked again, a FINAL one under the new lock.
        ReportRunStore.RunRow open = runs().claim(UOW, TestReportDefinition.CODE, september, probe("s"), ACCOUNTANT,
                correlation()).run();
        assertThat(open.basis()).isEqualTo(RunBasis.PROVISIONAL);
        assertThat(open.revision()).isEqualTo(2);
        periods().lock(UOW, september, ACCOUNTANT, null, correlation());
        ReportRunStore.RunRow relocked = runs().claim(UOW, TestReportDefinition.CODE, september, probe("s"),
                ACCOUNTANT, correlation()).run();
        assertThat(relocked.revision()).isEqualTo(3);
        assertThat(relocked.basis()).isEqualTo(RunBasis.FINAL);
        assertThat(relocked.periodLockedAt()).isNotEqualTo(fin.periodLockedAt());
    }

    @Test
    @DisplayName("a FINAL run claimed before an unlock fails PERIOD_MOVED at its completion - never a FINAL answer over a"
            + " period whose range can change")
    void aMovedPeriodFailsTheCompletion() {
        AccountingPeriodId september = lockedSeptember();
        ReportRunStore.RunRow run = runs().claim(UOW, TestReportDefinition.CODE, september, probe("m"), ACCOUNTANT,
                correlation()).run();
        ReportGeneration.Generated content = generation.generate(UOW, TestReportDefinition.echo(), run.inputs());
        unlock(september);
        ReportRuns.Concluded concluded = runs().complete(UOW, run.id(), 1, content, ACCOUNTANT);
        assertThat(concluded.run().status()).isEqualTo(RunStatus.FAILED);
        assertThat(concluded.run().failure()).contains(RunFailure.PERIOD_MOVED);
        assertThat(events).isEmpty();
        // And the snapshot itself refuses it, before any completion is attempted.
        assertThatThrownBy(() -> generation.generate(UOW, TestReportDefinition.echo(), run.inputs()))
                .isInstanceOf(ReportGeneration.PeriodMoved.class);
    }

    @Test
    @DisplayName("an open period's generating run answers concurrent claimers; a completed PROVISIONAL run does not once"
            + " its freshness window has passed (P14-TSK-009) - a later ask is a new snapshot")
    void aCompletedProvisionalIsANewSnapshot() {
        AccountingPeriodId october = lockedSeptemberAndOctober();
        ReportRunStore.RunRow first = runs().claim(UOW, TestReportDefinition.CODE, october, probe("p"), ACCOUNTANT,
                correlation()).run();
        assertThat(runs().claim(UOW, TestReportDefinition.CODE, october, probe("p"), CONTROLLER, correlation()).run()
                .id()).isEqualTo(first.id());
        complete(first);
        clock.advance(ReportRuns.Provisional.STANDARD.freshness().plusSeconds(1));
        ReportRuns.Claimed later = runs().claim(UOW, TestReportDefinition.CODE, october, probe("p"), ACCOUNTANT,
                correlation());
        assertThat(later.born()).isTrue();
        assertThat(later.run().revision()).isEqualTo(2);
    }

    @Test
    @DisplayName("the request is screened before anything is written: an unknown definition, a parameter it does not take,"
            + " a rate set it does not want, an unknown period, a service as the requester")
    void theRequestIsScreened() {
        AccountingPeriodId september = lockedSeptember();
        assertThatThrownBy(() -> runs().claim(UOW, "TRIAL_BALANCE", september, RunParameters.none(), ACCOUNTANT,
                correlation())).isInstanceOf(ReportRuns.DefinitionUnknown.class);
        assertThatThrownBy(() -> runs().claim(UOW, TestReportDefinition.CODE, september,
                RunParameters.of(Map.of("currency", "EUR"), Optional.empty()), ACCOUNTANT, correlation()))
                .isInstanceOf(ReportRuns.ParametersInvalid.class).hasMessageContaining("currency");
        assertThatThrownBy(() -> runs().claim(UOW, TestReportDefinition.CODE, september,
                RunParameters.of(Map.of(), Optional.of(UUID.randomUUID())), ACCOUNTANT, correlation()))
                .isInstanceOf(ReportRuns.ParametersInvalid.class).hasMessageContaining("valuation rate set");
        assertThatThrownBy(() -> runs().claim(UOW, TestReportDefinition.CODE,
                AccountingPeriodId.next(IDS), probe("u"), ACCOUNTANT, correlation()))
                .isInstanceOf(AccountingPeriods.PeriodNotFound.class);
        assertThatThrownBy(() -> runs().claim(UOW, TestReportDefinition.CODE, september, probe("s"),
                new Actor("svc", ActorType.SERVICE), correlation())).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.all()).isEmpty();
    }

    @Test
    @DisplayName("the review's M-1: past the attempts' bound, a slow attempt completing FINAL between the lease's read and the"
            + " failure is the answer - the failure moves nothing, and no second FINAL run is born for the question")
    void aSlowLastAttemptIsTheAnswerNotASecondRun() {
        AccountingPeriodId september = lockedSeptember();
        ReportRuns.Leases leases = new ReportRuns.Leases(Duration.ofMinutes(1), 1, Duration.ofSeconds(48));
        ReportRunStore.RunRow slow = runs(leases).claim(UOW, TestReportDefinition.CODE, september, probe("slow"),
                ACCOUNTANT, correlation()).run();
        ReportGeneration.Generated content = generation.generate(UOW, TestReportDefinition.echo(), slow.inputs());
        clock.advance(Duration.ofMinutes(2));
        // The slow attempt 1 completes in the instant between the claimer's lease read and its failure.
        ReportRunStore racing = new CompletingFirst(store, slow, content);
        ReportRuns.Claimed claimed = new ReportRuns(racing, periodStore, mappings,
                ReportDefinitions.of(TestReportDefinition.echo()), leases, (unitOfWork, record) -> audit.add(record),
                (unitOfWork, envelope, payload, mediaType) -> events.add(envelope), IDS, clock)
                .claim(UOW, TestReportDefinition.CODE, september, probe("slow"), CONTROLLER, correlation());
        assertThat(claimed.run().id()).isEqualTo(slow.id());
        assertThat(claimed.run().status()).isEqualTo(RunStatus.FINAL);
        assertThat(claimed.born()).isFalse();
        assertThat(store.all()).as("one run for the question").hasSize(1);
        assertThat(store.all()).filteredOn(row -> row.status() == RunStatus.FINAL).hasSize(1);
    }

    @Test
    @DisplayName("the review's M-2: the unlock supersedes the period's FINAL runs before it moves the cutoff - its row writes"
            + " done before postings pause behind the cutoff's exclusive key")
    void theUnlockSupersedesBeforeTheCutoffMoves() {
        AccountingPeriodId september = lockedSeptember();
        List<String> order = new ArrayList<>();
        StandInCutoff recording = new StandInCutoff() {
            @Override
            public boolean moveBack(Connection unitOfWork, LocalDate from, Optional<LocalDate> to, String reason) {
                order.add("cutoff.moveBack");
                return super.moveBack(unitOfWork, from, to, reason);
            }
        };
        AccountingPeriods periods = new AccountingPeriods(periodStore, mappings, recording, LockGrace.standard(),
                (unitOfWork, record) -> {}, (unitOfWork, envelope, payload, mediaType) -> {}, IDS, clock,
                (unitOfWork, period, actor, correlation) -> {
                    order.add("supersedeFinal");
                    return 0;
                });
        periods.proposeUnlock(UOW, september, ACCOUNTANT, "a correction", correlation());
        periods.approveUnlock(UOW, september, periods.pendingUnlock(UOW, september).orElseThrow(), CONTROLLER, "reviewed", correlation());
        assertThat(order).containsExactly("supersedeFinal", "cutoff.moveBack");
    }

    @Test
    @DisplayName("the review's I-1: the generation's bound must leave a fifth of the lease - a configuration where a live"
            + " generator could outlive its lease is refused")
    void theGenerationBoundLeavesAFifthOfTheLease() {
        assertThat(ReportRuns.Leases.STANDARD.generationTimeout()).isEqualTo(Duration.ofMinutes(4));
        assertThat(new ReportRuns.Leases(Duration.ofMinutes(5), 5, Duration.ofMinutes(4)).generationTimeout())
                .isEqualTo(Duration.ofMinutes(4));
        assertThatThrownBy(() -> new ReportRuns.Leases(Duration.ofMinutes(5), 5, Duration.ofSeconds(241)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inside its lease");
        assertThatThrownBy(() -> new ReportRuns.Leases(Duration.ofMinutes(5), 5, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportRuns.Leases(Duration.ofMinutes(5), 5, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------

    private ReportRuns runs() {
        return runs(ReportRuns.Leases.STANDARD);
    }

    private ReportRuns runs(ReportRuns.Leases leases) {
        return new ReportRuns(store, periodStore, mappings, ReportDefinitions.of(TestReportDefinition.echo()), leases,
                (unitOfWork, record) -> audit.add(record),
                (unitOfWork, envelope, payload, mediaType) -> {
                    events.add(envelope);
                    payloads.add(payload);
                },
                IDS, clock);
    }

    private AccountingPeriods periods() {
        return new AccountingPeriods(periodStore, mappings, new StandInCutoff(), LockGrace.standard(),
                (unitOfWork, record) -> audit.add(record), (unitOfWork, envelope, payload, mediaType) -> {},
                IDS, clock, runs());
    }

    private ReportRunStore.RunRow complete(ReportRunStore.RunRow run) {
        ReportGeneration.Generated content = generation.generate(UOW, TestReportDefinition.echo(), run.inputs());
        return runs().complete(UOW, run.id(), run.attempt(), content, ACCOUNTANT).run();
    }

    /** September opened and locked (its grace passed on the 10th of October); October open. */
    private AccountingPeriodId lockedSeptember() {
        Clock september = Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneOffset.UTC);
        periodStore.onDatabaseDate(LocalDate.of(2026, 9, 15), () -> new AccountingPeriods(periodStore, mappings, new StandInCutoff(), LockGrace.standard(), (u, r) -> {},
                (u, e, p, m) -> {}, IDS, september, runs()).openNext(UOW, Actor.SYSTEM, correlation()));
        periods().openNext(UOW, Actor.SYSTEM, correlation());
        AccountingPeriodId id = periodStore.month(SEPTEMBER).orElseThrow().id();
        periods().lock(UOW, id, ACCOUNTANT, null, correlation());
        audit.clear();
        return id;
    }

    private AccountingPeriodId lockedSeptemberAndOctober() {
        lockedSeptember();
        return periodStore.month(OCTOBER).orElseThrow().id();
    }

    private void unlock(AccountingPeriodId id) {
        periods().proposeUnlock(UOW, id, ACCOUNTANT, "a correction", correlation());
        periods().approveUnlock(UOW, id, periods().pendingUnlock(UOW, id).orElseThrow(), CONTROLLER, "reviewed", correlation());
    }

    private static RunParameters probe(String value) {
        return RunParameters.of(Map.of("probe", value), Optional.empty());
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    /** The stand-in ledger cutoff: moves recorded, nothing refused. */
    private static class StandInCutoff implements PostingCutoff {
        private Optional<LocalDate> closedThrough = Optional.empty();

        @Override
        public Optional<LocalDate> closedThrough(Connection unitOfWork) {
            return closedThrough;
        }

        @Override
        public boolean advance(Connection unitOfWork, Optional<LocalDate> from, LocalDate through, String reason) {
            closedThrough = Optional.of(through);
            return true;
        }

        @Override
        public boolean moveBack(Connection unitOfWork, LocalDate from, Optional<LocalDate> to, String reason) {
            closedThrough = to;
            return true;
        }
    }

    /**
     * The in-memory store with one interleaving: the slow attempt's completion lands just before the claimer's failure -
     * the window the review's M-1 names.
     */
    private static final class CompletingFirst implements ReportRunStore {
        private final InMemoryReportRunStore store;
        private final ReportRunStore.RunRow slow;
        private final ReportGeneration.Generated content;

        CompletingFirst(InMemoryReportRunStore store, ReportRunStore.RunRow slow, ReportGeneration.Generated content) {
            this.store = store;
            this.slow = slow;
            this.content = content;
        }

        @Override
        public Optional<RunRow> latest(Connection unitOfWork, RunKey key) {
            return store.latest(unitOfWork, key);
        }

        @Override
        public boolean insertIfAbsent(Connection unitOfWork, NewRun run) {
            return store.insertIfAbsent(unitOfWork, run);
        }

        @Override
        public Optional<RunRow> at(Connection unitOfWork, RunKey key, int revision) {
            return store.at(unitOfWork, key, revision);
        }

        @Override
        public Optional<RunRow> find(Connection unitOfWork, ReportRunId id) {
            return store.find(unitOfWork, id);
        }

        @Override
        public Optional<Content> content(Connection unitOfWork, ReportRunId id) {
            return store.content(unitOfWork, id);
        }

        @Override
        public boolean reclaim(Connection unitOfWork, ReportRunId id, int attempt, Duration lease) {
            return store.reclaim(unitOfWork, id, attempt, lease);
        }

        @Override
        public boolean pastLease(Connection unitOfWork, ReportRunId id) {
            return store.pastLease(unitOfWork, id);
        }

        @Override
        public List<RunRow> expired(Connection unitOfWork, int limit) {
            return store.expired(unitOfWork, limit);
        }

        @Override
        public boolean complete(Connection unitOfWork, ReportRunId id, int attempt, RunStatus status, String text,
                String sha256) {
            return store.complete(unitOfWork, id, attempt, status, text, sha256);
        }

        @Override
        public boolean fail(Connection unitOfWork, ReportRunId id, int attempt, RunFailure failure) {
            store.complete(unitOfWork, slow.id(), slow.attempt(), RunStatus.FINAL, content.content(), content.sha256());
            return store.fail(unitOfWork, id, attempt, failure);
        }

        @Override
        public int supersedeFinal(Connection unitOfWork, AccountingPeriodId period, CorrelationId correlation) {
            return store.supersedeFinal(unitOfWork, period, correlation);
        }

        @Override
        public boolean cutWithin(Connection unitOfWork, ReportRunId id, Duration window) {
            return store.cutWithin(unitOfWork, id, window);
        }
    }

    /** The "database" clock, moved by the suite. */
    static final class MovableClock extends Clock {
        private volatile Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
