package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.api.OperatorCollection;
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
import java.nio.charset.StandardCharsets;
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
 * The two operator lists over HTTP (`P17-TSK-107`; `API_CONVENTIONS.md` section 7, finapp-admin-panel ADR-C133): each under the
 * permission the existing reads of its records use - a role without it refused {@code 403} by the interceptor - and each refusing
 * what section 7 refuses, {@code 422} with nothing read: a limit over its maximum or under one, a cursor it did not write (another
 * collection's included), a window it does not serve, a status the queue does not hold. The last page carries no {@code nextCursor}
 * at all. The order and the keyset walk are the stores' suites' (`BatchBrowseDatabaseTest`, `PendingResolutionQueueDatabaseTest`);
 * the caller's mark is `PendingResolutionViewTest`'s.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the settlement batch browse and the approver's queue over HTTP (P17-TSK-107)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class OperatorListsApiDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final String BATCHES = "/v1/operator/settlement/batches";
    private static final String QUEUE = "/v1/operator/reconciliation/resolutions";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("a role without the reads' permission is refused 403 on both: the batches need RECONCILIATION_INVESTIGATE, the queue"
            + " RECONCILIATION_RESOLVE, and RECONCILIATION_CONTROLLER holds neither")
    void aRoleWithoutThePermissionIsRefused() throws Exception {
        String controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);

        assertThat(get(controller, BATCHES).statusCode()).isEqualTo(403);
        assertThat(get(controller, QUEUE).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("the batches: a window holding none is an empty page with no nextCursor at all")
    void anEmptyWindowIsTheLastPage() throws Exception {
        HttpResponse<String> page = get(sessionWith(RoleName.RECONCILIATION_OPERATOR), BATCHES + "?from=1985-01-01&to=1985-01-31");

        assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
        assertThat(page.body()).isEqualTo("{\"items\":[]}");
    }

    @Test
    @DisplayName("the batches refuse 422: a limit outside 1..200, a cursor they did not write, a reversed or over-wide window, an"
            + " unknown source")
    void theBatchesRefuseWhatSectionSevenRefuses() throws Exception {
        String operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        String queueCursor = OperatorCollection.cursor("reconciliation.resolutions", "1", UUID.randomUUID().toString());

        for (String query : new String[] {"?limit=201", "?limit=0", "?limit=ten", "?cursor=not-a-cursor", "?cursor=" + queueCursor,
                "?from=2026-02-01&to=2026-01-01", "?from=2026-01-01&to=2026-12-31", "?from=yesterday"}) {
            HttpResponse<String> refused = get(operator, BATCHES + query);
            assertThat(refused.statusCode()).as("%s: %s", query, refused.body()).isEqualTo(422);
            assertThat(refused.body()).as(query).contains("api.ValidationFailed");
        }
        HttpResponse<String> unknown = get(operator, BATCHES + "?source=no-such.source");
        assertThat(unknown.statusCode()).isEqualTo(422);
        assertThat(unknown.body()).contains("settlement.SourceUnknown");
    }

    @Test
    @DisplayName("the queue: served in its shape to a holder of RECONCILIATION_RESOLVE; refused 422 for a status it does not hold,"
            + " a limit outside 1..200 or a cursor it did not write")
    void theQueueRefusesWhatSectionSevenRefuses() throws Exception {
        String operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        String batchesCursor = OperatorCollection.cursor("settlement.batches", "2026-11-18", UUID.randomUUID().toString());

        HttpResponse<String> served = get(operator, QUEUE + "?status=PROPOSED&limit=200");
        assertThat(served.statusCode()).as(served.body()).isEqualTo(200);
        assertThat(served.body()).startsWith("{\"items\":[");
        for (String query : new String[] {"?status=APPROVED", "?limit=1000", "?limit=0", "?cursor=" + batchesCursor,
                "?cursor=" + Base64.getUrlEncoder().withoutPadding().encodeToString("reconciliation.resolutions|v1|soon|x".getBytes(StandardCharsets.UTF_8))}) {
            HttpResponse<String> refused = get(operator, QUEUE + query);
            assertThat(refused.statusCode()).as("%s: %s", query, refused.body()).isEqualTo(422);
            assertThat(refused.body()).as(query).contains("api.ValidationFailed");
        }
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private HttpResponse<String> get(String token, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A role holder's {@code MULTI_FACTOR} session, as the reconciliation suites make one. */
    private String sessionWith(RoleName role) throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'Operator Lists Person', now())",
                    party);
            execute(app, "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                    + " VALUES (?, ?, ?, 'ACTIVE', now(), now())",
                    identity, party, "ol" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("p17-107-" + UUID.randomUUID())));
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
                    AssuranceLevel.MULTI_FACTOR, SessionPolicy.current()));
        }
        return plaintext;
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
