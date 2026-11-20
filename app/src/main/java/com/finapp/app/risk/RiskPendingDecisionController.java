package com.finapp.app.risk;

import com.finapp.app.api.PendingDecisions.PendingDecisionPage;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Risk's queues of proposals awaiting a second person's decision (`P17-TSK-040`), in the one pending-decision shape, each
 * under the permission and the multi-factor session its approve door requires. Read-only: every decision stays on its kind's
 * own doors. The investigative queues' pages are recorded {@code risk.PendingDecisionsRead}.
 */
@RestController
@RequestMapping(path = "/operator/pending-decisions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RiskPendingDecisionController {

    @NonNull private final RiskPendingDecisionDesk desk;

    /** The alert suppressions awaiting approval, oldest first. */
    @GetMapping("/risk/suppressions")
    @RequiresPermission(PermissionName.RISK_CASE_APPROVE)
    public PendingDecisionPage listPendingRiskSuppressions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.suppressions(status, cursor, limit);
    }

    /** The case dispositions awaiting a second person, oldest first; each names its case. */
    @GetMapping("/risk/dispositions")
    @RequiresPermission(PermissionName.RISK_CASE_APPROVE)
    public PendingDecisionPage listPendingRiskDispositions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.dispositions(status, cursor, limit);
    }

    /** The case risk actions awaiting approval, oldest first; each names its case. */
    @GetMapping("/risk/actions")
    @RequiresPermission(PermissionName.RISK_CASE_APPROVE)
    public PendingDecisionPage listPendingRiskActions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.actions(status, cursor, limit);
    }

    /** The decision overrides awaiting a decision, oldest first; each names its assessment. */
    @GetMapping("/risk/overrides")
    @RequiresPermission(PermissionName.RISK_OVERRIDE)
    public PendingDecisionPage listPendingRiskOverrides(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.overrides(status, cursor, limit);
    }

    /** The regulatory reports in draft awaiting approval, oldest first; each names its case. */
    @GetMapping("/risk/reports")
    @RequiresPermission(PermissionName.AML_REPORT_APPROVE)
    public PendingDecisionPage listPendingRiskReports(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.reports(status, cursor, limit);
    }

    /** The policy versions awaiting activation, oldest first; each names its operation. */
    @GetMapping("/risk/policy-versions")
    @RequiresPermission(PermissionName.RISK_POLICY_ADMINISTER)
    public PendingDecisionPage listPendingRiskPolicyVersions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.policyVersions(status, cursor, limit);
    }

    /** The screening list versions recorded and awaiting activation, oldest first; each names its list. */
    @GetMapping("/risk/screening-list-versions")
    @RequiresPermission(PermissionName.RISK_POLICY_ADMINISTER)
    public PendingDecisionPage listPendingRiskScreeningListVersions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.screeningListVersions(status, cursor, limit);
    }
}
