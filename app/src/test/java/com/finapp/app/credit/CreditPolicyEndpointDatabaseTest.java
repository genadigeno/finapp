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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * The credit policy doors over HTTP (`P10-TSK-012`; ADR-0086 sections 4-6, {@code INV-AUD-04}, {@code INV-CRD-05},
 * {@code INV-CRD-10}): the seeded {@code PERSONAL_LOAN} v1 activated by a {@code CREDIT_POLICY_OFFICER}, a
 * person-proposed v2 refused to its own proposer and activated by a second officer - retiring v1 - and the policy in
 * force read now and at a past instant; every act keyed so a lost response replays; the domain's refusals on the error
 * contract, an incomplete policy and an approving fallback among them; and every door closed to an {@code UNDERWRITER},
 * to no session, and (for the acts) to a missing key - nothing written by a refused caller.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the credit policy doors (P10-TSK-012)")
class CreditPolicyEndpointDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String POLICIES = "/v1/operator/credit/policies";
    private static final String LOAN_SEED = "0190a1b2-5c0e-7000-8000-00000000d001";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @Order(1)
    @DisplayName("v1 is activated by an officer; v2 is one officer's proposal, refused to its proposer, activated by"
            + " another - the policy in force read now and at v1's start - and every keyed act replays")
    void twoPersonsActivate() throws Exception {
        String first = sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        String second = sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        assertThat(status(LOAN_SEED)).as("the seed is a proposal").isEqualTo("PROPOSED");
        assertThat(get(POLICIES + "?product=PERSONAL_LOAN", first).statusCode())
                .as("nothing is in force before two persons act").isEqualTo(404);

        String approvalKey = someKey();
        HttpResponse<String> v1 = post(POLICIES + "/" + LOAN_SEED + "/approval", decision("v1 reviewed"), first, approvalKey);
        assertThat(v1.statusCode()).as(v1.body()).isEqualTo(200);
        assertThat(field(v1.body(), "status")).isEqualTo("ACTIVE");
        assertThat(post(POLICIES + "/" + LOAN_SEED + "/approval", decision("v1 reviewed"), first, approvalKey).body())
                .as("the same key replays the receipt").isEqualTo(v1.body());
        Instant v1From = effectiveFrom(LOAN_SEED);

        String proposalKey = someKey();
        HttpResponse<String> proposed = post(POLICIES, policy("PERSONAL_LOAN", 950, true, "REFER", "v2: a higher stress rate"),
                first, proposalKey);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String v2 = field(proposed.body(), "id");
        assertThat(proposed.body()).contains("\"version\":2");
        assertThat(post(POLICIES, policy("PERSONAL_LOAN", 950, true, "REFER", "v2: a higher stress rate"), first,
                proposalKey).body()).as("the same key, the same proposal").contains(v2);

        HttpResponse<String> self = post(POLICIES + "/" + v2 + "/approval", decision("my own"), first, someKey());
        assertThat(self.statusCode()).isEqualTo(403);
        assertThat(self.body()).contains("credit.SelfApprovalRefused");
        HttpResponse<String> approved = post(POLICIES + "/" + v2 + "/approval", decision("checked"), second, someKey());
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "retiredId")).isEqualTo(LOAN_SEED);
        assertThat(status(LOAN_SEED)).isEqualTo("RETIRED");

        HttpResponse<String> now = get(POLICIES + "?product=PERSONAL_LOAN", second);
        assertThat(now.statusCode()).as(now.body()).isEqualTo(200);
        assertThat(field(now.body(), "id")).isEqualTo(v2);
        assertThat(now.body()).contains("\"assessmentRateBps\":950").contains("\"ruleCode\":\"SOURCE_UNAVAILABLE_FALLBACK\"");
        HttpResponse<String> then = get(POLICIES + "?product=PERSONAL_LOAN&at=" + encode(v1From.toString()), second);
        assertThat(then.statusCode()).as(then.body()).isEqualTo(200);
        assertThat(field(then.body(), "id")).as("v1 was in force from its own start").isEqualTo(LOAN_SEED);
        assertThat(field(then.body(), "status")).isEqualTo("RETIRED");
    }

    @Test
    @Order(2)
    @DisplayName("CREDIT_LINE v1 activated, and the refusals: an incomplete policy, an approving fallback, an unknown"
            + " product, a pending proposal, a stale approval, a missing reason, an unknown id, a malformed instant, nothing in force")
    void theRefusals() throws Exception {
        String officer = sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        String other = sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        HttpResponse<String> incomplete = post(POLICIES, policy("CREDIT_LINE", 900, false, "REFER", "no fallback"),
                officer, someKey());
        assertThat(incomplete.statusCode()).isEqualTo(422);
        assertThat(incomplete.body()).contains("credit.PolicyIncomplete");
        HttpResponse<String> approving = post(POLICIES, policy("CREDIT_LINE", 900, true, "APPROVE", "approve on outage"),
                officer, someKey());
        assertThat(approving.statusCode()).isEqualTo(422);
        assertThat(approving.body()).contains("credit.PolicyIncomplete");
        // INV-CRD-09 (P10-DOC-001): the evaluator judges exposure only through rules - a policy with none that refuses
        // past its maximum exposure would approve past it, so the door refuses it.
        String unbounded = policy("CREDIT_LINE", 900, true, "REFER", "no exposure rule")
                .replaceFirst("\\{\"ruleCode\":\"EXPOSURE_LIMIT\"[^}]*\\},", "");
        assertThat(unbounded).doesNotContain("EXPOSURE_HEADROOM");
        HttpResponse<String> exposureless = post(POLICIES, unbounded, officer, someKey());
        assertThat(exposureless.statusCode()).as(exposureless.body()).isEqualTo(422);
        assertThat(exposureless.body()).contains("credit.PolicyIncomplete");
        HttpResponse<String> unknown = post(POLICIES, policy("MORTGAGE", 900, true, "REFER", "x"), officer, someKey());
        assertThat(unknown.statusCode()).isEqualTo(422);
        assertThat(unknown.body()).contains("credit.ProductNotOffered");

        String seed = "0190a1b2-5c0e-7000-8000-00000000d002";
        HttpResponse<String> pending = post(POLICIES, policy("CREDIT_LINE", 901, true, "REFER", "v2"), officer, someKey());
        assertThat(pending.statusCode()).as("the CREDIT_LINE seed still awaits a decision").isEqualTo(409);
        assertThat(pending.body()).contains("credit.ProposalPending");
        HttpResponse<String> noReason = post(POLICIES + "/" + seed + "/rejection", "{}", officer, someKey());
        assertThat(noReason.statusCode()).isEqualTo(422);
        assertThat(noReason.body()).contains("credit.ReasonRequired");
        HttpResponse<String> v1 = post(POLICIES + "/" + seed + "/approval", decision("v1 reviewed"), officer, someKey());
        assertThat(v1.statusCode()).as("CREDIT_LINE v1 activated by a person, not its proposer").isEqualTo(200);
        assertThat(field(v1.body(), "status")).isEqualTo("ACTIVE");
        HttpResponse<String> stale = post(POLICIES + "/" + seed + "/approval", decision("late"), other, someKey());
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.body()).contains("credit.PolicyStale");

        assertThat(post(POLICIES + "/" + UUID.randomUUID() + "/approval", decision("x"), other, someKey()).statusCode())
                .isEqualTo(404);
        assertThat(post(POLICIES + "/not-a-uuid/approval", decision("x"), other, someKey()).statusCode()).isEqualTo(404);
        assertThat(get(POLICIES + "?product=MORTGAGE", other).body()).contains("credit.ProductNotOffered");
        HttpResponse<String> malformed = get(POLICIES + "?product=CREDIT_LINE&at=yesterday", other);
        assertThat(malformed.statusCode()).isEqualTo(422);
        assertThat(malformed.body()).contains("api.ValidationFailed");
        HttpResponse<String> line = get(POLICIES + "?product=CREDIT_LINE", other);
        assertThat(line.statusCode()).as(line.body()).isEqualTo(200);
        assertThat(field(line.body(), "id")).as("CREDIT_LINE v1 is in force").isEqualTo(seed);
        assertThat(get(POLICIES + "?product=PERSONAL_LOAN&at=" + encode("2000-01-01T00:00:00Z"), other).statusCode())
                .as("no version was in force before any activation").isEqualTo(404);
    }

    @Test
    @Order(3)
    @DisplayName("every door is closed to an UNDERWRITER and to no session, every act to a missing key - nothing written")
    void theDoorsAreClosed() throws Exception {
        String underwriter = sessionWith(RoleName.UNDERWRITER);
        String officer = sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        long versions = versions();
        String someVersion = "/" + LOAN_SEED;
        for (String path : new String[] {POLICIES, POLICIES + someVersion + "/approval", POLICIES + someVersion + "/rejection"}) {
            String body = path.equals(POLICIES) ? policy("PERSONAL_LOAN", 960, true, "REFER", "refused") : decision("refused");
            assertThat(post(path, body, underwriter, someKey()).statusCode()).as("UNDERWRITER " + path).isEqualTo(403);
            assertThat(post(path, body, null, someKey()).statusCode()).as("no session " + path).isEqualTo(401);
            HttpResponse<String> unkeyed = post(path, body, officer, null);
            assertThat(unkeyed.statusCode()).as("no key " + path).isEqualTo(422);
            assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
        }
        assertThat(get(POLICIES + "?product=PERSONAL_LOAN", underwriter).statusCode()).as("UNDERWRITER reads").isEqualTo(403);
        assertThat(get(POLICIES + "?product=PERSONAL_LOAN", null).statusCode()).as("no session reads").isEqualTo(401);
        assertThat(versions()).as("no refused caller wrote a version").isEqualTo(versions);
    }

    // -----------------------------------------------------------------

    /**
     * v1's shape over the wire for {@code product} at {@code rate} basis points: the parameters, both source kinds,
     * and rules - with the any-source fallback when {@code withFallback}, so a policy without it is incomplete.
     */
    private static String policy(String product, int rate, boolean withFallback, String fallback, String reason) {
        String fallbackRule = "{\"ruleCode\":\"SOURCE_UNAVAILABLE_FALLBACK\",\"subjectKind\":\"ATTRIBUTE\","
                + "\"subject\":\"SOURCE_UNAVAILABLE\",\"operator\":\"IS_PRESENT\",\"effect\":\"" + fallback + "\","
                + "\"reasonCode\":\"CRD-SOURCE-UNAVAILABLE\"},";
        return "{\"product\":\"" + product + "\",\"currency\":\"EUR\",\"assessmentRateBps\":" + rate
                + ",\"minimumDisposableMinor\":10000,\"minimumPaymentRatioBps\":300,\"maximumExposureMinor\":4000000,"
                + "\"sources\":[{\"kind\":\"BUREAU\",\"maximumDataAgeSeconds\":2592000},"
                + "{\"kind\":\"FINANCIAL_DATA\",\"maximumDataAgeSeconds\":2592000}],"
                + "\"unavailableFallback\":\"" + fallback + "\",\"autoApprovalCeilingMinor\":1000000,\"rules\":["
                + (withFallback ? fallbackRule : "")
                + "{\"ruleCode\":\"INSOLVENCY\",\"subjectKind\":\"ATTRIBUTE\",\"subject\":\"BUREAU_INSOLVENCY_FLAG\","
                + "\"operator\":\"EQ\",\"booleanOperand\":true,\"effect\":\"HARD_DECLINE\",\"reasonCode\":\"CRD-INSOLVENCY\"},"
                + "{\"ruleCode\":\"EXPOSURE_LIMIT\",\"subjectKind\":\"FIGURE\",\"subject\":\"EXPOSURE_HEADROOM\","
                + "\"operator\":\"LT\",\"moneyOperandMinor\":0,\"effect\":\"DECLINE\",\"reasonCode\":\"CRD-EXPOSURE-LIMIT\"},"
                + "{\"ruleCode\":\"LOW_SCORE_CAP\",\"subjectKind\":\"FIGURE\",\"subject\":\"SCORE\",\"operator\":\"LT\","
                + "\"integerOperand\":600,\"effect\":\"CAP_AMOUNT\",\"capMinor\":500000,"
                + "\"reasonCode\":\"CRD-SCORE-INSUFFICIENT\"}],\"reason\":\"" + reason + "\"}";
    }

    private static String decision(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String status(String id) throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT status FROM credit.credit_policy_version WHERE id = ?")) {
            select.setObject(1, UUID.fromString(id));
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static Instant effectiveFrom(String id) throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT effective_from FROM credit.credit_policy_version WHERE id = ?")) {
            select.setObject(1, UUID.fromString(id));
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getTimestamp(1).toInstant();
            }
        }
    }

    private static long versions() throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT count(*) FROM credit.credit_policy_version");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    /** A signed-in session holding {@code role}. */
    private String sessionWith(RoleName role) throws Exception {
        String login = "cp." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
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
