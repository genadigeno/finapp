package com.finapp.app.lending;

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
 * The lending desks' collections and the reads an investigator and an approver lacked (`P17-TSK-035`, gaps G-82, G-84 and
 * G-85): the loan applications, the loans, one account's schedule and statements and the terms versions behind
 * {@code LENDING_INVESTIGATE} (held by {@code LENDING_OFFICER}); the servicing proposals awaiting a decision behind
 * {@code LOAN_SERVICE_APPROVE} (held by {@code LOAN_SERVICING_AGENT}). Each list a §7 collection ({@code limit} 50 unless
 * named, 200 at most; the opaque {@code cursor} of the page before). The reader's own party withheld from every list of a
 * party's records, and never read; every serving of a party's records recorded. Reads: no idempotency key, nothing moved.
 */
@RestController
@RequestMapping(path = "/operator", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class LendingListController {

    @NonNull private final LendingListDesk desk;

    /** The loan applications, submitted newest first, of one {@code status} when named; recorded {@code lending.ApplicationsRead}. */
    @GetMapping("/loan-applications")
    @RequiresPermission(PermissionName.LENDING_INVESTIGATE)
    public LendingListDesk.LoanApplicationPage listLoanApplications(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.applications(status, cursor, limit);
    }

    /**
     * The loans, opened newest first, of one {@code partyId}, one {@code state} and one recorded delinquency {@code bucket}
     * ({@code CURRENT}, {@code B1_1_29}, {@code B2_30_59}, {@code B3_60_89}, {@code B4_90_PLUS}) when named; recorded
     * {@code lending.LoansRead}.
     */
    @GetMapping("/loans")
    @RequiresPermission(PermissionName.LENDING_INVESTIGATE)
    public LendingListDesk.LoanPage listLoansForOperator(
            @RequestParam(name = "partyId", required = false) String partyId,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "bucket", required = false) String bucket,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.loans(partyId, state, bucket, cursor, limit);
    }

    /** One account's schedule {@code version} - its latest when absent - as its borrower reads it; recorded {@code lending.LoanRead}. */
    @GetMapping("/loans/{id}/schedule")
    @RequiresPermission(PermissionName.LENDING_INVESTIGATE)
    public LoanAccountDesk.ScheduleView readLoanScheduleForOperator(
            @PathVariable("id") String id, @RequestParam(name = "version", required = false) String version) {
        return desk.schedule(id, version);
    }

    /** One credit line's statements, oldest first, as its borrower reads them; recorded {@code lending.LoanRead}. */
    @GetMapping("/loans/{id}/statements")
    @RequiresPermission(PermissionName.LENDING_INVESTIGATE)
    public LoanAccountDesk.StatementListView readLoanStatementsForOperator(@PathVariable("id") String id) {
        return desk.statements(id);
    }

    /**
     * The waivers, amendments and repayment reversals awaiting a decision, oldest first ({@code status} {@code PROPOSED} or
     * absent): the approver's queue in the one pending-decision shape (`P17-TSK-040`), under the approve doors' permission and
     * assurance; recorded {@code lending.ServicingRequestsRead}.
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @GetMapping("/loans/servicing-requests")
    @RequiresPermission(PermissionName.LOAN_SERVICE_APPROVE)
    public PendingDecisions.PendingDecisionPage listLoanServicingRequests(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.servicingRequests(status, cursor, limit);
    }

    /**
     * The loan terms versions, proposed newest first, of one {@code product} and one {@code status} when named -
     * {@code PROPOSED} is the approver's queue; each names its proposer and marks the caller's own.
     */
    @GetMapping("/lending/terms/versions")
    @RequiresPermission(PermissionName.LENDING_INVESTIGATE)
    public LendingListDesk.LoanTermsVersionPage listLoanTermsVersions(
            @RequestParam(name = "product", required = false) String product,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.termsVersions(product, status, cursor, limit);
    }

    /**
     * The loan terms versions awaiting a decision, oldest first, in the one pending-decision shape (`P17-TSK-040`), under the
     * approve door's permission and assurance. Unrecorded, as the terms versions are.
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @GetMapping("/pending-decisions/lending/terms-versions")
    @RequiresPermission(PermissionName.LENDING_ADMINISTER)
    public PendingDecisions.PendingDecisionPage listPendingLoanTermsVersions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.pendingTermsVersions(status, cursor, limit);
    }

    /**
     * The lending capital contributions awaiting a decision, oldest first, in the one pending-decision shape (`P17-TSK-040`),
     * under the approve door's permission and assurance. Unrecorded, as one contribution's read is.
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @GetMapping("/pending-decisions/lending/capital-contributions")
    @RequiresPermission(PermissionName.LENDING_CAPITAL_ADMINISTER)
    public PendingDecisions.PendingDecisionPage listPendingCapitalContributions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.pendingCapitalContributions(status, cursor, limit);
    }
}
