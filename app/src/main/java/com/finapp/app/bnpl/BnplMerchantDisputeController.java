package com.finapp.app.bnpl;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.merchant.MerchantKeyAuthenticationInterceptor;
import com.finapp.app.merchant.RequiresMerchantKey;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
 * The merchant's BNPL dispute doors (`P12-TSK-023`; ADR-0106 decision 4, OQ-9's merchant-key doors): its open disputes,
 * and its answer to one over its own API key before the response deadline - contesting with a statement (an operator
 * resolves) or accepting liability (a refund of the disputed amount at once, under the purchase's one bound). The tenant is
 * the key: another merchant's dispute is the uniform {@code 404 bnpl.NotFound} ({@code INV-MER-01}); the merchant never sees
 * the customer's reason, schedule, repayments or condition.
 */
@RestController
@RequestMapping(path = "/merchant/bnpl-disputes", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresMerchantKey
@RequiredArgsConstructor
public class BnplMerchantDisputeController {

    @NonNull private final BnplDisputeDesk desk;

    /** The authenticated merchant's open BNPL disputes - the amount, category and deadline of each. */
    @GetMapping
    public BnplDisputeDesk.BnplMerchantDisputeList listMerchantBnplDisputes(HttpServletRequest request) {
        return desk.openAtMerchant(MerchantKeyAuthenticationInterceptor.require(request));
    }

    /**
     * The merchant answers its purchase's dispute: {@code CONTEST} with a statement, or {@code ACCEPT} - a refund at once.
     * Keyed ({@code bnpl.dispute-response:MERCHANT:<merchant>}); {@code 409 bnpl.DisputeNotActionable} once answered,
     * resolved or past its deadline.
     */
    @PostMapping(path = "/{id}/response", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    public BnplDisputeDesk.BnplMerchantDisputeView respondToBnplDispute(
            @PathVariable("id") String id,
            @Valid @RequestBody BnplDisputeResponseBody body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return desk.respond(MerchantKeyAuthenticationInterceptor.require(request), id, idempotencyKey, body);
    }

    /**
     * A response: the decision ({@code CONTEST} or {@code ACCEPT}) and the merchant's statement, required (RESTRICTED-PII,
     * screened). Closed - an unknown field is {@code 422}; the merchant is the key's, never the body's to name.
     */
    @ClosedBody
    public record BnplDisputeResponseBody(
            @NotBlank @Size(max = 16) String decision,
            @NotBlank @Size(max = 1000) String statement) {}
}
