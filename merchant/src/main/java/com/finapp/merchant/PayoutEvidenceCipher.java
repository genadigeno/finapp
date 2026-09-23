package com.finapp.merchant;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM for the payout provider's retained answers (`P6-TSK-012`, ADR-0057 §9) —
 * {@code payments}' {@code EvidenceCipher}, restated here because {@code merchant} has no
 * {@code payments} edge by design (its build file says so), and each module that retains
 * provider bytes keys them itself.
 *
 * <h2>Why encrypt bytes whose shape the platform chose</h2>
 *
 * <p>The simulated wire answers with a status and a reference, but the retained bytes are
 * whatever arrived — a 5xx body, garbage, an answer a future real provider enriches with the
 * account holder's details. Untrusted content the platform does not control is never plaintext
 * at rest, and bank details never enter it readable (ADR-0056 §7's ceiling, held for bytes the
 * platform did not write).
 *
 * <h2>One key per concern</h2>
 *
 * <p>{@code FINAPP_PAYOUT_EVIDENCE_KEY} (the {@code PayoutEvidenceKey} spec in {@code app}) —
 * never the payment-evidence key, never the payout provider's API key — with the key version
 * recorded on every row so a rotation can tell which key wrote which. Tampering and the wrong
 * key are one indistinguishable failure.
 */
public final class PayoutEvidenceCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final int TAG_BITS = 128;

    /** GCM's tag, appended to every ciphertext: why `V007` checks length + 16. */
    public static final int TAG_BYTES = TAG_BITS / 8;

    /** 96 bits, the size GCM is specified for — `V007`'s nonce CHECK. */
    public static final int NONCE_BYTES = 12;

    public static final int KEY_BYTES = 32;

    private final SecretKeySpec key;
    private final int version;
    private final SecureRandom randomness;

    /**
     * @param key exactly {@value #KEY_BYTES} bytes — refused otherwise rather than stretched
     * @param version recorded on every ciphertext, so a rotation can tell which key wrote which
     */
    public PayoutEvidenceCipher(byte[] key, int version, SecureRandom randomness) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length != KEY_BYTES) {
            // Never the length seen: it is a fact about the key material.
            throw new IllegalArgumentException(
                    "The payout evidence encryption key must be exactly " + KEY_BYTES + " bytes");
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

    /** Encrypts with a fresh nonce per call, derived from nothing: a reused GCM nonce is fatal. */
    public Encrypted encrypt(byte[] plaintext) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        byte[] nonce = new byte[NONCE_BYTES];
        randomness.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new Encrypted(cipher.doFinal(plaintext), nonce, version);
        } catch (GeneralSecurityException e) {
            // Never the cause: a provider exception can carry key-material context, and this
            // message reaches a log line (INV-AUD-02).
            throw new IllegalStateException("Could not encrypt payout evidence");
        }
    }

    /**
     * Decrypts retained evidence.
     *
     * @throws IllegalStateException on tampering or the wrong key — one indistinguishable
     *     failure, deliberately, and neither ever yields content
     */
    public byte[] decrypt(Encrypted encrypted) {
        Objects.requireNonNull(encrypted, "encrypted must not be null");
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
            return cipher.doFinal(encrypted.ciphertext());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt payout evidence");
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
