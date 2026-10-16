package com.finapp.app.lending;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
 * The loan terms doors over HTTP (`P11-TSK-005`; ADR-0092 sections 1-3, {@code INV-AUD-04}, {@code INV-LND-05},
 * {@code INV-LND-09}) - the backlog's {@code LoanTermsApiTest}, named for the tier it needs: a {@code LENDING_OFFICER}'s
 * proposal refused to its own proposer and activated by a second officer, a successor retiring it, the terms in force
 * read now and at a past instant; every act keyed so a lost response replays; the domain's refusals on the error
 * contract - an unimplemented convention, another kind's terms, a pending proposal, a stale decision, a missing reason;
 * and every door closed to a {@code LENDING_TREASURY_OFFICER}, to a session holding no role, to no session, and (for the
 * acts) to a missing key - nothing written by a refused caller.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the loan terms doors (P11-TSK-005)")
class LoanTermsEndpointDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String TERMS = "/v1/operator/lending/terms";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @Order(1)
    @DisplayName("one officer proposes, is refused their own activation, a second activates; a successor retires it; the"
            + " terms in force read now and at a past instant - and every keyed act replays")
    void twoOfficersActivate() throws Exception {
        String first = sessionWith(RoleName.LENDING_OFFICER);
        String second = sessionWith(RoleName.LENDING_OFFICER);
        assertThat(get(TERMS + "?product=PERSONAL_LOAN", first).statusCode())
                .as("nothing is in force before two persons act - never by migration").isEqualTo(404);

        String proposalKey = someKey();
        HttpResponse<String> proposed = post(TERMS, terms("PERSONAL_LOAN", "0.0899", Map.of()), first, proposalKey);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String v1 = field(proposed.body(), "id");
        assertThat(field(proposed.body(), "status")).isEqualTo("PROPOSED");
        assertThat(post(TERMS, terms("PERSONAL_LOAN", "0.0899", Map.of()), first, proposalKey).body())
                .as("the same key, the same proposal").isEqualTo(proposed.body());

        HttpResponse<String> self = post(TERMS + "/" + v1 + "/approval", decision("my own"), first, someKey());
        assertThat(self.statusCode()).isEqualTo(403);
        assertThat(self.body()).contains("lending.SelfApprovalRefused");
        assertThat(LoanApplicationClient.count("SELECT count(*) FROM platform.audit_record"
                + " WHERE operation = 'lending.SelfApprovalRefused'"
                + " AND outcome = 'FAILED' AND target_type = 'loan_terms_version' AND target_id = ?", v1))
                .as("the self-approval recorded FAILED, once (the Phase 11 gate R4)").isEqualTo(1);
        assertThat(LoanApplicationClient.scalar("SELECT status FROM lending.loan_terms_version WHERE id = ?::uuid", v1))
                .as("the proposal still awaits a different approver").isEqualTo("PROPOSED");
        String approvalKey = someKey();
        HttpResponse<String> approved = post(TERMS + "/" + v1 + "/approval", decision("reviewed"), second, approvalKey);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "status")).isEqualTo("ACTIVE");
        assertThat(post(TERMS + "/" + v1 + "/approval", decision("reviewed"), second, approvalKey).body())
                .as("the same key replays the receipt").isEqualTo(approved.body());
        Instant v1From = effectiveFrom(v1);

        HttpResponse<String> now = get(TERMS + "?product=PERSONAL_LOAN", first);
        assertThat(now.statusCode()).as(now.body()).isEqualTo(200);
        assertThat(field(now.body(), "id")).isEqualTo(v1);
        assertThat(now.body()).contains("\"nominalAnnualRate\":\"0.0899\"", "\"dayCount\":\"ACT_365F\"",
                "\"allocationOrder\":[\"FEES\",\"INTEREST\",\"PRINCIPAL\"]", "\"minFirstPeriodDays\":15",
                "\"agreementTemplateId\":\"PERSONAL_LOAN_AGREEMENT\"", "\"minimumPaymentFloorMinor\":null");

        String v2 = field(post(TERMS, terms("PERSONAL_LOAN", "0.0950", Map.of()), second, someKey()).body(), "id");
        HttpResponse<String> successor = post(TERMS + "/" + v2 + "/approval", decision("a higher rate"), first, someKey());
        assertThat(successor.statusCode()).as(successor.body()).isEqualTo(200);
        assertThat(field(successor.body(), "retiredId")).isEqualTo(v1);
        assertThat(status(v1)).isEqualTo("RETIRED");
        HttpResponse<String> then = get(TERMS + "?product=PERSONAL_LOAN&at=" + encode(v1From.toString()), second);
        assertThat(then.statusCode()).as(then.body()).isEqualTo(200);
        assertThat(field(then.body(), "id")).as("v1 was in force from its own start").isEqualTo(v1);
        assertThat(field(then.body(), "status")).isEqualTo("RETIRED");
        assertThat(field(get(TERMS + "?product=PERSONAL_LOAN", second).body(), "id")).isEqualTo(v2);
        assertThat(get(TERMS + "?product=PERSONAL_LOAN&at=" + encode("2000-01-01T00:00:00Z"), second).statusCode())
                .as("no version was in force before any activation").isEqualTo(404);
    }

    @Test
    @Order(2)
    @DisplayName("the refusals: an unimplemented convention, terms that cannot amortise, another kind's terms, an"
            + " unregistered template, another currency, a pending proposal, a stale decision, a missing reason, an unknown"
            + " id, product or instant")
    void theRefusals() throws Exception {
        String officer = sessionWith(RoleName.LENDING_OFFICER);
        String other = sessionWith(RoleName.LENDING_OFFICER);
        for (Map<String, String> defect : java.util.List.of(
                Map.of("dayCount", "\"ACT_360\""),
                Map.of("interestRounding", "\"HALF_UP\""),
                Map.of("rateType", "\"VARIABLE\""),
                Map.of("allocationOrder", "[\"FEES\",\"PRINCIPAL\"]"),
                Map.of("servicingZone", "\"Europe/Berlin\""),
                Map.of("minimumPaymentFloorMinor", "2500"),
                Map.of("scheduleEngineVersion", "2"),
                Map.of("agreementTemplateId", "\"CREDIT_LINE_AGREEMENT\""),
                Map.of("currency", "\"GBP\""),
                Map.of("product", "\"MORTGAGE\""),
                Map.of("offerValidityDays", "15"),
                Map.of("nominalAnnualRate", "\"eight percent\""))) {
            HttpResponse<String> refused = post(TERMS, terms("PERSONAL_LOAN", "0.07", defect), officer, someKey());
            assertThat(refused.statusCode()).as(defect + ": " + refused.body()).isEqualTo(422);
            assertThat(refused.body()).as(defect.toString()).contains("lending.TermsInvalid");
        }
        long before = versions();
        HttpResponse<String> notAmortising = post(TERMS, terms("PERSONAL_LOAN", "0.30", Map.of()), officer, someKey());
        assertThat(notAmortising.statusCode()).as(notAmortising.body()).isEqualTo(422);
        assertThat(notAmortising.body()).as("P11-TSK-006: no 60-month loan at 30 % amortises")
                .contains("lending.TermsNotAmortising").doesNotContain("0.3");
        assertThat(versions()).as("nothing proposed").isEqualTo(before);

        HttpResponse<String> line = post(TERMS, terms("CREDIT_LINE", "0.1499", Map.of()), officer, someKey());
        assertThat(line.statusCode()).as(line.body()).isEqualTo(201);
        String pendingLine = field(line.body(), "id");
        HttpResponse<String> pending = post(TERMS, terms("CREDIT_LINE", "0.1599", Map.of()), other, someKey());
        assertThat(pending.statusCode()).isEqualTo(409);
        assertThat(pending.body()).contains("lending.ProposalPending");
        HttpResponse<String> noReason = post(TERMS + "/" + pendingLine + "/rejection", "{}", officer, someKey());
        assertThat(noReason.statusCode()).isEqualTo(422);
        assertThat(noReason.body()).contains("lending.ReasonRequired");
        HttpResponse<String> activated = post(TERMS + "/" + pendingLine + "/approval", decision("line v1"), other, someKey());
        assertThat(activated.statusCode()).as(activated.body()).isEqualTo(200);
        HttpResponse<String> stale = post(TERMS + "/" + pendingLine + "/rejection", decision("too late"), officer, someKey());
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.body()).contains("lending.TermsStale");

        assertThat(post(TERMS + "/" + UUID.randomUUID() + "/approval", decision("x"), other, someKey()).statusCode())
                .isEqualTo(404);
        assertThat(post(TERMS + "/not-a-uuid/approval", decision("x"), other, someKey()).statusCode()).isEqualTo(404);
        assertThat(get(TERMS + "?product=MORTGAGE", other).statusCode()).isEqualTo(404);
        HttpResponse<String> malformed = get(TERMS + "?product=CREDIT_LINE&at=yesterday", other);
        assertThat(malformed.statusCode()).isEqualTo(422);
        assertThat(malformed.body()).contains("api.ValidationFailed");
        HttpResponse<String> inForce = get(TERMS + "?product=CREDIT_LINE", other);
        assertThat(field(inForce.body(), "id")).isEqualTo(pendingLine);
        assertThat(inForce.body()).contains("\"minimumPaymentFloorMinor\":2500", "\"paymentDueDays\":25",
                "\"overpaymentTreatment\":\"PAY_DOWN_PRINCIPAL\"", "\"minFirstPeriodDays\":null");
    }

    @Test
    @Order(3)
    @DisplayName("every door is closed to a LENDING_TREASURY_OFFICER, to a session holding no role and to no session,"
            + " every act to a missing key - nothing written")
    void theDoorsAreClosed() throws Exception {
        String treasury = sessionWith(RoleName.LENDING_TREASURY_OFFICER);
        String nobody = sessionWith(null);
        String officer = sessionWith(RoleName.LENDING_OFFICER);
        long versions = versions();
        String someVersion = "/" + UUID.randomUUID();
        for (String path : new String[] {TERMS, TERMS + someVersion + "/approval", TERMS + someVersion + "/rejection"}) {
            String body = path.equals(TERMS) ? terms("PERSONAL_LOAN", "0.07", Map.of()) : decision("refused");
            assertThat(post(path, body, treasury, someKey()).statusCode()).as("LENDING_TREASURY_OFFICER " + path)
                    .isEqualTo(403);
            assertThat(post(path, body, nobody, someKey()).statusCode()).as("no role " + path).isEqualTo(403);
            assertThat(post(path, body, null, someKey()).statusCode()).as("no session " + path).isEqualTo(401);
            HttpResponse<String> unkeyed = post(path, body, officer, null);
            assertThat(unkeyed.statusCode()).as("no key " + path).isEqualTo(422);
            assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
        }
        assertThat(get(TERMS + "?product=PERSONAL_LOAN", treasury).statusCode()).as("treasury reads").isEqualTo(403);
        assertThat(get(TERMS + "?product=PERSONAL_LOAN", nobody).statusCode()).as("no role reads").isEqualTo(403);
        assertThat(get(TERMS + "?product=PERSONAL_LOAN", null).statusCode()).as("no session reads").isEqualTo(401);
        assertThat(versions()).as("no refused caller wrote a version").isEqualTo(versions);
    }

    @Test
    @Order(4)
    @DisplayName("every terms act under one key is one (INV-IDEM-01, the gate's idempotency clause): ten proposals, ten"
            + " rejections and ten approvals - each raced under one key - answer one receipt (or 409"
            + " api.IdempotencyInProgress) and make one version and one event each")
    void everyTermsActUnderOneKeyIsOne() throws Exception {
        String proposer = sessionWith(RoleName.LENDING_OFFICER);
        String decider = sessionWith(RoleName.LENDING_OFFICER);
        long before = versions();
        String body = terms("PERSONAL_LOAN", "0.0777", Map.of());
        String proposalKey = someKey();
        String rejected = field(OneKeyRace.tenUnderOneKey(201, () -> post(TERMS, body, proposer, proposalKey)).body(),
                "id");
        assertThat(versions()).as("one version proposed").isEqualTo(before + 1);
        assertThat(events(rejected, "PROPOSED")).isEqualTo(1);
        String rejectionKey = someKey();
        OneKeyRace.tenUnderOneKey(200, () -> post(TERMS + "/" + rejected + "/rejection", decision("raced rejection"),
                decider, rejectionKey));
        assertThat(status(rejected)).isEqualTo("REJECTED");
        assertThat(events(rejected, "REJECTED")).as("rejected once").isEqualTo(1);

        String activated = field(post(TERMS, body, proposer, someKey()).body(), "id");
        String approvalKey = someKey();
        HttpResponse<String> approved = OneKeyRace.tenUnderOneKey(200, () -> post(TERMS + "/" + activated + "/approval",
                decision("raced approval"), decider, approvalKey));
        assertThat(field(approved.body(), "status")).isEqualTo("ACTIVE");
        assertThat(status(activated)).isEqualTo("ACTIVE");
        assertThat(events(activated, "ACTIVE")).as("activated once").isEqualTo(1);
        assertThat(versions()).as("the rejected and the activated, no more").isEqualTo(before + 2);
    }

    private static long events(String version, String toStatus) throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT count(*) FROM lending.loan_terms_version_event"
                        + " WHERE terms_version_id = ? AND to_status = ?")) {
            select.setObject(1, UUID.fromString(version));
            select.setString(2, toStatus);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    // -----------------------------------------------------------------

    /**
     * The product's plan terms over the wire at {@code rate}, each entry of {@code changes} replacing (or adding) one
     * field's JSON literal.
     */
    private static String terms(String product, String rate, Map<String, String> changes) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("product", "\"" + product + "\"");
        fields.put("currency", "\"EUR\"");
        fields.put("nominalAnnualRate", "\"" + rate + "\"");
        fields.put("rateType", "\"FIXED\"");
        fields.put("originationFeeMinor", "0");
        fields.put("originationFeeTreatment", "\"DEDUCTED\"");
        fields.put("lateFeeMinor", "1500");
        fields.put("lateFeeDay", "5");
        fields.put("lateFeeCapMinor", "6000");
        fields.put("graceDays", "3");
        fields.put("delinquencyBounds", "[1,30,60,90]");
        fields.put("defaultThresholdDays", "90");
        fields.put("allocationSequence", "\"OLDEST_DUE_FIRST\"");
        fields.put("allocationOrder", "[\"FEES\",\"INTEREST\",\"PRINCIPAL\"]");
        fields.put("autoCollection", "true");
        fields.put("partialCollection", "true");
        fields.put("instalmentRounding", "\"UP\"");
        fields.put("interestRounding", "\"HALF_EVEN\"");
        fields.put("dayCount", "\"ACT_365F\"");
        fields.put("servicingZone", "\"UTC\"");
        fields.put("allowedDays", "[1,15,28]");
        fields.put("offerValidityDays", "14");
        fields.put("payoffHorizonDays", "30");
        fields.put("accrualEngineVersion", "1");
        fields.put("allocationEngineVersion", "1");
        if ("CREDIT_LINE".equals(product)) {
            fields.put("overpaymentTreatment", "\"PAY_DOWN_PRINCIPAL\"");
            fields.put("minimumPaymentFloorMinor", "2500");
            fields.put("minimumPaymentRatioBps", "300");
            fields.put("paymentDueDays", "25");
            fields.put("statementEngineVersion", "1");
            fields.put("agreementTemplateId", "\"CREDIT_LINE_AGREEMENT\"");
        } else {
            fields.put("overpaymentTreatment", "\"HOLD_AS_CREDIT\"");
            fields.put("minFirstPeriodDays", "15");
            fields.put("disbursementDeadlineHours", "24");
            fields.put("scheduleEngineVersion", "1");
            fields.put("agreementTemplateId", "\"PERSONAL_LOAN_AGREEMENT\"");
        }
        fields.put("agreementTemplateVersion", "1");
        fields.put("reason", "\"the plan's terms (PHASE_11_PLAN.md section 12.1)\"");
        fields.putAll(changes);
        return fields.entrySet().stream()
                .map(field -> "\"" + field.getKey() + "\":" + field.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String decision(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String status(String id) throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT status FROM lending.loan_terms_version WHERE id = ?")) {
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
                        "SELECT effective_from FROM lending.loan_terms_version WHERE id = ?")) {
            select.setObject(1, UUID.fromString(id));
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getTimestamp(1).toInstant();
            }
        }
    }

    private static long versions() throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT count(*) FROM lending.loan_terms_version");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    /** A signed-in session holding {@code role}, or no role at all when it is {@code null}. */
    private String sessionWith(RoleName role) throws Exception {
        String login = "lt." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
