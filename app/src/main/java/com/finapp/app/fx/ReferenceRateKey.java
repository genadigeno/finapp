package com.finapp.app.fx;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * Where the credential that reads the independent reference rate comes from (`P9-TSK-005`,
 * ADR-0075's security impact) - read as {@code finapp.fx.reference.key}, one key per concern:
 * the reference is a different party than the FX provider, so it never shares that provider's
 * key. Marked-local-default recognition, loopback confinement, domain separation, both length
 * rules and the non-echoing refusals are inherited from {@code ConfinedCredential};
 * {@code ConfinedCredentialVariablesTest} pins the variable a refusal names to the property the
 * application really reads.
 *
 * <p><strong>{@code AT_LEAST_32}</strong>, the bearer-secret argument; the domain suffix
 * {@code "/fx-reference"} keeps locally derived bytes separated from every sibling's. Consumed
 * at the composition root: the adapter takes the decoded bytes and never sees this class; they
 * are sent only over a transport {@code ProviderTransportGuard} admits, and never logged.
 */
public final class ReferenceRateKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "FX reference rate key",
                    "FX reference rate key",
                    "FINAPP_FX_REFERENCE_KEY",
                    "/fx-reference",
                    KeyLength.AT_LEAST_32,
                    ".");

    private ReferenceRateKey() {}

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
