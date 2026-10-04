package com.finapp.app.fx;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * Where the FX provider's API credential comes from (`P9-TSK-006`, ADR-0075's security impact) -
 * read as {@code finapp.fx.provider.key}, one key per concern: the money-moving FX API never
 * shares the reference source's key ({@link ReferenceRateKey}). {@code AT_LEAST_32}, the
 * bearer-secret argument; the domain suffix {@code "/fx-provider"} separates its locally derived
 * bytes. Consumed at the composition root: the adapter takes the decoded bytes, sends them only
 * over a transport {@code ProviderTransportGuard} admits, and never logs them.
 */
public final class FxProviderKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "FX provider API key",
                    "FX provider API key",
                    "FINAPP_FX_PROVIDER_KEY",
                    "/fx-provider",
                    KeyLength.AT_LEAST_32,
                    ".");

    private FxProviderKey() {}

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
