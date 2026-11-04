package com.finapp.app.accounting;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A report run's figure drilled to its journal lines (`P14-TSK-016`; {@code INV-ACC-02}): {@code ACCOUNTING_READ}, the accountant's alone, audited {@code accounting.GlRead} at every serving. A read: no idempotency key, nothing written but the audit row. An operator surface
 * only ({@code NoCustomerSurfaceReadsAccountingTest}). {@link ReportRunLinesDesk} says why the GL's own door is not enough.
 */
@RestController
@RequestMapping(path = "/operator/accounting/report-runs", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ReportRunLinesController {

    @NonNull private final ReportRunLinesDesk desk;

    /**
     * One GL account's journal lines in one calendar month and currency, under the run's pinned version: the month's figure and one keyset page ({@code limit} 1 to 500, default 100; {@code after} the previous page's {@code next}). A month after the run's period is refused.
     * A row's opening is walked back a month at a time until its {@code openingLines} are counted.
     */
    @GetMapping("/{runId}/lines")
    @RequiresPermission(PermissionName.ACCOUNTING_READ)
    public ReportRunLinesDesk.ReportRunLinesView readReportRunLines(
            @PathVariable("runId") String runId,
            @RequestParam("account") String account,
            @RequestParam("month") String month,
            @RequestParam("currency") String currency,
            @RequestParam(name = "after", required = false) String after,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return desk.lines(runId, account, month, currency, after, limit);
    }
}
