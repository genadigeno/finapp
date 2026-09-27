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
 * The instant rail's inbound door (`P7-TSK-009`, ADR-0062 §5) — the
 * {@code PaymentWebhookController} template at the second rail: {@code @Unauthenticated}
 * declares "no session exists here", the per-rail HMAC in {@link InstantCallbackService}
 * is the control, the body travels as <strong>bytes</strong> because the signature is
 * over the wire's bytes, and both headers are {@code required = false} so a missing
 * header is the same uniform 401 as a wrong one.
 *
 * <p>Present only where the scheme is ({@code @ConditionalOnProperty}): a deployment with
 * no instant rail receives no confirmations of it.
 */
@RestController
@RequestMapping("/providers/payments/instant/webhooks")
@Unauthenticated
@ConditionalOnProperty("finapp.payments.instant.url")
@RequiredArgsConstructor
public class InstantCallbackController {

    @NonNull private final InstantCallbackService callbacks;

    /**
     * Accepts one delivery. {@code 204} acknowledges — processed, duplicate, parked and
     * authentic-but-unmappable alike (ADR-0047 §5): the distinctions live in our tables,
     * not in the scheme's retry loop.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deliverInstantConfirmation(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(name = WebhookSignature.TIMESTAMP_HEADER, required = false)
                    String presentedTimestamp,
            @RequestHeader(name = WebhookSignature.SIGNATURE_HEADER, required = false)
                    String presentedSignature) {
        callbacks.deliver(
                rawBody == null ? new byte[0] : rawBody, presentedTimestamp, presentedSignature);
    }
}
