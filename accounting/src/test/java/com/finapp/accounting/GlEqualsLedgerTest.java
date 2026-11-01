package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.accounting.reporting.GlBalances;
import com.finapp.accounting.reporting.PinnedMapping;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.sharedkernel.id.IdGenerator;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GL = ledger (`P14-TSK-010`; ADR-0117 decision 2; {@code INV-ACC-06}, {@code INV-ACC-12}) over `P14-TSK-007`'s fold:
 * a planted unmapped purpose is one {@code MAPPING_GAP} per key whatever its currencies; a line counted twice is one
 * {@code DOUBLE_MAPPING} per currency, a line dropped one {@code MAPPING_GAP}; an agreeing period nothing - every finding
 * judged by the period's mapping version, its evidence counts and never an amount. Hermetic: the fold's inputs are values.
 */
@DisplayName("GL = ledger: every line mapped exactly once, judged by the period's version (P14-TSK-010)")
class GlEqualsLedgerTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final YearMonth MONTH = YearMonth.of(2097, 3);

    private final GlEqualsLedger judge = new GlEqualsLedger();
    private final GlMappingVersion version = ReferenceGlMapping.version();
    private final PinnedMapping mapping =
            new PinnedMapping(MONTH, Optional.empty(), GlMappingVersionId.next(IDS), 4, version);
    private final String debitKey = version.rules().get(0).key();
    private final String creditKey = version.rules().get(1).key();

    @Test
    @DisplayName("an agreeing period raises nothing")
    void anAgreeingPeriodRaisesNothing() {
        List<JournalReads.Aggregate> aggregates = entry(debitKey, creditKey, "EUR", 1500, 3);
        assertThat(judge.judge(fold(aggregates, totalsOf(aggregates)))).isEmpty();
    }

    @Test
    @DisplayName("a planted unmapped purpose is one MAPPING_GAP for its key, whatever its currencies - and no currency"
            + " break, because the unmapped bucket explains the difference exactly")
    void aPlantedUnmappedPurposeRaisesOneBreak() {
        List<JournalReads.Aggregate> aggregates = new ArrayList<>(entry(debitKey, creditKey, "EUR", 1500, 3));
        aggregates.addAll(entry(debitKey, "UNMAPPED_PROBE/OPERATIONAL", "EUR", 70, 1));
        aggregates.addAll(entry(debitKey, "UNMAPPED_PROBE/OPERATIONAL", "USD", 90, 2));

        List<BreakFinding> findings = judge.judge(fold(aggregates, totalsOf(aggregates)));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.check()).isEqualTo(CloseCheckCode.GL_EQUALS_LEDGER);
            assertThat(finding.type()).isEqualTo(BreakType.MAPPING_GAP);
            assertThat(finding.subject()).isEqualTo("key:UNMAPPED_PROBE/OPERATIONAL");
            assertThat(finding.evidence()).contains("EUR (1 lines)").contains("USD (2 lines)").contains("version=v4")
                    .doesNotContain("70").doesNotContain("90");
            assertThat(finding.judgedBy()).isEqualTo("gl-mapping:v4");
        });
    }

    @Test
    @DisplayName("a planted double mapping - a line the GL counts twice - is one DOUBLE_MAPPING for its currency")
    void aPlantedDoubleMappingRaisesOneBreak() {
        List<JournalReads.Aggregate> journal = entry(debitKey, creditKey, "EUR", 1500, 3);
        List<JournalReads.Aggregate> doubled = new ArrayList<>(journal);
        doubled.add(journal.get(0));

        assertThat(judge.judge(fold(doubled, totalsOf(journal)))).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(BreakType.DOUBLE_MAPPING);
            assertThat(finding.subject()).isEqualTo("currency:EUR");
            assertThat(finding.evidence()).contains("glLines=9").contains("ledgerLines=6")
                    .contains("debits=GL above ledger").contains("credits=equal");
        });
    }

    @Test
    @DisplayName("a line the GL drops is one MAPPING_GAP for its currency")
    void aDroppedLineIsAGap() {
        List<JournalReads.Aggregate> journal = entry(debitKey, creditKey, "USD", 800, 2);
        assertThat(judge.judge(fold(journal.subList(0, 1), totalsOf(journal)))).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(BreakType.MAPPING_GAP);
            assertThat(finding.subject()).isEqualTo("currency:USD");
            assertThat(finding.evidence()).contains("credits=GL below ledger");
        });
    }

    // ------------------------------------------------------------------ fixtures

    private GlBalances fold(List<JournalReads.Aggregate> aggregates, List<JournalReads.Total> totals) {
        return GeneralLedger.fold(mapping, aggregates, totals, Optional.empty());
    }

    /** A balanced movement of {@code lines} lines a side: debits on {@code debit}, credits on {@code credit}. */
    private static List<JournalReads.Aggregate> entry(String debit, String credit, String currency, long minor,
            long lines) {
        return List.of(aggregate(debit, currency, Side.DEBIT, minor, lines),
                aggregate(credit, currency, Side.CREDIT, minor, lines));
    }

    private static JournalReads.Aggregate aggregate(String key, String currency, Side side, long minor, long lines) {
        String[] parts = key.split("/");
        return new JournalReads.Aggregate(parts[0], parts[1], Optional.empty(), currency, 2, side,
                BigInteger.valueOf(minor), lines);
    }

    /** The ledger's own totals of {@code aggregates} - what a mapping-independent statement would read. */
    private static List<JournalReads.Total> totalsOf(List<JournalReads.Aggregate> aggregates) {
        java.util.Map<String, JournalReads.Total> totals = new java.util.TreeMap<>();
        for (JournalReads.Aggregate aggregate : aggregates) {
            String key = aggregate.currency() + aggregate.side();
            JournalReads.Total previous = totals.get(key);
            totals.put(key, new JournalReads.Total(aggregate.currency(), aggregate.scale(), aggregate.side(),
                    aggregate.minorUnits().add(previous == null ? BigInteger.ZERO : previous.minorUnits()),
                    aggregate.lines() + (previous == null ? 0 : previous.lines())));
        }
        return List.copyOf(totals.values());
    }
}
