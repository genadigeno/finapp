package com.finapp.merchant;

import com.finapp.platform.security.CipherFormat;
import com.finapp.platform.security.KeyRing;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

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
 * <p>{@code FINAPP_MERCHANT_PAYOUT_EVIDENCE_KEY} (the {@code PayoutEvidenceKey} spec in {@code app}) —
 * never the payment-evidence key, never the payout provider's API key — with the key version
 * recorded on every row so a rotation can tell which key wrote which. Tampering and the wrong
 * key are one indistinguishable failure.
 *
 * <h2>Bound to its row</h2>
 *
 * <p>`X-TSK-010`. Every call names the row's {@code AssociatedData}. While
 * {@code finapp.ciphers.bind-associated-data} is on, a write authenticates it with the ciphertext ({@link CipherFormat#BOUND}),
 * so a ciphertext copied into another row fails to decrypt there; while it is off, as in the release that introduced it, a
 * write is {@link CipherFormat#UNBOUND}, which the previous release can still read during a roll. A read follows the format
 * stored with the row, never a guess, and reads both.
 */
public final class PayoutEvidenceCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final int TAG_BITS = 128;

    /** GCM's tag, appended to every ciphertext: why `V007` checks length + 16. */
    public static final int TAG_BYTES = TAG_BITS / 8;

    /** 96 bits, the size GCM is specified for — `V007`'s nonce CHECK. */
    public static final int NONCE_BYTES = 12;

    public static final int KEY_BYTES = 32;

    private final KeyRing ring;
    private final SecureRandom randomness;

    /** Whether a write binds its associated data (`X-TSK-010`): the {@code finapp.ciphers.bind-associated-data} switch. */
    private final boolean bindWrites;

    /**
     * @param key exactly {@value #KEY_BYTES} bytes — refused otherwise rather than stretched
     * @param version recorded on every ciphertext, so a rotation can tell which key wrote which
     */
    public PayoutEvidenceCipher(byte[] key, int version, SecureRandom randomness) {
        this(ringOfOne(key, version), randomness);
    }

    /**
     * A cipher over a {@link KeyRing}: encrypts under the ring's active version, decrypts under each row's stamped version
     * (`P15-TSK-019`, ADR-0123 point 4).
     */
    public PayoutEvidenceCipher(KeyRing ring, SecureRandom randomness) {
        this(ring, randomness, false);
    }

    /** A cipher over a {@link KeyRing} whose writes bind their associated data when {@code bindWrites} (`X-TSK-010`). */
    public PayoutEvidenceCipher(KeyRing ring, SecureRandom randomness, boolean bindWrites) {
        this.ring = Objects.requireNonNull(ring, "ring must not be null");
        this.randomness = Objects.requireNonNull(randomness, "randomness must not be null");
        this.bindWrites = bindWrites;
    }

    private static KeyRing ringOfOne(byte[] key, int version) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length != KEY_BYTES) {
            // The message never states the length seen: it is a fact about the key material.
            throw new IllegalArgumentException("The payout evidence encryption key must be exactly " + KEY_BYTES + " bytes");
        }
        if (version < 1) {
            throw new IllegalArgumentException("key version must be positive");
        }
        return KeyRing.single("payout evidence encryption key", key, version);
    }

    public int version() {
        return ring.activeVersion();
    }

    /**
     * Encrypts with a fresh nonce per call, derived from nothing: a reused GCM nonce is fatal.
     *
     * @param associatedData the row's; bound with the ciphertext only while writes are bound ({@link CipherFormat#BOUND})
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
            throw new IllegalStateException("Could not encrypt payout evidence");
        }
    }

    /**
     * Decrypts retained evidence.
     *
     * @param associatedData the row's, rebuilt from the row read; presented only when the ciphertext is {@link CipherFormat#BOUND}
     * @throws IllegalStateException on tampering, the wrong key or another row's ciphertext — one indistinguishable
     *     failure, deliberately, and none ever yields content
     */
    public byte[] decrypt(byte[] associatedData, Encrypted encrypted) {
        Objects.requireNonNull(associatedData, "associatedData must not be null");
        Objects.requireNonNull(encrypted, "encrypted must not be null");
        javax.crypto.SecretKey key = ring.keyFor(encrypted.keyVersion(), "Could not decrypt payout evidence");
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
            if (encrypted.format() == CipherFormat.BOUND) {
                cipher.updateAAD(associatedData);
            }
            return cipher.doFinal(encrypted.ciphertext());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt payout evidence");
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
