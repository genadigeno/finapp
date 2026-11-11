package com.finapp.app.merchant;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;
import com.finapp.app.security.ConfinedKeyRing;
import com.finapp.platform.security.KeyRing;
import java.util.Map;
import org.springframework.core.env.Environment;

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

    /**
     * This key's further versions and its ring's non-secret state (`P15-TSK-019`): each named here so the secret inventory's
     * net reads it, and read by {@link ConfinedKeyRing}.
     */
    private static final String V2_KEY_PROPERTY = "finapp.merchant.payout.evidence.v2.key";

    private static final String V3_KEY_PROPERTY = "finapp.merchant.payout.evidence.v3.key";

    private static final String RING_VERSIONS_PROPERTY = "finapp.merchant.payout.evidence.ring.versions";

    private static final String RING_ACTIVE_VERSION_PROPERTY = "finapp.merchant.payout.evidence.ring.active-version";

    private static final String RING_RETIRED_VERSIONS_PROPERTY = "finapp.merchant.payout.evidence.ring.retired-versions";

    private static final String RING_CHECK_VALUES_PROPERTY = "finapp.merchant.payout.evidence.ring.check-values";

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
