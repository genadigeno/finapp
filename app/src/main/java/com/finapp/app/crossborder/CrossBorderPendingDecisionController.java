package com.finapp.app.crossborder;

import com.finapp.app.api.PendingDecisions.PendingDecisionPage;
import com.finapp.app.session.RecordsRefusals;
import com.finapp.app.session.RequiresAssurance;
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
 * Cross-border's queues of proposals awaiting a decision (`P17-TSK-040`), in the one pending-decision shape, each under the
 * permission and the multi-factor session its approve door requires. Read-only. The full histories stay where they were -
 * {@code GET /v1/operator/cross-border/corridor-enable-requests} and {@code /corridor-policies}.
 */
@RestController
@RecordsRefusals
@RequestMapping(path = "/operator/pending-decisions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class CrossBorderPendingDecisionController {

    @NonNull private final CrossBorderPendingDecisionDesk desk;

    /** The corridor enable requests awaiting a decision, oldest first. */
    @GetMapping("/crossborder/corridor-enable-requests")
    @RequiresPermission(PermissionName.CROSSBORDER_ADMINISTER)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public PendingDecisionPage listPendingCorridorEnableRequests(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.corridorEnableRequests(status, cursor, limit);
    }

    /** The corridor policy versions awaiting a decision, oldest first. */
    @GetMapping("/crossborder/corridor-policies")
    @RequiresPermission(PermissionName.CROSSBORDER_ADMINISTER)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public PendingDecisionPage listPendingCorridorPolicies(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.corridorPolicies(status, cursor, limit);
    }
}
