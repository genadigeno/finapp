package com.finapp.app.fx;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * Where the FX provider's <em>webhook</em> signing key comes from (`P9-TSK-012`, ADR-0077 section 9)
 * - read as {@code finapp.fx.webhook.key}, one key per concern: the callback door's HMAC secret
 * never shares the outbound API key ({@link FxProviderKey}). {@code AT_LEAST_32}, the HMAC-secret
 * argument; the domain suffix {@code "/fx-provider-webhook"} separates its locally derived bytes.
 * A stolen key moves no money: a callback is a hint, and only the authenticated inquiry it
 * triggers moves a cover.
 */
public final class FxWebhookKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "FX provider webhook key",
                    "FX provider webhook key",
                    "FINAPP_FX_WEBHOOK_KEY",
                    "/fx-provider-webhook",
                    KeyLength.AT_LEAST_32,
                    ".");

    private FxWebhookKey() {}

    /**
     * Decodes a configured key.
     *
     * @param configured base64, decoding to at least 32 bytes, or the marked local default
     * @param localDefaultPermitted whether the marked default may be used - false anywhere the
     *     database is not on loopback
     */
    public static byte[] decode(String configured, boolean localDefaultPermitted) {
        return SPEC.decode(configured, localDefaultPermitted);
    }
}
