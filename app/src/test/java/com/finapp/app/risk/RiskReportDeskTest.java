package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.security.Actor;
import com.finapp.risk.RegulatoryReportStatus;
import com.finapp.risk.RiskErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RiskReportDesk} over fakes (`P13-TSK-021`; {@code PHASE_13_PLAN.md} sections 9, 11, 12.9, 14; ADR-0111
 * decisions 5-6; {@code DESIGN-021} D7, D9): the AML desk's four doors - a draft, a second person's approval, the filing
 * answered after the platform took it to the authority <strong>on no transaction</strong>, the recorded read; each act
 * keyed per principal (a replay answers the report as it stands, writing nothing new); the drafter's approval {@code 403}
 * recorded {@code FAILED}; self-dealing {@code 403} recorded; a lost filing answer left to the inquiry leg and asked for,
 * never filed again; ten filers - one filing; the refusals in the contract's words. The operators are
 * {@code CUSTOMER}-typed, as the session establishes every identity. The HTTP half is {@code ReportApiTest} (written,
 * owed - C5).
 */
@DisplayName("the regulatory report doors: draft, approval, filing after the commit as the platform, the recorded read"
        + " (P13-TSK-021)")
class RiskReportDeskTest {

    private static final String NARRATIVE = "Funds were split below the threshold and moved out within a day.";

    @Test
    @DisplayName("draft, approve, file: the receipts carry the content and the narrative; the filing answers with the"
            + " authority's acknowledgement, the platform's one call made on no transaction; a replayed key writes"
            + " nothing new; the read is recorded risk.RegulatoryReportRead")
    void theFourDoors() {
        RiskActionWorld world = new RiskActionWorld();
        String caseId = world.confirmedSuspicious();
        Actor drafter = world.analyst();
        RiskReportDesk.RiskReportView drafted = draft(world, drafter, caseId, "draft-1");
        assertThat(drafted.status()).isEqualTo("DRAFT");
        assertThat(drafted.jurisdiction()).isEqualTo("EU-REF");
        assertThat(drafted.narrative()).isEqualTo(NARRATIVE);
        assertThat(drafted.accountRefs()).hasSize(2);
        assertThat(draft(world, drafter, caseId, "draft-1").reportId()).as("the key replays the report")
                .isEqualTo(drafted.reportId());
        assertThat(world.reportStore.all()).hasSize(1);

        RiskReportDesk.RiskReportView approved = approve(world, world.analyst(), caseId, drafted.reportId(), "a-1");
        assertThat(approved.status()).isEqualTo("APPROVED");
        Actor filer = world.analyst();
        RiskReportDesk.RiskReportView filed = file(world, filer, caseId, drafted.reportId(), "f-1");
        assertThat(filed.status()).as("the door's drive recorded the answer").isEqualTo("ACKNOWLEDGED");
        assertThat(filed.providerReference()).startsWith("SIM-FIU-");
        assertThat(filed.attempts()).isEqualTo(1);
        assertThat(world.authority.calls).containsExactly("file|" + drafted.reportId() + "|depth=0|SYSTEM");
        assertThat(file(world, filer, caseId, drafted.reportId(), "f-1").status()).as("the replay")
                .isEqualTo("ACKNOWLEDGED");
        assertThat(world.authority.calls).as("never filed twice").hasSize(1);
        assertThat(world.events).containsOnlyOnce("risk.RegulatoryReportFiled");

        RiskReportDesk.RiskReportView read = RiskActionWorld.as(world.analyst(), () -> world.reportDesk.read(caseId,
                drafted.reportId()));
        assertThat(read.narrative()).isEqualTo(NARRATIVE);
        assertThat(world.audited("risk.RegulatoryReportRead")).singleElement()
                .satisfies(record -> assertThat(record.changeSummary().orElseThrow()).doesNotContain("threshold"));
        assertThat(world.count(RegulatoryReportMetrics.REPORT, "status", "ACKNOWLEDGED")).isEqualTo(1);
        assertThat(world.count(RegulatoryReportMetrics.FILING, "outcome", "acknowledged")).isEqualTo(1);
    }

    @Test
    @DisplayName("four eyes at the door: the drafter's approval is 403 risk.SelfApprovalRefused and recorded FAILED in a"
            + " transaction of its own, nothing approved; self-dealing is 403 risk.SelfDealingRefused, recorded")
    void theRefusalsAreRecorded() {
        RiskActionWorld world = new RiskActionWorld();
        String caseId = world.confirmedSuspicious();
        Actor drafter = world.analyst();
        RiskReportDesk.RiskReportView drafted = draft(world, drafter, caseId, "d");
        ApiException self = refusal(drafter, () -> world.reportDesk.approve("s", caseId, drafted.reportId(),
                new RiskReportController.RiskReportDecisionRequest("mine")));
        assertThat(self.errorCode()).isEqualTo(RiskErrorCode.SELF_APPROVAL_REFUSED);
        assertThat(world.audited("risk.SelfApprovalRefused")).singleElement()
                .satisfies(record -> assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED));
        assertThat(world.reportOf(drafted.reportId()).status()).isEqualTo(RegulatoryReportStatus.DRAFT);

        Actor insider = world.analyst();
        world.partyOf.put(insider.id(), UUID.fromString(drafted.subjectRef()));
        ApiException dealing = refusal(insider, () -> world.reportDesk.read(caseId, drafted.reportId()));
        assertThat(dealing.errorCode()).isEqualTo(RiskErrorCode.SELF_DEALING_REFUSED);
        assertThat(dealing.clientDetail().orElseThrow()).doesNotContain(drafted.subjectRef());
        assertThat(world.audited("risk.SelfDealingRefused")).singleElement()
                .satisfies(record -> assertThat(record.targetId()).isEqualTo(drafted.reportId()))
                .satisfies(record -> assertThat(record.targetType()).isEqualTo("regulatory_report"));
        assertThat(world.audited("risk.RegulatoryReportRead")).as("nothing served").isEmpty();

        // A refused draft names the case it was drafted from - no report exists to name (the gate's finding).
        ApiException draft = refusal(insider, () -> world.reportDesk.draft("own", caseId, request("EU-REF", List.of(),
                "mine")));
        assertThat(draft.errorCode()).isEqualTo(RiskErrorCode.SELF_DEALING_REFUSED);
        assertThat(world.audited("risk.SelfDealingRefused")).hasSize(2).last()
                .satisfies(record -> assertThat(record.targetType()).isEqualTo("risk_case"))
                .satisfies(record -> assertThat(record.targetId()).isEqualTo(caseId))
                .satisfies(record -> assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED));
    }

    @Test
    @DisplayName("a lost filing response is inquired, never re-filed: the door answers 202 FILED; its retry claims"
            + " nothing within the lease; the inquiry leg past it asks by the reference and records the acknowledgement"
            + " - one submission")
    void aLostFilingResponseIsInquiredNeverReFiled() {
        RiskActionWorld world = new RiskActionWorld();
        String caseId = world.confirmedSuspicious();
        RiskReportDesk.RiskReportView approved = approve(world, world.analyst(), caseId,
                draft(world, world.analyst(), caseId, "d").reportId(), "a");
        world.authority.loseNextAnswer = true;
        Actor filer = world.analyst();

        RiskReportDesk.RiskReportView filed = file(world, filer, caseId, approved.reportId(), "f");
        assertThat(filed.status()).isEqualTo("FILED");
        assertThat(filed.attempts()).isEqualTo(1);
        assertThat(world.count(RegulatoryReportMetrics.FILING, "outcome", RegulatoryReportMetrics.FAILED)).isEqualTo(1);
        assertThat(file(world, filer, caseId, approved.reportId(), "f").status()).as("the retry, within the lease")
                .isEqualTo("FILED");
        assertThat(world.filings.inquireDue()).as("the leg, within the lease").isZero();

        world.reportStore.now = world.reportStore.now.plus(RiskActionWorld.LEASE);
        assertThat(world.filings.inquireDue()).isEqualTo(1);
        assertThat(world.reportOf(approved.reportId()).status()).isEqualTo(RegulatoryReportStatus.ACKNOWLEDGED);
        assertThat(world.authority.calls).containsExactly(
                "file|" + approved.reportId() + "|depth=0|SYSTEM",
                "inquire|" + approved.reportId() + "|depth=0|SYSTEM");
        assertThat(world.audited("risk.ReportAcknowledged")).singleElement()
                .satisfies(record -> assertThat(record.actor()).isEqualTo(Actor.SYSTEM));
    }

    @Test
    @DisplayName("ten filers of one approved report: one FILED and one submission; the others 409 risk.ReportNotFileable")
    void tenFilersFileOnce() {
        RiskActionWorld world = new RiskActionWorld();
        String caseId = world.confirmedSuspicious();
        String reportId = approve(world, world.analyst(), caseId, draft(world, world.analyst(), caseId, "d").reportId(),
                "a").reportId();
        List<Object> answers = new ArrayList<>();
        IntStream.range(0, 10).forEach(i -> {
            Actor filer = world.analyst();
            try {
                answers.add(file(world, filer, caseId, reportId, "f-" + i));
            } catch (ApiException refused) {
                answers.add(refused);
            }
        });
        assertThat(answers).filteredOn(RiskReportDesk.RiskReportView.class::isInstance).hasSize(1);
        assertThat(answers).filteredOn(ApiException.class::isInstance).hasSize(9)
                .allSatisfy(answer -> assertThat(((ApiException) answer).errorCode())
                        .isEqualTo(RiskErrorCode.REPORT_NOT_FILEABLE));
        assertThat(world.authority.calls).hasSize(1);
    }

    @Test
    @DisplayName("the refusals in the contract's words: a case not confirmed suspicious 409 risk.ReportNotFileable, an"
            + " undeclared jurisdiction or a malformed attachment 422 risk.ReportRequestInvalid, no reason 422"
            + " risk.ReasonRequired, an unknown case or report 404")
    void theRefusalsInTheContractsWords() {
        RiskActionWorld world = new RiskActionWorld();
        RiskActionWorld.Taken open = world.partyCase(com.finapp.risk.CaseKind.AML);
        Actor drafter = world.analyst();
        assertThat(refusal(drafter, () -> world.reportDesk.draft("x", open.id(), request("EU-REF", List.of(), "ok")))
                .errorCode()).isEqualTo(RiskErrorCode.REPORT_NOT_FILEABLE);
        String caseId = world.confirmedSuspicious();
        assertThat(refusal(drafter, () -> world.reportDesk.draft("y", caseId, request("US-FIN", List.of(), "ok")))
                .errorCode()).isEqualTo(RiskErrorCode.REPORT_REQUEST_INVALID);
        assertThat(refusal(drafter, () -> world.reportDesk.draft("z", caseId, request("EU-REF", List.of("nope"),
                "ok"))).errorCode()).isEqualTo(RiskErrorCode.REPORT_REQUEST_INVALID);
        assertThat(refusal(drafter, () -> world.reportDesk.draft("w", caseId, request("EU-REF", List.of(), " ")))
                .errorCode()).isEqualTo(RiskErrorCode.REASON_REQUIRED);
        assertThat(refusal(drafter, () -> world.reportDesk.draft("v", UUID.randomUUID().toString(),
                request("EU-REF", List.of(), "ok"))).errorCode()).isEqualTo(RiskErrorCode.NOT_FOUND);
        assertThat(refusal(drafter, () -> world.reportDesk.read(caseId, "not-an-id")).errorCode())
                .isEqualTo(RiskErrorCode.NOT_FOUND);
        assertThat(refusal(drafter, () -> world.reportDesk.read(caseId, UUID.randomUUID().toString())).errorCode())
                .isEqualTo(RiskErrorCode.NOT_FOUND);
        assertThat(world.reportStore.all()).isEmpty();
        assertThat(world.claims).as("a refusal stores no key").isEmpty();
    }

    // -----------------------------------------------------------------

    private static RiskReportController.RiskReportDraftRequest request(String jurisdiction, List<String> attachments,
            String reason) {
        return new RiskReportController.RiskReportDraftRequest(jurisdiction, NARRATIVE, attachments, reason);
    }

    private static RiskReportDesk.RiskReportView draft(RiskActionWorld world, Actor drafter, String caseId, String key) {
        return RiskActionWorld.as(drafter, () -> world.reportDesk.draft(key, caseId, request("EU-REF", List.of(),
                "the case is confirmed suspicious")));
    }

    private static RiskReportDesk.RiskReportView approve(RiskActionWorld world, Actor approver, String caseId,
            String reportId, String key) {
        return RiskActionWorld.as(approver, () -> world.reportDesk.approve(key, caseId, reportId,
                new RiskReportController.RiskReportDecisionRequest("the narrative is accurate")));
    }

    private static RiskReportDesk.RiskReportView file(RiskActionWorld world, Actor filer, String caseId,
            String reportId, String key) {
        return RiskActionWorld.as(filer, () -> world.reportDesk.file(key, caseId, reportId,
                new RiskReportController.RiskReportDecisionRequest("file it")));
    }

    private static ApiException refusal(Actor actor, Supplier<?> work) {
        ApiException refused = catchThrowableOfType(ApiException.class, () -> RiskActionWorld.as(actor, work));
        assertThat(refused).as("the door refuses").isNotNull();
        return refused;
    }
}
