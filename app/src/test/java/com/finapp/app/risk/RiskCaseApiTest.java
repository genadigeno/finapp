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
 * The case doors, end to end (`P13-TSK-016`; {@code PHASE_13_PLAN.md} sections 9, 11; `-012`'s
 * {@code RiskOverrideApiTest} shape): an analyst opens a case by hand ({@code 201}), takes it with the revision it read
 * and proposes a confirming, prohibiting disposition; a stale revision is {@code 409 risk.CaseRevisionStale}; the
 * proposer's approval {@code 403 risk.SelfApprovalRefused}, recorded {@code FAILED}; a second analyst's password-only
 * session {@code 403 identity.AssuranceRequired}; from {@code MULTI_FACTOR} the approval disposes ({@code 200}) and the
 * party's profile reads {@code PROHIBITED} once the approval has committed; the analyst's own party {@code 403
 * risk.SelfDealingRefused} recorded {@code FAILED}; a card-shaped note {@code 422 risk.ReasonRequired}; a confirming
 * proposal on an untaken case {@code 422 risk.CaseNotTaken}; an unknown case the uniform {@code 404}; a risk officer
 * {@code 403} and no session {@code 401}. Written and owed under C5.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the risk case doors (P13-TSK-016)")
class RiskCaseApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @DisplayName("open 201, take with the revision, a stale revision 409, a prohibiting CONFIRMED_FRAUD proposed; the"
            + " proposer's approval 403 recorded FAILED; a password session 403 AssuranceRequired; from MULTI_FACTOR 200"
            + " DISPOSED and the profile PROHIBITED after the commit")
    void theCaseOverHttp() throws Exception {
        Session alice = sessionWith(RoleName.RISK_ANALYST, false);
        UUID customer = IDS.next();
        HttpResponse<String> opened = post("/v1/operator/risk/cases", "{\"kind\":\"FRAUD\",\"subjectKind\":\"PARTY\","
                + "\"subjectRef\":\"" + customer + "\",\"reason\":\"a mule pattern\"}", alice.token());
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        assertThat(opened.body()).contains("\"status\":\"OPEN\"").contains("\"revision\":0")
                .contains("\"opened\":true").doesNotContain("takenBy");
        String caseId = field(opened.body(), "caseId");

        HttpResponse<String> taken = post(door(caseId) + "/actions", "{\"act\":\"TAKE\",\"revision\":0}", alice.token());
        assertThat(taken.statusCode()).as(taken.body()).isEqualTo(200);
        assertThat(taken.body()).contains("\"status\":\"IN_INVESTIGATION\"").contains("\"revision\":1");
        HttpResponse<String> stale = post(door(caseId) + "/actions", "{\"act\":\"NOTE\",\"revision\":0,\"text\":"
                + "\"seen\"}", alice.token());
        assertThat(stale.statusCode()).as(stale.body()).isEqualTo(409);
        assertThat(stale.body()).contains("risk.CaseRevisionStale");

        HttpResponse<String> proposed = post(door(caseId) + "/dispositions", "{\"revision\":1,\"disposition\":"
                + "\"CONFIRMED_FRAUD\",\"prohibit\":true,\"reason\":\"a mule account\"}", alice.token());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(proposed.body()).contains("\"status\":\"PROPOSED\"").contains("\"requiresSecondPerson\":true");
        String dispositionId = field(proposed.body(), "dispositionId");
        String approval = door(caseId) + "/dispositions/" + dispositionId + "/approval";

        Session aliceStepped = sessionOf(alice, true);
        HttpResponse<String> own = post(approval, "{\"revision\":2,\"reason\":\"mine\"}", aliceStepped.token());
        assertThat(own.statusCode()).as(own.body()).isEqualTo(403);
        assertThat(own.body()).contains("risk.SelfApprovalRefused");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.SelfApprovalRefused'"
                + " AND outcome = 'FAILED' AND target_id = '" + caseId + "'")).isEqualTo(1);

        Session bobPassword = sessionWith(RoleName.RISK_ANALYST, false);
        HttpResponse<String> stepUp = post(approval, "{\"revision\":2,\"reason\":\"ok\"}", bobPassword.token());
        assertThat(stepUp.statusCode()).as(stepUp.body()).isEqualTo(403);
        assertThat(stepUp.body()).contains("identity.AssuranceRequired");

        Session bob = sessionOf(bobPassword, true);
        HttpResponse<String> approved = post(approval, "{\"revision\":2,\"reason\":\"confirmed by the bank\"}",
                bob.token());
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(approved.body()).contains("\"status\":\"DISPOSED\"").contains("\"status\":\"DECIDED\"");
        assertThat(count("SELECT count(*) FROM risk.customer_risk_profile_version WHERE party_id = '" + customer
                + "' AND rating = 'PROHIBITED' AND disposition_id = '" + dispositionId + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation IN ('risk.CaseOpened',"
                + " 'risk.CaseTaken', 'risk.DispositionProposed', 'risk.DispositionApproved', 'risk.CaseDisposed')"
                + " AND target_id = '" + caseId + "'")).isEqualTo(5);
    }

    @Test
    @DisplayName("the refusals: a risk officer 403 and no session 401; the analyst's own party 403 SelfDealingRefused"
            + " recorded FAILED; a card-shaped note 422 ReasonRequired; a confirming proposal on an untaken case 422"
            + " CaseNotTaken; an unknown case 404; a false positive closed from OPEN 201 DISPOSED")
    void theRefusals() throws Exception {
        Session analyst = sessionWith(RoleName.RISK_ANALYST, false);
        Session officer = sessionWith(RoleName.RISK_OFFICER, false);
        String body = "{\"kind\":\"FRAUD\",\"subjectKind\":\"PARTY\",\"subjectRef\":\"" + IDS.next()
                + "\",\"reason\":\"worth a look\"}";
        assertThat(post("/v1/operator/risk/cases", body, officer.token()).statusCode()).as("another desk")
                .isEqualTo(403);
        assertThat(post("/v1/operator/risk/cases", body, null).statusCode()).isEqualTo(401);

        HttpResponse<String> dealing = post("/v1/operator/risk/cases", "{\"kind\":\"FRAUD\",\"subjectKind\":\"PARTY\","
                + "\"subjectRef\":\"" + analyst.party() + "\",\"reason\":\"me\"}", analyst.token());
        assertThat(dealing.statusCode()).as(dealing.body()).isEqualTo(403);
        assertThat(dealing.body()).contains("risk.SelfDealingRefused");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.SelfDealingRefused'"
                + " AND outcome = 'FAILED' AND actor_id = '" + analyst.identity() + "' AND change_summary NOT LIKE '%"
                + analyst.party() + "%'")).isEqualTo(1);

        HttpResponse<String> opened = post("/v1/operator/risk/cases", body, analyst.token());
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        String caseId = field(opened.body(), "caseId");
        HttpResponse<String> screened = post(door(caseId) + "/actions", "{\"act\":\"NOTE\",\"revision\":0,\"text\":"
                + "\"card 4111 1111 1111 1111\"}", analyst.token());
        assertThat(screened.statusCode()).as(screened.body()).isEqualTo(422);
        assertThat(screened.body()).contains("risk.ReasonRequired");
        HttpResponse<String> untaken = post(door(caseId) + "/dispositions", "{\"revision\":0,\"disposition\":"
                + "\"CONFIRMED_FRAUD\",\"reason\":\"fraud\"}", analyst.token());
        assertThat(untaken.statusCode()).as(untaken.body()).isEqualTo(422);
        assertThat(untaken.body()).contains("risk.CaseNotTaken");
        assertThat(post(door(IDS.next().toString()) + "/actions", "{\"act\":\"TAKE\",\"revision\":0}",
                analyst.token()).statusCode()).isEqualTo(404);

        HttpResponse<String> closed = post(door(caseId) + "/dispositions", "{\"revision\":0,\"disposition\":"
                + "\"CLOSED_FALSE_POSITIVE\",\"reason\":\"the customer's payroll\"}", analyst.token());
        assertThat(closed.statusCode()).as(closed.body()).isEqualTo(201);
        assertThat(closed.body()).contains("\"status\":\"DISPOSED\"");
    }

    // ------------------------------------------------------------------ fixtures

    private record Session(String token, UUID party, UUID identity, String login) {}

    private static String door(String caseId) {
        return "/v1/operator/risk/cases/" + caseId;
    }

    private Session sessionWith(RoleName role, boolean multiFactor) throws Exception {
        String login = "rc." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                + "\"password\":\"" + PASSWORD + "\"}", null).statusCode()).isEqualTo(201);
        UUID identity;
        UUID party;
        try (Connection app = DatabaseRoles.application(); PreparedStatement read = app.prepareStatement(
                "SELECT id, party_id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                identity = row.getObject("id", UUID.class);
                party = row.getObject("party_id", UUID.class);
            }
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        return sessionOf(new Session(null, party, identity, login), multiFactor);
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

    private static long count(String sql) throws Exception {
        try (Connection app = DatabaseRoles.application(); PreparedStatement select = app.prepareStatement(sql);
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(body);
        assertThat(matcher.find()).as("%s in %s", name, body).isTrue();
        return matcher.group(1);
    }
}
