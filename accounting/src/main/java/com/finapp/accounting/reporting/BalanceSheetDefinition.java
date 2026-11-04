package com.finapp.accounting.reporting;

import com.finapp.accounting.CounterpartyPositionReads;
import com.finapp.accounting.GlClassification;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.ReportKind;
import com.finapp.accounting.StatementSection;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.ValuationRateReads;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The {@code BALANCE_SHEET} report definition, version 1 (`P14-TSK-016`; ADR-0115; {@code PHASE_14_PLAN.md} section 13.6; owner decisions C7, C8; {@code INV-ACC-02}, {@code INV-ACC-04}, {@code INV-ACC-05}, {@code
 * INV-ACC-06}): per statement section the rows of its GL accounts, opening to closing; per currency the equation, each side summed from lines of its own classification and tested, retained earnings and the current-period result computed
 * and never posted ({@link StatementFigures}, whose javadoc holds the argument); the bridge when the version changed; the notes ({@link StatementNotes}).
 *
 * <p><strong>Sections are data.</strong> A purpose reaches its section through the pinned version's rule and its account: this class names no purpose. A section's totals are per classification and currency, so a section admitting two ({@code SUSPENSE}) writes two and
 * never adds across normal sides. A figure against its normal side reads negative and is never moved, so suspense in net debit is a negative liability under the reference chart, its gross in the note.
 *
 * <p><strong>The bridge</strong> ({@link Reclassification}): when the previous period pins another version, the opening restates the previous closing per account; {@code reclassification} says what moved, per counterparty, from the aggregates the rows were folded from. {@code null} when nothing could.
 *
 * <p><strong>Translated</strong> when a run names its period's approved set: two columns, {@code assets} and {@code liabilitiesEquityAndResult}, equal per currency and across them through their difference lines, {@code translation.balanced} computed and refused if false. The layout is {@link StatementTranslation}'s, and the owner's to settle.
 *
 * <p><strong>Issued only when closed</strong> ({@link #issuedOnlyWhenClosed()}): a run of a locked period is refused at its claim. FINAL by construction under {@code CLOSED}. Report code: it reads through its ports on the snapshot it is handed and writes nothing. No clock, no random; every list sorted.
 */
@RequiredArgsConstructor
public final class BalanceSheetDefinition implements ReportDefinition {

    /** The definition's code - {@code ReportKind.BALANCE_SHEET}. */
    public static final String CODE = ReportKind.BALANCE_SHEET.name();

    /** The definition's version: what {@link #generate} writes. */
    public static final int VERSION = 1;

    private static final String ASSETS = "assets";
    private static final String LIABILITIES_EQUITY_AND_RESULT = "liabilitiesEquityAndResult";

    private static final ReportStatement STATEMENT = new ReportStatement(
            "The ledger's journal lines by posting date, each read with its ledger account's purpose and owner kind, a counterparty's account also with the counterparty's code; the ledger's own totals, read joining no account; the pinned GL mapping"
                    + " version; for the notes, reconciliation's frozen stamps and the close record. No projection, cache or gauge, and no closing entry: none is posted (C8).",
            "Per statement section and GL account, the opening from inception, the period's debits and credits and the closing. Per currency assets equal liabilities, equity, retained earnings and the current-period result, each side summed from lines of its own"
                    + " classification and tested, never solved for: retained earnings the revenue and expense lines dated before the period, the result those dated in it. A line no rule maps, books that do not balance, or figures that are not the ledger's own refuse the run.",
            "A line counts by its posting date: the opening is every line dated before the period's first day, the closing every line through its last. A FINAL run is as of its period's close, the notes as of the first instant after the last day, UTC. A PROVISIONAL one is"
                    + " as of its cut, entries created at or before its claim, its notes as read when generated.",
            "Per currency, one scale: the largest its own amounts carry, an exact rescale, no JDK currency table. Never summed across currencies. Minor units in strings beside currency and scale, each balance on its account's normal side positive. Translated only where a run"
                    + " names the period's approved rate set, every column at that one set, and refused on a real hole.",
            "FINAL only for a CLOSED period: byte-identical on regeneration while its close stands, superseded by a reopen. Refused for a period locked and not yet closed, because the close record it discloses is still being decided. PROVISIONAL for an OPEN period: labelled,"
                    + " reused within its freshness window, never issued.");

    @NonNull private final TrialBalanceReads reads;
    @NonNull private final CounterpartyPositionReads positions;
    @NonNull private final ValuationRateReads rates;
    @NonNull private final StatementNotes notes;

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
    public boolean takesValuationRateSet() {
        return true;
    }

    @Override
    public boolean issuedOnlyWhenClosed() {
        return true;
    }

    @Override
    public CanonicalJson.Obj generate(Connection snapshot, ReportInputs inputs) {
        StatementFigures figures = StatementFigures.read(reads, positions, snapshot, inputs);
        Optional<Reclassification> bridge = bridge(snapshot, inputs, figures);
        Map<String, Integer> scales = new TreeMap<>(figures.scales());
        bridge.ifPresent(found -> found.currencies().forEach(currency -> scales.merge(currency, found.scaleIn(currency), Math::max)));

        Optional<StatementTranslation> translated = inputs.parameters().valuationRateSet().map(named -> translated(figures,
                Translation.Rates.pinnedBy(rates, snapshot, inputs)));
        CanonicalJson.Obj noted = notes.read(snapshot, inputs);

        CanonicalJson.Obj body = figures.window().ranges(CanonicalJson.object()
                .put("statement", STATEMENT.asValue())
                .put("sign", StatementFigures.SIGN)
                .put("sections", sections(figures, scales, translated))
                .put("equation", equation(figures, scales, translated))
                .put("retainedEarnings", computed(figures, scales, true))
                .put("currentPeriodResult", computed(figures, scales, false))
                .put("drillDown", drillDown())
                .put("notes", noted));
        if (bridge.isPresent()) {
            body.put("reclassification", bridge.get().asValue(scales));
        } else {
            body.putNull("reclassification");
        }
        translated.ifPresent(translation -> body.put("translation", translation.section(scales)
                .put("balanced", translation.balanced(ASSETS, LIABILITIES_EQUITY_AND_RESULT))));
        return body;
    }

    /** The bridge, when the period before pins another version - from the aggregates the rows were folded from, per counterparty. */
    private Optional<Reclassification> bridge(Connection snapshot, ReportInputs inputs, StatementFigures figures) {
        Optional<GlMappingVersionId> previous = figures.window().period().previousPin();
        if (previous.isEmpty() || previous.equals(inputs.mappingVersionId())) {
            return Optional.empty();
        }
        return Optional.of(Reclassification.between(previous.get(), reads.version(snapshot, previous.get()), inputs.mappingVersionId(), figures.window().version(),
                figures.openingPerCounterparty()));
    }

    // ------------------------------------------------------------------ translated

    private static StatementTranslation translated(StatementFigures figures, Translation.Rates rates) {
        StatementTranslation.Columns columns = new StatementTranslation.Columns(ASSETS, LIABILITIES_EQUITY_AND_RESULT);
        for (StatementFigures.Row row : figures.rowsOf(true)) {
            columns.named(row.key(), row.classification() == GlClassification.ASSET ? ASSETS : LIABILITIES_EQUITY_AND_RESULT, row.currency(), row.closingBalance());
        }
        for (StatementFigures.Equation equation : figures.equations()) {
            columns.named("retained\u0000" + equation.currency(), LIABILITIES_EQUITY_AND_RESULT, equation.currency(), equation.retainedEarnings());
            columns.named("result\u0000" + equation.currency(), LIABILITIES_EQUITY_AND_RESULT, equation.currency(), equation.currentPeriodResult());
        }
        StatementTranslation translation = new StatementTranslation(rates, columns, Set.copyOf(figures.currencies()));
        if (!translation.balanced(ASSETS, LIABILITIES_EQUITY_AND_RESULT)) {
            throw new ReportRefused(ReportRefused.Reason.OUT_OF_BALANCE, figures.currencies());
        }
        return translation;
    }

    // ------------------------------------------------------------------ the body

    private static CanonicalJson.Arr sections(StatementFigures figures, Map<String, Integer> scales, Optional<StatementTranslation> translated) {
        CanonicalJson.Arr out = CanonicalJson.array();
        for (StatementSection section : StatementSection.values()) {
            if (section.statement() != StatementSection.Statement.BALANCE_SHEET) {
                continue;
            }
            CanonicalJson.Arr rows = CanonicalJson.array();
            Map<String, BigDecimal> totals = new TreeMap<>();
            for (StatementFigures.Row row : figures.rowsOf(true)) {
                if (row.account().section() == section) {
                    rows.add(row(row, scales.get(row.currency()), translated));
                    totals.merge(row.classification().name() + "\u0000" + row.currency(), row.closingBalance(), BigDecimal::add);
                }
            }
            CanonicalJson.Arr totalled = CanonicalJson.array();
            totals.forEach((key, closing) -> {
                String[] parts = key.split("\u0000", 2);
                totalled.add(CanonicalJson.object()
                        .put("classification", parts[0])
                        .put("currency", parts[1])
                        .put("scale", scales.get(parts[1]))
                        .put("closing", TrialBalance.money(closing, parts[1], scales.get(parts[1]))));
            });
            out.add(CanonicalJson.object().put("section", section.name()).put("rows", rows).put("totals", totalled));
        }
        return out;
    }

    private static CanonicalJson.Obj row(StatementFigures.Row row, int scale, Optional<StatementTranslation> translated) {
        String currency = row.currency();
        CanonicalJson.Arr components = CanonicalJson.array();
        for (StatementFigures.Component component : row.components()) {
            components.add(PurposeRows.withCounterparty(CanonicalJson.object()
                    .put("purpose", component.purpose())
                    .put("ownerKind", component.ownerKind())
                    .put("opening", TrialBalance.money(row.onNormalSide(component.opening().net()), currency, scale))
                    .put("debits", TrialBalance.money(component.movement().debits(), currency, scale))
                    .put("credits", TrialBalance.money(component.movement().credits(), currency, scale))
                    .put("closing", TrialBalance.money(row.onNormalSide(component.opening().net().add(component.movement().net())), currency, scale))
                    .put("lines", component.movement().lines())
                    .put("openingLines", component.opening().lines()), component.counterparty()));
        }
        CanonicalJson.Obj out = CanonicalJson.object()
                .put("glAccount", row.account().code())
                .put("name", row.account().name())
                .put("classification", row.classification().name())
                .put("normalBalance", row.account().normalBalance())
                .put("currency", currency)
                .put("opening", TrialBalance.money(row.openingBalance(), currency, scale))
                .put("debits", TrialBalance.money(row.movement().debits(), currency, scale))
                .put("credits", TrialBalance.money(row.movement().credits(), currency, scale))
                .put("closing", TrialBalance.money(row.closingBalance(), currency, scale))
                .put("lines", row.movement().lines())
                .put("openingLines", row.opening().lines())
                .put("components", components);
        Optional<StatementFigures.Gross> gross = row.counterpartyGross();
        if (gross.isPresent()) {
            out.put("counterpartyGross", CanonicalJson.object()
                    .put("onNormalSide", TrialBalance.money(gross.get().onNormalSide(), currency, scale))
                    .put("againstNormalSide", TrialBalance.money(gross.get().againstNormalSide(), currency, scale)));
        } else {
            out.putNull("counterpartyGross");
        }
        translated.ifPresent(translation -> out.put("translated", CanonicalJson.object().put("closing", translation.figure(row.key()))));
        return out;
    }

    private static CanonicalJson.Arr equation(StatementFigures figures, Map<String, Integer> scales, Optional<StatementTranslation> translated) {
        CanonicalJson.Arr out = CanonicalJson.array();
        for (StatementFigures.Equation equation : figures.equations()) {
            String currency = equation.currency();
            int scale = scales.get(currency);
            TrialBalance.CurrencyCheck check = figures.balance().currencies().stream().filter(held -> held.currency().equals(currency)).findFirst().orElseThrow();
            CanonicalJson.Obj one = CanonicalJson.object()
                    .put("currency", currency)
                    .put("scale", scale)
                    .put("assets", TrialBalance.money(equation.assets(), currency, scale))
                    .put("liabilities", TrialBalance.money(equation.liabilities(), currency, scale))
                    .put("equity", TrialBalance.money(equation.equity(), currency, scale))
                    .put("retainedEarnings", TrialBalance.money(equation.retainedEarnings(), currency, scale))
                    .put("currentPeriodResult", TrialBalance.money(equation.currentPeriodResult(), currency, scale))
                    .put("liabilitiesEquityAndResult", TrialBalance.money(equation.liabilitiesEquityAndResult(), currency, scale))
                    .put("difference", TrialBalance.money(equation.difference(), currency, scale))
                    .put("journal", sides(check.closing(), currency, scale))
                    .put("ledger", sides(check.ledgerClosing(), currency, scale));
            translated.ifPresent(translation -> one.put("translated", CanonicalJson.object()
                    .put("retainedEarnings", translation.figure("retained\u0000" + currency))
                    .put("currentPeriodResult", translation.figure("result\u0000" + currency))));
            out.add(one);
        }
        return out;
    }

    private static CanonicalJson.Obj sides(TrialBalance.Sides sides, String currency, int scale) {
        return CanonicalJson.object()
                .put("debits", TrialBalance.money(sides.debits(), currency, scale))
                .put("credits", TrialBalance.money(sides.credits(), currency, scale))
                .put("lines", sides.lines());
    }

    /**
     * A computed figure, shown as what composes it: each revenue and expense account's part, a revenue adding and an expense taking away. {@code before} the lines dated before the period (retained earnings), else the period's (the result).
     */
    private static CanonicalJson.Obj computed(StatementFigures figures, Map<String, Integer> scales, boolean before) {
        CanonicalJson.Arr components = CanonicalJson.array();
        for (StatementFigures.Row row : figures.rowsOf(false)) {
            TrialBalance.Sides sides = before ? row.opening() : row.movement();
            if (sides.lines() == 0) {
                continue;
            }
            int scale = scales.get(row.currency());
            components.add(CanonicalJson.object()
                    .put("glAccount", row.account().code())
                    .put("classification", row.classification().name())
                    .put("currency", row.currency())
                    .put("debits", TrialBalance.money(sides.debits(), row.currency(), scale))
                    .put("credits", TrialBalance.money(sides.credits(), row.currency(), scale))
                    .put("lines", sides.lines())
                    .put("amount", TrialBalance.money(row.contributionOf(sides), row.currency(), scale)));
        }
        return CanonicalJson.object()
                .put("rule", before
                        ? "Computed, never posted (C8): the revenue and expense lines dated before the period, from inception, a revenue adding and an expense taking away."
                        : "Computed, never posted (C8): the revenue and expense lines dated in the period, a revenue adding and an expense taking away. The income statement's result, one fold.")
                .put("components", components);
    }

    /** How to get from a figure to its lines: this version's words. */
    private static CanonicalJson.Obj drillDown() {
        return CanonicalJson.object()
                .put("movement", "A row's or a component's debits, credits and lines: GET /v1/operator/accounting/gl/accounts/{glAccount}/activity?period={month}&currency={currency} (ACCOUNTING_READ, audited), whose pages sum to the figure for a period no longer OPEN.")
                .put("balance", "A row's opening, and so its closing: GET /v1/operator/accounting/report-runs/{runId}/lines?account={glAccount}&currency={currency}&month={month} (ACCOUNTING_READ, audited), under this run's version, walking back a month at a time until the row's openingLines are counted.")
                .put("computed", "retainedEarnings and currentPeriodResult list their components, each a revenue or expense account's lines by the two routes above. A section total and each side of the equation sum the rows above them.")
                .put("reclassification", "A bridge reads debit-positive, as GL_ACTIVITY's does, and a line names its purpose and counterparty, so a move is the lines of its purpose. A move's to and an account's opening: this run's GET /v1/operator/accounting/report-runs/{runId}/lines (ACCOUNTING_READ, audited), walked back from the month before this period as a row's opening is."
                        + " A move's from and an account's previousClosing stand under the version previousPeriodPin names: that door on a run of the previous month, whose TRIAL_BALANCE is FINAL once that month has closed, since an account this version retired is accounting.NotFound on this run. A null from names no account: its lines are its to's.")
                .put("whenTranslated", "A translated figure: the original beside it, the rate in translation.rates, the difference line in translation.currencies.");
    }
}
