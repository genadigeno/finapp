package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.Session;
import com.finapp.identity.SessionIssue;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionRevocation;
import com.finapp.identity.SessionRotation;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A session's life is the same on every instance, whatever each one's clock says (`X-TSK-007`).
 *
 * <h2>The defect</h2>
 *
 * <p>ADR-0014 makes the database supply both sides of any comparison of time across instances. The
 * session store did neither: the issuing instance stamped the bounds from its own clock, and every
 * lookup judged them against the asking instance's. With the writer {@code s_w} and the judge
 * {@code s_j} away from true time, a session lived {@code policy + s_w - s_j}. A fast issuer's
 * sessions outlived the absolute lifetime, a slow issuer's were dead on arrival, and a slow judge
 * honoured expired sessions late. Every existing test passed, because each ran one JVM with one
 * clock - the {@code P0-TSK-016} lesson, met again.
 *
 * <h2>How these tests make a clock matter</h2>
 *
 * <p>The {@code P0-TST-009} convention: each simulated instance has its own connection and its own
 * clock, skewed an hour against the <strong>server's</strong> {@code now()} rather than a fixture
 * constant, and every test first asserts that its skew is real and points the dangerous way. Only
 * the components that still hold a clock - issue, rotation, revocation - are given a skewed one;
 * the lookup has none to give ({@code SessionTimeIsTheDatabasesTest}), and its half is
 * {@code SessionClockSkewEndpointDatabaseTest}'s, over HTTP.
 *
 * <p>Each assertion about a bound is made <em>within one row</em> - a bound minus
 * {@code live_from} - so it is exact, and immune to the local container's clock stepping backwards
 * between two statements ({@code CURRENT_STATE.md} §Local Environment Prerequisites).
 */
@Tag("database")
@DisplayName("session time across skewed instances (X-TSK-007)")
class SessionClockSkewDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    private static final Duration AN_HOUR = Duration.ofHours(1);
    private static final Duration BEYOND_DOUBT = Duration.ofMinutes(59);

    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    // -----------------------------------------------------------------
    // Issue

    @Test
    @DisplayName("an issuer an hour fast cannot lengthen the session it issues")
    void aFastIssuerCannotLengthenASession() throws Exception {
        IdentityId identity = givenAnIdentity();

        try (SimulatedInstance fast = SimulatedInstance.skewedBy(AN_HOUR)) {
            assertThat(Duration.between(SimulatedInstance.serverNow(), fast.clock().instant()))
                    .as("precondition: the issuer is really an hour FAST - the direction that used"
                            + " to stretch the absolute lifetime by the skew")
                    .isGreaterThan(BEYOND_DOUBT);

            SessionIssue.Issued issued = issuer(fast.clock()).issue(fast.connection(), identity, null);
            fast.commit();

            Row row = rowOf(issued.session());
            assertThat(Duration.between(row.liveFrom(), row.absolute()))
                    .as("the absolute lifetime is the policy's exactly, on the database's clock -"
                            + " it used to be the policy plus the issuer's skew")
                    .isEqualTo(SessionPolicy.current().absoluteLifetime());
            assertThat(Duration.between(row.liveFrom(), row.idle()))
                    .isEqualTo(SessionPolicy.current().idleTimeout());
            assertThat(issued.session().idleExpiresAt())
                    .as("and the expiry a client is told is the stored one, not the issuer's guess")
                    .isEqualTo(row.idle());
            assertThat(issued.session().absoluteExpiresAt()).isEqualTo(row.absolute());
            assertThat(Duration.between(row.liveFrom(), row.issuedAt()))
                    .as("issued_at is the issuer's own reading: business time, an hour ahead, and"
                            + " compared with nothing")
                    .isGreaterThan(BEYOND_DOUBT);
        }
    }

    @Test
    @DisplayName("a session issued by an instance an hour slow is live on arrival, everywhere")
    void aSlowIssuersSessionIsLiveOnArrival() throws Exception {
        IdentityId identity = givenAnIdentity();

        try (SimulatedInstance slow = SimulatedInstance.skewedBy(AN_HOUR.negated());
                SimulatedInstance neighbour = SimulatedInstance.inAgreementWithTheServer()) {
            assertThat(Duration.between(slow.clock().instant(), SimulatedInstance.serverNow()))
                    .as("precondition: the issuer is really an hour SLOW - more than the thirty"
                            + " minute idle timeout, which used to leave its sessions dead on"
                            + " arrival")
                    .isGreaterThan(BEYOND_DOUBT);

            SessionIssue.Issued issued = issuer(slow.clock()).issue(slow.connection(), identity, null);
            slow.commit();

            assertThat(sessions.findLive(neighbour.connection(), issued.token()))
                    .as("another instance finds it live: its bounds are the database's")
                    .isPresent();
            assertThat(sessions.findLive(slow.connection(), issued.token()))
                    .as("and so does the instance that issued it - no instance's clock decides")
                    .isPresent();
        }
    }

    // -----------------------------------------------------------------
    // Rotation

    @Test
    @DisplayName("a rotator an hour fast neither shifts nor resets the absolute bound")
    void aFastRotatorKeepsTheAbsoluteBound() throws Exception {
        rotationOnAnInstanceSkewedBy(AN_HOUR);
    }

    @Test
    @DisplayName("a rotator an hour slow neither shifts nor resets the absolute bound")
    void aSlowRotatorKeepsTheAbsoluteBound() throws Exception {
        rotationOnAnInstanceSkewedBy(AN_HOUR.negated());
    }

    private void rotationOnAnInstanceSkewedBy(Duration skew) throws Exception {
        IdentityId identity = givenAnIdentity();
        Session original;
        try (Connection app = DatabaseRoles.application()) {
            original =
                    sessions.insert(
                            app,
                            Session.issue(
                                    IDS,
                                    CLOCK,
                                    identity,
                                    SessionToken.issue(RANDOMNESS),
                                    AssuranceLevel.PASSWORD,
                                    SessionPolicy.current()));
        }

        try (SimulatedInstance rotator = SimulatedInstance.skewedBy(skew)) {
            assertThat(Duration.between(SimulatedInstance.serverNow(), rotator.clock().instant()).abs())
                    .as("precondition: the rotator's clock really is an hour off the server's")
                    .isGreaterThan(BEYOND_DOUBT);

            SessionRotation rotation =
                    new SessionRotation(
                            sessions, RANDOMNESS, IDS, rotator.clock(), new JdbcAuditWriter());
            SessionRotation.Rotated rotated =
                    inAFlow(
                            () ->
                                    rotation.rotate(
                                                    rotator.connection(),
                                                    original,
                                                    AssuranceLevel.MULTI_FACTOR,
                                                    SessionPolicy.current().idleTimeout())
                                            .orElseThrow());
            rotator.commit();

            Row row = rowOf(rotated.session());
            assertThat(row.absolute())
                    .as("carried verbatim: no clock re-derived it, so no skew moved it")
                    .isEqualTo(original.absoluteExpiresAt());
            assertThat(Duration.between(row.liveFrom(), row.idle()))
                    .as("the fresh idle bound is measured from the database's now(), not the"
                            + " rotator's")
                    .isEqualTo(SessionPolicy.current().idleTimeout());
            assertThat(row.issuedAt())
                    .as("the replacement's issued_at is the rotator's own reading - the one its"
                            + " audit record carries")
                    .isEqualTo(rotator.clock().instant());
        }
    }

    // -----------------------------------------------------------------
    // The lifetime metric

    @Test
    @DisplayName("a lifetime measured across a fast issuer and a slow revoker is the true one")
    void aLifetimeAcrossSkewedInstancesIsTheTrueOne() throws Exception {
        // finapp.identity.session.lifetime is fed by revokeOwned's RETURNING. It was
        // revoked_at - issued_at: the revoker's clock minus the issuer's. Here that is two hours
        // apart the wrong way, so a session revoked a moment after issue measured minus two hours.
        IdentityId identity = givenAnIdentity();

        try (SimulatedInstance fastIssuer = SimulatedInstance.skewedBy(AN_HOUR);
                SimulatedInstance slowRevoker = SimulatedInstance.skewedBy(AN_HOUR.negated())) {
            assertThat(Duration.between(slowRevoker.clock().instant(), fastIssuer.clock().instant()))
                    .as("precondition: the two instances' clocks are two hours apart")
                    .isGreaterThan(Duration.ofMinutes(119));

            SessionIssue.Issued issued =
                    issuer(fastIssuer.clock()).issue(fastIssuer.connection(), identity, null);
            fastIssuer.commit();

            SessionRevocation revocation =
                    new SessionRevocation(sessions, IDS, slowRevoker.clock(), new JdbcAuditWriter());
            OptionalLong lifetime =
                    inAFlow(
                            () ->
                                    revocation.revoke(
                                            slowRevoker.connection(),
                                            issued.session().id(),
                                            identity));
            slowRevoker.commit();

            assertThat(lifetime).as("the session was live, so it was ended").isPresent();
            assertThat(Math.abs(lifetime.getAsLong()))
                    .as("measured on the database's clock the session lived moments - the old"
                            + " subtraction gave -7200 seconds")
                    .isLessThan(60L);

            Row row = rowOf(issued.session());
            assertThat(row.revokedAt())
                    .as("business time from two instances is out of order under skew - the row"
                            + " says it ended before it began - which is why nothing compares them")
                    .isBefore(row.issuedAt());
        }
    }

    // -----------------------------------------------------------------
    // V016

    @Test
    @DisplayName("V016 refuses a row whose bound does not follow live_from")
    void aRowWrittenAlreadyExpiredIsRefused() throws SQLException {
        IdentityId identity = givenAnIdentity();

        try (Connection app = DatabaseRoles.application()) {
            assertThatThrownBy(
                            () ->
                                    insertRaw(
                                            app,
                                            identity,
                                            "now()",
                                            "now() - interval '1 second'",
                                            "now() + interval '12 hours'"))
                    .as("a session already over when written is not a session - V005's rule, now"
                            + " on one clock")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("session_bounds_follow_liveness");
        }
    }

    @Test
    @DisplayName("V016 accepts a fast issuer's business time after the row's own bounds")
    void aFastIssuersBusinessTimeIsNotComparedWithTheBounds() throws SQLException {
        // V005's session_bounds_follow_issue would refuse this row: issued_at, an hour ahead as a
        // fast issuer writes it, lies after the database's thirty-minute idle bound. That check
        // compared two clocks, and V016 dropped it. This is the proof it is gone from the schema
        // and not only from the migration's text.
        IdentityId identity = givenAnIdentity();

        try (Connection app = DatabaseRoles.application()) {
            UUID id =
                    insertRaw(
                            app,
                            identity,
                            "now() + interval '1 hour'",
                            "now() + interval '30 minutes'",
                            "now() + interval '12 hours'");
            assertThat(id).isNotNull();
        }
    }

    @Test
    @DisplayName("an old-version INSERT, naming no live_from, lands during a rolling deploy")
    void anOldVersionInsertStillLands() throws SQLException {
        // The pre-X-TSK-007 statement, verbatim in shape: ten columns and every instant bound from
        // the instance. V016's DEFAULT gives it the database's now(), which is exactly what the new
        // code writes, so old and new instances can run side by side.
        IdentityId identity = givenAnIdentity();
        Instant agreeing = SimulatedInstance.serverNow();

        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                UUID id = IDS.next();
                try (PreparedStatement insert =
                        app.prepareStatement(
                                "INSERT INTO identity.session (id, identity_id, token_hash,"
                                        + " assurance, status, issued_at, idle_expires_at,"
                                        + " absolute_expires_at, device, revoked_at)"
                                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    insert.setObject(1, id);
                    insert.setObject(2, identity.value());
                    insert.setString(3, "old-version-" + id);
                    insert.setString(4, "PASSWORD");
                    insert.setString(5, "ACTIVE");
                    insert.setTimestamp(6, Timestamp.from(agreeing));
                    insert.setTimestamp(7, Timestamp.from(agreeing.plus(Duration.ofMinutes(30))));
                    insert.setTimestamp(8, Timestamp.from(agreeing.plus(Duration.ofHours(12))));
                    insert.setString(9, null);
                    insert.setTimestamp(10, null);
                    insert.executeUpdate();
                }
                try (PreparedStatement read =
                                app.prepareStatement(
                                        "SELECT live_from = now() FROM identity.session"
                                                + " WHERE id = ?")) {
                    read.setObject(1, id);
                    try (ResultSet rows = read.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getBoolean(1))
                                .as("the default is the database's now() - the same transaction"
                                        + " instant the new code writes explicitly")
                                .isTrue();
                    }
                }
            } finally {
                app.rollback();
            }
        }
    }

    // -----------------------------------------------------------------

    private SessionIssue issuer(Clock clock) {
        return new SessionIssue(sessions, SessionPolicy.current(), IDS, clock, RANDOMNESS);
    }

    /** One row's time columns, read in one statement. */
    private record Row(
            Instant issuedAt, Instant liveFrom, Instant idle, Instant absolute, Instant revokedAt) {}

    private static Row rowOf(Session session) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT issued_at, live_from, idle_expires_at,"
                                        + " absolute_expires_at, revoked_at"
                                        + " FROM identity.session WHERE id = ?")) {
            select.setObject(1, session.id().value());
            try (ResultSet rows = select.executeQuery()) {
                assertThat(rows.next()).as("the session row exists").isTrue();
                Timestamp revokedAt = rows.getTimestamp(5);
                return new Row(
                        rows.getTimestamp(1).toInstant(),
                        rows.getTimestamp(2).toInstant(),
                        rows.getTimestamp(3).toInstant(),
                        rows.getTimestamp(4).toInstant(),
                        revokedAt == null ? null : revokedAt.toInstant());
            }
        }
    }

    /** A row written with SQL time expressions, standing in for writers that are not the store. */
    private static UUID insertRaw(
            Connection connection,
            IdentityId identity,
            String issuedAt,
            String idle,
            String absolute)
            throws SQLException {
        UUID id = IDS.next();
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO identity.session (id, identity_id, token_hash, assurance,"
                                + " status, issued_at, idle_expires_at, absolute_expires_at,"
                                + " live_from) VALUES (?, ?, ?, 'PASSWORD', 'ACTIVE', "
                                + issuedAt + ", " + idle + ", " + absolute + ", now())")) {
            insert.setObject(1, id);
            insert.setObject(2, identity.value());
            insert.setString(3, "raw-" + id);
            insert.executeUpdate();
        }
        return id;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws Exception;
    }

    /** Runs work inside the correlation and security scopes an audited action requires. */
    @SuppressWarnings("try") // The Scopes are used for their close side effect.
    private static <T> T inAFlow(Work<T> work) throws Exception {
        try (CorrelationContext.Scope correlation =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            return work.run();
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
