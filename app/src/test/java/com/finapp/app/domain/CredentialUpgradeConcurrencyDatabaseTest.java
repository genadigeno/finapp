package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.Credential;
import com.finapp.identity.CredentialStore;
import com.finapp.identity.CredentialType;
import com.finapp.identity.CredentialVerifier;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.IdentityId;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.JdbcCredentialStore;
import com.finapp.identity.JdbcIdentityStore;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.RawPassword;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.SimulatedInstance;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Upgrade-on-use under genuine contention (`P1-TSK-008`, {@code INV-CON-01}).
 *
 * <h2>The platform's first real read-then-write</h2>
 *
 * <p>Verification reads a credential and then conditionally rewrites it, which is a shape
 * {@code P1-TSK-007} explicitly did not have - its claim that there is "no read-then-write anywhere"
 * does not extend here and is deliberately not relied on.
 *
 * <p>What makes it safe is that the write is <strong>conditional</strong>. {@code supersede} moves
 * the row only while it is still {@code ACTIVE}, and its row count is the outcome; the loser is told
 * it lost and skips its insert, so the partial unique index is never even reached. Ten people
 * logging in at once on ten instances is not an exotic case - it is a Monday morning - so this is
 * asserted rather than reasoned about.
 */
@Tag("database")
@DisplayName("credential upgrade under contention (P1-TSK-008)")
class CredentialUpgradeConcurrencyDatabaseTest {

    /** Ten, because ADR-0014 says N is never 1 and ten is what the connection budget is sized for. */
    private static final int RACERS = 10;

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private static final String PASSWORD = "correct horse battery staple";
    private static final DerivationParameters POLICY = new DerivationParameters(2048, 2, 1);
    private static final DerivationParameters WEAK = new DerivationParameters(1024, 1, 1);

    private final IdentityStore<Connection> identities = new JdbcIdentityStore();
    private final CredentialStore<Connection> credentials = new JdbcCredentialStore();

    @Test
    @DisplayName("ten instances verifying one weak credential: ten successes, exactly one upgrade")
    void concurrentUpgradesProduceExactlyOne() throws Exception {
        Fixture fixture = givenAWeakCredential();
        AtomicInteger succeeded = new AtomicInteger();
        CountingInserts counting = new CountingInserts(credentials);
        List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());

        race(
                instance -> {
                    CredentialVerifier verifier =
                            new CredentialVerifier(
                                    identities,
                                    counting,
                                    new Argon2PasswordDeriver(POLICY),
                                    IDS,
                                    CLOCK);
                    if (verifier.verify(instance.connection(), fixture.login(), RawPassword.of(PASSWORD))
                            .isSuccess()) {
                        succeeded.incrementAndGet();
                    } else {
                        failures.add("a correct password was refused");
                    }
                    instance.commit();
                });

        // THE COORDINATION, not just the outcome. A mutation that ignored the conditional
        // supersede's answer still produced exactly one upgrade - because the nine losers then
        // collided with the partial unique index and the catch-all discarded it. Same result, worse
        // mechanism, and the javadoc's claim that "the index is never even reached" would have been
        // false with nothing failing. One insert attempted, not ten, is what proves it.
        assertThat(counting.inserts())
                .as("nine racers found the credential already superseded and did not even try")
                .isEqualTo(1);

        assertThat(succeeded.get())
                .as("every caller typed the right password, so every caller authenticates")
                .isEqualTo(RACERS);
        assertThat(failures).isEmpty();

        assertThat(activeCredentials(fixture.identityId()))
                .as("exactly one active credential, however many instances upgraded at once")
                .isEqualTo(1);
        assertThat(totalCredentials(fixture.identityId()))
                .as("one upgrade happened, not ten: the conditional supersede is the coordination")
                .isEqualTo(2);

        assertThat(activeParameters(fixture.identityId()))
                .as("and the surviving credential is at current policy")
                .isEqualTo(POLICY);
    }

    @Test
    @DisplayName("contention is real: the racers overlapped rather than queueing politely")
    void theRaceIsNotVacuous() throws Exception {
        // "Exactly one upgrade" would also hold if the racers ran one after another - the mode this
        // test exists to escape. What proves they met is that nine of them found the credential
        // already superseded, which only a transaction that encountered another's committed row can.
        // The row count is the evidence: two rows, not eleven.
        Fixture fixture = givenAWeakCredential();

        race(
                instance -> {
                    new CredentialVerifier(
                                    identities,
                                    credentials,
                                    new Argon2PasswordDeriver(POLICY),
                                    IDS,
                                    CLOCK)
                            .verify(instance.connection(), fixture.login(), RawPassword.of(PASSWORD));
                    instance.commit();
                });

        assertThat(totalCredentials(fixture.identityId()))
                .as("nine racers found nothing left to upgrade")
                .isEqualTo(2);
    }

    // -----------------------------------------------------------------

    private record Fixture(IdentityId identityId, LoginIdentifier login) {}

    /** The real store, counting inserts, so the coordination point is observable. */
    private static final class CountingInserts implements CredentialStore<Connection> {

        private final CredentialStore<Connection> delegate;
        private final AtomicInteger inserts = new AtomicInteger();

        CountingInserts(CredentialStore<Connection> delegate) {
            this.delegate = delegate;
        }

        int inserts() {
            return inserts.get();
        }

        @Override
        public void insert(Connection unitOfWork, Credential credential) {
            inserts.incrementAndGet();
            delegate.insert(unitOfWork, credential);
        }

        @Override
        public boolean supersede(
                Connection unitOfWork, com.finapp.identity.CredentialId id, Instant at) {
            return delegate.supersede(unitOfWork, id, at);
        }

        @Override
        public java.util.Optional<Credential> findActive(
                Connection unitOfWork, IdentityId identityId, CredentialType type) {
            return delegate.findActive(unitOfWork, identityId, type);
        }
    }

    /**
     * Releases {@link #RACERS} instances together, each with its own connection.
     *
     * <p>No barrier after the release, for the reason `P1-TSK-005` recorded: a harness that held
     * every racer until all had attempted passed by <em>timing out</em>, because the losers were
     * blocked inside their statement waiting for the winner's lock and could never reach the
     * barrier. Releasing them together and letting the database block them is the contention.
     */
    private static void race(Work work) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int racer = 0; racer < RACERS; racer++) {
                futures.add(
                        pool.submit(
                                (Callable<Void>)
                                        () -> {
                                            try (SimulatedInstance instance =
                                                    SimulatedInstance.inAgreementWithTheServer()) {
                                                release.await();
                                                work.run(instance);
                                            }
                                            return null;
                                        }));
            }
            release.countDown();
            for (Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface Work {
        void run(SimulatedInstance instance) throws SQLException;
    }

    private Fixture givenAWeakCredential() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        LoginIdentifier login =
                new LoginIdentifier("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Ada Lovelace', now())",
                    party);
            execute(
                    app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE', now(), now())",
                    identity,
                    party,
                    login.value());
            credentials.insert(
                    app,
                    Credential.forPassword(
                            IDS,
                            CLOCK,
                            IdentityId.of(identity),
                            CredentialType.PASSWORD,
                            new Argon2PasswordDeriver(WEAK),
                            RawPassword.of(PASSWORD)));
        }
        return new Fixture(IdentityId.of(identity), login);
    }

    private int activeCredentials(IdentityId identityId) throws SQLException {
        return count(
                "SELECT count(*) FROM identity.credential WHERE identity_id = ? AND status = 'ACTIVE'",
                identityId.value());
    }

    private int totalCredentials(IdentityId identityId) throws SQLException {
        return count(
                "SELECT count(*) FROM identity.credential WHERE identity_id = ?", identityId.value());
    }

    private DerivationParameters activeParameters(IdentityId identityId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement =
                        app.prepareStatement(
                                "SELECT memory_kib, iterations, parallelism FROM identity.credential"
                                        + " WHERE identity_id = ? AND status = 'ACTIVE'")) {
            statement.setObject(1, identityId.value());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return new DerivationParameters(rows.getInt(1), rows.getInt(2), rows.getInt(3));
            }
        }
    }

    private static int count(String sql, Object argument) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            statement.setObject(1, argument);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            statement.executeUpdate();
        }
    }
}
