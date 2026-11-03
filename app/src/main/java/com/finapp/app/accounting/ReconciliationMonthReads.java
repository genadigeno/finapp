package com.finapp.app.accounting;

import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.ReconciliationRunCoverage;
import com.finapp.accounting.SuspenseAsOfCutoff;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.reconciliation.ReportReadings;
import com.finapp.reconciliation.SuspenseSide;
import com.finapp.sharedkernel.money.Money;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Two of accounting's reads of a month over reconciliation's own {@link ReportReadings} (`P14-TSK-017`; {@code INV-ACC-04},
 * {@code INV-ACC-05}; owner decision C11) - in {@code app}, the one module that sees both: the month's runs with the settlement sources they are measured against, which {@code
 * RECONCILIATION_STATUS} reports and {@code RECONCILIATION_DISCLOSURE} judges (`P14-TSK-012`), and the suspense register as of the cutoff through the
 * read that cannot move after a lock, which {@code SUSPENSE}'s FINAL body reads, aged in that definition's own bands. Nothing here reads {@code
 * ProductReconciliationDisclosure}'s table.
 *
 * <p><strong>Which sources</strong> (`P14-TSK-012`): {@code settlement.source}'s rows, read here, one {@code SELECT} (the {@code Phase9Reports} precedent of an {@code app} read over settlement's table), against the
 * codes this build declares. A row {@code ACTIVE} and declared is {@link SourceState#ACTIVE}; a row {@code RETIRED} is {@link SourceState#RETIRED}; a row {@code ACTIVE} that no build declares has no door, {@link SourceState#UNDECLARED}.
 * A declared code with no row is a deployment defect: the read throws, and the check is {@code FAILED}, never an empty pass.
 *
 * <p>Every read on the caller's connection: a report's and a check's {@code REPEATABLE READ READ ONLY} snapshot, and for {@link #ofMonth} also the close machine's {@code READ COMMITTED} transaction, which
 * {@link ReconciliationRunCoverage} explains; a read that fails throws, so a report fails rather than says nothing is there. Never a
 * reason, requester or reference; figures gross per currency and scale, in minor units ({@link BigInteger} folds, never floating
 * point), and never netted across sides.
 */
@RequiredArgsConstructor
public final class ReconciliationMonthReads implements ReconciliationRunCoverage, SuspenseAsOfCutoff {

    /** Every settlement source's identity and operational state - never a position. */
    static final String SOURCES_SQL = "SELECT id, code, status FROM settlement.source ORDER BY code";

    @NonNull private final ReportReadings readings;
    /** The source codes this build declares ({@code SettlementSources.declared()}). */
    @NonNull private final Set<String> declared;

    @Override
    public Coverage ofMonth(Connection connection, YearMonth month) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(month, "month");
        return new Coverage(month, readings.runsOfBusinessDates(connection, month.atDay(1), month.atEndOfMonth()).stream()
                .map(line -> new Line(line.sourceId(), line.kind().name(), line.status().name(), line.businessDate(),
                        line.repudiated(), line.runs()))
                .toList(), sources(connection));
    }

    private List<Source> sources(Connection connection) {
        try (PreparedStatement read = connection.prepareStatement(SOURCES_SQL); ResultSet row = read.executeQuery()) {
            List<Source> sources = new ArrayList<>();
            while (row.next()) {
                String code = row.getString("code");
                boolean active = "ACTIVE".equals(row.getString("status"));
                sources.add(new Source(row.getObject("id", UUID.class), code,
                        !active ? SourceState.RETIRED : declared.contains(code) ? SourceState.ACTIVE : SourceState.UNDECLARED));
            }
            if (!sources.stream().map(Source::code).collect(Collectors.toSet()).containsAll(declared)) {
                throw new IllegalStateException("a declared settlement source has no row: a deployment's defect, never a month that ran");
            }
            return sources;
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe("reading the settlement sources", failure));
        }
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
            SuspenseAsOfCutoff.AGEING_BANDS.forEach(band -> ageing.put(band, 0L));
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
            ageing.merge(SuspenseAsOfCutoff.ageingBand(days), 1L, Long::sum);
            oldest = oldest.filter(held -> !held.isAfter(line.openedOn())).or(() -> Optional.of(line.openedOn()));
        }

        Group value() {
            return new Group(currency, scale, creditItems, creditUnreleased, debitItems, debitUnreleased, ageing, oldest);
        }
    }
}
