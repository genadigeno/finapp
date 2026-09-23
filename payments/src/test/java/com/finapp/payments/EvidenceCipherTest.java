package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The at-rest mechanism's third restatement holds the same properties as its siblings
 * (`P5-TSK-009`; the {@code DocumentCipher} suite's shape — divergence between the
 * restatements is a finding, not an option).
 */
@DisplayName("EvidenceCipher (P5-TSK-009)")
class EvidenceCipherTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_KEY = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);

    private final EvidenceCipher cipher = new EvidenceCipher(KEY, 1, new SecureRandom());

    @Test
    @DisplayName("a payload round-trips, and the ciphertext carries GCM's arithmetic")
    void roundTrips() {
        byte[] payload = "  {\"status\" : \"approved\"}\n".getBytes(StandardCharsets.UTF_8);
        EvidenceCipher.Encrypted encrypted = cipher.encrypt(payload);
        assertThat(encrypted.ciphertext()).hasSize(payload.length + EvidenceCipher.TAG_BYTES);
        assertThat(encrypted.nonce()).hasSize(EvidenceCipher.NONCE_BYTES);
        assertThat(encrypted.keyVersion()).isEqualTo(1);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(payload);
    }

    @Test
    @DisplayName("a fresh nonce per encryption - two ciphertexts of one payload differ")
    void freshNoncePerCall() {
        byte[] payload = "same bytes".getBytes(StandardCharsets.UTF_8);
        EvidenceCipher.Encrypted first = cipher.encrypt(payload);
        EvidenceCipher.Encrypted second = cipher.encrypt(payload);
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    }

    @Test
    @DisplayName("tampering and the wrong key are one indistinguishable refusal, yielding nothing")
    void tamperingAndWrongKeyRefuse() {
        byte[] payload = "evidence".getBytes(StandardCharsets.UTF_8);
        EvidenceCipher.Encrypted encrypted = cipher.encrypt(payload);

        byte[] tampered = encrypted.ciphertext();
        tampered[0] ^= 1;
        assertThatThrownBy(
                        () ->
                                cipher.decrypt(
                                        new EvidenceCipher.Encrypted(
                                                tampered, encrypted.nonce(), 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not decrypt provider evidence");

        EvidenceCipher other = new EvidenceCipher(OTHER_KEY, 2, new SecureRandom());
        assertThatThrownBy(() -> other.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not decrypt provider evidence");
    }

    @Test
    @DisplayName("a key that is not exactly 32 bytes is refused, never stretched")
    void keyLengthIsExact() {
        assertThatThrownBy(() -> new EvidenceCipher(new byte[16], 1, new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 32 bytes");
        assertThatThrownBy(() -> new EvidenceCipher(KEY, 0, new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("nothing sensitive prints itself")
    void printsNothingSensitive() {
        EvidenceCipher.Encrypted encrypted =
                cipher.encrypt("secret payload".getBytes(StandardCharsets.UTF_8));
        assertThat(encrypted.toString()).isEqualTo("Encrypted[keyVersion=1]");
    }
}
