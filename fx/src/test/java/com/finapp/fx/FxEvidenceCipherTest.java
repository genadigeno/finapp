package com.finapp.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The FX evidence cipher's key ring (`P15-TSK-019`): this class had no test of its own, and {@code JdbcFxProviderEvidenceStore}
 * used to refuse any row not written under the one active key, which a rotation would have made unreadable.
 */
@DisplayName("the FX evidence cipher rotates without losing a row (P15-TSK-019)")
class FxEvidenceCipherTest {

    // ---------------------------------------------------------------- key rings (P15-TSK-019)

    /**
     * Bytes this class wrote before key rings existed: produced by the code on master 6a1e9626 from the fixture key below and a
     * fixed nonce, so a change to the ciphertext's format, or a read that stops using the stamp, goes red here. Not a real key.
     */
    private static final String RING_GOLDEN_NONCE = "272727272727272727272727";

    private static final String RING_GOLDEN_CIPHERTEXT = "a33298933376ce7dab3062ac3da7dd4cc6d2623e2e65cef932733d81ba557574531530e5460d02566e28ad280c286e81";

    private static byte[] ringKey(int fill) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) fill);
        return key;
    }

    private static com.finapp.platform.security.KeyRing rotatedRing(int active) {
        return com.finapp.platform.security.KeyRing.of(
                "test key", java.util.Map.of(1, ringKey(0x17), 2, ringKey(0x41)), active, java.util.Set.of());
    }

    private static FxEvidenceCipher.Encrypted writtenBeforeRings() {
        return new FxEvidenceCipher.Encrypted(
                java.util.HexFormat.of().parseHex(RING_GOLDEN_CIPHERTEXT), java.util.HexFormat.of().parseHex(RING_GOLDEN_NONCE), 1);
    }

    @Test
    @DisplayName("a row written before key rings existed decrypts unchanged, under a ring of one (the code on master, 6a1e9626)")
    void aRowWrittenBeforeRingsDecryptsUnderARingOfOne() {
        FxEvidenceCipher cipher = new FxEvidenceCipher(ringKey(0x17), 1, new java.security.SecureRandom());
        assertThat(new String(cipher.decrypt(writtenBeforeRings()), StandardCharsets.UTF_8)).isEqualTo("golden vector, not real evidence");
    }

    @Test
    @DisplayName("a v1 row is read under version 1 after rotation to version 2: the stamp decides, never the active version")
    void aV1RowIsReadUnderItsStampAfterRotation() {
        FxEvidenceCipher cipher = new FxEvidenceCipher(rotatedRing(2), new java.security.SecureRandom());
        assertThat(new String(cipher.decrypt(writtenBeforeRings()), StandardCharsets.UTF_8)).isEqualTo("golden vector, not real evidence");
    }

    @Test
    @DisplayName("new rows are written under the active version: version 1 before activation, version 2 after, each readable by every replica holding both")
    void newRowsAreWrittenUnderTheActiveVersion() {
        FxEvidenceCipher before = new FxEvidenceCipher(rotatedRing(1), new java.security.SecureRandom());
        FxEvidenceCipher after = new FxEvidenceCipher(rotatedRing(2), new java.security.SecureRandom());

        FxEvidenceCipher.Encrypted writtenBefore = before.encrypt("later evidence".getBytes(StandardCharsets.UTF_8));
        FxEvidenceCipher.Encrypted writtenAfter = after.encrypt("later evidence".getBytes(StandardCharsets.UTF_8));

        assertThat(writtenBefore.keyVersion()).as("a known version stays unused until it is activated").isEqualTo(1);
        assertThat(writtenAfter.keyVersion()).as("the active pointer decides the version written").isEqualTo(2);
        assertThat(new String(after.decrypt(writtenAfter), StandardCharsets.UTF_8)).isEqualTo("later evidence");
        assertThat(new String(before.decrypt(writtenAfter), StandardCharsets.UTF_8)).as("a replica not yet activated reads its neighbour's new row").isEqualTo("later evidence");
        assertThat(new String(after.decrypt(writtenBefore), StandardCharsets.UTF_8)).as("an activated replica reads an old row").isEqualTo("later evidence");
    }

    @Test
    @DisplayName("a row stamped with a version the ring does not hold is refused, even when the active key could read it")
    void anUnknownStampedVersionIsRefused() {
        FxEvidenceCipher cipher = new FxEvidenceCipher(rotatedRing(1), new java.security.SecureRandom());
        FxEvidenceCipher.Encrypted written = cipher.encrypt("later evidence".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> cipher.decrypt(new FxEvidenceCipher.Encrypted(written.ciphertext(), written.nonce(), 9)))
                .isInstanceOf(com.finapp.platform.security.UnknownKeyVersionException.class)
                .as("one refusal, whatever went wrong: the version the row claims is not told to a caller")
                .hasMessageNotContaining("9")
                .hasNoCause();
    }
}
