package com.finapp.app.kyc;

import com.finapp.app.session.Unauthenticated;
import com.finapp.kyc.CallbackSignature;
import java.util.Objects;
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
 * The phase's inbound door: asynchronous provider results (`P2-TSK-011`, plan §7's
 * <em>(inbound)</em> row).
 *
 * <h2>{@code @Unauthenticated} is the honest declaration, and the signature is the control</h2>
 *
 * <p>A provider holds no session — the registration precedent: the annotation states "no
 * session exists here", never "no control exists here". What authenticates the caller is the
 * HMAC signature over the raw body, verified in {@link ProviderCallbackService} before any
 * read or write, which is why this handler takes the body as <strong>bytes</strong>: the
 * signature is over the bytes the provider sent, and a {@code String} round-trip would make
 * verification depend on charset handling rather than on the wire.
 *
 * <h2>Present only where a provider is (`@ConditionalOnProperty`)</h2>
 *
 * <p>The adapters' own reasoning: a deployment with no provider endpoint has no provider to
 * receive callbacks from, so it carries no callback door either. The app test overlay supplies
 * the property so the published contract and the route-scanning guards see the full surface.
 */
@RestController
@RequestMapping("/providers/kyc/callbacks")
@Unauthenticated
@ConditionalOnProperty("finapp.kyc.provider.url")
public class ProviderCallbackController {

    private final ProviderCallbackService callbacks;

    public ProviderCallbackController(ProviderCallbackService callbacks) {
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks must not be null");
    }

    /**
     * Accepts one delivery. {@code 204} acknowledges — processed, duplicate and late-no-op
     * alike, because each is "we have this fact" and a refusal would make a correct provider
     * retry forever; the distinctions live in our tables, not in the provider's retry loop.
     *
     * <p>The signature header is {@code required = false} deliberately: a missing header must
     * be the same uniform 401 as a wrong one, not a differently-shaped 400 from the framework
     * — which way a forgery failed is not information to hand out.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deliver(
            @RequestBody byte[] rawBody,
            @RequestHeader(name = CallbackSignature.HEADER, required = false)
                    String presentedSignature) {
        callbacks.deliver(rawBody, presentedSignature);
    }
}
