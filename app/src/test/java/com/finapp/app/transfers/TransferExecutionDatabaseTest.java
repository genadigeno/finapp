package com.finapp.app.transfers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.party.JdbcPartyStore;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyConflictException;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.transfers.FailureReason;
import com.finapp.transfers.JdbcTransferStore;
import com.finapp.transfers.PermitAllUntilPhase13;
import com.finapp.transfers.TransferCommand;
import com.finapp.transfers.TransferExecution;
import com.finapp.transfers.TransferParticipants;
import com.finapp.transfers.TransferResult;
import com.finapp.transfers.TransferStatus;
import com.finapp.transfers.UnknownTransferSourceException;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The execution command against a live PostgreSQL (`P4-TSK-005`, the phase's High-risk task):
 * ADR-0043's one transaction, the source lock, and the judgement — with the drain counting its
 * outcomes <strong>in the tables</strong>, never inferring them from return values.
 */
@Tag("database")
@DisplayName("the transfer execution: one transaction, the lock, the outcome (P4-TSK-005)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class TransferExecutionDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private final CustomerAccountStore<Connection> products = new JdbcCustomerAccountStore();
    private final LedgerAccountStore<Connection> ledgerAccounts = new JdbcLedgerAccountStore();
    private final AccountHolderVerification<Connection> holders =
            new VerifiedAccountHolder(new JdbcPartyStore());
    private final TransferParticipants<Connection> participants =
            new JdbcTransferParticipants(
                    new JdbcPartyStore(), new JdbcCustomerAccountStore(),
                    new JdbcLedgerAccountStore());

    // ------------------------------------------------------------------
    // Wiring — direct, because no bean exists until P4-TSK-008 (the P1-TSK-007 licence).
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

    private TransferExecution execution() {
        return execution(participants, new JdbcOutboxWriter());
    }

    private TransferExecution execution(
            TransferParticipants<Connection> resolvedBy, OutboxWriter<Connection> outbox) {
        return new TransferExecution(
                executor(),
                resolvedBy,
                ledgerAccounts,
                new AvailableBalance<>(new JdbcBalanceDerivation(), new JdbcHoldStore()),
                postingService(),
                new JdbcTransferStore(),
                new PermitAllUntilPhase13(),
                new PermitAllUntilPhase13(),
                new JdbcAuditWriter(),
                outbox,
                IDS,
                CLOCK);
    }

    @Test
    @DisplayName("a transfer moves money once: entry, row, history, audit and event in one"
            + " commit — and its retry replays, while a changed request conflicts")
    void aTransferMovesMoneyOnceAndItsRetryReplays() throws Exception {
        Holder source = fundedHolder(10_00);
        Holder destination = holder();
        String key = "transfer-" + IDS.next();
        TransferResult first =
                asInstance(source, app -> {
                    TransferResult r =
                            execution().execute(app, command(key, source, destination, usd(3_00)));
                    app.commit();
                    return r;
                });
        assertThat(first.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(first.replayed()).isFalse();

        try (Connection app = DatabaseRoles.application()) {
            TransferRow row = transferRow(app, first.transferId().value());
            assertThat(row.status).isEqualTo("COMPLETED");
            assertThat(row.customerId).isEqualTo(source.customerId);
            assertThat(row.journalEntryId).isNotNull();

            // The entry: debit the source wallet, credit the destination wallet, and the
            // transfer id in its reference — the identifier chain in both directions (§12).
            assertThat(entryReference(app, row.journalEntryId))
                    .isEqualTo(first.transferId().value().toString());
            assertThat(lineAmount(app, row.journalEntryId, source.walletId, "DEBIT"))
                    .isEqualTo(3_00);
            assertThat(lineAmount(app, row.journalEntryId, destination.walletId, "CREDIT"))
                    .isEqualTo(3_00);

            assertThat(historyRows(app, first.transferId().value()))
                    .containsExactly("INITIATED>COMPLETED");
            assertThat(auditRecords(app, first.transferId().value())).isEqualTo(1);
            assertThat(outboxEvents(app, first.transferId().value(),
                            "transfers.TransferCompleted"))
                    .isEqualTo(1);
        }

        // The retry replays the original judgement and writes nothing more (INV-IDEM-01).
        TransferResult retried =
                asInstance(source, app -> {
                    TransferResult r =
                            execution().execute(app, command(key, source, destination, usd(3_00)));
                    app.commit();
                    return r;
                });
        assertThat(retried.transferId()).isEqualTo(first.transferId());
        assertThat(retried.replayed()).isTrue();
        try (Connection app = DatabaseRoles.application()) {
            assertThat(transferCountFor(app, source.customerId)).isEqualTo(1);
        }

        // The same key with a materially different request is a conflict, never a silent
        // replay and never a second movement (INV-IDEM-03).
        asInstance(source, app -> {
            assertThatThrownBy(
                            () ->
                                    execution()
                                            .execute(
                                                    app,
                                                    command(key, source, destination, usd(4_00))))
                    .isInstanceOf(IdempotencyConflictException.class);
            app.rollback();
                    return null;
        });
    }

    @Test
    @DisplayName("insufficient funds commits FAILED with its reason and no posting — a domain"
            + " outcome whose retry replays the refusal")
    void insufficientFundsCommitsItsOutcome() throws Exception {
        Holder source = fundedHolder(1_00);
        Holder destination = holder();
        String key = "transfer-" + IDS.next();
        TransferResult refused =
                asInstance(source, app -> {
                    TransferResult r =
                            execution().execute(app, command(key, source, destination, usd(5_00)));
                    app.commit();
                    return r;
                });
        assertThat(refused.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(refused.failureReason()).contains(FailureReason.INSUFFICIENT_FUNDS);

        try (Connection app = DatabaseRoles.application()) {
            TransferRow row = transferRow(app, refused.transferId().value());
            assertThat(row.status).isEqualTo("FAILED");
            assertThat(row.reason).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(row.journalEntryId).as("no posting for a refusal").isNull();
            assertThat(holdRowsFor(app, source.walletId))
                    .as("no hold on the money either")
                    .isZero();
            assertThat(outboxEvents(app, refused.transferId().value(),
                            "transfers.TransferFailed"))
                    .isEqualTo(1);
        }

        // The retry learns the refusal — the stored outcome, failure included (ADR-0044:
        // FAILED is a committed domain outcome, so its claim survived the commit).
        asInstance(source, app -> {
            TransferResult retried =
                    execution().execute(app, command(key, source, destination, usd(5_00)));
            app.commit();
            assertThat(retried.replayed()).isTrue();
            assertThat(retried.transferId()).isEqualTo(refused.transferId());
            assertThat(retried.failureReason()).contains(FailureReason.INSUFFICIENT_FUNDS);
                    return null;
        });
    }

    @Test
    @DisplayName("the sibling refusals each commit their reason — and a boundary mistake"
            + " commits nothing at all")
    void theSiblingRefusalsEachCommitTheirReason() throws Exception {
        Holder source = fundedHolder(10_00);
        Holder destination = holder();

        // Self: the committed record of refusing exactly that mistake, equal pair stored.
        asInstance(source, app -> {
            TransferResult self =
                    execution()
                            .execute(
                                    app,
                                    command("transfer-" + IDS.next(), source, source, usd(1_00)));
            app.commit();
            assertThat(self.failureReason()).contains(FailureReason.SELF_TRANSFER);
            TransferRow row = transferRow(app, self.transferId().value());
            assertThat(row.sourceAccountId).isEqualTo(row.destinationAccountId);
                    return null;
        });

        // Currency: the wallets are USD and the command is EUR — the row still carries the
        // real accounts, which is what currency-blind resolution exists for.
        asInstance(source, app -> {
            TransferResult mismatch =
                    execution()
                            .execute(
                                    app,
                                    command(
                                            "transfer-" + IDS.next(),
                                            source,
                                            destination,
                                            Money.ofMinorUnits(1_00, EUR)));
            app.commit();
            assertThat(mismatch.failureReason()).contains(FailureReason.CURRENCY_MISMATCH);
                    return null;
        });

        // Destination not postable, seen at resolution: its wallet is CLOSED.
        Holder closed = holder();
        closeWallet(closed);
        asInstance(source, app -> {
            TransferResult refused =
                    execution()
                            .execute(
                                    app,
                                    command("transfer-" + IDS.next(), source, closed, usd(1_00)));
            app.commit();
            assertThat(refused.failureReason())
                    .contains(FailureReason.DESTINATION_NOT_POSTABLE);
                    return null;
        });

        // Destination not postable MID-FLIGHT (the plan's scenario 7, sequential and
        // deterministic): resolution is lied to by a decorator, so the refusal arrives from
        // V007's trigger inside the posting — and the savepoint is what turns an aborted
        // transaction state into a committed FAILED.
        Holder closedLate = holder();
        closeWallet(closedLate);
        TransferParticipants<Connection> liesAboutDestination =
                new TransferParticipants<>() {
                    @Override
                    public Optional<Source> sourceOwnedBy(
                            Connection uow, UUID party, UUID product) {
                        return participants.sourceOwnedBy(uow, party, product);
                    }

                    @Override
                    public Optional<Side> destination(Connection uow, UUID product) {
                        return participants
                                .destination(uow, product)
                                .map(side -> new Side(side.account(), side.currency(), true));
                    }
                };
        asInstance(source, app -> {
            TransferResult refused =
                    execution(liesAboutDestination, new JdbcOutboxWriter())
                            .execute(
                                    app,
                                    command(
                                            "transfer-" + IDS.next(),
                                            source,
                                            closedLate,
                                            usd(1_00)));
            app.commit();
            assertThat(refused.failureReason())
                    .contains(FailureReason.DESTINATION_NOT_POSTABLE);
            assertThat(transferRow(app, refused.transferId().value()).journalEntryId).isNull();
                    return null;
        });

        // Boundary mistakes: unknown source, and somebody else's source — one exception,
        // nothing written, and the rollback takes the claim with it.
        long before;
        try (Connection app = DatabaseRoles.application()) {
            before = transferCountFor(app, source.customerId);
        }
        asInstance(source, app -> {
            assertThatThrownBy(
                            () ->
                                    execution()
                                            .execute(
                                                    app,
                                                    new TransferCommand(
                                                            "transfer-" + IDS.next(),
                                                            source.partyId,
                                                            IDS.next(),
                                                            destination.productId,
                                                            usd(1_00),
                                                            null)))
                    .isInstanceOf(UnknownTransferSourceException.class);
            app.rollback();

            assertThatThrownBy(
                            () ->
                                    execution()
                                            .execute(
                                                    app,
                                                    new TransferCommand(
                                                            "transfer-" + IDS.next(),
                                                            source.partyId,
                                                            destination.productId,
                                                            source.productId,
                                                            usd(1_00),
                                                            null)))
                    .as("somebody else's product as the source is the same indistinguishable"
                            + " refusal")
                    .isInstanceOf(UnknownTransferSourceException.class);
            app.rollback();
                    return null;
        });
        try (Connection app = DatabaseRoles.application()) {
            assertThat(transferCountFor(app, source.customerId)).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("ten instances draining one account accept exactly the affordable transfers,"
            + " the source never negative, total value conserved — counted in the tables")
    void theTenWayDrainConservesValue() throws Exception {
        Holder source = fundedHolder(10_00);
        Holder destination = holder();

        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Callable<TransferStatus>> drains = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                String key = "drain-" + IDS.next();
                drains.add(
                        () ->
                                asInstance(source, app -> {
                                    TransferResult result =
                                            execution()
                                                    .execute(
                                                            app,
                                                            command(
                                                                    key,
                                                                    source,
                                                                    destination,
                                                                    usd(3_00)));
                                    app.commit();
                                    return result.status();
                                }));
            }
            List<Future<TransferStatus>> outcomes = pool.invokeAll(drains);
            for (Future<TransferStatus> outcome : outcomes) {
                outcome.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        try (Connection app = DatabaseRoles.application()) {
            // Counted in the tables, never inferred from return values: 3 × 300 fits in
            // 1000 and 4 × 300 does not, so exactly three transfers completed and every
            // other one is the committed INSUFFICIENT_FUNDS refusal (INV-CON-02).
            assertThat(transferCountByStatus(app, source.customerId, "COMPLETED")).isEqualTo(3);
            assertThat(transferCountByStatus(app, source.customerId, "FAILED")).isEqualTo(7);
            assertThat(failedReasonsFor(app, source.customerId))
                    .containsOnly("INSUFFICIENT_FUNDS");

            // The source never went negative, and value over the pair is conserved: what
            // left one wallet arrived in the other, to the minor unit.
            long sourceSettled = settledOf(app, source.walletId);
            long destinationSettled = settledOf(app, destination.walletId);
            assertThat(sourceSettled).isEqualTo(1_00);
            assertThat(destinationSettled).isEqualTo(9_00);
            assertThat(sourceSettled + destinationSettled).isEqualTo(10_00);
        }
    }

    @Test
    @DisplayName("an injected failure at the last write leaves NOTHING — no row, no entry, no"
            + " claim: the retry executes afresh (ADR-0043's demonstration)")
    void anInjectedFailureAtTheLastWriteLeavesNothing() throws Exception {
        Holder source = fundedHolder(10_00);
        Holder destination = holder();
        String key = "transfer-" + IDS.next();

        OutboxWriter<Connection> failing =
                (uow, envelope, payload, mediaType) -> {
                    if (envelope.eventType().startsWith("transfers.")) {
                        throw new IllegalStateException("injected: the last write fails");
                    }
                    new JdbcOutboxWriter().write(uow, envelope, payload, mediaType);
                };

        asInstance(source, app -> {
            assertThatThrownBy(
                            () ->
                                    execution(participants, failing)
                                            .execute(
                                                    app,
                                                    command(key, source, destination, usd(2_00))))
                    .hasMessageContaining("injected");
            app.rollback();
                    return null;
        });

        try (Connection app = DatabaseRoles.application()) {
            assertThat(transferCountFor(app, source.customerId)).isZero();
            assertThat(settledOf(app, source.walletId))
                    .as("the posting rolled back with everything else")
                    .isEqualTo(10_00);
        }

        // No claim survived either: the same key executes afresh rather than replaying a
        // failure that never committed (P3-TSK-006's property, ADR-0043's demonstration).
        asInstance(source, app -> {
            TransferResult retried =
                    execution().execute(app, command(key, source, destination, usd(2_00)));
            app.commit();
            assertThat(retried.replayed()).isFalse();
            assertThat(retried.status()).isEqualTo(TransferStatus.COMPLETED);
                    return null;
        });
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static final class Holder {
        final UUID partyId;
        final UUID customerId;
        final UUID productId;
        final UUID walletId;
        // A real identity UUID: the execution parses the actor id as the initiating person
        // (a transfer is never the platform's act).
        final Actor actor = new Actor(IDS.next().toString(), ActorType.CUSTOMER);

        Holder(UUID partyId, UUID customerId, UUID productId, UUID walletId) {
            this.partyId = partyId;
            this.customerId = customerId;
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
                            + " VALUES (?, 'PERSON', 'Transfer Holder',"
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
        return new Holder(party, customer, product, wallet);
    }

    /** A holder whose wallet holds {@code minorUnits}, credited from the clearing account. */
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
                                                    com.finapp.ledger.LedgerAccountId.of(
                                                            holder.walletId),
                                                    Direction.CREDIT,
                                                    usd(minorUnits)))));
            app.commit();
        }
        return holder;
    }

    private void closeWallet(Holder holder) throws Exception {
        try (Connection migrator = DatabaseRoles.migrator()) {
            // GREATEST(now(), created_at): the container's clock runs behind the JVM's that
            // wrote created_at, and the ordering constraint is right to refuse a backwards
            // status change (P1-TSK-031, the P2-TSK-006 idiom).
            execute(
                    migrator,
                    "UPDATE ledger.ledger_account SET status = 'CLOSED',"
                            + " status_changed_at = GREATEST(now(), created_at) WHERE id = ?",
                    holder.walletId);
        }
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

    private record TransferRow(
            String status,
            String reason,
            UUID customerId,
            UUID sourceAccountId,
            UUID destinationAccountId,
            UUID journalEntryId) {}

    private static TransferRow transferRow(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement read =
                connection.prepareStatement(
                        "SELECT status, failure_reason, customer_id, source_account_id,"
                                + " destination_account_id, journal_entry_id"
                                + " FROM transfers.transfer WHERE id = ?")) {
            read.setObject(1, id);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("transfer row %s exists", id).isTrue();
                return new TransferRow(
                        row.getString(1),
                        row.getString(2),
                        row.getObject(3, UUID.class),
                        row.getObject(4, UUID.class),
                        row.getObject(5, UUID.class),
                        row.getObject(6, UUID.class));
            }
        }
    }

    private static long transferCountFor(Connection connection, UUID customerId)
            throws SQLException {
        return count(
                connection,
                "SELECT count(*) FROM transfers.transfer WHERE customer_id = ?",
                customerId);
    }

    private static long transferCountByStatus(
            Connection connection, UUID customerId, String status) throws SQLException {
        return count(
                connection,
                "SELECT count(*) FROM transfers.transfer WHERE customer_id = ? AND status = '"
                        + status + "'",
                customerId);
    }

    private static List<String> failedReasonsFor(Connection connection, UUID customerId)
            throws SQLException {
        List<String> reasons = new ArrayList<>();
        try (PreparedStatement read =
                connection.prepareStatement(
                        "SELECT failure_reason FROM transfers.transfer"
                                + " WHERE customer_id = ? AND status = 'FAILED'")) {
            read.setObject(1, customerId);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    reasons.add(rows.getString(1));
                }
            }
        }
        return reasons;
    }

    private static List<String> historyRows(Connection connection, UUID transferId)
            throws SQLException {
        List<String> rows = new ArrayList<>();
        try (PreparedStatement read =
                connection.prepareStatement(
                        "SELECT from_status, to_status FROM transfers.transfer_event"
                                + " WHERE transfer_id = ? ORDER BY id")) {
            read.setObject(1, transferId);
            try (ResultSet results = read.executeQuery()) {
                while (results.next()) {
                    rows.add(results.getString(1) + ">" + results.getString(2));
                }
            }
        }
        return rows;
    }

    private static long auditRecords(Connection connection, UUID transferId)
            throws SQLException {
        return count(
                connection,
                "SELECT count(*) FROM platform.audit_record"
                        + " WHERE operation = 'transfers.TransferExecuted' AND target_id = ?",
                transferId.toString());
    }

    private static long outboxEvents(Connection connection, UUID transferId, String type)
            throws SQLException {
        return count(
                connection,
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?"
                        + " AND event_type = '" + type + "'",
                transferId);
    }

    private static long holdRowsFor(Connection connection, UUID walletId) throws SQLException {
        return count(
                connection,
                "SELECT count(*) FROM ledger.hold WHERE ledger_account_id = ?",
                walletId);
    }

    private static String entryReference(Connection connection, UUID entryId)
            throws SQLException {
        try (PreparedStatement read =
                connection.prepareStatement(
                        "SELECT reference FROM ledger.journal_entry WHERE id = ?")) {
            read.setObject(1, entryId);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long lineAmount(
            Connection connection, UUID entryId, UUID accountId, String direction)
            throws SQLException {
        try (PreparedStatement read =
                connection.prepareStatement(
                        "SELECT amount_minor FROM ledger.journal_line"
                                + " WHERE entry_id = ? AND ledger_account_id = ?"
                                + " AND direction = '" + direction + "'")) {
            read.setObject(1, entryId);
            read.setObject(2, accountId);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next())
                        .as("a %s line on account %s", direction, accountId)
                        .isTrue();
                return row.getLong(1);
            }
        }
    }

    /** Settled, summed over the journal — the conservation count reads the money's own rows. */
    private static long settledOf(Connection connection, UUID accountId) throws SQLException {
        try (PreparedStatement read =
                connection.prepareStatement(
                        "SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor"
                                + " ELSE -amount_minor END), 0)"
                                + " FROM ledger.journal_line WHERE ledger_account_id = ?")) {
            read.setObject(1, accountId);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long count(Connection connection, String sql, Object parameter)
            throws SQLException {
        try (PreparedStatement read = connection.prepareStatement(sql)) {
            read.setObject(1, parameter);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }
}
