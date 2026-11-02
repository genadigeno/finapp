package com.finapp.accounting.reporting;

import com.finapp.accounting.JournalReads;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The journal's aggregates folded into rows per (purpose, owner kind, currency) (`P14-TSK-017`; {@code INV-MON-01}): the
 * opening's lines and the movement's, with exact decimals and sums past {@code long} ({@code BigDecimal} over minor units per
 * scale, never floating point), never across currencies. Sorted by key, then currency, whatever order the reads served - so one
 * snapshot folds to one answer on every instance. Pure: it reads and writes nothing.
 */
final class PurposeRows {

    private PurposeRows() {}

    /** One (purpose, owner kind, currency): the lines before the period and the period's. */
    record Row(String purpose, String ownerKind, String currency, TrialBalance.Sides opening, TrialBalance.Sides movement) {

        Row {
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(ownerKind, "ownerKind");
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
                .map(row -> new Row(row.purpose, row.ownerKind, row.currency, row.opening, row.movement))
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

    private static Building building(Map<String, Building> rows, JournalReads.Aggregate aggregate) {
        return rows.computeIfAbsent(aggregate.key() + "\u0000" + aggregate.currency(),
                ignored -> new Building(aggregate.purpose(), aggregate.ownerKind(), aggregate.currency()));
    }

    /** A row being folded. */
    private static final class Building {
        private final String purpose;
        private final String ownerKind;
        private final String currency;
        private TrialBalance.Sides opening = TrialBalance.Sides.NONE;
        private TrialBalance.Sides movement = TrialBalance.Sides.NONE;

        Building(String purpose, String ownerKind, String currency) {
            this.purpose = purpose;
            this.ownerKind = ownerKind;
            this.currency = currency;
        }
    }
}
