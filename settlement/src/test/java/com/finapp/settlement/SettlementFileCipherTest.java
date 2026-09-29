package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The chunk cipher (`P8-TSK-002`, ADR-0066 §6): GCM answers "did this key write this
 * ciphertext"; the associated data answers "was it written for this file, this source, this
 * content and this position" — and every wrong answer is one indistinguishable, content-free
 * failure.
 */
@DisplayName("the settlement file cipher (P8-TSK-002)")
class SettlementFileCipherTest {

    private static final byte[] KEY = new byte[32];

    private static final UUID FILE = UUID.fromString("01a0e2bc-8200-7101-8000-000000000001");
    private static final UUID SOURCE = UUID.fromString("01a0e2bc-8200-7001-8000-000000000001");
    private static final byte[] SHA = new byte[32];

    private final SettlementFileCipher cipher =
            new SettlementFileCipher(KEY, 1, new SecureRandom());

    @Test
    @DisplayName("bytes round-trip under their own binding")
    void roundTrips() {
        byte[] plaintext = "statement line".getBytes(StandardCharsets.UTF_8);
        byte[] aad = SettlementFileCipher.associatedData(FILE, SOURCE, SHA, 0);
        SettlementFileCipher.Encrypted encrypted = cipher.encrypt(plaintext, aad);
        assertThat(cipher.decrypt(encrypted, aad)).isEqualTo(plaintext);
        assertThat(encrypted.ciphertext())
                .as("GCM appends its 16-byte tag")
                .hasSize(plaintext.length + SettlementFileCipher.TAG_BYTES);
    }

    @Test
    @DisplayName("a chunk decrypts only in its own file, source, content and seat")
    void theBindingHolds() {
        byte[] plaintext = "statement line".getBytes(StandardCharsets.UTF_8);
        SettlementFileCipher.Encrypted encrypted =
                cipher.encrypt(plaintext, SettlementFileCipher.associatedData(FILE, SOURCE, SHA, 0));
        UUID otherFile = UUID.fromString("01a0e2bc-8200-7102-8000-000000000002");
        byte[] otherSha = new byte[32];
        otherSha[0] = 1;
        for (byte[] wrongSeat :
                new byte[][] {
                    SettlementFileCipher.associatedData(otherFile, SOURCE, SHA, 0),
                    SettlementFileCipher.associatedData(FILE, otherFile, SHA, 0),
                    SettlementFileCipher.associatedData(FILE, SOURCE, otherSha, 0),
                    SettlementFileCipher.associatedData(FILE, SOURCE, SHA, 1)
                }) {
            assertThatThrownBy(() -> cipher.decrypt(encrypted, wrongSeat))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("statement");
        }
    }

    @Test
    @DisplayName("one flipped ciphertext byte refuses - tampering and the wrong key are one"
            + " failure, and neither yields content")
    void tamperingRefuses() {
        byte[] aad = SettlementFileCipher.associatedData(FILE, SOURCE, SHA, 0);
        SettlementFileCipher.Encrypted encrypted =
                cipher.encrypt("statement line".getBytes(StandardCharsets.UTF_8), aad);
        byte[] flipped = encrypted.ciphertext();
        flipped[0] ^= 1;
        assertThatThrownBy(
                        () ->
                                cipher.decrypt(
                                        new SettlementFileCipher.Encrypted(
                                                flipped, encrypted.nonce()),
                                        aad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("statement");
    }

    @Test
    @DisplayName("a key that is not exactly 32 bytes is refused, never stretched")
    void aWrongLengthKeyIsRefused() {
        assertThatThrownBy(() -> new SettlementFileCipher(new byte[16], 1, new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("16");
    }

    @Test
    @DisplayName("nothing prints - a ciphertext never reaches a log line")
    void nothingPrints() {
        SettlementFileCipher.Encrypted encrypted =
                cipher.encrypt(
                        "statement line".getBytes(StandardCharsets.UTF_8),
                        SettlementFileCipher.associatedData(FILE, SOURCE, SHA, 0));
        assertThat(encrypted.toString()).doesNotContain("statement").doesNotContain("[B@");
    }
}
