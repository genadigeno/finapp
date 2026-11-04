package com.finapp.accounting.reporting;

import com.finapp.accounting.CounterpartyPositionReads;
import com.finapp.accounting.GlAccount;
import com.finapp.accounting.GlClassification;
import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.MappingRule;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.TrialBalanceReads;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The one fold both financial statements read (`P14-TSK-016`; {@code INV-ACC-01}, {@code INV-ACC-02}, {@code INV-ACC-06}; owner decision C8), so the income statement's result and the balance sheet's
 * current-period result are one figure by construction: the journal's lines through the period's end, folded through the run's version into rows per GL account and currency, each with its components.
 * Pure once read: exact decimals, sums past {@code long}, sorted, whatever order the reads served. It reads and writes through its ports on the snapshot it is handed.
 *
 * <p><strong>Nothing is a plug.</strong> Per currency, from lines of each classification and nothing else:
 * <pre>
 *   assets              = ASSET rows' closing, debit-normal
 *   liabilities         = LIABILITY rows' closing, credit-normal
 *   equity              = EQUITY rows' closing, credit-normal
 *   retainedEarnings    = -(the net of REVENUE and EXPENSE lines dated before the period)
 *   currentPeriodResult = -(the net of REVENUE and EXPENSE lines dated in it)
 *   difference          = assets - (liabilities + equity + retainedEarnings + currentPeriodResult)
 * </pre>
 * Nothing is solved for. With no closing entries (C8) the journal stays the economic record, and the two computed figures are sums of lines. {@link Equation#difference()} is then <em>tested</em>.
 *
 * <p><strong>How it would know.</strong> Two checks on separate reads, either of which refuses ({@link ReportRefused}): the difference is zero per currency, and {@link TrialBalance#passes()}, the same aggregates through the trial
 * balance's own fold against the ledger's own totals, read by a statement joining no account and bounded by the period's row. The second finds what the first cannot: an imbalance dated earlier and offset in this month, where the
 * equation holds at the closing and retained earnings and the result are each wrong; and a read that lost a balanced pair of lines, where the equation holds and the ledger disagrees.
 *
 * <p><strong>Unclassified refuses.</strong> A line the version does not map has no account and so no section, opening or movement alike; with no version, every line. Not a row in a bucket as the trial balance shows it: a statement cannot
 * stand with a gap, and {@code GL_EQUALS_LEDGER} has already refused the close that a FINAL statement needs.
 *
 * <p><strong>Nothing nets between counterparties</strong> ({@code INV-RAIL-04}): the components are per (purpose, owner kind, counterparty), from {@link PurposeRows#perCounterparty}, fail closed. A row's figure adds them, because the equation is of
 * every line, and {@link Row#counterpartyGross} writes the gross either side beside it, so +1,000 and -1,000 never read as nothing.
 *
 * <p><strong>The cost.</strong> The opening is {@link TrialBalanceReads#before}, from inception, and {@link TrialBalanceReads#ledger} walks the same range: about one trial balance per statement, linear in the journal's lines to date, on {@code
 * journal_entry_by_posting_date}, inside the run's generation budget and {@code GENERATION_TIMED_OUT} past it. Retained earnings has no cheaper honest read; a chained opening is `P14-TSK-009`'s follow-up, sound only under one version.
 *
 * @param window what every journal-derived definition reads first
 * @param balance the same aggregates through the trial balance's fold, its proofs having held
 * @param rows per GL account and currency, sorted
 * @param equations per currency, sorted
 * @param scales per currency, the one scale every amount of it is written at
 * @param openingPerCounterparty the opening's aggregates per counterparty, for the bridge: no second read
 */
record StatementFigures(JournalWindow window, TrialBalance balance, List<Row> rows, List<Equation> equations, Map<String, Integer> scales,
        List<JournalReads.Aggregate> openingPerCounterparty) {

    /** How every balance is written: on its account's normal side positive, so a figure against it reads negative and is never moved to another section. */
    static final String SIGN = "NORMAL_SIDE_POSITIVE";

    /** One purpose's figures in a row, and one counterparty's where it has them. */
    record Component(String purpose, String ownerKind, Optional<String> counterparty, TrialBalance.Sides opening, TrialBalance.Sides movement) {

        Component {
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(ownerKind, "ownerKind");
            Objects.requireNonNull(counterparty, "counterparty");
            Objects.requireNonNull(opening, "opening");
            Objects.requireNonNull(movement, "movement");
        }

        String key() {
            return purpose + "/" + ownerKind;
        }
    }

    /** One GL account in one currency: the lines before the period, the period's, and what composes them. */
    record Row(GlAccount account, String currency, TrialBalance.Sides opening, TrialBalance.Sides movement, List<Component> components) {

        Row {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(opening, "opening");
            Objects.requireNonNull(movement, "movement");
            components = List.copyOf(components);
        }

        GlClassification classification() {
            return account.classification();
        }

        /** {@code debitPositive} on this account's normal side. */
        BigDecimal onNormalSide(BigDecimal debitPositive) {
            return classification().debitNormal() ? debitPositive : debitPositive.negate();
        }

        BigDecimal openingBalance() {
            return onNormalSide(opening.net());
        }

        BigDecimal netMovement() {
            return onNormalSide(movement.net());
        }

        BigDecimal closingBalance() {
            return onNormalSide(opening.net().add(movement.net()));
        }

        /** This row's part in a period's result: a revenue adds, an expense takes away. Only meaningful of a revenue or an expense. */
        BigDecimal contributionOf(TrialBalance.Sides sides) {
            return sides.net().negate();
        }

        /** The components' closings on the normal side and against it, each positive - when a counterparty holds any of them. */
        Optional<Gross> counterpartyGross() {
            if (components.stream().allMatch(component -> component.counterparty().isEmpty())) {
                return Optional.empty();
            }
            BigDecimal on = BigDecimal.ZERO;
            BigDecimal against = BigDecimal.ZERO;
            for (Component component : components) {
                BigDecimal closing = onNormalSide(component.opening().net().add(component.movement().net()));
                if (closing.signum() >= 0) {
                    on = on.add(closing);
                } else {
                    against = against.add(closing.negate());
                }
            }
            return Optional.of(new Gross(on, against));
        }

        /** What this row's key names in a translation's places. */
        String key() {
            return account.code() + "\u0000" + currency;
        }
    }

    /** A row's counterparties' closings, gross either side: what its net figure must never hide. */
    record Gross(BigDecimal onNormalSide, BigDecimal againstNormalSide) {}

    /** The accounting equation in one currency, each side summed from its own lines. */
    record Equation(String currency, BigDecimal assets, BigDecimal liabilities, BigDecimal equity, BigDecimal retainedEarnings, BigDecimal currentPeriodResult) {

        Equation {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(assets, "assets");
            Objects.requireNonNull(liabilities, "liabilities");
            Objects.requireNonNull(equity, "equity");
            Objects.requireNonNull(retainedEarnings, "retainedEarnings");
            Objects.requireNonNull(currentPeriodResult, "currentPeriodResult");
        }

        BigDecimal liabilitiesEquityAndResult() {
            return liabilities.add(equity).add(retainedEarnings).add(currentPeriodResult);
        }

        /** Zero, or the run is refused: never closed by adjusting a side. */
        BigDecimal difference() {
            return assets.subtract(liabilitiesEquityAndResult());
        }
    }

    /**
     * The figures for {@code inputs}, on {@code snapshot}.
     *
     * @throws ReportRefused when the period is not closed, a line is unclassified, the figures are not the ledger's, or the books do not balance
     * @throws TrialBalanceDefinition.PinMismatch when a FINAL run's version is not its period's pin
     * @throws IllegalStateException when the journal's two reads disagree on one snapshot, or a counterparty's position names none
     */
    static StatementFigures read(TrialBalanceReads reads, CounterpartyPositionReads positions, Connection snapshot, ReportInputs inputs) {
        JournalWindow window = JournalWindow.of(reads, snapshot, inputs);
        // The claim refused this under the period's row; this rank holds for a reclaimed run and for any writer that bypassed it.
        if (inputs.basis() == RunBasis.FINAL && window.period().status() != PeriodStatus.CLOSED) {
            throw new ReportRefused(ReportRefused.Reason.NOT_CLOSED, List.of());
        }
        List<JournalReads.Aggregate> opening = reads.before(snapshot, inputs.start(), window.cut());
        List<JournalReads.Aggregate> movement = reads.within(snapshot, window.range(), window.cut());
        TrialBalance balance = TrialBalance.fold(inputs.period(), window.range(), window.period(), inputs.mappingVersionId(), window.version(), opening, movement,
                reads.ledger(snapshot, window.period(), window.cut()));
        refuseWhatCannotStand(balance);
        List<JournalReads.Aggregate> openingSplit = PurposeRows.perCounterparty(opening, positions.before(snapshot, inputs.start(), window.cut()));
        List<JournalReads.Aggregate> movementSplit = PurposeRows.perCounterparty(movement, positions.within(snapshot, window.range(), window.cut()));
        List<Row> rows = rows(balance, window.version(), openingSplit, movementSplit);
        List<Equation> equations = equations(balance, rows);
        List<String> unbalanced = equations.stream().filter(equation -> equation.difference().signum() != 0).map(Equation::currency).toList();
        if (!unbalanced.isEmpty()) {
            throw new ReportRefused(ReportRefused.Reason.OUT_OF_BALANCE, unbalanced);
        }
        return new StatementFigures(window, balance, rows, equations, scales(balance, rows), openingSplit);
    }

    private static void refuseWhatCannotStand(TrialBalance balance) {
        if (!balance.unmapped().isEmpty()) {
            throw new ReportRefused(ReportRefused.Reason.UNCLASSIFIED, new ArrayList<>(new TreeSet<>(balance.unmapped().stream()
                    .map(row -> row.purpose() + "/" + row.ownerKind()).toList())));
        }
        if (!balance.equalsLedger() || !balance.rangeIsThePeriods()) {
            throw new ReportRefused(ReportRefused.Reason.NOT_THE_LEDGERS, balance.disagreeing());
        }
        if (!balance.outOfBalance().isEmpty()) {
            throw new ReportRefused(ReportRefused.Reason.OUT_OF_BALANCE, balance.outOfBalance());
        }
    }

    /** {@code balance}'s account rows, each with the components {@code rules} folds into it. */
    private static List<Row> rows(TrialBalance balance, Optional<GlMappingVersion> version, List<JournalReads.Aggregate> opening, List<JournalReads.Aggregate> movement) {
        Map<String, MappingRule> rules = version.map(GlMappingVersion::rulesByKey).orElse(Map.of());
        Map<String, List<Component>> components = new TreeMap<>();
        for (PurposeRows.Row row : PurposeRows.fold(opening, movement, rules.keySet())) {
            components.computeIfAbsent(rules.get(row.key()).glAccountCode() + "\u0000" + row.currency(), ignored -> new ArrayList<>())
                    .add(new Component(row.purpose(), row.ownerKind(), row.counterparty(), row.opening(), row.movement()));
        }
        return balance.accounts().stream()
                .map(row -> new Row(row.account(), row.currency(), row.opening(), row.movement(),
                        components.getOrDefault(row.account().code() + "\u0000" + row.currency(), List.of())))
                .toList();
    }

    /** Every currency the books hold - a row in one, or a ledger line. */
    private static List<Equation> equations(TrialBalance balance, List<Row> rows) {
        List<Equation> equations = new ArrayList<>();
        for (TrialBalance.CurrencyCheck check : balance.currencies()) {
            String currency = check.currency();
            BigDecimal assets = BigDecimal.ZERO;
            BigDecimal liabilities = BigDecimal.ZERO;
            BigDecimal equity = BigDecimal.ZERO;
            BigDecimal retained = BigDecimal.ZERO;
            BigDecimal result = BigDecimal.ZERO;
            for (Row row : rows) {
                if (!row.currency().equals(currency)) {
                    continue;
                }
                switch (row.classification()) {
                    case ASSET -> assets = assets.add(row.closingBalance());
                    case LIABILITY -> liabilities = liabilities.add(row.closingBalance());
                    case EQUITY -> equity = equity.add(row.closingBalance());
                    case REVENUE, EXPENSE -> {
                        retained = retained.add(row.contributionOf(row.opening()));
                        result = result.add(row.contributionOf(row.movement()));
                    }
                }
            }
            equations.add(new Equation(currency, assets, liabilities, equity, retained, result));
        }
        return equations;
    }

    private static Map<String, Integer> scales(TrialBalance balance, List<Row> rows) {
        Map<String, Integer> scales = new TreeMap<>();
        for (TrialBalance.CurrencyCheck check : balance.currencies()) {
            scales.put(check.currency(), scale(List.of(check.opening(), check.movement(), check.ledgerOpening(), check.ledgerMovement(), check.ledgerClosing())));
        }
        for (Row row : rows) {
            scales.merge(row.currency(), scale(List.of(row.opening(), row.movement())), Math::max);
        }
        return scales;
    }

    private static int scale(List<TrialBalance.Sides> sides) {
        return sides.stream().mapToInt(PurposeRows::scaleOf).max().orElse(0);
    }

    /** The currencies the books hold, sorted - what a translation requires. */
    List<String> currencies() {
        return equations.stream().map(Equation::currency).toList();
    }

    /** The rows of the balance sheet's three classifications, or the income statement's two, sorted. */
    List<Row> rowsOf(boolean balanceSheet) {
        return rows.stream().filter(row -> row.classification().balanceSheet() == balanceSheet).toList();
    }

    /** A sum at {@code currency}'s scale. */
    CanonicalJson.Obj money(BigDecimal amount, String currency) {
        return TrialBalance.money(amount, currency, scales.get(currency));
    }
}
