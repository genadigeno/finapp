package com.finapp.app.lending;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The loan servicing agent's doors (`P11-TSK-020`; PHASE_11_PLAN.md sections 9 and 11, ADR-0099 points 1, 2, 4 and 8):
 * any loan read ({@code LOAN_SERVICE}, audited {@code lending.LoanRead}) and the repayment reversal under four eyes - a
 * holder of {@code LOAN_SERVICE} proposes it with a reason, a DIFFERENT holder of {@code LOAN_SERVICE_APPROVE} approves it
 * (posting rule 10, the exact swap of the repayment's entry), a holder of {@code LOAN_SERVICE} - the proposer's withdrawal
 * included - rejects it. Each act keyed per principal ({@code lending.servicing:<type>:<id>}); an operator never acts on
 * a loan of their own party ({@code 403 lending.SelfDealingRefused}, recorded). Role {@code LOAN_SERVICING_AGENT}.
 */
@RestController
@RequestMapping(path = "/operator/loans", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class LoanServicingController {

    @NonNull private final LoanServicingDesk desk;

    /** Any loan: the account, its repayments with their derived status, its reversals - audited per serving. */
    @GetMapping(path = "/{id}")
    @RequiresPermission(PermissionName.LOAN_SERVICE)
    public LoanServicingDesk.OperatorLoanView readLoanAsOperator(@PathVariable("id") String id) {
        return desk.read(id);
    }

    /** Proposes the exact reversal of one of the loan's repayments, with a reason; nothing is posted. */
    @PostMapping(path = "/{id}/repayments/{repaymentId}/reversal", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LOAN_SERVICE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public LoanServicingDesk.ReversalView proposeRepaymentReversal(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @PathVariable("repaymentId") String repaymentId,
            @Valid @RequestBody ReversalProposalRequest body) {
        return desk.propose(id, repaymentId, idempotencyKey, body.reason());
    }

    /**
     * Approves the named pending reversal - someone other than its proposer: the repayment's entry swapped exactly, the
     * wallet credited back, what it paid due again.
     */
    @PostMapping(path = "/{id}/repayments/{repaymentId}/reversal/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LOAN_SERVICE_APPROVE)
    @RequiresIdempotencyKey
    public LoanServicingDesk.ReversalView approveRepaymentReversal(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @PathVariable("repaymentId") String repaymentId,
            @Valid @RequestBody ReversalDecisionRequest body) {
        return desk.approve(id, repaymentId, idempotencyKey, body);
    }

    /** Rejects the named pending reversal - the proposer's withdrawal included; a reason is required. */
    @PostMapping(path = "/{id}/repayments/{repaymentId}/reversal/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LOAN_SERVICE)
    @RequiresIdempotencyKey
    public LoanServicingDesk.ReversalView rejectRepaymentReversal(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @PathVariable("repaymentId") String repaymentId,
            @Valid @RequestBody ReversalDecisionRequest body) {
        return desk.reject(id, repaymentId, idempotencyKey, body);
    }

    // -----------------------------------------------------------------

    /** A proposal's reason - required, judged by the domain ({@code lending.ReasonRequired}). */
    @ClosedBody
    public record ReversalProposalRequest(@Size(max = 1000) String reason) {}

    /**
     * A decision: the proposal decided - named, so an approver approves exactly the proposal they reviewed, never one
     * proposed after it - and the decider's reason.
     */
    @ClosedBody
    public record ReversalDecisionRequest(@NotBlank @Size(max = 36) String reversalId, @Size(max = 1000) String reason) {}
}
