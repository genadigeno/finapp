package com.finapp.app.administration;

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
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The segregation-of-duties refusal and its review over HTTP (`P17-TSK-016`): the grant door answers {@code 409
 * identity.RoleConflict} naming the role already held, grants nothing and records the refusal; the review is the administrator's,
 * on a stepped-up session, refused to another role and to a password.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the segregation-of-duties refusal and its review over HTTP (P17-TSK-016)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class RoleConflictApiDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("a conflicting grant: 409 identity.RoleConflict naming the held role, nothing granted, the refusal recorded")
    void aConflictingGrantIsRefusedAndRecorded() throws Exception {
        IdentityId admin = identity(RoleName.ADMINISTRATOR);
        String token = session(admin, AssuranceLevel.MULTI_FACTOR);
        IdentityId accountant = identity(RoleName.ACCOUNTANT);

        HttpResponse<String> refused = send("POST", token, "/v1/identities/" + accountant.value() + "/roles",
                "{\"role\":\"FINANCIAL_CONTROLLER\",\"reason\":\"the month-end close needs an approver\"}");

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("identity.RoleConflict").contains("ACCOUNTANT").contains("FINANCIAL_CONTROLLER");
        assertThat(single("SELECT count(*) FROM identity.role_assignment WHERE identity_id = '" + accountant.value()
                + "' AND role_name = 'FINANCIAL_CONTROLLER'")).isEqualTo("0");
        assertThat(single("SELECT count(*) FROM platform.audit_record WHERE actor_id = '" + admin.value()
                + "' AND operation = 'door.ActRefused' AND change_summary LIKE '%identity.RoleConflict%'"))
                .as("the refusal is on the record").isEqualTo("1");
    }

    @Test
    @DisplayName("the review: the administrator's on a stepped-up session; another role 403, a password 403")
    void theReviewIsTheAdministrators() throws Exception {
        String admin = session(identity(RoleName.ADMINISTRATOR), AssuranceLevel.MULTI_FACTOR);
        String password = session(identity(RoleName.ADMINISTRATOR), AssuranceLevel.PASSWORD);
        String reviewer = session(identity(RoleName.KYC_REVIEWER), AssuranceLevel.MULTI_FACTOR);

        HttpResponse<String> review = send("GET", admin, "/v1/operator/access-review/sod-conflicts", null);
        assertThat(review.statusCode()).as(review.body()).isEqualTo(200);
        assertThat(review.body()).startsWith("{\"items\":[");
        assertThat(send("GET", reviewer, "/v1/operator/access-review/sod-conflicts", null).statusCode()).isEqualTo(403);
        HttpResponse<String> stepUp = send("GET", password, "/v1/operator/access-review/sod-conflicts", null);
        assertThat(stepUp.statusCode()).isEqualTo(403);
        assertThat(stepUp.body()).contains("identity.AssuranceRequired");
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private HttpResponse<String> send(String method, String token, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json");
        if (body != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** An identity with a confirmed factor, holding {@code role}, granted as the fixtures grant. */
    private IdentityId identity(RoleName role) throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'SoD Door Person', now())", party);
            execute(app, "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                    + " VALUES (?, ?, ?, 'ACTIVE', now(), now())", identity, party,
                    "sdd" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
            execute(app, "INSERT INTO identity.mfa_enrolment (id, identity_id, type, secret_ciphertext, secret_nonce, key_version,"
                    + " algorithm, digits, period_seconds, status, created_at, confirmed_at)"
                    + " VALUES (?, ?, 'TOTP', ?, ?, 1, 'SHA1', 6, 30, 'ACTIVE', now(), now())",
                    IDS.next(), identity, new byte[32], new byte[12]);
        }
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("p17-016-" + UUID.randomUUID())));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        return IdentityId.of(identity);
    }

    private String session(IdentityId identity, AssuranceLevel assurance) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, Session.issue(IDS, CLOCK, identity, SessionToken.of(plaintext), assurance, SessionPolicy.current()));
        }
        return plaintext;
    }

    private static String single(String sql) throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap(); PreparedStatement select = root.prepareStatement(sql);
                ResultSet rows = select.executeQuery()) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static void execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }
}
