package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.ActiveCredentialAlreadyExistsException;
import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.Credential;
import com.finapp.identity.CredentialId;
import com.finapp.identity.CredentialType;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcCredentialStore;
import com.finapp.identity.PasswordDeriver;
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
 * The two credential rules under genuine contention (`P1-TSK-007`, {@code DOD-SEC},
 * {@code INV-CON-01}).
 *
 * <h2>Why these have to be raced rather than asserted sequentially</h2>
 *
 * <p>{@code CredentialSchemaDatabaseTest} proves the partial index rejects a second active
 * credential and that a conditional supersede reports losing. It proves both <em>sequentially</em>,
 * which is the one mode in which neither claim is interesting - the whole argument for putting
 * these rules in the database is that <strong>an entity sees only itself, so only the database can
 * arbitrate between two concurrent transactions</strong>, and asserting that with one transaction
 * at a time tests it where it does not matter. That gap was the `P1-TSK-005` gate's finding, and
 * this is the same gap closed before the gate rather than by it.
 *
 * <p>Each racer gets its <strong>own connection</strong> through {@link SimulatedInstance} - the
 * {@code P0-TST-009} convention. Ten application instances are ten JVMs with ten pools, and sharing
 * one connection between threads would serialise the very contention it means to create.
 */
@Tag("database")
@DisplayName("credential rules under contention (P1-TSK-007)")
class CredentialConcurrencyDatabaseTest {

    /** Ten, because ADR-0014 says N is never 1 and ten is what the connection budget is sized for. */
    private static final int RACERS = 10;

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    /** Cheap: this test is about the database arbitrating, not about Argon2's cost. */
    private static final PasswordDeriver DERIVER =
            new Argon2PasswordDeriver(new DerivationParameters(1024, 1, 1));

    private static final JdbcCredentialStore STORE = new JdbcCredentialStore();

    @Test
    @DisplayName("ten instances setting a credential produce exactly one active credential")
    void onlyOneActiveCredentialSurvivesContention() throws Exception {
        UUID identity = givenAnIdentity();
        AtomicInteger inserted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<String> otherFailures = java.util.Collections.synchronizedList(new ArrayList<>());

        race(
                instance -> {
                    try {
                        STORE.insert(instance.connection(), credentialFor(identity));
                        instance.commit();
                        inserted.incrementAndGet();
                    } catch (ActiveCredentialAlreadyExistsException taken) {
                        refused.incrementAndGet();
                        instance.rollback();
                    } catch (RuntimeException unexpected) {
                        otherFailures.add(String.valueOf(unexpected.getMessage()));
                        instance.rollback();
                    }
                });

        assertThat(inserted.get()).as("exactly one active credential, however many instances tried").isEqualTo(1);
        assertThat(refused.get())
                .as("every loser is refused by the index, not by chance")
                .isEqualTo(RACERS - 1);
        assertThat(otherFailures).as("no failure of another kind").isEmpty();
        assertThat(activeCredentialsOf(identity))
                .as("the database is the authority, not what any racer believed")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("ten instances superseding one credential: exactly one performs the transition")
    void onlyOneSupersedeWins() throws Exception {
        UUID identity = givenAnIdentity();
        Credential credential = credentialFor(identity);
        try (Connection app = DatabaseRoles.application()) {
            STORE.insert(app, credential);
        }

        AtomicInteger won = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();

        race(
                instance -> {
                    // The conditional UPDATE ... WHERE status = 'ACTIVE'. The second instance blocks
                    // on the row lock, then sees SUPERSEDED and affects zero rows. The row count IS
                    // the outcome - which is what makes a lost update impossible rather than
                    // unlikely, and why this returns a boolean instead of void.
                    boolean transitioned =
                            STORE.supersede(instance.connection(), credential.id(), Instant.now(CLOCK));
                    instance.commit();
                    if (transitioned) {
                        won.incrementAndGet();
                    } else {
                        lost.incrementAndGet();
                    }
                });

        assertThat(won.get()).as("exactly one caller performed the transition").isEqualTo(1);
        assertThat(lost.get())
                .as("every other caller was told it did not, rather than believing it had")
                .isEqualTo(RACERS - 1);
        assertThat(supersededAtCountOf(credential.id()))
                .as("one supersession timestamp, so the row was written once")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a rolled-back attempt frees the slot it was holding")
    void aRolledBackAttemptConsumesNothing() throws Exception {
        // The crash half. What a crash must not do here is leave the active-credential slot
        // consumed by work that never committed - a person unable to set a password because an
        // instance died while they were setting one.
        UUID identity = givenAnIdentity();

        try (SimulatedInstance abandoned = SimulatedInstance.inAgreementWithTheServer()) {
            STORE.insert(abandoned.connection(), credentialFor(identity));
            abandoned.rollback();
        }

        try (SimulatedInstance survivor = SimulatedInstance.inAgreementWithTheServer()) {
            STORE.insert(survivor.connection(), credentialFor(identity));
            survivor.commit();
        }

        assertThat(activeCredentialsOf(identity)).isEqualTo(1);
    }

    @Test
    @DisplayName("contention is real: the losers were refused, not merely late")
    void theRaceIsNotVacuous() throws Exception {
        // "Exactly one succeeded" would also hold if the racers ran one after another - the mode
        // this test exists to escape. A unique violation can only be raised by a transaction that
        // met another transaction's row, so requiring nine of them IS the proof they overlapped.
        UUID identity = givenAnIdentity();
        AtomicInteger refused = new AtomicInteger();

        race(
                instance -> {
                    try {
                        STORE.insert(instance.connection(), credentialFor(identity));
                        instance.commit();
                    } catch (ActiveCredentialAlreadyExistsException taken) {
                        refused.incrementAndGet();
                        instance.rollback();
                    }
                });

        assertThat(refused.get()).isEqualTo(RACERS - 1);
    }

    // -----------------------------------------------------------------

    /**
     * Releases {@link #RACERS} instances together, each with its own connection.
     *
     * <p>No barrier after the release, for the reason `P1-TSK-005` recorded: an earlier harness in
     * this repository held every racer until all had attempted and <strong>passed by timing
     * out</strong>, because the losers were blocked <em>inside</em> their insert waiting for the
     * winner's lock and could never reach the barrier. The overlap needs no arranging - releasing
     * them together and letting the database block them is the contention.
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
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface Work {
        void run(SimulatedInstance instance) throws SQLException;
    }

    private static Credential credentialFor(UUID identity) {
        return Credential.forPassword(
                IDS,
                CLOCK,
                IdentityId.of(identity),
                CredentialType.PASSWORD,
                DERIVER,
                RawPassword.of("correct horse battery staple"));
    }

    private static UUID givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
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
                    "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        return identity;
    }

    private static int activeCredentialsOf(UUID identity) throws SQLException {
        return count(
                "SELECT count(*) FROM identity.credential WHERE identity_id = ? AND status = 'ACTIVE'",
                identity);
    }

    private static int supersededAtCountOf(CredentialId credentialId) throws SQLException {
        return count(
                "SELECT count(*) FROM identity.credential WHERE id = ? AND superseded_at IS NOT NULL",
                credentialId.value());
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
