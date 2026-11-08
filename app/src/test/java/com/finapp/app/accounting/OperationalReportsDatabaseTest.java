package com.finapp.app.accounting;

import static com.finapp.app.accounting.AccountingPeriodFixture.FEBRUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.IDS;
import static com.finapp.app.accounting.AccountingPeriodFixture.JANUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.NOW;
import static com.finapp.app.accounting.AccountingPeriodFixture.correlation;
import static com.finapp.app.accounting.AccountingPeriodFixture.id;
import static com.finapp.app.accounting.AccountingPeriodFixture.inScope;
import static com.finapp.app.accounting.AccountingPeriodFixture.periods;
import static com.finapp.app.accounting.AccountingPeriodFixture.person;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.CloseRunStall;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.CounterpartyPositionReads;
import com.finapp.accounting.JdbcAccountingBreakStore;
import com.finapp.accounting.JdbcCloseRunStore;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.JdbcReportRunStore;
import com.finapp.accounting.JdbcValuationRateSetStore;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.OperationalDoorBound;
import com.finapp.accounting.OperationalReports;
import com.finapp.accounting.PastTheCutoffPostings;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.reporting.CloseStatusDefinition;
import com.finapp.accounting.reporting.GlActivityDefinition;
import com.finapp.accounting.reporting.PurposeReportDefinition;
import com.finapp.accounting.reporting.ReadOnlySnapshot;
import com.finapp.accounting.reporting.ReconciliationStatusDefinition;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.SuspenseDefinition;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
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
import com.finapp.reconciliation.ReportReadings;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The ten against a live PostgreSQL (`P14-TSK-017`; {@code INV-ACC-02}, {@code INV-ACC-04}, {@code INV-ACC-14}, {@code INV-BAL-05}): the
 * entry's one case per definition, each asserting its figures equal <em>this suite's own</em> raw sums of the ledger by purpose, per
 * currency; a FINAL run regenerating identical while the register behind it moves; the two operational ones refused a run and answered
 * live on the real statements.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}; `P14-TSK-017`'s review, I-2): it commits lines straight onto {@code
 * SETTLEMENT_CLEARING}, {@code SUSPENSE_UNMATCHED} and the counterparties' clearing accounts, and a parking with no line behind it. Journal
 * lines are immutable, no expectation names these and no backfill can adopt them, so in the shared container they would fail {@code
 * ReconciledPositionResidueDatabaseTest#noSuiteLeftAnUnexplainedLine} for good. The rule is {@code CloseRunDatabaseTest}'s; this suite cannot
 * roll them back, because a run reads them on a snapshot of its own.
 *
 * <p><strong>The books it uses.</strong> Only the fixture's <strong>January and February 1999</strong>
 * ({@link AccountingPeriodFixture}: `P14-TSK-012`'s close suites close January only in databases of their own, so only January can be locked here); it approves no valuation rate set,
 * reading January's as it finds it (`P14-TSK-014`'s note); it activates the reference GL mapping version from January once ({@code
 * PeriodCloseFixture.mappingInForce}), since its own database holds none and GL_ACTIVITY's accounts are a pinned version's; every comparison is a relation that holds whatever else those months hold,
 * this suite's own earlier cases included, whose lines stay. Each case returns the periods to {@code OPEN}, superseding its FINAL runs. An
 * adopted parking it opens stays - an item is never deleted - and is dated in January with a raise of today, so it never enters January's
 * frozen read. <strong>Written and owed</strong> (owner decision C5: the database tiers are skipped per task).
 */
@Tag("database")
@Tag("own-container") // its own JVM and database: ownContainerDatabaseTest, the javadoc says why
@DisplayName("the operational financial reports against the database (P14-TSK-017)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class OperationalReportsDatabaseTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final LocalDate HISTORY = LocalDate.of(1998, 12, 15);
    private static final LocalDate FIRST_DAY = JANUARY.atDay(1);
    private static final LocalDate LAST_DAY = JANUARY.atEndOfMonth();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID SOURCE = UUID.fromString("01a0e2bc-8200-7001-8000-000000000001");
    private static final UUID RULE_SET = UUID.fromString("01a0e2bd-8300-7001-8000-000000000001");
    private static final JdbcLedgerAccountStore ACCOUNTS = new JdbcLedgerAccountStore();
    private static final TrialBalanceReads READS = new LedgerTrialBalanceReads(new JdbcGlMappingStore());
    private static final CounterpartyPositionReads POSITIONS = new LedgerCounterpartyPositionReads();
    private static final ReconciliationMonthReads MONTH_READS = new ReconciliationMonthReads(new JdbcReportReadings(), AccountingPeriodFixture.DECLARED_SOURCES);
    private static final LedgerFlooredPostings POSTINGS = new LedgerFlooredPostings();
    private static final ProductReconciliationDisclosure DISCLOSURE =
            new ProductReconciliationDisclosure(new JdbcReportReadings(), POSTINGS);
    private static final CloseRunStall STALL = new CloseRunStall(Duration.ofSeconds(30), CloseRunStall.STANDARD_POLLS);
    private static final AccountingTransactions TRANSACTIONS = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            try (Connection connection = DatabaseRoles.application()) {
                connection.setAutoCommit(false);
                try {
                    R result = work.apply(connection);
                    connection.commit();
                    return result;
                } catch (RuntimeException failure) {
                    connection.rollback();
                    throw failure;
                }
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }
    };
    private static final JdbcReportSnapshots SNAPSHOTS = new JdbcReportSnapshots(DatabaseRoles::application,
            ReportRuns.Leases.STANDARD.generationTimeout());
    /** Report generation's snapshots, as production opens them: the reporting role ({@code P15-TSK-021}). */
    private static final JdbcReportSnapshots GENERATION_SNAPSHOTS = new JdbcReportSnapshots(DatabaseRoles::reporting,
            ReportRuns.Leases.STANDARD.generationTimeout());
    private static final OperationalDoorBound DOOR = new OperationalDoorBound(Duration.parse(OperationalDoorBound.STANDARD_TIMEOUT),
            OperationalDoorBound.STANDARD_PERMITS);
    private static final JdbcReportSnapshots DOOR_SNAPSHOTS = new JdbcReportSnapshots(DatabaseRoles::application, DOOR.timeout());
    private static final CloseRuns CLOSE_RUNS = new CloseRuns(new JdbcCloseRunStore(), AccountingPeriodFixture.STORE,
            new JdbcAccountingBreakStore(), CloseRuns.Leases.STANDARD, new JdbcAuditWriter(), IDS, NOW);
    private static final ReportDefinitions DEFINITIONS = ReportDefinitions.of(
            new GlActivityDefinition(READS, POSITIONS),
            PurposeReportDefinition.revenue(READS),
            PurposeReportDefinition.receivablesPayables(READS),
            PurposeReportDefinition.settlementPositions(READS, POSITIONS),
            PurposeReportDefinition.fxResults(READS),
            PurposeReportDefinition.loanPortfolio(READS),
            PurposeReportDefinition.bnplExposure(READS),
            new SuspenseDefinition(READS, MONTH_READS),
            new ReconciliationStatusDefinition(DISCLOSURE, MONTH_READS),
            new CloseStatusDefinition(new AccountingCloseStatusReads(AccountingPeriodFixture.STORE, CLOSE_RUNS,
                    new JdbcAccountingBreakStore(), new JdbcValuationRateSetStore()), STALL));

    private LedgerAccount cash;

    @BeforeEach
    void theBooksHoldEveryReportsPurposes() throws SQLException {
        AccountingPeriodFixture.restore();
        // P14-DOC-001: in a database of its own no GL mapping version is in force unless the suite activates one - the shared
        // container's was another suite's - and with none pinned GL_ACTIVITY puts every line in its unmapped rows, so its accounts
        // and their components were empty and every claim on them held of nothing. The reference version, from January, once.
        PeriodCloseFixture.mappingInForce();
        cash = operational(AccountPurpose.SETTLEMENT_CLEARING);
        LedgerAccount loan = owned(AccountPurpose.LOAN_PRINCIPAL, AccountType.ASSET);
        LedgerAccount obligation = owned(AccountPurpose.BNPL_PRINCIPAL, AccountType.ASSET);
        LedgerAccount wallet = owned(AccountPurpose.CUSTOMER_WALLET, AccountType.LIABILITY);
        for (LedgerAccount asset : List.of(loan, obligation)) {
            post(HISTORY, asset, cash, 70_000);
            post(FIRST_DAY, asset, cash, 1_000);
            post(LAST_DAY, cash, asset, 25);
            post(FEBRUARY.atDay(1), asset, cash, 7);
        }
        post(HISTORY, cash, wallet, 500);
        post(LAST_DAY, wallet, operational(AccountPurpose.FEE_REVENUE), 25);
        post(LAST_DAY, cash, operational(AccountPurpose.SUSPENSE_UNMATCHED), 40);
        fxAccount().ifPresent(spread -> post(LAST_DAY, wallet, spread, 11));
    }

    @AfterEach
    void leaveOpen() {
        AccountingPeriodFixture.restore();
    }

    // ------------------------------------------------------------------ one case per definition

    @Test
    @DisplayName("GL_ACTIVITY: per currency its debits and credits equal the ledger's over the month, every line in an account or the unmapped"
            + " row - and a component per purpose equal to the raw sum")
    void glActivityEqualsTheLedger() throws Exception {
        JsonNode body = bodyOf(askFinal(GlActivityDefinition.CODE, lockJanuary()));
        Map<String, BigDecimal[]> byPurpose = ledger(months());
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (BigDecimal[] sides : byPurpose.values()) {
            debits = debits.add(sides[0]);
            credits = credits.add(sides[1]);
        }
        BigDecimal writtenDebits = BigDecimal.ZERO;
        BigDecimal writtenCredits = BigDecimal.ZERO;
        for (JsonNode currency : body.get("currencies")) {
            writtenDebits = writtenDebits.add(amount(currency.get("debits")));
            writtenCredits = writtenCredits.add(amount(currency.get("credits")));
            assertThat(currency.get("balanced").asBoolean()).as(currency.toString()).isTrue();
        }
        assertThat(writtenDebits).isEqualByComparingTo(debits);
        assertThat(writtenCredits).isEqualByComparingTo(credits);
        for (JsonNode account : body.get("accounts")) {
            for (JsonNode component : account.get("components")) {
                BigDecimal[] raw = byPurpose.get(keyOf(component, account.get("currency").asText()));
                assertThat(amount(component.get("debits"))).isEqualByComparingTo(raw[0]);
                assertThat(amount(component.get("credits"))).isEqualByComparingTo(raw[1]);
            }
        }
    }

    @Test
    @DisplayName("REVENUE's movement equals the ledger by purpose")
    void revenueEqualsTheLedger() throws Exception {
        figuresEqualTheLedger(PurposeReportDefinition.revenue(READS));
    }

    @Test
    @DisplayName("RECEIVABLES_PAYABLES' balances equal the ledger by purpose")
    void receivablesPayablesEqualTheLedger() throws Exception {
        figuresEqualTheLedger(PurposeReportDefinition.receivablesPayables(READS));
    }

    @Test
    @DisplayName("SETTLEMENT_POSITIONS' balances equal the ledger by purpose")
    void settlementPositionsEqualTheLedger() throws Exception {
        figuresEqualTheLedger(PurposeReportDefinition.settlementPositions(READS, POSITIONS));
    }

    @Test
    @DisplayName("I-1, INV-RAIL-04: over the real join two counterparties' opposite positions stay two rows, each this suite's own raw sum, and two"
            + " components in GL_ACTIVITY; CLEARING has no total; and the trial balance's read still has no such dimension")
    void theCounterpartiesStayApart() throws Exception {
        post(LAST_DAY, counterpartyAccount("fx-sim-a"), cash, 1_000);
        post(LAST_DAY, cash, counterpartyAccount("fx-sim-b"), 1_000);
        AccountingPeriodId january = lockJanuary();
        JsonNode body = bodyOf(askFinal("SETTLEMENT_POSITIONS", january));
        Map<String, BigDecimal[]> opening = ledger("entry.posting_date < '" + FIRST_DAY + "'");
        Map<String, BigDecimal[]> movement = ledger(months());
        JsonNode clearing = null;
        List<String> seen = new ArrayList<>();
        for (JsonNode section : body.get("sections")) {
            if (section.get("section").asText().equals("CLEARING")) {
                clearing = section;
            }
            for (JsonNode row : section.get("rows")) {
                assertFigures(row, opening, movement, true);
                if (row.get("purpose").asText().equals("FX_PROVIDER_CLEARING") && row.get("currency").asText().equals("EUR")) {
                    seen.add(row.get("counterparty").asText());
                }
            }
        }
        assertThat(seen).as("one row per counterparty, whatever else they hold").contains("fx-sim-a", "fx-sim-b");
        assertThat(clearing.get("totals").isNull()).as("assets and liabilities: no total").isTrue();

        List<String> components = new ArrayList<>();
        for (JsonNode account : bodyOf(askFinal(GlActivityDefinition.CODE, january)).get("accounts")) {
            for (JsonNode component : account.get("components")) {
                if (component.get("purpose").asText().equals("FX_PROVIDER_CLEARING") && account.get("currency").asText().equals("EUR")) {
                    components.add(component.get("counterparty").asText());
                }
            }
        }
        assertThat(components).contains("fx-sim-a", "fx-sim-b");
        List<JournalReads.Aggregate> trialBalances = SNAPSHOTS.read(snapshot -> READS.within(snapshot, JournalReads.Range.of(JANUARY),
                Optional.empty()));
        assertThat(trialBalances).as("TRIAL_BALANCE's statements are left alone")
                .allSatisfy(line -> assertThat(line.counterparty()).isEmpty());
    }

    @Test
    @DisplayName("I-1: the two reads agree on the real journal, opening and movement: what a report folds per counterparty is what the trial balance"
            + " read of the same purpose, per currency, scale and side")
    void theTwoReadsAgree() {
        SNAPSHOTS.read(snapshot -> {
            Map<String, BigInteger> byPurpose = new TreeMap<>();
            READS.before(snapshot, FIRST_DAY, Optional.empty()).stream().filter(line -> line.ownerKind().equals("COUNTERPARTY"))
                    .forEach(line -> byPurpose.merge(line.key() + line.currency() + line.scale() + line.side(), line.minorUnits(), BigInteger::add));
            Map<String, BigInteger> byCounterparty = new TreeMap<>();
            POSITIONS.before(snapshot, FIRST_DAY, Optional.empty()).forEach(line -> {
                assertThat(line.counterparty()).as("every counterparty's account names one").isPresent();
                byCounterparty.merge(line.key() + line.currency() + line.scale() + line.side(), line.minorUnits(), BigInteger::add);
            });
            assertThat(byCounterparty).isEqualTo(byPurpose);
            return null;
        });
    }

    @Test
    @DisplayName("I-3: the door's snapshot is armed at its own bound, not the report runs' four minutes - read back from the database")
    void theDoorsSnapshotIsArmedAtItsBound() {
        String armed = DOOR_SNAPSHOTS.read(snapshot -> {
            try (PreparedStatement read = snapshot.prepareStatement("SELECT current_setting('statement_timeout')");
                    ResultSet row = read.executeQuery()) {
                row.next();
                return row.getString(1);
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertThat(armed).isEqualTo("30s");
    }

    @Test
    @DisplayName("FX_RESULTS' movement equals the ledger by purpose")
    void fxResultsEqualTheLedger() throws Exception {
        figuresEqualTheLedger(PurposeReportDefinition.fxResults(READS));
    }

    @Test
    @DisplayName("LOAN_PORTFOLIO equals the ledger by purpose - never lending's live rows (the entry's probe's real half)")
    void loanPortfolioEqualsTheLedger() throws Exception {
        figuresEqualTheLedger(PurposeReportDefinition.loanPortfolio(READS));
    }

    @Test
    @DisplayName("BNPL_EXPOSURE equals the ledger by purpose - never bnpl's live rows")
    void bnplExposureEqualsTheLedger() throws Exception {
        figuresEqualTheLedger(PurposeReportDefinition.bnplExposure(READS));
    }

    @Test
    @DisplayName("SUSPENSE's ledger rows equal the ledger by purpose; its register equals reconciliation's frozen read")
    void suspenseEqualsTheLedger() throws Exception {
        AccountingPeriodId january = lockJanuary();
        JsonNode body = bodyOf(askFinal(SuspenseDefinition.CODE, january));
        Map<String, BigDecimal[]> opening = ledger("entry.posting_date < '" + FIRST_DAY + "'");
        Map<String, BigDecimal[]> movement = ledger(months());
        for (JsonNode row : body.get("sections").get(0).get("rows")) {
            assertThat(row.get("purpose").asText()).isEqualTo("SUSPENSE_UNMATCHED");
            assertFigures(row, opening, movement, true);
        }
        long items = 0;
        for (JsonNode group : body.get("register").get("groups")) {
            items += group.get("creditItems").asLong() + group.get("debitItems").asLong();
        }
        List<ReportReadings.SuspenseAsOfLine> born = SNAPSHOTS.read(snapshot -> {
            List<ReportReadings.SuspenseAsOfLine> lines = new ArrayList<>();
            new JdbcReportReadings().eachSuspenseItemBornAsOf(snapshot, LAST_DAY, asOf(), lines::add);
            return lines;
        });
        assertThat(items).isEqualTo(born.size());
    }

    @Test
    @DisplayName("RECONCILIATION_STATUS: never a run; live, its run coverage equal to the raw table, the disclosure's suspense lines equal to the"
            + " ledger's; and M-2 through the real SQL - one floored entry and one on its own dating told apart by the audit record")
    void reconciliationStatusEqualsTheLedger() throws Exception {
        AccountingPeriodId january = lockJanuary();
        assertThatThrownBy(() -> ask(ReconciliationStatusDefinition.CODE, january))
                .isInstanceOf(ReportRuns.DefinitionIsOperational.class);
        PastTheCutoffPostings.Split before = split();
        // The lock covers its stated date: floored to the first open date, the value date kept, flooredFrom= on its audit record.
        posting(new PostingCommand("settlement-batch:" + IDS.next(), LAST_DAY, LAST_DAY, "operational-reports-suite", lines(9),
                PostingCommand.Dating.FLOOR_AT_FIRST_OPEN));
        // Accepted after the cutoff, valued at the batch's business date: the rule's ordinary dating, nothing floored.
        posting(new PostingCommand("settlement-batch:" + IDS.next(), FEBRUARY.atDay(2), LAST_DAY, "operational-reports-suite", lines(9),
                PostingCommand.Dating.FLOOR_AT_FIRST_OPEN));
        PastTheCutoffPostings.Split after = split();
        assertThat(after.entries() - before.entries()).isEqualTo(2);
        assertThat(after.floored() - before.floored()).isEqualTo(1);
        assertThat(after.ownValueDate() - before.ownValueDate()).isEqualTo(1);
        assertThat(after.unrecorded() - before.unrecorded()).as("each stands on its own posting record").isZero();
        assertThat(after).as("the disclosure's read is this one (P14-TSK-012, the owner's (b))").isEqualTo(
                SNAPSHOTS.read(snapshot -> DISCLOSURE.asOfCutoff(snapshot, JANUARY)).pastTheCutoff().get("settlement-batch"));

        JsonNode body = bodyOf(answer(ReconciliationStatusDefinition.CODE, january));
        assertThat(body.get("coverage").get("batchRuns").asLong()).isEqualTo(count("SELECT count(*) FROM reconciliation.reconciliation_batch"
                + " WHERE kind = 'BATCH' AND business_date BETWEEN '" + FIRST_DAY + "' AND '" + LAST_DAY + "'"));
        assertThat(body.get("coverage").has("noRun")).as("I-4: a count, and no verdict on the month").isFalse();
        Map<String, BigDecimal[]> through = ledger("entry.posting_date <= '" + LAST_DAY + "'");
        for (JsonNode suspense : body.get("disclosure").get("suspense")) {
            BigDecimal[] raw = through.get("SUSPENSE_UNMATCHED|" + suspense.get("currency").asText());
            int scale = suspense.get("scale").asInt();
            assertThat(new BigDecimal(new BigInteger(suspense.get("ledgerDebitsMinor").asText()), scale))
                    .isEqualByComparingTo(raw == null ? BigDecimal.ZERO : raw[0]);
            assertThat(new BigDecimal(new BigInteger(suspense.get("ledgerCreditsMinor").asText()), scale))
                    .isEqualByComparingTo(raw == null ? BigDecimal.ZERO : raw[1]);
        }
    }

    @Test
    @DisplayName("CLOSE_STATUS: never a run; live, it holds no ledger figure and equals its rows - the lock with no run, then the run's nine checks,"
            + " the approved set as the table holds it - and a second read after the leg ran is a new answer")
    void closeStatusEqualsItsRows() throws Exception {
        AccountingPeriodId january = lockJanuary();
        assertThatThrownBy(() -> ask(CloseStatusDefinition.CODE, january)).isInstanceOf(ReportRuns.DefinitionIsOperational.class);
        OperationalReports.Answer first = answer(CloseStatusDefinition.CODE, january);
        JsonNode firstBody = bodyOf(first);
        assertThat(firstBody.get("status").asText()).isEqualTo("LOCKED");
        assertThat(firstBody.get("runState").asText()).isEqualTo("AWAITING_FIRST_POLL");
        assertThat(first.generated().content()).doesNotContain("\"minor\"");
        String approved = single("SELECT id::text FROM accounting.valuation_rate_set WHERE period_id = '" + january.value()
                + "' AND status = 'APPROVED'");
        JsonNode set = firstBody.get("approvedValuationRateSet");
        assertThat(set.isNull() ? null : set.get("id").asText()).isEqualTo(approved);
        for (String status : List.of("PROPOSED", "APPROVED", "REJECTED")) {
            assertThat(firstBody.get("valuationRateSets").get(status).asLong()).as("M-11: counted by the database, %s", status)
                    .isEqualTo(count("SELECT count(*) FROM accounting.valuation_rate_set WHERE period_id = '" + january.value()
                            + "' AND status = '" + status + "'"));
        }

        TRANSACTIONS.inTransaction(uow -> CLOSE_RUNS.startMissing(uow, Actor.SYSTEM));
        OperationalReports.Answer second = answer(CloseStatusDefinition.CODE, january);
        JsonNode secondBody = bodyOf(second);
        assertThat(secondBody.get("runState").asText()).isEqualTo("RUNNING");
        assertThat(secondBody.get("run").get("checks")).hasSize(9);
        assertThat(second.generated().sha256()).isNotEqualTo(first.generated().sha256());
        assertThat(count("SELECT count(*) FROM accounting.report_run WHERE definition = '" + CloseStatusDefinition.CODE + "'"))
                .as("nothing of it is stored").isZero();
    }

    // ------------------------------------------------------------------ INV-ACC-04

    @Test
    @DisplayName("INV-ACC-04: a FINAL SUSPENSE run regenerates IDENTICAL after a parking dated inside the period is adopted past the lock - which the"
            + " close's moving read does see, and this body must not")
    void aFinalSuspenseRunRegeneratesIdenticalAfterAnAdoption() {
        AccountingPeriodId january = lockJanuary();
        ReportRunStore.RunRow run = askFinal(SuspenseDefinition.CODE, january);
        assertThat(run.status()).isEqualTo(RunStatus.FINAL);

        UUID parking = IDS.next();
        ParkedConfirmations.Opened adopted = TRANSACTIONS.inTransaction(uow -> new ParkedConfirmations(new JdbcBreakRegister(
                new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS), IDS).open(uow, new ParkedConfirmations.Opening(parking, SOURCE,
                RULE_SET, SuspenseSide.CREDIT, Money.ofPersisted(42_50, EUR, 2), LocalDate.of(1999, 1, 20), IDS.next(),
                BreakCause.PARKED_ON_RECEIPT, InternalClassification.values()[0], Optional.empty(), "UNATTRIBUTED",
                new Actor("system", ActorType.SYSTEM), NOW.instant(), correlation())));

        List<UUID> moving = new ArrayList<>();
        List<UUID> frozen = new ArrayList<>();
        SNAPSHOTS.read(snapshot -> {
            new JdbcReportReadings().eachSuspenseItemAsOf(snapshot, LAST_DAY, asOf(), line -> moving.add(line.id()));
            new JdbcReportReadings().eachSuspenseItemBornAsOf(snapshot, LAST_DAY, asOf(), line -> frozen.add(line.id()));
            return null;
        });
        assertThat(moving).as("the close's read: exactly as old as its entry").contains(adopted.suspenseItemId());
        assertThat(frozen).as("the FINAL body's read: its owner was raised after the instant").doesNotContain(adopted.suspenseItemId());
        assertThat(generator().regenerate(run.id()).identical()).isTrue();
    }

    @Test
    @DisplayName("INV-ACC-04: every journal definition's FINAL run regenerates IDENTICAL after a posting in the next period, and is frozen")
    void everyFinalRunRegeneratesIdentical() throws SQLException {
        AccountingPeriodId january = lockJanuary();
        List<ReportRunStore.RunRow> issued = new ArrayList<>();
        for (String code : List.of(GlActivityDefinition.CODE, "REVENUE", "RECEIVABLES_PAYABLES", "SETTLEMENT_POSITIONS", "FX_RESULTS",
                "LOAN_PORTFOLIO", "BNPL_EXPOSURE", SuspenseDefinition.CODE)) {
            issued.add(askFinal(code, january));
        }
        post(FEBRUARY.atDay(3), cash, operational(AccountPurpose.FEE_REVENUE), 13);
        for (ReportRunStore.RunRow run : issued) {
            assertThat(run.status()).as(run.definition()).isEqualTo(RunStatus.FINAL);
            assertThat(generator().regenerate(run.id()).identical()).as(run.definition()).isTrue();
        }
    }

    @Test
    @DisplayName("INV-ACC-14: every read runs through the definition's restricted view on a real read-only snapshot - its statements admitted, a"
            + " write refused by the database")
    void everyReadIsTheViews() {
        AccountingPeriodId january = lockJanuary();
        for (String code : List.of(ReconciliationStatusDefinition.CODE, CloseStatusDefinition.CODE)) {
            assertThat(answer(code, january).generated().content()).contains("\"basis\":\"OPERATIONAL\"");
        }
        assertThatThrownBy(() -> SNAPSHOTS.read(snapshot -> {
            try (PreparedStatement write = new ReadOnlySnapshot(snapshot, Duration.ofSeconds(30), () -> 0L).connection()
                    .prepareStatement("WITH gone AS (DELETE FROM accounting.report_run RETURNING 1) SELECT count(*) FROM gone");
                    ResultSet rows = write.executeQuery()) {
                return rows.next();
            } catch (SQLException refused) {
                throw new IllegalStateException(refused.getSQLState());
            }
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("25006");
    }

    // ------------------------------------------------------------------ the figures

    private void figuresEqualTheLedger(PurposeReportDefinition definition) throws Exception {
        JsonNode body = bodyOf(askFinal(definition.code(), lockJanuary()));
        Map<String, BigDecimal[]> opening = ledger("entry.posting_date < '" + FIRST_DAY + "'");
        Map<String, BigDecimal[]> movement = ledger(months());
        boolean balances = definition.window() == PurposeReportDefinition.Window.BALANCES;
        for (JsonNode section : body.get("sections")) {
            for (JsonNode row : section.get("rows")) {
                assertFigures(row, opening, movement, balances);
            }
        }
        // Both ways: each of the report's keys the ledger moved in the month has its row.
        for (PurposeReportDefinition.Section section : definition.sections()) {
            for (String key : section.keys()) {
                String purpose = key.substring(0, key.indexOf('/'));
                for (String held : movement.keySet()) {
                    if (held.startsWith(purpose + "|") || held.startsWith(purpose + "#")) {
                        assertThat(has(body, held)).as(held).isTrue();
                    }
                }
            }
        }
    }

    private static boolean has(JsonNode body, String key) {
        for (JsonNode section : body.get("sections")) {
            for (JsonNode row : section.get("rows")) {
                if (keyOf(row, row.get("currency").asText()).equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A figure's key in {@link #ledger}: its purpose, its counterparty where it has one, its currency. */
    private static String keyOf(JsonNode figure, String currency) {
        return keyOf(figure.get("purpose").asText(), figure.get("counterparty").isNull() ? null : figure.get("counterparty").asText(),
                currency);
    }

    private static String keyOf(String purpose, String counterparty, String currency) {
        return purpose + (counterparty == null ? "" : "#" + counterparty) + "|" + currency;
    }

    private static void assertFigures(JsonNode row, Map<String, BigDecimal[]> opening, Map<String, BigDecimal[]> movement,
            boolean balances) {
        String key = keyOf(row, row.get("currency").asText());
        BigDecimal[] moved = movement.getOrDefault(key, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        assertThat(amount(row.get("debits"))).as(key).isEqualByComparingTo(moved[0]);
        assertThat(amount(row.get("credits"))).as(key).isEqualByComparingTo(moved[1]);
        if (balances) {
            BigDecimal[] before = opening.getOrDefault(key, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal net = before[0].subtract(before[1]);
            assertThat(amount(row.get("opening"))).as(key).isEqualByComparingTo(net);
            assertThat(amount(row.get("closing"))).as(key).isEqualByComparingTo(net.add(moved[0]).subtract(moved[1]));
        }
    }

    private static BigDecimal amount(JsonNode money) {
        return new BigDecimal(new BigInteger(money.get("minor").asText()), money.get("scale").asInt());
    }

    private static String months() {
        return "entry.posting_date >= '" + FIRST_DAY + "' AND entry.posting_date <= '" + LAST_DAY + "'";
    }

    /** This suite's own sums, read as the migrator: per purpose, counterparty where it has one, and currency, debits then credits. */
    private static Map<String, BigDecimal[]> ledger(String predicate) throws SQLException {
        Map<String, BigDecimal[]> sums = new TreeMap<>();
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement read = migrator.prepareStatement("SELECT account.purpose, counterparty.code, line.currency, line.scale,"
                        + " line.direction, SUM(line.amount_minor) FROM ledger.journal_line line JOIN ledger.journal_entry entry"
                        + " ON entry.id = line.entry_id JOIN ledger.ledger_account account ON account.id = line.ledger_account_id"
                        + " LEFT JOIN ledger.counterparty counterparty ON account.owner_kind = 'COUNTERPARTY'"
                        + " AND counterparty.id = account.owner_ref"
                        + " WHERE " + predicate + " GROUP BY 1, 2, 3, 4, 5");
                ResultSet row = read.executeQuery()) {
            while (row.next()) {
                String key = keyOf(row.getString(1), row.getString(2), row.getString(3).stripTrailing());
                BigDecimal amount = new BigDecimal(row.getBigDecimal(6).toBigIntegerExact(), row.getInt(4));
                BigDecimal[] sides = sums.computeIfAbsent(key, ignored -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                int side = "DEBIT".equals(row.getString(5)) ? 0 : 1;
                sides[side] = sides[side].add(amount);
            }
        }
        return sums;
    }

    private static long count(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement read = migrator.prepareStatement(sql);
                ResultSet row = read.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String single(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement read = migrator.prepareStatement(sql);
                ResultSet row = read.executeQuery()) {
            return row.next() ? row.getString(1) : null;
        }
    }

    private static Instant asOf() {
        return LAST_DAY.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static PastTheCutoffPostings.Split split() {
        return SNAPSHOTS.read(snapshot -> POSTINGS.pastTheCutoff(snapshot, LAST_DAY)).getOrDefault("settlement-batch",
                new PastTheCutoffPostings.Split(0, 0, 0));
    }

    // ------------------------------------------------------------------ the runs

    private static AccountingPeriodId lockJanuary() {
        AccountingPeriodId january = id(JANUARY);
        Actor accountant = person();
        inScope(accountant, uow -> periods().lock(uow, january, accountant, null, correlation()));
        return january;
    }

    private static ReportRunStore.RunRow ask(String code, AccountingPeriodId period) {
        Actor accountant = person();
        ReportRuns.Claimed claimed = TRANSACTIONS.inTransaction(uow -> runs().claim(uow, code, period, RunParameters.none(), accountant,
                correlation()));
        return claimed.mine() ? generator().generateAndComplete(claimed.run(), accountant)
                : generator().drive(claimed.run().id(), accountant);
    }

    private static ReportRunStore.RunRow askFinal(String code, AccountingPeriodId period) {
        return ask(code, period);
    }

    private static OperationalReports.Answer answer(String code, AccountingPeriodId period) {
        OperationalReports reports = new OperationalReports(DEFINITIONS, AccountingPeriodFixture.STORE, new ReportGeneration(
                ReportGeneration.FROM_THE_SNAPSHOT, DOOR.timeout(), NOW), new JdbcAuditWriter(), IDS, NOW);
        return DOOR_SNAPSHOTS.read(snapshot -> reports.answer(snapshot, code, period));
    }

    private static ReportRuns runs() {
        return new ReportRuns(new JdbcReportRunStore(), AccountingPeriodFixture.STORE, new JdbcGlMappingStore(), DEFINITIONS,
                ReportRuns.Leases.STANDARD, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW);
    }

    private static ReportRunGenerator generator() {
        return new ReportRunGenerator(runs(), new ReportGeneration(ReportGeneration.FROM_THE_SNAPSHOT,
                ReportRuns.Leases.STANDARD.generationTimeout(), NOW), GENERATION_SNAPSHOTS, TRANSACTIONS,
                new ReportRunMetrics(new SimpleMeterRegistry(), DEFINITIONS));
    }

    private static JsonNode bodyOf(ReportRunStore.RunRow run) throws Exception {
        String content = TRANSACTIONS.inTransaction(uow -> runs().content(uow, run.id())).orElseThrow().text();
        return JSON.readTree(content).get("body");
    }

    private static JsonNode bodyOf(OperationalReports.Answer answer) throws Exception {
        return JSON.readTree(answer.generated().content()).get("body");
    }

    // ------------------------------------------------------------------ the journal

    private static LedgerAccount operational(AccountPurpose purpose) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return ACCOUNTS.findOperational(app, purpose, EUR).orElseThrow();
        }
    }

    private static LedgerAccount counterpartyAccount(String code) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return ACCOUNTS.findCounterpartyAccount(app, AccountPurpose.FX_PROVIDER_CLEARING, code, EUR).orElseThrow();
        }
    }

    private static Optional<LedgerAccount> fxAccount() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return ACCOUNTS.findOperational(app, AccountPurpose.FX_SPREAD_REVENUE, EUR);
        }
    }

    private static LedgerAccount owned(AccountPurpose purpose, AccountType type) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount account = ACCOUNTS.createOrConverge(app, LedgerAccount.owned(IDS, NOW, type, purpose, EUR, IDS.next())).account();
            app.commit();
            return account;
        }
    }

    private List<JournalLine> lines(long minor) {
        try {
            return List.of(new JournalLine(cash.id(), Direction.DEBIT, Money.ofMinorUnits(minor, EUR)),
                    new JournalLine(operational(AccountPurpose.FEE_REVENUE).id(), Direction.CREDIT, Money.ofMinorUnits(minor, EUR)));
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void post(LocalDate day, LedgerAccount debit, LedgerAccount credit, long minor) {
        posting(new PostingCommand("operational-reports-" + IDS.next(), day, day, "operational-reports-suite",
                List.of(new JournalLine(debit.id(), Direction.DEBIT, Money.ofMinorUnits(minor, EUR)),
                        new JournalLine(credit.id(), Direction.CREDIT, Money.ofMinorUnits(minor, EUR)))));
    }

    private static void posting(PostingCommand command) {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            new PostingService(new IdempotentExecutor(new JdbcIdempotencyRecordStore(), NOW, Duration.ofDays(1), Duration.ofMinutes(5)),
                    new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(), new JdbcBalanceProjection(), IDS,
                    NOW, PostingObserver.NONE).post(app, command);
            app.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
