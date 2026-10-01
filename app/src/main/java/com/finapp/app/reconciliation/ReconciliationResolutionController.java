package com.finapp.app.reconciliation;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The resolver's doors (`P8-TSK-015`, ADR-0071 §11): propose, approve, reject and withdraw a
 * break resolution — every one under {@link PermissionName#RECONCILIATION_RESOLVE} (the
 * {@code RECONCILIATION_OPERATOR} role; the controller's role alone is refused: whoever can
 * loosen a tolerance cannot resolve the breaks it would hide). The request carries no amount
 * and no account but a transfer's target — the lines are the template's. Handler names are
 * deliberately distinctive (the springdoc {@code operationId} rule).
 */
@RestController
@RequestMapping(
        path = "/operator/reconciliation",
        produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ReconciliationResolutionController {

    @NonNull private final BreakResolutionDesk desk;

    /**
     * The kind, its closed reason code and the investigator's narrative (1..1000 characters,
     * CONFIDENTIAL — screened for card-number and bank-account shapes, never logged, evented or
     * audited), with the ONE operand the kind takes: a transfer's target, an offset's item, or a
     * manual match's chosen candidate.
     */
    public record ResolutionProposalRequest(
            @NotNull String kind,
            @NotNull String reasonCode,
            @NotNull String narrative,
            String targetAccountId,
            String offsetItemId,
            String chosenExpectationId) {}

    /** The rejecting person's reason (1..1000 characters). */
    public record ResolutionRejectionRequest(@NotNull String reason) {}

    /**
     * A batch's repudiation: the closed reason code ({@code EVIDENCE_REPUDIATED}) and the
     * investigator's narrative - CONFIDENTIAL, screened, never logged, evented or audited.
     * No amount and no account: the plan is derived from the rows.
     */
    public record BatchRepudiationRequest(@NotNull String reasonCode, @NotNull String narrative) {}

    /** Proposes a template-bound resolution, keyed per principal; one live per break. */
    @PostMapping(path = "/breaks/{id}/resolutions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RECONCILIATION_RESOLVE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public BreakResolutionDesk.ResolutionReceipt proposeReconciliationBreakResolution(
            @PathVariable("id") String breakId,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody ResolutionProposalRequest body) {
        return desk.propose(
                breakId, idempotencyKey, body.kind(), body.reasonCode(), body.narrative(),
                body.targetAccountId(), body.offsetItemId(), body.chosenExpectationId());
    }

    /**
     * Proposes a settlement batch's repudiation (`P8-TSK-023`, ADR-0065 §10) - keyed per
     * principal, one live per batch; a second person approves it through the resolution
     * doors below.
     */
    @PostMapping(
            path = "/batches/{settlementBatchId}/repudiation",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RECONCILIATION_RESOLVE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public BreakResolutionDesk.RepudiationReceipt proposeReconciliationBatchRepudiation(
            @PathVariable("settlementBatchId") String settlementBatchId,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody BatchRepudiationRequest body) {
        return desk.proposeRepudiation(
                settlementBatchId, idempotencyKey, body.reasonCode(), body.narrative());
    }

    /** Approves a pending resolution — a second person's act; synchronous, one transaction. */
    @PostMapping("/resolutions/{id}/approval")
    @RequiresPermission(PermissionName.RECONCILIATION_RESOLVE)
    public BreakResolutionDesk.ResolutionDecision approveReconciliationResolution(
            @PathVariable("id") String resolutionId) {
        return desk.approve(resolutionId);
    }

    /** Rejects a pending resolution — another person's reasoned act. */
    @PostMapping(
            path = "/resolutions/{id}/rejection",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RECONCILIATION_RESOLVE)
    public BreakResolutionDesk.ResolutionDecision rejectReconciliationResolution(
            @PathVariable("id") String resolutionId,
            @Valid @RequestBody ResolutionRejectionRequest body) {
        return desk.reject(resolutionId, body.reason());
    }

    /** Withdraws a pending resolution — its proposer's act; the row moves, nothing is deleted. */
    @DeleteMapping("/resolutions/{id}")
    @RequiresPermission(PermissionName.RECONCILIATION_RESOLVE)
    public BreakResolutionDesk.ResolutionDecision withdrawReconciliationResolution(
            @PathVariable("id") String resolutionId) {
        return desk.withdraw(resolutionId);
    }
}
