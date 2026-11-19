package com.finapp.app.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityAdministration;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code GET /v1/operator/me} over HTTP against the real interceptor and the real tables (`X-TSK-019`; the console's
 * {@code P17-TSK-003}): what an operator learns about themselves, and that nobody else learns anything.
 *
 * <p><strong>Another replica.</strong> The harness runs one application context per JVM, so a second replica is not
 * started here. Nothing it would add is untested: every answer below is read per request from {@code identity.role_assignment},
 * {@code identity.session} and {@code identity.mfa_enrolment} - the revocation cases present the <em>same token</em> again after
 * a change committed outside the application, which is exactly what a second replica would see - and
 * {@code NoProcessLocalSessionStateTest} refuses a field on the interceptor that could hold a role between requests.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(OperatorSelfDatabaseTest.Probes.class)
@DisplayName("GET /v1/operator/me: an operator's own identity, roles, permissions and session (X-TSK-019)")
class OperatorSelfDatabaseTest {

    /** The declaration on its own, and beside a permission: what the interceptor does with each, whatever the route. */
    @TestConfiguration
    @RestController
    static class Probes {

        @RequiresAnyRole
        @GetMapping("/probe/any-role")
        String anyRole() {
            return "reached";
        }

        /** <strong>Two rules where one is the other's stronger form</strong>: refused, never resolved. */
        @RequiresAnyRole
        @RequiresPermission(PermissionName.KYC_REVIEW)
        @GetMapping("/probe/any-role-and-permission")
        String anyRoleAndPermission() {
            return "reached";
        }
    }

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final String PATH = "/v1/operator/me";

    @LocalServerPort private int port;

    @Autowired private Authorization authorization;
    @Autowired private IdentityAdministration administration;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final ObjectMapper json = new ObjectMapper();

    // ----------------------------------------------------------------------------------------- what an operator learns

    @Test
    @DisplayName("a role holder learns their roles and exactly the permissions RoleName grants them, sorted; a password"
            + " session with no factor says so; both bounds are the session row's, as extended for this request")
    void aRoleHolderLearnsExactlyWhatTheirRolesGrant() throws Exception {
        IdentityId operator = givenAnIdentity();
        givenTheRole(operator, RoleName.LEDGER_OPERATOR);
        givenTheRole(operator, RoleName.KYC_REVIEWER);
        String session = givenASessionAt(operator, AssuranceLevel.PASSWORD);

        HttpResponse<String> answered = get(session);

        assertThat(answered.statusCode()).as(answered.body()).isEqualTo(200);
        JsonNode self = json.readTree(answered.body());
        assertThat(fieldNames(self)).as("the published members and nothing else - no login identifier, no name")
                .containsExactly("identityId", "roles", "permissions", "assurance", "factorEnrolled",
                        "sessionIdleExpiresAt", "sessionAbsoluteExpiresAt");
        assertThat(self.get("identityId").asString()).isEqualTo(operator.value().toString());
        assertThat(strings(self.get("roles"))).containsExactly("KYC_REVIEWER", "LEDGER_OPERATOR");
        assertThat(strings(self.get("permissions")))
                .as("the union of RoleName's grants for the held roles, sorted, each once")
                .isEqualTo(grantsOf(RoleName.KYC_REVIEWER, RoleName.LEDGER_OPERATOR))
                .isSorted()
                .doesNotHaveDuplicates()
                .doesNotContain(PermissionName.ROLE_ASSIGN.name(), PermissionName.MERCHANT_ADMINISTER.name());
        assertThat(self.get("assurance").asString()).isEqualTo("PASSWORD");
        assertThat(self.get("factorEnrolled").asBoolean()).isFalse();

        Instant idle = Instant.parse(self.get("sessionIdleExpiresAt").asString());
        Instant absolute = Instant.parse(self.get("sessionAbsoluteExpiresAt").asString());
        assertThat(idle).isBeforeOrEqualTo(absolute);
        assertThat(List.of(idle, absolute)).as("the row as this request left it: the idle bound after its own extension")
                .isEqualTo(boundsOf(operator));
    }

    @Test
    @DisplayName("the operator read roles report their reads: a support agent and an operations viewer learn exactly the four"
            + " customer reads and the overview read, and no act (P17-TSK-025)")
    void theOperatorReadRolesAreReported() throws Exception {
        IdentityId operator = givenAnIdentity();
        givenTheRole(operator, RoleName.CUSTOMER_SUPPORT_AGENT);
        givenTheRole(operator, RoleName.OPERATIONS_VIEWER);
        String session = givenASessionAt(operator, AssuranceLevel.PASSWORD);

        HttpResponse<String> answered = get(session);

        assertThat(answered.statusCode()).as(answered.body()).isEqualTo(200);
        JsonNode self = json.readTree(answered.body());
        assertThat(strings(self.get("roles"))).containsExactly("CUSTOMER_SUPPORT_AGENT", "OPERATIONS_VIEWER");
        assertThat(strings(self.get("permissions")))
                .containsExactly("ACCOUNT_READ", "CUSTOMER_READ", "PAYMENT_READ", "PLATFORM_OVERVIEW_READ", "TRANSFER_READ")
                .isEqualTo(grantsOf(RoleName.CUSTOMER_SUPPORT_AGENT, RoleName.OPERATIONS_VIEWER));
    }

    @Test
    @DisplayName("a stepped-up session with an enrolled factor reports MULTI_FACTOR and factorEnrolled; the same identity on a"
            + " password session reports PASSWORD - the assurance is the session's, the factor the identity's")
    void theAssuranceIsTheSessionsAndTheFactorTheIdentitys() throws Exception {
        IdentityId operator = givenAnIdentity();
        givenTheRole(operator, RoleName.FX_CONTROLLER);
        givenAConfirmedFactorFor(operator);

        JsonNode stepped = json.readTree(get(givenASessionAt(operator, AssuranceLevel.MULTI_FACTOR)).body());
        assertThat(stepped.get("assurance").asString()).isEqualTo("MULTI_FACTOR");
        assertThat(stepped.get("factorEnrolled").asBoolean()).isTrue();

        JsonNode password = json.readTree(get(givenASessionAt(operator, AssuranceLevel.PASSWORD)).body());
        assertThat(password.get("assurance").asString()).as("no step-up is demanded: the console asks this to decide on one")
                .isEqualTo("PASSWORD");
        assertThat(password.get("factorEnrolled").asBoolean()).isTrue();
        assertThat(strings(password.get("permissions"))).isEqualTo(grantsOf(RoleName.FX_CONTROLLER));
    }

    // ----------------------------------------------------------------------------------------- who learns nothing

    @Test
    @DisplayName("no session, a malformed one, an unknown one: 401 api.Unauthenticated")
    void noSessionIs401() throws Exception {
        HttpResponse<String> none = get(null);
        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(none.body()).contains("api.Unauthenticated").doesNotContain("role");

        assertThat(get("not-a-session").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("a customer's session - valid, no live role - is 403 api.Forbidden, naming no role and no permission, and the"
            + " refusal is recorded once as identity.AuthorizationDenied (requirement=ANY_ROLE), the customer its actor")
    void aCustomerIsRefusedAndTheRefusalIsRecorded() throws Exception {
        IdentityId customer = givenAnIdentity();

        HttpResponse<String> refused = get(givenASessionAt(customer, AssuranceLevel.MULTI_FACTOR));

        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.body()).contains("api.Forbidden").doesNotContain("KYC_", "LEDGER_", "ADMINISTRATOR", "identityId");
        assertThat(rows("SELECT actor_type || ' ' || outcome || ' ' || change_summary FROM platform.audit_record"
                        + " WHERE operation = 'identity.AuthorizationDenied' AND target_id = ? AND actor_id = ?",
                customer.value().toString(), customer.value().toString()))
                .containsExactly("CUSTOMER FAILED requirement=ANY_ROLE");
    }

    @Test
    @DisplayName("a role holder's admitted read writes no audit record: a self-description is not audited")
    void theReadIsNotAudited() throws Exception {
        IdentityId operator = givenAnIdentity();
        givenTheRole(operator, RoleName.UNDERWRITER);
        String session = givenASessionAt(operator, AssuranceLevel.PASSWORD);
        List<String> before = rows("SELECT count(*)::text FROM platform.audit_record WHERE actor_id = ?", operator.value().toString());

        assertThat(get(session).statusCode()).isEqualTo(200);

        assertThat(rows("SELECT count(*)::text FROM platform.audit_record WHERE actor_id = ?", operator.value().toString()))
                .isEqualTo(before);
    }

    // ----------------------------------------------------------------------------------------- no cache

    @Test
    @DisplayName("a role revoked is gone on the next request with the same token; a role granted appears on it")
    void aRoleChangeIsTheNextRequestsAnswer() throws Exception {
        IdentityId operator = givenAnIdentity();
        givenTheRole(operator, RoleName.KYC_REVIEWER);
        givenTheRole(operator, RoleName.ACCOUNTANT);
        String session = givenASessionAt(operator, AssuranceLevel.PASSWORD);
        assertThat(strings(json.readTree(get(session).body()).get("roles"))).containsExactly("ACCOUNTANT", "KYC_REVIEWER");

        inAFlow(app -> authorization.revoke(app, operator, RoleName.KYC_REVIEWER, operator, "test fixture"));
        JsonNode after = json.readTree(get(session).body());
        assertThat(strings(after.get("roles"))).containsExactly("ACCOUNTANT");
        assertThat(strings(after.get("permissions"))).isEqualTo(grantsOf(RoleName.ACCOUNTANT));

        givenTheRole(operator, RoleName.RISK_ANALYST);
        assertThat(strings(json.readTree(get(session).body()).get("roles"))).containsExactly("ACCOUNTANT", "RISK_ANALYST");
    }

    @Test
    @DisplayName("the last role revoked through the domain ends the operator's sessions, so the same token is then 401, not a"
            + " 403 or an empty list")
    void theLastRoleRevokedIs401() throws Exception {
        IdentityId operator = givenAnIdentity();
        givenTheRole(operator, RoleName.KYC_REVIEWER);
        String session = givenASessionAt(operator, AssuranceLevel.PASSWORD);
        assertThat(get(session).statusCode()).isEqualTo(200);
        IdentityId administrator = givenAnIdentity();

        inAFlow(app -> assertThat(administration.revokeRole(app, operator, RoleName.KYC_REVIEWER, administrator,
                        "left the desk, OPS-1"))
                .isEqualTo(IdentityAdministration.RoleRevocation.REVOKED));

        HttpResponse<String> ended = get(session);
        assertThat(ended.statusCode()).isEqualTo(401);
        assertThat(ended.body()).contains("api.Unauthenticated");
    }

    @Test
    @DisplayName("a suspended identity's session no longer authenticates: 401 (suspension ends every session)")
    void aSuspendedOperatorIs401() throws Exception {
        IdentityId operator = givenAnIdentity();
        givenTheRole(operator, RoleName.KYC_REVIEWER);
        String session = givenASessionAt(operator, AssuranceLevel.PASSWORD);
        assertThat(get(session).statusCode()).isEqualTo(200);
        IdentityId administrator = givenAnIdentity();

        inAFlow(app -> assertThat(administration.suspend(app, operator, administrator, "compromised, OPS-2"))
                .isEqualTo(IdentityAdministration.Suspension.SUSPENDED));

        assertThat(get(session).statusCode()).isEqualTo(401);
    }

    // ----------------------------------------------------------------------------------------- the declaration itself

    @Test
    @DisplayName("@RequiresAnyRole: any role admits, none is 403 and no session 401, wherever the route is; beside a"
            + " permission it is refused even to the permission's holder - one rule per route")
    void theDeclarationIsEnforcedByTheInterceptor() throws Exception {
        IdentityId reviewer = givenAnIdentity();
        givenTheRole(reviewer, RoleName.KYC_REVIEWER);
        String holder = givenASessionAt(reviewer, AssuranceLevel.PASSWORD);
        String customer = givenASessionAt(givenAnIdentity(), AssuranceLevel.PASSWORD);

        assertThat(probe("/v1/probe/any-role", holder).statusCode()).isEqualTo(200);
        assertThat(probe("/v1/probe/any-role", customer).statusCode()).isEqualTo(403);
        assertThat(probe("/v1/probe/any-role", null).statusCode()).isEqualTo(401);
        assertThat(probe("/v1/probe/any-role-and-permission", holder).statusCode())
                .as("a contradiction is refused rather than resolved, as @Unauthenticated beside a rule is").isEqualTo(403);
    }

    // -----------------------------------------------------------------

    private HttpResponse<String> probe(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> grantsOf(RoleName... roles) {
        Set<PermissionName> union = EnumSet.noneOf(PermissionName.class);
        for (RoleName role : roles) {
            union.addAll(role.permissions());
        }
        return union.stream().map(PermissionName::name).sorted().collect(Collectors.toList());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }

    private static List<Instant> boundsOf(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT idle_expires_at, absolute_expires_at FROM identity.session WHERE identity_id = ?")) {
            select.setObject(1, identity.value());
            try (var rows = select.executeQuery()) {
                assertThat(rows.next()).isTrue();
                List<Instant> bounds = List.of(rows.getTimestamp(1).toInstant(), rows.getTimestamp(2).toInstant());
                assertThat(rows.next()).as("one session").isFalse();
                return bounds;
            }
        }
    }

    private static List<String> rows(String sql, String... arguments) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection app = DatabaseRoles.application(); PreparedStatement query = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                query.setString(i + 1, arguments[i]);
            }
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
        }
        return values;
    }

    private HttpResponse<String> get(String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void givenTheRole(IdentityId identity, RoleName role) throws SQLException {
        inAFlow(app -> authorization.assign(app, identity, role, identity, "test fixture"));
    }

    private interface Work {
        void run(Connection app) throws SQLException;
    }

    private static void inAFlow(Work work) throws SQLException {
        CorrelationContext.Scope correlation =
                CorrelationContext.enter(Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
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

    private static IdentityId givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at)"
                    + " VALUES (?, 'PERSON', 'Ada Lovelace', now())", party);
            execute(app, "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE', now(), now())",
                    identity, party, "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        return IdentityId.of(identity);
    }

    private static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }
}
