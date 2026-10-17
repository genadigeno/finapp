package com.finapp.app.bnpl;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's merchant BNPL doors (`P12-TSK-006`; OQ-8): a merchant's BNPL enablement under four eyes - propose,
 * approve (a second, distinct operator), reject, withdraw - each keyed per principal and behind
 * {@code MERCHANT_ADMINISTER}; and which fee schedule prices each BNPL product the merchant offers, behind
 * {@code FEE_ADMINISTER}, reasoned and converging (the existing {@code .../fee-schedule} door's precedent). No merchant
 * key reaches any of this. Reasons are {@code RESTRICTED-PII} and never served.
 *
 * <p>Handler names are distinct platform-wide (springdoc derives each {@code operationId} from them).
 */
@RestController
@RequestMapping(path = "/operator/merchants/{merchantId}", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class MerchantBnplController {

    @NonNull private final MerchantBnplDesk desk;

    /** Proposes enabling BNPL at the merchant - nothing is offered until a second operator approves. */
    @PostMapping(path = "/bnpl-enablement", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.MERCHANT_ADMINISTER)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantBnplDesk.BnplEnablementReceipt proposeMerchantBnplEnablement(
            @PathVariable("merchantId") String merchantId,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody BnplEnablementActRequest body) {
        return desk.propose(idempotencyKey, merchantId, body.reason());
    }

    /** Approves a proposal - never by its proposer ({@code 409 merchant.SelfApprovalRefused}, audited FAILED). */
    @PostMapping(path = "/bnpl-enablement/{enablementId}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.MERCHANT_ADMINISTER)
    @RequiresIdempotencyKey
    public MerchantBnplDesk.BnplEnablementReceipt approveMerchantBnplEnablement(
            @PathVariable("merchantId") String merchantId,
            @PathVariable("enablementId") String enablementId,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody BnplEnablementActRequest body) {
        return desk.approve(idempotencyKey, merchantId, enablementId, body.reason());
    }

    /** Rejects a proposal - any operator, the proposer included. Terminal. */
    @PostMapping(path = "/bnpl-enablement/{enablementId}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.MERCHANT_ADMINISTER)
    @RequiresIdempotencyKey
    public MerchantBnplDesk.BnplEnablementReceipt rejectMerchantBnplEnablement(
            @PathVariable("merchantId") String merchantId,
            @PathVariable("enablementId") String enablementId,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody BnplEnablementActRequest body) {
        return desk.reject(idempotencyKey, merchantId, enablementId, body.reason());
    }

    /** Withdraws the enablement in force - one operator's risk-reducing act. Terminal. */
    @PostMapping(path = "/bnpl-enablement/{enablementId}/withdrawal", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.MERCHANT_ADMINISTER)
    @RequiresIdempotencyKey
    public MerchantBnplDesk.BnplEnablementReceipt withdrawMerchantBnplEnablement(
            @PathVariable("merchantId") String merchantId,
            @PathVariable("enablementId") String enablementId,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody BnplEnablementActRequest body) {
        return desk.withdraw(idempotencyKey, merchantId, enablementId, body.reason());
    }

    /**
     * Points the merchant's BNPL {@code product} ({@code BNPL_PAY_IN_4}, {@code BNPL_PAY_IN_N}) at a fee schedule whose
     * versions return their fee on refund ({@code 422 merchant.BnplScheduleNotReturning} otherwise), reasoned. Converges
     * when it is already there.
     */
    @PutMapping(path = "/bnpl-fee-schedules/{product}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FEE_ADMINISTER)
    public MerchantBnplDesk.BnplFeeScheduleView assignMerchantBnplFeeSchedule(
            @PathVariable("merchantId") String merchantId,
            @PathVariable("product") String product,
            @Valid @RequestBody BnplFeeScheduleAssignmentRequest body) {
        return desk.assignFeeSchedule(merchantId, product, body.feeScheduleId(), body.reason());
    }

    // -----------------------------------------------------------------

    /** An enablement act's reason - required (the audit record's bound). */
    @ClosedBody
    public record BnplEnablementActRequest(@NotBlank @Size(max = 1000) String reason) {}

    /** The schedule to price the product, and why. */
    @ClosedBody
    public record BnplFeeScheduleAssignmentRequest(
            @NotNull UUID feeScheduleId, @NotBlank @Size(max = 1000) String reason) {}
}
