package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounts.AccountHolderVerification;
import com.finapp.accounts.AccountOpening;
import com.finapp.accounts.AccountOpeningRefusedException;
import com.finapp.accounts.CustomerAccountStatus;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.JdbcCustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.accounts.UnsupportedAccountCurrencyException;
import com.finapp.app.accounts.VerifiedAccountHolder;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.NormalBalance;
import com.finapp.party.JdbcPartyStore;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opening the customer account product against a live PostgreSQL (`P3-TSK-012`).
 *
 * <p>The acceptance in one place: a KYC-approved customer opens an account — the agreement, its
 * ledger account, the audit record and the announcement in <strong>one</strong> transaction —
 * and an unverified one is refused with <strong>nothing written</strong>. The gate is Phase 2's
 * projection consumed per decision ({@code INV-KYC-05}), which the cross-connection test holds
 * to its "very next decision" reading.
 */
@Tag("database")
@DisplayName("opening a customer account under the gate, races and rollback (P3-TSK-012)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class CustomerAccountDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");

    /** PostgreSQL SQLStates. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private final CustomerAccountStore<Connection> accounts = new JdbcCustomerAccountStore();
    private final LedgerAccountStore<Connection> ledgerAccounts = new JdbcLedgerAccountStore();
    private final AccountHolderVerification<Connection> holders =
            new VerifiedAccountHolder(new JdbcPartyStore());

    private AccountOpening opening() {
        return new AccountOpening(
                accounts,
                ledgerAccounts,
                holders,
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK);
    }

    @Test
    @DisplayName("an approved customer opens: agreement, ledger account, audit and event in one"
            + " commit - and a repeat converges writing nothing")
    void anApprovedCustomerOpensAndARepeatConverges() throws Exception {
        Person person = registered(CustomerFixtureStatus.ACTIVE);
        CustomerAccountStore.Creation created;
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(personActor(person));
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            created = opening().open(app, person.partyId(), ProductType.WALLET, USD);
            app.commit();

            assertThat(created.created()).isTrue();
            assertThat(created.account().status()).isEqualTo(CustomerAccountStatus.ACTIVE);
            assertThat(created.account().customerId()).isEqualTo(person.customerId());

            // The money side, in the same commit (ADR-0042): the platform's LIABILITY, the
            // product as its opaque owner_ref - and the classification is asserted, because a
            // wallet typed ASSET would state that customer money is the platform's own.
            LedgerAccount ledger =
                    ledgerAccounts
                            .findOwned(
                                    app,
                                    created.account().id().value(),
                                    AccountPurpose.CUSTOMER_WALLET,
                                    USD)
                            .orElseThrow();
            assertThat(ledger.accountType()).isEqualTo(AccountType.LIABILITY);
            assertThat(ledger.normalBalance()).isEqualTo(NormalBalance.CREDIT);

            // One act: the record names the person, not the platform.
            assertThat(auditRowsFor(app, created.account().id().value()))
                    .containsExactly(person.identityLabel());
            assertThat(outboxRowsFor(app, created.account().id().value())).isEqualTo(1);

            // The deliberate repeat - a retry, a double-tap, "ensure my account exists" - is
            // one agreement and NOT a second act: same row, no second ledger account, no
            // second record, no second announcement (INV-KYC-03's discipline).
            CustomerAccountStore.Creation converged =
                    opening().open(app, person.partyId(), ProductType.WALLET, USD);
            app.commit();
            assertThat(converged.created()).isFalse();
            assertThat(converged.account().id()).isEqualTo(created.account().id());
            assertThat(accountRowsFor(app, person.customerId())).isEqualTo(1);
            assertThat(auditRowsFor(app, created.account().id().value())).hasSize(1);
            assertThat(outboxRowsFor(app, created.account().id().value())).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a PENDING and a REJECTED customer are refused, and the refusal writes nothing")
    void anUnverifiedCustomerIsRefusedAndNothingIsWritten() throws Exception {
        for (CustomerFixtureStatus status :
                List.of(CustomerFixtureStatus.PENDING, CustomerFixtureStatus.REJECTED)) {
            Person person = registered(status);
            try (Connection app = DatabaseRoles.application();
                    SecurityContext.Scope actor = SecurityContext.enter(personActor(person));
                    CorrelationContext.Scope flow = flow()) {
                app.setAutoCommit(false);
                assertThatThrownBy(
                                () -> opening().open(app, person.partyId(), ProductType.WALLET, USD))
                        .as("a %s customer must not open an account", status)
                        .isInstanceOf(AccountOpeningRefusedException.class);
                app.rollback();

                assertThat(accountRowsFor(app, person.customerId()))
                        .as("no agreement for the %s customer", status)
                        .isZero();
            }
        }
    }

    @Test
    @DisplayName("an unsupported currency is refused before anything exists")
    void anUnsupportedCurrencyIsRefused() throws Exception {
        Person person = registered(CustomerFixtureStatus.ACTIVE);
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(personActor(person));
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            assertThatThrownBy(
                            () ->
                                    opening()
                                            .open(
                                                    app,
                                                    person.partyId(),
                                                    ProductType.WALLET,
                                                    CurrencyCode.of("CHF")))
                    .isInstanceOf(UnsupportedAccountCurrencyException.class);
            app.rollback();
            assertThat(accountRowsFor(app, person.customerId())).isZero();
        }
    }

    @Test
    @DisplayName("a rolled-back opening leaves nothing: the writes are one unit of work")
    void aRolledBackOpeningLeavesNothing() throws Exception {
        Person person = registered(CustomerFixtureStatus.ACTIVE);
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(personActor(person));
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            CustomerAccountStore.Creation creation =
                    opening().open(app, person.partyId(), ProductType.WALLET, USD);
            app.rollback();

            assertThat(accountRowsFor(app, person.customerId())).isZero();
            assertThat(
                            ledgerAccounts.findOwned(
                                    app,
                                    creation.account().id().value(),
                                    AccountPurpose.CUSTOMER_WALLET,
                                    USD))
                    .isEmpty();
            assertThat(auditRowsFor(app, creation.account().id().value())).isEmpty();
            assertThat(outboxRowsFor(app, creation.account().id().value())).isZero();
        }
    }

    @Test
    @DisplayName("ten instances opening concurrently produce one agreement, one ledger account,"
            + " one record, one event")
    void tenConcurrentOpensProduceOne() throws Exception {
        Person person = registered(CustomerFixtureStatus.ACTIVE);
        int instances = 10;
        CyclicBarrier start = new CyclicBarrier(instances);
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        try {
            List<Callable<Boolean>> opens = new ArrayList<>();
            for (int i = 0; i < instances; i++) {
                opens.add(
                        () -> {
                            // Own connection, own scopes: SecurityContext and
                            // CorrelationContext are per-thread, so each simulated instance
                            // establishes its own (P0-TST-009's convention).
                            try (Connection own = DatabaseRoles.application();
                                    SecurityContext.Scope actor =
                                            SecurityContext.enter(personActor(person));
                                    CorrelationContext.Scope flow = flow()) {
                                own.setAutoCommit(false);
                                start.await();
                                CustomerAccountStore.Creation creation =
                                        opening().open(
                                                own, person.partyId(), ProductType.WALLET, USD);
                                own.commit();
                                return creation.created();
                            }
                        });
            }
            List<Future<Boolean>> outcomes = pool.invokeAll(opens);
            int created = 0;
            for (Future<Boolean> outcome : outcomes) {
                if (outcome.get()) {
                    created++;
                }
            }
            assertThat(created).as("exactly one racer created; nine converged").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        try (Connection app = DatabaseRoles.application()) {
            assertThat(accountRowsFor(app, person.customerId()))
                    .as("counted in the table, never inferred from the outcomes")
                    .isEqualTo(1);
            UUID accountId = liveAccountIdFor(app, person.customerId());
            assertThat(auditRowsFor(app, accountId)).hasSize(1);
            assertThat(outboxRowsFor(app, accountId)).isEqualTo(1);
            assertThat(ledgerRowsOwnedBy(app, accountId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a customer closed on another connection is refused on this one's next opening")
    void aCustomerClosedElsewhereIsRefusedOnTheNextDecision() throws Exception {
        Person person = registered(CustomerFixtureStatus.ACTIVE);

        // Another instance ends the relationship. The write is the app role's own narrow
        // UPDATE grant - the projection's legitimate writer shape - committed before the
        // opener decides.
        try (Connection other = DatabaseRoles.application()) {
            other.setAutoCommit(false);
            try (PreparedStatement close =
                    other.prepareStatement(
                            "UPDATE party.customer SET status = 'CLOSED',"
                                    + " status_changed_at = now() WHERE id = ?")) {
                close.setObject(1, person.customerId());
                assertThat(close.executeUpdate()).isEqualTo(1);
            }
            other.commit();
        }

        // This instance's very next decision refuses: the gate reads authoritative state per
        // decision, so there is no cache to expire (the ConsentGate discipline, INV-KYC-05).
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(personActor(person));
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            assertThatThrownBy(
                            () -> opening().open(app, person.partyId(), ProductType.WALLET, USD))
                    .isInstanceOf(AccountOpeningRefusedException.class);
            app.rollback();
        }
    }

    @Test
    @DisplayName("the application role can move status and touch nothing else; no DELETE")
    void theGrantIsColumnNarrow() throws Exception {
        Person person = registered(CustomerFixtureStatus.ACTIVE);
        UUID accountId;
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(personActor(person));
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            accountId =
                    opening()
                            .open(app, person.partyId(), ProductType.WALLET, USD)
                            .account()
                            .id()
                            .value();
            app.commit();

            // Positive control first: the narrow grant is sufficient for the one legitimate
            // write, so the denials below are the grant being narrow rather than broken.
            // status_changed_at = status_changed_at, not now(): the row's opened_at is the
            // JVM's clock and the container's now() runs behind it (P1-TSK-031), so a now()
            // here trips the ordering constraint the schema is right to hold.
            try (PreparedStatement update =
                    app.prepareStatement(
                            "UPDATE accounts.customer_account SET status = 'SUSPENDED',"
                                    + " status_changed_at = status_changed_at WHERE id = ?")) {
                update.setObject(1, accountId);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            app.commit();

            for (String column : List.of("id", "customer_id", "product_type", "opened_at")) {
                assertThatThrownBy(
                                () -> {
                                    try (PreparedStatement update =
                                            app.prepareStatement(
                                                    "UPDATE accounts.customer_account SET "
                                                            + column + " = " + column
                                                            + " WHERE id = ?")) {
                                        update.setObject(1, accountId);
                                        update.executeUpdate();
                                    }
                                })
                        .as("the application role must hold no UPDATE on %s", column)
                        .isInstanceOf(SQLException.class)
                        .extracting(failure -> ((SQLException) failure).getSQLState())
                        .isEqualTo(INSUFFICIENT_PRIVILEGE);
                app.rollback();
            }

            assertThatThrownBy(
                            () -> {
                                try (PreparedStatement delete =
                                        app.prepareStatement(
                                                "DELETE FROM accounts.customer_account"
                                                        + " WHERE id = ?")) {
                                    delete.setObject(1, accountId);
                                    delete.executeUpdate();
                                }
                            })
                    .as("an agreement's end is a status, never an absence")
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);
            app.rollback();
        }
    }

    // ------------------------------------------------------------------
    // Fixtures and counters
    // ------------------------------------------------------------------

    /** The customer statuses the fixtures plant. REJECTED is terminal and outside the one-live index. */
    private enum CustomerFixtureStatus {
        PENDING,
        ACTIVE,
        REJECTED
    }

    private record Person(UUID partyId, UUID customerId, String identityLabel) {}

    /** The person's audit identity: an {@code Actor} as `P3-TSK-013`'s session chain will supply. */
    private static Actor personActor(Person person) {
        return new Actor(person.identityLabel(), ActorType.CUSTOMER);
    }

    /**
     * A party with a customer in {@code status}, planted directly (the
     * {@code ConsentWithdrawalBlocksTheCapabilityDatabaseTest} idiom) — back-dated, because the
     * container's clock steps backwards (`P1-TSK-031`).
     */
    private static Person registered(CustomerFixtureStatus status) throws SQLException {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Account Holder', now() - interval '2"
                            + " hour')",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, '" + status.name()
                            + "', now() - interval '1 hour', now() - interval '1 hour')",
                    customer,
                    party);
        }
        return new Person(party, customer, "identity-" + customer);
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

    private static long accountRowsFor(Connection connection, UUID customerId)
            throws SQLException {
        try (PreparedStatement count =
                connection.prepareStatement(
                        "SELECT count(*) FROM accounts.customer_account WHERE customer_id = ?")) {
            count.setObject(1, customerId);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static UUID liveAccountIdFor(Connection connection, UUID customerId)
            throws SQLException {
        try (PreparedStatement read =
                connection.prepareStatement(
                        "SELECT id FROM accounts.customer_account WHERE customer_id = ?")) {
            read.setObject(1, customerId);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    /** The actor ids of the opening's audit records — one, naming the person. */
    private static List<String> auditRowsFor(Connection connection, UUID accountId)
            throws SQLException {
        List<String> actors = new ArrayList<>();
        try (PreparedStatement read =
                connection.prepareStatement(
                        "SELECT actor_id FROM platform.audit_record"
                                + " WHERE operation = 'accounts.AccountOpened'"
                                + " AND target_id = ?")) {
            read.setString(1, accountId.toString());
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    actors.add(row.getString(1));
                }
            }
        }
        return actors;
    }

    private static long outboxRowsFor(Connection connection, UUID accountId)
            throws SQLException {
        try (PreparedStatement count =
                connection.prepareStatement(
                        "SELECT count(*) FROM platform.outbox_event"
                                + " WHERE event_type = 'accounts.AccountOpened'"
                                + " AND aggregate_id = ?")) {
            count.setObject(1, accountId);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long ledgerRowsOwnedBy(Connection connection, UUID accountId)
            throws SQLException {
        try (PreparedStatement count =
                connection.prepareStatement(
                        "SELECT count(*) FROM ledger.ledger_account WHERE owner_ref = ?")) {
            count.setObject(1, accountId);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static CorrelationContext.Scope flow() {
        return CorrelationContext.enter(
                Correlation.startingWith(CorrelationId.generate(IDS)));
    }
}
