package com.finapp.app.bnpl;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresPermission;
import com.finapp.bnpl.BnplSpans;
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
 * The operator's BNPL dispute doors (`P12-TSK-023`; ADR-0106 decision 5): the audited read, and the resolution of a dispute
 * the merchant answered - a refund or upheld, reasoned - under {@code BNPL_DISPUTE_RESOLVE}, keyed
 * ({@code bnpl.servicing:<actor type>:<id>}). At or below the pinned four-eyes threshold one operator resolves; above it the
 * resolution is a proposal another holder approves or rejects. A self-approval is {@code 403 bnpl.SelfApprovalRefused} and
 * a dispute of one's own party {@code 403 bnpl.SelfDealingRefused}, each recorded {@code FAILED}.
 */
@RestController
@RequestMapping(path = "/operator/bnpl-disputes", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class BnplOperatorDisputeController {

    @NonNull private final BnplDisputeDesk desk;
    @NonNull private final BnplFlowScope flow;

    /** The dispute, with the customer's reason and the merchant's statement - every access recorded. */
    @GetMapping("/{id}")
    @RequiresPermission(PermissionName.BNPL_DISPUTE_RESOLVE)
    public BnplDisputeDesk.BnplOperatorDisputeView readBnplDispute(@PathVariable("id") String id) {
        return desk.investigate(id);
    }

    /** Resolves - or, above the threshold, proposes the resolution of - a dispute the merchant answered. */
    @PostMapping(path = "/{id}/resolution", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.BNPL_DISPUTE_RESOLVE)
    @RequiresIdempotencyKey
    public BnplDisputeDesk.BnplDisputeView resolveBnplDispute(
            @PathVariable("id") String id,
            @Valid @RequestBody BnplDisputeResolutionBody body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey) {
        return flow.doorOnDispute(BnplSpans.DISPUTE_RESOLVE, id, () -> desk.resolve(id, idempotencyKey, body));
    }

    /** A second operator approves the proposal named - the resolution it proposed executed now. */
    @PostMapping(path = "/{id}/resolution/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.BNPL_DISPUTE_RESOLVE)
    @RequiresIdempotencyKey
    public BnplDisputeDesk.BnplDisputeView approveBnplDisputeResolution(
            @PathVariable("id") String id,
            @Valid @RequestBody BnplDisputeDecisionBody body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey) {
        return flow.doorOnDispute(BnplSpans.DISPUTE_RESOLVE, id, () -> desk.approve(id, idempotencyKey, body));
    }

    /** An operator rejects - or its proposer withdraws - the proposal named. */
    @PostMapping(path = "/{id}/resolution/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.BNPL_DISPUTE_RESOLVE)
    @RequiresIdempotencyKey
    public BnplDisputeDesk.BnplDisputeView rejectBnplDisputeResolution(
            @PathVariable("id") String id,
            @Valid @RequestBody BnplDisputeDecisionBody body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey) {
        return flow.doorOnDispute(BnplSpans.DISPUTE_RESOLVE, id, () -> desk.reject(id, idempotencyKey, body));
    }

    /** A resolution: {@code REFUND} or {@code UPHOLD}, and the reason, required (RESTRICTED-PII, screened). Closed. */
    @ClosedBody
    public record BnplDisputeResolutionBody(
            @NotBlank @Size(max = 16) String outcome,
            @NotBlank @Size(max = 1000) String reason) {}

    /** A decision on a proposal: the proposal it decides - exactly the one reviewed - and the reason. Closed. */
    @ClosedBody
    public record BnplDisputeDecisionBody(
            @NotBlank @Size(max = 36) String proposalId,
            @NotBlank @Size(max = 1000) String reason) {}
}
