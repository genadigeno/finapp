package com.finapp.accounting;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The reading behind {@code finapp.accounting.gl.unmapped} (`P14-TSK-007`; {@code PHASE_14_PLAN.md} section 16, alert
 * {@code AccountingMappingGap}; {@code INV-ACC-06}): how many journal lines dated in the open period(s) sit on an account
 * whose purpose and owner kind the <strong>active</strong> GL mapping version does not map - lines the GL shows in its
 * unmapped bucket, never drops, and that make GL = ledger disagree.
 *
 * <ul>
 *   <li>The unmapped keys are the ledger chart's keys ({@link LedgerChart#read} - every purpose of the ledger's closed
 *       enum with its owner kind, the activation's own totality domain) less the version's rules. An activation refuses
 *       a version that is not total, so the set is empty unless the ledger grew a purpose after it - and then the
 *       reading is 0 <strong>without reading the journal</strong>.</li>
 *   <li>Otherwise one count of the lines on those keys dated from the open period's start: the first {@code OPEN}
 *       period's, else the month after the latest period, else the version's own effective month - never before it
 *       (earlier lines are read under earlier versions).</li>
 *   <li>No active version: 0. The GL then answers {@code accounting.NoActiveMapping}; that is not a mapping gap.</li>
 * </ul>
 * Plain reads on the caller's (read-only) connection.
 */
public final class GlMappingGap {

    private final GlMappingStore mappings;
    private final LedgerChart chart;
    private final AccountingPeriodStore periods;
    private final JournalReads journal;

    public GlMappingGap(GlMappingStore mappings, LedgerChart chart, AccountingPeriodStore periods, JournalReads journal) {
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.chart = Objects.requireNonNull(chart, "chart");
        this.periods = Objects.requireNonNull(periods, "periods");
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    /** The journal lines in the open period(s) the active version does not map. */
    public long unmappedLines(Connection snapshot) {
        Optional<GlMappingStore.VersionRow> active = mappings.active(snapshot);
        if (active.isEmpty()) {
            return 0;
        }
        Set<String> unmapped = unmappedKeys(chart.read(snapshot), mappings.content(snapshot, active.get().id()));
        if (unmapped.isEmpty()) {
            return 0;
        }
        LocalDate effective = active.get().effectiveFrom().atDay(1);
        LocalDate open = periods.firstOpen(snapshot).map(AccountingPeriodStore.PeriodRow::start)
                .or(() -> periods.latest(snapshot).map(latest -> latest.plusMonths(1).atDay(1)))
                .orElse(effective);
        LocalDate from = open.isAfter(effective) ? open : effective;
        return journal.countLines(snapshot, unmapped, from);
    }

    /** The chart's keys no rule of {@code version} maps, sorted. */
    static Set<String> unmappedKeys(LedgerChart.Chart chart, GlMappingVersion version) {
        Set<String> mapped = version.rulesByKey().keySet();
        Set<String> unmapped = new TreeSet<>();
        for (LedgerChart.ChartPurpose purpose : chart.purposes()) {
            if (!mapped.contains(purpose.key())) {
                unmapped.add(purpose.key());
            }
        }
        return unmapped;
    }
}
