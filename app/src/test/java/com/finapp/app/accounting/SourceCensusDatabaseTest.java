package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.JdbcSourceCensusStore;
import com.finapp.accounting.SourceCensus;
import com.finapp.accounting.SourceJoinCatalogue;
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
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.sourcejoin.JournalSources;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The source-join census against the real schema and journal (`P15-TSK-035`; {@code THREAT_MODEL.md} T15, section 5). <strong>Written
 * and owed to {@code P15-TST-002}</strong> (C14: the database tiers are skipped per task): the entry's two planted entries counted,
 * a legitimate posting not counted, the amount mismatch counted, an entry whose id sorts below the head still judged (a forged id and a
 * skewed instance's), a late commit counted after its transaction commits, a walk held behind a running transaction, ten walkers judging
 * every entry once, and <strong>every join's statement run once against the real tables</strong>, which proves their columns and casts.
 *
 * <p><strong>The journal is shared by every suite</strong>, so each case plants its entries and judges only those; the walk runs from
 * wherever the shared head stands until it has passed what was planted. Other suites' raw journal fixtures are findings that do not
 * belong to these cases, which assert per planted entry and never a count of all findings.
 *
 * <p><strong>Two of the entry's legitimate postings are named, not every namespace's</strong>: a posting whose full flow runs here would
 * need that flow's whole world, one suite per namespace. Each namespace's join and comparison are stated on its module's read port;
 * they were walked by hand against each poster when the census was built, {@code SourceJoinCoverageTest} holds the coverage, and each
 * join's statement running against the real schema is the case below. The gap between "statement runs" and "a real poster's row and
 * lines agree with the join" is named in the task's gate record, as what the first run on a real journal will test.
 */
@Tag("database")
@DisplayName("the source-join census on a real database (P15-TSK-035)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class SourceCensusDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final int WALKERS = 10;

    private final JdbcLedgerAccountStore accounts = new JdbcLedgerAccountStore();
    private final List<JournalSources> sources = SourceCensusComposition.sources();
    private final JdbcSourceCensusStore store = new JdbcSourceCensusStore();
    private final LedgerJournalCensusWalk walk = new LedgerJournalCensusWalk();

    private final SourceCensus census = new SourceCensus(new SourceCensus.Units() {
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
    }, store, walk, new SourceJoinCatalogue(sources), 500, Duration.ofSeconds(30));

    @Test
    @DisplayName("every source join's statement runs against the real tables: its columns and casts are the schema's")
    void everyJoinStatementRunsAgainstTheRealSchema() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            for (JournalSources module : sources) {
                for (com.finapp.platform.sourcejoin.SourceJoin join : module.joins()) {
                    // A bogus-but-wellformed key that names no row: the statement plans and executes; the empty answer is the point.
                    try (PreparedStatement select = app.prepareStatement(join.sql())) {
                        String arrayType = join.sql().contains("?::text[]") ? "text" : "uuid";
                        Object[] key = arrayType.equals("text")
                                ? new Object[] {join.sql().contains("catch_up_sequence") ? "00000000-0000-7000-8000-000000000001:catch-up:1" : "00000000-0000-7000-8000-000000000001:2001-01-01"}
                                : new Object[] {UUID.fromString("00000000-0000-7000-8000-000000000001")};
                        select.setArray(1, app.createArrayOf(arrayType, key));
                        try (ResultSet row = select.executeQuery()) {
                            while (row.next()) {
                                assertThat(row.getString(1)).as(join.namespace().toString()).isNotNull();
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("a balanced entry between two customer wallets with no transfer behind it is counted")
    void aWalletToWalletEntryWithNoTransferIsCounted() throws Exception {
        UUID planted = post("transfer:" + IDS.next(), wallet(), wallet());
        walkPast(planted);
        assertThat(findingKindOf(planted)).isEqualTo("UNSOURCED");
    }

    @Test
    @DisplayName("a fee revenue to merchant payable entry with no fee record is counted")
    void aFeeRevenueToMerchantPayableWithNoRecordIsCounted() throws Exception {
        UUID planted = post("payment-refund:" + IDS.next(), operational(AccountPurpose.FEE_REVENUE), payable());
        walkPast(planted);
        assertThat(findingKindOf(planted)).isEqualTo("UNSOURCED");
    }

    @Test
    @DisplayName("a legitimate posting is not counted: a transfer whose row states the entry's amount, and a mismatch in the amount is")
    void aLegitimatePostingIsNotCountedAndAMismatchIs() throws Exception {
        LedgerAccount from = wallet();
        LedgerAccount to = wallet();
        UUID transferId = IDS.next();
        insertTransfer(transferId, 1234, from.id().value(), to.id().value());
        UUID legitimate = post("transfer:" + transferId, from, to);
        UUID mismatchedTransfer = IDS.next();
        insertTransfer(mismatchedTransfer, 999, from.id().value(), to.id().value());
        UUID mismatched = post("transfer:" + mismatchedTransfer, from, to);

        walkPast(mismatched);

        assertThat(findingKindOf(legitimate)).as("a legitimate transfer").isNull();
        assertThat(findingKindOf(mismatched)).isEqualTo("MISMATCHED");
    }

    @Test
    @DisplayName("an entry whose id sorts below the head is still judged: a forged id costs the forger nothing the walk needs")
    void anEntryMintedBelowTheHeadIsStillJudged() throws Exception {
        UUID head = post("transfer:" + IDS.next(), wallet(), wallet());
        walkPast(head);

        UUID forged = post("transfer:" + IDS.next(), wallet(), wallet(), new IdGenerator(Clock.fixed(Instant.parse("2001-01-01T00:00:00Z"), ZoneOffset.UTC), new SecureRandom()));
        assertThat(column("SELECT '" + forged + "'::uuid < '" + head + "'::uuid")).as("the plant really sorts below the head").isEqualTo("t");

        walkPast(forged);
        assertThat(findingKindOf(forged)).as("judged, for all its id").isEqualTo("UNSOURCED");
    }

    @Test
    @DisplayName("a skewed instance's entry is still judged: its id's clock runs behind the head's and the position does not read it")
    void aSkewedInstancesEntryIsStillJudged() throws Exception {
        UUID head = post("transfer:" + IDS.next(), wallet(), wallet());
        walkPast(head);

        Clock behind = Clock.offset(CLOCK, Duration.ofMinutes(-90));
        UUID skewed = post("transfer:" + IDS.next(), wallet(), wallet(), new IdGenerator(behind, new SecureRandom()));
        assertThat(column("SELECT '" + skewed + "'::uuid < '" + head + "'::uuid")).as("minted behind the head by the skew").isEqualTo("t");

        walkPast(skewed);
        assertThat(findingKindOf(skewed)).isEqualTo("UNSOURCED");
    }

    @Test
    @DisplayName("a raw insert cannot choose its transaction: the stamp is the database's")
    void aRawInsertCannotChooseItsTransaction() throws Exception {
        UUID id = IDS.next();
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
            app.setAutoCommit(false);
            // Named outright, as a raw writer would: the deferred balance and line-count checks never run, because this rolls back.
            statement.executeUpdate("INSERT INTO ledger.journal_entry (id, posting_date, value_date, entry_type, reference, actor_id, correlation_id, causation_id,"
                    + " idempotency_scope, created_at, txid) VALUES ('" + id + "', current_date, current_date, 'POSTING', 'p15-035', 'SYSTEM', 'p15-035', 'p15-035',"
                    + " 'ledger.post:p15-035:chosen', statement_timestamp(), '7')");
            try (ResultSet row = statement.executeQuery("SELECT txid::text, pg_current_xact_id()::text FROM ledger.journal_entry WHERE id = '" + id + "'")) {
                row.next();
                assertThat(row.getString(1)).as("the trigger overrules the writer").isEqualTo(row.getString(2)).isNotEqualTo("7");
            }
            app.rollback();
        }
    }

    @Test
    @DisplayName("a walk stops behind a running transaction and counts its entry once it commits")
    void aLateCommitIsCounted() throws Exception {
        LedgerAccount from = wallet();
        LedgerAccount to = wallet();
        Connection holding = DatabaseRoles.application();
        holding.setAutoCommit(false);
        UUID late;
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            // Holding the transaction open keeps its xid at or below xmin, so nothing with a later xid is judged.
            late = posting(IDS).post(holding, command("transfer:" + IDS.next(), from, to)).entryId().value();
        }
        try {
            UUID later = post("transfer:" + IDS.next(), wallet(), wallet());
            walkUntilIdle(Duration.ofSeconds(60));
            assertThat(findingKindOf(later)).as("nothing past the open transaction is judged").isNull();

            holding.commit();

            walkPast(later);
            assertThat(findingKindOf(late)).as("counted once it commits").isEqualTo("UNSOURCED");
            assertThat(findingKindOf(later)).isEqualTo("UNSOURCED");
        } finally {
            holding.close();
        }
    }

    @Test
    @DisplayName("ten walkers judge every entry once: a contended head does nothing and a finding is born once per entry")
    void tenWalkersJudgeEachEntryOnce() throws Exception {
        UUID planted = post("transfer:" + IDS.next(), wallet(), wallet());
        ExecutorService pool = Executors.newFixedThreadPool(WALKERS);
        try {
            List<Future<Void>> runs = new ArrayList<>();
            for (int walker = 0; walker < WALKERS; walker++) {
                runs.add(pool.submit(() -> {
                    walkPast(planted);
                    return null;
                }));
            }
            for (Future<Void> run : runs) {
                run.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(scalar("SELECT count(*) FROM accounting.source_census_finding WHERE entry_id = '" + planted + "'")).isEqualTo(1L);
    }

    /** Sweeps until the head has reached {@code entry}, in the walk's own order, bounded. */
    private void walkPast(UUID entry) throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(2).toMillis();
        while (System.currentTimeMillis() < deadline && !headHasPassed(entry)) {
            SourceCensus.Batch batch = census.sweepBatch();
            if (batch.judged() == 0) {
                Thread.sleep(50);
            }
        }
        assertThat(headHasPassed(entry)).as("the walk reached the planted entry").isTrue();
    }

    /** Sweeps until a batch finds nothing to judge: as far as the oldest running transaction allows. */
    private void walkUntilIdle(Duration bound) throws Exception {
        long deadline = System.currentTimeMillis() + bound.toMillis();
        while (System.currentTimeMillis() < deadline) {
            SourceCensus.Batch batch = census.sweepBatch();
            if (batch.claimed() && batch.judged() == 0) {
                return;
            }
            if (batch.judged() == 0) {
                Thread.sleep(50);
            }
        }
    }

    /** Whether the head stands at or past this entry's (txid, id), in the database's order. */
    private boolean headHasPassed(UUID entry) throws SQLException {
        return "t".equals(column("SELECT (h.position_txid, h.position_id) >= (e.txid, e.id) FROM accounting.source_census_head h"
                + " JOIN ledger.journal_entry e ON e.id = '" + entry + "'"));
    }

    private String findingKindOf(UUID entry) throws SQLException {
        return column("SELECT kind FROM accounting.source_census_finding WHERE entry_id = '" + entry + "'");
    }

    private void insertTransfer(UUID transferId, long amountMinor, UUID source, UUID destination) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement()) {
            statement.executeUpdate("INSERT INTO transfers.transfer (id, customer_id, source_account_id, destination_account_id, amount_minor, currency, scale,"
                    + " reference, status, journal_entry_id, initiated_by, initiated_at) VALUES ('" + transferId + "', '" + IDS.next() + "', '" + source + "', '" + destination
                    + "', " + amountMinor + ", 'EUR', 2, 'p15-035 fixture', 'COMPLETED', '" + IDS.next() + "', '" + IDS.next() + "', statement_timestamp())");
        }
    }

    private LedgerAccount wallet() throws SQLException {
        return ownedAccount(AccountPurpose.CUSTOMER_WALLET);
    }

    private LedgerAccount payable() throws SQLException {
        return ownedAccount(AccountPurpose.MERCHANT_PAYABLE);
    }

    private LedgerAccount ownedAccount(AccountPurpose purpose) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount account = accounts.createOrConverge(app, LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY, purpose, EUR, IDS.next())).account();
            app.commit();
            return account;
        }
    }

    private LedgerAccount operational(AccountPurpose purpose) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return accounts.findOperational(app, purpose, EUR).orElseThrow();
        }
    }

    private UUID post(String key, LedgerAccount debit, LedgerAccount credit) throws SQLException {
        return post(key, debit, credit, IDS);
    }

    /** Through the ledger's own door, the entry's id minted by {@code generator}: a forged or skewed id is simply one from another clock. */
    private UUID post(String key, LedgerAccount debit, LedgerAccount credit, IdGenerator generator) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            UUID entry = posting(generator).post(app, command(key, debit, credit)).entryId().value();
            app.commit();
            return entry;
        }
    }

    private PostingCommand command(String key, LedgerAccount debit, LedgerAccount credit) {
        LocalDate day = LocalDate.now(CLOCK.withZone(ZoneOffset.UTC));
        return new PostingCommand(key, day, day, "p15-035-fixture", List.of(
                new JournalLine(debit.id(), Direction.DEBIT, Money.ofMinorUnits(1234, EUR)),
                new JournalLine(credit.id(), Direction.CREDIT, Money.ofMinorUnits(1234, EUR))));
    }

    private static PostingService posting(IdGenerator entries) {
        return new PostingService(new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1), Duration.ofMinutes(5)),
                new JdbcJournalEntryStore(entries), new JdbcAuditWriter(), new JdbcOutboxWriter(), new JdbcBalanceProjection(), entries, CLOCK, PostingObserver.NONE);
    }

    private static Long scalar(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String column(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            return row.next() ? row.getString(1) : null;
        }
    }
}
