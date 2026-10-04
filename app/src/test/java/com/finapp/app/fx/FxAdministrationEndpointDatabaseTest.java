package com.finapp.app.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.platform.api.IdempotencyKeyHeader;
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
 * The FX controller's doors over HTTP (`P9-TSK-007`, ADR-0075 section 3, {@code INV-AUD-04}): v1
 * proposed and activated by two {@code FX_CONTROLLER}s, the proposer's own approval refused, the
 * kill switch one person to stop and two to restart, an undeclared provider refused, and every door
 * closed to a session without the role and to no session at all.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the FX controller's doors (P9-TSK-007)")
class FxAdministrationEndpointDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @DisplayName("v1 is proposed by one controller and activated by another; the proposer's approval is"
            + " refused; a replayed proposal returns the same receipt")
    void v1IsActivatedByTwoControllers() throws Exception {
        String first = sessionWith(RoleName.FX_CONTROLLER);
        String second = sessionWith(RoleName.FX_CONTROLLER);
        withdrawPending(first);
        String key = someKey();
        HttpResponse<String> proposed =
                post("/v1/operator/fx/pricing-policies", PricingPolicyV1.json("pricing policy v1 (O7)"), first, key);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String id = field(proposed.body(), "id");
        assertThat(field(proposed.body(), "status")).isEqualTo("PROPOSED");
        HttpResponse<String> replayed =
                post("/v1/operator/fx/pricing-policies", PricingPolicyV1.json("pricing policy v1 (O7)"), first, key);
        assertThat(replayed.statusCode()).isEqualTo(201);
        assertThat(field(replayed.body(), "id")).as("the same key, the same proposal").isEqualTo(id);

        HttpResponse<String> self = post(approval(id), decision("my own"), first, null);
        assertThat(self.statusCode()).isEqualTo(409);
        assertThat(self.body()).contains("fx.SelfApprovalRefused");

        HttpResponse<String> approved = post(approval(id), decision("checked against O7"), second, null);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "status")).isEqualTo("ACTIVE");
        assertThat(post(approval(id), decision("checked against O7"), second, null).statusCode())
                .as("the approver's retry converges")
                .isEqualTo(200);

        HttpResponse<String> listed = get("/v1/operator/fx/pricing-policies", second);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains(id).contains("\"rateScale\":10").contains("\"band\":\"0.03\"");
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count = app.prepareStatement(
                        "SELECT count(*) FROM fx.pricing_pair WHERE policy_id = ?")) {
            count.setObject(1, UUID.fromString(id));
            try (ResultSet row = count.executeQuery()) {
                row.next();
                assertThat(row.getLong(1)).as("40 rows frozen").isEqualTo(40);
            }
        }
    }

    @Test
    @DisplayName("one controller stops a pair at once; enabling it needs a second controller; enabling"
            + " what is available is refused")
    void theKillSwitch() throws Exception {
        String first = sessionWith(RoleName.FX_CONTROLLER);
        String second = sessionWith(RoleName.FX_CONTROLLER);
        String path = "/v1/operator/fx/pairs/GBP-JPY/availability";

        HttpResponse<String> disabled = post(path, availability(false, "provider outage"), first, someKey());
        assertThat(disabled.statusCode()).as(disabled.body()).isEqualTo(200);
        assertThat(field(disabled.body(), "outcome")).isEqualTo("DISABLED");
        assertThat(field(post(path, availability(false, "again"), second, someKey()).body(), "outcome"))
                .isEqualTo("UNCHANGED");

        HttpResponse<String> proposed = post(path, availability(true, "recovered"), first, someKey());
        assertThat(field(proposed.body(), "outcome")).isEqualTo("ENABLE_PROPOSED");
        String request = field(proposed.body(), "requestId");
        HttpResponse<String> self = post(enableApproval(request), decision("mine"), first, null);
        assertThat(self.statusCode()).isEqualTo(409);
        assertThat(self.body()).contains("fx.SelfApprovalRefused");
        HttpResponse<String> approved = post(enableApproval(request), decision("verified"), second, null);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "status")).isEqualTo("APPROVED");

        HttpResponse<String> again = post(path, availability(true, "once more"), first, someKey());
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("fx.AlreadyAvailable");
    }

    @Test
    @DisplayName("an undeclared provider is 422, a malformed pair 404, an unknown request 404")
    void malformedSubjectsAreRefused() throws Exception {
        String controller = sessionWith(RoleName.FX_CONTROLLER);
        HttpResponse<String> undeclared = post("/v1/operator/fx/providers/fx-nope/availability",
                availability(false, "outage"), controller, someKey());
        assertThat(undeclared.statusCode()).isEqualTo(422);
        assertThat(undeclared.body()).contains("fx.ProviderNotDeclared");
        assertThat(post("/v1/operator/fx/pairs/EURUSD/availability", availability(false, "outage"), controller,
                        someKey()).statusCode())
                .isEqualTo(404);
        assertThat(post(enableApproval(UUID.randomUUID().toString()), decision("x"), controller, null).statusCode())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("every door is refused to another operator role (403) and to no session (401)")
    void theDoorsAreTheControllersAlone() throws Exception {
        String other = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        String id = UUID.randomUUID().toString();
        List<String[]> posts = List.of(
                new String[] {"/v1/operator/fx/pricing-policies", PricingPolicyV1.json("not mine")},
                new String[] {approval(id), decision("not mine")},
                new String[] {"/v1/operator/fx/pricing-policies/" + id + "/rejection", decision("not mine")},
                new String[] {"/v1/operator/fx/pairs/EUR-USD/availability", availability(false, "not mine")},
                new String[] {"/v1/operator/fx/providers/fx-sim-a/availability", availability(false, "not mine")},
                new String[] {enableApproval(id), decision("not mine")},
                new String[] {"/v1/operator/fx/enable-requests/" + id + "/rejection", decision("not mine")});
        assertThat(get("/v1/operator/fx/pricing-policies", other).statusCode()).isEqualTo(403);
        assertThat(get("/v1/operator/fx/pricing-policies", null).statusCode()).isEqualTo(401);
        for (String[] route : posts) {
            assertThat(post(route[0], route[1], other, someKey()).statusCode()).as("403 at %s", route[0])
                    .isEqualTo(403);
            assertThat(post(route[0], route[1], null, someKey()).statusCode()).as("401 at %s", route[0])
                    .isEqualTo(401);
        }
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count = app.prepareStatement(
                        "SELECT count(*) FROM fx.provider_availability WHERE provider_code = 'fx-sim-a'");
                ResultSet row = count.executeQuery()) {
            row.next();
            assertThat(row.getLong(1)).as("no refused caller wrote a fact").isZero();
        }
    }

    // -----------------------------------------------------------------

    /** Rejects any proposal another case left pending, so this case is free to propose. */
    private void withdrawPending(String controller) throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT id FROM fx.pricing_policy_version WHERE status = 'PROPOSED'");
                ResultSet row = select.executeQuery()) {
            if (row.next()) {
                assertThat(post("/v1/operator/fx/pricing-policies/" + row.getObject(1, UUID.class) + "/rejection",
                                decision("cleared by the next test case"), controller, null).statusCode())
                        .isEqualTo(200);
            }
        }
    }

    private static String approval(String id) {
        return "/v1/operator/fx/pricing-policies/" + id + "/approval";
    }

    private static String enableApproval(String id) {
        return "/v1/operator/fx/enable-requests/" + id + "/approval";
    }

    private static String decision(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private static String availability(boolean available, String reason) {
        return "{\"available\":" + available + ",\"reason\":\"" + reason + "\"}";
    }

    private String sessionWith(RoleName role) throws Exception {
        String login = "fx." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations",
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                                + "\"password\":\"" + PASSWORD + "\"}",
                        null, someKey()).statusCode())
                .isEqualTo(201);
        UUID identity;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement("SELECT id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                identity = row.getObject("id", UUID.class);
            }
        }
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        HttpResponse<String> session = post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}", null, someKey());
        return field(session.body(), "sessionToken");
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return send(request.build());
    }

    private HttpResponse<String> post(String path, String body, String bearer, String key) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }
}
