package com.finapp.app.payments;

import com.finapp.app.session.Unauthenticated;
import com.finapp.payments.WebhookSignature;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The corridor provider's inbound door (`P9-TSK-020`, ADR-0083) - the FX door's template:
 * {@code @Unauthenticated} declares "no session exists here", the HMAC in {@link CorridorCallbackService} is the
 * control, the body travels as <strong>bytes</strong> because the signature is over the wire's bytes, and both
 * headers are {@code required = false} so a missing header is the same uniform 401 as a wrong one. A callback is
 * a hint: it triggers an inquiry, and moves nothing itself.
 *
 * <p>Present only where the corridor provider is ({@code @ConditionalOnProperty}).
 */
@RestController
@RequestMapping("/providers/payments/corridor/webhooks")
@Unauthenticated
@ConditionalOnProperty("finapp.corridor.provider.url")
@RequiredArgsConstructor
public class CorridorCallbackController {

    @NonNull private final CorridorCallbackService callbacks;

    /**
     * Accepts one delivery. {@code 202} acknowledges - first, duplicate and authentic-but-unmappable alike: the
     * inquiry the hint triggers is the asynchronous completion, and the distinctions live in our tables.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void deliverCorridorCallback(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(name = WebhookSignature.TIMESTAMP_HEADER, required = false) String presentedTimestamp,
            @RequestHeader(name = WebhookSignature.SIGNATURE_HEADER, required = false) String presentedSignature) {
        callbacks.deliver(rawBody == null ? new byte[0] : rawBody, presentedTimestamp, presentedSignature);
    }
}
