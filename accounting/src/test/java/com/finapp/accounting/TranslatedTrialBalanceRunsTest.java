package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.Translation;
import com.finapp.accounting.reporting.TrialBalanceDefinition;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.InMemoryReportRunStore;
import com.finapp.accounting.testing.InMemoryTrialBalanceReads;
import com.finapp.accounting.testing.InMemoryValuationRateSetStore;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.accounting.testing.TestReportDefinition;
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
import java.util.Set;
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
 * The translated trial balance through `P14-TSK-008`'s machine (`P14-TSK-015`; ADR-0116 decision 3; {@code INV-ACC-04},
 * {@code INV-ACC-11}): how a run asks - by naming its period's approved set - and what is refused. Plain beside translated, two
 * questions and two runs, the plain one the close's and never in need of a set; a FINAL translated run with no approved set
 * refused, whatever it names, nothing written; pinned and regenerating byte-identical when it has one; ten claimers, one run;
 * PROVISIONAL refused while its open period has no approved set, and reading its own set after an unlock left one approved;
 * nothing posted. Hermetic: the stores and the books in memory; the database's own proof is in
 * {@code ValuationRateSetDatabaseTest} (owed, C5).
 */
@DisplayName("the translated trial balance as a report run (P14-TSK-015)")
class TranslatedTrialBalanceRunsTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException("the in-memory stores never touch the connection");
            });
    private static final YearMonth FEBRUARY = YearMonth.of(2096, 2);
    private static final YearMonth MARCH = YearMonth.of(2096, 3);
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final Actor CAPTURER = new Actor("capturer-1", ActorType.CUSTOMER);
    private static final Actor CONTROLLER = new Actor("controller-1", ActorType.CUSTOMER);
    private static final String CASH = "CASH_AT_BANK/OPERATIONAL";
    private static final String WALLET = "CUSTOMER_WALLET/CUSTOMER";
    private static final String CODE = TrialBalanceDefinition.CODE;

    private final ReportRunsTest.MovableClock clock = new ReportRunsTest.MovableClock(
            Instant.parse("2096-03-10T09:00:00Z"));
    private final InMemoryAccountingPeriodStore periodStore = new InMemoryAccountingPeriodStore();
    private final InMemoryGlMappingStore mappings = new InMemoryGlMappingStore();
    private final InMemoryReportRunStore store = new InMemoryReportRunStore(clock, periodStore);
    private final InMemoryValuationRateSetStore sets = new InMemoryValuationRateSetStore().watching(periodStore);
    private final InMemoryTrialBalanceReads books = new InMemoryTrialBalanceReads();
    private final ValuationRateReads rates = (snapshot, id) -> sets.find(snapshot, ValuationRateSetId.of(id))
            .map(row -> new ValuationRateReads.PinnedSet(row.id().value(), row.period(), row.month(), row.status(),
                    row.asOf(), row.maxAgeHours(), sets.rates(snapshot, row.id())));
    private final TrialBalanceDefinition definition = new TrialBalanceDefinition(books, rates);
    private final List<AuditRecord> audit = Collections.synchronizedList(new ArrayList<>());
    private final List<EventEnvelope> events = Collections.synchronizedList(new ArrayList<>());
    private final List<byte[]> payloads = Collections.synchronizedList(new ArrayList<>());
    private final ReportGeneration generation = new ReportGeneration((snapshot, id) -> periodStore.find(snapshot, id)
            .map(row -> new ReportGeneration.PeriodState(row.status(), row.lockedAt())), Duration.ofMinutes(4), () -> 0L);
    private GlMappingVersionId version;
    private AccountingPeriodId february;
    private AccountingPeriodId march;

    @BeforeEach
    void februaryLockedMarchOpen() {
        version = GlMappingVersionId.next(IDS);
        mappings.insertProposal(UOW, new GlMappingStore.NewProposal(version, 1,
                ReferenceGlMapping.version(YearMonth.of(2096, 1)), new Actor("proposer", ActorType.CUSTOMER),
                "the reference chart", correlation(), UUID.randomUUID()));
        mappings.decide(UOW, version, GlMappingVersionStatus.ACTIVE, new Actor("approver", ActorType.CUSTOMER), "ok");
        february = AccountingPeriodId.next(IDS);
        march = AccountingPeriodId.next(IDS);
        periodStore.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(february, FEBRUARY, correlation()));
        periodStore.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(march, MARCH, correlation()));
        periodStore.markLocked(UOW, february, 1, ACCOUNTANT, Optional.of(new AccountingPeriodStore.Pin(version, 1)));
        Instant past = clock.instant().minusSeconds(86_400 * 10);
        books.version(version, mappings.content(UOW, version))
                .period(new TrialBalanceReads.Period(february, FEBRUARY.atDay(1), FEBRUARY.atEndOfMonth(),
                        PeriodStatus.LOCKED, Optional.of(version)))
                .period(new TrialBalanceReads.Period(march, MARCH.atDay(1), MARCH.atEndOfMonth(), PeriodStatus.OPEN,
                        Optional.empty()))
                .post(FEBRUARY.atDay(1), past, "EUR", 2, 2_500, CASH, WALLET)
                .post(FEBRUARY.atDay(3), past, "USD", 2, 1_001, CASH, WALLET)
                .post(FEBRUARY.atDay(4), past, "JPY", 0, 123_456, CASH, WALLET)
                .post(FEBRUARY.atEndOfMonth(), past, "BHD", 3, 1_501, WALLET, CASH)
                .post(MARCH.atDay(2), past, "GBP", 2, 1_200, CASH, WALLET);
    }

    @Test
    @DisplayName("the close's run (P14-TSK-011's question): RunParameters.none() claims TRIAL_BALANCE with no set in the world,"
            + " completes FINAL, reads no rate, its event and audit record naming none; the same bytes as the definition"
            + " with no way to the rates")
    void thePlainRunNeverNeedsASet() {
        ReportRunStore.RunRow run = ask(february, RunParameters.none(), runs());
        assertThat(run.status()).isEqualTo(RunStatus.FINAL);
        assertThat(run.definitionVersion()).isEqualTo(TrialBalanceDefinition.VERSION).isEqualTo(1);
        assertThat(run.valuationRateSetId()).isEmpty();
        String content = content(run);
        assertThat(content).contains("\"passes\":true").contains("\"valuationRateSet\":null")
                .doesNotContain("\"translation\"").doesNotContain("\"translated\"");
        assertThat(content).isEqualTo(generation.generate(UOW, new TrialBalanceDefinition(books), run.inputs()).content());
        assertThat(new String(payloads.get(0), StandardCharsets.UTF_8)).doesNotContain("valuationRateSet");
        assertThat(audit.get(0).changeSummary().orElseThrow()).doesNotContain("valuationRateSet");
    }

    @Test
    @DisplayName("the entry's refusal: no approved set, no FINAL translated run - naming an unknown set, a proposed one, a"
            + " rejected one or another period's approved one, the one refusal, with nothing written")
    void aFinalTranslatedRunWithNoApprovedSetIsRefused() {
        ValuationRateSetId proposed = captured(february);
        ValuationRateSetId rejected = captured(february);
        sets.decide(UOW, rejected, ValuationRateSetStatus.REJECTED, CONTROLLER, "wrong");
        for (UUID named : List.of(UUID.randomUUID(), proposed.value(), rejected.value())) {
            assertThatThrownBy(() -> runs().claim(UOW, CODE, february, asking(named), ACCOUNTANT, correlation()))
                    .isInstanceOf(ReportRuns.ValuationSetNotApproved.class)
                    .hasMessageContaining(FEBRUARY.toString());
        }
        // Another period's approved set: February closed, March locked - one period in close at a time.
        periodStore.closeForTest(FEBRUARY);
        periodStore.markLocked(UOW, march, 1, ACCOUNTANT, Optional.of(new AccountingPeriodStore.Pin(version, 1)));
        ValuationRateSetId others = approved(march);
        assertThatThrownBy(() -> runs().claim(UOW, CODE, february, asking(others.value()), ACCOUNTANT, correlation()))
                .isInstanceOf(ReportRuns.ValuationSetNotApproved.class);
        assertThat(store.all()).isEmpty();
        assertThat(audit).isEmpty();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("a machine with no way to the approved sets refuses every run naming one: fail closed")
    void noWayToTheSetsFailsClosed() {
        ValuationRateSetId set = approved(february);
        assertThatThrownBy(() -> plainMachine().claim(UOW, CODE, february, asking(set.value()), ACCOUNTANT, correlation()))
                .isInstanceOf(ReportRuns.ValuationSetNotApproved.class);
        assertThat(store.all()).isEmpty();
    }

    @Test
    @DisplayName("a FINAL translated run pins the approved set and regenerates byte-identical: the set in its key, header, row"
            + " and record, originals beside the translation, the verdict at the close's path; the next ask answers it")
    void aFinalTranslatedRunPinsItsSetAndRegeneratesIdentical() {
        ValuationRateSetId set = approved(february);
        ReportRunStore.RunRow run = ask(february, asking(set.value()), runs());
        assertThat(run.basis()).isEqualTo(RunBasis.FINAL);
        assertThat(run.status()).isEqualTo(RunStatus.FINAL);
        assertThat(run.valuationRateSetId()).contains(set.value());
        assertThat(run.key().parametersHash()).isEqualTo(asking(set.value()).hash())
                .isNotEqualTo(RunParameters.none().hash());
        String content = content(run);
        assertThat(content)
                .contains("\"valuationRateSet\":\"" + set.value() + "\"")
                .contains("\"translation\":{").contains("\"translated\":{")
                .contains("\"complete\":true").contains("\"rounding\":\"HALF_EVEN\"")
                .contains("\"translationDifference\":")
                .contains("\"passes\":true");
        ReportGeneration.Generated again = generation.generate(UOW, definition, run.inputs());
        assertThat(again.content()).isEqualTo(content);
        assertThat(run.contentSha256()).contains(again.sha256());
        assertThat(ask(february, asking(set.value()), runs()).id()).as("one FINAL run per question").isEqualTo(run.id());
        assertThat(new String(payloads.get(0), StandardCharsets.UTF_8)).contains(set.value().toString());
        assertThat(audit.get(0).changeSummary().orElseThrow()).contains("valuationRateSet=" + set.value());
    }

    @Test
    @DisplayName("plain and translated are two questions and two runs of one definition version: the plain run's bytes the same"
            + " whichever came first, the translated verdict the plain one's")
    void plainAndTranslatedAreTwoRuns() {
        ValuationRateSetId set = approved(february);
        ReportRunStore.RunRow translated = ask(february, asking(set.value()), runs());
        ReportRunStore.RunRow plain = ask(february, RunParameters.none(), runs());
        assertThat(plain.id()).isNotEqualTo(translated.id());
        assertThat(plain.definitionVersion()).isEqualTo(translated.definitionVersion());
        assertThat(plain.valuationRateSetId()).isEmpty();
        assertThat(store.all()).hasSize(2);
        assertThat(content(plain)).isEqualTo(generation.generate(UOW, new TrialBalanceDefinition(books),
                plain.inputs()).content());
        assertThat(ask(february, RunParameters.none(), runs()).id()).isEqualTo(plain.id());
    }

    @Test
    @DisplayName("ten claimers of the translated question, on ten threads: one run born, every claimer answering it")
    void tenClaimersBirthOneTranslatedRun() throws Exception {
        ValuationRateSetId set = approved(february);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ReportRuns.Claimed>> racing = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                racing.add(pool.submit(() -> {
                    start.await();
                    return runs().claim(UOW, CODE, february, asking(set.value()), ACCOUNTANT, correlation());
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
    @DisplayName("PROVISIONAL, the recorded answer: an open period has no approved set, so its translated run is refused -"
            + " never translated at another period's rates; asked plain it answers")
    void aProvisionalTranslatedRunIsRefusedWhileItsPeriodHasNoApprovedSet() {
        ValuationRateSetId februarys = approved(february);
        assertThatThrownBy(() -> runs().claim(UOW, CODE, march, asking(februarys.value()), ACCOUNTANT, correlation()))
                .isInstanceOf(ReportRuns.ValuationSetNotApproved.class).hasMessageContaining(MARCH.toString());
        assertThatThrownBy(() -> runs().claim(UOW, CODE, march, asking(UUID.randomUUID()), ACCOUNTANT, correlation()))
                .isInstanceOf(ReportRuns.ValuationSetNotApproved.class);
        assertThat(store.all()).isEmpty();
        assertThat(ask(march, RunParameters.none(), runs()).status()).isEqualTo(RunStatus.PROVISIONAL);
    }

    @Test
    @DisplayName("an unlock leaves an approved set approved, its rates the period end's: the period's PROVISIONAL translated run"
            + " reads its own set, labelled by the basis and the cut")
    void aPeriodUnlockedAfterApprovalIsTranslatedProvisionalAtItsOwnSet() {
        ValuationRateSetId set = approved(february);
        periodStore.proposeUnlock(UOW, february, periodStore.month(FEBRUARY).orElseThrow().revision(), ACCOUNTANT, "reopen");
        periodStore.approveUnlock(UOW, february, periodStore.month(FEBRUARY).orElseThrow().revision(), CONTROLLER, "reopen");
        assertThat(periodStore.month(FEBRUARY).orElseThrow().status()).isEqualTo(PeriodStatus.OPEN);
        ReportRunStore.RunRow run = ask(february, asking(set.value()), runs());
        assertThat(run.basis()).isEqualTo(RunBasis.PROVISIONAL);
        assertThat(run.status()).isEqualTo(RunStatus.PROVISIONAL);
        assertThat(run.valuationRateSetId()).contains(set.value());
        assertThat(content(run)).contains("\"basis\":\"PROVISIONAL\"").contains("\"cut\":\"" + run.cutAt() + "\"")
                .contains("\"translation\":{");
    }

    @Test
    @DisplayName("the second rank: a run whose set is not its period's approved one in the snapshot is not issued, whatever got"
            + " it past the claim - the definition refuses it, as it refuses a FINAL run off its period's pin")
    void aSetNotApprovedInTheSnapshotIsNotIssued() {
        ValuationRateSetId set = approved(february);
        ReportRunStore.RunRow run = runs().claim(UOW, CODE, february, asking(set.value()), ACCOUNTANT, correlation()).run();
        ValuationRateReads rejected = (snapshot, id) -> rates.set(snapshot, id).map(row ->
                new ValuationRateReads.PinnedSet(row.id(), row.period(), row.month(), ValuationRateSetStatus.REJECTED,
                        row.asOf(), row.maxAgeHours(), row.rates()));
        assertThatThrownBy(() -> generation.generate(UOW, new TrialBalanceDefinition(books, rejected), run.inputs()))
                .isInstanceOf(Translation.NotPinned.class);
    }

    @Test
    @DisplayName("the ask's three shapes: a definition taking no set refuses one; one that may take it answers either way; one"
            + " that must have it refuses none - and still only its period's approved set")
    void theAsksThreeShapes() {
        ValuationRateSetId set = approved(february);
        RunParameters named = asking(set.value());
        TestReportDefinition refuses = new TestReportDefinition("TEST_REFUSES", 1, Set.of(), false,
                TestReportDefinition::echoOf);
        TestReportDefinition may = new TestReportDefinition("TEST_MAY", 1, Set.of(), false,
                TestReportDefinition::echoOf).takingARateSet();
        TestReportDefinition must = new TestReportDefinition("TEST_MUST", 1, Set.of(), true, TestReportDefinition::echoOf);
        ReportRuns machine = machine(ReportDefinitions.of(refuses, may, must));

        assertThatThrownBy(() -> machine.claim(UOW, "TEST_REFUSES", february, named, ACCOUNTANT, correlation()))
                .isInstanceOf(ReportRuns.ParametersInvalid.class).hasMessageContaining("takes no valuation rate set");
        assertThat(machine.claim(UOW, "TEST_REFUSES", february, RunParameters.none(), ACCOUNTANT, correlation()).born()).isTrue();

        assertThat(machine.claim(UOW, "TEST_MAY", february, named, ACCOUNTANT, correlation()).born()).isTrue();
        assertThat(machine.claim(UOW, "TEST_MAY", february, RunParameters.none(), ACCOUNTANT, correlation()).born()).isTrue();

        assertThatThrownBy(() -> machine.claim(UOW, "TEST_MUST", february, RunParameters.none(), ACCOUNTANT, correlation()))
                .isInstanceOf(ReportRuns.ParametersInvalid.class).hasMessageContaining("needs a valuation rate set");
        assertThat(machine.claim(UOW, "TEST_MUST", february, named, ACCOUNTANT, correlation()).born()).isTrue();
        assertThatThrownBy(() -> machine.claim(UOW, "TEST_MUST", february, asking(UUID.randomUUID()), ACCOUNTANT,
                correlation())).isInstanceOf(ReportRuns.ValuationSetNotApproved.class);
    }

    @Test
    @DisplayName("nothing is posted (C7 A): a translated run's events are the run's completion and its records the run's; the"
            + " books hold exactly the lines they held")
    void nothingIsPosted() {
        ValuationRateSetId set = approved(february);
        int lines = books.lines().size();
        ask(february, asking(set.value()), runs());
        assertThat(books.lines()).hasSize(lines);
        assertThat(events).extracting(EventEnvelope::eventType).containsExactly(ReportRuns.COMPLETED_EVENT);
        assertThat(audit).extracting(record -> record.operation().code()).containsExactly("accounting.ReportGenerated");
    }

    // ------------------------------------------------------------------

    private static RunParameters asking(UUID set) {
        return RunParameters.of(Map.of(), Optional.of(set));
    }

    private ValuationRateSetId approved(AccountingPeriodId period) {
        ValuationRateSetId set = captured(period);
        assertThat(sets.decide(UOW, set, ValuationRateSetStatus.APPROVED, CONTROLLER, "the reference's")).isTrue();
        return set;
    }

    private ValuationRateSetId captured(AccountingPeriodId period) {
        YearMonth month = periodStore.find(UOW, period).orElseThrow().month();
        Instant asOf = ValuationRateSet.asOf(month);
        ValuationRateSetId id = ValuationRateSetId.next(IDS);
        List<ValuationRate> rates = Map.of("GBP", "0.7012", "USD", "1.1561", "JPY", "131.25", "BHD", "0.4358123456")
                .entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new ValuationRate(CurrencyCode.of("EUR"), CurrencyCode.of(entry.getKey()),
                        new BigDecimal(entry.getValue()), "reference.test", UUID.randomUUID(), asOf.minusSeconds(3_600),
                        asOf.minusSeconds(3_599)))
                .toList();
        sets.insert(UOW, new ValuationRateSetStore.NewSet(id, new ValuationRateSet(period, month, asOf,
                ValuationStaleness.standard(), rates), CAPTURER, Optional.empty(), correlation()));
        return id;
    }

    private ReportRuns runs() {
        return machine(ReportDefinitions.of(definition));
    }

    private ReportRuns machine(ReportDefinitions definitions) {
        return new ReportRuns(store, periodStore, mappings, definitions, ReportRuns.Leases.STANDARD,
                (unitOfWork, record) -> audit.add(record),
                (unitOfWork, envelope, payload, mediaType) -> {
                    events.add(envelope);
                    payloads.add(payload);
                },
                IDS, clock, ReportRuns.Provisional.STANDARD, ReportRuns.ApprovedSets.of(sets));
    }

    private ReportRuns plainMachine() {
        return new ReportRuns(store, periodStore, mappings, ReportDefinitions.of(definition), ReportRuns.Leases.STANDARD,
                (unitOfWork, record) -> audit.add(record),
                (unitOfWork, envelope, payload, mediaType) -> events.add(envelope), IDS, clock);
    }

    /** The desk's path: claim, then generate and complete the run this caller holds; otherwise the run as it stands. */
    private ReportRunStore.RunRow ask(AccountingPeriodId period, RunParameters parameters, ReportRuns runs) {
        ReportRuns.Claimed claimed = runs.claim(UOW, CODE, period, parameters, ACCOUNTANT, correlation());
        if (!claimed.mine()) {
            return claimed.run();
        }
        ReportRunStore.RunRow held = claimed.run();
        return runs.complete(UOW, held.id(), held.attempt(), generation.generate(UOW, definition, held.inputs()),
                ACCOUNTANT).run();
    }

    private String content(ReportRunStore.RunRow run) {
        return store.content(UOW, run.id()).orElseThrow().text();
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }
}
