package com.finapp.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.MappingRule;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.Side;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.testing.InMemoryTrialBalanceReads;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The period trial balance against an independent oracle over generated journals (`P14-TSK-009`; {@code INV-ACC-01}
 * reported, {@code INV-ACC-04}, {@code INV-ACC-06}; `P14-TSK-007`'s review, M-6). Each seed generates a balanced journal
 * of several hundred entries over six months - five currencies at three scales, EUR also at a fourth (a currency held at
 * two scales is a legal system-wide state), mapped and unmapped purposes, the period's boundary days and their
 * neighbours loaded on purpose - and the oracle folds it <strong>line by line</strong>, sharing nothing with the code
 * under test but the version's rules: per GL account (or unmapped key) and currency, the signed sum of every line before
 * the period, the period's debits and credits, the signed sum through its end; per currency the raw sweep-shaped totals
 * of the three windows. Hermetic: the journal, the periods and the version are {@link InMemoryTrialBalanceReads}; the
 * database's own proof is {@code TrialBalanceRunDatabaseTest} (owed, C5).
 */
@DisplayName("the period trial balance against an oracle over generated journals (P14-TSK-009)")
class TrialBalanceOracleTest {

    private static final int SEEDS = 24;
    private static final int ENTRIES = 360;
    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Connection SNAPSHOT = (Connection) Proxy.newProxyInstance(
            TrialBalanceOracleTest.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException("the trial balance reads only through its port");
            });
    private static final YearMonth FEBRUARY = YearMonth.of(2096, 2);
    private static final YearMonth MARCH = YearMonth.of(2096, 3);
    private static final LocalDate FIRST_DAY = LocalDate.of(2095, 11, 1);
    private static final LocalDate LAST_DAY = LocalDate.of(2096, 4, 30);
    private static final Instant MINTED_FROM = Instant.parse("2096-01-01T00:00:00Z");
    private static final GlMappingVersion VERSION = ReferenceGlMapping.version(YearMonth.of(2095, 11));
    private static final GlMappingVersionId VERSION_ID = GlMappingVersionId.next(IDS);
    private static final List<String> MAPPED_KEYS = VERSION.rules().stream().map(MappingRule::key).toList();
    private static final List<String> UNMAPPED_KEYS = List.of("UNCHARTED_PURPOSE/OPERATIONAL", "CUSTOMER_WALLET/MERCHANT");

    /** A currency and the scale an entry in it is written at. */
    private record Unit(String currency, int scale) {}

    private static final List<Unit> UNITS = List.of(new Unit("EUR", 2), new Unit("USD", 2), new Unit("GBP", 2),
            new Unit("JPY", 0), new Unit("BHD", 3), new Unit("EUR", 4));

    private final AccountingPeriodId february = AccountingPeriodId.next(IDS);
    private final AccountingPeriodId march = AccountingPeriodId.next(IDS);

    @Test
    @DisplayName("every row is the oracle's: per GL account (and unmapped key) and currency the opening, the debits, the"
            + " credits and the closing, exactly - for every seed, FINAL and PROVISIONAL alike")
    void theTrialBalanceIsTheOracles() {
        for (int seed = 1; seed <= SEEDS; seed++) {
            InMemoryTrialBalanceReads journal = journal(seed, true);
            for (YearMonth month : List.of(FEBRUARY, MARCH)) {
                TrialBalance balance = definition(journal).trialBalance(SNAPSHOT, finalInputs(month));
                assertThat(rows(balance)).as("seed %d, %s", seed, month).isEqualTo(oracleRows(journal.lines(), month));
                assertThat(balance.unmapped()).as("the unmapped keys are rows, never dropped").isNotEmpty();
                assertThat(balance.passes()).as("seed %d, %s", seed, month).isTrue();
            }
        }
    }

    @Test
    @DisplayName("zero per currency: a balanced journal is zero in every currency - its cumulative debits equal its credits"
            + " and its closing balances foot; one planted line makes its currency non-zero, listed and failing the check,"
            + " the others untouched, nothing corrected")
    void zeroPerCurrency() {
        for (int seed = 1; seed <= SEEDS; seed++) {
            InMemoryTrialBalanceReads journal = journal(seed, true);
            TrialBalance balance = definition(journal).trialBalance(SNAPSHOT, finalInputs(FEBRUARY));
            assertThat(balance.currencies()).extracting(TrialBalance.CurrencyCheck::currency)
                    .containsExactly("BHD", "EUR", "GBP", "JPY", "USD");
            for (TrialBalance.CurrencyCheck check : balance.currencies()) {
                assertThat(check.zero()).as("seed %d, %s", seed, check.currency()).isTrue();
                assertThat(check.closingDebitBalances()).isPositive();
                assertThat(check.closingDebitBalances()).isEqualByComparingTo(check.closingCreditBalances());
            }
            assertThat(balance.balanced()).isTrue();

            journal.line(new InMemoryTrialBalanceReads.JournalLine(FEBRUARY.atDay(10), MINTED_FROM, "FEE_REVENUE",
                    "OPERATIONAL", "JPY", 0, Side.DEBIT, 7));
            TrialBalance planted = definition(journal).trialBalance(SNAPSHOT, finalInputs(FEBRUARY));
            assertThat(planted.outOfBalance()).as("seed %d", seed).containsExactly("JPY");
            assertThat(planted.balanced()).isFalse();
            assertThat(planted.passes()).as("a non-zero currency fails the close's check").isFalse();
            assertThat(planted.equalsLedger()).as("reported as the ledger holds it - never corrected").isTrue();
            TrialBalance.CurrencyCheck jpy = planted.currencies().stream()
                    .filter(check -> check.currency().equals("JPY")).findFirst().orElseThrow();
            assertThat(jpy.closing().debits().subtract(jpy.closing().credits())).isEqualByComparingTo("7");
        }
    }

    @Test
    @DisplayName("equal to the ledger: per currency the trial balance's totals over every row equal the raw sweep-shaped"
            + " totals of the three windows - before the period, within it, through its end - debits, credits and lines")
    void equalsTheLedger() {
        for (int seed = 1; seed <= SEEDS; seed++) {
            InMemoryTrialBalanceReads journal = journal(seed, true);
            TrialBalance balance = definition(journal).trialBalance(SNAPSHOT, finalInputs(FEBRUARY));
            Map<String, TrialBalance.Sides[]> raw = rawWindows(journal.lines(), FEBRUARY);
            assertThat(balance.currencies()).hasSize(raw.size());
            for (TrialBalance.CurrencyCheck check : balance.currencies()) {
                TrialBalance.Sides[] expected = raw.get(check.currency());
                assertThat(check.opening().sameAs(expected[0])).as("seed %d %s opening", seed, check.currency()).isTrue();
                assertThat(check.movement().sameAs(expected[1])).as("seed %d %s movement", seed, check.currency())
                        .isTrue();
                assertThat(check.closing().sameAs(expected[2])).as("seed %d %s closing", seed, check.currency()).isTrue();
                assertThat(check.equalsLedger()).isTrue();
            }
            assertThat(balance.disagreeing()).isEmpty();
        }
    }

    @Test
    @DisplayName("the entry's case: the opening is the previous closing - March's opening of every row equals February's"
            + " closing of it, under one version, for every seed")
    void openingIsThePreviousClosing() {
        for (int seed = 1; seed <= SEEDS; seed++) {
            InMemoryTrialBalanceReads journal = journal(seed, true);
            Map<String, BigDecimal[]> previous = rows(definition(journal).trialBalance(SNAPSHOT, finalInputs(FEBRUARY)));
            Map<String, BigDecimal[]> next = rows(definition(journal).trialBalance(SNAPSHOT, finalInputs(MARCH)));
            assertThat(previous).as("seed %d: February holds rows", seed).isNotEmpty();
            for (String key : union(previous, next)) {
                BigDecimal closed = previous.containsKey(key) ? previous.get(key)[3] : BigDecimal.ZERO;
                BigDecimal opened = next.containsKey(key) ? next.get(key)[0] : BigDecimal.ZERO;
                assertThat(opened).as("seed %d, %s: March opens where February closed", seed, key)
                        .isEqualByComparingTo(closed);
            }
        }
    }

    @Test
    @DisplayName("the range bound (P14-TSK-007's review, M-6): a movement read a day short, an opening read a day long, or"
            + " a ledger whose period row ends a day early each turn the ledger comparison false and the check failed -"
            + " the two sides never share a bound")
    void aWrongRangeBoundIsCaught() {
        for (int seed = 1; seed <= SEEDS; seed++) {
            InMemoryTrialBalanceReads journal = journal(seed, true);
            TrialBalance shortMovement = definition(shifted(journal, range -> new JournalReads.Range(range.from(),
                    range.to().minusDays(1)), 0)).trialBalance(SNAPSHOT, finalInputs(FEBRUARY));
            assertThat(shortMovement.equalsLedger()).as("seed %d: the last day's lines missing", seed).isFalse();
            assertThat(shortMovement.passes()).isFalse();

            TrialBalance longOpening = definition(shifted(journal, UnaryOperator.identity(), 1))
                    .trialBalance(SNAPSHOT, finalInputs(FEBRUARY));
            assertThat(longOpening.equalsLedger()).as("seed %d: the first day's lines counted twice", seed).isFalse();
            assertThat(longOpening.passes()).isFalse();

            InMemoryTrialBalanceReads earlyEnd = journal(seed, true);
            earlyEnd.period(new TrialBalanceReads.Period(february, FEBRUARY.atDay(1),
                    FEBRUARY.atEndOfMonth().minusDays(1), PeriodStatus.LOCKED, Optional.of(VERSION_ID)));
            TrialBalance ledgerShort = definition(earlyEnd).trialBalance(SNAPSHOT, finalInputs(FEBRUARY));
            assertThat(ledgerShort.equalsLedger()).as("seed %d: the ledger's own bound wrong", seed).isFalse();
            assertThat(ledgerShort.rangeIsThePeriods()).isFalse();
            assertThat(ledgerShort.passes()).isFalse();
        }
        // No line on a boundary day: the comparison cannot see a bound - the direct comparison of the bounds still does.
        InMemoryTrialBalanceReads quiet = new InMemoryTrialBalanceReads().version(VERSION_ID, VERSION)
                .period(new TrialBalanceReads.Period(february, FEBRUARY.atDay(1), FEBRUARY.atEndOfMonth().minusDays(1),
                        PeriodStatus.LOCKED, Optional.of(VERSION_ID)))
                .post(FEBRUARY.atDay(10), MINTED_FROM, "EUR", 2, 1500, "CASH_AT_BANK/OPERATIONAL",
                        "CUSTOMER_WALLET/CUSTOMER");
        TrialBalance quietBalance = definition(quiet).trialBalance(SNAPSHOT, finalInputs(FEBRUARY));
        assertThat(quietBalance.equalsLedger()).isTrue();
        assertThat(quietBalance.rangeIsThePeriods()).isFalse();
        assertThat(quietBalance.passes()).isFalse();
    }

    @Test
    @DisplayName("reproducible (INV-ACC-04): the same journal served in any order generates byte-identical content and"
            + " hash through the report generation, every seed")
    void regeneratesByteIdentical() {
        ReportGeneration generation = new ReportGeneration((snapshot, id) -> Optional.of(
                new ReportGeneration.PeriodState(PeriodStatus.LOCKED, Optional.of(Instant.EPOCH))), Duration.ofMinutes(4),
                () -> 0L);
        for (int seed = 1; seed <= SEEDS; seed++) {
            InMemoryTrialBalanceReads ordered = journal(seed, true);
            List<InMemoryTrialBalanceReads.JournalLine> shuffled = new ArrayList<>(ordered.lines());
            Collections.shuffle(shuffled, new Random(seed * 31L));
            InMemoryTrialBalanceReads reordered = holding(new InMemoryTrialBalanceReads());
            shuffled.forEach(reordered::line);
            ReportGeneration.Generated first = generation.generate(SNAPSHOT, definition(ordered), finalInputs(FEBRUARY));
            ReportGeneration.Generated again = generation.generate(SNAPSHOT, definition(ordered), finalInputs(FEBRUARY));
            ReportGeneration.Generated other = generation.generate(SNAPSHOT, definition(reordered),
                    finalInputs(FEBRUARY));
            assertThat(again.content()).isEqualTo(first.content());
            assertThat(other.content()).as("seed %d: the read order never shows", seed).isEqualTo(first.content());
            assertThat(other.sha256()).isEqualTo(first.sha256());
            assertThat(first.content()).contains("\"passes\":true").contains("\"definition\":\"TRIAL_BALANCE\"");
        }
    }

    // ------------------------------------------------------------------ the generator

    /** A balanced journal of {@link #ENTRIES} entries for {@code seed}; the boundary days loaded when {@code edges}. */
    private InMemoryTrialBalanceReads journal(long seed, boolean edges) {
        Random random = new Random(seed);
        InMemoryTrialBalanceReads journal = holding(new InMemoryTrialBalanceReads());
        List<LocalDate> boundaries = List.of(LocalDate.of(2096, 1, 31), FEBRUARY.atDay(1), FEBRUARY.atEndOfMonth(),
                MARCH.atDay(1), MARCH.atEndOfMonth(), LocalDate.of(2096, 4, 1));
        long span = LAST_DAY.toEpochDay() - FIRST_DAY.toEpochDay() + 1;
        for (int i = 0; i < ENTRIES; i++) {
            LocalDate date = edges && random.nextInt(4) == 0
                    ? boundaries.get(random.nextInt(boundaries.size()))
                    : FIRST_DAY.plusDays(random.nextInt((int) span));
            Unit unit = UNITS.get(random.nextInt(UNITS.size()));
            Instant minted = MINTED_FROM.plusSeconds(random.nextInt(1_000_000));
            long amount = 1 + random.nextInt(1_000_000);
            String debit = key(random);
            if (random.nextBoolean()) {
                journal.post(date, minted, unit.currency(), unit.scale(), amount, debit, key(random));
            } else {
                long part = 1 + random.nextInt((int) Math.min(amount, Integer.MAX_VALUE));
                journal.line(line(date, minted, debit, unit, Side.DEBIT, amount + part));
                journal.line(line(date, minted, key(random), unit, Side.CREDIT, amount));
                journal.line(line(date, minted, key(random), unit, Side.CREDIT, part));
            }
        }
        return journal;
    }

    private InMemoryTrialBalanceReads holding(InMemoryTrialBalanceReads journal) {
        return journal.version(VERSION_ID, VERSION)
                .period(new TrialBalanceReads.Period(february, FEBRUARY.atDay(1), FEBRUARY.atEndOfMonth(),
                        PeriodStatus.LOCKED, Optional.of(VERSION_ID)))
                .period(new TrialBalanceReads.Period(march, MARCH.atDay(1), MARCH.atEndOfMonth(), PeriodStatus.LOCKED,
                        Optional.of(VERSION_ID)));
    }

    private static String key(Random random) {
        return random.nextInt(10) == 0
                ? UNMAPPED_KEYS.get(random.nextInt(UNMAPPED_KEYS.size()))
                : MAPPED_KEYS.get(random.nextInt(MAPPED_KEYS.size()));
    }

    private static InMemoryTrialBalanceReads.JournalLine line(LocalDate date, Instant minted, String key, Unit unit,
            Side side, long amount) {
        String[] parts = key.split("/", 2);
        return new InMemoryTrialBalanceReads.JournalLine(date, minted, parts[0], parts[1], unit.currency(), unit.scale(),
                side, amount);
    }

    // ------------------------------------------------------------------ the oracle

    /**
     * Per row key ({@code A:code|currency} or {@code U:purpose/ownerKind|currency}), folded line by line: the opening's
     * signed sum, the period's debits, its credits, the closing's signed sum.
     */
    private static Map<String, BigDecimal[]> oracleRows(List<InMemoryTrialBalanceReads.JournalLine> lines,
            YearMonth month) {
        Map<String, MappingRule> rules = VERSION.rulesByKey();
        Map<String, BigDecimal[]> rows = new TreeMap<>();
        for (InMemoryTrialBalanceReads.JournalLine line : lines) {
            if (line.postingDate().isAfter(month.atEndOfMonth())) {
                continue;
            }
            MappingRule rule = rules.get(line.key());
            String key = (rule == null ? "U:" + line.key() : "A:" + rule.glAccountCode()) + "|" + line.currency();
            BigDecimal[] row = rows.computeIfAbsent(key, ignored -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal amount = BigDecimal.valueOf(line.minorUnits(), line.scale());
            BigDecimal signed = line.side() == Side.DEBIT ? amount : amount.negate();
            if (line.postingDate().isBefore(month.atDay(1))) {
                row[0] = row[0].add(signed);
            } else if (line.side() == Side.DEBIT) {
                row[1] = row[1].add(amount);
            } else {
                row[2] = row[2].add(amount);
            }
            row[3] = row[3].add(signed);
        }
        return stripped(rows);
    }

    /** The trial balance's rows in the oracle's shape. */
    private static Map<String, BigDecimal[]> rows(TrialBalance balance) {
        Map<String, BigDecimal[]> rows = new TreeMap<>();
        for (TrialBalance.AccountRow row : balance.accounts()) {
            rows.put("A:" + row.account().code() + "|" + row.currency(), new BigDecimal[] {row.openingBalance(),
                    row.movement().debits(), row.movement().credits(), row.closingBalance()});
        }
        for (TrialBalance.UnmappedRow row : balance.unmapped()) {
            rows.put("U:" + row.purpose() + "/" + row.ownerKind() + "|" + row.currency(), new BigDecimal[] {
                    row.openingBalance(), row.movement().debits(), row.movement().credits(), row.closingBalance()});
        }
        return stripped(rows);
    }

    /** Values compared by number, not by scale: each stripped of trailing zeros, zero as {@code 0}. */
    private static Map<String, BigDecimal[]> stripped(Map<String, BigDecimal[]> rows) {
        Map<String, BigDecimal[]> out = new TreeMap<>();
        rows.forEach((key, values) -> {
            BigDecimal[] copy = new BigDecimal[values.length];
            for (int i = 0; i < values.length; i++) {
                copy[i] = values[i].signum() == 0 ? BigDecimal.ZERO : values[i].stripTrailingZeros();
            }
            out.put(key, copy);
        });
        return new MapOfArrays(out);
    }

    /** Per currency, the raw sweep-shaped totals of the three windows: before, within, through. */
    private static Map<String, TrialBalance.Sides[]> rawWindows(List<InMemoryTrialBalanceReads.JournalLine> lines,
            YearMonth month) {
        Map<String, TrialBalance.Sides[]> windows = new TreeMap<>();
        for (InMemoryTrialBalanceReads.JournalLine line : lines) {
            if (line.postingDate().isAfter(month.atEndOfMonth())) {
                continue;
            }
            TrialBalance.Sides[] sides = windows.computeIfAbsent(line.currency(), ignored -> new TrialBalance.Sides[] {
                    TrialBalance.Sides.NONE, TrialBalance.Sides.NONE, TrialBalance.Sides.NONE});
            TrialBalance.Sides one = line.side() == Side.DEBIT
                    ? new TrialBalance.Sides(BigDecimal.valueOf(line.minorUnits(), line.scale()), BigDecimal.ZERO, 1)
                    : new TrialBalance.Sides(BigDecimal.ZERO, BigDecimal.valueOf(line.minorUnits(), line.scale()), 1);
            int window = line.postingDate().isBefore(month.atDay(1)) ? 0 : 1;
            sides[window] = sides[window].plus(one);
            sides[2] = sides[2].plus(one);
        }
        return windows;
    }

    private static List<String> union(Map<String, BigDecimal[]> left, Map<String, BigDecimal[]> right) {
        TreeMap<String, Boolean> keys = new TreeMap<>();
        left.keySet().forEach(key -> keys.put(key, true));
        right.keySet().forEach(key -> keys.put(key, true));
        return List.copyOf(keys.keySet());
    }

    // ------------------------------------------------------------------ plumbing

    private static TrialBalanceDefinition definition(TrialBalanceReads reads) {
        return new TrialBalanceDefinition(reads);
    }

    private ReportInputs finalInputs(YearMonth month) {
        return new ReportInputs(month.equals(FEBRUARY) ? february : march, month, 1, RunBasis.FINAL,
                Optional.of(Instant.EPOCH), Optional.of(VERSION_ID), Optional.of(1), Instant.EPOCH,
                RunParameters.none());
    }

    /**
     * {@code reads} with its GL side's bounds moved - the movement's range by {@code movement}, the opening's bound by
     * {@code openingDays} - the defect `P14-TSK-007`'s review named, planted on one side only.
     */
    private static TrialBalanceReads shifted(TrialBalanceReads reads, UnaryOperator<JournalReads.Range> movement,
            int openingDays) {
        return new TrialBalanceReads() {
            @Override
            public Optional<Period> period(Connection snapshot, AccountingPeriodId id) {
                return reads.period(snapshot, id);
            }

            @Override
            public GlMappingVersion version(Connection snapshot, GlMappingVersionId id) {
                return reads.version(snapshot, id);
            }

            @Override
            public List<JournalReads.Aggregate> before(Connection snapshot, LocalDate before, Optional<Instant> cut) {
                return reads.before(snapshot, before.plusDays(openingDays), cut);
            }

            @Override
            public List<JournalReads.Aggregate> within(Connection snapshot, JournalReads.Range range,
                    Optional<Instant> cut) {
                return reads.within(snapshot, movement.apply(range), cut);
            }

            @Override
            public List<LedgerWindows> ledger(Connection snapshot, AccountingPeriodId period, Optional<Instant> cut) {
                return reads.ledger(snapshot, period, cut);
            }
        };
    }

    /** A sorted map whose equality compares its arrays by content - so two row maps compare as values. */
    private static final class MapOfArrays extends TreeMap<String, BigDecimal[]> {
        @java.io.Serial private static final long serialVersionUID = 1L;

        MapOfArrays(Map<String, BigDecimal[]> rows) {
            super(rows);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Map<?, ?> that) || !keySet().equals(that.keySet())) {
                return false;
            }
            for (Map.Entry<String, BigDecimal[]> entry : entrySet()) {
                if (!(that.get(entry.getKey()) instanceof BigDecimal[] values)
                        || !java.util.Arrays.equals(entry.getValue(), values)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return keySet().hashCode();
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder("{");
            forEach((key, values) -> out.append(key).append('=').append(java.util.Arrays.toString(values)).append(", "));
            return out.append('}').toString();
        }
    }
}
