package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.DeviceDescription;
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
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * One identity cannot see or end another's sessions (`P1-TSK-016`, ADR-0031).
 *
 * <h2>This is the task's acceptance criterion, and it is asserted where the check lives</h2>
 *
 * <p>ADR-0031 requires the ownership check in the <strong>domain, against authoritative state</strong>
 * — never at the boundary from a request parameter, because trusting an identifier out of the
 * request <em>is</em> the defect. So these assertions go through the store and
 * {@link SessionRevocation}, not through HTTP: an HTTP-level test would pass just as happily
 * against an implementation that checked ownership in a controller, which is the arrangement the
 * ADR forbids.
 *
 * <h2>What this found in existing code</h2>
 *
 * <p>{@code SessionRevocation.revoke} took an {@code owner} and used it <strong>only in the audit
 * change summary</strong> — the statement beneath was {@code WHERE id = ? AND status = 'ACTIVE'},
 * so any caller could end any session by identifier. That is worse than an absent parameter: the
 * signature reads as though ownership is enforced, and the audit record then asserts an owner
 * nobody verified. {@code P1-TSK-014} wrote it that way because it had no caller; this task is the
 * first.
 */
@Tag("database")
@DisplayName("session ownership (P1-TSK-016)")
class SessionOwnershipDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    // -----------------------------------------------------------------
    // Listing

    @Test
    @DisplayName("a listing contains only the asking identity's own sessions")
    void listingIsScopedToTheOwner() throws SQLException {
        IdentityId mine = givenAnIdentity();
        IdentityId theirs = givenAnIdentity();
        Session ofMine = givenALiveSession(mine);
        Session ofTheirs = givenALiveSession(theirs);

        try (Connection app = DatabaseRoles.application()) {
            List<Session> listed = sessions.findLiveFor(app, mine, Instant.now(CLOCK));

            assertThat(listed).extracting(Session::id).contains(ofMine.id());
            assertThat(listed)
                    .as("a listing that leaked another identity's session would disclose that"
                            + " somebody else is logged in, and from where")
                    .extracting(Session::id)
                    .doesNotContain(ofTheirs.id());
            assertThat(listed)
                    .as("every row must belong to the asking identity, not merely include its own")
                    .allSatisfy(session -> assertThat(session.identityId()).isEqualTo(mine));
        }
    }

    @Test
    @DisplayName("a listing shows live sessions only, exactly as the lookup defines live")
    void listingShowsOnlyLiveSessions() throws SQLException {
        IdentityId mine = givenAnIdentity();
        Session live = givenALiveSession(mine);
        Session revoked = givenALiveSession(mine);

        try (Connection app = DatabaseRoles.application()) {
            sessions.revoke(app, revoked.id(), Instant.now(CLOCK));

            assertThat(sessions.findLiveFor(app, mine, Instant.now(CLOCK)))
                    .as("a listing that disagreed with the lookup about what a session is would"
                            + " show a person a session they cannot use")
                    .extracting(Session::id)
                    .contains(live.id())
                    .doesNotContain(revoked.id());
        }
    }

    // -----------------------------------------------------------------
    // Revocation — the acceptance criterion

    @Test
    @DisplayName("an identity cannot revoke another identity's session")
    void revocationIsRefusedForSomebodyElsesSession() throws SQLException {
        IdentityId mine = givenAnIdentity();
        IdentityId theirs = givenAnIdentity();
        Session ofTheirs = givenALiveSession(theirs);

        try (Connection app = DatabaseRoles.application()) {
            boolean revoked = sessions.revokeOwned(app, ofTheirs.id(), mine, Instant.now(CLOCK)).isPresent();

            assertThat(revoked)
                    .as("this is the defect ADR-0031 names: a legitimate capability used against"
                            + " somebody else's resource")
                    .isFalse();

            // And the assertion that actually matters - the session is still usable by its owner.
            // A test asserting only the return value would pass against an implementation that
            // revoked the row and reported false.
            assertThat(sessions.findLiveFor(app, theirs, Instant.now(CLOCK)))
                    .as("their session must still be live: reporting a refusal while having"
                            + " revoked the row would be the worse half of the same defect")
                    .extracting(Session::id)
                    .contains(ofTheirs.id());
        }
    }

    @Test
    @DisplayName("an identity can revoke its own session")
    void revocationSucceedsForOwnSession() throws SQLException {
        IdentityId mine = givenAnIdentity();
        Session ofMine = givenALiveSession(mine);

        try (Connection app = DatabaseRoles.application()) {
            assertThat(sessions.revokeOwned(app, ofMine.id(), mine, Instant.now(CLOCK)).isPresent())
                    .as("the positive control: without it the ownership check could refuse"
                            + " everything and every negative test above would still pass")
                    .isTrue();
            assertThat(sessions.findLiveFor(app, mine, Instant.now(CLOCK))).isEmpty();
        }
    }

    @Test
    @DisplayName("a session that does not exist is refused exactly as somebody else's is")
    void anAbsentSessionIsRefusedIdentically() throws SQLException {
        IdentityId mine = givenAnIdentity();
        IdentityId theirs = givenAnIdentity();
        Session ofTheirs = givenALiveSession(theirs);

        try (Connection app = DatabaseRoles.application()) {
            boolean absent =
                    sessions.revokeOwned(app, SessionId.next(IDS), mine, Instant.now(CLOCK))
                            .isPresent();
            boolean notMine =
                    sessions.revokeOwned(app, ofTheirs.id(), mine, Instant.now(CLOCK)).isPresent();

            // Asserted as an EQUALITY between the two causes rather than as two assertions against
            // a remembered expectation - the P1-TSK-010 form. A caller able to tell them apart
            // could enumerate which session identifiers belong to somebody.
            assertThat(absent)
                    .as("'no such session' and 'not yours' must be one answer, or the endpoint is"
                            + " an oracle over other people's sessions")
                    .isEqualTo(notMine);
        }
    }

    // -----------------------------------------------------------------
    // The audit record, now that the owner is load-bearing

    @Test
    @DisplayName("a refused revocation writes no audit record")
    void aRefusedRevocationIsNotAudited() throws SQLException {
        IdentityId mine = givenAnIdentity();
        IdentityId theirs = givenAnIdentity();
        Session ofTheirs = givenALiveSession(theirs);

        long before = auditRecordCount();
        inAFlow(
                app -> {
                    boolean revoked = revocation().revoke(app, ofTheirs.id(), mine).isPresent();
                    assertThat(revoked).isFalse();
                });

        // Before the fix this wrote a record naming `mine` as the owner of a session belonging to
        // `theirs` - a trail that is confidently wrong rather than silent, which is the specific
        // harm of a parameter that reads as a check and is not one.
        assertThat(auditRecordCount())
                .as("a revocation that ended nothing must leave no trace claiming it did")
                .isEqualTo(before);
    }

    // -----------------------------------------------------------------
    // Multi-instance

    @Test
    @DisplayName("ten instances revoking one session produce one revocation and one audit record")
    void oneRevocationUnderContention() throws Exception {
        IdentityId mine = givenAnIdentity();
        Session ofMine = givenALiveSession(mine);

        long auditBefore = auditRecordCount();
        int instances = 10;
        java.util.concurrent.CountDownLatch ready =
                new java.util.concurrent.CountDownLatch(instances);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(instances);

        try {
            java.util.List<java.util.concurrent.Future<Boolean>> outcomes = new java.util.ArrayList<>();
            for (int i = 0; i < instances; i++) {
                outcomes.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    go.await();
                                    // Own connection each - the P0-TST-009 convention. A shared one
                                    // would let each racer see the others' uncommitted state, and
                                    // the test would prove the opposite of what it claims.
                                    boolean[] revoked = {false};
                                    inAFlow(
                                            app ->
                                                    revoked[0] =
                                                            revocation()
                                                                    .revoke(app, ofMine.id(), mine)
                                                                    .isPresent());
                                    return revoked[0];
                                }));
            }
            assertThat(ready.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            go.countDown();

            long winners = 0;
            for (var outcome : outcomes) {
                if (outcome.get(60, java.util.concurrent.TimeUnit.SECONDS)) {
                    winners++;
                }
            }

            // The conditional UPDATE's row count is the outcome, so exactly one transition happens
            // and nine racers are told they lost. No read-then-write, so there is nothing to lose.
            assertThat(winners)
                    .as("ten instances revoking one session must produce one revocation")
                    .isEqualTo(1);
            assertThat(auditRecordCount())
                    .as("and exactly one audit record: nine records for revocations that ended"
                            + " nothing would put a decision nobody made into the trail")
                    .isEqualTo(auditBefore + 1);
        } finally {
            pool.shutdownNow();
        }
    }

    // -----------------------------------------------------------------
    // Device

    @Test
    @DisplayName("the device is recorded, carried and returned")
    void theDeviceIsRecorded() throws SQLException {
        IdentityId mine = givenAnIdentity();
        DeviceDescription device =
                DeviceDescription.fromUserAgent("Mozilla/5.0 (Windows NT 10.0) Chrome/141")
                        .orElseThrow();
        SessionToken token = SessionToken.issue(RANDOMNESS);
        Session issued =
                Session.issue(
                        IDS, CLOCK, mine, token, AssuranceLevel.PASSWORD,
                        SessionPolicy.current(), device);

        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, issued);

            assertThat(sessions.findLiveFor(app, mine, Instant.now(CLOCK)))
                    .singleElement()
                    .satisfies(
                            session ->
                                    assertThat(session.device())
                                            .contains("Mozilla/5.0 (Windows NT 10.0) Chrome/141"));
        }
    }

    @Test
    @DisplayName("the database refuses a device carrying control characters")
    void theDatabaseRefusesAControlCharacterInADevice() throws SQLException {
        IdentityId mine = givenAnIdentity();
        Session issued = givenALiveSession(mine);

        // Driven as raw SQL on purpose: DeviceDescription already refuses this, so a test going
        // through the domain would prove the domain rule twice and the CHECK constraint not at all.
        // The constraint is what protects the column from a migration, an operator, or code nobody
        // has written yet - DEFINITION_OF_DONE.md 1.3.
        try (Connection app = DatabaseRoles.application()) {
            assertThatUpdateFails(
                    app,
                    "UPDATE identity.session SET device = ? WHERE id = ?",
                    "Chrome\nX-Forged-Log-Line: yes",
                    issued.id().value());
        }
    }

    @Test
    @DisplayName("the database refuses a device beyond the bound")
    void theDatabaseRefusesAnOversizedDevice() throws SQLException {
        IdentityId mine = givenAnIdentity();
        Session issued = givenALiveSession(mine);

        try (Connection app = DatabaseRoles.application()) {
            assertThatUpdateFails(
                    app,
                    "UPDATE identity.session SET device = ? WHERE id = ?",
                    "u".repeat(DeviceDescription.MAX_LENGTH + 1),
                    issued.id().value());
        }
    }

    // -----------------------------------------------------------------

    private SessionRevocation revocation() {
        return new SessionRevocation(sessions, IDS, CLOCK, new JdbcAuditWriter());
    }

    private interface Work {
        void run(Connection app) throws SQLException;
    }

    /** A correlation scope and an actor, as an authenticated request would have. */
    private static void inAFlow(Work work) throws SQLException {
        // Explicit close rather than try-with-resources: neither scope is referenced in the body,
        // which -Xlint:try reports and -Werror then fails the build for.
        CorrelationContext.Scope correlation =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
        SecurityContext.Scope actor = SecurityContext.enterSystem();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            work.run(app);
            app.commit();
        } finally {
            actor.close();
            correlation.close();
        }
    }

    private Session givenALiveSession(IdentityId identityId) throws SQLException {
        Session session =
                Session.issue(
                        IDS,
                        CLOCK,
                        identityId,
                        SessionToken.issue(RANDOMNESS),
                        AssuranceLevel.PASSWORD,
                        SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return session;
    }

    private static long auditRecordCount() throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count =
                        app.prepareStatement("SELECT count(*) FROM platform.audit_record");
                var rows = count.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static void assertThatUpdateFails(
            Connection connection, String sql, Object... arguments) {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> {
                            try (PreparedStatement update = connection.prepareStatement(sql)) {
                                for (int i = 0; i < arguments.length; i++) {
                                    update.setObject(i + 1, arguments[i]);
                                }
                                update.executeUpdate();
                            }
                        })
                .isInstanceOf(SQLException.class);
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
