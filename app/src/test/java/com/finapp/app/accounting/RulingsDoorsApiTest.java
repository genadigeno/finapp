package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
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
import java.time.Clock;
import java.util.List;
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
 * The doors `P14-TSK-022` changed, over HTTP: who may knock, at what assurance, and what the body takes. OWED (C5): written and walked by hand, not yet run. What the acts do is {@code PriorPeriodAdjustmentDatabaseTest}'s and {@code StatementsDatabaseTest}'s against a real database; the boundary is
 * {@code FinalStatementDeskTest}'s, {@code PeriodCloseDeskTest}'s and {@code ApprovalAssuranceTest}'s, hermetic. Nothing here needs a period to exist: each case knocks on an id that is nothing, so it holds in any order against whatever other suites left, and the password sessions are this suite's own registrations.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the doors the owner's rulings changed, over HTTP (P14-TSK-022)")
class RulingsDoorsApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String BASE = "/v1/operator/accounting";
    private static final String REASON = "{\"reason\":\"read and decided\"}";
    private static final Pattern SESSION_TOKEN = Pattern.compile("\"sessionToken\"\\s*:\\s*\"([^\"]+)\"");

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("(r) a prior-period adjustment's approval is MULTI_FACTOR: the controller on a password session is 403 identity.AssuranceRequired before the domain, while its read, its list and its rejection reach it")
    void thePriorPeriodApprovalIsMultiFactor() throws Exception {
        String controller = sessionWith(RoleName.FINANCIAL_CONTROLLER);
        String adjustment = BASE + "/prior-period-adjustments/" + IDS.next();
        String approval = "{\"postingDate\":\"1999-02-10\",\"correctedRevision\":1,\"postedCorrections\":[],\"reason\":\"read and decided\"}";

        HttpResponse<String> refused = post(adjustment + "/approval", approval, controller, key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).contains("identity.AssuranceRequired");

        assertThat(get(adjustment, controller).statusCode()).as("the read is not stepped up").isEqualTo(404);
        assertThat(post(adjustment + "/rejection", REASON, controller, key()).statusCode()).as("nor the rejection").isEqualTo(404);
        assertThat(get(BASE + "/prior-period-adjustments", controller).statusCode()).as("nor the list").isEqualTo(200);
    }

    @Test
    @DisplayName("(v) the final statement's door: 401 without a session, 403 to a session holding no role, to the accountant and to the ledger's operator; to the controller an unknown or malformed run is the one 404")
    void theFinalStatementsDoor() throws Exception {
        String run = BASE + "/report-runs/" + IDS.next() + "/final-statement";
        assertThat(get(run, null).statusCode()).isEqualTo(401);
        for (RoleName notTheController : new RoleName[] {null, RoleName.ACCOUNTANT, RoleName.LEDGER_OPERATOR}) {
            assertThat(get(run, sessionWith(notTheController)).statusCode()).as(String.valueOf(notTheController)).isEqualTo(403);
        }
        String controller = sessionWith(RoleName.FINANCIAL_CONTROLLER);
        for (String id : List.of(IDS.next().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> answer = get(BASE + "/report-runs/" + id + "/final-statement", controller);
            assertThat(answer.statusCode()).as(id + ": " + answer.body()).isEqualTo(404);
            assertThat(answer.body()).contains("accounting.NotFound");
        }
    }

    @Test
    @DisplayName("(m) a reopen's body: a null among the names is 422 at the door, never a 500; a field it does not declare is 422; an act without a key is 422; well formed, naming or not, an unknown period is 404")
    void aReopensBody() throws Exception {
        String accountant = sessionWith(RoleName.ACCOUNTANT);
        String proposal = BASE + "/periods/" + IDS.next() + "/reopen-proposal";

        HttpResponse<String> aNull = post(proposal, "{\"reason\":\"a correction\",\"postedAdjustments\":[null]}", accountant, key());
        assertThat(aNull.statusCode()).as(aNull.body()).isEqualTo(422);
        HttpResponse<String> undeclared = post(proposal, "{\"reason\":\"a correction\",\"approver\":\"me\"}", accountant, key());
        assertThat(undeclared.statusCode()).as("a closed body: " + undeclared.body()).isEqualTo(422);
        HttpResponse<String> unkeyed = post(proposal, "{\"reason\":\"a correction\",\"postedAdjustments\":[]}", accountant, null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");

        for (String body : List.of("{\"reason\":\"a correction\"}", "{\"reason\":\"a correction\",\"postedAdjustments\":[\"" + IDS.next() + "\"]}")) {
            HttpResponse<String> unknown = post(proposal, body, accountant, key());
            assertThat(unknown.statusCode()).as(unknown.body()).isEqualTo(404);
            assertThat(unknown.body()).contains("accounting.NotFound");
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static String key() {
        return "rulings-" + IDS.next();
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String bearer, String idempotencyKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A signed-in password session holding {@code role}, or no role at all when it is {@code null}. */
    private String sessionWith(RoleName role) throws Exception {
        String login = "rl." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\",\"password\":\"" + PASSWORD + "\"}", null, key()).statusCode()).isEqualTo(201);
        if (role != null) {
            UUID identity;
            try (Connection app = DatabaseRoles.application();
                    PreparedStatement read = app.prepareStatement("SELECT id FROM identity.identity WHERE login_identifier = ?")) {
                read.setString(1, login);
                try (ResultSet row = read.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    identity = row.getObject("id", UUID.class);
                }
            }
            try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                    SecurityContext.Scope actor = SecurityContext.enterSystem();
                    Connection app = DatabaseRoles.application()) {
                app.setAutoCommit(false);
                authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
                app.commit();
            }
        }
        HttpResponse<String> session = post("/v1/authentications", "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}", null, key());
        Matcher token = SESSION_TOKEN.matcher(session.body());
        assertThat(token.find()).as(session.body()).isTrue();
        return token.group(1);
    }
}
