package com.finapp.app.accounting;

import com.finapp.accounting.ReconciliationRunCoverage;
import com.finapp.accounting.SuspenseAsOfCutoff;
import com.finapp.reconciliation.ReportReadings;
import com.finapp.reconciliation.SuspenseSide;
import com.finapp.sharedkernel.money.Money;
import java.math.BigInteger;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Two of accounting's reads of a month over reconciliation's own {@link ReportReadings} (`P14-TSK-017`; {@code INV-ACC-04},
 * {@code INV-ACC-05}; owner decision C11) - in {@code app}, the one module that sees both: the month's runs, which {@code
 * RECONCILIATION_STATUS} reports and {@code P14-TSK-012}'s check reads, and the suspense register as of the cutoff through the
 * read that cannot move after a lock, which {@code SUSPENSE}'s FINAL body reads. {@code ProductReconciliationDisclosure} and the
 * check it answers are untouched.
 *
 * <p>Every read on the caller's snapshot; a read that fails throws, so a report fails rather than says nothing is there. Never a
 * reason, requester or reference; figures gross per currency and scale, in minor units ({@link BigInteger} folds, never floating
 * point), and never netted across sides.
 */
@RequiredArgsConstructor
public final class ReconciliationMonthReads implements ReconciliationRunCoverage, SuspenseAsOfCutoff {

    @NonNull private final ReportReadings readings;

    @Override
    public Coverage ofMonth(Connection snapshot, YearMonth month) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(month, "month");
        return new Coverage(month, readings.runsOfBusinessDates(snapshot, month.atDay(1), month.atEndOfMonth()).stream()
                .map(line -> new Line(line.sourceId(), line.kind().name(), line.status().name(), line.businessDate(),
                        line.runs()))
                .toList());
    }

    @Override
    public Register ofCutoff(Connection snapshot, YearMonth period) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(period, "period");
        LocalDate cutoff = period.atEndOfMonth();
        Instant asOf = cutoff.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<String, Fold> groups = new TreeMap<>();
        readings.eachSuspenseItemBornAsOf(snapshot, cutoff, asOf, line ->
                groups.computeIfAbsent(line.unreleased().currency().code() + ":" + line.unreleased().scale(),
                        ignored -> new Fold(line.unreleased())).add(line, ChronoUnit.DAYS.between(line.openedOn(), cutoff)));
        List<Group> folded = groups.values().stream().map(Fold::value).toList();
        return new Register(cutoff, asOf, folded);
    }

    /** One currency and scale of the register, gross per side. */
    private static final class Fold {
        private final String currency;
        private final int scale;
        private final SortedMap<String, Long> ageing = new TreeMap<>();
        private long creditItems;
        private BigInteger creditUnreleased = BigInteger.ZERO;
        private long debitItems;
        private BigInteger debitUnreleased = BigInteger.ZERO;
        private Optional<LocalDate> oldest = Optional.empty();

        Fold(Money first) {
            this.currency = first.currency().code();
            this.scale = first.scale();
            ProductReconciliationDisclosure.BANDS.forEach(band -> ageing.put(band, 0L));
        }

        void add(ReportReadings.SuspenseAsOfLine line, long days) {
            BigInteger remainder = BigInteger.valueOf(line.unreleased().minorUnits());
            if (line.side() == SuspenseSide.CREDIT) {
                creditItems++;
                creditUnreleased = creditUnreleased.add(remainder);
            } else {
                debitItems++;
                debitUnreleased = debitUnreleased.add(remainder);
            }
            ageing.merge(ProductReconciliationDisclosure.band(days), 1L, Long::sum);
            oldest = oldest.filter(held -> !held.isAfter(line.openedOn())).or(() -> Optional.of(line.openedOn()));
        }

        Group value() {
            return new Group(currency, scale, creditItems, creditUnreleased, debitItems, debitUnreleased, ageing, oldest);
        }
    }
}
