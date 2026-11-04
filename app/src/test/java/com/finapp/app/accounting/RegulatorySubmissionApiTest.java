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
import java.time.Duration;
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
 * The regulatory doors' contract over HTTP (`P14-TSK-018`; {@code LoanWriteOffApiTest}'s shape): who may knock, and at what assurance - the negative of every door. OWED (C5): written and walked by hand, not yet run. What the acts do is {@code RegulatorySubmissionDatabaseTest}'s, against a real database;
 * the boundary's parse and receipts are {@code RegulatorySubmissionDeskTest}'s and {@code RegulatoryObligationDeskTest}'s, hermetic. Nothing here reaches a period or commits an accounting row: every door is refused before the domain, or finds nothing under an id that exists nowhere, so it needs no container of its own.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the regulatory doors' contract (P14-TSK-018)")
class RegulatorySubmissionApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String SUBMISSIONS = "/v1/operator/regulatory/submissions";
    private static final String OBLIGATIONS = "/v1/operator/regulatory/obligations";
    private static final Pattern TOKEN = Pattern.compile("\"sessionToken\"\\s*:\\s*\"([^\"]+)\"");
    private static final String DRAFT = "{\"obligationId\":\"x\",\"periodId\":\"y\",\"reason\":\"a reason\"}";
    private static final String ATTESTATION = "{\"contentSha256\":\"h\",\"periodRevision\":1,\"reason\":\"a reason\"}";
    private static final String REASON = "{\"reason\":\"a reason\"}";
    private static final String OBLIGATION = "{\"jurisdictionCode\":\"REF\",\"formCode\":\"REF-API\",\"formVersion\":1,\"adapterName\":\"simulated-reference\",\"sources\":[],\"lines\":[],\"rules\":[],\"reason\":\"a reason\"}";
    private static final String ACTIVATION = "{\"configurationSha256\":\"h\",\"reason\":\"a reason\"}";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    /** Every door: method, path, body. */
    private record Door(String method, String path, String body) {}

    private static List<Door> preparerDoors(String id) {
        return List.of(new Door("POST", SUBMISSIONS, DRAFT), new Door("GET", SUBMISSIONS, null), new Door("GET", SUBMISSIONS + "/" + id, null), new Door("GET", SUBMISSIONS + "/" + id + "/reference-export", null),
                new Door("POST", SUBMISSIONS + "/" + id + "/validation", null), new Door("POST", SUBMISSIONS + "/" + id + "/withdrawal", REASON),
                new Door("POST", OBLIGATIONS, OBLIGATION), new Door("GET", OBLIGATIONS, null), new Door("GET", OBLIGATIONS + "/" + id, null));
    }

    private static List<Door> controllerDoors(String id) {
        return List.of(new Door("GET", SUBMISSIONS + "/awaiting-approval", null), new Door("GET", SUBMISSIONS + "/" + id + "/attestation", null), new Door("POST", SUBMISSIONS + "/" + id + "/approval", ATTESTATION),
                new Door("POST", SUBMISSIONS + "/" + id + "/refusal", REASON), new Door("GET", OBLIGATIONS + "/awaiting-activation", null), new Door("GET", OBLIGATIONS + "/" + id + "/attestation", null),
                new Door("POST", OBLIGATIONS + "/" + id + "/activation", ACTIVATION), new Door("POST", OBLIGATIONS + "/" + id + "/rejection", REASON));
    }

    private static List<Door> senderDoors(String id) {
        return List.of(new Door("POST", SUBMISSIONS + "/" + id + "/submission", ATTESTATION), new Door("POST", SUBMISSIONS + "/" + id + "/non-delivery", REASON));
    }

    @Test
    @DisplayName("every door is 401 without a session and 403 to a session holding no role, and to an accountant: ACCOUNTING_READ opens none of them")
    void everyDoorIsClosed() throws Exception {
        String id = IDS.next().toString();
        String noRole = sessionWith(null);
        String accountant = sessionWith(RoleName.ACCOUNTANT);
        for (List<Door> doors : List.of(preparerDoors(id), controllerDoors(id), senderDoors(id))) {
            for (Door door : doors) {
                assertThat(knock(door, null).statusCode()).as(door.toString()).isEqualTo(401);
                assertThat(knock(door, noRole).statusCode()).as(door.toString()).isEqualTo(403);
                assertThat(knock(door, accountant).statusCode()).as(door.toString()).isEqualTo(403);
            }
        }
    }

    @Test
    @DisplayName("the reporter opens no controller's door, and the controller no reporter's or sender's: four eyes in the routes too")
    void noRoleCrossesToTheOther() throws Exception {
        String id = IDS.next().toString();
        String reporter = sessionWith(RoleName.REGULATORY_REPORTER);
        String controller = sessionWith(RoleName.FINANCIAL_CONTROLLER);
        for (Door door : controllerDoors(id)) {
            assertThat(knock(door, reporter).statusCode()).as(door.toString()).isEqualTo(403);
        }
        for (Door door : preparerDoors(id)) {
            assertThat(knock(door, controller).statusCode()).as(door.toString()).isEqualTo(403);
        }
        for (Door door : senderDoors(id)) {
            assertThat(knock(door, controller).statusCode()).as(door.toString()).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("neither reader's role opens the run's drill-down the lineage names: what a view says of them is true")
    void aReadersRoleOpensNoDrillDown() throws Exception {
        String run = IDS.next().toString();
        for (String token : List.of(sessionWith(RoleName.REGULATORY_REPORTER), sessionWith(RoleName.FINANCIAL_CONTROLLER))) {
            assertThat(knock(new Door("GET", "/v1/operator/accounting/report-runs/" + run, null), token).statusCode()).isEqualTo(403);
            assertThat(knock(new Door("GET", "/v1/operator/accounting/report-runs/" + run + "/lines?account=1000&currency=EUR&month=2026-01", null), token).statusCode()).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("the attestation and the sending are MULTI_FACTOR: on a password session, 403 identity.AssuranceRequired before the domain, while the reads and the refusal reach it")
    void theDecisionsAreMultiFactor() throws Exception {
        String id = IDS.next().toString();
        String controller = sessionWith(RoleName.FINANCIAL_CONTROLLER);
        String reporter = sessionWith(RoleName.REGULATORY_REPORTER);
        for (Door door : List.of(new Door("POST", SUBMISSIONS + "/" + id + "/approval", ATTESTATION), new Door("POST", OBLIGATIONS + "/" + id + "/activation", ACTIVATION))) {
            HttpResponse<String> stepped = knock(door, controller);
            assertThat(stepped.statusCode()).as(stepped.body()).isEqualTo(403);
            assertThat(stepped.body()).contains("identity.AssuranceRequired");
        }
        for (Door door : senderDoors(id)) {
            HttpResponse<String> stepped = knock(door, reporter);
            assertThat(stepped.statusCode()).as(stepped.body()).isEqualTo(403);
            assertThat(stepped.body()).contains("identity.AssuranceRequired");
        }
        assertThat(knock(new Door("GET", SUBMISSIONS + "/" + id + "/attestation", null), controller).statusCode()).isEqualTo(404);
        assertThat(knock(new Door("POST", SUBMISSIONS + "/" + id + "/refusal", REASON), controller).statusCode()).isEqualTo(404);
        assertThat(knock(new Door("GET", SUBMISSIONS + "/awaiting-approval", null), controller).statusCode()).isEqualTo(200);
        assertThat(knock(new Door("GET", SUBMISSIONS, null), reporter).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("to the reporter: an act without a key or with a field the body does not declare is 422 before the domain; a null in a list is 422, never a 500; an unknown or malformed id the uniform 404")
    void theKeyTheBodyAndTheUniformNotFound() throws Exception {
        String reporter = sessionWith(RoleName.REGULATORY_REPORTER);
        HttpResponse<String> unkeyed = send("POST", SUBMISSIONS, DRAFT, reporter, null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
        assertThat(knock(new Door("POST", SUBMISSIONS, DRAFT.replace("}", ",\"preparedBy\":\"me\"}")), reporter).statusCode()).as("a closed body").isEqualTo(422);
        assertThat(knock(new Door("POST", OBLIGATIONS, OBLIGATION.replace("\"sources\":[]", "\"sources\":[null]")), reporter).statusCode()).as("a null in a list").isEqualTo(422);
        for (String id : List.of(IDS.next().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> read = knock(new Door("GET", SUBMISSIONS + "/" + id, null), reporter);
            assertThat(read.statusCode()).as(id + ": " + read.body()).isEqualTo(404);
            assertThat(read.body()).contains("accounting.NotFound");
        }
    }

    // ------------------------------------------------------------------ plumbing

    private HttpResponse<String> knock(Door door, String token) throws Exception {
        return send(door.method(), door.path(), door.body(), token, UUID.randomUUID().toString());
    }

    private HttpResponse<String> send(String method, String path, String body, String bearer, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if ("GET".equals(method)) {
            request.GET();
        } else if (body == null) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** A signed-in password session holding {@code role}, or no role at all when it is {@code null}. */
    private String sessionWith(RoleName role) throws Exception {
        String login = "reg." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(send("POST", "/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\",\"password\":\"" + PASSWORD + "\"}", null, UUID.randomUUID().toString()).statusCode()).isEqualTo(201);
        if (role != null) {
            UUID identity;
            try (Connection app = DatabaseRoles.application(); PreparedStatement read = app.prepareStatement("SELECT id FROM identity.identity WHERE login_identifier = ?")) {
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
        HttpResponse<String> session = send("POST", "/v1/authentications", "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}", null, UUID.randomUUID().toString());
        Matcher token = TOKEN.matcher(session.body());
        assertThat(token.find()).as(session.body()).isTrue();
        return token.group(1);
    }
}
