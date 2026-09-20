package com.finapp.app.payments;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * Where the payment provider's API credential comes from (`P5-TSK-003`).
 *
 * <h2>The fifth credential — the first to arrive as a declaration</h2>
 *
 * <p>`P5-TSK-002` generalised the four hand-written copies of the ADR-0020 confinement into
 * {@code ConfinedCredential} precisely so that this credential and the webhook key
 * (`P5-TSK-012`) would arrive as one-line {@link KeySpec}s rather than as classes five and six.
 * This is that arrival: the whole discipline — marked-local-default recognition, loopback
 * confinement, domain separation, both length rules, the non-echoing refusals — is inherited,
 * and {@code ProviderApiKeyTest} asserts the parts that are <em>this</em> spec's (the
 * `P2-TSK-011` lesson: a credential class with no test is a confinement nobody would notice
 * removed).
 *
 * <p><strong>{@code AT_LEAST_32}</strong>, the {@code CallbackKey} argument: a bearer API
 * secret has no valid-but-weaker interpretation the way an AES key does, so longer is
 * permitted. The domain suffix {@code "/payment-provider"} keeps the locally derived bytes
 * separated from every sibling's — the webhook key's coming suffix is
 * {@code "/payment-webhook"}, distinct on purpose: one key per concern, locally too.
 *
 * <p><strong>Lives in {@code app}, consumed at the composition root.</strong> The adapter
 * ({@code SimulatedCardPspAdapter}, in {@code payments}) cannot see this class and takes
 * decoded key bytes through its constructor — the {@code DocumentKey}/{@code DocumentCipher}
 * split. The consuming bean is `P5-TSK-009`'s (the unconsumed-wiring licence), at which point
 * the startup-guard test gains the fifth credential (the standing `P1-TSK-017` precedent).
 */
public final class ProviderApiKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "payment provider API key",
                    "payment provider API key",
                    "FINAPP_PAYMENT_PROVIDER_KEY",
                    "/payment-provider",
                    KeyLength.AT_LEAST_32,
                    ".");

    private ProviderApiKey() {}

    /**
     * Decodes a configured key.
     *
     * @param configured base64, decoding to at least 32 bytes, or the marked local default
     * @param localDefaultPermitted whether the marked default may be used — false anywhere the
     *     database is not on loopback
     */
    public static byte[] decode(String configured, boolean localDefaultPermitted) {
        return SPEC.decode(configured, localDefaultPermitted);
    }
}
