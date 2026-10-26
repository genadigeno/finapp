package com.finapp.app.risk;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Risk's operations reports (`P13-TSK-023`; {@code PHASE_13_PLAN.md} sections 9, 13.3, 15; F8; bnpl's
 * {@code BnplReportController}): read-only, each holding {@code RISK_INVESTIGATE}, each recorded {@code risk.ReportRead}
 * at every serving; {@code date} a UTC date {@code YYYY-MM-DD} (anything else, or none, 422 before any read); a date the
 * platform has not had answers {@code known=false}. Each carries its census - the phase's proofs as operator-runnable,
 * audited reads. Counts and codes, sums in exact minor units by currency, never a subject; an operator surface only
 * ({@code INV-RSK-08}). Handler names are distinctive (the springdoc {@code operationId} rule).
 */
@RestController
@RequestMapping(path = "/operator/reports/risk", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RiskOperationsReportController {

    @NonNull private final RiskOperationsReportDesk desk;

    /** The day's decisions per operation, decision, fail-safe, challenge, velocity and currency, with the assessment census. */
    @GetMapping("/decisions")
    @RequiresPermission(PermissionName.RISK_INVESTIGATE)
    public RiskOperationsReportDesk.RiskDecisionsReportView readRiskDecisionsReport(
            @RequestParam(value = "date", required = false) String date) {
        return desk.decisions(date);
    }

    /** The day's alerts by kind, severity, status and rule, with the born-once census. */
    @GetMapping("/alerts")
    @RequiresPermission(PermissionName.RISK_INVESTIGATE)
    public RiskOperationsReportDesk.RiskAlertsReportView readRiskAlertsReport(
            @RequestParam(value = "date", required = false) String date) {
        return desk.alerts(date);
    }

    /** The day's cases opened and disposed, with the one-open and the filing censuses at the snapshot. */
    @GetMapping("/cases")
    @RequiresPermission(PermissionName.RISK_INVESTIGATE)
    public RiskOperationsReportDesk.RiskCasesReportView readRiskCasesReport(
            @RequestParam(value = "date", required = false) String date) {
        return desk.cases(date);
    }

    /** The velocity proof made whole at the snapshot, and the day's monitoring census. */
    @GetMapping("/velocity")
    @RequiresPermission(PermissionName.RISK_INVESTIGATE)
    public RiskOperationsReportDesk.RiskVelocityReportView readRiskVelocityReport(
            @RequestParam(value = "date", required = false) String date) {
        return desk.velocity(date);
    }
}
