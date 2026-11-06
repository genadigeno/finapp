package com.finapp.app.accounting;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
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
 * The valuation rate set doors (`P14-TSK-014`; ADR-0116 decision 2; {@code PHASE_14_PLAN.md} sections 10, 12, 13.4;
 * owner decisions C1, C7, C12): an accountant captures a set for a LOCKED period under {@code ACCOUNTING_CLOSE_PROPOSE} -
 * a close-preparation act, like the lock - from fx's reference rates before the period's end; a financial controller - a
 * different person - approves or rejects it under {@code ACCOUNTING_CLOSE_APPROVE}, the close's decision, and reads the
 * set it decides under the same permission (the grants are a partition, identity {@code V025}). Every act keyed per
 * principal ({@code accounting.valuation:<type>:<id>}). Rates are decimal strings; reasons are RESTRICTED-PII and never
 * served. No customer or merchant route reads a set. The handlers' names are distinctive (the springdoc
 * {@code operationId} rule).
 */
@RestController
@RequestMapping(path = "/operator/accounting/valuation-rate-sets", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ValuationRateSetController {

    @NonNull private final ValuationRateSetDesk desk;

    /**
     * Captures a valuation rate set for a LOCKED period: per valued currency the reference's last EUR-based observation
     * before the period's end, within the staleness bound - one stale or missing pair refuses all. PROPOSED, immutable.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_PROPOSE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public ValuationRateSetDesk.ValuationRateSetReceipt captureValuationRateSet(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody ValuationRateSetCaptureRequest body) {
        return desk.capture(idempotencyKey, body.periodId(), body.reason());
    }

    /**
     * Captures a set across a reference gap (`P14-TSK-026`, ADR-0119 decision 2): for each named pair the reference's real
     * last observation before the period's end although it is older than the staleness bound - the snapshot named, which
     * {@code 422 accounting.ValuationRateStale}'s detail gives - flagged and disclosed; every other pair within the bound.
     * Reasoned. {@code 409 accounting.ReferenceGapRefused} when a named pair is no gap, or its last observation is not the
     * one named. PROPOSED, approved only at its own door.
     */
    @PostMapping(path = "/reference-gap", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_PROPOSE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public ValuationRateSetDesk.ValuationRateSetReceipt captureValuationRateSetAcrossGap(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody ValuationRateSetGapCaptureRequest body) {
        return desk.captureAcrossGap(idempotencyKey, body.periodId(), body.reason(), body.gaps().stream()
                .map(gap -> new ValuationRateSetDesk.ReferenceGapRequest(gap.pair(), gap.snapshotId())).toList());
    }

    /**
     * Approves a pending set holding a reference gap (`P14-TSK-026`) - a person other than its capturer, on a
     * {@code MULTI_FACTOR} session ({@code 403 identity.AssuranceRequired} below it, before anything is read), while the
     * period is LOCKED, each excepted pair still the reference's last observation before the end. A reason is required. A
     * set holding no gap is {@code 409 accounting.ValuationDoorMismatch} here, and one holding a gap the same at the
     * ordinary approval.
     */
    @PostMapping(path = "/{setId}/reference-gap-approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_APPROVE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    public ValuationRateSetDesk.ValuationRateSetReceipt approveValuationRateSetAcrossGap(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("setId") String setId,
            @Valid @RequestBody ValuationRateSetDecisionRequest body) {
        return desk.approveAcrossGap(idempotencyKey, setId, body.reason());
    }

    /**
     * Approves a pending set - a person other than its capturer, while the period is LOCKED; the period's one approved
     * set. A reason is required. A set holding a reference gap is approved at its own door.
     */
    @PostMapping(path = "/{setId}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_APPROVE)
    @RequiresIdempotencyKey
    public ValuationRateSetDesk.ValuationRateSetReceipt approveValuationRateSet(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("setId") String setId,
            @Valid @RequestBody ValuationRateSetDecisionRequest body) {
        return desk.approve(idempotencyKey, setId, body.reason());
    }

    /** Rejects a pending set - terminal; a correction is a new capture. A reason is required. */
    @PostMapping(path = "/{setId}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_APPROVE)
    @RequiresIdempotencyKey
    public ValuationRateSetDesk.ValuationRateSetReceipt rejectValuationRateSet(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("setId") String setId,
            @Valid @RequestBody ValuationRateSetDecisionRequest body) {
        return desk.reject(idempotencyKey, setId, body.reason());
    }

    /** One set by id, in any state - what the controller reviews before deciding: every rate with its provenance. */
    @GetMapping(path = "/{setId}")
    @RequiresPermission(PermissionName.ACCOUNTING_CLOSE_APPROVE)
    public ValuationRateSetDesk.ValuationRateSetView readValuationRateSet(@PathVariable("setId") String setId) {
        return desk.read(setId);
    }

    // -----------------------------------------------------------------

    /** The LOCKED period to value, and an optional reason ({@code accounting.ReasonRequired} when malformed). */
    @ClosedBody
    public record ValuationRateSetCaptureRequest(
            @NotBlank @Size(min = 36, max = 36) String periodId, @Size(max = 1000) String reason) {}

    /** A decision's reason - required, judged by the domain ({@code accounting.ReasonRequired}). */
    @ClosedBody
    public record ValuationRateSetDecisionRequest(@Size(max = 1000) String reason) {}

    /**
     * The LOCKED period, the reason (required, judged by the domain) and the pairs excepted, each with the snapshot id of
     * the observation meant ({@code P14-TSK-026}). A {@code null} element is a {@code 422}, never a 500.
     */
    @ClosedBody
    public record ValuationRateSetGapCaptureRequest(
            @NotBlank @Size(min = 36, max = 36) String periodId,
            @Size(max = 1000) String reason,
            @NotEmpty @Size(max = 20) List<@Valid @NotNull ReferenceGapBody> gaps) {}

    /** One excepted pair: {@code EUR/JPY} and the reference snapshot's id. */
    @ClosedBody
    public record ReferenceGapBody(
            @NotBlank @Size(min = 7, max = 7) String pair, @NotBlank @Size(min = 36, max = 36) String snapshotId) {}
}
