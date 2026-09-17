package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.TrialBalance;
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
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The trial balance against a real PostgreSQL (`P3-TSK-019`, {@code INV-ACC-01}).
 *
 * <p><strong>The injection rides the deferral.</strong> V004's balance constraint is a
 * {@code DEFERRABLE INITIALLY DEFERRED} trigger, so an open transaction can hold unbalanced
 * rows the constraint has not yet judged — which is exactly what a trigger-less writer's
 * committed rows would look like to the sweep's one {@code SELECT}. The test inserts raw
 * unbalanced SQL, sweeps on the same connection (the same read path production takes over
 * committed state), and rolls back: nothing commits, no trigger is disabled, no cleanup can
 * fail and leak corruption into sibling suites.
 *
 * <p><strong>The concurrency half demonstrates the design's no-{@code IN_FLIGHT} claim</strong>:
 * one statement reads one snapshot, a snapshot never contains half an entry, and every
 * committed entry balances — so sweeps racing live posters read zero every time.
 */
@Tag("database")
@DisplayName("the trial balance (P3-TSK-019)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class TrialBalanceDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 17);

    private final TrialBalance trialBalance = new TrialBalance();
    private final LedgerAccountStore<Connection> ledgerAccounts = new JdbcLedgerAccountStore();

    @Test
    @DisplayName("a committed journal sweeps balanced, and injected imbalances are detected"
            + " per currency - the equal-raw-sums scale probe and the cross-currency subsidy")
    void injectedImbalancesAreDetectedPerCurrency() throws Exception {
        // The positive control: a real balanced posting, committed, and a sweep over
        // committed state answers "verified, nothing out of balance" - without it the
        // detection assertions below could pass against a sweep that flags everything.
        postBalancedUsd(100);
        try (Connection app = DatabaseRoles.application()) {
            TrialBalance.Report clean = trialBalance.sweep(app);
            assertThat(clean.currenciesVerified()).as("the sweep verified something").isPositive();
            assertThat(clean.outOfBalance())
                    .as("every committed entry balanced at COMMIT, so every snapshot does")
                    .isEmpty();
        }

        // The injection: one raw entry, four unbalanced lines, inside an open transaction
        // the deferred constraint has not yet judged. Three shapes at once:
        //   USD - equal raw sums at DIFFERENT scales (1500@2 vs 1500@3: 15.00 vs 1.500),
        //         the P3-TSK-005 scales-clause probe at system level - a sweep comparing
        //         raw minor units reads it as balanced;
        //   EUR - a debit excess; GBP - a credit excess of the same decimal value, so a
        //         sweep that collapsed the currency buckets would watch them cancel.
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                UUID entry = IDS.next();
                execute(
                        app,
                        "INSERT INTO ledger.journal_entry (id, posting_date, value_date,"
                                + " entry_type, reference, actor_id, correlation_id,"
                                + " causation_id, idempotency_scope, created_at)"
                                + " VALUES (?, ?, ?, 'POSTING', 'trial-balance-probe',"
                                + " 'system', 'trial-probe', 'trial-probe',"
                                + " 'test.trial-probe', now())",
                        entry,
                        DATE,
                        DATE);
                insertLine(app, entry, clearing("USD"), "DEBIT", 1500, "USD", 2, 1);
                insertLine(app, entry, clearing("USD"), "CREDIT", 1500, "USD", 3, 2);
                insertLine(app, entry, clearing("EUR"), "DEBIT", 500, "EUR", 2, 3);
                insertLine(app, entry, clearing("GBP"), "CREDIT", 500, "GBP", 2, 4);

                TrialBalance.Report report = trialBalance.sweep(app);
                assertThat(report.outOfBalance())
                        .as("each currency is judged alone, at its decimal value")
                        .contains(
                                CurrencyCode.of("USD"),
                                CurrencyCode.of("EUR"),
                                CurrencyCode.of("GBP"));
            } finally {
                // Nothing was ever committed: the deferred trigger never fired, and the
                // sibling suites' global sweeps never see these rows.
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("sweeps racing live posters read zero every time - one statement, one"
            + " snapshot, and a snapshot never contains half an entry")
    void theSweepIsSafeUnderConcurrentPosting() throws Exception {
        LedgerAccount wallet = givenAnOwnedUsdWallet();
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger posted = new AtomicInteger();
        AtomicReference<Throwable> posterFailure = new AtomicReference<>();

        List<Thread> posters = new ArrayList<>();
        for (int poster = 0; poster < 4; poster++) {
            Thread thread =
                    new Thread(
                            () -> {
                                try {
                                    while (!stop.get()) {
                                        postBalancedUsdTo(wallet, 100);
                                        posted.incrementAndGet();
                                    }
                                } catch (Throwable failure) {
                                    posterFailure.set(failure);
                                }
                            });
            thread.start();
            posters.add(thread);
        }

        // The storm is ended by the SWEEPER (the P3-TST-001 overlap-by-construction rule):
        // at least 8 sweeps complete while at least 40 entries commit, so sweeps and
        // commits provably interleave rather than merely queue.
        int sweeps = 0;
        try (Connection app = DatabaseRoles.application()) {
            while (sweeps < 8 || posted.get() < 40) {
                TrialBalance.Report report = trialBalance.sweep(app);
                assertThat(report.outOfBalance())
                        .as("sweep %s while %s entries had committed", sweeps, posted.get())
                        .isEmpty();
                sweeps++;
                if (posterFailure.get() != null) {
                    break;
                }
            }
        } finally {
            stop.set(true);
            for (Thread thread : posters) {
                thread.join(Duration.ofSeconds(30).toMillis());
            }
        }
        assertThat(posterFailure.get()).as("every poster posted cleanly").isNull();
        assertThat(posted.get()).isGreaterThanOrEqualTo(40);
        assertThat(sweeps).isGreaterThanOrEqualTo(8);
    }

    // -----------------------------------------------------------------
    // Fixtures

    /** The operational clearing account's id for {@code currency} - seeded per currency. */
    private UUID clearing(String currency) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return ledgerAccounts
                    .findOperational(
                            app, AccountPurpose.SETTLEMENT_CLEARING, CurrencyCode.of(currency))
                    .orElseThrow()
                    .id()
                    .value();
        }
    }

    private LedgerAccount givenAnOwnedUsdWallet() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            // The store's savepoint idiom needs a real transaction (autocommit refused).
            app.setAutoCommit(false);
            LedgerAccount fresh =
                    LedgerAccount.owned(
                            IDS,
                            CLOCK,
                            AccountType.LIABILITY,
                            AccountPurpose.CUSTOMER_WALLET,
                            USD,
                            IDS.next());
            LedgerAccount created = ledgerAccounts.createOrConverge(app, fresh).account();
            app.commit();
            return created;
        }
    }

    private void postBalancedUsd(long minorUnits) throws Exception {
        postBalancedUsdTo(givenAnOwnedUsdWallet(), minorUnits);
    }

    /** A committed, balanced clearing-vs-wallet posting - the real write path. */
    private void postBalancedUsdTo(LedgerAccount wallet, long minorUnits) throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            UUID clearingUsd = clearing("USD");
            postingService()
                    .post(
                            app,
                            new PostingCommand(
                                    "trial-" + IDS.next(),
                                    DATE,
                                    DATE,
                                    "trial-balance-fixture",
                                    List.of(
                                            new JournalLine(
                                                    com.finapp.ledger.LedgerAccountId.of(
                                                            clearingUsd),
                                                    Direction.DEBIT,
                                                    Money.ofMinorUnits(minorUnits, USD)),
                                            new JournalLine(
                                                    wallet.id(),
                                                    Direction.CREDIT,
                                                    Money.ofMinorUnits(minorUnits, USD)))));
            app.commit();
        }
    }

    private static PostingService postingService() {
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
                CLOCK);
    }

    private static void insertLine(
            Connection connection,
            UUID entry,
            UUID account,
            String direction,
            long amountMinor,
            String currency,
            int scale,
            int seq)
            throws SQLException {
        execute(
                connection,
                "INSERT INTO ledger.journal_line (id, entry_id, ledger_account_id, direction,"
                        + " amount_minor, currency, scale, seq)"
                        + " VALUES (?, ?, ?, '" + direction + "', " + amountMinor + ", '"
                        + currency + "', " + scale + ", " + seq + ")",
                IDS.next(),
                entry,
                account);
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }
}
