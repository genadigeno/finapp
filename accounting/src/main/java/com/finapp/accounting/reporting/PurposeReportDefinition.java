package com.finapp.accounting.reporting;

import com.finapp.accounting.JournalReads;
import com.finapp.accounting.ReportKind;
import com.finapp.accounting.TrialBalanceReads;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The reports that are the journal's figures by account purpose (`P14-TSK-017`; {@code PHASE_14_PLAN.md} section 13.6;
 * ADR-0115 decision 1; {@code INV-ACC-02}, {@code INV-ACC-04}, {@code INV-ACC-14}, {@code INV-BAL-05}): {@code REVENUE},
 * {@code RECEIVABLES_PAYABLES}, {@code SETTLEMENT_POSITIONS}, {@code FX_RESULTS}, {@code LOAN_PORTFOLIO},
 * {@code BNPL_EXPOSURE} - each version 1, each a table of named sections of purposes over one window. A row is a (purpose, owner
 * kind, currency); no loan, customer, merchant or line appears.
 *
 * <p><strong>Ledger balances, never live rows</strong> (the entry; `PHASE_14_PLAN.md` section 13.6's "existing lending/bnpl report
 * reads through ports" is superseded, its as-built note says so). It reads only {@link TrialBalanceReads} - the very statements the
 * trial balance reads, so a figure here and a figure there are one read of the journal. {@code accounting} has no edge to lending,
 * bnpl or the ledger, and {@code ReportingNeverWritesTest} refuses a report port's adapter one to their reports.
 *
 * <p><strong>FINAL by construction</strong>: its only sources are the journal lines dated in or before a locked month and the
 * version its lock pinned, both immutable under that lock - so a FINAL run regenerates byte for byte ({@code INV-ACC-04}). A
 * PROVISIONAL run is cut at its claim, as the trial balance's is.
 *
 * <p><strong>Drill-down</strong> ({@code INV-ACC-02}): each row names the GL account the pinned version maps its purpose to, so
 * (GL account, currency, period) is the key the GL activity door lists its lines by; {@code null} when no version maps it, and the
 * lines are then the GL door's unmapped bucket's.
 *
 * <p><strong>No rate set</strong>: none of these takes one (`P14-TSK-015`'s translated layout is an open owner question, and a later
 * change means a new version, which moves the plain run's key). A run naming a set is refused at its claim.
 */
public final class PurposeReportDefinition implements ReportDefinition {

    /** The version of every report of this shape: what {@link #generate} writes. */
    public static final int VERSION = 1;

    /** What a report's figures are: balances through the period's end, or only the period's movement. */
    public enum Window {
        /** The opening from inception, the period's debits and credits, the closing. */
        BALANCES,
        /** The period's debits and credits and their net - income has no opening here (C8: no closing entries). */
        MOVEMENT
    }

    /** A named group of purposes, each as {@code PURPOSE/OWNER_KIND} - the ledger's names ({@code AccountingLedgerVocabularyTest}). */
    public record Section(String name, List<String> keys) {

        public Section {
            Objects.requireNonNull(name, "name");
            keys = List.copyOf(keys);
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("a report section names its purposes");
            }
        }
    }

    private final String code;
    private final Window window;
    private final List<Section> sections;
    private final ReportStatement statement;
    private final TrialBalanceReads reads;

    PurposeReportDefinition(String code, Window window, List<Section> sections, ReportStatement statement,
            TrialBalanceReads reads) {
        this.code = Objects.requireNonNull(code, "code");
        this.window = Objects.requireNonNull(window, "window");
        this.sections = List.copyOf(sections);
        this.statement = Objects.requireNonNull(statement, "statement");
        this.reads = Objects.requireNonNull(reads, "reads");
        Set<String> seen = new TreeSet<>();
        for (Section section : this.sections) {
            for (String key : section.keys()) {
                if (!seen.add(key)) {
                    throw new IllegalArgumentException("a purpose is in one section of a report: " + key);
                }
            }
        }
    }

    // ------------------------------------------------------------------ the six

    /** {@code REVENUE}: the period's fee, interest and spread income. */
    public static PurposeReportDefinition revenue(TrialBalanceReads reads) {
        return new PurposeReportDefinition(ReportKind.REVENUE.name(), Window.MOVEMENT, List.of(
                new Section("FEE", List.of("FEE_REVENUE/OPERATIONAL", "LOAN_FEE_INCOME/OPERATIONAL",
                        "BNPL_MERCHANT_FEE_INCOME/OPERATIONAL", "BNPL_FEE_INCOME/OPERATIONAL")),
                new Section("INTEREST", List.of("LOAN_INTEREST_INCOME/OPERATIONAL", "BNPL_INTEREST_INCOME/OPERATIONAL")),
                new Section("SPREAD", List.of("FX_SPREAD_REVENUE/OPERATIONAL"))),
                statement("Fee, interest and spread income accounts: per purpose and currency the period's debits (a reversal,"
                        + " a waiver, a refund) and credits and their net, debit-positive, so income reads negative.", Window.MOVEMENT),
                reads);
    }

    /** {@code RECEIVABLES_PAYABLES}: what is owed to the platform and what it owes, by purpose. */
    public static PurposeReportDefinition receivablesPayables(TrialBalanceReads reads) {
        return new PurposeReportDefinition(ReportKind.RECEIVABLES_PAYABLES.name(), Window.BALANCES, List.of(
                new Section("RECEIVABLES", List.of("LOAN_PRINCIPAL/LOAN", "LOAN_PRINCIPAL_DUE/LOAN",
                        "LOAN_INTEREST_ACCRUED/LOAN", "LOAN_INTEREST_DUE/LOAN", "LOAN_FEES_DUE/LOAN",
                        "BNPL_PRINCIPAL/BNPL_OBLIGATION", "BNPL_PRINCIPAL_DUE/BNPL_OBLIGATION",
                        "BNPL_INTEREST_ACCRUED/BNPL_OBLIGATION", "BNPL_INTEREST_DUE/BNPL_OBLIGATION",
                        "BNPL_FEES_DUE/BNPL_OBLIGATION", "CHARGEBACK_RECOVERABLE/OPERATIONAL")),
                new Section("PAYABLES", List.of("CUSTOMER_WALLET/CUSTOMER", "MERCHANT_PAYABLE/MERCHANT",
                        "LOAN_CREDIT_BALANCE/LOAN", "BNPL_CREDIT_BALANCE/BNPL_OBLIGATION"))),
                statement("Receivable and payable accounts: per purpose and currency the opening from inception, the period's"
                        + " debits and credits and the closing, debit-positive, so a payable reads negative. Never netted"
                        + " across the two sections.", Window.BALANCES),
                reads);
    }

    /** {@code SETTLEMENT_POSITIONS}: clearing positions and cash, by purpose. */
    public static PurposeReportDefinition settlementPositions(TrialBalanceReads reads) {
        return new PurposeReportDefinition(ReportKind.SETTLEMENT_POSITIONS.name(), Window.BALANCES, List.of(
                new Section("CLEARING", List.of("SETTLEMENT_CLEARING/OPERATIONAL", "INSTANT_CLEARING/OPERATIONAL",
                        "PAYOUT_CLEARING/OPERATIONAL", "FX_PROVIDER_CLEARING/COUNTERPARTY",
                        "CORRIDOR_CLEARING/COUNTERPARTY")),
                new Section("CASH", List.of("CASH_AT_BANK/OPERATIONAL"))),
                statement("Settlement and clearing positions and cash at bank: per purpose and currency the opening from"
                        + " inception, the period's debits and credits and the closing, debit-positive; counterparties are"
                        + " summed under their purpose.", Window.BALANCES),
                reads);
    }

    /** {@code FX_RESULTS}: realised results, the spread and the rounding residual. */
    public static PurposeReportDefinition fxResults(TrialBalanceReads reads) {
        return new PurposeReportDefinition(ReportKind.FX_RESULTS.name(), Window.MOVEMENT, List.of(
                new Section("REALISED", List.of("FX_REALISED_GAINS/OPERATIONAL", "FX_REALISED_LOSSES/OPERATIONAL")),
                new Section("SPREAD", List.of("FX_SPREAD_REVENUE/OPERATIONAL")),
                new Section("RESIDUAL", List.of("ROUNDING_RESIDUAL/OPERATIONAL"))),
                statement("FX results from their posted purposes: realised gains and losses, the spread and the rounding"
                        + " residual, per purpose and currency, the period's debits, credits and net, debit-positive."
                        + " Unrealised results are not posted (C7), so they are not here; no customer quote or executed rate"
                        + " is read.", Window.MOVEMENT),
                reads);
    }

    /** {@code LOAN_PORTFOLIO}: the loan book as of the period's end - every purpose of owner kind {@code LOAN}. */
    public static PurposeReportDefinition loanPortfolio(TrialBalanceReads reads) {
        return new PurposeReportDefinition(ReportKind.LOAN_PORTFOLIO.name(), Window.BALANCES, List.of(
                new Section("PRINCIPAL", List.of("LOAN_PRINCIPAL/LOAN", "LOAN_PRINCIPAL_DUE/LOAN")),
                new Section("INTEREST", List.of("LOAN_INTEREST_ACCRUED/LOAN", "LOAN_INTEREST_DUE/LOAN")),
                new Section("FEES", List.of("LOAN_FEES_DUE/LOAN")),
                new Section("CREDIT_BALANCE", List.of("LOAN_CREDIT_BALANCE/LOAN"))),
                statement("The loan book restated as of the period's end from ledger balances by purpose: per purpose and"
                        + " currency the opening from inception, the period's debits and credits and the closing,"
                        + " debit-positive. Never lending's live report rows; no loan, borrower or status is named.",
                        Window.BALANCES),
                reads);
    }

    /** {@code BNPL_EXPOSURE}: the BNPL book as of the period's end - every purpose of owner kind {@code BNPL_OBLIGATION}. */
    public static PurposeReportDefinition bnplExposure(TrialBalanceReads reads) {
        return new PurposeReportDefinition(ReportKind.BNPL_EXPOSURE.name(), Window.BALANCES, List.of(
                new Section("PRINCIPAL", List.of("BNPL_PRINCIPAL/BNPL_OBLIGATION", "BNPL_PRINCIPAL_DUE/BNPL_OBLIGATION")),
                new Section("INTEREST", List.of("BNPL_INTEREST_ACCRUED/BNPL_OBLIGATION", "BNPL_INTEREST_DUE/BNPL_OBLIGATION")),
                new Section("FEES", List.of("BNPL_FEES_DUE/BNPL_OBLIGATION")),
                new Section("CREDIT_BALANCE", List.of("BNPL_CREDIT_BALANCE/BNPL_OBLIGATION"))),
                statement("The BNPL book restated as of the period's end from ledger balances by purpose: per purpose and"
                        + " currency the opening from inception, the period's debits and credits and the closing,"
                        + " debit-positive. Never bnpl's live report rows; no obligation, customer or merchant is named.",
                        Window.BALANCES),
                reads);
    }

    /** The statement every report of this shape shares but for its rule. */
    static ReportStatement statement(String rule, Window window) {
        return new ReportStatement(
                "The ledger's journal lines by posting date, each read with its ledger account's purpose and owner kind, and"
                        + " the pinned GL mapping version for the drill-down key; no module's report rows, no projection, cache"
                        + " or gauge.",
                rule,
                (window == Window.BALANCES
                        ? "A line counts by its posting date: the opening is every line dated before the period's first day,"
                                + " the closing every line through its last."
                        : "A line counts by its posting date, inside the period's calendar month.")
                        + " A FINAL run is as of its period's lock; a PROVISIONAL one as of its cut, entries created at or"
                        + " before its claim.",
                "Per currency, one scale: the largest its own amounts in this report carry, an exact rescale, no JDK currency"
                        + " table. Never summed across currencies. Minor units in strings beside currency and scale. Not"
                        + " translated: no run of this definition takes a valuation rate set.",
                "FINAL for a period no longer OPEN: byte-identical on regeneration while its lock stands, superseded by an"
                        + " unlock or a reopen. PROVISIONAL for an OPEN period: labelled, reused within its freshness window,"
                        + " never issued.");
    }

    // ------------------------------------------------------------------ the definition

    @Override
    public String code() {
        return code;
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public ReportStatement statement() {
        return statement;
    }

    /** The sections, as named - what {@code AccountingLedgerVocabularyTest} holds to the ledger's enum. */
    public List<Section> sections() {
        return sections;
    }

    /** The window its figures are over. */
    public Window window() {
        return window;
    }

    @Override
    public CanonicalJson.Obj generate(Connection snapshot, ReportInputs inputs) {
        JournalWindow read = JournalWindow.of(reads, snapshot, inputs);
        List<JournalReads.Aggregate> opening = window == Window.BALANCES
                ? reads.before(snapshot, inputs.start(), read.cut())
                : List.of();
        List<JournalReads.Aggregate> movement = reads.within(snapshot, read.range(), read.cut());
        List<PurposeRows.Row> rows = PurposeRows.fold(opening, movement, allKeys());
        Map<String, Integer> scales = PurposeRows.scales(rows);
        CanonicalJson.Arr written = CanonicalJson.array();
        for (Section section : sections) {
            written.add(section(section, rows, scales, read));
        }
        return read.ranges(CanonicalJson.object()
                .put("statement", statement.asValue())
                .put("sign", TrialBalance.SIGN)
                .put("window", window.name())
                .put("sections", written));
    }

    private Set<String> allKeys() {
        Set<String> keys = new TreeSet<>();
        sections.forEach(section -> keys.addAll(section.keys()));
        return keys;
    }

    private CanonicalJson.Obj section(Section section, List<PurposeRows.Row> rows, Map<String, Integer> scales,
            JournalWindow read) {
        Map<String, Total> totals = new TreeMap<>();
        CanonicalJson.Arr out = CanonicalJson.array();
        for (PurposeRows.Row row : rows) {
            if (!section.keys().contains(row.key())) {
                continue;
            }
            int scale = scales.get(row.currency());
            CanonicalJson.Obj line = figures(CanonicalJson.object(), row.opening(), row.movement(), row.currency(), scale)
                    .put("purpose", row.purpose())
                    .put("ownerKind", row.ownerKind())
                    .put("currency", row.currency());
            read.glAccountOf(row.key()).ifPresentOrElse(account -> line.put("glAccount", account),
                    () -> line.putNull("glAccount"));
            out.add(line);
            totals.computeIfAbsent(row.currency(), ignored -> new Total()).add(row);
        }
        CanonicalJson.Arr totalled = CanonicalJson.array();
        totals.forEach((currency, total) -> totalled.add(figures(CanonicalJson.object(), total.opening, total.movement,
                currency, scales.get(currency)).put("currency", currency).put("scale", scales.get(currency))));
        return CanonicalJson.object().put("section", section.name()).put("rows", out).put("totals", totalled);
    }

    /** The window's figures: always the debits, credits and line count; a balance report its opening and closing, else the net. */
    private CanonicalJson.Obj figures(CanonicalJson.Obj out, TrialBalance.Sides opening, TrialBalance.Sides movement,
            String currency, int scale) {
        out.put("debits", TrialBalance.money(movement.debits(), currency, scale))
                .put("credits", TrialBalance.money(movement.credits(), currency, scale))
                .put("lines", movement.lines());
        if (window == Window.BALANCES) {
            BigDecimal closing = opening.net().add(movement.net());
            return out.put("opening", TrialBalance.money(opening.net(), currency, scale))
                    .put("closing", TrialBalance.money(closing, currency, scale));
        }
        return out.put("net", TrialBalance.money(movement.net(), currency, scale));
    }

    /** A section's total in one currency. */
    private static final class Total {
        private TrialBalance.Sides opening = TrialBalance.Sides.NONE;
        private TrialBalance.Sides movement = TrialBalance.Sides.NONE;

        void add(PurposeRows.Row row) {
            opening = opening.plus(row.opening());
            movement = movement.plus(row.movement());
        }
    }
}
