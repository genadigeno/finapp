package com.finapp.app.bnpl;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import java.util.stream.Collectors;
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
 * The BNPL terms doors over HTTP (`P12-TSK-004`; ADR-0102 decision 4, {@code INV-AUD-04}, {@code INV-HIST-04}) -
 * lending's {@code LoanTermsEndpointDatabaseTest}, applied: a {@code BNPL_OFFICER}'s proposal refused to its own proposer
 * (recorded {@code FAILED}) and activated by a second officer, a successor retiring it, the version read by id while
 * pending and the terms in force read now; every act keyed so a lost response replays; the domain's refusals on the
 * error contract; and every door closed to a {@code BNPL_SERVICING_AGENT}, to a session holding no role, to no session,
 * and (for the acts) to a missing key - nothing written by a refused caller.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the BNPL terms doors (P12-TSK-004)")
class BnplTermsEndpointDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String TERMS = "/v1/operator/bnpl/terms";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @Order(1)
    @DisplayName("one officer proposes, is refused their own activation (recorded FAILED), a second reads the proposal and"
            + " activates it; a successor retires it; the terms in force read now - and every keyed act replays")
    void twoOfficersActivate() throws Exception {
        String first = sessionWith(RoleName.BNPL_OFFICER);
        String second = sessionWith(RoleName.BNPL_OFFICER);
        clearPending("BNPL_PAY_IN_4", second);

        String proposalKey = someKey();
        HttpResponse<String> proposed = post(TERMS, terms("BNPL_PAY_IN_4", Map.of()), first, proposalKey);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String v1 = field(proposed.body(), "id");
        assertThat(field(proposed.body(), "status")).isEqualTo("PROPOSED");
        assertThat(post(TERMS, terms("BNPL_PAY_IN_4", Map.of()), first, proposalKey).body())
                .as("the same key, the same proposal").isEqualTo(proposed.body());

        HttpResponse<String> pending = get(TERMS + "/" + v1, second);
        assertThat(pending.statusCode()).as(pending.body()).isEqualTo(200);
        assertThat(pending.body()).contains("\"status\":\"PROPOSED\"", "\"nominalAnnualRate\":\"0\"",
                "\"instalmentCount\":4", "\"intervalDays\":14", "\"scheduleEngine\":\"BNPL_SCHEDULE_ENGINE_V1\"",
                "\"allowedTerms\":null").doesNotContain("reason");

        HttpResponse<String> self = post(TERMS + "/" + v1 + "/approval", decision("my own"), first, someKey());
        assertThat(self.statusCode()).isEqualTo(403);
        assertThat(self.body()).contains("bnpl.SelfApprovalRefused");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'bnpl.SelfApprovalRefused'"
                + " AND outcome = 'FAILED' AND target_type = 'bnpl_terms_version' AND target_id = ?", v1))
                .as("the self-approval recorded FAILED, once (the Phase 11 gate's R4)").isEqualTo(1);
        assertThat(status(v1)).isEqualTo("PROPOSED");

        String approvalKey = someKey();
        HttpResponse<String> approved = post(TERMS + "/" + v1 + "/approval", decision("reviewed"), second, approvalKey);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "status")).isEqualTo("ACTIVE");
        assertThat(post(TERMS + "/" + v1 + "/approval", decision("reviewed"), second, approvalKey).body())
                .as("the same key replays the receipt").isEqualTo(approved.body());
        HttpResponse<String> now = get(TERMS + "?product=BNPL_PAY_IN_4", first);
        assertThat(field(now.body(), "id")).isEqualTo(v1);

        String v2 = field(post(TERMS, terms("BNPL_PAY_IN_4", Map.of()), second, someKey()).body(), "id");
        HttpResponse<String> successor = post(TERMS + "/" + v2 + "/approval", decision("v2"), first, someKey());
        assertThat(successor.statusCode()).as(successor.body()).isEqualTo(200);
        assertThat(field(successor.body(), "retiredId")).isEqualTo(v1);
        assertThat(status(v1)).isEqualTo("RETIRED");
        assertThat(field(get(TERMS + "?product=BNPL_PAY_IN_4", second).body(), "id")).isEqualTo(v2);
        assertThat(get(TERMS + "?product=BNPL_PAY_IN_4&at=" + encode("2000-01-01T00:00:00Z"), second).statusCode())
                .as("no version was in force before any activation").isEqualTo(404);
    }

    @Test
    @Order(2)
    @DisplayName("the refusals: an unimplemented convention, a rate on pay-in-4, another product's part, terms that cannot"
            + " amortise, a pending proposal, a stale decision, a missing reason, an unknown id, product or instant")
    void theRefusals() throws Exception {
        String officer = sessionWith(RoleName.BNPL_OFFICER);
        String other = sessionWith(RoleName.BNPL_OFFICER);
        clearPending("BNPL_PAY_IN_N", other);
        for (Map<String, String> defect : List.of(
                Map.of("dayCount", "\"ACT_360\""),
                Map.of("interestRounding", "\"HALF_UP\""),
                Map.of("allocationOrder", "[\"FEES\",\"PRINCIPAL\"]"),
                Map.of("servicingZone", "\"Europe/Berlin\""),
                Map.of("instalmentCount", "4"),
                Map.of("scheduleEngine", "\"BNPL_SCHEDULE_ENGINE_V1\""),
                Map.of("allowedTerms", "[3,24]"),
                Map.of("agreementTemplateId", "\"BNPL_PAY_IN_4_AGREEMENT\""),
                Map.of("currency", "\"GBP\""),
                Map.of("product", "\"MORTGAGE\""),
                Map.of("offerValidityMinutes", "31"),
                Map.of("nominalAnnualRate", "\"eight percent\""))) {
            HttpResponse<String> refused = post(TERMS, terms("BNPL_PAY_IN_N", defect), officer, someKey());
            assertThat(refused.statusCode()).as(defect + ": " + refused.body()).isEqualTo(422);
            assertThat(refused.body()).as(defect.toString()).contains("bnpl.TermsInvalid");
        }
        HttpResponse<String> rated = post(TERMS, terms("BNPL_PAY_IN_4", Map.of("nominalAnnualRate", "\"0.05\"")), officer,
                someKey());
        assertThat(rated.statusCode()).isEqualTo(422);
        assertThat(rated.body()).contains("bnpl.TermsInvalid");
        long before = versions();
        HttpResponse<String> notAmortising = post(TERMS, terms("BNPL_PAY_IN_N",
                Map.of("nominalAnnualRate", "\"0.85\"", "minFirstPeriodDays", "28")), officer, someKey());
        assertThat(notAmortising.statusCode()).as(notAmortising.body()).isEqualTo(422);
        assertThat(notAmortising.body()).contains("bnpl.TermsNotAmortising").doesNotContain("0.85");
        assertThat(versions()).as("nothing proposed").isEqualTo(before);

        HttpResponse<String> proposed = post(TERMS, terms("BNPL_PAY_IN_N", Map.of()), officer, someKey());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String version = field(proposed.body(), "id");
        HttpResponse<String> pending = post(TERMS, terms("BNPL_PAY_IN_N", Map.of()), other, someKey());
        assertThat(pending.statusCode()).isEqualTo(409);
        assertThat(pending.body()).contains("bnpl.ProposalPending");
        HttpResponse<String> noReason = post(TERMS + "/" + version + "/rejection", "{}", officer, someKey());
        assertThat(noReason.statusCode()).isEqualTo(422);
        assertThat(noReason.body()).contains("bnpl.ReasonRequired");
        assertThat(post(TERMS + "/" + version + "/approval", decision("pn v"), other, someKey()).statusCode())
                .isEqualTo(200);
        HttpResponse<String> stale = post(TERMS + "/" + version + "/rejection", decision("too late"), officer, someKey());
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.body()).contains("bnpl.TermsStale");

        assertThat(post(TERMS + "/" + UUID.randomUUID() + "/approval", decision("x"), other, someKey()).statusCode())
                .isEqualTo(404);
        assertThat(post(TERMS + "/not-a-uuid/approval", decision("x"), other, someKey()).statusCode()).isEqualTo(404);
        assertThat(get(TERMS + "/" + UUID.randomUUID(), other).statusCode()).isEqualTo(404);
        assertThat(get(TERMS + "?product=MORTGAGE", other).statusCode()).isEqualTo(404);
        HttpResponse<String> malformed = get(TERMS + "?product=BNPL_PAY_IN_N&at=yesterday", other);
        assertThat(malformed.statusCode()).isEqualTo(422);
        assertThat(malformed.body()).contains("api.ValidationFailed");
    }

    @Test
    @Order(3)
    @DisplayName("every door is closed to a BNPL_SERVICING_AGENT, to a session holding no role and to no session, every act"
            + " to a missing key - nothing written")
    void theDoorsAreClosed() throws Exception {
        String agent = sessionWith(RoleName.BNPL_SERVICING_AGENT);
        String nobody = sessionWith(null);
        String officer = sessionWith(RoleName.BNPL_OFFICER);
        long versions = versions();
        String someVersion = "/" + UUID.randomUUID();
        for (String path : new String[] {TERMS, TERMS + someVersion + "/approval", TERMS + someVersion + "/rejection"}) {
            String body = path.equals(TERMS) ? terms("BNPL_PAY_IN_4", Map.of()) : decision("refused");
            assertThat(post(path, body, agent, someKey()).statusCode()).as("BNPL_SERVICING_AGENT " + path).isEqualTo(403);
            assertThat(post(path, body, nobody, someKey()).statusCode()).as("no role " + path).isEqualTo(403);
            assertThat(post(path, body, null, someKey()).statusCode()).as("no session " + path).isEqualTo(401);
            HttpResponse<String> unkeyed = post(path, body, officer, null);
            assertThat(unkeyed.statusCode()).as("no key " + path).isEqualTo(422);
            assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
        }
        for (String path : new String[] {TERMS + "?product=BNPL_PAY_IN_4", TERMS + someVersion}) {
            assertThat(get(path, agent).statusCode()).as("the agent reads " + path).isEqualTo(403);
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
        String proposer = sessionWith(RoleName.BNPL_OFFICER);
        String decider = sessionWith(RoleName.BNPL_OFFICER);
        clearPending("BNPL_PAY_IN_N", decider);
        long before = versions();
        String body = terms("BNPL_PAY_IN_N", Map.of());
        String key = someKey();
        List<HttpResponse<String>> responses = tenAtOnce(() -> post(TERMS, body, proposer, key));
        List<HttpResponse<String>> created = responses.stream().filter(r -> r.statusCode() == 201).toList();
        assertThat(created).isNotEmpty();
        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isIn(201, 409));
        assertThat(created.stream().map(HttpResponse::body).distinct()).as("one receipt").hasSize(1);
        assertThat(versions()).as("one version").isEqualTo(before + 1);
        String id = field(created.get(0).body(), "id");
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_terms_version_event WHERE terms_version_id = ?::uuid", id))
                .isEqualTo(1);
        post(TERMS + "/" + id + "/rejection", decision("cleared"), decider, someKey());
    }

    // -----------------------------------------------------------------

    /** The product's plan terms over the wire, each entry of {@code changes} replacing (or adding) one field's literal. */
    private static String terms(String product, Map<String, String> changes) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("product", "\"" + product + "\"");
        fields.put("currency", "\"EUR\"");
        fields.put("lateFeeMinor", "500");
        fields.put("lateFeeDay", "5");
        fields.put("lateFeeCapMinor", "1500");
        fields.put("graceDays", "3");
        fields.put("delinquencyBounds", "[1,30,60,90]");
        fields.put("defaultThresholdDays", "90");
        fields.put("allocationSequence", "\"OLDEST_DUE_FIRST\"");
        fields.put("allocationOrder", "[\"FEES\",\"INTEREST\",\"PRINCIPAL\"]");
        fields.put("overpaymentTreatment", "\"HOLD_AS_CREDIT\"");
        fields.put("autoCollection", "true");
        fields.put("partialCollection", "true");
        fields.put("collectionRetryDays", "1");
        fields.put("servicingZone", "\"UTC\"");
        fields.put("offerValidityMinutes", "30");
        fields.put("disputeResponseDays", "14");
        fields.put("disputeFourEyesThresholdMinor", "25000");
        fields.put("payoffHorizonDays", "30");
        fields.put("allocationEngine", "\"ALLOCATION_ENGINE_V1\"");
        fields.put("refundAllocationEngine", "\"REFUND_ALLOCATION_ENGINE_V1\"");
        if ("BNPL_PAY_IN_4".equals(product)) {
            fields.put("nominalAnnualRate", "\"0\"");
            fields.put("frequency", "\"BIWEEKLY\"");
            fields.put("instalmentRounding", "\"DOWN\"");
            fields.put("instalmentCount", "4");
            fields.put("intervalDays", "14");
            fields.put("scheduleEngine", "\"BNPL_SCHEDULE_ENGINE_V1\"");
            fields.put("agreementTemplateId", "\"BNPL_PAY_IN_4_AGREEMENT\"");
        } else {
            fields.put("nominalAnnualRate", "\"0.1999\"");
            fields.put("frequency", "\"MONTHLY\"");
            fields.put("instalmentRounding", "\"UP\"");
            fields.put("interestRounding", "\"HALF_EVEN\"");
            fields.put("dayCount", "\"ACT_365F\"");
            fields.put("allowedTerms", "[3,6,12]");
            fields.put("minFirstPeriodDays", "15");
            fields.put("scheduleEngine", "\"SCHEDULE_ENGINE_V1\"");
            fields.put("accrualEngine", "\"ACCRUAL_ENGINE_V1\"");
            fields.put("agreementTemplateId", "\"BNPL_PAY_IN_N_AGREEMENT\"");
        }
        fields.put("agreementTemplateVersion", "1");
        fields.put("reason", "\"the plan's terms (PHASE_12_PLAN.md section 12.1)\"");
        fields.putAll(changes);
        return fields.entrySet().stream()
                .map(field -> "\"" + field.getKey() + "\":" + field.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }

    /** Rejects the product's pending proposal, if any, as {@code decider} - so a case can propose. */
    private void clearPending(String product, String decider) throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT id FROM bnpl.bnpl_terms_version WHERE product = ? AND status = 'PROPOSED'")) {
            select.setString(1, product);
            try (ResultSet row = select.executeQuery()) {
                if (row.next()) {
                    post(TERMS + "/" + row.getObject(1, UUID.class) + "/rejection", decision("cleared by the suite"),
                            decider, someKey());
                }
            }
        }
    }

    private static String decision(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String status(String id) throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT status FROM bnpl.bnpl_terms_version WHERE id = ?")) {
            select.setObject(1, UUID.fromString(id));
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long versions() throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT count(*) FROM bnpl.bnpl_terms_version");
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

    /** A signed-in session holding {@code role}, or no role at all when it is {@code null}. */
    private String sessionWith(RoleName role) throws Exception {
        String login = "bt." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
