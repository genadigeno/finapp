package com.finapp.app.payments;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;
import com.finapp.app.security.ConfinedKeyRing;
import com.finapp.platform.security.KeyRing;
import java.util.Map;
import org.springframework.core.env.Environment;

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
                    "FINAPP_PAYMENTS_EVIDENCE_KEY",
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

    /**
     * This key's further versions and its ring's non-secret state (`P15-TSK-019`): each named here so the secret inventory's
     * net reads it, and read by {@link ConfinedKeyRing}.
     */
    private static final String V2_KEY_PROPERTY = "finapp.payments.evidence.v2.key";

    private static final String V3_KEY_PROPERTY = "finapp.payments.evidence.v3.key";

    private static final String RING_VERSIONS_PROPERTY = "finapp.payments.evidence.ring.versions";

    private static final String RING_ACTIVE_VERSION_PROPERTY = "finapp.payments.evidence.ring.active-version";

    private static final String RING_RETIRED_VERSIONS_PROPERTY = "finapp.payments.evidence.ring.retired-versions";

    private static final String RING_CHECK_VALUES_PROPERTY = "finapp.payments.evidence.ring.check-values";

    private static final ConfinedKeyRing.RingProperties RING =
            new ConfinedKeyRing.RingProperties(
                    RING_VERSIONS_PROPERTY,
                    RING_ACTIVE_VERSION_PROPERTY,
                    RING_RETIRED_VERSIONS_PROPERTY,
                    RING_CHECK_VALUES_PROPERTY,
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
