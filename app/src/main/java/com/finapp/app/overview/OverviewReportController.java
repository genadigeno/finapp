package com.finapp.app.overview;

import com.finapp.app.session.RecordsRefusals;
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
 * The operations overview's customers and transfers reports (`P17-TSK-039`, gap G-150), under {@code PLATFORM_OVERVIEW_READ} -
 * the {@code OPERATIONS_VIEWER}'s one permission, beside the payments report. Each states its {@code asOf}, the database's
 * instant, and each serving is recorded.
 */
@RestController
@RecordsRefusals
@RequestMapping(path = "/operator/reports", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class OverviewReportController {

    @NonNull private final OverviewReportDesk desk;

    /** How many customers stand in each lifecycle status, and the UTC day's openings and changes. */
    @GetMapping("/customers")
    @RequiresPermission(PermissionName.PLATFORM_OVERVIEW_READ)
    public OverviewReportDesk.CustomersReportView readCustomersReport(
            @RequestParam(name = "date", required = false) String date) {
        return desk.customers(date);
    }

    /** The UTC day's transfers by outcome and currency: how many and how much. */
    @GetMapping("/transfers")
    @RequiresPermission(PermissionName.PLATFORM_OVERVIEW_READ)
    public OverviewReportDesk.TransfersReportView readTransfersReport(
            @RequestParam(name = "date", required = false) String date) {
        return desk.transfers(date);
    }
}
