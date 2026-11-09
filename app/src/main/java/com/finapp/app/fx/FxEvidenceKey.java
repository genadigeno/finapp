package com.finapp.app.fx;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;
import com.finapp.app.security.ConfinedKeyRing;
import com.finapp.platform.security.KeyRing;
import java.util.Map;
import org.springframework.core.env.Environment;

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

    /**
     * This key's further versions and its ring's non-secret state (`P15-TSK-019`): each named here so the secret inventory's
     * net reads it, and read by {@link ConfinedKeyRing}.
     */
    private static final String V2_KEY_PROPERTY = "finapp.fx.evidence.v2.key";

    private static final String V3_KEY_PROPERTY = "finapp.fx.evidence.v3.key";

    private static final ConfinedKeyRing.RingProperties RING =
            new ConfinedKeyRing.RingProperties(
                    "finapp.fx.evidence.ring.versions",
                    "finapp.fx.evidence.ring.active-version",
                    "finapp.fx.evidence.ring.retired-versions",
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
