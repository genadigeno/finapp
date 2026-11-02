package com.finapp.accounting.reporting;

import com.finapp.accounting.CounterpartyPositionReads;
import com.finapp.accounting.GlAccount;
import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.MappingRule;
import com.finapp.accounting.ReportKind;
import com.finapp.accounting.TrialBalanceReads;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The {@code GL_ACTIVITY} report definition, version 1 (`P14-TSK-017`; {@code PHASE_14_PLAN.md} section 13.6; ADR-0115 decision 4;
 * {@code INV-ACC-02}, {@code INV-ACC-04}, {@code INV-ACC-06}): the period's movement per GL account and currency under the pinned
 * version, with its components by purpose - and every line the version does not map in an explicit row, never dropped. The lines
 * themselves are not in the content (bounded: the GL door's keyset pages list them): each figure's (GL account, currency, period) is the
 * key {@code GET .../gl/accounts/{code}/activity} drills by, and those pages sum to the figure for a period no longer {@code OPEN}.
 *
 * <p><strong>Nothing nets between counterparties</strong> (`P14-TSK-017`'s review, I-1; {@code INV-RAIL-04}): a component, an unmapped row
 * and a bridge's move are each per counterparty on a {@code COUNTERPARTY}-owned purpose, so {@link CounterpartyPositionReads} stands beside
 * the trial balance's read. An account's own figure and a currency's totals are gross debits and credits: the balance proof, netting nobody.
 *
 * <p><strong>The data's scale, never the JDK's</strong> (`P14-TSK-015`'s review, M-6): it folds {@link TrialBalanceReads#within}
 * itself. {@code GeneralLedger.fold} would write {@code GeneralLedger.zero}'s scale - {@code CurrencyCode.minorUnits}, a JDK table - into
 * FINAL content, unflagged; {@code ReportingNeverWritesTest} now refuses every definition a path to it.
 *
 * <p><strong>The reclassification bridge</strong> lands here ({@link Reclassification}): when the previous period pins another version,
 * the content says what moved between accounts. Only then does it read the opening's lines, from inception.
 *
 * <p>FINAL by construction: the month's lines and the version its lock pinned are immutable under that lock. Report code: it reads
 * through its port on the snapshot it is handed. No rate set.
 */
@RequiredArgsConstructor
public final class GlActivityDefinition implements ReportDefinition {

    /** The definition's code - {@code ReportKind.GL_ACTIVITY}. */
    public static final String CODE = ReportKind.GL_ACTIVITY.name();

    /** The definition's version: what {@link #generate} writes. */
    public static final int VERSION = 1;

    private static final ReportStatement STATEMENT = new ReportStatement(
            "The ledger's journal lines by posting date, each read with its ledger account's purpose and owner kind, a"
                    + " counterparty's account also with the counterparty's code, through the pinned GL mapping version; no"
                    + " projection, cache or gauge.",
            "Per GL account and currency the period's debits, credits and line count, with components by purpose and owner kind,"
                    + " and by counterparty on a counterparty's purpose, because nothing nets between them; every line the version"
                    + " does not map an explicit row. When the previous period pins another version, the balances the change moved"
                    + " between accounts, debit-positive - a restatement, never a figure in the movement.",
            "A line counts by its posting date, inside the period's calendar month. A FINAL run is as of its period's lock; a"
                    + " PROVISIONAL one as of its cut, entries created at or before its claim.",
            "Per currency, one scale: the largest its own amounts in this report carry, an exact rescale, no JDK currency table."
                    + " Never summed across currencies. Minor units in strings beside currency and scale. Not translated: no run of"
                    + " this definition takes a valuation rate set.",
            "FINAL for a period no longer OPEN: byte-identical on regeneration while its lock stands, superseded by an unlock or a"
                    + " reopen. PROVISIONAL for an OPEN period: labelled, reused within its freshness window, never issued.");

    @NonNull private final TrialBalanceReads reads;
    @NonNull private final CounterpartyPositionReads positions;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public ReportStatement statement() {
        return STATEMENT;
    }

    @Override
    public CanonicalJson.Obj generate(Connection snapshot, ReportInputs inputs) {
        JournalWindow read = JournalWindow.of(reads, snapshot, inputs);
        Map<String, MappingRule> rules = read.version().map(GlMappingVersion::rulesByKey).orElse(Map.of());
        List<JournalReads.Aggregate> movement = PurposeRows.perCounterparty(reads.within(snapshot, read.range(), read.cut()),
                positions.within(snapshot, read.range(), read.cut()));

        Map<String, Row> accounts = new TreeMap<>();
        Map<String, Components> unmapped = new TreeMap<>();
        Map<String, TrialBalance.Sides> currencies = new TreeMap<>();
        Map<String, Integer> scales = new TreeMap<>();
        for (JournalReads.Aggregate aggregate : movement) {
            currencies.merge(aggregate.currency(), TrialBalance.Sides.NONE.plus(aggregate.side(), aggregate.amount(),
                    aggregate.lines()), TrialBalance.Sides::plus);
            scales.merge(aggregate.currency(), aggregate.scale(), Math::max);
            MappingRule rule = rules.get(aggregate.key());
            if (rule == null) {
                unmapped.computeIfAbsent(Components.keyOf(aggregate) + "\u0000" + aggregate.currency(),
                        ignored -> new Components(aggregate)).add(aggregate);
                continue;
            }
            GlAccount account = read.version().orElseThrow().account(rule.glAccountCode()).orElseThrow(() ->
                    new IllegalStateException("a coherent version's rule names an account it holds"));
            accounts.computeIfAbsent(account.code() + "\u0000" + aggregate.currency(),
                    ignored -> new Row(account, aggregate.currency())).add(aggregate);
        }
        Optional<Reclassification> bridge = bridge(snapshot, inputs, read);
        bridge.ifPresent(found -> found.currencies().forEach(currency ->
                scales.merge(currency, found.scaleIn(currency), Math::max)));

        CanonicalJson.Arr accountsOut = CanonicalJson.array();
        accounts.values().forEach(row -> accountsOut.add(row.asValue(scales.get(row.currency))));
        CanonicalJson.Arr unmappedOut = CanonicalJson.array();
        unmapped.values().forEach(row -> unmappedOut.add(row.asValue(row.currency, scales.get(row.currency))
                .put("currency", row.currency)));
        CanonicalJson.Arr currenciesOut = CanonicalJson.array();
        currencies.forEach((currency, sides) -> currenciesOut.add(CanonicalJson.object()
                .put("currency", currency)
                .put("scale", scales.get(currency))
                .put("debits", TrialBalance.money(sides.debits(), currency, scales.get(currency)))
                .put("credits", TrialBalance.money(sides.credits(), currency, scales.get(currency)))
                .put("lines", sides.lines())
                .put("balanced", sides.debits().compareTo(sides.credits()) == 0)));
        CanonicalJson.Obj body = read.ranges(CanonicalJson.object()
                .put("statement", STATEMENT.asValue())
                .put("sign", TrialBalance.SIGN)
                .put("accounts", accountsOut)
                .put("unmapped", unmappedOut)
                .put("currencies", currenciesOut)
                .put("fullyMapped", read.version().isPresent() && unmapped.isEmpty()));
        if (bridge.isPresent()) {
            return body.put("reclassification", bridge.get().asValue(scales));
        }
        return body.putNull("reclassification");
    }

    /** The bridge, when the period before pins another version - the opening's lines are read only then. */
    private Optional<Reclassification> bridge(Connection snapshot, ReportInputs inputs, JournalWindow read) {
        Optional<GlMappingVersionId> previous = read.period().previousPin();
        if (previous.isEmpty() || previous.equals(inputs.mappingVersionId())) {
            return Optional.empty();
        }
        return Optional.of(Reclassification.between(previous.get(), reads.version(snapshot, previous.get()),
                inputs.mappingVersionId(), read.version(), PurposeRows.perCounterparty(
                        reads.before(snapshot, inputs.start(), read.cut()),
                        positions.before(snapshot, inputs.start(), read.cut()))));
    }

    /** One purpose's movement in one currency, one counterparty's where it has them. */
    private static final class Components {
        private final String purpose;
        private final String ownerKind;
        private final Optional<String> counterparty;
        private final String currency;
        private TrialBalance.Sides sides = TrialBalance.Sides.NONE;

        Components(JournalReads.Aggregate first) {
            this.purpose = first.purpose();
            this.ownerKind = first.ownerKind();
            this.counterparty = first.counterparty();
            this.currency = first.currency();
        }

        static String keyOf(JournalReads.Aggregate aggregate) {
            return aggregate.key() + "\u0000" + aggregate.counterparty().orElse("");
        }

        void add(JournalReads.Aggregate aggregate) {
            sides = sides.plus(aggregate.side(), aggregate.amount(), aggregate.lines());
        }

        CanonicalJson.Obj asValue(String currency, int scale) {
            return PurposeRows.withCounterparty(CanonicalJson.object()
                    .put("purpose", purpose)
                    .put("ownerKind", ownerKind)
                    .put("debits", TrialBalance.money(sides.debits(), currency, scale))
                    .put("credits", TrialBalance.money(sides.credits(), currency, scale))
                    .put("lines", sides.lines()), counterparty);
        }
    }

    /** One GL account's movement in one currency, with its components. */
    private static final class Row {
        private final GlAccount account;
        private final String currency;
        private final Map<String, Components> components = new TreeMap<>();
        private TrialBalance.Sides sides = TrialBalance.Sides.NONE;

        Row(GlAccount account, String currency) {
            this.account = account;
            this.currency = currency;
        }

        void add(JournalReads.Aggregate aggregate) {
            sides = sides.plus(aggregate.side(), aggregate.amount(), aggregate.lines());
            components.computeIfAbsent(Components.keyOf(aggregate), ignored -> new Components(aggregate)).add(aggregate);
        }

        CanonicalJson.Obj asValue(int scale) {
            CanonicalJson.Arr parts = CanonicalJson.array();
            components.values().forEach(component -> parts.add(component.asValue(currency, scale)));
            return CanonicalJson.object()
                    .put("account", account.code())
                    .put("name", account.name())
                    .put("classification", account.classification().name())
                    .put("normalBalance", account.normalBalance())
                    .put("currency", currency)
                    .put("debits", TrialBalance.money(sides.debits(), currency, scale))
                    .put("credits", TrialBalance.money(sides.credits(), currency, scale))
                    .put("lines", sides.lines())
                    .put("components", parts);
        }
    }
}
