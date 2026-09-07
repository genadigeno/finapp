package com.finapp.app.mfa;

import java.util.Base64;
import java.util.Objects;

/**
 * Where the MFA encryption key comes from (`P1-TSK-017`, ADR-0020, {@code INV-IDN-08}).
 *
 * <h2>The seam that already existed, rather than a second mechanism beside it</h2>
 *
 * <p>ADR-0020 established externalised configuration with a <strong>marked local default confined
 * to loopback</strong>, and deferred a secrets manager to Phase 15. Its recorded debt row said the
 * loopback guard <em>"covers one credential"</em> and named the trigger as <em>"the second
 * credential, which is Phase 1's authentication"</em>. This is that credential.
 *
 * <p>So the key is read from configuration, and the marked local default is refused anywhere but a
 * developer's machine — the same shape as {@code DatabaseCredentialGuard}, for the same reason: a
 * published default is only safe while it cannot reach anything real.
 *
 * <h2>Why a missing key must stop the application, not the first challenge</h2>
 *
 * <p>An unusable key produces a platform that starts, serves traffic, and fails at the first MFA
 * challenge — for every customer, at a moment nobody is watching, with an error that looks like a
 * cryptography bug rather than a configuration one. Refusing at startup makes it a deploy failure
 * instead, which is the cheapest place for it to be found.
 */
public final class MfaKey {

    /**
     * The published local default, and the reason it is published.
     *
     * <p>An unmarked default is worse than this one: it looks like a real key, so nobody notices it
     * is not. Named so that {@code CommittedConfigurationHoldsNoSecretTest} recognises it, and so
     * that a person reading a configuration file cannot mistake it for a secret.
     */
    public static final String MARKED_LOCAL_DEFAULT = "local-development-only-not-a-secret";

    private MfaKey() {}

    /**
     * Decodes a configured key.
     *
     * @param configured base64, decoding to exactly 32 bytes, or the marked local default
     * @param localDefaultPermitted whether the marked default may be used — false anywhere the
     *     database is not on loopback
     */
    public static byte[] decode(String configured, boolean localDefaultPermitted) {
        Objects.requireNonNull(configured, "The MFA encryption key must be configured");

        if (MARKED_LOCAL_DEFAULT.equals(configured)) {
            if (!localDefaultPermitted) {
                throw new IllegalStateException(
                        "The MFA encryption key is still the published local default, and this"
                            + " instance is not talking to a database on loopback. Set"
                            + " FINAPP_MFA_KEY to a base64 32-byte key. The published default is"
                            + " not a secret: every reader of this repository has it.");
            }
            // DERIVED from the marked string rather than being a second literal, so this
            // repository holds exactly one published default and the build rule that
            // single-sources it has one subject rather than two.
            try {
                return java.security.MessageDigest.getInstance("SHA-256")
                        .digest(MARKED_LOCAL_DEFAULT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is required by every JVM");
            }
        }

        byte[] key;
        try {
            key = Base64.getDecoder().decode(configured);
        } catch (IllegalArgumentException e) {
            // Never echoes the value: it is key material, and this message reaches a log line.
            throw new IllegalStateException("The MFA encryption key is not valid base64");
        }
        if (key.length != 32) {
            throw new IllegalStateException(
                    "The MFA encryption key must decode to exactly 32 bytes, for AES-256");
        }
        return key;
    }
}
