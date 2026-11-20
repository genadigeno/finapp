package com.finapp.app.merchant;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * `/v1/operator/merchants/{merchantId}/fee-schedule` (`P6-TSK-004`): which schedule prices a
 * merchant.
 *
 * <p><strong>{@code PUT}, because the assignment is a pointer with one value</strong> — a
 * merchant is on one schedule or none, so the operation states the desired state rather than
 * appending to a collection. It converges: assigning the schedule a merchant is already on
 * returns the same {@code 200}, {@code ASSIGNED}, having written nothing — no proposal, no
 * history row, no audit record — the administrative-move idiom.
 *
 * <p><strong>By four eyes, keyed</strong> (`P17-TSK-123`; finapp-admin-panel ADR-C135 decision 3, the BNPL door's shape).
 * <em>Read "the pointer moves at this door" until then.</em> Any other assignment is a proposal, judged now and answered
 * {@code PROPOSED}; a holder of {@code FEE_SCHEDULE_APPROVE} who did not propose it moves the pointer
 * ({@link FeeProposalController}). Keyed because a proposal is a new row.
 *
 * <p>The reason is required ({@code INV-AUD-03}): changing what a counterparty is charged is a
 * commercial judgement about that counterparty.
 *
 * <p><strong>Operator-facing only.</strong> A merchant reading its own pricing over its API
 * key is a real capability and it is {@code P6-TSK-009}'s, where it arrives with the tenant
 * predicate that surface requires ({@code INV-MER-01}).
 */
@RestController
@RequestMapping(
        path = "/operator/merchants/{merchantId}/fee-schedule",
        produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class MerchantFeeScheduleController {

    @NonNull private final FeeScheduleOperations feeSchedules;

    /**
     * Proposes pointing the merchant at a schedule, reasoned and keyed: {@code PROPOSED}; {@code ASSIGNED}, nothing
     * proposed, when it is already there.
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FEE_ADMINISTER)
    @RequiresIdempotencyKey
    public FeeScheduleOperations.MerchantFeeScheduleView assignFeeSchedule(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("merchantId") String merchantId,
            @Valid @RequestBody AssignFeeScheduleRequest body) {
        return feeSchedules.proposeAssignment(idempotencyKey, merchantId, body);
    }

    /** Which schedule prices this merchant, {@code ASSIGNED}. One 404 for an unknown merchant and for none. */
    @GetMapping
    @RequiresPermission(PermissionName.FEE_ADMINISTER)
    public FeeScheduleOperations.MerchantFeeScheduleView viewMerchantFeeSchedule(
            @PathVariable("merchantId") String merchantId) {
        return feeSchedules.assignmentOf(merchantId);
    }
}
