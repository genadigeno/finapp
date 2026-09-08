package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.platform.correlation.CorrelationContext;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The two administrative endpoints, over real HTTP (`P1-TSK-028`).
 *
 * <h2>The assertion that matters most is that a suspension ends live sessions</h2>
 *
 * <p>{@code JdbcSessionStore.findByToken} filters on the <strong>session's</strong> status and never
 * joins {@code identity.identity}, and {@code CredentialVerifier} refuses a suspended identity only
 * at <em>authentication</em>. So without the revocation, suspending somebody would stop their next
 * login and leave the session an attacker is holding right now working until its absolute bound
 * expired — a success response for an operation that did almost nothing.
 *
 * <p>{@link #aSuspensionEndsLiveSessions} drives that end to end: the subject's token works, an
 * administrator suspends, and the <strong>same token</strong> is refused. A new token would prove
 * only that a suspended identity cannot log in, which was already true.
 *
 * <h2>Ownership is inverted here, and both halves are asserted</h2>
 *
 * <p>ADR-0031 wants permission at the boundary and ownership in the domain. The ownership rule is
 * the opposite of every other one in this codebase — the subject must <strong>not</strong> be the
 * actor — so the negative ownership tests are {@link #anAdministratorCannotSuspendThemselves} and
 * {@link #anAdministratorCannotElevateThemselves}.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the administrative endpoints (P1-TSK-028)")
class IdentityAdministrationDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    private static final String REASON = "{\"reason\":\"offboarding, ticket OPS-4417\"}";

    @LocalServerPort private int port;

    @Autowired private Authorization authorization;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    // -----------------------------------------------------------------
    // The acceptance criterion: refused without the role, and the refusal is audited

    @Test
    @DisplayName("a session holding no role is refused by both endpoints")
    void bothEndpointsRefuseASessionWithNoRole() throws Exception {
        IdentityId caller = givenAnIdentity();
        String session = givenASessionFor(caller);
        IdentityId subject = givenAnIdentity();

        assertThat(post(suspensionOf(subject), session, REASON).statusCode())
                .as("a valid session is not a role")
                .isEqualTo(403);
        assertThat(post(rolesOf(subject), session, adminRole()).statusCode()).isEqualTo(403);

        assertThat(statusOf(subject)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("each refusal is audited against the person who attempted it")
    void everyRefusalIsAudited() throws Exception {
        IdentityId caller = givenAnIdentity();
        String session = givenASessionFor(caller);
        IdentityId subject = givenAnIdentity();

        long before = denialsFor(caller);
        assertThat(post(suspensionOf(subject), session, REASON).statusCode()).isEqualTo(403);
        assertThat(post(rolesOf(subject), session, adminRole()).statusCode()).isEqualTo(403);

        // A refused privileged attempt is the only durable trace an attacker probing for
        // administrative endpoints leaves behind (INV-AUD-03, and P1-TSK-020's reasoning).
        assertThat(denialsFor(caller) - before).isEqualTo(2);
    }

    // -----------------------------------------------------------------
    // The positive controls, so refusal is not blanket

    @Test
    @DisplayName("an administrator can suspend somebody else, and it is audited against them")
    void anAdministratorCanSuspend() throws Exception {
        IdentityId admin = givenAnAdministrator();
        String session = givenASessionFor(admin);
        IdentityId subject = givenAnIdentity();

        assertThat(post(suspensionOf(subject), session, REASON).statusCode()).isEqualTo(204);
        assertThat(statusOf(subject)).isEqualTo("SUSPENDED");

        // The actor is the administrator and the target is the subject. Recording it the other way
        // round would make every administrative action read as self-inflicted.
        assertThat(auditOf("identity.IdentitySuspended", subject, admin))
                .as("the trail names who acted, on whom, and why")
                .contains("OPS-4417");
    }

    @Test
    @DisplayName("an administrator can grant a role to somebody else")
    void anAdministratorCanGrantARole() throws Exception {
        IdentityId admin = givenAnAdministrator();
        String session = givenASessionFor(admin);
        IdentityId subject = givenAnIdentity();

        assertThat(post(rolesOf(subject), session, adminRole()).statusCode()).isEqualTo(204);
        assertThat(auditOf("identity.RoleAssigned", subject, admin)).contains("OPS-4417");

        // And the grant is real: the subject can now do what only an administrator may.
        IdentityId victim = givenAnIdentity();
        assertThat(post(suspensionOf(victim), givenASessionFor(subject), REASON).statusCode())
                .isEqualTo(204);
    }

    // -----------------------------------------------------------------
    // The inverted ownership rule

    @Test
    @DisplayName("an administrator cannot suspend themselves")
    void anAdministratorCannotSuspendThemselves() throws Exception {
        IdentityId admin = givenAnAdministrator();
        String session = givenASessionFor(admin);

        assertThat(post(suspensionOf(admin), session, REASON).statusCode())
                .as("there is no reinstatement endpoint, so this is a one-way door")
                .isEqualTo(422);
        assertThat(statusOf(admin)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("an administrator cannot assign a role to themselves")
    void anAdministratorCannotElevateThemselves() throws Exception {
        IdentityId admin = givenAnAdministrator();

        assertThat(post(rolesOf(admin), givenASessionFor(admin), adminRole()).statusCode())
                .as("every escalation must name two parties, so the trail can never hold a"
                        + " self-loop that reads like a system action")
                .isEqualTo(422);
    }

    // -----------------------------------------------------------------
    // The suspension has to actually suspend

    @Test
    @DisplayName("a suspension ends the subject's live sessions, on the very next request")
    void aSuspensionEndsLiveSessions() throws Exception {
        IdentityId admin = givenAnAdministrator();
        IdentityId subject = givenAnIdentity();
        String victimSession = givenASessionFor(subject);

        assertThat(get("/sessions", victimSession).statusCode())
                .as("precondition: the session works before the suspension")
                .isEqualTo(200);

        assertThat(post(suspensionOf(subject), givenASessionFor(admin), REASON).statusCode())
                .isEqualTo(204);

        // The SAME token. Issuing a new one would prove only that a suspended identity cannot log
        // in, which CredentialVerifier already enforced and which is not what suspension is for.
        assertThat(get("/sessions", victimSession).statusCode())
                .as("a suspended identity that keeps a live session is suspended in name only")
                .isEqualTo(401);
    }

    // -----------------------------------------------------------------
    // Everything else

    @Test
    @DisplayName("suspending an identity that is not ACTIVE is a conflict, not a second suspension")
    void aSecondSuspensionIsAConflict() throws Exception {
        IdentityId admin = givenAnAdministrator();
        String session = givenASessionFor(admin);
        IdentityId subject = givenAnIdentity();

        assertThat(post(suspensionOf(subject), session, REASON).statusCode()).isEqualTo(204);
        assertThat(post(suspensionOf(subject), session, REASON).statusCode()).isEqualTo(409);

        assertThat(auditCountOf("identity.IdentitySuspended", subject))
                .as("one transition, one record: a second would describe something that did not"
                        + " happen")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a CLOSED identity is refused too, and the outcome does not claim it was suspended")
    void aClosedIdentityCannotBeSuspended() throws Exception {
        String session = givenASessionFor(givenAnAdministrator());
        IdentityId subject = givenAnIdentity();
        closeIdentity(subject);

        // The same 409 as an already-suspended one, and that is right: both mean "not ACTIVE". What
        // the completion gate found was the OUTCOME NAME - ALREADY_SUSPENDED is a claim that is
        // false here, since a CLOSED identity is gone permanently rather than suspended, and a
        // caller reading it would conclude the operation had effectively succeeded.
        assertThat(post(suspensionOf(subject), session, REASON).statusCode()).isEqualTo(409);
        assertThat(statusOf(subject)).isEqualTo("CLOSED");
        assertThat(auditCountOf("identity.IdentitySuspended", subject))
                .as("a refused transition writes no record of a transition")
                .isZero();
    }

    @Test
    @DisplayName("an unknown or malformed identity is one answer, and it creates nothing")
    void anUnknownSubjectIs404() throws Exception {
        String session = givenASessionFor(givenAnAdministrator());

        // IDS.next(), not UUID.randomUUID(). IdentityId.of validates UUIDv7 (P0-TSK-012), so a v4
        // is refused as MALFORMED and never reaches the service - which is a different 404 for a
        // different reason. The first version of this test used randomUUID and passed for exactly
        // that wrong reason; a mutation removing assignRole's existence check survived it, which is
        // how the substitution was found.
        assertThat(post("/identities/" + IDS.next() + "/suspension", session, REASON).statusCode())
                .as("well-formed, and names nobody")
                .isEqualTo(404);
        assertThat(post("/identities/not-a-uuid/suspension", session, REASON).statusCode())
                .isEqualTo(404);

        // Role assignment too, and this half was added because a mutation survived without it:
        // removing the existence check from assignRole broke nothing, because only suspension was
        // covered. Without the check the assignment reaches the foreign key on
        // identity.role_assignment and arrives as a storage exception rendered api.InternalError -
        // our fault reported for a caller's typo (ERROR_CONTRACT.md section 3), retryable for ever
        // on a request that can never succeed. The javadoc on assignRole predicted exactly that
        // and no test held it.
        assertThat(post("/identities/" + IDS.next() + "/roles", session, adminRole()).statusCode())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("a missing reason is a 422 naming the field, never a 500")
    void aMissingReasonIsRefusedAtTheBoundary() throws Exception {
        String session = givenASessionFor(givenAnAdministrator());
        IdentityId subject = givenAnIdentity();

        HttpResponse<String> response = post(suspensionOf(subject), session, "{}");

        // AuditRecord refuses construction without a reason for this action, so an absent one could
        // never reach the table - but it would arrive as api.InternalError, our fault reported for
        // the caller's omission (ERROR_CONTRACT.md section 3).
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("api.ValidationFailed").contains("reason");
        assertThat(statusOf(subject)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("an unknown role is refused at the boundary, not by valueOf three layers down")
    void anUnknownRoleIsRefused() throws Exception {
        String session = givenASessionFor(givenAnAdministrator());
        IdentityId subject = givenAnIdentity();

        HttpResponse<String> response =
                post(rolesOf(subject), session, "{\"role\":\"SUPERUSER\",\"reason\":\"x\"}");

        assertThat(response.statusCode()).isIn(400, 422);
        assertThat(response.body()).doesNotContain("api.InternalError");
    }

    @Test
    @DisplayName("granting a role the identity already holds is success, not a conflict")
    void aRepeatedGrantIsSuccess() throws Exception {
        String session = givenASessionFor(givenAnAdministrator());
        IdentityId subject = givenAnIdentity();

        assertThat(post(rolesOf(subject), session, adminRole()).statusCode()).isEqualTo(204);
        assertThat(post(rolesOf(subject), session, adminRole()).statusCode())
                .as("a retry after a lost response must not look like a failure")
                .isEqualTo(204);
    }

    // -----------------------------------------------------------------

    private static String suspensionOf(IdentityId subject) {
        return "/identities/" + subject.value() + "/suspension";
    }

    private static String rolesOf(IdentityId subject) {
        return "/identities/" + subject.value() + "/roles";
    }

    private static String adminRole() {
        return "{\"role\":\"ADMINISTRATOR\",\"reason\":\"offboarding, ticket OPS-4417\"}";
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void closeIdentity(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "UPDATE identity.identity SET status = 'CLOSED', status_changed_at = now()"
                            + " WHERE id = ?",
                    identity.value());
        }
    }

    private static String statusOf(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement("SELECT status FROM identity.identity WHERE id = ?")) {
            select.setObject(1, identity.value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static String auditOf(String operation, IdentityId target, IdentityId actor)
            throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT coalesce(string_agg(reason || ' ' ||"
                                        + " coalesce(change_summary, ''), ' '), '')"
                                        + " FROM platform.audit_record"
                                        + " WHERE operation = ? AND target_id = ? AND actor_id = ?")) {
            select.setString(1, operation);
            select.setString(2, target.value().toString());
            select.setString(3, actor.value().toString());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static long auditCountOf(String operation, IdentityId target) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.audit_record"
                                        + " WHERE operation = ? AND target_id = ?")) {
            select.setString(1, operation);
            select.setString(2, target.value().toString());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static long denialsFor(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.audit_record"
                                        + " WHERE operation = 'identity.AuthorizationDenied'"
                                        + " AND actor_id = ?")) {
            count.setString(1, identity.value().toString());
            try (ResultSet rows = count.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    /**
     * The first administrator, created out of band.
     *
     * <p>There is no API route to this state and that is a decision rather than a gap:
     * {@code ROLE_ASSIGN} is held only by {@code ADMINISTRATOR}, so the first assignment cannot come
     * through the endpoint that requires it. An operator with database access performs it, and the
     * consequence is recorded — that first grant has <strong>no actor in the audit trail</strong>,
     * because no authenticated actor performed it.
     */
    private IdentityId givenAnAdministrator() throws SQLException {
        IdentityId admin = givenAnIdentity();
        CorrelationContext.Scope correlation =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
        SecurityContext.Scope actor = SecurityContext.enterSystem();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, admin, RoleName.ADMINISTRATOR, admin, "bootstrap");
            app.commit();
        } finally {
            actor.close();
            correlation.close();
        }
        return admin;
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session session =
                Session.issue(
                        IDS,
                        CLOCK,
                        identity,
                        SessionToken.of(plaintext),
                        AssuranceLevel.PASSWORD,
                        SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return plaintext;
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
                    // Back-dated deliberately, not written at now().
                    //
                    // `identity_status_change_is_not_before_creation` compares this row's
                    // created_at against the status_changed_at a suspension writes - and the two
                    // come from DIFFERENT CLOCKS: this fixture uses the container's, while
                    // production uses the injected JVM Clock. CURRENT_STATE.md records that the
                    // local container's clock runs fast and is corrected backwards, so `now()` can
                    // be ahead of the JVM by hundreds of milliseconds and the constraint fires on a
                    // suspension that is perfectly correct.
                    //
                    // The constraint is right and the fixture was fragile - P1-TSK-031's finding,
                    // met here for the first time by production code rather than by another
                    // fixture. Back-dating is the established remedy (OutboxRelayTest.backDate).
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at)"
                            + " VALUES (?, ?, ?, 'ACTIVE', now() - interval '1 hour',"
                            + " now() - interval '1 hour')",
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
