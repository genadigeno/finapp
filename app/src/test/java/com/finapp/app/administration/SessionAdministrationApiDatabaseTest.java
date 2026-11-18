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
 * Another identity's sessions over HTTP (`P17-TSK-015`): a negative per route; a password session refused at every route; the list
 * serving no token material; one session ended and its next request {@code 401} while the person's other session lives; all
 * ended without suspending; the administrator's own identity {@code 422}; a session that is not the identity's, or no session at
 * all, {@code 204} and untouched; a reason required.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("another identity's sessions over HTTP (P17-TSK-015)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class SessionAdministrationApiDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final String REASON = "{\"reason\":\"the holder reported the laptop lost\"}";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final HttpClient http = HttpClient.newHttpClient();

    private record Signed(IdentityId identity, String token, Session session) {}

    @Test
    @DisplayName("a negative per route: a role without SESSION_ADMINISTER is refused 403 on the list, the one and the all")
    void aRoleWithoutThePermissionIsRefused() throws Exception {
        Signed reviewer = signedIn(person(RoleName.KYC_REVIEWER), AssuranceLevel.MULTI_FACTOR);
        Signed holder = signedIn(person(null), AssuranceLevel.PASSWORD);
        String base = "/v1/operator/identities/" + holder.identity().value() + "/sessions";

        assertThat(send("GET", reviewer, base, null).statusCode()).isEqualTo(403);
        assertThat(send("DELETE", reviewer, base + "/" + holder.session().id().value(), REASON).statusCode()).isEqualTo(403);
        assertThat(send("DELETE", reviewer, base, REASON).statusCode()).isEqualTo(403);
        assertThat(live(holder)).isTrue();
    }

    @Test
    @DisplayName("an administrator on a password session is refused at every route: these are an incident's acts, not a password's")
    void aPasswordSessionIsRefused() throws Exception {
        Signed admin = signedIn(person(RoleName.ADMINISTRATOR), AssuranceLevel.PASSWORD);
        Signed holder = signedIn(person(null), AssuranceLevel.PASSWORD);
        String base = "/v1/operator/identities/" + holder.identity().value() + "/sessions";

        for (HttpResponse<String> refused : java.util.List.of(send("GET", admin, base, null),
                send("DELETE", admin, base + "/" + holder.session().id().value(), REASON), send("DELETE", admin, base, REASON))) {
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
            assertThat(refused.body()).contains("identity.AssuranceRequired");
        }
        assertThat(live(holder)).isTrue();
    }

    @Test
    @DisplayName("listed with no token material; one ended - its next request 401, the person's other session alive; then all"
            + " ended, the identity still active")
    void oneEndsThenAll() throws Exception {
        Signed admin = signedIn(person(RoleName.ADMINISTRATOR), AssuranceLevel.MULTI_FACTOR);
        IdentityId person = person(null);
        Signed laptop = signedIn(person, AssuranceLevel.PASSWORD);
        Signed phone = signedIn(person, AssuranceLevel.PASSWORD);
        String base = "/v1/operator/identities/" + person.value() + "/sessions";

        HttpResponse<String> listed = send("GET", admin, base, null);
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        assertThat(listed.body()).contains(laptop.session().id().value().toString(), phone.session().id().value().toString())
                .doesNotContain(laptop.token()).doesNotContain(phone.token()).doesNotContainIgnoringCase("token")
                .doesNotContainIgnoringCase("hash");

        HttpResponse<String> one = send("DELETE", admin, base + "/" + laptop.session().id().value(), REASON);
        assertThat(one.statusCode()).as(one.body()).isEqualTo(204);
        assertThat(send("GET", laptop, "/v1/sessions", null).statusCode()).as("the ended session's next request").isEqualTo(401);
        assertThat(send("GET", phone, "/v1/sessions", null).statusCode()).as("the person keeps the rest").isEqualTo(200);

        HttpResponse<String> all = send("DELETE", admin, base, REASON);
        assertThat(all.statusCode()).as(all.body()).isEqualTo(200);
        assertThat(all.body()).isEqualTo("{\"sessionsRevoked\":1}");
        assertThat(send("GET", phone, "/v1/sessions", null).statusCode()).isEqualTo(401);
        assertThat(status(person)).as("ending sessions is not a suspension").isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("the administrator's own identity 422; another's session named under this one, or none at all, 204 and untouched;"
            + " no reason refused; an unknown identity 404")
    void theRefusalsAndTheNonOracle() throws Exception {
        Signed admin = signedIn(person(RoleName.ADMINISTRATOR), AssuranceLevel.MULTI_FACTOR);
        Signed holder = signedIn(person(null), AssuranceLevel.PASSWORD);
        Signed stranger = signedIn(person(null), AssuranceLevel.PASSWORD);
        String base = "/v1/operator/identities/" + holder.identity().value() + "/sessions";

        HttpResponse<String> self = send("DELETE", admin, "/v1/operator/identities/" + admin.identity().value() + "/sessions", REASON);
        assertThat(self.statusCode()).isEqualTo(422);
        assertThat(self.body()).contains("api.ValidationFailed");
        assertThat(send("DELETE", admin, base + "/" + stranger.session().id().value(), REASON).statusCode()).isEqualTo(204);
        assertThat(send("DELETE", admin, base + "/not-a-session", REASON).statusCode()).isEqualTo(204);
        assertThat(live(stranger)).as("a session that is not the named identity's ends nothing").isTrue();
        assertThat(live(holder)).isTrue();
        assertThat(send("DELETE", admin, base + "/" + holder.session().id().value(), "{}").statusCode()).isEqualTo(422);
        assertThat(live(holder)).as("no reason, nothing ended").isTrue();
        assertThat(send("GET", admin, "/v1/operator/identities/" + IdentityId.next(IDS).value() + "/sessions", null).statusCode())
                .isEqualTo(404);
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private HttpResponse<String> send(String method, Signed who, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + who.token()).header("Accept", "application/json");
        if (body != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private boolean live(Signed who) throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            return sessions.findLive(app, SessionToken.of(who.token())).isPresent();
        }
    }

    private static String status(IdentityId identity) throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap();
                PreparedStatement select = root.prepareStatement("SELECT status FROM identity.identity WHERE id = ?")) {
            select.setObject(1, identity.value());
            try (var rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    /** An identity, holding {@code role} when one is named. */
    private IdentityId person(RoleName role) throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement partyRow = app.prepareStatement(
                        "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'Session Door Person', now())");
                PreparedStatement identityRow = app.prepareStatement(
                        "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                                + " VALUES (?, ?, ?, 'ACTIVE', now(), now())")) {
            partyRow.setObject(1, party);
            partyRow.executeUpdate();
            identityRow.setObject(1, identity);
            identityRow.setObject(2, party);
            identityRow.setString(3, "sd" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
            identityRow.executeUpdate();
        }
        if (role != null) {
            try (CorrelationContext.Scope correlation = CorrelationContext.enter(
                            Correlation.startingWith(CorrelationId.of("p17-015-" + UUID.randomUUID())));
                    SecurityContext.Scope actor = SecurityContext.enterSystem();
                    Connection app = DatabaseRoles.application()) {
                app.setAutoCommit(false);
                authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
                app.commit();
            }
        }
        return IdentityId.of(identity);
    }

    private Signed signedIn(IdentityId identity, AssuranceLevel assurance) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try (Connection app = DatabaseRoles.application()) {
            Session session = sessions.insert(app, Session.issue(IDS, CLOCK, identity, SessionToken.of(plaintext), assurance,
                    SessionPolicy.current()));
            return new Signed(identity, plaintext, session);
        }
    }
}
