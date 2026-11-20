package com.finapp.app.bnpl;

import com.finapp.app.api.PendingDecisions;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
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
 * The BNPL desks' lists (`P17-TSK-035`, gaps G-83 and G-84): the applications, the agreements and one purchase's refunds
 * behind {@code BNPL_INVESTIGATE} (held by {@code BNPL_OFFICER}); the servicing proposals awaiting a decision behind
 * {@code BNPL_SERVICE_APPROVE} (held by {@code BNPL_SERVICING_AGENT}). Each a §7 collection ({@code limit} 50 unless named, 200
 * at most; the opaque {@code cursor} of the page before). The reader's own party withheld from every list and never read;
 * every serving recorded. Reads: no idempotency key, nothing moved.
 */
@RestController
@RequestMapping(path = "/operator", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class BnplListController {

    @NonNull private final BnplListDesk desk;

    /** The BNPL applications, submitted newest first, of one {@code status} when named; recorded {@code bnpl.ApplicationsRead}. */
    @GetMapping("/bnpl-applications")
    @RequiresPermission(PermissionName.BNPL_INVESTIGATE)
    public BnplListDesk.BnplApplicationPage listBnplApplications(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.applications(status, cursor, limit);
    }

    /**
     * The BNPL agreements, opened newest first, of one obligation {@code status} and one {@code merchantId} when named;
     * recorded {@code bnpl.AgreementsRead}.
     */
    @GetMapping("/bnpl-agreements")
    @RequiresPermission(PermissionName.BNPL_INVESTIGATE)
    public BnplListDesk.BnplAgreementPage listBnplAgreementsForOperator(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "merchantId", required = false) String merchantId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.agreements(status, merchantId, cursor, limit);
    }

    /** One purchase's refunds, oldest first; recorded {@code bnpl.RefundsRead}. */
    @GetMapping("/bnpl-purchases/{id}/refunds")
    @RequiresPermission(PermissionName.BNPL_INVESTIGATE)
    public BnplListDesk.BnplPurchaseRefundPage listBnplPurchaseRefunds(
            @PathVariable("id") String id,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.refunds(id, cursor, limit);
    }

    /**
     * The waivers and repayment reversals awaiting a decision, oldest first ({@code status} {@code PROPOSED} or absent): the
     * approver's queue in the one pending-decision shape (`P17-TSK-040`), under the approve doors' permission and assurance;
     * recorded {@code bnpl.ServicingRequestsRead}.
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @GetMapping("/bnpl-agreements/servicing-requests")
    @RequiresPermission(PermissionName.BNPL_SERVICE_APPROVE)
    public PendingDecisions.PendingDecisionPage listBnplServicingRequests(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.servicingRequests(status, cursor, limit);
    }

    /**
     * The BNPL terms versions awaiting a decision, oldest first, in the one pending-decision shape (`P17-TSK-040`), under the
     * approve door's permission and assurance. Unrecorded, as the terms reads are.
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @GetMapping("/pending-decisions/bnpl/terms-versions")
    @RequiresPermission(PermissionName.BNPL_ADMINISTER)
    public PendingDecisions.PendingDecisionPage listPendingBnplTermsVersions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.pendingTermsVersions(status, cursor, limit);
    }

    /**
     * The operator BNPL refunds proposed above the four-eyes threshold and awaiting a decision, oldest first, in the one
     * pending-decision shape (`P17-TSK-040`), under the approve door's permission and assurance; recorded
     * {@code bnpl.RefundsRead}.
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @GetMapping("/pending-decisions/bnpl/refund-proposals")
    @RequiresPermission(PermissionName.PAYMENT_REFUND_APPROVE)
    public PendingDecisions.PendingDecisionPage listPendingBnplRefundProposals(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.pendingRefundProposals(status, cursor, limit);
    }

    /**
     * The BNPL disputes whose resolution awaits a second operator's approval, oldest first, in the one pending-decision shape
     * (`P17-TSK-040`; until then no list said which), under the approve door's permission and assurance; recorded
     * {@code bnpl.DisputeQueueRead}.
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @GetMapping("/pending-decisions/bnpl/dispute-resolutions")
    @RequiresPermission(PermissionName.BNPL_DISPUTE_RESOLVE)
    public PendingDecisions.PendingDecisionPage listPendingBnplDisputeResolutions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.pendingDisputeResolutions(status, cursor, limit);
    }
}
