package com.finapp.app.ledger;

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
 * The ledger's queues in the one pending-decision shape (`P17-TSK-040`, gap G-140; finapp-admin-panel ADR-C133 decision 3): the
 * manual adjustments and the projection rebuilds awaiting a second person, each a section 7 collection of
 * {@link PendingDecisions.PendingDecision}s - oldest first, {@code limit} 50 unless named, 200 at most, the opaque {@code cursor}
 * of the page before, {@code status} {@code PROPOSED} or absent. Under {@code LEDGER_ADJUST}, each
 * one's approval door's permission, on a password session as every read (the owner's D-6), so a queue is read only by someone who may decide it. Each decision is still taken at its
 * own door; a proposal decided between the list and the click answers {@code 409} there.
 */
@RestController
@RequestMapping(path = "/operator/pending-decisions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class LedgerPendingDecisionController {

    @NonNull private final LedgerCollectionDesk collections;

    /** The manual adjustments awaiting a second person ({@code ledger.Adjustment}), decided at {@code /v1/ledger/adjustments/{id}}. */
    @GetMapping("/ledger/adjustments")
    @RequiresPermission(PermissionName.LEDGER_ADJUST)
    public PendingDecisions.PendingDecisionPage listLedgerAdjustmentDecisions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return collections.adjustmentDecisions(status, cursor, limit);
    }

    /**
     * The projection rebuilds awaiting a second person ({@code ledger.ProjectionRebuild}), each subject the ledger account it would
     * rebuild, decided at {@code /v1/ledger/projection-rebuilds/{id}}.
     */
    @GetMapping("/ledger/projection-rebuilds")
    @RequiresPermission(PermissionName.LEDGER_ADJUST)
    public PendingDecisions.PendingDecisionPage listLedgerProjectionRebuildDecisions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return collections.rebuildDecisions(status, cursor, limit);
    }
}
