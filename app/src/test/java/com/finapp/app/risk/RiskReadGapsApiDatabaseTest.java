package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.RoleName;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.telemetry.Spans;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.AlertEvidence;
import com.finapp.risk.AlertFolding;
import com.finapp.risk.AlertKind;
import com.finapp.risk.AlertObserver;
import com.finapp.risk.AlertSeverity;
import com.finapp.risk.AlertWindow;
import com.finapp.risk.Alerts;
import com.finapp.risk.CommandRef;
import com.finapp.risk.JdbcRiskAlertStore;
import com.finapp.risk.NewAlert;
import com.finapp.risk.RiskAlert;
import com.finapp.risk.RiskAssessmentId;
import com.finapp.risk.RiskEvaluation;
import com.finapp.risk.RiskEvaluationRequest;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicyAdministration;
import com.finapp.risk.RiskSubjectParties;
import com.finapp.risk.RuleKind;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import com.finapp.risk.VelocityDimension;
import com.finapp.risk.VelocityWindowKey;
import com.finapp.risk.WindowKind;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The risk desks' read gaps over HTTP (`P17-TSK-020`, gaps G-90, G-91, G-92): one alert, as the queue judges it and recorded
 * {@code risk.AlertRead}, the reader's own party's {@code 403 risk.SelfDealingRefused} recorded {@code FAILED}; the assessment
 * search's window, cursor, own-party withholding and refusal, each page recorded {@code risk.AssessmentsSearched}; the policy
 * versions and the screening list versions, metadata and never a person or an entry; and every route refused to the other
 * desk and to no session.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the risk desks' read gaps over HTTP (P17-TSK-020)")
class RiskReadGapsApiDatabaseTest {

    private static final Pattern ITEM_ID = Pattern.compile("\"assessmentId\":\"([0-9a-f-]{36})\"");

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private RiskPolicyAdministration riskPolicyAdministration;
    @Autowired private RiskEvaluation riskEvaluation;

    private RiskOperatorSessions http;

    @BeforeEach
    void sessions() {
        http = new RiskOperatorSessions(port, authorization);
    }

    @Test
    @DisplayName("one alert: 200 as the queue shows it, recorded risk.AlertRead; the reader's own party's 403, recorded FAILED;"
            + " an unknown or malformed id 404; the officer 403, no session 401")
    void oneAlert() throws Exception {
        RiskOperatorSessions.Session reader = http.sessionWith(RoleName.RISK_ANALYST, false);
        RiskAlert alert = openAlert(RiskOperatorSessions.IDS.next());

        HttpResponse<String> read = http.get("/v1/operator/risk/alerts/" + alert.id().value(), reader.token());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.body()).contains("\"alertId\":\"" + alert.id().value() + "\"").contains("\"status\":\"OPEN\"")
                .contains("\"kind\":\"ACCOUNT_TAKEOVER\"");
        assertThat(RiskOperatorSessions.count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.AlertRead'"
                + " AND target_type = 'risk_alert' AND target_id = '" + alert.id().value() + "' AND actor_id = '"
                + reader.identity() + "'")).isEqualTo(1);

        RiskAlert own = openAlert(reader.party());
        HttpResponse<String> refused = http.get("/v1/operator/risk/alerts/" + own.id().value(), reader.token());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).contains("risk.SelfDealingRefused").doesNotContain(reader.party().toString());
        assertThat(RiskOperatorSessions.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'risk.SelfDealingRefused' AND outcome = 'FAILED' AND target_type = 'risk_alert' AND target_id = '"
                + own.id().value() + "'")).isEqualTo(1);

        HttpResponse<String> unknown = http.get("/v1/operator/risk/alerts/" + RiskOperatorSessions.IDS.next(), reader.token());
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("risk.NotFound");
        assertThat(http.get("/v1/operator/risk/alerts/not-an-id", reader.token()).statusCode()).isEqualTo(404);
        RiskOperatorSessions.Session officer = http.sessionWith(RoleName.RISK_OFFICER, false);
        assertThat(http.get("/v1/operator/risk/alerts/" + alert.id().value(), officer.token()).statusCode()).isEqualTo(403);
        assertThat(http.get("/v1/operator/risk/alerts/" + alert.id().value(), null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("the assessment search: a party's newest first, walked by the cursor; the reader's own withheld and refused when"
            + " named, recorded; the window required and bounded; a bad parameter 422; each page recorded; the officer 403")
    void theAssessmentSearch() throws Exception {
        RiskOperatorSessions.Session reader = http.sessionWith(RoleName.RISK_ANALYST, false);
        UUID party = RiskOperatorSessions.IDS.next();
        Instant from = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS);
        List<UUID> sealed = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            sealed.add(seal(party));
        }
        UUID ownAssessment = seal(reader.party());
        String window = "from=" + from + "&to=" + Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.SECONDS);

        HttpResponse<String> first = http.get("/v1/operator/risk/assessments?" + window + "&partyId=" + party + "&limit=2",
                reader.token());
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(ids(first.body())).as("newest first").containsExactly(sealed.get(2), sealed.get(1));
        assertThat(first.body()).contains("\"partyId\":\"" + party + "\"").contains("\"operation\":\"TRANSFER\"")
                .doesNotContain("inputs").doesNotContain("reasons");
        String cursor = RiskOperatorSessions.field(first.body(), "nextCursor");
        HttpResponse<String> second = http.get("/v1/operator/risk/assessments?" + window + "&partyId=" + party + "&cursor="
                + cursor, reader.token());
        assertThat(ids(second.body())).containsExactly(sealed.get(0));
        assertThat(second.body()).doesNotContain("nextCursor");

        List<UUID> everyone = new ArrayList<>();
        String next = null;
        int pages = 0;
        do {
            HttpResponse<String> page = http.get("/v1/operator/risk/assessments?" + window + "&limit=200"
                    + (next == null ? "" : "&cursor=" + next), reader.token());
            assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
            everyone.addAll(ids(page.body()));
            next = page.body().contains("\"nextCursor\"") ? RiskOperatorSessions.field(page.body(), "nextCursor") : null;
            pages++;
        } while (next != null);
        assertThat(everyone).containsAll(sealed).doesNotContain(ownAssessment);
        assertThat(RiskOperatorSessions.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'risk.AssessmentsSearched' AND actor_id = '" + reader.identity() + "'")).isEqualTo(2 + pages);

        HttpResponse<String> mine = http.get("/v1/operator/risk/assessments?" + window + "&partyId=" + reader.party(),
                reader.token());
        assertThat(mine.statusCode()).as(mine.body()).isEqualTo(403);
        assertThat(mine.body()).contains("risk.SelfDealingRefused");
        assertThat(RiskOperatorSessions.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'risk.SelfDealingRefused' AND outcome = 'FAILED' AND target_type = 'risk_assessment_search' AND actor_id = '"
                + reader.identity() + "'")).isEqualTo(1);

        for (String invalid : List.of(
                "partyId=" + party,
                "from=" + from,
                "from=" + from + "&to=" + from.plus(32, ChronoUnit.DAYS),
                "from=" + from + "&to=" + from,
                window + "&cursor=bm90LWEtY3Vyc29y",
                window + "&operation=LOAN",
                window + "&decision=MAYBE",
                window + "&partyId=not-a-party",
                window + "&limit=201")) {
            HttpResponse<String> refused = http.get("/v1/operator/risk/assessments?" + invalid, reader.token());
            assertThat(refused.statusCode()).as("%s: %s", invalid, refused.body()).isEqualTo(422);
            assertThat(refused.body()).contains("api.ValidationFailed");
        }
        RiskOperatorSessions.Session officer = http.sessionWith(RoleName.RISK_OFFICER, false);
        assertThat(http.get("/v1/operator/risk/assessments?" + window, officer.token()).statusCode()).isEqualTo(403);
        assertThat(http.get("/v1/operator/risk/assessments?" + window, null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("the officer's lists: policy versions newest first, PROPOSED the approver's queue; screening list versions and"
            + " one version's metadata - never a person, never an entry; a bad parameter 422, an unknown version 404; the"
            + " analyst 403")
    void theOfficersLists() throws Exception {
        RiskOperatorSessions.Session officer = http.sessionWith(RoleName.RISK_OFFICER, true);
        Actor proposer = new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE);
        String older = propose(proposer);
        String newer = propose(proposer);

        List<String> proposed = new ArrayList<>();
        String next = null;
        do {
            HttpResponse<String> page = http.get("/v1/operator/risk/policies/versions?operation=TRANSFER&status=PROPOSED"
                    + "&limit=200" + (next == null ? "" : "&cursor=" + next), officer.token());
            assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
            assertThat(page.body()).doesNotContain(proposer.id()).doesNotContain("\"status\":\"ACTIVE\"");
            Matcher version = Pattern.compile("\"versionId\":\"([0-9a-f-]{36})\"").matcher(page.body());
            while (version.find()) {
                proposed.add(version.group(1));
            }
            next = page.body().contains("\"nextCursor\"") ? RiskOperatorSessions.field(page.body(), "nextCursor") : null;
        } while (next != null);
        assertThat(proposed).as("newest first").containsSubsequence(newer, older);
        HttpResponse<String> one = http.get("/v1/operator/risk/policies/versions?limit=1", officer.token());
        assertThat(one.statusCode()).as(one.body()).isEqualTo(200);
        assertThat(one.body()).contains("\"nextCursor\"");

        String label = "p17-020-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> recorded = http.post("/v1/operator/risk/screening-lists", "{\"list\":\"PEP\",\"version\":\""
                + label + "\",\"reason\":\"the read gaps suite\"}", officer.token());
        assertThat(recorded.statusCode()).as(recorded.body()).isEqualTo(201);
        String listVersion = RiskOperatorSessions.field(recorded.body(), "id");
        HttpResponse<String> lists = http.get("/v1/operator/risk/screening-lists?list=PEP&limit=200", officer.token());
        assertThat(lists.statusCode()).as(lists.body()).isEqualTo(200);
        assertThat(lists.body()).contains("\"id\":\"" + listVersion + "\"").contains("\"version\":\"" + label + "\"")
                .doesNotContain(officer.identity().toString()).doesNotContain("recordedBy").doesNotContain("entries");
        HttpResponse<String> read = http.get("/v1/operator/risk/screening-lists/" + listVersion, officer.token());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.body()).startsWith("{\"id\":\"" + listVersion + "\",\"list\":\"PEP\",\"version\":\"" + label + "\",")
                .contains("\"status\":\"RECORDED\"").contains("\"recordedAt\":")
                .doesNotContain(officer.identity().toString()).doesNotContain("entries");
        assertThat(keys(read.body())).as("metadata, and only that")
                .containsExactly("id", "list", "version", "ordinal", "status", "recordedAt", "activatedAt", "retiredAt");

        assertThat(http.get("/v1/operator/risk/screening-lists/" + RiskOperatorSessions.IDS.next(), officer.token())
                .statusCode()).isEqualTo(404);
        assertThat(http.get("/v1/operator/risk/screening-lists/not-an-id", officer.token()).statusCode()).isEqualTo(404);
        assertThat(http.get("/v1/operator/risk/screening-lists?list=WATCH", officer.token()).statusCode()).isEqualTo(422);
        assertThat(http.get("/v1/operator/risk/policies/versions?status=DRAFT", officer.token()).statusCode()).isEqualTo(422);

        RiskOperatorSessions.Session analyst = http.sessionWith(RoleName.RISK_ANALYST, false);
        for (String path : List.of("/v1/operator/risk/policies/versions", "/v1/operator/risk/screening-lists",
                "/v1/operator/risk/screening-lists/" + listVersion)) {
            assertThat(http.get(path, analyst.token()).statusCode()).as(path).isEqualTo(403);
            assertThat(http.get(path, null).statusCode()).as(path).isEqualTo(401);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static List<UUID> ids(String body) {
        List<UUID> ids = new ArrayList<>();
        Matcher id = ITEM_ID.matcher(body);
        while (id.find()) {
            ids.add(UUID.fromString(id.group(1)));
        }
        return ids;
    }

    private static List<String> keys(String object) {
        List<String> keys = new ArrayList<>();
        Matcher key = Pattern.compile("\"([A-Za-z]+)\":").matcher(object);
        while (key.find()) {
            keys.add(key.group(1));
        }
        return keys;
    }

    /** A transfer of {@code party}'s judged by the composition's T-eval in its own transaction; the assessment's id. */
    private UUID seal(UUID party) throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            RiskEvaluation.Assessed assessed = riskEvaluation.evaluate(app, RiskEvaluationRequest.builder(
                            RiskOperation.TRANSFER, CommandRef.of(RiskOperatorSessions.IDS.next()),
                            Money.ofMinorUnits(100_00, CurrencyCode.of("EUR")), party,
                            CorrelationId.generate(RiskOperatorSessions.IDS), CausationId.of("read-gaps"))
                    .sourceAccount(RiskOperatorSessions.IDS.next()).destinationAccount(RiskOperatorSessions.IDS.next()).build());
            app.commit();
            return assessed.assessmentId().value();
        }
    }

    /** A TRANSFER version proposed by {@code proposer} - never activated; its id. */
    private String propose(Actor proposer) {
        return RiskOperatorSessions.inOneTransaction(uow -> riskPolicyAdministration.propose(uow,
                RiskPolicyFixtures.v1(RiskOperation.TRANSFER), "the read gaps suite's version", proposer,
                CorrelationId.generate(RiskOperatorSessions.IDS))).id().value().toString();
    }

    /** An open alert whose subject is the party {@code party} - raised by the machine with no folding. */
    private RiskAlert openAlert(UUID party) {
        Alerts unfolded = new Alerts(new JdbcRiskAlertStore(), AlertFolding.NONE, (uow, actor) -> Optional.empty(),
                RiskSubjectParties.NONE, new JdbcAuditWriter(), new JdbcOutboxWriter(), RiskOperatorSessions.IDS,
                java.time.Clock.systemUTC(), Spans.NONE, AlertObserver.NONE);
        SignalSubject subject = SignalSubject.of(SubjectKind.PARTY, party);
        NewAlert alert = NewAlert.ofRule(AlertKind.ACCOUNT_TAKEOVER, AlertSeverity.HIGH, subject,
                RuleKind.RECENT_CREDENTIAL_CHANGE, AlertWindow.of(new VelocityWindowKey(VelocityDimension.PARTY,
                        subject.ref(), WindowKind.D1, Instant.now().truncatedTo(ChronoUnit.DAYS))),
                com.finapp.risk.RiskPolicyVersionId.of(UUID.fromString(
                        propose(new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE)))),
                AlertEvidence.assessment(RiskAssessmentId.of(RiskOperatorSessions.IDS.next())),
                CorrelationId.generate(RiskOperatorSessions.IDS), CausationId.of("read-gaps"));
        return RiskOperatorSessions.inOneTransaction(uow -> unfolded.raise(uow, alert)).alert();
    }
}
