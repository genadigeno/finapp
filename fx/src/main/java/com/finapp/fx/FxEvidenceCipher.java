package com.finapp.fx;

import com.finapp.platform.security.CipherFormat;
import com.finapp.platform.security.KeyRing;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-256-GCM for FX provider evidence (`P9-TSK-006`, {@code INV-HIST-02}) - {@code payments}'
 * {@code EvidenceCipher} restated for {@code fx}, which may not see that module (the
 * {@code merchant} {@code PayoutEvidenceCipher} precedent): a fresh 96-bit nonce per encryption,
 * a 128-bit tag, the key version recorded beside every ciphertext, and one indistinguishable
 * failure for tampering and the wrong key. The key is {@code FINAPP_FX_EVIDENCE_KEY}, its own.
 *
 * <p><strong>Bound to its row</strong> (`X-TSK-010`). Every call names the row's {@code AssociatedData}. While
 * {@code finapp.ciphers.bind-associated-data} is on, a write authenticates it with the ciphertext ({@link CipherFormat#BOUND}),
 * so a ciphertext copied into another row fails to decrypt there; while it is off, as in the release that introduced it, a
 * write is {@link CipherFormat#UNBOUND}, which the previous release can still read during a roll. A read follows the format
 * stored with the row, never a guess, and reads both.
 */
public final class FxEvidenceCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final int TAG_BITS = 128;

    /** GCM's tag, appended to every ciphertext: why {@code V005} checks length + 16. */
    public static final int TAG_BYTES = TAG_BITS / 8;

    /** 96 bits, the size GCM is specified for — {@code V005}'s nonce CHECK. */
    public static final int NONCE_BYTES = 12;

    public static final int KEY_BYTES = 32;

    private final KeyRing ring;
    private final SecureRandom randomness;

    /** Whether a write binds its associated data (`X-TSK-010`): the {@code finapp.ciphers.bind-associated-data} switch. */
    private final boolean bindWrites;

    /**
     * @param key exactly {@value #KEY_BYTES} bytes — refused otherwise rather than stretched
     *     (AES accepts 16 bytes and quietly gives AES-128, the recorded finding)
     * @param version recorded on every ciphertext, so a rotation can tell which key wrote which
     */
    public FxEvidenceCipher(byte[] key, int version, SecureRandom randomness) {
        this(ringOfOne(key, version), randomness);
    }

    /**
     * A cipher over a {@link KeyRing}: encrypts under the ring's active version, decrypts under each row's stamped version
     * (`P15-TSK-019`, ADR-0123 point 4).
     */
    public FxEvidenceCipher(KeyRing ring, SecureRandom randomness) {
        this(ring, randomness, false);
    }

    /** A cipher over a {@link KeyRing} whose writes bind their associated data when {@code bindWrites} (`X-TSK-010`). */
    public FxEvidenceCipher(KeyRing ring, SecureRandom randomness, boolean bindWrites) {
        this.ring = Objects.requireNonNull(ring, "ring must not be null");
        this.randomness = Objects.requireNonNull(randomness, "randomness must not be null");
        this.bindWrites = bindWrites;
    }

    private static KeyRing ringOfOne(byte[] key, int version) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length != KEY_BYTES) {
            // The message never states the length seen: it is a fact about the key material.
            throw new IllegalArgumentException("The evidence encryption key must be exactly " + KEY_BYTES + " bytes");
        }
        if (version < 1) {
            throw new IllegalArgumentException("key version must be positive");
        }
        return KeyRing.single("FX evidence encryption key", key, version);
    }

    public int version() {
        return ring.activeVersion();
    }

    /**
     * Encrypts a payload with a fresh nonce — generated per call and derived from nothing,
     * because reusing a nonce under one key breaks GCM completely.
     */
    public Encrypted encrypt(byte[] associatedData, byte[] plaintext) {
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
            return new Encrypted(cipher.doFinal(plaintext), nonce, ring.activeVersion(), format);
        } catch (GeneralSecurityException e) {
            // Never the cause: a provider exception can carry key-material context, and this
            // message reaches a log line (INV-AUD-02).
            throw new IllegalStateException("Could not encrypt provider evidence");
        }
    }

    /**
     * Decrypts a payload.
     *
     * @param associatedData the row's, rebuilt from the row read; presented only when the ciphertext is {@link CipherFormat#BOUND}
     * @throws IllegalStateException on tampering, the wrong key or another row's ciphertext — one indistinguishable
     *     failure, deliberately, and none ever yields content
     */
    public byte[] decrypt(byte[] associatedData, Encrypted encrypted) {
        Objects.requireNonNull(associatedData, "associatedData must not be null");
        Objects.requireNonNull(encrypted, "encrypted must not be null");
        javax.crypto.SecretKey key = ring.keyFor(encrypted.keyVersion(), "Could not decrypt provider evidence");
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
            if (encrypted.format() == CipherFormat.BOUND) {
                cipher.updateAAD(associatedData);
            }
            return cipher.doFinal(encrypted.ciphertext());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt provider evidence");
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

        /** The key version and nothing else — the ciphertext never reaches a log line. */
        @Override
        public String toString() {
            return "Encrypted[keyVersion=" + keyVersion + ", format=" + format + "]";
        }
    }
}
