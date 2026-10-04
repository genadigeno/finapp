package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounts.AccountClosing;
import com.finapp.accounts.AccountHolderVerification;
import com.finapp.accounts.AccountNotActiveException;
import com.finapp.accounts.AccountOpening;
import com.finapp.accounts.AccountOpeningRefusedException;
import com.finapp.accounts.CustomerAccountId;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.JdbcCustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.accounts.UnsupportedAccountCurrencyException;
import com.finapp.accounts.WalletAccounts;
import com.finapp.app.accounts.VerifiedAccountHolder;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcHoldStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccountStore;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opening a wallet currency if absent, raced, against a live PostgreSQL (`P9-TSK-004`,
 * ADR-0076 §6, D28).
 *
 * <p>The task's acceptance in one place: <strong>ten concurrent openers of one currency make
 * one account, none aborts, and one {@code accounts.WalletCurrencyAdded} is written</strong> —
 * counted in the tables, never inferred from the outcomes. The coordination is asserted, not
 * only the outcome (the {@code P0-TST-004} idiom): the losing opener is observed Lock-waiting
 * on the winner's insert, and an opener and a close are each observed waiting for the other,
 * because an outcome-only test passes against the wrong mechanism too.
 */
@Tag("database")
@DisplayName("a wallet currency opened if absent: ten racers, the lost race, and the close"
        + " (P9-TSK-004)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class WalletOpenIfAbsentRaceDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");

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

    /** The door the conversion and return paths will reuse, raced alongside the act. */
    private WalletAccounts wallets() {
        return new WalletAccounts(accounts, ledgerAccounts, new JdbcOutboxWriter(), IDS, CLOCK);
    }

    private AccountClosing closing() {
        return new AccountClosing(
                accounts,
                ledgerAccounts,
                new JdbcBalanceDerivation(),
                new JdbcHoldStore(),
                new com.finapp.payments.JdbcPaymentIntentStore()::anyInFlightCrediting,
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK);
    }

    /** One racer's answer: the account it was handed, whether it created it, and how it asked. */
    private record Answer(UUID ledgerAccount, boolean created, boolean viaAct) {}

    @Test
    @DisplayName("ten concurrent openers of one currency - five add-currency acts, five"
            + " open-if-absent calls - make one account, none aborts, one event, one record at"
            + " most")
    void tenOpenersMakeOneAccount() throws Exception {
        Person person = registered("ACTIVE");
        UUID product = openedInUsd(person);

        int instances = 10;
        CyclicBarrier start = new CyclicBarrier(instances);
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        List<Answer> answers = new ArrayList<>();
        try {
            List<Callable<Answer>> racers = new ArrayList<>();
            for (int i = 0; i < instances; i++) {
                boolean viaAct = i % 2 == 0;
                racers.add(
                        () -> {
                            // Own connection, own scopes, own commit: one simulated instance.
                            try (Connection own = DatabaseRoles.application();
                                    SecurityContext.Scope actor =
                                            SecurityContext.enter(personActor(person));
                                    CorrelationContext.Scope flow = flow()) {
                                own.setAutoCommit(false);
                                start.await();
                                Answer answer =
                                        viaAct
                                                ? viaAct(own, person, product)
                                                : viaDoor(own, person, product);
                                own.commit();
                                return answer;
                            }
                        });
            }
            // Every future's get() rethrows a racer's failure: an aborted transaction - a
            // unique violation, a serialization failure - fails the test right here.
            for (Future<Answer> outcome : pool.invokeAll(racers)) {
                answers.add(outcome.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(answers).as("all ten committed").hasSize(instances);
        Set<UUID> handed = new HashSet<>();
        answers.forEach(answer -> handed.add(answer.ledgerAccount()));
        assertThat(handed).as("every racer was handed the one account").hasSize(1);
        List<Answer> creators = answers.stream().filter(Answer::created).toList();
        assertThat(creators).as("exactly one racer's insert returned the row").hasSize(1);

        try (Connection app = DatabaseRoles.application()) {
            assertThat(walletsIn(app, product, GBP))
                    .as("counted in the table, never inferred from the outcomes")
                    .isEqualTo(1);
            assertThat(walletEvents(app, product, GBP)).isEqualTo(1);
            // Every wallet account announced exactly once: USD at opening, GBP here.
            assertThat(walletEvents(app, product, null)).isEqualTo(walletsIn(app, product, null));
            // The act audits only when IT created the wallet; the door never audits.
            assertThat(additionAudits(app, product))
                    .isEqualTo(creators.get(0).viaAct() ? 1 : 0);
        }
    }

    @Test
    @DisplayName("the losing opener waits for the winner's commit and reads its row - and when"
            + " the winner rolls back, the waiter becomes the creator")
    void theLoserWaitsAndNeverAborts() throws Exception {
        Person person = registered("ACTIVE");
        UUID product = openedInUsd(person);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection winner = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(personActor(person));
                CorrelationContext.Scope flow = flow()) {
            winner.setAutoCommit(false);

            // Round one: the winner's insert stands uncommitted; the loser blocks on it.
            Answer won = viaDoor(winner, person, product);
            assertThat(won.created()).isTrue();
            Future<Answer> loser = pool.submit(() -> openOnOwnConnection(person, product));
            awaitBlockedOn("ledger.ledger_account", "ON CONFLICT");
            winner.commit();
            Answer lost = loser.get(30, TimeUnit.SECONDS);
            assertThat(lost.created()).isFalse();
            assertThat(lost.ledgerAccount()).isEqualTo(won.ledgerAccount());

            // Round two, a fresh currency: the winner ROLLS BACK, and the waiting insert
            // proceeds - the waiter is the creator, and the one event is its.
            CurrencyCode eur = CurrencyCode.of("EUR");
            Answer abandoned = viaDoor(winner, person, product, eur);
            assertThat(abandoned.created()).isTrue();
            Future<Answer> waiter = pool.submit(() -> openOnOwnConnection(person, product, eur));
            awaitBlockedOn("ledger.ledger_account", "ON CONFLICT");
            winner.rollback();
            Answer inherited = waiter.get(30, TimeUnit.SECONDS);
            assertThat(inherited.created()).isTrue();
            assertThat(inherited.ledgerAccount()).isNotEqualTo(abandoned.ledgerAccount());
        } finally {
            pool.shutdownNow();
        }
        try (Connection app = DatabaseRoles.application()) {
            assertThat(walletsIn(app, product, GBP)).isEqualTo(1);
            assertThat(walletEvents(app, product, GBP)).isEqualTo(1);
            assertThat(walletsIn(app, product, CurrencyCode.of("EUR"))).isEqualTo(1);
            assertThat(walletEvents(app, product, CurrencyCode.of("EUR"))).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a close holding the agreement makes the opener wait, and the opener then"
            + " refuses: no wallet is ever opened under a closed agreement")
    void aCloseFirstRefusesTheOpener() throws Exception {
        Person person = registered("ACTIVE");
        UUID product = openedInUsd(person);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection closer = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(personActor(person));
                CorrelationContext.Scope flow = flow()) {
            closer.setAutoCommit(false);
            assertThat(
                            closing()
                                    .close(closer, person.customerId(), CustomerAccountId.of(product))
                                    .orElseThrow()
                                    .closed())
                    .isTrue();
            Future<Answer> opener = pool.submit(() -> openOnOwnConnection(person, product));
            awaitBlockedOn("accounts.customer_account", "FOR SHARE");
            closer.commit();
            assertThatThrownBy(() -> opener.get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(AccountNotActiveException.class);
        } finally {
            pool.shutdownNow();
        }
        try (Connection app = DatabaseRoles.application()) {
            assertThat(walletsIn(app, product, GBP)).isZero();
            assertThat(activeWalletsUnderClosedAgreements(app, product)).isZero();
        }
    }

    @Test
    @DisplayName("an opener holding the agreement makes the close wait, and the close then"
            + " closes the new wallet too")
    void anOpenerFirstIsClosedWithTheAgreement() throws Exception {
        Person person = registered("ACTIVE");
        UUID product = openedInUsd(person);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection opener = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(personActor(person));
                CorrelationContext.Scope flow = flow()) {
            opener.setAutoCommit(false);
            assertThat(viaDoor(opener, person, product).created()).isTrue();
            Future<Boolean> closer = pool.submit(() -> closeOnOwnConnection(person, product));
            awaitBlockedOn("accounts.customer_account", "FOR UPDATE");
            opener.commit();
            assertThat(closer.get(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        try (Connection app = DatabaseRoles.application()) {
            assertThat(walletsIn(app, product, GBP)).isEqualTo(1);
            assertThat(activeWalletsUnderClosedAgreements(app, product)).isZero();
        }
    }

    @Test
    @DisplayName("the add-currency act's refusals write nothing: the gate, a stranger's"
            + " agreement, an unsupported currency, a closed agreement - and a repeat converges")
    void theActsRefusalsWriteNothing() throws Exception {
        Person person = registered("ACTIVE");
        UUID product = openedInUsd(person);
        Person stranger = registered("ACTIVE");
        Person pending = registered("PENDING");
        try (Connection app = DatabaseRoles.application();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            CustomerAccountId id = CustomerAccountId.of(product);

            // The gate is the opening's own read (INV-KYC-05): no ACTIVE customer, no addition.
            try (SecurityContext.Scope actor = SecurityContext.enter(personActor(pending))) {
                assertThatThrownBy(() -> opening().addCurrency(app, pending.partyId(), id, GBP))
                        .isInstanceOf(AccountOpeningRefusedException.class);
            }
            app.rollback();
            // Ownership is the lock's statement predicate: a stranger's ask finds nothing.
            try (SecurityContext.Scope actor = SecurityContext.enter(personActor(stranger))) {
                assertThat(opening().addCurrency(app, stranger.partyId(), id, GBP)).isEmpty();
            }
            app.rollback();
            try (SecurityContext.Scope actor = SecurityContext.enter(personActor(person))) {
                assertThatThrownBy(
                                () ->
                                        opening().addCurrency(
                                                app, person.partyId(), id, CurrencyCode.of("CHF")))
                        .isInstanceOf(UnsupportedAccountCurrencyException.class);
                app.rollback();
                assertThat(walletsIn(app, product, GBP)).isZero();
                assertThat(walletEvents(app, product, GBP)).isZero();
                assertThat(additionAudits(app, product)).isZero();

                // The act itself, then its repeat: one wallet, one record, one event.
                AccountOpening.CurrencyAddition added =
                        opening().addCurrency(app, person.partyId(), id, GBP).orElseThrow();
                app.commit();
                assertThat(added.added()).isTrue();
                assertThat(added.wallet().currency()).isEqualTo(GBP);
                AccountOpening.CurrencyAddition repeat =
                        opening().addCurrency(app, person.partyId(), id, GBP).orElseThrow();
                app.commit();
                assertThat(repeat.added()).isFalse();
                assertThat(repeat.wallet().id()).isEqualTo(added.wallet().id());
                assertThat(additionAudits(app, product)).isEqualTo(1);
                assertThat(walletEvents(app, product, GBP)).isEqualTo(1);

                // A closed agreement takes no further currency.
                closing().close(app, person.customerId(), id).orElseThrow();
                app.commit();
                assertThatThrownBy(
                                () ->
                                        opening().addCurrency(
                                                app, person.partyId(), id, CurrencyCode.of("EUR")))
                        .isInstanceOf(AccountNotActiveException.class);
                app.rollback();
                assertThat(walletsIn(app, product, CurrencyCode.of("EUR"))).isZero();
            }
        }
    }

    // ------------------------------------------------------------------
    // Racers
    // ------------------------------------------------------------------

    private Answer viaAct(Connection own, Person person, UUID product) {
        AccountOpening.CurrencyAddition added =
                opening()
                        .addCurrency(own, person.partyId(), CustomerAccountId.of(product), GBP)
                        .orElseThrow();
        return new Answer(added.wallet().id().value(), added.added(), true);
    }

    private Answer viaDoor(Connection own, Person person, UUID product) {
        return viaDoor(own, person, product, GBP);
    }

    private Answer viaDoor(Connection own, Person person, UUID product, CurrencyCode currency) {
        WalletAccounts.Opened opened =
                wallets()
                        .openIfAbsent(
                                own, person.customerId(), CustomerAccountId.of(product), currency)
                        .orElseThrow();
        return new Answer(opened.wallet().id().value(), opened.created(), false);
    }

    private Answer openOnOwnConnection(Person person, UUID product) throws Exception {
        return openOnOwnConnection(person, product, GBP);
    }

    private Answer openOnOwnConnection(Person person, UUID product, CurrencyCode currency)
            throws Exception {
        try (Connection own = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(personActor(person));
                CorrelationContext.Scope flow = flow()) {
            own.setAutoCommit(false);
            Answer answer = viaDoor(own, person, product, currency);
            own.commit();
            return answer;
        }
    }

    private boolean closeOnOwnConnection(Person person, UUID product) throws Exception {
        try (Connection own = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(personActor(person));
                CorrelationContext.Scope flow = flow()) {
            own.setAutoCommit(false);
            boolean closed =
                    closing()
                            .close(own, person.customerId(), CustomerAccountId.of(product))
                            .orElseThrow()
                            .closed();
            own.commit();
            return closed;
        }
    }

    // ------------------------------------------------------------------
    // Fixtures and counters
    // ------------------------------------------------------------------

    private record Person(UUID partyId, UUID customerId, String identityLabel) {}

    private static Actor personActor(Person person) {
        return new Actor(person.identityLabel(), ActorType.CUSTOMER);
    }

    /** A party with a customer in {@code status}, planted directly and back-dated. */
    private static Person registered(String status) throws SQLException {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Wallet Holder', now() - interval '2 hour')",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, '" + status
                            + "', now() - interval '1 hour', now() - interval '1 hour')",
                    customer,
                    party);
        }
        return new Person(party, customer, "identity-" + customer);
    }

    /** The person's wallet agreement, opened in USD through the real act. */
    private UUID openedInUsd(Person person) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(personActor(person));
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            UUID product =
                    opening()
                            .open(app, person.partyId(), ProductType.WALLET, USD)
                            .account()
                            .id()
                            .value();
            app.commit();
            return product;
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

    /** The product's wallet accounts in {@code currency}, or in every currency when null. */
    private static long walletsIn(Connection connection, UUID product, CurrencyCode currency)
            throws SQLException {
        return count(
                connection,
                "SELECT count(*) FROM ledger.ledger_account WHERE owner_ref = ?"
                        + " AND purpose = 'CUSTOMER_WALLET'"
                        + (currency == null ? "" : " AND currency = '" + currency.code() + "'"),
                product);
    }

    /** The product's WalletCurrencyAdded events in {@code currency}, or in all when null. */
    private static long walletEvents(Connection connection, UUID product, CurrencyCode currency)
            throws SQLException {
        return count(
                connection,
                "SELECT count(*) FROM platform.outbox_event"
                        + " WHERE event_type = 'accounts.WalletCurrencyAdded'"
                        + " AND aggregate_id = ?"
                        + (currency == null
                                ? ""
                                : " AND convert_from(payload, 'UTF8') LIKE '%\"currency\":\""
                                        + currency.code() + "\"%'"),
                product);
    }

    private static long additionAudits(Connection connection, UUID product)
            throws SQLException {
        return count(
                connection,
                "SELECT count(*) FROM platform.audit_record"
                        + " WHERE operation = 'accounts.WalletCurrencyAdded'"
                        + " AND target_id = ?::text",
                product);
    }

    /** The invariant the agreement lock protects: no live wallet under a closed agreement. */
    private static long activeWalletsUnderClosedAgreements(Connection connection, UUID product)
            throws SQLException {
        return count(
                connection,
                "SELECT count(*) FROM ledger.ledger_account l"
                        + " JOIN accounts.customer_account a ON a.id = l.owner_ref"
                        + " WHERE a.id = ? AND a.status = 'CLOSED' AND l.status = 'ACTIVE'",
                product);
    }

    private static long count(Connection connection, String sql, Object parameter)
            throws SQLException {
        try (PreparedStatement count = connection.prepareStatement(sql)) {
            count.setObject(1, parameter);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    /** The {@code P0-TST-004} idiom: the waiting side observed Lock-waiting, never assumed. */
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
                "the waiting side never blocked on " + table + " (" + queryMarker + ") - the"
                        + " race would be decided by a stale snapshot");
    }

    private static CorrelationContext.Scope flow() {
        return CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
    }
}
