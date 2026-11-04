package com.finapp.accounting.reporting;

import static com.finapp.accounting.testing.ReportBodies.isNull;
import static com.finapp.accounting.testing.ReportBodies.minor;
import static com.finapp.accounting.testing.ReportBodies.object;
import static com.finapp.accounting.testing.ReportBodies.objects;
import static com.finapp.accounting.testing.ReportBodies.row;
import static com.finapp.accounting.testing.ReportBodies.scale;
import static com.finapp.accounting.testing.ReportBodies.section;
import static com.finapp.accounting.testing.ReportBodies.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.CounterpartyPositionReads;
import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.ReportKind;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.Side;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.testing.InMemoryTrialBalanceReads;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The six reports that are the journal's figures by purpose (`P14-TSK-017`; {@code INV-ACC-02}, {@code INV-ACC-04},
 * {@code INV-BAL-05}, {@code INV-MON-01}): each figure equal to an independent fold over the same journal lines, per purpose and
 * currency; nothing across currencies; sums past {@code long}; the data's scale, never a JDK's; the period's pin and the PROVISIONAL
 * cut; and byte-identical whatever order the reads serve. The SQL's own behaviour against a real journal is {@code
 * OperationalReportsDatabaseTest}'s (owed, C5).
 */
@DisplayName("the reports by purpose (P14-TSK-017)")
class PurposeReportDefinitionTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Connection SNAPSHOT = (Connection) Proxy.newProxyInstance(
            PurposeReportDefinitionTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("a report reads only through its port");
            });
    private static final YearMonth MONTH = YearMonth.of(2097, 6);
    private static final Instant CUT = Instant.parse("2097-06-20T10:00:00Z");
    private static final Instant EARLY = Instant.parse("2097-06-01T00:00:00Z");
    private static final GlMappingVersion VERSION = ReferenceGlMapping.version(YearMonth.of(2097, 1));
    private static final String CASH = "CASH_AT_BANK/OPERATIONAL";
    private static final String LOAN_PRINCIPAL = "LOAN_PRINCIPAL/LOAN";
    private static final String FEES = "FEE_REVENUE/OPERATIONAL";
    private static final String FX_CLEARING = "FX_PROVIDER_CLEARING/COUNTERPARTY";

    private static final Map<String, Function<InMemoryTrialBalanceReads, PurposeReportDefinition>> SIX = Map.of(
            "REVENUE", PurposeReportDefinition::revenue,
            "RECEIVABLES_PAYABLES", PurposeReportDefinition::receivablesPayables,
            "SETTLEMENT_POSITIONS", reads -> PurposeReportDefinition.settlementPositions(reads, reads.counterparties()),
            "FX_RESULTS", PurposeReportDefinition::fxResults,
            "LOAN_PORTFOLIO", PurposeReportDefinition::loanPortfolio,
            "BNPL_EXPOSURE", PurposeReportDefinition::bnplExposure);

    private final AccountingPeriodId periodId = AccountingPeriodId.next(IDS);
    private final GlMappingVersionId pinned = GlMappingVersionId.next(IDS);

    @Test
    @DisplayName("the six: each its ReportKind's name, version 1, no parameter, no rate set, FINAL by default - its statement written")
    void theSix() {
        InMemoryTrialBalanceReads reads = books();
        SIX.forEach((code, factory) -> {
            PurposeReportDefinition definition = factory.apply(reads);
            assertThat(definition.code()).isEqualTo(ReportKind.valueOf(code).name());
            assertThat(definition.version()).isEqualTo(1);
            assertThat(definition.parameterNames()).isEmpty();
            assertThat(definition.takesValuationRateSet()).as("%s translates nothing the owner has not settled", code).isFalse();
            assertThat(definition.operational()).as("%s is FINAL for a locked period", code).isFalse();
            assertThat(CanonicalJson.text(definition.generate(SNAPSHOT, inputs(RunBasis.FINAL))))
                    .contains(CanonicalJson.text(definition.statement().asValue()));
        });
    }

    @Test
    @DisplayName("every figure equals the ledger by purpose: for each report, each section's every key, each currency - the opening,"
            + " the debits, the credits and the closing equal an independent fold over the same journal lines")
    void everyFigureEqualsTheLedgerByPurpose() {
        SIX.forEach((code, factory) -> {
            InMemoryTrialBalanceReads reads = books();
            for (PurposeReportDefinition.Section section : factory.apply(reads).sections()) {
                for (String key : section.keys()) {
                    // A line each way, before and inside the month and after it, in two currencies at their own scales.
                    reads.post(MONTH.atDay(1).minusDays(1), EARLY, "EUR", 2, 1_000, key, CASH);
                    reads.post(MONTH.atDay(1), EARLY, "EUR", 2, 200, key, CASH);
                    reads.post(MONTH.atEndOfMonth(), EARLY, "EUR", 2, 30, CASH, key);
                    reads.post(MONTH.atEndOfMonth().plusDays(1), EARLY, "EUR", 2, 7, key, CASH);
                    reads.post(MONTH.atDay(9), EARLY, "BHD", 3, 12_345, key, CASH);
                }
            }
            PurposeReportDefinition definition = factory.apply(reads);
            CanonicalJson.Obj body = definition.generate(SNAPSHOT, inputs(RunBasis.FINAL));
            for (PurposeReportDefinition.Section section : definition.sections()) {
                for (String key : section.keys()) {
                    String purpose = key.substring(0, key.indexOf('/'));
                    for (String currency : List.of("EUR", "BHD")) {
                        Figures oracle = oracle(reads, key, currency);
                        CanonicalJson.Obj written = row(body, purpose, currency).orElseThrow();
                        assertThat(text(written, "ownerKind")).isEqualTo(key.substring(key.indexOf('/') + 1));
                        assertThat(minor(written, "debits")).as("%s %s %s debits", code, key, currency)
                                .isEqualTo(oracle.debits);
                        assertThat(minor(written, "credits")).as("%s %s %s credits", code, key, currency)
                                .isEqualTo(oracle.credits);
                        if (definition.window() == PurposeReportDefinition.Window.BALANCES) {
                            assertThat(minor(written, "opening")).as("%s %s %s opening", code, key, currency)
                                    .isEqualTo(oracle.opening);
                            assertThat(minor(written, "closing")).as("%s %s %s closing", code, key, currency)
                                    .isEqualTo(oracle.opening.add(oracle.debits).subtract(oracle.credits));
                        } else {
                            assertThat(written.members()).doesNotContainKey("opening").doesNotContainKey("closing");
                            assertThat(minor(written, "net")).isEqualTo(oracle.debits.subtract(oracle.credits));
                        }
                    }
                }
            }
        });
    }

    @Test
    @DisplayName("a purpose outside the report is no row of it, and none of its lines is in a figure")
    void aPurposeOutsideIsNoRow() {
        InMemoryTrialBalanceReads reads = books();
        reads.post(MONTH.atDay(3), EARLY, "EUR", 2, 500, LOAN_PRINCIPAL, CASH);
        CanonicalJson.Obj portfolio = PurposeReportDefinition.loanPortfolio(reads).generate(SNAPSHOT, inputs(RunBasis.FINAL));
        assertThat(row(portfolio, "CASH_AT_BANK", "EUR")).isEmpty();
        assertThat(row(portfolio, "LOAN_PRINCIPAL", "EUR")).isPresent();
        CanonicalJson.Obj revenue = PurposeReportDefinition.revenue(reads).generate(SNAPSHOT, inputs(RunBasis.FINAL));
        assertThat(objects(section(revenue, "FEE"), "rows")).isEmpty();
        assertThat(objects(section(revenue, "FEE"), "totals")).isEmpty();
    }

    @Test
    @DisplayName("never across currencies: a section's totals are per currency, each at its own scale, and no amount crosses")
    void neverAcrossCurrencies() {
        InMemoryTrialBalanceReads reads = books();
        reads.post(MONTH.atDay(3), EARLY, "EUR", 2, 100, LOAN_PRINCIPAL, CASH);
        reads.post(MONTH.atDay(4), EARLY, "EUR", 2, 50, "LOAN_PRINCIPAL_DUE/LOAN", CASH);
        reads.post(MONTH.atDay(3), EARLY, "BHD", 3, 7_000, LOAN_PRINCIPAL, CASH);
        CanonicalJson.Obj body = PurposeReportDefinition.loanPortfolio(reads).generate(SNAPSHOT, inputs(RunBasis.FINAL));
        List<CanonicalJson.Obj> totals = objects(section(body, "PRINCIPAL"), "totals");
        assertThat(totals).extracting(total -> text(total, "currency")).containsExactly("BHD", "EUR");
        assertThat(minor(totals.get(0), "closing")).isEqualTo(BigInteger.valueOf(7_000));
        assertThat(scale(totals.get(0), "closing")).isEqualTo(3);
        assertThat(minor(totals.get(1), "closing")).isEqualTo(BigInteger.valueOf(150));
        assertThat(scale(totals.get(1), "closing")).isEqualTo(2);
    }

    @Test
    @DisplayName("I-1: nothing nets between counterparties (INV-RAIL-04, ledger V025): fx-sim-a at +1,000 and fx-sim-b at -1,000 stay two rows,"
            + " never a position of 0, each naming its counterparty and the one account; an account no counterparty owns names none")
    void theCounterpartiesStayApart() {
        InMemoryTrialBalanceReads reads = books();
        reads.postFor("fx-sim-a", MONTH.atDay(1).minusDays(1), EARLY, "EUR", 2, 1_000, FX_CLEARING, CASH);
        reads.postFor("fx-sim-b", MONTH.atDay(3), EARLY, "EUR", 2, 1_000, CASH, FX_CLEARING);
        CanonicalJson.Obj body = PurposeReportDefinition.settlementPositions(reads, reads.counterparties())
                .generate(SNAPSHOT, inputs(RunBasis.FINAL));

        List<CanonicalJson.Obj> positions = objects(section(body, "CLEARING"), "rows");
        assertThat(positions).as("two rows, not a summed one").hasSize(2)
                .extracting(position -> text(position, "counterparty")).containsExactly("fx-sim-a", "fx-sim-b");
        CanonicalJson.Obj first = row(body, "FX_PROVIDER_CLEARING", "fx-sim-a", "EUR").orElseThrow();
        assertThat(minor(first, "opening")).isEqualTo(BigInteger.valueOf(1_000));
        assertThat(minor(first, "closing")).isEqualTo(BigInteger.valueOf(1_000));
        CanonicalJson.Obj second = row(body, "FX_PROVIDER_CLEARING", "fx-sim-b", "EUR").orElseThrow();
        assertThat(minor(second, "credits")).isEqualTo(BigInteger.valueOf(1_000));
        assertThat(minor(second, "closing")).isEqualTo(BigInteger.valueOf(-1_000));
        assertThat(text(first, "glAccount")).isEqualTo(text(second, "glAccount")).isEqualTo("1120");
        assertThat(isNull(row(body, "CASH_AT_BANK", "EUR").orElseThrow(), "counterparty")).isTrue();
    }

    @Test
    @DisplayName("I-1: the counterparty's opening honours the same cut as the rest of a PROVISIONAL run")
    void theCounterpartiesHonourTheCut() {
        InMemoryTrialBalanceReads reads = books();
        reads.postFor("fx-sim-a", MONTH.atDay(1).minusDays(1), CUT, "EUR", 2, 100, FX_CLEARING, CASH);
        reads.postFor("fx-sim-a", MONTH.atDay(1).minusDays(1), CUT.plusSeconds(1), "EUR", 2, 40, FX_CLEARING, CASH);
        reads.postFor("fx-sim-a", MONTH.atDay(3), CUT.plusSeconds(1), "EUR", 2, 9, FX_CLEARING, CASH);
        CanonicalJson.Obj body = PurposeReportDefinition.settlementPositions(reads, reads.counterparties())
                .generate(SNAPSHOT, inputs(RunBasis.PROVISIONAL));
        CanonicalJson.Obj position = row(body, "FX_PROVIDER_CLEARING", "fx-sim-a", "EUR").orElseThrow();
        assertThat(minor(position, "opening")).isEqualTo(BigInteger.valueOf(100));
        assertThat(minor(position, "debits")).isEqualTo(BigInteger.ZERO);
    }

    @Test
    @DisplayName("I-1, fail closed: a counterparty's purpose with no counterparty read is refused at construction; a position naming nobody, or two"
            + " reads of one snapshot that disagree, fail the report rather than fold")
    void theCounterpartyDimensionFailsClosed() {
        ReportStatement statement = PurposeReportDefinition.statement("a rule stated at length", PurposeReportDefinition.Window.BALANCES);
        assertThatThrownBy(() -> new PurposeReportDefinition("TEST_REPORT", PurposeReportDefinition.Window.BALANCES, List.of(
                PurposeReportDefinition.Section.ofBothSides("A", List.of(FX_CLEARING))), statement, books()))
                .isInstanceOf(IllegalArgumentException.class);

        InMemoryTrialBalanceReads reads = books();
        reads.postFor("fx-sim-a", MONTH.atDay(3), EARLY, "EUR", 2, 100, FX_CLEARING, CASH);
        JournalReads.Aggregate unnamed = new JournalReads.Aggregate("FX_PROVIDER_CLEARING", "COUNTERPARTY", Optional.empty(), "EUR", 2,
                Side.DEBIT, BigInteger.valueOf(100), 1);
        assertThatThrownBy(() -> PurposeReportDefinition.settlementPositions(reads, answering(List.of(unnamed)))
                .generate(SNAPSHOT, inputs(RunBasis.FINAL))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PurposeReportDefinition.settlementPositions(reads, answering(List.of()))
                .generate(SNAPSHOT, inputs(RunBasis.FINAL))).isInstanceOf(IllegalStateException.class);
        JournalReads.Aggregate short_ = new JournalReads.Aggregate("FX_PROVIDER_CLEARING", "COUNTERPARTY", Optional.of("fx-sim-a"), "EUR", 2,
                Side.DEBIT, BigInteger.valueOf(99), 1);
        assertThatThrownBy(() -> PurposeReportDefinition.settlementPositions(reads, answering(List.of(short_)))
                .generate(SNAPSHOT, inputs(RunBasis.FINAL))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("I-1: a total adds one normal side - held against the reference chart for every section of the six, both ways: a section of both"
            + " sides writes none, and a section of one side keeps its total")
    void aTotalAddsOneNormalSide() {
        InMemoryTrialBalanceReads reads = books();
        SIX.forEach((code, factory) -> factory.apply(reads).sections().forEach(section -> {
            Set<Boolean> sides = section.keys().stream()
                    .map(key -> VERSION.account(VERSION.rulesByKey().get(key).glAccountCode()).orElseThrow().classification().debitNormal())
                    .collect(Collectors.toSet());
            assertThat(section.totalled()).as("%s %s: %s", code, section.name(), sides).isEqualTo(sides.size() == 1);
        }));
        reads.post(MONTH.atDay(3), EARLY, "EUR", 2, 100, "SETTLEMENT_CLEARING/OPERATIONAL", "PAYOUT_CLEARING/OPERATIONAL");
        reads.post(MONTH.atDay(3), EARLY, "EUR", 2, 7, "FX_REALISED_LOSSES/OPERATIONAL", "FX_REALISED_GAINS/OPERATIONAL");
        CanonicalJson.Obj positions = PurposeReportDefinition.settlementPositions(reads, reads.counterparties())
                .generate(SNAPSHOT, inputs(RunBasis.FINAL));
        assertThat(isNull(section(positions, "CLEARING"), "totals")).as("assets and liabilities").isTrue();
        CanonicalJson.Obj results = PurposeReportDefinition.fxResults(reads).generate(SNAPSHOT, inputs(RunBasis.FINAL));
        assertThat(isNull(section(results, "REALISED"), "totals")).as("a revenue and an expense").isTrue();
        assertThat(objects(section(results, "REALISED"), "rows")).hasSize(2);
        reads.post(MONTH.atDay(3), EARLY, "EUR", 2, 5, "LOAN_PRINCIPAL/LOAN", CASH);
        assertThat(objects(section(PurposeReportDefinition.receivablesPayables(reads).generate(SNAPSHOT, inputs(RunBasis.FINAL)),
                "RECEIVABLES"), "totals")).hasSize(1);
    }

    @Test
    @DisplayName("past long: a position beyond Long.MAX_VALUE in minor units is written exactly")
    void pastLong() {
        InMemoryTrialBalanceReads reads = books();
        reads.post(MONTH.atDay(1).minusDays(2), EARLY, "EUR", 2, Long.MAX_VALUE, LOAN_PRINCIPAL, CASH);
        reads.post(MONTH.atDay(1).minusDays(1), EARLY, "EUR", 2, Long.MAX_VALUE, LOAN_PRINCIPAL, CASH);
        reads.post(MONTH.atDay(1), EARLY, "EUR", 2, 5, LOAN_PRINCIPAL, CASH);
        CanonicalJson.Obj body = PurposeReportDefinition.loanPortfolio(reads).generate(SNAPSHOT, inputs(RunBasis.FINAL));
        BigInteger twice = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO);
        assertThat(minor(row(body, "LOAN_PRINCIPAL", "EUR").orElseThrow(), "opening")).isEqualTo(twice);
        assertThat(minor(row(body, "LOAN_PRINCIPAL", "EUR").orElseThrow(), "closing")).isEqualTo(twice.add(BigInteger.valueOf(5)));
    }

    @Test
    @DisplayName("the data's scale, never the JDK's (P14-TSK-015's review, M-6): JPY held at 2 is written at 2, one scale per currency,"
            + " a currency held at two scales at the larger, exactly")
    void theDatasScale() {
        InMemoryTrialBalanceReads reads = books();
        reads.post(MONTH.atDay(3), EARLY, "JPY", 2, 1_50, LOAN_PRINCIPAL, CASH);
        reads.post(MONTH.atDay(3), EARLY, "EUR", 2, 1_50, LOAN_PRINCIPAL, CASH);
        reads.post(MONTH.atDay(4), EARLY, "EUR", 3, 1_005, "LOAN_FEES_DUE/LOAN", CASH);
        CanonicalJson.Obj body = PurposeReportDefinition.loanPortfolio(reads).generate(SNAPSHOT, inputs(RunBasis.FINAL));
        assertThat(scale(row(body, "LOAN_PRINCIPAL", "JPY").orElseThrow(), "closing")).isEqualTo(2);
        assertThat(minor(row(body, "LOAN_PRINCIPAL", "EUR").orElseThrow(), "closing")).as("150 at scale 2 reads 1500 at 3")
                .isEqualTo(BigInteger.valueOf(1_500));
        assertThat(scale(row(body, "LOAN_FEES_DUE", "EUR").orElseThrow(), "closing")).isEqualTo(3);
    }

    @Test
    @DisplayName("the pin: a FINAL run under another version is refused before anything is read; each row names the GL account the pinned"
            + " version maps it to; no version, no account - never a guess")
    void thePin() {
        InMemoryTrialBalanceReads reads = books();
        reads.post(MONTH.atDay(3), EARLY, "EUR", 2, 100, LOAN_PRINCIPAL, CASH);
        PurposeReportDefinition definition = PurposeReportDefinition.loanPortfolio(reads);
        assertThat(text(row(definition.generate(SNAPSHOT, inputs(RunBasis.FINAL)), "LOAN_PRINCIPAL", "EUR").orElseThrow(),
                "glAccount")).isEqualTo("1300");
        GlMappingVersionId other = GlMappingVersionId.next(IDS);
        reads.version(other, VERSION);
        assertThatThrownBy(() -> definition.generate(SNAPSHOT, new ReportInputs(periodId, MONTH, 1, RunBasis.FINAL,
                Optional.of(CUT), Optional.of(other), Optional.of(2), CUT, RunParameters.none())))
                .isInstanceOf(TrialBalanceDefinition.PinMismatch.class);

        InMemoryTrialBalanceReads unpinned = new InMemoryTrialBalanceReads().period(new TrialBalanceReads.Period(periodId,
                MONTH.atDay(1), MONTH.atEndOfMonth(), PeriodStatus.LOCKED, Optional.empty()));
        unpinned.post(MONTH.atDay(3), EARLY, "EUR", 2, 100, LOAN_PRINCIPAL, CASH);
        CanonicalJson.Obj body = PurposeReportDefinition.loanPortfolio(unpinned).generate(SNAPSHOT, new ReportInputs(periodId,
                MONTH, 1, RunBasis.FINAL, Optional.of(CUT), Optional.empty(), Optional.empty(), CUT, RunParameters.none()));
        assertThat(isNull(row(body, "LOAN_PRINCIPAL", "EUR").orElseThrow(), "glAccount")).isTrue();
    }

    @Test
    @DisplayName("the cut: a PROVISIONAL run counts only entries created at or before its claim, opening and movement alike; a FINAL run"
            + " counts every entry")
    void theCut() {
        InMemoryTrialBalanceReads reads = books();
        reads.post(MONTH.atDay(3), CUT, "EUR", 2, 100, LOAN_PRINCIPAL, CASH);
        reads.post(MONTH.atDay(3), CUT.plusSeconds(1), "EUR", 2, 40, LOAN_PRINCIPAL, CASH);
        reads.post(MONTH.atDay(1).minusDays(1), CUT.plusSeconds(1), "EUR", 2, 9, LOAN_PRINCIPAL, CASH);
        PurposeReportDefinition definition = PurposeReportDefinition.loanPortfolio(reads);
        CanonicalJson.Obj provisional = row(definition.generate(SNAPSHOT, inputs(RunBasis.PROVISIONAL)), "LOAN_PRINCIPAL", "EUR")
                .orElseThrow();
        assertThat(minor(provisional, "debits")).isEqualTo(BigInteger.valueOf(100));
        assertThat(minor(provisional, "opening")).isEqualTo(BigInteger.ZERO);
        CanonicalJson.Obj whole = row(definition.generate(SNAPSHOT, inputs(RunBasis.FINAL)), "LOAN_PRINCIPAL", "EUR").orElseThrow();
        assertThat(minor(whole, "debits")).isEqualTo(BigInteger.valueOf(140));
        assertThat(minor(whole, "opening")).isEqualTo(BigInteger.valueOf(9));
    }

    @Test
    @DisplayName("INV-ACC-04: byte-identical whatever order the journal serves, for every report - no iteration order the code does not fix")
    void regeneratesByteIdentical() {
        SIX.forEach((code, factory) -> {
            InMemoryTrialBalanceReads forward = books();
            InMemoryTrialBalanceReads backward = books();
            List<String> keys = factory.apply(forward).sections().stream().flatMap(section -> section.keys().stream()).toList();
            for (int i = 0; i < keys.size(); i++) {
                forward.post(MONTH.atDay(1 + i), EARLY, "EUR", 2, 100L + i, keys.get(i), CASH);
                forward.post(MONTH.atDay(1 + i), EARLY, "USD", 2, 300L + i, CASH, keys.get(i));
            }
            for (int i = keys.size() - 1; i >= 0; i--) {
                backward.post(MONTH.atDay(1 + i), EARLY, "USD", 2, 300L + i, CASH, keys.get(i));
                backward.post(MONTH.atDay(1 + i), EARLY, "EUR", 2, 100L + i, keys.get(i), CASH);
            }
            assertThat(CanonicalJson.text(factory.apply(forward).generate(SNAPSHOT, inputs(RunBasis.FINAL))))
                    .as(code)
                    .isEqualTo(CanonicalJson.text(factory.apply(backward).generate(SNAPSHOT, inputs(RunBasis.FINAL))));
        });
    }

    @Test
    @DisplayName("INV-ACC-04: a FINAL body is a function of what the lock makes immutable - lines dated after the period, whenever created,"
            + " move no byte of any report")
    void aFinalBodyIgnoresWhatComesAfterItsPeriod() {
        SIX.forEach((code, factory) -> {
            InMemoryTrialBalanceReads reads = books();
            List<String> keys = factory.apply(reads).sections().stream().flatMap(section -> section.keys().stream()).toList();
            keys.forEach(key -> reads.post(MONTH.atDay(5), EARLY, "EUR", 2, 100, key, CASH));
            PurposeReportDefinition definition = factory.apply(reads);
            String issued = CanonicalJson.text(definition.generate(SNAPSHOT, inputs(RunBasis.FINAL)));
            keys.forEach(key -> reads.post(MONTH.atEndOfMonth().plusDays(1), CUT.plusSeconds(3_600), "EUR", 2, 999, key, CASH));
            keys.forEach(key -> reads.post(MONTH.plusMonths(1).atDay(15), CUT.plusSeconds(7_200), "USD", 2, 999, CASH, key));
            assertThat(CanonicalJson.text(definition.generate(SNAPSHOT, inputs(RunBasis.FINAL)))).as(code).isEqualTo(issued);
        });
    }

    @Test
    @DisplayName("P14-TSK-021: a write-off in a later month moves no byte of LOAN_PORTFOLIO's or BNPL_EXPOSURE's FINAL for an earlier one - neither"
            + " reads a status; in its own month the account does not vanish: the book's rows hold its credits, and the expense is not the book's")
    void aWriteOffMovesNoEarlierFinalAndVanishesFromNoReport() {
        Map<String, String[]> books = Map.of(
                "LOAN_PORTFOLIO", new String[] {"LOAN_PRINCIPAL/LOAN", "LOAN_FEES_DUE/LOAN", "LOAN_WRITE_OFF_EXPENSE/OPERATIONAL"},
                "BNPL_EXPOSURE", new String[] {"BNPL_PRINCIPAL/BNPL_OBLIGATION", "BNPL_FEES_DUE/BNPL_OBLIGATION",
                        "BNPL_WRITE_OFF_EXPENSE/OPERATIONAL"});
        books.forEach((code, keys) -> {
            InMemoryTrialBalanceReads reads = books();
            reads.post(MONTH.atDay(5), EARLY, "EUR", 2, 500_00, keys[0], CASH);
            reads.post(MONTH.atDay(5), EARLY, "EUR", 2, 20_00, keys[1], FEES);
            PurposeReportDefinition definition = SIX.get(code).apply(reads);
            String issued = CanonicalJson.text(definition.generate(SNAPSHOT, inputs(RunBasis.FINAL)));

            // The entry a month on: DR the expense, CR each asset to zero.
            reads.post(MONTH.plusMonths(1).atDay(3), CUT.plusSeconds(3_600), "EUR", 2, 500_00, keys[2], keys[0]);
            reads.post(MONTH.plusMonths(1).atDay(3), CUT.plusSeconds(3_600), "EUR", 2, 20_00, keys[2], keys[1]);
            assertThat(CanonicalJson.text(definition.generate(SNAPSHOT, inputs(RunBasis.FINAL)))).as(code).isEqualTo(issued);

            // The same entry inside the month: the rows stand, the credits named, and the expense is no row of the book's report.
            InMemoryTrialBalanceReads within = books();
            within.post(MONTH.atDay(5), EARLY, "EUR", 2, 500_00, keys[0], CASH);
            within.post(MONTH.atDay(5), EARLY, "EUR", 2, 20_00, keys[1], FEES);
            within.post(MONTH.atDay(9), EARLY, "EUR", 2, 500_00, keys[2], keys[0]);
            within.post(MONTH.atDay(9), EARLY, "EUR", 2, 20_00, keys[2], keys[1]);
            String body = CanonicalJson.text(SIX.get(code).apply(within).generate(SNAPSHOT, inputs(RunBasis.FINAL)));
            assertThat(body).as(code).contains(keys[0].substring(0, keys[0].indexOf('/'))).contains("50000")
                    .doesNotContain("WRITE_OFF");
            assertThat(SIX.get(code).apply(within).sections().stream().flatMap(section -> section.keys().stream()))
                    .as("the expense is in no section of the book's report").doesNotContain(keys[2]);
        });
    }

    @Test
    @DisplayName("a movement report never reads from inception - revenue and FX results are bounded by the month; a balance report is what"
            + " pays for the opening")
    void aMovementReportReadsNoOpening() {
        CountingReads counting = new CountingReads(books());
        PurposeReportDefinition.revenue(counting).generate(SNAPSHOT, inputs(RunBasis.FINAL));
        PurposeReportDefinition.fxResults(counting).generate(SNAPSHOT, inputs(RunBasis.FINAL));
        assertThat(counting.befores).isZero();
        PurposeReportDefinition.loanPortfolio(counting).generate(SNAPSHOT, inputs(RunBasis.FINAL));
        assertThat(counting.befores).isEqualTo(1);
    }

    @Test
    @DisplayName("the shape of the sections: a purpose in two sections of one report is refused - it would be counted twice")
    void aPurposeIsInOneSection() {
        ReportStatement statement = PurposeReportDefinition.statement("a rule stated at length", PurposeReportDefinition.Window.MOVEMENT);
        assertThatThrownBy(() -> new PurposeReportDefinition("TEST_REPORT", PurposeReportDefinition.Window.MOVEMENT, List.of(
                PurposeReportDefinition.Section.ofOneSide("A", List.of(FEES)),
                PurposeReportDefinition.Section.ofOneSide("B", List.of(FEES))), statement, books()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("what a body holds: the sign, the window, the two ranges - and no loan, customer, merchant, line or name; aggregates only")
    void whatABodyHolds() {
        InMemoryTrialBalanceReads reads = books();
        reads.post(MONTH.atDay(3), EARLY, "EUR", 2, 100, LOAN_PRINCIPAL, CASH);
        CanonicalJson.Obj body = PurposeReportDefinition.loanPortfolio(reads).generate(SNAPSHOT, inputs(RunBasis.FINAL));
        assertThat(text(body, "sign")).isEqualTo(TrialBalance.SIGN);
        assertThat(text(body, "window")).isEqualTo("BALANCES");
        assertThat(text(object(body, "range"), "from")).isEqualTo("2097-06-01");
        assertThat(text(object(body, "periodBounds"), "through")).isEqualTo("2097-06-30");
        assertThat(row(body, "LOAN_PRINCIPAL", "EUR").orElseThrow().members().keySet()).containsExactlyInAnyOrder("purpose",
                "ownerKind", "counterparty", "currency", "glAccount", "opening", "debits", "credits", "closing", "lines");
    }

    // -----------------------------------------------------------------

    private InMemoryTrialBalanceReads books() {
        return new InMemoryTrialBalanceReads()
                .period(new TrialBalanceReads.Period(periodId, MONTH.atDay(1), MONTH.atEndOfMonth(), PeriodStatus.LOCKED,
                        Optional.of(pinned)))
                .version(pinned, VERSION);
    }

    private ReportInputs inputs(RunBasis basis) {
        return new ReportInputs(periodId, MONTH, 1, basis, basis == RunBasis.FINAL ? Optional.of(CUT) : Optional.empty(),
                Optional.of(pinned), Optional.of(1), CUT, RunParameters.none());
    }

    /** A counterparty read answering {@code aggregates} for the month, and nothing before it. */
    private static CounterpartyPositionReads answering(List<JournalReads.Aggregate> aggregates) {
        return new CounterpartyPositionReads() {
            @Override
            public List<JournalReads.Aggregate> before(Connection snapshot, LocalDate before, Optional<Instant> cut) {
                return List.of();
            }

            @Override
            public List<JournalReads.Aggregate> within(Connection snapshot, JournalReads.Range range, Optional<Instant> cut) {
                return aggregates;
            }
        };
    }

    /** The independent fold: over the journal's lines themselves, no aggregate, no report code. */
    private static Figures oracle(InMemoryTrialBalanceReads reads, String key, String currency) {
        Figures figures = new Figures();
        LocalDate first = MONTH.atDay(1);
        LocalDate last = MONTH.atEndOfMonth();
        for (InMemoryTrialBalanceReads.JournalLine line : reads.lines()) {
            if (!line.key().equals(key) || !line.currency().equals(currency) || line.postingDate().isAfter(last)) {
                continue;
            }
            BigInteger amount = BigInteger.valueOf(line.minorUnits());
            if (line.postingDate().isBefore(first)) {
                figures.opening = line.side() == Side.DEBIT ? figures.opening.add(amount) : figures.opening.subtract(amount);
            } else if (line.side() == Side.DEBIT) {
                figures.debits = figures.debits.add(amount);
            } else {
                figures.credits = figures.credits.add(amount);
            }
        }
        return figures;
    }

    private static final class Figures {
        private BigInteger opening = BigInteger.ZERO;
        private BigInteger debits = BigInteger.ZERO;
        private BigInteger credits = BigInteger.ZERO;
    }

    /** The reads, counting how often a report pays for the walk from inception. */
    private static final class CountingReads implements TrialBalanceReads {
        private final TrialBalanceReads reads;
        private int befores;

        CountingReads(TrialBalanceReads reads) {
            this.reads = reads;
        }

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
            befores++;
            return reads.before(snapshot, before, cut);
        }

        @Override
        public List<JournalReads.Aggregate> within(Connection snapshot, JournalReads.Range range, Optional<Instant> cut) {
            return reads.within(snapshot, range, cut);
        }

        @Override
        public List<LedgerWindows> ledger(Connection snapshot, Period period, Optional<Instant> cut) {
            return reads.ledger(snapshot, period, cut);
        }
    }
}
