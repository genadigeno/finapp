package com.finapp.app.accounting;

import static com.finapp.app.accounting.AccountingPeriodFixture.IDS;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingBreaks;
import com.finapp.accounting.AccountingChecks;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.AccrualCompleteness;
import com.finapp.accounting.CheckVerdict;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseCheckStatus;
import com.finapp.accounting.CloseChecks;
import com.finapp.accounting.CloseRunId;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.GlMappingAdministration;
import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.GlPins;
import com.finapp.accounting.JdbcAccountingBreakStore;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.JdbcPeriodPins;
import com.finapp.accounting.JdbcReportRunStore;
import com.finapp.accounting.JdbcValuationRateSetStore;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.JournalShapes;
import com.finapp.accounting.PeriodCloses;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.RuleConformance;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.SourceProofs;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.ValuationRateSetStore;
import com.finapp.accounting.ValuationRateSets;
import com.finapp.accounting.ValuationRates;
import com.finapp.accounting.ValuationStaleness;
import com.finapp.accounting.reporting.BalanceSheetDefinition;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.accounting.reporting.IncomeStatementDefinition;
import com.finapp.accounting.reporting.ReadOnlySnapshot;
import com.finapp.accounting.reporting.ReportDefinition;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.ReportInputs;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.StatementNotes;
import com.finapp.accounting.reporting.TrialBalance;
import com.finapp.accounting.reporting.TrialBalanceDefinition;
import com.finapp.app.accounting.AccountingBatteryWorld.Chart;
import com.finapp.app.accounting.AccountingBatteryWorld.ChartRow;
import com.finapp.app.accounting.AccountingBatteryWorld.Entry;
import com.finapp.app.accounting.AccountingBatteryWorld.Line;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PeriodClosedException;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.JdbcReportReadings;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The accounting battery against a live PostgreSQL (`P14-TST-002`; {@code PHASE_14_PLAN.md} section 14.3; {@code INV-ACC-01},
 * {@code -02}, {@code -04}, {@code -06}, {@code -11}, {@code -12}): {@link AccountingBatteryWorld}'s seeded journal - the very one
 * {@code AccountingBatteryHermeticTest} proves in two JVMs, its fingerprint pinned there - posted through the real {@code
 * PostingService} on the real stores, the year 2001 driven through the real doors, and every report's facts held to {@link
 * AccountingBatteryOracle}'s, computed from the generated lines alone.
 *
 * <p><strong>What {@link #theYearIsPostedClosedAndReported} does, once, before any case</strong> - in this order, because the doors
 * require it:
 * <ol>
 *   <li>twelve periods born by {@code AccountingPeriods.openNext} on a clock in each month in turn (periods are contiguous calendar
 *       months; the first one born is the clock's);
 *   <li>the reference version proposed and activated by two persons from January, and the restated one from July - the July
 *       activation retiring the first, whose months it never reaches (never retroactive: no period is locked yet);
 *   <li>the accounts: the seeded operational ones per purpose and currency, four customers' wallets per currency created;
 *   <li>the journal posted in batches of 50 per transaction ({@code PostingService} joins the caller's) - every entry before any
 *       lock, so the ledger's cutoff refuses none; then every 97th entry's command sent again after its first committed, each in a
 *       transaction and a correlation of its own, a client's retry the idempotency record answers;
 *   <li>then each month in order: locked (the ledger's cutoff advanced to its end under advisory key {@code (14, 'cutoff')}
 *       exclusive, the month's version pinned under {@code (14, 'mapping')} shared - no poster holds either, so neither waits; the
 *       previous month is {@code CLOSED}, which a lock requires); its valuation set captured from the world's rates and approved by
 *       another person; its close run started and its checks concluded - the ledger trial balance, GL = ledger, rule conformance
 *       (under the battery's own register: under production's, its kinds are no poster's and unregistered) and the approved set by
 *       the <strong>real judges</strong>, the open breaks in the run's own conclusion, and only source completeness, accruals,
 *       projections and the reconciliation disclosure by the suite's verdict, none of them this journal's to answer
 *       ({@link #STUB}); proposed by one person and approved by another; and its three FINAL runs - {@code TRIAL_BALANCE}, {@code
 *       BALANCE_SHEET}, {@code INCOME_STATEMENT} - claimed and generated through the real claim and generator, translated at the
 *       month's approved set;
 *   <li>every fact read: each run's definition again on a {@code REPEATABLE READ READ ONLY} snapshot through {@link
 *       ReadOnlySnapshot}, the GL through {@code LedgerJournalReads} under {@code GlPins}, conformance through {@code
 *       LedgerJournalShapes} under the battery's register.
 * </ol>
 * Every case only reads (the cutoff's case is refused and rolled back), so JUnit's method order - deterministic and unspecified -
 * cannot change a verdict.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): it opens the year 2001 as the database's first periods, closes
 * all twelve for good, activates two mapping versions and commits some 22,000 journal lines - none of which a shared container
 * could survive. <strong>Written and owed</strong> (owner decision C5: the database tiers run once, at {@code P14-DOC-001}'s pass).
 */
@Tag("database")
@Tag("own-container") // its own JVM and database: ownContainerDatabaseTest, the javadoc says why
@DisplayName("P14-TST-002 - the accounting battery against the database: 10,000 entries posted, twelve months closed, every"
        + " FINAL report the oracle's")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class AccountingBatteryTest {

    private static final long SEED = AccountingBatteryWorld.SEED;
    private static final int BATCH = 50;
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final JdbcLedgerAccountStore ACCOUNTS = new JdbcLedgerAccountStore();
    private static final TrialBalanceReads READS = new LedgerTrialBalanceReads(new JdbcGlMappingStore());
    private static final PinnedValuationRates RATES = new PinnedValuationRates(new JdbcValuationRateSetStore());
    private static final StatementNotes NOTES = new StatementNotes(new ReconciliationMonthReads(new JdbcReportReadings(),
            AccountingPeriodFixture.DECLARED_SOURCES), new ReconciliationAsOfCutoffReads(new JdbcReportReadings()),
            new LedgerPriorPeriodCorrections(), new AccountingCloseRecordReads());
    private static final TrialBalanceDefinition TRIAL_BALANCE = new TrialBalanceDefinition(READS, RATES);
    private static final BalanceSheetDefinition SHEET = new BalanceSheetDefinition(READS, new LedgerCounterpartyPositionReads(), RATES,
            NOTES);
    private static final IncomeStatementDefinition INCOME = new IncomeStatementDefinition(READS, new LedgerCounterpartyPositionReads(),
            RATES, NOTES);
    private static final ReportDefinitions DEFINITIONS = ReportDefinitions.of(TRIAL_BALANCE, SHEET, INCOME);
    private static final AccountingTransactions TRANSACTIONS = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            return inScope(Actor.SYSTEM, work);
        }
    };
    private static final JdbcReportSnapshots SNAPSHOTS = new JdbcReportSnapshots(DatabaseRoles::application,
            ReportRuns.Leases.STANDARD.generationTimeout());
    private static final GlSnapshots GL_SNAPSHOTS = new GlSnapshots(DatabaseRoles::application, Duration.ofSeconds(30));
    private static final GeneralLedger LEDGER = new GeneralLedger(new LedgerJournalReads());
    private static final GlPins PINS = new GlPins(new JdbcGlMappingStore(), new JdbcPeriodPins());

    private static List<Entry> journal;
    /** Each line's account, by {@code purpose|owner|currency}. */
    private static final Map<String, LedgerAccountId> ACCOUNT_OF = new HashMap<>();
    /** A wallet's owner reference, back to the world's owner index. */
    private static final Map<UUID, Integer> OWNER_OF = new HashMap<>();
    /** Each posted entry's id, back to the world's index. */
    private static final Map<UUID, Integer> INDEX_OF = new HashMap<>();
    /** The FINAL runs, by {@code month CODE}. */
    private static final Map<String, ReportRunStore.RunRow> RUNS = new LinkedHashMap<>();
    private static int replays;
    private static SortedMap<String, String> facts;
    private static SortedMap<String, String> oracle;

    @BeforeAll
    static void theYearIsPostedClosedAndReported() throws SQLException {
        journal = AccountingBatteryWorld.generate(SEED, AccountingBatteryWorld.ENTRIES);
        for (YearMonth month : AccountingBatteryWorld.MONTHS) {
            inScope(Actor.SYSTEM, uow -> AccountingPeriodFixture.periods(on(month.atDay(15).toString())).openNext(uow, Actor.SYSTEM,
                    correlation()));
        }
        activate(AccountingBatteryBooks.content(Chart.BASE, 1));
        activate(AccountingBatteryBooks.content(Chart.BASE, 2));
        accounts();
        post();
        for (YearMonth month : AccountingBatteryWorld.MONTHS) {
            close(month);
            report(month);
        }
        facts = read();
        oracle = AccountingBatteryOracle.facts(journal, Chart.BASE, month -> AccountingBatteryWorld.rates(SEED, month));
    }

    // ------------------------------------------------------------------ the cases

    @Test
    @DisplayName("the census: the database holds exactly the generated journal - 10,000 entries, every line, read back to the"
            + " fingerprint the hermetic half pins - and every replay was one effect")
    void theDatabaseHoldsTheGeneratedJournal() throws SQLException {
        assertThat(AccountingBatteryWorld.ENTRIES).isGreaterThanOrEqualTo(10_000);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry")).isEqualTo((long) journal.size());
        assertThat(count("SELECT count(*) FROM ledger.journal_line"))
                .isEqualTo(journal.stream().mapToLong(entry -> entry.lines().size()).sum());
        assertThat(replays).isEqualTo((int) journal.stream().filter(entry -> entry.index() % AccountingBatteryWorld.REPLAYED == 0)
                .count());
        String readBack = AccountingBatteryWorld.fingerprint(readBack());
        assertThat(readBack).isEqualTo(AccountingBatteryWorld.fingerprint(journal)).isEqualTo(AccountingBatteryHermeticTest.FINGERPRINT);
    }

    @Test
    @DisplayName("the doors: every month CLOSED at revision 1 under its pin - the first version through June, the restated one"
            + " from July - with an approved valuation set, and the ledger's cutoff at the year's end")
    void everyMonthClosedUnderItsPin() {
        for (YearMonth month : AccountingBatteryWorld.MONTHS) {
            AccountingPeriodStore.PeriodRow period = row(month);
            assertThat(period.status()).as("%s", month).isEqualTo(PeriodStatus.CLOSED);
            assertThat(period.revision()).as("%s", month).isEqualTo(1);
            assertThat(period.pinned().orElseThrow().version()).as("%s", month).isEqualTo(AccountingBatteryWorld.versionOf(month));
            Optional<ValuationRateSetStore.SetRow> approved = inScope(Actor.SYSTEM,
                    uow -> PeriodCloseFixture.SETS.approvedFor(uow, period.id()));
            assertThat(approved).as("%s", month).isPresent();
        }
        assertThat(AccountingPeriodFixture.closedThrough()).contains(LocalDate.of(2001, 12, 31));
    }

    @Test
    @DisplayName("the closes' judges: in every month the ledger trial balance, GL = ledger, rule conformance under the battery's"
            + " register and the approved set were concluded PASSED by the real judges, not the suite's verdict")
    void theRealJudgesPassedWhatTheyJudged() {
        for (YearMonth month : AccountingBatteryWorld.MONTHS) {
            for (CloseCheckCode check : JUDGED) {
                CheckVerdict verdict = VERDICTS.get(month + " " + check);
                assertThat(verdict).as("%s %s judged", month, check).isNotNull();
                assertThat(verdict.status()).as("%s %s: %s", month, check, verdict.failures()).isEqualTo(CloseCheckStatus.PASSED);
                assertThat(verdict.judgedBy()).as("%s %s", month, check).isNotEqualTo(STUB);
            }
        }
    }

    @Test
    @DisplayName("INV-ACC-01, -06: every month's FINAL trial balance is the oracle's - rows, currency proofs, verdict - and its"
            + " translation")
    void theTrialBalanceIsTheOracles() {
        assertAgree("TB|", "TBT|");
    }

    @Test
    @DisplayName("INV-ACC-02, -06: the GL through the real read model is the oracle's, nothing unmapped, GL = ledger per currency")
    void theGeneralLedgerIsTheOracles() {
        assertAgree("GL|");
    }

    @Test
    @DisplayName("INV-ACC-06, -11: every month's FINAL balance sheet and income statement are the oracle's - the equation per"
            + " currency, every section and row, the result, net realised FX - and so is their translation, balanced")
    void theStatementsAreTheOracles() {
        assertAgree("BS|", "IS|", "BST|", "IST|");
    }

    @Test
    @DisplayName("INV-ACC-12: every entry read back from the journal conforms to its kind's registered shape, as the oracle judges it")
    void everyEntryConformsToItsRule() {
        assertAgree("RC|");
    }

    @Test
    @DisplayName("INV-ACC-04: every one of the 36 FINAL runs is FINAL and regenerates IDENTICAL - the stored bytes' hash the"
            + " regeneration's")
    void everyFinalRunRegeneratesIdentical() {
        assertThat(RUNS).hasSize(36);
        for (Map.Entry<String, ReportRunStore.RunRow> run : RUNS.entrySet()) {
            assertThat(run.getValue().status()).as(run.getKey()).isEqualTo(RunStatus.FINAL);
            assertThat(run.getValue().basis()).as(run.getKey()).isEqualTo(RunBasis.FINAL);
            ReportRunGenerator.Compared compared = generator().regenerate(run.getValue().id());
            assertThat(compared.identical()).as(run.getKey()).isTrue();
            assertThat(compared.regeneratedSha256()).as(run.getKey()).isEqualTo(run.getValue().contentSha256().orElseThrow());
        }
    }

    @Test
    @DisplayName("the cutoff: an entry dated in December, after the year closed, is refused by the ledger and moves nothing")
    void aPostingDatedInTheClosedYearIsRefused() throws SQLException {
        long before = count("SELECT count(*) FROM ledger.journal_entry");
        Entry late = new Entry(-1, "battery.fee:late-" + IDS.next(), "battery.fee:", LocalDate.of(2001, 12, 15),
                LocalDate.of(2001, 12, 15), "EUR", 2, List.of(new Line(AccountingBatteryWorld.WALLET, "CUSTOMER", 0, true, 100),
                        new Line(AccountingBatteryWorld.FEES, "OPERATIONAL", 0, false, 100)));
        assertThatThrownBy(() -> {
            try (Connection app = DatabaseRoles.application();
                    SecurityContext.Scope actor = SecurityContext.enterSystem();
                    CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
                app.setAutoCommit(false);
                postings().post(app, command(late));
                app.commit();
            }
        }).isInstanceOf(PeriodClosedException.class);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry")).isEqualTo(before);
    }

    // ------------------------------------------------------------------ the year

    /** {@code version} proposed by one person and activated by another. */
    private static void activate(GlMappingVersion version) {
        GlMappingAdministration administration = new GlMappingAdministration(new JdbcGlMappingStore(), new LedgerPurposeChart(),
                new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW);
        Actor proposer = person();
        GlMappingAdministration.Proposed proposed = inScope(proposer, uow -> administration.propose(uow, version,
                "the battery's chart", proposer, correlation()));
        Actor approver = person();
        inScope(approver, uow -> administration.activate(uow, proposed.id(), approver, "the battery's chart", correlation()));
    }

    /** The seeded operational account of each purpose and currency; four wallets per currency, opened. */
    private static void accounts() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            for (AccountingBatteryWorld.Unit unit : AccountingBatteryWorld.UNITS) {
                CurrencyCode currency = CurrencyCode.of(unit.currency());
                for (ChartRow row : AccountingBatteryWorld.CHART) {
                    AccountPurpose purpose = AccountPurpose.valueOf(row.purpose());
                    if (row.purpose().equals(AccountingBatteryWorld.WALLET)) {
                        for (int owner = 0; owner < AccountingBatteryWorld.WALLETS; owner++) {
                            UUID reference = IDS.next();
                            LedgerAccount wallet = ACCOUNTS.createOrConverge(app, LedgerAccount.owned(IDS, NOW, AccountType.LIABILITY,
                                    purpose, currency, reference)).account();
                            ACCOUNT_OF.put(row.purpose() + "|" + owner + "|" + unit.currency(), wallet.id());
                            OWNER_OF.put(reference, owner);
                        }
                    } else {
                        ACCOUNT_OF.put(row.purpose() + "|0|" + unit.currency(),
                                ACCOUNTS.findOperational(app, purpose, currency).orElseThrow().id());
                    }
                }
            }
            app.commit();
        }
    }

    /**
     * The journal through the real posting service, {@link #BATCH} entries per transaction; then every 97th entry's command sent
     * again, after its first has committed, each in a transaction of its own and with a correlation of its own - the client's
     * retry the idempotency record answers, never a second effect.
     */
    private static void post() throws SQLException {
        for (int from = 0; from < journal.size(); from += BATCH) {
            List<Entry> batch = journal.subList(from, Math.min(from + BATCH, journal.size()));
            try (Connection app = DatabaseRoles.application();
                    SecurityContext.Scope actor = SecurityContext.enterSystem();
                    CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
                app.setAutoCommit(false);
                PostingService postings = postings();
                for (Entry entry : batch) {
                    INDEX_OF.put(postings.post(app, command(entry)).entryId().value(), entry.index());
                }
                app.commit();
            }
        }
        for (Entry entry : journal) {
            if (entry.index() % AccountingBatteryWorld.REPLAYED != 0) {
                continue;
            }
            try (Connection app = DatabaseRoles.application();
                    SecurityContext.Scope actor = SecurityContext.enterSystem();
                    CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
                app.setAutoCommit(false);
                PostingResult again = postings().post(app, command(entry));
                app.commit();
                replays += again.replayed() && Integer.valueOf(entry.index()).equals(INDEX_OF.get(again.entryId().value())) ? 1 : 0;
            }
        }
    }

    private static PostingService postings() {
        return new PostingService(new IdempotentExecutor(new JdbcIdempotencyRecordStore(), NOW, Duration.ofDays(1),
                Duration.ofMinutes(5)), new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(),
                new JdbcBalanceProjection(), IDS, NOW, PostingObserver.NONE);
    }

    private static PostingCommand command(Entry entry) {
        CurrencyCode currency = CurrencyCode.of(entry.currency());
        List<JournalLine> lines = new ArrayList<>();
        for (Line line : entry.lines()) {
            lines.add(new JournalLine(ACCOUNT_OF.get(line.purpose() + "|" + line.owner() + "|" + entry.currency()),
                    line.debit() ? Direction.DEBIT : Direction.CREDIT, Money.ofMinorUnits(line.minor(), currency)));
        }
        return new PostingCommand(entry.key(), entry.postingDate(), entry.valueDate(), "accounting-battery", lines);
    }

    /** {@code month} locked, its set captured from the world's rates and approved, its run ready, proposed and approved. */
    private static void close(YearMonth month) {
        AccountingPeriodId period = PeriodCloseFixture.locked(month);
        ValuationRateSets valuation = new ValuationRateSets(PeriodCloseFixture.SETS, AccountingPeriodFixture.STORE, new WorldRates(),
                ValuationStaleness.standard(), new JdbcAuditWriter(), IDS, NOW);
        Actor capturer = person();
        ValuationRateSets.Valued captured = inScope(capturer, uow -> valuation.capture(uow, period, capturer, null, correlation()));
        Actor approver = person();
        inScope(approver, uow -> valuation.approve(uow, captured.id(), approver, "the battery's rates", correlation()));
        CloseRunId run = PeriodCloseFixture.started(period);
        conclude(month);
        PeriodCloses.Acted proposed = inScope(ACCOUNTANT, uow -> MACHINE.propose(uow, period, run, List.of(), ACCOUNTANT, REASON,
                correlation()));
        inScope(CONTROLLER, uow -> MACHINE.approve(uow, period, proposed.proposal().orElseThrow(), CONTROLLER, REASON, correlation()));
    }

    /**
     * The checks the real judges conclude: the ledger trial balance (a FINAL {@code TRIAL_BALANCE} run's verdict), GL = ledger
     * and rule conformance (the real {@code AccountingChecks} over {@code LedgerJournalShapes} and the GL door - conformance under
     * the battery's own register, its kinds being no production poster's), the approved set, and the open breaks (concluded in
     * the run's own conclusion).
     */
    private static final Set<CloseCheckCode> JUDGED = EnumSet.of(CloseCheckCode.LEDGER_TRIAL_BALANCE, CloseCheckCode.GL_EQUALS_LEDGER,
            CloseCheckCode.RULE_CONFORMANCE, CloseCheckCode.VALUATION_SET_APPROVED);

    /**
     * The checks concluded by the suite's own verdict, as {@code PeriodCloseFixture} concludes them, each for a reason: source
     * completeness asks the products' sources and their expectations, of which this journal has none; accruals ask the loan and
     * BNPL books; projection verification the products' proofs; the reconciliation disclosure a month's reconciliation runs - none
     * of them this journal's to answer, and each judged by its own suite ({@code CloseRunDatabaseTest}, {@code
     * AccountingSourceProofsDatabaseTest}).
     *
     * <p>A name in {@code CheckVerdict.JUDGED_BY}'s grammar ({@code accounting V007}'s {@code close_check_judged_by_a_version}):
     * the phase's database pass found it written {@code battery:the-suite's-verdict}, whose apostrophe the grammar refuses, so
     * the battery stopped at its first stub verdict before any fact was compared ({@code P14-DOC-001}, the battery's wiring).
     */
    static final String STUB = "battery:the-suites-verdict";

    /** What each judged check concluded, by {@code month CHECK}. */
    private static final Map<String, CheckVerdict> VERDICTS = new TreeMap<>();

    /**
     * Every check of {@code month}'s run concluded: the {@link #JUDGED} by the real judges, the rest by the suite - each taken as the
     * check leg takes it ({@code PeriodCloseFixture.nextClaim}: a claim answering empty while the database's clock is behind an attempt's
     * birth is not "nothing left", `P14-DOC-001`).
     */
    private static void conclude(YearMonth month) {
        CloseChecks judges = judges();
        while (true) {
            Optional<CloseRuns.ClaimedCheck> held = PeriodCloseFixture.nextClaim();
            if (held.isEmpty()) {
                return;
            }
            CloseRuns.ClaimedCheck claimed = held.get();
            if (claimed.check() == CloseCheckCode.OPEN_ACCOUNTING_BREAKS) {
                inScope(Actor.SYSTEM, uow -> PeriodCloseFixture.RUNS.concludeOpenBreaks(uow, claimed));
                continue;
            }
            CheckVerdict verdict;
            if (JUDGED.contains(claimed.check())) {
                verdict = judges.judge(claimed).orElseThrow(() -> new AssertionError(month + " " + claimed.check()
                        + ": the judge answered in progress"));
                VERDICTS.put(month + " " + claimed.check(), verdict);
            } else {
                verdict = CheckVerdict.passed(claimed.check(), STUB, CanonicalJson.object().put("check", claimed.check().name()));
            }
            inScope(Actor.SYSTEM, uow -> PeriodCloseFixture.RUNS.conclude(uow, claimed, verdict));
        }
    }

    /** The real judges, wired as the close's worker wires them, over this database. */
    private static CloseChecks judges() {
        SourceProofs unasked = (snapshot, source) -> {
            throw new AssertionError("source completeness and projections are concluded by the suite's verdict");
        };
        AccrualCompleteness accruals = new AccrualCompleteness() {
            @Override
            public List<Lagging> lagging(Connection unitOfWork) {
                throw new AssertionError("accruals are concluded by the suite's verdict");
            }

            @Override
            public List<Floored> flooredPast(Connection unitOfWork, LocalDate periodEnd) {
                throw new AssertionError("accruals are concluded by the suite's verdict");
            }
        };
        AccountingChecks checks = new AccountingChecks(new GeneralLedger(new LedgerJournalReads()), PINS, new LedgerJournalShapes(),
                unasked, AccountingBatteryBooks.register(), new AccountingBreaks(new JdbcAccountingBreakStore(),
                        new LedgerJournalReads(), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW),
                GL_SNAPSHOTS::read, TRANSACTIONS::inTransaction);
        ValuationRateSets sets = new ValuationRateSets(PeriodCloseFixture.SETS, AccountingPeriodFixture.STORE, new WorldRates(),
                ValuationStaleness.standard(), new JdbcAuditWriter(), IDS, NOW);
        return new CloseChecks(checks::run, new FinalTrialBalanceReader(runs(), generator(), TRANSACTIONS,
                FinalTrialBalanceReader.overSnapshots(SNAPSHOTS)), accruals, unasked, (snapshot, period) -> {
                    throw new AssertionError("the disclosure is concluded by the suite's verdict");
                }, (connection, period) -> {
                    throw new AssertionError("the disclosure is concluded by the suite's verdict");
                }, sets, AccountingPeriodFixture.STORE, GL_SNAPSHOTS::read);
    }

    /** {@code month}'s three FINAL runs, translated at its approved set. */
    private static void report(YearMonth month) {
        AccountingPeriodId period = id(month);
        UUID set = inScope(Actor.SYSTEM, uow -> PeriodCloseFixture.SETS.approvedFor(uow, period)).orElseThrow().id().value();
        RunParameters translated = RunParameters.of(Map.of(), Optional.of(set));
        for (ReportDefinition definition : List.of(TRIAL_BALANCE, SHEET, INCOME)) {
            RUNS.put(month + " " + definition.code(), ask(definition, period, translated));
        }
    }

    /** Every fact of every month, read from the database: each FINAL run's definition again on a snapshot, the GL, conformance. */
    private static SortedMap<String, String> read() {
        SortedMap<String, String> read = new TreeMap<>();
        RuleConformance conformance = new RuleConformance(AccountingBatteryBooks.register());
        LedgerJournalShapes shapes = new LedgerJournalShapes();
        for (YearMonth month : AccountingBatteryWorld.MONTHS) {
            String m = month.toString();
            ReportInputs trial = RUNS.get(m + " " + TrialBalanceDefinition.CODE).inputs();
            SNAPSHOTS.read(snapshot -> {
                Connection view = view(snapshot);
                TrialBalance balance = TRIAL_BALANCE.trialBalance(view, trial);
                AccountingBatteryBooks.trialBalanceFacts(read, m, balance, TRIAL_BALANCE.generate(view, trial));
                return null;
            });
            CanonicalJson.Obj sheet = SNAPSHOTS.read(snapshot -> SHEET.generate(view(snapshot),
                    RUNS.get(m + " " + BalanceSheetDefinition.CODE).inputs()));
            AccountingBatteryBooks.balanceSheetFacts(read, m, sheet);
            CanonicalJson.Obj income = SNAPSHOTS.read(snapshot -> INCOME.generate(view(snapshot),
                    RUNS.get(m + " " + IncomeStatementDefinition.CODE).inputs()));
            AccountingBatteryBooks.incomeStatementFacts(read, m, income);
            AccountingBatteryBooks.glFacts(read, m, GL_SNAPSHOTS.read(snapshot -> LEDGER.balances(snapshot,
                    PINS.forPeriod(snapshot, month), Optional.empty())));
            read.put("RC|" + m, GL_SNAPSHOTS.read(snapshot -> {
                RuleConformance.Tally tally = conformance.tally(Map.of(), 100_000);
                Optional<JournalShapes.EntryCursor> after = Optional.empty();
                List<JournalShapes.EntryShape> page;
                do {
                    page = shapes.entries(snapshot, JournalReads.Range.of(month), after, JournalShapes.MAX_LIMIT);
                    page.forEach(tally::add);
                    after = page.isEmpty() ? Optional.empty() : Optional.of(page.get(page.size() - 1).cursor());
                } while (page.size() == JournalShapes.MAX_LIMIT);
                return AccountingBatteryBooks.findings(tally, INDEX_OF);
            }));
        }
        return read;
    }

    /** The journal as the database holds it, back in the world's terms: each entry's index from its key, each line's owner. */
    private static List<Entry> readBack() throws SQLException {
        Map<UUID, List<Object[]>> rows = new LinkedHashMap<>();
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement read = migrator.prepareStatement("SELECT entry.id, entry.idempotency_scope, entry.posting_date,"
                        + " entry.value_date, line.currency, line.scale, account.purpose, account.owner_kind, account.owner_ref,"
                        + " line.direction, line.amount_minor FROM ledger.journal_entry entry JOIN ledger.journal_line line ON"
                        + " line.entry_id = entry.id JOIN ledger.ledger_account account ON account.id = line.ledger_account_id"
                        + " ORDER BY entry.id, line.seq");
                ResultSet row = read.executeQuery()) {
            while (row.next()) {
                rows.computeIfAbsent(row.getObject(1, UUID.class), ignored -> new ArrayList<>()).add(new Object[] {row.getString(2),
                        row.getObject(3, LocalDate.class), row.getObject(4, LocalDate.class), row.getString(5).stripTrailing(),
                        row.getInt(6), row.getString(7), row.getString(8), row.getObject(9, UUID.class), row.getString(10),
                        row.getLong(11)});
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (List<Object[]> lines : rows.values()) {
            Object[] first = lines.get(0);
            String key = ((String) first[0]).substring((PostingService.IDEMPOTENCY_SCOPE + ":").length());
            List<Line> held = new ArrayList<>();
            for (Object[] line : lines) {
                int owner = line[7] == null ? 0 : OWNER_OF.get((UUID) line[7]);
                held.add(new Line((String) line[5], (String) line[6], owner, "DEBIT".equals(line[8]), (Long) line[9]));
            }
            entries.add(new Entry(Integer.parseInt(key.substring(key.lastIndexOf("-e") + 2)), key, key.substring(0,
                    key.indexOf(':') + 1), (LocalDate) first[1], (LocalDate) first[2], (String) first[3], (Integer) first[4], held));
        }
        entries.sort(java.util.Comparator.comparingInt(Entry::index));
        return entries;
    }

    // ------------------------------------------------------------------ plumbing

    private static void assertAgree(String... families) {
        SortedMap<String, String> expected = family(oracle, families);
        SortedMap<String, String> actual = family(facts, families);
        assertThat(expected).isNotEmpty();
        List<String> differences = new ArrayList<>();
        java.util.TreeSet<String> keys = new java.util.TreeSet<>(expected.keySet());
        keys.addAll(actual.keySet());
        for (String key : keys) {
            if (!java.util.Objects.equals(expected.get(key), actual.get(key))) {
                differences.add(key + " oracle=" + expected.get(key) + " database=" + actual.get(key));
            }
        }
        assertThat(differences.size()).as("%s facts differ; the first: %s", differences.size(),
                differences.subList(0, Math.min(20, differences.size()))).isZero();
    }

    private static SortedMap<String, String> family(SortedMap<String, String> all, String... families) {
        SortedMap<String, String> out = new TreeMap<>();
        all.forEach((key, value) -> {
            for (String prefix : families) {
                if (key.startsWith(prefix)) {
                    out.put(key, value);
                }
            }
        });
        return out;
    }

    private static ReportRuns runs() {
        return new ReportRuns(new JdbcReportRunStore(), AccountingPeriodFixture.STORE, new JdbcGlMappingStore(), DEFINITIONS,
                ReportRuns.Leases.STANDARD, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW, ReportRuns.Provisional.STANDARD,
                ReportRuns.ApprovedSets.of(new JdbcValuationRateSetStore()));
    }

    private static ReportRunGenerator generator() {
        return new ReportRunGenerator(runs(), new ReportGeneration(ReportGeneration.FROM_THE_SNAPSHOT,
                ReportRuns.Leases.STANDARD.generationTimeout(), NOW), SNAPSHOTS, TRANSACTIONS,
                new ReportRunMetrics(new SimpleMeterRegistry(), DEFINITIONS));
    }

    /** Claim, then generate and complete when this caller holds the run - the desk's path without the key. */
    private static ReportRunStore.RunRow ask(ReportDefinition definition, AccountingPeriodId period, RunParameters parameters) {
        Actor accountant = person();
        ReportRuns.Claimed claimed = TRANSACTIONS.inTransaction(uow -> runs().claim(uow, definition.code(), period, parameters,
                accountant, correlation()));
        return claimed.mine() ? generator().generateAndComplete(claimed.run(), accountant)
                : generator().drive(claimed.run().id(), accountant);
    }

    private static Connection view(Connection snapshot) {
        return new ReadOnlySnapshot(snapshot, ReportRuns.Leases.STANDARD.generationTimeout(), () -> 0L).connection();
    }

    private static long count(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement read = migrator.prepareStatement(sql);
                ResultSet row = read.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    /**
     * The reference as the battery's world states it: per valued currency the world's {@code EUR/<currency>} rate for the month
     * the capture values, observed the hour before its as-of. Provenance is {@code ValuationRateSetDatabaseTest}'s; here the
     * rates are the oracle's.
     */
    private static final class WorldRates implements ValuationRates {

        @Override
        public List<CurrencyCode> valued() {
            return List.of(CurrencyCode.of("BHD"), CurrencyCode.of("GBP"), CurrencyCode.of("JPY"), CurrencyCode.of("USD"));
        }

        @Override
        public List<Observed> lastObservedBefore(Connection unitOfWork, Instant before) {
            YearMonth month = YearMonth.from(before.minusSeconds(1).atZone(ZoneOffset.UTC));
            SortedMap<String, BigDecimal> rates = AccountingBatteryWorld.rates(SEED, month);
            return valued().stream().map(currency -> new Observed(currency, Optional.of(new Observation(IDS.next(),
                    "battery-reference", EUR, currency, rates.get(currency.code()), before.minusSeconds(3_600),
                    before.minusSeconds(3_598))))).toList();
        }
    }
}
