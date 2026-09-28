package com.finapp.app.merchant;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * Where the payout-evidence encryption key comes from (`P6-TSK-012`) — a {@link KeySpec}
 * declaration, the {@code PaymentEvidenceKey} shape for the payout provider's retained answers.
 *
 * <p><strong>{@code EXACTLY_32}</strong>: an AES key that is not exactly 32 bytes is not an
 * AES-256 key. The domain suffix {@code "/payout-evidence"} keeps the locally derived bytes
 * separated from every sibling's — one key per concern, locally too: never the payment-evidence
 * key, never the payout provider's API key, so each rotates alone.
 *
 * <p>Consumed by {@code MerchantPayoutBeans}' {@code PayoutEvidenceCipher}, with
 * {@code PayoutEvidenceKeyTest} asserting this spec's own parts (the `P2-TSK-011` lesson).
 */
public final class PayoutEvidenceKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "payout evidence encryption key",
                    "payout evidence encryption key",
                    "FINAPP_MERCHANT_PAYOUT_EVIDENCE_KEY",
                    "/payout-evidence",
                    KeyLength.EXACTLY_32,
                    ".");

    private PayoutEvidenceKey() {}

    /**
     * Decodes a configured key.
     *
     * @param configured base64, decoding to exactly 32 bytes, or the marked local default
     * @param localDefaultPermitted whether the marked default may be used — false anywhere the
     *     database is not on loopback
     */
    public static byte[] decode(String configured, boolean localDefaultPermitted) {
        return SPEC.decode(configured, localDefaultPermitted);
    }
}
