package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Identity;
import com.finapp.identity.IdentityId;
import com.finapp.identity.IdentityStatus;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.JdbcIdentityStore;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.SimulatedInstance;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Suspension under concurrent instances (`P1-TSK-028`).
 *
 * <h2>What goes wrong without the conditional, and it is not a lost update</h2>
 *
 * <p>An unconditional {@code UPDATE … SET status = 'SUSPENDED' WHERE id = ?} produces the
 * <strong>correct end state</strong> from every instance: the identity really is suspended. What it
 * also produces is <em>ten</em> audit records and ten events for <em>one</em> transition — a trail
 * saying that ten administrators independently suspended somebody, when one did and nine arrived
 * after the fact.
 *
 * <p>That is worse than an ordinary lost update, because nothing looks wrong: the row is right, no
 * error is raised, and the defect lives only in the record an investigator would later rely on
 * ({@code INV-HIST-03} makes it permanent). Asserting the row's status alone would pass over it,
 * which is the {@code P1-TSK-008} finding — <em>assert the coordination, not the end state</em>.
 *
 * <p>Ten instances, ten connections — the {@code P0-TST-009} convention. Two instances sharing one
 * connection serialise themselves and the test proves nothing.
 */
@Tag("database")
@DisplayName("suspension under concurrent instances (P1-TSK-028)")
class SuspensionConcurrencyDatabaseTest {

    private static final int INSTANCES = 10;
    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private final IdentityStore<Connection> identities = new JdbcIdentityStore();

    @Test
    @DisplayName("ten instances suspending the same identity produce exactly one transition")
    void concurrentSuspensionsProduceOneTransition() throws Exception {
        IdentityId identity = givenAnIdentity();
        AtomicInteger won = new AtomicInteger();

        raceOn(
                instance -> {
                    Identity loaded = identities.findById(instance.connection(), identity).orElseThrow();
                    if (loaded.status() == IdentityStatus.ACTIVE
                            && identities.moveStatus(
                                    instance.connection(),
                                    identity,
                                    IdentityStatus.ACTIVE,
                                    loaded.suspend(instance.clock()))) {
                        won.incrementAndGet();
                    }
                    instance.commit();
                    return null;
                });

        // The row count is the outcome. Without `AND status = ?` every instance would be told it
        // won, and every one would go on to write an audit record.
        assertThat(won.get())
                .as("exactly one instance may believe it performed the suspension")
                .isEqualTo(1);
        assertThat(statusOf(identity)).isEqualTo("SUSPENDED");
    }

    @Test
    @DisplayName("a rolled-back suspension leaves the identity ACTIVE and suspendable")
    void aRolledBackSuspensionChangesNothing() throws Exception {
        IdentityId identity = givenAnIdentity();

        try (SimulatedInstance crashing = SimulatedInstance.inAgreementWithTheServer()) {
            Identity loaded = identities.findById(crashing.connection(), identity).orElseThrow();
            assertThat(
                            identities.moveStatus(
                                    crashing.connection(),
                                    identity,
                                    IdentityStatus.ACTIVE,
                                    loaded.suspend(crashing.clock())))
                    .isTrue();
            crashing.rollback();
        }

        // A transition consumed by an attempt that never committed would mean a crashed
        // administrator permanently prevents the real suspension - and it would present as "this
        // identity is not active" while the identity is plainly active. The P1-TSK-005 shape.
        assertThat(statusOf(identity)).isEqualTo("ACTIVE");
        try (SimulatedInstance retrying = SimulatedInstance.inAgreementWithTheServer()) {
            Identity loaded = identities.findById(retrying.connection(), identity).orElseThrow();
            assertThat(
                            identities.moveStatus(
                                    retrying.connection(),
                                    identity,
                                    IdentityStatus.ACTIVE,
                                    loaded.suspend(retrying.clock())))
                    .isTrue();
            retrying.commit();
        }
        assertThat(statusOf(identity)).isEqualTo("SUSPENDED");
    }

    // -----------------------------------------------------------------

    private interface InstanceWork {
        Void run(SimulatedInstance instance) throws Exception;
    }

    private static void raceOn(InstanceWork work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(INSTANCES);
        CyclicBarrier start = new CyclicBarrier(INSTANCES);
        try {
            List<Callable<Void>> racers =
                    IntStream.range(0, INSTANCES)
                            .<Callable<Void>>mapToObj(
                                    i ->
                                            () -> {
                                                try (SimulatedInstance instance =
                                                        SimulatedInstance
                                                                .inAgreementWithTheServer()) {
                                                    start.await();
                                                    return work.run(instance);
                                                }
                                            })
                            .toList();
            for (Future<Void> outcome : pool.invokeAll(racers)) {
                outcome.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static String statusOf(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement("SELECT status FROM identity.identity WHERE id = ?")) {
            select.setObject(1, identity.value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static IdentityId givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Ada Lovelace', now())",
                    party);
            // Back-dated: the fixture writes created_at from the container's clock and the
            // suspension writes status_changed_at from the JVM's, and the two drift here
            // (P1-TSK-031, CURRENT_STATE.md section Local Environment Prerequisites).
            execute(
                    app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at)"
                            + " VALUES (?, ?, ?, 'ACTIVE', now() - interval '1 hour',"
                            + " now() - interval '1 hour')",
                    identity,
                    party,
                    "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        return IdentityId.of(identity);
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
