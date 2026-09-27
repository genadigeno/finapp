package com.finapp.app.payments;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * Where the instant scheme's <em>webhook</em> signing key comes from (`P7-TSK-009`,
 * ADR-0062 §5) — the per-rail key `P7-TSK-006` deliberately deferred to the door that
 * reads it: the {@code PaymentWebhookKey} discipline at the second rail's inbound door.
 * Marked-local-default recognition, loopback confinement, domain separation, both length
 * rules and the non-echoing refusals are inherited from {@code ConfinedCredential};
 * {@code ConfinedCredentialVariablesTest} pins the variable to the property the
 * application really reads.
 *
 * <p><strong>{@code AT_LEAST_32}</strong>, the HMAC-secret argument; the domain suffix
 * {@code "/instant-scheme-webhook"} keeps locally derived bytes separated from the
 * outbound API key's ({@code "/instant-scheme"}) — a signature key and a bearer key are
 * different secrets even toward one counterparty.
 */
public final class InstantWebhookKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "instant scheme webhook key",
                    "instant scheme webhook key",
                    "FINAPP_PAYMENTS_INSTANT_WEBHOOK_KEY",
                    "/instant-scheme-webhook",
                    KeyLength.AT_LEAST_32,
                    ".");

    private InstantWebhookKey() {}

    /**
     * Decodes a configured key.
     *
     * @param configured base64, decoding to at least 32 bytes, or the marked local default
     * @param localDefaultPermitted whether the marked default may be used — false anywhere
     *     the database is not on loopback
     */
    public static byte[] decode(String configured, boolean localDefaultPermitted) {
        return SPEC.decode(configured, localDefaultPermitted);
    }
}
