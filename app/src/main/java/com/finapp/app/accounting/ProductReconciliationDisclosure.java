package com.finapp.app.accounting;

import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.ReconciliationDisclosure;
import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.reconciliation.ReportReadings;
import com.finapp.reconciliation.SuspenseSide;
import com.finapp.sharedkernel.money.Money;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Reconciliation's disclosure at a period's close (`P14-TSK-011`; {@code PHASE_14_PLAN.md} sections 13.3, 13.5; owner
 * decision C11; {@code INV-ACC-05}; gate criterion 5) - in {@code app}, the one module that sees reconciliation and the
 * ledger: reconciliation's own {@link ReportReadings} (its frozen stamps answer "as of" exactly), the {@code
 * SUSPENSE_UNMATCHED} lines dated through the last day and the journal's postings past the cutoff ({@link
 * LedgerFlooredPostings}, `P14-TSK-006`'s review, M-2). Read, disclosed, never copied: no row of reconciliation is
 * touched and nothing here raises an accounting break.
 *
 * <p><strong>As of the cutoff</strong>: the breaks standing open at the first instant after the last day, UTC ({@code
 * eachBreakAround} over an empty window - its third arm, raised before and not resolved before); the items opened
 * through the last day less their releases before that instant; both aged at the last day in the operator reports'
 * bands. An item whose owning break was not standing open then is {@code suspenseUnowned} (`P14-TSK-010`'s review,
 * M-10). What the grace days added is counted apart.
 *
 * <p>Figures per currency and scale, gross and in minor units ({@link Money}'s own, {@link BigInteger} folds - never
 * floating point, never netted across sides). Read-only, on the caller's {@code REPEATABLE READ READ ONLY} snapshot; a
 * read that fails throws, so the check is {@code FAILED}, never an empty pass.
 */
@RequiredArgsConstructor
public final class ProductReconciliationDisclosure implements ReconciliationDisclosure {

    /** The operator reports' ageing bands (ADR-0072 section 3), in days. */
    static final List<String> BANDS = List.of("0-2", "3-7", "8-30", "over-30");

    /** The suspense account's lines through the last day, per currency, scale and side. */
    static final String SUSPENSE_LEDGER_SQL = "SELECT line.currency, line.scale, line.direction,"
            + " sum(line.amount_minor) AS total FROM ledger.journal_line line"
            + " JOIN ledger.journal_entry entry ON entry.id = line.entry_id"
            + " JOIN ledger.ledger_account account ON account.id = line.ledger_account_id"
            + " WHERE account.purpose = ? AND entry.posting_date <= ?"
            + " GROUP BY line.currency, line.scale, line.direction ORDER BY 1, 2, 3";

    @NonNull private final ReportReadings readings;
    @NonNull private final LedgerFlooredPostings postings;

    @Override
    public Disclosure asOfCutoff(Connection snapshot, YearMonth period) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(period, "period");
        LocalDate cutoff = period.atEndOfMonth();
        Instant asOf = cutoff.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Map<String, BreakFold> breaks = new TreeMap<>();
        Set<UUID> standing = new HashSet<>();
        readings.eachBreakAround(snapshot, asOf, asOf, line -> {
            // The window is empty: only what stood open at the instant comes back. Re-judged, not assumed.
            if (line.raisedAt().isBefore(asOf) && line.resolvedAt().filter(resolved -> resolved.isBefore(asOf)).isEmpty()) {
                standing.add(line.id());
                breaks.computeIfAbsent(key(line.valueAtIssue()), absent -> new BreakFold(line.valueAtIssue()))
                        .add(line.valueAtIssue(), ChronoUnit.DAYS.between(
                                line.raisedAt().atZone(ZoneOffset.UTC).toLocalDate(), cutoff));
            }
        });
        long[] raisedSince = new long[1];
        readings.eachOpenBreak(snapshot, line -> {
            if (!line.raisedAt().isBefore(asOf)) {
                raisedSince[0]++;
            }
        });

        Map<String, SuspenseFold> suspense = new TreeMap<>();
        long[] unowned = new long[1];
        readings.eachSuspenseItemAsOf(snapshot, cutoff, asOf, line -> {
            suspense.computeIfAbsent(key(line.unreleased()), absent -> new SuspenseFold(line.unreleased()))
                    .add(line, ChronoUnit.DAYS.between(line.openedOn(), cutoff));
            if (!standing.contains(line.breakId())) {
                unowned[0]++;
            }
        });
        ledgerLines(snapshot, cutoff, suspense);

        return new Disclosure(cutoff, asOf, breaks.values().stream().map(BreakFold::value).toList(), raisedSince[0],
                suspense.values().stream().map(SuspenseFold::value).toList(), unowned[0],
                postings.pastTheCutoff(snapshot, cutoff));
    }

    private static void ledgerLines(Connection snapshot, LocalDate cutoff, Map<String, SuspenseFold> suspense) {
        try (PreparedStatement read = snapshot.prepareStatement(SUSPENSE_LEDGER_SQL)) {
            read.setString(1, AccountPurpose.SUSPENSE_UNMATCHED.name());
            read.setDate(2, Date.valueOf(cutoff));
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    String currency = row.getString("currency").stripTrailing();
                    int scale = row.getInt("scale");
                    suspense.computeIfAbsent(currency + ":" + scale, absent -> new SuspenseFold(currency, scale))
                            .ledger(row.getString("direction"), row.getBigDecimal("total").toBigIntegerExact());
                }
            }
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe("reading the suspense account as of a cutoff", failure));
        }
    }

    private static String key(Money money) {
        return money.currency().code() + ":" + money.scale();
    }

    /** The band of an age in days - an age at or below zero (raised on the last day) the youngest. */
    static String band(long days) {
        if (days <= 2) {
            return BANDS.get(0);
        }
        if (days <= 7) {
            return BANDS.get(1);
        }
        return days <= 30 ? BANDS.get(2) : BANDS.get(3);
    }

    private static SortedMap<String, Long> emptyBands() {
        SortedMap<String, Long> bands = new TreeMap<>();
        BANDS.forEach(name -> bands.put(name, 0L));
        return bands;
    }

    /** Open breaks of one currency and scale. */
    private static final class BreakFold {
        private final String currency;
        private final int scale;
        private final SortedMap<String, Long> ageing = emptyBands();
        private long count;
        private BigInteger value = BigInteger.ZERO;

        BreakFold(Money first) {
            this.currency = first.currency().code();
            this.scale = first.scale();
        }

        void add(Money valueAtIssue, long days) {
            count++;
            value = value.add(BigInteger.valueOf(valueAtIssue.minorUnits()));
            ageing.merge(band(days), 1L, Long::sum);
        }

        OpenBreaks value() {
            return new OpenBreaks(currency, scale, count, value, ageing);
        }
    }

    /** Suspense of one currency and scale - the ledger's sides and the register's, never netted. */
    private static final class SuspenseFold {
        private final String currency;
        private final int scale;
        private final SortedMap<String, Long> ageing = emptyBands();
        private BigInteger ledgerDebits = BigInteger.ZERO;
        private BigInteger ledgerCredits = BigInteger.ZERO;
        private long creditItems;
        private BigInteger creditUnreleased = BigInteger.ZERO;
        private long debitItems;
        private BigInteger debitUnreleased = BigInteger.ZERO;
        private Optional<LocalDate> oldest = Optional.empty();

        SuspenseFold(Money first) {
            this(first.currency().code(), first.scale());
        }

        SuspenseFold(String currency, int scale) {
            this.currency = currency;
            this.scale = scale;
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
            ageing.merge(band(days), 1L, Long::sum);
            // The stream is oldest first, but a fold owes nothing to that.
            oldest = oldest.filter(held -> !held.isAfter(line.openedOn())).or(() -> Optional.of(line.openedOn()));
        }

        void ledger(String direction, BigInteger total) {
            if ("DEBIT".equals(direction)) {
                ledgerDebits = ledgerDebits.add(total);
            } else {
                ledgerCredits = ledgerCredits.add(total);
            }
        }

        Suspense value() {
            return new Suspense(currency, scale, ledgerDebits, ledgerCredits, creditItems, creditUnreleased, debitItems,
                    debitUnreleased, ageing, oldest);
        }
    }
}
