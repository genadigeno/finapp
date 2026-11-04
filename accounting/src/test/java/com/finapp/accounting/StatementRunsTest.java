package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.reporting.BalanceSheetDefinition;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.IncomeStatementDefinition;
import com.finapp.accounting.reporting.ReportDefinition;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.ReportInputs;
import com.finapp.accounting.reporting.ReportRefused;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.StatementNotes;
import com.finapp.accounting.reporting.TrialBalanceDefinition;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.InMemoryReportRunStore;
import com.finapp.accounting.testing.InMemoryTrialBalanceReads;
import com.finapp.accounting.testing.InMemoryValuationRateSetStore;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
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
import java.util.Map;
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
 * The financial statements through the report run machine (`P14-TSK-016`; ADR-0115; {@code INV-ACC-04}, {@code INV-ACC-14}): a statement waits for its close, a refusal blocks nothing, a reopen supersedes and the re-close is a new run beside the first's record, ten claimers one run, translation's
 * completeness on the event and the record. Hermetic: stores and books in memory, the notes' ports over the period's own row. Against PostgreSQL: {@code StatementsDatabaseTest} (owed, C5).
 */
@DisplayName("the financial statements as report runs (P14-TSK-016)")
class StatementRunsTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
        throw new UnsupportedOperationException("the in-memory stores never touch the connection");
    });
    private static final YearMonth FEBRUARY = YearMonth.of(2096, 2);
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final Actor CONTROLLER = new Actor("controller-1", ActorType.CUSTOMER);
    private static final String CASH = "CASH_AT_BANK/OPERATIONAL";
    private static final String WALLET = "CUSTOMER_WALLET/CUSTOMER";

    private final ReportRunsTest.MovableClock clock = new ReportRunsTest.MovableClock(Instant.parse("2096-03-10T09:00:00Z"));
    private final InMemoryAccountingPeriodStore periodStore = new InMemoryAccountingPeriodStore();
    private final InMemoryGlMappingStore mappings = new InMemoryGlMappingStore();
    private final InMemoryReportRunStore store = new InMemoryReportRunStore(clock, periodStore);
    private final InMemoryValuationRateSetStore sets = new InMemoryValuationRateSetStore().watching(periodStore);
    private final InMemoryTrialBalanceReads books = new InMemoryTrialBalanceReads();
    private final ValuationRateReads rates = (snapshot, id) -> sets.find(snapshot, ValuationRateSetId.of(id))
            .map(row -> new ValuationRateReads.PinnedSet(row.id().value(), row.period(), row.month(), row.status(), row.asOf(), row.maxAgeHours(), sets.rates(snapshot, row.id())));
    private final List<AuditRecord> audit = Collections.synchronizedList(new ArrayList<>());
    private final List<EventEnvelope> events = Collections.synchronizedList(new ArrayList<>());
    private final List<byte[]> payloads = Collections.synchronizedList(new ArrayList<>());
    private final ReportGeneration generation = new ReportGeneration((snapshot, id) -> periodStore.find(snapshot, id).map(row -> new ReportGeneration.PeriodState(row.status(), row.lockedAt())), Duration.ofMinutes(4), () -> 0L);
    private final List<CloseRecordReads.EarlierClose> earlier = new ArrayList<>();
    private final StatementNotes notes = new StatementNotes((snapshot, period) -> new SuspenseAsOfCutoff.Register(period.atEndOfMonth(), Instant.EPOCH, List.of()),
            (snapshot, period) -> new ReconciliationAsOfCutoff.Standing(period.atEndOfMonth(), Instant.EPOCH, List.of(), List.of()),
            (snapshot, month) -> new PriorPeriodCorrections.Corrections(List.of(), List.of(), 0),
            (snapshot, period, revision) -> periodStore.find(snapshot, period).filter(row -> row.status() == PeriodStatus.CLOSED && row.revision() == revision)
                    .map(row -> new CloseRecordReads.CloseRecord(UUID.nameUUIDFromBytes(new byte[] {(byte) revision}), revision, row.lockedAt().orElseThrow(), UUID.nameUUIDFromBytes(new byte[] {1}), this.version.value(), UUID.nameUUIDFromBytes(new byte[] {2}), 0, 0, Instant.EPOCH, Instant.EPOCH, List.of(), earlier)));
    private GlMappingVersionId version;
    private AccountingPeriodId february;
    private ReportDefinition sheet;
    private ReportDefinition income;
    private ReportDefinition trialBalance;

    @BeforeEach
    void februaryOpen() {
        version = GlMappingVersionId.next(IDS);
        mappings.insertProposal(UOW, new GlMappingStore.NewProposal(version, 1, ReferenceGlMapping.version(YearMonth.of(2096, 1)), new Actor("proposer", ActorType.CUSTOMER), "the reference chart", correlation(), UUID.randomUUID()));
        mappings.decide(UOW, version, GlMappingVersionStatus.ACTIVE, new Actor("approver", ActorType.CUSTOMER), "ok");
        february = AccountingPeriodId.next(IDS);
        periodStore.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(february, FEBRUARY, correlation()));
        books.version(version, mappings.content(UOW, version));
        showPeriod(PeriodStatus.OPEN);
        books.post(FEBRUARY.atDay(3), clock.instant().minusSeconds(86_400), "EUR", 2, 2_500, CASH, WALLET);
        books.post(FEBRUARY.atDay(4), clock.instant().minusSeconds(86_400), "USD", 2, 1_001, CASH, WALLET);
        sheet = new BalanceSheetDefinition(books, books.counterparties(), rates, notes);
        income = new IncomeStatementDefinition(books, books.counterparties(), rates, notes);
        trialBalance = new TrialBalanceDefinition(books, rates);
    }

    private void showPeriod(PeriodStatus status) {
        books.period(new TrialBalanceReads.Period(february, FEBRUARY.atDay(1), FEBRUARY.atEndOfMonth(), status, status == PeriodStatus.OPEN ? Optional.empty() : Optional.of(version)));
    }

    private int revision() {
        return periodStore.month(FEBRUARY).orElseThrow().revision();
    }

    private void lock() {
        periodStore.markLocked(UOW, february, revision(), ACCOUNTANT, Optional.of(new AccountingPeriodStore.Pin(version, 1)));
        showPeriod(PeriodStatus.LOCKED);
    }

    private void proposeClose() {
        periodStore.markCloseProposed(UOW, february, revision());
        showPeriod(PeriodStatus.CLOSE_PROPOSED);
    }

    private void close() {
        periodStore.markClosed(UOW, february, revision());
        showPeriod(PeriodStatus.CLOSED);
    }

    @Test
    @DisplayName("a statement waits for its close: refused at the claim while LOCKED and CLOSE_PROPOSED, nothing written, no record and no event - so nothing exists that a close could wait on; asked again once CLOSED, FINAL")
    void aStatementWaitsForItsClose() {
        lock();
        for (String code : List.of(BalanceSheetDefinition.CODE, IncomeStatementDefinition.CODE)) {
            assertThatThrownBy(() -> runs().claim(UOW, code, february, RunParameters.none(), ACCOUNTANT, correlation())).isInstanceOf(ReportRuns.PeriodNotClosed.class).hasMessageContaining(FEBRUARY.toString());
        }
        proposeClose();
        for (String code : List.of(BalanceSheetDefinition.CODE, IncomeStatementDefinition.CODE)) {
            assertThatThrownBy(() -> runs().claim(UOW, code, february, RunParameters.none(), ACCOUNTANT, correlation())).as(code).isInstanceOf(ReportRuns.PeriodNotClosed.class);
        }
        assertThat(store.all()).isEmpty();
        assertThat(audit).isEmpty();
        assertThat(events).isEmpty();

        close();
        for (ReportDefinition definition : List.of(sheet, income)) {
            ReportRunStore.RunRow run = ask(definition, RunParameters.none());
            assertThat(run.status()).as(definition.code()).isEqualTo(RunStatus.FINAL);
            assertThat(run.basis()).isEqualTo(RunBasis.FINAL);
            assertThat(generation.generate(UOW, definition, run.inputs()).content()).as("regenerated byte-identical").isEqualTo(store.content(UOW, run.id()).orElseThrow().text());
        }
    }

    @Test
    @DisplayName("every other definition is as it was: the trial balance is FINAL at the lock, which is what the close reads, and no statement stands in its way")
    void theTrialBalanceStillAnswersAtTheLock() {
        lock();
        assertThat(ask(trialBalance, RunParameters.none()).status()).isEqualTo(RunStatus.FINAL);
    }

    @Test
    @DisplayName("an OPEN period: PROVISIONAL, labelled, cut at its claim, no close record, never issued")
    void anOpenPeriodIsProvisional() {
        ReportRunStore.RunRow run = ask(sheet, RunParameters.none());
        assertThat(run.basis()).isEqualTo(RunBasis.PROVISIONAL);
        assertThat(run.status()).isEqualTo(RunStatus.PROVISIONAL);
        assertThat(store.content(UOW, run.id()).orElseThrow().text()).contains("\"basis\":\"PROVISIONAL\"").contains("\"closeRecord\":null").contains("\"cut\":\"");
    }

    @Test
    @DisplayName("ten claimers of one statement, on ten threads: one run, every claimer answering it")
    void tenClaimersOneRun() throws Exception {
        lock();
        proposeClose();
        close();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ReportRuns.Claimed>> racing = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                racing.add(pool.submit(() -> {
                    start.await();
                    return runs().claim(UOW, BalanceSheetDefinition.CODE, february, RunParameters.none(), ACCOUNTANT, correlation());
                }));
            }
            start.countDown();
            List<ReportRuns.Claimed> claimed = new ArrayList<>();
            for (Future<ReportRuns.Claimed> claim : racing) {
                claimed.add(claim.get(1, TimeUnit.MINUTES));
            }
            assertThat(claimed.stream().filter(ReportRuns.Claimed::born)).hasSize(1);
            assertThat(claimed.stream().map(claim -> claim.run().id()).distinct()).hasSize(1);
            assertThat(store.all()).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a refusal ends the run FAILED, DEFINITION_FAILED, its reason on the record and no event; a new ask is a new revision, so nothing is wedged, and the period's edges are untouched")
    void aRefusalIsAFailedRunAndBlocksNothing() {
        lock();
        proposeClose();
        close();
        ReportRuns.Claimed claimed = runs().claim(UOW, BalanceSheetDefinition.CODE, february, RunParameters.none(), ACCOUNTANT, correlation());
        ReportRefused refused = new ReportRefused(ReportRefused.Reason.UNCLASSIFIED, List.of("LOAN_PRINCIPAL/LOAN"));

        ReportRuns.Concluded failed = runs().refuse(UOW, claimed.run().id(), claimed.run().attempt(), refused.summary(), ACCOUNTANT, claimed.run().correlation());

        assertThat(failed.moved()).isTrue();
        assertThat(failed.run().status()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.run().failure()).contains(RunFailure.DEFINITION_FAILED);
        assertThat(events).isEmpty();
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.changeSummary().orElseThrow()).contains("failure=DEFINITION_FAILED").contains("refusal=UNCLASSIFIED:LOAN_PRINCIPAL/LOAN").doesNotContain("25");
        });
        ReportRunStore.RunRow again = ask(sheet, RunParameters.none());
        assertThat(again.revision()).as("a FAILED run never answers: the next ask is the next revision").isEqualTo(2);
        assertThat(again.status()).isEqualTo(RunStatus.FINAL);
        assertThat(periodStore.month(FEBRUARY).orElseThrow().status()).isEqualTo(PeriodStatus.CLOSED);
    }

    @Test
    @DisplayName("the refusal's summary is codes alone, at most sixteen subjects with the rest counted: an audit summary is bounded, and no amount reaches it")
    void aRefusalsSummaryIsBounded() {
        List<String> many = new ArrayList<>();
        for (int at = 0; at < 20; at++) {
            many.add("CUR" + at);
        }
        ReportRefused refused = new ReportRefused(ReportRefused.Reason.OUT_OF_BALANCE, many);
        assertThat(refused.summary()).startsWith("OUT_OF_BALANCE:CUR0,CUR1,").endsWith(",CUR15,+4");
        assertThat(new ReportRefused(ReportRefused.Reason.NOT_CLOSED, List.of()).summary()).isEqualTo("NOT_CLOSED");
    }

    @Test
    @DisplayName("a reopened and re-closed month: the first FINAL run SUPERSEDED, never deleted; the next ask a new run under the new revision and lock, whose note reads its own close and names the first")
    void aReCloseIsANewRunBesideTheFirst() {
        lock();
        proposeClose();
        close();
        ReportRunStore.RunRow first = ask(sheet, RunParameters.none());
        assertThat(first.periodRevision()).isEqualTo(1);

        assertThat(runs().supersedeFinal(UOW, february, ACCOUNTANT, correlation())).isEqualTo(1);
        periodStore.markReopened(UOW, february, revision());
        showPeriod(PeriodStatus.OPEN);
        clock.advance(Duration.ofHours(1));
        lock();
        proposeClose();
        close();
        UUID firstClose = UUID.randomUUID();
        earlier.add(new CloseRecordReads.EarlierClose(1, firstClose, Instant.EPOCH));

        ReportRunStore.RunRow second = ask(sheet, RunParameters.none());

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.periodRevision()).isEqualTo(2);
        assertThat(second.status()).isEqualTo(RunStatus.FINAL);
        assertThat(store.find(UOW, first.id()).orElseThrow().status()).as("kept").isEqualTo(RunStatus.SUPERSEDED);
        assertThat(store.content(UOW, second.id()).orElseThrow().text()).contains("\"revision\":\"2\"").contains(firstClose.toString());
        assertThat(store.content(UOW, first.id()).orElseThrow().text()).as("what was issued stands").doesNotContain(firstClose.toString());
    }

    @Test
    @DisplayName("I-1(a): a translated run's completeness is on the event and the audit record, by currency code; a plain run's names none. A hole in a trial balance stays issued, disclosed there too; a statement refuses it")
    void completenessIsCarried() {
        lock();
        ValuationRateSetId set = approved(Map.of("USD", "1.1561"));
        proposeClose();
        close();
        ReportRunStore.RunRow plain = ask(sheet, RunParameters.none());
        assertThat(new String(payloads.get(0), StandardCharsets.UTF_8)).doesNotContain("translationComplete");
        assertThat(audit.get(0).changeSummary().orElseThrow()).doesNotContain("translationComplete");
        assertThat(plain.status()).isEqualTo(RunStatus.FINAL);

        ReportRunStore.RunRow translated = ask(sheet, asking(set));
        assertThat(translated.status()).isEqualTo(RunStatus.FINAL);
        assertThat(new String(payloads.get(1), StandardCharsets.UTF_8)).contains("\"translationComplete\":\"true\"").contains("\"untranslatedCount\":\"0\"").doesNotContain("\"untranslated\":");
        assertThat(audit.get(1).changeSummary().orElseThrow()).contains("translationComplete=true").contains("untranslated=[]");

        books.post(FEBRUARY.atDay(4), clock.instant().minusSeconds(86_400), "GBP", 2, 700, CASH, WALLET);
        ReportRunStore.RunRow withAHole = ask(trialBalance, asking(set));
        assertThat(withAHole.status()).as("the owner's decision: a translated FINAL trial balance with a real hole stays issued").isEqualTo(RunStatus.FINAL);
        assertThat(new String(payloads.get(2), StandardCharsets.UTF_8)).contains("\"translationComplete\":\"false\"").contains("\"untranslatedCount\":\"1\"").contains("\"untranslated\":\"GBP\"");
        assertThat(audit.get(2).changeSummary().orElseThrow()).contains("translationComplete=false").contains("untranslated=[GBP]").doesNotContain("700");

        assertThatThrownBy(() -> generation.generate(UOW, sheet, translated.inputs())).as("the same books, the same set: a statement cannot stand with it").isInstanceOfSatisfying(ReportRefused.class, refused -> {
            assertThat(refused.reason()).isEqualTo(ReportRefused.Reason.TRANSLATION_INCOMPLETE);
            assertThat(refused.subjects()).containsExactly("GBP");
        });
    }

    @Test
    @DisplayName("a run names a set only where it is the period's approved one, a statement like any other; the header names it")
    void aStatementPinsItsPeriodsApprovedSet() {
        lock();
        ValuationRateSetId set = approved(Map.of("USD", "1.1561"));
        proposeClose();
        close();
        assertThatThrownBy(() -> runs().claim(UOW, BalanceSheetDefinition.CODE, february, asking(ValuationRateSetId.next(IDS)), ACCOUNTANT, correlation())).isInstanceOf(ReportRuns.ValuationSetNotApproved.class);
        ReportRunStore.RunRow run = ask(sheet, asking(set));
        assertThat(store.content(UOW, run.id()).orElseThrow().text()).contains("\"valuationRateSet\":\"" + set.value() + "\"").contains("\"balanced\":true").contains("\"complete\":true");
        assertThat(generation.generate(UOW, sheet, run.inputs()).content()).isEqualTo(store.content(UOW, run.id()).orElseThrow().text());
    }

    @Test
    @DisplayName("the completeness read is generic: a body with no translation member says nothing; one holding complete false says so; anything but an explicit true is not complete")
    void completenessIsReadFromTheBody() {
        ReportGeneration plain = new ReportGeneration((snapshot, id) -> Optional.empty(), Duration.ofMinutes(1), () -> 0L);
        ReportInputs inputs = new ReportInputs(february, FEBRUARY, 1, RunBasis.PROVISIONAL, Optional.empty(), Optional.empty(), Optional.empty(), Instant.EPOCH, RunParameters.none());
        assertThat(plain.generate(UOW, definition(CanonicalJson.object().put("a", "b")), inputs).translation()).isEmpty();
        assertThat(plain.generate(UOW, definition(CanonicalJson.object().put("translation", CanonicalJson.object().put("complete", false).put("untranslated", CanonicalJson.array().add("JPY").add("USD")))), inputs).translation())
                .hasValueSatisfying(found -> {
                    assertThat(found.complete()).isFalse();
                    assertThat(found.untranslated()).containsExactly("JPY", "USD");
                });
        assertThat(plain.generate(UOW, definition(CanonicalJson.object().put("translation", CanonicalJson.object())), inputs).translation()).hasValueSatisfying(found -> assertThat(found.complete()).isFalse());
    }

    // ------------------------------------------------------------------

    private static ReportDefinition definition(CanonicalJson.Obj body) {
        return new com.finapp.accounting.testing.TestReportDefinition("TEST_BODY", 1, java.util.Set.of(), false, (snapshot, inputs) -> body);
    }

    private static RunParameters asking(ValuationRateSetId set) {
        return RunParameters.of(Map.of(), Optional.of(set.value()));
    }

    private ValuationRateSetId approved(Map<String, String> pinned) {
        Instant asOf = ValuationRateSet.asOf(FEBRUARY);
        ValuationRateSetId id = ValuationRateSetId.next(IDS);
        List<ValuationRate> rows = pinned.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new ValuationRate(CurrencyCode.of("EUR"), CurrencyCode.of(entry.getKey()), new BigDecimal(entry.getValue()), "reference.test", UUID.randomUUID(), asOf.minusSeconds(3_600), asOf.minusSeconds(3_599))).toList();
        sets.insert(UOW, new ValuationRateSetStore.NewSet(id, new ValuationRateSet(february, FEBRUARY, asOf, ValuationStaleness.standard(), rows), ACCOUNTANT, Optional.empty(), correlation()));
        assertThat(sets.decide(UOW, id, ValuationRateSetStatus.APPROVED, CONTROLLER, "the reference's")).isTrue();
        return id;
    }

    private ReportRuns runs() {
        return new ReportRuns(store, periodStore, mappings, ReportDefinitions.of(sheet, income, trialBalance), ReportRuns.Leases.STANDARD, (unitOfWork, record) -> audit.add(record),
                (unitOfWork, envelope, payload, mediaType) -> {
                    events.add(envelope);
                    payloads.add(payload);
                }, IDS, clock, ReportRuns.Provisional.STANDARD, ReportRuns.ApprovedSets.of(sets));
    }

    /** The desk's path: claim, then generate and complete the run this caller holds; otherwise the run as it stands. */
    private ReportRunStore.RunRow ask(ReportDefinition definition, RunParameters parameters) {
        ReportRuns.Claimed claimed = runs().claim(UOW, definition.code(), february, parameters, ACCOUNTANT, correlation());
        if (!claimed.mine()) {
            return claimed.run();
        }
        ReportRunStore.RunRow held = claimed.run();
        return runs().complete(UOW, held.id(), held.attempt(), generation.generate(UOW, definition, held.inputs()), ACCOUNTANT).run();
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }
}
