package com.finapp.app.mfa;

import com.finapp.app.security.ConfinedCredential;
import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;
import com.finapp.app.security.ConfinedKeyRing;
import com.finapp.platform.security.KeyRing;
import java.util.Map;
import org.springframework.core.env.Environment;

/**
 * Where the MFA encryption key comes from (`P1-TSK-017`, ADR-0020, {@code INV-IDN-08}).
 *
 * <h2>The seam that already existed, rather than a second mechanism beside it</h2>
 *
 * <p>ADR-0020 established externalised configuration with a <strong>marked local default confined
 * to loopback</strong>, and deferred a secrets manager to Phase 15. Its recorded debt row said the
 * loopback guard <em>"covers one credential"</em> and named the trigger as <em>"the second
 * credential, which is Phase 1's authentication"</em>. This is that credential.
 *
 * <p>So the key is read from configuration, and the marked local default is refused anywhere but a
 * developer's machine — the same shape as {@code DatabaseCredentialGuard}, for the same reason: a
 * published default is only safe while it cannot reach anything real.
 *
 * <h2>Why a missing key must stop the application, not the first challenge</h2>
 *
 * <p>An unusable key produces a platform that starts, serves traffic, and fails at the first MFA
 * challenge — for every customer, at a moment nobody is watching, with an error that looks like a
 * cryptography bug rather than a configuration one. Refusing at startup makes it a deploy failure
 * instead, which is the cheapest place for it to be found.
 *
 * <h2>Re-expressed over the generalised confinement</h2>
 *
 * <p>This was the shape's second hand-written copy; since `P5-TSK-002` it is a
 * {@link ConfinedCredential.KeySpec} declaration, with its behaviour — signature, exception
 * types, message texts, and the derived local key bytes (no domain suffix, because this
 * credential's derivation predates the suffix idea and its bytes must not change) — preserved
 * byte for byte. {@code MfaKeyTest}, untouched, is the equivalence proof.
 */
public final class MfaKey {

    /**
     * The published local default — a reference to the repository's one Java literal of it
     * ({@link ConfinedCredential#MARKED_LOCAL_DEFAULT}), kept here as a public constant because
     * {@code DocumentKey}, {@code CallbackKey} and the test fixtures have referenced it by this
     * name since before the generalisation existed.
     */
    public static final String MARKED_LOCAL_DEFAULT = ConfinedCredential.MARKED_LOCAL_DEFAULT;

    private static final KeySpec SPEC =
            new KeySpec(
                    "MFA encryption key",
                    "MFA encryption key",
                    "FINAPP_MFA_KEY",
                    "",
                    KeyLength.EXACTLY_32,
                    ".");

    private MfaKey() {}

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
    private static final String V2_KEY_PROPERTY = "finapp.mfa.v2.key";

    private static final String V3_KEY_PROPERTY = "finapp.mfa.v3.key";

    private static final ConfinedKeyRing.RingProperties RING =
            new ConfinedKeyRing.RingProperties(
                    "finapp.mfa.ring.versions",
                    "finapp.mfa.ring.active-version",
                    "finapp.mfa.ring.retired-versions",
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
