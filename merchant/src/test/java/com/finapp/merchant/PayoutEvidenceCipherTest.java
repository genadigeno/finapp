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

    // ---------------------------------------------------------------- key rings (P15-TSK-019)

    /**
     * Bytes this class wrote before key rings existed: produced by the code on master 6a1e9626 from the fixture key below and a
     * fixed nonce, so a change to the ciphertext's format, or a read that stops using the stamp, goes red here. Not a real key.
     */
    private static final String RING_GOLDEN_NONCE = "252525252525252525252525";

    private static final String RING_GOLDEN_CIPHERTEXT = "2305f54b79bed4edb5752d3d3066394f8fe919fdb5793f964eedaba89d9921341f75b7eca41c83907e613675549195ab";

    private static byte[] ringKey(int fill) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) fill);
        return key;
    }

    private static com.finapp.platform.security.KeyRing rotatedRing(int active) {
        return com.finapp.platform.security.KeyRing.of(
                "test key", java.util.Map.of(1, ringKey(0x15), 2, ringKey(0x41)), active, java.util.Set.of());
    }

    private static PayoutEvidenceCipher.Encrypted writtenBeforeRings() {
        return new PayoutEvidenceCipher.Encrypted(
                java.util.HexFormat.of().parseHex(RING_GOLDEN_CIPHERTEXT), java.util.HexFormat.of().parseHex(RING_GOLDEN_NONCE), 1);
    }

    @Test
    @DisplayName("a row written before key rings existed decrypts unchanged, under a ring of one (the code on master, 6a1e9626)")
    void aRowWrittenBeforeRingsDecryptsUnderARingOfOne() {
        PayoutEvidenceCipher cipher = new PayoutEvidenceCipher(ringKey(0x15), 1, new java.security.SecureRandom());
        assertThat(new String(cipher.decrypt(writtenBeforeRings()), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("golden vector, not real evidence");
    }

    @Test
    @DisplayName("a v1 row is read under version 1 after rotation to version 2: the stamp decides, never the active version")
    void aV1RowIsReadUnderItsStampAfterRotation() {
        PayoutEvidenceCipher cipher = new PayoutEvidenceCipher(rotatedRing(2), new java.security.SecureRandom());
        assertThat(new String(cipher.decrypt(writtenBeforeRings()), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("golden vector, not real evidence");
    }

    @Test
    @DisplayName("new rows are written under the active version: version 1 before activation, version 2 after, each readable by every replica holding both")
    void newRowsAreWrittenUnderTheActiveVersion() {
        PayoutEvidenceCipher before = new PayoutEvidenceCipher(rotatedRing(1), new java.security.SecureRandom());
        PayoutEvidenceCipher after = new PayoutEvidenceCipher(rotatedRing(2), new java.security.SecureRandom());

        PayoutEvidenceCipher.Encrypted writtenBefore = before.encrypt("later evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        PayoutEvidenceCipher.Encrypted writtenAfter = after.encrypt("later evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(writtenBefore.keyVersion()).as("a known version stays unused until it is activated").isEqualTo(1);
        assertThat(writtenAfter.keyVersion()).as("the active pointer decides the version written").isEqualTo(2);
        assertThat(new String(after.decrypt(writtenAfter), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("later evidence");
        assertThat(new String(before.decrypt(writtenAfter), java.nio.charset.StandardCharsets.UTF_8)).as("a replica not yet activated reads its neighbour's new row").isEqualTo("later evidence");
        assertThat(new String(after.decrypt(writtenBefore), java.nio.charset.StandardCharsets.UTF_8)).as("an activated replica reads an old row").isEqualTo("later evidence");
    }

    @Test
    @DisplayName("a row stamped with a version the ring does not hold is refused, even when the active key could read it")
    void anUnknownStampedVersionIsRefused() {
        PayoutEvidenceCipher cipher = new PayoutEvidenceCipher(rotatedRing(1), new java.security.SecureRandom());
        PayoutEvidenceCipher.Encrypted written = cipher.encrypt("later evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> cipher.decrypt(new PayoutEvidenceCipher.Encrypted(written.ciphertext(), written.nonce(), 9)))
                .isInstanceOf(com.finapp.platform.security.UnknownKeyVersionException.class)
                .as("one refusal, whatever went wrong: the version the row claims is not told to a caller")
                .hasMessageNotContaining("9")
                .hasNoCause();
    }
}
