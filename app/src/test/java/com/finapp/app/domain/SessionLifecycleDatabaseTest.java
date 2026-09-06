package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
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
 * Session issuance, expiry and lookup (`P1-TSK-013`, ADR-0030).
 *
 * <h2>The two properties this suite exists for</h2>
 *
 * <p><strong>Both bounds are enforced, independently.</strong> Idle alone lets an active attacker
 * hold a session for ever; absolute alone logs a working customer out mid-task. A suite that tested
 * them together would pass against an implementation that checks only one.
 *
 * <p><strong>Expired, revoked and never-existed are indistinguishable.</strong> Telling them apart
 * tells somebody holding a stolen identifier whether it was ever real, and which of the two
 * happened — {@code INV-IDN-07}'s reasoning applied to a session.
 */
@Tag("database")
@DisplayName("session lifecycle (P1-TSK-013)")
class SessionLifecycleDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    @Test
    @DisplayName("an issued session is found by its token")
    void anIssuedSessionIsFound() throws SQLException {
        IdentityId identity = givenAnIdentity();
        SessionToken token = SessionToken.issue(RANDOMNESS);
        Session issued = issue(identity, token, SessionPolicy.current());

        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, issued);

            Optional<Session> found = sessions.findLive(app, token, Instant.now(CLOCK));

            assertThat(found).isPresent();
            assertThat(found.orElseThrow().id()).isEqualTo(issued.id());
            assertThat(found.orElseThrow().identityId()).isEqualTo(identity);
            assertThat(found.orElseThrow().assurance()).isEqualTo(AssuranceLevel.PASSWORD);
        }
    }

    @Test
    @DisplayName("the idle bound alone expires a session, with the absolute bound still far away")
    void theIdleBoundIsEnforcedOnItsOwn() throws SQLException {
        // A short idle bound inside a long absolute one. If the lookup only checked the absolute
        // bound - the easy half to remember - this session would still be live.
        IdentityId identity = givenAnIdentity();
        SessionToken token = SessionToken.issue(RANDOMNESS);
        SessionPolicy shortIdle =
                new SessionPolicy(Duration.ofSeconds(1), Duration.ofHours(12));
        Session issued = issue(identity, token, shortIdle);

        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, issued);

            Instant afterIdle = issued.idleExpiresAt().plusSeconds(1);
            assertThat(afterIdle)
                    .as("precondition: the absolute bound has NOT been reached")
                    .isBefore(issued.absoluteExpiresAt());

            assertThat(sessions.findLive(app, token, afterIdle))
                    .as("idle expiry alone ends the session")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("the absolute bound alone expires a session, however recently it was used")
    void theAbsoluteBoundIsEnforcedOnItsOwn() throws SQLException {
        // The bound that cannot be extended by using the session, which is what makes it the one
        // that matters against a stolen token.
        IdentityId identity = givenAnIdentity();
        SessionToken token = SessionToken.issue(RANDOMNESS);
        SessionPolicy shortAbsolute =
                new SessionPolicy(Duration.ofSeconds(2), Duration.ofSeconds(2));
        Session issued = issue(identity, token, shortAbsolute);

        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, issued);
            Instant afterAbsolute = issued.absoluteExpiresAt().plusSeconds(1);

            // Touching it does not save it: the touch is conditional on the session being live.
            assertThat(sessions.touch(app, issued.id(), afterAbsolute, shortAbsolute))
                    .as("a session past its absolute bound cannot be extended")
                    .isFalse();
            assertThat(sessions.findLive(app, token, afterAbsolute))
                    .as("and it is gone")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("touching extends the idle bound but NEVER past the absolute one")
    void touchingNeverOutlastsTheAbsoluteBound() throws SQLException {
        // Without this, an attacker holding a stolen token and using it steadily would keep the
        // session alive for ever and the absolute lifetime would be advisory.
        IdentityId identity = givenAnIdentity();
        SessionToken token = SessionToken.issue(RANDOMNESS);
        SessionPolicy longIdle = new SessionPolicy(Duration.ofHours(11), Duration.ofHours(12));
        Session issued = issue(identity, token, longIdle);

        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, issued);

            // Use it late enough that a naive extension would push the idle bound past the absolute.
            Instant late = issued.issuedAt().plus(Duration.ofHours(6));
            assertThat(sessions.touch(app, issued.id(), late, longIdle)).isTrue();

            Session after = sessions.findLive(app, token, late).orElseThrow();
            assertThat(after.idleExpiresAt())
                    .as("the idle bound was clamped to the absolute one, not pushed past it")
                    .isEqualTo(after.absoluteExpiresAt());
        }
    }

    @Test
    @DisplayName("a policy change does not extend a session already issued")
    void aPolicyChangeDoesNotReachBackwards() throws SQLException {
        // INV-HIST-04's reasoning. The bounds are copied onto the row at issue and never re-read,
        // so a later, more generous policy cannot lengthen a session that already exists.
        IdentityId identity = givenAnIdentity();
        SessionToken token = SessionToken.issue(RANDOMNESS);
        SessionPolicy strict = new SessionPolicy(Duration.ofSeconds(1), Duration.ofSeconds(2));
        Session issued = issue(identity, token, strict);

        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, issued);
            Instant afterStrictBounds = issued.absoluteExpiresAt().plusSeconds(1);

            // A far more generous policy arrives. It must change nothing about this session.
            SessionPolicy generous = new SessionPolicy(Duration.ofDays(7), Duration.ofDays(30));
            assertThat(sessions.touch(app, issued.id(), afterStrictBounds, generous))
                    .as("a generous new policy cannot revive a session issued under a strict one")
                    .isFalse();
            assertThat(sessions.findLive(app, token, afterStrictBounds))
                    .as("the session died under the policy it was issued under")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("expired, revoked and never-existed are indistinguishable")
    void everyUnusableSessionLooksTheSame() throws SQLException {
        IdentityId identity = givenAnIdentity();

        SessionToken expiredToken = SessionToken.issue(RANDOMNESS);
        SessionPolicy brief = new SessionPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1));
        Session expiring = issue(identity, expiredToken, brief);

        SessionToken revokedToken = SessionToken.issue(RANDOMNESS);
        Session revoking = issue(identity, revokedToken, SessionPolicy.current());

        SessionToken neverExisted = SessionToken.issue(RANDOMNESS);

        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, expiring);
            sessions.insert(app, revoking);
            revoke(app, revoking);

            Instant after = expiring.absoluteExpiresAt().plusSeconds(1);

            // Three different situations, one answer. A caller cannot tell which it hit, so nothing
            // downstream can ever report which it hit.
            assertThat(sessions.findLive(app, expiredToken, after)).as("expired").isEmpty();
            assertThat(sessions.findLive(app, revokedToken, after)).as("revoked").isEmpty();
            assertThat(sessions.findLive(app, neverExisted, after)).as("never existed").isEmpty();
        }
    }

    @Test
    @DisplayName("the token appears in no column of the stored row")
    void theTokenIsNeverStored() throws SQLException {
        // The P1-TSK-007 idiom: the column list comes from information_schema rather than from a
        // list somebody wrote, so a column added later is inspected without anyone remembering.
        IdentityId identity = givenAnIdentity();
        SessionToken token = SessionToken.issue(RANDOMNESS);
        Session issued = issue(identity, token, SessionPolicy.current());
        String presented = token.presentedValue().expose();

        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, issued);

            String wholeRow = wholeRowAsText(app, issued.id().value());

            assertThat(wholeRow).as("the row is really there to inspect").isNotBlank();
            assertThat(wholeRow)
                    .as("a database leak with plaintext tokens hands an attacker every live"
                            + " session, with no work at all")
                    .doesNotContain(presented);
            assertThat(wholeRow)
                    .as("precondition: what IS stored is the hash, so the search had something to"
                            + " find and did not merely search an empty row")
                    .contains(token.hash().expose());
        }
    }

    @Test
    @DisplayName("ten instances touching one session lose no update")
    void touchingIsSafeUnderContention() throws Exception {
        // Each racer gets its own connection - the P0-TST-009 convention. The conditional UPDATE is
        // the coordination: no racer reads then writes, so none can overwrite another's extension
        // with a stale one.
        IdentityId identity = givenAnIdentity();
        SessionToken token = SessionToken.issue(RANDOMNESS);
        SessionPolicy policy = new SessionPolicy(Duration.ofMinutes(30), Duration.ofHours(12));
        Session issued = issue(identity, token, policy);
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, issued);
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
                                        return sessions.touch(
                                                own, issued.id(), Instant.now(CLOCK), policy);
                                    }
                                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<Boolean> result : results) {
                assertThat(result.get(60, TimeUnit.SECONDS))
                        .as("every touch of a live session succeeds; none is lost or refused")
                        .isTrue();
            }
        } finally {
            pool.shutdownNow();
        }

        try (Connection app = DatabaseRoles.application()) {
            Session after = sessions.findLive(app, token, Instant.now(CLOCK)).orElseThrow();
            assertThat(after.idleExpiresAt())
                    .as("the session is still live and its bound moved forward")
                    .isAfter(issued.issuedAt());
            assertThat(after.idleExpiresAt())
                    .as("and never past the absolute bound, however many racers extended it")
                    .isBeforeOrEqualTo(after.absoluteExpiresAt());
        }
    }

    // -----------------------------------------------------------------

    private static Session issue(IdentityId identity, SessionToken token, SessionPolicy policy) {
        return Session.issue(IDS, CLOCK, identity, token, AssuranceLevel.PASSWORD, policy);
    }

    private static void revoke(Connection connection, Session session) throws SQLException {
        // P1-TSK-014 owns revocation; this writes the row directly so the LOOKUP's treatment of a
        // revoked session can be asserted now. The transition itself is that task's subject.
        execute(
                connection,
                "UPDATE identity.session SET status = 'REVOKED', revoked_at = now() WHERE id = ?",
                session.id().value());
    }

    private static String wholeRowAsText(Connection connection, UUID sessionId) throws SQLException {
        StringBuilder columns = new StringBuilder();
        try (PreparedStatement names =
                        connection.prepareStatement(
                                "SELECT column_name FROM information_schema.columns"
                                        + " WHERE table_schema = 'identity' AND table_name = 'session'"
                                        + " ORDER BY ordinal_position");
                ResultSet rows = names.executeQuery()) {
            while (rows.next()) {
                if (columns.length() > 0) {
                    columns.append(" || ' ' || ");
                }
                columns.append("coalesce(").append(rows.getString(1)).append("::text, '')");
            }
        }
        if (columns.length() == 0) {
            throw new IllegalStateException("No columns found; the guard would be vacuous");
        }
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT " + columns + " FROM identity.session WHERE id = ?")) {
            select.setObject(1, sessionId);
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
