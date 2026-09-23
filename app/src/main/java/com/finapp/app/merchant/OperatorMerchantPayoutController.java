package com.finapp.app.merchant;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * An operator's payout on a merchant's behalf (`P6-TSK-012`, ADR-0057 §6) — its own route,
 * because the authentication populations are disjoint by design (ADR-0052): a handler cannot
 * take a merchant's key and an operator's session both, so the plan's "merchant key (or
 * operator)" is two routes over one command.
 *
 * <p>{@code MERCHANT_PAYOUT}, held by the money-operating population, with a reason that is
 * required and audited. What the operator cannot choose is where the money goes: the merchant's
 * effective destination, and nothing else.
 */
@RestController
@RequestMapping(
        path = "/operator/merchants/{merchantId}/payouts",
        produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class OperatorMerchantPayoutController {

    @NonNull private final MerchantPayoutOperations payouts;

    /**
     * Initiates a payout of the merchant's payable, keyed, reasoned. {@code 201} with the honest
     * status; an unknown or malformed merchant is one 404.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.MERCHANT_PAYOUT)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantPayoutOperations.PayoutView initiatePayoutForMerchant(
            @PathVariable("merchantId") String merchantId,
            @Valid @RequestBody OperatorPayoutRequest body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey) {
        return payouts.initiateAsOperator(merchantId, body, idempotencyKey);
    }
}
