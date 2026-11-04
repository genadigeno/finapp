package com.finapp.accounting.reporting;

import com.finapp.accounting.CounterpartyPositionReads;
import com.finapp.accounting.GlClassification;
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
 * The {@code INCOME_STATEMENT} report definition, version 1 (`P14-TSK-016`; ADR-0115; {@code PHASE_14_PLAN.md} section 13.6; owner decisions C6, C7, C8; {@code INV-ACC-02}, {@code INV-ACC-04}, {@code INV-ACC-05}, {@code INV-ACC-06}): per statement
 * section the period's movement on its revenue and expense accounts; per currency the result; net realised FX; the notes ({@link StatementNotes}).
 *
 * <p><strong>One fold with the balance sheet</strong> ({@link StatementFigures}): its result is the balance sheet's current-period result by construction, and it refuses what that refuses, so the two stand or fall together. It reads the opening for that alone: it shows none, a month's income having no opening
 * (C8), and so nothing of it is restated by a version change, and it carries no bridge.
 *
 * <p><strong>A total adds one normal side</strong>: per classification and currency, so {@code FX_RESULT}, which admits a revenue and an expense, writes two. <strong>Net realised FX</strong> (the owner's decision, 2026-11-03: {@code FX_RESULTS.REALISED} keeps no total, so this carries it): {@link #FX_GAINS} less {@link #FX_LOSSES}, by
 * purpose, whichever account a version maps them to. A revenue less an expense is a result, as the result itself is, and no sum across two normal sides.
 *
 * <p><strong>Write-offs</strong> are {@code LOAN_WRITE_OFF_EXPENSE} and {@code BNPL_WRITE_OFF_EXPENSE} in {@code CREDIT_LOSSES} under the reference chart, a section by the version's rule like any other: their figure is what the journal holds.
 *
 * <p><strong>Translated</strong> when a run names its period's approved set: columns {@code revenue}, {@code expenses}, {@code result} and {@code netRealisedFx}, each on its own, so revenue less expenses need not foot to the result by a cent; the result per currency is the balance sheet's translated part, both a function of the one original. Refused on a real hole. The layout is {@link
 * StatementTranslation}'s, and the owner's to settle.
 *
 * <p><strong>Issued only when closed</strong> ({@link #issuedOnlyWhenClosed()}). Report code: it reads through its ports on the snapshot it is handed and writes nothing. No clock, no random; every list sorted.
 */
@RequiredArgsConstructor
public final class IncomeStatementDefinition implements ReportDefinition {

    /** The definition's code - {@code ReportKind.INCOME_STATEMENT}. */
    public static final String CODE = ReportKind.INCOME_STATEMENT.name();

    /** The definition's version: what {@link #generate} writes. */
    public static final int VERSION = 1;

    /** The realised gains' purpose: the ledger's name ({@code AccountingLedgerVocabularyTest}). */
    public static final String FX_GAINS = "FX_REALISED_GAINS/OPERATIONAL";

    /** The realised losses' purpose: the ledger's name ({@code AccountingLedgerVocabularyTest}). */
    public static final String FX_LOSSES = "FX_REALISED_LOSSES/OPERATIONAL";

    private static final String REVENUE = "revenue";
    private static final String EXPENSES = "expenses";
    private static final String RESULT = "result";
    private static final String NET_REALISED_FX = "netRealisedFx";

    private static final ReportStatement STATEMENT = new ReportStatement(
            "The ledger's journal lines by posting date, each read with its ledger account's purpose and owner kind; the ledger's own totals, read joining no account; the pinned GL mapping version; for the notes, reconciliation's frozen stamps and the close record."
                    + " No projection, cache or gauge, and no closing entry: none is posted (C8).",
            "Per statement section and revenue or expense account, the period's debits, credits and net on the account's normal side. Per currency the result, revenue less expenses, computed from the lines, the balance sheet's current-period result. Net realised FX is realised"
                    + " gains less realised losses. A section totals each classification apart. A line no rule maps, books that do not balance, or figures that are not the ledger's own refuse the run.",
            "A line counts by its posting date, inside the period's calendar month. A FINAL run is as of its period's close, the notes as of the first instant after the last day, UTC. A PROVISIONAL one is as of its cut, entries created at or before its claim, its notes as read when generated.",
            "Per currency, one scale: the largest its own amounts carry, an exact rescale, no JDK currency table. Never summed across currencies. Minor units in strings beside currency and scale, each net on its account's normal side positive. Translated only where a run names the"
                    + " period's approved rate set, every column at that one set, and refused on a real hole.",
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
        Map<String, NetFx> netFx = netRealisedFx(figures);
        Optional<StatementTranslation> translated = inputs.parameters().valuationRateSet().map(named -> translated(figures, netFx,
                Translation.Rates.pinnedBy(rates, snapshot, inputs)));
        CanonicalJson.Obj noted = notes.read(snapshot, inputs);
        CanonicalJson.Obj body = figures.window().ranges(CanonicalJson.object()
                .put("statement", STATEMENT.asValue())
                .put("sign", StatementFigures.SIGN)
                .put("sections", sections(figures, translated))
                .put("result", result(figures))
                .put("netRealisedFx", netRealisedFx(figures, netFx))
                .put("drillDown", CanonicalJson.object()
                        .put("movement", "A row's or a component's debits, credits and lines: GET /v1/operator/accounting/gl/accounts/{glAccount}/activity?period={month}&currency={currency} (ACCOUNTING_READ, audited), whose pages sum to the figure for a period no longer OPEN.")
                        .put("computed", "A section total, the result and net realised FX sum the rows and components above them.")
                        .put("whenTranslated", "A translated figure: the original beside it, the rate in translation.rates, the difference line in translation.currencies."))
                .put("notes", noted));
        translated.ifPresent(translation -> body.put("translation", translation.section(figures.scales())));
        return body;
    }

    // ------------------------------------------------------------------ what it computes

    /** One currency's realised FX, each on its own normal side. */
    private record NetFx(BigDecimal gains, BigDecimal losses) {

        BigDecimal net() {
            return gains.subtract(losses);
        }
    }

    /** By purpose, whichever account a version maps them to: from the rows' components. */
    private static Map<String, NetFx> netRealisedFx(StatementFigures figures) {
        Map<String, NetFx> net = new TreeMap<>();
        for (StatementFigures.Row row : figures.rowsOf(false)) {
            for (StatementFigures.Component component : row.components()) {
                if (component.key().equals(FX_GAINS)) {
                    net.merge(row.currency(), new NetFx(component.movement().net().negate(), BigDecimal.ZERO), IncomeStatementDefinition::plus);
                } else if (component.key().equals(FX_LOSSES)) {
                    net.merge(row.currency(), new NetFx(BigDecimal.ZERO, component.movement().net()), IncomeStatementDefinition::plus);
                }
            }
        }
        return net;
    }

    private static NetFx plus(NetFx one, NetFx other) {
        return new NetFx(one.gains.add(other.gains), one.losses.add(other.losses));
    }

    // ------------------------------------------------------------------ translated

    private static StatementTranslation translated(StatementFigures figures, Map<String, NetFx> netFx, Translation.Rates rates) {
        StatementTranslation.Columns columns = new StatementTranslation.Columns(REVENUE, EXPENSES, RESULT, NET_REALISED_FX);
        for (StatementFigures.Row row : figures.rowsOf(false)) {
            if (row.movement().lines() == 0) {
                continue;
            }
            columns.named(row.key(), row.classification() == GlClassification.REVENUE ? REVENUE : EXPENSES, row.currency(), row.netMovement());
            columns.add(RESULT, row.currency(), row.contributionOf(row.movement()));
        }
        // Every currency the books hold has a result, a zero where this month moved none of its income: a zero needs no rate, and is no hole.
        figures.currencies().forEach(currency -> columns.add(RESULT, currency, BigDecimal.ZERO));
        netFx.forEach((currency, fx) -> {
            columns.add(NET_REALISED_FX, currency, fx.gains());
            columns.add(NET_REALISED_FX, currency, fx.losses().negate());
        });
        return new StatementTranslation(rates, columns, Set.copyOf(figures.currencies()));
    }

    // ------------------------------------------------------------------ the body

    private static CanonicalJson.Arr sections(StatementFigures figures, Optional<StatementTranslation> translated) {
        CanonicalJson.Arr out = CanonicalJson.array();
        for (StatementSection section : StatementSection.values()) {
            if (section.statement() != StatementSection.Statement.INCOME_STATEMENT) {
                continue;
            }
            CanonicalJson.Arr rows = CanonicalJson.array();
            Map<String, BigDecimal> totals = new TreeMap<>();
            for (StatementFigures.Row row : figures.rowsOf(false)) {
                if (row.account().section() == section && row.movement().lines() > 0) {
                    rows.add(row(row, figures.scales().get(row.currency()), translated));
                    totals.merge(row.classification().name() + "\u0000" + row.currency(), row.netMovement(), BigDecimal::add);
                }
            }
            CanonicalJson.Arr totalled = CanonicalJson.array();
            totals.forEach((key, net) -> {
                String[] parts = key.split("\u0000", 2);
                totalled.add(CanonicalJson.object()
                        .put("classification", parts[0])
                        .put("currency", parts[1])
                        .put("scale", figures.scales().get(parts[1]))
                        .put("net", figures.money(net, parts[1])));
            });
            out.add(CanonicalJson.object().put("section", section.name()).put("rows", rows).put("totals", totalled));
        }
        return out;
    }

    private static CanonicalJson.Obj row(StatementFigures.Row row, int scale, Optional<StatementTranslation> translated) {
        String currency = row.currency();
        CanonicalJson.Arr components = CanonicalJson.array();
        for (StatementFigures.Component component : row.components()) {
            if (component.movement().lines() == 0) {
                continue;
            }
            components.add(PurposeRows.withCounterparty(CanonicalJson.object()
                    .put("purpose", component.purpose())
                    .put("ownerKind", component.ownerKind())
                    .put("debits", TrialBalance.money(component.movement().debits(), currency, scale))
                    .put("credits", TrialBalance.money(component.movement().credits(), currency, scale))
                    .put("net", TrialBalance.money(row.onNormalSide(component.movement().net()), currency, scale))
                    .put("lines", component.movement().lines()), component.counterparty()));
        }
        CanonicalJson.Obj out = CanonicalJson.object()
                .put("glAccount", row.account().code())
                .put("name", row.account().name())
                .put("classification", row.classification().name())
                .put("normalBalance", row.account().normalBalance())
                .put("currency", currency)
                .put("debits", TrialBalance.money(row.movement().debits(), currency, scale))
                .put("credits", TrialBalance.money(row.movement().credits(), currency, scale))
                .put("net", TrialBalance.money(row.netMovement(), currency, scale))
                .put("lines", row.movement().lines())
                .put("components", components);
        translated.ifPresent(translation -> out.put("translated", CanonicalJson.object().put("net", translation.figure(row.key()))));
        return out;
    }

    /** Per currency the books hold: the revenue, the expenses and the result, one figure with the balance sheet's. */
    private static CanonicalJson.Arr result(StatementFigures figures) {
        CanonicalJson.Arr out = CanonicalJson.array();
        for (StatementFigures.Equation equation : figures.equations()) {
            String currency = equation.currency();
            BigDecimal revenue = BigDecimal.ZERO;
            BigDecimal expenses = BigDecimal.ZERO;
            for (StatementFigures.Row row : figures.rowsOf(false)) {
                if (row.currency().equals(currency)) {
                    if (row.classification() == GlClassification.REVENUE) {
                        revenue = revenue.add(row.netMovement());
                    } else {
                        expenses = expenses.add(row.netMovement());
                    }
                }
            }
            out.add(CanonicalJson.object()
                    .put("currency", currency)
                    .put("scale", figures.scales().get(currency))
                    .put("revenue", figures.money(revenue, currency))
                    .put("expenses", figures.money(expenses, currency))
                    .put("result", figures.money(equation.currentPeriodResult(), currency)));
        }
        return out;
    }

    private static CanonicalJson.Arr netRealisedFx(StatementFigures figures, Map<String, NetFx> netFx) {
        CanonicalJson.Arr out = CanonicalJson.array();
        netFx.forEach((currency, fx) -> out.add(CanonicalJson.object()
                .put("currency", currency)
                .put("scale", figures.scales().get(currency))
                .put("gains", figures.money(fx.gains(), currency))
                .put("losses", figures.money(fx.losses(), currency))
                .put("net", figures.money(fx.net(), currency))));
        return out;
    }
}
