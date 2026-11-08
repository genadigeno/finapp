package com.finapp.app.accounting;

import static com.finapp.app.accounting.AccountingPeriodFixture.FEBRUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.IDS;
import static com.finapp.app.accounting.AccountingPeriodFixture.JANUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.NOW;
import static com.finapp.app.accounting.AccountingPeriodFixture.correlation;
import static com.finapp.app.accounting.AccountingPeriodFixture.id;
import static com.finapp.app.accounting.AccountingPeriodFixture.inScope;
import static com.finapp.app.accounting.AccountingPeriodFixture.on;
import static com.finapp.app.accounting.AccountingPeriodFixture.person;
import static com.finapp.app.accounting.AccountingPeriodFixture.row;
import static com.finapp.app.accounting.PeriodCloseFixture.ACCOUNTANT;
import static com.finapp.app.accounting.PeriodCloseFixture.CONTROLLER;
import static com.finapp.app.accounting.PeriodCloseFixture.MACHINE;
import static com.finapp.app.accounting.PeriodCloseFixture.REASON;
import static com.finapp.app.accounting.PeriodCloseFixture.SETS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseRunId;
import com.finapp.accounting.GlAccount;
import com.finapp.accounting.GlClassification;
import com.finapp.accounting.GlMappingAdministration;
import com.finapp.accounting.GlMappingStore;
import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.JdbcPeriodPins;
import com.finapp.accounting.JdbcPriorPeriodAdjustmentStore;
import com.finapp.accounting.JdbcReportRunStore;
import com.finapp.accounting.JdbcValuationRateSetStore;
import com.finapp.accounting.MappingRule;
import com.finapp.accounting.PeriodCloses;
import com.finapp.accounting.PriorPeriodAdjustmentStatus;
import com.finapp.accounting.PriorPeriodAdjustments;
import com.finapp.accounting.PriorPeriodPostings;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.Side;
import com.finapp.accounting.StatementSection;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.reporting.BalanceSheetDefinition;
import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.accounting.reporting.IncomeStatementDefinition;
import com.finapp.accounting.reporting.ReportDefinition;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.ReportInputs;
import com.finapp.accounting.reporting.ReportRefused;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.StatementNotes;
import com.finapp.accounting.reporting.TrialBalanceDefinition;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.AdjustmentService;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcAdjustmentProposalStore;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.BreakCause;
import com.finapp.reconciliation.InternalClassification;
import com.finapp.reconciliation.JdbcBreakRegister;
import com.finapp.reconciliation.JdbcReportReadings;
import com.finapp.reconciliation.ParkedConfirmations;
import com.finapp.reconciliation.SuspenseSide;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The financial statements against a live PostgreSQL (`P14-TSK-016`; ADR-0115; owner decisions C6, C7, C8; {@code INV-ACC-02}, {@code INV-ACC-04}, {@code INV-ACC-05}, {@code INV-ACC-06}, {@code INV-ACC-11}): the equation, every side held to <em>this suite's own</em> raw sums of the ledger by classification; the notes over real rows; every figure walked to its lines through the real door;
 * FINAL bytes after a prior-period adjustment, a break resolved and an item parked; a journal that really does not fit; a statement waiting for its close; a re-close beside the first.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): it closes January and February 1999 for real, commits ledger lines and parks items with no line behind them, which in the shared container would fail {@code ReconciledPositionResidueDatabaseTest#noSuiteLeftAnUnexplainedLine} for good. <strong>Written and owed</strong> (owner decision C5). Every comparison is a relation that holds whatever else
 * those months hold, this suite's earlier cases included, whose lines and items stay. The close's checks are concluded by the fixture's own verdicts ({@link PeriodCloseFixture}): what is proven here is the statements and the machine, the judges being {@code CloseRunDatabaseTest}'s. Refusals are asserted by exception type or raw by SQLSTATE: a store's failure carries none of the driver's text.
 *
 * <p><strong>One case runs last, and every other holds in any order.</strong> A reopen moves a revision for good, a correction is refused into a month ever reopened ({@code PriorPeriodAdjustments.propose}, {@code V009}'s birth), and {@link #theBooks} reopens whatever the case before it closed. So {@link #aCorrectionIsDisclosedWhereItIsDated}, the one case that closes February, is ordered last, and {@link #theBooks} says so if February ever moved. January is
 * reopened and closed again by whichever case wants it, each reading its revision from the row. {@link #everyFigureDrillsToLines} leaves a second GL version in force from February, wherever it falls: January stays under the first, and the only other case reading February's figures is the last, whose every comparison is by its own adjustment's id.
 */
@Tag("database")
@Tag("own-container") // its own JVM and database: ownContainerDatabaseTest, the javadoc says why
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the financial statements against the database (P14-TSK-016)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class StatementsDatabaseTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    /** The account {@link #everyFigureDrillsToLines}' second version retires, and the one it restates the loans' purposes to. */
    private static final String RETIRED = "1300";
    private static final String RESTATED = "1305";
    private static final LocalDate HISTORY = LocalDate.of(1998, 12, 15);
    private static final LocalDate FIRST_DAY = JANUARY.atDay(1);
    private static final LocalDate LAST_DAY = JANUARY.atEndOfMonth();
    private static final Instant AS_OF = Instant.parse("1999-02-01T00:00:00Z");
    private static final Clock FEBRUARY_TENTH = on("1999-02-10");
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The source and rule set {@code OperationalReportsDatabaseTest} parks under. */
    private static final UUID SOURCE = UUID.fromString("01a0e2bc-8200-7001-8000-000000000001");
    private static final UUID RULE_SET = UUID.fromString("01a0e2bd-8300-7001-8000-000000000001");
    private static final JdbcLedgerAccountStore ACCOUNTS = new JdbcLedgerAccountStore();
    private static final TrialBalanceReads READS = new LedgerTrialBalanceReads(new JdbcGlMappingStore());
    private static final LedgerCounterpartyPositionReads POSITIONS = new LedgerCounterpartyPositionReads();
    private static final StatementNotes NOTES = new StatementNotes(new ReconciliationMonthReads(new JdbcReportReadings(), AccountingPeriodFixture.DECLARED_SOURCES), new ReconciliationAsOfCutoffReads(new JdbcReportReadings()),
            new LedgerPriorPeriodCorrections(), new AccountingCloseRecordReads());
    private static final BalanceSheetDefinition SHEET = new BalanceSheetDefinition(READS, POSITIONS, new PinnedValuationRates(new JdbcValuationRateSetStore()), NOTES);
    private static final IncomeStatementDefinition INCOME = new IncomeStatementDefinition(READS, POSITIONS, new PinnedValuationRates(new JdbcValuationRateSetStore()), NOTES);
    private static final ReportDefinitions DEFINITIONS = ReportDefinitions.of(SHEET, INCOME, new TrialBalanceDefinition(READS));
    private static final GlMappingVersion CHART = ReferenceGlMapping.version(JANUARY);
    private static final AccountingTransactions TRANSACTIONS = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            return inScope(Actor.SYSTEM, work);
        }
    };
    private static final JdbcReportSnapshots SNAPSHOTS = new JdbcReportSnapshots(DatabaseRoles::application, ReportRuns.Leases.STANDARD.generationTimeout());
    /** Report generation's snapshots, as production opens them: the reporting role ({@code P15-TSK-021}). */
    private static final JdbcReportSnapshots GENERATION_SNAPSHOTS = new JdbcReportSnapshots(DatabaseRoles::reporting,
            ReportRuns.Leases.STANDARD.generationTimeout());
    private static final GlSnapshots GL_SNAPSHOTS = new GlSnapshots(DatabaseRoles::application, Duration.ofSeconds(30));

    private LedgerAccount wallet;
    private LedgerAccount loan;

    /** A month before the periods begin, once: a reopen of January leaves the cutoff at December's end, so nothing is dated there again. */
    @BeforeAll
    static void history() throws SQLException {
        AccountingPeriodFixture.openJanuaryAndFebruary();
        PeriodCloseFixture.mappingInForce();
        PeriodCloseFixture.everyPeriodOpen();
        LedgerAccount wallet = owned(AccountPurpose.CUSTOMER_WALLET, AccountType.LIABILITY, EUR);
        LedgerAccount loan = owned(AccountPurpose.LOAN_PRINCIPAL, AccountType.ASSET, EUR);
        LedgerAccount fees = operational(AccountPurpose.FEE_REVENUE);
        post(HISTORY, loan, wallet, 70_000, EUR);
        post(HISTORY, wallet, fees, 300, EUR);
    }

    @BeforeEach
    void theBooks() throws SQLException {
        PeriodCloseFixture.everyPeriodOpen();
        assertThat(row(FEBRUARY).revision()).as("only the last case closes February: a month ever reopened takes no correction, and a reopen is for good").isEqualTo(1);
        wallet = owned(AccountPurpose.CUSTOMER_WALLET, AccountType.LIABILITY, EUR);
        loan = owned(AccountPurpose.LOAN_PRINCIPAL, AccountType.ASSET, EUR);
        post(FIRST_DAY, loan, wallet, 1_000, EUR);
        post(LAST_DAY, wallet, operational(AccountPurpose.FEE_REVENUE), 25, EUR);
        post(LAST_DAY, operational(AccountPurpose.PROCESSING_COSTS), wallet, 7, EUR);
        post(FEBRUARY.atDay(1), loan, wallet, 11, EUR);
    }

    // ------------------------------------------------------------------ the entry's three

    @Test
    @DisplayName("assets = liabilities + equity + retained earnings + the result, per currency, each side equal to this suite's own sums of the ledger by classification; the income statement's result the same figure; the ledger's own totals written beside the journal's")
    void assetsEqualLiabilitiesPlusEquityPlusResult() throws Exception {
        PeriodCloseFixture.closed(JANUARY);
        JsonNode sheet = bodyOf(ask(SHEET, id(JANUARY), RunParameters.none()));
        JsonNode income = bodyOf(ask(INCOME, id(JANUARY), RunParameters.none()));
        Map<String, Map<GlClassification, BigDecimal>> before = byClassification("entry.posting_date < '" + FIRST_DAY + "'");
        Map<String, Map<GlClassification, BigDecimal>> through = byClassification("entry.posting_date <= '" + LAST_DAY + "'");
        assertThat(sheet.get("equation")).isNotEmpty();
        for (JsonNode equation : sheet.get("equation")) {
            String currency = equation.get("currency").asText();
            Map<GlClassification, BigDecimal> atStart = before.getOrDefault(currency, Map.of());
            Map<GlClassification, BigDecimal> atEnd = through.getOrDefault(currency, Map.of());
            // Raw sums debit-positive: a credit-normal side's figure is its negation.
            assertThat(amount(equation.get("assets"))).as(currency).isEqualByComparingTo(atEnd.getOrDefault(GlClassification.ASSET, BigDecimal.ZERO));
            assertThat(amount(equation.get("liabilities"))).as(currency).isEqualByComparingTo(atEnd.getOrDefault(GlClassification.LIABILITY, BigDecimal.ZERO).negate());
            assertThat(amount(equation.get("equity"))).as(currency).isEqualByComparingTo(atEnd.getOrDefault(GlClassification.EQUITY, BigDecimal.ZERO).negate());
            BigDecimal retained = pAndL(atStart);
            BigDecimal result = pAndL(atEnd).subtract(retained);
            assertThat(amount(equation.get("retainedEarnings"))).as(currency).isEqualByComparingTo(retained);
            assertThat(amount(equation.get("currentPeriodResult"))).as(currency).isEqualByComparingTo(result);
            assertThat(amount(equation.get("difference"))).as(currency).isZero();
            assertThat(amount(equation.get("assets"))).as(currency + ": it balances from lines").isEqualByComparingTo(amount(equation.get("liabilitiesEquityAndResult")));
            assertThat(equation.get("journal")).as("the ledger's own totals, joining no account").isEqualTo(equation.get("ledger"));
        }
        for (JsonNode row : income.get("result")) {
            String currency = row.get("currency").asText();
            JsonNode equation = find(sheet.get("equation"), "currency", currency);
            assertThat(amount(row.get("result"))).as(currency).isEqualByComparingTo(amount(equation.get("currentPeriodResult")));
        }
        assertThat(row(JANUARY).status().name()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("the notes disclose suspense and breaks over real rows: the frozen register's items and each one's owner, a break standing at the instant by id, its value at issue equal to the raw sum, the close's counts and checks; both statements alike; no reason, person or evidence anywhere in them")
    void notesDiscloseSuspenseAndBreaks() throws Exception {
        ParkedConfirmations.Opened parked = park(LocalDate.of(1999, 1, 30), Instant.parse("1999-01-30T10:00:00Z"), 42_50);
        CloseRunId run = PeriodCloseFixture.ready(JANUARY, CloseCheckCode.RECONCILIATION_DISCLOSURE);
        String reason = "the disclosure was read, and this sentence is quoted nowhere";
        int attempt = (int) count("SELECT max(attempt) FROM accounting.close_check WHERE close_run_id = '" + run.value() + "' AND check_code = 'RECONCILIATION_DISCLOSURE'");
        PeriodCloses.Acted close = inScope(ACCOUNTANT, uow -> MACHINE.propose(uow, id(JANUARY), run, List.of(new PeriodCloses.Disclosure(CloseCheckCode.RECONCILIATION_DISCLOSURE, attempt, reason)), ACCOUNTANT, REASON, correlation()));
        inScope(CONTROLLER, uow -> MACHINE.approve(uow, id(JANUARY), close.proposal().orElseThrow(), CONTROLLER, REASON, correlation()));

        for (ReportDefinition definition : List.of(SHEET, INCOME)) {
            ReportRunStore.RunRow statement = ask(definition, id(JANUARY), RunParameters.none());
            assertThat(statement.status()).as(definition.code()).isEqualTo(RunStatus.FINAL);
            String content = contentOf(statement);
            JsonNode notes = JSON.readTree(content).get("body").get("notes");

            JsonNode suspense = notes.get("suspense");
            assertThat(suspense.get("asOf").asText()).isEqualTo(AS_OF.toString());
            long items = 0;
            for (JsonNode group : suspense.get("groups")) {
                items += group.get("creditItems").asLong() + group.get("debitItems").asLong();
            }
            assertThat(items).as("the register's own count").isEqualTo(count("SELECT count(*) FROM reconciliation.suspense_item s JOIN reconciliation.break b ON b.id = s.break_id"
                    + " WHERE b.raised_at < '" + AS_OF + "' AND s.opened_on <= '" + LAST_DAY + "' AND (s.status <> 'RELEASED' OR s.status_changed_at >= '" + AS_OF + "')"));
            assertThat(suspense.get("items")).hasSize((int) items);
            assertThat(texts(suspense.get("items"), "item")).contains(parked.suspenseItemId().toString());
            assertThat(texts(suspense.get("items"), "owningBreak")).contains(parked.breakId().toString());

            JsonNode eur = find(notes.get("reconciliationBreaks").get("groups"), "currency", "EUR");
            assertThat(texts(eur.get("breakIds"))).contains(parked.breakId().toString());
            String standing = " FROM reconciliation.break WHERE currency = 'EUR' AND raised_at < '" + AS_OF + "' AND (resolved_at IS NULL OR resolved_at >= '" + AS_OF + "')";
            assertThat(eur.get("breaks").asLong()).isEqualTo(count("SELECT count(*)" + standing));
            assertThat(new BigInteger(eur.get("valueAtIssueMinor").asText())).isEqualTo(BigInteger.valueOf(count("SELECT sum(value_at_issue_minor)" + standing)));

            JsonNode record = notes.get("closeRecord");
            assertThat(record.get("revision").asInt()).isEqualTo(row(JANUARY).revision());
            assertThat(record.get("checks")).hasSize(9);
            assertThat(record.get("disclosedChecks").asLong()).isEqualTo(1);
            assertThat(record.get("accountingBreaks").get("acknowledgedAtProposal").asLong()).isEqualTo(count("SELECT breaks_acknowledged FROM accounting.period_close WHERE id = '" + close.proposal().orElseThrow().value() + "'"));
            assertThat(content).as("a CONFIDENTIAL reason is neither read nor quoted").doesNotContain(reason).doesNotContain(ACCOUNTANT.id()).doesNotContain(CONTROLLER.id()).doesNotContain(REASON);
            assertThat(notes.get("deferredTreatments").get("writeOffsProvisioningAndEffectiveInterest").asText()).contains("four-eyes");
            assertThat(notes.get("referenceChart").asText()).contains("reference artefacts");
        }
    }

    @Test
    @DisplayName("every figure drills to lines through the real door, under the run's version: a row's movement the month's lines, its opening the earlier months' walked back until openingLines are counted, a side of the equation the sum of its rows, each computed figure its components' lines; each read recorded accounting.GlRead, each page naming its month's own status. And the bridge: a move's balance and an account's opening this run's lines, an account the version retired refused here and its previous closing the previous month's run's")
    void everyFigureDrillsToLines() throws Exception {
        PeriodCloseFixture.closed(JANUARY);
        ReportRunStore.RunRow statement = ask(SHEET, id(JANUARY), RunParameters.none());
        JsonNode body = bodyOf(statement);
        long read = count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.GlRead' AND target_id = '" + statement.id().value() + "'");
        long walked = 0;
        Map<String, BigDecimal> assetsFromRows = new TreeMap<>();

        for (JsonNode section : body.get("sections")) {
            for (JsonNode row : section.get("rows")) {
                String account = row.get("glAccount").asText();
                String currency = row.get("currency").asText();
                BigDecimal[] movement = walk(statement, account, currency, JANUARY, row.get("lines").asLong());
                walked++;
                assertThat(movement[0]).as(account + " debits").isEqualByComparingTo(amount(row.get("debits")));
                assertThat(movement[1]).as(account + " credits").isEqualByComparingTo(amount(row.get("credits")));
                BigDecimal[] opening = walk(statement, account, currency, JANUARY.minusMonths(1), row.get("openingLines").asLong());
                walked += row.get("openingLines").asLong() > 0 ? 1 : 0;
                boolean debitNormal = row.get("normalBalance").asText().equals("DEBIT");
                BigDecimal openingNet = debitNormal ? opening[0].subtract(opening[1]) : opening[1].subtract(opening[0]);
                BigDecimal movementNet = debitNormal ? movement[0].subtract(movement[1]) : movement[1].subtract(movement[0]);
                assertThat(openingNet).as(account + " opening").isEqualByComparingTo(amount(row.get("opening")));
                assertThat(openingNet.add(movementNet)).as(account + " closing").isEqualByComparingTo(amount(row.get("closing")));
                if (row.get("classification").asText().equals("ASSET")) {
                    assetsFromRows.merge(currency, amount(row.get("closing")), BigDecimal::add);
                }
            }
        }
        for (JsonNode equation : body.get("equation")) {
            assertThat(assetsFromRows.getOrDefault(equation.get("currency").asText(), BigDecimal.ZERO)).isEqualByComparingTo(amount(equation.get("assets")));
        }
        for (String computed : List.of("retainedEarnings", "currentPeriodResult")) {
            Map<String, BigDecimal> sums = new TreeMap<>();
            for (JsonNode component : body.get(computed).get("components")) {
                String currency = component.get("currency").asText();
                BigDecimal[] lines = walk(statement, component.get("glAccount").asText(), currency, computed.equals("retainedEarnings") ? JANUARY.minusMonths(1) : JANUARY, component.get("lines").asLong());
                walked++;
                sums.merge(currency, lines[1].subtract(lines[0]), BigDecimal::add);
            }
            for (JsonNode equation : body.get("equation")) {
                assertThat(sums.getOrDefault(equation.get("currency").asText(), BigDecimal.ZERO)).as(computed).isEqualByComparingTo(amount(equation.get(computed)));
            }
        }
        assertThat(walked).isPositive();
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.GlRead' AND target_id = '" + statement.id().value() + "'")).as("every page recorded").isGreaterThan(read);

        // The bridge: a version from February moves the loans' purposes to an account of their own and retires the old one, so February's opening restates January's closing.
        secondVersionFromFebruary();
        journalBehindTheDatabaseClock();
        ReportRunStore.RunRow restated = ask(SHEET, id(FEBRUARY), RunParameters.none());
        assertThat(restated.status()).as("February is open: PROVISIONAL, its door saying its pages are the journal's as it stands").isEqualTo(RunStatus.PROVISIONAL);
        JsonNode restatedBody = bodyOf(restated);
        JsonNode bridge = restatedBody.get("reclassification");
        assertThat(bridge.get("footsToZero").asBoolean()).isTrue();
        assertThat(bridge.get("previousPeriodPin").asText()).as("the version January's run pinned").isEqualTo(statement.mappingVersionId().orElseThrow().value().toString());
        assertThat(bridge.get("moves")).isNotEmpty();
        Map<String, BigDecimal> moved = new TreeMap<>();
        for (JsonNode move : bridge.get("moves")) {
            assertThat(move.get("from").asText()).isEqualTo(RETIRED);
            assertThat(move.get("to").asText()).isEqualTo(RESTATED);
            moved.merge(move.get("currency").asText(), amount(move.get("balance")), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> currency : moved.entrySet()) {
            BigDecimal[] to = walk(restated, RESTATED, currency.getKey(), JANUARY, rowOf(restatedBody, RESTATED, currency.getKey()).get("openingLines").asLong());
            assertThat(to[0].subtract(to[1])).as("what moved is the to account's lines, debit-positive").isEqualByComparingTo(currency.getValue());
            assertThat(to[0].subtract(to[1])).isEqualByComparingTo(amount(bridgeOf(bridge, RESTATED, currency.getKey()).get("opening")));

            assertThatThrownBy(() -> lines(restated, RETIRED, currency.getKey(), JANUARY, null)).as("retired from this run's version, no door on this run").isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode()).isEqualTo(AccountingErrorCode.NOT_FOUND));
            JsonNode was = rowOf(body, RETIRED, currency.getKey());
            BigDecimal[] from = walk(statement, RETIRED, currency.getKey(), JANUARY, was.get("openingLines").asLong() + was.get("lines").asLong());
            assertThat(from[0].subtract(from[1])).as("the previous closing: the previous month's run, under the version the bridge names").isEqualByComparingTo(amount(bridgeOf(bridge, RETIRED, currency.getKey()).get("previousClosing")));
        }
    }

    // ------------------------------------------------------------------ frozen, or it is not FINAL

    @Test
    @DisplayName("INV-ACC-04: both FINAL statements regenerate byte-identical after a prior-period adjustment posted after the close, a break resolved after it, an item parked after it dated inside the month, and a later month's lines; an item's ageing is from the cutoff, in a month twenty-seven years gone")
    void aFinalStatementRegeneratesIdentical() throws Exception {
        ParkedConfirmations.Opened standing = park(LocalDate.of(1999, 1, 30), Instant.parse("1999-01-30T10:00:00Z"), 11_00);
        PeriodCloseFixture.closed(JANUARY);
        List<ReportRunStore.RunRow> issued = List.of(ask(SHEET, id(JANUARY), RunParameters.none()), ask(INCOME, id(JANUARY), RunParameters.none()));
        assertThat(issued).allSatisfy(run -> assertThat(run.status()).isEqualTo(RunStatus.FINAL));
        JsonNode ageing = bodyOf(issued.get(0)).get("notes").get("suspense").get("groups").get(0).get("ageing");
        assertThat(ageing.get("0-2").asLong()).as("two days old at its cutoff, whatever today is").isGreaterThanOrEqualTo(1);

        PriorPeriodAdjustments.Acted adjustment = approved(wallet);
        assertThat(adjustment.status()).isEqualTo(PriorPeriodAdjustmentStatus.POSTED);
        resolve(standing.breakId());
        ParkedConfirmations.Opened adopted = park(LocalDate.of(1999, 1, 20), NOW.instant(), 5_00);
        post(FEBRUARY.atDay(3), wallet, operational(AccountPurpose.FEE_REVENUE), 13, EUR);

        for (ReportRunStore.RunRow run : issued) {
            ReportRunGenerator.Compared compared = generator().regenerate(run.id());
            assertThat(compared.identical()).as(run.definition()).isTrue();
            assertThat(compared.regeneratedSha256()).isEqualTo(run.contentSha256().orElseThrow());
        }
        assertThat(contentOf(issued.get(0))).as("resolved after the instant, it stood at it").contains(standing.breakId().toString()).doesNotContain(adopted.suspenseItemId().toString());
    }

    @Test
    @Order(Integer.MAX_VALUE) // It closes February, the one case that does: the next #theBooks would reopen it, and a correction is refused into a month ever reopened.
    @DisplayName("a correction is disclosed where it is dated: February's statements name the adjustment, January and its revision, with nothing untraced; a PROVISIONAL read says 'before the month'; January's say nothing of it")
    void aCorrectionIsDisclosedWhereItIsDated() throws Exception {
        PeriodCloseFixture.closed(JANUARY);
        PriorPeriodAdjustments.Acted posted = approved(wallet);
        JsonNode open = bodyOf(ask(SHEET, id(FEBRUARY), RunParameters.none())).get("notes");
        assertThat(open.get("closeRecord").isNull()).as("no close at an open month's revision").isTrue();
        assertThat(open.get("priorPeriodCorrections").get("reversalsMean").asText()).contains("before the month").contains("may still be open");

        PeriodCloseFixture.closed(FEBRUARY);
        JsonNode february = bodyOf(ask(SHEET, id(FEBRUARY), RunParameters.none())).get("notes").get("priorPeriodCorrections");
        JsonNode adjustment = find(february.get("adjustments"), "id", posted.id().value().toString());
        assertThat(adjustment.get("correctedMonth").asText()).isEqualTo(JANUARY.toString());
        assertThat(adjustment.get("correctedRevision").asInt()).isEqualTo(posted.correctedRevision());
        assertThat(adjustment.get("entry").asText()).isEqualTo(posted.entry().orElseThrow().toString());
        assertThat(february.get("untraced").asLong()).as("written, and zero because nothing bypassed the door").isZero();
        assertThat(february.get("reversalsMean").asText()).contains("closed month");

        JsonNode january = bodyOf(ask(SHEET, id(JANUARY), RunParameters.none())).get("notes").get("priorPeriodCorrections");
        assertThat(texts(january.get("adjustments"), "id")).doesNotContain(posted.id().value().toString());
    }

    // ------------------------------------------------------------------ what refuses

    @Test
    @DisplayName("a journal that really does not fit: a one-sided entry, planted on its own connection because the balance trigger is deferred to commit, read in the same transaction: both statements refused OUT_OF_BALANCE, the books never forced to balance; rolled back, the same read answers")
    void aJournalThatDoesNotFitIsRefused() throws Exception {
        ReportGeneration generation = new ReportGeneration(ReportGeneration.FROM_THE_SNAPSHOT, Duration.ofMinutes(4), NOW);
        try (Connection plant = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            plant.setAutoCommit(false);
            rawOneSidedEntry(plant, wallet);
            ReportInputs provisional = provisional(plant);
            for (ReportDefinition definition : List.of(SHEET, INCOME)) {
                assertThatThrownBy(() -> generation.generate(plant, definition, provisional)).as(definition.code()).isInstanceOfSatisfying(ReportRefused.class, refused -> {
                    assertThat(refused.reason()).isEqualTo(ReportRefused.Reason.OUT_OF_BALANCE);
                    assertThat(refused.subjects()).containsExactly("EUR");
                });
            }
            plant.rollback();
            ReportInputs clean = provisional(plant);
            assertThat(generation.generate(plant, SHEET, clean).content()).as("and only then").contains("\"basis\":\"PROVISIONAL\"");
            plant.rollback();
        }
    }

    @Test
    @DisplayName("a statement waits for its close: refused at the claim while LOCKED and CLOSE_PROPOSED, no row born, so nothing a close could wait on; the close goes through untroubled and the statement is then FINAL")
    void aStatementWaitsForItsClose() throws Exception {
        AccountingPeriodId january = id(JANUARY);
        long runs = count("SELECT count(*) FROM accounting.report_run WHERE period_id = '" + january.value() + "' AND definition = '" + BalanceSheetDefinition.CODE + "'");
        CloseRunId run = PeriodCloseFixture.ready(JANUARY);
        Actor accountant = person();
        assertThatThrownBy(() -> TRANSACTIONS.inTransaction(uow -> runs().claim(uow, BalanceSheetDefinition.CODE, january, RunParameters.none(), accountant, correlation()))).isInstanceOf(ReportRuns.PeriodNotClosed.class);
        PeriodCloses.Acted close = inScope(ACCOUNTANT, uow -> MACHINE.propose(uow, january, run, List.of(), ACCOUNTANT, REASON, correlation()));
        assertThatThrownBy(() -> TRANSACTIONS.inTransaction(uow -> runs().claim(uow, IncomeStatementDefinition.CODE, january, RunParameters.none(), accountant, correlation()))).isInstanceOf(ReportRuns.PeriodNotClosed.class);
        assertThat(count("SELECT count(*) FROM accounting.report_run WHERE period_id = '" + january.value() + "' AND definition = '" + BalanceSheetDefinition.CODE + "'")).isEqualTo(runs);

        inScope(CONTROLLER, uow -> MACHINE.approve(uow, january, close.proposal().orElseThrow(), CONTROLLER, REASON, correlation()));
        assertThat(ask(SHEET, january, RunParameters.none()).status()).isEqualTo(RunStatus.FINAL);
    }

    /** One read through the real door, as a controller. */
    private static FinalStatementDesk.FinalStatementView readAsAController(String runId) {
        FinalStatementDesk desk = new FinalStatementDesk(runs(), new JdbcPeriodPins(), SNAPSHOTS, TRANSACTIONS, new JdbcAuditWriter(), AccountingFlowScope.NONE, IDS, NOW);
        try (SecurityContext.Scope who = SecurityContext.enter(CONTROLLER);
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(correlation()))) {
            return desk.read(runId);
        }
    }

    @Test
    @DisplayName("P14-TSK-022 (v): a controller reads the FINAL statements of the close it approved, over the real stores and the real bytes: the month's real status, the issued hash, the figures as issued, no door named, audited; another definition and an unknown id are the one 404; after a reopen the same id is that 404, and nothing more is recorded")
    void aControllerReadsAFinalStatement() throws Exception {
        PeriodCloseFixture.closed(JANUARY);
        AccountingPeriodId january = id(JANUARY);
        int revision = row(JANUARY).revision();
        ReportRunStore.RunRow sheet = ask(SHEET, january, RunParameters.none());
        ReportRunStore.RunRow income = ask(INCOME, january, RunParameters.none());
        ReportRunStore.RunRow trialBalance = ask(DEFINITIONS.current(TrialBalanceDefinition.CODE).orElseThrow(), january, RunParameters.none());

        for (ReportRunStore.RunRow run : List.of(sheet, income)) {
            FinalStatementDesk.FinalStatementView view = readAsAController(run.id().value().toString());

            assertThat(view.periodStatus()).isEqualTo("CLOSED");
            assertThat(view.periodRevision()).isEqualTo(revision);
            assertThat(view.issuedSha256()).isEqualTo(single("SELECT content_sha256 FROM accounting.report_run WHERE id = '" + run.id().value() + "'"));
            assertThat(view.statement()).doesNotContain("/v1/").doesNotContain("ACCOUNTING_READ").doesNotContain("RECONCILIATION_INVESTIGATE");
            // The view is the whole issued content less what names a door (FinalStatementDesk: "nothing else changed"), so its figures are under its body, as the issued bytes' are.
            assertThat(JSON.readTree(view.statement()).get("body").get("sections")).as("the figures as issued").isNotNull().isEqualTo(bodyOf(run).get("sections"));
            assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.FinalStatementRead' AND target_id = '" + run.id().value() + "'")).isEqualTo(1);
        }
        String stale = sheet.id().value().toString();
        List<String> refused = new ArrayList<>(List.of(trialBalance.id().value().toString(), com.finapp.accounting.ReportRunId.next(IDS).value().toString()));
        inScope(ACCOUNTANT, uow -> MACHINE.proposeReopen(uow, january, ACCOUNTANT, PeriodCloseFixture.posted(uow, january), "a correction was found", correlation()));
        assertThat(readAsAController(stale).periodStatus()).as("only proposed: the statement stands").isEqualTo("CLOSED");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.FinalStatementRead' AND target_id = '" + stale + "'")).isEqualTo(2);
        inScope(CONTROLLER, uow -> MACHINE.approveReopen(uow, january, MACHINE.pendingOf(uow, january).reopen().orElseThrow().id(), CONTROLLER, PeriodCloseFixture.posted(uow, january), "agreed", correlation()));
        refused.add(stale);

        for (String id : refused) {
            assertThatThrownBy(() -> readAsAController(id)).isInstanceOfSatisfying(com.finapp.platform.api.ApiException.class, notFound -> assertThat(notFound.errorCode()).isEqualTo(com.finapp.accounting.AccountingErrorCode.NOT_FOUND));
        }
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.FinalStatementRead' AND target_id = '" + stale + "'")).as("a refusal records nothing").isEqualTo(2);
    }

    @Test
    @DisplayName("reopened and re-closed: the first FINAL run SUPERSEDED and kept; the next ask a new run under the new revision and lock; its note reads its own close and names the first, which stands beside it")
    void aReCloseIsANewRunBesideTheFirst() throws Exception {
        PeriodCloseFixture.closed(JANUARY);
        AccountingPeriodId january = id(JANUARY);
        int firstRevision = row(JANUARY).revision();
        ReportRunStore.RunRow first = ask(SHEET, january, RunParameters.none());
        String issued = single("SELECT content FROM accounting.report_run WHERE id = '" + first.id().value() + "'");
        String firstClose = single("SELECT id::text FROM accounting.period_close WHERE period_id = '" + january.value() + "' AND period_revision = " + firstRevision + " AND status = 'APPROVED'");

        inScope(ACCOUNTANT, uow -> MACHINE.proposeReopen(uow, january, ACCOUNTANT, PeriodCloseFixture.posted(uow, january), "a correction was found", correlation()));
        inScope(CONTROLLER, uow -> MACHINE.approveReopen(uow, january, MACHINE.pendingOf(uow, january).reopen().orElseThrow().id(), CONTROLLER, PeriodCloseFixture.posted(uow, january), "agreed", correlation()));
        assertThat(single("SELECT status FROM accounting.report_run WHERE id = '" + first.id().value() + "'")).isEqualTo("SUPERSEDED");

        post(LAST_DAY, wallet, operational(AccountPurpose.FEE_REVENUE), 3, EUR);
        PeriodCloseFixture.closed(JANUARY);
        ReportRunStore.RunRow second = ask(SHEET, january, RunParameters.none());

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.periodRevision()).isEqualTo(firstRevision + 1);
        assertThat(second.status()).isEqualTo(RunStatus.FINAL);
        JsonNode record = bodyOf(second).get("notes").get("closeRecord");
        assertThat(record.get("revision").asInt()).isEqualTo(firstRevision + 1);
        assertThat(texts(record.get("earlierCloses"), "close")).contains(firstClose);
        assertThat(single("SELECT content FROM accounting.report_run WHERE id = '" + first.id().value() + "'")).as("what was issued stands, its bytes as they were before the reopen").isEqualTo(issued);
        assertThat(issued).as("the first close's statement never read the second's").doesNotContain(single("SELECT id::text FROM accounting.period_close WHERE period_id = '" + january.value() + "' AND period_revision = " + (firstRevision + 1) + " AND status = 'APPROVED'"));
        assertThat(generator().regenerate(second.id()).identical()).isTrue();
    }

    @Test
    @DisplayName("a statement refused, and one abandoned mid-generation, wedge nothing: a FAILED row and a GENERATING one stand on a CLOSED month, the month reopens and closes again past them, the abandoned attempt's completion fails PERIOD_MOVED, and the next ask is FINAL")
    void aRefusedOrAbandonedStatementWedgesNothing() throws Exception {
        PeriodCloseFixture.closed(JANUARY);
        AccountingPeriodId january = id(JANUARY);
        Actor accountant = person();
        ReportRuns.Claimed refused = TRANSACTIONS.inTransaction(uow -> runs().claim(uow, BalanceSheetDefinition.CODE, january, RunParameters.none(), accountant, correlation()));
        ReportRuns.Concluded failed = TRANSACTIONS.inTransaction(uow -> runs().refuse(uow, refused.run().id(), refused.run().attempt(), new ReportRefused(ReportRefused.Reason.OUT_OF_BALANCE, List.of("EUR")).summary(), accountant, refused.run().correlation()));
        assertThat(failed.run().status()).isEqualTo(RunStatus.FAILED);
        assertThat(single("SELECT change_summary FROM platform.audit_record WHERE operation = 'accounting.ReportGenerated' AND target_id = '" + refused.run().id().value() + "'")).contains("refusal=OUT_OF_BALANCE:EUR");
        ReportRuns.Claimed abandoned = TRANSACTIONS.inTransaction(uow -> runs().claim(uow, IncomeStatementDefinition.CODE, january, RunParameters.none(), accountant, correlation()));
        assertThat(abandoned.run().status()).isEqualTo(RunStatus.GENERATING);
        ReportGeneration.Generated generated = SNAPSHOTS.read(snapshot -> new ReportGeneration(ReportGeneration.FROM_THE_SNAPSHOT, ReportRuns.Leases.STANDARD.generationTimeout(), NOW).generate(snapshot, INCOME, abandoned.run().inputs()));

        inScope(ACCOUNTANT, uow -> MACHINE.proposeReopen(uow, january, ACCOUNTANT, PeriodCloseFixture.posted(uow, january), "a correction was found", correlation()));
        inScope(CONTROLLER, uow -> MACHINE.approveReopen(uow, january, MACHINE.pendingOf(uow, january).reopen().orElseThrow().id(), CONTROLLER, PeriodCloseFixture.posted(uow, january), "agreed", correlation()));
        assertThat(TRANSACTIONS.inTransaction(uow -> runs().complete(uow, abandoned.run().id(), abandoned.run().attempt(), generated, accountant)).run().failure()).contains(com.finapp.accounting.RunFailure.PERIOD_MOVED);

        PeriodCloseFixture.closed(JANUARY);
        assertThat(row(JANUARY).status().name()).isEqualTo("CLOSED");
        assertThat(ask(SHEET, january, RunParameters.none()).status()).isEqualTo(RunStatus.FINAL);
    }

    // ------------------------------------------------------------------ translated

    @Test
    @DisplayName("the balance sheet balances translated: at the period's approved set, the two columns' totals equal per currency and across them through their difference lines, translation.balanced computed; completeness on the audit record; the income statement's result the same part; byte-identical on regeneration")
    void theBalanceSheetBalancesTranslated() throws Exception {
        LedgerAccount dollars = owned(AccountPurpose.CUSTOMER_WALLET, AccountType.LIABILITY, USD);
        LedgerAccount dollarLoan = owned(AccountPurpose.LOAN_PRINCIPAL, AccountType.ASSET, USD);
        post(LAST_DAY, dollarLoan, dollars, 1_001, USD);
        PeriodCloseFixture.closed(JANUARY);
        UUID set = inScope(Actor.SYSTEM, uow -> SETS.approvedFor(uow, id(JANUARY))).orElseThrow().id().value();
        RunParameters translated = RunParameters.of(Map.of(), Optional.of(set));

        ReportRunStore.RunRow run = ask(SHEET, id(JANUARY), translated);
        assertThat(run.status()).isEqualTo(RunStatus.FINAL);
        JsonNode translation = bodyOf(run).get("translation");
        assertThat(translation.get("complete").asBoolean()).isTrue();
        assertThat(translation.get("balanced").asBoolean()).isTrue();
        assertThat(translation.get("rateSet").get("id").asText()).isEqualTo(set.toString());
        assertThat(amount(translation.get("total").get("assets").get("translatedTotal"))).isEqualByComparingTo(amount(translation.get("total").get("liabilitiesEquityAndResult").get("translatedTotal")));
        for (JsonNode currency : translation.get("currencies")) {
            JsonNode columns = currency.get("columns");
            if (!columns.get("assets").isNull()) {
                assertThat(amount(columns.get("assets").get("translatedTotal"))).as(currency.get("currency").asText()).isEqualByComparingTo(amount(columns.get("liabilitiesEquityAndResult").get("translatedTotal")));
            }
        }
        assertThat(single("SELECT change_summary FROM platform.audit_record WHERE operation = 'accounting.ReportGenerated' AND target_id = '" + run.id().value() + "'")).contains("translationComplete=true").contains("untranslated=[]");
        assertThat(generator().regenerate(run.id()).identical()).isTrue();

        ReportRunStore.RunRow income = ask(INCOME, id(JANUARY), translated);
        assertThat(income.status()).isEqualTo(RunStatus.FINAL);
        JsonNode resultColumn = find(bodyOf(income).get("translation").get("currencies"), "currency", "EUR").get("columns").get("result");
        assertThat(amount(resultColumn.get("translatedTotal"))).isEqualByComparingTo(amount(find(bodyOf(run).get("equation"), "currency", "EUR").get("translated").get("currentPeriodResult")));
    }

    // ------------------------------------------------------------------ the raw side: this suite's own sums

    /** Per currency and classification, the raw sums debit-positive, by the reference chart this suite's version holds. */
    private static Map<String, Map<GlClassification, BigDecimal>> byClassification(String predicate) throws SQLException {
        Map<String, MappingRule> rules = CHART.rulesByKey();
        Map<String, Map<GlClassification, BigDecimal>> sums = new TreeMap<>();
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement read = migrator.prepareStatement("SELECT account.purpose, account.owner_kind, line.currency, line.scale, line.direction, SUM(line.amount_minor)"
                        + " FROM ledger.journal_line line JOIN ledger.journal_entry entry ON entry.id = line.entry_id JOIN ledger.ledger_account account ON account.id = line.ledger_account_id"
                        + " WHERE " + predicate + " GROUP BY 1, 2, 3, 4, 5");
                ResultSet row = read.executeQuery()) {
            while (row.next()) {
                MappingRule rule = rules.get(row.getString(1) + "/" + row.getString(2));
                GlClassification classification = CHART.account(rule.glAccountCode()).orElseThrow().classification();
                BigDecimal amount = new BigDecimal(row.getBigDecimal(6).toBigIntegerExact(), row.getInt(4));
                sums.computeIfAbsent(row.getString(3).stripTrailing(), ignored -> new TreeMap<>())
                        .merge(classification, "DEBIT".equals(row.getString(5)) ? amount : amount.negate(), BigDecimal::add);
            }
        }
        return sums;
    }

    /** A month's result, credit-positive, from raw debit-positive sums. */
    private static BigDecimal pAndL(Map<GlClassification, BigDecimal> sums) {
        return sums.getOrDefault(GlClassification.REVENUE, BigDecimal.ZERO).add(sums.getOrDefault(GlClassification.EXPENSE, BigDecimal.ZERO)).negate();
    }

    private static BigDecimal amount(JsonNode money) {
        return new BigDecimal(new BigInteger(money.get("minor").asText()), money.get("scale").asInt());
    }

    private static JsonNode rowOf(JsonNode body, String account, String currency) {
        for (JsonNode section : body.get("sections")) {
            for (JsonNode row : section.get("rows")) {
                if (row.get("glAccount").asText().equals(account) && row.get("currency").asText().equals(currency)) {
                    return row;
                }
            }
        }
        throw new AssertionError("no row " + account + " " + currency);
    }

    private static JsonNode bridgeOf(JsonNode bridge, String account, String currency) {
        for (JsonNode row : bridge.get("accounts")) {
            if (row.get("account").asText().equals(account) && row.get("currency").asText().equals(currency)) {
                return row;
            }
        }
        throw new AssertionError("no bridge row " + account + " " + currency);
    }

    private static JsonNode find(JsonNode array, String member, String value) {
        for (JsonNode element : array) {
            if (element.get(member).asText().equals(value)) {
                return element;
            }
        }
        throw new AssertionError("no " + member + " " + value + " in " + array);
    }

    private static Set<String> texts(JsonNode array) {
        Set<String> found = new HashSet<>();
        array.forEach(element -> found.add(element.asText()));
        return found;
    }

    private static Set<String> texts(JsonNode array, String member) {
        Set<String> found = new HashSet<>();
        array.forEach(element -> found.add(element.get(member).asText()));
        return found;
    }

    private static long count(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement read = migrator.prepareStatement(sql); ResultSet row = read.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String single(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement read = migrator.prepareStatement(sql); ResultSet row = read.executeQuery()) {
            return row.next() ? row.getString(1) : null;
        }
    }

    // ------------------------------------------------------------------ the door

    /** A figure's lines through the real door: under the run's version, a month at a time back from {@code from}, until {@code wanted} are counted. Debits, then credits. */
    private BigDecimal[] walk(ReportRunStore.RunRow run, String account, String currency, YearMonth from, long wanted) {
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        long counted = 0;
        for (YearMonth month = from; counted < wanted; month = month.minusMonths(1)) {
            assertThat(month).as("walked past the journal's start with lines still uncounted").isAfter(YearMonth.of(1998, 1));
            String after = null;
            do {
                ReportRunLinesDesk.ReportRunLinesView read = lines(run, account, currency, month, after);
                assertThat(read.exact()).as("a FINAL run's pages sum to its figure; any other's are the journal's as it stands").isEqualTo(run.status() == RunStatus.FINAL);
                assertThat(read.activity().periodStatus()).as("the month's own status").isEqualTo(month.equals(JANUARY) || month.equals(FEBRUARY) ? row(month).status().name() : "NONE");
                for (GeneralLedgerDesk.GlLineView line : read.activity().lines()) {
                    counted++;
                    if (line.side().equals(Side.DEBIT.name())) {
                        debits = debits.add(new BigDecimal(line.amount()));
                    } else {
                        credits = credits.add(new BigDecimal(line.amount()));
                    }
                }
                after = read.activity().next();
            } while (after != null);
        }
        assertThat(counted).as("exactly the lines the figure counts").isEqualTo(wanted);
        return new BigDecimal[] {debits, credits};
    }

    /** One page through the real door, as an accountant. */
    private static ReportRunLinesDesk.ReportRunLinesView lines(ReportRunStore.RunRow run, String account, String currency, YearMonth month, String after) {
        ReportRunLinesDesk desk = new ReportRunLinesDesk(runs(), new JdbcGlMappingStore(), new JdbcPeriodPins(), new GeneralLedger(new LedgerJournalReads()), GL_SNAPSHOTS, TRANSACTIONS, new JdbcAuditWriter(), AccountingFlowScope.NONE, IDS, NOW);
        try (SecurityContext.Scope who = SecurityContext.enter(person());
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(correlation()))) {
            return desk.lines(run.id().value().toString(), account, month.toString(), currency, after, 500);
        }
    }

    /**
     * Waits until the database's clock has passed every journal entry's {@code created_at}, so a PROVISIONAL run claimed next cuts after every line this case posted. ADR-0115 (`-008`'s
     * review, M-8): a PROVISIONAL run counts entries {@code created_at <= cut_at}, {@code created_at} the posting instance's clock and {@code cut_at} the claim's database clock -
     * "fuzzy both ways" by design. Docker Desktop's VM clock runs up to ~2 s off the JVM's (`P14-DOC-001`), so a run claimed a second after this case's postings left the January
     * line out: its opening counted one line and 700.00 where the journal held two and 710.00, and the door's walk of that one line read 10.00. The bridge's claim is over the
     * journal as asked, so it is asked once the journal is behind the cut - by 3 s, more than the largest backward step measured (~2.4 s), so a step between this read and the claim
     * cannot put the cut back before a stamp.
     */
    private static void journalBehindTheDatabaseClock() throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!"true".equals(single("SELECT (clock_timestamp() > (SELECT max(created_at) FROM ledger.journal_entry) + interval '3 seconds')::text"))) {
            assertThat(System.nanoTime()).as("the database's clock reached the journal's newest stamp within 30 s").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    /** A version in force from February that moves the loans' purposes to {@link #RESTATED} and drops {@link #RETIRED} from its chart, by the machine's four eyes. */
    private static void secondVersionFromFebruary() {
        GlMappingVersion reference = ReferenceGlMapping.version(FEBRUARY);
        List<GlAccount> accounts = new ArrayList<>(reference.accounts().stream().filter(account -> !account.code().equals(RETIRED)).toList());
        accounts.add(new GlAccount(RESTATED, "Loans - principal, restated", GlClassification.ASSET, StatementSection.LOANS_AND_BNPL_RECEIVABLES));
        List<MappingRule> rules = reference.rules().stream().map(rule -> rule.glAccountCode().equals(RETIRED) ? new MappingRule(rule.purpose(), rule.ownerKind(), RESTATED) : rule).toList();
        GlMappingVersion restated = new GlMappingVersion(FEBRUARY, accounts, rules);
        GlMappingAdministration administration = new GlMappingAdministration(new JdbcGlMappingStore(), new LedgerPurposeChart(), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW);
        Actor proposer = person();
        GlMappingAdministration.Proposed proposed = inScope(proposer, uow -> administration.propose(uow, restated, "the suite's restatement", proposer, correlation()));
        Actor approver = person();
        inScope(approver, uow -> administration.activate(uow, proposed.id(), approver, "the suite's restatement", correlation()));
    }

    // ------------------------------------------------------------------ the runs

    private static ReportRuns runs() {
        return new ReportRuns(new JdbcReportRunStore(), AccountingPeriodFixture.STORE, new JdbcGlMappingStore(), DEFINITIONS, ReportRuns.Leases.STANDARD, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW, ReportRuns.Provisional.STANDARD,
                ReportRuns.ApprovedSets.of(new JdbcValuationRateSetStore()));
    }

    private static ReportRunGenerator generator() {
        return new ReportRunGenerator(runs(), new ReportGeneration(ReportGeneration.FROM_THE_SNAPSHOT, ReportRuns.Leases.STANDARD.generationTimeout(), NOW), GENERATION_SNAPSHOTS, TRANSACTIONS, new ReportRunMetrics(new SimpleMeterRegistry(), DEFINITIONS));
    }

    private static ReportRunStore.RunRow ask(ReportDefinition definition, AccountingPeriodId period, RunParameters parameters) {
        Actor accountant = person();
        ReportRuns.Claimed claimed = TRANSACTIONS.inTransaction(uow -> runs().claim(uow, definition.code(), period, parameters, accountant, correlation()));
        return claimed.mine() ? generator().generateAndComplete(claimed.run(), accountant) : generator().drive(claimed.run().id(), accountant);
    }

    private static String contentOf(ReportRunStore.RunRow run) {
        return TRANSACTIONS.inTransaction(uow -> runs().content(uow, run.id())).orElseThrow().text();
    }

    private static JsonNode bodyOf(ReportRunStore.RunRow run) throws Exception {
        return JSON.readTree(contentOf(run)).get("body");
    }

    /** January's PROVISIONAL question, as the claim would ask it, on {@code connection}. */
    private static ReportInputs provisional(Connection connection) {
        GlMappingStore.VersionRow version = new JdbcGlMappingStore().inForceFor(connection, JANUARY).orElseThrow();
        return new ReportInputs(id(JANUARY), JANUARY, row(JANUARY).revision(), RunBasis.PROVISIONAL, Optional.empty(), Optional.of(version.id()), Optional.of(version.version()), NOW.instant().plusSeconds(60), RunParameters.none());
    }

    // ------------------------------------------------------------------ the books

    private static LedgerAccount operational(AccountPurpose purpose) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return ACCOUNTS.findOperational(app, purpose, EUR).orElseThrow();
        }
    }

    private static LedgerAccount owned(AccountPurpose purpose, AccountType type, CurrencyCode currency) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount account = ACCOUNTS.createOrConverge(app, LedgerAccount.owned(IDS, NOW, type, purpose, currency, IDS.next())).account();
            app.commit();
            return account;
        }
    }

    private static void post(LocalDate day, LedgerAccount debit, LedgerAccount credit, long minor, CurrencyCode currency) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            new PostingService(new IdempotentExecutor(new JdbcIdempotencyRecordStore(), NOW, Duration.ofDays(1), Duration.ofMinutes(5)), new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(), new JdbcBalanceProjection(), IDS, NOW, PostingObserver.NONE)
                    .post(app, new PostingCommand("statements-" + IDS.next(), day, day, "statements-suite", List.of(new JournalLine(debit.id(), Direction.DEBIT, Money.ofMinorUnits(minor, currency)), new JournalLine(credit.id(), Direction.CREDIT, Money.ofMinorUnits(minor, currency)))));
            app.commit();
        }
    }

    /** A one-sided entry dated in January, uncommitted: the balance trigger is deferred to commit, so it is visible only on {@code app}. */
    private static void rawOneSidedEntry(Connection app, LedgerAccount account) throws SQLException {
        UUID entry = IDS.next();
        String flow = UUID.randomUUID().toString();
        try (PreparedStatement insert = app.prepareStatement("INSERT INTO ledger.journal_entry (id, posting_date, value_date, entry_type, reference, actor_id, correlation_id, causation_id, idempotency_scope, created_at)"
                + " VALUES (?, ?, ?, 'POSTING', 'raw-sql', 'raw', ?, ?, ?, ?)")) {
            insert.setObject(1, entry);
            insert.setObject(2, LAST_DAY);
            insert.setObject(3, LAST_DAY);
            insert.setString(4, flow);
            insert.setString(5, flow);
            insert.setString(6, PostingService.IDEMPOTENCY_SCOPE + ":raw-" + IDS.next());
            insert.setTimestamp(7, Timestamp.from(NOW.instant()));
            insert.executeUpdate();
        }
        try (PreparedStatement line = app.prepareStatement("INSERT INTO ledger.journal_line (id, entry_id, ledger_account_id, direction, amount_minor, currency, scale, seq) VALUES (?, ?, ?, 'DEBIT', 1, 'EUR', 2, 1)")) {
            line.setObject(1, IDS.next());
            line.setObject(2, entry);
            line.setObject(3, account.id().value());
            line.executeUpdate();
        }
    }

    /** A parking dated {@code openedOn}, its owning break raised {@code raisedAt}: a stamp this suite states, as an adoption's own instant is its act's. */
    private static ParkedConfirmations.Opened park(LocalDate openedOn, Instant raisedAt, long minor) {
        return TRANSACTIONS.inTransaction(uow -> new ParkedConfirmations(new JdbcBreakRegister(new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS), IDS).open(uow, new ParkedConfirmations.Opening(IDS.next(), SOURCE, RULE_SET, SuspenseSide.CREDIT, Money.ofPersisted(minor, EUR, 2), openedOn, IDS.next(),
                BreakCause.PARKED_ON_RECEIPT, InternalClassification.values()[0], Optional.empty(), "UNATTRIBUTED", new Actor("system", ActorType.SYSTEM), raisedAt, correlation())));
    }

    /**
     * A break resolved now, planted as the owner with the triggers off, for this one row (the {@code BnplProofDatabaseTest} idiom): {@code reconciliation V015} holds a {@code RESOLVED} break to a resolution naming it, and which resolution resolves it is that module's and no part of this. What is proven here is that a note does not move when
     * the row does: its {@code status}, {@code resolved_at} and {@code status_changed_at} are what the real edge writes.
     */
    private static void resolve(UUID breakId) throws SQLException {
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement role = owner.createStatement(); PreparedStatement update = owner.prepareStatement("UPDATE reconciliation.break SET status = 'RESOLVED', resolved_at = clock_timestamp(), status_changed_at = clock_timestamp() WHERE id = ?")) {
                role.execute("SET LOCAL session_replication_role = replica");
                update.setObject(1, breakId);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            owner.commit();
        }
    }

    // ------------------------------------------------------------------ the adjustment

    private static PriorPeriodAdjustments machine() {
        return new PriorPeriodAdjustments(AccountingPeriodFixture.STORE, new JdbcPriorPeriodAdjustmentStore(), new LedgerPriorPeriodPostings(new AdjustmentService(new IdempotentExecutor(new JdbcIdempotencyRecordStore(), NOW, Duration.ofDays(1), Duration.ofMinutes(5)), new JdbcJournalEntryStore(IDS),
                new JdbcAdjustmentProposalStore(), new JdbcAuditWriter(), new JdbcOutboxWriter(), new JdbcBalanceProjection(), IDS, NOW, PostingObserver.NONE, ACCOUNTS), ACCOUNTS), new JdbcGlMappingStore(), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, FEBRUARY_TENTH);
    }

    /** A correction of January posted on February the tenth, under four eyes: fee income given back. */
    private static PriorPeriodAdjustments.Acted approved(LedgerAccount customer) throws SQLException {
        List<PriorPeriodPostings.Line> lines = List.of(new PriorPeriodPostings.Line(operational(AccountPurpose.FEE_REVENUE).id().value(), Side.DEBIT, Money.ofMinorUnits(1_500, EUR)), new PriorPeriodPostings.Line(customer.id().value(), Side.CREDIT, Money.ofMinorUnits(1_500, EUR)));
        PriorPeriodAdjustments.Acted proposed = inScope(ACCOUNTANT, uow -> machine().propose(uow, id(JANUARY), row(JANUARY).revision(), lines, ACCOUNTANT, REASON, correlation()));
        Set<UUID> posted = new HashSet<>(inScope(Actor.SYSTEM, uow -> machine().postedAgainst(uow, id(JANUARY))));
        PriorPeriodAdjustments.Approval reading = new PriorPeriodAdjustments.Approval(proposed.postingDate(), proposed.correctedRevision(), posted);
        Actor approver = person();
        return inScope(approver, uow -> machine().approve(uow, proposed.id(), reading, approver, "read and agreed", correlation()));
    }
}
