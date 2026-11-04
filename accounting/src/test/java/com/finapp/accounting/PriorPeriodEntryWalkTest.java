package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.reporting.BalanceSheetDefinition;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.GlActivityDefinition;
import com.finapp.accounting.reporting.IncomeStatementDefinition;
import com.finapp.accounting.reporting.PurposeReportDefinition;
import com.finapp.accounting.reporting.ReportDefinition;
import com.finapp.accounting.reporting.ReportInputs;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.StatementNotes;
import com.finapp.accounting.reporting.SuspenseDefinition;
import com.finapp.accounting.reporting.TrialBalanceDefinition;
import com.finapp.accounting.testing.InMemoryAccountingBreakStore;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.InMemoryJournal;
import com.finapp.accounting.testing.InMemoryPriorPeriodAdjustmentStore;
import com.finapp.accounting.testing.InMemoryPriorPeriodPostings;
import com.finapp.accounting.testing.InMemoryTrialBalanceReads;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An {@code ACCOUNTING}-origin entry walked through what reads it (`P14-TSK-013`; {@code PHASE_14_PLAN.md} section 13.5; {@code INV-ACC-04}, {@code -10}, {@code -12}): the month it corrects must not move, and the month it lands in must still close. The judges and the
 * definitions themselves, hermetic; {@code PriorPeriodAdjustmentDatabaseTest} walks the same over the real journal (owed, C5). The reads with nothing to judge are stated there: {@code ACCRUALS_COMPLETE} and {@code RECONCILIATION_DISCLOSURE} read {@code ledger.post:} scopes
 * ({@code LedgerFlooredPostings}), and the source proofs read purposes closed to this origin but for two: {@code MERCHANT_PAYABLE}, whose terms {@code MerchantPayableTermsTest} walks and whose {@code BNPL_THREE_WAY} leg compares only {@code bnpl.}-keyed lines, a line of
 * this origin carrying no key ({@code LedgerBnplProofReads}), and every projection, which holds because the entry posts like any other. Five of the eleven FINAL definitions read none of its five purposes, so they have nothing a control could move; the six that do have the control. `P14-TSK-016` added the two statements, which read every line.
 */
@DisplayName("an ACCOUNTING entry walked through the close and the reports (P14-TSK-013)")
class PriorPeriodEntryWalkTest {

    private static final YearMonth JANUARY = YearMonth.of(2026, 1);
    private static final YearMonth MARCH = YearMonth.of(2026, 3);
    private static final LocalDate LANDS_ON = MARCH.atDay(10);
    private static final Connection SNAPSHOT = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    private static final IdGenerator IDS = new IdGenerator(Clock.fixed(Instant.parse("2026-03-10T09:00:00Z"), ZoneOffset.UTC), new SecureRandom());
    private static final Instant CUT = Instant.parse("2026-04-05T09:00:00Z");
    private static final String FEES = "FEE_REVENUE/OPERATIONAL";
    private static final String WALLET = "CUSTOMER_WALLET/CUSTOMER";
    private static final String CHARGEBACKS = "CHARGEBACK_RECOVERABLE/OPERATIONAL";

    private final AccountingPeriodId january = AccountingPeriodId.next(IDS);
    private final AccountingPeriodId march = AccountingPeriodId.next(IDS);
    private final GlMappingVersionId version = GlMappingVersionId.next(IDS);
    private final InMemoryTrialBalanceReads reads = new InMemoryTrialBalanceReads()
            .version(version, ReferenceGlMapping.version(JANUARY))
            .period(new TrialBalanceReads.Period(january, JANUARY.atDay(1), JANUARY.atEndOfMonth(), PeriodStatus.CLOSED, Optional.of(version)))
            .period(new TrialBalanceReads.Period(march, MARCH.atDay(1), MARCH.atEndOfMonth(), PeriodStatus.LOCKED, Optional.of(version)))
            .post(JANUARY.atDay(20), CUT.minusSeconds(9000), "EUR", 2, 10_000, CHARGEBACKS, FEES);

    private ReportInputs inputsFor(AccountingPeriodId period, YearMonth month) {
        return new ReportInputs(period, month, 1, RunBasis.FINAL, Optional.of(Instant.EPOCH), Optional.of(version), Optional.of(1), CUT, RunParameters.none());
    }

    private String bytesOf(ReportDefinition definition, AccountingPeriodId period, YearMonth month) {
        return CanonicalJson.text(definition.generate(SNAPSHOT, inputsFor(period, month)));
    }

    /** The date the machine itself gives the ledger, so a machine dating into the corrected month is what moves the bytes below. */
    private static LocalDate theMachinesDate() {
        Connection uow = SNAPSHOT;
        CorrelationId flow = CorrelationId.of("flow-1");
        Actor accountant = new Actor("accountant-1", ActorType.CUSTOMER);
        InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
        for (YearMonth month : List.of(JANUARY, JANUARY.plusMonths(1), MARCH)) {
            periods.insertIfAbsent(uow, new AccountingPeriodStore.NewPeriod(AccountingPeriodId.next(IDS), month, flow));
        }
        periods.closeForTest(JANUARY);
        InMemoryGlMappingStore mappings = new InMemoryGlMappingStore();
        GlMappingVersionId active = GlMappingVersionId.next(IDS);
        mappings.insertProposal(null, new GlMappingStore.NewProposal(active, 1, ReferenceGlMapping.version(JANUARY), accountant, "the reference", flow, UUID.randomUUID()));
        assertThat(mappings.decide(null, active, GlMappingVersionStatus.ACTIVE, new Actor("controller-1", ActorType.CUSTOMER), "approved")).isTrue();
        InMemoryPriorPeriodPostings ledger = new InMemoryPriorPeriodPostings();
        List<PriorPeriodPostings.Line> lines = List.of(new PriorPeriodPostings.Line(ledger.account("FEE_REVENUE", "OPERATIONAL"), Side.DEBIT, Money.ofMinorUnits(1_500, CurrencyCode.of("EUR"))),
                new PriorPeriodPostings.Line(ledger.account("CUSTOMER_WALLET", "CUSTOMER"), Side.CREDIT, Money.ofMinorUnits(1_500, CurrencyCode.of("EUR"))));
        PriorPeriodAdjustments machine = new PriorPeriodAdjustments(periods, new InMemoryPriorPeriodAdjustmentStore(periods), ledger, mappings, (unitOfWork, record) -> { }, (unitOfWork, envelope, payload, mediaType) -> { }, IDS,
                Clock.fixed(LANDS_ON.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
        PriorPeriodAdjustments.Acted proposed = machine.propose(uow, periods.month(JANUARY).orElseThrow().id(), 1, lines, accountant, "a fee was booked twice", flow);
        return ledger.proposal(proposed.proposal()).date();
    }

    /** The prior-period adjustment's entry: today's date for both, on two open purposes. */
    private void theCorrectionLands(LocalDate on) {
        reads.post(on, CUT.minusSeconds(60), "EUR", 2, 1_500, FEES, WALLET);
    }

    @Test
    @DisplayName("the corrected month's FINAL runs do not move: all eleven definitions' bytes identical after the entry lands on the date the machine gives it; and the control, for the six that read its purposes, the same entry dated into the month, moves them - so the comparison can see it")
    void theCorrectedMonthsBytesDoNotMove() {
        StatementNotes notes = new StatementNotes((snapshot, period) -> new SuspenseAsOfCutoff.Register(period.atEndOfMonth(), Instant.EPOCH, List.of()),
                (snapshot, period) -> new ReconciliationAsOfCutoff.Standing(period.atEndOfMonth(), Instant.EPOCH, List.of(), List.of()),
                (snapshot, month) -> new PriorPeriodCorrections.Corrections(List.of(), List.of(), 0),
                (snapshot, period, revision) -> Optional.of(new CloseRecordReads.CloseRecord(UUID.nameUUIDFromBytes(new byte[] {1}), revision, Instant.EPOCH, UUID.nameUUIDFromBytes(new byte[] {2}), version.value(), UUID.nameUUIDFromBytes(new byte[] {3}), 0, 0, Instant.EPOCH, Instant.EPOCH, List.of(), List.of())));
        ValuationRateReads noRates = (snapshot, id) -> Optional.empty();
        List<ReportDefinition> definitions = List.of(new TrialBalanceDefinition(reads), new GlActivityDefinition(reads, reads.counterparties()), PurposeReportDefinition.revenue(reads),
                PurposeReportDefinition.receivablesPayables(reads), new BalanceSheetDefinition(reads, reads.counterparties(), noRates, notes), new IncomeStatementDefinition(reads, reads.counterparties(), noRates, notes));
        List<ReportDefinition> readingNoneOfItsPurposes = List.of(PurposeReportDefinition.settlementPositions(reads, reads.counterparties()), PurposeReportDefinition.fxResults(reads),
                PurposeReportDefinition.loanPortfolio(reads), PurposeReportDefinition.bnplExposure(reads), new SuspenseDefinition(reads, (snapshot, period) -> new SuspenseAsOfCutoff.Register(period.atEndOfMonth(), Instant.EPOCH, List.of())));
        Map<String, String> issued = new java.util.HashMap<>();
        definitions.forEach(definition -> issued.put(definition.code(), bytesOf(definition, january, JANUARY)));
        readingNoneOfItsPurposes.forEach(definition -> issued.put(definition.code(), bytesOf(definition, january, JANUARY)));
        assertThat(issued).as("every FINAL definition").hasSize(11);

        theCorrectionLands(theMachinesDate());

        definitions.forEach(definition -> assertThat(bytesOf(definition, january, JANUARY)).as(definition.code()).isEqualTo(issued.get(definition.code())));
        readingNoneOfItsPurposes.forEach(definition -> assertThat(bytesOf(definition, january, JANUARY)).as(definition.code()).isEqualTo(issued.get(definition.code())));

        // The control: F1 itself, the same entry dated into the closed month.
        theCorrectionLands(JANUARY.atDay(25));
        definitions.forEach(definition -> assertThat(bytesOf(definition, january, JANUARY)).as(definition.code() + " sees an entry dated into the month").isNotEqualTo(issued.get(definition.code())));
    }

    @Test
    @DisplayName("the month it lands in: the trial balance takes it, balanced, every line mapped; the revenue report carries the correction in that month and no other")
    void theMonthItLandsInCarriesIt() {
        String before = bytesOf(new TrialBalanceDefinition(reads), march, MARCH);

        theCorrectionLands(LANDS_ON);

        TrialBalanceDefinition definition = new TrialBalanceDefinition(reads);
        assertThat(bytesOf(definition, march, MARCH)).isNotEqualTo(before);
        com.finapp.accounting.reporting.TrialBalance balance = definition.trialBalance(SNAPSHOT, inputsFor(march, MARCH));
        assertThat(balance.fullyMapped()).as("GL_EQUALS_LEDGER's other half: no line unmapped under the pin").isTrue();
        assertThat(balance.passes()).as("LEDGER_TRIAL_BALANCE's verdict").isTrue();
    }

    @Test
    @DisplayName("RULE_CONFORMANCE: the entry conforms, on every open purpose either side, at any posting date; a closed purpose is the mismatch it would be, and without the rule the month could not close - the catalogue's reason to carry it")
    void ruleConformance() {
        RuleConformance conformance = new RuleConformance(PostingRuleRegister.CURRENT);
        for (String debit : PostingRuleCatalogue.OPEN_TO_FREE_ADJUSTMENT) {
            for (String credit : PostingRuleCatalogue.OPEN_TO_FREE_ADJUSTMENT) {
                assertThat(conformance.judge(entry(LANDS_ON, debit, credit))).as(debit + " / " + credit).isEmpty();
            }
        }
        assertThat(conformance.judge(entry(LocalDate.of(2000, 1, 1), "FEE_REVENUE", "CUSTOMER_WALLET"))).as("no posting date predates the rule").isEmpty();
        assertThat(conformance.judge(entry(LANDS_ON, "LOAN_PRINCIPAL", "CUSTOMER_WALLET"))).singleElement()
                .satisfies(finding -> assertThat(finding.type()).isEqualTo(BreakType.SHAPE_MISMATCH));

        List<PostingRule> withoutIt = PostingRuleRegister.CURRENT.rules().stream().filter(rule -> !rule.namespace().equals("ACCOUNTING")).toList();
        RuleConformance.Tally tally = new RuleConformance(new PostingRuleRegister(PostingRuleRegister.CATALOGUE_VERSION, withoutIt)).tally(Map.of(), 100);
        tally.add(entry(LANDS_ON, "FEE_REVENUE", "CUSTOMER_WALLET"));
        assertThat(tally.findings()).singleElement().satisfies(finding -> assertThat(finding.type()).isEqualTo(BreakType.UNREGISTERED_POSTING));
    }

    @Test
    @DisplayName("C3's floor cannot reach it: the rule is refused at approval, and the constructor refuses a floored adjustment outright")
    void theFloorCannotReachIt() {
        PostingRule rule = PostingRuleRegister.CURRENT.rule("ACC-PRIOR-PERIOD", 1).orElseThrow();
        assertThat(rule.cutoff()).isEqualTo(CutoffReaction.REFUSED_AT_APPROVAL);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PostingRule(rule.id(), 2, rule.effectiveFrom(), rule.type(), rule.namespace(), rule.sourceModule(), rule.posters(), rule.economicEvent(),
                rule.authoritativeReference(), rule.dating(), CutoffReaction.FLOORED_AT_FIRST_OPEN, rule.lines(), rule.catalogue())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only a POSTING");
    }

    @Test
    @DisplayName("every purpose it may touch is one a version total over the chart maps: so what the proposal found mapped, the pin at the month's lock cannot lose")
    void everyPurposeItMayTouchIsMapped() {
        Map<String, MappingRule> rules = ReferenceGlMapping.version(JANUARY).rulesByKey();
        Set<String> mappedPurposes = rules.values().stream().map(MappingRule::purpose).collect(java.util.stream.Collectors.toSet());
        assertThat(mappedPurposes).containsAll(PostingRuleCatalogue.OPEN_TO_FREE_ADJUSTMENT);
    }

    @Test
    @DisplayName("a break's correction: a break of the corrected month is resolved by reference to the adjustment's entry, its date after the subject's; nothing posts from a break, and no new link is needed")
    void aBreakIsResolvedByTheAdjustmentsEntry() {
        InMemoryAccountingBreakStore store = new InMemoryAccountingBreakStore();
        InMemoryJournal journal = new InMemoryJournal();
        AccountingBreaks breaks = new AccountingBreaks(store, journal, (uow, record) -> { }, (uow, envelope, payload, mediaType) -> { }, IDS, Clock.fixed(Instant.parse("2026-03-10T09:00:00Z"), ZoneOffset.UTC));
        UUID subject = UUID.randomUUID();
        UUID correcting = UUID.randomUUID();
        journal.post(new JournalReads.Entry(subject, "POSTING", JANUARY.atDay(20), JANUARY.atDay(20), Optional.empty(), List.of()));
        journal.post(new JournalReads.Entry(correcting, "ADJUSTMENT", LANDS_ON, LANDS_ON, Optional.empty(), List.of()));
        BreakFinding finding = new BreakFinding(CloseCheckCode.RULE_CONFORMANCE, BreakType.SHAPE_MISMATCH, "shape:" + subject, "{\"lines\":2}", "posting-rules:v3");
        assertThat(breaks.raise(SNAPSHOT, JANUARY, finding, Actor.SYSTEM, CorrelationId.of("flow-1"))).isTrue();
        AccountingBreakId id = store.rows().get(0).id();

        AccountingBreaks.Acted resolved = breaks.resolve(SNAPSHOT, id, new Actor("controller-1", ActorType.CUSTOMER), correcting, "corrected by the prior-period adjustment", CorrelationId.of("flow-2"));

        assertThat(resolved.status()).isEqualTo(BreakStatus.RESOLVED);
        assertThat(store.rows().get(0).resolvingEntry()).contains(correcting);
    }

    private static JournalShapes.EntryShape entry(LocalDate on, String debit, String credit) {
        return new JournalShapes.EntryShape(UUID.randomUUID(), EntryType.ADJUSTMENT, on, Optional.of("ACCOUNTING"), List.of(
                new JournalShapes.ShapeLine(0, debit, Side.DEBIT, "EUR", 2, BigInteger.TEN),
                new JournalShapes.ShapeLine(1, credit, Side.CREDIT, "EUR", 2, BigInteger.TEN)));
    }
}
