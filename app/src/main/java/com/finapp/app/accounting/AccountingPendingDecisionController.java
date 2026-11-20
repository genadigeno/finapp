package com.finapp.app.accounting;

import com.finapp.app.api.PendingDecisions;
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
 * Accounting's queues in the one pending-decision shape (`P17-TSK-040`, gap G-140; finapp-admin-panel ADR-C133 decision 3): the GL
 * mapping versions, the valuation rate sets, and the period close, reopen and unlock requests awaiting a second person, each a
 * section 7 collection of {@link PendingDecisions.PendingDecision}s - oldest first, {@code limit} 50 unless named, 200 at most, the
 * opaque {@code cursor} of the page before, {@code status} {@code PROPOSED} or absent. Each under its approval door's permission - a read, so on
 * a password session (the owner's D-6), the door alone stepped up - so a queue is read only by someone who may decide it. No item carries a reason:
 * every one of these proposals' reasons is {@code RESTRICTED-PII} and never served ({@code DATA_CLASSIFICATION.md}). Each decision
 * is still taken at its own door; a proposal decided between the list and the click answers {@code 409} there.
 *
 * <p>The prior-period adjustments and the regulatory obligations and submissions keep their own queues, converted in place
 * ({@code PriorPeriodAdjustmentController}, {@code RegulatoryObligationController}, {@code RegulatorySubmissionController}).
 */
@RestController
@RequestMapping(path = "/operator/pending-decisions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AccountingPendingDecisionController {

    @NonNull private final AccountingCollectionDesk collections;

    /** The GL mapping versions awaiting activation ({@code accounting.MappingVersion}), decided at {@code .../mapping-versions/{id}/activation}. */
    @GetMapping("/accounting/mapping-versions")
    @RequiresPermission(PermissionName.ACCOUNTING_MAPPING_APPROVE)
    public PendingDecisions.PendingDecisionPage listAccountingMappingVersionDecisions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return collections.pendingMappingVersions(status, cursor, limit);
    }

    /** The valuation rate sets awaiting approval ({@code accounting.ValuationRateSet}), each subject its period. */
    @GetMapping("/accounting/valuation-rate-sets")
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_APPROVE)
    public PendingDecisions.PendingDecisionPage listAccountingValuationRateSetDecisions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return collections.pendingRateSets(status, cursor, limit);
    }

    /** The close proposals awaiting a decision ({@code accounting.CloseRequest}), each subject its period, decided at {@code .../periods/{periodId}/close-approval}. */
    @GetMapping("/accounting/close-requests")
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_APPROVE)
    public PendingDecisions.PendingDecisionPage listAccountingCloseRequestDecisions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return collections.pendingCloses(status, cursor, limit);
    }

    /** The reopen proposals awaiting a decision ({@code accounting.ReopenRequest}), each subject its period, decided at {@code .../periods/{periodId}/reopen-approval}. */
    @GetMapping("/accounting/reopen-requests")
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_APPROVE)
    public PendingDecisions.PendingDecisionPage listAccountingReopenRequestDecisions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return collections.pendingReopens(status, cursor, limit);
    }

    /**
     * The unlock proposals awaiting a decision ({@code accounting.UnlockRequest}): the id and the subject the period's, decided at
     * {@code .../periods/{periodId}/unlock-approval} naming {@code proposedAt} as {@code unlockProposedAt}.
     */
    @GetMapping("/accounting/unlock-requests")
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_APPROVE)
    public PendingDecisions.PendingDecisionPage listAccountingUnlockRequestDecisions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return collections.pendingUnlocks(status, cursor, limit);
    }
}
