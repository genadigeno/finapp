package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.CommandRef;
import com.finapp.risk.RiskEvaluation;
import com.finapp.risk.RiskEvaluationRequest;
import com.finapp.risk.RiskOperation;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The analyst's assessment doors, end to end (`P13-TSK-004`; {@code PHASE_13_PLAN.md} sections 9, 11; `-007`'s
 * {@code ProfileApiTest} shape): an assessment sealed by the composition's own {@link RiskEvaluation} read {@code 200}
 * and recorded {@code risk.AssessmentRead}; replayed {@code IDENTICAL} with a reason and recorded {@code risk.Replayed};
 * a blank reason {@code 422 risk.ReasonRequired}; an unknown or malformed id the uniform {@code 404 risk.NotFound}; the
 * analyst's own party {@code 403 risk.SelfDealingRefused}, recorded {@code FAILED} and never naming the party; another
 * desk {@code 403}; no session {@code 401}.
 *
 * <p>Written and owed under C5 (the standing criterion-7 deviation): the database tier is not run per task.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the risk assessment doors (P13-TSK-004)")
class RiskAssessmentApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private RiskEvaluation riskEvaluation;

    @Test
    @DisplayName("an analyst reads another party's assessment - 200 whole, recorded risk.AssessmentRead once - and"
            + " replays it IDENTICAL with a reason, recorded risk.Replayed with the reason; a blank reason is 422")
    void theReadAndTheReplay() throws Exception {
        Session analyst = sessionWith(RoleName.RISK_ANALYST);
        UUID assessment = sealed(IDS.next());

        HttpResponse<String> read = get(door(assessment), analyst.token());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.body()).contains("\"assessmentId\":\"" + assessment + "\"").contains("\"inputsCanonical\":")
                .contains("\"sealHolds\":true").contains("\"decision\":");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.AssessmentRead'"
                + " AND outcome = 'SUCCEEDED' AND target_type = 'risk_assessment' AND target_id = '" + assessment + "'"))
                .isEqualTo(1);

        HttpResponse<String> blank = post(door(assessment) + "/replay", "{\"reason\":\" \"}", analyst.token());
        assertThat(blank.statusCode()).as(blank.body()).isEqualTo(422);
        assertThat(blank.body()).contains("risk.ReasonRequired");

        HttpResponse<String> replay = post(door(assessment) + "/replay", "{\"reason\":\"a disputed refusal\"}",
                analyst.token());
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(replay.body()).contains("\"verdict\":\"IDENTICAL\"").contains("\"diverged\":[]");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.Replayed'"
                + " AND target_id = '" + assessment + "' AND reason = 'a disputed refusal'"
                + " AND change_summary = 'verdict=IDENTICAL, diverged=none'")).isEqualTo(1);
    }

    @Test
    @DisplayName("the refusals: an unknown and a malformed id 404 risk.NotFound; the analyst's own party 403"
            + " risk.SelfDealingRefused recorded FAILED; an officer 403; no session 401 - none served")
    void theRefusals() throws Exception {
        Session analyst = sessionWith(RoleName.RISK_ANALYST);
        Session officer = sessionWith(RoleName.RISK_OFFICER);

        HttpResponse<String> none = get(door(IDS.next()), analyst.token());
        assertThat(none.statusCode()).as(none.body()).isEqualTo(404);
        assertThat(none.body()).contains("risk.NotFound");
        HttpResponse<String> malformed = get("/v1/operator/risk/assessments/not-an-assessment", analyst.token());
        assertThat(malformed.statusCode()).isEqualTo(404);

        UUID own = sealed(analyst.party());
        HttpResponse<String> refused = get(door(own), analyst.token());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).contains("risk.SelfDealingRefused").doesNotContain("inputsCanonical");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.SelfDealingRefused'"
                + " AND outcome = 'FAILED' AND target_id = '" + own + "'"
                + " AND change_summary NOT LIKE '%" + analyst.party() + "%'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.AssessmentRead'"
                + " AND target_id = '" + own + "'")).as("nothing served").isZero();

        UUID other = sealed(IDS.next());
        assertThat(get(door(other), officer.token()).statusCode()).as("another desk").isEqualTo(403);
        assertThat(get(door(other), null).statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ fixtures

    private record Session(String token, UUID party) {}

    private static String door(UUID assessment) {
        return "/v1/operator/risk/assessments/" + assessment;
    }

    /** A transfer of {@code party}'s judged by the composition's T-eval in its own transaction; the assessment's id. */
    private UUID sealed(UUID party) throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            RiskEvaluation.Assessed assessed = riskEvaluation.evaluate(app, RiskEvaluationRequest.builder(
                            RiskOperation.TRANSFER, CommandRef.of(IDS.next()),
                            Money.ofMinorUnits(100_00, CurrencyCode.of("EUR")), party, CorrelationId.generate(IDS),
                            CausationId.of("assessment-api"))
                    .sourceAccount(IDS.next()).destinationAccount(IDS.next()).build());
            app.commit();
            return assessed.assessmentId().value();
        }
    }

    private Session sessionWith(RoleName role) throws Exception {
        String login = "ra." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations",
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                                + "\"password\":\"" + PASSWORD + "\"}", null).statusCode())
                .isEqualTo(201);
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
        HttpResponse<String> session = post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}", null);
        return new Session(field(session.body(), "sessionToken"), party);
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

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body));
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
