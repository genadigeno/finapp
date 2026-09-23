package com.finapp.app.merchant;

import com.finapp.merchant.AuthenticatedMerchant;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
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
 * A merchant's own payouts (`P6-TSK-012`, ADR-0051): initiate with its API key, keyed, and read
 * one back.
 *
 * <h2>The tenant is the key</h2>
 *
 * <p>No merchant identifier in the route: the merchant is the one the key authenticated, and a
 * payout of another merchant's payable has no shape a request could take. A payout goes only
 * to the merchant's effective destination — four-eyes-approved and cooled off (ADR-0056) — so a
 * leaked key can pay the merchant's own money to the merchant's own verified account, and no
 * further.
 *
 * <p>Handler names are distinct platform-wide ({@code initiateMerchantPayout}, not
 * {@code initiate}): springdoc derives each {@code operationId} from them.
 */
@RestController
@RequestMapping(path = "/merchant/payouts", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class MerchantPayoutController {

    @NonNull private final MerchantPayoutOperations payouts;

    /**
     * Initiates a payout, or replays the recorded judgement for a retried key
     * ({@code INV-IDEM-01}); a reused key for a different request is the distinct {@code 409}
     * ({@code INV-IDEM-03}). {@code 201} with the honest status: {@code COMPLETED},
     * {@code FAILED}, or {@code UNKNOWN} with the amount still held.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresMerchantKey
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantPayoutOperations.PayoutView initiateMerchantPayout(
            HttpServletRequest request,
            @Valid @RequestBody MerchantPayoutRequest body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey) {
        AuthenticatedMerchant merchant = MerchantKeyAuthenticationInterceptor.require(request);
        return payouts.initiateAsMerchant(merchant, body, idempotencyKey);
    }

    /** One of the merchant's payouts, current. Another merchant's and unknown are one 404. */
    @GetMapping("/{payoutId}")
    @RequiresMerchantKey
    public MerchantPayoutOperations.PayoutView viewMerchantPayout(
            HttpServletRequest request, @PathVariable("payoutId") String payoutId) {
        AuthenticatedMerchant merchant = MerchantKeyAuthenticationInterceptor.require(request);
        return payouts.view(merchant, payoutId);
    }
}
