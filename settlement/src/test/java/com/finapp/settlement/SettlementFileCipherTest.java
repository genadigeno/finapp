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
        assertThat(cipher.decrypt(encrypted, aad, 1)).isEqualTo(plaintext);
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
            assertThatThrownBy(() -> cipher.decrypt(encrypted, wrongSeat, 1))
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
                                        aad,
                                        1))
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

    private static byte[] ringAad() {
        byte[] sha = new byte[32];
        java.util.Arrays.fill(sha, (byte) 0x5a);
        return SettlementFileCipher.associatedData(
                UUID.fromString("00000000-0000-0000-0000-00000000f001"), UUID.fromString("00000000-0000-0000-0000-00000000f002"), sha, 0);
    }

    // ---------------------------------------------------------------- key rings (P15-TSK-019)

    /**
     * Bytes this class wrote before key rings existed: produced by the code on master 6a1e9626 from the fixture key below and a
     * fixed nonce, so a change to the ciphertext's format, or a read that stops using the stamp, goes red here. Not a real key.
     */
    private static final String RING_GOLDEN_NONCE = "282828282828282828282828";

    private static final String RING_GOLDEN_CIPHERTEXT = "0d22083e28cf89cbb2cbcfe5920036347b30e7edd48b4f2971ae7ddee8626716c4531732545ec6a6d07d3770669c351a";

    private static byte[] ringKey(int fill) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) fill);
        return key;
    }

    private static com.finapp.platform.security.KeyRing rotatedRing(int active) {
        return com.finapp.platform.security.KeyRing.of(
                "test key", java.util.Map.of(1, ringKey(0x18), 2, ringKey(0x41)), active, java.util.Set.of());
    }

    private static SettlementFileCipher.Encrypted writtenBeforeRings() {
        return new SettlementFileCipher.Encrypted(
                java.util.HexFormat.of().parseHex(RING_GOLDEN_CIPHERTEXT), java.util.HexFormat.of().parseHex(RING_GOLDEN_NONCE));
    }

    @Test
    @DisplayName("a row written before key rings existed decrypts unchanged, under a ring of one (the code on master, 6a1e9626)")
    void aRowWrittenBeforeRingsDecryptsUnderARingOfOne() {
        SettlementFileCipher cipher = new SettlementFileCipher(ringKey(0x18), 1, new java.security.SecureRandom());
        assertThat(new String(cipher.decrypt(writtenBeforeRings(), ringAad(), 1), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("golden vector, not real evidence");
    }

    @Test
    @DisplayName("a v1 row is read under version 1 after rotation to version 2: the stamp decides, never the active version")
    void aV1RowIsReadUnderItsStampAfterRotation() {
        SettlementFileCipher cipher = new SettlementFileCipher(rotatedRing(2), new java.security.SecureRandom());
        assertThat(new String(cipher.decrypt(writtenBeforeRings(), ringAad(), 1), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("golden vector, not real evidence");
    }

    @Test
    @DisplayName("new rows are written under the active version: version 1 before activation, version 2 after, each readable by every replica holding both")
    void newRowsAreWrittenUnderTheActiveVersion() {
        SettlementFileCipher before = new SettlementFileCipher(rotatedRing(1), new java.security.SecureRandom());
        SettlementFileCipher after = new SettlementFileCipher(rotatedRing(2), new java.security.SecureRandom());

        SettlementFileCipher.Encrypted writtenBefore = before.encrypt("a later chunk".getBytes(java.nio.charset.StandardCharsets.UTF_8), ringAad());
        SettlementFileCipher.Encrypted writtenAfter = after.encrypt("a later chunk".getBytes(java.nio.charset.StandardCharsets.UTF_8), ringAad());

        assertThat(before.version()).as("a known version stays unused until it is activated").isEqualTo(1);
        assertThat(after.version()).as("the active pointer decides the version written").isEqualTo(2);
        assertThat(new String(after.decrypt(writtenAfter, ringAad(), 2), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("a later chunk");
        assertThat(new String(before.decrypt(writtenAfter, ringAad(), 2), java.nio.charset.StandardCharsets.UTF_8)).as("a replica not yet activated reads its neighbour's new row").isEqualTo("a later chunk");
        assertThat(new String(after.decrypt(writtenBefore, ringAad(), 1), java.nio.charset.StandardCharsets.UTF_8)).as("an activated replica reads an old row").isEqualTo("a later chunk");
    }

    @Test
    @DisplayName("a row stamped with a version the ring does not hold is refused, even when the active key could read it")
    void anUnknownStampedVersionIsRefused() {
        SettlementFileCipher cipher = new SettlementFileCipher(rotatedRing(1), new java.security.SecureRandom());
        SettlementFileCipher.Encrypted written = cipher.encrypt("a later chunk".getBytes(java.nio.charset.StandardCharsets.UTF_8), ringAad());
        assertThatThrownBy(() -> cipher.decrypt(written, ringAad(), 9))
                .isInstanceOf(com.finapp.platform.security.UnknownKeyVersionException.class)
                .as("one refusal, whatever went wrong: the version the row claims is not told to a caller")
                .hasMessageNotContaining("9")
                .hasNoCause();
    }
}
