package com.finapp.app.accounting;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The report run doors (`P14-TSK-008`; ADR-0115; {@code PHASE_14_PLAN.md} sections 10, 12, 13.6; owner decisions C10,
 * C12): an accountant generates a run under {@code ACCOUNTING_REPORT_GENERATE} - FINAL for a period no longer OPEN,
 * PROVISIONAL for an open one; one run per question however many ask - and regenerates a FINAL run to compare it
 * ({@code IDENTICAL | DIVERGED}) under the same permission; reads a run with its content under {@code ACCOUNTING_READ},
 * every read audited. Every POST keyed per principal ({@code accounting.report-run:<type>:<id>}). The content is
 * {@code CONFIDENTIAL} and served only by the audited read. The handlers' names are distinctive (the springdoc
 * {@code operationId} rule). {@code RECONCILIATION_STATUS} and {@code CLOSE_STATUS} are refused here, {@code 409
 * accounting.ReportDoorMismatch} (`P14-TSK-017`): they read what keeps moving after a lock, so they are never a run, and
 * {@link OperationalReportController} answers them.
 */
@RestController
@RequestMapping(path = "/operator/accounting/report-runs", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ReportRunController {

    @NonNull private final ReportRunDesk desk;
    @NonNull private final AccountingCollectionDesk collections;

    /**
     * Generates the run answering a definition for a period and parameters - or answers the run that already does: its
     * status GENERATING (another request holds it), FINAL, PROVISIONAL (reused within its freshness window, `P14-TSK-009`)
     * or FAILED (a new request asks a new revision).
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_REPORT_GENERATE)
    @RequiresIdempotencyKey
    public ReportRunDesk.ReportRunView generateReportRun(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody ReportRunRequest body) {
        return desk.generate(idempotencyKey, body.definition(), body.periodId(), body.parameters(),
                body.valuationRateSetId());
    }

    /** A run with its content - the issued canonical JSON, verifiable against its sha256; every read audited. */
    /**
     * The report runs, newest claim first (`P17-TSK-023`, G-113): a §7 collection of each run's head as {@code GET .../{runId}}
     * serves it, never its content; {@code periodId} and {@code status} narrow it. Every page recorded
     * {@code accounting.ReportRunsListed}.
     */
    @GetMapping
    @RequiresPermission(PermissionName.ACCOUNTING_READ)
    public AccountingCollectionDesk.ReportRunPage listReportRuns(
            @RequestParam(name = "periodId", required = false) String periodId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return collections.reportRuns(periodId, status, cursor, limit);
    }

    @GetMapping(path = "/{runId}")
    @RequiresPermission(PermissionName.ACCOUNTING_READ)
    public ReportRunDesk.ReportRunContentView readReportRun(@PathVariable("runId") String runId) {
        return desk.read(runId);
    }

    /** Regenerates a FINAL run from its pinned inputs and compares the bytes: IDENTICAL or DIVERGED, never repaired. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/{runId}/regeneration")
    @RequiresPermission(PermissionName.ACCOUNTING_REPORT_GENERATE)
    @RequiresIdempotencyKey
    public ReportRunDesk.ReportRegenerationView regenerateReportRun(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("runId") String runId) {
        return desk.regenerate(idempotencyKey, runId);
    }

    // -----------------------------------------------------------------

    /**
     * A generation: the definition's code, the period, the parameters it takes (at most 16 plain values) and, to ask for a
     * translated report (`P14-TSK-015`), the period's approved valuation rate set - anything else named is refused
     * {@code accounting.ValuationSetNotApproved}; a plain run names none. No reason, no person's data.
     */
    @ClosedBody
    public record ReportRunRequest(
            @NotBlank @Size(max = 64) String definition,
            @NotBlank @Size(max = 36) String periodId,
            @Size(max = 16) Map<String, String> parameters,
            @Size(max = 36) String valuationRateSetId) {}
}
