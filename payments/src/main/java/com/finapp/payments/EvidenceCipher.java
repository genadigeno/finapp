package com.finapp.payments;

import com.finapp.platform.security.CipherFormat;
import com.finapp.platform.security.KeyRing;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts provider evidence at rest (`P5-TSK-009`, `V005`, {@code INV-HIST-02}).
 *
 * <h2>{@code DocumentCipher}'s mechanism, deliberately not its class</h2>
 *
 * <p>The third restatement of the AES-256-GCM at-rest mechanism ({@code SecretCipher} in
 * {@code identity}, {@code DocumentCipher} in {@code kyc}), for {@code DocumentCipher}'s own
 * recorded reason: the class cannot travel across module isolation, hoisting it to
 * {@code platform} would refactor proven code and move pinned {@code expose()} call sites, and
 * this module's plaintext is raw bytes. The duplication is the recorded cost; divergence
 * between the three is a finding, not an option.
 *
 * <h2>Why encryption, and why GCM's authentication half matters here</h2>
 *
 * <p>Provider payloads may quote masked instrument data, names and issuer messages
 * ({@code PHASE_5_PLAN.md} §8 mandates encryption for exactly this table), and evidence an
 * attacker with write access could <em>substitute</em> would defeat {@code INV-HIST-02}'s whole
 * point — a tampered ciphertext fails rather than decrypting to different evidence, and
 * tampering and the wrong key are one indistinguishable failure.
 *
 * <h2>One key per concern</h2>
 *
 * <p>{@code FINAPP_PAYMENTS_EVIDENCE_KEY} (the {@code PaymentEvidenceKey} spec in {@code app}),
 * never the document key and never the provider API key: one key per concern is what makes
 * later rotation per concern possible, and the key version is recorded on every row so a
 * rotation can tell which key wrote which ({@code INV-HIST-04}'s rule applied to a key).
 *
 * <h2>Bound to its row (`X-TSK-010`)</h2>
 *
 * <p>Every call names the row's {@code AssociatedData}. While
 * {@code finapp.ciphers.bind-associated-data} is on, a write authenticates it with the ciphertext ({@link CipherFormat#BOUND}),
 * so a ciphertext copied into another row fails to decrypt there; while it is off, as in the release that introduced it, a
 * write is {@link CipherFormat#UNBOUND}, which the previous release can still read during a roll. A read follows the format
 * stored with the row, never a guess, and reads both.
 */
public final class EvidenceCipher {

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
    public EvidenceCipher(byte[] key, int version, SecureRandom randomness) {
        this(ringOfOne(key, version), randomness);
    }

    /**
     * A cipher over a {@link KeyRing}: encrypts under the ring's active version, decrypts under each row's stamped version
     * (`P15-TSK-019`, ADR-0123 point 4).
     */
    public EvidenceCipher(KeyRing ring, SecureRandom randomness) {
        this(ring, randomness, false);
    }

    /** A cipher over a {@link KeyRing} whose writes bind their associated data when {@code bindWrites} (`X-TSK-010`). */
    public EvidenceCipher(KeyRing ring, SecureRandom randomness, boolean bindWrites) {
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
        return KeyRing.single("evidence encryption key", key, version);
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

        /** The key version and the format and nothing else — the ciphertext never reaches a log line. */
        @Override
        public String toString() {
            return "Encrypted[keyVersion=" + keyVersion + ", format=" + format + "]";
        }
    }
}
