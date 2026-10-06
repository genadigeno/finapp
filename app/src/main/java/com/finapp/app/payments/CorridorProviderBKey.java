package com.finapp.app.payments;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * Where the second corridor provider's API credential comes from (`P9-TSK-026`, M9.8) - read as
 * {@code finapp.corridor.provider.b.key}, one key per counterparty: {@code corridor-sim-b} never shares
 * {@code corridor-sim-a}'s key ({@link CorridorProviderKey}). {@code AT_LEAST_32}; the domain suffix
 * {@code "/corridor-provider-b"} separates its locally derived bytes. Consumed at the composition root only.
 */
public final class CorridorProviderBKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "corridor provider corridor-sim-b API key",
                    "corridor provider corridor-sim-b API key",
                    "FINAPP_CORRIDOR_PROVIDER_B_KEY",
                    "/corridor-provider-b",
                    KeyLength.AT_LEAST_32,
                    ".");

    private CorridorProviderBKey() {}

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
