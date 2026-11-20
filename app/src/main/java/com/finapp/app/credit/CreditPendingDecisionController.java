package com.finapp.app.credit;

import com.finapp.app.api.PendingDecisions;
import com.finapp.app.session.RecordsRefusals;
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
 * Credit's pending-decision queues (`P17-TSK-040`; finapp-admin-panel ADR-C133 decision 3): the credit policy versions and
 * the scorecard model versions awaiting a decision, and the review cases whose first decision awaits a second approval, in the
 * one shape every four-eyes domain serves ({@link PendingDecisions}), each under its approve door's own permission -
 * {@code CREDIT_POLICY_ADMINISTER}, and {@code CREDIT_UNDERWRITE} for the review cases; reads, so on a password
 * session (the owner's D-6). Every page recorded, as credit's version lists and review queue are.
 * Reads: no idempotency key, nothing moved; the decision is still taken at the proposal's own decision doors.
 */
@RestController
@RecordsRefusals
@RequestMapping(path = "/operator/pending-decisions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class CreditPendingDecisionController {

    @NonNull private final CreditCollectionDesk desk;

    /** The credit policy versions awaiting a decision, oldest first; recorded {@code credit.PolicyRead}. */
    @GetMapping("/credit/policy-versions")
    @RequiresPermission(PermissionName.CREDIT_POLICY_ADMINISTER)
    public PendingDecisions.PendingDecisionPage listPendingCreditPolicyVersions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.pendingPolicyVersions(status, cursor, limit);
    }

    /** The scorecard model versions awaiting a decision, oldest first; recorded {@code credit.ScorecardRead}. */
    @GetMapping("/credit/scorecard-versions")
    @RequiresPermission(PermissionName.CREDIT_POLICY_ADMINISTER)
    public PendingDecisions.PendingDecisionPage listPendingScorecardVersions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.pendingScorecardVersions(status, cursor, limit);
    }

    /**
     * The review cases whose first decision awaits a different underwriter's second approval, oldest first, under the
     * second-approval door's permission ({@code CREDIT_UNDERWRITE}); recorded
     * {@code credit.ReviewCasesRead}.
     */
    @GetMapping("/credit/review-case-decisions")
    @RequiresPermission(PermissionName.CREDIT_UNDERWRITE)
    public PendingDecisions.PendingDecisionPage listPendingReviewCaseDecisions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.pendingSecondApprovals(status, cursor, limit);
    }
}
