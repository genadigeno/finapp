package com.finapp.accounting.reporting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A statement's translated columns (`P14-TSK-016`; {@code INV-ACC-11}, ADR-0116 decision 3, owner decision C7 A) - {@link Translation} as it stands, every column at the run's one set. <strong>Which columns, and which figures a
 * translated body shows beside its originals, are this class and the definitions' two calls to it, and nothing else</strong>: the translated body's shape is still the owner's to settle before the first translated FINAL, so a change to it
 * is here. No FINAL run has been issued, so until the first one it can change inside version 1; after it, a new version.
 *
 * <p><strong>A statement cannot stand with a hole</strong> (the owner's decision, 2026-11-03): a real hole in any column is {@link ReportRefused}, with {@link Translation.Translated#total()} never read. A currency with no rate and every figure zero is no hole ({@code ZERO_NO_RATE}).
 *
 * <p><strong>Places</strong>: a figure the body shows beside its original is named when it is gathered ({@link Columns#named}), and read back by that name ({@link #figure}).
 *
 * <p><strong>Balanced through the difference lines.</strong> A translated total is a function of the original total alone, so two columns whose originals agree per currency have equal translated totals, each column's parts plus its own difference line reaching them. {@link
 * #balanced} compares them, it does not assume them. Nothing is posted, and no difference is absorbed into a figure.
 */
final class StatementTranslation {

    /** A figure's place in a column. */
    private record Place(String column, int index) {}

    /** The parts being gathered, in the body's order. */
    static final class Columns {
        private final SortedMap<String, List<Translation.Part>> parts = new TreeMap<>();
        private final Map<String, Place> places = new TreeMap<>();

        Columns(String... names) {
            for (String name : names) {
                parts.put(name, new ArrayList<>());
            }
        }

        /** A part of {@code column}. */
        void add(String column, String currency, BigDecimal amount) {
            parts.get(column).add(new Translation.Part(currency, amount));
        }

        /** A part of {@code column} the body shows, by {@code name}. */
        void named(String name, String column, String currency, BigDecimal amount) {
            add(column, currency, amount);
            places.put(name, new Place(column, parts.get(column).size() - 1));
        }
    }

    private final Translation.Rates rates;
    private final Map<String, Place> places;
    private final SortedMap<String, Translation.Translated> columns;

    /**
     * @param required the currencies the books hold whether or not a part carries them
     * @throws ReportRefused when a column holds a currency the set cannot reach, naming them
     */
    StatementTranslation(Translation.Rates rates, Columns gathered, Set<String> required) {
        this.rates = rates;
        this.places = gathered.places;
        this.columns = rates.translate(gathered.parts, required);
        TreeSet<String> holes = new TreeSet<>();
        columns.values().forEach(column -> holes.addAll(column.untranslated()));
        if (!holes.isEmpty()) {
            throw new ReportRefused(ReportRefused.Reason.TRANSLATION_INCOMPLETE, List.copyOf(holes));
        }
    }

    /** The figure named {@code name}, in the reporting currency. */
    CanonicalJson.Obj figure(String name) {
        Place place = places.get(name);
        return Translation.reportingMoney(columns.get(place.column()).lines().get(place.index()).translated()
                .orElseThrow(() -> new IllegalStateException("a complete translation holds every figure")));
    }

    /** Per currency and across them, {@code left}'s translated total is {@code right}'s - each reached through its own difference line. */
    boolean balanced(String left, String right) {
        Translation.Translated one = columns.get(left);
        Translation.Translated other = columns.get(right);
        TreeSet<String> currencies = new TreeSet<>();
        one.groups().forEach(group -> currencies.add(group.currency()));
        other.groups().forEach(group -> currencies.add(group.currency()));
        for (String currency : currencies) {
            if (total(one, currency).compareTo(total(other, currency)) != 0) {
                return false;
            }
        }
        return one.total().map(Translation.Figures::total).orElseThrow().compareTo(other.total().map(Translation.Figures::total).orElseThrow()) == 0;
    }

    private static BigDecimal total(Translation.Translated column, String currency) {
        Optional<Translation.Group> group = column.group(currency);
        return group.flatMap(Translation.Group::figures).map(Translation.Figures::total).orElse(BigDecimal.ZERO);
    }

    /** The content's {@code translation} member: the pinned set, its rates, per currency each column's original, parts, difference and total. */
    CanonicalJson.Obj section(Map<String, Integer> scales) {
        return Translation.section(rates, columns, scales);
    }
}
