package com.finapp.app.payments;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;
import com.finapp.app.security.ConfinedKeyRing;
import com.finapp.platform.security.KeyRing;
import java.util.Map;
import org.springframework.core.env.Environment;

/**
 * Where the dispute-evidence encryption key comes from (`P7-TSK-014`, {@code INV-DSP-03}) — one
 * more {@link KeySpec} declaration, the mechanism doing what `P5-TSK-002` built it for.
 *
 * <p><strong>Its own key, never the payment-evidence key</strong>: dispute evidence is customer
 * material a responder attaches (receipts, correspondence) — {@code INV-KYC-06}'s class — and the
 * provider-evidence rows are PSP payloads. One key per concern is what lets each rotate alone and
 * keeps a leak of one from opening the other's rows (ADR-0036); the domain suffix
 * {@code "/dispute-evidence"} separates the locally derived bytes too.
 *
 * <p><strong>{@code EXACTLY_32}</strong>, the {@code DocumentKey} argument: an AES key that is not
 * exactly 32 bytes is not an AES-256 key. Consumed by {@code PaymentBeans}' dispute evidence
 * store, with {@code DisputeEvidenceKeyTest} asserting this spec's own parts.
 */
public final class DisputeEvidenceKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "dispute evidence encryption key",
                    "dispute evidence encryption key",
                    "FINAPP_PAYMENTS_DISPUTE_EVIDENCE_KEY",
                    "/dispute-evidence",
                    KeyLength.EXACTLY_32,
                    ".");

    private DisputeEvidenceKey() {}

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
    private static final String V2_KEY_PROPERTY = "finapp.payments.dispute.evidence.v2.key";

    private static final String V3_KEY_PROPERTY = "finapp.payments.dispute.evidence.v3.key";

    private static final String RING_VERSIONS_PROPERTY = "finapp.payments.dispute.evidence.ring.versions";

    private static final String RING_ACTIVE_VERSION_PROPERTY = "finapp.payments.dispute.evidence.ring.active-version";

    private static final String RING_RETIRED_VERSIONS_PROPERTY = "finapp.payments.dispute.evidence.ring.retired-versions";

    private static final String RING_CHECK_VALUES_PROPERTY = "finapp.payments.dispute.evidence.ring.check-values";

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
