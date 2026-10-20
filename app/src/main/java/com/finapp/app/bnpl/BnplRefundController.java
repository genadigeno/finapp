package com.finapp.app.bnpl;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.merchant.MerchantKeyAuthenticationInterceptor;
import com.finapp.app.merchant.RequiresMerchantKey;
import com.finapp.bnpl.BnplSpans;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
 * {@code POST /v1/merchant/bnpl-purchases/{id}/refunds} (`P12-TSK-021`; ADR-0105 decision 9, {@code PHASE_12_PLAN.md}
 * section 9): the platform's first merchant-key refund door - a merchant refunds all or part of a BNPL purchase made at
 * it, over its own API key carrying the {@code BNPL_REFUND} scope, keyed ({@code bnpl.refund:MERCHANT:<merchant>}), with a
 * reason, {@code 201} with the refund's purchase-level figures. The tenant is the key: another merchant's purchase is the
 * uniform {@code 404 bnpl.NotFound}; a key without the scope {@code 403 bnpl.RefundScopeRequired}. Bounded only by the
 * purchase ({@code 422 bnpl.RefundExceedsPurchase}, {@code 409 bnpl.PurchaseFullyRefunded}), never by the payable - a
 * refund after a payout may leave it below zero by at most the purchase's net (B7).
 *
 * <p>The handler is {@code refundMerchantBnplPurchase}: springdoc derives each {@code operationId} from the method name.
 */
@RestController
@RequestMapping(path = "/merchant/bnpl-purchases", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresMerchantKey
@RequiredArgsConstructor
public class BnplRefundController {

    @NonNull private final BnplRefundDesk desk;
    @NonNull private final BnplFlowScope flow;

    /** The authenticated merchant refunds its own BNPL purchase. */
    @PostMapping(path = "/{id}/refunds", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public BnplRefundDesk.BnplMerchantRefundView refundMerchantBnplPurchase(
            @PathVariable("id") String id,
            @Valid @RequestBody BnplRefundBody body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return flow.doorOnPurchase(BnplSpans.REFUND, id, () -> desk.refundAsMerchant(MerchantKeyAuthenticationInterceptor.require(request), id, idempotencyKey, body));
    }

    /**
     * A refund: the amount and its currency - an exact decimal at the currency's minor units - and the reason, required
     * (RESTRICTED-PII, screened for card and account numbers). Closed - an unknown field is {@code 422}; the merchant is the
     * key's, never the body's to name, and the body carries no instalment, customer or wallet.
     */
    @ClosedBody
    public record BnplRefundBody(
            @NotBlank @Size(max = 32) String amount,
            @NotBlank @Size(max = 3) String currency,
            @NotBlank @Size(max = 1000) String reason) {}
}
