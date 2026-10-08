package com.finapp.app.credit;

import static com.finapp.app.credit.CreditWorld.BALANCES;
import static com.finapp.app.credit.CreditWorld.count;
import static com.finapp.app.credit.CreditWorld.decide;
import static com.finapp.app.credit.CreditWorld.deciding;
import static com.finapp.app.credit.CreditWorld.eur;
import static com.finapp.app.credit.CreditWorld.evaluated;
import static com.finapp.app.credit.CreditWorld.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditEvidenceCipher;
import com.finapp.credit.CreditPolicy;
import com.finapp.credit.CreditPolicyAdministration;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.Decider;
import com.finapp.credit.PolicyEffect;
import com.finapp.credit.PolicyFigure;
import com.finapp.credit.PolicyOperator;
import com.finapp.credit.ReasonCode;
import com.finapp.credit.UnavailableFallback;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Decision retrieval and the adverse-action explanation over HTTP (`P10-TSK-017`; {@code INV-CRD-02},
 * {@code INV-CRD-07}, {@code INV-AUD-01}, {@code INV-AUD-02}): the customer told the outcome and the reasons' texts in
 * order and never an internal figure; the investigator given the full explanation from rows alone and the raw evidence
 * with a reason, each serving audited; every door closed to the wrong caller.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): the suite brings its own policies into force - a
 * line policy that always cites a thin file before the exposure limit, so a decline's reasons have an order that is not
 * the alphabet's.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings("try")
@DisplayName("decision retrieval and the adverse-action explanation (P10-TSK-017)")
class DecisionExplanationDatabaseTest {

    private static final Duration WEEK = Duration.ofDays(7);
    private static boolean policies;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private CreditEvidenceCipher applicationCipher;

    private CreditTestClient client;

    @BeforeEach
    void world() {
        client = new CreditTestClient(port);
        if (!policies) {
            CreditWorld.seedsInForce();
            CreditWorld.inForce(CreditProduct.PERSONAL_LOAN, 900, false);
            lineWithAThinFileRule();
            policies = true;
        }
        CreditWorld.cipher = applicationCipher;
    }

    // ------------------------------------------------------------------ the customer

    @Test
    @DisplayName("a decline shows its reasons' customer texts in ordinal order - not the alphabet's")
    void theCustomerSeesReasonTextsInOrder() throws Exception {
        CreditTestClient.Customer customer = client.customer(true);
        BALANCES.put(customer.party(), 3_000_000L);
        UUID line = decided(customer, CreditProduct.CREDIT_LINE, eur(200_000));
        HttpResponse<String> read = client.read(customer, line.toString());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(CreditTestClient.field(read.body(), "outcome")).isEqualTo("DECLINED");
        assertThat(read.body()).contains("\"reasons\":[\"" + ReasonCode.INSUFFICIENT_CREDIT_HISTORY.customerText() + "\",\""
                + ReasonCode.EXPOSURE_LIMIT.customerText() + "\"]");
        assertThat(read.body()).contains("\"approvedAmount\":null");
    }

    @Test
    @DisplayName("an approval below its request carries the cap's reason - the auto-approval ceiling's text - with the"
            + " approved amount, the term and the validity")
    void aCappedApprovalCarriesTheCapReason() throws Exception {
        CreditTestClient.Customer customer = client.customer(true);
        UUID loan = decided(customer, CreditProduct.PERSONAL_LOAN, eur(1_200_000));
        HttpResponse<String> read = client.read(customer, loan.toString());
        assertThat(CreditTestClient.field(read.body(), "outcome")).isEqualTo("APPROVED");
        assertThat(CreditTestClient.field(read.body(), "approvedAmount")).isEqualTo("10000.00");
        assertThat(read.body()).contains("\"approvedTermMonths\":36");
        assertThat(java.time.Instant.parse(CreditTestClient.field(read.body(), "validUntil")).toEpochMilli())
                .isEqualTo(Long.parseLong(scalar("SELECT floor(extract(epoch FROM valid_until) * 1000)::bigint::text"
                        + " FROM credit.credit_decision WHERE decision_request_id = ?", loan)));
        assertThat(read.body()).contains("\"reasons\":[\"" + ReasonCode.AUTO_APPROVAL_CEILING.customerText() + "\"]");
    }

    @Test
    @DisplayName("no internal figure reaches the customer - a needle per score, attribute value, threshold, risk signal and"
            + " payload, absent from the request and the profile alike")
    void noInternalFigureReachesTheCustomer() throws Exception {
        CreditTestClient.Customer customer = client.customer(true);
        BALANCES.put(customer.party(), 987_654_321L);
        UUID line = decided(customer, CreditProduct.CREDIT_LINE, eur(200_000));
        UUID loan = decided(customer, CreditProduct.PERSONAL_LOAN, eur(1_000_000));
        String score = scalar("SELECT score::text FROM credit.credit_assessment WHERE decision_request_id = ?"
                + " ORDER BY assessed_at DESC LIMIT 1", loan);
        List<String> bodies = List.of(client.read(customer, line.toString()).body(),
                client.read(customer, loan.toString()).body(), client.get("/v1/me/credit/profile", customer.token()).body());
        List<String> needles = List.of(
                "9876543",                     // a bureau attribute value - the total balance
                "\"score\"", "\"" + score + "\"", // the score, as a key or a value
                "40000", "20000", "\"2500",    // thresholds: the exposure limits and the line's ceiling
                "NOT_ASSESSED", "RISK_SIGNAL", // the risk signal
                "BUREAU_", "FINDATA_",         // attribute codes
                "answer",                      // the source's payload
                "CRD-", "EXPOSURE_LIMIT", "THIN_FILE", "rule");
        for (String body : bodies) {
            for (String needle : needles) {
                assertThat(body).as("needle %s", needle).doesNotContain(needle);
            }
        }
    }

    @Test
    @DisplayName("the profile shows the sources on file and the current decisions - and another party's request and"
            + " profile are not found")
    void anotherPartysRequestAndProfileAreNotFound() throws Exception {
        CreditTestClient.Customer owner = client.customer(true);
        CreditTestClient.Customer stranger = client.customer(true);
        UUID loan = decided(owner, CreditProduct.PERSONAL_LOAN, eur(1_000_000));
        HttpResponse<String> profile = client.get("/v1/me/credit/profile", owner.token());
        assertThat(profile.statusCode()).as(profile.body()).isEqualTo(200);
        assertThat(profile.body()).contains("\"sourceKind\":\"BUREAU\"").contains("\"sourceKind\":\"FINANCIAL_DATA\"")
                .contains("\"requestId\":\"" + loan + "\"").contains("\"outcome\":\"APPROVED\"");
        HttpResponse<String> theirs = client.read(stranger, loan.toString());
        assertThat(theirs.statusCode()).isEqualTo(404);
        assertThat(theirs.body()).contains("credit.NotFound");
        String strangers = client.get("/v1/me/credit/profile", stranger.token()).body();
        assertThat(strangers).doesNotContain(loan.toString()).contains("\"sources\":[]").contains("\"decisions\":[]");
    }

    // ------------------------------------------------------------------ the investigator

    @Test
    @DisplayName("every decision explains from its rows alone: the snapshot's attributes with provenance and retrieval"
            + " times, the pinned rules and which fired in order, the reasons, the outcome, when and by whom")
    void everyDecisionExplainsFromRowsAlone() throws Exception {
        String officer = sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        CreditTestClient.Customer customer = client.customer(true);
        BALANCES.put(customer.party(), 3_000_000L);
        for (UUID request : List.of(decided(customer, CreditProduct.CREDIT_LINE, eur(200_000)),
                decided(customer, CreditProduct.PERSONAL_LOAN, eur(1_000_000)))) {
            String decision = scalar("SELECT id::text FROM credit.credit_decision WHERE decision_request_id = ?", request);
            HttpResponse<String> explained = client.get("/v1/operator/credit/decisions/" + decision + "/explanation", officer);
            assertThat(explained.statusCode()).as(explained.body()).isEqualTo(200);
            String body = explained.body();
            assertThat(CreditTestClient.field(body, "outcome"))
                    .isEqualTo(scalar("SELECT outcome FROM credit.credit_decision WHERE id = ?::uuid", decision));
            assertThat(CreditTestClient.field(body, "snapshotSha256")).isEqualTo(scalar("SELECT encode(snapshot_sha256, 'hex')"
                    + " FROM credit.credit_decision WHERE id = ?::uuid", decision));
            String codes = scalar("SELECT coalesce(string_agg('\"' || reason_code || '\"', ',' ORDER BY ordinal), '')"
                    + " FROM credit.credit_decision_reason WHERE decision_id = ?::uuid", decision);
            assertThat(body).contains("\"reasonCodes\":[" + codes + "]");
            long rules = count("SELECT count(*) FROM credit.policy_evaluation_rule r JOIN credit.policy_evaluation e"
                    + " ON e.id = r.evaluation_id JOIN credit.credit_assessment a ON a.id = e.assessment_id"
                    + " JOIN credit.credit_decision d ON d.snapshot_id = a.snapshot_id WHERE d.id = ?::uuid", decision);
            assertThat(body.split("\"ruleCode\"", -1).length - 1).as("every rule of the pinned policy").isEqualTo((int) rules);
            long fired = count("SELECT count(*) FROM credit.policy_evaluation_rule r JOIN credit.policy_evaluation e"
                    + " ON e.id = r.evaluation_id JOIN credit.credit_assessment a ON a.id = e.assessment_id"
                    + " JOIN credit.credit_decision d ON d.snapshot_id = a.snapshot_id WHERE d.id = ?::uuid AND r.triggered",
                    decision);
            assertThat(body.split("\"state\":\"TRIGGERED\"", -1).length - 1).isEqualTo((int) fired);
            assertThat(body.split("\"code\":\"", -1).length - 1).as("every attribute the snapshot froze")
                    .isEqualTo(CreditAttributeCode.values().length);
            assertThat(body).contains("\"provenance\":\"RECORD\"").contains("\"decidedByType\":\"SYSTEM\"");
            assertThat(body).doesNotContain("\"retrievedAt\":null,\"provenance\":\"RECORD\"");
        }
    }

    @Test
    @DisplayName("every serving is audited in its own transaction - each explanation read, each evidence read, attempted"
            + " ones included")
    void everyServingIsAudited() throws Exception {
        String officer = sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        CreditTestClient.Customer customer = client.customer(true);
        UUID loan = decided(customer, CreditProduct.PERSONAL_LOAN, eur(1_000_000));
        String decision = scalar("SELECT id::text FROM credit.credit_decision WHERE decision_request_id = ?", loan);
        for (int i = 0; i < 2; i++) {
            assertThat(client.get("/v1/operator/credit/decisions/" + decision + "/explanation", officer).statusCode())
                    .isEqualTo(200);
        }
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ExplanationRead'"
                + " AND target_id = ?", decision)).isEqualTo(2);
        String record = bureauRecordOf(loan);
        assertThat(evidence(officer, record, "a dispute review").statusCode()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.EvidenceRead'"
                + " AND target_id = ? AND outcome = 'SUCCEEDED' AND reason = 'a dispute review'", record)).isEqualTo(1);
    }

    @Test
    @DisplayName("evidence without a reason is 422 credit.ReasonRequired - nothing read, nothing recorded")
    void evidenceWithoutAReasonIs422() throws Exception {
        String officer = sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        CreditTestClient.Customer customer = client.customer(true);
        String record = bureauRecordOf(decided(customer, CreditProduct.PERSONAL_LOAN, eur(1_000_000)));
        for (String body : List.of("{\"reason\":\"\"}", "{\"reason\":\"   \"}", "{}")) {
            HttpResponse<String> refused = client.post("/v1/operator/credit/records/" + record + "/evidence-read", body,
                    officer, null);
            assertThat(refused.statusCode()).as(body + " " + refused.body()).isEqualTo(422);
            assertThat(refused.body()).contains("credit.ReasonRequired");
        }
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.EvidenceRead'"
                + " AND target_id = ?", record)).isZero();
    }

    @Test
    @DisplayName("evidence is readable only through the definer: the application cannot select its content; the door"
            + " serves it decrypted, and evidence its key cannot read is 503, recorded FAILED")
    void evidenceIsReadableOnlyThroughTheDefiner() throws Exception {
        String officer = sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        CreditTestClient.Customer customer = client.customer(true);
        String readable = bureauRecordOf(decided(customer, CreditProduct.PERSONAL_LOAN, eur(1_000_000)));
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
            assertThat(statement.executeQuery("SELECT count(*) FROM credit.credit_evidence").next()).isTrue();
            assertThatRefused(app, "SELECT content_ciphertext FROM credit.credit_evidence LIMIT 1", "42501");
            assertThatRefused(app, "SELECT * FROM credit.credit_evidence LIMIT 1", "42501");
        }
        HttpResponse<String> served = evidence(officer, readable, "a complaint");
        assertThat(served.statusCode()).as(served.body()).isEqualTo(200);
        assertThat(new String(Base64.getDecoder().decode(CreditTestClient.field(served.body(), "contentBase64")),
                StandardCharsets.UTF_8)).isEqualTo("{\"answer\":\"test\"}");
        CreditWorld.cipher = new CreditEvidenceCipher(new byte[32], 1, new java.security.SecureRandom());
        CreditTestClient.Customer other = client.customer(true);
        String unreadable = bureauRecordOf(decided(other, CreditProduct.PERSONAL_LOAN, eur(1_000_000)));
        HttpResponse<String> refused = evidence(officer, unreadable, "a complaint");
        assertThat(refused.statusCode()).isEqualTo(503);
        assertThat(refused.body()).contains("credit.EvidenceUnreadable").doesNotContain("answer");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.EvidenceRead'"
                + " AND target_id = ? AND outcome = 'FAILED'", unreadable)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ every door, every wrong caller

    @Test
    @DisplayName("every door's negatives: no session 401; a customer, and an underwriter without CREDIT_INVESTIGATE,"
            + " 403 on the operator doors - nothing served, nothing recorded")
    void everyDoorRefusesTheWrongCaller() throws Exception {
        CreditTestClient.Customer customer = client.customer(true);
        UUID loan = decided(customer, CreditProduct.PERSONAL_LOAN, eur(1_000_000));
        String decision = scalar("SELECT id::text FROM credit.credit_decision WHERE decision_request_id = ?", loan);
        String record = bureauRecordOf(loan);
        String explanation = "/v1/operator/credit/decisions/" + decision + "/explanation";
        String evidence = "/v1/operator/credit/records/" + record + "/evidence-read";
        assertThat(client.get("/v1/me/credit/profile", null).statusCode()).isEqualTo(401);
        assertThat(client.get(explanation, null).statusCode()).isEqualTo(401);
        assertThat(client.post(evidence, "{\"reason\":\"x\"}", null, null).statusCode()).isEqualTo(401);
        String underwriter = sessionWith(RoleName.UNDERWRITER);
        for (String bearer : List.of(customer.token(), underwriter)) {
            assertThat(client.get(explanation, bearer).statusCode()).isEqualTo(403);
            assertThat(client.post(evidence, "{\"reason\":\"x\"}", bearer, null).statusCode()).isEqualTo(403);
        }
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation IN ('credit.ExplanationRead',"
                + " 'credit.EvidenceRead') AND target_id IN (?, ?)", decision, record)).isZero();
        String officer = sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        assertThat(client.get("/v1/operator/credit/decisions/" + CreditWorld.IDS.next() + "/explanation", officer)
                .statusCode()).isEqualTo(404);
        assertThat(client.post("/v1/operator/credit/records/" + CreditWorld.IDS.next() + "/evidence-read",
                "{\"reason\":\"x\"}", officer, null).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ plumbing

    /** {@code customer}'s request for {@code product}, driven through the world and decided. */
    private static UUID decided(CreditTestClient.Customer customer, CreditProduct product, com.finapp.sharedkernel.money.Money amount) {
        UUID request = evaluated(customer.party(), product, amount, WEEK);
        assertThat(decide(deciding(), request)).isEqualTo(Decider.Decided.DECIDED);
        return request;
    }

    private static String bureauRecordOf(UUID request) {
        return scalar("SELECT r.id::text FROM credit.credit_record r JOIN credit.data_request d ON d.id = r.data_request_id"
                + " WHERE d.decision_request_id = ? AND r.source_kind = 'BUREAU'", request);
    }

    private HttpResponse<String> evidence(String officer, String record, String reason) throws Exception {
        return client.post("/v1/operator/credit/records/" + record + "/evidence-read", "{\"reason\":\"" + reason + "\"}",
                officer, null);
    }

    private static void assertThatRefused(Connection connection, String sql, String sqlState) {
        try (Statement statement = connection.createStatement()) {
            statement.executeQuery(sql);
            throw new AssertionError("expected " + sqlState + ": " + sql);
        } catch (SQLException refused) {
            assertThat(refused.getSQLState()).as(sql).isEqualTo(sqlState);
        }
    }

    /** CREDIT_LINE v1 without its score rules, and a rule at ordinal 2 that always cites a thin file - then exposure. */
    private static void lineWithAThinFileRule() {
        CreditWorld.TRANSACTIONS.inTransaction(uow -> {
            CreditPolicy v1 = CreditWorld.POLICIES.policy(uow, CreditWorld.LINE_SEED).orElseThrow().policy();
            List<CreditPolicy.PolicyRule> rules = new ArrayList<>(v1.rules());
            rules.removeIf(rule -> rule.subject() instanceof CreditPolicy.Subject.Figure figure
                    && figure.figure() == PolicyFigure.SCORE);
            rules.add(1, new CreditPolicy.PolicyRule("THIN_FILE",
                    new CreditPolicy.Subject.Attribute(CreditAttributeCode.BUREAU_ACTIVE_ACCOUNTS), PolicyOperator.GE,
                    new CreditPolicy.Operand.IntegerOperand(0), PolicyEffect.DECLINE, Optional.empty(),
                    ReasonCode.INSUFFICIENT_CREDIT_HISTORY));
            CreditPolicy policy = new CreditPolicy(v1.product(), v1.assessmentRateBps(), v1.minimumDisposable(),
                    v1.minimumPaymentRatioBps(), v1.maximumExposure(), v1.maximumDataAge(), UnavailableFallback.REFER,
                    v1.autoApprovalCeiling(), rules);
            CreditPolicyAdministration.Proposed proposed = CreditWorld.POLICY_ADMINISTRATION.propose(uow, policy,
                    "the explanation suite's line", new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE),
                    CorrelationId.generate(CreditWorld.IDS));
            CreditWorld.POLICY_ADMINISTRATION.approve(uow, proposed.id(),
                    new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE), "activated",
                    CorrelationId.generate(CreditWorld.IDS));
            return null;
        });
    }

    /** A registered identity holding {@code role}, and its session. */
    private String sessionWith(RoleName role) throws Exception {
        String login = "inv." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(client.post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                + "\"password\":\"" + CreditTestClient.PASSWORD + "\"}", null, CreditTestClient.key()).statusCode())
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
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(
                        CorrelationId.generate(CreditWorld.IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        HttpResponse<String> session = client.post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + CreditTestClient.PASSWORD + "\"}", null,
                CreditTestClient.key());
        return CreditTestClient.field(session.body(), "sessionToken");
    }
}
