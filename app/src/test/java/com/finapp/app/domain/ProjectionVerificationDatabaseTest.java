package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.BalanceDerivation;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.ProjectionVerification;
import com.finapp.ledger.ProjectionVerification.Verdict;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The verification job against a live PostgreSQL (`P3-TSK-010`): the comparison — not
 * anybody's confidence — is the evidence the projection is right ({@code INV-BAL-02},
 * ADR-0041 rule 2).
 *
 * <p>The injected corruptions use the application role's own narrow {@code UPDATE} grant on
 * the accumulating columns — the closest available stand-in for "a writer nobody wrote",
 * which is exactly the writer the comparison exists to catch. The raw-SQL plants are the
 * other stand-in: entries the projection never saw, `P3-TSK-009`'s recorded limit, detected
 * here.
 */
@Tag("database")
@DisplayName("the projection verification under drift, raw writers and live posting (P3-TSK-010)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class ProjectionVerificationDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 16);

    private final LedgerAccountStore<Connection> accounts = new JdbcLedgerAccountStore();
    private final ProjectionVerification verification =
            new ProjectionVerification(new JdbcBalanceDerivation());

    @Test
    @DisplayName("a clean account verifies clean, and each injected corruption is detected")
    void aCleanAccountVerifiesCleanAndInjectedDriftIsDetected() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount wallet = wallet(app);
            LedgerAccount clearing = operational(app);
            post(app, entry(clearing, Direction.DEBIT, wallet, Direction.CREDIT, 5000));
            post(app, entry(wallet, Direction.DEBIT, clearing, Direction.CREDIT, 1200));
            app.commit();

            assertThat(verification.verdictOf(app, wallet.id())).isEqualTo(Verdict.CLEAN);

            // The number corrupted: settled disagrees while the watermark still matches.
            nudge(app, wallet.id(), "posted_minor", +1);
            assertThat(verification.verdictOf(app, wallet.id()))
                    .as("a corrupted settled number is drift")
                    .isEqualTo(Verdict.DRIFTING);
            nudge(app, wallet.id(), "posted_minor", -1);
            assertThat(verification.verdictOf(app, wallet.id()))
                    .as("the positive control: the detection was the corruption")
                    .isEqualTo(Verdict.CLEAN);

            // The watermark corrupted: settled still agrees, so only the seq-vs-count
            // comparison can see it - which is what makes this its own probe.
            nudge(app, wallet.id(), "last_entry_seq", +1);
            assertThat(verification.verdictOf(app, wallet.id()))
                    .as("a watermark disagreeing with the applied-entry count is drift")
                    .isEqualTo(Verdict.DRIFTING);
            nudge(app, wallet.id(), "last_entry_seq", -1);
            assertThat(verification.verdictOf(app, wallet.id())).isEqualTo(Verdict.CLEAN);
        }
    }

    @Test
    @DisplayName("holds_minor disagreeing with the ACTIVE hold rows is drift - P3-TSK-015's"
            + " owned remainder, landed by P3-TSK-020")
    void aCorruptedHoldsMinorIsDetected() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount wallet = wallet(app);
            post(app, entry(operational(app), Direction.DEBIT, wallet, Direction.CREDIT, 5000));
            app.commit();

            assertThat(verification.verdictOf(app, wallet.id())).isEqualTo(Verdict.CLEAN);

            // No ACTIVE hold exists, so the only holds_minor the rows explain is zero. A
            // phantom 900 is exactly the corruption HoldDatabaseTest proves the DECISION
            // ignores - and what this comparison exists to surface (reported, never
            // repaired). The row and its holds are read in ONE statement, so no bracket is
            // needed and no interleaving can fake agreement. Uncommitted: the close rolls
            // it back, and nothing leaks into sibling sweeps.
            nudge(app, wallet.id(), "holds_minor", +900);
            assertThat(verification.verdictOf(app, wallet.id()))
                    .as("a holds_minor no ACTIVE row explains is drift")
                    .isEqualTo(Verdict.DRIFTING);
            nudge(app, wallet.id(), "holds_minor", -900);
            assertThat(verification.verdictOf(app, wallet.id()))
                    .as("the positive control: the detection was the corruption")
                    .isEqualTo(Verdict.CLEAN);
        }
    }

    @Test
    @DisplayName("the raw-SQL writer is detected, and the sweep reports it")
    void theRawSqlWriterIsDetectedAndTheSweepReportsIt() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);

            // Entries the projection never saw: rows absent while lines exist.
            LedgerAccount bypassedDebit = wallet(app);
            LedgerAccount bypassedCredit = wallet(app);
            plantRawEntry(app, bypassedDebit.id(), bypassedCredit.id(), 700, 2);

            // A history the derivation refuses while a projection number stands: posted
            // normally at scale 2, then a raw scale-3 entry - unverifiable is not clean.
            LedgerAccount mixed = wallet(app);
            LedgerAccount counterparty = wallet(app);
            post(app, entry(operational(app), Direction.DEBIT, mixed, Direction.CREDIT, 1500));
            plantRawEntry(app, mixed.id(), counterparty.id(), 777, 3);
            app.commit();

            assertThat(verification.verdictOf(app, bypassedDebit.id()))
                    .as("lines with no projection row: the projection was bypassed")
                    .isEqualTo(Verdict.DRIFTING);
            assertThat(verification.verdictOf(app, bypassedCredit.id()))
                    .isEqualTo(Verdict.DRIFTING);
            assertThat(verification.verdictOf(app, mixed.id()))
                    .as("underivable with a standing projection number is drift, not a skip")
                    .isEqualTo(Verdict.DRIFTING);
            assertThat(verification.verdictOf(app, counterparty.id()))
                    .isEqualTo(Verdict.DRIFTING);

            // The sweep sees them all - on a shared database other suites' raw plants are
            // legitimately counted too, so the bound is a floor, never an equality.
            ProjectionVerification.Report report = verification.verify(app);
            assertThat(report.drifting()).isGreaterThanOrEqualTo(4);
            assertThat(report.driftingAccounts()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("an entry committing mid-comparison is IN_FLIGHT, never false drift")
    void anEntryCommittingMidComparisonIsInFlightNeverFalseDrift() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount wallet = wallet(app);
            LedgerAccount clearing = operational(app);
            post(app, entry(clearing, Direction.DEBIT, wallet, Direction.CREDIT, 900));
            app.commit();

            // The deterministic interleaving (the P1-TSK-012 decorator idiom): the moment
            // the verifier asks for the derivation, another instance commits a posting to
            // the same account - the exact window the seq bracket exists for. No sleeps, no
            // timing luck: the commit happens inside the comparison, every run.
            AtomicBoolean interleaved = new AtomicBoolean(false);
            BalanceDerivation<Connection> interleaving =
                    (uow, account, asOf) -> {
                        if (account.equals(wallet.id()) && interleaved.compareAndSet(false, true)) {
                            postOnAnotherInstance(
                                    entry(clearing, Direction.DEBIT, wallet,
                                            Direction.CREDIT, 600));
                        }
                        return new JdbcBalanceDerivation().derive(uow, account, asOf);
                    };

            Verdict midFlight =
                    new ProjectionVerification(interleaving).verdictOf(app, wallet.id());
            assertThat(interleaved).as("the interleaving actually happened").isTrue();
            assertThat(midFlight)
                    .as("a mid-comparison commit is tolerated by the watermark, never"
                            + " reported as drift and never waved through as clean")
                    .isEqualTo(Verdict.IN_FLIGHT);

            assertThat(verification.verdictOf(app, wallet.id()))
                    .as("the next run settles it")
                    .isEqualTo(Verdict.CLEAN);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private PostingService service() {
        return new PostingService(
                new IdempotentExecutor(
                        new JdbcIdempotencyRecordStore(),
                        CLOCK,
                        Duration.ofDays(1),
                        Duration.ofMinutes(5)),
                new JdbcJournalEntryStore(IDS),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                new JdbcBalanceProjection(),
                IDS,
                CLOCK, PostingObserver.NONE);
    }

    private void post(Connection app, PostingCommand command) {
        service().post(app, command);
    }

    /** A whole other instance: its own connection, scopes and commit (P0-TST-009). */
    private void postOnAnotherInstance(PostingCommand command) {
        try (Connection own = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            own.setAutoCommit(false);
            post(own, command);
            own.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException("the interleaved posting failed", failure);
        }
    }

    private LedgerAccount wallet(Connection app) {
        return accounts
                .createOrConverge(
                        app,
                        LedgerAccount.owned(
                                IDS, CLOCK, AccountType.LIABILITY,
                                AccountPurpose.CUSTOMER_WALLET, USD, IDS.next()))
                .account();
    }

    private LedgerAccount operational(Connection app) {
        return accounts
                .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, USD)
                .orElseThrow();
    }

    private static PostingCommand entry(
            LedgerAccount first, Direction firstSide,
            LedgerAccount second, Direction secondSide, long minorUnits) {
        return new PostingCommand(
                "verify-" + IDS.next(), DATE, DATE, "probe-event",
                List.of(
                        new JournalLine(first.id(), firstSide,
                                Money.ofMinorUnits(minorUnits, USD)),
                        new JournalLine(second.id(), secondSide,
                                Money.ofMinorUnits(minorUnits, USD))));
    }

    /** The "writer nobody wrote": the narrow grant moving an accumulating column. */
    private static void nudge(
            Connection app, LedgerAccountId account, String column, long by)
            throws SQLException {
        try (PreparedStatement corrupt =
                app.prepareStatement(
                        "UPDATE ledger.account_balance SET " + column + " = " + column
                                + " + ? WHERE ledger_account_id = ?")) {
            corrupt.setLong(1, by);
            corrupt.setObject(2, account.value());
            assertThat(corrupt.executeUpdate()).isEqualTo(1);
        }
        app.commit();
    }

    /**
     * The writer the projection never sees: a balanced raw-SQL entry at the given scale -
     * one scale per currency within the entry, so the deferred triggers admit it.
     */
    private static void plantRawEntry(
            Connection app, LedgerAccountId debit, LedgerAccountId credit, long minor,
            int scale) throws SQLException {
        Object entryId = IDS.next();
        try (PreparedStatement insert = app.prepareStatement(
                "INSERT INTO ledger.journal_entry (id, posting_date, value_date, entry_type,"
                        + " reference, reason, actor_id, correlation_id, causation_id,"
                        + " idempotency_scope, created_at)"
                        + " VALUES (?, ?, ?, 'POSTING', 'probe-event', NULL, 'probe', ?, ?,"
                        + " ?, ?)")) {
            insert.setObject(1, entryId);
            insert.setObject(2, DATE);
            insert.setObject(3, DATE);
            insert.setString(4, CorrelationId.generate(IDS).value());
            insert.setString(5, CorrelationId.generate(IDS).value());
            insert.setString(6, "probe:" + IDS.next());
            insert.setTimestamp(7, Timestamp.from(Instant.now(CLOCK)));
            insert.executeUpdate();
        }
        try (PreparedStatement insert = app.prepareStatement(
                "INSERT INTO ledger.journal_line (id, entry_id, ledger_account_id, direction,"
                        + " amount_minor, currency, scale, seq)"
                        + " VALUES (?, ?, ?, ?, ?, 'USD', ?, ?)")) {
            insert.setObject(1, IDS.next());
            insert.setObject(2, entryId);
            insert.setObject(3, debit.value());
            insert.setString(4, "DEBIT");
            insert.setLong(5, minor);
            insert.setInt(6, scale);
            insert.setInt(7, 0);
            insert.addBatch();
            insert.setObject(1, IDS.next());
            insert.setObject(2, entryId);
            insert.setObject(3, credit.value());
            insert.setString(4, "CREDIT");
            insert.setLong(5, minor);
            insert.setInt(6, scale);
            insert.setInt(7, 1);
            insert.addBatch();
            insert.executeBatch();
        }
    }

    private static CorrelationContext.Scope flow() {
        return CorrelationContext.enter(
                Correlation.startingWith(CorrelationId.generate(IDS)));
    }
}
