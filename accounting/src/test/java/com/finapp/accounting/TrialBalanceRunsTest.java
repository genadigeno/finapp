package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.TrialBalanceDefinition;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.InMemoryReportRunStore;
import com.finapp.accounting.testing.InMemoryTrialBalanceReads;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The trial balance as a report run (`P14-TSK-009`; ADR-0115; {@code INV-ACC-01} reported, {@code INV-ACC-04},
 * {@code INV-ACC-06}) - the production definition through `P14-TSK-008`'s machine: a locked period's FINAL run under its
 * pin, passing, regenerating identical; an open period's PROVISIONAL run at its cut; and the bound on PROVISIONAL
 * revisions the review's M-8 asked for - a completed PROVISIONAL run reused within its freshness window (the database's
 * clock), a new revision past it, ten claimers past it birthing one. Hermetic: the stores and the books in memory; the
 * database's own proof is {@code TrialBalanceRunDatabaseTest} (owed, C5).
 */
@DisplayName("the trial balance as a report run, and the PROVISIONAL bound (P14-TSK-009)")
class TrialBalanceRunsTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException("the in-memory stores never touch the connection");
            });
    private static final YearMonth FEBRUARY = YearMonth.of(2096, 2);
    private static final YearMonth MARCH = YearMonth.of(2096, 3);
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final String CASH = "CASH_AT_BANK/OPERATIONAL";
    private static final String WALLET = "CUSTOMER_WALLET/CUSTOMER";

    private final ReportRunsTest.MovableClock clock = new ReportRunsTest.MovableClock(
            Instant.parse("2096-03-10T09:00:00Z"));
    private final InMemoryAccountingPeriodStore periodStore = new InMemoryAccountingPeriodStore();
    private final InMemoryGlMappingStore mappings = new InMemoryGlMappingStore();
    private final InMemoryReportRunStore store = new InMemoryReportRunStore(clock, periodStore);
    private final InMemoryTrialBalanceReads books = new InMemoryTrialBalanceReads();
    private final TrialBalanceDefinition definition = new TrialBalanceDefinition(books);
    private final List<AuditRecord> audit = Collections.synchronizedList(new ArrayList<>());
    private final List<EventEnvelope> events = Collections.synchronizedList(new ArrayList<>());
    private final List<byte[]> payloads = Collections.synchronizedList(new ArrayList<>());
    private final ReportGeneration generation = new ReportGeneration((snapshot, id) -> periodStore.find(snapshot, id)
            .map(row -> new ReportGeneration.PeriodState(row.status(), row.lockedAt())), Duration.ofMinutes(4),
            System::nanoTime);
    private GlMappingVersionId version;
    private AccountingPeriodId february;
    private AccountingPeriodId march;

    @BeforeEach
    void februaryLockedUnderTheReferenceVersionMarchOpen() {
        version = GlMappingVersionId.next(IDS);
        mappings.insertProposal(UOW, new GlMappingStore.NewProposal(version, 1,
                ReferenceGlMapping.version(YearMonth.of(2096, 1)), new Actor("proposer", ActorType.CUSTOMER),
                "the reference chart", CorrelationId.generate(IDS), UUID.randomUUID()));
        mappings.decide(UOW, version, GlMappingVersionStatus.ACTIVE, new Actor("approver", ActorType.CUSTOMER), "ok");
        february = AccountingPeriodId.next(IDS);
        march = AccountingPeriodId.next(IDS);
        periodStore.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(february, FEBRUARY, correlation()));
        periodStore.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(march, MARCH, correlation()));
        periodStore.markLocked(UOW, february, 1, ACCOUNTANT, Optional.of(new AccountingPeriodStore.Pin(version, 1)));
        books.version(version, mappings.content(UOW, version))
                .period(new TrialBalanceReads.Period(february, FEBRUARY.atDay(1), FEBRUARY.atEndOfMonth(),
                        PeriodStatus.LOCKED, Optional.of(version)))
                .period(new TrialBalanceReads.Period(march, MARCH.atDay(1), MARCH.atEndOfMonth(), PeriodStatus.OPEN,
                        Optional.empty()))
                .post(YearMonth.of(2096, 1).atDay(20), clock.instant().minusSeconds(86_400 * 30), "EUR", 2, 100_000,
                        CASH, WALLET)
                .post(FEBRUARY.atDay(1), clock.instant().minusSeconds(86_400 * 20), "EUR", 2, 2_500, CASH, WALLET)
                .post(FEBRUARY.atEndOfMonth(), clock.instant().minusSeconds(86_400 * 10), "USD", 2, 700, WALLET, CASH)
                .post(MARCH.atDay(2), clock.instant().minusSeconds(3_600), "EUR", 2, 1_200, CASH, WALLET);
    }

    @Test
    @DisplayName("a locked period gets a FINAL trial balance under its pin - passing, its event naming TRIAL_BALANCE and"
            + " the version, regenerating byte-identical (INV-ACC-04)")
    void aLockedPeriodGetsAFinalTrialBalance() {
        ReportRunStore.RunRow run = ask(february, runs());
        assertThat(run.basis()).isEqualTo(RunBasis.FINAL);
        assertThat(run.status()).isEqualTo(RunStatus.FINAL);
        assertThat(run.mappingVersionId()).as("the period's pin").contains(version);
        String content = store.content(UOW, run.id()).orElseThrow().text();
        assertThat(content).contains("\"definition\":\"TRIAL_BALANCE\"").contains("\"basis\":\"FINAL\"")
                .contains("\"passes\":true").contains("\"cut\":null");
        assertThat(events).singleElement().satisfies(event -> assertThat(event.eventType())
                .isEqualTo(ReportRuns.COMPLETED_EVENT));
        assertThat(new String(payloads.get(0), StandardCharsets.UTF_8)).contains("TRIAL_BALANCE");
        ReportGeneration.Generated again = generation.generate(UOW, definition, run.inputs());
        assertThat(again.content()).isEqualTo(content);
        assertThat(run.contentSha256()).contains(again.sha256());
        assertThat(ask(february, runs()).id()).as("one FINAL run per lock - the next ask answers it").isEqualTo(run.id());
    }

    @Test
    @DisplayName("an open period gets a PROVISIONAL trial balance cut at its claim: an entry created after the claim is not"
            + " in it, and its content names the cut")
    void anOpenPeriodGetsAProvisionalTrialBalanceAtItsCut() {
        ReportRuns runs = runs();
        ReportRuns.Claimed claimed = runs.claim(UOW, TrialBalanceDefinition.CODE, march, RunParameters.none(),
                ACCOUNTANT, correlation());
        books.post(MARCH.atDay(3), clock.instant().plusSeconds(5), "EUR", 2, 999_999, CASH, WALLET);
        clock.advance(Duration.ofSeconds(30)); // generated later than claimed - a reclaim's shape
        ReportRunStore.RunRow run = complete(runs, claimed.run());
        assertThat(run.status()).isEqualTo(RunStatus.PROVISIONAL);
        String content = store.content(UOW, run.id()).orElseThrow().text();
        assertThat(content).contains("\"cut\":\"" + claimed.run().cutAt() + "\"").contains("\"passes\":true")
                .contains("\"minor\":\"1200\"").doesNotContain("999999");
    }

    @Test
    @DisplayName("the PROVISIONAL bound (P14-TSK-008's review, M-8): within the freshness window a later ask answers the"
            + " completed PROVISIONAL run - no new row; past it, the next revision")
    void aFreshProvisionalIsReused() {
        ReportRunStore.RunRow first = ask(march, runs());
        clock.advance(ReportRuns.Provisional.STANDARD.freshness().minusSeconds(1));
        ReportRunStore.RunRow reused = ask(march, runs());
        assertThat(reused.id()).isEqualTo(first.id());
        assertThat(store.all()).hasSize(1);
        clock.advance(Duration.ofSeconds(2));
        ReportRunStore.RunRow fresh = ask(march, runs());
        assertThat(fresh.id()).isNotEqualTo(first.id());
        assertThat(fresh.revision()).isEqualTo(2);
        assertThat(fresh.status()).isEqualTo(RunStatus.PROVISIONAL);
        assertThat(store.all()).hasSize(2);
    }

    @Test
    @DisplayName("ten claimers past the window, on ten threads: one revision born, every claimer answering it")
    void tenClaimersPastTheWindowBirthOneRevision() throws Exception {
        ReportRunStore.RunRow first = ask(march, runs());
        clock.advance(ReportRuns.Provisional.STANDARD.freshness().plusSeconds(1));
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ReportRuns.Claimed>> racing = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                racing.add(pool.submit(() -> {
                    start.await();
                    return runs().claim(UOW, TrialBalanceDefinition.CODE, march, RunParameters.none(), ACCOUNTANT,
                            correlation());
                }));
            }
            start.countDown();
            List<ReportRuns.Claimed> claimed = new ArrayList<>();
            for (Future<ReportRuns.Claimed> claim : racing) {
                claimed.add(claim.get(1, TimeUnit.MINUTES));
            }
            assertThat(claimed.stream().filter(ReportRuns.Claimed::born)).hasSize(1);
            assertThat(claimed.stream().map(claim -> claim.run().id()).distinct()).singleElement()
                    .isNotEqualTo(first.id());
            assertThat(store.all()).hasSize(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("only a completed PROVISIONAL run is reused: a FAILED one - even a second old - is followed by a new"
            + " revision; the window is a configured bound, one second to a day, judged on the database's clock")
    void onlyACompletedProvisionalIsReused() {
        ReportRuns runs = runs(new ReportRuns.Provisional(Duration.ofMinutes(5)));
        ReportRunStore.RunRow claimed = runs.claim(UOW, TrialBalanceDefinition.CODE, march, RunParameters.none(),
                ACCOUNTANT, correlation()).run();
        runs.fail(UOW, claimed.id(), claimed.attempt(), RunFailure.DEFINITION_FAILED, ACCOUNTANT, correlation());
        clock.advance(Duration.ofSeconds(1));
        assertThat(ask(march, runs).revision()).isEqualTo(2);
        assertThatThrownBy(() -> new ReportRuns.Provisional(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportRuns.Provisional(Duration.ofHours(25)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ReportRuns.Provisional.STANDARD.freshness()).isEqualTo(Duration.ofMinutes(15));
        assertThat(JdbcReportRunStore.CUT_WITHIN_SQL).contains("cut_at > clock_timestamp() - ?")
                .contains("WHERE id = ?").doesNotContain("now()");
        assertThat(store.cutWithin(UOW, ReportRunId.next(IDS), Duration.ofHours(1))).isFalse();
    }

    // ------------------------------------------------------------------

    private ReportRuns runs() {
        return runs(ReportRuns.Provisional.STANDARD);
    }

    private ReportRuns runs(ReportRuns.Provisional provisional) {
        return new ReportRuns(store, periodStore, mappings, ReportDefinitions.of(definition), ReportRuns.Leases.STANDARD,
                (unitOfWork, record) -> audit.add(record),
                (unitOfWork, envelope, payload, mediaType) -> {
                    events.add(envelope);
                    payloads.add(payload);
                },
                IDS, clock, provisional);
    }

    /** The desk's path: claim, then generate and complete the run this caller holds; otherwise the run as it stands. */
    private ReportRunStore.RunRow ask(AccountingPeriodId period, ReportRuns runs) {
        ReportRuns.Claimed claimed = runs.claim(UOW, TrialBalanceDefinition.CODE, period, RunParameters.none(),
                ACCOUNTANT, correlation());
        return claimed.mine() ? complete(runs, claimed.run()) : claimed.run();
    }

    private ReportRunStore.RunRow complete(ReportRuns runs, ReportRunStore.RunRow run) {
        return runs.complete(UOW, run.id(), run.attempt(), generation.generate(UOW, definition, run.inputs()),
                ACCOUNTANT).run();
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }
}
