package com.finapp.app.kyc;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * Where the document encryption key comes from (`P2-TSK-008`, ADR-0036, {@code INV-KYC-06}).
 *
 * <h2>The third per-credential confinement — now a declaration, not a copy</h2>
 *
 * <p>This was {@code MfaKey}'s shape hand-written a third time, and its own javadoc recorded that
 * three copies is the trigger for the generalisation while refusing to smuggle that refactor into
 * a document-storage task ({@code EXECUTION_PROTOCOL.md} rule 4). `P5-TSK-002` is that
 * generalisation as its own work: this class is now a {@link KeySpec} declaration over
 * {@code ConfinedCredential}, with its behaviour — signature, exception types, message texts,
 * derived local bytes — preserved byte for byte. {@code DocumentKeyTest}, untouched, is the
 * equivalence proof.
 *
 * <h2>Domain separation</h2>
 *
 * <p>The locally derived key is domain-separated from the MFA one — {@code "/doc"} appended
 * before hashing — because even a published non-secret should not quietly make two concerns share
 * key bytes: "one key per concern" (ADR-0036) is a property worth keeping true locally, where the
 * tamper and wrong-key tests run.
 */
public final class DocumentKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "document encryption key",
                    "document encryption key",
                    "FINAPP_DOC_KEY",
                    "/doc",
                    KeyLength.EXACTLY_32,
                    ".");

    private DocumentKey() {}

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
}
