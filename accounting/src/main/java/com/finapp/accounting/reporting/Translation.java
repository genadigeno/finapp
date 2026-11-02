package com.finapp.accounting.reporting;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.ValuationRate;
import com.finapp.accounting.ValuationRateReads;
import com.finapp.accounting.ValuationRateSetStatus;
import com.finapp.accounting.ValuationRates;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Translation into the reporting currency at a pinned valuation rate set (`P14-TSK-015`; ADR-0116 decision 3;
 * {@code PHASE_14_PLAN.md} section 13.4; owner decisions C1, C7 A; {@code INV-ACC-11}, {@code INV-MON-03},
 * {@code INV-MON-04}) - pure, reusable by every definition that translates: no clock, no reference to a store, no table
 * of a JDK's; a function of the set and the parts.
 *
 * <p><strong>The arithmetic.</strong> A part - one line group - in a currency {@code C} at the set's {@code EUR/C} rate
 * (units of {@code C} per one euro) is {@code amount / rate}, rounded {@link #ROUNDING} to the {@link #REPORTING_SCALE
 * reporting minor unit}: a correctly rounded exact division, no intermediate rounding and no floating point
 * ({@code INV-MON-01}). {@code EUR} is translated at <strong>identity</strong>, explicitly ({@link Basis#IDENTITY}): rounded
 * to the same scale, so a euro line held at a finer scale is rounded too, by name, and shows its own difference.
 *
 * <p><strong>The translation difference</strong> ({@code INV-ACC-11}, C7 A). Per currency the original total is translated
 * once; the parts, each rounded on its own, do not in general add up to it. The gap is an explicit line of the group,
 * {@code difference = translated total - sum of translated parts}, so {@code parts + difference = total} holds exactly -
 * and is <strong>never absorbed</strong> into the largest part. Because a translated total is a function of the original
 * total alone, a currency whose debits equal its credits has equal translated totals, through the difference lines.
 * Each column of a report is translated on its own, so a translated row need not cross-foot (opening + debits - credits
 * against closing) by a cent or so; the rule is per line group and the totals foot.
 *
 * <p><strong>A currency with no rate</strong> ({@link Basis#NO_RATE}) is neither dropped nor translated at one: its parts
 * keep their originals and translate to nothing, its group is listed in {@link Translated#untranslated()}, and the
 * report-wide {@link Translated#total()} is withheld - a partial total cannot be read by accident. The shape of the trial
 * balance's unmapped row: the gap between a pinned input and the journal is explicit and the run still completes, as the
 * evidence. A definition whose content cannot stand with a hole refuses on {@link Translated#complete()} itself.
 *
 * <p><strong>Never across currencies</strong> ({@code INV-MON-04}): originals are summed within one currency's group; only
 * reporting-currency figures are summed across groups.
 *
 * <p><strong>Identical bytes on every JVM</strong> ({@code INV-ACC-04}): {@link #REPORTING_SCALE} is a literal, never a
 * JDK's currency table (ADR-0003, `P14-TSK-009`'s review, M-3); the parts' scales are the data's own; everything written
 * is an exact integer, a fixed-scale decimal or a code. A change to anything here is a new definition version.
 *
 * <p><strong>How a definition asks</strong>: {@link ReportDefinition#takesValuationRateSet()} (or {@code needs...}), then
 * {@link Rates#pinnedBy} on its snapshot, {@link Rates#translate} per column, and {@link #section} for the content's
 * {@code translation} member. Nothing is posted, no journal line or balance changes (C7 A; {@code INV-ACC-14}).
 */
public final class Translation {

    /** The reporting currency (owner decision C1): the functional one. */
    public static final String REPORTING_CURRENCY = ValuationRates.FUNCTIONAL.code();

    /** The reporting minor unit - {@code EUR}'s two places, a literal: no JDK's currency table decides a FINAL byte. */
    public static final int REPORTING_SCALE = 2;

    /** The one rounding, named in every translated report ({@code INV-MON-03}). */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    /** The written rate's scale - {@code NUMERIC(20,10)}'s, however the value arrived. */
    private static final int RATE_SCALE = 10;

    private static final String IDENTITY_RATE = "1";

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private Translation() {}

    /** How a currency reaches the reporting currency. */
    public enum Basis {
        /** The reporting currency itself: rate one, stated - never a rate from the set. */
        IDENTITY,
        /** The pinned set's {@code EUR/<currency>} rate. */
        PINNED_RATE,
        /** The pinned set values no such currency: untranslated, originals kept, the report-wide total withheld. */
        NO_RATE
    }

    /** The run's valuation rate set is not its period's approved one in the snapshot - nothing is issued. */
    public static final class NotPinned extends IllegalStateException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NotPinned(String detail) {
            super(detail);
        }
    }

    // ------------------------------------------------------------------ the rates

    /**
     * A pinned set's rates, per quote currency, and what the content names of the set.
     *
     * @param setId the set
     * @param month the period it values
     * @param asOf the instant it is as of
     * @param maxAgeHours the staleness bound it was captured under
     * @param pinned its rates by quote currency - with their source, observation instant and snapshot id
     */
    public record Rates(UUID setId, YearMonth month, Instant asOf, int maxAgeHours, SortedMap<String, ValuationRate> pinned) {

        public Rates {
            Objects.requireNonNull(setId, "setId");
            Objects.requireNonNull(month, "month");
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(pinned, "pinned");
            pinned = Collections.unmodifiableSortedMap(new TreeMap<>(pinned));
            pinned.forEach((currency, rate) -> {
                if (!rate.quote().code().equals(currency)) {
                    throw new IllegalArgumentException("a pinned rate is keyed by its quote currency: " + rate.pair());
                }
            });
        }

        /** The rates of {@code set}, whatever its status - {@link #pinnedBy} is the judged way in. */
        public static Rates of(ValuationRateReads.PinnedSet set) {
            TreeMap<String, ValuationRate> byQuote = new TreeMap<>();
            for (ValuationRate rate : set.rates()) {
                if (byQuote.putIfAbsent(rate.quote().code(), rate) != null) {
                    throw new IllegalArgumentException("a valuation rate set values each currency once: " + rate.pair());
                }
            }
            return new Rates(set.id(), set.month(), set.asOf(), set.maxAgeHours(), byQuote);
        }

        /**
         * The rates a run pins - read on {@code snapshot} and judged: the set the run names exists, is {@code APPROVED}, and
         * values the run's own period. The claim judged the same under the period's row (`ReportRuns`); this rank holds for a
         * reclaimed run and for any writer that bypassed the claim.
         *
         * @throws NotPinned when the run names no set, or one that is not its period's approved set
         */
        public static Rates pinnedBy(ValuationRateReads reads, Connection snapshot, ReportInputs inputs) {
            Objects.requireNonNull(reads, "reads");
            Objects.requireNonNull(inputs, "inputs");
            UUID named = inputs.parameters().valuationRateSet()
                    .orElseThrow(() -> new NotPinned("a translated report names its valuation rate set"));
            ValuationRateReads.PinnedSet set = reads.set(snapshot, named)
                    .orElseThrow(() -> new NotPinned("the run's valuation rate set is not in the snapshot"));
            AccountingPeriodId period = inputs.periodId();
            if (set.status() != ValuationRateSetStatus.APPROVED || !set.period().equals(period)) {
                throw new NotPinned("a translated report is read at its period's approved valuation rate set, and the run"
                        + " names another");
            }
            return of(set);
        }

        /** How {@code currency} is translated at this set. */
        public Basis basis(String currency) {
            if (REPORTING_CURRENCY.equals(currency)) {
                return Basis.IDENTITY;
            }
            return pinned.containsKey(currency) ? Basis.PINNED_RATE : Basis.NO_RATE;
        }

        /**
         * {@code parts}, translated: one {@link Line} per part in its order, one {@link Group} per currency in currency
         * order. Total: a currency with no rate is reported, never refused.
         */
        public Translated translate(List<Part> parts) {
            Objects.requireNonNull(parts, "parts");
            List<Line> lines = new ArrayList<>(parts.size());
            Map<String, Grouping> grouping = new TreeMap<>();
            for (Part part : parts) {
                Optional<BigDecimal> translated = translated(part.currency(), part.amount());
                lines.add(new Line(part, translated));
                grouping.computeIfAbsent(part.currency(), ignored -> new Grouping()).add(part.amount(), translated);
            }
            List<Group> groups = new ArrayList<>(grouping.size());
            grouping.forEach((currency, group) -> groups.add(new Group(currency, basis(currency), group.original,
                    translated(currency, group.original).map(total -> Figures.of(group.parts, total)))));
            return new Translated(lines, groups);
        }

        /** {@code amount} in {@code currency} at the reporting scale - empty when the set values no such currency. */
        private Optional<BigDecimal> translated(String currency, BigDecimal amount) {
            return switch (basis(currency)) {
                case IDENTITY -> Optional.of(amount.setScale(REPORTING_SCALE, ROUNDING));
                case PINNED_RATE -> Optional.of(amount.divide(pinned.get(currency).rate(), REPORTING_SCALE, ROUNDING));
                case NO_RATE -> Optional.empty();
            };
        }
    }

    /** One currency's sums on the way to its {@link Group}. */
    private static final class Grouping {
        private BigDecimal original = BigDecimal.ZERO;
        private BigDecimal parts = reporting(BigDecimal.ZERO);

        void add(BigDecimal amount, Optional<BigDecimal> translated) {
            original = original.add(amount);
            parts = parts.add(translated.orElse(BigDecimal.ZERO));
        }
    }

    // ------------------------------------------------------------------ the values

    /**
     * A figure to translate - one line group.
     *
     * @param currency its currency, as the data holds it
     * @param amount the original, at the data's own scale; a balance may be negative
     */
    public record Part(String currency, BigDecimal amount) {

        public Part {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(amount, "amount");
            if (!CURRENCY.matcher(currency).matches()) {
                throw new IllegalArgumentException("a part names an ISO 4217-shaped currency");
            }
        }
    }

    /**
     * A part translated: its original kept beside it ({@code INV-ACC-11}).
     *
     * @param original the part, exactly as given
     * @param translated its reporting-currency figure - empty when the set values no such currency
     */
    public record Line(Part original, Optional<BigDecimal> translated) {

        public Line {
            Objects.requireNonNull(original, "original");
            Objects.requireNonNull(translated, "translated");
        }
    }

    /**
     * Reporting-currency figures: the sum of the translated parts, the explicit difference, the translated total -
     * {@code parts + difference = total} exactly.
     */
    public record Figures(BigDecimal parts, BigDecimal difference, BigDecimal total) {

        public Figures {
            Objects.requireNonNull(parts, "parts");
            Objects.requireNonNull(difference, "difference");
            Objects.requireNonNull(total, "total");
            if (parts.add(difference).compareTo(total) != 0) {
                throw new IllegalArgumentException("the translated parts and the translation difference add up to the"
                        + " translated total");
            }
        }

        static Figures of(BigDecimal parts, BigDecimal total) {
            return new Figures(parts, total.subtract(parts), total);
        }

        Figures plus(Figures other) {
            return new Figures(parts.add(other.parts), difference.add(other.difference), total.add(other.total));
        }
    }

    /**
     * One currency's translation.
     *
     * @param currency the currency - every part of the group is in it
     * @param basis how it reaches the reporting currency
     * @param original the exact total of its parts, in it
     * @param figures its reporting-currency figures - empty when the set values no such currency
     */
    public record Group(String currency, Basis basis, BigDecimal original, Optional<Figures> figures) {

        public Group {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(basis, "basis");
            Objects.requireNonNull(original, "original");
            Objects.requireNonNull(figures, "figures");
        }
    }

    /** One column translated: the lines in the parts' order, the groups in currency order. */
    public record Translated(List<Line> lines, List<Group> groups) {

        public Translated {
            lines = List.copyOf(lines);
            groups = List.copyOf(groups);
        }

        /** Every currency present reached the reporting currency. */
        public boolean complete() {
            return groups.stream().allMatch(group -> group.figures().isPresent());
        }

        /** The currencies with no rate, sorted. */
        public List<String> untranslated() {
            return groups.stream().filter(group -> group.figures().isEmpty()).map(Group::currency).toList();
        }

        /**
         * The report-wide figures across the groups - <strong>withheld</strong> when any currency is untranslated: a partial
         * total is not a total.
         */
        public Optional<Figures> total() {
            if (!complete()) {
                return Optional.empty();
            }
            Figures none = Figures.of(reporting(BigDecimal.ZERO), reporting(BigDecimal.ZERO));
            return Optional.of(groups.stream().map(group -> group.figures().orElseThrow()).reduce(none, Figures::plus));
        }

        /** The group of {@code currency}, when this column holds it. */
        Optional<Group> group(String currency) {
            return groups.stream().filter(group -> group.currency().equals(currency)).findFirst();
        }
    }

    // ------------------------------------------------------------------ the content

    /**
     * The content's {@code translation} member, for {@code columns} translated at {@code rates}: the reporting currency, its
     * scale and the rounding; the pinned set and every rate with its provenance, {@code EUR}'s identity stated; per currency
     * and column the original total beside the translated parts, the difference line and the translated total; the
     * report-wide figures per column, withheld when incomplete; and whether the translation is complete, naming what is not.
     *
     * @param columns the report's translated columns, by name
     * @param scales per currency, the scale its original totals are written at - the report's own, the data's
     */
    public static CanonicalJson.Obj section(Rates rates, SortedMap<String, Translated> columns,
            Map<String, Integer> scales) {
        Objects.requireNonNull(rates, "rates");
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(scales, "scales");
        TreeSet<String> currencies = new TreeSet<>();
        TreeSet<String> untranslated = new TreeSet<>();
        columns.values().forEach(column -> {
            column.groups().forEach(group -> currencies.add(group.currency()));
            untranslated.addAll(column.untranslated());
        });
        CanonicalJson.Arr currenciesOut = CanonicalJson.array();
        for (String currency : currencies) {
            currenciesOut.add(currencySection(rates, currency, columns, scales));
        }
        CanonicalJson.Obj totals = CanonicalJson.object();
        columns.forEach((name, column) -> {
            Optional<Figures> total = column.total();
            if (total.isPresent()) {
                totals.put(name, figures(total.get()));
            } else {
                totals.putNull(name);
            }
        });
        CanonicalJson.Arr untranslatedOut = CanonicalJson.array();
        untranslated.forEach(untranslatedOut::add);
        return CanonicalJson.object()
                .put("reportingCurrency", REPORTING_CURRENCY)
                .put("scale", REPORTING_SCALE)
                .put("rounding", ROUNDING.name())
                .put("rateSet", CanonicalJson.object()
                        .put("id", rates.setId().toString())
                        .put("period", rates.month().toString())
                        .put("asOf", rates.asOf().toString())
                        .put("maxAgeHours", rates.maxAgeHours()))
                .put("rates", ratesSection(rates))
                .put("currencies", currenciesOut)
                .put("total", totals)
                .put("complete", untranslated.isEmpty())
                .put("untranslated", untranslatedOut);
    }

    /** {@code amount} in the reporting currency, as the content writes every translated figure. */
    static CanonicalJson.Obj reportingMoney(BigDecimal amount) {
        return TrialBalance.money(amount, REPORTING_CURRENCY, REPORTING_SCALE);
    }

    private static BigDecimal reporting(BigDecimal amount) {
        return amount.setScale(REPORTING_SCALE, RoundingMode.UNNECESSARY);
    }

    private static CanonicalJson.Arr ratesSection(Rates rates) {
        TreeMap<String, CanonicalJson.Obj> sorted = new TreeMap<>();
        sorted.put(REPORTING_CURRENCY, CanonicalJson.object()
                .put("currency", REPORTING_CURRENCY)
                .put("basis", Basis.IDENTITY.name())
                .put("rate", IDENTITY_RATE));
        rates.pinned().forEach((currency, rate) -> sorted.put(currency, CanonicalJson.object()
                .put("currency", currency)
                .put("basis", Basis.PINNED_RATE.name())
                .put("rate", written(rate))
                .put("pair", rate.pair())
                .put("source", rate.source())
                .put("snapshot", rate.snapshotId().toString())
                .put("observedAt", rate.observedAt().toString())));
        CanonicalJson.Arr out = CanonicalJson.array();
        sorted.values().forEach(out::add);
        return out;
    }

    private static CanonicalJson.Obj currencySection(Rates rates, String currency,
            SortedMap<String, Translated> columns, Map<String, Integer> scales) {
        Basis basis = rates.basis(currency);
        int scale = Objects.requireNonNull(scales.get(currency), "a currency's scale");
        CanonicalJson.Obj perColumn = CanonicalJson.object();
        columns.forEach((name, column) -> {
            Optional<Group> group = column.group(currency);
            if (group.isPresent()) {
                perColumn.put(name, groupSection(group.get(), scale));
            } else {
                perColumn.putNull(name);
            }
        });
        CanonicalJson.Obj out = CanonicalJson.object()
                .put("currency", currency)
                .put("basis", basis.name())
                .put("columns", perColumn);
        switch (basis) {
            case IDENTITY -> out.put("rate", IDENTITY_RATE);
            case PINNED_RATE -> out.put("rate", written(rates.pinned().get(currency)));
            case NO_RATE -> out.putNull("rate");
        }
        return out;
    }

    private static CanonicalJson.Obj groupSection(Group group, int scale) {
        CanonicalJson.Obj out = CanonicalJson.object()
                .put("original", TrialBalance.money(group.original(), group.currency(), scale));
        if (group.figures().isPresent()) {
            Figures figures = group.figures().get();
            out.put("translatedParts", reportingMoney(figures.parts()))
                    .put("translationDifference", reportingMoney(figures.difference()))
                    .put("translatedTotal", reportingMoney(figures.total()));
        } else {
            out.putNull("translatedParts").putNull("translationDifference").putNull("translatedTotal");
        }
        return out;
    }

    private static CanonicalJson.Obj figures(Figures figures) {
        return CanonicalJson.object()
                .put("translatedParts", reportingMoney(figures.parts()))
                .put("translationDifference", reportingMoney(figures.difference()))
                .put("translatedTotal", reportingMoney(figures.total()));
    }

    /** The rate's text at {@code NUMERIC(20,10)}'s scale, exactly - never its arrival's. */
    private static String written(ValuationRate rate) {
        return rate.rate().setScale(RATE_SCALE, RoundingMode.UNNECESSARY).toPlainString();
    }
}
