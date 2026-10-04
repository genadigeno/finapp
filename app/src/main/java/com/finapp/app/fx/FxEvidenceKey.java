package com.finapp.app.fx;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * Where the key that encrypts FX provider evidence comes from (`P9-TSK-006`, {@code INV-HIST-02})
 * - read as {@code finapp.fx.evidence.key}, one key per concern: the FX provider's retained bytes
 * never share the card PSP's or the payout provider's evidence key. {@code EXACTLY_32}, an
 * AES-256 key; the domain suffix {@code "/fx-evidence"} keeps locally derived bytes separated from
 * every sibling's. Confinement, recognition of the marked local default and the non-echoing
 * refusals are inherited from {@code ConfinedCredential}; {@code ConfinedCredentialVariablesTest}
 * pins the variable a refusal names to the property the application really reads.
 */
public final class FxEvidenceKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "FX evidence encryption key",
                    "FX evidence encryption key",
                    "FINAPP_FX_EVIDENCE_KEY",
                    "/fx-evidence",
                    KeyLength.EXACTLY_32,
                    ".");

    private FxEvidenceKey() {}

    /**
     * Decodes a configured key.
     *
     * @param configured base64 decoding to exactly 32 bytes, or the marked local default
     * @param localDefaultPermitted whether the marked default may be used - false anywhere the
     *     database is not on loopback
     */
    public static byte[] decode(String configured, boolean localDefaultPermitted) {
        return SPEC.decode(configured, localDefaultPermitted);
    }
}
