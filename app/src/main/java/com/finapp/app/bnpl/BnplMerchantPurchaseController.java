package com.finapp.app.bnpl;

import com.finapp.app.merchant.MerchantKeyAuthenticationInterceptor;
import com.finapp.app.merchant.RequiresMerchantKey;
import jakarta.servlet.http.HttpServletRequest;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/merchant/bnpl-purchases/{id}} (`P12-TSK-014`; {@code PHASE_12_PLAN.md} section 9): a merchant reads, over
 * its own API key, a BNPL purchase made at it - the gross, the fee and its pinned version, the financed net, the financing
 * entry - and never anything of the customer's credit ({@code INV-CRD-04}). The tenant is the key ({@code INV-MER-01},
 * ADR-0052): no, a revoked or a suspended merchant's key is the platform's {@code 401} before this runs; another
 * merchant's purchase is the uniform {@code 404 bnpl.NotFound}, never {@code 403}. Refunds join at `P12-TSK-021`.
 *
 * <p>The handler is {@code readMerchantBnplPurchase}: springdoc derives each {@code operationId} from the method name.
 */
@RestController
@RequestMapping(path = "/merchant/bnpl-purchases", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresMerchantKey
@RequiredArgsConstructor
public class BnplMerchantPurchaseController {

    @NonNull private final BnplMerchantPurchaseDesk desk;

    /** The authenticated merchant's own BNPL purchase. */
    @GetMapping("/{id}")
    public BnplMerchantPurchaseDesk.BnplMerchantPurchaseView readMerchantBnplPurchase(@PathVariable("id") String id,
            HttpServletRequest request) {
        return desk.read(MerchantKeyAuthenticationInterceptor.require(request), id);
    }
}
