package com.finapp.app.merchant;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * `/v1/operator/merchants/{merchantId}/fee-schedule` (`P6-TSK-004`): which schedule prices a
 * merchant.
 *
 * <p><strong>{@code PUT}, because the assignment is a pointer with one value</strong> — a
 * merchant is on one schedule or none, so the operation states the desired state rather than
 * appending to a collection. It converges: assigning the schedule a merchant is already on
 * returns the same {@code 200} having written nothing — no history row, no audit record —
 * which is {@code INV-IDEM-01} through state, the administrative-move idiom.
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
public class MerchantFeeScheduleController {

    private final FeeScheduleOperations feeSchedules;

    public MerchantFeeScheduleController(FeeScheduleOperations feeSchedules) {
        this.feeSchedules = Objects.requireNonNull(feeSchedules, "feeSchedules must not be null");
    }

    /** Points the merchant at a schedule, reasoned. Converges when it is already there. */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FEE_ADMINISTER)
    public FeeScheduleOperations.MerchantFeeScheduleView assignFeeSchedule(
            @PathVariable("merchantId") String merchantId,
            @Valid @RequestBody AssignFeeScheduleRequest body) {
        return feeSchedules.assign(merchantId, body);
    }

    /** Which schedule prices this merchant. One 404 for an unknown merchant and for none. */
    @GetMapping
    @RequiresPermission(PermissionName.FEE_ADMINISTER)
    public FeeScheduleOperations.MerchantFeeScheduleView viewMerchantFeeSchedule(
            @PathVariable("merchantId") String merchantId) {
        return feeSchedules.assignmentOf(merchantId);
    }
}
