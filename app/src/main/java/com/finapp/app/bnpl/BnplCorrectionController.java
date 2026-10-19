package com.finapp.app.bnpl;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The BNPL servicing agent's correction doors (`P12-TSK-024`; {@code PHASE_12_PLAN.md} section 9, {@code BNPL_LIFECYCLES.md}
 * section 4.11; lending's {@code LoanServicingController} precedent): the repayment reversal and the fee waiver under four
 * eyes - a holder of {@code BNPL_SERVICE} proposes with a reason, a DIFFERENT holder of {@code BNPL_SERVICE_APPROVE}
 * approves (naming the proposal decided), a holder of {@code BNPL_SERVICE} - the proposer's withdrawal included - rejects.
 * Each act keyed per principal ({@code bnpl.servicing:<actor type>:<id>}); an operator never acts on an agreement of their
 * own party ({@code 403 bnpl.SelfDealingRefused}, recorded). Role {@code BNPL_SERVICING_AGENT}. The operator's agreement
 * read and its explanation are `P12-TSK-025`'s.
 */
@RestController
@RequestMapping(path = "/operator/bnpl-agreements", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class BnplCorrectionController {

    @NonNull private final BnplCorrectionDesk desk;

    /** Proposes the exact reversal of one of the agreement's repayments, with a reason; nothing is posted. */
    @PostMapping(path = "/{id}/repayments/{repaymentId}/reversal", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.BNPL_SERVICE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public BnplCorrectionDesk.BnplReversalView proposeBnplRepaymentReversal(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @PathVariable("repaymentId") String repaymentId,
            @Valid @RequestBody BnplReversalProposalBody body) {
        return desk.proposeReversal(id, repaymentId, idempotencyKey, body.reason());
    }

    /**
     * Approves the named pending reversal - someone other than its proposer: the repayment's entry swapped exactly, the
     * wallet credited back, what it paid due again.
     */
    @PostMapping(path = "/{id}/repayments/{repaymentId}/reversal/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.BNPL_SERVICE_APPROVE)
    @RequiresIdempotencyKey
    public BnplCorrectionDesk.BnplReversalView approveBnplRepaymentReversal(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @PathVariable("repaymentId") String repaymentId,
            @Valid @RequestBody BnplReversalDecisionBody body) {
        return desk.approveReversal(id, repaymentId, idempotencyKey, body);
    }

    /** Rejects the named pending reversal - the proposer's withdrawal included; a reason is required. */
    @PostMapping(path = "/{id}/repayments/{repaymentId}/reversal/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.BNPL_SERVICE)
    @RequiresIdempotencyKey
    public BnplCorrectionDesk.BnplReversalView rejectBnplRepaymentReversal(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @PathVariable("repaymentId") String repaymentId,
            @Valid @RequestBody BnplReversalDecisionBody body) {
        return desk.rejectReversal(id, repaymentId, idempotencyKey, body);
    }

    /** Proposes waiving an amount of one of the agreement's late fees, with a reason; nothing is posted. */
    @PostMapping(path = "/{id}/waivers", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.BNPL_SERVICE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public BnplCorrectionDesk.BnplWaiverView proposeBnplWaiver(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody BnplWaiverProposalBody body) {
        return desk.proposeWaiver(id, idempotencyKey, body);
    }

    /**
     * Approves the named pending waiver - someone other than its proposer: posted within what may still be forgiven of the
     * fee, the agreement closed when it settled the last receivable.
     */
    @PostMapping(path = "/{id}/waivers/{waiverId}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.BNPL_SERVICE_APPROVE)
    @RequiresIdempotencyKey
    public BnplCorrectionDesk.BnplWaiverView approveBnplWaiver(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @PathVariable("waiverId") String waiverId,
            @Valid @RequestBody BnplWaiverDecisionBody body) {
        return desk.approveWaiver(id, waiverId, idempotencyKey, body);
    }

    /** Rejects the named pending waiver - the proposer's withdrawal included; a reason is required. */
    @PostMapping(path = "/{id}/waivers/{waiverId}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.BNPL_SERVICE)
    @RequiresIdempotencyKey
    public BnplCorrectionDesk.BnplWaiverView rejectBnplWaiver(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @PathVariable("waiverId") String waiverId,
            @Valid @RequestBody BnplWaiverDecisionBody body) {
        return desk.rejectWaiver(id, waiverId, idempotencyKey, body);
    }

    // -----------------------------------------------------------------

    /** A reversal's proposal: the reason - required, judged by the domain ({@code bnpl.ReasonRequired}). */
    @ClosedBody
    public record BnplReversalProposalBody(@Size(max = 1000) String reason) {}

    /**
     * A reversal's decision: the proposal decided - named, so an approver approves exactly the proposal they reviewed,
     * never one proposed after it - and the decider's reason.
     */
    @ClosedBody
    public record BnplReversalDecisionBody(@NotBlank @Size(max = 36) String reversalId, @Size(max = 1000) String reason) {}

    /**
     * A waiver's proposal: the fee assessment it forgives an amount of, the amount and its currency (an exact decimal at the
     * currency's minor units) and the reason, judged by the domain ({@code bnpl.ReasonRequired}). Kind {@code FEE} only.
     */
    @ClosedBody
    public record BnplWaiverProposalBody(
            @NotBlank @Size(max = 36) String assessmentId,
            @NotBlank @Size(max = 32) String amount,
            @NotBlank @Size(max = 3) String currency,
            @Size(max = 1000) String reason) {}

    /** A waiver's decision - the waiver is named by the path; the decider's reason. */
    @ClosedBody
    public record BnplWaiverDecisionBody(@Size(max = 1000) String reason) {}
}
