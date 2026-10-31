package com.finapp.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.MappingRule;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.Side;
import com.finapp.accounting.testing.InMemoryJournal;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.sharedkernel.id.IdGenerator;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The GL read model's fold (`P14-TSK-007`; {@code INV-ACC-02}, {@code INV-ACC-06}, {@code INV-ACC-07}) over a stand-in
 * journal answering the port as {@code app}'s adapter does: every line maps exactly once, GL = ledger per currency, every
 * figure equals the sum of its drill-down lines and names its version, an unmapped line is counted and never dropped.
 * The hermetic twins of {@code GlReadModelDatabaseTest}'s cases (owed, C5) - the entry's probe, a purpose double
 * counted, turns {@link #glEqualsLedgerPerCurrency} red here as it would the database case.
 */
@DisplayName("the GL read model: the fold and the drill-down (P14-TSK-007)")
class GeneralLedgerTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
    private static final Connection SNAPSHOT = null;
    private static final GlMappingVersionId VERSION_ID = GlMappingVersionId.next(IDS);

    private final InMemoryJournal journal = new InMemoryJournal();
    private final GeneralLedger ledger = new GeneralLedger(journal);

    @Test
    @DisplayName("every journal line in the period maps exactly once: the figures' and the unmapped lines together are the"
            + " ledger's lines per currency, none unmapped under a total version, every component under its own rule")
    void everyLineMapsOnce() {
        postNovember();
        PinnedMapping mapping = pinned(ReferenceGlMapping.version());
        GlBalances balances = ledger.balances(SNAPSHOT, mapping, Optional.empty());

        assertThat(balances.unmapped()).isEmpty();
        Map<String, Long> figureLines = balances.figures().stream()
                .collect(Collectors.groupingBy(GlFigure::currency, TreeMap::new, Collectors.summingLong(GlFigure::lines)));
        assertThat(figureLines).isEqualTo(linesPerCurrency());
        Map<String, MappingRule> rules = mapping.content().rulesByKey();
        Set<String> seen = new HashSet<>();
        for (GlFigure figure : balances.figures()) {
            long componentLines = 0;
            for (GlFigure.Component component : figure.components()) {
                assertThat(rules.get(component.key()).glAccountCode())
                        .as("component %s folds under its own rule", component.key())
                        .isEqualTo(figure.account().code());
                assertThat(seen.add(component.key() + "|" + component.counterparty() + "|" + figure.currency()))
                        .as("a component appears in exactly one figure").isTrue();
                componentLines += component.lines();
            }
            assertThat(componentLines).as("a figure is the sum of its components").isEqualTo(figure.lines());
        }
    }

    @Test
    @DisplayName("GL = ledger per currency over the same posting-date range: debits, credits and lines, against the"
            + " ledger's own totals and against the test's own sums of the lines")
    void glEqualsLedgerPerCurrency() {
        postNovember();
        GlBalances balances = ledger.balances(SNAPSHOT, pinned(ReferenceGlMapping.version()), Optional.empty());

        assertThat(balances.agreement()).extracting(GlBalances.Agreement::currency).containsExactly("EUR", "JPY", "USD");
        assertThat(balances.agrees()).isTrue();
        for (GlBalances.Agreement agreement : balances.agreement()) {
            assertThat(agreement.agrees()).as("GL = ledger in %s", agreement.currency()).isTrue();
            BigDecimal debits = sum(agreement.currency(), Side.DEBIT);
            BigDecimal credits = sum(agreement.currency(), Side.CREDIT);
            assertThat(agreement.glDebits()).isEqualByComparingTo(debits).isEqualByComparingTo(agreement.ledgerDebits());
            assertThat(agreement.glCredits()).isEqualByComparingTo(credits)
                    .isEqualByComparingTo(agreement.ledgerCredits());
            assertThat(agreement.glLines()).isEqualTo(agreement.ledgerLines());
            assertThat(agreement.unmappedLines()).isZero();
            BigDecimal figureDebits = balances.figures().stream().filter(f -> f.currency().equals(agreement.currency()))
                    .map(GlFigure::debits).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal figureCredits = balances.figures().stream().filter(f -> f.currency().equals(agreement.currency()))
                    .map(GlFigure::credits).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(figureDebits).as("the figures sum to the GL side").isEqualByComparingTo(agreement.glDebits());
            assertThat(figureCredits).isEqualByComparingTo(agreement.glCredits());
            assertThat(figureDebits).as("double entry survives the fold").isEqualByComparingTo(figureCredits);
        }
        GlFigure wallets = balances.figure("2000", "EUR").orElseThrow();
        assertThat(wallets.debits()).as("two fees, the 31 October and 1 December ones outside the month")
                .isEqualByComparingTo("3.25");
        assertThat(wallets.credits()).isEqualByComparingTo("100.00");
        assertThat(wallets.balance()).as("a liability's net on its credit side").isEqualByComparingTo("96.75");
        GlFigure loans = balances.figure("1300", "EUR").orElseThrow();
        assertThat(loans.components()).extracting(GlFigure.Component::purpose)
                .as("two purposes, one GL account").containsExactly("LOAN_PRINCIPAL", "LOAN_PRINCIPAL_DUE");
        assertThat(loans.balance()).as("an asset's net on its debit side").isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("every figure equals the sum of its drill-down lines: paged two by two, the pages disjoint, in order,"
            + " their lines exactly the figure's count and sides; the last page says there is no next")
    void aFigureDrillsToItsLines() {
        postNovember();
        PinnedMapping mapping = pinned(ReferenceGlMapping.version());
        GlBalances balances = ledger.balances(SNAPSHOT, mapping, Optional.empty());
        assertThat(balances.figures()).isNotEmpty();
        for (GlFigure figure : balances.figures()) {
            BigDecimal debits = BigDecimal.ZERO;
            BigDecimal credits = BigDecimal.ZERO;
            long lines = 0;
            Set<String> served = new HashSet<>();
            Optional<JournalReads.LineCursor> after = Optional.empty();
            int pages = 0;
            do {
                GlActivity page = ledger.activity(SNAPSHOT, mapping, figure.account().code(), figure.currency(), after, 2);
                assertThat(page.figure()).as("each page carries its figure").isEqualTo(figure);
                for (JournalReads.Line line : page.lines()) {
                    assertThat(served.add(line.entryId() + "/" + line.seq())).as("no line served twice").isTrue();
                    if (line.side() == Side.DEBIT) {
                        debits = debits.add(line.amount());
                    } else {
                        credits = credits.add(line.amount());
                    }
                    lines++;
                }
                after = page.next();
                assertThat(++pages).as("the drill-down terminates").isLessThan(50);
            } while (after.isPresent());
            assertThat(debits).as("%s %s debits", figure.account().code(), figure.currency())
                    .isEqualByComparingTo(figure.debits());
            assertThat(credits).isEqualByComparingTo(figure.credits());
            assertThat(lines).isEqualTo(figure.lines());
        }
    }

    @Test
    @DisplayName("a line the version does not map is counted in the unmapped bucket, never dropped: GL + unmapped = ledger,"
            + " and GL = ledger fails for its currency only")
    void anUnmappedLineIsCountedNeverDropped() {
        postNovember();
        journal.post(LocalDate.of(2026, 11, 20), "USD", 2, 999, "SUSPENSE_UNMATCHED", "SUSPENSE", "CASH_AT_BANK",
                "OPERATIONAL");
        GlMappingVersion withoutSuspense = ReferenceGlMapping.without(NOVEMBER, Set.of("SUSPENSE_UNMATCHED"));
        GlBalances balances = ledger.balances(SNAPSHOT, pinned(withoutSuspense), Optional.empty());

        assertThat(balances.unmapped()).singleElement().satisfies(gap -> {
            assertThat(gap.key()).isEqualTo("SUSPENSE_UNMATCHED/SUSPENSE");
            assertThat(gap.currency()).isEqualTo("USD");
            assertThat(gap.debits()).isEqualByComparingTo("9.99");
            assertThat(gap.lines()).isEqualTo(1);
        });
        GlBalances.Agreement usd = agreement(balances, "USD");
        assertThat(usd.agrees()).isFalse();
        assertThat(usd.glDebits().add(usd.unmappedDebits())).isEqualByComparingTo(usd.ledgerDebits());
        assertThat(usd.glCredits().add(usd.unmappedCredits())).isEqualByComparingTo(usd.ledgerCredits());
        assertThat(usd.glLines() + usd.unmappedLines()).isEqualTo(usd.ledgerLines());
        assertThat(agreement(balances, "EUR").agrees()).isTrue();
        assertThat(balances.agrees()).isFalse();
    }

    @Test
    @DisplayName("every figure names its mapping version - in the balances, in the activity, in the entry view")
    void everyFigureNamesItsVersion() {
        postNovember();
        PinnedMapping mapping = pinned(ReferenceGlMapping.version());
        GlBalances balances = ledger.balances(SNAPSHOT, mapping, Optional.empty());
        assertThat(balances.figures()).isNotEmpty().allSatisfy(figure -> {
            assertThat(figure.versionId()).isEqualTo(VERSION_ID);
            assertThat(figure.version()).isEqualTo(7);
        });
        assertThat(ledger.activity(SNAPSHOT, mapping, "4000", "EUR", Optional.empty(), 10).figure().version())
                .isEqualTo(7);
        assertThat(ledger.activity(SNAPSHOT, mapping, "5100", "EUR", Optional.empty(), 10).figure())
                .as("an account with no movement still names its version").satisfies(figure -> {
                    assertThat(figure.versionId()).isEqualTo(VERSION_ID);
                    assertThat(figure.lines()).isZero();
                    assertThat(figure.debits().toPlainString()).isEqualTo("0.00");
                });
    }

    @Test
    @DisplayName("scales are grouping keys and the fold is exact decimal: 12.345 at scale 3 beside 1.00 at scale 2 is"
            + " 13.345, never rounded, never mixed in minor units")
    void mixedScalesFoldExactly() {
        UUID id = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 11, 10);
        journal.post(new JournalReads.Entry(id, "POSTING", day, day, "ref", Optional.empty(), List.of(
                InMemoryJournal.line(id, 0, day, "FEE_REVENUE", "OPERATIONAL", Optional.empty(), Side.CREDIT, 12345,
                        "EUR", 3),
                InMemoryJournal.line(id, 1, day, "CASH_AT_BANK", "OPERATIONAL", Optional.empty(), Side.DEBIT, 12345,
                        "EUR", 3))));
        journal.post(day, "EUR", 2, 100, "CASH_AT_BANK", "OPERATIONAL", "FEE_REVENUE", "OPERATIONAL");
        GlBalances balances = ledger.balances(SNAPSHOT, pinned(ReferenceGlMapping.version()), Optional.of("EUR"));
        assertThat(balances.figure("4000", "EUR").orElseThrow().credits().toPlainString()).isEqualTo("13.345");
        assertThat(balances.agrees()).isTrue();
    }

    @Test
    @DisplayName("the GL view of an entry maps each line under its month's version; a line no rule maps is shown"
            + " unmapped; an entry is never read under another month's version")
    void theEntryViewMapsEachLine() {
        JournalReads.Entry entry = journal.post(LocalDate.of(2026, 11, 4), "EUR", 2, 1500, "SUSPENSE_UNMATCHED",
                "SUSPENSE", "FEE_REVENUE", "OPERATIONAL");
        PinnedMapping mapping = pinned(ReferenceGlMapping.without(NOVEMBER, Set.of("SUSPENSE_UNMATCHED")));
        GlEntry view = ledger.entry(mapping, ledger.findEntry(SNAPSHOT, entry.id()).orElseThrow());
        assertThat(view.lines()).extracting(line -> line.glAccountCode().orElse("UNMAPPED"))
                .containsExactly("UNMAPPED", "4000");
        assertThat(ledger.findEntry(SNAPSHOT, UUID.randomUUID())).isEmpty();
        PinnedMapping december = new PinnedMapping(NOVEMBER.plusMonths(1), Optional.empty(), VERSION_ID, 7,
                ReferenceGlMapping.version());
        assertThatThrownBy(() -> ledger.entry(december, entry)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a GL account code the version does not hold is not found; the currency narrows the balances")
    void aCodeTheVersionDoesNotHoldIsNotFound() {
        postNovember();
        PinnedMapping mapping = pinned(ReferenceGlMapping.version());
        assertThatThrownBy(() -> ledger.activity(SNAPSHOT, mapping, "9999", "EUR", Optional.empty(), 10))
                .isInstanceOf(GeneralLedger.GlAccountNotFound.class).hasMessageContaining("9999");
        GlBalances usd = ledger.balances(SNAPSHOT, mapping, Optional.of("USD"));
        assertThat(usd.figures()).isNotEmpty().allSatisfy(figure -> assertThat(figure.currency()).isEqualTo("USD"));
        assertThat(usd.agreement()).extracting(GlBalances.Agreement::currency).containsExactly("USD");
    }

    @Test
    @DisplayName("a journal serving a drill-down line outside the figure's keys, currency or order is refused - the"
            + " drill-down proves the figure or nothing")
    void aDrillDownOutsideTheSelectionIsRefused() {
        postNovember();
        PinnedMapping mapping = pinned(ReferenceGlMapping.version());
        JournalReads lying = new DelegatingJournal(journal) {
            @Override
            public List<JournalReads.Line> lines(Connection snapshot, JournalReads.LineQuery query) {
                return journal.lines(snapshot, new JournalReads.LineQuery(query.range(), Set.of("CASH_AT_BANK/OPERATIONAL"),
                        query.currency(), query.after(), query.limit()));
            }
        };
        assertThatThrownBy(() -> new GeneralLedger(lying).activity(SNAPSHOT, mapping, "2000", "EUR", Optional.empty(),
                10)).isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ the journal

    /**
     * November 2026 in three currencies, with two lines outside the month: top-ups, a fee, a loan disbursed to two
     * principal purposes, a counterparty-owned clearing (a dimension), and a yen fee at scale 0.
     */
    private void postNovember() {
        journal.post(LocalDate.of(2026, 11, 3), "EUR", 2, 10000, "SETTLEMENT_CLEARING", "OPERATIONAL", "CUSTOMER_WALLET",
                "CUSTOMER");
        journal.post(LocalDate.of(2026, 11, 5), "EUR", 2, 250, "CUSTOMER_WALLET", "CUSTOMER", "FEE_REVENUE",
                "OPERATIONAL");
        journal.post(LocalDate.of(2026, 11, 6), "EUR", 2, 400000, "LOAN_PRINCIPAL", "LOAN", "CASH_AT_BANK",
                "OPERATIONAL");
        journal.post(LocalDate.of(2026, 11, 6), "EUR", 2, 100000, "LOAN_PRINCIPAL_DUE", "LOAN", "CASH_AT_BANK",
                "OPERATIONAL");
        journal.post(LocalDate.of(2026, 11, 30), "EUR", 2, 75, "CUSTOMER_WALLET", "CUSTOMER", "FEE_REVENUE",
                "OPERATIONAL");
        UUID fx = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 11, 12);
        journal.post(new JournalReads.Entry(fx, "POSTING", day, day, "fx", Optional.empty(), List.of(
                InMemoryJournal.line(fx, 0, day, "FX_PROVIDER_CLEARING", "COUNTERPARTY", Optional.of("fx-sim-a"),
                        Side.DEBIT, 5000, "USD", 2),
                InMemoryJournal.line(fx, 1, day, "FX_POSITION", "OPERATIONAL", Optional.empty(), Side.CREDIT, 5000,
                        "USD", 2))));
        journal.post(LocalDate.of(2026, 11, 1), "USD", 2, 1200, "SETTLEMENT_CLEARING", "OPERATIONAL", "CUSTOMER_WALLET",
                "CUSTOMER");
        journal.post(LocalDate.of(2026, 11, 15), "JPY", 0, 300, "CUSTOMER_WALLET", "CUSTOMER", "FEE_REVENUE",
                "OPERATIONAL");
        journal.post(LocalDate.of(2026, 10, 31), "EUR", 2, 1, "CUSTOMER_WALLET", "CUSTOMER", "FEE_REVENUE",
                "OPERATIONAL");
        journal.post(LocalDate.of(2026, 12, 1), "EUR", 2, 1, "CUSTOMER_WALLET", "CUSTOMER", "FEE_REVENUE",
                "OPERATIONAL");
    }

    private Map<String, Long> linesPerCurrency() {
        Map<String, Long> counts = new TreeMap<>();
        for (JournalReads.Total total : journal.totals(SNAPSHOT, JournalReads.Range.of(NOVEMBER))) {
            counts.merge(total.currency(), total.lines(), Long::sum);
        }
        return counts;
    }

    /** The test's own sum of the month's lines on one side - by walking every line, not through the port's totals. */
    private BigDecimal sum(String currency, Side side) {
        BigDecimal sum = BigDecimal.ZERO;
        List<JournalReads.Line> lines = new ArrayList<>();
        for (String key : ReferenceGlMapping.version().rulesByKey().keySet()) {
            Optional<JournalReads.LineCursor> after = Optional.empty();
            List<JournalReads.Line> page;
            do {
                page = journal.lines(SNAPSHOT, new JournalReads.LineQuery(JournalReads.Range.of(NOVEMBER), Set.of(key),
                        currency, after, 500));
                lines.addAll(page);
                after = page.isEmpty() ? Optional.empty() : Optional.of(page.get(page.size() - 1).cursor());
            } while (page.size() == 500);
        }
        for (JournalReads.Line line : lines) {
            if (line.side() == side) {
                sum = sum.add(line.amount());
            }
        }
        return sum;
    }

    private static GlBalances.Agreement agreement(GlBalances balances, String currency) {
        return balances.agreement().stream().filter(a -> a.currency().equals(currency)).findFirst().orElseThrow();
    }

    private static PinnedMapping pinned(GlMappingVersion content) {
        return new PinnedMapping(NOVEMBER, Optional.of(PeriodStatus.OPEN), VERSION_ID, 7, content);
    }

    /** A journal that answers as another does - a suite overrides the read it plants a defect in. */
    private static class DelegatingJournal implements JournalReads {
        private final JournalReads delegate;

        DelegatingJournal(JournalReads delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Aggregate> aggregates(Connection snapshot, Range range) {
            return delegate.aggregates(snapshot, range);
        }

        @Override
        public List<Total> totals(Connection snapshot, Range range) {
            return delegate.totals(snapshot, range);
        }

        @Override
        public List<Line> lines(Connection snapshot, LineQuery query) {
            return delegate.lines(snapshot, query);
        }

        @Override
        public Optional<Entry> entry(Connection snapshot, UUID entryId) {
            return delegate.entry(snapshot, entryId);
        }

        @Override
        public long countLines(Connection snapshot, Set<String> keys, LocalDate fromInclusive) {
            return delegate.countLines(snapshot, keys, fromInclusive);
        }
    }
}
