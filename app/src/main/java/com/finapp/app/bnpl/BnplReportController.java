package com.finapp.app.bnpl;

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
 * BNPL's operations reports (`P12-TSK-026`, {@code PHASE_12_PLAN.md} sections 9 and 15; lending's
 * {@code LendingReportController}): read-only, each holding {@code BNPL_INVESTIGATE}, each recorded {@code bnpl.ReportRead}
 * at every serving; {@code date} is a business date {@code YYYY-MM-DD} (anything else, or none, 422 before any read); a
 * date the platform has not had answers empty. The amounts BNPL never publishes as a series. Handler names are
 * distinctive (the springdoc {@code operationId} rule).
 */
@RestController
@RequestMapping(path = "/operator/reports/bnpl", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class BnplReportController {

    @NonNull private final BnplReports reports;

    /** The BNPL book by product and status, tied to the ledger and the trial balance, with the three proofs. */
    @GetMapping("/portfolio")
    @RequiresPermission(PermissionName.BNPL_INVESTIGATE)
    public BnplReports.BnplPortfolioReport readBnplPortfolioReport(
            @RequestParam(value = "date", required = false) String date) {
        return reports.portfolio(date);
    }

    /** The merchant financing exposure per currency, tied to the merchant payables' lines. */
    @GetMapping("/merchant-financing")
    @RequiresPermission(PermissionName.BNPL_INVESTIGATE)
    public BnplReports.BnplMerchantFinancingReport readBnplMerchantFinancingReport(
            @RequestParam(value = "date", required = false) String date) {
        return reports.merchantFinancing(date);
    }

    /** The refunds valued on the date and their impact on outstanding, tied to each refund's entry. */
    @GetMapping("/refunds")
    @RequiresPermission(PermissionName.BNPL_INVESTIGATE)
    public BnplReports.BnplRefundReport readBnplRefundReport(
            @RequestParam(value = "date", required = false) String date) {
        return reports.refunds(date);
    }

    /** The active book by days-past-due bucket, with its due sums from the ledger. */
    @GetMapping("/delinquency")
    @RequiresPermission(PermissionName.BNPL_INVESTIGATE)
    public BnplReports.BnplDelinquencyReport readBnplDelinquencyReport(
            @RequestParam(value = "date", required = false) String date) {
        return reports.delinquency(date);
    }
}
