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
 * Session issuance, expiry and lookup (`P1-TSK-013`, ADR-0030, `X-TSK-007`).
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
 *
 * <h2>Time passes in the row, never in an argument</h2>
 *
 * <p>Until {@code X-TSK-007} these tests handed the lookup an instant from the future to make a
 * session look old. Nothing takes an instant any more, because liveness is judged at the database's
 * {@code now()}. So a test ages a session by moving its bounds - and {@code live_from}, which V016's
 * constraint measures them from - back relative to that {@code now()}. That is the established
 * fixture shape here ({@code OutboxRelayTest.backDate}), and it survives the local container's
 * clock stepping backwards, which a real wait would not.
 */
@Tag("database")
@DisplayName("session lifecycle (P1-TSK-013, X-TSK-007)")
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

        try (Connection app = DatabaseRoles.application()) {
            Session issued = sessions.insert(app, draft(identity, token, SessionPolicy.current()));

            Optional<Session> found = sessions.findLive(app, token);

            assertThat(found).isPresent();
            assertThat(found.orElseThrow().id()).isEqualTo(issued.id());
            assertThat(found.orElseThrow().identityId()).isEqualTo(identity);
            assertThat(found.orElseThrow().assurance()).isEqualTo(AssuranceLevel.PASSWORD);
        }
    }

    @Test
    @DisplayName("the idle bound alone expires a session, with the absolute bound still far away")
    void theIdleBoundIsEnforcedOnItsOwn() throws SQLException {
        // The idle bound moves into the past; the absolute one stays hours ahead. If the lookup only
        // checked the absolute bound - the easy half to remember - this session would still be live.
        IdentityId identity = givenAnIdentity();
        SessionToken token = SessionToken.issue(RANDOMNESS);

        try (Connection app = DatabaseRoles.application()) {
            Session issued = sessions.insert(app, draft(identity, token, SessionPolicy.current()));
            execute(
                    app,
                    "UPDATE identity.session SET live_from = now() - interval '2 hours',"
                            + " idle_expires_at = now() - interval '1 second' WHERE id = ?",
                    issued.id().value());

            assertThat(secondsUntil(app, issued, "absolute_expires_at"))
                    .as("precondition: the absolute bound has NOT been reached")
                    .isGreaterThan(3600);

            assertThat(sessions.findLive(app, token))
                    .as("idle expiry alone ends the session")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("the absolute bound alone expires a session, however recently it was used")
    void theAbsoluteBoundIsEnforcedOnItsOwn() throws SQLException {
        // The bound that cannot be extended by using the session, which is what makes it the one
        // that matters against a stolen token. Used a moment before its end, so the last touch
        // clamped the idle bound onto the absolute one - and both have now passed.
        IdentityId identity = givenAnIdentity();
        SessionToken token = SessionToken.issue(RANDOMNESS);
        SessionPolicy policy = SessionPolicy.current();

        try (Connection app = DatabaseRoles.application()) {
            Session issued = sessions.insert(app, draft(identity, token, policy));
            execute(
                    app,
                    "UPDATE identity.session SET live_from = now() - interval '12 hours',"
                            + " idle_expires_at = now() - interval '1 second',"
                            + " absolute_expires_at = now() - interval '1 second' WHERE id = ?",
                    issued.id().value());

            // Touching it does not save it: the touch is conditional on the session being live.
            assertThat(sessions.touch(app, issued.id(), policy))
                    .as("a session past its absolute bound cannot be extended")
                    .isFalse();
            assertThat(sessions.findLive(app, token))
                    .as("and it is gone")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("the boundary instant itself is not live, judged at the database's own now()")
    void theBoundIsExclusiveAtTheDatabasesInstant() throws SQLException {
        // Which side of the boundary counts is the kind of thing nobody writes down and everybody
        // assumes differently. SessionTest pinned it on isLiveAt until X-TSK-007 removed that; it is
        // pinned here instead, where it can be exact: now() is one instant for a whole transaction,
        // so a bound set to now() is compared with exactly itself.
        IdentityId identity = givenAnIdentity();
        SessionToken token = SessionToken.issue(RANDOMNESS);

        try (Connection app = DatabaseRoles.application()) {
            Session issued = sessions.insert(app, draft(identity, token, SessionPolicy.current()));
            app.setAutoCommit(false);
            try {
                execute(
                        app,
                        "UPDATE identity.session SET live_from = now() - interval '1 hour',"
                                + " idle_expires_at = now() + interval '1 hour' WHERE id = ?",
                        issued.id().value());
                assertThat(sessions.findLive(app, token))
                        .as("precondition: an hour inside the bound, this transaction finds it")
                        .isPresent();

                execute(
                        app,
                        "UPDATE identity.session SET idle_expires_at = now() WHERE id = ?",
                        issued.id().value());
                assertThat(sessions.findLive(app, token))
                        .as("expiry is exclusive: at the bound, it is over")
                        .isEmpty();
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("touching extends the idle bound but NEVER past the absolute one")
    void touchingNeverOutlastsTheAbsoluteBound() throws SQLException {
        // Without this, an attacker holding a stolen token and using it steadily would keep the
        // session alive for ever and the absolute lifetime would be advisory. Six hours into a
        // twelve-hour session, an eleven-hour idle timeout would reach five hours past the end.
        IdentityId identity = givenAnIdentity();
        SessionToken token = SessionToken.issue(RANDOMNESS);
        SessionPolicy longIdle = new SessionPolicy(Duration.ofHours(11), Duration.ofHours(12));

        try (Connection app = DatabaseRoles.application()) {
            Session issued = sessions.insert(app, draft(identity, token, longIdle));
            execute(
                    app,
                    "UPDATE identity.session SET live_from = now() - interval '6 hours',"
                            + " idle_expires_at = now() + interval '1 hour',"
                            + " absolute_expires_at = now() + interval '6 hours' WHERE id = ?",
                    issued.id().value());

            assertThat(sessions.touch(app, issued.id(), longIdle)).isTrue();

            Session after = sessions.findLive(app, token).orElseThrow();
            assertThat(after.idleExpiresAt())
                    .as("the idle bound was clamped to the absolute one, not pushed past it")
                    .isEqualTo(after.absoluteExpiresAt());
        }
    }

    @Test
    @DisplayName("a touch never pulls the idle bound back")
    void aTouchNeverMovesTheBoundBack() throws SQLException {
        // X-TSK-007's GREATEST. now() is a transaction's start, so a touch whose transaction began
        // first can reach the row after a later one has already extended it. Stated large here so
        // it is observable: the bound already reaches an hour ahead, as if extended from an
        // instant thirty minutes after this touch's own, and this touch would write now() plus
        // thirty minutes. Without the floor it would take that half hour away.
        IdentityId identity = givenAnIdentity();
        SessionToken token = SessionToken.issue(RANDOMNESS);
        SessionPolicy policy = new SessionPolicy(Duration.ofMinutes(30), Duration.ofHours(12));

        try (Connection app = DatabaseRoles.application()) {
            Session issued = sessions.insert(app, draft(identity, token, policy));
            execute(
                    app,
                    "UPDATE identity.session SET idle_expires_at = now() + interval '1 hour'"
                            + " WHERE id = ?",
                    issued.id().value());
            Instant extended = sessions.findLive(app, token).orElseThrow().idleExpiresAt();

            assertThat(sessions.touch(app, issued.id(), policy))
                    .as("the session is live, so the touch matches it")
                    .isTrue();

            assertThat(sessions.findLive(app, token).orElseThrow().idleExpiresAt())
                    .as("an earlier-judged touch must not undo a later one's extension")
                    .isEqualTo(extended);
        }
    }

    @Test
    @DisplayName("a generous new policy cannot revive a session that died under the old one")
    void aPolicyChangeCannotReviveADeadSession() throws SQLException {
        // INV-HIST-04's reasoning for a session that has ENDED: its bounds were written at issue,
        // and a later, more generous policy cannot bring it back. This used to be named for a
        // wider claim - that no policy change extends any session already issued - which the touch
        // does not keep for a LIVE session's idle bound: it extends by whatever policy the touching
        // instance holds. That half is recorded against X-TSK-008 rather than claimed here.
        IdentityId identity = givenAnIdentity();
        SessionToken token = SessionToken.issue(RANDOMNESS);
        SessionPolicy strict = new SessionPolicy(Duration.ofSeconds(1), Duration.ofSeconds(2));

        try (Connection app = DatabaseRoles.application()) {
            Session issued = sessions.insert(app, draft(identity, token, strict));
            execute(
                    app,
                    "UPDATE identity.session SET live_from = now() - interval '3 seconds',"
                            + " idle_expires_at = now() - interval '2 seconds',"
                            + " absolute_expires_at = now() - interval '1 second' WHERE id = ?",
                    issued.id().value());

            // A far more generous policy arrives. It must change nothing about this session.
            SessionPolicy generous = new SessionPolicy(Duration.ofDays(7), Duration.ofDays(30));
            assertThat(sessions.touch(app, issued.id(), generous))
                    .as("a generous new policy cannot revive a session issued under a strict one")
                    .isFalse();
            assertThat(sessions.findLive(app, token))
                    .as("the session died under the policy it was issued under")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("expired, revoked and never-existed are indistinguishable")
    void everyUnusableSessionLooksTheSame() throws SQLException {
        IdentityId identity = givenAnIdentity();

        SessionToken expiredToken = SessionToken.issue(RANDOMNESS);
        SessionToken revokedToken = SessionToken.issue(RANDOMNESS);
        SessionToken neverExisted = SessionToken.issue(RANDOMNESS);

        try (Connection app = DatabaseRoles.application()) {
            Session expiring =
                    sessions.insert(app, draft(identity, expiredToken, SessionPolicy.current()));
            Session revoking =
                    sessions.insert(app, draft(identity, revokedToken, SessionPolicy.current()));
            execute(
                    app,
                    "UPDATE identity.session SET live_from = now() - interval '13 hours',"
                            + " idle_expires_at = now() - interval '1 hour',"
                            + " absolute_expires_at = now() - interval '1 hour' WHERE id = ?",
                    expiring.id().value());
            revoke(app, revoking);

            // Three different situations, one answer. A caller cannot tell which it hit, so nothing
            // downstream can ever report which it hit.
            assertThat(sessions.findLive(app, expiredToken)).as("expired").isEmpty();
            assertThat(sessions.findLive(app, revokedToken)).as("revoked").isEmpty();
            assertThat(sessions.findLive(app, neverExisted)).as("never existed").isEmpty();
        }
    }

    @Test
    @DisplayName("the token appears in no column of the stored row")
    void theTokenIsNeverStored() throws SQLException {
        // The P1-TSK-007 idiom: the column list comes from information_schema rather than from a
        // list somebody wrote, so a column added later is inspected without anyone remembering.
        IdentityId identity = givenAnIdentity();
        SessionToken token = SessionToken.issue(RANDOMNESS);
        String presented = token.presentedValue().expose();

        try (Connection app = DatabaseRoles.application()) {
            Session issued = sessions.insert(app, draft(identity, token, SessionPolicy.current()));

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
        // with a stale one - and since X-TSK-007 none can pull it back either, whichever order
        // their transactions reach the row in.
        IdentityId identity = givenAnIdentity();
        SessionToken token = SessionToken.issue(RANDOMNESS);
        SessionPolicy policy = new SessionPolicy(Duration.ofMinutes(30), Duration.ofHours(12));
        Session issued;
        try (Connection app = DatabaseRoles.application()) {
            issued = sessions.insert(app, draft(identity, token, policy));
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
                                        return sessions.touch(own, issued.id(), policy);
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
            Session after = sessions.findLive(app, token).orElseThrow();
            // Not "isAfter": the local container's clock can step backwards between the insert and
            // the racers, and then the floor rightly keeps the bound where the insert put it.
            assertThat(after.idleExpiresAt())
                    .as("the session is still live and its bound never moved back")
                    .isAfterOrEqualTo(issued.idleExpiresAt());
            assertThat(after.idleExpiresAt())
                    .as("and never past the absolute bound, however many racers extended it")
                    .isBeforeOrEqualTo(after.absoluteExpiresAt());
        }
    }

    // -----------------------------------------------------------------

    private static Session.Draft draft(IdentityId identity, SessionToken token, SessionPolicy policy) {
        return Session.issue(IDS, CLOCK, identity, token, AssuranceLevel.PASSWORD, policy);
    }

    /** Whole seconds from the database's now() to one of the session's bounds. */
    private static long secondsUntil(Connection connection, Session session, String bound)
            throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT extract(epoch FROM " + bound + " - now())::bigint"
                                + " FROM identity.session WHERE id = ?")) {
            select.setObject(1, session.id().value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
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
