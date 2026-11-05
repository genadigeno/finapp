package com.finapp.app.accounting;

import static com.finapp.app.accounting.AccountingPeriodFixture.IDS;
import static com.finapp.app.accounting.AccountingPeriodFixture.NOW;
import static com.finapp.app.accounting.AccountingPeriodFixture.correlation;
import static com.finapp.app.accounting.AccountingPeriodFixture.id;
import static com.finapp.app.accounting.AccountingPeriodFixture.inScope;
import static com.finapp.app.accounting.AccountingPeriodFixture.on;
import static com.finapp.app.accounting.AccountingPeriodFixture.periods;
import static com.finapp.app.accounting.AccountingPeriodFixture.person;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.CounterpartyPositionReads;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.JdbcPeriodPins;
import com.finapp.accounting.JdbcRegulatoryObligationStore;
import com.finapp.accounting.JdbcRegulatorySubmissionStore;
import com.finapp.accounting.JdbcReportRunStore;
import com.finapp.accounting.LendingFundedAsOfCutoff;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.RegulatoryAdapters;
import com.finapp.accounting.RegulatoryFormLine;
import com.finapp.accounting.RegulatoryObligationConfiguration;
import com.finapp.accounting.RegulatoryObligationId;
import com.finapp.accounting.RegulatoryObligations;
import com.finapp.accounting.RegulatoryRuleType;
import com.finapp.accounting.RegulatorySubmissionStore;
import com.finapp.accounting.RegulatorySubmissions;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.SubmissionStatus;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.SafeguardingPositionDefinition;
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
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.RepositoryPaths;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The reference safeguarding return against a live PostgreSQL (`P14-TSK-019`; ADR-0118 decision 4; {@code INV-ACC-13}, {@code INV-ACC-04}): the real statements of {@link LedgerTrialBalanceReads}, {@link LedgerCounterpartyPositionReads} and {@link LendingFundedAsOfCutoffReads}, in a real {@code REPEATABLE READ READ ONLY} snapshot, under the application role, each figure asserted against <em>this suite's own raw sum</em>. OWED (C5): written and walked by hand; it has never executed. What real loans do is {@code SafeguardingCommittedDatabaseTest}'s: every loan is opened
 * now, so none stands at a cutoff in 1999, and here {@code committed} is a true zero read by the real statement.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): it commits lines straight onto {@code CASH_AT_BANK} and the other reconciled positions, closes months of 1999 for real, reopens one, and activates a GL mapping from January.
 *
 * <p><strong>No state crosses a case, in any order.</strong> A reopen moves a revision for good, and periods close in order: so <em>every case takes its own month</em> from {@link #openMonth}, the earliest not closed, whatever a case before it left, and closes it. Journal lines are immutable and every figure is cumulative from inception, so a case never asserts a figure outright: each compares the run's figure with a raw sum through the same last day, whatever earlier cases
 * posted, or a difference it caused itself. Every case takes its own form code, so no obligation or one-live index crosses cases. No case leaves a period {@code LOCKED} or {@code CLOSE_PROPOSED}, so {@code JUnit}'s order, which is not this file's, does not matter. Only {@code #aShortfallIsReportedInItsOwnCurrency} posts in GBP.
 */
@Tag("database")
@Tag("own-container") // its own JVM and database: ownContainerDatabaseTest, the javadoc says why
@DisplayName("the reference safeguarding return against the database (P14-TSK-019)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class SafeguardingReturnDatabaseTest {

    private static final int INSTANCES = 10;
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final String REASON = "read and agreed";
    private static final String HEADING = "#### The reference safeguarding position return: its configuration";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger FORMS = new AtomicInteger();
    private static final JdbcLedgerAccountStore ACCOUNTS = new JdbcLedgerAccountStore();
    private static final TrialBalanceReads READS = new LedgerTrialBalanceReads(new JdbcGlMappingStore());
    private static final CounterpartyPositionReads POSITIONS = new LedgerCounterpartyPositionReads();
    private static final LendingFundedAsOfCutoffReads LENDING = new LendingFundedAsOfCutoffReads();
    private static final SafeguardingPositionDefinition DEFINITION = new SafeguardingPositionDefinition(READS, POSITIONS, LENDING);
    private static final ReportDefinitions DEFINITIONS = ReportDefinitions.of(DEFINITION);
    private static final JdbcRegulatoryObligationStore OBLIGATIONS = new JdbcRegulatoryObligationStore();
    private static final JdbcRegulatorySubmissionStore STORE = new JdbcRegulatorySubmissionStore();
    private static final JdbcReportRunStore RUNS = new JdbcReportRunStore();
    private static final ReportRuns REPORT_RUNS = new ReportRuns(RUNS, AccountingPeriodFixture.STORE, new JdbcGlMappingStore(), DEFINITIONS, ReportRuns.Leases.STANDARD, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW);
    private static final JdbcReportSnapshots SNAPSHOTS = new JdbcReportSnapshots(DatabaseRoles::application, ReportRuns.Leases.STANDARD.generationTimeout());
    private static final GlSnapshots GL_SNAPSHOTS = new GlSnapshots(DatabaseRoles::application, Duration.ofSeconds(30));
    private static final RegulatoryAdapters ADAPTERS = new RegulatoryAdapters(List.of(new SimulatedRegulatoryReportingProvider(SimulatedRegulatoryReportingProvider.Behaviour.ACKNOWLEDGE)));
    private static final RegulatorySubmissions MACHINE = new RegulatorySubmissions(AccountingPeriodFixture.STORE, STORE, OBLIGATIONS, RUNS, ADAPTERS, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW, Duration.ofDays(1));
    private static final RegulatoryObligations OBLIGATION_MACHINE = new RegulatoryObligations(OBLIGATIONS, ADAPTERS, DEFINITIONS, new JdbcAuditWriter(), IDS, NOW);

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

    @BeforeAll
    static void aChartInForce() {
        AccountingPeriodFixture.openJanuaryAndFebruary();
        PeriodCloseFixture.mappingInForce();
    }

    // ------------------------------------------------------------------ the ladder

    /** A month of the case's own, {@code OPEN}: the earliest not closed, whatever left it so, or the next one opened. Its predecessor is closed, so the ledger takes a line dated in it. */
    private static YearMonth openMonth() {
        Optional<AccountingPeriodStore.PeriodRow> unclosed = inScope(Actor.SYSTEM, uow -> AccountingPeriodFixture.STORE.oldestUnclosed(uow));
        if (unclosed.isPresent()) {
            assertThat(unclosed.get().status()).as("no case leaves a period past OPEN and not closed").isEqualTo(PeriodStatus.OPEN);
            return unclosed.get().month();
        }
        YearMonth month = inScope(Actor.SYSTEM, uow -> AccountingPeriodFixture.STORE.latest(uow)).orElseThrow().plusMonths(1);
        inScope(Actor.SYSTEM, uow -> periods(on(month.atDay(15).toString())).openNext(uow, Actor.SYSTEM, correlation()));
        return month;
    }

    private static ReportRunStore.RunRow issued(YearMonth month) {
        AccountingPeriodId period = id(month);
        Actor accountant = person();
        ReportRuns.Claimed claimed = TRANSACTIONS.inTransaction(uow -> REPORT_RUNS.claim(uow, SafeguardingPositionDefinition.CODE, period, RunParameters.none(), accountant, correlation()));
        ReportRunStore.RunRow done = claimed.mine() ? generator().generateAndComplete(claimed.run(), accountant) : generator().drive(claimed.run().id(), accountant);
        assertThat(done.status()).as("the statements answered").isEqualTo(RunStatus.FINAL);
        return done;
    }

    private static ReportRunGenerator generator() {
        return new ReportRunGenerator(REPORT_RUNS, new ReportGeneration(ReportGeneration.FROM_THE_SNAPSHOT, ReportRuns.Leases.STANDARD.generationTimeout(), NOW), SNAPSHOTS, TRANSACTIONS,
                new ReportRunMetrics(new SimpleMeterRegistry(), DEFINITIONS));
    }

    private static String contentOf(ReportRunStore.RunRow run) {
        return TRANSACTIONS.inTransaction(uow -> REPORT_RUNS.content(uow, run.id())).orElseThrow().text();
    }

    private static JsonNode bodyOf(ReportRunStore.RunRow run) throws Exception {
        return JSON.readTree(contentOf(run)).get("body");
    }

    private static JsonNode figures(JsonNode body, CurrencyCode currency) {
        for (JsonNode element : body.get("currencies")) {
            if (element.get("currency").asText().equals(currency.code())) {
                return element;
            }
        }
        throw new AssertionError("no element for " + currency.code());
    }

    private static long minor(JsonNode figures, String member) {
        return Long.parseLong(figures.get(member).get("minor").asText());
    }

    // ------------------------------------------------------------------ the form

    /** The documented configuration, the form's code the case's own: no obligation crosses cases. */
    private static RegulatoryObligationId activeForm() throws Exception {
        String runbook = Files.readString(RepositoryPaths.locate("docs/architecture/OPERATIONS_RUNBOOK.md"));
        int start = runbook.indexOf("```json\n", runbook.indexOf(HEADING));
        JsonNode documented = JSON.readTree(runbook.substring(start + "```json\n".length(), runbook.indexOf("\n```", start)));
        List<RegulatoryObligationConfiguration.Line> lines = new ArrayList<>();
        documented.get("lines").forEach(line -> lines.add(new RegulatoryObligationConfiguration.Line(line.get("code").asText(), line.get("label").asText(), line.get("source").asText(), line.get("figure").asText())));
        List<RegulatoryObligationConfiguration.Rule> rules = new ArrayList<>();
        documented.get("rules").forEach(rule -> rules.add(new RegulatoryObligationConfiguration.Rule(rule.get("code").asText(), RegulatoryRuleType.valueOf(rule.get("type").asText()), texts(rule.get("left")), texts(rule.get("right")))));
        RegulatoryObligationConfiguration configuration = new RegulatoryObligationConfiguration(List.of(new RegulatoryObligationConfiguration.Source(SafeguardingPositionDefinition.CODE, SafeguardingPositionDefinition.VERSION)), lines, rules);
        String form = "REF-SAFEGUARDING-" + FORMS.incrementAndGet();
        Actor proposer = person();
        RegulatoryObligations.Acted proposed = inScope(proposer, uow -> OBLIGATION_MACHINE.propose(uow, new RegulatoryObligations.Proposal(documented.get("jurisdictionCode").asText(), form, documented.get("formVersion").asInt(), documented.get("adapterName").asText(), configuration), REASON, proposer, correlation()));
        Actor activator = person();
        inScope(activator, uow -> OBLIGATION_MACHINE.activate(uow, proposed.id(), proposed.configurationSha256(), REASON, activator, correlation()));
        return proposed.id();
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(element -> texts.add(element.asText()));
        return texts;
    }

    private static RegulatorySubmissions.Acted drafted(RegulatoryObligationId obligation, YearMonth month) {
        Actor preparer = person();
        return inScope(preparer, uow -> MACHINE.draft(uow, new RegulatorySubmissions.Draft(obligation, id(month), Optional.empty(), REASON), preparer, correlation()));
    }

    private static RegulatorySubmissions.Acted validated(RegulatorySubmissions.Acted drafted) {
        Actor preparer = person();
        return inScope(preparer, uow -> MACHINE.validate(uow, drafted.id(), preparer, correlation()));
    }

    // ------------------------------------------------------------------ the entry's two cases

    @Test
    @DisplayName("lendingFundedIsSubtracted: each term equals this suite's raw sum through the last day, committed the real statement's true zero, and position = assets - liabilities + lendingFunded")
    void lendingFundedIsSubtracted() throws Exception {
        YearMonth month = openMonth();
        LocalDate day = month.atDay(10);
        LedgerAccount cash = operational(AccountPurpose.CASH_AT_BANK, EUR);
        LedgerAccount wallet = owned(AccountPurpose.CUSTOMER_WALLET, AccountType.LIABILITY, EUR);
        LedgerAccount loan = owned(AccountPurpose.LOAN_PRINCIPAL, AccountType.ASSET, EUR);
        LedgerAccount interest = owned(AccountPurpose.LOAN_INTEREST_DUE, AccountType.ASSET, EUR);
        LedgerAccount bnpl = owned(AccountPurpose.BNPL_PRINCIPAL, AccountType.ASSET, EUR);
        LedgerAccount merchant = owned(AccountPurpose.MERCHANT_PAYABLE, AccountType.LIABILITY, EUR);
        post(day, cash, wallet, 100_00, EUR);
        post(day, cash, operational(AccountPurpose.LENDING_CAPITAL, EUR), 50_00, EUR);
        post(day, loan, wallet, 30_00, EUR);
        post(day, bnpl, merchant, 13_00, EUR);
        post(day, interest, operational(AccountPurpose.LOAN_INTEREST_INCOME, EUR), 25, EUR);
        writeOff(day, day, loan, interest, 5_00, 25, EUR);
        post(day, operational(AccountPurpose.BNPL_WRITE_OFF_EXPENSE, EUR), bnpl, 1_00, EUR);
        PeriodCloseFixture.closed(month);

        JsonNode eur = figures(bodyOf(issued(month)), EUR);
        LocalDate lastDay = month.atEndOfMonth();

        assertThat(minor(eur, "cashAtBank")).isEqualTo(raw("'CASH_AT_BANK'", lastDay, EUR));
        assertThat(minor(eur, "customerWalletsNet")).isEqualTo(-raw("'CUSTOMER_WALLET'", lastDay, EUR));
        assertThat(minor(eur, "merchantPayablesNet")).isEqualTo(-raw("'MERCHANT_PAYABLE'", lastDay, EUR));
        assertThat(minor(eur, "principalOutstanding")).isEqualTo(raw("'LOAN_PRINCIPAL','LOAN_PRINCIPAL_DUE','BNPL_PRINCIPAL','BNPL_PRINCIPAL_DUE'", lastDay, EUR));
        assertThat(minor(eur, "principalWrittenOff")).isEqualTo(rawWrittenOff(lastDay, EUR)).isGreaterThanOrEqualTo(5_00 + 1_00);
        assertThat(minor(eur, "principalCommitted")).as("no loan was opened by a cutoff in 1999: the real statement's true zero").isZero();
        assertThat(eur.get("principalCommittedLoans").asText()).isEqualTo("0");
        long funded = minor(eur, "principalOutstanding") + minor(eur, "principalWrittenOff");
        assertThat(minor(eur, "lendingFunded")).isEqualTo(funded).isGreaterThanOrEqualTo(30_00 + 13_00);
        assertThat(minor(eur, "position")).isEqualTo(minor(eur, "assets") - minor(eur, "liabilities") + funded);
        assertThat(minor(eur, "position") - minor(eur, "positionBeforeLendingFunded")).as("subtracted").isEqualTo(funded);
        assertThat(minor(eur, "customerFundedLiabilities")).isEqualTo(minor(eur, "liabilities") - funded);
    }

    @Test
    @DisplayName("everyLineDrillsDown: each line its run, hash and path; the view's route is the run's, under ACCOUNTING_READ; a journal figure's GL account really lists its lines through the door the run names; committed names no such door")
    void everyLineDrillsDown() throws Exception {
        YearMonth month = openMonth();
        post(month.atDay(11), operational(AccountPurpose.CASH_AT_BANK, EUR), owned(AccountPurpose.CUSTOMER_WALLET, AccountType.LIABILITY, EUR), 77_00, EUR);
        PeriodCloseFixture.closed(month);
        ReportRunStore.RunRow run = issued(month);
        JsonNode body = bodyOf(run);

        RegulatorySubmissions.Acted drafted = drafted(activeForm(), month);
        RegulatorySubmissionStore.Row row = inScope(Actor.SYSTEM, uow -> STORE.find(uow, drafted.id())).orElseThrow();
        List<RegulatoryFormLine> lines = inScope(Actor.SYSTEM, uow -> STORE.lines(uow, drafted.id()));

        assertThat(lines).isNotEmpty().allSatisfy(line -> {
            assertThat(line.run()).isEqualTo(run.id());
            assertThat(line.runSha256()).isEqualTo(run.contentSha256().orElseThrow());
            assertThat(line.figure()).startsWith("body.currencies[currency=" + line.currency() + "].");
            RegulatorySubmissionDesk.RegulatoryDrillDownView route = RegulatorySubmissionDesk.drillDown(line, row);
            assertThat(route.route()).isEqualTo("GET /v1/operator/accounting/report-runs/" + run.id().value());
            assertThat(route.requiresPermission()).isEqualTo("ACCOUNTING_READ");
            assertThat(body.get("drillDown").get(line.figure().substring(line.figure().lastIndexOf('.') + 1)).asText()).as(line.code()).isNotBlank();
        });
        assertThat(body.get("drillDown").get("principalCommitted").asText()).startsWith("No journal line stands behind this figure").doesNotContain("/report-runs/");

        String glAccount = null;
        for (JsonNode candidate : body.get("rows")) {
            if (candidate.get("purpose").asText().equals("CASH_AT_BANK") && candidate.get("currency").asText().equals("EUR")) {
                glAccount = candidate.get("glAccount").asText();
            }
        }
        assertThat(glAccount).as("a CLOSED month's row names its account").isNotNull();
        ReportRunLinesDesk door = new ReportRunLinesDesk(REPORT_RUNS, new JdbcGlMappingStore(), new JdbcPeriodPins(), new GeneralLedger(new LedgerJournalReads()), GL_SNAPSHOTS, TRANSACTIONS, new JdbcAuditWriter(), AccountingFlowScope.NONE, IDS, NOW);
        String account = glAccount;
        try (SecurityContext.Scope who = SecurityContext.enter(person()); CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(correlation()))) {
            ReportRunLinesDesk.ReportRunLinesView listed = door.lines(run.id().value().toString(), account, month.toString(), "EUR", null, 500);
            assertThat(listed.exact()).as("a FINAL run's pages sum to its figure").isTrue();
            assertThat(listed.activity().lines()).as("the door finds the line this case posted").isNotEmpty();
        }
    }

    // ------------------------------------------------------------------ question 3

    @Test
    @DisplayName("a shortfall is reported in its own currency: a negative position, unfloored, every rule passed and the return VALIDATED, V010's line holding its minus sign")
    void aShortfallIsReportedInItsOwnCurrency() throws Exception {
        YearMonth month = openMonth();
        post(month.atDay(12), operational(AccountPurpose.CHARGEBACK_RECOVERABLE, GBP), owned(AccountPurpose.CUSTOMER_WALLET, AccountType.LIABILITY, GBP), 1_000_00, GBP);
        PeriodCloseFixture.closed(month);
        ReportRunStore.RunRow run = issued(month);
        JsonNode gbp = figures(bodyOf(run), GBP);
        assertThat(minor(gbp, "position")).isEqualTo(-1_000_00);
        assertThat(gbp.get("positionNegative").asBoolean()).isTrue();
        assertThat(gbp.get("positionBeforeLendingFundedNegative").asBoolean()).isTrue();

        RegulatorySubmissions.Acted validated = validated(drafted(activeForm(), month));

        assertThat(validated.status()).isEqualTo(SubmissionStatus.VALIDATED);
        assertThat(validated.validationPassed()).contains(true);
        List<RegulatoryFormLine> lines = inScope(Actor.SYSTEM, uow -> STORE.lines(uow, validated.id()));
        assertThat(lines).filteredOn(line -> line.code().equals("POSITION") && line.currency().equals("GBP"))
                .singleElement().satisfies(line -> assertThat(line.minor().longValueExact()).isEqualTo(-1_000_00));
    }

    // ------------------------------------------------------------------ I-1

    @Test
    @DisplayName("I-1, on the real statement: a wallet and a payable truly in debit: gross and debt each equal this suite's own per-account fold of the journal, each footing to the trial balance's net through the real form, and the debt no cover")
    void aWalletAndAPayableInDebtAreNoCover() throws Exception {
        YearMonth month = openMonth();
        LocalDate day = month.atDay(13);
        LedgerAccount cash = operational(AccountPurpose.CASH_AT_BANK, EUR);
        post(day, cash, owned(AccountPurpose.CUSTOMER_WALLET, AccountType.LIABILITY, EUR), 100_00, EUR);
        post(day, owned(AccountPurpose.CUSTOMER_WALLET, AccountType.LIABILITY, EUR), cash, 30_00, EUR);
        post(day, cash, owned(AccountPurpose.MERCHANT_PAYABLE, AccountType.LIABILITY, EUR), 50_00, EUR);
        post(day, owned(AccountPurpose.MERCHANT_PAYABLE, AccountType.LIABILITY, EUR), cash, 20_00, EUR);
        PeriodCloseFixture.closed(month);
        LocalDate lastDay = month.atEndOfMonth();

        JsonNode eur = figures(bodyOf(issued(month)), EUR);

        long[] wallets = rawPerAccount("CUSTOMER_WALLET", lastDay, EUR);
        long[] payables = rawPerAccount("MERCHANT_PAYABLE", lastDay, EUR);
        assertThat(minor(eur, "customerWallets")).as("this suite's own fold of every wallet in credit").isEqualTo(wallets[0]).isGreaterThanOrEqualTo(100_00);
        assertThat(minor(eur, "customerWalletDebt")).as("and of every wallet in debit").isEqualTo(wallets[1]).isGreaterThanOrEqualTo(30_00);
        assertThat(minor(eur, "merchantPayables")).isEqualTo(payables[0]).isGreaterThanOrEqualTo(50_00);
        assertThat(minor(eur, "merchantDebt")).isEqualTo(payables[1]).isGreaterThanOrEqualTo(20_00);
        assertThat(minor(eur, "customerWallets") - minor(eur, "customerWalletDebt")).isEqualTo(minor(eur, "customerWalletsNet")).isEqualTo(-raw("'CUSTOMER_WALLET'", lastDay, EUR));
        assertThat(minor(eur, "merchantPayables") - minor(eur, "merchantDebt")).isEqualTo(minor(eur, "merchantPayablesNet")).isEqualTo(-raw("'MERCHANT_PAYABLE'", lastDay, EUR));
        assertThat(eur.get("customerWalletDebtAccounts").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(minor(eur, "liabilities")).as("gross, the debt no cover").isGreaterThanOrEqualTo(minor(eur, "customerWallets") + minor(eur, "merchantPayables"));

        RegulatorySubmissions.Acted validated = validated(drafted(activeForm(), month));
        assertThat(validated.status()).as("the footings hold on real reads").isEqualTo(SubmissionStatus.VALIDATED);
    }

    /** This suite's own fold, in Java, of each account's balance by posting date: {in credit, in debit}, both positive. No statement of the definition's. */
    private static long[] rawPerAccount(String purpose, LocalDate through, CurrencyCode currency) throws SQLException {
        String sql = "SELECT line.ledger_account_id, line.direction, line.amount_minor FROM ledger.journal_line line"
                + " JOIN ledger.journal_entry entry ON entry.id = line.entry_id JOIN ledger.ledger_account account ON account.id = line.ledger_account_id"
                + " WHERE account.purpose = ? AND line.currency = ? AND entry.posting_date <= ?";
        java.util.Map<java.util.UUID, Long> balances = new java.util.HashMap<>();
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement read = migrator.prepareStatement(sql)) {
            read.setString(1, purpose);
            read.setString(2, currency.code());
            read.setObject(3, through);
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    long amount = row.getLong("amount_minor");
                    balances.merge(row.getObject("ledger_account_id", java.util.UUID.class), "DEBIT".equals(row.getString("direction")) ? amount : -amount, Long::sum);
                }
            }
        }
        long owed = balances.values().stream().filter(balance -> balance < 0).mapToLong(balance -> -balance).sum();
        long debt = balances.values().stream().filter(balance -> balance > 0).mapToLong(Long::longValue).sum();
        return new long[] {owed, debt};
    }

    // ------------------------------------------------------------------ as of the cutoff

    @Test
    @DisplayName("the journal's reads are by posting date: a write-off valued in the month and posted after it has not happened as of the month, its principal still outstanding and nothing more written off, on the real statements, and is written off by the next month's")
    void aBackDatedWriteOffCountsByItsPostingDate() throws Exception {
        YearMonth month = openMonth();
        LedgerAccount loan = owned(AccountPurpose.LOAN_PRINCIPAL, AccountType.ASSET, EUR);
        LedgerAccount interest = owned(AccountPurpose.LOAN_INTEREST_DUE, AccountType.ASSET, EUR);
        post(month.atDay(5), loan, owned(AccountPurpose.CUSTOMER_WALLET, AccountType.LIABILITY, EUR), 40_00, EUR);
        LocalDate lastDay = month.atEndOfMonth();
        long before = sum(SNAPSHOTS.read(snapshot -> LENDING.writtenOff(snapshot, lastDay, Optional.empty())), EUR);

        writeOff(lastDay.plusDays(3), month.atDay(20), loan, interest, 40_00, 0, EUR);

        assertThat(sum(SNAPSHOTS.read(snapshot -> LENDING.writtenOff(snapshot, lastDay, Optional.empty())), EUR)).as("posted after the cutoff").isEqualTo(before);
        assertThat(sum(SNAPSHOTS.read(snapshot -> LENDING.writtenOff(snapshot, lastDay.plusDays(3), Optional.empty())), EUR)).isEqualTo(before + 40_00);
        PeriodCloseFixture.closed(month);
        JsonNode eur = figures(bodyOf(issued(month)), EUR);
        assertThat(minor(eur, "principalWrittenOff")).isEqualTo(rawWrittenOff(lastDay, EUR));
        assertThat(minor(eur, "principalOutstanding")).isEqualTo(raw("'LOAN_PRINCIPAL','LOAN_PRINCIPAL_DUE','BNPL_PRINCIPAL','BNPL_PRINCIPAL_DUE'", lastDay, EUR));
    }

    @Test
    @DisplayName("INV-ACC-04: a FINAL regenerated after a later line, a later write-off and a later month is IDENTICAL by the machine's own comparison")
    void aFinalRegeneratesIdenticalAfterLaterPostings() throws Exception {
        YearMonth month = openMonth();
        LedgerAccount loan = owned(AccountPurpose.LOAN_PRINCIPAL, AccountType.ASSET, EUR);
        LedgerAccount wallet = owned(AccountPurpose.CUSTOMER_WALLET, AccountType.LIABILITY, EUR);
        post(month.atDay(6), loan, wallet, 60_00, EUR);
        PeriodCloseFixture.closed(month);
        ReportRunStore.RunRow run = issued(month);

        LocalDate after = month.plusMonths(1).atDay(4);
        post(after, loan, wallet, 7_00, EUR);
        writeOff(after, after, loan, owned(AccountPurpose.LOAN_INTEREST_DUE, AccountType.ASSET, EUR), 60_00, 0, EUR);

        ReportRunGenerator.Compared compared = generator().regenerate(run.id());
        assertThat(compared.identical()).isTrue();
        assertThat(compared.regeneratedSha256()).isEqualTo(run.contentSha256().orElseThrow());
    }

    @Test
    @DisplayName("a reopened month's new revision: the first FINAL superseded, its bytes kept; the new FINAL holds the line posted while it stood open, the position moved by exactly that")
    void aReopenedMonthsNewRevision() throws Exception {
        YearMonth month = openMonth();
        LedgerAccount wallet = owned(AccountPurpose.CUSTOMER_WALLET, AccountType.LIABILITY, EUR);
        post(month.atDay(7), operational(AccountPurpose.CASH_AT_BANK, EUR), wallet, 90_00, EUR);
        PeriodCloseFixture.closed(month);
        ReportRunStore.RunRow first = issued(month);
        String firstBytes = contentOf(first);
        long firstPosition = minor(figures(bodyOf(first), EUR), "position");

        AccountingPeriodId period = id(month);
        Actor proposer = person();
        inScope(proposer, uow -> PeriodCloseFixture.MACHINE.proposeReopen(uow, period, proposer, PeriodCloseFixture.posted(uow, period), "the suite reopens it", correlation()));
        Actor approver = person();
        inScope(approver, uow -> PeriodCloseFixture.MACHINE.approveReopen(uow, period, approver, PeriodCloseFixture.posted(uow, period), "the suite reopens it", correlation()));

        post(month.atEndOfMonth(), wallet, operational(AccountPurpose.FEE_REVENUE, EUR), 2_00, EUR);
        PeriodCloseFixture.closed(month);
        ReportRunStore.RunRow second = issued(month);

        assertThat(inScope(Actor.SYSTEM, uow -> REPORT_RUNS.find(uow, first.id())).orElseThrow().status()).isEqualTo(RunStatus.SUPERSEDED);
        assertThat(contentOf(first)).isEqualTo(firstBytes);
        assertThat(second.periodRevision()).isEqualTo(first.periodRevision() + 1);
        assertThat(minor(figures(bodyOf(second), EUR), "position") - firstPosition).isEqualTo(2_00);
    }

    // ------------------------------------------------------------------ ten instances

    @Test
    @DisplayName("ten instances race to issue one month's FINAL: one run, FINAL, its stored hash its content's, and regenerating it identical")
    void tenInstancesIssueOneFinal() throws Exception {
        YearMonth month = openMonth();
        post(month.atDay(8), operational(AccountPurpose.CASH_AT_BANK, EUR), owned(AccountPurpose.CUSTOMER_WALLET, AccountType.LIABILITY, EUR), 11_00, EUR);
        PeriodCloseFixture.closed(month);
        AccountingPeriodId period = id(month);
        ExecutorService pool = Executors.newFixedThreadPool(INSTANCES);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ReportRunStore.RunRow>> asked = new ArrayList<>();
            for (int i = 0; i < INSTANCES; i++) {
                Callable<ReportRunStore.RunRow> instance = () -> {
                    start.await();
                    Actor accountant = person();
                    ReportRuns.Claimed claimed = TRANSACTIONS.inTransaction(uow -> REPORT_RUNS.claim(uow, SafeguardingPositionDefinition.CODE, period, RunParameters.none(), accountant, correlation()));
                    return claimed.mine() ? generator().generateAndComplete(claimed.run(), accountant) : generator().drive(claimed.run().id(), accountant);
                };
                asked.add(pool.submit(instance));
            }
            start.countDown();
            Set<com.finapp.accounting.ReportRunId> runs = new java.util.HashSet<>();
            for (Future<ReportRunStore.RunRow> one : asked) {
                runs.add(one.get(120, TimeUnit.SECONDS).id());
            }
            assertThat(runs).hasSize(1);
            ReportRunStore.RunRow run = inScope(Actor.SYSTEM, uow -> REPORT_RUNS.find(uow, runs.iterator().next())).orElseThrow();
            assertThat(run.status()).isEqualTo(RunStatus.FINAL);
            assertThat(contentOf(run)).isNotBlank();
            assertThat(com.finapp.accounting.reporting.CanonicalJson.sha256(contentOf(run))).isEqualTo(run.contentSha256().orElseThrow());
            assertThat(generator().regenerate(run.id()).identical()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ the raw sums

    private static long sum(List<LendingFundedAsOfCutoff.WrittenOff> writtenOff, CurrencyCode currency) {
        return writtenOff.stream().filter(held -> held.currency().equals(currency.code())).map(LendingFundedAsOfCutoff.WrittenOff::amount).reduce(BigDecimal.ZERO, BigDecimal::add).movePointRight(2).longValueExact();
    }

    /** This suite's own debit-positive sum of a set of purposes through a day, by posting date: no statement of the definition's. */
    private static long raw(String purposes, LocalDate through, CurrencyCode currency) throws SQLException {
        return single("SELECT COALESCE(SUM(CASE WHEN line.direction = 'DEBIT' THEN line.amount_minor ELSE -line.amount_minor END), 0)"
                + " FROM ledger.journal_line line JOIN ledger.journal_entry entry ON entry.id = line.entry_id JOIN ledger.ledger_account account ON account.id = line.ledger_account_id"
                + " WHERE account.purpose IN (" + purposes + ") AND line.currency = ? AND entry.posting_date <= ?", currency, through);
    }

    /** This suite's own credit-positive sum of the principal purposes of the entries beside a write-off expense. */
    private static long rawWrittenOff(LocalDate through, CurrencyCode currency) throws SQLException {
        return single("SELECT COALESCE(SUM(CASE WHEN line.direction = 'CREDIT' THEN line.amount_minor ELSE -line.amount_minor END), 0)"
                + " FROM ledger.journal_line line JOIN ledger.journal_entry entry ON entry.id = line.entry_id JOIN ledger.ledger_account account ON account.id = line.ledger_account_id"
                + " WHERE account.purpose IN ('LOAN_PRINCIPAL','LOAN_PRINCIPAL_DUE','BNPL_PRINCIPAL','BNPL_PRINCIPAL_DUE') AND line.currency = ? AND entry.posting_date <= ?"
                + " AND line.entry_id IN (SELECT beside.entry_id FROM ledger.journal_line beside JOIN ledger.ledger_account expense ON expense.id = beside.ledger_account_id WHERE expense.purpose IN ('LOAN_WRITE_OFF_EXPENSE','BNPL_WRITE_OFF_EXPENSE'))", currency, through);
    }

    private static long single(String sql, CurrencyCode currency, LocalDate through) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement read = migrator.prepareStatement(sql)) {
            read.setString(1, currency.code());
            read.setObject(2, through);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getBigDecimal(1).longValueExact();
            }
        }
    }

    // ------------------------------------------------------------------ the journal

    private static LedgerAccount operational(AccountPurpose purpose, CurrencyCode currency) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return ACCOUNTS.findOperational(app, purpose, currency).orElseThrow();
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

    private static void post(LocalDate day, LedgerAccount debit, LedgerAccount credit, long minor, CurrencyCode currency) {
        posting(new PostingCommand("safeguarding-" + IDS.next(), day, day, "safeguarding-suite",
                List.of(new JournalLine(debit.id(), Direction.DEBIT, Money.ofMinorUnits(minor, currency)), new JournalLine(credit.id(), Direction.CREDIT, Money.ofMinorUnits(minor, currency)))));
    }

    /** Rule 14's shape: the expense debited, the principal and the interest credited, posted one day and valued another. */
    private static void writeOff(LocalDate posted, LocalDate valued, LedgerAccount principal, LedgerAccount interest, long principalMinor, long interestMinor, CurrencyCode currency) throws SQLException {
        List<JournalLine> lines = new ArrayList<>(List.of(
                new JournalLine(operational(AccountPurpose.LOAN_WRITE_OFF_EXPENSE, currency).id(), Direction.DEBIT, Money.ofMinorUnits(principalMinor + interestMinor, currency)),
                new JournalLine(principal.id(), Direction.CREDIT, Money.ofMinorUnits(principalMinor, currency))));
        if (interestMinor > 0) {
            lines.add(new JournalLine(interest.id(), Direction.CREDIT, Money.ofMinorUnits(interestMinor, currency)));
        }
        posting(new PostingCommand("safeguarding-write-off-" + IDS.next(), posted, valued, "safeguarding-suite", lines));
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
