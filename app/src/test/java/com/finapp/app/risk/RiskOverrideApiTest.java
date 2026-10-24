package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authenticator;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.identity.TotpParameters;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.CanonicalEvaluation;
import com.finapp.risk.CommandRef;
import com.finapp.risk.EvaluationInputs;
import com.finapp.risk.RiskAssessment;
import com.finapp.risk.RiskAssessmentId;
import com.finapp.risk.RiskAssessmentStore;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskEngineV1;
import com.finapp.risk.RiskEvaluationRequest;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicy;
import com.finapp.risk.RiskPolicyAdministration;
import com.finapp.risk.ScoreBand;
import com.finapp.risk.VelocityOutcome;
import com.finapp.risk.Verdict;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The override doors, end to end (`P13-TSK-012`; {@code PHASE_13_PLAN.md} sections 9, 11; `-004`'s
 * {@code RiskAssessmentApiTest} and `-002`'s step-up shape): an officer proposes an override ({@code 201}); the
 * proposer's own approval at the threshold is {@code 403 risk.SelfApprovalRefused}, recorded {@code FAILED}; a second
 * officer's password-only session is {@code 403 identity.AssuranceRequired}; from a {@code MULTI_FACTOR} session the
 * approval answers {@code 200} with the effect it issued (a {@code DENY}'s {@code RESUBMISSION}; the held withdrawal's
 * release through the composition's real door is {@code WithdrawalHoldDatabaseTest}'s override half); the officer
 * reads the assessment and its overrides under {@code RISK_OVERRIDE}; an analyst is {@code 403}, no session
 * {@code 401}; the officer's own party {@code 403 risk.SelfDealingRefused}; an {@code ALLOW} {@code 409
 * risk.ActionNotProposable}; a card-shaped reason {@code 422 risk.ReasonRequired}; an unknown id {@code 404}.
 *
 * <p>No policy version is activated: the assessments are sealed through the store under a version PROPOSED for them,
 * so no other suite's movements are judged by it. Written and owed under C5.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the risk override doors (P13-TSK-012)")
class RiskOverrideApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private RiskPolicyAdministration riskPolicyAdministration;
    @Autowired private RiskAssessmentStore riskAssessmentStore;

    @Test
    @DisplayName("a DENY's override proposed 201 (a second holder required); the proposer's approval 403"
            + " SelfApprovalRefused recorded FAILED; a second officer's password session 403 AssuranceRequired; from"
            + " MULTI_FACTOR 200 APPROVED issuing its RESUBMISSION; read back under RISK_OVERRIDE, each read recorded")
    void theOverrideOverHttp() throws Exception {
        Session proposer = sessionWith(RoleName.RISK_OFFICER, true);
        UUID assessment = sealed(RiskOperation.TRANSFER, RiskDecision.DENY, 50_00, IDS.next());
        HttpResponse<String> proposed = post(door(assessment), "{\"reasonCode\":\"CUSTOMER_VERIFIED\",\"reason\":"
                + "\"verified by phone\"}", proposer.token());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(proposed.body()).contains("\"status\":\"PROPOSED\"").contains("\"effect\":\"RESUBMISSION\"")
                .contains("\"requiresSecondPerson\":true").doesNotContain("proposedBy");
        String overrideId = field(proposed.body(), "overrideId");

        HttpResponse<String> own = post(door(assessment) + "/" + overrideId + "/approval", "{\"reason\":\"mine\"}",
                proposer.token());
        assertThat(own.statusCode()).as(own.body()).isEqualTo(403);
        assertThat(own.body()).contains("risk.SelfApprovalRefused");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.SelfApprovalRefused'"
                + " AND outcome = 'FAILED' AND target_id = '" + overrideId + "'")).isEqualTo(1);

        Session password = sessionWith(RoleName.RISK_OFFICER, false);
        HttpResponse<String> stepUp = post(door(assessment) + "/" + overrideId + "/approval", "{\"reason\":\"ok\"}",
                password.token());
        assertThat(stepUp.statusCode()).as(stepUp.body()).isEqualTo(403);
        assertThat(stepUp.body()).contains("identity.AssuranceRequired");

        Session second = sessionWith(RoleName.RISK_OFFICER, true);
        HttpResponse<String> approved = post(door(assessment) + "/" + overrideId + "/approval",
                "{\"reason\":\"a second pair of eyes\"}", second.token());
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(approved.body()).contains("\"status\":\"APPROVED\"").contains("\"action\":\"RESUBMISSION\"")
                .contains("\"actionOutcome\":null");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation IN ('risk.OverrideProposed',"
                + " 'risk.OverrideApproved') AND target_id = '" + overrideId + "'")).isEqualTo(2);

        HttpResponse<String> read = get(door(assessment), second.token());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.body()).contains("\"assessmentId\":\"" + assessment + "\"").contains("\"decision\":\"DENY\"")
                .contains("\"overrideId\":\"" + overrideId + "\"");
        assertThat(get(door(assessment) + "/" + overrideId, second.token()).statusCode()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.AssessmentRead'"
                + " AND target_id = '" + assessment + "' AND change_summary LIKE '%door=override%'")).isEqualTo(2);
    }

    @Test
    @DisplayName("the refusals: an analyst 403 and no session 401; the officer's own party 403 SelfDealingRefused"
            + " recorded FAILED; an ALLOW 409 ActionNotProposable; a card-shaped reason 422 ReasonRequired; an unknown"
            + " assessment 404; a rejection 200 issuing nothing")
    void theRefusals() throws Exception {
        Session officer = sessionWith(RoleName.RISK_OFFICER, false);
        Session analyst = sessionWith(RoleName.RISK_ANALYST, false);
        UUID review = sealed(RiskOperation.TRANSFER, RiskDecision.REVIEW, 100_00, IDS.next());
        String body = "{\"reasonCode\":\"FALSE_POSITIVE\",\"reason\":\"explained\"}";

        assertThat(post(door(review), body, analyst.token()).statusCode()).as("another desk").isEqualTo(403);
        assertThat(get(door(review), null).statusCode()).isEqualTo(401);

        UUID own = sealed(RiskOperation.TRANSFER, RiskDecision.REVIEW, 100_00, officer.party());
        HttpResponse<String> dealing = post(door(own), body, officer.token());
        assertThat(dealing.statusCode()).as(dealing.body()).isEqualTo(403);
        assertThat(dealing.body()).contains("risk.SelfDealingRefused");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.SelfDealingRefused'"
                + " AND outcome = 'FAILED' AND target_id = '" + own + "' AND change_summary NOT LIKE '%"
                + officer.party() + "%'")).isEqualTo(1);

        UUID allowed = sealed(RiskOperation.TRANSFER, RiskDecision.ALLOW, 100_00, IDS.next());
        HttpResponse<String> never = post(door(allowed), body, officer.token());
        assertThat(never.statusCode()).as(never.body()).isEqualTo(409);
        assertThat(never.body()).contains("risk.ActionNotProposable");

        HttpResponse<String> screened = post(door(review), "{\"reasonCode\":\"FALSE_POSITIVE\",\"reason\":"
                + "\"card 4111 1111 1111 1111\"}", officer.token());
        assertThat(screened.statusCode()).as(screened.body()).isEqualTo(422);
        assertThat(screened.body()).contains("risk.ReasonRequired");
        assertThat(get(door(IDS.next()), officer.token()).statusCode()).isEqualTo(404);

        HttpResponse<String> proposed = post(door(review), body, officer.token());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(proposed.body()).contains("\"effect\":\"RESUBMISSION\"");
        HttpResponse<String> rejected = post(door(review) + "/" + field(proposed.body(), "overrideId") + "/rejection",
                "{\"reason\":\"withdrawn\"}", officer.token());
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
        assertThat(rejected.body()).contains("\"status\":\"REJECTED\"").contains("\"action\":null");
    }

    // ------------------------------------------------------------------ fixtures

    private record Session(String token, UUID party) {}

    private static String door(UUID assessment) {
        return "/v1/operator/risk/assessments/" + assessment + "/overrides";
    }

    /**
     * A {@code decision} of {@code operation} for {@code party}, sealed through the store under a version PROPOSED for
     * it (never activated) whose one score band decides it; the assessment's id.
     */
    private UUID sealed(RiskOperation operation, RiskDecision decision, long minor, UUID party) {
        RiskPolicy v1 = RiskPolicyFixtures.v1(operation);
        List<ScoreBand> bands = List.of(new ScoreBand(0, 1000, decision));
        RiskPolicy policy = new RiskPolicy(operation, v1.engine(), v1.currency(), v1.rules(), bands, v1.ratingBands(),
                v1.failSafeBands(), v1.challengeAssurance(), v1.overrideThreshold(),
                RiskPolicy.namesHoldValidity(operation, v1.rules(), bands)
                        ? Optional.of(v1.holdValidity().orElse(RiskPolicy.DEFAULT_HOLD_VALIDITY)) : Optional.empty());
        RiskPolicyAdministration.Proposed version = inOneTransaction(uow -> riskPolicyAdministration.propose(uow, policy,
                "the override API suite's version", new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE),
                CorrelationId.generate(IDS)));
        Money amount = Money.ofMinorUnits(minor, RiskPolicyFixtures.EUR);
        RiskEvaluationRequest request = RiskEvaluationRequest.builder(operation, CommandRef.of(IDS.next()), amount, party,
                CorrelationId.generate(IDS), CausationId.of("override-api")).sourceAccount(IDS.next()).build();
        EvaluationInputs inputs = request.judged().build();
        Verdict verdict = RiskEngineV1.evaluate(policy, inputs);
        String inputsText = CanonicalEvaluation.render(inputs);
        RiskAssessment assessment = new RiskAssessment(RiskAssessmentId.next(IDS), operation, request.commandRef(),
                RiskAssessment.Subjects.of(request), amount,
                Optional.of(new RiskAssessment.Pin(version.id(), version.version())), verdict.engine(), verdict.score(),
                decision, Optional.empty(), Optional.empty(),
                decision == RiskDecision.ALLOW ? VelocityOutcome.UNCOUNTED : VelocityOutcome.NOT_PERMITTED,
                Optional.empty(), inputsText, CanonicalEvaluation.sha256(inputsText),
                CanonicalEvaluation.sha256(CanonicalEvaluation.render(verdict)), verdict.hits(), verdict.reasons(), 100,
                request.correlation().value(), "override-api", Optional.empty());
        boolean inserted = inOneTransaction(uow -> riskAssessmentStore.insert(uow, assessment));
        assertThat(inserted).isTrue();
        return assessment.id().value();
    }

    private Session sessionWith(RoleName role, boolean multiFactor) throws Exception {
        String login = "ro." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
        HttpResponse<String> session = post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}", null);
        String token = field(session.body(), "sessionToken");
        if (!multiFactor) {
            return new Session(token, party);
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
        return new Session(field(elevated.body(), "sessionToken"), party);
    }

    private static String codeAt(Sensitive<String> secret, long step) {
        return Authenticator.codeAt(secret, TotpParameters.current(),
                Instant.ofEpochSecond(step * TotpParameters.current().periodSeconds()));
    }

    private static <R> R inOneTransaction(Function<Connection, R> work) {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            try {
                R result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
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
