package com.finapp.app.credit;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 10's operations reports (`P10-TSK-020`, PHASE_10_PLAN.md section 9): read-only, each audited
 * {@code credit.ReportRead} at every serving, each holding {@code CREDIT_INVESTIGATE}; {@code month} is {@code YYYY-MM},
 * the current month when absent, anything else 422 before any read. Handler names are distinctive (the springdoc
 * {@code operationId} rule).
 */
@RestController
@RequiredArgsConstructor
public class Phase10ReportController {

    @NonNull private final Phase10Reports reports;

    /** A month's decisions by product, pinned policy version and decider, with the referrals opened. */
    @GetMapping(path = "/operator/reports/credit/outcomes", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.CREDIT_INVESTIGATE)
    public Phase10Reports.OutcomesReport readCreditOutcomesReport(
            @RequestParam(value = "month", required = false) String month) {
        return reports.outcomes(month);
    }

    /** A month's reason-code distribution per product. */
    @GetMapping(path = "/operator/reports/credit/reasons", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.CREDIT_INVESTIGATE)
    public Phase10Reports.ReasonsReport readCreditReasonsReport(
            @RequestParam(value = "month", required = false) String month) {
        return reports.reasons(month);
    }

    /** A month's credit data requests by source kind and provider, and each provider's availability. */
    @GetMapping(path = "/operator/reports/credit/sources", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.CREDIT_INVESTIGATE)
    public Phase10Reports.SourcesReport readCreditSourcesReport(
            @RequestParam(value = "month", required = false) String month) {
        return reports.sources(month);
    }
}
