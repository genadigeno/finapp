package com.finapp.app.audit;

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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The audit trail over HTTP (`P17-TSK-017`): a negative for every role but the auditor's - {@code ADMINISTRATOR} among them, the
 * owner's decision - on both routes (the console plan's §19 criterion 7); the auditor refused on a password session; the window
 * required and bounded; the cursor; and every page and record read itself recorded as {@code audit.RecordsRead}.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the audit trail over HTTP (P17-TSK-017)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class AuditTrailApiDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final String WINDOW = "from=2098-05-01T00:00:00Z&to=2098-05-02T00:00:00Z";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("a negative for every other role, ADMINISTRATOR among them: 403 on the list and on one record")
    void everyOtherRoleIsRefused() throws Exception {
        for (RoleName role : RoleName.values()) {
            if (role == RoleName.AUDITOR) {
                continue;
            }
            String token = session(identity(role), AssuranceLevel.MULTI_FACTOR);
            assertThat(get(token, "/v1/operator/audit-records?" + WINDOW).statusCode()).as("%s on the list", role).isEqualTo(403);
            assertThat(get(token, "/v1/operator/audit-records/" + UUID.randomUUID()).statusCode()).as("%s on a record", role)
                    .isEqualTo(403);
        }
    }

    @Test
    @DisplayName("the auditor on a password session is refused: the trail is behind a second factor")
    void aPasswordSessionIsRefused() throws Exception {
        String token = session(identity(RoleName.AUDITOR), AssuranceLevel.PASSWORD);

        HttpResponse<String> refused = get(token, "/v1/operator/audit-records?" + WINDOW);
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.body()).contains("identity.AssuranceRequired");
    }

    @Test
    @DisplayName("the window is required and bounded: none, one end, a reversed one or one past 31 days is refused 422")
    void theWindowIsRequiredAndBounded() throws Exception {
        String token = session(identity(RoleName.AUDITOR), AssuranceLevel.MULTI_FACTOR);

        for (String query : new String[] {"", "?from=2098-05-01T00:00:00Z", "?to=2098-05-01T00:00:00Z",
                "?from=2098-05-02T00:00:00Z&to=2098-05-01T00:00:00Z", "?from=2098-01-01T00:00:00Z&to=2098-03-01T00:00:00Z",
                "?from=yesterday&to=2098-05-01T00:00:00Z", "?" + WINDOW + "&outcome=MAYBE", "?" + WINDOW + "&limit=201"}) {
            HttpResponse<String> refused = get(token, "/v1/operator/audit-records" + query);
            assertThat(refused.statusCode()).as("%s: %s", query, refused.body()).isEqualTo(422);
            assertThat(refused.body()).contains("api.ValidationFailed");
        }
    }

    @Test
    @DisplayName("the auditor walks a window by its cursor, newest first, reads one record, and every read is itself recorded")
    void theAuditorReadsAndIsRecorded() throws Exception {
        IdentityId auditor = identity(RoleName.AUDITOR);
        String token = session(auditor, AssuranceLevel.MULTI_FACTOR);
        String flow = "p17-017-api-" + UUID.randomUUID();
        UUID oldest = null;
        try (Connection root = DatabaseRoles.bootstrap()) {
            for (int i = 0; i < 5; i++) {
                UUID id = UUID.randomUUID();
                try (PreparedStatement insert = root.prepareStatement("INSERT INTO platform.audit_record (audit_id, actor_id,"
                        + " actor_type, occurred_at, operation, target_type, target_id, reason, outcome, correlation_id, change_summary)"
                        + " VALUES (?, 'p17-017-api', 'EMPLOYEE', ?, 'probe.Api', 'probe.Target', 'api', 'a reason', 'SUCCEEDED', ?, 'n')")) {
                    insert.setObject(1, id);
                    insert.setTimestamp(2, Timestamp.from(Instant.parse("2098-05-01T10:00:00Z").plusSeconds(i)));
                    insert.setString(3, flow);
                    insert.executeUpdate();
                }
                if (i == 0) {
                    oldest = id;
                }
            }
        }
        String filter = "/v1/operator/audit-records?" + WINDOW + "&correlationId=" + flow + "&limit=2";

        HttpResponse<String> first = get(token, filter);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(first.body()).contains("\"nextCursor\":\"").contains("2098-05-01T10:00:04Z");
        String cursor = field(first.body(), "nextCursor");
        HttpResponse<String> second = get(token, filter + "&cursor=" + cursor);
        HttpResponse<String> third = get(token, filter + "&cursor=" + field(second.body(), "nextCursor"));
        assertThat(third.statusCode()).isEqualTo(200);
        assertThat(third.body()).contains(oldest.toString()).doesNotContain("nextCursor");

        HttpResponse<String> one = get(token, "/v1/operator/audit-records/" + oldest);
        assertThat(one.statusCode()).as(one.body()).isEqualTo(200);
        assertThat(one.body()).contains("\"reason\":\"a reason\"", "\"actorType\":\"EMPLOYEE\"");
        assertThat(get(token, "/v1/operator/audit-records/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(get(token, "/v1/operator/audit-records/not-an-id").statusCode()).isEqualTo(404);

        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE actor_id = '" + auditor.value()
                + "' AND operation = 'audit.RecordsRead'")).as("three pages and one record, each a read").isEqualTo(4);
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private HttpResponse<String> get(String token, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(body);
        assertThat(matcher.find()).as("%s in %s", name, body).isTrue();
        return matcher.group(1);
    }

    /** A role holder with a confirmed factor, as the fixtures grant. */
    private IdentityId identity(RoleName role) throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'Audit Door Person', now())", party);
            execute(app, "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                    + " VALUES (?, ?, ?, 'ACTIVE', now(), now())", identity, party,
                    "ad" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
            execute(app, "INSERT INTO identity.mfa_enrolment (id, identity_id, type, secret_ciphertext, secret_nonce, key_version,"
                    + " algorithm, digits, period_seconds, status, created_at, confirmed_at)"
                    + " VALUES (?, ?, 'TOTP', ?, ?, 1, 'SHA1', 6, 30, 'ACTIVE', now(), now())", IDS.next(), identity, new byte[32], new byte[12]);
        }
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("p17-017-" + UUID.randomUUID())));
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

    private static long count(String sql) throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap(); PreparedStatement select = root.prepareStatement(sql);
                ResultSet rows = select.executeQuery()) {
            rows.next();
            return rows.getLong(1);
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
