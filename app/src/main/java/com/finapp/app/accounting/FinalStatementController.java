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
 * A controller's read of a FINAL statement (`P14-TSK-022`, the owner's ruling (v); ADR-0115; identity {@code V025}'s partition): a controller holds no {@code ACCOUNTING_READ}, so it could not read at any door the statement of a close it approved. {@code ACCOUNTING_CLOSE_APPROVE}, audited
 * {@code accounting.FinalStatementRead} at every serving. A read: no idempotency key. {@link FinalStatementDesk} says what it serves and what it withholds. {@code ACCOUNTING_READ} stays the accountant's, and {@link ReportRunController}'s door and {@link ReportRunLinesController}'s are untouched. An operator's route
 * ({@code NoCustomerSurfaceReadsAccountingTest}).
 */
@RestController
@RequestMapping(path = "/operator/accounting/report-runs", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class FinalStatementController {

    @NonNull private final FinalStatementDesk desk;
    @NonNull private final AccountingCollectionDesk collections;

    /**
     * The FINAL statements a controller may read, newest first (`P17-TSK-023`, the owner's answer to the finance roles' read
     * question): a §7 collection of exactly the runs {@code .../{runId}/final-statement} serves - FINAL BALANCE_SHEET and
     * INCOME_STATEMENT runs - each its head, never its figures; {@code periodId} narrows it. Every page recorded
     * {@code accounting.FinalStatementsListed}.
     */
    @GetMapping("/final-statements")
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_APPROVE)
    public AccountingCollectionDesk.FinalStatementPage listFinalStatements(
            @RequestParam(name = "periodId", required = false) String periodId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return collections.finalStatements(periodId, cursor, limit);
    }

    /**
     * A FINAL BALANCE_SHEET or INCOME_STATEMENT, its routes withheld, with the real status of its month. Any other definition, any other run status, and an unknown or malformed id are one 404. No journal line comes through here: a figure's lines are an accountant's read, so the accountant holding this run's id
     * reads them at .../{runId}/lines. A statement superseded by a reopen is the 404: the month's next FINAL, once it is closed again, is a new run.
     */
    @GetMapping("/{runId}/final-statement")
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_APPROVE)
    public FinalStatementDesk.FinalStatementView readFinalStatement(@PathVariable("runId") String runId) {
        return desk.read(runId);
    }
}
