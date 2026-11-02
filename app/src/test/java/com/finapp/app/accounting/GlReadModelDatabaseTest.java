package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.GlMappingAdministration;
import com.finapp.accounting.GlPins;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.JdbcPeriodPins;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.MappingRule;
import com.finapp.accounting.Side;
import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.accounting.reporting.GlActivity;
import com.finapp.accounting.reporting.GlBalances;
import com.finapp.accounting.reporting.GlEntry;
import com.finapp.accounting.reporting.GlFigure;
import com.finapp.accounting.reporting.PinnedMapping;
import com.finapp.accounting.testing.ReferenceGlMapping;
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
import com.finapp.ledger.PostingResult;
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
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The GL read model against a real journal (`P14-TSK-007`; ADR-0113 decision 2; {@code INV-ACC-02}, {@code INV-ACC-06},
 * {@code INV-ACC-14}; ledger {@code V029}): the entry's three named cases - every line in the period maps exactly once,
 * a figure drills to its lines, GL = ledger per currency - each judged against the database's own raw counts and sums,
 * not against the read model's; and the read-only snapshot refusing a write and cancelling a statement past its bound,
 * the drill-down's pages neither repeating nor skipping - the month's last day in and the next month's first out, two
 * lines of one entry by line order, two entries of one date by id - the range served by
 * {@code journal_entry_by_posting_date}, an entry's GL view. <strong>Written and owed</strong>
 * (owner decision C5: the database tiers are skipped per task; the owner-authorised pass before the exit review runs
 * it).
 *
 * <p>The journal is shared by every suite, so this one posts only in <strong>February 2096</strong> - a month nothing
 * else dates into, past every cutoff any suite moves (1999's) - and judges relations that hold whatever else that month
 * holds on a rerun. The version read is the one in force for the month: the reference chart, activated under four eyes
 * when no version is active yet (the {@code GlMappingDatabaseTest} precedent: from the latest month already in force).
 *
 * <p><strong>A database of its own</strong> ({@code own-container}; `P14-TSK-017`'s review, I-2): it commits lines straight onto {@code
 * SETTLEMENT_CLEARING}, which no expectation names and no backfill can adopt, so in the shared container they would fail {@code
 * ReconciledPositionResidueDatabaseTest#noSuiteLeftAnUnexplainedLine} for good.
 */
@Tag("database")
@Tag("own-container") // its own JVM and database: ownContainerDatabaseTest, the javadoc says why
@DisplayName("the GL read model and drill-down against the journal (P14-TSK-007)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class GlReadModelDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final YearMonth MONTH = YearMonth.of(2096, 2);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final JdbcGlMappingStore MAPPINGS = new JdbcGlMappingStore();

    private final JdbcLedgerAccountStore accounts = new JdbcLedgerAccountStore();
    private final GeneralLedger ledger = new GeneralLedger(new LedgerJournalReads());
    private final GlPins pins = new GlPins(MAPPINGS, new JdbcPeriodPins());
    private final GlSnapshots snapshots = new GlSnapshots(DatabaseRoles::application, GlSnapshots.STATEMENT_TIMEOUT);

    @BeforeEach
    void aVersionIsInForceAndTheMonthHasPostings() throws Exception {
        ensureAnActiveVersion();
        for (CurrencyCode currency : List.of(EUR, USD)) {
            LedgerAccount wallet = wallet(currency);
            for (int i = 1; i <= 3; i++) {
                post(MONTH.atDay(i), operational(AccountPurpose.SETTLEMENT_CLEARING, currency), wallet, 1000L * i,
                        currency);
                post(MONTH.atDay(10 + i), wallet, operational(AccountPurpose.FEE_REVENUE, currency), 25L * i, currency);
            }
        }
    }

    @Test
    @DisplayName("every journal line of the month maps exactly once: the figures' lines are the database's lines per"
            + " currency, none unmapped, and every key the month's lines carry has exactly one rule")
    void everyLineMapsOnce() throws Exception {
        GlBalances balances = read(snapshot -> ledger.balances(snapshot, pins.forPeriod(snapshot, MONTH),
                Optional.empty()));
        assertThat(balances.unmapped()).isEmpty();
        Map<String, Long> figureLines = new TreeMap<>();
        for (GlFigure figure : balances.figures()) {
            figureLines.merge(figure.currency(), figure.lines(), Long::sum);
        }
        assertThat(figureLines).isEqualTo(rawLinesPerCurrency());
        Map<String, MappingRule> rules = balances.mapping().content().rulesByKey();
        for (String key : rawKeys()) {
            assertThat(rules).as("the month's key %s has its one rule", key).containsKey(key);
        }
    }

    @Test
    @DisplayName("every figure equals the sum of its drill-down lines: paged two by two through the real keyset, no line"
            + " served twice, the count and both sides exactly the figure's")
    void aFigureDrillsToItsLines() {
        PinnedMapping mapping = read(snapshot -> pins.forPeriod(snapshot, MONTH));
        GlBalances balances = read(snapshot -> ledger.balances(snapshot, mapping, Optional.empty()));
        assertThat(balances.figures()).isNotEmpty();
        for (GlFigure figure : balances.figures()) {
            BigDecimal debits = BigDecimal.ZERO;
            BigDecimal credits = BigDecimal.ZERO;
            long lines = 0;
            Set<String> served = new HashSet<>();
            Optional<JournalReads.LineCursor> after = Optional.empty();
            do {
                Optional<JournalReads.LineCursor> position = after;
                GlActivity page = read(snapshot -> ledger.activity(snapshot, mapping, figure.account().code(),
                        figure.currency(), position, 2));
                assertThat(page.figure()).as("the month is not moving under this suite").isEqualTo(figure);
                for (JournalReads.Line line : page.lines()) {
                    assertThat(served.add(line.entryId() + "/" + line.seq())).as("no line served twice").isTrue();
                    if (line.side() == Side.DEBIT) {
                        debits = debits.add(line.amount());
                    } else {
                        credits = credits.add(line.amount());
                    }
                    lines++;
                }
                after = page.next();
            } while (after.isPresent());
            assertThat(debits).isEqualByComparingTo(figure.debits());
            assertThat(credits).isEqualByComparingTo(figure.credits());
            assertThat(lines).isEqualTo(figure.lines());
        }
    }

    @Test
    @DisplayName("GL = ledger per currency over the month: the GL's debits and credits equal the ledger's own totals and"
            + " the database's raw sums")
    void glEqualsLedgerPerCurrency() throws Exception {
        GlBalances balances = read(snapshot -> ledger.balances(snapshot, pins.forPeriod(snapshot, MONTH),
                Optional.empty()));
        assertThat(balances.agrees()).isTrue();
        Map<String, BigDecimal[]> raw = rawSumsPerCurrency();
        assertThat(balances.agreement()).extracting(GlBalances.Agreement::currency)
                .containsExactlyElementsOf(raw.keySet());
        for (GlBalances.Agreement agreement : balances.agreement()) {
            assertThat(agreement.agrees()).as("GL = ledger in %s", agreement.currency()).isTrue();
            assertThat(agreement.glDebits()).isEqualByComparingTo(raw.get(agreement.currency())[0])
                    .isEqualByComparingTo(agreement.ledgerDebits());
            assertThat(agreement.glCredits()).isEqualByComparingTo(raw.get(agreement.currency())[1])
                    .isEqualByComparingTo(agreement.ledgerCredits());
        }
    }

    @Test
    @DisplayName("the GL's snapshot is REPEATABLE READ READ ONLY at the database: a write inside it is refused 25006")
    void aGlReadCannotWrite() {
        assertThatThrownBy(() -> read(snapshot -> {
            try (Statement statement = snapshot.createStatement()) {
                statement.executeUpdate("UPDATE accounting.accounting_period SET revision = revision WHERE false");
                return null;
            } catch (SQLException refused) {
                throw new IllegalStateException(refused.getSQLState(), refused);
            }
        })).hasMessage("25006");
        String[] settings = read(snapshot -> {
            try (Statement statement = snapshot.createStatement(); ResultSet row = statement.executeQuery(
                    "SELECT current_setting('transaction_isolation'), current_setting('transaction_read_only')")) {
                row.next();
                return new String[] {row.getString(1), row.getString(2)};
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertThat(settings).containsExactly("repeatable read", "on");
    }

    @Test
    @DisplayName("the month's range is served by journal_entry_by_posting_date (ledger V029) - the planner can use it"
            + " for the aggregate and the keyset")
    void theMonthReadUsesThePostingDateIndex() throws Exception {
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
            app.setAutoCommit(false);
            statement.execute("SET LOCAL enable_seqscan = off");
            String range = LedgerJournalReads.AGGREGATES.replaceFirst("\\?", "'" + MONTH.atDay(1) + "'")
                    .replaceFirst("\\?", "'" + MONTH.atEndOfMonth() + "'");
            assertThat(plan(statement, range)).contains("journal_entry_by_posting_date");
            app.rollback();
        }
    }

    @Test
    @DisplayName("an entry's GL view maps each of its lines under its month's version")
    void theEntryViewMapsEveryLine() throws Exception {
        LedgerAccount wallet = wallet(EUR);
        PostingResult posted = post(MONTH.atDay(20), operational(AccountPurpose.SETTLEMENT_CLEARING, EUR), wallet, 4321,
                EUR);
        GlEntry view = read(snapshot -> {
            JournalReads.Entry entry = ledger.findEntry(snapshot, posted.entryId().value()).orElseThrow();
            return ledger.entry(pins.forPeriod(snapshot, MONTH), entry);
        });
        assertThat(view.lines()).extracting(line -> line.glAccountCode().orElse("UNMAPPED"))
                .containsExactly("1100", "2000");
        assertThat(view.lines()).allSatisfy(line -> assertThat(line.line().postingDate()).isEqualTo(MONTH.atDay(20)));
        assertThat(view.lines().get(1).line().ownerRef()).isPresent();
    }

    @Test
    @DisplayName("the month is inclusive of its last day and of nothing after it: a line on 2096-02-29 (a leap day) is"
            + " in February's figure and drill-down, one on 2096-03-01 is not (the review's M-7)")
    void theMonthIsInclusiveOfItsLastDayOnly() throws Exception {
        LedgerAccount wallet = wallet(EUR);
        LedgerAccount clearing = operational(AccountPurpose.SETTLEMENT_CLEARING, EUR);
        UUID lastDay = post(MONTH.atEndOfMonth(), clearing, wallet, 2929, EUR).entryId().value();
        UUID nextMonth = post(MONTH.plusMonths(1).atDay(1), clearing, wallet, 301, EUR).entryId().value();
        assertThat(MONTH.atEndOfMonth()).isEqualTo(LocalDate.of(2096, 2, 29));
        PinnedMapping mapping = read(snapshot -> pins.forPeriod(snapshot, MONTH));
        List<JournalReads.Line> lines = drill(mapping, "2000", "EUR", 7);
        assertThat(lines).extracting(JournalReads.Line::entryId).contains(lastDay).doesNotContain(nextMonth);
        assertThat(rawLinesPerCurrency().get("EUR")).as("the raw month agrees on the bound")
                .isEqualTo(read(snapshot -> ledger.balances(snapshot, mapping, Optional.of("EUR"))).figures().stream()
                        .mapToLong(GlFigure::lines).sum());
    }

    @Test
    @DisplayName("a figure holding two lines of one entry pages through them by line order: page size one, each line"
            + " served once, the entry's two wallet lines adjacent and in seq order (the review's M-7)")
    void twoLinesOfOneEntryPageByLineOrder() throws Exception {
        LedgerAccount first = wallet(EUR);
        LedgerAccount second = wallet(EUR);
        LedgerAccount clearing = operational(AccountPurpose.SETTLEMENT_CLEARING, EUR);
        UUID split = postLines(MONTH.atDay(17), EUR, List.of(
                new JournalLine(clearing.id(), Direction.DEBIT, Money.ofMinorUnits(200, EUR)),
                new JournalLine(first.id(), Direction.CREDIT, Money.ofMinorUnits(120, EUR)),
                new JournalLine(second.id(), Direction.CREDIT, Money.ofMinorUnits(80, EUR))), IDS, CLOCK);
        PinnedMapping mapping = read(snapshot -> pins.forPeriod(snapshot, MONTH));
        List<JournalReads.Line> lines = drill(mapping, "2000", "EUR", 1);
        List<JournalReads.Line> ofSplit = lines.stream().filter(line -> line.entryId().equals(split)).toList();
        assertThat(ofSplit).extracting(JournalReads.Line::seq).containsExactly(1, 2);
        int at = lines.indexOf(ofSplit.get(0));
        assertThat(lines.get(at + 1)).as("the tie-break on seq keeps the entry's lines together").isEqualTo(ofSplit.get(1));
    }

    @Test
    @DisplayName("two entries on one date page in id order, not insertion order: the later-minted id inserted first is"
            + " served second (ids minted explicitly on clocks two seconds apart; the review's M-7)")
    void sameDateEntriesPageInIdOrder() throws Exception {
        LedgerAccount wallet = wallet(EUR);
        LedgerAccount clearing = operational(AccountPurpose.SETTLEMENT_CLEARING, EUR);
        java.time.Instant now = CLOCK.instant();
        Clock later = Clock.fixed(now.plusSeconds(2), java.time.ZoneOffset.UTC);
        Clock earlier = Clock.fixed(now, java.time.ZoneOffset.UTC);
        LocalDate day = MONTH.atDay(23);
        List<JournalLine> lines = List.of(new JournalLine(clearing.id(), Direction.DEBIT, Money.ofMinorUnits(23, EUR)),
                new JournalLine(wallet.id(), Direction.CREDIT, Money.ofMinorUnits(23, EUR)));
        UUID insertedFirst = postLines(day, EUR, lines, new IdGenerator(later, new SecureRandom()), later);
        UUID insertedSecond = postLines(day, EUR, lines, new IdGenerator(earlier, new SecureRandom()), earlier);
        assertThat(JournalReads.LineCursor.compareIds(insertedSecond, insertedFirst))
                .as("the second insert carries the smaller id").isNegative();
        PinnedMapping mapping = read(snapshot -> pins.forPeriod(snapshot, MONTH));
        List<UUID> served = drill(mapping, "2000", "EUR", 2).stream().map(JournalReads.Line::entryId)
                .filter(id -> id.equals(insertedFirst) || id.equals(insertedSecond)).toList();
        assertThat(served).containsExactly(insertedSecond, insertedFirst);
    }

    @Test
    @DisplayName("a statement past the snapshot's bound is cancelled by PostgreSQL and the read ends ReadTimedOut, rolled"
            + " back; the next read on the pool is bounded afresh (the review's I-2)")
    void aStatementPastTheBoundIsCancelled() {
        GlSnapshots bounded = new GlSnapshots(DatabaseRoles::application, Duration.ofMillis(50));
        assertThatThrownBy(() -> bounded.read(snapshot -> {
            try (PreparedStatement sleep = snapshot.prepareStatement("SELECT pg_sleep(2)");
                    ResultSet row = sleep.executeQuery()) {
                return row.next();
            } catch (SQLException failure) {
                throw new com.finapp.accounting.AccountingStorageException(
                        com.finapp.platform.persistence.DatabaseFailure.describe("sleeping past the bound", failure));
            }
        })).isInstanceOf(GlSnapshots.ReadTimedOut.class);
        String timeout = snapshots.read(snapshot -> {
            try (Statement statement = snapshot.createStatement();
                    ResultSet row = statement.executeQuery("SELECT current_setting('statement_timeout')")) {
                row.next();
                return row.getString(1);
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertThat(timeout).as("the configured bound, local to the transaction").isEqualTo("10s");
    }

    // ------------------------------------------------------------------ the database's own answers

    /** Every line of a figure, paged {@code size} at a time through the real keyset. */
    private List<JournalReads.Line> drill(PinnedMapping mapping, String code, String currency, int size) {
        List<JournalReads.Line> lines = new java.util.ArrayList<>();
        Optional<JournalReads.LineCursor> after = Optional.empty();
        do {
            Optional<JournalReads.LineCursor> position = after;
            GlActivity page = read(snapshot -> ledger.activity(snapshot, mapping, code, currency, position, size));
            lines.addAll(page.lines());
            after = page.next();
        } while (after.isPresent());
        return lines;
    }

    private static Map<String, Long> rawLinesPerCurrency() throws SQLException {
        Map<String, Long> counts = new TreeMap<>();
        try (Connection app = DatabaseRoles.application(); PreparedStatement select = app.prepareStatement(
                "SELECT line.currency, count(*) FROM ledger.journal_line line JOIN ledger.journal_entry entry"
                        + " ON entry.id = line.entry_id WHERE entry.posting_date BETWEEN ? AND ? GROUP BY line.currency")) {
            select.setObject(1, MONTH.atDay(1));
            select.setObject(2, MONTH.atEndOfMonth());
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    counts.put(row.getString(1).stripTrailing(), row.getLong(2));
                }
            }
        }
        return counts;
    }

    /** Per currency: {debits, credits}, summed line by line in Java from the raw rows. */
    private static Map<String, BigDecimal[]> rawSumsPerCurrency() throws SQLException {
        Map<String, BigDecimal[]> sums = new TreeMap<>();
        try (Connection app = DatabaseRoles.application(); PreparedStatement select = app.prepareStatement(
                "SELECT line.currency, line.direction, line.amount_minor, line.scale FROM ledger.journal_line line"
                        + " JOIN ledger.journal_entry entry ON entry.id = line.entry_id"
                        + " WHERE entry.posting_date BETWEEN ? AND ?")) {
            select.setObject(1, MONTH.atDay(1));
            select.setObject(2, MONTH.atEndOfMonth());
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    BigDecimal[] pair = sums.computeIfAbsent(row.getString(1).stripTrailing(),
                            ignored -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                    BigDecimal amount = BigDecimal.valueOf(row.getLong(3), row.getInt(4));
                    int side = row.getString(2).equals("DEBIT") ? 0 : 1;
                    pair[side] = pair[side].add(amount);
                }
            }
        }
        return sums;
    }

    private static Set<String> rawKeys() throws SQLException {
        Set<String> keys = new HashSet<>();
        try (Connection app = DatabaseRoles.application(); PreparedStatement select = app.prepareStatement(
                "SELECT DISTINCT account.purpose || '/' || account.owner_kind FROM ledger.journal_line line"
                        + " JOIN ledger.journal_entry entry ON entry.id = line.entry_id"
                        + " JOIN ledger.ledger_account account ON account.id = line.ledger_account_id"
                        + " WHERE entry.posting_date BETWEEN ? AND ?")) {
            select.setObject(1, MONTH.atDay(1));
            select.setObject(2, MONTH.atEndOfMonth());
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    keys.add(row.getString(1));
                }
            }
        }
        return keys;
    }

    private static String plan(Statement statement, String sql) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (ResultSet row = statement.executeQuery("EXPLAIN " + sql)) {
            while (row.next()) {
                plan.append(row.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }

    // ------------------------------------------------------------------ fixtures

    private <R> R read(java.util.function.Function<Connection, R> work) {
        return snapshots.read(work);
    }

    /** The reference chart activated under four eyes when no version is active - from the latest month in force. */
    private static void ensureAnActiveVersion() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            if (MAPPINGS.active(app).isPresent()) {
                return;
            }
        }
        GlMappingAdministration administration = new GlMappingAdministration(MAPPINGS, new LedgerPurposeChart(),
                new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);
        YearMonth from;
        try (Connection app = DatabaseRoles.application()) {
            from = MAPPINGS.latestEffectiveFrom(app).orElse(ReferenceGlMapping.EFFECTIVE_FROM);
        }
        GlMappingAdministration.Proposed proposed = inOneTransaction(uow -> administration.propose(uow,
                ReferenceGlMapping.version(from), "the reference chart", person(), CorrelationId.generate(IDS)));
        inOneTransaction(uow -> administration.activate(uow, proposed.id(), person(), "the GL suite's version",
                CorrelationId.generate(IDS)));
    }

    private LedgerAccount wallet(CurrencyCode currency) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount account = accounts.createOrConverge(app, LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY,
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

    private PostingResult post(LocalDate day, LedgerAccount debit, LedgerAccount credit, long minor,
            CurrencyCode currency) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            PostingResult result = postings().post(app, new PostingCommand("gl-read-" + IDS.next(), day, day,
                    "gl-read-model", List.of(new JournalLine(debit.id(), Direction.DEBIT, Money.ofMinorUnits(minor,
                            currency)), new JournalLine(credit.id(), Direction.CREDIT, Money.ofMinorUnits(minor,
                            currency)))));
            app.commit();
            return result;
        }
    }

    /** One entry of {@code lines} on {@code day}, through the ledger's own door, its id minted by {@code ids}. */
    private static UUID postLines(LocalDate day, CurrencyCode currency, List<JournalLine> lines, IdGenerator ids,
            Clock clock) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            PostingResult result = postings(ids, clock).post(app, new PostingCommand("gl-read-" + IDS.next() + "-"
                    + currency.code(), day, day, "gl-read-model", lines));
            app.commit();
            return result.entryId().value();
        }
    }

    private static PostingService postings() {
        return postings(IDS, CLOCK);
    }

    private static PostingService postings(IdGenerator ids, Clock clock) {
        return new PostingService(new IdempotentExecutor(new JdbcIdempotencyRecordStore(), clock, Duration.ofDays(1),
                Duration.ofMinutes(5)), new JdbcJournalEntryStore(ids), new JdbcAuditWriter(), new JdbcOutboxWriter(),
                new JdbcBalanceProjection(), ids, clock, PostingObserver.NONE);
    }

    private static <R> R inOneTransaction(java.util.function.Function<Connection, R> work) {
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

    private static Actor person() {
        return new Actor("operator-" + UUID.randomUUID(), ActorType.CUSTOMER);
    }

}
