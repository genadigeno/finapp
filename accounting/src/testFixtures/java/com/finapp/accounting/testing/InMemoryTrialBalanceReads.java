package com.finapp.accounting.testing;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.CounterpartyPositionReads;
import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.Side;
import com.finapp.accounting.TrialBalanceReads;
import java.math.BigInteger;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * {@link TrialBalanceReads} in memory (`P14-TSK-009`): the hermetic suites' journal, periods and versions, answering the
 * port the way {@code app}'s {@code LedgerTrialBalanceReads} answers it over the database - the GL side's lines grouped
 * by purpose, owner kind, currency, scale and side before a date or within a range; the ledger side per currency, scale
 * and side over the three windows of a period, <strong>its bounds the period row's</strong> - the row {@link #period}
 * served, never the caller's month; every read honouring the cut on the line's creation instant. The SQL's own behaviour is
 * {@code TrialBalanceRunDatabaseTest}'s (owed, C5). {@link #counterparties()} is the same lines through {@code
 * CounterpartyPositionReads}, the two reads of one journal a report folds together.
 */
public final class InMemoryTrialBalanceReads implements TrialBalanceReads {

    /** The owner kind whose accounts are each one counterparty's. */
    private static final String COUNTERPARTY = "COUNTERPARTY";

    /** The code a counterparty's line carries when a suite names none. */
    public static final String THE_COUNTERPARTY = "fx-sim-a";

    /** One journal line: its entry's posting date and creation instant, its account's key and counterparty, its amount. */
    public record JournalLine(
            LocalDate postingDate,
            Instant createdAt,
            String purpose,
            String ownerKind,
            Optional<String> counterparty,
            String currency,
            int scale,
            Side side,
            long minorUnits) {

        public JournalLine {
            Objects.requireNonNull(postingDate, "postingDate");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(ownerKind, "ownerKind");
            Objects.requireNonNull(counterparty, "counterparty");
            Objects.requireNonNull(currency, "currency");
            if (side != Side.DEBIT && side != Side.CREDIT) {
                throw new IllegalArgumentException("a journal line is a debit or a credit");
            }
            if (minorUnits <= 0) {
                throw new IllegalArgumentException("a journal line's amount is positive");
            }
        }

        /** A line whose counterparty, if its account has one, is {@link #THE_COUNTERPARTY}. */
        public JournalLine(LocalDate postingDate, Instant createdAt, String purpose, String ownerKind, String currency, int scale,
                Side side, long minorUnits) {
            this(postingDate, createdAt, purpose, ownerKind, named(ownerKind, THE_COUNTERPARTY), currency, scale, side, minorUnits);
        }

        public String key() {
            return purpose + "/" + ownerKind;
        }
    }

    private final List<JournalLine> lines = new CopyOnWriteArrayList<>();
    private final Map<AccountingPeriodId, Period> periods = new ConcurrentHashMap<>();
    private final Map<GlMappingVersionId, GlMappingVersion> versions = new ConcurrentHashMap<>();

    /** Holds a period row. */
    public InMemoryTrialBalanceReads period(Period period) {
        periods.put(period.id(), period);
        return this;
    }

    /** Holds a version's content. */
    public InMemoryTrialBalanceReads version(GlMappingVersionId id, GlMappingVersion content) {
        versions.put(id, content);
        return this;
    }

    /** Records one line - a balanced journal is the caller's to post, a planted imbalance its probe's. */
    public InMemoryTrialBalanceReads line(JournalLine line) {
        lines.add(Objects.requireNonNull(line, "line"));
        return this;
    }

    /** One balanced two-line entry: {@code debit} and {@code credit} as {@code PURPOSE/OWNER_KIND} keys. */
    public InMemoryTrialBalanceReads post(LocalDate date, Instant createdAt, String currency, int scale, long minorUnits,
            String debit, String credit) {
        return postFor(THE_COUNTERPARTY, date, createdAt, currency, scale, minorUnits, debit, credit);
    }

    /** As {@link #post}, a counterparty's account among the two being {@code counterparty}'s. */
    public InMemoryTrialBalanceReads postFor(String counterparty, LocalDate date, Instant createdAt, String currency, int scale,
            long minorUnits, String debit, String credit) {
        String[] debited = debit.split("/", 2);
        String[] credited = credit.split("/", 2);
        line(new JournalLine(date, createdAt, debited[0], debited[1], named(debited[1], counterparty), currency, scale, Side.DEBIT,
                minorUnits));
        line(new JournalLine(date, createdAt, credited[0], credited[1], named(credited[1], counterparty), currency, scale,
                Side.CREDIT, minorUnits));
        return this;
    }

    private static Optional<String> named(String ownerKind, String counterparty) {
        return COUNTERPARTY.equals(ownerKind) ? Optional.of(counterparty) : Optional.empty();
    }

    /** The same journal through {@link CounterpartyPositionReads}, answering as {@code LedgerCounterpartyPositionReads} does. */
    public CounterpartyPositionReads counterparties() {
        return new CounterpartyPositionReads() {
            @Override
            public List<JournalReads.Aggregate> before(Connection snapshot, LocalDate before, Optional<Instant> cut) {
                return grouped(line -> COUNTERPARTY.equals(line.ownerKind()) && line.postingDate().isBefore(before)
                        && atOrBefore(line, cut), JournalLine::counterparty);
            }

            @Override
            public List<JournalReads.Aggregate> within(Connection snapshot, JournalReads.Range range, Optional<Instant> cut) {
                return grouped(line -> COUNTERPARTY.equals(line.ownerKind()) && range.holds(line.postingDate())
                        && atOrBefore(line, cut), JournalLine::counterparty);
            }
        };
    }

    /** Every line held, in posting order. */
    public List<JournalLine> lines() {
        return List.copyOf(lines);
    }

    @Override
    public Optional<Period> period(Connection snapshot, AccountingPeriodId id) {
        return Optional.ofNullable(periods.get(id));
    }

    @Override
    public GlMappingVersion version(Connection snapshot, GlMappingVersionId id) {
        GlMappingVersion content = versions.get(id);
        if (content == null) {
            throw new IllegalStateException("content is read for a version that exists");
        }
        return content;
    }

    @Override
    public List<JournalReads.Aggregate> before(Connection snapshot, LocalDate before, Optional<Instant> cut) {
        return grouped(line -> line.postingDate().isBefore(before) && atOrBefore(line, cut), line -> Optional.empty());
    }

    @Override
    public List<JournalReads.Aggregate> within(Connection snapshot, JournalReads.Range range, Optional<Instant> cut) {
        return grouped(line -> range.holds(line.postingDate()) && atOrBefore(line, cut), line -> Optional.empty());
    }

    @Override
    public List<LedgerWindows> ledger(Connection snapshot, Period period, Optional<Instant> cut) {
        Objects.requireNonNull(period, "period");
        Map<String, long[]> counts = new TreeMap<>();
        Map<String, BigInteger[]> sums = new TreeMap<>();
        Map<String, JournalLine> exemplars = new TreeMap<>();
        for (JournalLine line : lines) {
            if (line.postingDate().isAfter(period.end()) || !atOrBefore(line, cut)) {
                continue;
            }
            String key = line.currency() + "\u0000" + line.scale() + "\u0000" + line.side().name();
            exemplars.putIfAbsent(key, line);
            long[] count = counts.computeIfAbsent(key, ignored -> new long[3]);
            BigInteger[] sum = sums.computeIfAbsent(key,
                    ignored -> new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO});
            BigInteger amount = BigInteger.valueOf(line.minorUnits());
            int window = line.postingDate().isBefore(period.start()) ? 0 : 1;
            count[window]++;
            sum[window] = sum[window].add(amount);
            count[2]++;
            sum[2] = sum[2].add(amount);
        }
        List<LedgerWindows> windows = new ArrayList<>();
        exemplars.forEach((key, line) -> {
            long[] count = counts.get(key);
            BigInteger[] sum = sums.get(key);
            windows.add(new LedgerWindows(line.currency(), line.scale(), line.side(), sum[0], count[0], sum[1],
                    count[1], sum[2], count[2]));
        });
        return windows;
    }

    private static boolean atOrBefore(JournalLine line, Optional<Instant> cut) {
        return cut.map(at -> !line.createdAt().isAfter(at)).orElse(true);
    }

    private List<JournalReads.Aggregate> grouped(Predicate<JournalLine> selected, Function<JournalLine, Optional<String>> dimension) {
        Map<String, JournalReads.Aggregate> grouped = new TreeMap<>();
        for (JournalLine line : lines) {
            if (!selected.test(line)) {
                continue;
            }
            Optional<String> counterparty = dimension.apply(line);
            String key = String.join("\u0000", line.purpose(), line.ownerKind(), counterparty.orElse(""), line.currency(),
                    Integer.toString(line.scale()), line.side().name());
            JournalReads.Aggregate was = grouped.get(key);
            BigInteger amount = BigInteger.valueOf(line.minorUnits());
            grouped.put(key, was == null
                    ? new JournalReads.Aggregate(line.purpose(), line.ownerKind(), counterparty, line.currency(),
                            line.scale(), line.side(), amount, 1)
                    : new JournalReads.Aggregate(was.purpose(), was.ownerKind(), counterparty, was.currency(),
                            was.scale(), was.side(), was.minorUnits().add(amount), was.lines() + 1));
        }
        return List.copyOf(grouped.values());
    }
}
