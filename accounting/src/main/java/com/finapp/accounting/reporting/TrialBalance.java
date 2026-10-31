package com.finapp.accounting.reporting;

import com.finapp.accounting.GlAccount;
import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.MappingRule;
import com.finapp.accounting.Side;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * A period's trial balance (`P14-TSK-009`; {@code PHASE_14_PLAN.md} section 13.6; ADR-0115; {@code INV-ACC-01} reported,
 * {@code INV-ACC-04}, {@code INV-ACC-06}): per GL account and currency the opening balance, the period's debits and
 * credits and the closing balance, with every journal line the version does not map in an explicit unmapped row - never
 * dropped - and, per currency, the two proofs the close reads first.
 *
 * <p><strong>The figures.</strong> The opening is the account's balance before the period - a cumulative fold of every
 * line dated before its first day, from inception, under the period's own version; the debits and credits are the
 * period's movement (`P14-TSK-007`'s GL figures); the closing is the opening plus the debits less the credits. Balances
 * are signed {@link #SIGN debit-positive} - a credit balance is negative - so the rows foot with no convention left to the
 * reader. Exact decimal arithmetic on minor units summed per scale; no floating point ({@code INV-MON-01}).
 *
 * <p><strong>Zero per currency</strong> ({@code INV-ACC-01}, reported - never corrected): the cumulative debits through
 * the period's end equal its credits, <em>and</em> the closing debit balances foot to the closing credit balances. A
 * currency failing either is {@link CurrencyCheck#zero() not zero}, listed in the verdict, and fails the close's check -
 * the run still completes: it is the evidence.
 *
 * <p><strong>Equal to the ledger</strong> ({@code INV-ACC-06}): the trial balance's totals per currency - every row,
 * mapped and unmapped - equal the ledger's own, read in {@code TrialBalance.sweep}'s shape by a statement joining no
 * account, over <strong>three windows</strong>: before the period (the opening), within it (the movement), through its end
 * (the closing, computed here as opening plus movement). The ledger's windows take their bounds from the period's row,
 * not from {@link #range}, which this side computed - so a wrong range bound on either side lands a boundary day's lines
 * in the wrong window and turns a comparison false (`P14-TSK-007`'s review, M-6); {@link #rangeIsThePeriods()} compares
 * the two bounds directly, so the defect shows even on a month with no line on its boundary days.
 *
 * <p>Report code: a value and a pure fold; it reads and writes nothing ({@code ReportingNeverWritesTest}).
 *
 * @param period the month
 * @param range the GL side's posting-date range - the run's month as this code computes it
 * @param periodStart the period row's {@code period_start}, as the database holds it
 * @param periodEnd the period row's {@code period_end}
 * @param mapped whether a mapping version was pinned - without one every line is in an unmapped row
 */
public record TrialBalance(
        YearMonth period,
        JournalReads.Range range,
        LocalDate periodStart,
        LocalDate periodEnd,
        boolean mapped,
        List<AccountRow> accounts,
        List<UnmappedRow> unmapped,
        List<CurrencyCheck> currencies) {

    /** The balances' sign: a debit balance positive, a credit balance negative. */
    public static final String SIGN = "DEBIT_POSITIVE";

    public TrialBalance {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        accounts = List.copyOf(accounts);
        unmapped = List.copyOf(unmapped);
        currencies = List.copyOf(currencies);
    }

    // ------------------------------------------------------------------ the values

    /** One window's gross: its debits, its credits and its line count. */
    public record Sides(BigDecimal debits, BigDecimal credits, long lines) {

        /** Nothing. */
        public static final Sides NONE = new Sides(BigDecimal.ZERO, BigDecimal.ZERO, 0);

        public Sides {
            Objects.requireNonNull(debits, "debits");
            Objects.requireNonNull(credits, "credits");
            if (lines < 0 || debits.signum() < 0 || credits.signum() < 0) {
                throw new IllegalArgumentException("a window's sides and count are never negative");
            }
        }

        /** This window and {@code other}, summed exactly. */
        public Sides plus(Sides other) {
            return new Sides(debits.add(other.debits), credits.add(other.credits), Math.addExact(lines, other.lines));
        }

        /** {@code amount} added to {@code side}, {@code count} lines more. */
        Sides plus(Side side, BigDecimal amount, long count) {
            return side == Side.DEBIT
                    ? new Sides(debits.add(amount), credits, Math.addExact(lines, count))
                    : new Sides(debits, credits.add(amount), Math.addExact(lines, count));
        }

        /** The net, debit-positive. */
        public BigDecimal net() {
            return debits.subtract(credits);
        }

        /** Equal debits, credits and count - by value, whatever the scales. */
        public boolean sameAs(Sides other) {
            return debits.compareTo(other.debits) == 0 && credits.compareTo(other.credits) == 0 && lines == other.lines;
        }
    }

    /** One GL account in one currency: the opening's lines (cumulative before the period) and the period's movement. */
    public record AccountRow(GlAccount account, String currency, Sides opening, Sides movement) {

        public AccountRow {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(opening, "opening");
            Objects.requireNonNull(movement, "movement");
        }

        /** The opening balance, debit-positive. */
        public BigDecimal openingBalance() {
            return opening.net();
        }

        /** The closing balance: the opening plus the debits less the credits, debit-positive. */
        public BigDecimal closingBalance() {
            return opening.net().add(movement.debits()).subtract(movement.credits());
        }
    }

    /**
     * The lines of one (purpose, owner kind) in one currency that the version does not map - or every line, when no version
     * was pinned. Counted, never dropped.
     */
    public record UnmappedRow(String purpose, String ownerKind, String currency, Sides opening, Sides movement) {

        public UnmappedRow {
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(ownerKind, "ownerKind");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(opening, "opening");
            Objects.requireNonNull(movement, "movement");
        }

        public BigDecimal openingBalance() {
            return opening.net();
        }

        public BigDecimal closingBalance() {
            return opening.net().add(movement.debits()).subtract(movement.credits());
        }
    }

    /**
     * One currency's proofs: the trial balance's totals over every row - the opening's, the movement's and their sum, the
     * closing - beside the ledger's own over the same three windows, and the closing balances' footing.
     */
    public record CurrencyCheck(
            String currency,
            Sides opening,
            Sides movement,
            Sides ledgerOpening,
            Sides ledgerMovement,
            Sides ledgerClosing,
            BigDecimal closingDebitBalances,
            BigDecimal closingCreditBalances,
            long unmappedLines) {

        public CurrencyCheck {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(opening, "opening");
            Objects.requireNonNull(movement, "movement");
            Objects.requireNonNull(ledgerOpening, "ledgerOpening");
            Objects.requireNonNull(ledgerMovement, "ledgerMovement");
            Objects.requireNonNull(ledgerClosing, "ledgerClosing");
            Objects.requireNonNull(closingDebitBalances, "closingDebitBalances");
            Objects.requireNonNull(closingCreditBalances, "closingCreditBalances");
        }

        /** The trial balance's cumulative totals through the period's end: the opening's plus the movement's. */
        public Sides closing() {
            return opening.plus(movement);
        }

        /**
         * {@code INV-ACC-01} for the period: the cumulative debits equal the credits, and the closing debit balances foot
         * to the closing credit balances.
         */
        public boolean zero() {
            Sides closing = closing();
            return closing.debits().compareTo(closing.credits()) == 0
                    && closingDebitBalances.compareTo(closingCreditBalances) == 0;
        }

        /** The trial balance equals the ledger over all three windows - debits, credits and lines. */
        public boolean equalsLedger() {
            return opening.sameAs(ledgerOpening) && movement.sameAs(ledgerMovement) && closing().sameAs(ledgerClosing);
        }
    }

    // ------------------------------------------------------------------ the verdict

    /** The GL side's range is the period row's - the code and the database agree on the month's bounds. */
    public boolean rangeIsThePeriods() {
        return range.from().equals(periodStart) && range.to().equals(periodEnd);
    }

    /** Every currency zero ({@code INV-ACC-01}). */
    public boolean balanced() {
        return currencies.stream().allMatch(CurrencyCheck::zero);
    }

    /** Every currency equal to the ledger over the three windows. */
    public boolean equalsLedger() {
        return currencies.stream().allMatch(CurrencyCheck::equalsLedger);
    }

    /** No line outside the version's rules - reported; judged by the close's {@code GL_EQUALS_LEDGER}. */
    public boolean fullyMapped() {
        return mapped && unmapped.isEmpty();
    }

    /** The currencies not zero, sorted. */
    public List<String> outOfBalance() {
        return currencies.stream().filter(check -> !check.zero()).map(CurrencyCheck::currency).toList();
    }

    /** The currencies whose totals differ from the ledger's, sorted. */
    public List<String> disagreeing() {
        return currencies.stream().filter(check -> !check.equalsLedger()).map(CurrencyCheck::currency).toList();
    }

    /**
     * The close's first check: zero per currency, equal to the ledger, over the period's own bounds. A {@code false} is
     * reported and fails the check; nothing is corrected.
     */
    public boolean passes() {
        return rangeIsThePeriods() && balanced() && equalsLedger();
    }

    // ------------------------------------------------------------------ the fold

    /**
     * The pure fold: the opening's and the movement's aggregates through {@code version}'s rules into account rows and
     * unmapped rows (every aggregate in exactly one), and per currency the totals beside the ledger's windows. Every list
     * sorted - account code, then currency; purpose, owner kind, currency; currency - so one snapshot folds to one
     * answer on every instance, whatever order the reads served.
     *
     * @param period the run's month
     * @param range the GL side's range, as the caller read the movement
     * @param row the period's row, as the database holds it
     * @param version the pinned version's content - empty when none was pinned
     */
    public static TrialBalance fold(YearMonth period, JournalReads.Range range, TrialBalanceReads.Period row,
            Optional<GlMappingVersion> version, List<JournalReads.Aggregate> opening,
            List<JournalReads.Aggregate> movement, List<TrialBalanceReads.LedgerWindows> ledger) {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(version, "version");
        Map<String, MappingRule> rules = version.map(GlMappingVersion::rulesByKey).orElse(Map.of());
        Map<String, Building> accounts = new TreeMap<>();
        Map<String, Building> unmapped = new TreeMap<>();
        Consumer<Folding> into = folding -> {
            JournalReads.Aggregate aggregate = folding.aggregate();
            MappingRule rule = rules.get(aggregate.key());
            Building building;
            if (rule == null) {
                building = unmapped.computeIfAbsent(aggregate.purpose() + "\u0000" + aggregate.ownerKind() + "\u0000"
                        + aggregate.currency(), ignored -> new Building(null, aggregate.purpose(),
                        aggregate.ownerKind(), aggregate.currency()));
            } else {
                GlAccount account = version.orElseThrow().account(rule.glAccountCode()).orElseThrow(() ->
                        new IllegalStateException("a coherent version's rule names an account it holds"));
                building = accounts.computeIfAbsent(account.code() + "\u0000" + aggregate.currency(),
                        ignored -> new Building(account, null, null, aggregate.currency()));
            }
            if (folding.opening()) {
                building.opening = building.opening.plus(aggregate.side(), aggregate.amount(), aggregate.lines());
            } else {
                building.movement = building.movement.plus(aggregate.side(), aggregate.amount(), aggregate.lines());
            }
        };
        opening.forEach(aggregate -> into.accept(new Folding(aggregate, true)));
        movement.forEach(aggregate -> into.accept(new Folding(aggregate, false)));

        List<AccountRow> accountRows = accounts.values().stream()
                .map(building -> new AccountRow(building.account, building.currency, building.opening,
                        building.movement))
                .toList();
        List<UnmappedRow> unmappedRows = unmapped.values().stream()
                .map(building -> new UnmappedRow(building.purpose, building.ownerKind, building.currency,
                        building.opening, building.movement))
                .toList();

        Map<String, Checking> checks = new TreeMap<>();
        for (AccountRow account : accountRows) {
            checks.computeIfAbsent(account.currency(), ignored -> new Checking())
                    .row(account.opening(), account.movement(), account.closingBalance(), false);
        }
        for (UnmappedRow gap : unmappedRows) {
            checks.computeIfAbsent(gap.currency(), ignored -> new Checking())
                    .row(gap.opening(), gap.movement(), gap.closingBalance(), true);
        }
        for (TrialBalanceReads.LedgerWindows windows : ledger) {
            Checking checking = checks.computeIfAbsent(windows.currency(), ignored -> new Checking());
            checking.ledgerOpening = checking.ledgerOpening.plus(windows.side(), windows.before(), windows.beforeLines());
            checking.ledgerMovement = checking.ledgerMovement.plus(windows.side(), windows.within(),
                    windows.withinLines());
            checking.ledgerClosing = checking.ledgerClosing.plus(windows.side(), windows.through(),
                    windows.throughLines());
        }
        List<CurrencyCheck> currencyChecks = new ArrayList<>();
        checks.forEach((currency, checking) -> currencyChecks.add(new CurrencyCheck(currency, checking.opening,
                checking.movement, checking.ledgerOpening, checking.ledgerMovement, checking.ledgerClosing,
                checking.debitBalances, checking.creditBalances, checking.unmappedLines)));
        return new TrialBalance(period, range, row.start(), row.end(), version.isPresent(), accountRows, unmappedRows,
                currencyChecks);
    }

    /** One aggregate on its way into a row - the opening's or the movement's. */
    private record Folding(JournalReads.Aggregate aggregate, boolean opening) {}

    /** A row being folded. */
    private static final class Building {
        private final GlAccount account;
        private final String purpose;
        private final String ownerKind;
        private final String currency;
        private Sides opening = Sides.NONE;
        private Sides movement = Sides.NONE;

        Building(GlAccount account, String purpose, String ownerKind, String currency) {
            this.account = account;
            this.purpose = purpose;
            this.ownerKind = ownerKind;
            this.currency = currency;
        }
    }

    /** One currency's totals being folded. */
    private static final class Checking {
        private Sides opening = Sides.NONE;
        private Sides movement = Sides.NONE;
        private Sides ledgerOpening = Sides.NONE;
        private Sides ledgerMovement = Sides.NONE;
        private Sides ledgerClosing = Sides.NONE;
        private BigDecimal debitBalances = BigDecimal.ZERO;
        private BigDecimal creditBalances = BigDecimal.ZERO;
        private long unmappedLines;

        void row(Sides rowOpening, Sides rowMovement, BigDecimal closing, boolean unmappedRow) {
            opening = opening.plus(rowOpening);
            movement = movement.plus(rowMovement);
            if (closing.signum() > 0) {
                debitBalances = debitBalances.add(closing);
            } else if (closing.signum() < 0) {
                creditBalances = creditBalances.add(closing.negate());
            }
            if (unmappedRow) {
                unmappedLines = Math.addExact(unmappedLines, Math.addExact(rowOpening.lines(), rowMovement.lines()));
            }
        }
    }

    // ------------------------------------------------------------------ the body

    /**
     * The report's body, canonical: the rows, the unmapped rows, the per-currency proofs, the two ranges, the sign and the
     * verdict. Every amount is written as minor units in a string beside its currency and scale - one scale per currency,
     * the largest any of its amounts carries and never below its ISO minor unit, reached by an exact rescale - so a sum
     * past {@code long} and a currency held at two scales are both written exactly.
     */
    public CanonicalJson.Obj body() {
        Map<String, Integer> scales = scales();
        CanonicalJson.Arr accountsOut = CanonicalJson.array();
        for (AccountRow row : accounts) {
            int scale = scales.get(row.currency());
            accountsOut.add(CanonicalJson.object()
                    .put("account", row.account().code())
                    .put("name", row.account().name())
                    .put("classification", row.account().classification().name())
                    .put("normalBalance", row.account().normalBalance())
                    .put("currency", row.currency())
                    .put("opening", money(row.openingBalance(), row.currency(), scale))
                    .put("debits", money(row.movement().debits(), row.currency(), scale))
                    .put("credits", money(row.movement().credits(), row.currency(), scale))
                    .put("closing", money(row.closingBalance(), row.currency(), scale))
                    .put("lines", row.movement().lines()));
        }
        CanonicalJson.Arr unmappedOut = CanonicalJson.array();
        for (UnmappedRow row : unmapped) {
            int scale = scales.get(row.currency());
            unmappedOut.add(CanonicalJson.object()
                    .put("purpose", row.purpose())
                    .put("ownerKind", row.ownerKind())
                    .put("currency", row.currency())
                    .put("opening", money(row.openingBalance(), row.currency(), scale))
                    .put("debits", money(row.movement().debits(), row.currency(), scale))
                    .put("credits", money(row.movement().credits(), row.currency(), scale))
                    .put("closing", money(row.closingBalance(), row.currency(), scale))
                    .put("lines", row.movement().lines()));
        }
        CanonicalJson.Arr currenciesOut = CanonicalJson.array();
        for (CurrencyCheck check : currencies) {
            String currency = check.currency();
            int scale = scales.get(currency);
            currenciesOut.add(CanonicalJson.object()
                    .put("currency", currency)
                    .put("scale", scale)
                    .put("opening", sides(check.opening(), currency, scale))
                    .put("movement", sides(check.movement(), currency, scale))
                    .put("closing", sides(check.closing(), currency, scale))
                    .put("ledger", CanonicalJson.object()
                            .put("opening", sides(check.ledgerOpening(), currency, scale))
                            .put("movement", sides(check.ledgerMovement(), currency, scale))
                            .put("closing", sides(check.ledgerClosing(), currency, scale)))
                    .put("closingDebitBalances", money(check.closingDebitBalances(), currency, scale))
                    .put("closingCreditBalances", money(check.closingCreditBalances(), currency, scale))
                    .put("zero", check.zero())
                    .put("equalsLedger", check.equalsLedger())
                    .put("unmappedLines", check.unmappedLines()));
        }
        CanonicalJson.Arr outOfBalance = CanonicalJson.array();
        outOfBalance().forEach(outOfBalance::add);
        CanonicalJson.Arr disagreeing = CanonicalJson.array();
        disagreeing().forEach(disagreeing::add);
        return CanonicalJson.object()
                .put("sign", SIGN)
                .put("range", CanonicalJson.object()
                        .put("from", range.from().toString())
                        .put("through", range.to().toString()))
                .put("periodBounds", CanonicalJson.object()
                        .put("from", periodStart.toString())
                        .put("through", periodEnd.toString()))
                .put("accounts", accountsOut)
                .put("unmapped", unmappedOut)
                .put("currencies", currenciesOut)
                .put("verdict", CanonicalJson.object()
                        .put("balanced", balanced())
                        .put("equalsLedger", equalsLedger())
                        .put("rangeIsThePeriods", rangeIsThePeriods())
                        .put("fullyMapped", fullyMapped())
                        .put("outOfBalance", outOfBalance)
                        .put("disagreeing", disagreeing)
                        .put("passes", passes()));
    }

    /** Per currency, the one scale every amount of it is written at. */
    private Map<String, Integer> scales() {
        Map<String, Integer> scales = new TreeMap<>();
        for (CurrencyCheck check : currencies) {
            int scale = isoMinorUnits(check.currency());
            for (Sides sides : List.of(check.opening(), check.movement(), check.ledgerOpening(), check.ledgerMovement(),
                    check.ledgerClosing())) {
                scale = Math.max(scale, Math.max(sides.debits().scale(), sides.credits().scale()));
            }
            scale = Math.max(scale, Math.max(check.closingDebitBalances().scale(), check.closingCreditBalances().scale()));
            scales.put(check.currency(), scale);
        }
        for (AccountRow row : accounts) {
            scales.merge(row.currency(), maxScale(row.opening(), row.movement()), Math::max);
        }
        for (UnmappedRow row : unmapped) {
            scales.merge(row.currency(), maxScale(row.opening(), row.movement()), Math::max);
        }
        return scales;
    }

    private static int maxScale(Sides opening, Sides movement) {
        return Math.max(Math.max(opening.debits().scale(), opening.credits().scale()),
                Math.max(movement.debits().scale(), movement.credits().scale()));
    }

    private static int isoMinorUnits(String currency) {
        try {
            return CurrencyCode.of(currency).minorUnits();
        } catch (IllegalArgumentException unknown) {
            return 0;
        }
    }

    private static CanonicalJson.Obj sides(Sides sides, String currency, int scale) {
        return CanonicalJson.object()
                .put("debits", money(sides.debits(), currency, scale))
                .put("credits", money(sides.credits(), currency, scale))
                .put("lines", sides.lines());
    }

    /**
     * {@code {"currency":…,"minor":…,"scale":…}} - {@link CanonicalJson.Obj#putMoney}'s shape, the minor units an exact
     * integer of any size; the rescale is exact or refused ({@link RoundingMode#UNNECESSARY}), never rounded.
     */
    static CanonicalJson.Obj money(BigDecimal amount, String currency, int scale) {
        if (!currency.matches("[A-Z]{3}") || scale < 0 || scale > 18) {
            throw new IllegalArgumentException("an amount names an ISO 4217 currency and a scale of 0 to 18");
        }
        return CanonicalJson.object()
                .put("currency", currency)
                .put("minor", amount.setScale(scale, RoundingMode.UNNECESSARY).unscaledValue().toString())
                .put("scale", scale);
    }
}
