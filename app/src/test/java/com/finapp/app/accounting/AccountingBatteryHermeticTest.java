package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.MappingRule;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.accounting.reporting.GlActivity;
import com.finapp.accounting.reporting.PinnedMapping;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.app.accounting.AccountingBatteryBooks.Books;
import com.finapp.app.accounting.AccountingBatteryBooks.Journal;
import com.finapp.app.accounting.AccountingBatteryBooks.Reported;
import com.finapp.app.accounting.AccountingBatteryWorld.Chart;
import com.finapp.app.accounting.AccountingBatteryWorld.ChartRow;
import com.finapp.app.accounting.AccountingBatteryWorld.Entry;
import com.finapp.app.accounting.AccountingBatteryWorld.Line;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.YearMonth;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The accounting battery, its hermetic half (`P14-TST-002`; {@code PHASE_14_PLAN.md} section 14.3; {@code INV-ACC-01},
 * {@code -02}, {@code -04}, {@code -06}, {@code -11}, {@code -12}): {@link AccountingBatteryWorld}'s seeded journal - at least
 * 10,000 entries over five currencies at three scales and the twelve months of 2001, a mapping restated from July - posted
 * through the real {@code PostingService} over stores in memory and reported by the production folds ({@link
 * AccountingBatteryBooks}), every fact of every month held equal to {@link AccountingBatteryOracle}'s, computed from the
 * generated lines alone by code that names no class of what it checks ({@link #theOracleSharesNoCode}).
 *
 * <ul>
 *   <li>the trial balance, the GL and its drill-down, both statements, their translation and rule conformance, each 100% the
 *       oracle's;
 *   <li>the same lines written by a second JVM with other defaults, and by this one with the journal served in another order;
 *   <li>three perturbations - a mapping rule, a rate, a planted shape - each flipping exactly the facts the oracle names, the
 *       oracle and production agreeing under each.
 * </ul>
 *
 * <p><strong>What is not here</strong>: the SQL. The read adapters are the accounting module's in-memory ones, the stand-ins for
 * {@code LedgerTrialBalanceReads}, {@code LedgerJournalReads} and {@code LedgerJournalShapes}; the periods, versions and rate sets
 * are stood in closed, pinned and approved. The same journal through PostgreSQL, the real period machine, the real report runs
 * and the real read adapters is {@code AccountingBatteryTest} (own-container, owed to the phase's database pass, C5).
 *
 * <p>Hermetic: it needs only the JVM. The size and seed are {@code FINAPP_ACCOUNTING_BATTERY_ENTRIES} and {@code _SEED} for a
 * probe's quick run or a failing seed's diagnosis only - either fails {@link #theCensus} by design, so it can never pass for the
 * gate's run.
 */
@DisplayName("P14-TST-002 - the accounting battery, hermetic: an independent oracle over 10,000 generated entries")
class AccountingBatteryHermeticTest {

    /** The journal's fingerprint at {@link AccountingBatteryWorld#SEED} and full size - moved only by a reviewed change. */
    static final String FINGERPRINT = "da304bfb2a6f551ba33c9a2a8da368c5fc05ed9e21fbf2fb5c6b2bbca8d0f56c";

    private static final long SEED = AccountingBatteryWorld.SEED_IN_USE;
    private static final Function<YearMonth, SortedMap<String, BigDecimal>> RATES = month -> AccountingBatteryWorld.rates(SEED, month);

    private static List<Entry> journal;
    private static Journal posted;
    private static Books books;
    private static Reported production;
    private static SortedMap<String, String> oracle;
    private static List<String> ours;
    private static List<String> theirs;

    @BeforeAll
    static void battery() throws Exception {
        // The second JVM first, so it works while this one does.
        AccountingBatteryInAnotherJvm.Started child = AccountingBatteryInAnotherJvm.start(SEED, AccountingBatteryWorld.ENTRIES);
        try {
            journal = AccountingBatteryWorld.generate(SEED, AccountingBatteryWorld.ENTRIES);
            posted = AccountingBatteryBooks.post(journal, SEED);
            books = AccountingBatteryBooks.books(posted, Chart.BASE, Optional.empty());
            production = AccountingBatteryBooks.report(books, RATES);
            oracle = AccountingBatteryOracle.facts(journal, Chart.BASE, RATES);
            ours = AccountingBatteryBooks.lines(AccountingBatteryWorld.fingerprint(journal), production);
        } catch (RuntimeException | Error failed) {
            child.abandon(); // this JVM's pass failed: no orphaned child, no temporary files
            throw failed;
        }
        theirs = child.join(Duration.ofMinutes(10));
        System.out.println("P14-TST-002 FINGERPRINT: seed=" + SEED + " entries=" + AccountingBatteryWorld.ENTRIES + " fingerprint="
                + AccountingBatteryWorld.fingerprint(journal));
        System.out.println("P14-TST-002 CENSUS: facts=" + oracle.size() + " posted=" + posted.entries().size() + " replays="
                + posted.replays() + " lines=" + journal.stream().mapToInt(entry -> entry.lines().size()).sum());
    }

    // ------------------------------------------------------------------ the census

    @Test
    @DisplayName("the census: at least 10,000 entries through the real posting service, five currencies in every month, the"
            + " boundary days loaded, two to four lines, value dates apart from posting dates, one to ten million minor units, every"
            + " replay one effect, the chart the reference's, the fingerprint pinned")
    void theCensus() {
        assertThat(AccountingBatteryWorld.ENTRIES).as("entries - a shrunk run is a probe's, never the gate's").isGreaterThanOrEqualTo(10_000);
        assertThat(SEED).as("the seed - a reseeded run is a diagnosis, never the gate's").isEqualTo(AccountingBatteryWorld.SEED);
        assertThat(journal).hasSize(AccountingBatteryWorld.ENTRIES);
        assertThat(posted.entries()).as("one stored entry per generated one, replays included").hasSize(journal.size());
        long replayed = journal.stream().filter(entry -> entry.index() % AccountingBatteryWorld.REPLAYED == 0).count();
        assertThat(posted.replays()).as("every second posting under a key replayed").isEqualTo((int) replayed).isGreaterThan(100);
        assertThat(posted.replayedSame()).as("and answered with the first entry").isEqualTo((int) replayed);
        for (YearMonth month : AccountingBatteryWorld.MONTHS) {
            for (AccountingBatteryWorld.Unit unit : AccountingBatteryWorld.UNITS) {
                assertThat(journal).as("%s holds %s", month, unit.currency()).anyMatch(entry -> YearMonth.from(entry.postingDate())
                        .equals(month) && entry.currency().equals(unit.currency()));
            }
            assertThat(journal).as("%s's first day", month).anyMatch(entry -> entry.postingDate().equals(month.atDay(1)));
            assertThat(journal).as("%s's last day", month).anyMatch(entry -> entry.postingDate().equals(month.atEndOfMonth()));
        }
        assertThat(journal).anyMatch(entry -> entry.lines().size() == 2).anyMatch(entry -> entry.lines().size() == 3)
                .anyMatch(entry -> entry.lines().size() == 4);
        for (AccountingBatteryWorld.Kind kind : AccountingBatteryWorld.KINDS) {
            assertThat(journal).as(kind.namespace()).anyMatch(entry -> entry.namespace().equals(kind.namespace()));
        }
        long apart = journal.stream().filter(entry -> !entry.valueDate().equals(entry.postingDate())).count();
        long otherMonth = journal.stream().filter(entry -> !YearMonth.from(entry.valueDate()).equals(YearMonth.from(entry.postingDate())))
                .count();
        assertThat(apart).as("value dates apart from posting dates").isGreaterThan(journal.size() / 3);
        assertThat(otherMonth).as("value dates in another month").isGreaterThan(journal.size() / 10);
        List<Long> amounts = journal.stream().flatMap(entry -> entry.lines().stream()).map(Line::minor).sorted().toList();
        assertThat(amounts.get(0)).as("the smallest line").isEqualTo(1L);
        assertThat(amounts.get(amounts.size() - 1)).as("the largest line").isGreaterThan(5_000_000L);
        // Every classification and both statements' sections reached.
        for (String classification : List.of("ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE")) {
            assertThat(AccountingBatteryWorld.CHART).anyMatch(row -> row.classification().equals(classification));
        }
        assertThat(production.facts().keySet()).noneMatch(key -> key.endsWith("|refused"));
        // Ties are met, not hoped for: at EUR/GBP 0.8000 a pound figure of 2 mod 4 pence is exactly half a cent from two.
        long ties = 0;
        for (Map.Entry<String, String> row : oracle.entrySet()) {
            if (row.getKey().matches("TB\\|2001-(01|03|05|07|09|11)\\|A:\\d+\\|GBP")) {
                for (BigDecimal figure : figures(row.getValue())) {
                    ties += figure.movePointRight(2).toBigIntegerExact().abs().mod(java.math.BigInteger.valueOf(4)).intValue() == 2 ? 1 : 0;
                }
            }
        }
        assertThat(ties).as("pound figures on a rounding tie in the months it is 0.8000").isGreaterThan(10);
        assertThat(AccountingBatteryWorld.fingerprint(journal)).as("the journal at the seed and size - a moved journal is a"
                + " reviewed change").isEqualTo(FINGERPRINT);
    }

    @Test
    @DisplayName("the oracle's chart is the production version's, every purpose of every month under both charts - the second"
            + " statement of the mapping checked, never trusted; the reference's rows as ReferenceGlMapping holds them")
    void theOraclesChartIsTheVersions() {
        GlMappingVersion reference = ReferenceGlMapping.version(AccountingBatteryWorld.MONTHS.get(0));
        for (ChartRow row : AccountingBatteryWorld.CHART) {
            assertRow(reference, row);
        }
        for (Chart chart : Chart.values()) {
            for (YearMonth month : AccountingBatteryWorld.MONTHS) {
                GlMappingVersion version = AccountingBatteryBooks.version(chart, month).content();
                for (ChartRow row : AccountingBatteryWorld.chart(chart, month)) {
                    assertRow(version, row);
                }
            }
        }
    }

    private static void assertRow(GlMappingVersion version, ChartRow row) {
        MappingRule rule = version.rulesByKey().get(row.key());
        assertThat(rule).as(row.key()).isNotNull();
        assertThat(rule.glAccountCode()).as(row.key()).isEqualTo(row.code());
        com.finapp.accounting.GlAccount account = version.account(row.code()).orElseThrow();
        assertThat(account.classification().name()).as(row.key()).isEqualTo(row.classification());
        assertThat(account.section().name()).as(row.key()).isEqualTo(row.section());
    }

    // ------------------------------------------------------------------ the folds against the oracle

    @Test
    @DisplayName("INV-ACC-01, -06: the period trial balance is the oracle's - every GL account's opening, debits, credits and"
            + " lines per currency, every currency's three legs zero and equal to the ledger, the verdict, the July restatement")
    void theTrialBalanceIsTheOracles() {
        assertAgree("TB|");
        for (YearMonth month : AccountingBatteryWorld.MONTHS) {
            assertThat(oracle.get("TB|" + month + "|verdict")).as("%s - the oracle's own claim", month)
                    .isEqualTo("passes=true fullyMapped=true reclassified=" + month.equals(AccountingBatteryWorld.RESTATED_FROM));
            for (AccountingBatteryWorld.Unit unit : AccountingBatteryWorld.UNITS) {
                assertThat(production.facts().get("TB|" + month + "|C:" + unit.currency())).as("%s %s", month, unit.currency())
                        .contains("zero=true openingZero=true periodZero=true equalsLedger=true");
            }
        }
    }

    @Test
    @DisplayName("INV-ACC-02, -06: the GL is the oracle's - every figure per account and currency, nothing unmapped, GL = ledger"
            + " per currency; and every figure drills to its lines: the pages of its activity sum to the oracle's figure")
    void theGeneralLedgerIsTheOracles() {
        assertAgree("GL|");
        assertThat(production.facts().keySet()).noneMatch(key -> key.startsWith("GL|") && key.contains("|U:"));
        GeneralLedger ledger = new GeneralLedger(books.lines());
        int figures = 0;
        int pages = 0;
        for (YearMonth month : AccountingBatteryWorld.MONTHS) {
            AccountingBatteryBooks.Version version = AccountingBatteryBooks.version(Chart.BASE, month);
            PinnedMapping mapping = new PinnedMapping(month, Optional.of(PeriodStatus.CLOSED), version.id(), version.number(),
                    version.content());
            for (Map.Entry<String, String> fact : oracle.entrySet()) {
                String prefix = "GL|" + month + "|";
                if (!fact.getKey().startsWith(prefix) || fact.getKey().contains("|=")) {
                    continue;
                }
                String[] keys = fact.getKey().substring(prefix.length()).split("\\|");
                BigDecimal debits = BigDecimal.ZERO;
                BigDecimal credits = BigDecimal.ZERO;
                long lines = 0;
                Optional<JournalReads.LineCursor> after = Optional.empty();
                int walked = 0;
                do {
                    walked++;
                    GlActivity page = ledger.activity(AccountingBatteryBooks.NO_SQL, mapping, keys[0], keys[1], after, PAGE);
                    for (JournalReads.Line line : page.lines()) {
                        BigDecimal amount = BigDecimal.valueOf(line.minorUnits(), line.scale());
                        debits = line.side() == com.finapp.accounting.Side.DEBIT ? debits.add(amount) : debits;
                        credits = line.side() == com.finapp.accounting.Side.CREDIT ? credits.add(amount) : credits;
                        lines++;
                    }
                    after = page.next();
                } while (after.isPresent());
                assertThat(AccountingBatteryBooks.normal(debits) + "/" + AccountingBatteryBooks.normal(credits) + "/" + lines)
                        .as("%s drilled to its lines", fact.getKey()).isEqualTo(fact.getValue());
                figures++;
                pages += walked > 1 ? 1 : 0;
            }
        }
        assertThat(figures).as("figures drilled").isGreaterThan(12 * 30);
        assertThat(pages).as("figures that took more than one page - the keyset walked").isGreaterThan(figures / 2);
    }

    /** A drill-down page: small, so a month's figure takes several and the keyset is walked, not merely read once. */
    private static final int PAGE = 7;

    @Test
    @DisplayName("INV-ACC-06: both statements are the oracle's - per month and currency assets = liabilities + equity + retained"
            + " earnings + the result, each side from its own lines, every section total and row, the income statement's result"
            + " the balance sheet's, net realised FX")
    void theStatementsAreTheOracles() {
        assertAgree("BS|", "IS|");
        for (YearMonth month : AccountingBatteryWorld.MONTHS) {
            for (AccountingBatteryWorld.Unit unit : AccountingBatteryWorld.UNITS) {
                String equation = production.facts().get("BS|" + month + "|eq|" + unit.currency());
                assertThat(equation).as("%s %s", month, unit.currency()).endsWith(" difference=0");
                String result = equation.substring(equation.indexOf(" result=") + 1, equation.indexOf(" difference="));
                assertThat(production.facts().get("IS|" + month + "|result|" + unit.currency())).as("one result, both statements")
                        .endsWith(" " + result);
            }
            assertThat(production.facts().get("BS|" + month + "|bridge"))
                    .isEqualTo(Boolean.toString(month.equals(AccountingBatteryWorld.RESTATED_FROM)));
        }
    }

    @Test
    @DisplayName("INV-ACC-11: the translation is the oracle's - each report's columns per currency, the original kept beside the"
            + " translated parts, the difference line and the total, the report-wide figures, every row's translated figure, the"
            + " rates; the balance sheet balanced through its difference lines, which are not all zero")
    void theTranslationIsTheOracles() {
        assertAgree("TBT|", "BST|", "IST|");
        for (YearMonth month : AccountingBatteryWorld.MONTHS) {
            assertThat(production.facts().get("BST|" + month + "|balanced")).as("%s", month).isEqualTo("true");
        }
        long differences = production.facts().entrySet().stream()
                .filter(fact -> fact.getKey().matches("(TBT|BST|IST)\\|[^|]+\\|(?!rate\\||eq\\|)[a-zA-Z]+\\|[A-Z]{3}"))
                .filter(fact -> !fact.getValue().split(" ")[2].equals("0")).count();
        assertThat(differences).as("difference lines that are not zero - the explicit line is exercised").isGreaterThan(10);
    }

    @Test
    @DisplayName("INV-ACC-12: every entry conforms to its kind's registered shape, month by month, as the oracle judges it")
    void everyEntryConformsToItsRule() {
        assertAgree("RC|");
        for (YearMonth month : AccountingBatteryWorld.MONTHS) {
            assertThat(production.facts().get("RC|" + month)).as("%s", month).isEqualTo("none");
        }
    }

    // ------------------------------------------------------------------ reproducibility

    @Test
    @DisplayName("INV-ACC-04: regeneration in two JVMs is byte-identical - the second JVM (charset UTF-16, locale ar-EG, zone"
            + " America/Adak) writes this one's fingerprint, every report's content hash and the facts' digest, line for line")
    void regeneratesByteIdenticalInTwoJvms() {
        assertThat(ours).as("the fingerprint, 36 reports and the facts").hasSize(1 + 36 + 1);
        assertThat(ours.get(0)).isEqualTo("fingerprint " + AccountingBatteryWorld.fingerprint(journal));
        assertThat(theirs).isEqualTo(ours);
    }

    @Test
    @DisplayName("INV-ACC-04: the read order never shows - the entries shuffled and every list a read answers served in a random"
            + " order, as SQL's GROUP BY promises none, every report's bytes and every fact are the same")
    void theReadOrderNeverShows() {
        Reported shuffled = AccountingBatteryBooks.report(AccountingBatteryBooks.books(posted, Chart.BASE, Optional.of(SEED * 7)),
                RATES);
        assertThat(shuffled.contents()).isEqualTo(production.contents());
        assertThat(differences(production.facts(), shuffled.facts())).isEmpty();
    }

    // ------------------------------------------------------------------ the perturbations

    @Test
    @DisplayName("a mapping rule moving a purpose between sections: chargebacks recoverable from 1130 in other assets to 1190 in"
            + " the clearing receivables - exactly the facts the oracle names flip (the two accounts' rows, the two sections' asset"
            + " totals), the equation and every translation group untouched, production the oracle's under the move")
    void aMappingRuleMovesASectionAndNotTheEquation() {
        Reported moved = AccountingBatteryBooks.report(AccountingBatteryBooks.books(posted, Chart.MOVED, Optional.empty()), RATES);
        SortedMap<String, String> movedOracle = AccountingBatteryOracle.facts(journal, Chart.MOVED, RATES);
        assertThat(differences(movedOracle, moved.facts())).as("production is the oracle's under the move").isEmpty();
        Set<String> named = flipped(oracle, movedOracle);
        assertThat(flipped(production.facts(), moved.facts())).as("production flips exactly what the oracle names").isEqualTo(named);
        Predicate<String> theMove = key -> key.contains("|" + AccountingBatteryWorld.MOVED_FROM + "|")
                || key.contains("|A:" + AccountingBatteryWorld.MOVED_FROM + "|") || key.contains("|" + AccountingBatteryWorld.MOVED_TO + "|")
                || key.contains("|A:" + AccountingBatteryWorld.MOVED_TO + "|")
                || key.matches("BS\\|[^|]+\\|total\\|(" + AccountingBatteryWorld.MOVED_FROM_SECTION + "|"
                        + AccountingBatteryWorld.MOVED_TO_SECTION + ")\\|ASSET\\|[A-Z]{3}");
        assertThat(named).as("only the two accounts and the two sections' asset totals").allMatch(theMove);
        assertThat(named).as("the equation, the currency proofs, conformance and every translation group stay")
                .noneMatch(key -> key.contains("|eq|") || key.contains("|C:") || key.startsWith("RC|") || key.startsWith("IS")
                        || key.matches("(TBT|BST|IST)\\|[^|]+\\|[a-zA-Z]+\\|([A-Z]{3}|\\*)"));
        for (YearMonth month : AccountingBatteryWorld.MONTHS) {
            assertThat(named).as("%s: the row leaves other assets", month).anyMatch(key -> key.startsWith("BS|" + month + "|row|"
                    + AccountingBatteryWorld.MOVED_FROM_SECTION + "|" + AccountingBatteryWorld.MOVED_FROM + "|"));
            assertThat(named).as("%s: and arrives in the clearing receivables", month).anyMatch(key -> key.startsWith("BS|" + month
                    + "|row|" + AccountingBatteryWorld.MOVED_TO_SECTION + "|" + AccountingBatteryWorld.MOVED_TO + "|"));
        }
    }

    @Test
    @DisplayName("a rate: June's EUR/USD moved - exactly the facts the oracle names flip, all of June's translation (USD groups,"
            + " the report-wide totals, USD rows' translated figures, the rate), a difference line among them; no original figure;"
            + " the balance sheet balanced before and after")
    void aRateMovesADifferenceLineAndNotTheBalance() {
        Function<YearMonth, SortedMap<String, BigDecimal>> moved = month -> AccountingBatteryWorld.movedRates(SEED, month);
        Reported perturbed = AccountingBatteryBooks.report(books, moved);
        SortedMap<String, String> movedOracle = AccountingBatteryOracle.facts(journal, Chart.BASE, moved);
        assertThat(differences(movedOracle, perturbed.facts())).as("production is the oracle's at the moved rate").isEmpty();
        Set<String> named = flipped(oracle, movedOracle);
        assertThat(flipped(production.facts(), perturbed.facts())).as("production flips exactly what the oracle names").isEqualTo(named);
        String june = AccountingBatteryWorld.PERTURBED.toString();
        assertThat(named).as("June's translation alone, in USD or across currencies").isNotEmpty().allMatch(key ->
                key.matches("(TBT|BST|IST)\\|" + june + "\\|.*") && (key.endsWith("|USD") || key.endsWith("|*")));
        assertThat(named).contains("TBT|" + june + "|rate|USD", "BST|" + june + "|rate|USD", "IST|" + june + "|rate|USD");
        assertThat(named).as("a difference line moved").anyMatch(key -> key.matches("(TBT|BST|IST)\\|" + june + "\\|(?!rate\\||eq\\|)[a-zA-Z]+\\|USD")
                && !oracle.get(key).split(" ")[2].equals(movedOracle.get(key).split(" ")[2]));
        assertThat(perturbed.facts().get("BST|" + june + "|balanced")).isEqualTo("true");
        assertThat(production.facts().get("BST|" + june + "|balanced")).isEqualTo("true");
    }

    @Test
    @DisplayName("a planted shape: one June funding entry posted under the fee's namespace - exactly one SHAPE_MISMATCH, that"
            + " entry, and nothing else: no fact and no byte of any report of any month moves")
    void aPlantedShapeIsOneFindingAndMovesNoFigure() {
        List<Entry> planted = AccountingBatteryWorld.planted(journal);
        int index = AccountingBatteryWorld.plantedIndex(journal);
        Reported perturbed = AccountingBatteryBooks.report(AccountingBatteryBooks.books(AccountingBatteryBooks.post(planted, SEED),
                Chart.BASE, Optional.empty()), RATES);
        SortedMap<String, String> plantedOracle = AccountingBatteryOracle.facts(planted, Chart.BASE, RATES);
        assertThat(differences(plantedOracle, perturbed.facts())).as("production is the oracle's over the planted journal").isEmpty();
        String june = "RC|" + AccountingBatteryWorld.PERTURBED;
        assertThat(flipped(oracle, plantedOracle)).as("the oracle names one fact").containsExactly(june);
        assertThat(flipped(production.facts(), perturbed.facts())).as("production flips exactly it").containsExactly(june);
        assertThat(perturbed.facts().get(june)).isEqualTo("SHAPE_MISMATCH:" + index);
        assertThat(perturbed.contents()).as("no report's bytes moved").isEqualTo(production.contents());
    }

    // ------------------------------------------------------------------ independence

    /** The classes the oracle may name: itself and the world - nothing of the code it checks. */
    private static final Pattern REFERENCE = Pattern.compile("com/finapp/[A-Za-z0-9_/$]+");
    private static final Set<String> ALLOWED = Set.of("com/finapp/app/accounting/AccountingBatteryOracle",
            "com/finapp/app/accounting/AccountingBatteryWorld");

    @Test
    @DisplayName("the oracle shares no code with what it checks: its class files and the world's name no com.finapp class but"
            + " theirs - read from the constant pools, so a fully-qualified use, a descriptor or a nested class cannot hide; and the"
            + " reading has teeth, finding the folds in the books' class file")
    void theOracleSharesNoCode() throws Exception {
        for (Class<?> type : List.of(AccountingBatteryOracle.class, AccountingBatteryWorld.class)) {
            Set<String> named = referencesOf(type);
            assertThat(named).as("%s's class files name com.finapp", type.getSimpleName()).isNotEmpty();
            assertThat(named).as("%s names only the oracle and the world", type.getSimpleName())
                    .allMatch(reference -> ALLOWED.stream().anyMatch(allowed -> reference.equals(allowed)
                            || reference.startsWith(allowed + "$")));
        }
        assertThat(referencesOf(AccountingBatteryBooks.class)).as("the reading finds what the books call")
                .contains("com/finapp/accounting/reporting/TrialBalanceDefinition", "com/finapp/accounting/reporting/GeneralLedger",
                        "com/finapp/accounting/reporting/BalanceSheetDefinition", "com/finapp/ledger/PostingService");
    }

    /** Every {@code com/finapp/...} name in the constant pools of {@code type}'s class file and its nested and anonymous ones'. */
    private static Set<String> referencesOf(Class<?> type) throws IOException, URISyntaxException {
        Path file = Path.of(Objects.requireNonNull(type.getResource(type.getSimpleName() + ".class")).toURI());
        Set<String> names = new TreeSet<>();
        int files = 0;
        try (Stream<Path> siblings = Files.list(file.getParent())) {
            for (Path sibling : siblings.toList()) {
                String name = sibling.getFileName().toString();
                if (name.equals(type.getSimpleName() + ".class") || name.startsWith(type.getSimpleName() + "$")) {
                    for (String text : utf8Constants(sibling)) {
                        Matcher matcher = REFERENCE.matcher(text);
                        while (matcher.find()) {
                            names.add(matcher.group());
                        }
                    }
                    files++;
                }
            }
        }
        assertThat(files).as("%s's class files", type.getSimpleName()).isGreaterThan(1);
        return names;
    }

    /** The {@code CONSTANT_Utf8} entries of a class file - every class name, descriptor and signature it holds. */
    private static List<String> utf8Constants(Path classFile) {
        try (InputStream bytes = Files.newInputStream(classFile); DataInputStream in = new DataInputStream(bytes)) {
            if (in.readInt() != 0xCAFEBABE) {
                throw new IllegalStateException("not a class file: " + classFile);
            }
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            List<String> texts = new ArrayList<>();
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> texts.add(in.readUTF());
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> in.readInt();
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> throw new IllegalStateException("constant pool tag " + tag + " in " + classFile);
                }
            }
            return texts;
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** A trial balance row's four translated originals - the opening, the debits, the credits, the closing - from its fact. */
    private static List<BigDecimal> figures(String fact) {
        String[] windows = fact.split(" ");
        String[] opening = windows[0].split("/");
        String[] movement = windows[1].split("/");
        BigDecimal net = new BigDecimal(opening[0]).subtract(new BigDecimal(opening[1]));
        BigDecimal debits = new BigDecimal(movement[0]);
        BigDecimal credits = new BigDecimal(movement[1]);
        return List.of(net, debits, credits, net.add(debits).subtract(credits));
    }

    /** The families of production's facts equal to the oracle's, every key. */
    private static void assertAgree(String... families) {
        SortedMap<String, String> expected = family(oracle, families);
        SortedMap<String, String> actual = family(production.facts(), families);
        assertThat(expected).as("the oracle states %s", List.of(families)).isNotEmpty();
        List<String> differences = differences(expected, actual);
        assertThat(differences.size()).as("%s facts differ; the first: %s", differences.size(),
                differences.subList(0, Math.min(20, differences.size()))).isZero();
    }

    private static SortedMap<String, String> family(SortedMap<String, String> facts, String... families) {
        SortedMap<String, String> out = new TreeMap<>();
        facts.forEach((key, value) -> {
            for (String prefix : families) {
                if (key.startsWith(prefix)) {
                    out.put(key, value);
                }
            }
        });
        return out;
    }

    /** Each key the two maps hold differently, with both values. */
    private static List<String> differences(SortedMap<String, String> expected, SortedMap<String, String> actual) {
        List<String> out = new ArrayList<>();
        for (String key : flipped(expected, actual)) {
            out.add(key + " oracle=" + expected.get(key) + " production=" + actual.get(key));
        }
        return out;
    }

    /** The keys whose values differ between {@code before} and {@code after}, a key in one only included. */
    private static Set<String> flipped(SortedMap<String, String> before, SortedMap<String, String> after) {
        TreeSet<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        keys.removeIf(key -> Objects.equals(before.get(key), after.get(key)));
        return keys;
    }
}
