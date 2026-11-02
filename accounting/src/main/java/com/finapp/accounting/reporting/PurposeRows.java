package com.finapp.accounting.reporting;

import com.finapp.accounting.JournalReads;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The journal's aggregates folded into rows per (purpose, owner kind, counterparty, currency) (`P14-TSK-017`; {@code INV-MON-01},
 * {@code INV-RAIL-04}): the opening's lines and the movement's, with exact decimals and sums past {@code long} ({@code BigDecimal} over
 * minor units per scale, never floating point), never across currencies and never across counterparties. Sorted by key, then counterparty,
 * then currency, whatever order the reads served - so one snapshot folds to one answer on every instance. Pure: it reads and writes
 * nothing.
 */
final class PurposeRows {

    /** The owner kind whose accounts are each one counterparty's: nothing nets between them (ledger {@code V025}). */
    static final String COUNTERPARTY = "COUNTERPARTY";

    private PurposeRows() {}

    /** One (purpose, owner kind, counterparty, currency): the lines before the period and the period's. */
    record Row(String purpose, String ownerKind, Optional<String> counterparty, String currency, TrialBalance.Sides opening,
            TrialBalance.Sides movement) {

        Row {
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(ownerKind, "ownerKind");
            Objects.requireNonNull(counterparty, "counterparty");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(opening, "opening");
            Objects.requireNonNull(movement, "movement");
        }

        String key() {
            return purpose + "/" + ownerKind;
        }

        BigDecimal openingBalance() {
            return opening.net();
        }

        BigDecimal closingBalance() {
            return opening.net().add(movement.net());
        }
    }

    /**
     * {@code lines} - the trial balance's read, which has no counterparty - with each {@code COUNTERPARTY}-owned aggregate replaced by
     * {@code positions}' for the same window on the same snapshot, so what follows folds per counterparty.
     *
     * <p><strong>Fail closed.</strong> One snapshot's two reads of one set of lines agree, per key, currency, scale and side, in amount and
     * in line count; a code missing means an account whose owner reference names no counterparty. Either is refused: a figure dropped, or
     * folded under no name, would be the very netting this exists to end.
     *
     * @throws IllegalStateException when a position has no counterparty, is not a counterparty's, or the two reads differ
     */
    static List<JournalReads.Aggregate> perCounterparty(List<JournalReads.Aggregate> lines, List<JournalReads.Aggregate> positions) {
        Map<String, BigInteger> held = new TreeMap<>();
        List<JournalReads.Aggregate> split = new ArrayList<>();
        for (JournalReads.Aggregate line : lines) {
            if (COUNTERPARTY.equals(line.ownerKind())) {
                tally(held, line);
            } else {
                split.add(line);
            }
        }
        Map<String, BigInteger> named = new TreeMap<>();
        for (JournalReads.Aggregate position : positions) {
            if (!COUNTERPARTY.equals(position.ownerKind()) || position.counterparty().isEmpty()) {
                throw new IllegalStateException("a counterparty position names its counterparty: " + position.key());
            }
            tally(named, position);
            split.add(position);
        }
        if (!held.equals(named)) {
            throw new IllegalStateException("the journal's lines and its counterparties' disagree on one snapshot");
        }
        return split;
    }

    private static void tally(Map<String, BigInteger> tally, JournalReads.Aggregate aggregate) {
        String key = String.join("\u0000", aggregate.key(), aggregate.currency(), Integer.toString(aggregate.scale()),
                aggregate.side().name());
        tally.merge(key, aggregate.minorUnits(), BigInteger::add);
        tally.merge(key + "\u0000lines", BigInteger.valueOf(aggregate.lines()), BigInteger::add);
    }

    /** The rows of {@code keys} ({@code PURPOSE/OWNER_KIND}), from every aggregate of theirs and nothing else. */
    static List<Row> fold(List<JournalReads.Aggregate> opening, List<JournalReads.Aggregate> movement, Set<String> keys) {
        Map<String, Building> rows = new TreeMap<>();
        for (JournalReads.Aggregate aggregate : opening) {
            if (keys.contains(aggregate.key())) {
                Building row = building(rows, aggregate);
                row.opening = row.opening.plus(aggregate.side(), aggregate.amount(), aggregate.lines());
            }
        }
        for (JournalReads.Aggregate aggregate : movement) {
            if (keys.contains(aggregate.key())) {
                Building row = building(rows, aggregate);
                row.movement = row.movement.plus(aggregate.side(), aggregate.amount(), aggregate.lines());
            }
        }
        return rows.values().stream()
                .map(row -> new Row(row.purpose, row.ownerKind, row.counterparty, row.currency, row.opening, row.movement))
                .toList();
    }

    /**
     * Per currency, the one scale every amount of it in {@code rows} is written at: <strong>the largest its own amounts
     * carry</strong> - the data's, as {@link TrialBalance} writes it, so no JDK currency table decides a byte
     * ({@code P14-TSK-015}'s review, M-6).
     */
    static Map<String, Integer> scales(List<Row> rows) {
        Map<String, Integer> scales = new TreeMap<>();
        for (Row row : rows) {
            scales.merge(row.currency(), Math.max(scaleOf(row.opening()), scaleOf(row.movement())), Math::max);
        }
        return scales;
    }

    static int scaleOf(TrialBalance.Sides sides) {
        return Math.max(sides.debits().scale(), sides.credits().scale());
    }

    /** Writes a figure's {@code counterparty}: its code, or {@code null} on an account that is no counterparty's. */
    static CanonicalJson.Obj withCounterparty(CanonicalJson.Obj out, Optional<String> counterparty) {
        return counterparty.isPresent() ? out.put("counterparty", counterparty.get()) : out.putNull("counterparty");
    }

    private static Building building(Map<String, Building> rows, JournalReads.Aggregate aggregate) {
        return rows.computeIfAbsent(aggregate.key() + "\u0000" + aggregate.counterparty().orElse("") + "\u0000" + aggregate.currency(),
                ignored -> new Building(aggregate.purpose(), aggregate.ownerKind(), aggregate.counterparty(), aggregate.currency()));
    }

    /** A row being folded. */
    private static final class Building {
        private final String purpose;
        private final String ownerKind;
        private final Optional<String> counterparty;
        private final String currency;
        private TrialBalance.Sides opening = TrialBalance.Sides.NONE;
        private TrialBalance.Sides movement = TrialBalance.Sides.NONE;

        Building(String purpose, String ownerKind, Optional<String> counterparty, String currency) {
            this.purpose = purpose;
            this.ownerKind = ownerKind;
            this.counterparty = counterparty;
            this.currency = currency;
        }
    }
}
