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
 * The payments inbound door (`P5-TSK-012`, ADR-0047): the platform's second machine-facing
 * surface, the `P2-TSK-011` template with the freshness window payments requires.
 *
 * <h2>{@code @Unauthenticated} is the honest declaration, and the signature is the control</h2>
 *
 * <p>A provider holds no session: the annotation states "no session exists here", never "no
 * control exists here". What authenticates the caller is the HMAC over
 * {@code timestamp + "." + raw body}, verified in {@link PaymentWebhookService} before any
 * read or write — which is why this handler takes the body as <strong>bytes</strong>: the
 * signature is over the bytes the provider sent, and a {@code String} round-trip would make
 * verification depend on charset handling rather than on the wire.
 *
 * <h2>Present only where a provider is ({@code @ConditionalOnProperty})</h2>
 *
 * <p>A deployment with no provider endpoint has no provider to receive webhooks from, so it
 * carries no webhook door either — the KYC door's reasoning. The test overlay supplies the
 * property so the published contract and the route-scanning guards see the full surface.
 */
@RestController
@RequestMapping("/providers/payments/webhooks")
@Unauthenticated
@ConditionalOnProperty("finapp.payments.provider.url")
@RequiredArgsConstructor
public class PaymentWebhookController {

    @NonNull private final PaymentWebhookService webhooks;

    /**
     * Accepts one delivery. {@code 204} acknowledges — processed, duplicate, and
     * authentic-but-unparseable/unmappable alike (ADR-0047 §5): each is "we hold this
     * statement", and a refusal would make a correct provider retry bytes already retained;
     * the distinctions live in our tables, not in the provider's retry loop.
     *
     * <p>Both headers are {@code required = false} deliberately: a missing header must be the
     * same uniform 401 as a wrong one, not a differently-shaped 400 from the framework —
     * which way a forgery failed is not information to hand out.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deliverPaymentWebhook(
            // required = false so an EMPTY body reaches the service's own refusal (the
            // evidence-bound 413) rather than a framework 400 whose shape differs: the door's
            // refusals are decided in one place, like its verifications.
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(name = WebhookSignature.TIMESTAMP_HEADER, required = false)
                    String presentedTimestamp,
            @RequestHeader(name = WebhookSignature.SIGNATURE_HEADER, required = false)
                    String presentedSignature) {
        webhooks.deliver(
                rawBody == null ? new byte[0] : rawBody, presentedTimestamp, presentedSignature);
    }
}
