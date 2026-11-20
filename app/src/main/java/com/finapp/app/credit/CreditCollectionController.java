package com.finapp.app.credit;

import com.finapp.app.session.RecordsRefusals;
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
 * The credit investigator's collections and the reads an approver lacked (`P17-TSK-035`, gaps G-80 and G-81): the decision
 * requests, the policy versions and one version, the scorecard model versions and one version with its points table - each
 * behind {@code CREDIT_INVESTIGATE} (held by {@code CREDIT_POLICY_OFFICER}, who also holds the four-eyes administration), each
 * list a §7 collection ({@code limit} 50 unless named, 200 at most; the opaque {@code cursor} of the page before), every
 * serving recorded, and every refused or failed one recorded {@code door.ActRefused}, as credit's other doors are
 * (`P15-TSK-039`). Reads: no idempotency key, nothing moved.
 */
@RestController
@RecordsRefusals
@RequestMapping(path = "/operator/credit", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class CreditCollectionController {

    @NonNull private final CreditCollectionDesk desk;

    /**
     * The decision requests, submitted newest first, of one {@code status} and one {@code product} when named; every page
     * recorded {@code credit.DecisionRequestsRead}.
     */
    @GetMapping("/decision-requests")
    @RequiresPermission(PermissionName.CREDIT_INVESTIGATE)
    public CreditCollectionDesk.CreditDecisionRequestPage listCreditDecisionRequests(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "product", required = false) String product,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.decisionRequests(status, product, cursor, limit);
    }

    /**
     * The credit policy versions, proposed newest first, of one {@code product} and one {@code status} when named -
     * {@code PROPOSED} is the approver's queue; each names its proposer and marks the caller's own. Recorded
     * {@code credit.PolicyRead}.
     */
    @GetMapping("/policies/versions")
    @RequiresPermission(PermissionName.CREDIT_INVESTIGATE)
    public CreditCollectionDesk.CreditPolicyVersionPage listCreditPolicyVersions(
            @RequestParam(name = "product", required = false) String product,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.policyVersions(product, status, cursor, limit);
    }

    /** One credit policy version, whatever its status, with its whole policy; recorded {@code credit.PolicyRead}. */
    @GetMapping("/policies/versions/{versionId}")
    @RequiresPermission(PermissionName.CREDIT_INVESTIGATE)
    public CreditCollectionDesk.CreditPolicyVersionView readCreditPolicyVersion(@PathVariable("versionId") String versionId) {
        return desk.policyVersion(versionId);
    }

    /**
     * The scorecard model versions, proposed newest first, of one {@code status} when named; recorded
     * {@code credit.ScorecardRead}.
     */
    @GetMapping("/scorecards")
    @RequiresPermission(PermissionName.CREDIT_INVESTIGATE)
    public CreditCollectionDesk.ScorecardVersionPage listScorecardVersions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.scorecards(status, cursor, limit);
    }

    /**
     * One scorecard model version, whatever its status, with its base points and every attribute's bands - the version an
     * approver approves; recorded {@code credit.ScorecardRead}.
     */
    @GetMapping("/scorecards/{versionId}")
    @RequiresPermission(PermissionName.CREDIT_INVESTIGATE)
    public CreditCollectionDesk.ScorecardVersionView readScorecardVersion(@PathVariable("versionId") String versionId) {
        return desk.scorecard(versionId);
    }
}
