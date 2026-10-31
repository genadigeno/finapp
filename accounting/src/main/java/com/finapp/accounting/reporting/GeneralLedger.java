package com.finapp.accounting.reporting;

import com.finapp.accounting.GlAccount;
import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.MappingRule;
import com.finapp.accounting.Side;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The General Ledger as a function of the journal (`P14-TSK-007`; ADR-0113 decision 2; {@code INV-ACC-02},
 * {@code INV-ACC-06}, {@code INV-ACC-07}, {@code INV-ACC-14}): a period's journal aggregates folded through the mapping
 * version the period is read under into GL figures per GL account and currency; a figure's activity - its figure and a
 * keyset page of its lines; the GL view of one entry.
 *
 * <p><strong>The fold is a partition.</strong> A version's rules are a map keyed by (purpose, owner kind), so every
 * aggregate - and so every journal line - lands in exactly one GL account, or in the explicit unmapped bucket; nothing is
 * dropped and nothing counted twice. The proof is carried in every answer: per currency, the GL's debits, credits and line
 * count against the ledger's own totals over the same range, read by a statement that never joins an account
 * ({@link GlBalances.Agreement}). A drill-down selects exactly a figure's keys, currency and range, so its lines sum to
 * the figure.
 *
 * <p><strong>Report code: it reads, it never writes.</strong> It lives in {@code com.finapp.accounting.reporting}, the
 * package {@code ReportingNeverWritesTest} holds to no writer, and reaches the journal only through {@link JournalReads}
 * on the caller's {@code REPEATABLE READ READ ONLY} snapshot; the version it folds through arrives resolved
 * ({@link PinnedMapping}), so it touches no store. Exact decimal arithmetic only - sums of minor units per scale, never
 * floating point ({@code INV-MON-01}).
 */
public final class GeneralLedger {

    /** A GL account code the version does not hold. */
    public static final class GlAccountNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public GlAccountNotFound(String code, int version) {
            super("mapping version " + version + " holds no GL account " + code);
        }
    }

    private final JournalReads journal;

    public GeneralLedger(JournalReads journal) {
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    /** The period's GL: every figure with movement, the unmapped lines and the agreement per currency. */
    public GlBalances balances(Connection snapshot, PinnedMapping mapping, Optional<String> currency) {
        JournalReads.Range range = mapping.range();
        return fold(mapping, journal.aggregates(snapshot, range), journal.totals(snapshot, range), currency);
    }

    /**
     * One GL account's activity in {@code currency}: its figure and the page of its lines after {@code after}, both from
     * {@code snapshot}. A code the version does not hold is {@link GlAccountNotFound}.
     */
    public GlActivity activity(Connection snapshot, PinnedMapping mapping, String code, String currency,
            Optional<JournalReads.LineCursor> after, int limit) {
        GlAccount account = mapping.content().account(code)
                .orElseThrow(() -> new GlAccountNotFound(code, mapping.version()));
        JournalReads.Range range = mapping.range();
        Set<String> keys = keysOf(mapping.content(), code);
        GlFigure figure = fold(mapping, journal.aggregates(snapshot, range), List.of(), Optional.of(currency))
                .figure(code, currency)
                .orElseGet(() -> new GlFigure(account, currency, zero(currency), zero(currency), 0, List.of(),
                        mapping.versionId(), mapping.version()));
        if (keys.isEmpty()) {
            return new GlActivity(mapping, figure, List.of(), Optional.empty());
        }
        List<JournalReads.Line> page = journal.lines(snapshot,
                new JournalReads.LineQuery(range, keys, currency, after, limit));
        JournalReads.Line previous = null;
        for (JournalReads.Line line : page) {
            // The drill-down is the figure's selection or it proves nothing: a line outside it is the adapter's defect.
            if (!keys.contains(line.key()) || !line.currency().equals(currency) || !range.holds(line.postingDate())
                    || after.map(cursor -> !cursor.precedes(line)).orElse(false)
                    || (previous != null && !previous.cursor().precedes(line))) {
                throw new IllegalStateException("the journal served a drill-down line outside the figure's selection");
            }
            previous = line;
        }
        Optional<JournalReads.LineCursor> next = page.size() == limit && previous != null
                ? Optional.of(previous.cursor())
                : Optional.empty();
        return new GlActivity(mapping, figure, page, next);
    }

    /** The journal entry {@code entryId}, if it exists - its posting month decides the version it is read under. */
    public Optional<JournalReads.Entry> findEntry(Connection snapshot, UUID entryId) {
        return journal.entry(snapshot, entryId);
    }

    /** The GL view of {@code entry} under {@code mapping}, which must be its posting month's. */
    public GlEntry entry(PinnedMapping mapping, JournalReads.Entry entry) {
        if (!YearMonth.from(entry.postingDate()).equals(mapping.period())) {
            throw new IllegalArgumentException("an entry is read under its own posting month's version");
        }
        Map<String, MappingRule> rules = mapping.content().rulesByKey();
        List<GlEntry.MappedLine> lines = new ArrayList<>();
        for (JournalReads.Line line : entry.lines()) {
            lines.add(new GlEntry.MappedLine(line,
                    Optional.ofNullable(rules.get(line.key())).map(MappingRule::glAccountCode)));
        }
        return new GlEntry(mapping, entry, lines);
    }

    /** The mapping keys whose rules name {@code code} - what its figure folds and its drill-down selects. */
    static Set<String> keysOf(GlMappingVersion content, String code) {
        Set<String> keys = new TreeSet<>();
        for (MappingRule rule : content.rules()) {
            if (rule.glAccountCode().equals(code)) {
                keys.add(rule.key());
            }
        }
        return keys;
    }

    // ------------------------------------------------------------------ the fold

    /**
     * The pure fold: {@code aggregates} through {@code mapping}'s rules into figures (with their components) and the
     * unmapped bucket, and per currency the agreement with {@code totals}; narrowed to {@code currency} when named.
     * Every list sorted - every instance reading one snapshot answers byte-identically.
     */
    public static GlBalances fold(PinnedMapping mapping, List<JournalReads.Aggregate> aggregates,
            List<JournalReads.Total> totals, Optional<String> currency) {
        Map<String, MappingRule> rules = mapping.content().rulesByKey();
        Map<String, Figure> figures = new TreeMap<>();
        Map<String, Sides> unmapped = new TreeMap<>();
        Map<String, Agreeing> agreement = new TreeMap<>();
        for (JournalReads.Aggregate aggregate : aggregates) {
            if (currency.isPresent() && !currency.get().equals(aggregate.currency())) {
                continue;
            }
            Agreeing agreeing = agreement.computeIfAbsent(aggregate.currency(), Agreeing::new);
            MappingRule rule = rules.get(aggregate.key());
            if (rule == null) {
                unmapped.computeIfAbsent(aggregate.key() + "\u0000" + aggregate.currency(),
                        ignored -> new Sides(aggregate.purpose(), aggregate.ownerKind(), aggregate.currency()))
                        .add(aggregate.side(), aggregate.amount(), aggregate.lines());
                agreeing.unmapped.add(aggregate.side(), aggregate.amount(), aggregate.lines());
                continue;
            }
            GlAccount account = mapping.content().account(rule.glAccountCode()).orElseThrow(() ->
                    new IllegalStateException("a coherent version's rule names an account it holds"));
            figures.computeIfAbsent(account.code() + "\u0000" + aggregate.currency(),
                    ignored -> new Figure(account, aggregate.currency())).add(aggregate);
            agreeing.gl.add(aggregate.side(), aggregate.amount(), aggregate.lines());
        }
        for (JournalReads.Total total : totals) {
            if (currency.isPresent() && !currency.get().equals(total.currency())) {
                continue;
            }
            agreement.computeIfAbsent(total.currency(), Agreeing::new).ledger
                    .add(total.side(), total.amount(), total.lines());
        }
        List<GlFigure> folded = figures.values().stream()
                .map(figure -> figure.toFigure(mapping))
                .toList();
        List<GlBalances.Unmapped> gaps = unmapped.values().stream()
                .map(sides -> new GlBalances.Unmapped(sides.purpose, sides.ownerKind, sides.currency, sides.debits,
                        sides.credits, sides.lines))
                .toList();
        List<GlBalances.Agreement> agreed = agreement.values().stream().map(Agreeing::toAgreement).toList();
        return new GlBalances(mapping, currency, folded, gaps, agreed);
    }

    /** A currency's zero at its minor unit - so an empty side reads {@code 0.00}, never a bare {@code 0}. */
    static BigDecimal zero(String currency) {
        try {
            return BigDecimal.ZERO.setScale(CurrencyCode.of(currency).minorUnits());
        } catch (IllegalArgumentException unknown) {
            return BigDecimal.ZERO;
        }
    }

    /** Debits, credits and a count, exact. */
    private static final class Sides {
        private final String purpose;
        private final String ownerKind;
        private final String currency;
        private BigDecimal debits;
        private BigDecimal credits;
        private long lines;

        Sides(String purpose, String ownerKind, String currency) {
            this.purpose = purpose;
            this.ownerKind = ownerKind;
            this.currency = currency;
            this.debits = zero(currency);
            this.credits = zero(currency);
        }

        void add(Side side, BigDecimal amount, long count) {
            if (side == Side.DEBIT) {
                debits = debits.add(amount);
            } else {
                credits = credits.add(amount);
            }
            lines = Math.addExact(lines, count);
        }
    }

    /** One figure being folded, with its components by (purpose, owner kind, counterparty). */
    private static final class Figure {
        private final GlAccount account;
        private final String currency;
        private final Sides sides;
        private final Map<String, Sides> components = new TreeMap<>();
        private final Map<String, Optional<String>> counterparties = new TreeMap<>();

        Figure(GlAccount account, String currency) {
            this.account = account;
            this.currency = currency;
            this.sides = new Sides(null, null, currency);
        }

        void add(JournalReads.Aggregate aggregate) {
            sides.add(aggregate.side(), aggregate.amount(), aggregate.lines());
            String key = aggregate.purpose() + "\u0000" + aggregate.ownerKind() + "\u0000"
                    + aggregate.counterparty().orElse("");
            components.computeIfAbsent(key, ignored -> new Sides(aggregate.purpose(), aggregate.ownerKind(), currency))
                    .add(aggregate.side(), aggregate.amount(), aggregate.lines());
            counterparties.putIfAbsent(key, aggregate.counterparty());
        }

        GlFigure toFigure(PinnedMapping mapping) {
            List<GlFigure.Component> folded = new ArrayList<>();
            components.forEach((key, component) -> folded.add(new GlFigure.Component(component.purpose,
                    component.ownerKind, counterparties.get(key), component.debits, component.credits,
                    component.lines)));
            folded.sort(Comparator.comparing(GlFigure.Component::purpose)
                    .thenComparing(GlFigure.Component::ownerKind)
                    .thenComparing(component -> component.counterparty().orElse("")));
            return new GlFigure(account, currency, sides.debits, sides.credits, sides.lines, folded,
                    mapping.versionId(), mapping.version());
        }
    }

    /** One currency's three buckets. */
    private static final class Agreeing {
        private final String currency;
        private final Sides gl;
        private final Sides unmapped;
        private final Sides ledger;

        Agreeing(String currency) {
            this.currency = currency;
            this.gl = new Sides(null, null, currency);
            this.unmapped = new Sides(null, null, currency);
            this.ledger = new Sides(null, null, currency);
        }

        GlBalances.Agreement toAgreement() {
            return new GlBalances.Agreement(currency, gl.debits, gl.credits, gl.lines, unmapped.debits,
                    unmapped.credits, unmapped.lines, ledger.debits, ledger.credits, ledger.lines);
        }
    }
}
