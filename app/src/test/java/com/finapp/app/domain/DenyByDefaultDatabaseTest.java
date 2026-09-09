package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.PermissionName;
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
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deny by default, and the permission check (`P1-TSK-020`, ADR-0031, {@code INV-IDN-04}).
 *
 * <h2>The acceptance criterion is an endpoint nobody declared</h2>
 *
 * <p>ADR-0031: <em>"an operation with no declared permission is refused, not permitted. A rule's
 * absence is never a grant."</em> {@code P1-TSK-016} recorded that an endpoint forgetting
 * {@code @RequiresSession} failed closed only <strong>by accident</strong> — it threw at
 * {@code SecurityContext.require()} deep inside the handler, which is a 500 standing in for a
 * security decision. This is the rule that makes it deliberate.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DenyByDefaultDatabaseTest.Endpoints.class)
@DisplayName("deny by default (P1-TSK-020)")
class DenyByDefaultDatabaseTest {

    /** One endpoint per declaration class, including the one that declares nothing. */
    @TestConfiguration
    @RestController
    static class Endpoints {

        /** <strong>Declares nothing.</strong> This is the acceptance criterion's subject. */
        @GetMapping("/probe/undeclared")
        String undeclared() {
            return "reached";
        }

        @com.finapp.app.session.Unauthenticated
        @GetMapping("/probe/public")
        String publicly() {
            return "reached";
        }

        @com.finapp.app.session.RequiresSession
        @GetMapping("/probe/session")
        String withSession() {
            return "reached";
        }

        @com.finapp.app.session.RequiresPermission(PermissionName.ROLE_ASSIGN)
        @GetMapping("/probe/privileged")
        String privileged() {
            return "reached";
        }

        /**
         * The reviewer's permission, which no production endpoint carries until
         * {@code P2-TSK-012} — a probe for the {@code P1-TSK-018} reason: inventing a production
         * surface to give a check something to guard would be a surface chosen to suit a test.
         */
        @com.finapp.app.session.RequiresPermission(PermissionName.KYC_REVIEW)
        @GetMapping("/probe/review")
        String review() {
            return "reached";
        }

        /**
         * <strong>Declares two rules, and they contradict.</strong> Found served by the completion
         * gate.
         */
        @com.finapp.app.session.Unauthenticated
        @com.finapp.app.session.RequiresPermission(PermissionName.ROLE_ASSIGN)
        @GetMapping("/probe/contradictory")
        String contradictory() {
            return "reached";
        }
    }

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    @LocalServerPort private int port;

    @Autowired private Authorization authorization;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    // -----------------------------------------------------------------
    // The acceptance criterion

    @Test
    @DisplayName("an endpoint that declares nothing is refused, even with a valid session")
    void anUndeclaredEndpointIsRefused() throws Exception {
        IdentityId identity = givenAnIdentity();
        String session = givenASessionFor(identity);

        // A VALID session, deliberately. A weaker test presenting none would pass against an
        // implementation that merely required authentication - and the property here is that a
        // rule's absence is never a grant, which is about the DECLARATION rather than the caller.
        assertThat(get("/probe/undeclared", session).statusCode())
                .as("a rule's absence is never a grant (ADR-0031, INV-IDN-04)")
                .isEqualTo(403);

        // And with no session at all, so neither route reaches it.
        assertThat(get("/probe/undeclared", null).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("a handler declaring @Unauthenticated AND a protective rule is refused")
    void aContradictoryDeclarationIsRefused() throws Exception {
        // The completion gate found this SERVED - 200, with no session at all - because the
        // @Unauthenticated branch returns before both checks and the build guard is satisfied by ANY
        // ONE declaration being present.
        //
        // The harm is not that it is public. It is that it READS as protected: a reviewer grepping
        // for @RequiresPermission finds it and stops looking. P1-TSK-016's unowned revoke, in a new
        // place - a declaration asserting a control nobody applies is worse than an absent one.
        assertThat(get("/probe/contradictory", null).statusCode())
                .as("a handler declares exactly one rule; a contradiction is refused rather than"
                        + " silently resolved to the stricter reading, which would hide the defect")
                .isEqualTo(403);

        // And with a valid session, so it is not the missing session doing the refusing.
        IdentityId identity = givenAnIdentity();
        assertThat(get("/probe/contradictory", givenASessionFor(identity)).statusCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("the declared endpoints still work, so the rule is not a blanket refusal")
    void everyDeclarationClassStillWorks() throws Exception {
        IdentityId identity = givenAnIdentity();
        String session = givenASessionFor(identity);

        // The positive control. Without it, `anUndeclaredEndpointIsRefused` passes against an
        // interceptor that refuses everything - which would be a broken application rather than an
        // enforced rule.
        assertThat(get("/probe/public", null).statusCode()).isEqualTo(200);
        assertThat(get("/probe/session", session).statusCode()).isEqualTo(200);
    }

    // -----------------------------------------------------------------
    // Negative authorization, per declaration class

    @Test
    @DisplayName("a public endpoint needs nothing; a session endpoint refuses an absent session")
    void negativeAuthorizationForSessionEndpoints() throws Exception {
        assertThat(get("/probe/public", null).statusCode()).isEqualTo(200);
        assertThat(get("/probe/session", null).statusCode())
                .as("authentication is required, and its absence is a 401 rather than a 403")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("a privileged endpoint refuses a session whose identity holds no role")
    void negativeAuthorizationForPrivilegedEndpoints() throws Exception {
        IdentityId identity = givenAnIdentity();
        String session = givenASessionFor(identity);

        // INV-IDN-04: an authenticated session grants no permission by itself. This is that
        // sentence as a test - the session is perfectly valid and the answer is still no.
        assertThat(get("/probe/privileged", session).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("and admits one that holds the role, so the check is not a blanket refusal")
    void aRoleHolderIsAdmitted() throws Exception {
        IdentityId identity = givenAnIdentity();
        String session = givenASessionFor(identity);
        givenTheRole(identity, RoleName.ADMINISTRATOR);

        assertThat(get("/probe/privileged", session).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("the two administrative populations are disjoint, proven from both directions")
    void theTwoPopulationsAreDisjoint() throws Exception {
        IdentityId administrator = givenAnIdentity();
        String adminSession = givenASessionFor(administrator);
        givenTheRole(administrator, RoleName.ADMINISTRATOR);

        IdentityId reviewer = givenAnIdentity();
        String reviewerSession = givenASessionFor(reviewer);
        givenTheRole(reviewer, RoleName.KYC_REVIEWER);

        // Positive controls first, so neither refusal below can be a blanket one.
        assertThat(get("/probe/privileged", adminSession).statusCode()).isEqualTo(200);
        assertThat(get("/probe/review", reviewerSession).statusCode()).isEqualTo(200);

        // The least-privilege split, tested from the attacker's direction on BOTH sides
        // (INV-AUD-03). This is the behavioural half of the mutation P1-TSK-020 recorded as
        // untestable at one role: a role granting everything passes every positive control in
        // this suite and fails exactly these two assertions.
        assertThat(get("/probe/review", adminSession).statusCode())
                .as("an administrator does not review cases")
                .isEqualTo(403);
        assertThat(get("/probe/privileged", reviewerSession).statusCode())
                .as("a reviewer does not manage identities")
                .isEqualTo(403);
    }

    // -----------------------------------------------------------------
    // Revocation is immediate

    @Test
    @DisplayName("revoking a role takes effect on the NEXT request, not at session expiry")
    void roleRevocationIsImmediate() throws Exception {
        IdentityId identity = givenAnIdentity();
        String session = givenASessionFor(identity);
        givenTheRole(identity, RoleName.ADMINISTRATOR);
        assertThat(get("/probe/privileged", session).statusCode()).isEqualTo(200);

        revokeTheRole(identity, RoleName.ADMINISTRATOR);

        // The reason permissions are resolved per request and never stamped on the session. A role
        // carried on the session would survive its own revocation until that session expired, and
        // "remove their access now" would be a promise the architecture cannot keep - INV-IDN-03's
        // reasoning, applied to authorization.
        //
        // The SAME session token, deliberately: if the test issued a new one it would prove nothing
        // about revocation, only that a fresh session reads fresh roles.
        assertThat(get("/probe/privileged", session).statusCode())
                .as("a revoked role must stop granting on the very next request")
                .isEqualTo(403);
    }

    // -----------------------------------------------------------------
    // The denial is audited

    @Test
    @DisplayName("a refused privileged attempt is audited: the only trace an attacker leaves")
    void aDenialIsAudited() throws Exception {
        IdentityId identity = givenAnIdentity();
        String session = givenASessionFor(identity);

        long before = denialsFor(identity);
        assertThat(get("/probe/privileged", session).statusCode()).isEqualTo(403);

        // INV-AUD-03 asks for authorization to be tested from the attacker's direction. A SUCCESSFUL
        // privileged action is audited by the operation itself; a refused one has no operation to do
        // it, so without this record a probe for privileged endpoints is indistinguishable from
        // silence.
        assertThat(denialsFor(identity) - before)
                .as("a refused privileged attempt must leave a durable trace")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------

    private static long denialsFor(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.audit_record"
                                        + " WHERE operation = 'identity.AuthorizationDenied'"
                                        + " AND target_id = ? AND actor_id = ?")) {
            count.setString(1, identity.value().toString());
            // The actor must be the PERSON, not the platform: the interceptor establishes the scope
            // before the permission check precisely so the denial names who attempted it.
            count.setString(2, identity.value().toString());
            try (var rows = count.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private void givenTheRole(IdentityId identity, RoleName role) throws SQLException {
        inAFlow(app -> authorization.assign(app, identity, role, identity, "test fixture"));
    }

    private void revokeTheRole(IdentityId identity, RoleName role) throws SQLException {
        inAFlow(app -> authorization.revoke(app, identity, role, identity, "test fixture"));
    }

    private interface Work {
        void run(Connection app) throws SQLException;
    }

    private static void inAFlow(Work work) throws SQLException {
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

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
