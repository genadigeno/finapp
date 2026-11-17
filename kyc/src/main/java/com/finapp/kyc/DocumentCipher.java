package com.finapp.kyc;

import com.finapp.platform.security.CipherFormat;
import com.finapp.platform.security.KeyRing;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts document content at rest (`P2-TSK-008`, ADR-0036, {@code INV-KYC-06}).
 *
 * <h2>{@code SecretCipher}'s mechanism, deliberately not its class</h2>
 *
 * <p>ADR-0036 reuses the {@code INV-IDN-08} mechanism — AES-256-GCM, a fresh 96-bit nonce per
 * encryption, the key version recorded on every ciphertext, tampering and the wrong key refused
 * as one indistinguishable failure. The <em>class</em> could not travel: {@code SecretCipher}
 * lives in {@code identity}, which this module may not see (module isolation, ADR-0029's rule for
 * every sibling pair), moving it to {@code platform} would refactor proven Phase 1 code and pull
 * an {@code expose()} call site out of the set {@code SecretsAreUnwrappedInOnePlaceTest} pins to
 * {@code identity} — a security-rule modification to save sixty lines — and it speaks
 * {@code Sensitive<String>} where a document is bytes. The duplication is the recorded cost;
 * divergence between the two is a finding, not an option.
 *
 * <h2>Why encryption, when the harm model differs from the MFA secret's</h2>
 *
 * <p>A leaked document is not a credential — nothing authenticates with it — but it is the most
 * sensitive PII the platform holds before card data, and a database leak must not yield a folder
 * of passports ({@code INV-KYC-06}). GCM's authentication half matters here too: evidence that an
 * attacker with write access could <em>substitute</em> would defeat {@code INV-HIST-02}'s whole
 * point, so a tampered ciphertext fails rather than decrypting to different evidence.
 *
 * <h2>One key per concern</h2>
 *
 * <p>{@code FINAPP_DOC_KEY}, never the MFA key: one key per concern is what makes later rotation
 * per concern possible (ADR-0036). The key version is recorded on every row, so a rotation can
 * tell which key wrote which — {@code INV-HIST-04}'s rule applied to a key.
 *
 * <h2>Bound to its row (`X-TSK-010`)</h2>
 *
 * <p>Every call names the row's {@code AssociatedData}. While {@code finapp.ciphers.bind-associated-data} is on, a write
 * authenticates it with the ciphertext ({@link CipherFormat#BOUND}), so a ciphertext copied into another row - another
 * document, another case, another check's evidence - fails to decrypt there; while it is off, as in the release that
 * introduced it, a write is {@link CipherFormat#UNBOUND}, which the previous release can still read during a roll. A read
 * follows the format stored with the row, never a guess, and reads both.
 */
public final class DocumentCipher {

    /** AES-256. A 128-bit tag is GCM's maximum and the only size worth choosing. */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final int TAG_BITS = 128;

    /** GCM's tag, appended to every ciphertext: why ciphertext length = plaintext length + 16. */
    public static final int TAG_BYTES = TAG_BITS / 8;

    /** 96 bits, which is the size GCM is specified for and the only one that avoids re-hashing. */
    public static final int NONCE_BYTES = 12;

    public static final int KEY_BYTES = 32;

    private final KeyRing ring;
    private final SecureRandom randomness;

    /** Whether a write binds its associated data (`X-TSK-010`): the {@code finapp.ciphers.bind-associated-data} switch. */
    private final boolean bindWrites;

    /**
     * @param key exactly {@value #KEY_BYTES} bytes. Refused otherwise rather than stretched,
     *     because silently accepting a short key would produce a cipher that looks like AES-256
     *     and is not ({@code SecretCipher}'s recorded finding: AES accepts 16 bytes and quietly
     *     gives AES-128)
     * @param version recorded on every ciphertext this produces, so a rotation can tell which key
     *     wrote which row
     */
    public DocumentCipher(byte[] key, int version, SecureRandom randomness) {
        this(ringOfOne(key, version), randomness);
    }

    /**
     * A cipher over a {@link KeyRing}: encrypts under the ring's active version, decrypts under each row's stamped version
     * (`P15-TSK-019`, ADR-0123 point 4).
     */
    public DocumentCipher(KeyRing ring, SecureRandom randomness) {
        this(ring, randomness, false);
    }

    /** A cipher over a {@link KeyRing} whose writes bind their associated data when {@code bindWrites} (`X-TSK-010`). */
    public DocumentCipher(KeyRing ring, SecureRandom randomness, boolean bindWrites) {
        this.ring = Objects.requireNonNull(ring, "ring must not be null");
        this.randomness = Objects.requireNonNull(randomness, "randomness must not be null");
        this.bindWrites = bindWrites;
    }

    private static KeyRing ringOfOne(byte[] key, int version) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length != KEY_BYTES) {
            // The message never states the length seen: it is a fact about the key material.
            throw new IllegalArgumentException("The document encryption key must be exactly " + KEY_BYTES + " bytes");
        }
        if (version < 1) {
            throw new IllegalArgumentException("key version must be positive");
        }
        return KeyRing.single("document encryption key", key, version);
    }

    public int version() {
        return ring.activeVersion();
    }

    /**
     * Encrypts document content, producing a fresh nonce that must be stored with the ciphertext.
     *
     * <p><strong>Reusing a nonce under one key breaks GCM completely</strong> — it leaks the
     * authentication key, not merely a plaintext — so the nonce is generated per call and never
     * derived from anything.
     *
     * @param associatedData the row's, bound only while writes are bound (`X-TSK-010`)
     */
    public Encrypted encrypt(byte[] associatedData, DocumentBytes plaintext) {
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
            return new Encrypted(cipher.doFinal(plaintext.value()), nonce, ring.activeVersion(), format);
        } catch (GeneralSecurityException e) {
            // Never the cause: a provider exception can carry key-material context, and this
            // message reaches a log line (INV-AUD-02). The P1-TSK-008 DatabaseFailure precedent.
            throw new IllegalStateException("Could not encrypt document content");
        }
    }

    /**
     * Decrypts document content.
     *
     * @param associatedData the row's, rebuilt from the row read; presented only when the ciphertext is {@link CipherFormat#BOUND}
     * @throws IllegalStateException if the ciphertext was tampered with, was written under a
     *     different key, or is another row's. <strong>All are the same outcome deliberately</strong>:
     *     none may be distinguished by a caller, and none ever yields content
     */
    public byte[] decrypt(byte[] associatedData, Encrypted encrypted) {
        Objects.requireNonNull(associatedData, "associatedData must not be null");
        Objects.requireNonNull(encrypted, "encrypted must not be null");
        javax.crypto.SecretKey key = ring.keyFor(encrypted.keyVersion(), "Could not decrypt document content");
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
            if (encrypted.format() == CipherFormat.BOUND) {
                cipher.updateAAD(associatedData);
            }
            return cipher.doFinal(encrypted.ciphertext());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt document content");
        }
    }

    /** A ciphertext, the nonce that produced it, the key version that wrote it, and whether it is bound to its row. */
    public record Encrypted(byte[] ciphertext, byte[] nonce, int keyVersion, CipherFormat format) {

        /** A ciphertext written without associated data: every row before `X-TSK-010`. */
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
         * The key version and the format, and nothing else. Printing the ciphertext would put the
         * thing an offline attacker needs into a log archive ({@code SecretCipher.Encrypted}'s reasoning).
         */
        @Override
        public String toString() {
            return "Encrypted[keyVersion=" + keyVersion + ", format=" + format + "]";
        }
    }
}
