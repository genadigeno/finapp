package com.finapp.app.payments;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * Where the payment webhook signing credential comes from (`P5-TSK-012`).
 *
 * <h2>The seventh credential — the arrival {@code ProviderApiKey}'s javadoc promised</h2>
 *
 * <p>One line of {@link KeySpec}, the whole ADR-0020 discipline inherited through
 * `P5-TSK-002`'s generalisation: marked-local-default recognition, loopback confinement,
 * domain separation, both length rules, the non-echoing refusals. The domain suffix
 * {@code "/payment-webhook"} is the one credential five reserved by name — one key per
 * concern, locally too: the bytes that authenticate the provider's <em>statements to us</em>
 * are never the bytes that authenticate <em>us to the provider</em>, so compromising either
 * surface alone forges nothing on the other.
 *
 * <p><strong>{@code AT_LEAST_32}</strong>, the {@code CallbackKey} argument: an HMAC key has
 * no valid-but-weaker interpretation the way an AES key does, so longer is permitted.
 *
 * <p><strong>Lives in {@code app}, consumed at the composition root.</strong> The verifier
 * ({@code WebhookSignature}, in {@code payments}) cannot see this class and takes decoded key
 * bytes through its constructor — the {@code DocumentKey}/{@code DocumentCipher} split.
 */
public final class PaymentWebhookKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "payment webhook signing key",
                    "payment webhook signing key",
                    "FINAPP_PAYMENT_WEBHOOK_KEY",
                    "/payment-webhook",
                    KeyLength.AT_LEAST_32,
                    ".");

    private PaymentWebhookKey() {}

    /**
     * Decodes a configured key.
     *
     * @param configured base64, decoding to at least 32 bytes, or the marked local default
     * @param localDefaultPermitted whether the marked default may be used — false anywhere the
     *     database is not on loopback
     */
    public static byte[] decode(String configured, boolean localDefaultPermitted) {
        return SPEC.decode(configured, localDefaultPermitted);
    }
}
