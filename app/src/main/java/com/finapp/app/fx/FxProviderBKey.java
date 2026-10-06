package com.finapp.app.fx;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * Where the second FX provider's API credential comes from (`P9-TSK-026`, M9.8) - read as
 * {@code finapp.fx.provider.b.key}, one key per counterparty: {@code fx-sim-b} never shares
 * {@code fx-sim-a}'s key ({@link FxProviderKey}). {@code AT_LEAST_32}, the bearer-secret argument; the domain
 * suffix {@code "/fx-provider-b"} separates its locally derived bytes. Consumed at the composition root only.
 */
public final class FxProviderBKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "FX provider fx-sim-b API key",
                    "FX provider fx-sim-b API key",
                    "FINAPP_FX_PROVIDER_B_KEY",
                    "/fx-provider-b",
                    KeyLength.AT_LEAST_32,
                    ".");

    private FxProviderBKey() {}

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
