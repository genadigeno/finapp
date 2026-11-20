package com.finapp.app.reconciliation;

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
 * Reconciliation's queues in the one pending-decision shape (`P17-TSK-040`, gap G-140; finapp-admin-panel ADR-C133 decision 3):
 * the break resolutions and batch repudiations awaiting a second person, and the rule set versions awaiting a second controller,
 * each a section 7 collection of {@link PendingDecisions.PendingDecision}s - oldest first, {@code limit} 50 unless named, 200 at
 * most, the opaque {@code cursor} of the page before, {@code status} {@code PROPOSED} or absent. Each under the permission its
 * approval door decides under, on a password session as every read (the owner's D-6); each decision still taken at its own door, where
 * a proposal decided between the list and the click answers {@code 409}.
 */
@RestController
@RequestMapping(path = "/operator/pending-decisions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ReconciliationPendingDecisionController {

    @NonNull private final BreakResolutionDesk resolutions;
    @NonNull private final ReconciliationAdministrationDesk administration;

    /** The resolutions awaiting a second person, under {@code RECONCILIATION_RESOLVE}. */
    @GetMapping("/reconciliation/resolutions")
    @RequiresPermission(PermissionName.RECONCILIATION_RESOLVE)
    public PendingDecisions.PendingDecisionPage listReconciliationResolutionDecisions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return resolutions.resolutionDecisions(status, cursor, limit);
    }

    /** The rule set versions awaiting a second controller, under {@code RECONCILIATION_ADMINISTER}. */
    @GetMapping("/reconciliation/rule-sets")
    @RequiresPermission(PermissionName.RECONCILIATION_ADMINISTER)
    public PendingDecisions.PendingDecisionPage listReconciliationRuleSetDecisions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return administration.ruleSetDecisions(status, cursor, limit);
    }
}
