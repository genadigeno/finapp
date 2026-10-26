package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.RoleName;
import com.finapp.risk.CommandRef;
import com.finapp.risk.MonitoredCounterpartyKind;
import com.finapp.risk.MonitoredDirection;
import com.finapp.risk.MonitoredEntry;
import com.finapp.risk.MonitoredKind;
import com.finapp.risk.MonitoredSubjectKind;
import com.finapp.risk.MonitoredTransaction;
import com.finapp.risk.MonitoredTransactionId;
import com.finapp.risk.MonitoredTransactionStore;
import com.finapp.risk.MonitoringWatermark;
import com.finapp.risk.RiskEvaluation;
import com.finapp.risk.RiskEvaluationRequest;
import com.finapp.risk.RiskOperation;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * The proofs' doors end to end (`P13-TSK-023`; {@code PHASE_13_PLAN.md} sections 9, 11, 13.3; {@code DESIGN-023} D7-D9;
 * `-018`'s {@code QueueApiTest} shape): the four operations reports served {@code 200} for today with their censuses
 * and recorded {@code risk.ReportRead} each; a date that is no calendar date {@code 422 api.ValidationFailed},
 * unrecorded; the day's replay {@code IDENTICAL} over two assessments the composition sealed, recorded
 * {@code risk.Replayed} against the day, a blank reason {@code 422}; the evidence door resolving a journal entry the case
 * holds - its amount, currency and scale - recorded {@code risk.CaseRead}, an unheld reference {@code 404}, the
 * analyst's own party's case {@code 403}; another desk {@code 403} and no session {@code 401} on every door.
 *
 * <p>Written and owed under C5 (the standing criterion-7 deviation): the database tier is not run per task.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the risk proofs' doors: reports, the day's replay and the evidence door, audited (P13-TSK-023)")
class RiskReportsApiTest {

    private static final String[] REPORTS = {"decisions", "alerts", "cases", "velocity"};

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private RiskEvaluation riskEvaluation;
    @Autowired private MonitoredTransactionStore monitoredTransactionStore;

    private RiskOperatorSessions http;

    @BeforeEach
    void sessions() {
        http = new RiskOperatorSessions(port, authorization);
    }

    @Test
    @DisplayName("each report for today is 200, known, carrying its census, and recorded risk.ReportRead once; a date"
            + " that is no calendar date is 422 api.ValidationFailed and recorded by nothing")
    void theReports() throws Exception {
        RiskOperatorSessions.Session analyst = http.sessionWith(RoleName.RISK_ANALYST, false);
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        for (String report : REPORTS) {
            HttpResponse<String> served = http.get("/v1/operator/reports/risk/" + report + "?date=" + today,
                    analyst.token());
            assertThat(served.statusCode()).as(report + ": " + served.body()).isEqualTo(200);
            assertThat(served.body()).contains("\"known\":true").contains("\"date\":\"" + today + "\"");
            assertThat(RiskOperatorSessions.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                    + " 'risk.ReportRead' AND target_type = 'risk_report' AND target_id = '" + report + "' AND actor_id"
                    + " = '" + analyst.identity() + "' AND change_summary LIKE 'report=" + report + ", date=" + today
                    + "%'")).as(report).isEqualTo(1);
        }
        HttpResponse<String> decisions = http.get("/v1/operator/reports/risk/decisions?date=" + today, analyst.token());
        assertThat(decisions.body()).contains("\"census\":{\"verdict\":");
        HttpResponse<String> velocity = http.get("/v1/operator/reports/risk/velocity?date=" + today, analyst.token());
        assertThat(velocity.body()).contains("\"record\":{").contains("\"journal\":{").contains("\"monitoring\":{");

        HttpResponse<String> malformed = http.get("/v1/operator/reports/risk/alerts?date=2026-02-30", analyst.token());
        assertThat(malformed.statusCode()).as(malformed.body()).isEqualTo(422);
        assertThat(malformed.body()).contains("api.ValidationFailed");
        assertThat(http.get("/v1/operator/reports/risk/cases", analyst.token()).statusCode()).isEqualTo(422);
        assertThat(RiskOperatorSessions.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'risk.ReportRead' AND actor_id = '" + analyst.identity() + "'")).isEqualTo(6);
    }

    @Test
    @DisplayName("the day's replay: two seals of today replay IDENTICAL, recorded risk.Replayed against the day with the"
            + " reason; a blank reason 422 risk.ReasonRequired")
    void theDayReplay() throws Exception {
        RiskOperatorSessions.Session analyst = http.sessionWith(RoleName.RISK_ANALYST, false);
        seal(RiskOperatorSessions.IDS.next());
        seal(RiskOperatorSessions.IDS.next());
        String today = LocalDate.now(ZoneOffset.UTC).toString();

        HttpResponse<String> blank = http.post("/v1/operator/risk/assessments/replay?date=" + today,
                "{\"reason\":\" \"}", analyst.token());
        assertThat(blank.statusCode()).as(blank.body()).isEqualTo(422);
        assertThat(blank.body()).contains("risk.ReasonRequired");

        HttpResponse<String> replay = http.post("/v1/operator/risk/assessments/replay?date=" + today,
                "{\"reason\":\"the daily check\"}", analyst.token());
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(replay.body()).contains("\"known\":true").contains("\"verdict\":\"IDENTICAL\"")
                .contains("\"diverged\":0");
        assertThat(RiskOperatorSessions.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'risk.Replayed' AND target_type = 'risk_assessment_day' AND target_id = '" + today + "' AND reason"
                + " = 'the daily check' AND actor_id = '" + analyst.identity() + "'")).isEqualTo(1);

        // The review's I3: a page at a time - a page of one names the cursor of the next; the next continues from it.
        HttpResponse<String> first = http.post("/v1/operator/risk/assessments/replay?date=" + today + "&limit=1",
                "{\"reason\":\"the paged check\"}", analyst.token());
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(first.body()).contains("\"replayed\":1").contains("\"after\":null");
        String next = RiskOperatorSessions.field(first.body(), "next");
        HttpResponse<String> second = http.post("/v1/operator/risk/assessments/replay?date=" + today + "&limit=1&after="
                + next, "{\"reason\":\"the paged check\"}", analyst.token());
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        assertThat(second.body()).contains("\"after\":\"" + next + "\"");
        for (String page : new String[] {"&limit=0", "&limit=ten", "&after=not-an-id",
                "&after=" + RiskOperatorSessions.IDS.next().toString().substring(0, 8)}) {
            HttpResponse<String> refused = http.post("/v1/operator/risk/assessments/replay?date=" + today + page,
                    "{\"reason\":\"the paged check\"}", analyst.token());
            assertThat(refused.statusCode()).as(page + ": " + refused.body()).isEqualTo(422);
            assertThat(refused.body()).contains("api.ValidationFailed");
        }
    }

    @Test
    @DisplayName("the evidence door: a journal entry the case holds is served with its amount, currency and scale and"
            + " recorded risk.CaseRead (act=evidence); an unheld reference 404; the analyst's own party's case 403")
    void theEvidenceDoor() throws Exception {
        RiskOperatorSessions.Session analyst = http.sessionWith(RoleName.RISK_ANALYST, false);
        RiskOperatorSessions.Session colleague = http.sessionWith(RoleName.RISK_ANALYST, false);
        UUID customer = RiskOperatorSessions.IDS.next();
        UUID journalEntry = monitored(customer);
        String caseId = caseHolding(colleague, customer, journalEntry);

        HttpResponse<String> served = http.get("/v1/operator/risk/cases/" + caseId + "/evidence/journal-entries/"
                + journalEntry, analyst.token());
        assertThat(served.statusCode()).as(served.body()).isEqualTo(200);
        assertThat(served.body()).contains("\"kind\":\"JOURNAL_ENTRY\"").contains("\"amountMinor\":25000")
                .contains("\"currency\":\"EUR\"").contains("\"scale\":2");
        assertThat(RiskOperatorSessions.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'risk.CaseRead' AND target_id = '" + caseId + "' AND change_summary = 'act=evidence,"
                + " kind=JOURNAL_ENTRY, evidence=" + journalEntry + "'")).isEqualTo(1);

        assertThat(http.get("/v1/operator/risk/cases/" + caseId + "/evidence/journal-entries/"
                + RiskOperatorSessions.IDS.next(), analyst.token()).statusCode()).as("not held").isEqualTo(404);
        assertThat(http.get("/v1/operator/risk/cases/" + caseId + "/evidence/assessments/" + journalEntry,
                analyst.token()).statusCode()).as("the assessment door's").isEqualTo(404);

        String own = caseHolding(colleague, analyst.party(), monitored(analyst.party()));
        HttpResponse<String> refused = http.get("/v1/operator/risk/cases/" + own + "/evidence/journal-entries/"
                + journalEntry, analyst.token());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).contains("risk.SelfDealingRefused");
    }

    @Test
    @DisplayName("another desk is 403 and no session 401 on every proof door")
    void theNegatives() throws Exception {
        RiskOperatorSessions.Session officer = http.sessionWith(RoleName.RISK_OFFICER, false);
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        for (String report : REPORTS) {
            String path = "/v1/operator/reports/risk/" + report + "?date=" + today;
            assertThat(http.get(path, officer.token()).statusCode()).as(report).isEqualTo(403);
            assertThat(http.get(path, null).statusCode()).as(report).isEqualTo(401);
        }
        String replay = "/v1/operator/risk/assessments/replay?date=" + today;
        assertThat(http.post(replay, "{\"reason\":\"x\"}", officer.token()).statusCode()).isEqualTo(403);
        assertThat(http.post(replay, "{\"reason\":\"x\"}", null).statusCode()).isEqualTo(401);
        String evidence = "/v1/operator/risk/cases/" + RiskOperatorSessions.IDS.next() + "/evidence/signals/"
                + RiskOperatorSessions.IDS.next();
        assertThat(http.get(evidence, officer.token()).statusCode()).isEqualTo(403);
        assertThat(http.get(evidence, null).statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ fixtures

    /** A transfer of {@code party}'s judged by the composition's T-eval in its own transaction. */
    private void seal(UUID party) {
        RiskOperatorSessions.inOneTransaction(app -> riskEvaluation.evaluate(app, RiskEvaluationRequest.builder(
                        RiskOperation.TRANSFER, CommandRef.of(RiskOperatorSessions.IDS.next()),
                        Money.ofMinorUnits(100_00, CurrencyCode.of("EUR")), party,
                        CorrelationId.generate(RiskOperatorSessions.IDS), CausationId.of("reports-api"))
                .sourceAccount(RiskOperatorSessions.IDS.next()).destinationAccount(RiskOperatorSessions.IDS.next())
                .build()));
    }

    /** A monitored transfer of EUR 250 out of {@code customer}'s account, born from a fresh journal entry id. */
    private UUID monitored(UUID customer) {
        UUID entry = RiskOperatorSessions.IDS.next();
        RiskOperatorSessions.inOneTransaction(app -> monitoredTransactionStore.insertAbsent(app, List.of(
                new MonitoredTransaction(MonitoredTransactionId.next(RiskOperatorSessions.IDS), entry,
                        MonitoringWatermark.sequenceOf(entry), MonitoredEntry.EntryType.POSTING, "transfer",
                        MonitoredKind.TRANSFER, Optional.of(MonitoredSubjectKind.ACCOUNT), Optional.of(customer),
                        Optional.of(MonitoredDirection.OUT), MonitoredCounterpartyKind.CUSTOMER,
                        Optional.of(RiskOperatorSessions.IDS.next()), Optional.empty(), 250_00, 2,
                        CurrencyCode.of("EUR"), LocalDate.now(ZoneOffset.UTC), Instant.now(), Optional.empty()))));
        return entry;
    }

    /** An AML case of {@code party} opened and taken by {@code who}, the journal entry attached as evidence. */
    private String caseHolding(RiskOperatorSessions.Session who, UUID party, UUID journalEntry) throws Exception {
        HttpResponse<String> opened = http.post("/v1/operator/risk/cases", "{\"kind\":\"AML\",\"subjectKind\":\"PARTY\","
                + "\"subjectRef\":\"" + party + "\",\"reason\":\"a structuring pattern\"}", who.token());
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        String caseId = RiskOperatorSessions.field(opened.body(), "caseId");
        assertThat(http.post("/v1/operator/risk/cases/" + caseId + "/actions", "{\"act\":\"TAKE\",\"revision\":0}",
                who.token()).statusCode()).isEqualTo(200);
        HttpResponse<String> attached = http.post("/v1/operator/risk/cases/" + caseId + "/actions",
                "{\"act\":\"ATTACH_EVIDENCE\",\"revision\":1,\"text\":\"the ledger line\",\"journalEntryIds\":[\""
                        + journalEntry + "\"]}", who.token());
        assertThat(attached.statusCode()).as(attached.body()).isEqualTo(200);
        return caseId;
    }
}
