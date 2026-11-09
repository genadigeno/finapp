package com.finapp.fx;

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
        this.ring = Objects.requireNonNull(ring, "ring must not be null");
        this.randomness = Objects.requireNonNull(randomness, "randomness must not be null");
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
    public Encrypted encrypt(byte[] plaintext) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        byte[] nonce = new byte[NONCE_BYTES];
        randomness.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, ring.activeKey(), new GCMParameterSpec(TAG_BITS, nonce));
            return new Encrypted(cipher.doFinal(plaintext), nonce, ring.activeVersion());
        } catch (GeneralSecurityException e) {
            // Never the cause: a provider exception can carry key-material context, and this
            // message reaches a log line (INV-AUD-02).
            throw new IllegalStateException("Could not encrypt provider evidence");
        }
    }

    /**
     * Decrypts a payload.
     *
     * @throws IllegalStateException on tampering or the wrong key — one indistinguishable
     *     failure, deliberately, and neither ever yields content
     */
    public byte[] decrypt(Encrypted encrypted) {
        Objects.requireNonNull(encrypted, "encrypted must not be null");
        javax.crypto.SecretKey key = ring.keyFor(encrypted.keyVersion(), "Could not decrypt provider evidence");
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
            return cipher.doFinal(encrypted.ciphertext());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt provider evidence");
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

        /** The key version and nothing else — the ciphertext never reaches a log line. */
        @Override
        public String toString() {
            return "Encrypted[keyVersion=" + keyVersion + "]";
        }
    }
}
