package com.finapp.kyc;

import com.finapp.platform.security.KeyRing;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts a counterparty's name (`P9-TSK-016`, ADR-0081 point 1) - RESTRICTED-PII, held only by kyc:
 * AES-256-GCM under kyc's evidence key, with the <strong>screening id as associated data</strong>, so
 * a ciphertext copied onto another screening's row fails to decrypt instead of naming the wrong
 * counterparty ({@code SettlementFileCipher}'s binding, applied to a row). Tampering, the wrong key and
 * the wrong row are one indistinguishable failure; the key version travels with every ciphertext.
 */
public final class CounterpartySubjectCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;
    public static final int NONCE_BYTES = 12;
    public static final int KEY_BYTES = 32;

    private final KeyRing ring;
    private final SecureRandom randomness;

    public CounterpartySubjectCipher(byte[] key, int version, SecureRandom randomness) {
        this(ringOfOne(key, version), randomness);
    }

    /**
     * A cipher over a {@link KeyRing}: encrypts under the ring's active version, decrypts under each row's stamped version
     * (`P15-TSK-019`, ADR-0123 point 4).
     */
    public CounterpartySubjectCipher(KeyRing ring, SecureRandom randomness) {
        this.ring = Objects.requireNonNull(ring, "ring must not be null");
        this.randomness = Objects.requireNonNull(randomness, "randomness must not be null");
    }

    private static KeyRing ringOfOne(byte[] key, int version) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length != KEY_BYTES) {
            // The message never states the length seen: it is a fact about the key material.
            throw new IllegalArgumentException("the subject encryption key must be exactly " + KEY_BYTES + " bytes");
        }
        if (version < 1) {
            throw new IllegalArgumentException("a key version is positive");
        }
        return KeyRing.single("subject encryption key", key, version);
    }

    /** The ciphertext, its nonce and the key version that made it. */
    public record Encrypted(byte[] ciphertext, byte[] nonce, int keyVersion) {
        public Encrypted {
            Objects.requireNonNull(ciphertext, "ciphertext must not be null");
            Objects.requireNonNull(nonce, "nonce must not be null");
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

        @Override
        public boolean equals(Object other) {
            return other instanceof Encrypted that && keyVersion == that.keyVersion
                    && Arrays.equals(ciphertext, that.ciphertext) && Arrays.equals(nonce, that.nonce);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(ciphertext);
        }

        @Override
        public String toString() {
            return "Encrypted[" + ciphertext.length + " bytes, v" + keyVersion + "]";
        }
    }

    /** Encrypts {@code name} bound to {@code screening}. */
    public Encrypted encrypt(CounterpartyScreeningId screening, String name) {
        Objects.requireNonNull(name, "name must not be null");
        return seal(screening, name.getBytes(StandardCharsets.UTF_8));
    }

    /** Decrypts a name bound to {@code screening}; any mismatch is one refusal. */
    public String decrypt(CounterpartyScreeningId screening, Encrypted encrypted) {
        return new String(open(screening, encrypted), StandardCharsets.UTF_8);
    }

    /** Encrypts provider evidence bound to {@code screening} - the same binding as the name. */
    public Encrypted seal(CounterpartyScreeningId screening, byte[] plaintext) {
        Objects.requireNonNull(screening, "screening must not be null");
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        byte[] nonce = new byte[NONCE_BYTES];
        randomness.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, ring.activeKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(screening.value()));
            return new Encrypted(cipher.doFinal(plaintext), nonce, ring.activeVersion());
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("could not encrypt counterparty screening content");
        }
    }

    /** Decrypts content bound to {@code screening}; tampering, the wrong key and the wrong row are one refusal. */
    public byte[] open(CounterpartyScreeningId screening, Encrypted encrypted) {
        Objects.requireNonNull(screening, "screening must not be null");
        Objects.requireNonNull(encrypted, "encrypted must not be null");
        javax.crypto.SecretKey key = ring.keyFor(encrypted.keyVersion(), "could not decrypt counterparty screening content");
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
            cipher.updateAAD(associatedData(screening.value()));
            return cipher.doFinal(encrypted.ciphertext());
        } catch (GeneralSecurityException refused) {
            throw new IllegalStateException("could not decrypt counterparty screening content");
        }
    }

    /** The screening id's sixteen bytes - fixed width, so no two ids share associated data. */
    static byte[] associatedData(UUID screening) {
        return ByteBuffer.allocate(16).putLong(screening.getMostSignificantBits())
                .putLong(screening.getLeastSignificantBits()).array();
    }
}
