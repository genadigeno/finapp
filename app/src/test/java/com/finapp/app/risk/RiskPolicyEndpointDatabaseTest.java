package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.database.DateShifted;
import com.finapp.identity.Authenticator;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.identity.TotpParameters;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * The risk policy doors over HTTP (`P13-TSK-002`; ADR-0109, {@code INV-RSK-05}, {@code INV-RSK-07},
 * {@code INV-AUD-04}) - bnpl's {@code BnplTermsEndpointDatabaseTest}, applied: a {@code RISK_OFFICER}'s proposal
 * refused to its own proposer (recorded {@code FAILED}) and activated by a second officer from a {@code MULTI_FACTOR}
 * session - a password-only session refused {@code identity.AssuranceRequired} before anything is written - a
 * successor retiring it, the version read by id while pending and the version in force read now; every act keyed so a
 * lost response replays; the domain's refusals on the error contract; and every door closed to a {@code RISK_ANALYST},
 * to a session holding no role, to no session, and (for the acts) to a missing key - nothing written by a refused
 * caller. <strong>Written and owed</strong> (owner decision C5).
 *
 * <p><strong>Its own container</strong> (since `P13-TSK-008`): it activates a EUR {@code TRANSFER} version, and since
 * transfers are judged by risk every transfer in the database is then judged by it - a USD transfer of another suite in a
 * shared container would be refused by the open-ended fail-safe band, depending on the order the suites ran in.
 */
@DateShifted
@Tag("database")
@Tag("own-container")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the risk policy doors (P13-TSK-002)")
class RiskPolicyEndpointDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String POLICIES = "/v1/operator/risk/policies";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @Order(1)
    @DisplayName("one officer proposes, is refused their own activation (recorded FAILED), a second reads the proposal and"
            + " activates it from a MULTI_FACTOR session - a password-only session refused first; a successor retires it;"
            + " the version in force reads now - and every keyed act replays")
    void twoOfficersActivate() throws Exception {
        String first = sessionWith(RoleName.RISK_OFFICER, true);
        String second = sessionWith(RoleName.RISK_OFFICER, true);
        String secondPasswordOnly = sessionWith(RoleName.RISK_OFFICER, false);

        String proposalKey = someKey();
        HttpResponse<String> proposed = post(POLICIES, transfer(Map.of()), first, proposalKey);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String v1 = field(proposed.body(), "id");
        assertThat(field(proposed.body(), "status")).isEqualTo("PROPOSED");
        assertThat(post(POLICIES, transfer(Map.of()), first, proposalKey).body())
                .as("the same key, the same proposal").isEqualTo(proposed.body());

        HttpResponse<String> pending = get(POLICIES + "/" + v1, second);
        assertThat(pending.statusCode()).as(pending.body()).isEqualTo(200);
        assertThat(pending.body()).contains("\"status\":\"PROPOSED\"", "\"kind\":\"AMOUNT_BAND\"",
                "\"threshold_minor\":50000", "\"hardDecision\":\"CHALLENGE\"", "\"challengeAssurance\":\"MULTI_FACTOR\"",
                "\"overrideThresholdMinor\":100000", "\"holdValidityHours\":null").doesNotContain("reason");

        HttpResponse<String> self = post(POLICIES + "/" + v1 + "/activation", decision("my own"), first, someKey());
        assertThat(self.statusCode()).as(self.body()).isEqualTo(403);
        assertThat(self.body()).contains("risk.SelfApprovalRefused");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.SelfApprovalRefused'"
                + " AND outcome = 'FAILED' AND target_type = 'risk_policy_version' AND target_id = ?", v1))
                .as("the self-approval recorded FAILED, once").isEqualTo(1);
        assertThat(status(v1)).isEqualTo("PROPOSED");

        HttpResponse<String> lesser = post(POLICIES + "/" + v1 + "/activation", decision("not stepped up"),
                secondPasswordOnly, someKey());
        assertThat(lesser.statusCode()).as(lesser.body()).isEqualTo(403);
        assertThat(lesser.body()).contains("identity.AssuranceRequired");
        assertThat(status(v1)).isEqualTo("PROPOSED");

        String activationKey = someKey();
        HttpResponse<String> activated = post(POLICIES + "/" + v1 + "/activation", decision("reviewed"), second,
                activationKey);
        assertThat(activated.statusCode()).as(activated.body()).isEqualTo(200);
        assertThat(field(activated.body(), "status")).isEqualTo("ACTIVE");
        assertThat(post(POLICIES + "/" + v1 + "/activation", decision("reviewed"), second, activationKey).body())
                .as("the same key replays the receipt").isEqualTo(activated.body());
        HttpResponse<String> now = get(POLICIES + "?operation=TRANSFER", first);
        assertThat(now.statusCode()).as(now.body()).isEqualTo(200);
        assertThat(field(now.body(), "id")).isEqualTo(v1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'risk.RiskPolicyActivated'"
                + " AND aggregate_id = ?", v1)).isEqualTo(1);

        String v2 = field(post(POLICIES, transfer(Map.of()), second, someKey()).body(), "id");
        HttpResponse<String> successor = post(POLICIES + "/" + v2 + "/activation", decision("v2"), first, someKey());
        assertThat(successor.statusCode()).as(successor.body()).isEqualTo(200);
        assertThat(field(successor.body(), "retiredId")).isEqualTo(v1);
        assertThat(status(v1)).isEqualTo("RETIRED");
        assertThat(field(get(POLICIES + "?operation=TRANSFER", second).body(), "id")).isEqualTo(v2);
    }

    @Test
    @Order(2)
    @DisplayName("the refusals: an unknown kind, a parameter outside its schema, a band to an unadmitted decision, the"
            + " credit signal, a missing reason, a stale decision, a superseded proposal, an unknown id or operation, no"
            + " version in force")
    void theRefusals() throws Exception {
        String officer = sessionWith(RoleName.RISK_OFFICER, true);
        String other = sessionWith(RoleName.RISK_OFFICER, true);
        long before = versions();
        for (Map<String, String> defect : List.of(
                Map.of("rules", "[{\"kind\":\"GUT_FEELING\",\"parameters\":{},\"weight\":100}]"),
                Map.of("rules", "[{\"kind\":\"AMOUNT_BAND\",\"parameters\":{\"threshold_minor\":50000,\"window\":\"D1\"},"
                        + "\"weight\":100}]"),
                Map.of("rules", "[{\"kind\":\"AMOUNT_BAND\",\"parameters\":{\"threshold_minor\":\"lots\"},\"weight\":100}]"),
                Map.of("rules", "[{\"kind\":\"STRUCTURING\",\"parameters\":{\"window\":\"D1\",\"below_minor\":100000,"
                        + "\"threshold_count\":3,\"sum_minor\":250000},\"weight\":100}]"),
                Map.of("rules", "[{\"kind\":\"AMOUNT_BAND\",\"parameters\":{\"threshold_minor\":50000},"
                        + "\"hardDecision\":\"HOLD\"}]"),
                Map.of("scoreBands", "[{\"from\":0,\"to\":1000,\"decision\":\"HOLD\"}]"),
                Map.of("scoreBands", "[{\"from\":0,\"to\":999,\"decision\":\"ALLOW\"}]"),
                Map.of("failSafeBands", "[{\"fromMinor\":0,\"decision\":\"REVIEW\"}]"),
                Map.of("operation", "\"CREDIT_SIGNAL\""),
                Map.of("engine", "\"RISK_ENGINE_V9\""),
                Map.of("challengeAssurance", "\"SINGLE_FACTOR\""),
                Map.of("holdValidityHours", "72"))) {
            HttpResponse<String> refused = post(POLICIES, transfer(defect), officer, someKey());
            assertThat(refused.statusCode()).as(defect + ": " + refused.body()).isEqualTo(422);
            assertThat(refused.body()).as(defect.toString()).contains("risk.PolicyInvalid");
        }
        HttpResponse<String> unreasoned = post(POLICIES, transfer(Map.of("reason", "\" \"")), officer, someKey());
        assertThat(unreasoned.statusCode()).isEqualTo(422);
        assertThat(unreasoned.body()).contains("risk.ReasonRequired");
        assertThat(versions()).as("nothing proposed").isEqualTo(before);

        String earlier = field(post(POLICIES, transfer(Map.of()), officer, someKey()).body(), "id");
        String later = field(post(POLICIES, transfer(Map.of()), officer, someKey()).body(), "id");
        HttpResponse<String> noReason = post(POLICIES + "/" + earlier + "/rejection", "{}", officer, someKey());
        assertThat(noReason.statusCode()).isEqualTo(422);
        assertThat(noReason.body()).contains("risk.ReasonRequired");
        assertThat(post(POLICIES + "/" + later + "/activation", decision("the later"), other, someKey()).statusCode())
                .isEqualTo(200);
        HttpResponse<String> superseded = post(POLICIES + "/" + earlier + "/activation", decision("too old"), other,
                someKey());
        assertThat(superseded.statusCode()).as(superseded.body()).isEqualTo(409);
        assertThat(superseded.body()).contains("risk.PolicyStale");
        assertThat(status(earlier)).isEqualTo("PROPOSED");
        assertThat(post(POLICIES + "/" + earlier + "/rejection", decision("superseded"), officer, someKey()).statusCode())
                .isEqualTo(200);
        HttpResponse<String> stale = post(POLICIES + "/" + earlier + "/rejection", decision("again"), other, someKey());
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.body()).contains("risk.PolicyStale");

        assertThat(post(POLICIES + "/" + UUID.randomUUID() + "/activation", decision("x"), other, someKey()).statusCode())
                .isEqualTo(404);
        assertThat(post(POLICIES + "/not-a-uuid/activation", decision("x"), other, someKey()).statusCode()).isEqualTo(404);
        assertThat(get(POLICIES + "/" + UUID.randomUUID(), other).statusCode()).isEqualTo(404);
        assertThat(get(POLICIES + "?operation=MORTGAGE", other).statusCode()).isEqualTo(404);
        HttpResponse<String> none = get(POLICIES + "?operation=CROSS_BORDER", other);
        assertThat(none.statusCode()).as(none.body()).isEqualTo(409);
        assertThat(none.body()).contains("risk.PolicyNotActive");
    }

    @Test
    @Order(3)
    @DisplayName("every door is closed to a RISK_ANALYST, to a session holding no role and to no session, every act to a"
            + " missing key - nothing written")
    void theDoorsAreClosed() throws Exception {
        String analyst = sessionWith(RoleName.RISK_ANALYST, false);
        String nobody = sessionWith(null, false);
        String officer = sessionWith(RoleName.RISK_OFFICER, true);
        long versions = versions();
        String someVersion = "/" + UUID.randomUUID();
        for (String path : new String[] {POLICIES, POLICIES + someVersion + "/activation",
                POLICIES + someVersion + "/rejection"}) {
            String body = path.equals(POLICIES) ? transfer(Map.of()) : decision("refused");
            assertThat(post(path, body, analyst, someKey()).statusCode()).as("RISK_ANALYST " + path).isEqualTo(403);
            assertThat(post(path, body, nobody, someKey()).statusCode()).as("no role " + path).isEqualTo(403);
            assertThat(post(path, body, null, someKey()).statusCode()).as("no session " + path).isEqualTo(401);
            HttpResponse<String> unkeyed = post(path, body, officer, null);
            assertThat(unkeyed.statusCode()).as("no key " + path).isEqualTo(422);
            assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
        }
        for (String path : new String[] {POLICIES + "?operation=TRANSFER", POLICIES + someVersion}) {
            assertThat(get(path, analyst).statusCode()).as("the analyst reads " + path).isEqualTo(403);
            assertThat(get(path, nobody).statusCode()).as("no role reads " + path).isEqualTo(403);
            assertThat(get(path, null).statusCode()).as("no session reads " + path).isEqualTo(401);
        }
        assertThat(versions()).as("no refused caller wrote a version").isEqualTo(versions);
    }

    @Test
    @Order(4)
    @DisplayName("ten proposals under one key are one (INV-IDEM-01): one receipt or 409 api.IdempotencyInProgress, one"
            + " version, one history row")
    void tenProposalsUnderOneKeyAreOne() throws Exception {
        String proposer = sessionWith(RoleName.RISK_OFFICER, false);
        long before = versions();
        String body = transfer(Map.of());
        String key = someKey();
        List<HttpResponse<String>> responses = tenAtOnce(() -> post(POLICIES, body, proposer, key));
        List<HttpResponse<String>> created = responses.stream().filter(r -> r.statusCode() == 201).toList();
        assertThat(created).isNotEmpty();
        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isIn(201, 409));
        assertThat(created.stream().map(HttpResponse::body).distinct()).as("one receipt").hasSize(1);
        assertThat(versions()).as("one version").isEqualTo(before + 1);
        String id = field(created.get(0).body(), "id");
        assertThat(count("SELECT count(*) FROM risk.risk_policy_version_event WHERE policy_version_id = ?::uuid", id))
                .isEqualTo(1);
        post(POLICIES + "/" + id + "/rejection", decision("cleared"), proposer, someKey());
    }

    // -----------------------------------------------------------------

    /** A transfer policy over the wire, each entry of {@code changes} replacing (or adding) one field's literal. */
    private static String transfer(Map<String, String> changes) {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("operation", "\"TRANSFER\"");
        fields.put("engine", "\"RISK_ENGINE_V1\"");
        fields.put("currency", "\"EUR\"");
        fields.put("rules", "[{\"kind\":\"AMOUNT_BAND\",\"parameters\":{\"threshold_minor\":50000},\"hardDecision\":\"CHALLENGE\"},"
                + "{\"kind\":\"SCREENING_HIT\",\"parameters\":{},\"hardDecision\":\"DENY\"},"
                + "{\"kind\":\"NEW_DEVICE\",\"parameters\":{\"lookback_days\":30},\"weight\":200},"
                + "{\"kind\":\"VELOCITY_COUNT\",\"parameters\":{\"dimension\":\"SOURCE_ACCOUNT\",\"window\":\"D1\","
                + "\"threshold_count\":10},\"hardDecision\":\"REVIEW\"}]");
        fields.put("scoreBands", "[{\"from\":0,\"to\":299,\"decision\":\"ALLOW\"},"
                + "{\"from\":300,\"to\":1000,\"decision\":\"CHALLENGE\"}]");
        fields.put("failSafeBands", "[{\"fromMinor\":0,\"toMinor\":100000,\"decision\":\"ALLOW\"},"
                + "{\"fromMinor\":100000,\"decision\":\"DENY\"}]");
        fields.put("challengeAssurance", "\"MULTI_FACTOR\"");
        fields.put("overrideThresholdMinor", "100000");
        fields.put("reason", "\"the plan's v1 (PHASE_13_PLAN.md section 12.2)\"");
        fields.putAll(changes);
        return fields.entrySet().stream()
                .map(field -> "\"" + field.getKey() + "\":" + field.getValue())
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    private static String decision(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private static String status(String id) throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT status FROM risk.risk_policy_version WHERE id = ?")) {
            select.setObject(1, UUID.fromString(id));
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long versions() throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT count(*) FROM risk.risk_policy_version");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    private static long count(String sql, String parameter) throws Exception {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            select.setString(1, parameter);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static List<HttpResponse<String>> tenAtOnce(java.util.concurrent.Callable<HttpResponse<String>> request)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> pending = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                pending.add(pool.submit(() -> {
                    start.await();
                    return request.call();
                }));
            }
            start.countDown();
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> response : pending) {
                responses.add(response.get(2, TimeUnit.MINUTES));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A signed-in session holding {@code role}, or no role at all when it is {@code null}; stepped up to
     * {@code MULTI_FACTOR} with a freshly enrolled TOTP factor when {@code multiFactor} (the credit client's shape).
     */
    private String sessionWith(RoleName role, boolean multiFactor) throws Exception {
        String login = "rp." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
        String token = field(session.body(), "sessionToken");
        if (!multiFactor) {
            return token;
        }
        Matcher secret = Pattern.compile("secret=([A-Z2-7]+)").matcher(post("/v1/me/mfa", null, token, null).body());
        assertThat(secret.find()).as("the response must carry a base32 secret").isTrue();
        Sensitive<String> factor = Sensitive.of(secret.group(1));
        long step = Instant.now().getEpochSecond() / TotpParameters.current().periodSeconds();
        long confirmed = -1;
        for (long candidate : new long[] {step - 1, step, step + 1}) {
            if (post("/v1/me/mfa/confirmation", "{\"code\":\"" + codeAt(factor, candidate) + "\"}", token, null)
                    .statusCode() == 204) {
                confirmed = candidate;
                break;
            }
        }
        assertThat(confirmed).as("some step around this JVM's confirms the factor").isNotNegative();
        HttpResponse<String> elevated = post("/v1/authentications/mfa", "{\"code\":\"" + codeAt(factor, confirmed + 1)
                + "\"}", token, null);
        if (elevated.statusCode() == 401) {
            elevated = post("/v1/authentications/mfa", "{\"code\":\"" + codeAt(factor, confirmed + 2) + "\"}", token, null);
        }
        assertThat(elevated.statusCode()).as("the step-up: %s", elevated.body()).isEqualTo(200);
        assertThat(field(elevated.body(), "assurance")).isEqualTo("MULTI_FACTOR");
        return field(elevated.body(), "sessionToken");
    }

    private static String codeAt(Sensitive<String> secret, long step) {
        return Authenticator.codeAt(secret, TotpParameters.current(),
                Instant.ofEpochSecond(step * TotpParameters.current().periodSeconds()));
    }

    private HttpResponse<String> post(String path, String body, String bearer, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
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
