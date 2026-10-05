package com.finapp.app.payments;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * Where the corridor provider's API credential comes from (`P9-TSK-014`, ADR-0080) - read as
 * {@code finapp.corridor.provider.key}, one key per counterparty and per concern: the corridor's
 * money-moving API shares no key with its settlement report ({@code CorridorReportKey}) or with any
 * sibling rail. Marked-local-default recognition, loopback confinement, domain separation, both
 * length rules and the non-echoing refusals are inherited from {@code ConfinedCredential};
 * {@code ConfinedCredentialVariablesTest} pins the variable a refusal names to the property the
 * application really reads.
 *
 * <p><strong>{@code AT_LEAST_32}</strong>, the bearer-secret argument. The domain suffix
 * {@code "/corridor-provider"} keeps locally derived bytes separated from every sibling's. Consumed
 * at the composition root: the adapter ({@code SimulatedCorridorAdapter}, in {@code payments}) takes
 * the decoded bytes and never sees this class; they travel only over a transport
 * {@code ProviderTransportGuard} admits, and are never logged. The corridor's callback key arrives
 * with the door that reads it (`P9-TSK-022`).
 */
public final class CorridorProviderKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "corridor provider API key",
                    "corridor provider API key",
                    "FINAPP_CORRIDOR_PROVIDER_KEY",
                    "/corridor-provider",
                    KeyLength.AT_LEAST_32,
                    ".");

    private CorridorProviderKey() {}

    /**
     * Decodes a configured key.
     *
     * @param configured base64, decoding to at least 32 bytes, or the marked local default
     * @param localDefaultPermitted whether the marked default may be used - false anywhere the
     *     database is not on loopback
     */
    public static byte[] decode(String configured, boolean localDefaultPermitted) {
        return SPEC.decode(configured, localDefaultPermitted);
    }
}
