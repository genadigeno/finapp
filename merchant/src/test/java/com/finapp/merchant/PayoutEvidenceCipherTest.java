package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payout evidence cipher (`P6-TSK-012`): AES-256-GCM with a fresh nonce per call, tampering
 * and the wrong key one indistinguishable refusal, and a key that is not 32 bytes refused rather
 * than silently becoming AES-128.
 */
@DisplayName("PayoutEvidenceCipher (P6-TSK-012)")
class PayoutEvidenceCipherTest {

    private static final byte[] PAYLOAD =
            "{\"status\":\"paid\",\"reference\":\"po_1\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("round-trips, with a fresh nonce and the GCM tag on every ciphertext")
    void roundTrips() {
        PayoutEvidenceCipher cipher = cipher((byte) 7);
        PayoutEvidenceCipher.Encrypted first = cipher.encrypt(PAYLOAD);
        PayoutEvidenceCipher.Encrypted second = cipher.encrypt(PAYLOAD);
        assertThat(cipher.decrypt(first)).isEqualTo(PAYLOAD);
        assertThat(first.ciphertext()).hasSize(PAYLOAD.length + PayoutEvidenceCipher.TAG_BYTES);
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(PAYLOAD);
    }

    @Test
    @DisplayName("tampering and the wrong key are one indistinguishable refusal, never content")
    void tamperAndWrongKeyAreOneFailure() {
        PayoutEvidenceCipher.Encrypted sealed = cipher((byte) 7).encrypt(PAYLOAD);
        byte[] flipped = sealed.ciphertext();
        flipped[0] ^= 1;
        assertThatThrownBy(
                        () ->
                                cipher((byte) 7)
                                        .decrypt(
                                                new PayoutEvidenceCipher.Encrypted(
                                                        flipped, sealed.nonce(), 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not decrypt payout evidence");
        assertThatThrownBy(() -> cipher((byte) 8).decrypt(sealed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not decrypt payout evidence");
    }

    @Test
    @DisplayName("exactly 32 bytes of key, a positive version, and a toString with neither bytes")
    void theKeyIsExactlyAes256() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PayoutEvidenceCipher(new byte[16], 1, new SecureRandom()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PayoutEvidenceCipher(new byte[32], 0, new SecureRandom()));
        assertThat(cipher((byte) 7).encrypt(PAYLOAD).toString())
                .isEqualTo("Encrypted[keyVersion=1]");
    }

    private static PayoutEvidenceCipher cipher(byte fill) {
        byte[] key = new byte[PayoutEvidenceCipher.KEY_BYTES];
        java.util.Arrays.fill(key, fill);
        return new PayoutEvidenceCipher(key, 1, new SecureRandom());
    }
}
