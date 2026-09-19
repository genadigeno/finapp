package com.finapp.app.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

/**
 * The one definition of the per-credential confinement (`P5-TSK-002`, ADR-0020).
 *
 * <h2>Why this class exists, and why only now</h2>
 *
 * <p>ADR-0020 established externalised configuration with a <strong>marked local default
 * confined to loopback</strong>, and the shape was then hand-written four times as its
 * credentials arrived: {@code DatabaseCredentialGuard} (`P0-TSK-031`), {@code MfaKey}
 * (`P1-TSK-017`), {@code DocumentKey} (`P2-TSK-008`) and {@code CallbackKey} (`P2-TSK-011`).
 * Each arrival recorded, correctly, that generalising inside an unrelated task would be a
 * refactor of proven security controls smuggled into other work ({@code EXECUTION_PROTOCOL.md}
 * rule 4) — and the debt row's trigger fired at the fourth copy, because four is the count at
 * which copies drift. This task is that generalisation as its own work, before the fifth and
 * sixth credentials (the payment provider's API key, `P5-TSK-003`, and its webhook key,
 * `P5-TSK-012`) arrive: they declare a {@link KeySpec} instead of becoming classes five and six.
 *
 * <h2>What is deliberately NOT here</h2>
 *
 * <p>The loopback predicate stays {@link DatabaseEndpoint}'s — one definition of "is this
 * machine's database local", shared with the transport guard, and callers pass the answer in as
 * {@code localDefaultPermitted} exactly as they always have. Rotation and a secrets manager stay
 * Phase 15's recorded deferral; this class is the seam they will replace, not a step toward
 * implementing them.
 *
 * <h2>The equivalence obligation</h2>
 *
 * <p>The four existing guards were re-expressed over this class with their behaviour —
 * signatures, exception types, message texts, derived local key bytes — preserved
 * <strong>byte for byte</strong>, so that their existing test suites, untouched, are the
 * equivalence proof. A reworded message here is an edited test there, and both are refactor
 * failures.
 */
public final class ConfinedCredential {

    /**
     * The one sanctioned local-development fallback — now the repository's <strong>single</strong>
     * Java literal of it.
     *
     * <p>Until this task the literal lived twice in Java ({@code DatabaseCredentialGuard} and
     * {@code MfaKey}), with {@code DocumentKey} and {@code CallbackKey} referencing the second;
     * both now reference this one. The YAML, Kotlin and SQL occurrences cannot import it and are
     * held to it by {@code CommittedConfigurationHoldsNoSecretTest}, as ever.
     *
     * <p><strong>On the name</strong>: this is not a credential — it is the published marker that
     * identifies a value as deliberately not one, which is why it can be compared, committed and
     * printed ({@code DatabaseCredentialGuard}'s recorded reasoning, unchanged).
     */
    public static final String MARKED_LOCAL_DEFAULT = "local-development-only-not-a-secret";

    private ConfinedCredential() {}

    /** True exactly when the configured value is the published marker. */
    public static boolean isMarkedLocalDefault(String configured) {
        return MARKED_LOCAL_DEFAULT.equals(configured);
    }

    /** The length rule a key kind imposes, with the message and fix-phrase it implies. */
    public enum KeyLength {
        /**
         * Exactly 32 bytes — an AES-256 key. "Exactly" is load-bearing: AES accepts a 16-byte
         * key and silently gives AES-128, a cipher weaker than the caller documents
         * (`P1-TSK-017`'s {@code SecretCipher} finding).
         */
        EXACTLY_32 {
            @Override
            void check(int length, String name) {
                if (length != 32) {
                    throw new IllegalStateException(
                            "The " + name + " must decode to exactly 32 bytes, for AES-256");
                }
            }

            @Override
            String phrase() {
                return "a base64 32-byte key";
            }
        },
        /**
         * At least 32 bytes — an HMAC key. HMAC accepts any length and RFC 2104 recommends a key
         * no shorter than the digest; unlike an AES key there is no second valid-but-weaker
         * interpretation to guard against, so longer is permitted (`P2-TSK-011`'s recorded
         * difference from {@code DocumentKey}).
         */
        AT_LEAST_32 {
            @Override
            void check(int length, String name) {
                if (length < 32) {
                    throw new IllegalStateException(
                            "The " + name + " must decode to at least 32 bytes");
                }
            }

            @Override
            String phrase() {
                return "a base64 key of at least 32 bytes";
            }
        };

        abstract void check(int length, String name);

        abstract String phrase();
    }

    /**
     * One credential's declaration to the confinement.
     *
     * @param name the short name used in the configuration-mistake messages ("MFA encryption
     *     key")
     * @param confinementName the name used in the confinement refusal — usually {@code name},
     *     but a credential may present itself more fully there ({@code CallbackKey}'s
     *     "provider-callback signing key")
     * @param environmentVariable named in the refusal so the fix needs no documentation
     * @param domainSuffix appended to the marker before hashing, so no two credentials share
     *     locally derived bytes even though the marker is one published string — "one key per
     *     concern" kept true locally, where the tamper and wrong-key tests run
     *     ({@code DocumentKey}'s recorded reasoning). Empty for the first credential, whose
     *     derived bytes predate the suffix idea and must not change.
     * @param length the length rule for a supplied (non-default) key
     * @param refusalTail closes the confinement refusal — "." ordinarily; a credential may add
     *     what the published default would cost ({@code CallbackKey}'s forged-outcomes sentence)
     */
    public record KeySpec(
            String name,
            String confinementName,
            String environmentVariable,
            String domainSuffix,
            KeyLength length,
            String refusalTail) {

        public KeySpec {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(confinementName, "confinementName");
            Objects.requireNonNull(environmentVariable, "environmentVariable");
            Objects.requireNonNull(domainSuffix, "domainSuffix");
            Objects.requireNonNull(length, "length");
            Objects.requireNonNull(refusalTail, "refusalTail");
        }

        /**
         * Decodes a configured key: the marked local default — domain-separated, and only where
         * permitted — or base64 satisfying this spec's length rule.
         *
         * <p>No refusal ever echoes the configured value: it is key material, and every one of
         * these messages reaches a log line ({@code INV-AUD-02}).
         *
         * @param configured base64 to this spec's length rule, or the marked local default
         * @param localDefaultPermitted whether the marked default may be used — false anywhere
         *     the database is not on loopback ({@link DatabaseEndpoint} is the one definition of
         *     that answer; callers pass it in)
         */
        public byte[] decode(String configured, boolean localDefaultPermitted) {
            Objects.requireNonNull(configured, "The " + name + " must be configured");

            if (isMarkedLocalDefault(configured)) {
                if (!localDefaultPermitted) {
                    throw new IllegalStateException(
                            "The "
                                    + confinementName
                                    + " is still the published local default, and this instance"
                                    + " is not talking to a database on loopback. Set "
                                    + environmentVariable
                                    + " to "
                                    + length.phrase()
                                    + ". The published default is not a secret: every reader of"
                                    + " this repository has it"
                                    + refusalTail);
                }
                return sha256(MARKED_LOCAL_DEFAULT + domainSuffix);
            }

            byte[] key;
            try {
                key = Base64.getDecoder().decode(configured);
            } catch (IllegalArgumentException e) {
                // Never echoes the value: it is key material, and this message reaches a log
                // line.
                throw new IllegalStateException("The " + name + " is not valid base64");
            }
            length.check(key.length, name);
            return key;
        }
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every JVM");
        }
    }
}
