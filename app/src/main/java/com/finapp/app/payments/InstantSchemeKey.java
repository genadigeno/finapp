package com.finapp.app.payments;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * Where the instant scheme's API credential comes from (`P7-TSK-006`, ADR-0062 §1) — the
 * {@code ProviderApiKey} discipline, one line per rail: marked-local-default recognition,
 * loopback confinement, domain separation, both length rules and the non-echoing refusals
 * are all inherited from {@code ConfinedCredential}, and
 * {@code ConfinedCredentialVariablesTest} pins the variable a refusal names to the property
 * the application really reads.
 *
 * <p><strong>{@code AT_LEAST_32}</strong>, the bearer-secret argument: no valid-but-weaker
 * interpretation exists, so longer is permitted. The domain suffix
 * {@code "/instant-scheme"} keeps locally derived bytes separated from the card provider's
 * ({@code "/payment-provider"}) and every other sibling's — one key per counterparty,
 * locally too. The rail's own <em>webhook</em> key (inbound confirmations) arrives with the
 * door that reads it (`P7-TSK-009`): a {@code KeySpec} nothing reads would fail the
 * confinement test's own vacuity pin, deliberately.
 *
 * <p><strong>Lives in {@code app}, consumed at the composition root</strong>: the adapter
 * ({@code SimulatedInstantSchemeAdapter}, in {@code payments}) takes decoded key bytes
 * through its constructor and never sees this class.
 */
public final class InstantSchemeKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "instant scheme API key",
                    "instant scheme API key",
                    "FINAPP_PAYMENTS_INSTANT_KEY",
                    "/instant-scheme",
                    KeyLength.AT_LEAST_32,
                    ".");

    private InstantSchemeKey() {}

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
