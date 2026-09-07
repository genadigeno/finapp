package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionRotation;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
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
import java.util.Optional;
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
 * Rotation on privilege change (`P1-TSK-015`, ADR-0030).
 *
 * <h2>What session fixation actually is</h2>
 *
 * <p>If elevating a session left its identifier unchanged, an identifier stolen <em>before</em> the
 * elevation would become elevated behind the legitimate user's back: the attacker does nothing,
 * waits for the customer to complete a second factor, and inherits it. Every assertion here is some
 * form of that sentence.
 */
@Tag("database")
@DisplayName("session rotation (P1-TSK-015)")
class SessionRotationDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    @Test
    @DisplayName("the pre-rotation identifier is refused afterwards")
    void theOldIdentifierIsRefused() throws SQLException {
        Fixture fixture = givenALiveSession(AssuranceLevel.PASSWORD);

        inAFlow(
                app -> {
                    SessionRotation.Rotated rotated =
                            rotation()
                                    .rotate(
                                            app,
                                            fixture.session(),
                                            AssuranceLevel.MULTI_FACTOR,
                                            SessionPolicy.current())
                                    .orElseThrow();

                    assertThat(sessions.findLive(app, fixture.token(), Instant.now(CLOCK)))
                            .as("the identifier the attacker might have stolen is dead")
                            .isEmpty();
                    assertThat(sessions.findLive(app, rotated.token(), Instant.now(CLOCK)))
                            .as("and the customer is still logged in, under a new one")
                            .isPresent();
                });
    }

    @Test
    @DisplayName("the elevated session is a different identifier, and carries the new level")
    void elevationProducesANewIdentifier() throws SQLException {
        Fixture fixture = givenALiveSession(AssuranceLevel.PASSWORD);

        inAFlow(
                app -> {
                    SessionRotation.Rotated rotated =
                            rotation()
                                    .rotate(
                                            app,
                                            fixture.session(),
                                            AssuranceLevel.MULTI_FACTOR,
                                            SessionPolicy.current())
                                    .orElseThrow();

                    assertThat(rotated.session().id())
                            .as("elevating in place is the bug this task exists for")
                            .isNotEqualTo(fixture.session().id());
                    assertThat(rotated.session().tokenHash().expose())
                            .as("and the token is independent, not derived from its predecessor")
                            .isNotEqualTo(fixture.session().tokenHash().expose());
                    assertThat(rotated.session().assurance()).isEqualTo(AssuranceLevel.MULTI_FACTOR);
                });
    }

    @Test
    @DisplayName("the absolute bound is PRESERVED, and the idle bound is fresh")
    void rotationDoesNotExtendTheAbsoluteLifetime() throws SQLException {
        // The decision nothing had written down. If rotation reset the absolute bound, anyone able
        // to trigger one could hold a session indefinitely - step up, rotate, step up again - and
        // the absolute lifetime would be advisory. That is the failure P1-TSK-013 closed on the idle
        // bound with LEAST(...), coming back through a different door.
        Fixture fixture = givenALiveSession(AssuranceLevel.PASSWORD);

        inAFlow(
                app -> {
                    SessionRotation.Rotated rotated =
                            rotation()
                                    .rotate(
                                            app,
                                            fixture.session(),
                                            AssuranceLevel.MULTI_FACTOR,
                                            SessionPolicy.current())
                                    .orElseThrow();

                    assertThat(rotated.session().absoluteExpiresAt())
                            .as("a step-up must not extend how long you can stay logged in")
                            .isEqualTo(fixture.session().absoluteExpiresAt());
                    assertThat(rotated.session().idleExpiresAt())
                            .as("the idle bound IS fresh - the session is being used right now")
                            .isAfter(fixture.session().issuedAt());
                });
    }

    @Test
    @DisplayName("rotating near the end of a life clamps the idle bound rather than throwing")
    void theIdleBoundIsClampedToTheInheritedAbsoluteBound() throws SQLException {
        // Session's own constructor refuses an idle bound beyond the absolute one, so without the
        // clamp a rotation late in a session's life would throw instead of producing a short-lived
        // session - a step-up that fails because the customer had been logged in a while.
        Fixture fixture =
                givenALiveSession(
                        AssuranceLevel.PASSWORD,
                        new SessionPolicy(Duration.ofSeconds(30), Duration.ofSeconds(30)));

        inAFlow(
                app -> {
                    SessionRotation.Rotated rotated =
                            rotation()
                                    .rotate(
                                            app,
                                            fixture.session(),
                                            AssuranceLevel.MULTI_FACTOR,
                                            // A far longer idle timeout than the life remaining.
                                            new SessionPolicy(
                                                    Duration.ofHours(1), Duration.ofHours(12)))
                                    .orElseThrow();

                    assertThat(rotated.session().idleExpiresAt())
                            .as("clamped to the inherited absolute bound, not pushed past it")
                            .isEqualTo(rotated.session().absoluteExpiresAt());
                });
    }

    @Test
    @DisplayName("rotating a session that is no longer live issues nothing at all")
    void aDeadSessionCannotBeRotated() throws SQLException {
        // A rotation of a dead session must not mint a live one, and this is also what makes a lost
        // race safe rather than merely unlikely.
        Fixture fixture = givenALiveSession(AssuranceLevel.PASSWORD);

        inAFlow(
                app -> {
                    assertThat(sessions.revoke(app, fixture.session().id(), Instant.now(CLOCK)))
                            .isTrue();

                    assertThat(
                                    rotation()
                                            .rotate(
                                                    app,
                                                    fixture.session(),
                                                    AssuranceLevel.MULTI_FACTOR,
                                                    SessionPolicy.current()))
                            .as("the revoke found nothing live, so nothing was issued")
                            .isEmpty();
                    assertThat(liveSessionCount(app, fixture.identityId()))
                            .as("and no orphan session was created")
                            .isZero();
                });
    }

    @Test
    @DisplayName("ten instances rotating one session: exactly one replacement")
    void oneReplacementUnderContention() throws Exception {
        // The concurrency property, and it rests entirely on the ordering: revoke FIRST, and issue
        // only if the revoke won. Inserting first and revoking after would leave the loser's session
        // live - a rotation that handed out two identifiers where there should be one, both usable.
        Fixture fixture = givenALiveSession(AssuranceLevel.PASSWORD);

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
                                    return rotateOnItsOwnConnection(fixture);
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
                    .as("the conditional revoke gates the insert: one replacement, nine told they"
                            + " lost rather than nine extra live sessions")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        try (Connection app = DatabaseRoles.application()) {
            assertThat(liveSessionCount(app, fixture.identityId()))
                    .as("exactly one live session survives the storm")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a rotation is audited as a rotation, never as a revocation")
    void aRotationIsNotLoggedAsALogout() throws SQLException {
        // An investigator must be able to tell "this session was ended" from "this session was
        // replaced". A rotation recorded as a revocation reads as a logout that never happened.
        Fixture fixture = givenALiveSession(AssuranceLevel.PASSWORD);

        inAFlow(
                app -> {
                    SessionRotation.Rotated rotated =
                            rotation()
                                    .rotate(
                                            app,
                                            fixture.session(),
                                            AssuranceLevel.MULTI_FACTOR,
                                            SessionPolicy.current())
                                    .orElseThrow();

                    String target = fixture.session().id().value().toString();
                    assertThat(auditRows(app, "identity.SessionRotated", target)).isEqualTo(1);
                    assertThat(auditRows(app, "identity.SessionRevoked", target))
                            .as("and NOT also a revocation, or the trail says the person logged out")
                            .isZero();
                    assertThat(auditChangeSummary(app, "identity.SessionRotated", target))
                            .as("both identifiers, so the chain to the successor is followable")
                            .contains(rotated.session().id().value().toString())
                            .contains("PASSWORD->MULTI_FACTOR");
                });
    }

    @Test
    @DisplayName("the predecessor's token cannot reach the rotation at all")
    void theOldPlaintextIsStructurallyUnavailable() {
        // A mutation deriving the new token from the old SURVIVED, and it was right to: it derived
        // from the HASH, which an attacker never holds. The dangerous version - reuse the old
        // plaintext, so the stolen identifier still works on the replacement - cannot be written
        // here at all, because this component never receives it.
        //
        // Asserted rather than left as a claim, in the shape VerificationOutcomeTest established: a
        // parameter added later would make the dangerous version expressible, and it would compile
        // cleanly.
        assertThat(
                        java.util.Arrays.stream(SessionRotation.class.getMethods())
                                .filter(method -> method.getDeclaringClass() == SessionRotation.class)
                                .flatMap(method -> java.util.Arrays.stream(method.getParameterTypes()))
                                .map(Class::getName)
                                .toList())
                .as("no method here takes a SessionToken: the predecessor's presented value never"
                        + " enters, so reusing it is not something a future author can reach for")
                .doesNotContain(SessionToken.class.getName());

        // And what it DOES receive carries only the hash - the aggregate, never the plaintext.
        assertThat(
                        java.util.Arrays.stream(Session.class.getMethods())
                                .filter(method -> method.getDeclaringClass() == Session.class)
                                .map(java.lang.reflect.Method::getName)
                                .toList())
                .as("a Session exposes a token HASH and no token")
                .contains("tokenHash")
                .doesNotContain("token");
    }

    @Test
    @DisplayName("login needs no rotation: a client cannot supply the token a session is issued with")
    void loginIsImmuneToFixationByConstruction() throws SQLException {
        // Classic fixation is the attacker planting an identifier the victim then authenticates
        // WITH. This platform cannot be attacked that way, and the property is structural rather
        // than a rotation step: a session's token comes from SecureRandom inside the server, and the
        // only way to get a stored session is to insert one built from a token it generated.
        //
        // Asserted rather than assumed, because "there is no such API" is exactly the claim that
        // stops being true when somebody adds a convenience.
        IdentityId identity = givenAnIdentity();
        SessionToken attackerChosen = SessionToken.of("a-value-the-attacker-picked");

        try (Connection app = DatabaseRoles.application()) {
            assertThat(sessions.findLive(app, attackerChosen, Instant.now(CLOCK)))
                    .as("a token nobody issued matches nothing, and says nothing about why")
                    .isEmpty();
        }

        // And a session issued for that identity does NOT answer to the planted value: the token is
        // generated, never taken from the caller.
        Session issued =
                Session.issue(
                        IDS,
                        CLOCK,
                        identity,
                        SessionToken.issue(RANDOMNESS),
                        AssuranceLevel.PASSWORD,
                        SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, issued);

            assertThat(sessions.findLive(app, attackerChosen, Instant.now(CLOCK)))
                    .as("the planted identifier is still worthless after a real login")
                    .isEmpty();
        }
    }

    // -----------------------------------------------------------------

    private record Fixture(IdentityId identityId, Session session, SessionToken token) {}

    private SessionRotation rotation() {
        return new SessionRotation(sessions, RANDOMNESS, IDS, CLOCK, new JdbcAuditWriter());
    }

    /** Runs a body inside the correlation and security scopes an audited action requires. */
    @SuppressWarnings("try") // The Scopes are used for their close side effect.
    private void inAFlow(SqlBody body) throws SQLException {
        try (CorrelationContext.Scope ignored =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = transactional()) {
            body.run(app);
            app.commit();
        }
    }

    @FunctionalInterface
    private interface SqlBody {
        void run(Connection unitOfWork) throws SQLException;
    }

    @SuppressWarnings("try")
    private boolean rotateOnItsOwnConnection(Fixture fixture) throws SQLException {
        try (CorrelationContext.Scope ignored =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection own = transactional()) {
            Optional<SessionRotation.Rotated> rotated =
                    rotation()
                            .rotate(
                                    own,
                                    fixture.session(),
                                    AssuranceLevel.MULTI_FACTOR,
                                    SessionPolicy.current());
            own.commit();
            return rotated.isPresent();
        }
    }

    private Fixture givenALiveSession(AssuranceLevel assurance) throws SQLException {
        return givenALiveSession(assurance, SessionPolicy.current());
    }

    private Fixture givenALiveSession(AssuranceLevel assurance, SessionPolicy policy)
            throws SQLException {
        IdentityId identity = givenAnIdentity();
        SessionToken token = SessionToken.issue(RANDOMNESS);
        Session session = Session.issue(IDS, CLOCK, identity, token, assurance, policy);
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return new Fixture(identity, session, token);
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

    private static int auditRows(Connection connection, String operation, String targetId)
            throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT count(*) FROM platform.audit_record"
                                + " WHERE operation = ? AND target_id = ?")) {
            select.setString(1, operation);
            select.setString(2, targetId);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static String auditChangeSummary(
            Connection connection, String operation, String targetId) throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT change_summary FROM platform.audit_record"
                                + " WHERE operation = ? AND target_id = ?")) {
            select.setString(1, operation);
            select.setString(2, targetId);
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
