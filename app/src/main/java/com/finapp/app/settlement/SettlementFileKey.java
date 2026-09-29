package com.finapp.app.settlement;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * Where the settlement-file encryption key comes from (`P8-TSK-002`, ADR-0066 §6,
 * {@code INV-REC-10}) — the platform's twelfth confined credential.
 *
 * <p><strong>Its own key, never the provider-evidence, dispute-evidence or document key</strong>:
 * settlement files are counterparties' statements — bank statements name people
 * ({@code INV-KYC-06}'s class) — and one key per concern is what lets each rotate, and leak,
 * alone (ADR-0036); the domain suffix {@code "/settlement-file"} separates the locally derived
 * bytes too.
 *
 * <p><strong>{@code EXACTLY_32}</strong>, the {@code DocumentKey} argument: an AES key that is
 * not exactly 32 bytes is not an AES-256 key. Consumed by {@code SettlementBeans}' file store,
 * with {@code SettlementFileKeyTest} asserting this spec's own parts.
 */
public final class SettlementFileKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "settlement file encryption key",
                    "settlement file encryption key",
                    "FINAPP_SETTLEMENT_FILE_KEY",
                    "/settlement-file",
                    KeyLength.EXACTLY_32,
                    ".");

    private SettlementFileKey() {}

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
