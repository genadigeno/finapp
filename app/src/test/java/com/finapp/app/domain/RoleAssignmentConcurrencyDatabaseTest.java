package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcRoleAssignmentStore;
import com.finapp.identity.RoleAssignmentStore;
import com.finapp.identity.RoleName;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.SimulatedInstance;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
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
 * Role assignment under concurrent instances (`P1-TSK-020`, ADR-0014, {@code INV-CON-01}).
 *
 * <h2>Why the completion gate added this</h2>
 *
 * <p>{@code JdbcRoleAssignmentStore.assign}'s own comment claims <em>"ten instances granting the
 * same role produce one assignment and nine are told they lost"</em>, and <strong>nothing tested
 * it</strong>. {@code CLAUDE.md}'s multi-instance rule is not conditional on the operation being
 * financial: <em>"a feature is not complete until it remains correct under concurrent execution by
 * multiple instances"</em>.
 *
 * <p>Ten instances, ten connections — the {@code P0-TST-009} convention. Two instances sharing one
 * connection serialise themselves and the test proves nothing.
 *
 * <h2>What would go wrong without it, and why it is not merely untidy</h2>
 *
 * <p>Two live rows for one identity and role means revocation becomes <strong>partial</strong>:
 * {@code revoke} is a conditional {@code UPDATE} whose row count is the outcome, so it would report
 * success having revoked one of them, and the identity would keep the role. <em>"Remove their
 * access now"</em> would return success and be false — the exact failure the per-request resolution
 * exists to prevent, arriving through a different door.
 */
@Tag("database")
@DisplayName("role assignment under concurrent instances (P1-TSK-020)")
class RoleAssignmentConcurrencyDatabaseTest {

    private static final int INSTANCES = 10;
    private static final IdGenerator IDS =
            new IdGenerator(Clock.system(ZoneOffset.UTC), new SecureRandom());

    private final RoleAssignmentStore<Connection> roles = new JdbcRoleAssignmentStore(IDS);

    @Test
    @DisplayName("ten instances granting the same role produce exactly one live assignment")
    void concurrentGrantsProduceOneAssignment() throws Exception {
        IdentityId identity = givenAnIdentity();
        AtomicInteger won = new AtomicInteger();

        raceOn(
                instance -> {
                    if (roles.assign(
                            instance.connection(),
                            identity,
                            RoleName.ADMINISTRATOR,
                            identity,
                            instance.clock().instant())) {
                        won.incrementAndGet();
                    }
                    instance.commit();
                    return null;
                });

        // The row count is the outcome, so exactly one instance is told it won. Asserting only the
        // row count would pass against an implementation where all ten believe they succeeded -
        // which is the P1-TSK-008 finding: assert the coordination, not just the end state.
        assertThat(won.get()).as("exactly one instance may believe it granted the role").isEqualTo(1);
        assertThat(liveAssignments(identity))
                .as("the partial unique index admits exactly one live row")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a rolled-back grant does not consume the slot")
    void aRolledBackGrantLeavesTheSlotFree() throws Exception {
        IdentityId identity = givenAnIdentity();

        try (SimulatedInstance crashing = SimulatedInstance.inAgreementWithTheServer()) {
            assertThat(
                            roles.assign(
                                    crashing.connection(),
                                    identity,
                                    RoleName.ADMINISTRATOR,
                                    identity,
                                    crashing.clock().instant()))
                    .isTrue();
            crashing.rollback();
        }

        // The crash half. A uniqueness slot consumed by an attempt that never committed would mean a
        // failed grant permanently prevents the real one - and it would present as "that person
        // already has the role" while `liveRolesOf` returns nothing. The P1-TSK-005 shape.
        assertThat(liveAssignments(identity)).isZero();
        try (SimulatedInstance retrying = SimulatedInstance.inAgreementWithTheServer()) {
            assertThat(
                            roles.assign(
                                    retrying.connection(),
                                    identity,
                                    RoleName.ADMINISTRATOR,
                                    identity,
                                    retrying.clock().instant()))
                    .as("the slot must still be free after a rolled-back attempt")
                    .isTrue();
            retrying.commit();
        }
        assertThat(liveAssignments(identity)).isEqualTo(1);
    }

    @Test
    @DisplayName("ten instances revoking the same role produce exactly one revocation")
    void concurrentRevocationsProduceOneRevocation() throws Exception {
        IdentityId identity = givenAnIdentity();
        try (SimulatedInstance granting = SimulatedInstance.inAgreementWithTheServer()) {
            roles.assign(
                    granting.connection(),
                    identity,
                    RoleName.ADMINISTRATOR,
                    identity,
                    granting.clock().instant());
            granting.commit();
        }

        AtomicInteger won = new AtomicInteger();
        raceOn(
                instance -> {
                    if (roles.revoke(
                            instance.connection(),
                            identity,
                            RoleName.ADMINISTRATOR,
                            identity,
                            instance.clock().instant())) {
                        won.incrementAndGet();
                    }
                    instance.commit();
                    return null;
                });

        // Ten operators clicking "revoke" must produce one audit-worthy revocation, not ten. The
        // conditional UPDATE's `revoked_at IS NULL` is what makes the row count the answer; without
        // it every instance would rewrite the row and each would report success, so the trail would
        // record ten revocations of a role that was withdrawn once.
        assertThat(won.get()).isEqualTo(1);
        assertThat(liveAssignments(identity)).isZero();
    }

    // -----------------------------------------------------------------
    // The clock

    /**
     * A role is revoked on the clock of whichever instance serves the revocation, which may read
     * behind the one that granted it - {@code Authorization} reads each from its own instance's
     * injected Clock. The store's conditional driven directly: what such a clock can break is the
     * statement.
     */
    @Test
    @DisplayName("a clock behind the grant cannot fail a legal revocation: revoked_at clamps to"
            + " assigned_at in the statement (the P1-TSK-031 drift; ADR-0014)")
    void aClockBehindTheGrantCannotFailALegalRevocation() throws Exception {
        IdentityId clamped = givenAnIdentity();
        IdentityId own = givenAnIdentity();
        try (SimulatedInstance granting = SimulatedInstance.inAgreementWithTheServer()) {
            for (IdentityId identity : List.of(clamped, own)) {
                assertThat(
                                roles.assign(
                                        granting.connection(),
                                        identity,
                                        RoleName.ADMINISTRATOR,
                                        identity,
                                        granting.clock().instant()))
                        .isTrue();
            }
            granting.commit();
        }

        Instant born = stampsOf(clamped).assignedAt();
        try (Connection revoking = DatabaseRoles.application()) {
            assertThat(
                            roles.revoke(
                                    revoking,
                                    clamped,
                                    RoleName.ADMINISTRATOR,
                                    clamped,
                                    born.minusMillis(250)))
                    .isTrue();
        }
        assertThat(stampsOf(clamped).revokedAt()).isEqualTo(born);

        // A floor, not a pin: a clock past the grant stamps its own read.
        Instant later = stampsOf(own).assignedAt().plusSeconds(5);
        try (Connection revoking = DatabaseRoles.application()) {
            assertThat(roles.revoke(revoking, own, RoleName.ADMINISTRATOR, own, later)).isTrue();
        }
        assertThat(stampsOf(own).revokedAt()).isEqualTo(later);
    }

    @Test
    @DisplayName("revoking a revoked role under a behind clock is still the conditional's"
            + " refusal, never V010's CHECK")
    void anIllegalRevocationUnderABehindClockIsStillTheConditionalsRefusal() throws Exception {
        IdentityId identity = givenAnIdentity();
        try (SimulatedInstance granting = SimulatedInstance.inAgreementWithTheServer()) {
            assertThat(
                            roles.assign(
                                    granting.connection(),
                                    identity,
                                    RoleName.ADMINISTRATOR,
                                    identity,
                                    granting.clock().instant()))
                    .isTrue();
            granting.commit();
        }
        Instant born = stampsOf(identity).assignedAt();

        try (Connection app = DatabaseRoles.application()) {
            assertThat(roles.revoke(app, identity, RoleName.ADMINISTRATOR, identity, born))
                    .isTrue();
            // Nothing live is zero rows, whatever the clock reads.
            assertThat(
                            roles.revoke(
                                    app,
                                    identity,
                                    RoleName.ADMINISTRATOR,
                                    identity,
                                    born.minusSeconds(1)))
                    .isFalse();
        }
        assertThat(stampsOf(identity).revokedAt()).isEqualTo(born);
    }

    // -----------------------------------------------------------------

    private interface InstanceWork {
        Void run(SimulatedInstance instance) throws Exception;
    }

    /**
     * Releases every instance together and lets the database do the blocking.
     *
     * <p>No barrier <em>inside</em> the statement: {@code P1-TSK-005} found that arranging the
     * overlap that way deadlocks, because the losers are blocked in their insert and can never reach
     * the barrier. The contention needs no arranging.
     */
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

    private static int liveAssignments(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count =
                        app.prepareStatement(
                                "SELECT count(*) FROM identity.role_assignment"
                                        + " WHERE identity_id = ? AND revoked_at IS NULL")) {
            count.setObject(1, identity.value());
            try (ResultSet rows = count.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /** The assignment's two instants as stored, at the column's own microsecond resolution. */
    private record Stamps(Instant assignedAt, Instant revokedAt) {}

    private static Stamps stampsOf(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT assigned_at, revoked_at FROM identity.role_assignment"
                                        + " WHERE identity_id = ?")) {
            read.setObject(1, identity.value());
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                Timestamp revokedAt = row.getTimestamp(2);
                Stamps stamps =
                        new Stamps(
                                row.getTimestamp(1).toInstant(),
                                revokedAt == null ? null : revokedAt.toInstant());
                assertThat(row.next()).as("one assignment per fixture identity").isFalse();
                return stamps;
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
            execute(
                    app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                        + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE', now(), now())",
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
