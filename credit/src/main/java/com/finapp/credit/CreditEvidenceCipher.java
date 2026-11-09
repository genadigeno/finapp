package com.finapp.credit;

import com.finapp.platform.security.KeyRing;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-256-GCM for credit evidence (`P10-TSK-006`; ADR-0085 point 3, ADR-0066's envelope) - settlement's
 * {@code SettlementFileCipher} restated for {@code credit}, which may not see that module, under credit's own key
 * ({@code finapp.credit.evidence.key}, never another module's): a fresh 96-bit nonce per encryption, a 128-bit tag,
 * the key version beside every ciphertext, and <strong>the evidence id's sixteen bytes as associated data</strong> -
 * a ciphertext moved onto another row refuses to decrypt rather than answering for the wrong request. Tampering, the
 * wrong key and the wrong row are one indistinguishable failure.
 */
public final class CreditEvidenceCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    /** GCM's tag, appended to every ciphertext: why {@code V004} checks length + 16. */
    public static final int TAG_BYTES = TAG_BITS / 8;

    /** 96 bits, the size GCM is specified for. */
    public static final int NONCE_BYTES = 12;

    public static final int KEY_BYTES = 32;

    private final KeyRing ring;
    private final SecureRandom randomness;

    /**
     * @param key exactly {@value #KEY_BYTES} bytes - refused otherwise rather than stretched
     * @param version recorded on every ciphertext, so a rotation can tell which key wrote which
     */
    public CreditEvidenceCipher(byte[] key, int version, SecureRandom randomness) {
        this(ringOfOne(key, version), randomness);
    }

    /**
     * A cipher over a {@link KeyRing}: encrypts under the ring's active version, decrypts under each row's stamped version
     * (`P15-TSK-019`, ADR-0123 point 4).
     */
    public CreditEvidenceCipher(KeyRing ring, SecureRandom randomness) {
        this.ring = Objects.requireNonNull(ring, "ring must not be null");
        this.randomness = Objects.requireNonNull(randomness, "randomness must not be null");
    }

    private static KeyRing ringOfOne(byte[] key, int version) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length != KEY_BYTES) {
            // The message never states the length seen: it is a fact about the key material.
            throw new IllegalArgumentException("The credit evidence key must be exactly " + KEY_BYTES + " bytes");
        }
        if (version < 1) {
            throw new IllegalArgumentException("key version must be positive");
        }
        return KeyRing.single("credit evidence key", key, version);
    }

    public int version() {
        return ring.activeVersion();
    }

    /** Encrypts {@code plaintext} for evidence row {@code id} - a fresh nonce, the id bound as associated data. */
    public Encrypted encrypt(CreditEvidenceId id, byte[] plaintext) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(plaintext, "plaintext");
        byte[] nonce = new byte[NONCE_BYTES];
        randomness.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, ring.activeKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(id));
            return new Encrypted(cipher.doFinal(plaintext), nonce, ring.activeVersion());
        } catch (GeneralSecurityException e) {
            // Never the cause: a provider exception can carry key-material context.
            throw new IllegalStateException("Could not encrypt credit evidence");
        }
    }

    /** Decrypts evidence row {@code id}'s ciphertext - refused for tampering, the wrong key and the wrong row alike. */
    public byte[] decrypt(CreditEvidenceId id, Encrypted encrypted) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(encrypted, "encrypted");
        javax.crypto.SecretKey key = ring.keyFor(encrypted.keyVersion(), "Could not decrypt credit evidence");
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
            cipher.updateAAD(associatedData(id));
            return cipher.doFinal(encrypted.ciphertext());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt credit evidence");
        }
    }

    private static byte[] associatedData(CreditEvidenceId id) {
        return ByteBuffer.allocate(16)
                .putLong(id.value().getMostSignificantBits())
                .putLong(id.value().getLeastSignificantBits())
                .array();
    }

    /** A ciphertext, the nonce that produced it, and the key version that wrote it. */
    public record Encrypted(byte[] ciphertext, byte[] nonce, int keyVersion) {

        public Encrypted {
            Objects.requireNonNull(ciphertext, "ciphertext");
            Objects.requireNonNull(nonce, "nonce");
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

        /** The key version and nothing else - a ciphertext never reaches a log line. */
        @Override
        public String toString() {
            return "Encrypted[keyVersion=" + keyVersion + "]";
        }
    }
}
