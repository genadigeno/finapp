package com.finapp.app.kyc;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * Where the provider-callback signing key comes from (`P2-TSK-011`).
 *
 * <h2>The fourth per-credential confinement — the copy that fired the trigger, now a
 * declaration</h2>
 *
 * <p>This was {@code MfaKey}'s shape hand-written a fourth time, and its own javadoc recorded
 * that the debt row's trigger was thereby reached and the generalisation due as its own piece of
 * work. `P5-TSK-002` is that work: this class is now a {@link KeySpec} declaration over
 * {@code ConfinedCredential}, with its behaviour — signature, exception types, message texts,
 * derived local bytes — preserved byte for byte. {@code CallbackKeyTest}, untouched, is the
 * equivalence proof.
 *
 * <h2>What its spec says that the others' do not</h2>
 *
 * <p>Domain separation {@code "/callback"}; a length rule of <strong>at least</strong> 32 bytes
 * rather than exactly (HMAC accepts any length and RFC 2104 recommends no shorter than the
 * digest; unlike an AES key there is no valid-but-weaker interpretation to guard against); and a
 * refusal tail stating what the published default would cost — a callback endpoint it signs
 * would accept anybody's check outcomes.
 */
public final class CallbackKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "callback signing key",
                    "provider-callback signing key",
                    "FINAPP_KYC_CALLBACK_KEY",
                    "/callback",
                    KeyLength.AT_LEAST_32,
                    ", and a callback endpoint it signs would accept anybody's check outcomes.");

    private CallbackKey() {}

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
