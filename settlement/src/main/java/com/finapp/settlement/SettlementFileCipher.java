package com.finapp.settlement;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts settlement file chunks at rest (`P8-TSK-002`, ADR-0066 §6, {@code INV-REC-10}).
 *
 * <h2>{@code EvidenceCipher}'s mechanism, plus the question it cannot answer</h2>
 *
 * <p>The fourth restatement of the AES-256-GCM at-rest mechanism ({@code SecretCipher},
 * {@code DocumentCipher}, {@code EvidenceCipher}), duplicated across module boundaries for the
 * recorded reason; divergence between them is a finding, not an option. What this one adds is
 * <strong>associated data</strong>: GCM's tag answers <em>did this key write this
 * ciphertext</em>, and the AAD — {@code file_id ‖ source_id ‖ content_sha256 ‖ seq}, each at
 * its fixed width so the concatenation is unambiguous — answers <em>was it written for this
 * file, this source, this content and this position</em>. A chunk moved to any other file,
 * source, content or seat fails to decrypt at that chunk. (The whole-plaintext checksum,
 * verified by the store, answers the third question: <em>are these the bytes received</em>.)
 * The four existing ciphers gain their AAD under `X-TSK-008`; this one binds it from birth.
 *
 * <p>One key per concern: {@code FINAPP_SETTLEMENT_FILE_KEY} (the {@code SettlementFileKey}
 * spec in {@code app}), never the provider-evidence, dispute-evidence or document key, so each
 * rotates — and leaks — alone. The key version is recorded on every file.
 */
public final class SettlementFileCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final int TAG_BITS = 128;

    /** GCM's tag, appended to every ciphertext. */
    public static final int TAG_BYTES = TAG_BITS / 8;

    /** 96 bits, the size GCM is specified for. */
    public static final int NONCE_BYTES = 12;

    public static final int KEY_BYTES = 32;

    /** 16 + 16 + 32 + 4: two UUIDs, the SHA-256, the chunk's seat. */
    private static final int AAD_BYTES = 68;

    private final SecretKeySpec key;
    private final int version;
    private final SecureRandom randomness;

    /**
     * @param key exactly {@value #KEY_BYTES} bytes — refused otherwise rather than stretched
     *     (AES accepts 16 bytes and quietly gives AES-128, the recorded finding)
     * @param version recorded on every file, so a rotation can tell which key wrote which
     */
    public SettlementFileCipher(byte[] key, int version, SecureRandom randomness) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length != KEY_BYTES) {
            // Never the length seen: it is a fact about the key material.
            throw new IllegalArgumentException(
                    "The settlement file key must be exactly " + KEY_BYTES + " bytes");
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

    /** The chunk's binding, encoded at fixed widths. */
    public static byte[] associatedData(UUID fileId, UUID sourceId, byte[] contentSha256, int seq) {
        Objects.requireNonNull(fileId, "fileId must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(contentSha256, "contentSha256 must not be null");
        if (contentSha256.length != 32) {
            throw new IllegalArgumentException("a content address is SHA-256: 32 bytes");
        }
        if (seq < 0) {
            throw new IllegalArgumentException("a chunk's seat is never negative");
        }
        return ByteBuffer.allocate(AAD_BYTES)
                .putLong(fileId.getMostSignificantBits())
                .putLong(fileId.getLeastSignificantBits())
                .putLong(sourceId.getMostSignificantBits())
                .putLong(sourceId.getLeastSignificantBits())
                .put(contentSha256)
                .putInt(seq)
                .array();
    }

    /** Encrypts one chunk under a fresh nonce, bound to its seat by {@code associatedData}. */
    public Encrypted encrypt(byte[] plaintext, byte[] associatedData) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        Objects.requireNonNull(associatedData, "associatedData must not be null");
        byte[] nonce = new byte[NONCE_BYTES];
        randomness.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData);
            return new Encrypted(cipher.doFinal(plaintext), nonce);
        } catch (GeneralSecurityException e) {
            // Never the cause: its message can carry key-material context, and this message
            // reaches a log line (INV-AUD-02).
            throw new IllegalStateException("Could not encrypt a settlement file chunk");
        }
    }

    /**
     * Decrypts one chunk.
     *
     * @throws IllegalStateException on tampering, a transplanted chunk or the wrong key — one
     *     indistinguishable failure, deliberately, and none ever yields content
     */
    public byte[] decrypt(Encrypted encrypted, byte[] associatedData) {
        Objects.requireNonNull(encrypted, "encrypted must not be null");
        Objects.requireNonNull(associatedData, "associatedData must not be null");
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
            cipher.updateAAD(associatedData);
            return cipher.doFinal(encrypted.ciphertext());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt a settlement file chunk");
        }
    }

    /** A ciphertext and the nonce that produced it. */
    public record Encrypted(byte[] ciphertext, byte[] nonce) {

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

        /** Nothing — a ciphertext never reaches a log line. */
        @Override
        public String toString() {
            return "Encrypted[settlement file chunk]";
        }
    }
}
