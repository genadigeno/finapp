package com.finapp.accounting.reporting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * A trial balance's translated columns (`P14-TSK-015`; {@code INV-ACC-11}): its four columns - the opening and closing
 * balances (signed, a credit balance negative), the period's debits and credits - each translated on its own by
 * {@link Translation}, the rows the parts in {@link TrialBalance}'s own order, the account rows then the unmapped ones, so
 * an unmapped line is translated like any other and each currency's total is the whole of its books. Every currency the
 * trial balance holds is required, the ledger's own included: a currency with ledger lines and no row - a read that lost
 * its rows, which {@link TrialBalance#equalsLedger()} fails - is a hole here too, never a {@code complete} translation over
 * a partial total. It reads nothing and judges nothing: {@link TrialBalance#passes()} is the trial balance's, and
 * translation does not enter it.
 */
final class TrialBalanceTranslation {

    private final Translation.Rates rates;
    private final SortedMap<String, Translation.Translated> columns;

    TrialBalanceTranslation(TrialBalance balance, Translation.Rates rates) {
        Objects.requireNonNull(balance, "balance");
        this.rates = Objects.requireNonNull(rates, "rates");
        List<Row> rows = new ArrayList<>(balance.accounts().size() + balance.unmapped().size());
        balance.accounts().forEach(row -> rows.add(new Row(row.currency(), row.openingBalance(),
                row.movement().debits(), row.movement().credits(), row.closingBalance())));
        balance.unmapped().forEach(row -> rows.add(new Row(row.currency(), row.openingBalance(),
                row.movement().debits(), row.movement().credits(), row.closingBalance())));
        TreeMap<String, List<Translation.Part>> parts = new TreeMap<>();
        parts.put("opening", parts(rows, Row::opening));
        parts.put("debits", parts(rows, Row::debits));
        parts.put("credits", parts(rows, Row::credits));
        parts.put("closing", parts(rows, Row::closing));
        Set<String> held = new TreeSet<>();
        balance.currencies().forEach(check -> held.add(check.currency()));
        this.columns = rates.translate(parts, held);
    }

    /**
     * Writes the row at {@code index} - in {@link TrialBalance}'s order, account rows then unmapped - its {@code translated}
     * member: the four figures beside the originals the row already holds, or {@code null} for a currency the set does not
     * value.
     */
    void writeRow(CanonicalJson.Obj row, int index) {
        CanonicalJson.Obj translated = CanonicalJson.object();
        for (Map.Entry<String, Translation.Translated> column : columns.entrySet()) {
            Optional<BigDecimal> figure = column.getValue().lines().get(index).translated();
            if (figure.isEmpty()) {
                row.putNull("translated");
                return;
            }
            translated.put(column.getKey(), Translation.reportingMoney(figure.get()));
        }
        row.put("translated", translated);
    }

    /** The content's {@code translation} member. */
    CanonicalJson.Obj section(Map<String, Integer> scales) {
        return Translation.section(rates, columns, scales);
    }

    /** The translated column of {@code name} - {@code opening}, {@code debits}, {@code credits} or {@code closing}. */
    Translation.Translated column(String name) {
        return columns.get(name);
    }

    private static List<Translation.Part> parts(List<Row> rows, Function<Row, BigDecimal> figure) {
        return rows.stream().map(row -> new Translation.Part(row.currency(), figure.apply(row))).toList();
    }

    /** One row's four originals. */
    private record Row(String currency, BigDecimal opening, BigDecimal debits, BigDecimal credits, BigDecimal closing) {}
}
