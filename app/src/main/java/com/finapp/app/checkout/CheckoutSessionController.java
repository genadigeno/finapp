package com.finapp.app.checkout;

import com.finapp.app.merchant.MerchantKeyAuthenticationInterceptor;
import com.finapp.app.merchant.RequiresMerchantKey;
import com.finapp.merchant.AuthenticatedMerchant;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Objects;
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
 * `/v1/checkout/sessions` (`P6-TSK-007`, ADR-0053): the merchant's half of the purchase
 * experience — making the offer, and reading what became of it.
 *
 * <h2>The tenant is the key, never the request</h2>
 *
 * <p>Both routes authenticate with the merchant's API credential and take their tenant from
 * it. The create body names no merchant; the read resolves through the merchant's own
 * identifier, so an unknown session, a malformed identifier and <strong>another merchant's
 * session</strong> are one {@code 404} ({@code INV-MER-01}, the {@code P1-TSK-016} oracle
 * reasoning at the multi-tenant boundary).
 *
 * <h2>The token appears once, in the creation's response</h2>
 *
 * <p>And in no other response, ever — not the read, not a replay. It is a bearer credential
 * whose possession lets a customer confirm this session, so the platform shows it once and
 * then cannot produce it again ({@code INV-IDN-01}; the merchant API key's shape, inherited).
 * That is also why a replayed create answers {@code alreadyCreated} with no token: recording
 * the response would have persisted a live credential.
 *
 * <p><strong>Handler names are deliberately distinctive</strong> — {@code createCheckoutSession},
 * not {@code create}: springdoc derives each {@code operationId} from the method name and
 * renumbers collisions order-dependently, so a generic name in a new file is a breaking change
 * to somebody else's published endpoint ({@code OpenApiContractTest}'s build rule).
 */
@RestController
@RequestMapping(path = "/checkout/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
public class CheckoutSessionController {

    private final CheckoutService checkout;

    public CheckoutSessionController(CheckoutService checkout) {
        this.checkout = Objects.requireNonNull(checkout, "checkout must not be null");
    }

    /**
     * Opens an offer, or converges on the one this key already opened.
     *
     * <p>Refused with {@code 409} when the merchant is not trading and {@code 422} when it has
     * no fee schedule — both **before** anything is written, because discovering either at the
     * capture would mean discovering it after the customer had paid.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresMerchantKey
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutService.CreatedSessionView createCheckoutSession(
            HttpServletRequest request,
            @Valid @RequestBody CreateSessionRequest body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey) {
        AuthenticatedMerchant merchant = MerchantKeyAuthenticationInterceptor.require(request);
        return checkout.create(merchant, body, idempotencyKey);
    }

    /** The merchant's own session as it stands. Unknown, malformed and another's are one 404. */
    @GetMapping("/{id}")
    @RequiresMerchantKey
    public CheckoutService.SessionView viewCheckoutSession(
            HttpServletRequest request, @PathVariable("id") String id) {
        AuthenticatedMerchant merchant = MerchantKeyAuthenticationInterceptor.require(request);
        return checkout.view(merchant, id);
    }
}
