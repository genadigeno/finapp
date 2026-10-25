package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.RoleName;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The investigator's reads, end to end (`P13-TSK-018`; {@code PHASE_13_PLAN.md} sections 9, 11; {@code INV-RSK-08}):
 * the case queue pages oldest first after a cursor, each page recorded {@code risk.CaseRead}; a case's file answers its
 * head, alerts, actions, history, downstream actions and evidence by reference, recorded {@code risk.CaseRead}; the
 * analyst's own party's case is withheld from the queue and its file {@code 403 risk.SelfDealingRefused}, recorded
 * {@code FAILED}; an unknown or malformed case the uniform {@code 404 risk.NotFound}; a filter outside the vocabulary
 * and a cursor naming no row {@code 422 risk.QueueRequestInvalid}; the alert queue {@code 200}, recorded
 * {@code risk.AlertRead}; a risk officer {@code 403}, no session {@code 401}. <strong>Written and owed</strong> (C5).
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the risk queues and the case file over HTTP (P13-TSK-018)")
class QueueApiTest {

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    private RiskOperatorSessions http;

    @BeforeEach
    void sessions() {
        http = new RiskOperatorSessions(port, authorization);
    }

    @Test
    @DisplayName("the case queue: two cases opened after a marker page oldest first after it, one a page; each page"
            + " recorded risk.CaseRead; the file 200 with its parts and evidence by reference, recorded")
    void theCaseQueueAndTheFile() throws Exception {
        RiskOperatorSessions.Session opener = http.sessionWith(RoleName.RISK_ANALYST, false);
        String marker = open(opener, RiskOperatorSessions.IDS.next());
        String first = open(opener, RiskOperatorSessions.IDS.next());
        String second = open(opener, RiskOperatorSessions.IDS.next());
        RiskOperatorSessions.Session reader = http.sessionWith(RoleName.RISK_ANALYST, false);

        HttpResponse<String> page = http.get("/v1/operator/risk/cases?kind=FRAUD&limit=1&after=" + marker,
                reader.token());
        assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
        assertThat(page.body()).contains("\"caseId\":\"" + first + "\"").doesNotContain("takenBy");
        String next = RiskOperatorSessions.field(page.body(), "next");
        assertThat(next).isEqualTo(first);
        HttpResponse<String> following = http.get("/v1/operator/risk/cases?kind=FRAUD&limit=1&after=" + next,
                reader.token());
        assertThat(following.body()).contains("\"caseId\":\"" + second + "\"");
        assertThat(RiskOperatorSessions.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'risk.CaseRead' AND target_type = 'risk_case_queue' AND actor_id = '" + reader.identity() + "'"))
                .isEqualTo(2);

        HttpResponse<String> file = http.get("/v1/operator/risk/cases/" + first, reader.token());
        assertThat(file.statusCode()).as(file.body()).isEqualTo(200);
        assertThat(file.body()).contains("\"riskCase\":{").contains("\"alerts\":[").contains("\"actions\":[")
                .contains("\"history\":[").contains("\"riskActions\":[").contains("\"evidence\":[")
                .doesNotContain(opener.identity().toString());
        assertThat(RiskOperatorSessions.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'risk.CaseRead' AND target_type = 'risk_case' AND target_id = '" + first + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("self-dealing: the analyst's own party's case is withheld from the queue and its file is 403"
            + " risk.SelfDealingRefused, recorded FAILED; the uniform 404; the queue's 422s; another desk 403, no"
            + " session 401; the alert queue 200, recorded risk.AlertRead")
    void theRefusals() throws Exception {
        RiskOperatorSessions.Session reader = http.sessionWith(RoleName.RISK_ANALYST, false);
        RiskOperatorSessions.Session opener = http.sessionWith(RoleName.RISK_ANALYST, false);
        String marker = open(opener, RiskOperatorSessions.IDS.next());
        String own = open(opener, reader.party());

        HttpResponse<String> page = http.get("/v1/operator/risk/cases?kind=FRAUD&after=" + marker, reader.token());
        assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
        assertThat(page.body()).doesNotContain(own);
        HttpResponse<String> file = http.get("/v1/operator/risk/cases/" + own, reader.token());
        assertThat(file.statusCode()).as(file.body()).isEqualTo(403);
        assertThat(file.body()).contains("risk.SelfDealingRefused").doesNotContain(reader.party().toString());
        assertThat(RiskOperatorSessions.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'risk.SelfDealingRefused' AND outcome = 'FAILED' AND target_id = '" + own + "'")).isEqualTo(1);

        assertThat(http.get("/v1/operator/risk/cases/" + RiskOperatorSessions.IDS.next(), reader.token()).statusCode())
                .isEqualTo(404);
        assertThat(http.get("/v1/operator/risk/cases/not-an-id", reader.token()).statusCode()).isEqualTo(404);
        HttpResponse<String> badStatus = http.get("/v1/operator/risk/cases?status=CLOSED", reader.token());
        assertThat(badStatus.statusCode()).as(badStatus.body()).isEqualTo(422);
        assertThat(badStatus.body()).contains("risk.QueueRequestInvalid");
        assertThat(http.get("/v1/operator/risk/alerts?after=" + RiskOperatorSessions.IDS.next(), reader.token())
                .statusCode()).isEqualTo(422);
        assertThat(http.get("/v1/operator/risk/alerts?limit=0", reader.token()).statusCode()).isEqualTo(422);

        RiskOperatorSessions.Session officer = http.sessionWith(RoleName.RISK_OFFICER, false);
        assertThat(http.get("/v1/operator/risk/cases", officer.token()).statusCode()).isEqualTo(403);
        assertThat(http.get("/v1/operator/risk/alerts", null).statusCode()).isEqualTo(401);

        HttpResponse<String> alerts = http.get("/v1/operator/risk/alerts?limit=500", reader.token());
        assertThat(alerts.statusCode()).as(alerts.body()).isEqualTo(200);
        assertThat(alerts.body()).contains("\"alerts\":[");
        assertThat(RiskOperatorSessions.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'risk.AlertRead' AND actor_id = '" + reader.identity() + "'")).isEqualTo(1);
    }

    /** A FRAUD case of {@code party} opened by hand by {@code opener}; its id. */
    private String open(RiskOperatorSessions.Session opener, UUID party) throws Exception {
        HttpResponse<String> opened = http.post("/v1/operator/risk/cases", "{\"kind\":\"FRAUD\",\"subjectKind\":"
                + "\"PARTY\",\"subjectRef\":\"" + party + "\",\"reason\":\"the queue suite\"}", opener.token());
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        return RiskOperatorSessions.field(opened.body(), "caseId");
    }
}
