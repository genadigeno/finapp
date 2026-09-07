package com.finapp.identity;

import com.finapp.sharedkernel.security.Sensitive;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts a shared authentication secret at rest (`P1-TSK-017`, {@code INV-IDN-08}).
 *
 * <h2>Why this exists at all, when every other secret here is hashed</h2>
 *
 * <p>{@code INV-IDN-01} requires that a credential be stored in a form from which the original
 * cannot be recovered, and a password satisfies it because verification compares <em>derivations</em>.
 * A TOTP secret cannot: the server computes the expected code <strong>from the secret</strong> on
 * every challenge, so holding it is the mechanism rather than a shortcut.
 *
 * <p>So irreversibility is unavailable and <strong>confidentiality replaces it</strong>: the secret
 * is encrypted under a key that is not in the database, so a database leak alone does not yield it.
 * That is {@code INV-IDN-08}, catalogued rather than left as a comment, because a reader who found
 * a recoverable secret here would otherwise have to guess whether it was a defect.
 *
 * <h2>AES-256-GCM, and the authentication half is the point</h2>
 *
 * <p>GCM is authenticated encryption: a tampered ciphertext <strong>fails</strong> rather than
 * decrypting to a different secret. Without that, an attacker with write access to the column could
 * replace a secret with one they control and the platform would accept their codes — a second
 * factor that authenticates the attacker, silently.
 *
 * <p>A fresh random nonce per encryption, stored beside the ciphertext. <strong>Reusing a nonce
 * under one key breaks GCM completely</strong> — it is not a weakening, it leaks the authentication
 * key — so the nonce is generated per call and never derived from anything.
 *
 * <h2>The key is configuration, not a new mechanism</h2>
 *
 * <p>ADR-0020 established externalised configuration with a marked local default confined to
 * loopback, and deferred a secrets manager to Phase 15. Its recorded debt row named the trigger as
 * <em>"the second credential, which is Phase 1's authentication"</em> — this is that credential, so
 * it uses the seam that exists rather than inventing a second one beside it.
 *
 * <p>The key version is recorded on every row this produces. Without it, rotating the key means
 * guessing which rows were written under which — {@code INV-HIST-04}'s rule applied to a key, and
 * the same argument ADR-0032 made for recording derivation parameters per credential.
 */
public final class SecretCipher {

    /** AES-256. A 128-bit tag is GCM's maximum and the only size worth choosing. */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final int TAG_BITS = 128;

    /** 96 bits, which is the size GCM is specified for and the only one that avoids re-hashing. */
    public static final int NONCE_BYTES = 12;

    public static final int KEY_BYTES = 32;

    private final SecretKeySpec key;
    private final int version;
    private final SecureRandom randomness;

    /**
     * @param key exactly {@value #KEY_BYTES} bytes. Refused otherwise rather than stretched, because
     *     silently accepting a short key would produce a cipher that looks like AES-256 and is not
     * @param version recorded on every ciphertext this produces, so a rotation can tell which key
     *     wrote which row
     */
    public SecretCipher(byte[] key, int version, SecureRandom randomness) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length != KEY_BYTES) {
            // The message never states the length seen: it is a fact about the key material.
            throw new IllegalArgumentException(
                    "The MFA encryption key must be exactly " + KEY_BYTES + " bytes");
        }
        if (version < 1) {
            throw new IllegalArgumentException("key version must be positive");
        }
        this.key = new SecretKeySpec(key, "AES");
        this.version = version;
        this.randomness = Objects.requireNonNull(randomness, "randomness must not be null");
    }

    public int version() {
        return version;
    }

    /** Encrypts a secret, producing a fresh nonce that must be stored with the ciphertext. */
    public Encrypted encrypt(Sensitive<String> plaintext) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");

        byte[] nonce = new byte[NONCE_BYTES];
        randomness.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext =
                    cipher.doFinal(plaintext.expose().getBytes(StandardCharsets.UTF_8));
            return new Encrypted(ciphertext, nonce, version);
        } catch (GeneralSecurityException e) {
            // Never the cause: a provider exception can carry key material context, and this
            // message reaches a log line (INV-AUD-02). The P1-TSK-008 DatabaseFailure precedent.
            throw new IllegalStateException("Could not encrypt an MFA secret");
        }
    }

    /**
     * Decrypts a secret.
     *
     * @throws IllegalStateException if the ciphertext was tampered with, or was written under a
     *     different key. <strong>Both are the same outcome deliberately</strong>: neither may be
     *     distinguished by a caller, and neither ever yields a secret
     */
    public Sensitive<String> decrypt(Encrypted encrypted) {
        Objects.requireNonNull(encrypted, "encrypted must not be null");
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
            return Sensitive.of(
                    new String(cipher.doFinal(encrypted.ciphertext()), StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt an MFA secret");
        }
    }

    /** A ciphertext, the nonce that produced it, and the key version that wrote it. */
    public record Encrypted(byte[] ciphertext, byte[] nonce, int keyVersion) {

        public Encrypted {
            Objects.requireNonNull(ciphertext, "ciphertext must not be null");
            Objects.requireNonNull(nonce, "nonce must not be null");
            if (nonce.length != NONCE_BYTES) {
                throw new IllegalArgumentException("nonce must be " + NONCE_BYTES + " bytes");
            }
            ciphertext = ciphertext.clone();
            nonce = nonce.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }

        /**
         * Masked, and the ciphertext is masked too.
         *
         * <p>A record's generated {@code toString} prints every component, and a byte array prints
         * as an identity hash — harmless in itself, but printing the ciphertext would put the thing
         * an offline attacker needs into a log archive. Nothing here is worth rendering.
         */
        @Override
        public String toString() {
            return "Encrypted[keyVersion=" + keyVersion + ", " + com.finapp.sharedkernel.security.Sensitive.MASK + "]";
        }
    }
}
