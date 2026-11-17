package com.finapp.identity;

import com.finapp.platform.security.CipherFormat;
import com.finapp.platform.security.KeyRing;
import com.finapp.sharedkernel.security.Sensitive;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

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
 *
 * <h2>Bound to its enrolment (`X-TSK-010`)</h2>
 *
 * <p>Every call names the enrolment's {@code AssociatedData}. While
 * {@code finapp.ciphers.bind-associated-data} is on, a write authenticates it with the ciphertext
 * ({@link CipherFormat#BOUND}), so a ciphertext copied into another enrolment's row - an attacker's
 * own secret over a victim's - fails to decrypt there instead of confirming the attacker's codes; while
 * it is off, as in the release that introduced it, a write is {@link CipherFormat#UNBOUND}, which the
 * previous release can still read during a roll. A read follows the format stored with the row, never a
 * guess, and reads both.
 */
public final class SecretCipher {

    /** AES-256. A 128-bit tag is GCM's maximum and the only size worth choosing. */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final int TAG_BITS = 128;

    /** 96 bits, which is the size GCM is specified for and the only one that avoids re-hashing. */
    public static final int NONCE_BYTES = 12;

    public static final int KEY_BYTES = 32;

    private final KeyRing ring;
    private final SecureRandom randomness;

    /** Whether a write binds its associated data (`X-TSK-010`): the {@code finapp.ciphers.bind-associated-data} switch. */
    private final boolean bindWrites;

    /**
     * @param key exactly {@value #KEY_BYTES} bytes. Refused otherwise rather than stretched, because
     *     silently accepting a short key would produce a cipher that looks like AES-256 and is not
     * @param version recorded on every ciphertext this produces, so a rotation can tell which key
     *     wrote which row
     */
    public SecretCipher(byte[] key, int version, SecureRandom randomness) {
        this(ringOfOne(key, version), randomness);
    }

    /**
     * A cipher over a {@link KeyRing}: encrypts under the ring's active version, decrypts under each row's stamped version
     * (`P15-TSK-019`, ADR-0123 point 4).
     */
    public SecretCipher(KeyRing ring, SecureRandom randomness) {
        this(ring, randomness, false);
    }

    /** A cipher over a {@link KeyRing} whose writes bind their associated data when {@code bindWrites} (`X-TSK-010`). */
    public SecretCipher(KeyRing ring, SecureRandom randomness, boolean bindWrites) {
        this.ring = Objects.requireNonNull(ring, "ring must not be null");
        this.randomness = Objects.requireNonNull(randomness, "randomness must not be null");
        this.bindWrites = bindWrites;
    }

    private static KeyRing ringOfOne(byte[] key, int version) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length != KEY_BYTES) {
            // The message never states the length seen: it is a fact about the key material.
            throw new IllegalArgumentException("The MFA encryption key must be exactly " + KEY_BYTES + " bytes");
        }
        if (version < 1) {
            throw new IllegalArgumentException("key version must be positive");
        }
        return KeyRing.single("MFA encryption key", key, version);
    }

    public int version() {
        return ring.activeVersion();
    }

    /**
     * Encrypts a secret, producing a fresh nonce that must be stored with the ciphertext.
     *
     * @param associatedData the enrolment's, bound into the tag only while writes are bound (`X-TSK-010`)
     */
    public Encrypted encrypt(byte[] associatedData, Sensitive<String> plaintext) {
        Objects.requireNonNull(associatedData, "associatedData must not be null");
        Objects.requireNonNull(plaintext, "plaintext must not be null");

        byte[] nonce = new byte[NONCE_BYTES];
        randomness.nextBytes(nonce);
        CipherFormat format = bindWrites ? CipherFormat.BOUND : CipherFormat.UNBOUND;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, ring.activeKey(), new GCMParameterSpec(TAG_BITS, nonce));
            if (format == CipherFormat.BOUND) {
                cipher.updateAAD(associatedData);
            }
            byte[] ciphertext =
                    cipher.doFinal(plaintext.expose().getBytes(StandardCharsets.UTF_8));
            return new Encrypted(ciphertext, nonce, ring.activeVersion(), format);
        } catch (GeneralSecurityException e) {
            // Never the cause: a provider exception can carry key material context, and this
            // message reaches a log line (INV-AUD-02). The P1-TSK-008 DatabaseFailure precedent.
            throw new IllegalStateException("Could not encrypt an MFA secret");
        }
    }

    /**
     * Decrypts a secret.
     *
     * @param associatedData the enrolment's, rebuilt from the row read; presented only when the
     *     ciphertext is {@link CipherFormat#BOUND}
     * @throws IllegalStateException if the ciphertext was tampered with, was written under a
     *     different key, or is another enrolment's. <strong>All are the same outcome
     *     deliberately</strong>: none may be distinguished by a caller, and none ever yields a secret
     */
    public Sensitive<String> decrypt(byte[] associatedData, Encrypted encrypted) {
        Objects.requireNonNull(associatedData, "associatedData must not be null");
        Objects.requireNonNull(encrypted, "encrypted must not be null");
        javax.crypto.SecretKey key = ring.keyFor(encrypted.keyVersion(), "Could not decrypt an MFA secret");
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
            if (encrypted.format() == CipherFormat.BOUND) {
                cipher.updateAAD(associatedData);
            }
            return Sensitive.of(
                    new String(cipher.doFinal(encrypted.ciphertext()), StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt an MFA secret");
        }
    }

    /**
     * A ciphertext, the nonce that produced it, the key version that wrote it, and whether it is
     * bound to its enrolment.
     */
    public record Encrypted(byte[] ciphertext, byte[] nonce, int keyVersion, CipherFormat format) {

        /** A ciphertext written without associated data: every enrolment before `X-TSK-010`. */
        public Encrypted(byte[] ciphertext, byte[] nonce, int keyVersion) {
            this(ciphertext, nonce, keyVersion, CipherFormat.UNBOUND);
        }

        public Encrypted {
            Objects.requireNonNull(ciphertext, "ciphertext must not be null");
            Objects.requireNonNull(nonce, "nonce must not be null");
            Objects.requireNonNull(format, "format must not be null");
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
            return "Encrypted[keyVersion=" + keyVersion + ", format=" + format + ", "
                    + com.finapp.sharedkernel.security.Sensitive.MASK + "]";
        }
    }
}
