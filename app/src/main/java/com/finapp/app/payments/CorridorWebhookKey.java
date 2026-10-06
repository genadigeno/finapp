package com.finapp.app.payments;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * Where the corridor provider's <em>webhook</em> signing key comes from (`P9-TSK-020`, ADR-0083) - read as
 * {@code finapp.corridor.webhook.key}, one key per concern: the callback door's HMAC secret never shares the
 * outbound API key ({@link CorridorProviderKey}). {@code AT_LEAST_32}, the HMAC-secret argument; the domain
 * suffix {@code "/corridor-provider-webhook"} separates its locally derived bytes. A stolen key moves no money:
 * a callback is a hint, and only the authenticated inquiry it triggers moves a credit.
 */
public final class CorridorWebhookKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "Corridor provider webhook key",
                    "Corridor provider webhook key",
                    "FINAPP_CORRIDOR_WEBHOOK_KEY",
                    "/corridor-provider-webhook",
                    KeyLength.AT_LEAST_32,
                    ".");

    private CorridorWebhookKey() {}

    /**
     * Decodes a configured key.
     *
     * @param configured base64, decoding to at least 32 bytes, or the marked local default
     * @param localDefaultPermitted whether the marked default may be used - false anywhere the database is not on
     *     loopback
     */
    public static byte[] decode(String configured, boolean localDefaultPermitted) {
        return SPEC.decode(configured, localDefaultPermitted);
    }
}
