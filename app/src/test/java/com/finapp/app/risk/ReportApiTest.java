package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

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
 * The regulatory report's doors, end to end (`P13-TSK-021`; {@code PHASE_13_PLAN.md} sections 9, 11, 12.9; ADR-0111;
 * {@code DESIGN-021} D9): two analysts dispose an AML case of a real customer {@code CONFIRMED_SUSPICIOUS}; an AML
 * officer drafts it ({@code 201}, the narrative in the answer); the drafter's approval {@code 403
 * risk.SelfApprovalRefused} recorded {@code FAILED}; a password-only session {@code 403 identity.AssuranceRequired}; a
 * second officer's {@code MULTI_FACTOR} approval {@code 200}; <strong>the narrative's permission</strong>: the AML desk's
 * read {@code 200} (audited {@code risk.RegulatoryReportRead}), an analyst's {@code 403} - another desk - and the
 * analyst's case file carrying no trace of the report; the filing from {@code MULTI_FACTOR} {@code 202} with the
 * simulated authority's acknowledgement; {@code risk.RegulatoryReportFiled} naming the report and the jurisdiction, never
 * the subject; the refusals - a case not so disposed {@code 409 risk.ReportNotFileable}, no session {@code 401}, an
 * unknown report {@code 404}. Every operator {@code CUSTOMER}-typed, as the session establishes them. Written and owed
 * under C5.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the regulatory report doors: the narrative's permission, four eyes, MFA, filed through the simulated FIU"
        + " (P13-TSK-021)")
class ReportApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String NARRATIVE = "Funds were split below the threshold and moved out within a day.";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @DisplayName("drafted 201, the drafter's approval 403 recorded, a password session 403, a second MFA officer 200; the"
            + " AML desk reads the narrative 200 and an analyst 403; filed 202 ACKNOWLEDGED; the event names no subject")
    void theReportThroughItsDoors() throws Exception {
        Session customer = customer();
        String caseId = confirmedSuspicious(customer);

        Session drafter = sessionWith(RoleName.AML_OFFICER, false);
        HttpResponse<String> drafted = post(reports(caseId), "{\"jurisdiction\":\"EU-REF\",\"narrative\":\"" + NARRATIVE
                + "\",\"reason\":\"the case is confirmed suspicious\"}", drafter.token());
        assertThat(drafted.statusCode()).as(drafted.body()).isEqualTo(201);
        assertThat(drafted.body()).contains("\"status\":\"DRAFT\"").contains("\"jurisdiction\":\"EU-REF\"")
                .contains("\"subjectRef\":\"" + customer.party() + "\"").contains(NARRATIVE).doesNotContain("draftedBy");
        String reportId = field(drafted.body(), "reportId");

        Session drafterStepped = sessionOf(drafter, true);
        HttpResponse<String> own = post(reports(caseId) + "/" + reportId + "/approval", "{\"reason\":\"mine\"}",
                drafterStepped.token());
        assertThat(own.statusCode()).as(own.body()).isEqualTo(403);
        assertThat(own.body()).contains("risk.SelfApprovalRefused");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.SelfApprovalRefused'"
                + " AND outcome = 'FAILED' AND target_id = '" + reportId + "'")).isEqualTo(1);
        Session approverPassword = sessionWith(RoleName.AML_OFFICER, false);
        HttpResponse<String> stepUp = post(reports(caseId) + "/" + reportId + "/approval", "{\"reason\":\"ok\"}",
                approverPassword.token());
        assertThat(stepUp.statusCode()).as(stepUp.body()).isEqualTo(403);
        assertThat(stepUp.body()).contains("identity.AssuranceRequired");
        HttpResponse<String> approved = post(reports(caseId) + "/" + reportId + "/approval",
                "{\"reason\":\"the narrative is accurate\"}", sessionOf(approverPassword, true).token());
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(approved.body()).contains("\"status\":\"APPROVED\"");

        HttpResponse<String> read = get(reports(caseId) + "/" + reportId, approverPassword.token());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.body()).contains(NARRATIVE);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.RegulatoryReportRead'"
                + " AND target_id = '" + reportId + "'")).isEqualTo(1);
        Session analyst = sessionWith(RoleName.RISK_ANALYST, false);
        assertThat(get(reports(caseId) + "/" + reportId, analyst.token()).statusCode()).as("another desk")
                .isEqualTo(403);
        HttpResponse<String> file = get("/v1/operator/risk/cases/" + caseId, analyst.token());
        assertThat(file.statusCode()).as(file.body()).isEqualTo(200);
        assertThat(file.body()).as("the case file carries no report").doesNotContain(reportId)
                .doesNotContain(NARRATIVE).doesNotContain("REPORT_DRAFTED");

        Session filer = sessionWith(RoleName.AML_OFFICER, true);
        HttpResponse<String> filed = post(reports(caseId) + "/" + reportId + "/filing", "{\"reason\":\"file it\"}",
                filer.token());
        assertThat(filed.statusCode()).as(filed.body()).isEqualTo(202);
        assertThat(filed.body()).contains("\"status\":\"ACKNOWLEDGED\"").contains("\"providerReference\":\"SIM-FIU-");
        assertThat(scalar("SELECT payload::text FROM platform.outbox_event WHERE event_type ="
                + " 'risk.RegulatoryReportFiled' AND aggregate_id = '" + reportId + "'")).contains(reportId)
                .contains("EU-REF").doesNotContain(customer.party().toString()).doesNotContain("threshold");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.ReportAcknowledged'"
                + " AND target_id = '" + reportId + "' AND actor_type = 'SYSTEM'")).isEqualTo(1);
        assertThat(scalar("SELECT drafted_by_type || ',' || approved_by_type || ',' || filed_by_type FROM"
                + " risk.regulatory_report WHERE id = '" + reportId + "'")).isEqualTo("CUSTOMER,CUSTOMER,CUSTOMER");
    }

    @Test
    @DisplayName("the refusals: a case not disposed CONFIRMED_SUSPICIOUS 409 risk.ReportNotFileable, no session 401, an"
            + " analyst's draft 403, an unknown report 404")
    void theRefusals() throws Exception {
        Session analyst = sessionWith(RoleName.RISK_ANALYST, false);
        HttpResponse<String> opened = post("/v1/operator/risk/cases", "{\"kind\":\"AML\",\"subjectKind\":\"PARTY\","
                + "\"subjectRef\":\"" + IDS.next() + "\",\"reason\":\"a pattern\"}", analyst.token());
        String open = field(opened.body(), "caseId");
        Session officer = sessionWith(RoleName.AML_OFFICER, false);
        String body = "{\"jurisdiction\":\"EU-REF\",\"narrative\":\"" + NARRATIVE + "\",\"reason\":\"r\"}";
        HttpResponse<String> notFileable = post(reports(open), body, officer.token());
        assertThat(notFileable.statusCode()).as(notFileable.body()).isEqualTo(409);
        assertThat(notFileable.body()).contains("risk.ReportNotFileable");
        assertThat(post(reports(open), body, null).statusCode()).isEqualTo(401);
        assertThat(post(reports(open), body, analyst.token()).statusCode()).as("another desk").isEqualTo(403);
        assertThat(get(reports(open) + "/" + IDS.next(), officer.token()).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ fixtures

    private record Session(String token, UUID party, UUID identity, String login) {}

    private static String reports(String caseId) {
        return "/v1/operator/risk/cases/" + caseId + "/reports";
    }

    /** An AML case of {@code customer}'s party, taken and disposed CONFIRMED_SUSPICIOUS by two analysts; its id. */
    private String confirmedSuspicious(Session customer) throws Exception {
        Session alice = sessionWith(RoleName.RISK_ANALYST, false);
        HttpResponse<String> opened = post("/v1/operator/risk/cases", "{\"kind\":\"AML\",\"subjectKind\":\"PARTY\","
                + "\"subjectRef\":\"" + customer.party() + "\",\"reason\":\"structured inflows\"}", alice.token());
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        String caseId = field(opened.body(), "caseId");
        assertThat(post("/v1/operator/risk/cases/" + caseId + "/actions", "{\"act\":\"TAKE\",\"revision\":0}",
                alice.token()).statusCode()).isEqualTo(200);
        HttpResponse<String> proposed = post("/v1/operator/risk/cases/" + caseId + "/dispositions", "{\"revision\":1,"
                + "\"disposition\":\"CONFIRMED_SUSPICIOUS\",\"reason\":\"the pattern is confirmed\"}", alice.token());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String dispositionId = field(proposed.body(), "dispositionId");
        HttpResponse<String> approved = post("/v1/operator/risk/cases/" + caseId + "/dispositions/" + dispositionId
                + "/approval", "{\"revision\":2,\"reason\":\"agreed\"}", sessionWith(RoleName.RISK_ANALYST, true)
                .token());
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        return caseId;
    }

    /** A registered customer with a live session. */
    private Session customer() throws Exception {
        String login = "rr." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                + "\"password\":\"" + PASSWORD + "\"}", null).statusCode()).isEqualTo(201);
        UUID[] ids = identityOf(login);
        return sessionOf(new Session(null, ids[1], ids[0], login), false);
    }

    private Session sessionWith(RoleName role, boolean multiFactor) throws Exception {
        String login = "rr." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                + "\"password\":\"" + PASSWORD + "\"}", null).statusCode()).isEqualTo(201);
        UUID[] ids = identityOf(login);
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(ids[0]), role, IdentityId.of(ids[0]), "test fixture");
            app.commit();
        }
        return sessionOf(new Session(null, ids[1], ids[0], login), multiFactor);
    }

    /** The identity's id and its party's. */
    private static UUID[] identityOf(String login) throws Exception {
        try (Connection app = DatabaseRoles.application(); PreparedStatement read = app.prepareStatement(
                "SELECT id, party_id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return new UUID[] {row.getObject("id", UUID.class), row.getObject("party_id", UUID.class)};
            }
        }
    }

    /** A fresh session of {@code who} - password only, or stepped up through a factor confirmed now. */
    private Session sessionOf(Session who, boolean multiFactor) throws Exception {
        HttpResponse<String> session = post("/v1/authentications",
                "{\"loginIdentifier\":\"" + who.login() + "\",\"password\":\"" + PASSWORD + "\"}", null);
        String token = field(session.body(), "sessionToken");
        if (!multiFactor) {
            return new Session(token, who.party(), who.identity(), who.login());
        }
        Matcher secret = Pattern.compile("secret=([A-Z2-7]+)").matcher(post("/v1/me/mfa", null, token).body());
        assertThat(secret.find()).as("the response must carry a base32 secret").isTrue();
        Sensitive<String> factor = Sensitive.of(secret.group(1));
        long step = Instant.now().getEpochSecond() / TotpParameters.current().periodSeconds();
        long confirmed = -1;
        for (long candidate : new long[] {step - 1, step, step + 1}) {
            if (post("/v1/me/mfa/confirmation", "{\"code\":\"" + codeAt(factor, candidate) + "\"}", token)
                    .statusCode() == 204) {
                confirmed = candidate;
                break;
            }
        }
        assertThat(confirmed).as("some step around this JVM's confirms the factor").isNotNegative();
        HttpResponse<String> elevated = post("/v1/authentications/mfa", "{\"code\":\"" + codeAt(factor, confirmed + 1)
                + "\"}", token);
        if (elevated.statusCode() == 401) {
            elevated = post("/v1/authentications/mfa", "{\"code\":\"" + codeAt(factor, confirmed + 2) + "\"}", token);
        }
        assertThat(elevated.statusCode()).as("the step-up: %s", elevated.body()).isEqualTo(200);
        return new Session(field(elevated.body(), "sessionToken"), who.party(), who.identity(), who.login());
    }

    private static String codeAt(Sensitive<String> secret, long step) {
        return Authenticator.codeAt(secret, TotpParameters.current(),
                Instant.ofEpochSecond(step * TotpParameters.current().periodSeconds()));
    }

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString())
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
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

    private static long count(String sql) throws Exception {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql);
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String scalar(String sql) throws Exception {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql);
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getString(1);
        }
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(body);
        assertThat(matcher.find()).as("%s in %s", name, body).isTrue();
        return matcher.group(1);
    }
}
