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

    // ---------------------------------------------------------------- key rings (P15-TSK-019)

    /**
     * Bytes this class wrote before key rings existed: produced by the code on master 6a1e9626 from the fixture key below and a
     * fixed nonce, so a change to the ciphertext's format, or a read that stops using the stamp, goes red here. Not a real key.
     */
    private static final String RING_GOLDEN_NONCE = "242424242424242424242424";

    private static final String RING_GOLDEN_CIPHERTEXT = "5760d253e8c8b4a9536b0b916e53c012108e56f84af6690be2bc20566cf95580978d0152a6bab3a2e793c0a651de8d52";

    private static byte[] ringKey(int fill) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) fill);
        return key;
    }

    private static com.finapp.platform.security.KeyRing rotatedRing(int active) {
        return com.finapp.platform.security.KeyRing.of(
                "test key", java.util.Map.of(1, ringKey(0x14), 2, ringKey(0x41)), active, java.util.Set.of());
    }

    private static EvidenceCipher.Encrypted writtenBeforeRings() {
        return new EvidenceCipher.Encrypted(
                java.util.HexFormat.of().parseHex(RING_GOLDEN_CIPHERTEXT), java.util.HexFormat.of().parseHex(RING_GOLDEN_NONCE), 1);
    }

    @Test
    @DisplayName("a row written before key rings existed decrypts unchanged, under a ring of one (the code on master, 6a1e9626)")
    void aRowWrittenBeforeRingsDecryptsUnderARingOfOne() {
        EvidenceCipher cipher = new EvidenceCipher(ringKey(0x14), 1, new java.security.SecureRandom());
        assertThat(new String(cipher.decrypt(writtenBeforeRings()), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("golden vector, not real evidence");
    }

    @Test
    @DisplayName("a v1 row is read under version 1 after rotation to version 2: the stamp decides, never the active version")
    void aV1RowIsReadUnderItsStampAfterRotation() {
        EvidenceCipher cipher = new EvidenceCipher(rotatedRing(2), new java.security.SecureRandom());
        assertThat(new String(cipher.decrypt(writtenBeforeRings()), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("golden vector, not real evidence");
    }

    @Test
    @DisplayName("new rows are written under the active version: version 1 before activation, version 2 after, each readable by every replica holding both")
    void newRowsAreWrittenUnderTheActiveVersion() {
        EvidenceCipher before = new EvidenceCipher(rotatedRing(1), new java.security.SecureRandom());
        EvidenceCipher after = new EvidenceCipher(rotatedRing(2), new java.security.SecureRandom());

        EvidenceCipher.Encrypted writtenBefore = before.encrypt("later evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        EvidenceCipher.Encrypted writtenAfter = after.encrypt("later evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(writtenBefore.keyVersion()).as("a known version stays unused until it is activated").isEqualTo(1);
        assertThat(writtenAfter.keyVersion()).as("the active pointer decides the version written").isEqualTo(2);
        assertThat(new String(after.decrypt(writtenAfter), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("later evidence");
        assertThat(new String(before.decrypt(writtenAfter), java.nio.charset.StandardCharsets.UTF_8)).as("a replica not yet activated reads its neighbour's new row").isEqualTo("later evidence");
        assertThat(new String(after.decrypt(writtenBefore), java.nio.charset.StandardCharsets.UTF_8)).as("an activated replica reads an old row").isEqualTo("later evidence");
    }

    @Test
    @DisplayName("a row stamped with a version the ring does not hold is refused, even when the active key could read it")
    void anUnknownStampedVersionIsRefused() {
        EvidenceCipher cipher = new EvidenceCipher(rotatedRing(1), new java.security.SecureRandom());
        EvidenceCipher.Encrypted written = cipher.encrypt("later evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> cipher.decrypt(new EvidenceCipher.Encrypted(written.ciphertext(), written.nonce(), 9)))
                .isInstanceOf(com.finapp.platform.security.UnknownKeyVersionException.class)
                .as("one refusal, whatever went wrong: the version the row claims is not told to a caller")
                .hasMessageNotContaining("9")
                .hasNoCause();
    }
}
