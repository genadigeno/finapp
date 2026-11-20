package com.finapp.app.merchant;

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
 * The merchant domain's queues of proposals awaiting a decision (`P17-TSK-040`), in the one pending-decision shape, each under
 * the permission and the multi-factor session its approve door requires. Read-only: every decision stays on its kind's doors.
 */
@RestController
@RecordsRefusals
@RequestMapping(path = "/operator/pending-decisions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class MerchantPendingDecisionController {

    @NonNull private final MerchantPendingDecisionDesk desk;

    /** The payout destination changes awaiting a decision, oldest first; each names its merchant. */
    @GetMapping("/merchant/payout-destinations")
    @RequiresPermission(PermissionName.PAYOUT_DESTINATION_APPROVE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public PendingDecisionPage listPendingPayoutDestinations(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.payoutDestinations(status, cursor, limit);
    }

    /** The operator payouts above the four-eyes threshold awaiting a decision, oldest first. */
    @GetMapping("/merchant/payout-proposals")
    @RequiresPermission(PermissionName.MERCHANT_PAYOUT_APPROVE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public PendingDecisionPage listPendingPayoutProposals(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.payoutProposals(status, cursor, limit);
    }

    /** The merchant closures awaiting a decision, oldest first. */
    @GetMapping("/merchant/closures")
    @RequiresPermission(PermissionName.MERCHANT_CLOSE_APPROVE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public PendingDecisionPage listPendingMerchantClosures(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.closures(status, cursor, limit);
    }

    /** The BNPL fee assignment proposals awaiting a decision, oldest first; each names its merchant. */
    @GetMapping("/bnpl/fee-assignments")
    @RequiresPermission(PermissionName.FEE_SCHEDULE_APPROVE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public PendingDecisionPage listPendingBnplFeeAssignments(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.bnplFeeAssignments(status, cursor, limit);
    }

    /** The merchant BNPL enablements awaiting a decision, oldest first; each names its merchant, which its doors take in their path. */
    @GetMapping("/merchant/bnpl-enablements")
    @RequiresPermission(PermissionName.MERCHANT_ADMINISTER)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public PendingDecisionPage listPendingMerchantBnplEnablements(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.bnplEnablements(status, cursor, limit);
    }
}
