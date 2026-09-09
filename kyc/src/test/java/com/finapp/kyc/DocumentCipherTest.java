package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The document cipher's refusals (`P2-TSK-008`, {@code INV-KYC-06}).
 *
 * <p>The {@code SecretCipherTest} shape, because this class is that mechanism re-stated over
 * bytes: each of these tests exists because its absence over there let a mutation survive
 * (`P1-TSK-017` — a class with no test is a class whose loopback confinement, key-length check
 * and tamper refusal can all vanish silently).
 */
@DisplayName("DocumentCipher (P2-TSK-008)")
class DocumentCipherTest {

    private static final byte[] KEY = key((byte) 7);
    private static final byte[] OTHER_KEY = key((byte) 8);

    private final DocumentCipher cipher = new DocumentCipher(KEY, 1, new SecureRandom());

    @Test
    @DisplayName("content round-trips, and the ciphertext is not the plaintext")
    void roundTrips() {
        DocumentBytes content = DocumentBytes.of(bytes("a passport photograph"));

        DocumentCipher.Encrypted encrypted = cipher.encrypt(content);

        assertThat(encrypted.ciphertext()).isNotEqualTo(content.value());
        assertThat(cipher.decrypt(encrypted)).isEqualTo(content.value());
        assertThat(encrypted.keyVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("a tampered ciphertext is refused, never decrypted to something else")
    void tamperIsRefused() {
        DocumentCipher.Encrypted encrypted =
                cipher.encrypt(DocumentBytes.of(bytes("evidence to protect")));
        byte[] tampered = encrypted.ciphertext();
        tampered[0] ^= 0x01;
        DocumentCipher.Encrypted substituted =
                new DocumentCipher.Encrypted(tampered, encrypted.nonce(), encrypted.keyVersion());

        // GCM's authentication half is the point: evidence an attacker with write access could
        // SUBSTITUTE would defeat INV-HIST-02 entirely.
        assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(substituted));
    }

    @Test
    @DisplayName("the wrong key cannot read, and is indistinguishable from tampering")
    void wrongKeyIsRefused() {
        DocumentCipher.Encrypted encrypted =
                cipher.encrypt(DocumentBytes.of(bytes("written under key 7")));
        DocumentCipher other = new DocumentCipher(OTHER_KEY, 2, new SecureRandom());

        assertThatIllegalStateException().isThrownBy(() -> other.decrypt(encrypted));
    }

    @Test
    @DisplayName("a short key is refused rather than silently producing AES-128")
    void shortKeyIsRefused() {
        // AES accepts a 16-byte key and quietly gives AES-128 - a cipher that looks like AES-256
        // and is not (SecretCipher's recorded finding).
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DocumentCipher(new byte[16], 1, new SecureRandom()));
    }

    @Test
    @DisplayName("every encryption gets a fresh nonce")
    void noncesAreFresh() {
        // Reusing a nonce under one key breaks GCM completely - it leaks the authentication key.
        // Two encryptions of ONE plaintext must therefore differ in nonce and ciphertext alike;
        // this is the assertion a fixed-nonce mutation cannot survive.
        DocumentBytes content = DocumentBytes.of(bytes("the same bytes twice"));

        DocumentCipher.Encrypted first = cipher.encrypt(content);
        DocumentCipher.Encrypted second = cipher.encrypt(content);

        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    }

    @Test
    @DisplayName("a wrongly-sized nonce cannot be constructed, and toString renders no ciphertext")
    void encryptedValidatesItself() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DocumentCipher.Encrypted(bytes("ct"), new byte[11], 1));

        DocumentCipher.Encrypted encrypted =
                cipher.encrypt(DocumentBytes.of(bytes("never in a log")));
        assertThat(encrypted.toString()).doesNotContain(java.util.Arrays.toString(encrypted.ciphertext()));
        assertThat(encrypted.toString()).isEqualTo("Encrypted[keyVersion=1]");
    }

    private static byte[] key(byte fill) {
        byte[] key = new byte[DocumentCipher.KEY_BYTES];
        java.util.Arrays.fill(key, fill);
        return key;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
