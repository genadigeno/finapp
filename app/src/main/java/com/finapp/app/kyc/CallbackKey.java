package com.finapp.app.kyc;

import com.finapp.app.mfa.MfaKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

/**
 * Where the provider-callback signing key comes from (`P2-TSK-011`).
 *
 * <h2>The fourth per-credential confinement — the debt row's own trigger, met</h2>
 *
 * <p>{@code MfaKey}'s shape, hand-written a fourth time: the recorded debt row names <em>"the
 * fourth credential, or Phase 5's provider adapters — whichever asks first"</em> as its trigger,
 * and this is the fourth credential. Generalising the confinement inside a callback task would
 * be a refactor of three proven guards smuggled into unrelated work ({@code EXECUTION_PROTOCOL.md}
 * rule 4, the `P2-TSK-008` precedent verbatim) — so the row is updated instead: the trigger is
 * reached, four hand-written instances exist, and the generalisation is due as its own piece of
 * work for the phase review to schedule.
 *
 * <h2>One published default literal in the whole repository</h2>
 *
 * <p>The marked local default is {@link MfaKey#MARKED_LOCAL_DEFAULT} <em>by reference</em>, so
 * the build rule that single-sources the marker keeps one subject. The locally derived key is
 * domain-separated — {@code "/callback"} appended before hashing — so no two concerns share key
 * bytes even locally, the {@code DocumentKey} reasoning.
 */
public final class CallbackKey {

    private CallbackKey() {}

    /**
     * Decodes a configured key.
     *
     * @param configured base64, decoding to at least 32 bytes, or the marked local default
     * @param localDefaultPermitted whether the marked default may be used — false anywhere the
     *     database is not on loopback
     */
    public static byte[] decode(String configured, boolean localDefaultPermitted) {
        Objects.requireNonNull(configured, "The callback signing key must be configured");

        if (MfaKey.MARKED_LOCAL_DEFAULT.equals(configured)) {
            if (!localDefaultPermitted) {
                throw new IllegalStateException(
                        "The provider-callback signing key is still the published local default,"
                            + " and this instance is not talking to a database on loopback. Set"
                            + " FINAPP_KYC_CALLBACK_KEY to a base64 key of at least 32 bytes. The"
                            + " published default is not a secret: every reader of this repository"
                            + " has it, and a callback endpoint it signs would accept anybody's"
                            + " check outcomes.");
            }
            try {
                return MessageDigest.getInstance("SHA-256")
                        .digest(
                                (MfaKey.MARKED_LOCAL_DEFAULT + "/callback")
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
            throw new IllegalStateException("The callback signing key is not valid base64");
        }
        if (configuredKey.length < 32) {
            // At least, not exactly: HMAC accepts any length, and RFC 2104 recommends a key no
            // shorter than the digest - 32 bytes for SHA-256. Unlike an AES key there is no
            // second valid-but-weaker interpretation to guard against, so longer is permitted.
            throw new IllegalStateException(
                    "The callback signing key must decode to at least 32 bytes");
        }
        return configuredKey;
    }
}
