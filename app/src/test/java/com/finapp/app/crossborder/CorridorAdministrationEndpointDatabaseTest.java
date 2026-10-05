package com.finapp.app.crossborder;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.crossborder.CorridorAvailability;
import com.finapp.crossborder.CorridorPolicyAdministration;
import com.finapp.crossborder.TransactionRunner;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
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
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The corridor doors over HTTP (`P9-TSK-015`, ADR-0080 section 4, {@code INV-AUD-04}): v1 per O7 proposed
 * and activated by two {@code FX_CONTROLLER}s with {@code CROSSBORDER_ADMINISTER}, the proposer's own
 * approval refused, its fees and limits frozen at each currency's scale; discovery offering exactly the
 * active, available corridors a declared rail carries; the kill switch one person to stop and two to
 * restart; an undeclared rail and unsatisfiable data refused; and every door closed to a session without
 * the role and to no session at all. No corridor is offered without two named persons.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the corridor doors and discovery (P9-TSK-015)")
class CorridorAdministrationEndpointDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String POLICIES = "/v1/operator/cross-border/corridor-policies";
    private static final String DISCOVERY = "/v1/me/cross-border/corridors";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private CorridorPolicyAdministration corridorPolicyAdministration;
    @Autowired private CorridorAvailability corridorAvailability;
    @Autowired private IdempotentExecutor idempotentExecutor;
    @Autowired private TransactionRunner crossborderTransactionRunner;

    @Test
    @Order(1)
    @DisplayName("nothing is offered before two persons act: v1 proposed by one controller, refused to its own"
            + " approval, activated by another - and only then discovered, with O7's fees and limits")
    void v1IsActivatedByTwoControllersThenOffered() throws Exception {
        String first = sessionWith(RoleName.FX_CONTROLLER);
        String second = sessionWith(RoleName.FX_CONTROLLER);
        String customer = sessionWith(null);
        withdrawPending(first);
        String key = someKey();
        HttpResponse<String> proposed = post(POLICIES, CorridorPolicyV1.json("corridor policy v1 (O7)"), first, key);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String id = field(proposed.body(), "id");
        assertThat(post(POLICIES, CorridorPolicyV1.json("corridor policy v1 (O7)"), first, key).body())
                .as("the same key, the same proposal").contains(id);
        if (!activeExists()) {
            assertThat(get(DISCOVERY, customer).body()).as("a proposal offers nothing").contains("\"corridors\":[]");
        }

        HttpResponse<String> self = post(approval(id), decision("my own"), first, null);
        assertThat(self.statusCode()).isEqualTo(409);
        assertThat(self.body()).contains("crossborder.SelfApprovalRefused");
        HttpResponse<String> approved = post(approval(id), decision("checked against O7"), second, null);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "status")).isEqualTo("ACTIVE");
        assertThat(post(approval(id), decision("checked against O7"), second, null).statusCode())
                .as("the approver's retry converges").isEqualTo(200);

        HttpResponse<String> listed = get(POLICIES, second);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains(id).contains("\"maximum\":\"1500000\"").contains("\"maximum\":\"4000.000\"");
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(
                        "SELECT source_currency || '-' || destination_currency || '-' || destination_country || ':'"
                                + " || fee_fixed_minor || ':' || maximum_minor || ':' || screening_validity_hours"
                                + " FROM crossborder.corridor WHERE policy_id = ? ORDER BY 1")) {
            read.setObject(1, UUID.fromString(id));
            List<String> rows = new java.util.ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    rows.add(row.getString(1));
                }
            }
            assertThat(rows).as("O7 in minor units of each side's own scale").containsExactly(
                    "EUR-JPY-JP:250:1500000:168",
                    "EUR-USD-US:250:1000000:168",
                    "GBP-USD-US:200:1000000:168",
                    "USD-BHD-BH:300:4000000:168");
        }

        HttpResponse<String> offered = get(DISCOVERY, customer);
        assertThat(offered.statusCode()).as(offered.body()).isEqualTo(200);
        assertThat(offered.body())
                .contains("\"corridor\":\"EUR-USD-US\"", "\"corridor\":\"EUR-JPY-JP\"", "\"corridor\":\"USD-BHD-BH\"",
                        "\"corridor\":\"GBP-USD-US\"")
                .contains("\"feeFixed\":\"2.50\"", "\"feeFixed\":\"3.00\"", "\"maximum\":\"4000.000\"")
                .doesNotContain("corridor-sim-a")
                .as("the customer sees no rail");
        assertThat(get(DISCOVERY, null).statusCode()).as("discovery needs a session").isEqualTo(401);
    }

    @Test
    @Order(2)
    @DisplayName("one controller stops a corridor at once - it leaves discovery - and restarting it needs a"
            + " second controller")
    void theKillSwitch() throws Exception {
        String first = sessionWith(RoleName.FX_CONTROLLER);
        String second = sessionWith(RoleName.FX_CONTROLLER);
        String customer = sessionWith(null);
        String path = "/v1/operator/cross-border/corridors/GBP-USD-US/availability";

        HttpResponse<String> disabled = post(path, availability(false, "provider outage"), first, someKey());
        assertThat(disabled.statusCode()).as(disabled.body()).isEqualTo(200);
        assertThat(field(disabled.body(), "outcome")).isEqualTo("DISABLED");
        assertThat(get(DISCOVERY, customer).body()).doesNotContain("GBP-USD-US").contains("EUR-USD-US");
        assertThat(field(post(path, availability(false, "again"), second, someKey()).body(), "outcome"))
                .isEqualTo("UNCHANGED");

        HttpResponse<String> proposed = post(path, availability(true, "recovered"), first, someKey());
        assertThat(field(proposed.body(), "outcome")).isEqualTo("ENABLE_PROPOSED");
        String request = field(proposed.body(), "requestId");
        assertThat(get(DISCOVERY, customer).body()).as("a proposal enables nothing").doesNotContain("GBP-USD-US");
        HttpResponse<String> self = post(enableApproval(request), decision("mine"), first, null);
        assertThat(self.statusCode()).isEqualTo(409);
        assertThat(self.body()).contains("crossborder.SelfApprovalRefused");
        HttpResponse<String> approved = post(enableApproval(request), decision("verified"), second, null);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "status")).isEqualTo("APPROVED");
        assertThat(get(DISCOVERY, customer).body()).contains("GBP-USD-US");

        HttpResponse<String> again = post(path, availability(true, "once more"), first, someKey());
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("crossborder.AlreadyAvailable");
    }

    @Test
    @Order(3)
    @DisplayName("discovery is filtered by the build's declared rails: the same active version, judged by a build"
            + " declaring no corridor rail, offers nothing")
    void discoveryFollowsTheBuild() {
        CorridorAdministrationDesk noRails = new CorridorAdministrationDesk(
                corridorPolicyAdministration, corridorAvailability, Set::of, idempotentExecutor,
                crossborderTransactionRunner, Clock.systemUTC());
        assertThat(noRails.offered().corridors()).isEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("an undeclared rail and data the platform does not hold are 422 at proposal; a malformed corridor"
            + " 404; an unknown request 404")
    void malformedProposalsAreRefused() throws Exception {
        String controller = sessionWith(RoleName.FX_CONTROLLER);
        withdrawPending(controller);
        HttpResponse<String> undeclared = post(POLICIES,
                CorridorPolicyV1.json("undeclared").replace("corridor-sim-a", "corridor-sim-z"), controller, someKey());
        assertThat(undeclared.statusCode()).as(undeclared.body()).isEqualTo(422);
        assertThat(undeclared.body()).contains("crossborder.RailNotDeclared");
        HttpResponse<String> unsatisfiable = post(POLICIES,
                CorridorPolicyV1.json("needs a purpose").replace("\"ENTITY_TYPE\"", "\"PAYMENT_PURPOSE\""),
                controller, someKey());
        assertThat(unsatisfiable.statusCode()).as(unsatisfiable.body()).isEqualTo(422);
        assertThat(unsatisfiable.body()).contains("crossborder.RequiredDataUnsatisfiable");
        HttpResponse<String> badScale = post(POLICIES,
                CorridorPolicyV1.json("a fee past its scale").replace("\"feeFixed\":\"2.50\"", "\"feeFixed\":\"2.505\""),
                controller, someKey());
        assertThat(badScale.statusCode()).as(badScale.body()).isEqualTo(422);
        assertThat(badScale.body()).contains("crossborder.CorridorPolicyInvalid");
        assertThat(post("/v1/operator/cross-border/corridors/EURUSD/availability", availability(false, "outage"),
                        controller, someKey()).statusCode())
                .isEqualTo(404);
        assertThat(post(enableApproval(UUID.randomUUID().toString()), decision("x"), controller, null).statusCode())
                .isEqualTo(404);
    }

    @Test
    @Order(5)
    @DisplayName("every door is refused to another operator role and to a customer (403), and to no session (401)")
    void theDoorsAreTheControllersAlone() throws Exception {
        String other = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        String customer = sessionWith(null);
        String id = UUID.randomUUID().toString();
        List<String[]> posts = List.of(
                new String[] {POLICIES, CorridorPolicyV1.json("not mine")},
                new String[] {approval(id), decision("not mine")},
                new String[] {POLICIES + "/" + id + "/rejection", decision("not mine")},
                new String[] {"/v1/operator/cross-border/corridors/EUR-USD-US/availability", availability(false, "not mine")},
                new String[] {enableApproval(id), decision("not mine")},
                new String[] {"/v1/operator/cross-border/corridor-enable-requests/" + id + "/rejection", decision("not mine")});
        long facts = availabilityFacts();
        for (String bearer : new String[] {other, customer}) {
            assertThat(get(POLICIES, bearer).statusCode()).isEqualTo(403);
            for (String[] route : posts) {
                assertThat(post(route[0], route[1], bearer, someKey()).statusCode()).as("403 at %s", route[0])
                        .isEqualTo(403);
            }
        }
        assertThat(get(POLICIES, null).statusCode()).isEqualTo(401);
        for (String[] route : posts) {
            assertThat(post(route[0], route[1], null, someKey()).statusCode()).as("401 at %s", route[0]).isEqualTo(401);
        }
        assertThat(availabilityFacts()).as("no refused caller wrote a fact").isEqualTo(facts);
    }

    // -----------------------------------------------------------------

    private static boolean activeExists() throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT count(*) FROM crossborder.corridor_policy_version WHERE status = 'ACTIVE'");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getLong(1) > 0;
        }
    }

    private static long availabilityFacts() throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT count(*) FROM crossborder.corridor_availability");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    /** Rejects any proposal another case left pending, so this case is free to propose. */
    private void withdrawPending(String controller) throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT id FROM crossborder.corridor_policy_version WHERE status = 'PROPOSED'");
                ResultSet row = select.executeQuery()) {
            if (row.next()) {
                assertThat(post(POLICIES + "/" + row.getObject(1, UUID.class) + "/rejection",
                                decision("cleared by the next test case"), controller, null).statusCode())
                        .isEqualTo(200);
            }
        }
    }

    private static String approval(String id) {
        return POLICIES + "/" + id + "/approval";
    }

    private static String enableApproval(String id) {
        return "/v1/operator/cross-border/corridor-enable-requests/" + id + "/approval";
    }

    private static String decision(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private static String availability(boolean available, String reason) {
        return "{\"available\":" + available + ",\"reason\":\"" + reason + "\"}";
    }

    /** A signed-in session holding {@code role}, or a plain registered customer when it is null. */
    private String sessionWith(RoleName role) throws Exception {
        String login = "xb." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations",
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                                + "\"password\":\"" + PASSWORD + "\"}",
                        null, someKey()).statusCode())
                .isEqualTo(201);
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
        HttpResponse<String> session = post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}", null, someKey());
        return field(session.body(), "sessionToken");
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return send(request.build());
    }

    private HttpResponse<String> post(String path, String body, String bearer, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
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
