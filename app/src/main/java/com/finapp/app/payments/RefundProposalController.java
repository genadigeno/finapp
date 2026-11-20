package com.finapp.app.payments;

import com.finapp.app.session.RecordsRefusals;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The decision on a refund proposal - a refund above the four-eyes threshold (`P17-TSK-031`, ADR-0154): a holder of
 * {@code PAYMENT_REFUND_APPROVE} - {@code FINANCIAL_CONTROLLER} - who did not propose it approves, and the refund dispatches
 * with the decision in its dispatch transaction, or rejects, and nothing moves. Each on a {@code MULTI_FACTOR} session, keyed,
 * reasoned; refusals recorded.
 */
@RestController
@RecordsRefusals
@RequestMapping(path = "/operator/refund-proposals", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RefundProposalController {

    @NonNull private final RefundDesk desk;

    /** Approves a refund proposal the caller did not propose: the refund dispatches. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/{id}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.PAYMENT_REFUND_APPROVE)
    @RequiresIdempotencyKey
    public PaymentService.RefundView approveRefundProposal(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody RefundDecisionRequest body) {
        return desk.approve(idempotencyKey, id, body.reason());
    }

    /** Rejects a refund proposal the caller did not propose: nothing moves. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/{id}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.PAYMENT_REFUND_APPROVE)
    @RequiresIdempotencyKey
    public PaymentService.RefundView rejectRefundProposal(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody RefundDecisionRequest body) {
        return desk.reject(idempotencyKey, id, body.reason());
    }
}
