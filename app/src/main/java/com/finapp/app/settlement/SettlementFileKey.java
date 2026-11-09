package com.finapp.app.settlement;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;
import com.finapp.app.security.ConfinedKeyRing;
import com.finapp.platform.security.KeyRing;
import java.util.Map;
import org.springframework.core.env.Environment;

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

    /**
     * This key's further versions and its ring's non-secret state (`P15-TSK-019`): each named here so the secret inventory's
     * net reads it, and read by {@link ConfinedKeyRing}.
     */
    private static final String V2_KEY_PROPERTY = "finapp.settlement.file.v2.key";

    private static final String V3_KEY_PROPERTY = "finapp.settlement.file.v3.key";

    private static final ConfinedKeyRing.RingProperties RING =
            new ConfinedKeyRing.RingProperties(
                    "finapp.settlement.file.ring.versions",
                    "finapp.settlement.file.ring.active-version",
                    "finapp.settlement.file.ring.retired-versions",
                    Map.of(2, V2_KEY_PROPERTY, 3, V3_KEY_PROPERTY));

    /**
     * This key's ring: the base key at {@code baseVersion}, the further versions the environment lists, the active one
     * where the environment points. Absent ring configuration, one version: the base key alone, exactly as it always was.
     */
    public static KeyRing ring(
            String configuredBase, int baseVersion, Environment environment, boolean localDefaultPermitted) {
        return ConfinedKeyRing.read(SPEC, RING, configuredBase, baseVersion, environment, localDefaultPermitted);
    }
}
