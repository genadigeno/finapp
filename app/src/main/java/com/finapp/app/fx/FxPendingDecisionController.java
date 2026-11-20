package com.finapp.app.fx;

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
 * FX's queues of proposals awaiting a decision (`P17-TSK-040`), in the one pending-decision shape, each under the permission
 * and the multi-factor session its approve door requires. Read-only: every decision stays on its kind's own doors. The full
 * histories stay where they were - {@code GET /v1/operator/fx/enable-requests} and {@code /pricing-policies}.
 */
@RestController
@RecordsRefusals
@RequestMapping(path = "/operator/pending-decisions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class FxPendingDecisionController {

    @NonNull private final FxPendingDecisionDesk desk;

    /** The pair and provider enable requests awaiting a decision, oldest first. */
    @GetMapping("/fx/enable-requests")
    @RequiresPermission(PermissionName.FX_ADMINISTER)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public PendingDecisionPage listPendingFxEnableRequests(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.enableRequests(status, cursor, limit);
    }

    /** The pricing policy versions awaiting activation, oldest first. */
    @GetMapping("/fx/pricing-policies")
    @RequiresPermission(PermissionName.FX_ADMINISTER)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public PendingDecisionPage listPendingFxPricingPolicies(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.pricingPolicies(status, cursor, limit);
    }

    /** The trade reversals awaiting a decision, oldest first; each names its trade, which the reversal's doors take. */
    @GetMapping("/fx/trade-reversals")
    @RequiresPermission(PermissionName.FX_TRADE_REVERSE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public PendingDecisionPage listPendingFxTradeReversals(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.tradeReversals(status, cursor, limit);
    }
}
