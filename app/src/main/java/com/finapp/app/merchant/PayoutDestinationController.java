package com.finapp.app.merchant;

import com.finapp.app.session.RequiresPermission;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.PermissionName;
import com.finapp.identity.Session;
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
 * The payout destination's operator surface (`P6-TSK-011`, ADR-0056): propose, read, approve,
 * reject, withdraw — each an operator session's act, each audited with its reason.
 *
 * <p><strong>No merchant key reaches any of this</strong> (ADR-0056 §1): a merchant has only a
 * machine credential this phase, and a leaked server key must not be one click from redirecting
 * its money. The routes sit under {@code /v1/operator}, behind the session interceptor.
 *
 * <p>Handler names are distinct platform-wide ({@code proposePayoutDestination}, not
 * {@code propose}): springdoc derives each {@code operationId} from them, and a collision would
 * renumber operations that already shipped — the {@code viewMerchant} finding.
 */
@RestController
@RequestMapping(
        path = "/operator/merchants/{merchantId}/payout-destinations",
        produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class PayoutDestinationController {

    @NonNull private final PayoutDestinationOperations destinations;

    /**
     * Proposes a destination from a payout provider grant, or replays the recorded proposal for a
     * retried key ({@code INV-IDEM-01}); a reused key for a different request is the distinct
     * {@code 409} ({@code INV-IDEM-03}). Step-up when the proposer has a factor.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.MERCHANT_ADMINISTER)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public PayoutDestinationOperations.PayoutDestinationView proposePayoutDestination(
            @PathVariable("merchantId") String merchantId,
            @Valid @RequestBody ProposePayoutDestinationRequest body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return destinations.propose(current(request), merchantId, body, idempotencyKey);
    }

    /** The effective destination and the open change, masked. Unknown merchant: one 404. */
    @GetMapping
    @RequiresPermission(PermissionName.MERCHANT_ADMINISTER)
    public PayoutDestinationOperations.PayoutDestinationsView listPayoutDestinations(
            @PathVariable("merchantId") String merchantId) {
        return destinations.list(merchantId);
    }

    /**
     * Approves as a second operator — never the proposer ({@code INV-AUD-04}: refused {@code 409}
     * and audited {@code DENIED}). Step-up when the approver has a factor. The cooling-off starts.
     */
    @PostMapping(path = "/{destinationId}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.PAYOUT_DESTINATION_APPROVE)
    public PayoutDestinationOperations.PayoutDestinationView approvePayoutDestination(
            @PathVariable("merchantId") String merchantId,
            @PathVariable("destinationId") String destinationId,
            @Valid @RequestBody PayoutDestinationDecisionRequest body,
            HttpServletRequest request) {
        return destinations.approve(current(request), merchantId, destinationId, body);
    }

    /** Rejects a proposed destination, reasoned. Terminal. */
    @PostMapping(path = "/{destinationId}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.PAYOUT_DESTINATION_APPROVE)
    public PayoutDestinationOperations.PayoutDestinationView rejectPayoutDestination(
            @PathVariable("merchantId") String merchantId,
            @PathVariable("destinationId") String destinationId,
            @Valid @RequestBody PayoutDestinationDecisionRequest body) {
        return destinations.reject(merchantId, destinationId, body);
    }

    /** Withdraws a change before it takes effect — during the proposal or the cooling-off. */
    @PostMapping(path = "/{destinationId}/withdrawal", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.MERCHANT_ADMINISTER)
    public PayoutDestinationOperations.PayoutDestinationView withdrawPayoutDestination(
            @PathVariable("merchantId") String merchantId,
            @PathVariable("destinationId") String destinationId,
            @Valid @RequestBody PayoutDestinationDecisionRequest body) {
        return destinations.withdraw(merchantId, destinationId, body);
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: the payout destination routes are"
                        + " reachable without SessionAuthenticationInterceptor having run");
    }
}
