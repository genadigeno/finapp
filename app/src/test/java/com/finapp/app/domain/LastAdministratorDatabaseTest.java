package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityAdministration;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.identity.SuspensionCause;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The last identity that can grant roles, on PostgreSQL (`P15-TSK-032`, item 42 (d) as decided): an administrator's revocation
 * or suspension never takes it, and two racing acts on the last two holders leave exactly one.
 *
 * <h2>A database of its own, and why</h2>
 *
 * <p>"The last" is a count over the whole database: in the tier's shared container every other suite's fixture administrators
 * would be counted, and no race here could reach the last two. So the suite runs in a container of its own
 * ({@code own-container}, {@code ownContainerDatabaseTest}), and each case first takes every live {@code ADMINISTRATOR} holding
 * away ({@link #noAdministratorIsLeft}), so it starts with only the holders it names.
 *
 * <p><strong>Deterministic races</strong>, as {@code IdentityAdministrationDatabaseTest#aGrantAndARevocationOfOneIdentitySerialise}
 * is: the first act is held open after it has acted, the second is seen waiting behind it on a lock, and only then does the
 * first commit. So the second always counts after the first committed - which is what the lock and the fresh count after it
 * are for.
 */
@Tag("database")
@Tag("own-container") // its own JVM and database: "the last" counts the whole database (see the class comment)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the last identity that can grant roles, on PostgreSQL (P15-TSK-032)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class LastAdministratorDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final String REASON = "the incident, OPS-9001";

    @LocalServerPort private int port;

    @Autowired private Authorization authorization;

    @Autowired private IdentityAdministration administration;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    @BeforeEach
    void noAdministratorIsLeft() throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement revoke = app.prepareStatement(
                        "UPDATE identity.role_assignment SET revoked_at = GREATEST(now(), assigned_at), revoked_by = identity_id"
                                + " WHERE revoked_at IS NULL AND role_name = 'ADMINISTRATOR'")) {
            revoke.executeUpdate();
        }
    }

    @Test
    @DisplayName("two administrators suspending each other at once, the last two: one is suspended, the other refused 409")
    void twoAdministratorsSuspendingEachOtherLeaveOne() throws Exception {
        IdentityId first = givenAnAdministrator();
        IdentityId second = givenAnAdministrator();

        Object[] outcomes = race(
                held -> administration.suspend(held, second, first, REASON),
                () -> actingAs(second, () -> {
                    try (Connection app = DatabaseRoles.application()) {
                        app.setAutoCommit(false);
                        IdentityAdministration.Suspension outcome = administration.suspend(app, first, second, REASON);
                        app.commit();
                        return outcome;
                    }
                }),
                first);

        assertThat(outcomes).containsExactly(IdentityAdministration.Suspension.SUSPENDED,
                IdentityAdministration.Suspension.LAST_ROLE_GRANTER);
        assertThat(statusOf(second)).isEqualTo("SUSPENDED");
        assertThat(statusOf(first)).isEqualTo("ACTIVE");
        assertThat(activeAdministrators()).isEqualTo(1);
    }

    @Test
    @DisplayName("a suspension racing a revocation of its own actor's administrator role, in either order: exactly one lands, one"
            + " administrator is left")
    void aSuspensionRacingTheActorsRevocationLeavesOne() throws Exception {
        // The revocation first: SECOND takes FIRST's role while FIRST suspends SECOND.
        IdentityId first = givenAnAdministrator();
        IdentityId second = givenAnAdministrator();
        Object[] revocationFirst = race(
                held -> administration.revokeRole(held, first, RoleName.ADMINISTRATOR, second, REASON),
                () -> actingAs(first, () -> {
                    try (Connection app = DatabaseRoles.application()) {
                        app.setAutoCommit(false);
                        IdentityAdministration.Suspension outcome = administration.suspend(app, second, first, REASON);
                        app.commit();
                        return outcome;
                    }
                }),
                second);
        assertThat(revocationFirst).containsExactly(IdentityAdministration.RoleRevocation.REVOKED,
                IdentityAdministration.Suspension.LAST_ROLE_GRANTER);
        assertThat(statusOf(second)).isEqualTo("ACTIVE");
        assertThat(activeAdministrators()).isEqualTo(1);

        // And the suspension first.
        noAdministratorIsLeft();
        IdentityId third = givenAnAdministrator();
        IdentityId fourth = givenAnAdministrator();
        Object[] suspensionFirst = race(
                held -> administration.suspend(held, fourth, third, REASON),
                () -> actingAs(fourth, () -> {
                    try (Connection app = DatabaseRoles.application()) {
                        app.setAutoCommit(false);
                        IdentityAdministration.RoleRevocation outcome =
                                administration.revokeRole(app, third, RoleName.ADMINISTRATOR, fourth, REASON);
                        app.commit();
                        return outcome;
                    }
                }),
                third);
        assertThat(suspensionFirst).containsExactly(IdentityAdministration.Suspension.SUSPENDED,
                IdentityAdministration.RoleRevocation.LAST_ROLE_GRANTER);
        assertThat(statusOf(fourth)).isEqualTo("SUSPENDED");
        assertThat(activeAdministrators()).isEqualTo(1);
    }

    @Test
    @DisplayName("two administrators revoking each other's role at once, the last two: one revoked, the other refused 409")
    void twoRevocationsOfTheLastTwoLeaveOne() throws Exception {
        IdentityId first = givenAnAdministrator();
        IdentityId second = givenAnAdministrator();

        Object[] outcomes = race(
                held -> administration.revokeRole(held, first, RoleName.ADMINISTRATOR, second, REASON),
                () -> actingAs(first, () -> {
                    try (Connection app = DatabaseRoles.application()) {
                        app.setAutoCommit(false);
                        IdentityAdministration.RoleRevocation outcome =
                                administration.revokeRole(app, second, RoleName.ADMINISTRATOR, first, REASON);
                        app.commit();
                        return outcome;
                    }
                }),
                second);

        assertThat(outcomes).containsExactly(IdentityAdministration.RoleRevocation.REVOKED,
                IdentityAdministration.RoleRevocation.LAST_ROLE_GRANTER);
        assertThat(activeAdministrators()).isEqualTo(1);
    }

    @Test
    @DisplayName("risk's suspension is not bound by the rule: the last administrator, compromised, is suspended")
    void risksSuspensionTakesTheLastAdministrator() throws Exception {
        IdentityId last = givenAnAdministrator();
        IdentityId decider = givenAnIdentity();

        IdentityAdministration.Suspension outcome = actingAs(decider, () -> {
            try (Connection app = DatabaseRoles.application()) {
                app.setAutoCommit(false);
                IdentityAdministration.Suspension suspended =
                        administration.suspend(app, last, decider, REASON, SuspensionCause.RISK);
                app.commit();
                return suspended;
            }
        });

        assertThat(outcome).isEqualTo(IdentityAdministration.Suspension.SUSPENDED);
        assertThat(activeAdministrators()).as("security wins over availability there").isZero();
    }

    @Test
    @DisplayName("the access review's check names an administrator with no factor, and not one with a factor (P15-TSK-032)")
    void theChecksNamesAFactorlessAdministrator() throws Exception {
        IdentityId factorless = givenAnAdministrator();
        IdentityId reader = givenAnAdministrator();
        givenAConfirmedFactorFor(reader);

        HttpResponse<String> named = get("/operator/access-review/factorless-holdings",
                givenASessionAt(reader, AssuranceLevel.MULTI_FACTOR));

        assertThat(named.statusCode()).isEqualTo(200);
        assertThat(named.body()).contains(factorless.value().toString()).contains("ADMINISTRATOR")
                .doesNotContain(reader.value().toString());
    }

    // ----------------------------------------------------------------------------------------------- the race

    private interface HeldAct {
        Object act(Connection held) throws Exception;
    }

    /**
     * {@code first} on a connection held open after it acted, as {@code firstActor}; then {@code second} on a thread of its own,
     * seen waiting on a lock behind the first; then the first's commit. Answers both outcomes, the first's first.
     */
    private Object[] race(HeldAct first, Callable<Object> second, IdentityId firstActor) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            return actingAs(firstActor, () -> {
                try (Connection held = DatabaseRoles.application()) {
                    held.setAutoCommit(false);
                    Object firstOutcome = first.act(held);
                    Future<Object> secondOutcome = pool.submit(second);
                    awaitBlockedBehind(held, secondOutcome);
                    held.commit();
                    return new Object[] {firstOutcome, secondOutcome.get(30, TimeUnit.SECONDS)};
                }
            });
        } finally {
            pool.shutdownNow();
        }
    }

    /** Runs {@code act} under the scopes the door runs in, acting as {@code actor}. */
    private static <T> T actingAs(IdentityId actor, Callable<T> act) throws Exception {
        try (CorrelationContext.Scope correlation =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
                SecurityContext.Scope acting = SecurityContext.enter(new Actor(actor.value().toString(), ActorType.EMPLOYEE))) {
            return act.call();
        }
    }

    /** A session active and waiting on a lock {@code holder}'s session holds, awaited about 30 s. */
    private static void awaitBlockedBehind(Connection holder, Future<?> racer) throws Exception {
        int blocker;
        try (PreparedStatement select = holder.prepareStatement("SELECT pg_backend_pid()");
                ResultSet row = select.executeQuery()) {
            row.next();
            blocker = row.getInt(1);
        }
        for (int attempt = 0; attempt < 1500; attempt++) {
            if (racer.isDone()) {
                throw new AssertionError("the second act finished while the first held its locks: " + racer.get());
            }
            try (Connection observer = DatabaseRoles.application();
                    PreparedStatement select = observer.prepareStatement("SELECT count(*) FROM pg_stat_activity"
                            + " WHERE ? = ANY (pg_blocking_pids(pid)) AND state = 'active' AND wait_event_type = 'Lock'")) {
                select.setInt(1, blocker);
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    if (row.getLong(1) >= 1) {
                        return;
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no session waiting on a lock behind session " + blocker + " within about 30 s");
    }

    // ----------------------------------------------------------------------------------------------- fixtures

    private IdentityId givenAnAdministrator() throws Exception {
        IdentityId admin = givenAnIdentity();
        try (CorrelationContext.Scope correlation =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, admin, RoleName.ADMINISTRATOR, admin, "bootstrap");
            app.commit();
        }
        return admin;
    }

    private static long activeAdministrators() throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count = app.prepareStatement(
                        "SELECT count(DISTINCT held.identity_id) FROM identity.role_assignment held"
                                + " JOIN identity.identity holder ON holder.id = held.identity_id"
                                + " WHERE held.revoked_at IS NULL AND held.role_name = 'ADMINISTRATOR' AND holder.status = 'ACTIVE'");
                ResultSet rows = count.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static String statusOf(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT status FROM identity.identity WHERE id = ?")) {
            select.setObject(1, identity.value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    /** A confirmed factor, written directly: this suite is about the rule, not enrolment ({@code IdentityAdministrationDatabaseTest}'s). */
    private static void givenAConfirmedFactorFor(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement insert = app.prepareStatement(
                        "INSERT INTO identity.mfa_enrolment (id, identity_id, type, secret_ciphertext, secret_nonce, key_version,"
                                + " algorithm, digits, period_seconds, status, created_at, confirmed_at)"
                                + " VALUES (?, ?, 'TOTP', ?, ?, 1, 'SHA1', 6, 30, 'ACTIVE', now(), now())")) {
            insert.setObject(1, IDS.next());
            insert.setObject(2, identity.value());
            insert.setBytes(3, new byte[32]);
            insert.setBytes(4, new byte[12]);
            insert.executeUpdate();
        }
    }

    private String givenASessionAt(IdentityId identity, AssuranceLevel assurance) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session.Draft session = Session.issue(IDS, CLOCK, identity, SessionToken.of(plaintext), assurance, SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return plaintext;
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Back-dated, for {@code IdentityAdministrationDatabaseTest#givenAnIdentity}'s reason: a suspension's stamp is the JVM's. */
    private static IdentityId givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            try (PreparedStatement insert = app.prepareStatement(
                    "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'Ada Lovelace', now())")) {
                insert.setObject(1, party);
                insert.executeUpdate();
            }
            try (PreparedStatement insert = app.prepareStatement(
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                            + " VALUES (?, ?, ?, 'ACTIVE', now() - interval '1 hour', now() - interval '1 hour')")) {
                insert.setObject(1, identity);
                insert.setObject(2, party);
                insert.setString(3, "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
                insert.executeUpdate();
            }
        }
        return IdentityId.of(identity);
    }
}
