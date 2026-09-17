package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounts.AccountHolderVerification;
import com.finapp.accounts.AccountOpening;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.JdbcCustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.app.accounts.VerifiedAccountHolder;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.Direction;
import com.finapp.ledger.BalanceDisplay.DisplayedBalance;
import com.finapp.ledger.Hold;
import com.finapp.ledger.HoldExceedsAvailableBalanceException;
import com.finapp.ledger.HoldId;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.HoldStatus;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceDisplay;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcHoldStore;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.party.JdbcPartyStore;
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
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Holds against available balance (`P3-TSK-015`, {@code INV-BAL-04}, {@code INV-CON-01}),
 * against a live PostgreSQL — the phase's sharpest contention point.
 *
 * <p>The race tests assert the <strong>coordination, not only the outcome</strong>: the
 * deterministic interleaving observes the losing placer Lock-waiting in
 * {@code pg_stat_activity} before the winner commits (the {@code P0-TST-004}/{@code
 * P2-TSK-015} idiom), because an outcome-only test passes against both the right and the
 * wrong mechanism — and the wrong mechanism here is an availability check on a snapshot
 * taken before the lock was granted.
 */
@Tag("database")
@DisplayName("holds: available balance under contention (P3-TSK-015)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class HoldDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 17);

    private final CustomerAccountStore<Connection> accounts = new JdbcCustomerAccountStore();
    private final LedgerAccountStore<Connection> ledgerAccounts = new JdbcLedgerAccountStore();
    private final JdbcHoldStore holds = new JdbcHoldStore();
    private final AccountHolderVerification<Connection> holders =
            new VerifiedAccountHolder(new JdbcPartyStore());

    private HoldService holdService() {
        return new HoldService(
                ledgerAccounts,
                new JdbcBalanceDerivation(),
                holds,
                new JdbcBalanceProjection(),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK);
    }

    @Test
    @DisplayName("a hold is bounded by available balance: exactly available accepted, one"
            + " minor unit more refused with nothing written")
    void aHoldIsBoundedByAvailableBalance() throws Exception {
        Wallet wallet = fundedWallet(1000);
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(wallet.actor());
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);

            long placedAuditBefore = auditRowsFor(app, "ledger.HoldPlaced");
            long placedEventsBefore = eventRowsFor(app, "ledger.HoldPlaced");

            // The boundary: a hold of exactly the available balance is legal - INV-BAL-04
            // forbids NEGATIVE availability, and zero is not negative.
            Hold full =
                    holdService().place(app, wallet.account().id(), Money.ofMinorUnits(1000, USD));
            app.commit();
            assertThat(full.status()).isEqualTo(HoldStatus.ACTIVE);
            assertThat(holdsMinorOf(app, wallet.account())).isEqualTo(1000);

            // The acting call recorded and announced - once (INV-AUD-01, INV-EVT-01).
            assertThat(auditRowsFor(app, "ledger.HoldPlaced"))
                    .isEqualTo(placedAuditBefore + 1);
            assertThat(eventRowsFor(app, "ledger.HoldPlaced"))
                    .isEqualTo(placedEventsBefore + 1);

            // One more minor unit is the refusal - amount-free (INV-AUD-02) - and the
            // refusal writes nothing: no row, no projection change, no record, no event.
            long auditBefore = auditRowsFor(app, "ledger.HoldPlaced");
            long eventsBefore = eventRowsFor(app, "ledger.HoldPlaced");
            assertThatThrownBy(
                            () ->
                                    holdService()
                                            .place(
                                                    app,
                                                    wallet.account().id(),
                                                    Money.ofMinorUnits(1, USD)))
                    .isInstanceOf(HoldExceedsAvailableBalanceException.class)
                    .hasMessageNotContaining("1000")
                    .hasMessageNotContaining("10.00");
            app.rollback();
            assertThat(activeHoldRowsFor(app, wallet.account())).isEqualTo(1);
            assertThat(holdsMinorOf(app, wallet.account())).isEqualTo(1000);
            assertThat(auditRowsFor(app, "ledger.HoldPlaced")).isEqualTo(auditBefore);
            assertThat(eventRowsFor(app, "ledger.HoldPlaced")).isEqualTo(eventsBefore);
        }
    }

    @Test
    @DisplayName("release restores availability exactly, and a second release converges with"
            + " nothing written")
    void releaseRestoresAvailabilityExactly() throws Exception {
        Wallet wallet = fundedWallet(1000);
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(wallet.actor());
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);

            Hold first =
                    holdService().place(app, wallet.account().id(), Money.ofMinorUnits(1000, USD));
            app.commit();

            long releasedAuditBefore = auditRowsFor(app, "ledger.HoldReleased");
            long releasedEventsBefore = eventRowsFor(app, "ledger.HoldReleased");
            HoldService.Release release =
                    holdService().release(app, first.id()).orElseThrow();
            app.commit();
            assertThat(release.released()).isTrue();
            assertThat(release.hold().status()).isEqualTo(HoldStatus.RELEASED);
            assertThat(holdsMinorOf(app, wallet.account())).isZero();
            assertThat(auditRowsFor(app, "ledger.HoldReleased"))
                    .isEqualTo(releasedAuditBefore + 1);
            assertThat(eventRowsFor(app, "ledger.HoldReleased"))
                    .isEqualTo(releasedEventsBefore + 1);

            // EXACTLY: the full amount is placeable again - not almost, not off by a unit.
            Hold second =
                    holdService().place(app, wallet.account().id(), Money.ofMinorUnits(1000, USD));
            app.commit();
            assertThat(second.status()).isEqualTo(HoldStatus.ACTIVE);

            // The retried release converges: no second decrement (holds_minor still carries
            // the second hold, untouched), no second record, no second event.
            long auditBefore = auditRowsFor(app, "ledger.HoldReleased");
            long eventsBefore = eventRowsFor(app, "ledger.HoldReleased");
            HoldService.Release retried =
                    holdService().release(app, first.id()).orElseThrow();
            app.commit();
            assertThat(retried.released()).as("a retry is not a second act").isFalse();
            assertThat(retried.hold().status()).isEqualTo(HoldStatus.RELEASED);
            assertThat(holdsMinorOf(app, wallet.account())).isEqualTo(1000);
            assertThat(auditRowsFor(app, "ledger.HoldReleased")).isEqualTo(auditBefore);
            assertThat(eventRowsFor(app, "ledger.HoldReleased")).isEqualTo(eventsBefore);

            // And a hold nobody ever placed is an empty answer, not an invented one.
            assertThat(holdService().release(app, HoldId.next(IDS))).isEmpty();
        }
    }

    @Test
    @DisplayName("two placements serialize on the account lock - the loser observed blocked,"
            + " then refused on the winner's committed hold")
    void theLosingPlacerIsObservedBlockedThenRefused() throws Exception {
        Wallet wallet = fundedWallet(1000);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try (Connection winner = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(wallet.actor());
                CorrelationContext.Scope flow = flow()) {
            winner.setAutoCommit(false);
            holdService()
                    .place(winner, wallet.account().id(), Money.ofMinorUnits(600, USD));
            // Uncommitted: the account row's FOR UPDATE is held.

            Future<Object> loser =
                    pool.submit(
                            () -> {
                                try (Connection own = DatabaseRoles.application();
                                        SecurityContext.Scope a =
                                                SecurityContext.enter(wallet.actor());
                                        CorrelationContext.Scope f = flow()) {
                                    own.setAutoCommit(false);
                                    try {
                                        Hold placed =
                                                holdService()
                                                        .place(
                                                                own,
                                                                wallet.account().id(),
                                                                Money.ofMinorUnits(600, USD));
                                        own.commit();
                                        return placed;
                                    } catch (HoldExceedsAvailableBalanceException refused) {
                                        own.rollback();
                                        return refused;
                                    }
                                }
                            });

            // The coordination assertion: the loser is Lock-waiting on the account row.
            // Without the FOR UPDATE the loser decides on a snapshot that predates the
            // winner's hold, and both 600s "fit" in 1000 (M1's mutation).
            awaitBlockedOn("ledger.ledger_account", "FOR UPDATE");
            assertThat(loser.isDone())
                    .as("the loser must be waiting on the winner's lock, not deciding on a"
                            + " stale snapshot")
                    .isFalse();
            winner.commit();
            assertThat(loser.get(30, TimeUnit.SECONDS))
                    .as("resumed after the winner committed, the loser's fresh derivation"
                            + " sees the standing hold and refuses")
                    .isInstanceOf(HoldExceedsAvailableBalanceException.class);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("ten concurrent placements respect available balance - counted in the table,"
            + " never inferred")
    void tenConcurrentPlacementsRespectAvailableBalance() throws Exception {
        Wallet wallet = fundedWallet(3000);
        int instances = 10;
        CyclicBarrier start = new CyclicBarrier(instances);
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        try {
            List<Callable<Boolean>> placements = new ArrayList<>();
            for (int i = 0; i < instances; i++) {
                placements.add(
                        () -> {
                            try (Connection own = DatabaseRoles.application();
                                    SecurityContext.Scope actor =
                                            SecurityContext.enter(wallet.actor());
                                    CorrelationContext.Scope flow = flow()) {
                                own.setAutoCommit(false);
                                start.await();
                                try {
                                    holdService()
                                            .place(
                                                    own,
                                                    wallet.account().id(),
                                                    Money.ofMinorUnits(1000, USD));
                                    own.commit();
                                    return true;
                                } catch (HoldExceedsAvailableBalanceException refused) {
                                    own.rollback();
                                    return false;
                                }
                            }
                        });
            }
            int accepted = 0;
            for (Future<Boolean> outcome : pool.invokeAll(placements)) {
                if (outcome.get()) {
                    accepted++;
                }
            }
            assertThat(accepted)
                    .as("3000 available admits exactly three holds of 1000; the other seven"
                            + " are refused (INV-BAL-04, INV-CON-01)")
                    .isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }

        try (Connection app = DatabaseRoles.application()) {
            // Counted in the table, and the projection followed: the sum of ACTIVE rows -
            // folded here as the decision folds them - equals holds_minor exactly.
            assertThat(activeHoldRowsFor(app, wallet.account())).isEqualTo(3);
            assertThat(activeHoldSumFor(app, wallet.account())).isEqualTo(3000);
            assertThat(holdsMinorOf(app, wallet.account())).isEqualTo(3000);
        }
    }

    @Test
    @DisplayName("a crash mid-placement is all-or-nothing: the rollback leaves no trace")
    void aCrashMidPlacementIsAllOrNothing() throws Exception {
        Wallet wallet = fundedWallet(1000);
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(wallet.actor());
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            long auditBefore = auditRowsFor(app, "ledger.HoldPlaced");
            long eventsBefore = eventRowsFor(app, "ledger.HoldPlaced");

            holdService().place(app, wallet.account().id(), Money.ofMinorUnits(700, USD));
            // The instance dies before commit: everything the placement wrote goes with it.
            app.rollback();

            assertThat(activeHoldRowsFor(app, wallet.account())).isZero();
            assertThat(holdsMinorOf(app, wallet.account())).isZero();
            assertThat(auditRowsFor(app, "ledger.HoldPlaced")).isEqualTo(auditBefore);
            assertThat(eventRowsFor(app, "ledger.HoldPlaced")).isEqualTo(eventsBefore);
        }
    }

    @Test
    @DisplayName("a corrupted projection changes no decision (INV-BAL-05, behaviourally)")
    void aCorruptedProjectionChangesNoDecision() throws Exception {
        Wallet wallet = fundedWallet(1000);
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(wallet.actor());
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);

            // Corrupt holds_minor through the application role's own narrow UPDATE grant -
            // the closest stand-in for the writer nobody wrote (P3-TSK-010's idiom). If the
            // decision read the projection, 900 of phantom holds would refuse this
            // placement; deriving from the authoritative rows - no ACTIVE hold exists - it
            // accepts. The drift this plants is exactly what the verification job exists to
            // detect; the decision must not be its victim.
            execute(
                    app,
                    "UPDATE ledger.account_balance SET holds_minor = 900 WHERE"
                            + " ledger_account_id = ?",
                    wallet.account().id().value());
            app.commit();

            Hold placed =
                    holdService().place(app, wallet.account().id(), Money.ofMinorUnits(1000, USD));
            app.commit();
            assertThat(placed.status()).isEqualTo(HoldStatus.ACTIVE);

            // P3-TSK-020: the verification job now compares holds_minor against the ACTIVE
            // rows, so the planted corruption must not stay committed in the shared
            // container - restored to the fold of what actually stands (the one 1000 hold
            // above), or every later suite's global sweep inherits this account's drift.
            execute(
                    app,
                    "UPDATE ledger.account_balance SET holds_minor = 1000 WHERE"
                            + " ledger_account_id = ?",
                    wallet.account().id().value());
            app.commit();
        }
    }

    @Test
    @DisplayName("the display shows the triplet, and available is settled minus holds - the"
            + " limit P3-TSK-013 recorded, owned here")
    void theDisplayedAvailableSubtractsHolds() throws Exception {
        Wallet wallet = fundedWallet(1000);
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(wallet.actor());
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            holdService().place(app, wallet.account().id(), Money.ofMinorUnits(400, USD));
            app.commit();

            // The display is lock-free and reads the projection (P3-TSK-013's stance); with
            // holds no longer structurally zero, its subtraction is finally load-bearing -
            // a display answering available = settled would show 10.00 here.
            List<DisplayedBalance> balances =
                    new JdbcBalanceDisplay().balancesFor(app, wallet.ownerRef());
            assertThat(balances).hasSize(1);
            DisplayedBalance usd = balances.get(0);
            assertThat(usd.settled()).isEqualTo(Money.ofPersisted(1000, USD, 2));
            assertThat(usd.holds()).isEqualTo(Money.ofPersisted(400, USD, 2));
            assertThat(usd.available()).isEqualTo(Money.ofPersisted(600, USD, 2));
        }
    }

    // -----------------------------------------------------------------
    // Fixtures

    private record Wallet(UUID party, UUID customer, UUID ownerRef, LedgerAccount account) {
        Actor actor() {
            return new Actor("identity-" + customer, ActorType.CUSTOMER);
        }
    }

    /** A verified holder's USD wallet ledger account, funded with {@code minorUnits}. */
    private Wallet fundedWallet(long minorUnits) throws SQLException {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Hold Holder', now() - interval '2 hour')",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    customer,
                    party);
        }
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor =
                        SecurityContext.enter(
                                new Actor("identity-" + customer, ActorType.CUSTOMER));
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            CustomerAccountStore.Creation creation =
                    new AccountOpening(
                                    accounts,
                                    ledgerAccounts,
                                    holders,
                                    new JdbcAuditWriter(),
                                    new JdbcOutboxWriter(),
                                    IDS,
                                    CLOCK)
                            .open(app, party, ProductType.WALLET, USD);
            app.commit();
            LedgerAccount wallet =
                    ledgerAccounts
                            .findOwned(
                                    app,
                                    creation.account().id().value(),
                                    AccountPurpose.CUSTOMER_WALLET,
                                    USD)
                            .orElseThrow();
            if (minorUnits > 0) {
                LedgerAccount clearing =
                        ledgerAccounts
                                .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, USD)
                                .orElseThrow();
                new PostingService(
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
                                CLOCK, PostingObserver.NONE)
                        .post(
                                app,
                                new PostingCommand(
                                        "hold-fixture-" + IDS.next(),
                                        DATE,
                                        DATE,
                                        "hold-fixture",
                                        List.of(
                                                new JournalLine(
                                                        clearing.id(),
                                                        Direction.DEBIT,
                                                        Money.ofMinorUnits(minorUnits, USD)),
                                                new JournalLine(
                                                        wallet.id(),
                                                        Direction.CREDIT,
                                                        Money.ofMinorUnits(
                                                                minorUnits, USD)))));
                app.commit();
            }
            return new Wallet(party, customer, creation.account().id().value(), wallet);
        }
    }

    /** The {@code P0-TST-004} idiom: the losing side observed Lock-waiting, never assumed. */
    private static void awaitBlockedOn(String table, String queryMarker)
            throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try (Connection observer = DatabaseRoles.application();
                PreparedStatement select =
                        observer.prepareStatement(
                                "SELECT count(*) FROM pg_stat_activity"
                                        + " WHERE wait_event_type = 'Lock'"
                                        + " AND query LIKE '%" + table + "%'"
                                        + " AND query LIKE '%" + queryMarker + "%'")) {
            while (System.nanoTime() < deadline) {
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    if (row.getLong(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(25);
            }
        }
        throw new AssertionError(
                "the losing side never blocked on " + table + " - without the lock the race"
                        + " is decided by a stale snapshot");
    }

    // -----------------------------------------------------------------
    // Counters - in the tables, never inferred from return values

    private static long activeHoldRowsFor(Connection app, LedgerAccount account)
            throws SQLException {
        try (PreparedStatement count =
                app.prepareStatement(
                        "SELECT count(*) FROM ledger.hold WHERE ledger_account_id = ?"
                                + " AND status = 'ACTIVE'")) {
            count.setObject(1, account.id().value());
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long activeHoldSumFor(Connection app, LedgerAccount account)
            throws SQLException {
        try (PreparedStatement sum =
                app.prepareStatement(
                        // A fixture checksum over stored values, not monetary arithmetic:
                        // the question is "what did the race commit", not "what is owed".
                        "SELECT coalesce(sum(amount_minor), 0) FROM ledger.hold"
                                + " WHERE ledger_account_id = ? AND status = 'ACTIVE'")) {
            sum.setObject(1, account.id().value());
            try (ResultSet row = sum.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long holdsMinorOf(Connection app, LedgerAccount account)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT holds_minor FROM ledger.account_balance"
                                + " WHERE ledger_account_id = ?")) {
            read.setObject(1, account.id().value());
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static long auditRowsFor(Connection app, String operation) throws SQLException {
        try (PreparedStatement count =
                app.prepareStatement(
                        "SELECT count(*) FROM platform.audit_record WHERE operation = ?")) {
            count.setString(1, operation);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long eventRowsFor(Connection app, String eventType) throws SQLException {
        try (PreparedStatement count =
                app.prepareStatement(
                        "SELECT count(*) FROM platform.outbox_event WHERE event_type = ?")) {
            count.setString(1, eventType);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
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

    private static CorrelationContext.Scope flow() {
        return CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
    }
}
