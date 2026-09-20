package com.finapp.app.payments;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * Where the payment-evidence encryption key comes from (`P5-TSK-009`) — the sixth credential,
 * and the second to arrive as a one-line {@link KeySpec} declaration (`P5-TSK-002`'s
 * generalisation doing exactly what it was built for; {@code ProviderApiKey} was the first).
 *
 * <p><strong>{@code EXACTLY_32}</strong>, the {@code DocumentKey} argument: an AES key that is
 * not exactly 32 bytes is not an AES-256 key, and stretching or truncating one silently is how
 * a cipher looks like AES-256 and is not. The domain suffix {@code "/payment-evidence"} keeps
 * the locally derived bytes separated from every sibling's — one key per concern, locally too:
 * never the document key ({@code FINAPP_DOC_KEY}), never the provider API key, so each rotates
 * alone ({@code INV-HIST-04}'s rule applied to a key).
 *
 * <p>Consumed by {@code PaymentBeans}' {@code EvidenceCipher}, with {@code PaymentEvidenceKeyTest}
 * asserting this spec's own parts (the `P2-TSK-011` lesson: a credential class with no test is
 * a confinement nobody would notice removed).
 */
public final class PaymentEvidenceKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "payment evidence encryption key",
                    "payment evidence encryption key",
                    "FINAPP_PAYMENT_EVIDENCE_KEY",
                    "/payment-evidence",
                    KeyLength.EXACTLY_32,
                    ".");

    private PaymentEvidenceKey() {}

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
