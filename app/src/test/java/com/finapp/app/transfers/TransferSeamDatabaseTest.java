package com.finapp.app.transfers;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounts.AccountHolderVerification;
import com.finapp.accounts.AccountOpening;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.JdbcCustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.app.accounts.VerifiedAccountHolder;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AvailableBalance;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcHoldStore;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
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
import com.finapp.transfers.FailureReason;
import com.finapp.transfers.JdbcTransferStore;
import com.finapp.transfers.PermitAllUntilPhase13;
import com.finapp.transfers.SeamVerdict;
import com.finapp.transfers.Transfer;
import com.finapp.transfers.TransferCommand;
import com.finapp.transfers.TransferExecution;
import com.finapp.transfers.TransferLimitCheck;
import com.finapp.transfers.TransferParticipants;
import com.finapp.transfers.TransferResult;
import com.finapp.transfers.TransferRiskDecision;
import com.finapp.transfers.TransferStatus;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The seams against a live PostgreSQL (`P4-TSK-010`): the in-lock contract <strong>observed
 * rather than stated</strong>, and the two reserved refusals proven to be committed domain
 * outcomes — which is what makes Phase 13's arrival change no contract.
 *
 * <h2>The in-lock probe is deterministic, never timed</h2>
 *
 * <p>A decorator seam, when consulted, opens its <em>own second connection</em> and attempts
 * {@code SELECT … FOR UPDATE NOWAIT} on the transfer's source account row: the lock held by
 * the execution's transaction answers {@code 55P03 lock_not_available}, and an execution that
 * consulted the seam outside the lock would hand the probe the row — so the
 * hoisted-above-the-lock mutation (the {@code P3-TST-002} shape: still consulted, still
 * permits, just too early) is caught by <em>where</em> the seam ran, which no outcome
 * assertion over a permit-all seam could ever see.
 */
@Tag("database")
@DisplayName("the limit and risk seams in the execution (P4-TSK-010)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class TransferSeamDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");

    private final CustomerAccountStore<Connection> products = new JdbcCustomerAccountStore();
    private final LedgerAccountStore<Connection> ledgerAccounts = new JdbcLedgerAccountStore();
    private final AccountHolderVerification<Connection> holders =
            new VerifiedAccountHolder(new JdbcPartyStore());
    private final TransferParticipants<Connection> participants =
            new JdbcTransferParticipants(
                    new JdbcPartyStore(), new JdbcCustomerAccountStore(),
                    new JdbcLedgerAccountStore());

    // ------------------------------------------------------------------

    @Test
    @DisplayName("both seams observe the source lock held when consulted - the in-lock"
            + " contract as a fact, not a javadoc")
    void bothSeamsObserveTheSourceLockHeld() throws Exception {
        Holder source = fundedHolder(10_00);
        Holder destination = holder();
        LockObservingSeam limits = new LockObservingSeam();
        LockObservingSeam risk = new LockObservingSeam();

        TransferResult judged =
                asInstance(source, app -> {
                    TransferResult r =
                            execution(limits, risk)
                                    .execute(app, command(source, destination, usd(3_00)));
                    app.commit();
                    return r;
                });

        // The judgement itself is ordinary - the probes permit - and each seam was consulted
        // exactly once, WITH the source row already locked by the execution's transaction.
        assertThat(judged.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(limits.consultations.get()).isEqualTo(1);
        assertThat(risk.consultations.get()).isEqualTo(1);
        assertThat(limits.observedHeld.get())
                .as("the limit seam must run under the held source lock (INV-CON-03)")
                .isTrue();
        assertThat(risk.observedHeld.get())
                .as("the risk seam must run under the held source lock (INV-CON-03)")
                .isTrue();
    }

    @Test
    @DisplayName("a refusing limit commits FAILED(LIMIT_REFUSED) with nothing posted, and the"
            + " retry replays the refusal")
    void aRefusingLimitCommitsItsReservedReason() throws Exception {
        Holder source = fundedHolder(10_00);
        Holder destination = holder();
        String key = "limit-" + IDS.next();
        TransferExecution refusingLimit =
                execution(new RefusingSeam(), new PermitAllUntilPhase13<>());

        TransferResult refused =
                asInstance(source, app -> {
                    TransferResult r =
                            refusingLimit.execute(
                                    app, command(key, source, destination, usd(3_00)));
                    app.commit();
                    return r;
                });
        assertThat(refused.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(refused.failureReason()).contains(FailureReason.LIMIT_REFUSED);
        assertThat(refused.replayed()).isFalse();

        // In the tables, never inferred: the committed row carries the seam's reserved reason
        // and no entry - V004's widened CHECK admitting exactly this - and the money never
        // moved: no journal entry references the transfer.
        try (Connection app = DatabaseRoles.application()) {
            assertThat(rowStatusAndReason(app, refused.transferId().value()))
                    .containsExactly("FAILED", "LIMIT_REFUSED");
            assertThat(entriesReferencing(app, refused.transferId().value())).isZero();
        }

        // The refusal is a definitive outcome (CommandResult.failed - the executor's own
        // documented rejected-transfer case): the same key replays it, and never re-consults
        // a seam that might answer differently tomorrow.
        TransferResult retried =
                asInstance(source, app -> {
                    TransferResult r =
                            refusingLimit.execute(
                                    app, command(key, source, destination, usd(3_00)));
                    app.commit();
                    return r;
                });
        assertThat(retried.replayed()).isTrue();
        assertThat(retried.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(retried.failureReason()).contains(FailureReason.LIMIT_REFUSED);
    }

    @Test
    @DisplayName("a refusing risk commits FAILED(RISK_REFUSED) - and when both seams refuse,"
            + " the limit's reason wins by the consultation order")
    void aRefusingRiskCommitsItsReservedReason() throws Exception {
        Holder source = fundedHolder(10_00);
        Holder destination = holder();

        TransferResult riskRefused =
                asInstance(source, app -> {
                    TransferResult r =
                            execution(new PermitAllUntilPhase13<>(), new RefusingSeam())
                                    .execute(app, command(source, destination, usd(2_00)));
                    app.commit();
                    return r;
                });
        assertThat(riskRefused.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(riskRefused.failureReason()).contains(FailureReason.RISK_REFUSED);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(rowStatusAndReason(app, riskRefused.transferId().value()))
                    .containsExactly("FAILED", "RISK_REFUSED");
            assertThat(entriesReferencing(app, riskRefused.transferId().value())).isZero();
        }

        // Both refusing: the limit is consulted first, so its reason is the committed one -
        // the fixed mapping making each seam's vocabulary its own (SeamVerdict's javadoc).
        TransferResult bothRefused =
                asInstance(source, app -> {
                    TransferResult r =
                            execution(new RefusingSeam(), new RefusingSeam())
                                    .execute(app, command(source, destination, usd(2_00)));
                    app.commit();
                    return r;
                });
        assertThat(bothRefused.failureReason()).contains(FailureReason.LIMIT_REFUSED);
    }

    // ------------------------------------------------------------------
    // The probes
    // ------------------------------------------------------------------

    /**
     * Permits, and records whether the source row was already locked when it was consulted —
     * observed from a second connection's {@code FOR UPDATE NOWAIT}, which only a held lock
     * refuses. The probe's own acquisition (when the contract is broken) releases at statement
     * end on its autocommit connection, so a failing run leaves nothing held.
     */
    private static final class LockObservingSeam
            implements TransferLimitCheck<Connection>, TransferRiskDecision<Connection> {
        final AtomicInteger consultations = new AtomicInteger();
        final AtomicBoolean observedHeld = new AtomicBoolean();

        @Override
        public SeamVerdict check(Connection unitOfWork, Transfer transfer) {
            consultations.incrementAndGet();
            observedHeld.set(rowIsLockedByAnotherTransaction(transfer.sourceAccount()));
            return SeamVerdict.PERMIT;
        }
    }

    /** Refuses everything — the Phase 13 stand-in that proves the reserved contract works. */
    private static final class RefusingSeam
            implements TransferLimitCheck<Connection>, TransferRiskDecision<Connection> {
        @Override
        public SeamVerdict check(Connection unitOfWork, Transfer transfer) {
            return SeamVerdict.REFUSE;
        }
    }

    private static boolean rowIsLockedByAnotherTransaction(LedgerAccountId source) {
        try (Connection probe = DatabaseRoles.application();
                PreparedStatement nowait =
                        probe.prepareStatement(
                                "SELECT id FROM ledger.ledger_account WHERE id = ?"
                                        + " FOR UPDATE NOWAIT")) {
            nowait.setObject(1, source.value());
            try (ResultSet row = nowait.executeQuery()) {
                // Acquired: nobody held the row - the contract is broken.
                return false;
            }
        } catch (SQLException refusal) {
            if ("55P03".equals(refusal.getSQLState())) {
                return true;
            }
            throw new IllegalStateException(
                    "the lock probe failed for a reason other than lock_not_available"
                            + " (SQLState " + refusal.getSQLState() + ")");
        }
    }

    // ------------------------------------------------------------------
    // Wiring — direct, deliberately (the TransferExecutionDatabaseTest stance): the probes
    // and refusers are this suite's whole subject, and the composition root's permit-all
    // wiring is exercised over HTTP by TransferEndpointDatabaseTest.
    // ------------------------------------------------------------------

    private static IdempotentExecutor executor() {
        return new IdempotentExecutor(
                new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1),
                Duration.ofMinutes(5));
    }

    private static PostingService postingService() {
        return new PostingService(
                executor(),
                new JdbcJournalEntryStore(IDS),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                new JdbcBalanceProjection(),
                IDS,
                CLOCK,
                PostingObserver.NONE);
    }

    private TransferExecution execution(
            TransferLimitCheck<Connection> limits, TransferRiskDecision<Connection> risk) {
        return new TransferExecution(
                executor(),
                participants,
                ledgerAccounts,
                new AvailableBalance<>(new JdbcBalanceDerivation(), new JdbcHoldStore()),
                postingService(),
                new JdbcTransferStore(),
                limits,
                risk,
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK);
    }

    // ------------------------------------------------------------------
    // Fixtures — the execution suite's, trimmed to what these tests need
    // ------------------------------------------------------------------

    private static final class Holder {
        final UUID partyId;
        final UUID productId;
        final UUID walletId;
        // A real identity UUID: the execution parses the actor id as the initiating person.
        final Actor actor = new Actor(IDS.next().toString(), ActorType.CUSTOMER);

        Holder(UUID partyId, UUID productId, UUID walletId) {
            this.partyId = partyId;
            this.productId = productId;
            this.walletId = walletId;
        }
    }

    private Holder holder() throws Exception {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Seam Holder',"
                            + " now() - interval '2 hour')",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    customer,
                    party);
        }
        UUID product;
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope scope =
                        SecurityContext.enter(
                                new Actor(IDS.next().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            product =
                    new AccountOpening(
                                    products,
                                    ledgerAccounts,
                                    holders,
                                    new JdbcAuditWriter(),
                                    new JdbcOutboxWriter(),
                                    IDS,
                                    CLOCK)
                            .open(app, party, ProductType.WALLET, USD)
                            .account()
                            .id()
                            .value();
            app.commit();
        }
        UUID wallet;
        try (Connection app = DatabaseRoles.application()) {
            wallet =
                    ledgerAccounts.findAllOwned(app, product).stream()
                            .filter(a -> a.purpose() == AccountPurpose.CUSTOMER_WALLET)
                            .findFirst()
                            .orElseThrow()
                            .id()
                            .value();
        }
        return new Holder(party, product, wallet);
    }

    private Holder fundedHolder(long minorUnits) throws Exception {
        Holder holder = holder();
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope scope = SecurityContext.enter(holder.actor);
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount clearing =
                    ledgerAccounts
                            .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, USD)
                            .orElseThrow();
            LocalDate today = LocalDate.now(CLOCK);
            postingService()
                    .post(
                            app,
                            new PostingCommand(
                                    "fund-" + IDS.next(),
                                    today,
                                    today,
                                    "funding",
                                    List.of(
                                            new JournalLine(
                                                    clearing.id(),
                                                    Direction.DEBIT,
                                                    usd(minorUnits)),
                                            new JournalLine(
                                                    LedgerAccountId.of(holder.walletId),
                                                    Direction.CREDIT,
                                                    usd(minorUnits)))));
            app.commit();
        }
        return holder;
    }

    private TransferCommand command(Holder source, Holder destination, Money amount) {
        return command("seam-" + IDS.next(), source, destination, amount);
    }

    private TransferCommand command(
            String key, Holder source, Holder destination, Money amount) {
        return new TransferCommand(
                key, source.partyId, source.productId, destination.productId, amount, "rent");
    }

    /** One simulated instance: own connection, own scopes, one transaction (P0-TST-009). */
    private interface InTransaction<R> {
        R run(Connection app) throws Exception;
    }

    private <R> R asInstance(Holder caller, InTransaction<R> work) throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope scope = SecurityContext.enter(caller.actor);
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            return work.run(app);
        }
    }

    private static CorrelationContext.Scope flow() {
        return CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
    }

    private static Money usd(long minorUnits) {
        return Money.ofMinorUnits(minorUnits, USD);
    }

    // ------------------------------------------------------------------
    // Counters — the tables, never return values
    // ------------------------------------------------------------------

    private static List<String> rowStatusAndReason(Connection app, UUID transfer)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT status, failure_reason FROM transfers.transfer"
                                + " WHERE id = ?")) {
            read.setObject(1, transfer);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("the judged transfer must have a row").isTrue();
                return List.of(row.getString(1), row.getString(2));
            }
        }
    }

    /** Journal entries referencing the transfer — the money-moved count, zero on a refusal. */
    private static long entriesReferencing(Connection app, UUID transfer) throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT count(*) FROM ledger.journal_entry WHERE reference = ?")) {
            read.setString(1, transfer.toString());
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
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
}
