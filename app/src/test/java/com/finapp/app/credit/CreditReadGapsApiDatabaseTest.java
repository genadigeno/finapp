package com.finapp.app.credit;

import static com.finapp.app.api.OperatorReadClient.column;
import static com.finapp.app.api.OperatorReadClient.json;
import static com.finapp.app.api.OperatorReadClient.problem;
import static com.finapp.app.api.OperatorReadClient.recorded;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.api.OperatorReadClient;
import com.finapp.credit.CreditProduct;
import com.finapp.identity.Authorization;
import com.finapp.identity.RoleName;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/**
 * Credit's operator collections over HTTP and PostgreSQL (`P17-TSK-035`, gaps G-80 and G-81): the decision requests, the
 * policy versions and the scorecard versions walked page by page against the tables' own order - so a keyset that skipped or
 * repeated a row would show - each served version naming its proposer and never its decider, one version of each read by its
 * identifier, every serving recorded; the doors closed to an underwriter and to no session, and a foreign cursor refused. The
 * database is shared with the other suites of the JVM, so every walk is compared with what the table holds then.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("credit's operator collections over the doors (P17-TSK-035)")
class CreditReadGapsApiDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String SEEDED_SCORECARD = "0190a1b2-5c0e-7000-8000-00000000c001";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @DisplayName("the decision requests walk once, newest first, two a page - exactly the table's rows in its declared order -"
            + " each serving its party and the amount asked, every page recorded credit.DecisionRequestsRead")
    void theDecisionRequestsWalkOnce() throws Exception {
        OperatorReadClient client = new OperatorReadClient(port, authorization);
        OperatorReadClient.Operator investigator = client.operator(RoleName.CREDIT_POLICY_OFFICER);
        List<UUID> born = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            born.add(CreditFixtures.requestFor(UUID.randomUUID(), CreditProduct.CREDIT_LINE,
                    Money.ofMinorUnits(1_000_00 + i, CurrencyCode.of("EUR"))));
        }

        OperatorReadClient.Walk walk = client.walk("/v1/operator/credit/decision-requests?product=CREDIT_LINE&limit=2",
                investigator.token());
        List<String> expected = column("SELECT id FROM credit.decision_request WHERE product = 'CREDIT_LINE'"
                + " ORDER BY submitted_at DESC, id DESC");
        assertThat(walk.each("requestId")).as("every row once, in the declared order").containsExactlyElementsOf(expected);
        assertThat(walk.each("requestId")).containsAll(born.stream().map(UUID::toString).toList());
        JsonNode first = walk.items().stream().filter(item -> item.path("requestId").asString().equals(born.getFirst().toString()))
                .findFirst().orElseThrow();
        assertThat(first.path("partyId").asString())
                .isEqualTo(column("SELECT party_id FROM credit.decision_request WHERE id = ?", born.getFirst()).getFirst());
        assertThat(first.path("requestedAmount").asString()).isEqualTo("1000.00");
        assertThat(first.path("status").asString()).isEqualTo("SUBMITTED");
        assertThat(first.has("declaredIncome")).isFalse();
        assertThat(recorded("credit.DecisionRequestsRead", investigator.identity())).isEqualTo(walk.pages());

        OperatorReadClient.Walk submitted = client.walk("/v1/operator/credit/decision-requests?status=SUBMITTED&product=CREDIT_LINE",
                investigator.token());
        assertThat(submitted.each("requestId")).containsExactlyElementsOf(column("SELECT id FROM credit.decision_request"
                + " WHERE product = 'CREDIT_LINE' AND status = 'SUBMITTED' ORDER BY submitted_at DESC, id DESC"));
    }

    @Test
    @DisplayName("the policy and scorecard versions walk once in the table's order, each naming its proposer, never its decider;"
            + " one of each read by its identifier with its policy or points table; every serving recorded")
    void theVersionsWalkAndReadOne() throws Exception {
        OperatorReadClient client = new OperatorReadClient(port, authorization);
        OperatorReadClient.Operator investigator = client.operator(RoleName.CREDIT_POLICY_OFFICER);

        OperatorReadClient.Walk policies = client.walk("/v1/operator/credit/policies/versions?limit=1", investigator.token());
        assertThat(policies.each("versionId")).isNotEmpty().containsExactlyElementsOf(
                column("SELECT id FROM credit.credit_policy_version ORDER BY proposed_at DESC, id DESC"));
        assertThat(policies.each("proposedBy")).containsExactlyElementsOf(
                column("SELECT proposed_by FROM credit.credit_policy_version ORDER BY proposed_at DESC, id DESC"));
        assertThat(policies.items()).allSatisfy(item -> assertThat(item.has("decidedBy")).isFalse());

        String one = policies.each("versionId").getFirst();
        JsonNode version = json(client.get("/v1/operator/credit/policies/versions/" + one, investigator.token()), 200);
        assertThat(version.path("version").path("versionId").asString()).isEqualTo(one);
        assertThat(version.path("policy").path("id").asString()).isEqualTo(one);
        assertThat(version.path("policy").path("rules").size()).isPositive();

        OperatorReadClient.Walk scorecards = client.walk("/v1/operator/credit/scorecards", investigator.token());
        assertThat(scorecards.each("versionId")).contains(SEEDED_SCORECARD).containsExactlyElementsOf(
                column("SELECT id FROM credit.scorecard_model_version ORDER BY proposed_at DESC, id DESC"));
        JsonNode scorecard = json(client.get("/v1/operator/credit/scorecards/" + SEEDED_SCORECARD, investigator.token()), 200);
        assertThat(scorecard.path("version").path("family").asString()).isEqualTo("RETAIL_SCORECARD");
        assertThat(scorecard.path("basePoints").asInt()).isEqualTo(Integer.parseInt(column(
                "SELECT base_points FROM credit.scorecard_model_version WHERE id = ?", UUID.fromString(SEEDED_SCORECARD)).getFirst()));
        assertThat(scorecard.path("attributes").size()).isEqualTo(Integer.parseInt(column("SELECT count(DISTINCT attribute_code)"
                + " FROM credit.scorecard_band WHERE model_version_id = ?", UUID.fromString(SEEDED_SCORECARD)).getFirst()));

        assertThat(recorded("credit.PolicyRead", investigator.identity())).isEqualTo(policies.pages() + 1);
        assertThat(recorded("credit.ScorecardRead", investigator.identity())).isEqualTo(scorecards.pages() + 1);

        for (String unknown : List.of(IDS.next().toString(), UUID.randomUUID().toString(), "not-an-id")) {
            assertThat(problem(client.get("/v1/operator/credit/scorecards/" + unknown, investigator.token()), 404))
                    .isEqualTo("credit.NotFound");
            assertThat(problem(client.get("/v1/operator/credit/policies/versions/" + unknown, investigator.token()), 404))
                    .isEqualTo("credit.NotFound");
        }
    }

    @Test
    @DisplayName("every route is closed to an underwriter (403) and to no session (401); a limit over 200 and another"
            + " collection's cursor are 422 api.ValidationFailed, each recorded door.ActRefused")
    void theDoorsAreClosed() throws Exception {
        OperatorReadClient client = new OperatorReadClient(port, authorization);
        OperatorReadClient.Operator underwriter = client.operator(RoleName.UNDERWRITER);
        OperatorReadClient.Operator investigator = client.operator(RoleName.CREDIT_POLICY_OFFICER);
        for (String route : List.of("/v1/operator/credit/decision-requests", "/v1/operator/credit/policies/versions",
                "/v1/operator/credit/policies/versions/" + IDS.next(), "/v1/operator/credit/scorecards",
                "/v1/operator/credit/scorecards/" + SEEDED_SCORECARD)) {
            assertThat(client.get(route, underwriter.token()).statusCode()).as(route).isEqualTo(403);
            assertThat(client.get(route, null).statusCode()).as(route).isEqualTo(401);
        }
        assertThat(problem(client.get("/v1/operator/credit/decision-requests?limit=201", investigator.token()), 422))
                .isEqualTo("api.ValidationFailed");
        // Two policies are seeded (credit V008), so a page of one always carries a cursor - the policy versions' own.
        String policiesCursor = json(client.get("/v1/operator/credit/policies/versions?limit=1", investigator.token()), 200)
                .path("nextCursor").asString();
        assertThat(policiesCursor).isNotEmpty();
        assertThat(problem(client.get("/v1/operator/credit/decision-requests?cursor=" + policiesCursor, investigator.token()),
                422)).isEqualTo("api.ValidationFailed");
        assertThat(recorded("door.ActRefused", investigator.identity()))
                .as("each refusal at a credit door recorded with its outcome (P15-TSK-039)").isEqualTo(2);
        assertThat(recorded("credit.DecisionRequestsRead", underwriter.identity())).isZero();
    }
}
