package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    // ---------------------------------------------------------------- key rings (P15-TSK-019)

    /**
     * Bytes this class wrote before key rings existed: produced by the code on master 6a1e9626 from the fixture key below and a
     * fixed nonce, so a change to the ciphertext's format, or a read that stops using the stamp, goes red here. Not a real key.
     */
    private static final String RING_GOLDEN_NONCE = "222222222222222222222222";

    private static final String RING_GOLDEN_CIPHERTEXT = "2c0cb8cacee515506d349859637e18f08783e9087512276a51e9472698c38b9fd4e748b77f7d6f96bec4f6aaab30b7e8";

    private static byte[] ringKey(int fill) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) fill);
        return key;
    }

    private static com.finapp.platform.security.KeyRing rotatedRing(int active) {
        return com.finapp.platform.security.KeyRing.of(
                "test key", java.util.Map.of(1, ringKey(0x12), 2, ringKey(0x41)), active, java.util.Set.of());
    }

    private static DocumentCipher.Encrypted writtenBeforeRings() {
        return new DocumentCipher.Encrypted(
                java.util.HexFormat.of().parseHex(RING_GOLDEN_CIPHERTEXT), java.util.HexFormat.of().parseHex(RING_GOLDEN_NONCE), 1);
    }

    @Test
    @DisplayName("a row written before key rings existed decrypts unchanged, under a ring of one (the code on master, 6a1e9626)")
    void aRowWrittenBeforeRingsDecryptsUnderARingOfOne() {
        DocumentCipher cipher = new DocumentCipher(ringKey(0x12), 1, new java.security.SecureRandom());
        assertThat(new String(cipher.decrypt(writtenBeforeRings()), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("golden vector, not real evidence");
    }

    @Test
    @DisplayName("a v1 row is read under version 1 after rotation to version 2: the stamp decides, never the active version")
    void aV1RowIsReadUnderItsStampAfterRotation() {
        DocumentCipher cipher = new DocumentCipher(rotatedRing(2), new java.security.SecureRandom());
        assertThat(new String(cipher.decrypt(writtenBeforeRings()), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("golden vector, not real evidence");
    }

    @Test
    @DisplayName("new rows are written under the active version: version 1 before activation, version 2 after, each readable by every replica holding both")
    void newRowsAreWrittenUnderTheActiveVersion() {
        DocumentCipher before = new DocumentCipher(rotatedRing(1), new java.security.SecureRandom());
        DocumentCipher after = new DocumentCipher(rotatedRing(2), new java.security.SecureRandom());

        DocumentCipher.Encrypted writtenBefore = before.encrypt(DocumentBytes.of("a later document".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        DocumentCipher.Encrypted writtenAfter = after.encrypt(DocumentBytes.of("a later document".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(writtenBefore.keyVersion()).as("a known version stays unused until it is activated").isEqualTo(1);
        assertThat(writtenAfter.keyVersion()).as("the active pointer decides the version written").isEqualTo(2);
        assertThat(new String(after.decrypt(writtenAfter), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("a later document");
        assertThat(new String(before.decrypt(writtenAfter), java.nio.charset.StandardCharsets.UTF_8)).as("a replica not yet activated reads its neighbour's new row").isEqualTo("a later document");
        assertThat(new String(after.decrypt(writtenBefore), java.nio.charset.StandardCharsets.UTF_8)).as("an activated replica reads an old row").isEqualTo("a later document");
    }

    @Test
    @DisplayName("a row stamped with a version the ring does not hold is refused, even when the active key could read it")
    void anUnknownStampedVersionIsRefused() {
        DocumentCipher cipher = new DocumentCipher(rotatedRing(1), new java.security.SecureRandom());
        DocumentCipher.Encrypted written = cipher.encrypt(DocumentBytes.of("a later document".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> cipher.decrypt(new DocumentCipher.Encrypted(written.ciphertext(), written.nonce(), 9)))
                .isInstanceOf(com.finapp.platform.security.UnknownKeyVersionException.class)
                .as("one refusal, whatever went wrong: the version the row claims is not told to a caller")
                .hasMessageNotContaining("9")
                .hasNoCause();
    }
}
