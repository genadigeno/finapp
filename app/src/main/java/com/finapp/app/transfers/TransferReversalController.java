package com.finapp.app.transfers;

import com.finapp.app.session.RecordsRefusals;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.transfers.ReversalProposalStatus;
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
 * The decision on a transfer reversal proposal (`P17-TSK-031`, ADR-0154): a holder of {@code TRANSFER_REVERSE_APPROVE} -
 * {@code FINANCIAL_CONTROLLER} - who did not propose it approves, and the transfer is reversed in the same transaction, or
 * rejects, and nothing moves. Each on a {@code MULTI_FACTOR} session, keyed, reasoned; refusals recorded.
 */
@RestController
@RecordsRefusals
@RequestMapping(path = "/operator/transfer-reversals", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class TransferReversalController {

    @NonNull private final TransferReversalDesk desk;

    /** Approves a reversal the caller did not propose: the transfer is reversed. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/{id}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.TRANSFER_REVERSE_APPROVE)
    @RequiresIdempotencyKey
    public TransferReversalDesk.TransferReversalReceipt approveTransferReversal(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody TransferReversalDecisionRequest body) {
        return desk.decide(idempotencyKey, id, ReversalProposalStatus.APPROVED, body.reason());
    }

    /** Rejects a reversal the caller did not propose: nothing moves. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/{id}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.TRANSFER_REVERSE_APPROVE)
    @RequiresIdempotencyKey
    public TransferReversalDesk.TransferReversalReceipt rejectTransferReversal(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody TransferReversalDecisionRequest body) {
        return desk.decide(idempotencyKey, id, ReversalProposalStatus.REJECTED, body.reason());
    }
}
