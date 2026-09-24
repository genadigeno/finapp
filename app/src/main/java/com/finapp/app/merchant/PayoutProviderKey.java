package com.finapp.app.merchant;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * Where the payout provider's API key comes from (`P6-TSK-012`) — the credential regime
 * arriving with the provider that moves money, as the destination tokenisation adapter recorded
 * it would. The {@code ProviderApiKey} shape, its own concern.
 *
 * <p><strong>{@code AT_LEAST_32}</strong>: a bearer API secret has no valid-but-weaker reading
 * the way an AES key does, so longer is permitted. The domain suffix {@code "/payout-provider"}
 * keeps the locally derived bytes separated from the payment provider's
 * {@code "/payment-provider"} — one key per concern, locally too.
 *
 * <p><strong>Lives in {@code app}, consumed at the composition root.</strong> The adapter
 * ({@code SimulatedPayoutProvider}, in {@code merchant}) cannot see this class and takes decoded
 * key bytes through its constructor — the {@code DocumentKey}/{@code DocumentCipher} split.
 */
public final class PayoutProviderKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "payout provider API key",
                    "payout provider API key",
                    "FINAPP_MERCHANT_PAYOUT_PROVIDER_KEY",
                    "/payout-provider",
                    KeyLength.AT_LEAST_32,
                    ".");

    private PayoutProviderKey() {}

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
