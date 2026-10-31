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

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.JdbcReportRunStore;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.reporting.ReadOnlySnapshot;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.ReportInputs;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.TrialBalance;
import com.finapp.accounting.reporting.TrialBalanceDefinition;
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
import com.finapp.platform.testing.database.DatabaseRoles;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The period trial balance against a live PostgreSQL (`P14-TSK-009`; ADR-0115; {@code INV-ACC-01} reported,
 * {@code INV-ACC-04}, {@code INV-ACC-06}) - the backlog entry's named cases and what the design needs: a locked period's
 * FINAL run zero per currency; equal to the ledger, judged against <em>this suite's own</em> raw sweep-shaped sums over
 * the three windows; the opening the previous closing; a wrong range bound caught; a FINAL run regenerating identical
 * under its period's pin; a PROVISIONAL run read at its cut and reused within its freshness window.
 *
 * <p><strong>The books it uses.</strong> Accounting periods are contiguous calendar months shared by every suite, so this
 * one uses only the fixture's <strong>January and February 1999</strong> ({@link AccountingPeriodFixture} - before
 * `P14-TSK-012` builds the close only January can be locked); it posts entries dated 1998-12-15 (the opening's history),
 * 1999-01-01 and 1999-01-31 (the period's boundary days) and 1999-02-01 (the day after), in EUR and BHD, and judges
 * relations that hold whatever else those months hold on a rerun. Whether a mapping version is in force for 1999 depends
 * on what other suites activated first; every case holds either way (with none, every line is an unmapped row). Each case
 * returns the periods to {@code OPEN} - superseding its FINAL runs on the way. <strong>Written and owed</strong> (owner
 * decision C5: the database tiers are skipped per task).
 */
@Tag("database")
@DisplayName("the period trial balance against the database (P14-TSK-009)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class TrialBalanceRunDatabaseTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode BHD = CurrencyCode.of("BHD");
    private static final LocalDate HISTORY = LocalDate.of(1998, 12, 15);
    private static final LocalDate FIRST_DAY = JANUARY.atDay(1);
    private static final LocalDate LAST_DAY = JANUARY.atEndOfMonth();
    private static final LocalDate DAY_AFTER = FEBRUARY.atDay(1);
    private static final TrialBalanceReads READS = new LedgerTrialBalanceReads(new JdbcGlMappingStore());
    private static final TrialBalanceDefinition TRIAL_BALANCE = new TrialBalanceDefinition(READS);
    private static final ReportDefinitions DEFINITIONS = ReportDefinitions.of(TRIAL_BALANCE);
    private static final JdbcReportSnapshots SNAPSHOTS = new JdbcReportSnapshots(DatabaseRoles::application,
            ReportRuns.Leases.STANDARD.generationTimeout());
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

    private final JdbcLedgerAccountStore accounts = new JdbcLedgerAccountStore();

    @BeforeEach
    void theBooksAreOpenAndHoldTheBoundaryDays() throws SQLException {
        AccountingPeriodFixture.restore();
        for (CurrencyCode currency : List.of(EUR, BHD)) {
            LedgerAccount wallet = wallet(currency);
            post(HISTORY, operational(AccountPurpose.SETTLEMENT_CLEARING, currency), wallet, 70_000, currency, NOW);
            post(FIRST_DAY, operational(AccountPurpose.SETTLEMENT_CLEARING, currency), wallet, 1_000, currency, NOW);
            post(LAST_DAY, wallet, operational(AccountPurpose.FEE_REVENUE, currency), 25, currency, NOW);
            post(DAY_AFTER, wallet, operational(AccountPurpose.FEE_REVENUE, currency), 7, currency, NOW);
        }
    }

    @AfterEach
    void leaveOpen() {
        AccountingPeriodFixture.restore();
    }

    @Test
    @DisplayName("the entry's case: a locked period's FINAL trial balance is zero per currency - cumulative debits equal"
            + " credits and the closing balances foot - and its stored verdict passes")
    void zeroPerCurrency() throws Exception {
        AccountingPeriodId january = lockJanuary();
        ReportRunStore.RunRow run = ask(january);
        assertThat(run.basis()).isEqualTo(RunBasis.FINAL);
        assertThat(run.status()).isEqualTo(RunStatus.FINAL);
        assertThat(verdict(run)).isEqualTo("true");
        TrialBalance balance = regenerated(run);
        assertThat(balance.currencies()).extracting(TrialBalance.CurrencyCheck::currency).contains("EUR", "BHD");
        for (TrialBalance.CurrencyCheck check : balance.currencies()) {
            assertThat(check.zero()).as(check.currency()).isTrue();
            assertThat(check.closingDebitBalances()).isEqualByComparingTo(check.closingCreditBalances());
        }
        assertThat(balance.outOfBalance()).isEmpty();
    }

    @Test
    @DisplayName("the entry's case: the trial balance equals the ledger - per currency its totals over every row equal this"
            + " suite's own sweep-shaped sums of journal_line before 1999-01-01, within January and through 1999-01-31")
    void equalsTheLedger() throws Exception {
        AccountingPeriodId january = lockJanuary();
        TrialBalance balance = regenerated(ask(january));
        Map<String, TrialBalance.Sides> before = raw("entry.posting_date < DATE '1999-01-01'");
        Map<String, TrialBalance.Sides> within = raw("entry.posting_date BETWEEN DATE '1999-01-01' AND DATE '1999-01-31'");
        Map<String, TrialBalance.Sides> through = raw("entry.posting_date <= DATE '1999-01-31'");
        assertThat(balance.currencies()).extracting(TrialBalance.CurrencyCheck::currency)
                .containsExactlyElementsOf(through.keySet());
        for (TrialBalance.CurrencyCheck check : balance.currencies()) {
            String currency = check.currency();
            assertThat(check.opening().sameAs(before.getOrDefault(currency, TrialBalance.Sides.NONE))).as(currency)
                    .isTrue();
            assertThat(check.movement().sameAs(within.getOrDefault(currency, TrialBalance.Sides.NONE))).as(currency)
                    .isTrue();
            assertThat(check.closing().sameAs(through.get(currency))).as(currency).isTrue();
            assertThat(check.equalsLedger()).as(currency).isTrue();
        }
        assertThat(balance.rangeIsThePeriods()).isTrue();
    }

    @Test
    @DisplayName("the entry's case (the probe's target): the opening is the previous closing - February's PROVISIONAL"
            + " opening of every row equals January's FINAL closing of it, January locked")
    void openingIsThePreviousClosing() throws Exception {
        AccountingPeriodId january = lockJanuary();
        ReportRunStore.RunRow previous = ask(january);
        TrialBalance closed = regenerated(previous);
        // A PROVISIONAL run another case completed would be reused within its window, cut before this case's postings.
        Thread.sleep(1_100);
        ReportRunStore.RunRow february = ask(id(FEBRUARY), new ReportRuns.Provisional(Duration.ofSeconds(1)));
        assertThat(february.basis()).isEqualTo(RunBasis.PROVISIONAL);
        assertThat(february.mappingVersionId()).as("one version for both months")
                .isEqualTo(previous.mappingVersionId());
        TrialBalance opened = regenerated(february);
        Map<String, BigDecimal> closings = balances(closed, false);
        Map<String, BigDecimal> openings = balances(opened, true);
        assertThat(closings).isNotEmpty();
        TreeMap<String, Boolean> keys = new TreeMap<>();
        closings.keySet().forEach(key -> keys.put(key, true));
        openings.keySet().forEach(key -> keys.put(key, true));
        for (String key : keys.keySet()) {
            assertThat(openings.getOrDefault(key, BigDecimal.ZERO)).as(key)
                    .isEqualByComparingTo(closings.getOrDefault(key, BigDecimal.ZERO));
        }
    }

    @Test
    @DisplayName("the range bound (P14-TSK-007's review, M-6): the movement read a day short - the last day's lines on it -"
            + " turns the ledger comparison false and the check failed; the two sides never share a bound")
    void aWrongRangeBoundIsCaught() {
        AccountingPeriodId january = lockJanuary();
        ReportRunStore.RunRow run = ask(january);
        TrialBalanceReads shortened = new TrialBalanceReads() {
            @Override
            public Optional<Period> period(Connection snapshot, AccountingPeriodId id) {
                return READS.period(snapshot, id);
            }

            @Override
            public com.finapp.accounting.GlMappingVersion version(Connection snapshot,
                    com.finapp.accounting.GlMappingVersionId id) {
                return READS.version(snapshot, id);
            }

            @Override
            public List<JournalReads.Aggregate> before(Connection snapshot, LocalDate before, Optional<Instant> cut) {
                return READS.before(snapshot, before, cut);
            }

            @Override
            public List<JournalReads.Aggregate> within(Connection snapshot, JournalReads.Range range,
                    Optional<Instant> cut) {
                return READS.within(snapshot, new JournalReads.Range(range.from(), range.to().minusDays(1)), cut);
            }

            @Override
            public List<LedgerWindows> ledger(Connection snapshot, AccountingPeriodId period, Optional<Instant> cut) {
                return READS.ledger(snapshot, period, cut);
            }
        };
        TrialBalance balance = SNAPSHOTS.read(snapshot -> new TrialBalanceDefinition(shortened)
                .trialBalance(view(snapshot), run.inputs()));
        assertThat(balance.equalsLedger()).isFalse();
        assertThat(balance.disagreeing()).contains("EUR", "BHD");
        assertThat(balance.passes()).isFalse();
    }

    @Test
    @DisplayName("a FINAL run regenerates IDENTICAL, under its period's pin: the run's version is the period row's"
            + " pinned_mapping_version_id, and the regeneration's bytes and hash are the stored ones")
    void aFinalRunRegeneratesIdentical() throws Exception {
        AccountingPeriodId january = lockJanuary();
        ReportRunStore.RunRow run = ask(january);
        assertThat(run.mappingVersionId().map(pin -> pin.value().toString()))
                .isEqualTo(Optional.ofNullable(single("SELECT pinned_mapping_version_id::text FROM"
                        + " accounting.accounting_period WHERE id = '" + january.value() + "'")));
        ReportRunGenerator.Compared compared = generator().regenerate(run.id());
        assertThat(compared.identical()).isTrue();
        assertThat(compared.regeneratedSha256()).isEqualTo(run.contentSha256().orElseThrow());
    }

    @Test
    @DisplayName("the cut (P14-TSK-008's review, M-8): a PROVISIONAL run generated after an entry created past its claim"
            + " does not count it; the content names the cut")
    void aProvisionalRunReadsAtItsCut() throws Exception {
        AccountingPeriodId february = id(FEBRUARY);
        Actor accountant = person();
        ReportRuns.Claimed claimed = TRANSACTIONS.inTransaction(uow -> runs().claim(uow, TrialBalanceDefinition.CODE,
                february, RunParameters.none(), accountant, correlation()));
        // Created "an hour later" than the claim - the posting's own clock, never the database's.
        post(FEBRUARY.atDay(2), wallet(EUR), operational(AccountPurpose.FEE_REVENUE, EUR), 999_999, EUR,
                Clock.offset(NOW, Duration.ofHours(1)));
        ReportRunStore.RunRow run = claimed.mine() ? generator().generateAndComplete(claimed.run(), accountant)
                : generator().drive(claimed.run().id(), accountant);
        TrialBalance atTheCut = regenerated(run);
        assertThat(atTheCut.passes()).isTrue();
        ReportInputs asked = run.inputs();
        ReportInputs later = new ReportInputs(asked.periodId(), asked.period(), asked.periodRevision(), asked.basis(),
                asked.periodLockedAt(), asked.mappingVersionId(), asked.mappingVersion(),
                asked.cutAt().plus(Duration.ofHours(2)), asked.parameters());
        TrialBalance pastTheCut = SNAPSHOTS.read(snapshot -> TRIAL_BALANCE.trialBalance(view(snapshot), later));
        assertThat(credits(pastTheCut, "EUR").subtract(credits(atTheCut, "EUR")))
                .as("the entry created past the claim is counted only by a later cut")
                .isGreaterThanOrEqualTo(new BigDecimal("9999.99"));
        assertThat(single("SELECT content FROM accounting.report_run WHERE id = '" + run.id().value() + "'"))
                .contains("\"cut\":\"" + run.cutAt() + "\"");
    }

    private static BigDecimal credits(TrialBalance balance, String currency) {
        return balance.currencies().stream().filter(check -> check.currency().equals(currency)).findFirst()
                .map(check -> check.movement().credits()).orElse(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("the PROVISIONAL bound (M-8): a second ask within the freshness window answers the completed run - no new"
            + " row; past a one-second window, the next revision - judged on the database's clock")
    void aFreshProvisionalIsReused() throws Exception {
        AccountingPeriodId february = id(FEBRUARY);
        ReportRuns.Provisional oneSecond = new ReportRuns.Provisional(Duration.ofSeconds(1));
        ReportRunStore.RunRow first = ask(february, ReportRuns.Provisional.STANDARD);
        assertThat(ask(february, ReportRuns.Provisional.STANDARD).id()).isEqualTo(first.id());
        Thread.sleep(1_500);
        ReportRunStore.RunRow next = ask(february, oneSecond);
        assertThat(next.id()).isNotEqualTo(first.id());
        assertThat(next.revision()).isEqualTo(first.revision() + 1);
    }

    // ------------------------------------------------------------------ the run

    private static AccountingPeriodId lockJanuary() {
        AccountingPeriodId january = id(JANUARY);
        Actor accountant = person();
        inScope(accountant, uow -> periods().lock(uow, january, accountant, null, correlation()));
        return january;
    }

    private static ReportRunStore.RunRow ask(AccountingPeriodId period) {
        return ask(period, ReportRuns.Provisional.STANDARD);
    }

    /** Claim, then generate and complete when this caller holds the run - the desk's path without the key. */
    private static ReportRunStore.RunRow ask(AccountingPeriodId period, ReportRuns.Provisional provisional) {
        Actor accountant = person();
        ReportRuns.Claimed claimed = TRANSACTIONS.inTransaction(uow -> runs(provisional).claim(uow,
                TrialBalanceDefinition.CODE, period, RunParameters.none(), accountant, correlation()));
        return claimed.mine() ? generator().generateAndComplete(claimed.run(), accountant)
                : generator().drive(claimed.run().id(), accountant);
    }

    private static ReportRuns runs() {
        return runs(ReportRuns.Provisional.STANDARD);
    }

    private static ReportRuns runs(ReportRuns.Provisional provisional) {
        return new ReportRuns(new JdbcReportRunStore(), AccountingPeriodFixture.STORE, new JdbcGlMappingStore(),
                DEFINITIONS, ReportRuns.Leases.STANDARD, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW,
                provisional);
    }

    private static ReportRunGenerator generator() {
        return new ReportRunGenerator(runs(), new ReportGeneration(ReportGeneration.FROM_THE_SNAPSHOT,
                ReportRuns.Leases.STANDARD.generationTimeout(), NOW), SNAPSHOTS, TRANSACTIONS,
                new ReportRunMetrics(new SimpleMeterRegistry(), DEFINITIONS));
    }

    /** The run's trial balance read again in a snapshot, through the definition's restricted view. */
    private static TrialBalance regenerated(ReportRunStore.RunRow run) {
        ReportInputs inputs = run.inputs();
        return SNAPSHOTS.read(snapshot -> TRIAL_BALANCE.trialBalance(view(snapshot), inputs));
    }

    private static Connection view(Connection snapshot) {
        return new ReadOnlySnapshot(snapshot, ReportRuns.Leases.STANDARD.generationTimeout(), () -> 0L)
                .connection();
    }

    /** The stored content's verdict, read by the database - the path `P14-TSK-011` reads. */
    private static String verdict(ReportRunStore.RunRow run) throws SQLException {
        return single("SELECT content::jsonb #>> '" + TrialBalanceDefinition.VERDICT_PATH + "' FROM accounting.report_run"
                + " WHERE id = '" + run.id().value() + "'");
    }

    /** Per row key ({@code A:code|currency} or {@code U:purpose/ownerKind|currency}), the opening or the closing. */
    private static Map<String, BigDecimal> balances(TrialBalance balance, boolean opening) {
        Map<String, BigDecimal> balances = new TreeMap<>();
        for (TrialBalance.AccountRow row : balance.accounts()) {
            balances.put("A:" + row.account().code() + "|" + row.currency(),
                    opening ? row.openingBalance() : row.closingBalance());
        }
        for (TrialBalance.UnmappedRow row : balance.unmapped()) {
            balances.put("U:" + row.purpose() + "/" + row.ownerKind() + "|" + row.currency(),
                    opening ? row.openingBalance() : row.closingBalance());
        }
        return balances;
    }

    // ------------------------------------------------------------------ the raw ledger

    /** This suite's own sweep-shaped sums: {@code journal_line} per currency, scale and direction, no account joined. */
    private static Map<String, TrialBalance.Sides> raw(String predicate) throws SQLException {
        Map<String, TrialBalance.Sides> sums = new TreeMap<>();
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement read = migrator.prepareStatement("SELECT line.currency, line.scale, line.direction,"
                        + " SUM(line.amount_minor), COUNT(*) FROM ledger.journal_line line JOIN ledger.journal_entry"
                        + " entry ON entry.id = line.entry_id WHERE " + predicate
                        + " GROUP BY line.currency, line.scale, line.direction");
                ResultSet row = read.executeQuery()) {
            while (row.next()) {
                BigDecimal amount = new BigDecimal(row.getBigDecimal(4).toBigIntegerExact(), row.getInt(2));
                TrialBalance.Sides one = "DEBIT".equals(row.getString(3))
                        ? new TrialBalance.Sides(amount, BigDecimal.ZERO, row.getLong(5))
                        : new TrialBalance.Sides(BigDecimal.ZERO, amount, row.getLong(5));
                sums.merge(row.getString(1).stripTrailing(), one, TrialBalance.Sides::plus);
            }
        }
        return sums;
    }

    private static String single(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement read = migrator.prepareStatement(sql);
                ResultSet row = read.executeQuery()) {
            return row.next() ? row.getString(1) : null;
        }
    }

    // ------------------------------------------------------------------ the journal

    private LedgerAccount wallet(CurrencyCode currency) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount account = accounts.createOrConverge(app, LedgerAccount.owned(IDS, NOW, AccountType.LIABILITY,
                    AccountPurpose.CUSTOMER_WALLET, currency, IDS.next())).account();
            app.commit();
            return account;
        }
    }

    private LedgerAccount operational(AccountPurpose purpose, CurrencyCode currency) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return accounts.findOperational(app, purpose, currency).orElseThrow();
        }
    }

    /** One balanced entry through the ledger's own door, created on {@code clock}. */
    private static void post(LocalDate day, LedgerAccount debit, LedgerAccount credit, long minor, CurrencyCode currency,
            Clock clock) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            new PostingService(new IdempotentExecutor(new JdbcIdempotencyRecordStore(), clock, Duration.ofDays(1),
                    Duration.ofMinutes(5)), new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(),
                    new JdbcOutboxWriter(), new JdbcBalanceProjection(), IDS, clock, PostingObserver.NONE)
                    .post(app, new PostingCommand("trial-balance-" + IDS.next(), day, day, "trial-balance-suite",
                            List.of(new JournalLine(debit.id(), Direction.DEBIT, Money.ofMinorUnits(minor, currency)),
                                    new JournalLine(credit.id(), Direction.CREDIT,
                                            Money.ofMinorUnits(minor, currency)))));
            app.commit();
        }
    }
}
