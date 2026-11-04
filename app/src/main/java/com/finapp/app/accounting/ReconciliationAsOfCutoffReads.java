package com.finapp.app.accounting;

import com.finapp.accounting.ReconciliationAsOfCutoff;
import com.finapp.reconciliation.ReportReadings;
import com.finapp.sharedkernel.money.Money;
import java.math.BigInteger;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * accounting's {@link ReconciliationAsOfCutoff} over reconciliation's own {@link ReportReadings} (`P14-TSK-016`; `P14-TSK-017`'s note; {@code INV-ACC-04}, {@code INV-ACC-05}; owner decision C11) - in {@code app}, the one module that sees both. Read, disclosed, never
 * copied: no row of reconciliation is touched.
 *
 * <p><strong>What it uses of a break, and what it will not</strong>: its raise, its resolution and its value at issue, each frozen, so a break resolved after the instant still stood open at it. {@link ReportReadings.BreakLine} also carries a present status, type and severity, which keep moving, and this fold reads none of
 * them. Aged from the cutoff in {@link ReconciliationAsOfCutoff#AGEING_BANDS}, never from today, and never in {@code ProductReconciliationDisclosure}'s table.
 *
 * <p>Figures per currency and scale, gross and in minor units ({@link BigInteger} folds over {@link Money}'s own, never floating point; a sum is never folded back into a {@code long}). Read-only, on the caller's snapshot; a read that fails throws, so the run fails rather than says nothing stood.
 */
@RequiredArgsConstructor
public final class ReconciliationAsOfCutoffReads implements ReconciliationAsOfCutoff {

    @NonNull private final ReportReadings readings;

    @Override
    public Standing ofCutoff(Connection snapshot, YearMonth period) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(period, "period");
        LocalDate cutoff = period.atEndOfMonth();
        Instant asOf = cutoff.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<String, Fold> breaks = new TreeMap<>();
        readings.eachBreakAround(snapshot, asOf, asOf, line -> {
            // The window is empty: only what stood open at the instant comes back. Re-judged, not assumed, on the frozen stamps alone.
            if (line.raisedAt().isBefore(asOf) && line.resolvedAt().filter(resolved -> resolved.isBefore(asOf)).isEmpty()) {
                breaks.computeIfAbsent(line.valueAtIssue().currency().code() + ":" + line.valueAtIssue().scale(), ignored -> new Fold(line.valueAtIssue())).add(line, cutoff);
            }
        });
        List<ParkedItem> items = new ArrayList<>();
        readings.eachSuspenseItemBornAsOf(snapshot, cutoff, asOf, line -> items.add(new ParkedItem(line.id(), line.breakId())));
        return new Standing(cutoff, asOf, breaks.values().stream().map(Fold::value).toList(), items);
    }

    /** One currency and scale of what stood. */
    private static final class Fold {
        private final String currency;
        private final int scale;
        private final SortedMap<String, Long> ageing = new TreeMap<>();
        private final List<UUID> ids = new ArrayList<>();
        private BigInteger value = BigInteger.ZERO;
        private LocalDate oldest;

        Fold(Money first) {
            this.currency = first.currency().code();
            this.scale = first.scale();
            ReconciliationAsOfCutoff.AGEING_BANDS.forEach(band -> ageing.put(band, 0L));
        }

        void add(ReportReadings.BreakLine line, LocalDate cutoff) {
            LocalDate raised = line.raisedAt().atZone(ZoneOffset.UTC).toLocalDate();
            value = value.add(BigInteger.valueOf(line.valueAtIssue().minorUnits()));
            ageing.merge(ReconciliationAsOfCutoff.ageingBand(ChronoUnit.DAYS.between(raised, cutoff)), 1L, Long::sum);
            ids.add(line.id());
            oldest = oldest == null || raised.isBefore(oldest) ? raised : oldest;
        }

        OpenBreaks value() {
            return new OpenBreaks(currency, scale, ids.size(), value, ageing, oldest, ids);
        }
    }
}
