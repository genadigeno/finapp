package com.finapp.app.merchant;

import com.finapp.app.api.RecordedRead;
import com.finapp.app.session.RecordsRefusals;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.merchant.ClosureProposalStatus;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
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
 * The decision on a merchant closure proposal (`P17-TSK-031`, ADR-0154): a holder of {@code MERCHANT_CLOSE_APPROVE} -
 * {@code FINANCIAL_CONTROLLER} - who did not propose it approves, and the merchant is closed in the same transaction, or
 * rejects, and nothing moves. Each on a {@code MULTI_FACTOR} session, keyed, reasoned; refusals recorded.
 */
@RestController
@RecordsRefusals
@RequestMapping(path = "/operator/merchant-closures", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class MerchantClosureController {

    @NonNull private final MerchantClosureDesk desk;

    /**
     * One merchant closure proposal - the merchant, its status, the reason and who proposed it - what its decider decides
     * (`P17-TSK-124`). Every serving recorded ({@code merchant.ClosureProposalRead}); unknown or malformed 404.
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @GetMapping("/{id}")
    @RequiresPermission(PermissionName.MERCHANT_CLOSE_APPROVE)
    @RecordedRead
    public MerchantClosureDesk.MerchantClosureProposalView readMerchantClosureProposal(@PathVariable("id") String id) {
        return desk.read(id);
    }

    /** Approves a closure the caller did not propose: the merchant is closed. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/{id}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.MERCHANT_CLOSE_APPROVE)
    @RequiresIdempotencyKey
    public MerchantClosureDesk.ClosureReceipt approveMerchantClosure(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody MerchantStandingRequest body) {
        return desk.decide(idempotencyKey, id, ClosureProposalStatus.APPROVED, body.reason());
    }

    /** Rejects a closure the caller did not propose: nothing moves. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/{id}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.MERCHANT_CLOSE_APPROVE)
    @RequiresIdempotencyKey
    public MerchantClosureDesk.ClosureReceipt rejectMerchantClosure(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody MerchantStandingRequest body) {
        return desk.decide(idempotencyKey, id, ClosureProposalStatus.REJECTED, body.reason());
    }
}
