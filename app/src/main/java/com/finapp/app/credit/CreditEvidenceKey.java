package com.finapp.app.credit;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;
import com.finapp.app.security.ConfinedKeyRing;
import com.finapp.platform.security.KeyRing;
import java.util.Map;
import org.springframework.core.env.Environment;

/**
 * Where the key that encrypts credit evidence comes from (`P10-TSK-006`; ADR-0085 point 3, {@code INV-HIST-02}) -
 * read as {@code finapp.credit.evidence.key}, credit's own key purpose: a credit report never shares another module's
 * evidence key. {@code EXACTLY_32}, an AES-256 key; the domain suffix {@code "/credit-evidence"} keeps locally derived
 * bytes separated from every sibling's. Confinement, the marked local default and the non-echoing refusals are
 * {@code ConfinedCredential}'s; {@code ConfinedCredentialVariablesTest} pins the variable to the property read.
 */
public final class CreditEvidenceKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "credit evidence encryption key",
                    "credit evidence encryption key",
                    "FINAPP_CREDIT_EVIDENCE_KEY",
                    "/credit-evidence",
                    KeyLength.EXACTLY_32,
                    ".");

    private CreditEvidenceKey() {}

    /**
     * Decodes a configured key.
     *
     * @param configured base64 decoding to exactly 32 bytes, or the marked local default
     * @param localDefaultPermitted whether the marked default may be used - false anywhere the database is not on
     *     loopback
     */
    public static byte[] decode(String configured, boolean localDefaultPermitted) {
        return SPEC.decode(configured, localDefaultPermitted);
    }

    /**
     * This key's further versions and its ring's non-secret state (`P15-TSK-019`): each named here so the secret inventory's
     * net reads it, and read by {@link ConfinedKeyRing}.
     */
    private static final String V2_KEY_PROPERTY = "finapp.credit.evidence.v2.key";

    private static final String V3_KEY_PROPERTY = "finapp.credit.evidence.v3.key";

    private static final ConfinedKeyRing.RingProperties RING =
            new ConfinedKeyRing.RingProperties(
                    "finapp.credit.evidence.ring.versions",
                    "finapp.credit.evidence.ring.active-version",
                    "finapp.credit.evidence.ring.retired-versions",
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
