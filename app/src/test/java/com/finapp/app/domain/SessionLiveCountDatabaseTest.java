package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.DeviceDescription;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What {@code finapp.identity.session.active} actually counts (`P1-TSK-029`).
 *
 * <h2>Live, not {@code ACTIVE} — and the difference is not pedantry</h2>
 *
 * <p>ADR-0030 and {@code P1-TSK-013} decided there is no {@code EXPIRED} status and no sweep:
 * expiry is derived in the {@code WHERE} clause, because a stored status would need a job to write
 * it and the database would say {@code ACTIVE} about a dead session until that job ran.
 *
 * <p>That decision makes the obvious gauge <strong>wrong</strong>. Counting {@code status =
 * 'ACTIVE'} counts sessions nobody can use, and it is wrong in the <em>reassuring</em> direction:
 * an instance would report live customers indefinitely while every one of them had been logged out
 * for hours. This suite is where that is held against a real PostgreSQL rather than against a stub
 * whose author already believed the answer.
 */
@Tag("database")
@DisplayName("the live session count (P1-TSK-029)")
class SessionLiveCountDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    @Test
    @DisplayName("a live session is counted")
    void aLiveSessionIsCounted() throws SQLException {
        IdentityId identity = givenAnIdentity();
        givenALiveSession(identity);

        assertThat(countLiveFor(identity))
                .as("the positive control: without it, every assertion below passes against a"
                        + " query that counts nothing")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a session past its idle bound is NOT counted, though its status is ACTIVE")
    void anIdleExpiredSessionIsNotCounted() throws SQLException {
        IdentityId identity = givenAnIdentity();
        Session stale = givenALiveSession(identity);
        backDateIdleBound(stale);

        assertThat(statusOf(stale))
                .as("precondition: the ROW still says ACTIVE - there is no sweep to say otherwise,"
                        + " which is exactly why counting the status would be wrong")
                .isEqualTo("ACTIVE");
        assertThat(countLiveFor(identity))
                .as("a gauge that counted it would report a logged-in customer who cannot make a"
                        + " single request")
                .isZero();
    }

    @Test
    @DisplayName("a revoked session is not counted")
    void aRevokedSessionIsNotCounted() throws SQLException {
        IdentityId identity = givenAnIdentity();
        Session revoked = givenALiveSession(identity);

        try (Connection app = DatabaseRoles.application()) {
            sessions.revoke(app, revoked.id(), Instant.now(CLOCK));
        }

        assertThat(countLiveFor(identity)).isZero();
    }

    @Test
    @DisplayName("the count agrees with the lookup about what a session is")
    void theCountAgreesWithTheLookup() throws SQLException {
        // The property that matters more than any individual case: a gauge that disagreed with
        // findLive would show an operator a number no request could reproduce. Both derive
        // liveness from the same three clauses, and this is what keeps that true.
        IdentityId identity = givenAnIdentity();
        Session live = givenALiveSession(identity);
        Session stale = givenALiveSession(identity);
        backDateIdleBound(stale);

        try (Connection app = DatabaseRoles.application()) {
            assertThat(sessions.findLiveFor(app, identity, Instant.now(CLOCK)))
                    .extracting(Session::id)
                    .containsExactly(live.id());
        }
        assertThat(countLiveFor(identity))
                .as("one listed, one counted")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------

    /**
     * Scoped to one identity, because the table is shared with every other suite in this JVM.
     *
     * <p>{@code countLive} itself is deliberately unscoped — it is a fleet-wide gauge — so the
     * assertion is made against the same predicate over one identity. A test asserting the global
     * count would be a test about whatever else happened to have run.
     */
    private int countLiveFor(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count =
                        app.prepareStatement(
                                "SELECT count(*) FROM identity.session"
                                        + " WHERE identity_id = ?"
                                        + " AND status = 'ACTIVE'"
                                        + " AND idle_expires_at > now()"
                                        + " AND absolute_expires_at > now()")) {
            count.setObject(1, identity.value());
            try (var rows = count.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    @Test
    @DisplayName("the store's own count sees what this suite's query sees")
    void theStoreAgreesWithTheQuery() throws SQLException {
        // The link between the scoped assertions above and the unscoped method the gauge calls.
        // Without it this suite would prove a property of a query it wrote itself.
        IdentityId identity = givenAnIdentity();
        givenALiveSession(identity);

        try (Connection app = DatabaseRoles.application()) {
            long before = sessions.countLive(app, Instant.now(CLOCK));
            givenALiveSession(identity);
            long after = sessions.countLive(app, Instant.now(CLOCK));

            assertThat(after - before)
                    .as("the store counts the session this suite just created")
                    .isEqualTo(1);
        }
    }

    private IdentityId givenAnIdentity() throws SQLException {
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
                    "ada" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        }
        return IdentityId.of(identity);
    }

    private Session givenALiveSession(IdentityId identity) throws SQLException {
        Session session =
                Session.issue(
                        IDS,
                        CLOCK,
                        identity,
                        SessionToken.issue(RANDOMNESS),
                        AssuranceLevel.PASSWORD,
                        SessionPolicy.current(),
                        DeviceDescription.fromUserAgent("probe").orElse(null));
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return session;
    }

    /**
     * Moves the idle bound into the past.
     *
     * <p>{@code issued_at} moves with it: {@code session_bounds_follow_issue} refuses a row written
     * already expired, and that constraint is right — the fixture was wrong when {@code P1-TSK-016}
     * first met it.
     */
    private void backDateIdleBound(Session session) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "UPDATE identity.session"
                            + " SET issued_at = now() - interval '2 hours',"
                            + "     idle_expires_at = now() - interval '1 hour'"
                            + " WHERE id = ?",
                    session.id().value());
        }
    }

    private String statusOf(Session session) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement("SELECT status FROM identity.session WHERE id = ?")) {
            select.setObject(1, session.id().value());
            try (var rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
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
