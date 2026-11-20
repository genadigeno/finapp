package com.finapp.app.bnpl;

import com.finapp.app.api.RecordedRead;
import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RecordsRefusals;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The decision on an operator's BNPL refund proposal - a refund above the four-eyes threshold (`P17-TSK-116`, ADR-0154's
 * shape; finapp-admin-panel ADR-C135 decision 3): a holder of {@code PAYMENT_REFUND_APPROVE} - {@code FINANCIAL_CONTROLLER}
 * - who did not propose it approves, and the refund applies through T-ref with the decision in its transaction, or rejects,
 * and nothing moves. Each on a {@code MULTI_FACTOR} session, keyed, reasoned; refusals recorded.
 */
@RestController
@RecordsRefusals
@RequestMapping(path = "/operator/bnpl-refund-proposals", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class BnplRefundProposalController {

    @NonNull private final BnplRefundDesk desk;
    @NonNull private final BnplListDesk reads;

    /**
     * One BNPL refund proposal - the purchase, the amount, the reason and who proposed it - what its decider decides
     * (`P17-TSK-124`). Every serving recorded ({@code bnpl.RefundProposalRead}); the reader's own party's purchase 403
     * {@code bnpl.SelfDealingRefused}, recorded FAILED; unknown or malformed 404.
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @GetMapping("/{id}")
    @RequiresPermission(PermissionName.PAYMENT_REFUND_APPROVE)
    @RecordedRead
    public BnplListDesk.BnplRefundProposalView readBnplRefundProposal(@PathVariable("id") String id) {
        return reads.refundProposal(id);
    }

    /** Approves a BNPL refund proposal the caller did not propose: the refund applies. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/{id}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.PAYMENT_REFUND_APPROVE)
    @RequiresIdempotencyKey
    public BnplRefundDesk.BnplOperatorRefundView approveBnplRefundProposal(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody BnplRefundDecisionRequest body) {
        return desk.approve(idempotencyKey, id, body.reason());
    }

    /** Rejects a BNPL refund proposal the caller did not propose: nothing moves. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/{id}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.PAYMENT_REFUND_APPROVE)
    @RequiresIdempotencyKey
    public BnplRefundDesk.BnplOperatorRefundView rejectBnplRefundProposal(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody BnplRefundDecisionRequest body) {
        return desk.reject(idempotencyKey, id, body.reason());
    }

    /** The decider's reason on a BNPL refund proposal: the refund's own bound. */
    @ClosedBody
    public record BnplRefundDecisionRequest(@NotBlank @Size(max = 1000) String reason) {}
}
