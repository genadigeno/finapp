package com.finapp.app.lending;

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
 * Lending's operations reports (`P11-TSK-030`, PHASE_11_PLAN.md sections 9 and 15): read-only, each holding
 * {@code LENDING_INVESTIGATE}, each recorded {@code lending.ReportRead} at every serving; {@code date} is a business date
 * {@code YYYY-MM-DD} (anything else, or none, 422 before any read); a date the platform has not had answers empty. The
 * amounts lending never publishes as a series. Handler names are distinctive (the springdoc {@code operationId} rule).
 */
@RestController
@RequestMapping(path = "/operator/reports/lending", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class LendingReportController {

    @NonNull private final LendingReports reports;

    /** The loan book by product and state, tied to the ledger and the trial balance, with the proofs. */
    @GetMapping("/portfolio")
    @RequiresPermission(PermissionName.LENDING_INVESTIGATE)
    public LendingReports.PortfolioReport readLendingPortfolioReport(
            @RequestParam(value = "date", required = false) String date) {
        return reports.portfolio(date);
    }

    /** The book in servicing by days-past-due bucket, with its due sums. */
    @GetMapping("/delinquency")
    @RequiresPermission(PermissionName.LENDING_INVESTIGATE)
    public LendingReports.DelinquencyReport readLendingDelinquencyReport(
            @RequestParam(value = "date", required = false) String date) {
        return reports.delinquency(date);
    }

    /** The interest accrued for the date, by product, tied to the ledger. */
    @GetMapping("/accrual")
    @RequiresPermission(PermissionName.LENDING_INVESTIGATE)
    public LendingReports.AccrualReport readLendingAccrualReport(
            @RequestParam(value = "date", required = false) String date) {
        return reports.accrual(date);
    }

    /** The lending capital's headroom per supported currency. */
    @GetMapping("/capital")
    @RequiresPermission(PermissionName.LENDING_INVESTIGATE)
    public LendingReports.CapitalReport readLendingCapitalReport(
            @RequestParam(value = "date", required = false) String date) {
        return reports.capital(date);
    }
}
