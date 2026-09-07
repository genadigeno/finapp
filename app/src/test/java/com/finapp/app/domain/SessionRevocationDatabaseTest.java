package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.Session;
import com.finapp.identity.SessionId;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionRevocation;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.SimulatedInstance;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Revocation is immediate, on every instance (`P1-TSK-014`, {@code INV-IDN-03}).
 *
 * <h2>The invariant's own stated verification</h2>
 *
 * <p>{@code INV-IDN-03} names it: <em>"multi-instance test: revoke on one instance, assert refusal
 * on another"</em>. Each instance gets its own connection — the {@code P0-TST-009} convention —
 * because a shared one would make the second read the first's uncommitted state and the test would
 * prove the opposite of what it claims.
 *
 * <h2>And the race the plan states as an absolute</h2>
 *
 * <p>{@code PHASE_1_PLAN.md} §8: <em>"Revocation wins. A session must never survive a concurrent
 * revoke."</em> The easy reading — a lookup racing a revoke — is true without any new mechanism. The
 * hard one is a session <strong>issued</strong> concurrently with a revoke-all, and it was
 * <strong>verified to be broken before the fix was written</strong>: an insert landing after the
 * revoke had selected its rows produced a session the revoke never saw, so an attacker holding the
 * old password kept a live session across a password change. Both sides now take a lock on the
 * identity.
 */
@Tag("database")
@DisplayName("session revocation (P1-TSK-014)")
class SessionRevocationDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    @Test
    @DisplayName("revoked on one instance, refused on another (INV-IDN-03)")
    void revocationIsImmediateAcrossInstances() throws Exception {
        IdentityId identity = givenAnIdentity();
        SessionToken token = SessionToken.issue(RANDOMNESS);
        Session session = issue(identity, token);

        try (SimulatedInstance issuer = SimulatedInstance.inAgreementWithTheServer()) {
            sessions.insert(issuer.connection(), session);
            issuer.commit();
        }

        // Instance A revokes. Instance B - a different connection, as a different pod would be -
        // must refuse on its very next lookup, with nothing having told it anything.
        try (SimulatedInstance a = SimulatedInstance.inAgreementWithTheServer();
                SimulatedInstance b = SimulatedInstance.inAgreementWithTheServer()) {

            assertThat(sessions.findLive(b.connection(), token, SimulatedInstance.serverNow()))
                    .as("precondition: instance B can see the session before it is revoked")
                    .isPresent();

            assertThat(sessions.revoke(a.connection(), session.id(), SimulatedInstance.serverNow()))
                    .isTrue();
            a.commit();
            // B's transaction started before A committed, so it must not read a stale snapshot.
            b.rollback();

            assertThat(sessions.findLive(b.connection(), token, SimulatedInstance.serverNow()))
                    .as("an eventually-revoked session is an unrevoked session")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("issuance BLOCKS while a revoke-all is open: the two cannot interleave")
    void revocationWinsAgainstAConcurrentIssue() throws Exception {
        // The hard reading of PHASE_1_PLAN.md §8, and the assertion is THE COORDINATION rather than
        // the outcome - because the outcome is identical either way.
        //
        // The first version of this test asserted the end state and a mutation removing the lock
        // SURVIVED it: with the lock the insert serialises after the revoke and the new session is
        // live; without it the insert races and the new session is also live. Same rows, opposite
        // mechanisms. What distinguishes them is that with the lock the insert cannot COMPLETE while
        // the revoke is open - so the test waits for PostgreSQL to report the issuer waiting on a
        // lock, which is the P0-TST-004 idiom and is deterministic rather than timed.
        IdentityId identity = givenAnIdentity();
        SessionToken existing = SessionToken.issue(RANDOMNESS);
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, issue(identity, existing));
        }

        CountDownLatch revokeHasRun = new CountDownLatch(1);
        CountDownLatch mayCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> revoker =
                    pool.submit(
                            () -> {
                                try (Connection own = transactional()) {
                                    sessions.revokeAllFor(own, identity, Instant.now(CLOCK));
                                    revokeHasRun.countDown();
                                    mayCommit.await(60, TimeUnit.SECONDS);
                                    own.commit();
                                }
                                return null;
                            });

            Future<?> issuer =
                    pool.submit(
                            () -> {
                                try (Connection own = transactional()) {
                                    revokeHasRun.await(30, TimeUnit.SECONDS);
                                    sessions.insert(
                                            own,
                                            issue(identity, SessionToken.issue(RANDOMNESS)));
                                    own.commit();
                                }
                                return null;
                            });

            // The database's own answer to "is somebody blocked?". Without the identity lock the
            // issuer never waits, this never becomes true, and the test fails on the bound rather
            // than passing quietly.
            assertThat(waitUntilTheInsertIsBlocked())
                    .as("the issuer must BLOCK on the identity lock while the revoke is open."
                            + " If it does not, the two interleaved - and a session issued inside"
                            + " that window is one the revocation never saw")
                    .isTrue();

            mayCommit.countDown();
            revoker.get(60, TimeUnit.SECONDS);
            issuer.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        try (Connection app = DatabaseRoles.application()) {
            assertThat(sessions.findLive(app, existing, Instant.now(CLOCK)))
                    .as("the session that existed when the revocation ran is gone")
                    .isEmpty();
            assertThat(liveSessionCount(app, identity))
                    .as("and exactly the one issued afterwards remains - nothing the revocation"
                            + " should have caught survived it")
                    .isEqualTo(1);
        }
    }

    /**
     * Waits until PostgreSQL reports <strong>the session insert</strong> blocked on a lock.
     *
     * <p>Observing the database's own view rather than sleeping: a fixed sleep would pass on a slow
     * machine for the wrong reason and fail on a fast one. The bound is generous because exceeding
     * it is a failure and never a pass.
     *
     * <p><strong>Scoped to the issuer's own statement</strong>, and the completion gate had to add
     * that. The first version counted <em>any</em> backend waiting on a lock in this database, which
     * is a different claim: it would be satisfied by anything else contending and would pass while
     * saying nothing about whether the insert was blocked. Matching the statement text makes the
     * assertion say what it means.
     */
    private static boolean waitUntilTheInsertIsBlocked() throws Exception {
        Instant deadline = Instant.now(CLOCK).plusSeconds(20);
        while (Instant.now(CLOCK).isBefore(deadline)) {
            try (Connection observer = DatabaseRoles.application();
                    PreparedStatement select =
                            observer.prepareStatement(
                                    "SELECT count(*) FROM pg_stat_activity"
                                        + " WHERE wait_event_type = 'Lock'"
                                        + " AND datname = current_database()"
                                        + " AND pid <> pg_backend_pid()"
                                        + " AND query LIKE '%INSERT INTO identity.session%'");
                    ResultSet rows = select.executeQuery()) {
                rows.next();
                if (rows.getInt(1) > 0) {
                    return true;
                }
            }
            Thread.sleep(25);
        }
        return false;
    }

    @Test
    @DisplayName("revoke-all ends every session of that identity and none of another's")
    void revokeAllIsScopedToItsIdentity() throws SQLException {
        // A predicate of `id <> ?` alone would revoke every session on the platform except one, so
        // the scope is asserted with a second identity present rather than assumed.
        IdentityId mine = givenAnIdentity();
        IdentityId somebodyElse = givenAnIdentity();
        SessionToken theirs = SessionToken.issue(RANDOMNESS);

        try (Connection app = DatabaseRoles.application()) {
            for (int i = 0; i < 3; i++) {
                sessions.insert(app, issue(mine, SessionToken.issue(RANDOMNESS)));
            }
            sessions.insert(app, issue(somebodyElse, theirs));

            assertThat(sessions.revokeAllFor(app, mine, Instant.now(CLOCK))).isEqualTo(3);

            assertThat(liveSessionCount(app, mine)).isZero();
            assertThat(sessions.findLive(app, theirs, Instant.now(CLOCK)))
                    .as("somebody else's session is untouched")
                    .isPresent();
        }
    }

    @Test
    @DisplayName("revoke-all-except spares exactly the session named")
    void revokeAllExceptSparesOne() throws SQLException {
        IdentityId identity = givenAnIdentity();
        SessionToken kept = SessionToken.issue(RANDOMNESS);
        Session keeper = issue(identity, kept);

        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, keeper);
            sessions.insert(app, issue(identity, SessionToken.issue(RANDOMNESS)));
            sessions.insert(app, issue(identity, SessionToken.issue(RANDOMNESS)));

            assertThat(sessions.revokeAllForExcept(app, identity, keeper.id(), Instant.now(CLOCK)))
                    .isEqualTo(2);

            assertThat(sessions.findLive(app, kept, Instant.now(CLOCK)))
                    .as("the session the password is being changed FROM stays alive")
                    .isPresent();
            assertThat(liveSessionCount(app, identity)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("revocation is terminal, and revoking again is not an error")
    void revocationIsTerminalAndIdempotentInEffect() throws SQLException {
        // INV-LIFE-04. And re-revoking must report "nothing to do" rather than failing: a failure
        // would let a caller tell "that session existed and was live" from "it did not", which is an
        // oracle over somebody else's session identifiers.
        IdentityId identity = givenAnIdentity();
        Session session = issue(identity, SessionToken.issue(RANDOMNESS));

        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);

            assertThat(sessions.revoke(app, session.id(), Instant.now(CLOCK))).isTrue();
            assertThat(sessions.revoke(app, session.id(), Instant.now(CLOCK)))
                    .as("terminal: the second revocation finds nothing live to end")
                    .isFalse();
            assertThat(sessions.revoke(app, SessionId.next(IDS), Instant.now(CLOCK)))
                    .as("and a session that never existed answers exactly the same way")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("revoking an already-expired session is not an error either")
    void revokingAnExpiredSessionIsNotAnError() throws SQLException {
        IdentityId identity = givenAnIdentity();
        SessionPolicy brief = new SessionPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1));
        Session session =
                Session.issue(
                        IDS,
                        CLOCK,
                        identity,
                        SessionToken.issue(RANDOMNESS),
                        AssuranceLevel.PASSWORD,
                        brief);

        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);

            // Still ACTIVE by status - expiry is derived - so this DOES transition it. The point is
            // that it does not fail, and the caller learns nothing about why.
            assertThat(sessions.revoke(app, session.id(), session.absoluteExpiresAt().plusSeconds(1)))
                    .isTrue();
        }
    }

    @Test
    @DisplayName("ten instances revoking one session: exactly one transition")
    void oneTransitionUnderContention() throws Exception {
        IdentityId identity = givenAnIdentity();
        Session session = issue(identity, SessionToken.issue(RANDOMNESS));
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }

        int racers = 10;
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < racers; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    go.await(30, TimeUnit.SECONDS);
                                    try (Connection own = DatabaseRoles.application()) {
                                        return sessions.revoke(
                                                own, session.id(), Instant.now(CLOCK));
                                    }
                                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            int winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get(60, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners)
                    .as("the conditional UPDATE is the coordination: one transition, and nine"
                            + " racers told they lost rather than nine silent no-ops")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("one audit record per operation, whatever the session count")
    @SuppressWarnings("try") // The Scopes are used for their close side effect.
    void bulkRevocationIsAuditedOnce() throws SQLException {
        IdentityId identity = givenAnIdentity();

        try (CorrelationContext.Scope ignored =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                // Transactional, because the audit writer refuses an auto-commit connection: the
                // record must commit with the action it records, or a rolled-back revocation would
                // leave a trail saying it happened (ADR-0010). The guard doing its job.
                Connection app = transactional()) {

            for (int i = 0; i < 5; i++) {
                sessions.insert(app, issue(identity, SessionToken.issue(RANDOMNESS)));
            }

            SessionRevocation revocation =
                    new SessionRevocation(sessions, IDS, CLOCK, new JdbcAuditWriter());
            assertThat(revocation.revokeAll(app, identity)).isEqualTo(5);

            assertThat(auditRows(app, identity))
                    .as("five sessions ended by one decision is one record: the rows carry"
                            + " revoked_at and say WHEN, the trail says WHO decided")
                    .isEqualTo(1);
            assertThat(auditChangeSummary(app, identity))
                    .as("and the count is where an investigator can see it")
                    .contains("sessionsRevoked=5");
            app.commit();
        }
    }

    @Test
    @DisplayName("a single revocation is audited against the session, and only when one ended")
    @SuppressWarnings("try") // The Scopes are used for their close side effect.
    void aSingleRevocationIsAudited() throws SQLException {
        // The completion gate found only the bulk path had its audit asserted. Three claims here
        // that nothing else covered: the single path writes a record at all; its target is the
        // SESSION rather than the identity, because that is what was acted on; and a revocation
        // that ended nothing writes NOTHING - a record for a caller's guess at a session identifier
        // would put identifiers that were never real into the trail.
        IdentityId identity = givenAnIdentity();
        Session session = issue(identity, SessionToken.issue(RANDOMNESS));

        try (CorrelationContext.Scope ignored =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = transactional()) {

            sessions.insert(app, session);
            SessionRevocation revocation =
                    new SessionRevocation(sessions, IDS, CLOCK, new JdbcAuditWriter());

            assertThat(revocation.revoke(app, session.id(), identity)).isTrue();
            assertThat(auditRowsForTarget(app, session.id().value().toString()))
                    .as("audited against the session, which is what was acted on")
                    .isEqualTo(1);

            // A second revocation ends nothing, so it records nothing.
            assertThat(revocation.revoke(app, session.id(), identity)).isFalse();
            assertThat(auditRowsForTarget(app, session.id().value().toString()))
                    .as("a revocation that ended nothing must not appear in the trail")
                    .isEqualTo(1);

            SessionId neverExisted = SessionId.next(IDS);
            assertThat(revocation.revoke(app, neverExisted, identity)).isFalse();
            assertThat(auditRowsForTarget(app, neverExisted.value().toString()))
                    .as("nor may a caller's guess at an identifier put that identifier in the trail")
                    .isZero();

            app.commit();
        }
    }

    @Test
    @DisplayName("revoke-all-except records what it spared, not only what it ended")
    @SuppressWarnings("try") // The Scopes are used for their close side effect.
    void revokeAllExceptRecordsWhatItSpared() throws SQLException {
        // The credential-change path, and the fact an investigator actually needs: "the password was
        // changed and eleven other sessions were closed" is a different fact from "and none were",
        // and only the first suggests somebody else was using the account.
        IdentityId identity = givenAnIdentity();
        Session keeper = issue(identity, SessionToken.issue(RANDOMNESS));

        try (CorrelationContext.Scope ignored =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = transactional()) {

            sessions.insert(app, keeper);
            sessions.insert(app, issue(identity, SessionToken.issue(RANDOMNESS)));
            sessions.insert(app, issue(identity, SessionToken.issue(RANDOMNESS)));

            SessionRevocation revocation =
                    new SessionRevocation(sessions, IDS, CLOCK, new JdbcAuditWriter());
            assertThat(revocation.revokeAllExcept(app, identity, keeper.id())).isEqualTo(2);

            assertThat(auditChangeSummary(app, identity))
                    .as("the count and the spared session are both recorded")
                    .contains("sessionsRevoked=2")
                    .contains(keeper.id().value().toString());

            app.commit();
        }
    }

    // -----------------------------------------------------------------

    private static Session issue(IdentityId identity, SessionToken token) {
        return Session.issue(
                IDS, CLOCK, identity, token, AssuranceLevel.PASSWORD, SessionPolicy.current());
    }

    private static Connection transactional() throws SQLException {
        Connection connection = DatabaseRoles.application();
        connection.setAutoCommit(false);
        return connection;
    }

    private static int liveSessionCount(Connection connection, IdentityId identity)
            throws SQLException {
        return count(
                connection,
                "SELECT count(*) FROM identity.session WHERE identity_id = ? AND status = 'ACTIVE'",
                identity.value());
    }

    private static int revokedSessionCount(Connection connection, IdentityId identity)
            throws SQLException {
        return count(
                connection,
                "SELECT count(*) FROM identity.session WHERE identity_id = ? AND status = 'REVOKED'",
                identity.value());
    }

    private static int auditRows(Connection connection, IdentityId identity) throws SQLException {
        return auditRowsForTarget(connection, identity.value().toString());
    }

    private static int auditRowsForTarget(Connection connection, String targetId)
            throws SQLException {
        return count(
                connection,
                "SELECT count(*) FROM platform.audit_record"
                        + " WHERE operation = 'identity.SessionRevoked' AND target_id = ?",
                targetId);
    }

    private static String auditChangeSummary(Connection connection, IdentityId identity)
            throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT change_summary FROM platform.audit_record"
                                + " WHERE operation = 'identity.SessionRevoked' AND target_id = ?")) {
            select.setString(1, identity.value().toString());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static int count(Connection connection, String sql, Object argument)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setObject(1, argument);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getInt(1);
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
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            statement.executeUpdate();
        }
    }
}
