package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Credit's evidence cipher and its ring (`P15-TSK-019`): a ciphertext is read under the version stamped on its row, and
 * its binding to the evidence row survives a rotation. This class had no test of its own.
 */
@DisplayName("the credit evidence cipher (P15-TSK-019)")
class CreditEvidenceCipherTest {

    private static final CreditEvidenceId GOLDEN_EVIDENCE =
            CreditEvidenceId.of(UUID.fromString("01890000-0000-7000-8000-00000000c002"));

    private static final CreditEvidenceId ANOTHER_EVIDENCE =
            CreditEvidenceId.of(UUID.fromString("01890000-0000-7000-8000-00000000c003"));

    @Test
    @DisplayName("a ciphertext moved onto another evidence row is refused")
    void aMovedCiphertextIsRefused() {
        CreditEvidenceCipher cipher = new CreditEvidenceCipher(ringKey(0x41), 1, new SecureRandom());
        byte[] evidence = "bureau answer".getBytes(StandardCharsets.UTF_8);
        CreditEvidenceCipher.Encrypted encrypted = cipher.encrypt(GOLDEN_EVIDENCE, evidence);
        assertThat(cipher.decrypt(GOLDEN_EVIDENCE, encrypted)).isEqualTo(evidence);
        assertThatThrownBy(() -> cipher.decrypt(ANOTHER_EVIDENCE, encrypted)).isInstanceOf(IllegalStateException.class);
    }

    // ---------------------------------------------------------------- key rings (P15-TSK-019)

    /**
     * Bytes this class wrote before key rings existed: produced by the code on master 6a1e9626 from the fixture key below and a
     * fixed nonce, so a change to the ciphertext's format, or a read that stops using the stamp, goes red here. Not a real key.
     */
    private static final String RING_GOLDEN_NONCE = "262626262626262626262626";

    private static final String RING_GOLDEN_CIPHERTEXT = "f7a3dd07807cd55fc17984c831007084126fa37a4fb6798e6190fbb774255d418a45dc12e0652e7014393339720fe3df";

    private static byte[] ringKey(int fill) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) fill);
        return key;
    }

    private static com.finapp.platform.security.KeyRing rotatedRing(int active) {
        return com.finapp.platform.security.KeyRing.of(
                "test key", java.util.Map.of(1, ringKey(0x16), 2, ringKey(0x41)), active, java.util.Set.of());
    }

    private static CreditEvidenceCipher.Encrypted writtenBeforeRings() {
        return new CreditEvidenceCipher.Encrypted(
                java.util.HexFormat.of().parseHex(RING_GOLDEN_CIPHERTEXT), java.util.HexFormat.of().parseHex(RING_GOLDEN_NONCE), 1);
    }

    @Test
    @DisplayName("a row written before key rings existed decrypts unchanged, under a ring of one (the code on master, 6a1e9626)")
    void aRowWrittenBeforeRingsDecryptsUnderARingOfOne() {
        CreditEvidenceCipher cipher = new CreditEvidenceCipher(ringKey(0x16), 1, new java.security.SecureRandom());
        assertThat(new String(cipher.decrypt(GOLDEN_EVIDENCE, writtenBeforeRings()), StandardCharsets.UTF_8)).isEqualTo("golden vector, not real evidence");
    }

    @Test
    @DisplayName("a v1 row is read under version 1 after rotation to version 2: the stamp decides, never the active version")
    void aV1RowIsReadUnderItsStampAfterRotation() {
        CreditEvidenceCipher cipher = new CreditEvidenceCipher(rotatedRing(2), new java.security.SecureRandom());
        assertThat(new String(cipher.decrypt(GOLDEN_EVIDENCE, writtenBeforeRings()), StandardCharsets.UTF_8)).isEqualTo("golden vector, not real evidence");
    }

    @Test
    @DisplayName("new rows are written under the active version: version 1 before activation, version 2 after, each readable by every replica holding both")
    void newRowsAreWrittenUnderTheActiveVersion() {
        CreditEvidenceCipher before = new CreditEvidenceCipher(rotatedRing(1), new java.security.SecureRandom());
        CreditEvidenceCipher after = new CreditEvidenceCipher(rotatedRing(2), new java.security.SecureRandom());

        CreditEvidenceCipher.Encrypted writtenBefore = before.encrypt(GOLDEN_EVIDENCE, "later evidence".getBytes(StandardCharsets.UTF_8));
        CreditEvidenceCipher.Encrypted writtenAfter = after.encrypt(GOLDEN_EVIDENCE, "later evidence".getBytes(StandardCharsets.UTF_8));

        assertThat(writtenBefore.keyVersion()).as("a known version stays unused until it is activated").isEqualTo(1);
        assertThat(writtenAfter.keyVersion()).as("the active pointer decides the version written").isEqualTo(2);
        assertThat(new String(after.decrypt(GOLDEN_EVIDENCE, writtenAfter), StandardCharsets.UTF_8)).isEqualTo("later evidence");
        assertThat(new String(before.decrypt(GOLDEN_EVIDENCE, writtenAfter), StandardCharsets.UTF_8)).as("a replica not yet activated reads its neighbour's new row").isEqualTo("later evidence");
        assertThat(new String(after.decrypt(GOLDEN_EVIDENCE, writtenBefore), StandardCharsets.UTF_8)).as("an activated replica reads an old row").isEqualTo("later evidence");
    }

    @Test
    @DisplayName("a row stamped with a version the ring does not hold is refused, even when the active key could read it")
    void anUnknownStampedVersionIsRefused() {
        CreditEvidenceCipher cipher = new CreditEvidenceCipher(rotatedRing(1), new java.security.SecureRandom());
        CreditEvidenceCipher.Encrypted written = cipher.encrypt(GOLDEN_EVIDENCE, "later evidence".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> cipher.decrypt(GOLDEN_EVIDENCE, new CreditEvidenceCipher.Encrypted(written.ciphertext(), written.nonce(), 9)))
                .isInstanceOf(com.finapp.platform.security.UnknownKeyVersionException.class)
                .as("one refusal, whatever went wrong: the version the row claims is not told to a caller")
                .hasMessageNotContaining("9")
                .hasNoCause();
    }
}
