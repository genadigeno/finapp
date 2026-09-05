package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.SimulatedInstance;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
 * The two uniqueness rules, under genuine contention (`P1-TSK-005`, `DOD-KERNEL`).
 *
 * <h2>Why this test exists, and why the sequential one was not enough</h2>
 *
 * <p>{@code PartyAndIdentitySchemaDatabaseTest} proves the constraints reject a second row. It
 * proves it by inserting one row and then another, which is the one mode that <strong>cannot</strong>
 * demonstrate the property actually being relied on. The argument for putting these rules in the
 * database rather than in the aggregate is precisely that <em>an aggregate sees only itself, so only
 * the database can arbitrate between two concurrent transactions</em> — and asserting that
 * sequentially tests the claim in the only situation where it is not the interesting one.
 *
 * <p>{@code DOD-KERNEL} requires the component's behaviour under concurrency to be proven by
 * integration test. This is that proof.
 *
 * <h2>Each racer is its own instance</h2>
 *
 * <p>Every thread gets its <strong>own connection</strong>, through {@link SimulatedInstance}, which
 * is the {@code P0-TST-009} convention: ten application instances are ten JVMs with ten pools, and a
 * test sharing one connection between threads would exercise a scenario that does not exist and
 * would serialise the very contention it means to create.
 *
 * <h2>What the database is expected to do</h2>
 *
 * <p>Under {@code READ COMMITTED}, a second insert on a contended unique key <em>blocks</em> until
 * the first transaction ends, and only then reports {@code 23505} — the behaviour
 * {@code JdbcIdempotencyRecordStore}'s javadoc documents for the idempotency table, and which
 * applies here for the same reason.
 *
 * <p><strong>That blocking is what makes the race real, and it is also what the first version of
 * this harness got wrong.</strong> It held every racer until all ten had "attempted", so that the
 * winner's transaction stayed open across the others' inserts. The losers could never reach that
 * point: they were blocked <em>inside</em> the insert, waiting for the winner's lock. The barrier
 * therefore always expired, and the suite passed in 30 seconds per race by timing out — a test that
 * passes by waiting, which is precisely what this repository rejects elsewhere. The overlap does
 * not need arranging: releasing the racers together and letting the database block them is the
 * contention.
 */
@Tag("database")
@DisplayName("party and identity uniqueness under contention (P1-TSK-005)")
class PartyAndIdentityConcurrencyDatabaseTest {

    private static final String UNIQUE_VIOLATION = "23505";

    /**
     * Ten, because ADR-0014 says N is never 1 and ten is the instance count the connection budget
     * is sized for (`DISTRIBUTED_EXECUTION.md` §4a). Not chosen to be large: chosen to be the
     * number the platform actually claims to run.
     */
    private static final int RACERS = 10;

    @Test
    @DisplayName("ten instances opening a relationship for one party produce exactly one")
    void oneLiveRelationshipSurvivesContention() throws Exception {
        UUID party = givenAParty();

        Outcome outcome =
                race(instance -> insertCustomer(instance.connection(), party, "PENDING"));

        assertThat(outcome.succeeded())
                .as("exactly one live relationship, however many instances tried")
                .isEqualTo(1);
        assertThat(outcome.uniqueViolations())
                .as("every loser is refused by the constraint, not by chance")
                .isEqualTo(RACERS - 1);
        assertThat(outcome.otherFailures()).as("no failure of another kind").isEmpty();

        // The database is the arbiter, so the row count is the authority - not what any racer
        // believed about its own outcome.
        assertThat(liveCustomersOf(party)).isEqualTo(1);
    }

    @Test
    @DisplayName("ten instances claiming one login identifier produce exactly one identity")
    void oneLoginIdentifierSurvivesContention() throws Exception {
        UUID party = givenAParty();
        String contested = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        Outcome outcome =
                race(instance -> insertIdentity(instance.connection(), party, contested, "ACTIVE"));

        assertThat(outcome.succeeded()).isEqualTo(1);
        assertThat(outcome.uniqueViolations()).isEqualTo(RACERS - 1);
        assertThat(outcome.otherFailures()).isEmpty();
        assertThat(identitiesWithLogin(contested)).isEqualTo(1);
    }

    @Test
    @DisplayName("a racer that rolls back releases the identifier it was holding")
    void aRolledBackAttemptConsumesNothing() throws Exception {
        // The crash half of DOD-KERNEL, in the form that applies to these tables. There is no
        // recovery path to test - nothing here is a claim, a lease or a two-phase anything - so
        // what a crash must not do is leave the uniqueness slot consumed by work that never
        // committed. A test that only ever committed would not notice if it did.
        UUID party = givenAParty();
        String contested = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        try (SimulatedInstance abandoned = SimulatedInstance.inAgreementWithTheServer()) {
            insertIdentity(abandoned.connection(), party, contested, "ACTIVE");
            abandoned.rollback();
        }

        try (SimulatedInstance survivor = SimulatedInstance.inAgreementWithTheServer()) {
            insertIdentity(survivor.connection(), party, contested, "ACTIVE");
            survivor.commit();
        }

        assertThat(identitiesWithLogin(contested))
                .as("the abandoned attempt left nothing behind")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("contention is real: the losers were refused, not merely late")
    void theRaceIsNotVacuous() throws Exception {
        // Every assertion above is of the form "exactly one succeeded". All of them would pass if
        // the racers ran one after another - which is exactly the mode this test exists to escape,
        // and the failure this repository has met when a latch made threads START together while
        // the winner finished before the others began.
        //
        // A unique violation can only be raised by a transaction that met another transaction's
        // row. Requiring RACERS - 1 of them above is therefore already the proof that they
        // overlapped; this asserts the arrangement that makes it so, rather than trusting it.
        UUID party = givenAParty();
        Outcome outcome = race(instance -> insertCustomer(instance.connection(), party, "PENDING"));

        assertThat(outcome.uniqueViolations())
                .as("a unique violation can only be raised by a transaction that met another "
                        + "transaction's row, so requiring nine of them IS the proof they overlapped")
                .isEqualTo(RACERS - 1);
        assertThat(outcome.attempted()).isEqualTo(RACERS);
        assertThat(outcome.succeeded() + outcome.uniqueViolations())
                .as("every racer reached a decided outcome; none was lost or silently skipped")
                .isEqualTo(RACERS);
    }

    // -----------------------------------------------------------------

    private record Outcome(int attempted, int succeeded, int uniqueViolations, List<String> otherFailures) {}

    /**
     * Runs {@code work} on {@link #RACERS} instances, each with its own connection, released
     * together and each holding its transaction open until all have attempted.
     *
     * <p>Holding the transactions open is what creates the overlap. Committing immediately would
     * let a fast racer finish before a slow one started, and the test would then be a sequential
     * test wearing threads.
     */
    private static Outcome race(ThrowingConsumer work) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger uniqueViolations = new AtomicInteger();
        List<String> otherFailures = java.util.Collections.synchronizedList(new ArrayList<>());

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
                                                // Released together. From here the database decides
                                                // the order, which is the whole point: one racer
                                                // takes the lock and the rest block inside their
                                                // insert until it commits.
                                                release.await();
                                                try {
                                                    work.accept(instance);
                                                    instance.commit();
                                                    succeeded.incrementAndGet();
                                                } catch (SQLException refused) {
                                                    if (UNIQUE_VIOLATION.equals(refused.getSQLState())) {
                                                        uniqueViolations.incrementAndGet();
                                                    } else {
                                                        otherFailures.add(
                                                                refused.getSQLState() + " " + refused.getMessage());
                                                    }
                                                    instance.rollback();
                                                }
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

        return new Outcome(RACERS, succeeded.get(), uniqueViolations.get(), otherFailures);
    }

    @FunctionalInterface
    private interface ThrowingConsumer {
        void accept(SimulatedInstance instance) throws SQLException;
    }

    private static UUID givenAParty() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement =
                        app.prepareStatement(
                                "INSERT INTO party.party (id, kind, display_name, registered_at)"
                                        + " VALUES (?, 'PERSON', 'Ada Lovelace', now())")) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
        return id;
    }

    private static void insertCustomer(Connection connection, UUID partyId, String status)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO party.customer"
                                + " (id, party_id, status, opened_at, status_changed_at)"
                                + " VALUES (?, ?, ?, now(), now())")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, partyId);
            statement.setString(3, status);
            statement.executeUpdate();
        }
    }

    private static void insertIdentity(
            Connection connection, UUID partyId, String loginIdentifier, String status)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO identity.identity"
                                + " (id, party_id, login_identifier, status, created_at, status_changed_at)"
                                + " VALUES (?, ?, ?, ?, now(), now())")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, partyId);
            statement.setString(3, loginIdentifier);
            statement.setString(4, status);
            statement.executeUpdate();
        }
    }

    private static int liveCustomersOf(UUID partyId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement =
                        app.prepareStatement(
                                "SELECT count(*) FROM party.customer"
                                        + " WHERE party_id = ? AND status <> 'CLOSED'")) {
            statement.setObject(1, partyId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static int identitiesWithLogin(String loginIdentifier) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement =
                        app.prepareStatement(
                                "SELECT count(*) FROM identity.identity WHERE login_identifier = ?")) {
            statement.setString(1, loginIdentifier);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }
}
