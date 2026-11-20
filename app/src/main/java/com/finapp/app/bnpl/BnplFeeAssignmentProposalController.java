package com.finapp.app.bnpl;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The decision on a merchant's BNPL fee assignment proposal (`P17-TSK-116`, ADR-0154's shape; finapp-admin-panel ADR-C135
 * decision 3): a holder of {@code FEE_SCHEDULE_APPROVE} - {@code FINANCIAL_CONTROLLER} - who did not propose it approves,
 * and the merchant's BNPL product is pointed at the schedule in the decision's transaction, or rejects, and nothing changes.
 * Each on a {@code MULTI_FACTOR} session, keyed, reasoned; refusals recorded.
 */
@RestController
@RecordsRefusals
@RequestMapping(path = "/operator/bnpl-fee-schedule-proposals", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class BnplFeeAssignmentProposalController {

    @NonNull private final MerchantBnplDesk desk;

    /** Approves a BNPL fee assignment proposal the caller did not propose: the pointer moves. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/{id}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FEE_SCHEDULE_APPROVE)
    @RequiresIdempotencyKey
    public MerchantBnplDesk.BnplFeeScheduleView approveBnplFeeAssignment(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody BnplFeeAssignmentDecisionRequest body) {
        return desk.approveFeeAssignment(idempotencyKey, id, body.reason());
    }

    /** Rejects a BNPL fee assignment proposal the caller did not propose: nothing changes. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/{id}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FEE_SCHEDULE_APPROVE)
    @RequiresIdempotencyKey
    public MerchantBnplDesk.BnplFeeScheduleView rejectBnplFeeAssignment(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody BnplFeeAssignmentDecisionRequest body) {
        return desk.rejectFeeAssignment(idempotencyKey, id, body.reason());
    }

    /** The decider's reason on a BNPL fee assignment proposal. */
    @ClosedBody
    public record BnplFeeAssignmentDecisionRequest(@NotBlank @Size(max = 1000) String reason) {}
}
