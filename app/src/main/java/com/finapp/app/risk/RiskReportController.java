package com.finapp.app.risk;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The AML desk's regulatory report doors (`P13-TSK-021`; {@code PHASE_13_PLAN.md} sections 9, 11, 12.9;
 * {@code RISK_LIFECYCLES.md} section 4.8; ADR-0111 decisions 5-6): a report drafted from an AML case disposed
 * {@code CONFIRMED_SUSPICIOUS} ({@code AML_REPORT_APPROVE}); approved by a second holder from a {@code MULTI_FACTOR}
 * session; filed by an {@code AML_REPORT_FILE} holder from a {@code MULTI_FACTOR} session - {@code 202}: {@code FILED} is
 * committed before the platform takes it to the authority, whose answer is recorded on the report; and read
 * ({@code AML_REPORT_APPROVE}, {@code DESIGN-021} D9 - the second person reads what they approve; audited
 * {@code risk.RegulatoryReportRead}). Both permissions are {@code AML_OFFICER}'s: four eyes is two holders of the role.
 * Each act keyed per principal ({@code risk.report:<type>:<id>}). The case not so disposed, a live report already, or a
 * report not awaiting the act is {@code 409 risk.ReportNotFileable}; the drafter's approval {@code 403
 * risk.SelfApprovalRefused} and the subject's own party {@code 403 risk.SelfDealingRefused} - each refusal recorded
 * {@code FAILED}. The narrative is served only by these doors; no customer or merchant route reads any of this, and the
 * analyst's case file never carries a report ({@code INV-RSK-08}).
 */
@RestController
@RequestMapping(path = "/operator/risk/cases", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RiskReportController {

    @NonNull private final RiskReportDesk desk;

    /** Drafts a regulatory report from a disposed confirmed-suspicious AML case - awaiting a second holder. */
    @PostMapping(path = "/{caseId}/reports", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.AML_REPORT_APPROVE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public RiskReportDesk.RiskReportView draftRegulatoryReport(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("caseId") String caseId,
            @Valid @RequestBody RiskReportDraftRequest body) {
        return desk.draft(idempotencyKey, caseId, body);
    }

    /** Approves a drafted report - a second holder's act, from a {@code MULTI_FACTOR} session. */
    @PostMapping(path = "/{caseId}/reports/{reportId}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.AML_REPORT_APPROVE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    public RiskReportDesk.RiskReportView approveRegulatoryReport(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("caseId") String caseId,
            @PathVariable("reportId") String reportId,
            @Valid @RequestBody RiskReportDecisionRequest body) {
        return desk.approve(idempotencyKey, caseId, reportId, body);
    }

    /**
     * Files an approved report from a {@code MULTI_FACTOR} session; {@code 202}: {@code FILED} is committed, then the
     * platform submits it under its filing reference, the authority's answer recorded on the report.
     */
    @PostMapping(path = "/{caseId}/reports/{reportId}/filing", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.AML_REPORT_FILE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RiskReportDesk.RiskReportView fileRegulatoryReport(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("caseId") String caseId,
            @PathVariable("reportId") String reportId,
            @Valid @RequestBody RiskReportDecisionRequest body) {
        return desk.file(idempotencyKey, caseId, reportId, body);
    }

    /** Reads a report - its content, the narrative among it; audited {@code risk.RegulatoryReportRead}. */
    @GetMapping(path = "/{caseId}/reports/{reportId}")
    @RequiresPermission(PermissionName.AML_REPORT_APPROVE)
    public RiskReportDesk.RiskReportView readRegulatoryReport(
            @PathVariable("caseId") String caseId,
            @PathVariable("reportId") String reportId) {
        return desk.read(caseId, reportId);
    }

    // -----------------------------------------------------------------

    /**
     * A report's draft: the {@code jurisdiction} a filing adapter declares ({@code EU-REF} in Phase 13), the
     * {@code narrative} of the grounds (required, 1 to 8000 characters, screened for card-number and account shapes -
     * {@code risk.ReasonRequired}), the attachments' references (identifiers, at most 20, each once) and the act's
     * {@code reason} (required). The subject, its accounts, the transactions referenced and the grounds' codes are drawn
     * from the case, never typed.
     */
    @ClosedBody
    public record RiskReportDraftRequest(
            @NotNull @Size(max = 16) String jurisdiction,
            @Size(max = 8000) String narrative,
            @Size(max = 20) List<@NotNull @Size(max = 64) String> attachments,
            @Size(max = 1000) String reason) {}

    /** An approval or a filing: the act's reason (required, judged by the domain - {@code risk.ReasonRequired}). */
    @ClosedBody
    public record RiskReportDecisionRequest(@Size(max = 1000) String reason) {}
}
