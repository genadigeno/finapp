package com.finapp.app.administration;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The staff directory over HTTP (`P17-TSK-014`): each of the four routes refused to a role without {@code IDENTITY_READ} - a
 * negative per route - the three that name or find people refused to a password session, and what they serve: identifier-first,
 * with the lookup's login identifier in the body and never in a URL, an audit record or a log line.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("the staff directory over HTTP (P17-TSK-014)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class StaffDirectoryApiDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final HttpClient http = HttpClient.newHttpClient();

    private record Person(UUID id, String login, String token) {}

    @Test
    @DisplayName("a negative per route: a role without IDENTITY_READ is refused 403 on the list, the detail, the lookup and the"
            + " catalogue")
    void aRoleWithoutTheReadIsRefusedOnEveryRoute() throws Exception {
        Person reviewer = person(RoleName.KYC_REVIEWER, AssuranceLevel.MULTI_FACTOR);

        assertThat(get(reviewer, "/v1/operator/identities").statusCode()).isEqualTo(403);
        assertThat(get(reviewer, "/v1/operator/identities/" + reviewer.id()).statusCode()).isEqualTo(403);
        assertThat(lookup(reviewer, reviewer.login()).statusCode()).isEqualTo(403);
        assertThat(get(reviewer, "/v1/operator/roles").statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("an administrator on a password session: the list, the detail and the lookup ask for a second factor; the"
            + " catalogue, which names nobody, does not")
    void thePeopleReadsAskForASecondFactor() throws Exception {
        Person admin = person(RoleName.ADMINISTRATOR, AssuranceLevel.PASSWORD);

        for (HttpResponse<String> refused : List.of(get(admin, "/v1/operator/identities"),
                get(admin, "/v1/operator/identities/" + admin.id()), lookup(admin, admin.login()))) {
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
            assertThat(refused.body()).contains("identity.AssuranceRequired");
        }
        assertThat(get(admin, "/v1/operator/roles").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("an administrator on a stepped-up session: the directory and the detail name nobody; the lookup answers the id,"
            + " or 404; the login identifier is in no URL, audit record or log line")
    void anAdministratorReadsIdentifierFirst(CapturedOutput output) throws Exception {
        Person admin = person(RoleName.ADMINISTRATOR, AssuranceLevel.MULTI_FACTOR);

        HttpResponse<String> page = get(admin, "/v1/operator/identities?role=ADMINISTRATOR&limit=200");
        assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
        assertThat(page.body()).startsWith("{\"items\":[").doesNotContain(admin.login()).doesNotContain("login");

        HttpResponse<String> detail = get(admin, "/v1/operator/identities/" + admin.id());
        assertThat(detail.statusCode()).as(detail.body()).isEqualTo(200);
        assertThat(detail.body())
                .contains("\"identityId\":\"" + admin.id() + "\"", "\"status\":\"ACTIVE\"", "\"factorEnrolled\":false",
                        "\"role\":\"ADMINISTRATOR\"")
                .doesNotContain(admin.login()).doesNotContain("login").doesNotContain("displayName")
                .doesNotContain("suspendedAt").doesNotContain("suspensionCause");

        HttpResponse<String> found = lookup(admin, admin.login());
        assertThat(found.statusCode()).as(found.body()).isEqualTo(200);
        assertThat(found.body()).isEqualTo("{\"identityId\":\"" + admin.id() + "\"}");
        HttpResponse<String> missing = lookup(admin, admin.login() + "x");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).contains("api.NotFound").doesNotContain(admin.login());

        assertThat(get(admin, "/v1/operator/identities?status=SLEEPING").statusCode()).isEqualTo(422);
        assertThat(get(admin, "/v1/operator/identities?role=WIZARD").statusCode()).isEqualTo(422);
        assertThat(get(admin, "/v1/operator/identities/not-an-id").statusCode()).isEqualTo(404);

        assertThat(recordedAbout(admin)).as("every recorded read of this run, and the identifier in none")
                .contains("identity.IdentityDirectoryRead", "identity.IdentityRead", "identity.IdentityLookedUp")
                .doesNotContain(admin.login());
        assertThat(output.getAll()).as("no log line carries the login identifier").doesNotContain(admin.login());
    }

    @Test
    @DisplayName("the role catalogue is RoleName itself: every role, what it grants, whether it needs an enrolled factor")
    void theCatalogueIsTheCodes() throws Exception {
        HttpResponse<String> catalogue = get(person(RoleName.ADMINISTRATOR, AssuranceLevel.MULTI_FACTOR), "/v1/operator/roles");

        assertThat(catalogue.statusCode()).isEqualTo(200);
        for (RoleName role : RoleName.values()) {
            assertThat(catalogue.body()).contains("{\"role\":\"" + role.name() + "\",\"permissions\":"
                    + Arrays.toString(role.permissions().stream().map(p -> "\"" + p.name() + "\"").sorted().toArray()).replace(" ", "")
                    + ",\"requiresEnrolledFactor\":" + role.requiresEnrolledFactor() + "}");
        }
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private HttpResponse<String> get(Person who, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + who.token()).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The lookup, as the console sends it: the identifier in the body of a POST to a fixed path. */
    private HttpResponse<String> lookup(Person who, String login) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/operator/identities/lookup"))
                .header("Authorization", "Bearer " + who.token()).header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"loginIdentifier\":\"" + login + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Every audit record this person wrote, as text: operation, target, summary, reason. */
    private static String recordedAbout(Person actor) throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap();
                PreparedStatement select = root.prepareStatement("SELECT string_agg(operation || ' ' || target_type || ' ' || target_id"
                        + " || ' ' || coalesce(change_summary, '') || ' ' || coalesce(reason, ''), E'\\n') FROM platform.audit_record"
                        + " WHERE actor_id = ?")) {
            select.setString(1, actor.id().toString());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return String.valueOf(rows.getString(1));
            }
        }
    }

    /** A role holder with a session at {@code assurance}, as the reconciliation suites make one; no factor enrolled. */
    private Person person(RoleName role, AssuranceLevel assurance) throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        String login = "sd" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'Staff Directory Person', now())",
                    party);
            execute(app, "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                    + " VALUES (?, ?, ?, 'ACTIVE', now(), now())", identity, party, login);
        }
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("p17-014-" + UUID.randomUUID())));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, com.finapp.identity.Session.issue(IDS, CLOCK, IdentityId.of(identity), SessionToken.of(plaintext),
                    assurance, SessionPolicy.current()));
        }
        return new Person(identity, login, plaintext);
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
