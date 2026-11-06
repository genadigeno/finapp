package com.finapp.app.accounting;

import com.finapp.app.accounting.AccountingBatteryWorld.Chart;
import com.finapp.app.accounting.AccountingBatteryWorld.ChartRow;
import com.finapp.app.accounting.AccountingBatteryWorld.Entry;
import com.finapp.app.accounting.AccountingBatteryWorld.Kind;
import com.finapp.app.accounting.AccountingBatteryWorld.Line;
import com.finapp.app.accounting.AccountingBatteryWorld.ShapeLine;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The accounting battery's oracle (`P14-TST-002`): every figure the trial balance, the GL, the two statements, their translation
 * and rule conformance write for each of the twelve months, computed <strong>from the generated lines alone</strong> - the
 * {@link AccountingBatteryWorld}'s entries, chart, rates and shapes - as facts keyed the way {@code AccountingBatteryBooks} reads
 * the production output, so the two compare as maps.
 *
 * <p><strong>It reaches no production fold.</strong> Not {@code TrialBalance.fold}, {@code StatementFigures}, {@code
 * GeneralLedger.fold}, {@code Translation} or {@code RuleConformance}: it names no class outside this one and the world (JDK
 * aside) and reaches nothing by reflection, which {@code AccountingBatteryHermeticTest#theOracleSharesNoCode} holds by reading the
 * class files. It is written from what the definitions <em>say</em> they write (their javadocs), with its own arithmetic: sums
 * of {@link BigInteger} minor units at each currency's one scale, and a translation divided in {@link BigInteger}s and rounded
 * half-even by hand - never {@code BigDecimal.divide}, so the oracle and {@code Translation} cannot share a rounding.
 *
 * <p><strong>A figure is its minor units and its scale</strong> ({@code 12345@2}): an exact amount written at the wrong scale is
 * a different fact. Each currency is written at its own scale, a translated figure at the reporting currency's two places, a
 * rate at {@code NUMERIC(20,10)}'s ten.
 *
 * <p>Fact families (keys {@code FAMILY|month|...}): {@code TB} the trial balance's body - rows, unmapped rows, per-currency
 * proofs and the verdict - and {@code TBO} its rows' opening and movement sides as the fold holds them; {@code GL} figures and
 * the agreement; {@code BS} rows, their components, section totals, the equation with the journal's and the ledger's own totals,
 * the breakdowns of retained earnings and the result, the bridge's presence, or the refusal; {@code IS} rows, components, section
 * totals, the result and net realised FX, or the refusal; {@code TBT}, {@code BST}, {@code IST} each report's translation - per
 * column and currency the original, translated parts, difference and total, the report-wide figures, every row's translated
 * figure, the rates, and the balance sheet's {@code balanced}; {@code RC} conformance's findings.
 */
final class AccountingBatteryOracle {

    private AccountingBatteryOracle() {}

    private static final String EUR = "EUR";
    private static final String ASSET = "ASSET";
    private static final String LIABILITY = "LIABILITY";
    private static final String EQUITY = "EQUITY";
    private static final String REVENUE = "REVENUE";
    private static final String EXPENSE = "EXPENSE";
    private static final String FX_GAINS = AccountingBatteryWorld.FX_GAINS + "/OPERATIONAL";
    private static final String FX_LOSSES = AccountingBatteryWorld.FX_LOSSES + "/OPERATIONAL";
    private static final int REPORTING_SCALE = 2;
    private static final int RATE_SCALE = 10;

    /** Debits, credits and a count of lines, in minor units. */
    private static final class Sides {
        private BigInteger debits = BigInteger.ZERO;
        private BigInteger credits = BigInteger.ZERO;
        private long lines;

        void add(boolean debit, long minor) {
            if (debit) {
                debits = debits.add(BigInteger.valueOf(minor));
            } else {
                credits = credits.add(BigInteger.valueOf(minor));
            }
            lines++;
        }

        void add(Sides other) {
            debits = debits.add(other.debits);
            credits = credits.add(other.credits);
            lines += other.lines;
        }

        BigInteger net() {
            return debits.subtract(credits);
        }
    }

    /** One purpose's lines inside a row: before the month and in it. */
    private static final class Component {
        private final Sides opening = new Sides();
        private final Sides movement = new Sides();
    }

    /** One GL account (or unmapped key) in one currency: its row's account, the lines before the month and in it, by purpose. */
    private static final class Row {
        private final Optional<ChartRow> account;
        private final String key;
        private final String currency;
        private final int scale;
        private final Sides opening = new Sides();
        private final Sides movement = new Sides();
        private final Sides fxGains = new Sides();
        private final Sides fxLosses = new Sides();
        private final SortedMap<String, Component> components = new TreeMap<>();

        Row(Optional<ChartRow> account, String key, String currency, int scale) {
            this.account = account;
            this.key = key;
            this.currency = currency;
            this.scale = scale;
        }

        String classification() {
            return account.map(ChartRow::classification).orElse("");
        }

        boolean debitNormal() {
            return classification().equals(ASSET) || classification().equals(EXPENSE);
        }

        /** {@code debitPositive} on the account's normal side. */
        BigInteger normal(BigInteger debitPositive) {
            return debitNormal() ? debitPositive : debitPositive.negate();
        }

        BigInteger closingNet() {
            return opening.net().add(movement.net());
        }
    }

    /** Every fact of every month of {@code entries}, read under {@code chart} at {@code rates}. */
    static SortedMap<String, String> facts(List<Entry> entries, Chart chart, Function<YearMonth, SortedMap<String, BigDecimal>> rates) {
        SortedMap<String, String> facts = new TreeMap<>();
        for (YearMonth month : AccountingBatteryWorld.MONTHS) {
            month(facts, entries, chart, month, rates.apply(month));
        }
        return facts;
    }

    private static void month(SortedMap<String, String> facts, List<Entry> entries, Chart chart, YearMonth month,
            SortedMap<String, BigDecimal> rates) {
        String m = month.toString();
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        Map<String, ChartRow> rules = new TreeMap<>();
        AccountingBatteryWorld.chart(chart, month).forEach(row -> rules.put(row.key(), row));

        SortedMap<String, Row> rows = new TreeMap<>();
        SortedMap<String, Sides[]> currencies = new TreeMap<>();
        SortedMap<String, Integer> scales = new TreeMap<>();
        for (Entry entry : entries) {
            if (entry.postingDate().isAfter(end)) {
                continue;
            }
            boolean before = entry.postingDate().isBefore(start);
            scales.put(entry.currency(), entry.scale());
            Sides[] currency = currencies.computeIfAbsent(entry.currency(), ignored -> new Sides[] {new Sides(), new Sides()});
            for (Line line : entry.lines()) {
                Optional<ChartRow> account = Optional.ofNullable(rules.get(line.key()));
                String rowKey = account.map(found -> "A:" + found.code()).orElse("U:" + line.key()) + "|" + entry.currency();
                Row row = rows.computeIfAbsent(rowKey, ignored -> new Row(account, line.key(), entry.currency(), entry.scale()));
                Component component = row.components.computeIfAbsent(line.key(), ignored -> new Component());
                (before ? row.opening : row.movement).add(line.debit(), line.minor());
                (before ? component.opening : component.movement).add(line.debit(), line.minor());
                (before ? currency[0] : currency[1]).add(line.debit(), line.minor());
                if (!before && line.key().equals(FX_GAINS)) {
                    row.fxGains.add(line.debit(), line.minor());
                } else if (!before && line.key().equals(FX_LOSSES)) {
                    row.fxLosses.add(line.debit(), line.minor());
                }
            }
        }
        boolean first = month.equals(AccountingBatteryWorld.MONTHS.get(0));
        boolean reclassified = !first && !AccountingBatteryWorld.versionKey(chart, month.minusMonths(1))
                .equals(AccountingBatteryWorld.versionKey(chart, month));

        List<String> outOfBalance = trialBalance(facts, m, rows, currencies, reclassified, first, rates);
        generalLedger(facts, m, rows, currencies);
        statements(facts, m, rows, currencies, scales, reclassified, outOfBalance, rates);
        conformance(facts, entries, month);
    }

    // ------------------------------------------------------------------ the trial balance

    /** The trial balance's facts; returns the currencies not zero every way, sorted. */
    private static List<String> trialBalance(SortedMap<String, String> facts, String m, SortedMap<String, Row> rows,
            SortedMap<String, Sides[]> currencies, boolean reclassified, boolean first, SortedMap<String, BigDecimal> rates) {
        List<String> outOfBalance = new ArrayList<>();
        boolean mapped = true;
        boolean openingBalanced = true;
        boolean periodBalanced = true;
        boolean balanced = true;
        List<Part[]> parts = new ArrayList<>();
        for (Map.Entry<String, Row> held : rows.entrySet()) {
            Row row = held.getValue();
            BigInteger closing = row.closingNet();
            facts.put("TB|" + m + "|" + held.getKey(), amount(row.opening.net(), row.scale) + " " + amount(row.movement.debits,
                    row.scale) + " " + amount(row.movement.credits, row.scale) + " " + amount(closing, row.scale) + " "
                    + row.movement.lines);
            facts.put("TBO|" + m + "|" + held.getKey(), plain(row.opening, row.scale) + " " + plain(row.movement, row.scale));
            mapped &= row.account.isPresent();
            parts.add(new Part[] {new Part(row.currency, row.scale, row.opening.net()), new Part(row.currency, row.scale,
                    row.movement.debits), new Part(row.currency, row.scale, row.movement.credits), new Part(row.currency, row.scale,
                    closing)});
            facts.put("TBT|" + m + "|row|" + held.getKey(), cents(row.currency, row.scale, row.opening.net(), rates) + " "
                    + cents(row.currency, row.scale, row.movement.debits, rates) + " "
                    + cents(row.currency, row.scale, row.movement.credits, rates) + " "
                    + cents(row.currency, row.scale, closing, rates));
        }
        for (Map.Entry<String, Sides[]> held : currencies.entrySet()) {
            String currency = held.getKey();
            Sides opening = held.getValue()[0];
            Sides movement = held.getValue()[1];
            Sides closing = new Sides();
            closing.add(opening);
            closing.add(movement);
            int scale = AccountingBatteryWorld.unit(currency).scale();
            BigInteger debitBalances = BigInteger.ZERO;
            BigInteger creditBalances = BigInteger.ZERO;
            long unmappedLines = 0;
            for (Row row : rows.values()) {
                if (!row.currency.equals(currency)) {
                    continue;
                }
                BigInteger rowClosing = row.closingNet();
                if (rowClosing.signum() > 0) {
                    debitBalances = debitBalances.add(rowClosing);
                } else {
                    creditBalances = creditBalances.add(rowClosing.negate());
                }
                if (row.account.isEmpty()) {
                    unmappedLines += row.opening.lines + row.movement.lines;
                }
            }
            boolean zero = closing.debits.equals(closing.credits) && debitBalances.equals(creditBalances);
            boolean openingZero = opening.debits.equals(opening.credits);
            boolean periodZero = movement.debits.equals(movement.credits);
            openingBalanced &= openingZero;
            periodBalanced &= periodZero;
            balanced &= zero;
            if (!(zero && openingZero && periodZero)) {
                outOfBalance.add(currency);
            }
            // The ledger's side is the same lines, joined to no account: the journal's own totals.
            facts.put("TB|" + m + "|C:" + currency, "scale=" + scale + " o=" + sides(opening, scale) + " m=" + sides(movement, scale)
                    + " c=" + sides(closing, scale) + " ledger=o:" + sides(opening, scale) + ",m:" + sides(movement, scale) + ",c:"
                    + sides(closing, scale) + " dr=" + amount(debitBalances, scale) + " cr=" + amount(creditBalances, scale)
                    + " openingZero=" + openingZero + " periodZero=" + periodZero + " zero=" + zero + " equalsLedger=true unmapped="
                    + unmappedLines);
        }
        facts.put("TB|" + m + "|verdict", "openingBalanced=" + openingBalanced + " periodBalanced=" + periodBalanced + " balanced="
                + balanced + " equalsLedger=true rangeIsThePeriods=true fullyMapped=" + mapped + " outOfBalance=["
                + String.join(",", outOfBalance) + "] disagreeing=[] passes=" + outOfBalance.isEmpty() + " reclassified="
                + reclassified + " previousPin=" + (first ? "null" : "present"));
        List<String> names = List.of("opening", "debits", "credits", "closing");
        SortedMap<String, List<Part>> columns = new TreeMap<>();
        for (int i = 0; i < names.size(); i++) {
            List<Part> column = new ArrayList<>();
            for (Part[] row : parts) {
                column.add(row[i]);
            }
            columns.put(names.get(i), column);
        }
        translation(facts, "TBT|" + m, columns, rates);
        return outOfBalance;
    }

    // ------------------------------------------------------------------ the GL

    private static void generalLedger(SortedMap<String, String> facts, String m, SortedMap<String, Row> rows,
            SortedMap<String, Sides[]> currencies) {
        SortedMap<String, Sides> unmapped = new TreeMap<>();
        for (Row row : rows.values()) {
            if (row.movement.lines == 0) {
                continue;
            }
            if (row.account.isPresent()) {
                facts.put("GL|" + m + "|" + row.account.get().code() + "|" + row.currency, sides(row.movement, row.scale));
            } else {
                facts.put("GL|" + m + "|U:" + row.key + "|" + row.currency, sides(row.movement, row.scale));
                unmapped.computeIfAbsent(row.currency, ignored -> new Sides()).add(row.movement);
            }
        }
        for (Map.Entry<String, Sides[]> held : currencies.entrySet()) {
            Sides movement = held.getValue()[1];
            if (movement.lines == 0) {
                continue;
            }
            int scale = AccountingBatteryWorld.unit(held.getKey()).scale();
            Sides gap = unmapped.getOrDefault(held.getKey(), new Sides());
            Sides gl = new Sides();
            gl.debits = movement.debits.subtract(gap.debits);
            gl.credits = movement.credits.subtract(gap.credits);
            gl.lines = movement.lines - gap.lines;
            facts.put("GL|" + m + "|=" + held.getKey(), "gl=" + sides(gl, scale) + " unmapped=" + sides(gap, scale) + " ledger="
                    + sides(movement, scale));
        }
    }

    // ------------------------------------------------------------------ the statements

    private static void statements(SortedMap<String, String> facts, String m, SortedMap<String, Row> rows,
            SortedMap<String, Sides[]> currencies, SortedMap<String, Integer> scales, boolean reclassified, List<String> outOfBalance,
            SortedMap<String, BigDecimal> rates) {
        // What cannot stand refuses, in the definitions' stated order: a line no rule maps, then books not zero every way.
        String refused = rows.values().stream().anyMatch(row -> row.account.isEmpty()) ? "UNCLASSIFIED"
                : outOfBalance.isEmpty() ? null : "OUT_OF_BALANCE:" + String.join(",", outOfBalance);
        if (refused != null) {
            facts.put("BS|" + m + "|refused", refused);
            facts.put("IS|" + m + "|refused", refused);
            return;
        }
        SortedMap<String, BigInteger> sheetTotals = new TreeMap<>();
        SortedMap<String, BigInteger> incomeTotals = new TreeMap<>();
        SortedMap<String, List<Part>> sheet = new TreeMap<>();
        sheet.put("assets", new ArrayList<>());
        sheet.put("liabilitiesEquityAndResult", new ArrayList<>());
        SortedMap<String, List<Part>> income = new TreeMap<>();
        for (String column : List.of("revenue", "expenses", "result", "netRealisedFx")) {
            income.put(column, new ArrayList<>());
        }
        SortedMap<String, BigInteger[]> fx = new TreeMap<>();
        for (Row row : rows.values()) {
            ChartRow account = row.account.orElseThrow();
            String code = account.code();
            String classification = account.classification();
            String at = code + "|" + row.currency;
            if (classification.equals(ASSET) || classification.equals(LIABILITY) || classification.equals(EQUITY)) {
                BigInteger closing = row.normal(row.closingNet());
                facts.put("BS|" + m + "|row|" + account.section() + "|" + at, amount(row.normal(row.opening.net()), row.scale) + " "
                        + amount(row.movement.debits, row.scale) + " " + amount(row.movement.credits, row.scale) + " "
                        + amount(closing, row.scale) + " " + row.movement.lines + " " + row.opening.lines + " gross=null");
                row.components.forEach((key, component) -> facts.put("BS|" + m + "|comp|" + at + "|" + key,
                        amount(row.normal(component.opening.net()), row.scale) + " " + amount(component.movement.debits, row.scale)
                                + " " + amount(component.movement.credits, row.scale) + " "
                                + amount(row.normal(component.opening.net().add(component.movement.net())), row.scale) + " "
                                + component.movement.lines + " " + component.opening.lines + " cp=null"));
                sheetTotals.merge(account.section() + "|" + classification + "|" + row.currency, closing, BigInteger::add);
                sheet.get(classification.equals(ASSET) ? "assets" : "liabilitiesEquityAndResult")
                        .add(new Part(row.currency, row.scale, closing));
                facts.put("BST|" + m + "|row|" + at, cents(row.currency, row.scale, closing, rates));
                continue;
            }
            // A revenue or an expense: its part in retained earnings (lines before the month) and in the result (lines in it).
            if (row.opening.lines > 0) {
                facts.put("BS|" + m + "|retained|" + at, sides(row.opening, row.scale) + " " + amount(row.opening.net().negate(),
                        row.scale));
            }
            if (row.movement.lines > 0) {
                facts.put("BS|" + m + "|current|" + at, sides(row.movement, row.scale) + " " + amount(row.movement.net().negate(),
                        row.scale));
                BigInteger net = row.normal(row.movement.net());
                facts.put("IS|" + m + "|row|" + account.section() + "|" + at, amount(row.movement.debits, row.scale) + " "
                        + amount(row.movement.credits, row.scale) + " " + amount(net, row.scale) + " " + row.movement.lines);
                row.components.forEach((key, component) -> {
                    if (component.movement.lines > 0) {
                        facts.put("IS|" + m + "|comp|" + at + "|" + key, amount(component.movement.debits, row.scale) + " "
                                + amount(component.movement.credits, row.scale) + " "
                                + amount(row.normal(component.movement.net()), row.scale) + " " + component.movement.lines + " cp=null");
                    }
                });
                incomeTotals.merge(account.section() + "|" + classification + "|" + row.currency, net, BigInteger::add);
                income.get(classification.equals(REVENUE) ? "revenue" : "expenses").add(new Part(row.currency, row.scale, net));
                income.get("result").add(new Part(row.currency, row.scale, row.movement.net().negate()));
                facts.put("IST|" + m + "|row|" + at, cents(row.currency, row.scale, net, rates));
                if (row.fxGains.lines > 0 || row.fxLosses.lines > 0) {
                    BigInteger[] held = fx.computeIfAbsent(row.currency, ignored -> new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO});
                    held[0] = held[0].add(row.fxGains.net().negate());
                    held[1] = held[1].add(row.fxLosses.net());
                }
            }
        }
        sheetTotals.forEach((key, total) -> facts.put("BS|" + m + "|total|" + key, amount(total, scaleOf(key, scales))));
        incomeTotals.forEach((key, total) -> facts.put("IS|" + m + "|total|" + key, amount(total, scaleOf(key, scales))));

        for (Map.Entry<String, Sides[]> held : currencies.entrySet()) {
            String currency = held.getKey();
            int scale = scales.get(currency);
            Sides journal = new Sides();
            journal.add(held.getValue()[0]);
            journal.add(held.getValue()[1]);
            BigInteger assets = BigInteger.ZERO;
            BigInteger liabilities = BigInteger.ZERO;
            BigInteger equity = BigInteger.ZERO;
            BigInteger before = BigInteger.ZERO;
            BigInteger within = BigInteger.ZERO;
            BigInteger revenue = BigInteger.ZERO;
            BigInteger expenses = BigInteger.ZERO;
            for (Row row : rows.values()) {
                if (!row.currency.equals(currency)) {
                    continue;
                }
                switch (row.classification()) {
                    case ASSET -> assets = assets.add(row.closingNet());
                    case LIABILITY -> liabilities = liabilities.add(row.closingNet().negate());
                    case EQUITY -> equity = equity.add(row.closingNet().negate());
                    default -> {
                        before = before.add(row.opening.net());
                        within = within.add(row.movement.net());
                        if (row.classification().equals(REVENUE)) {
                            revenue = revenue.add(row.movement.net().negate());
                        } else {
                            expenses = expenses.add(row.movement.net());
                        }
                    }
                }
            }
            BigInteger retained = before.negate();
            BigInteger result = within.negate();
            BigInteger owed = liabilities.add(equity).add(retained).add(result);
            facts.put("BS|" + m + "|eq|" + currency, "assets=" + amount(assets, scale) + " liabilities=" + amount(liabilities, scale)
                    + " equity=" + amount(equity, scale) + " retained=" + amount(retained, scale) + " result=" + amount(result, scale)
                    + " ler=" + amount(owed, scale) + " difference=" + amount(assets.subtract(owed), scale) + " journal="
                    + sides(journal, scale) + " ledger=" + sides(journal, scale));
            facts.put("IS|" + m + "|result|" + currency, "revenue=" + amount(revenue, scale) + " expenses="
                    + amount(expenses, scale) + " result=" + amount(result, scale));
            sheet.get("liabilitiesEquityAndResult").add(new Part(currency, scale, retained));
            sheet.get("liabilitiesEquityAndResult").add(new Part(currency, scale, result));
            facts.put("BST|" + m + "|eq|" + currency, cents(currency, scale, retained, rates) + " " + cents(currency, scale, result,
                    rates));
            income.get("result").add(new Part(currency, scale, BigInteger.ZERO));
        }
        fx.forEach((currency, held) -> {
            int scale = scales.get(currency);
            facts.put("IS|" + m + "|fx|" + currency, "gains=" + amount(held[0], scale) + " losses=" + amount(held[1], scale)
                    + " net=" + amount(held[0].subtract(held[1]), scale));
            income.get("netRealisedFx").add(new Part(currency, scale, held[0]));
            income.get("netRealisedFx").add(new Part(currency, scale, held[1].negate()));
        });
        facts.put("BS|" + m + "|bridge", Boolean.toString(reclassified));

        SortedMap<String, Group[]> sheetGroups = translation(facts, "BST|" + m, sheet, rates);
        boolean balanced = true;
        BigInteger leftTotal = BigInteger.ZERO;
        BigInteger rightTotal = BigInteger.ZERO;
        for (Group[] pair : sheetGroups.values()) {
            BigInteger left = pair[0] == null ? BigInteger.ZERO : pair[0].total;
            BigInteger right = pair[1] == null ? BigInteger.ZERO : pair[1].total;
            balanced &= left.equals(right);
            leftTotal = leftTotal.add(left);
            rightTotal = rightTotal.add(right);
        }
        balanced &= leftTotal.equals(rightTotal);
        facts.put("BST|" + m + "|balanced", Boolean.toString(balanced));
        translation(facts, "IST|" + m, income, rates);
    }

    private static int scaleOf(String key, SortedMap<String, Integer> scales) {
        return scales.get(key.substring(key.lastIndexOf('|') + 1));
    }

    // ------------------------------------------------------------------ translation

    /** One figure to translate: its currency, its scale and its minor units. */
    private record Part(String currency, int scale, BigInteger minor) {}

    /** One currency's group in one column, in reporting cents. */
    private static final class Group {
        private BigInteger original = BigInteger.ZERO;
        private int scale;
        private BigInteger parts = BigInteger.ZERO;
        private BigInteger total = BigInteger.ZERO;
    }

    /**
     * {@code columns} translated at {@code rates}: per column and currency its group, per column the report-wide figures, and
     * the rates. Returns, per currency, the first two columns' groups (in column order) - what {@code balanced} compares.
     */
    private static SortedMap<String, Group[]> translation(SortedMap<String, String> facts, String prefix,
            SortedMap<String, List<Part>> columns, SortedMap<String, BigDecimal> rates) {
        SortedMap<String, Group[]> firstTwo = new TreeMap<>();
        List<String> names = new ArrayList<>(columns.keySet());
        for (int c = 0; c < names.size(); c++) {
            String name = names.get(c);
            SortedMap<String, Group> groups = new TreeMap<>();
            for (Part part : columns.get(name)) {
                Group group = groups.computeIfAbsent(part.currency(), ignored -> new Group());
                group.scale = part.scale();
                group.original = group.original.add(part.minor());
                group.parts = group.parts.add(rounded(part.currency(), part.scale(), part.minor(), rates));
            }
            BigInteger parts = BigInteger.ZERO;
            BigInteger totals = BigInteger.ZERO;
            for (Map.Entry<String, Group> held : groups.entrySet()) {
                Group group = held.getValue();
                group.total = rounded(held.getKey(), group.scale, group.original, rates);
                facts.put(prefix + "|" + name + "|" + held.getKey(), amount(group.original, group.scale) + " "
                        + amount(group.parts, REPORTING_SCALE) + " " + amount(group.total.subtract(group.parts), REPORTING_SCALE) + " "
                        + amount(group.total, REPORTING_SCALE));
                parts = parts.add(group.parts);
                totals = totals.add(group.total);
                if (c < 2) {
                    firstTwo.computeIfAbsent(held.getKey(), ignored -> new Group[2])[c] = group;
                }
            }
            facts.put(prefix + "|" + name + "|*", amount(parts, REPORTING_SCALE) + " " + amount(totals.subtract(parts), REPORTING_SCALE)
                    + " " + amount(totals, REPORTING_SCALE));
        }
        // A rate is written at NUMERIC(20,10)'s ten places, exactly.
        rates.forEach((currency, rate) -> facts.put(prefix + "|rate|" + currency,
                amount(rate.unscaledValue().multiply(BigInteger.TEN.pow(RATE_SCALE - rate.scale())), RATE_SCALE)));
        return firstTwo;
    }

    /** {@code minor} at {@code scale} in {@code currency}, in reporting cents, as a fact. */
    private static String cents(String currency, int scale, BigInteger minor, SortedMap<String, BigDecimal> rates) {
        return amount(rounded(currency, scale, minor, rates), REPORTING_SCALE);
    }

    /**
     * {@code minor} at {@code scale} in {@code currency} in reporting cents: the euro at identity, rescaled; any other currency
     * divided by its {@code EUR/<currency>} rate - {@code minor * 10^(q - s + 2) / u} for a rate {@code u * 10^-q} - and rounded
     * half-even by hand.
     */
    static BigInteger rounded(String currency, int scale, BigInteger minor, SortedMap<String, BigDecimal> rates) {
        if (currency.equals(EUR)) {
            int shift = REPORTING_SCALE - scale;
            return shift >= 0 ? minor.multiply(BigInteger.TEN.pow(shift)) : divide(minor, BigInteger.TEN.pow(-shift));
        }
        BigDecimal rate = rates.get(currency);
        if (rate == null) {
            throw new IllegalStateException("the world values every currency it posts: " + currency);
        }
        int exponent = rate.scale() - scale + REPORTING_SCALE;
        BigInteger numerator = exponent >= 0 ? minor.multiply(BigInteger.TEN.pow(exponent)) : minor;
        BigInteger denominator = exponent >= 0 ? rate.unscaledValue() : rate.unscaledValue().multiply(BigInteger.TEN.pow(-exponent));
        return divide(numerator, denominator);
    }

    /** {@code numerator / denominator} rounded half-even, the denominator positive. */
    static BigInteger divide(BigInteger numerator, BigInteger denominator) {
        BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(denominator);
        BigInteger quotient = quotientAndRemainder[0];
        BigInteger remainder = quotientAndRemainder[1];
        if (remainder.signum() == 0) {
            return quotient;
        }
        BigInteger away = quotient.add(BigInteger.valueOf(numerator.signum()));
        int half = remainder.abs().shiftLeft(1).compareTo(denominator);
        if (half > 0) {
            return away;
        }
        if (half < 0) {
            return quotient;
        }
        return quotient.testBit(0) ? away : quotient;
    }

    // ------------------------------------------------------------------ conformance

    private static void conformance(SortedMap<String, String> facts, List<Entry> entries, YearMonth month) {
        TreeSet<String> findings = new TreeSet<>();
        for (Entry entry : entries) {
            if (!YearMonth.from(entry.postingDate()).equals(month)) {
                continue;
            }
            Optional<Kind> kind = AccountingBatteryWorld.kindOf(entry.namespace());
            if (kind.isEmpty()) {
                findings.add("UNREGISTERED_POSTING:" + entry.namespace());
            } else if (!admits(kind.get().shape(), entry.lines())) {
                findings.add("SHAPE_MISMATCH:" + entry.index());
            }
            BigInteger net = BigInteger.ZERO;
            for (Line line : entry.lines()) {
                net = line.debit() ? net.add(BigInteger.valueOf(line.minor())) : net.subtract(BigInteger.valueOf(line.minor()));
            }
            if (net.signum() != 0) {
                findings.add("UNBALANCED_ENTRY:" + entry.index());
            }
        }
        facts.put("RC|" + month, findings.isEmpty() ? "none" : String.join(",", findings));
    }

    /** Every line taken by a shape line naming its purpose and side, and every required shape line taken. */
    private static boolean admits(List<ShapeLine> shape, List<Line> lines) {
        boolean[] taken = new boolean[shape.size()];
        for (Line line : lines) {
            boolean admitted = false;
            for (int i = 0; i < shape.size(); i++) {
                ShapeLine candidate = shape.get(i);
                if (candidate.purposes().contains(line.purpose()) && candidate.side().equals(line.debit() ? "DEBIT" : "CREDIT")) {
                    taken[i] = true;
                    admitted = true;
                }
            }
            if (!admitted) {
                return false;
            }
        }
        for (int i = 0; i < shape.size(); i++) {
            if (shape.get(i).required() && !taken[i]) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ text

    private static String sides(Sides sides, int scale) {
        return amount(sides.debits, scale) + "/" + amount(sides.credits, scale) + "/" + sides.lines;
    }

    /** Minor units and their scale: {@code 12345@2} - the amount and how it is written, both compared. */
    static String amount(BigInteger minor, int scale) {
        return minor + "@" + scale;
    }

    /** The fold's sides as plain numbers - the {@code TBO} family, the object's, whose zeros carry no scale. */
    private static String plain(Sides sides, int scale) {
        return number(sides.debits, scale) + "/" + number(sides.credits, scale) + "/" + sides.lines;
    }

    private static String number(BigInteger minor, int scale) {
        BigDecimal value = new BigDecimal(minor, scale);
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
