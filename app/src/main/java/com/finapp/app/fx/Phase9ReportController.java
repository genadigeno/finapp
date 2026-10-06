package com.finapp.app.fx;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 9's reports and the payment trace (`P9-TSK-027`, PHASE_9_PLAN.md section 15): read-only, each audited at every
 * serving, each holding {@code FX_INVESTIGATE}. Handler names are distinctive (the springdoc {@code operationId} rule).
 */
@RestController
@RequiredArgsConstructor
public class Phase9ReportController {

    @NonNull private final Phase9Reports reports;

    /** The FX position by currency, and the open cover legs. */
    @GetMapping(path = "/operator/reports/fx/position", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FX_INVESTIGATE)
    public Phase9Reports.FxPositionReport readFxPositionReport() {
        return reports.fxPosition();
    }

    /** A month's FX revenue; {@code month} is {@code YYYY-MM}, the current month when absent, anything else 422. */
    @GetMapping(path = "/operator/reports/fx/revenue", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FX_INVESTIGATE)
    public Phase9Reports.FxRevenueReport readFxRevenueReport(@RequestParam(value = "month", required = false) String month) {
        return reports.fxRevenue(month);
    }

    /** A month's corridors; {@code month} as the revenue report's. */
    @GetMapping(path = "/operator/reports/cross-border/corridors", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FX_INVESTIGATE)
    public Phase9Reports.CorridorReport readCorridorReport(@RequestParam(value = "month", required = false) String month) {
        return reports.corridors(month);
    }

    /** A cross-border payment's durable identifier chain. */
    @GetMapping(path = "/operator/cross-border/payments/{id}/trace", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FX_INVESTIGATE)
    public Phase9Reports.PaymentTrace readCrossBorderPaymentTrace(@PathVariable("id") String id) {
        return reports.trace(id);
    }
}
