package com.finapp.accounting.reporting;

import static com.finapp.accounting.testing.ReportBodies.flag;
import static com.finapp.accounting.testing.ReportBodies.isNull;
import static com.finapp.accounting.testing.ReportBodies.minor;
import static com.finapp.accounting.testing.ReportBodies.object;
import static com.finapp.accounting.testing.ReportBodies.objects;
import static com.finapp.accounting.testing.ReportBodies.section;
import static com.finapp.accounting.testing.ReportBodies.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.CloseRecordReads;
import com.finapp.accounting.CounterpartyPositionReads;
import com.finapp.accounting.GlAccount;
import com.finapp.accounting.GlClassification;
import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.MappingRule;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.PriorPeriodCorrections;
import com.finapp.accounting.ReconciliationAsOfCutoff;
import com.finapp.accounting.ReportKind;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.Side;
import com.finapp.accounting.StatementSection;
import com.finapp.accounting.SuspenseAsOfCutoff;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.ValuationRate;
import com.finapp.accounting.ValuationRateReads;
import com.finapp.accounting.ValuationRateSetStatus;
import com.finapp.accounting.testing.InMemoryJournal;
import com.finapp.accounting.testing.InMemoryTrialBalanceReads;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code BALANCE_SHEET} and {@code INCOME_STATEMENT} (`P14-TSK-016`; owner decisions C6, C7, C8; {@code INV-ACC-02}, {@code INV-ACC-04}, {@code INV-ACC-05}, {@code INV-ACC-06}, {@code INV-ACC-11}): the arithmetic itself, over a real journal of real entries, and every refusal
 * with a journal that really does not fit. Hermetic: the journal in memory, the notes' ports stood in. What those ports read is {@code app}'s {@code StatementNotesAreFrozenTest}'s and {@code StatementReadsPassTheViewTest}'s; the statements against PostgreSQL are {@code StatementsDatabaseTest}'s (owed, C5).
 */
@DisplayName("the financial statements (P14-TSK-016)")
class FinancialStatementsTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Connection SNAPSHOT = (Connection) Proxy.newProxyInstance(FinancialStatementsTest.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
        throw new UnsupportedOperationException("a report reads only through its ports");
    });
    private static final YearMonth MAY = YearMonth.of(2097, 5);
    private static final YearMonth JUNE = YearMonth.of(2097, 6);
    private static final YearMonth JULY = YearMonth.of(2097, 7);
    private static final Instant EARLY = Instant.parse("2097-05-01T00:00:00Z");
    private static final Instant AS_OF = Instant.parse("2097-07-01T00:00:00Z");
    private static final Instant LOCK = Instant.parse("2097-07-05T10:00:00Z");

    private static final String CASH = "CASH_AT_BANK/OPERATIONAL";
    private static final String WALLET = "CUSTOMER_WALLET/CUSTOMER";
    private static final String CAPITAL = "LENDING_CAPITAL/OPERATIONAL";
    private static final String FEES = "FEE_REVENUE/OPERATIONAL";
    private static final String COSTS = "PROCESSING_COSTS/OPERATIONAL";
    private static final String LOAN = "LOAN_PRINCIPAL/LOAN";
    private static final String INTEREST_DUE = "LOAN_INTEREST_DUE/LOAN";
    private static final String INTEREST_INCOME = "LOAN_INTEREST_INCOME/OPERATIONAL";
    private static final String LOAN_WRITE_OFF = "LOAN_WRITE_OFF_EXPENSE/OPERATIONAL";
    private static final String BNPL_WRITE_OFF = "BNPL_WRITE_OFF_EXPENSE/OPERATIONAL";
    private static final String BNPL = "BNPL_PRINCIPAL/BNPL_OBLIGATION";
    private static final String SUSPENSE = "SUSPENSE_UNMATCHED/SUSPENSE";
    private static final String FX_GAINS = "FX_REALISED_GAINS/OPERATIONAL";
    private static final String FX_LOSSES = "FX_REALISED_LOSSES/OPERATIONAL";
    private static final String FX_CLEARING = "FX_PROVIDER_CLEARING/COUNTERPARTY";

    private final AccountingPeriodId june = AccountingPeriodId.next(IDS);
    private final AccountingPeriodId may = AccountingPeriodId.next(IDS);
    private final GlMappingVersionId pinned = GlMappingVersionId.next(IDS);
    private final UUID rateSet = UUID.randomUUID();
    private final InMemoryTrialBalanceReads books = new InMemoryTrialBalanceReads()
            .version(pinned, ReferenceGlMapping.version(YearMonth.of(2097, 1)))
            .period(closed(june, JUNE, Optional.empty()));
    private final InMemoryJournal journal = new InMemoryJournal();

    private final Map<YearMonth, PriorPeriodCorrections.Corrections> corrections = new HashMap<>();
    private final List<ReconciliationAsOfCutoff.OpenBreaks> openBreaks = new ArrayList<>();
    private final List<ReconciliationAsOfCutoff.ParkedItem> parkedItems = new ArrayList<>();
    private final List<SuspenseAsOfCutoff.Group> registerGroups = new ArrayList<>();
    private final List<CloseRecordReads.Check> closeChecks = new ArrayList<>();
    private final List<CloseRecordReads.EarlierClose> earlierCloses = new ArrayList<>();
    private Optional<Instant> closedUnderLock = Optional.of(LOCK);
    private final Map<String, String> pinnedRates = new TreeMap<>(Map.of("USD", "1.0837", "JPY", "157.31"));

    private TrialBalanceReads.Period closed(AccountingPeriodId id, YearMonth month, Optional<GlMappingVersionId> previous) {
        return new TrialBalanceReads.Period(id, month.atDay(1), month.atEndOfMonth(), PeriodStatus.CLOSED, Optional.of(pinned), previous);
    }

    private final ValuationRateReads rates = (snapshot, id) -> Optional.of(new ValuationRateReads.PinnedSet(id, june, JUNE, ValuationRateSetStatus.APPROVED, AS_OF, 96,
            pinnedRates.entrySet().stream().map(entry -> rate(entry.getKey(), entry.getValue())).toList()));

    private static ValuationRate rate(String quote, String value) {
        return new ValuationRate(CurrencyCode.of("EUR"), CurrencyCode.of(quote), new BigDecimal(value), "reference.test", UUID.nameUUIDFromBytes(quote.getBytes(java.nio.charset.StandardCharsets.UTF_8)), AS_OF.minusSeconds(3_600), AS_OF.minusSeconds(3_599));
    }

    private StatementNotes notes() {
        return new StatementNotes((snapshot, period) -> new SuspenseAsOfCutoff.Register(period.atEndOfMonth(), AS_OF, registerGroups),
                (snapshot, period) -> new ReconciliationAsOfCutoff.Standing(period.atEndOfMonth(), AS_OF, openBreaks, parkedItems),
                (snapshot, month) -> corrections.getOrDefault(month, new PriorPeriodCorrections.Corrections(List.of(), List.of(), 0)),
                (snapshot, period, revision) -> closedUnderLock.map(lock -> new CloseRecordReads.CloseRecord(UUID.nameUUIDFromBytes(new byte[] {(byte) revision}), revision, lock, UUID.nameUUIDFromBytes(new byte[] {1}), pinned.value(),
                        rateSet, 3, 2, LOCK.plusSeconds(60), LOCK.plusSeconds(120), closeChecks, earlierCloses)));
    }

    private BalanceSheetDefinition balanceSheet() {
        return balanceSheet(books, books.counterparties());
    }

    private BalanceSheetDefinition balanceSheet(TrialBalanceReads reads, CounterpartyPositionReads positions) {
        return new BalanceSheetDefinition(reads, positions, rates, notes());
    }

    private IncomeStatementDefinition incomeStatement() {
        return incomeStatement(books, books.counterparties());
    }

    private IncomeStatementDefinition incomeStatement(TrialBalanceReads reads, CounterpartyPositionReads positions) {
        return new IncomeStatementDefinition(reads, positions, rates, notes());
    }

    private ReportInputs finalRun() {
        return new ReportInputs(june, JUNE, 1, RunBasis.FINAL, Optional.of(LOCK), Optional.of(pinned), Optional.of(1), LOCK, RunParameters.none());
    }

    private ReportInputs translatedRun() {
        return new ReportInputs(june, JUNE, 1, RunBasis.FINAL, Optional.of(LOCK), Optional.of(pinned), Optional.of(1), LOCK, RunParameters.of(Map.of(), Optional.of(rateSet)));
    }

    private ReportInputs provisionalRun() {
        books.period(new TrialBalanceReads.Period(june, JUNE.atDay(1), JUNE.atEndOfMonth(), PeriodStatus.OPEN, Optional.empty()));
        return new ReportInputs(june, JUNE, 1, RunBasis.PROVISIONAL, Optional.empty(), Optional.of(pinned), Optional.of(1), LOCK, RunParameters.none());
    }

    /** One balanced entry, in the journal the statements read and the one their lines are drilled through. */
    private void post(LocalDate date, String currency, int scale, long minor, String debit, String credit) {
        books.post(date, EARLY, currency, scale, minor, debit, credit);
        String[] debited = debit.split("/", 2);
        String[] credited = credit.split("/", 2);
        journal.post(date, currency, scale, minor, debited[0], debited[1], credited[0], credited[1]);
    }

    /**
     * Two months of ordinary business, every figure by hand. EUR: capital 1,000.00; deposits 500.00; May's fee 3.00 and cost 1.20, so retained earnings of 1.80; June's loan 200.00, interest 5.00 and a write-off 2.00, so a result of 3.00. Assets 1,501.80: cash 1,298.80 + loan
     * 198.00 + interest due 5.00. Liabilities 497.00, equity 1,000.00. 497.00 + 1,000.00 + 1.80 + 3.00 = 1,501.80. JPY: a deposit of 5,000 alone.
     */
    private void ordinaryBusiness() {
        post(MAY.atDay(2), "EUR", 2, 100_000, CASH, CAPITAL);
        post(MAY.atDay(3), "EUR", 2, 50_000, CASH, WALLET);
        post(MAY.atDay(10), "EUR", 2, 300, WALLET, FEES);
        post(MAY.atEndOfMonth(), "EUR", 2, 120, COSTS, CASH);
        post(JUNE.atDay(1), "EUR", 2, 20_000, LOAN, CASH);
        post(JUNE.atDay(15), "EUR", 2, 500, INTEREST_DUE, INTEREST_INCOME);
        post(JUNE.atEndOfMonth(), "EUR", 2, 200, LOAN_WRITE_OFF, LOAN);
        post(JUNE.atDay(20), "JPY", 0, 5_000, CASH, WALLET);
    }

    private static CanonicalJson.Obj equationOf(CanonicalJson.Obj body, String currency) {
        return objects(body, "equation").stream().filter(row -> text(row, "currency").equals(currency)).findFirst().orElseThrow();
    }

    private static CanonicalJson.Obj rowOf(CanonicalJson.Obj body, String account, String currency) {
        return allRows(body).stream().filter(row -> text(row, "glAccount").equals(account) && text(row, "currency").equals(currency)).findFirst().orElseThrow(() -> new AssertionError("no row " + account));
    }

    private static List<CanonicalJson.Obj> allRows(CanonicalJson.Obj body) {
        return objects(body, "sections").stream().flatMap(found -> objects(found, "rows").stream()).toList();
    }

    private static BigInteger big(long value) {
        return BigInteger.valueOf(value);
    }

    // ------------------------------------------------------------------ what balances, and why

    @Test
    @DisplayName("assets = liabilities + equity + retained earnings + the result, per currency, every side summed from its own lines: EUR 1,501.80 both ways, JPY 5,000 both ways, a difference of zero written and never made so")
    void assetsEqualLiabilitiesPlusEquityPlusResult() {
        ordinaryBusiness();
        CanonicalJson.Obj body = balanceSheet().generate(SNAPSHOT, finalRun());

        CanonicalJson.Obj eur = equationOf(body, "EUR");
        assertThat(minor(eur, "assets")).isEqualTo(big(150_180));
        assertThat(minor(eur, "liabilities")).isEqualTo(big(49_700));
        assertThat(minor(eur, "equity")).isEqualTo(big(100_000));
        assertThat(minor(eur, "retainedEarnings")).as("May's fee less May's cost, from their lines").isEqualTo(big(180));
        assertThat(minor(eur, "currentPeriodResult")).as("June's interest less June's write-off, from their lines").isEqualTo(big(300));
        assertThat(minor(eur, "liabilitiesEquityAndResult")).isEqualTo(big(150_180));
        assertThat(minor(eur, "difference")).isZero();
        CanonicalJson.Obj jpy = equationOf(body, "JPY");
        assertThat(minor(jpy, "assets")).isEqualTo(big(5_000));
        assertThat(minor(jpy, "liabilities")).isEqualTo(big(5_000));
        assertThat(minor(jpy, "difference")).isZero();
        assertThat(text(object(jpy, "assets"), "scale")).as("the data's scale, never the JDK's").isEqualTo("0");
        assertThat(CanonicalJson.text(object(eur, "journal"))).as("the figures are the ledger's own, written side by side").isEqualTo(CanonicalJson.text(object(eur, "ledger")));
    }

    @Test
    @DisplayName("nothing is posted and nothing is solved for: retained earnings and the result list their components, each a revenue or expense account's lines, and those sum to the figure")
    void theComputedFiguresAreSumsOfLines() {
        ordinaryBusiness();
        CanonicalJson.Obj body = balanceSheet().generate(SNAPSHOT, finalRun());

        List<CanonicalJson.Obj> retained = objects(object(body, "retainedEarnings"), "components");
        assertThat(retained).extracting(row -> text(row, "glAccount")).containsExactly("4000", "5000");
        assertThat(retained.stream().map(row -> minor(row, "amount")).reduce(BigInteger.ZERO, BigInteger::add)).isEqualTo(big(180));
        List<CanonicalJson.Obj> result = objects(object(body, "currentPeriodResult"), "components");
        assertThat(result).extracting(row -> text(row, "glAccount")).containsExactly("4100", "5100");
        assertThat(result.stream().map(row -> minor(row, "amount")).reduce(BigInteger.ZERO, BigInteger::add)).isEqualTo(big(300));
        assertThat(text(object(body, "retainedEarnings"), "rule")).contains("never posted").contains("C8");
    }

    @Test
    @DisplayName("one fold: the income statement's result per currency is the balance sheet's current-period result, and revenue less expenses reaches it")
    void theResultIsOneFigure() {
        ordinaryBusiness();
        CanonicalJson.Obj sheet = balanceSheet().generate(SNAPSHOT, finalRun());
        CanonicalJson.Obj income = incomeStatement().generate(SNAPSHOT, finalRun());

        assertThat(objects(income, "result")).extracting(row -> text(row, "currency")).containsExactly("EUR", "JPY");
        for (CanonicalJson.Obj row : objects(income, "result")) {
            assertThat(minor(row, "result")).as(text(row, "currency")).isEqualTo(minor(equationOf(sheet, text(row, "currency")), "currentPeriodResult"));
            assertThat(minor(row, "revenue").subtract(minor(row, "expenses"))).isEqualTo(minor(row, "result"));
        }
    }

    @Test
    @DisplayName("each balance on its account's normal side, and a figure on it reads positive whichever that is")
    void balancesAreOnTheirNormalSide() {
        ordinaryBusiness();
        CanonicalJson.Obj body = balanceSheet().generate(SNAPSHOT, finalRun());

        assertThat(text(body, "sign")).isEqualTo("NORMAL_SIDE_POSITIVE");
        assertThat(minor(rowOf(body, "1000", "EUR"), "closing")).as("cash, debit-normal").isEqualTo(big(129_880));
        assertThat(minor(rowOf(body, "2000", "EUR"), "closing")).as("wallets, credit-normal").isEqualTo(big(49_700));
        assertThat(minor(rowOf(body, "2000", "EUR"), "opening")).isEqualTo(big(49_700));
        assertThat(minor(rowOf(body, "1000", "EUR"), "opening")).isEqualTo(big(149_880));
        assertThat(minor(rowOf(body, "1000", "EUR"), "credits")).isEqualTo(big(20_000));
    }

    @Test
    @DisplayName("a sum past a long is written exactly: no fold into a long, no 5xx, three lines of Long.MAX_VALUE a side")
    void aSumPastLongIsWrittenExactly() {
        for (int line = 0; line < 3; line++) {
            post(JUNE.atDay(2), "EUR", 2, Long.MAX_VALUE, CASH, WALLET);
        }
        CanonicalJson.Obj eur = equationOf(balanceSheet().generate(SNAPSHOT, finalRun()), "EUR");
        assertThat(minor(eur, "assets")).isEqualTo(big(Long.MAX_VALUE).multiply(big(3)));
        assertThat(minor(eur, "difference")).isZero();
    }

    @Test
    @DisplayName("an empty month and no version, PROVISIONAL: nothing to classify, an empty answer; refused the moment a line exists")
    void anEmptyMonthIsAnEmptyAnswer() {
        books.period(new TrialBalanceReads.Period(june, JUNE.atDay(1), JUNE.atEndOfMonth(), PeriodStatus.OPEN, Optional.empty()));
        ReportInputs noVersion = new ReportInputs(june, JUNE, 1, RunBasis.PROVISIONAL, Optional.empty(), Optional.empty(), Optional.empty(), LOCK, RunParameters.none());
        assertThat(objects(balanceSheet().generate(SNAPSHOT, noVersion), "equation")).isEmpty();

        post(JUNE.atDay(2), "EUR", 2, 100, CASH, WALLET);
        assertThatThrownBy(() -> balanceSheet().generate(SNAPSHOT, noVersion)).isInstanceOfSatisfying(ReportRefused.class, refused -> {
            assertThat(refused.reason()).isEqualTo(ReportRefused.Reason.UNCLASSIFIED);
            assertThat(refused.subjects()).containsExactly(CASH, WALLET);
        });
    }

    // ------------------------------------------------------------------ a journal that does not fit refuses the run: it never issues a statement that balances because something forced it to

    @Test
    @DisplayName("a one-sided line this month: both statements refused, OUT_OF_BALANCE, naming the currency, and neither has been forced to balance")
    void aOneSidedLineRefuses() {
        ordinaryBusiness();
        books.line(new InMemoryTrialBalanceReads.JournalLine(JUNE.atDay(9), EARLY, "CASH_AT_BANK", "OPERATIONAL", "EUR", 2, Side.DEBIT, 1));
        for (ReportDefinition definition : List.of(balanceSheet(), incomeStatement())) {
            assertThatThrownBy(() -> definition.generate(SNAPSHOT, finalRun())).as(definition.code()).isInstanceOfSatisfying(ReportRefused.class, refused -> {
                assertThat(refused.reason()).isEqualTo(ReportRefused.Reason.OUT_OF_BALANCE);
                assertThat(refused.subjects()).containsExactly("EUR");
            });
        }
    }

    @Test
    @DisplayName("an imbalance dated in May and offset in June: the equation holds at the closing, and retained earnings and the result are each wrong. Refused: the trial balance's legs see it")
    void anEarlierImbalanceOffsetThisMonthRefuses() {
        ordinaryBusiness();
        books.line(new InMemoryTrialBalanceReads.JournalLine(MAY.atDay(20), EARLY, "FEE_REVENUE", "OPERATIONAL", "EUR", 2, Side.CREDIT, 700));
        books.line(new InMemoryTrialBalanceReads.JournalLine(JUNE.atDay(20), EARLY, "FEE_REVENUE", "OPERATIONAL", "EUR", 2, Side.DEBIT, 700));
        assertThatThrownBy(() -> balanceSheet().generate(SNAPSHOT, finalRun())).isInstanceOfSatisfying(ReportRefused.class,
                refused -> assertThat(refused.reason()).isEqualTo(ReportRefused.Reason.OUT_OF_BALANCE));
    }

    @Test
    @DisplayName("a read that lost a balanced pair: the equation still holds, so only the ledger's own totals can say so. Refused, NOT_THE_LEDGERS")
    void aReadThatLostABalancedPairRefuses() {
        ordinaryBusiness();
        TrialBalanceReads losing = transforming(lines -> lines.stream().filter(line -> !line.key().equals(INTEREST_DUE) && !line.key().equals(INTEREST_INCOME)).toList());
        CounterpartyPositionReads positions = books.counterparties();
        assertThatThrownBy(() -> balanceSheet(losing, positions).generate(SNAPSHOT, finalRun())).isInstanceOfSatisfying(ReportRefused.class, refused -> {
            assertThat(refused.reason()).isEqualTo(ReportRefused.Reason.NOT_THE_LEDGERS);
            assertThat(refused.subjects()).containsExactly("EUR");
        });
    }

    @Test
    @DisplayName("the journal's two reads disagreeing on one snapshot: fails closed, never a counterparty's position folded under no name")
    void theCounterpartyReadDisagreeingFails() {
        post(JUNE.atDay(2), "EUR", 2, 100, FX_CLEARING, CASH);
        CounterpartyPositionReads none = new CounterpartyPositionReads() {
            @Override
            public List<JournalReads.Aggregate> before(Connection snapshot, LocalDate before, Optional<Instant> cut) {
                return List.of();
            }

            @Override
            public List<JournalReads.Aggregate> within(Connection snapshot, JournalReads.Range range, Optional<Instant> cut) {
                return List.of();
            }
        };
        assertThatThrownBy(() -> balanceSheet(books, none).generate(SNAPSHOT, finalRun())).isInstanceOf(IllegalStateException.class).isNotInstanceOf(ReportRefused.class);
    }

    // ------------------------------------------------------------------ sections: every purpose reaches one by the version's rule, or the run is refused

    @Test
    @DisplayName("an unclassified line refuses the run: opening or movement, either statement, FINAL or PROVISIONAL, naming the purpose - never in a bucket on a statement")
    void anUnclassifiedLineRefuses() {
        books.version(pinned, ReferenceGlMapping.without(YearMonth.of(2097, 1), Set.of("BNPL_PRINCIPAL")));
        post(MAY.atDay(2), "EUR", 2, 100, BNPL, CASH);
        for (ReportDefinition definition : List.of(balanceSheet(), incomeStatement())) {
            assertThatThrownBy(() -> definition.generate(SNAPSHOT, finalRun())).as(definition.code() + ", in the opening alone").isInstanceOfSatisfying(ReportRefused.class, refused -> {
                assertThat(refused.reason()).isEqualTo(ReportRefused.Reason.UNCLASSIFIED);
                assertThat(refused.subjects()).containsExactly(BNPL);
            });
        }
        assertThatThrownBy(() -> balanceSheet().generate(SNAPSHOT, provisionalRun())).as("a PROVISIONAL run too").isInstanceOf(ReportRefused.class);
    }

    @Test
    @DisplayName("the write-offs: LOAN_WRITE_OFF_EXPENSE and BNPL_WRITE_OFF_EXPENSE in CREDIT_LOSSES, an expense, their figure what the journal holds")
    void theWriteOffsReachCreditLosses() {
        post(JUNE.atDay(2), "EUR", 2, 200, LOAN_WRITE_OFF, LOAN);
        post(JUNE.atDay(3), "EUR", 2, 90, BNPL_WRITE_OFF, BNPL);
        CanonicalJson.Obj body = incomeStatement().generate(SNAPSHOT, finalRun());
        CanonicalJson.Obj losses = section(body, "CREDIT_LOSSES");
        assertThat(objects(losses, "rows")).extracting(row -> text(row, "glAccount")).containsExactly("5100", "5110");
        assertThat(objects(losses, "totals")).singleElement().satisfies(total -> {
            assertThat(text(total, "classification")).isEqualTo("EXPENSE");
            assertThat(minor(total, "net")).isEqualTo(big(290));
        });
    }

    @Test
    @DisplayName("nothing written off, the section stands empty: the note makes no claim either way, and the figure is the journal's")
    void noWriteOffLeavesAnEmptySection() {
        post(JUNE.atDay(2), "EUR", 2, 100, CASH, WALLET);
        CanonicalJson.Obj body = incomeStatement().generate(SNAPSHOT, finalRun());
        assertThat(objects(section(body, "CREDIT_LOSSES"), "rows")).isEmpty();
        assertThat(objects(section(body, "CREDIT_LOSSES"), "totals")).isEmpty();
    }

    @Test
    @DisplayName("suspense either side: in net credit a liability; in net debit a negative liability, shown as such and never moved to assets, and the equation still holds")
    void suspenseEitherSide() {
        post(JUNE.atDay(2), "EUR", 2, 400, CASH, SUSPENSE);
        CanonicalJson.Obj credit = balanceSheet().generate(SNAPSHOT, finalRun());
        assertThat(minor(rowOf(credit, "2900", "EUR"), "closing")).isEqualTo(big(400));
        assertThat(text(rowOf(credit, "2900", "EUR"), "classification")).isEqualTo("LIABILITY");

        post(JUNE.atDay(3), "EUR", 2, 1_000, SUSPENSE, CASH);
        CanonicalJson.Obj debit = balanceSheet().generate(SNAPSHOT, finalRun());
        assertThat(minor(rowOf(debit, "2900", "EUR"), "closing")).as("against its normal side").isEqualTo(big(-600));
        assertThat(objects(section(debit, "SUSPENSE"), "totals")).singleElement().satisfies(total -> assertThat(text(total, "classification")).isEqualTo("LIABILITY"));
        assertThat(minor(equationOf(debit, "EUR"), "liabilities")).isEqualTo(big(-600));
        assertThat(minor(equationOf(debit, "EUR"), "assets")).isEqualTo(big(-600));
        assertThat(minor(equationOf(debit, "EUR"), "difference")).isZero();
    }

    @Test
    @DisplayName("a section total sums one normal side: SUSPENSE holding an asset and a liability writes two totals, FX_RESULT a revenue and an expense writes two, and never one figure across them")
    void aSectionTotalSumsOneNormalSide() {
        GlAccount assetSuspense = new GlAccount("2905", "Suspense held as an asset", GlClassification.ASSET, StatementSection.SUSPENSE);
        GlMappingVersion base = ReferenceGlMapping.version(YearMonth.of(2097, 1));
        List<GlAccount> accounts = new ArrayList<>(base.accounts());
        accounts.add(assetSuspense);
        List<MappingRule> rules = base.rules().stream().map(rule -> rule.purpose().equals("CHARGEBACK_RECOVERABLE") ? new MappingRule(rule.purpose(), rule.ownerKind(), "2905") : rule).toList();
        books.version(pinned, new GlMappingVersion(YearMonth.of(2097, 1), accounts, rules));
        post(JUNE.atDay(2), "EUR", 2, 400, "CHARGEBACK_RECOVERABLE/OPERATIONAL", SUSPENSE);
        post(JUNE.atDay(3), "EUR", 2, 300, CASH, FX_GAINS);
        post(JUNE.atDay(4), "EUR", 2, 120, FX_LOSSES, CASH);

        CanonicalJson.Obj sheet = balanceSheet().generate(SNAPSHOT, finalRun());
        assertThat(objects(section(sheet, "SUSPENSE"), "totals")).extracting(total -> text(total, "classification") + ":" + minor(total, "closing")).containsExactly("ASSET:400", "LIABILITY:400");
        CanonicalJson.Obj income = incomeStatement().generate(SNAPSHOT, finalRun());
        assertThat(objects(section(income, "FX_RESULT"), "totals")).extracting(total -> text(total, "classification") + ":" + minor(total, "net")).containsExactly("EXPENSE:120", "REVENUE:300");
        assertThat(minor(equationOf(sheet, "EUR"), "difference")).isZero();
    }

    @Test
    @DisplayName("every section of each statement is written, in the enum's order, rows or none: a section is never absent, and each row lands in the section its account says")
    void everySectionIsWritten() {
        ordinaryBusiness();
        assertThat(objects(balanceSheet().generate(SNAPSHOT, finalRun()), "sections")).extracting(found -> text(found, "section"))
                .containsExactly("CASH_AND_BANK", "CLEARING_AND_SETTLEMENT_RECEIVABLES", "LOANS_AND_BNPL_RECEIVABLES", "OTHER_ASSETS", "CUSTOMER_FUNDS", "MERCHANT_PAYABLES", "CLEARING_AND_SETTLEMENT_PAYABLES", "OTHER_LIABILITIES", "SUSPENSE", "CAPITAL");
        assertThat(objects(incomeStatement().generate(SNAPSHOT, finalRun()), "sections")).extracting(found -> text(found, "section"))
                .containsExactly("FEE_AND_COMMISSION_INCOME", "INTEREST_INCOME", "FX_RESULT", "OTHER_INCOME", "PROCESSING_AND_DISPUTE_COSTS", "CREDIT_LOSSES", "OTHER_EXPENSES");
    }

    @Test
    @DisplayName("net realised FX: gains less losses per currency, by purpose whichever account a version maps them to - the owner's decision: FX_RESULTS.REALISED keeps no total, so the statement carries it")
    void netRealisedFx() {
        post(JUNE.atDay(3), "EUR", 2, 300, CASH, FX_GAINS);
        post(JUNE.atDay(4), "EUR", 2, 120, FX_LOSSES, CASH);
        post(JUNE.atDay(5), "USD", 2, 40, FX_LOSSES, CASH);
        CanonicalJson.Obj body = incomeStatement().generate(SNAPSHOT, finalRun());
        List<CanonicalJson.Obj> net = objects(body, "netRealisedFx");
        assertThat(net).extracting(row -> text(row, "currency")).containsExactly("EUR", "USD");
        assertThat(minor(net.get(0), "gains")).isEqualTo(big(300));
        assertThat(minor(net.get(0), "losses")).isEqualTo(big(120));
        assertThat(minor(net.get(0), "net")).isEqualTo(big(180));
        assertThat(minor(net.get(1), "net")).as("a net loss reads negative").isEqualTo(big(-40));
    }

    @Test
    @DisplayName("no FX line in the month, no row: with no FX at all, and with FX only in an earlier month, which is in retained earnings and moved nothing here. A gain and its reversal in the month moved: a row, reading zero")
    void noFxInTheMonthNoRow() {
        ordinaryBusiness();
        assertThat(objects(incomeStatement().generate(SNAPSHOT, finalRun()), "netRealisedFx")).as("no FX at all").isEmpty();

        post(MAY.atDay(4), "EUR", 2, 300, CASH, FX_GAINS);
        post(MAY.atDay(5), "USD", 2, 40, FX_LOSSES, CASH);
        assertThat(objects(incomeStatement().generate(SNAPSHOT, finalRun()), "netRealisedFx")).as("FX only in May, and June's statement").isEmpty();

        post(JUNE.atDay(6), "EUR", 2, 50, CASH, FX_GAINS);
        post(JUNE.atDay(7), "EUR", 2, 50, FX_GAINS, CASH);
        List<CanonicalJson.Obj> moved = objects(incomeStatement().generate(SNAPSHOT, finalRun()), "netRealisedFx");
        assertThat(moved).extracting(row -> text(row, "currency")).containsExactly("EUR");
        assertThat(minor(moved.get(0), "net")).isZero();
    }

    @Test
    @DisplayName("nothing nets between counterparties: fx-sim-a +1,000 and fx-sim-b -1,000 on one account read 0 net, with two components and 1,000 gross each side")
    void nothingNetsBetweenCounterparties() {
        books.postFor("fx-sim-a", JUNE.atDay(2), EARLY, "EUR", 2, 1_000, FX_CLEARING, CASH);
        books.postFor("fx-sim-b", JUNE.atDay(3), EARLY, "EUR", 2, 1_000, CASH, FX_CLEARING);
        CanonicalJson.Obj row = rowOf(balanceSheet().generate(SNAPSHOT, finalRun()), "1120", "EUR");

        assertThat(minor(row, "closing")).isZero();
        assertThat(objects(row, "components")).extracting(component -> text(component, "counterparty") + ":" + minor(component, "closing")).containsExactly("fx-sim-a:1000", "fx-sim-b:-1000");
        assertThat(minor(object(row, "counterpartyGross"), "onNormalSide")).isEqualTo(big(1_000));
        assertThat(minor(object(row, "counterpartyGross"), "againstNormalSide")).isEqualTo(big(1_000));
        assertThat(isNull(rowOf(balanceSheet().generate(SNAPSHOT, finalRun()), "1000", "EUR"), "counterpartyGross")).as("no counterparty on a row of none").isTrue();
    }

    @Test
    @DisplayName("the bridge: the previous period pins another version, so the opening restates its closing per account; fx-sim-a's +1,000 and fx-sim-b's -1,000 moved are two moves, never no move; it foots to zero; the income statement has no opening to bridge")
    void theBridgeIsPerCounterparty() {
        GlMappingVersionId previous = GlMappingVersionId.next(IDS);
        GlMappingVersion base = ReferenceGlMapping.version(YearMonth.of(2097, 1));
        List<GlAccount> accounts = new ArrayList<>(base.accounts());
        accounts.add(new GlAccount("1125", "FX provider clearing, as it was", GlClassification.ASSET, StatementSection.CLEARING_AND_SETTLEMENT_RECEIVABLES));
        List<MappingRule> rules = base.rules().stream().map(rule -> rule.purpose().equals("FX_PROVIDER_CLEARING") ? new MappingRule(rule.purpose(), rule.ownerKind(), "1125") : rule).toList();
        books.version(previous, new GlMappingVersion(YearMonth.of(2097, 1), accounts, rules));
        books.period(closed(june, JUNE, Optional.of(previous)));
        books.postFor("fx-sim-a", MAY.atDay(2), EARLY, "EUR", 2, 1_000, FX_CLEARING, CASH);
        books.postFor("fx-sim-b", MAY.atDay(3), EARLY, "EUR", 2, 1_000, CASH, FX_CLEARING);

        CanonicalJson.Obj bridge = object(balanceSheet().generate(SNAPSHOT, finalRun()), "reclassification");
        assertThat(objects(bridge, "moves")).extracting(move -> text(move, "counterparty") + ":" + text(move, "from") + ">" + text(move, "to") + ":" + minor(move, "balance"))
                .containsExactly("fx-sim-a:1125>1120:1000", "fx-sim-b:1125>1120:-1000");
        assertThat(flag(bridge, "footsToZero")).isTrue();
        assertThat(incomeStatement().generate(SNAPSHOT, finalRun()).members()).doesNotContainKey("reclassification");
    }

    @Test
    @DisplayName("the same version as the month before, or no month before: no bridge, and it says so")
    void noBridgeWhenNothingMoved() {
        ordinaryBusiness();
        assertThat(isNull(balanceSheet().generate(SNAPSHOT, finalRun()), "reclassification")).isTrue();
        books.period(closed(june, JUNE, Optional.of(pinned)));
        assertThat(isNull(balanceSheet().generate(SNAPSHOT, finalRun()), "reclassification")).isTrue();
    }

    // ------------------------------------------------------------------ frozen, or it is not FINAL

    @Test
    @DisplayName("only a CLOSED period: a FINAL run of a LOCKED or CLOSE_PROPOSED period is refused in the snapshot too, for a reclaimed run or a bypass of the claim")
    void onlyAClosedPeriod() {
        ordinaryBusiness();
        for (PeriodStatus status : List.of(PeriodStatus.LOCKED, PeriodStatus.CLOSE_PROPOSED)) {
            books.period(new TrialBalanceReads.Period(june, JUNE.atDay(1), JUNE.atEndOfMonth(), status, Optional.of(pinned)));
            for (ReportDefinition definition : List.of(balanceSheet(), incomeStatement())) {
                assertThatThrownBy(() -> definition.generate(SNAPSHOT, finalRun())).as(status + " " + definition.code()).isInstanceOfSatisfying(ReportRefused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(ReportRefused.Reason.NOT_CLOSED));
            }
        }
    }

    @Test
    @DisplayName("a CLOSED period with no approved close at its revision, or one under another lock: refused, because the note would say what no record holds")
    void aClosedPeriodHasItsRecord() {
        ordinaryBusiness();
        closedUnderLock = Optional.empty();
        assertThatThrownBy(() -> balanceSheet().generate(SNAPSHOT, finalRun())).isInstanceOfSatisfying(ReportRefused.class, refused -> assertThat(refused.reason()).isEqualTo(ReportRefused.Reason.CLOSE_RECORD_MISSING));
        closedUnderLock = Optional.of(LOCK.minusSeconds(1));
        assertThatThrownBy(() -> incomeStatement().generate(SNAPSHOT, finalRun())).isInstanceOfSatisfying(ReportRefused.class, refused -> assertThat(refused.reason()).isEqualTo(ReportRefused.Reason.CLOSE_RECORD_MISSING));
    }

    @Test
    @DisplayName("INV-ACC-04: regenerated byte-identical after a later month's entries, after a prior-period adjustment dated later that corrects it, whatever order the reads serve; and the control, an entry dated into the month, moves both, so the comparison can see it")
    void aFinalBodyIgnoresWhatComesAfter() {
        ordinaryBusiness();
        String sheet = CanonicalJson.text(balanceSheet().generate(SNAPSHOT, finalRun()));
        String income = CanonicalJson.text(incomeStatement().generate(SNAPSHOT, finalRun()));

        post(JULY.atDay(3), "EUR", 2, 4_000, FEES, WALLET);
        corrections.put(JULY, new PriorPeriodCorrections.Corrections(List.of(new PriorPeriodCorrections.Adjustment(UUID.randomUUID(), JUNE, 1, UUID.randomUUID(), JULY.atDay(3))), List.of(), 0));
        assertThat(CanonicalJson.text(balanceSheet().generate(SNAPSHOT, finalRun()))).isEqualTo(sheet);
        assertThat(CanonicalJson.text(incomeStatement().generate(SNAPSHOT, finalRun()))).isEqualTo(income);

        TrialBalanceReads reversed = transforming(lines -> {
            List<JournalReads.Aggregate> flipped = new ArrayList<>(lines);
            Collections.reverse(flipped);
            return flipped;
        });
        assertThat(CanonicalJson.text(balanceSheet(reversed, books.counterparties()).generate(SNAPSHOT, finalRun()))).as("the reads' order decides no byte").isEqualTo(sheet);

        post(JUNE.atDay(25), "EUR", 2, 4_000, FEES, WALLET);
        assertThat(CanonicalJson.text(balanceSheet().generate(SNAPSHOT, finalRun()))).as("the control").isNotEqualTo(sheet);
        assertThat(CanonicalJson.text(incomeStatement().generate(SNAPSHOT, finalRun()))).as("the control").isNotEqualTo(income);
    }

    @Test
    @DisplayName("the adjustment dated later is disclosed where it is dated, naming the month it corrects and the revision: July's note says June, and June's bytes above never moved")
    void aLaterCorrectionIsDisclosedWhereItIsDated() {
        UUID adjustment = UUID.randomUUID();
        UUID entry = UUID.randomUUID();
        corrections.put(JULY, new PriorPeriodCorrections.Corrections(List.of(new PriorPeriodCorrections.Adjustment(adjustment, JUNE, 2, entry, JULY.atDay(3))), List.of(), 0));
        AccountingPeriodId july = AccountingPeriodId.next(IDS);
        books.period(new TrialBalanceReads.Period(july, JULY.atDay(1), JULY.atEndOfMonth(), PeriodStatus.CLOSED, Optional.of(pinned)));
        CanonicalJson.Obj body = balanceSheet().generate(SNAPSHOT, new ReportInputs(july, JULY, 1, RunBasis.FINAL, Optional.of(LOCK), Optional.of(pinned), Optional.of(1), LOCK, RunParameters.none()));

        CanonicalJson.Obj note = objects(object(body, "notes", "priorPeriodCorrections"), "adjustments").get(0);
        assertThat(text(note, "correctedMonth")).isEqualTo("2097-06");
        assertThat(text(note, "correctedRevision")).isEqualTo("2");
        assertThat(text(note, "id")).isEqualTo(adjustment.toString());
        assertThat(text(note, "entry")).isEqualTo(entry.toString());
        assertThat(objects(object(balanceSheet().generate(SNAPSHOT, finalRun()), "notes", "priorPeriodCorrections"), "adjustments")).isEmpty();
    }

    // ------------------------------------------------------------------ the notes

    @Test
    @DisplayName("they disclose suspense and ageing and open breaks gross, the close record's counts, and what corrected earlier months; both statements, the same set")
    void notesDiscloseSuspenseAndBreaks() {
        ordinaryBusiness();
        UUID item = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID standing = UUID.randomUUID();
        registerGroups.add(new SuspenseAsOfCutoff.Group("EUR", 2, 2, big(600), 1, big(90), new TreeMap<>(Map.of("0-2", 2L, "over-30", 1L)), Optional.of(LocalDate.of(2097, 5, 1))));
        parkedItems.add(new ReconciliationAsOfCutoff.ParkedItem(item, owner));
        openBreaks.add(new ReconciliationAsOfCutoff.OpenBreaks("EUR", 2, 1, big(450), new TreeMap<>(Map.of("3-7", 1L)), LocalDate.of(2097, 6, 25), List.of(standing)));
        closeChecks.add(new CloseRecordReads.Check("RECONCILIATION_DISCLOSURE", "DISCLOSED", 2, UUID.randomUUID(), "reconciliation-disclosure:v2"));
        closeChecks.add(new CloseRecordReads.Check("GL_EQUALS_LEDGER", "PASSED", 1, UUID.randomUUID(), "gl-mapping:v1"));

        for (ReportDefinition definition : List.of(balanceSheet(), incomeStatement())) {
            CanonicalJson.Obj notes = object(definition.generate(SNAPSHOT, finalRun()), "notes");
            CanonicalJson.Obj suspense = object(notes, "suspense");
            assertThat(text(suspense, "asOf")).isEqualTo(AS_OF.toString());
            CanonicalJson.Obj eur = objects(suspense, "groups").get(0);
            assertThat(text(eur, "creditUnreleasedMinor")).isEqualTo("600");
            assertThat(text(eur, "debitUnreleasedMinor")).as("gross: a debit item never nets a credit item").isEqualTo("90");
            assertThat(text(object(eur, "ageing"), "over-30")).isEqualTo("1");
            assertThat(objects(suspense, "items")).singleElement().satisfies(found -> {
                assertThat(text(found, "item")).isEqualTo(item.toString());
                assertThat(text(found, "owningBreak")).isEqualTo(owner.toString());
            });
            assertThat(text(suspense, "apart")).contains("never netted");

            CanonicalJson.Obj breaks = objects(object(notes, "reconciliationBreaks"), "groups").get(0);
            assertThat(text(breaks, "breaks")).isEqualTo("1");
            assertThat(text(breaks, "valueAtIssueMinor")).isEqualTo("450");
            assertThat(text(object(breaks, "ageing"), "3-7")).isEqualTo("1");
            assertThat(text(breaks, "oldestRaisedOn")).isEqualTo("2097-06-25");
            assertThat(CanonicalJson.text(breaks)).contains(standing.toString());

            CanonicalJson.Obj record = object(notes, "closeRecord");
            assertThat(text(object(record, "accountingBreaks"), "acknowledgedAtProposal")).isEqualTo("3");
            assertThat(text(object(record, "accountingBreaks"), "resolvedAtProposal")).isEqualTo("2");
            assertThat(text(record, "disclosedChecks")).isEqualTo("1");
            assertThat(objects(record, "checks")).hasSize(2);
            assertThat(text(record, "revision")).isEqualTo("1");
        }
    }

    @Test
    @DisplayName("a CONFIDENTIAL reason is not quoted, and not even read: no check in a note holds one, the port's record has no such member, and the note says where it is read")
    void aReasonIsNeverQuoted() {
        ordinaryBusiness();
        closeChecks.add(new CloseRecordReads.Check("RECONCILIATION_DISCLOSURE", "DISCLOSED", 2, UUID.randomUUID(), "reconciliation-disclosure:v2"));
        assertThat(java.util.Arrays.stream(CloseRecordReads.Check.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)).doesNotContain("reason", "disclosureReason", "evidence");
        assertThat(java.util.Arrays.stream(CloseRecordReads.CloseRecord.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)).doesNotContain("proposedBy", "decidedBy", "proposalReason", "decisionReason");
        CanonicalJson.Obj record = object(balanceSheet().generate(SNAPSHOT, finalRun()), "notes", "closeRecord");
        assertThat(objects(record, "checks").get(0).members()).containsOnlyKeys("check", "status", "attempt", "attemptId", "judgedBy");
        assertThat(text(record, "reasons")).contains("CONFIDENTIAL").contains("ACCOUNTING_CLOSE_APPROVE").contains("not quoted");
    }

    @Test
    @DisplayName("prior-period corrections: untraced is written, zero or not, never assumed; the month's reversals by namespace; FINAL says a closed month, PROVISIONAL says only before the month, which may be open")
    void corrections() {
        corrections.put(JUNE, new PriorPeriodCorrections.Corrections(List.of(), List.of(new PriorPeriodCorrections.DomainReversals("transfer", MAY, 2)), 3));
        CanonicalJson.Obj closed = object(balanceSheet().generate(SNAPSHOT, finalRun()), "notes", "priorPeriodCorrections");
        assertThat(text(closed, "untraced")).isEqualTo("3");
        assertThat(objects(closed, "domainReversals")).singleElement().satisfies(reversal -> {
            assertThat(text(reversal, "namespace")).isEqualTo("transfer");
            assertThat(text(reversal, "originalMonth")).isEqualTo("2097-05");
            assertThat(text(reversal, "entries")).isEqualTo("2");
        });
        assertThat(text(closed, "reversalsMean")).isEqualTo(StatementNotes.REVERSALS_FINAL).contains("closed month");

        corrections.put(JUNE, new PriorPeriodCorrections.Corrections(List.of(), List.of(), 0));
        assertThat(text(object(balanceSheet().generate(SNAPSHOT, finalRun()), "notes", "priorPeriodCorrections"), "untraced")).as("zero, written").isEqualTo("0");

        CanonicalJson.Obj open = object(balanceSheet().generate(SNAPSHOT, provisionalRun()), "notes", "priorPeriodCorrections");
        assertThat(text(open, "reversalsMean")).isEqualTo(StatementNotes.REVERSALS_PROVISIONAL).contains("before the month").contains("may still be open").doesNotContain("closed month");
    }

    @Test
    @DisplayName("a PROVISIONAL run has no close at its revision: closeRecord null, the rest as read when generated")
    void aProvisionalRunHasNoCloseRecord() {
        ordinaryBusiness();
        CanonicalJson.Obj body = incomeStatement().generate(SNAPSHOT, provisionalRun());
        assertThat(isNull(object(body, "notes"), "closeRecord")).isTrue();
        assertThat(object(body, "notes").members()).containsKeys("suspense", "reconciliationBreaks", "priorPeriodCorrections", "deferredTreatments", "referenceChart");
    }

    @Test
    @DisplayName("a re-closed month: its own revision's record, and the first close named beside it, frozen")
    void aReClosedMonthNamesTheFirstClose() {
        UUID first = UUID.randomUUID();
        earlierCloses.add(new CloseRecordReads.EarlierClose(1, first, LOCK.minusSeconds(86_400)));
        ReportInputs revisionTwo = new ReportInputs(june, JUNE, 2, RunBasis.FINAL, Optional.of(LOCK), Optional.of(pinned), Optional.of(1), LOCK, RunParameters.none());
        CanonicalJson.Obj record = object(balanceSheet().generate(SNAPSHOT, revisionTwo), "notes", "closeRecord");
        assertThat(text(record, "revision")).isEqualTo("2");
        assertThat(objects(record, "earlierCloses")).singleElement().satisfies(earlier -> {
            assertThat(text(earlier, "revision")).isEqualTo("1");
            assertThat(text(earlier, "close")).isEqualTo(first.toString());
        });
    }

    @Test
    @DisplayName("C6 reads true whichever of this task and P14-TSK-021 merges first: the owner's words, claiming neither that write-offs have occurred nor that none can; provisioning and effective interest not applied")
    void theWriteOffNoteClaimsNeither() {
        CanonicalJson.Obj treatments = object(balanceSheet().generate(SNAPSHOT, finalRun()), "notes", "deferredTreatments");
        assertThat(text(treatments, "writeOffsProvisioningAndEffectiveInterest"))
                .contains("posted only by a four-eyes command")
                .contains("LOAN_WRITE_OFF_EXPENSE").contains("BNPL_WRITE_OFF_EXPENSE")
                .contains("no automatic charge-off")
                .contains("Provisioning and effective-interest amortisation are not applied")
                .doesNotContainIgnoringCase("no write-off").doesNotContainIgnoringCase("have been").doesNotContainIgnoringCase("has been").doesNotContainIgnoringCase("none").doesNotContainIgnoringCase("not yet");
    }

    @Test
    @DisplayName("C7, M-7 decided: the note names the unrealised effect and the cross-foot, on a plain run and a translated one alike. The reference-chart disclaimer stands on every statement")
    void theTranslationNoteAndTheDisclaimer() {
        for (ReportInputs run : List.of(finalRun(), translatedRun())) {
            CanonicalJson.Obj notes = object(balanceSheet().generate(SNAPSHOT, run), "notes");
            assertThat(text(object(notes, "deferredTreatments"), "unrealisedFx")).contains("unrealised revaluation is not posted").contains("need not cross-foot").contains("comparing two runs").contains("one pinned valuation rate set");
            assertThat(text(notes, "referenceChart")).contains("reference artefacts").contains("not a statutory chart").contains("no").contains("qualified review");
        }
    }

    @Test
    @DisplayName("every note states its route to what it counts, naming the permission")
    void everyNoteStatesItsRoute() {
        ordinaryBusiness();
        CanonicalJson.Obj notes = object(balanceSheet().generate(SNAPSHOT, finalRun()), "notes");
        assertThat(text(object(notes, "suspense"), "route")).contains("RECONCILIATION_INVESTIGATE").contains("not the accountant's").contains("ACCOUNTING_READ");
        assertThat(text(object(notes, "reconciliationBreaks"), "route")).contains("RECONCILIATION_INVESTIGATE").contains("not the accountant's").contains("ACCOUNTING_READ");
        assertThat(text(object(notes, "closeRecord"), "route")).contains("ACCOUNTING_READ");
        assertThat(text(object(notes, "priorPeriodCorrections"), "route")).contains("ACCOUNTING_READ").contains("REVERSAL").contains("ADJUSTMENT");
        assertThat(text(object(notes, "priorPeriodCorrections"), "route")).as("the count says it has no route, and does not name a subtraction that cannot isolate it").contains("Untraced has no line route").doesNotContain("less GET");
    }

    // ------------------------------------------------------------------ every figure drills down

    /** A row's lines through the very fold the door runs: under the run's version, month by month, by keyset page, until {@code wanted} lines are counted. */
    private BigInteger[] walk(String account, String currency, YearMonth from, long wanted) {
        return walk(pinned, books.version(SNAPSHOT, pinned), account, currency, from, wanted);
    }

    /** {@link #walk(String, String, YearMonth, long)} under {@code version}: the door on a run that pinned it. */
    private BigInteger[] walk(GlMappingVersionId version, GlMappingVersion content, String account, String currency, YearMonth from, long wanted) {
        GeneralLedger ledger = new GeneralLedger(journal);
        BigInteger debits = BigInteger.ZERO;
        BigInteger credits = BigInteger.ZERO;
        long counted = 0;
        for (YearMonth month = from; counted < wanted; month = month.minusMonths(1)) {
            assertThat(month).as("walked past the journal's start with lines still uncounted").isAfter(YearMonth.of(2097, 1));
            Optional<JournalReads.LineCursor> after = Optional.empty();
            do {
                GlActivity page = ledger.activity(SNAPSHOT, new PinnedMapping(month, Optional.empty(), version, 1, content), account, currency, after, 2);
                for (JournalReads.Line line : page.lines()) {
                    counted++;
                    if (line.side() == Side.DEBIT) {
                        debits = debits.add(big(line.minorUnits()));
                    } else {
                        credits = credits.add(big(line.minorUnits()));
                    }
                }
                after = page.next();
            } while (after.isPresent());
        }
        assertThat(counted).as("exactly the lines the row counts").isEqualTo(wanted);
        return new BigInteger[] {debits, credits};
    }

    @Test
    @DisplayName("each row's debits, credits and lines are the month's lines; each opening is the earlier months' lines, walked back until openingLines are counted; the closing is their sum; a total and each side of the equation sum the rows; each computed figure sums its components' lines; a bridge's opening and moves are this run's lines, and its previous closing the previous version's, an account this version retired refused on this run")
    void everyFigureDrillsToLines() {
        ordinaryBusiness();
        CanonicalJson.Obj body = balanceSheet().generate(SNAPSHOT, finalRun());

        BigInteger assets = BigInteger.ZERO;
        for (CanonicalJson.Obj row : allRows(body)) {
            String account = text(row, "glAccount");
            String currency = text(row, "currency");
            BigInteger[] movement = walk(account, currency, JUNE, Long.parseLong(text(row, "lines")));
            assertThat(movement[0]).as(account + " debits").isEqualTo(minor(row, "debits"));
            assertThat(movement[1]).as(account + " credits").isEqualTo(minor(row, "credits"));
            long openingLines = Long.parseLong(text(row, "openingLines"));
            BigInteger[] opening = openingLines == 0 ? new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO} : walk(account, currency, MAY, openingLines);
            boolean debitNormal = text(row, "normalBalance").equals("DEBIT");
            BigInteger openingNet = debitNormal ? opening[0].subtract(opening[1]) : opening[1].subtract(opening[0]);
            assertThat(openingNet).as(account + " opening").isEqualTo(minor(row, "opening"));
            BigInteger movementNet = debitNormal ? movement[0].subtract(movement[1]) : movement[1].subtract(movement[0]);
            assertThat(openingNet.add(movementNet)).as(account + " closing").isEqualTo(minor(row, "closing"));
            if (text(row, "classification").equals("ASSET") && currency.equals("EUR")) {
                assets = assets.add(minor(row, "closing"));
            }
        }
        assertThat(assets).as("a side of the equation is the sum of its rows").isEqualTo(minor(equationOf(body, "EUR"), "assets"));

        BigInteger retained = BigInteger.ZERO;
        for (CanonicalJson.Obj component : objects(object(body, "retainedEarnings"), "components")) {
            BigInteger[] lines = walk(text(component, "glAccount"), text(component, "currency"), MAY, Long.parseLong(text(component, "lines")));
            assertThat(lines[0]).isEqualTo(minor(component, "debits"));
            assertThat(lines[1]).isEqualTo(minor(component, "credits"));
            retained = retained.add(lines[1]).subtract(lines[0]);
        }
        assertThat(retained).as("retained earnings is its accounts' lines, and nothing else").isEqualTo(minor(equationOf(body, "EUR"), "retainedEarnings"));

        BigInteger result = BigInteger.ZERO;
        for (CanonicalJson.Obj component : objects(object(body, "currentPeriodResult"), "components")) {
            BigInteger[] lines = walk(text(component, "glAccount"), text(component, "currency"), JUNE, Long.parseLong(text(component, "lines")));
            result = result.add(lines[1]).subtract(lines[0]);
        }
        assertThat(result).as("the result likewise").isEqualTo(minor(equationOf(body, "EUR"), "currentPeriodResult"));

        // The bridge: the previous period pinned a version holding the cash under an account this version retired.
        GlMappingVersionId previous = GlMappingVersionId.next(IDS);
        GlMappingVersion reference = ReferenceGlMapping.version(YearMonth.of(2097, 1));
        List<GlAccount> accounts = new ArrayList<>(reference.accounts().stream().filter(account -> !account.code().equals("1000")).toList());
        accounts.add(new GlAccount("1005", "Cash at bank, as it was", GlClassification.ASSET, StatementSection.CASH_AND_BANK));
        List<MappingRule> rules = reference.rules().stream().map(rule -> rule.glAccountCode().equals("1000") ? new MappingRule(rule.purpose(), rule.ownerKind(), "1005") : rule).toList();
        GlMappingVersion previousContent = new GlMappingVersion(YearMonth.of(2097, 1), accounts, rules);
        books.version(previous, previousContent);
        books.period(closed(june, JUNE, Optional.of(previous)));

        CanonicalJson.Obj restated = balanceSheet().generate(SNAPSHOT, finalRun());
        CanonicalJson.Obj bridge = object(restated, "reclassification");
        long openingLines = Long.parseLong(text(rowOf(restated, "1000", "EUR"), "openingLines"));
        BigInteger[] to = walk("1000", "EUR", MAY, openingLines);
        BigInteger carried = to[0].subtract(to[1]);
        List<CanonicalJson.Obj> moves = objects(bridge, "moves");
        assertThat(moves).as("a bridge there is").isNotEmpty();
        assertThat(moves).allSatisfy(move -> assertThat(text(move, "from") + ">" + text(move, "to")).isEqualTo("1005>1000"));
        assertThat(moves.stream().map(move -> minor(move, "balance")).reduce(BigInteger.ZERO, BigInteger::add)).as("what moved is the to account's lines, debit-positive").isEqualTo(carried);
        assertThat(minor(bridgeOf(bridge, "1000"), "opening")).isEqualTo(carried);

        assertThatThrownBy(() -> new GeneralLedger(journal).activity(SNAPSHOT, new PinnedMapping(MAY, Optional.empty(), pinned, 1, books.version(SNAPSHOT, pinned)), "1005", "EUR", Optional.empty(), 2))
                .as("retired from this run's version, the from account has no door on this run").isInstanceOf(GeneralLedger.GlAccountNotFound.class);
        BigInteger[] from = walk(previous, previousContent, "1005", "EUR", MAY, openingLines);
        assertThat(from[0].subtract(from[1])).as("the previous closing: a run of the previous month, under the version the bridge names").isEqualTo(minor(bridgeOf(bridge, "1005"), "previousClosing"));
    }

    private static CanonicalJson.Obj bridgeOf(CanonicalJson.Obj bridge, String account) {
        return objects(bridge, "accounts").stream().filter(row -> text(row, "account").equals(account)).findFirst().orElseThrow(() -> new AssertionError("no bridge row " + account));
    }

    @Test
    @DisplayName("the income statement's rows are the month's lines, and a section's total sums them")
    void theIncomeStatementDrills() {
        ordinaryBusiness();
        CanonicalJson.Obj body = incomeStatement().generate(SNAPSHOT, finalRun());
        for (CanonicalJson.Obj row : allRows(body)) {
            BigInteger[] lines = walk(text(row, "glAccount"), text(row, "currency"), JUNE, Long.parseLong(text(row, "lines")));
            assertThat(lines[0]).isEqualTo(minor(row, "debits"));
            assertThat(lines[1]).isEqualTo(minor(row, "credits"));
        }
        assertThat(minor(objects(section(body, "INTEREST_INCOME"), "totals").get(0), "net")).isEqualTo(big(500));
    }

    @Test
    @DisplayName("every statement says how: the routes are in the content, naming the permission and the run's own door")
    void theRoutesAreStated() {
        CanonicalJson.Obj drill = object(balanceSheet().generate(SNAPSHOT, finalRun()), "drillDown");
        assertThat(text(drill, "movement")).contains("/gl/accounts/{glAccount}/activity").contains("ACCOUNTING_READ");
        assertThat(text(drill, "balance")).contains("/report-runs/{runId}/lines").contains("openingLines").contains("ACCOUNTING_READ");
        assertThat(text(drill, "computed")).contains("retainedEarnings").contains("currentPeriodResult");
        assertThat(text(drill, "reclassification")).as("a bridge's figures have a route, the retired account's stated").contains("/report-runs/{runId}/lines").contains("previousPeriodPin").contains("accounting.NotFound").contains("debit-positive");
    }

    // ------------------------------------------------------------------ translated: balanced through the difference lines, every column at the run's one set

    /** Three USD assets of 1.00 each: 0.92 apiece and 2.77 translated together, so a difference line of 0.01. One USD liability of 3.00: 2.77, no difference. */
    private void awkwardUsd() {
        post(JUNE.atDay(2), "USD", 2, 100, CASH, WALLET);
        post(JUNE.atDay(3), "USD", 2, 100, LOAN, WALLET);
        post(JUNE.atDay(4), "USD", 2, 100, "CHARGEBACK_RECOVERABLE/OPERATIONAL", WALLET);
    }

    private CanonicalJson.Obj currencyOf(CanonicalJson.Obj translation, String currency) {
        return objects(translation, "currencies").stream().filter(row -> text(row, "currency").equals(currency)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the balance sheet balances translated: per currency and across them the two columns' totals are equal, each through its own difference line, the difference shown and never absorbed")
    void theBalanceSheetBalancesTranslated() {
        awkwardUsd();
        post(JUNE.atDay(5), "EUR", 2, 7_777, CASH, WALLET);
        post(JUNE.atDay(6), "JPY", 0, 1_234_567, CASH, WALLET);
        CanonicalJson.Obj body = balanceSheet().generate(SNAPSHOT, translatedRun());
        CanonicalJson.Obj translation = object(body, "translation");

        assertThat(flag(translation, "complete")).isTrue();
        assertThat(flag(translation, "balanced")).as("computed, not assumed").isTrue();
        assertThat(CanonicalJson.text(object(translation, "total", "assets"))).isNotEmpty();
        assertThat(minor(object(translation, "total", "assets"), "translatedTotal")).isEqualTo(minor(object(translation, "total", "liabilitiesEquityAndResult"), "translatedTotal"));
        for (String currency : List.of("USD", "EUR", "JPY")) {
            CanonicalJson.Obj columns = object(currencyOf(translation, currency), "columns");
            assertThat(minor(object(columns, "assets"), "translatedTotal")).as(currency).isEqualTo(minor(object(columns, "liabilitiesEquityAndResult"), "translatedTotal"));
            assertThat(minor(object(columns, "assets"), "original")).as(currency + " the originals agree exactly").isEqualTo(minor(object(columns, "liabilitiesEquityAndResult"), "original"));
        }
        CanonicalJson.Obj usd = object(currencyOf(translation, "USD"), "columns");
        assertThat(minor(object(usd, "assets"), "translatedParts")).as("0.92 three times").isEqualTo(big(276));
        assertThat(minor(object(usd, "assets"), "translationDifference")).as("the explicit line").isEqualTo(big(1));
        assertThat(minor(object(usd, "assets"), "translatedTotal")).isEqualTo(big(277));
        assertThat(minor(object(usd, "liabilitiesEquityAndResult"), "translationDifference")).isZero();
        assertThat(text(object(translation, "rateSet"), "id")).as("the run's one set").isEqualTo(rateSet.toString());
        assertThat(minor(object(rowOf(body, "1000", "USD"), "translated"), "closing")).as("the row's translated figure beside its original").isEqualTo(big(92));
        assertThat(minor(rowOf(body, "1000", "USD"), "closing")).isEqualTo(big(100));
    }

    @Test
    @DisplayName("the originals stand: a translated body's every original member reads as the plain body's, and a plain run has no translated member at all")
    void aPlainRunHasNoTranslation() {
        awkwardUsd();
        CanonicalJson.Obj plain = balanceSheet().generate(SNAPSHOT, finalRun());
        CanonicalJson.Obj translated = balanceSheet().generate(SNAPSHOT, translatedRun());
        assertThat(plain.members()).doesNotContainKey("translation");
        assertThat(CanonicalJson.text(plain)).doesNotContain("\"translated\"");
        assertThat(minor(equationOf(translated, "USD"), "assets")).isEqualTo(minor(equationOf(plain, "USD"), "assets"));
        assertThat(minor(equationOf(translated, "USD"), "difference")).isZero();
        assertThat(balanceSheet().takesValuationRateSet()).isTrue();
        assertThat(incomeStatement().takesValuationRateSet()).isTrue();
    }

    @Test
    @DisplayName("the income statement translated: revenue, expenses, result and net realised FX each on its own, and the result per currency is the balance sheet's translated result, both one function of one original")
    void theIncomeStatementTranslates() {
        post(JUNE.atDay(2), "USD", 2, 333, WALLET, FEES);
        post(JUNE.atDay(3), "USD", 2, 111, COSTS, CASH);
        post(JUNE.atDay(4), "USD", 2, 50, CASH, FX_GAINS);
        CanonicalJson.Obj income = incomeStatement().generate(SNAPSHOT, translatedRun());
        CanonicalJson.Obj sheet = balanceSheet().generate(SNAPSHOT, translatedRun());

        CanonicalJson.Obj usd = object(currencyOf(object(income, "translation"), "USD"), "columns");
        assertThat(usd.members()).containsKeys("revenue", "expenses", "result", "netRealisedFx");
        assertThat(minor(object(usd, "result"), "original")).isEqualTo(big(272));
        assertThat(minor(object(usd, "result"), "translatedTotal")).isEqualTo(minor(object(equationOf(sheet, "USD"), "translated"), "currentPeriodResult"));
        assertThat(minor(object(rowOf(income, "4000", "USD"), "translated"), "net")).isEqualTo(big(307));
        assertThat(flag(object(income, "translation"), "complete")).isTrue();
    }

    @Test
    @DisplayName("a real hole refuses the run: a currency the set does not value with a figure not zero, naming it, on either statement, and no partial total was ever read")
    void aRealHoleRefuses() {
        awkwardUsd();
        post(JUNE.atDay(2), "GBP", 2, 500, CASH, WALLET);
        for (ReportDefinition definition : List.of(balanceSheet(), incomeStatement())) {
            if (definition instanceof IncomeStatementDefinition) {
                post(JUNE.atDay(3), "GBP", 2, 10, WALLET, FEES);
            }
            assertThatThrownBy(() -> definition.generate(SNAPSHOT, translatedRun())).as(definition.code()).isInstanceOfSatisfying(ReportRefused.class, refused -> {
                assertThat(refused.reason()).isEqualTo(ReportRefused.Reason.TRANSLATION_INCOMPLETE);
                assertThat(refused.subjects()).containsExactly("GBP");
            });
        }
    }

    @Test
    @DisplayName("a retired currency at zero is no hole: ZERO_NO_RATE, translated complete, and a currency the income statement moved nothing of needs no rate there either")
    void aZeroNeedsNoRate() {
        awkwardUsd();
        post(MAY.atDay(2), "CHF", 2, 900, CASH, WALLET);
        post(JUNE.atDay(2), "CHF", 2, 900, WALLET, CASH);
        CanonicalJson.Obj translation = object(balanceSheet().generate(SNAPSHOT, translatedRun()), "translation");
        assertThat(flag(translation, "complete")).isTrue();
        assertThat(text(currencyOf(translation, "CHF"), "basis")).isEqualTo("ZERO_NO_RATE");
        assertThat(flag(object(incomeStatement().generate(SNAPSHOT, translatedRun()), "translation"), "complete")).isTrue();
    }

    @Test
    @DisplayName("regenerated byte-identical, and an order of reads decides no byte, translated too")
    void aTranslatedRunRegeneratesIdentical() {
        awkwardUsd();
        assertThat(CanonicalJson.text(balanceSheet().generate(SNAPSHOT, translatedRun()))).isEqualTo(CanonicalJson.text(balanceSheet().generate(SNAPSHOT, translatedRun())));
        assertThat(CanonicalJson.text(incomeStatement().generate(SNAPSHOT, translatedRun()))).isEqualTo(CanonicalJson.text(incomeStatement().generate(SNAPSHOT, translatedRun())));
    }

    @Test
    @DisplayName("the two definitions: each a ReportKind's code, version 1, FINAL only once closed, not operational, and each body states its five facts")
    void theDefinitions() {
        assertThat(balanceSheet().code()).isEqualTo(ReportKind.BALANCE_SHEET.name());
        assertThat(incomeStatement().code()).isEqualTo(ReportKind.INCOME_STATEMENT.name());
        for (ReportDefinition definition : List.of(balanceSheet(), incomeStatement())) {
            assertThat(definition.version()).isEqualTo(1);
            assertThat(definition.operational()).isFalse();
            assertThat(definition.issuedOnlyWhenClosed()).isTrue();
            assertThat(definition.needsValuationRateSet()).isFalse();
            assertThat(CanonicalJson.text(definition.generate(SNAPSHOT, finalRun()))).contains(CanonicalJson.text(definition.statement().asValue()));
            assertThat(definition.statement().rule()).contains("refuse the run");
            assertThat(definition.statement().freshness()).contains("FINAL only for a CLOSED period");
        }
    }

    // -----------------------------------------------------------------

    /** The journal's GL reads with {@code change} applied to what they serve; the ledger's own totals untouched. */
    private TrialBalanceReads transforming(Function<List<JournalReads.Aggregate>, List<JournalReads.Aggregate>> change) {
        return new TrialBalanceReads() {
            @Override
            public Optional<Period> period(Connection snapshot, AccountingPeriodId id) {
                return books.period(snapshot, id);
            }

            @Override
            public GlMappingVersion version(Connection snapshot, GlMappingVersionId id) {
                return books.version(snapshot, id);
            }

            @Override
            public List<JournalReads.Aggregate> before(Connection snapshot, LocalDate before, Optional<Instant> cut) {
                return change.apply(books.before(snapshot, before, cut));
            }

            @Override
            public List<JournalReads.Aggregate> within(Connection snapshot, JournalReads.Range range, Optional<Instant> cut) {
                return change.apply(books.within(snapshot, range, cut));
            }

            @Override
            public List<LedgerWindows> ledger(Connection snapshot, Period period, Optional<Instant> cut) {
                return books.ledger(snapshot, period, cut);
            }
        };
    }
}
