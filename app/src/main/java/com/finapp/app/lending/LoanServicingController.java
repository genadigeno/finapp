package com.finapp.app.lending;

import com.finapp.lending.LendingSpans;
import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 * a loan of their own party ({@code 403 lending.SelfDealingRefused}, recorded). Role {@code LOAN_SERVICING_AGENT}. Since
 * `P11-TSK-025` the fee and interest waivers under the same four eyes (A14): a holder of {@code LOAN_SERVICE} proposes a
 * waiver of an amount with a reason, a DIFFERENT holder of {@code LOAN_SERVICE_APPROVE} approves it - posted within what is
 * due now ({@code 422 lending.WaiverExceedsDue} otherwise) - and the loan read lists the account's fees and waivers. Since
 * `P11-TSK-027` the restructuring by agreement amendment under the same four eyes (ADR-0099 point 5): a holder of
 * {@code LOAN_SERVICE} proposes a lower or equal rate, a remaining term never shorter, arrears re-scheduled or not, with a
 * reason; a DIFFERENT holder of {@code LOAN_SERVICE_APPROVE} approves it, which offers it to the customer - who alone makes
 * it agreement version n+1 - and the loan read lists the account's amendments.
 */
@RestController
@RequestMapping(path = "/operator/loans", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class LoanServicingController {

    @NonNull private final LoanServicingDesk desk;
    @NonNull private final LoanWaiverDesk waivers;
    @NonNull private final LoanAmendmentDesk amendments;
    @NonNull private final LendingFlowScope flow;

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
        return flow.doorOnLoan(LendingSpans.CORRECT, id, () -> desk.propose(id, repaymentId, idempotencyKey, body.reason()));
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
        return flow.doorOnLoan(LendingSpans.CORRECT, id, () -> desk.approve(id, repaymentId, idempotencyKey, body));
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
        return flow.doorOnLoan(LendingSpans.CORRECT, id, () -> desk.reject(id, repaymentId, idempotencyKey, body));
    }

    /**
     * Proposes a waiver of an amount of one of the loan's fees ({@code FEE} - its unpaid remainder; {@code FEE_REFUND} - its
     * paid part, to the credit balance) or of a billed item's interest ({@code INTEREST}), with a reason; nothing is posted
     * (`P11-TSK-025`).
     */
    @PostMapping(path = "/{id}/waivers", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LOAN_SERVICE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public LoanWaiverDesk.WaiverView proposeWaiver(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody WaiverProposalRequest body) {
        return flow.doorOnLoan(LendingSpans.CORRECT, id, () -> waivers.propose(id, idempotencyKey, body));
    }

    /**
     * Approves the named pending waiver - someone other than its proposer: posted (rule 8, 8a or 9) within what is due now,
     * the account closed when it settled the last receivable.
     */
    @PostMapping(path = "/{id}/waivers/{waiverId}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LOAN_SERVICE_APPROVE)
    @RequiresIdempotencyKey
    public LoanWaiverDesk.WaiverView approveWaiver(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @PathVariable("waiverId") String waiverId,
            @Valid @RequestBody WaiverDecisionRequest body) {
        return flow.doorOnLoan(LendingSpans.CORRECT, id, () -> waivers.approve(id, waiverId, idempotencyKey, body));
    }

    /** Rejects the named pending waiver - the proposer's withdrawal included; a reason is required. */
    @PostMapping(path = "/{id}/waivers/{waiverId}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LOAN_SERVICE)
    @RequiresIdempotencyKey
    public LoanWaiverDesk.WaiverView rejectWaiver(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @PathVariable("waiverId") String waiverId,
            @Valid @RequestBody WaiverDecisionRequest body) {
        return flow.doorOnLoan(LendingSpans.CORRECT, id, () -> waivers.reject(id, waiverId, idempotencyKey, body));
    }

    /**
     * Proposes amending the loan's agreement - a rate never above it, a remaining term never shorter than its schedule has
     * left, arrears re-scheduled or not - with a reason; nothing is posted and nothing amended (`P11-TSK-027`).
     */
    @PostMapping(path = "/{id}/amendments", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LOAN_SERVICE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public LoanAmendmentDesk.AmendmentView proposeAmendment(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody AmendmentProposalRequest body) {
        return flow.doorOnLoan(LendingSpans.CORRECT, id, () -> amendments.propose(id, idempotencyKey, body));
    }

    /**
     * Approves the named pending amendment - someone other than its proposer, while the agreement it amends is still the
     * loan's: offered to the customer until its expiry.
     */
    @PostMapping(path = "/{id}/amendments/{amendmentId}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LOAN_SERVICE_APPROVE)
    @RequiresIdempotencyKey
    public LoanAmendmentDesk.AmendmentView approveAmendment(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @PathVariable("amendmentId") String amendmentId,
            @Valid @RequestBody AmendmentDecisionRequest body) {
        return flow.doorOnLoan(LendingSpans.CORRECT, id, () -> amendments.approve(id, amendmentId, idempotencyKey, body));
    }

    /** Rejects the named pending amendment - the proposer's withdrawal included; a reason is required. */
    @PostMapping(path = "/{id}/amendments/{amendmentId}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LOAN_SERVICE)
    @RequiresIdempotencyKey
    public LoanAmendmentDesk.AmendmentView rejectAmendment(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @PathVariable("amendmentId") String amendmentId,
            @Valid @RequestBody AmendmentDecisionRequest body) {
        return flow.doorOnLoan(LendingSpans.CORRECT, id, () -> amendments.reject(id, amendmentId, idempotencyKey, body));
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

    /**
     * A waiver's proposal (`P11-TSK-025`): its kind (`FEE`, `FEE_REFUND` or `INTEREST`), what it names - the fee
     * assessment for a fee's kinds, the billed item for interest - the amount and its currency (an exact decimal at the
     * currency's minor units) and the reason, judged by the domain ({@code lending.ReasonRequired}).
     */
    @ClosedBody
    public record WaiverProposalRequest(
            @NotBlank @Size(max = 16) String kind,
            @NotBlank @Size(max = 36) String targetId,
            @NotBlank @Size(max = 32) String amount,
            @NotBlank @Size(max = 3) String currency,
            @Size(max = 1000) String reason) {}

    /** A waiver's decision - the waiver is named by the path; the decider's reason. */
    @ClosedBody
    public record WaiverDecisionRequest(@Size(max = 1000) String reason) {}

    /**
     * An amendment's proposal (`P11-TSK-027`): the amended nominal annual rate (a decimal fraction, never above the
     * agreement's), the new schedule's remaining instalments, whether arrears are re-scheduled, and the reason, judged by
     * the domain ({@code lending.ReasonRequired}). Closed - no amount can be named: an amendment never raises principal.
     */
    @ClosedBody
    public record AmendmentProposalRequest(
            @NotBlank @Size(max = 12) String nominalAnnualRate,
            @NotNull @Min(1) @Max(600) Integer remainingInstalments,
            @NotNull Boolean rescheduleArrears,
            @Size(max = 1000) String reason) {}

    /** An amendment's decision - the amendment is named by the path; the decider's reason. */
    @ClosedBody
    public record AmendmentDecisionRequest(@Size(max = 1000) String reason) {}
}
