package com.finapp.app.kyc;

import com.finapp.app.mfa.MfaKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

/**
 * Where the document encryption key comes from (`P2-TSK-008`, ADR-0036, {@code INV-KYC-06}).
 *
 * <h2>The third per-credential confinement, and the debt row met its trigger early</h2>
 *
 * <p>{@code MfaKey}'s shape, hand-written a third time rather than generalised: the recorded debt
 * row deferred the general mechanism to "the third credential, which is Phase 5's provider
 * adapters" — and the third credential arrived here instead, one phase early. Two hand-written
 * guards were rightly not a pattern; three is the recorded trigger, met — but generalising inside
 * a document-storage task would be a refactor of two proven guards smuggled into unrelated work
 * ({@code EXECUTION_PROTOCOL.md} rule 4), so the row's premise is corrected and the
 * generalisation stays owned where the row now points.
 *
 * <h2>One published default literal in the whole repository</h2>
 *
 * <p>The marked local default is {@link MfaKey#MARKED_LOCAL_DEFAULT} <em>by reference</em>, so
 * the build rule that single-sources the marker keeps one subject. The locally derived key is
 * domain-separated from the MFA one — {@code "/doc"} appended before hashing — because even a
 * published non-secret should not quietly make two concerns share key bytes: "one key per
 * concern" (ADR-0036) is a property worth keeping true locally, where the tamper and wrong-key
 * tests run.
 */
public final class DocumentKey {

    private DocumentKey() {}

    /**
     * Decodes a configured key.
     *
     * @param configured base64, decoding to exactly 32 bytes, or the marked local default
     * @param localDefaultPermitted whether the marked default may be used — false anywhere the
     *     database is not on loopback
     */
    public static byte[] decode(String configured, boolean localDefaultPermitted) {
        Objects.requireNonNull(configured, "The document encryption key must be configured");

        if (MfaKey.MARKED_LOCAL_DEFAULT.equals(configured)) {
            if (!localDefaultPermitted) {
                throw new IllegalStateException(
                        "The document encryption key is still the published local default, and"
                            + " this instance is not talking to a database on loopback. Set"
                            + " FINAPP_DOC_KEY to a base64 32-byte key. The published default is"
                            + " not a secret: every reader of this repository has it.");
            }
            try {
                return MessageDigest.getInstance("SHA-256")
                        .digest(
                                (MfaKey.MARKED_LOCAL_DEFAULT + "/doc")
                                        .getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is required by every JVM");
            }
        }

        byte[] configuredKey;
        try {
            configuredKey = Base64.getDecoder().decode(configured);
        } catch (IllegalArgumentException e) {
            // Never echoes the value: it is key material, and this message reaches a log line.
            throw new IllegalStateException("The document encryption key is not valid base64");
        }
        if (configuredKey.length != 32) {
            throw new IllegalStateException(
                    "The document encryption key must decode to exactly 32 bytes, for AES-256");
        }
        return configuredKey;
    }
}
