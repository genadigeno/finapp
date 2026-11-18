package com.finapp.app.administration;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcIdentityStore;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.Session;
import com.finapp.identity.SessionAdministration;
import com.finapp.identity.SessionId;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionToken;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Another identity's sessions, administered on PostgreSQL (`P17-TSK-015`): the list is the store's own live predicate; one session
 * ends only if it is the identity's and only once, however many instances race for it; "all" ends the rest; the administrator's
 * own identity is refused; and the trail holds each act that ended something, with its reason, and no guessed identifier.
 */
@Tag("database")
@DisplayName("another identity's sessions, administered, on PostgreSQL (P17-TSK-015)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class SessionAdministrationDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final String REASON = "the holder reported the laptop lost";

    private final JdbcSessionStore sessions = new JdbcSessionStore();
    private final SessionAdministration administration =
            new SessionAdministration(new JdbcIdentityStore(), sessions, new JdbcAuditWriter(), IDS, CLOCK);

    private IdentityId admin;
    private IdentityId subject;
    private IdentityId other;

    @BeforeEach
    void seed() throws SQLException {
        admin = identity();
        subject = identity();
        other = identity();
    }

    @Test
    @DisplayName("the list is the identity's live sessions, newest first; an ended one is not in it; recorded with the count")
    void theListIsTheLiveSessions() throws SQLException {
        Session first = session(subject);
        Session second = session(subject);
        Session ended = session(subject);
        session(other);
        try (Connection app = DatabaseRoles.application()) {
            sessions.revoke(app, ended.id(), CLOCK.instant());
        }
        String flow = flow("list");

        List<Session> live = as(admin, flow, uow -> administration.liveSessionsOf(uow, subject)).orElseThrow();

        assertThat(live).extracting(Session::id).containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(audits(flow)).containsExactly("identity.SessionsRead|identity.Identity|" + subject.value() + "|live=2|-");
        assertThat(this.<Optional<List<Session>>>as(admin, flow("unknown"), uow -> administration.liveSessionsOf(uow, IdentityId.next(IDS)))).isEmpty();
    }

    @Test
    @DisplayName("one session ends only if it is the identity's: another's is untouched and unrecorded, an ended one answers done"
            + " again with no second record, an unreadable one ends nothing")
    void oneSessionEndsOnlyIfItIsTheIdentitys() throws SQLException {
        Session mine = session(subject);
        Session theirs = session(other);
        String flow = flow("one");

        assertThat(this.<SessionAdministration.Outcome>as(admin, flow, uow -> administration.revoke(uow, subject, Optional.of(theirs.id()), admin, REASON)))
                .isEqualTo(SessionAdministration.Outcome.DONE);
        assertThat(live(theirs.id())).as("another identity's session, named under this one, ends nothing").isTrue();
        assertThat(this.<SessionAdministration.Outcome>as(admin, flow, uow -> administration.revoke(uow, subject, Optional.of(mine.id()), admin, REASON)))
                .isEqualTo(SessionAdministration.Outcome.DONE);
        assertThat(this.<SessionAdministration.Outcome>as(admin, flow, uow -> administration.revoke(uow, subject, Optional.of(mine.id()), admin, REASON)))
                .isEqualTo(SessionAdministration.Outcome.DONE);
        assertThat(this.<SessionAdministration.Outcome>as(admin, flow, uow -> administration.revoke(uow, subject, Optional.empty(), admin, REASON)))
                .isEqualTo(SessionAdministration.Outcome.DONE);

        assertThat(live(mine.id())).isFalse();
        assertThat(audits(flow)).as("one record: the one session that ended, with its reason")
                .singleElement().asString()
                .startsWith("identity.SessionRevokedByAdministrator|identity.Session|" + mine.id().value() + "|identity=" + subject.value())
                .endsWith("|" + REASON);
    }

    @Test
    @DisplayName("all of them end, the identity stays active; recorded with the count, even when it is none")
    void allEndWithoutSuspending() throws SQLException {
        session(subject);
        session(subject);
        Session theirs = session(other);
        String flow = flow("all");

        SessionAdministration.AllRevoked all = as(admin, flow, uow -> administration.revokeAll(uow, subject, admin, REASON));
        SessionAdministration.AllRevoked again = as(admin, flow, uow -> administration.revokeAll(uow, subject, admin, REASON));

        assertThat(all.revoked()).isEqualTo(2);
        assertThat(again.revoked()).isZero();
        assertThat(live(theirs.id())).isTrue();
        assertThat(single("SELECT status FROM identity.identity WHERE id = '" + subject.value() + "'"))
                .as("the person keeps their access: a session ended is not a suspension").isEqualTo("ACTIVE");
        assertThat(audits(flow)).containsExactly(
                "identity.SessionRevokedByAdministrator|identity.Identity|" + subject.value() + "|sessionsRevoked=2|" + REASON,
                "identity.SessionRevokedByAdministrator|identity.Identity|" + subject.value() + "|sessionsRevoked=0|" + REASON);
    }

    @Test
    @DisplayName("the administrator's own identity is refused, and an unknown one is not found; neither ends or records anything")
    void selfAndUnknownAreRefused() throws SQLException {
        Session own = session(admin);
        String flow = flow("refused");

        assertThat(this.<SessionAdministration.Outcome>as(admin, flow, uow -> administration.revoke(uow, admin, Optional.of(own.id()), admin, REASON)))
                .isEqualTo(SessionAdministration.Outcome.SELF);
        assertThat(this.<SessionAdministration.AllRevoked>as(admin, flow, uow -> administration.revokeAll(uow, admin, admin, REASON)).outcome())
                .isEqualTo(SessionAdministration.Outcome.SELF);
        assertThat(this.<SessionAdministration.AllRevoked>as(admin, flow, uow -> administration.revokeAll(uow, IdentityId.next(IDS), admin, REASON)).outcome())
                .isEqualTo(SessionAdministration.Outcome.NOT_FOUND);
        assertThat(live(own.id())).isTrue();
        assertThat(audits(flow)).isEmpty();
    }

    @Test
    @DisplayName("ten instances ending one session at once: it ends once, and one record says so")
    void tenRacersEndOneSessionOnce() throws Exception {
        Session target = session(subject);
        String flow = flow("race");
        ExecutorService instances = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<SessionAdministration.Outcome>> outcomes = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                outcomes.add(instances.submit(() -> {
                    start.await();
                    return as(admin, flow, uow -> administration.revoke(uow, subject, Optional.of(target.id()), admin, REASON));
                }));
            }
            start.countDown();
            for (Future<SessionAdministration.Outcome> outcome : outcomes) {
                assertThat(outcome.get()).isEqualTo(SessionAdministration.Outcome.DONE);
            }
        } finally {
            instances.shutdownNow();
        }

        assertThat(live(target.id())).isFalse();
        assertThat(audits(flow)).as("the conditional revocation is the arbiter: one transition, one record").hasSize(1);
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private <R> R as(IdentityId who, String flow, Function<Connection, R> work) throws SQLException {
        try (SecurityContext.Scope actor = SecurityContext.enter(new Actor(who.value().toString(), ActorType.EMPLOYEE));
                CorrelationContext.Scope correlation = CorrelationContext.enter(Correlation.startingWith(CorrelationId.of(flow)));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            R result = work.apply(app);
            app.commit();
            return result;
        }
    }

    private static String flow(String label) {
        return "p17-015-" + label + "-" + UUID.randomUUID();
    }

    private Session session(IdentityId owner) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        try (Connection app = DatabaseRoles.application()) {
            return sessions.insert(app, Session.issue(IDS, CLOCK, owner,
                    SessionToken.of(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)), AssuranceLevel.PASSWORD,
                    SessionPolicy.current()));
        }
    }

    private boolean live(SessionId session) throws SQLException {
        return "true".equals(single("SELECT (status = 'ACTIVE' AND idle_expires_at > now() AND absolute_expires_at > now())::text"
                + " FROM identity.session WHERE id = '" + session.value() + "'"));
    }

    private static IdentityId identity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement partyRow = app.prepareStatement(
                        "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'Session Desk Person', now())");
                PreparedStatement identityRow = app.prepareStatement(
                        "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                                + " VALUES (?, ?, ?, 'ACTIVE', now(), now())")) {
            partyRow.setObject(1, party);
            partyRow.executeUpdate();
            identityRow.setObject(1, identity);
            identityRow.setObject(2, party);
            identityRow.setString(3, "sa" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
            identityRow.executeUpdate();
        }
        return IdentityId.of(identity);
    }

    private static List<String> audits(String flow) throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap();
                PreparedStatement select = root.prepareStatement("SELECT operation || '|' || target_type || '|' || target_id || '|'"
                        + " || coalesce(change_summary, '') || '|' || coalesce(reason, '-')"
                        + " FROM platform.audit_record WHERE correlation_id = ? ORDER BY occurred_at, audit_id")) {
            select.setString(1, flow);
            try (ResultSet rows = select.executeQuery()) {
                List<String> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(rows.getString(1));
                }
                return found;
            }
        }
    }

    private static String single(String sql) throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap(); PreparedStatement select = root.prepareStatement(sql);
                ResultSet rows = select.executeQuery()) {
            assertThat(rows.next()).as("a row for %s", sql).isTrue();
            return rows.getString(1);
        }
    }
}
