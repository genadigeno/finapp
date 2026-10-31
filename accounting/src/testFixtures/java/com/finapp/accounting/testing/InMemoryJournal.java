package com.finapp.accounting.testing;

import com.finapp.accounting.JournalReads;
import com.finapp.accounting.Side;
import java.math.BigInteger;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * {@link JournalReads} in memory (`P14-TSK-007`): the hermetic suites' journal - entries of lines with their accounts'
 * purposes, owner kinds, owner references and counterparties - answering the port's five reads the way {@code app}'s
 * {@code LedgerJournalReads} answers them over the ledger's tables: the aggregates grouped by purpose, owner kind,
 * counterparty, currency, scale and side; the totals by currency, scale and side alone; the drill-down in posting-date,
 * entry and line order after a cursor. The SQL's own behaviour is {@code GlReadModelDatabaseTest}'s (owed, C5).
 */
public final class InMemoryJournal implements JournalReads {

    private final List<Entry> entries = new ArrayList<>();
    private int reads;

    /** How many reads were served - a suite proving a short-circuit reads nothing. */
    public int reads() {
        return reads;
    }

    /** Records one entry. */
    public InMemoryJournal post(Entry entry) {
        entries.add(Objects.requireNonNull(entry, "entry"));
        return this;
    }

    /** One balanced two-line entry on {@code date}: {@code debit} and {@code credit} as (purpose, owner kind) pairs. */
    public Entry post(LocalDate date, String currency, int scale, long minorUnits, String debitPurpose,
            String debitOwnerKind, String creditPurpose, String creditOwnerKind) {
        UUID id = UUID.randomUUID();
        Entry entry = new Entry(id, "POSTING", date, date, Optional.empty(), List.of(
                line(id, 0, date, debitPurpose, debitOwnerKind, Optional.empty(), Side.DEBIT, minorUnits, currency, scale),
                line(id, 1, date, creditPurpose, creditOwnerKind, Optional.empty(), Side.CREDIT, minorUnits, currency,
                        scale)));
        post(entry);
        return entry;
    }

    /** A line of entry {@code entryId}, its account a fresh id, owner reference present for an owned kind. */
    public static Line line(UUID entryId, int seq, LocalDate date, String purpose, String ownerKind,
            Optional<String> counterparty, Side side, long minorUnits, String currency, int scale) {
        boolean owned = !ownerKind.equals("OPERATIONAL") && !ownerKind.equals("SUSPENSE");
        return new Line(entryId, seq, date, date, "POSTING", UUID.nameUUIDFromBytes((purpose + ownerKind + currency
                + counterparty.orElse("")).getBytes(java.nio.charset.StandardCharsets.UTF_8)), purpose, ownerKind,
                owned ? Optional.of(UUID.randomUUID()) : Optional.empty(), counterparty, side, minorUnits, currency,
                scale);
    }

    @Override
    public List<Aggregate> aggregates(Connection snapshot, Range range) {
        reads++;
        Map<String, Aggregate> grouped = new TreeMap<>();
        for (Line line : lines(range)) {
            String key = String.join("\u0000", line.purpose(), line.ownerKind(), line.counterparty().orElse(""),
                    line.currency(), Integer.toString(line.scale()), line.side().name());
            Aggregate was = grouped.get(key);
            BigInteger sum = BigInteger.valueOf(line.minorUnits());
            grouped.put(key, was == null
                    ? new Aggregate(line.purpose(), line.ownerKind(), line.counterparty(), line.currency(), line.scale(),
                            line.side(), sum, 1)
                    : new Aggregate(was.purpose(), was.ownerKind(), was.counterparty(), was.currency(), was.scale(),
                            was.side(), was.minorUnits().add(sum), was.lines() + 1));
        }
        return List.copyOf(grouped.values());
    }

    @Override
    public List<Total> totals(Connection snapshot, Range range) {
        reads++;
        Map<String, Total> grouped = new TreeMap<>();
        for (Line line : lines(range)) {
            String key = String.join("\u0000", line.currency(), Integer.toString(line.scale()), line.side().name());
            Total was = grouped.get(key);
            BigInteger sum = BigInteger.valueOf(line.minorUnits());
            grouped.put(key, was == null
                    ? new Total(line.currency(), line.scale(), line.side(), sum, 1)
                    : new Total(was.currency(), was.scale(), was.side(), was.minorUnits().add(sum), was.lines() + 1));
        }
        return List.copyOf(grouped.values());
    }

    @Override
    public List<Line> lines(Connection snapshot, LineQuery query) {
        reads++;
        return lines(query.range()).stream()
                .filter(line -> query.keys().contains(line.key()) && line.currency().equals(query.currency()))
                .filter(line -> query.after().map(cursor -> cursor.precedes(line)).orElse(true))
                .limit(query.limit())
                .toList();
    }

    @Override
    public Optional<Entry> entry(Connection snapshot, UUID entryId) {
        reads++;
        return entries.stream().filter(entry -> entry.id().equals(entryId)).findFirst();
    }

    @Override
    public long countLines(Connection snapshot, Set<String> keys, LocalDate fromInclusive) {
        reads++;
        return entries.stream().flatMap(entry -> entry.lines().stream())
                .filter(line -> keys.contains(line.key()) && !line.postingDate().isBefore(fromInclusive))
                .count();
    }

    /** Every line in {@code range}, in the drill-down's order. */
    private List<Line> lines(Range range) {
        return entries.stream().flatMap(entry -> entry.lines().stream())
                .filter(line -> range.holds(line.postingDate()))
                .sorted(Comparator.comparing(Line::postingDate)
                        .thenComparing(Line::entryId, LineCursor::compareIds)
                        .thenComparingInt(Line::seq))
                .toList();
    }
}
