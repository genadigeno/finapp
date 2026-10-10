package com.finapp.app.credit;

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
 * The scorecard doors over HTTP (`P10-TSK-011`; ADR-0086 section 3, {@code INV-AUD-04}, {@code INV-CRD-05}): the seeded
 * {@code RETAIL_SCORECARD} v1 activated by a {@code CREDIT_POLICY_OFFICER}, a person-proposed v2 refused to its own
 * proposer and activated by a second officer - retiring v1 - every act keyed so a lost response replays; the domain's
 * refusals on the error contract; and every door closed to an {@code UNDERWRITER}, to no session, and to a missing
 * key - nothing written by a refused caller.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the scorecard doors (P10-TSK-011)")
class ScorecardAdministrationEndpointDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String SCORECARDS = "/v1/operator/credit/scorecards";
    private static final String SEED = "0190a1b2-5c0e-7000-8000-00000000c001";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @Order(1)
    @DisplayName("v1 is activated by an officer; v2 is one officer's proposal, refused to its proposer, activated by"
            + " another - and every keyed act replays")
    void twoPersonsActivate() throws Exception {
        String first = sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        String second = sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        assertThat(status(SEED)).as("the seed is a proposal").isEqualTo("PROPOSED");

        String approvalKey = someKey();
        HttpResponse<String> v1 = post(SCORECARDS + "/" + SEED + "/approval", decision("v1 reviewed"), first, approvalKey);
        assertThat(v1.statusCode()).as(v1.body()).isEqualTo(200);
        assertThat(field(v1.body(), "status")).isEqualTo("ACTIVE");
        assertThat(post(SCORECARDS + "/" + SEED + "/approval", decision("v1 reviewed"), first, approvalKey).body())
                .as("the same key replays the receipt").isEqualTo(v1.body());

        String proposalKey = someKey();
        HttpResponse<String> proposed = post(SCORECARDS, table(520, "v2: a higher base"), first, proposalKey);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String v2 = field(proposed.body(), "id");
        assertThat(proposed.body()).contains("\"version\":2");
        assertThat(post(SCORECARDS, table(520, "v2: a higher base"), first, proposalKey).body())
                .as("the same key, the same proposal").contains(v2);

        HttpResponse<String> self = post(SCORECARDS + "/" + v2 + "/approval", decision("my own"), first, someKey());
        assertThat(self.statusCode()).isEqualTo(403);
        assertThat(self.body()).contains("credit.SelfApprovalRefused");
        HttpResponse<String> approved = post(SCORECARDS + "/" + v2 + "/approval", decision("checked"), second, someKey());
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "status")).isEqualTo("ACTIVE");
        assertThat(field(approved.body(), "retiredId")).isEqualTo(SEED);
        assertThat(status(SEED)).isEqualTo("RETIRED");
        assertThat(status(v2)).isEqualTo("ACTIVE");
    }

    @Test
    @Order(2)
    @DisplayName("the refusals: a malformed table, a pending proposal, a stale approval, a missing reason, an unknown id")
    void theRefusals() throws Exception {
        String officer = sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        String other = sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        HttpResponse<String> money = post(SCORECARDS, "{\"family\":\"RETAIL_SCORECARD\",\"basePoints\":500,\"attributes\":"
                + "[{\"code\":\"DECLARED_MONTHLY_INCOME\",\"absentPoints\":0,\"bands\":[{\"points\":1}]}],\"reason\":\"r\"}",
                officer, someKey());
        assertThat(money.statusCode()).isEqualTo(422);
        assertThat(money.body()).contains("credit.ScorecardInvalid");
        assertThat(post(SCORECARDS, table(530, "x").replace("RETAIL_SCORECARD", "NO_SUCH_FAMILY"), officer, someKey()).body())
                .contains("credit.ScorecardInvalid");

        HttpResponse<String> pending = post(SCORECARDS, table(530, "v3"), officer, someKey());
        assertThat(pending.statusCode()).as(pending.body()).isEqualTo(201);
        String v3 = field(pending.body(), "id");
        HttpResponse<String> second = post(SCORECARDS, table(531, "v4"), officer, someKey());
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("credit.ProposalPending");

        HttpResponse<String> noReason = post(SCORECARDS + "/" + v3 + "/rejection", "{}", officer, someKey());
        assertThat(noReason.statusCode()).isEqualTo(422);
        assertThat(noReason.body()).contains("credit.ReasonRequired");
        // The rejection is keyed: its replay under the same key is the same response, never a second act
        // (P10-DOC-001 - PHASE_GATES' Idempotency clause names the rejection).
        String rejectionKey = someKey();
        HttpResponse<String> rejected = post(SCORECARDS + "/" + v3 + "/rejection", decision("withdrawn"), officer,
                rejectionKey);
        assertThat(rejected.statusCode()).isEqualTo(200);
        HttpResponse<String> replayed = post(SCORECARDS + "/" + v3 + "/rejection", decision("withdrawn"), officer,
                rejectionKey);
        assertThat(replayed.statusCode()).isEqualTo(200);
        assertThat(replayed.body()).as("replayed byte-identical").isEqualTo(rejected.body());
        HttpResponse<String> stale = post(SCORECARDS + "/" + v3 + "/approval", decision("late"), other, someKey());
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.body()).contains("credit.PolicyStale");

        assertThat(post(SCORECARDS + "/" + UUID.randomUUID() + "/approval", decision("x"), other, someKey()).statusCode())
                .isEqualTo(404);
        assertThat(post(SCORECARDS + "/not-a-uuid/approval", decision("x"), other, someKey()).statusCode()).isEqualTo(404);
    }

    @Test
    @Order(3)
    @DisplayName("every door is closed to an UNDERWRITER, to a customer, to no session and to a missing key - nothing"
            + " written")
    void theDoorsAreClosed() throws Exception {
        String underwriter = sessionWith(RoleName.UNDERWRITER);
        String customer = new CreditTestClient(port).customer(true).token();
        String officer = sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        long versions = versions();
        String someVersion = "/" + SEED;
        for (String path : new String[] {SCORECARDS, SCORECARDS + someVersion + "/approval",
                SCORECARDS + someVersion + "/rejection"}) {
            String body = path.equals(SCORECARDS) ? table(540, "refused") : decision("refused");
            assertThat(post(path, body, underwriter, someKey()).statusCode()).as("UNDERWRITER " + path).isEqualTo(403);
            assertThat(post(path, body, customer, someKey()).statusCode()).as("a customer " + path).isEqualTo(403);
            assertThat(post(path, body, null, someKey()).statusCode()).as("no session " + path).isEqualTo(401);
            HttpResponse<String> unkeyed = post(path, body, officer, null);
            assertThat(unkeyed.statusCode()).as("no key " + path).isEqualTo(422);
            assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
        }
        assertThat(versions()).as("no refused caller wrote a version").isEqualTo(versions);
    }

    // -----------------------------------------------------------------

    /** v1's table with {@code base} - the seed's shape over the wire. */
    private static String table(int base, String reason) {
        return "{\"family\":\"RETAIL_SCORECARD\",\"basePoints\":" + base + ",\"attributes\":["
                + "{\"code\":\"BUREAU_EXTERNAL_SCORE\",\"absentPoints\":-40,\"bands\":[{\"upper\":550,\"points\":-60},"
                + "{\"lower\":550,\"upper\":650,\"points\":0},{\"lower\":650,\"upper\":750,\"points\":40},"
                + "{\"lower\":750,\"points\":80}]},"
                + "{\"code\":\"BUREAU_INSOLVENCY_FLAG\",\"absentPoints\":-30,\"bands\":[{\"codes\":[\"false\"],\"points\":10},"
                + "{\"codes\":[\"true\"],\"points\":-200}]}],\"reason\":\"" + reason + "\"}";
    }

    private static String decision(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private static String status(String id) throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT status FROM credit.scorecard_model_version WHERE id = ?")) {
            select.setObject(1, UUID.fromString(id));
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long versions() throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT count(*) FROM credit.scorecard_model_version");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    /** A signed-in session holding {@code role}. */
    private String sessionWith(RoleName role) throws Exception {
        String login = "sc." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations",
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                                + "\"password\":\"" + PASSWORD + "\"}",
                        null, someKey()).statusCode())
                .isEqualTo(201);
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
        HttpResponse<String> session = post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}", null, someKey());
        return field(session.body(), "sessionToken");
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
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
