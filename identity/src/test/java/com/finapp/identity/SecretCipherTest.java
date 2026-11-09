package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cipher that makes {@code INV-IDN-08} true.
 *
 * <h2>Written because a mutation survived</h2>
 *
 * <p>Removing the key-length check left every test green, because {@code MfaKey} refuses a short key
 * before it reaches here. That made this check defence in depth with nothing defending it — and the
 * defect it prevents is quiet: <strong>AES accepts a 16-byte key perfectly well</strong> and gives
 * AES-128, so a caller passing one would get a cipher that is weaker than this class documents,
 * with nothing failing.
 */
@DisplayName("SecretCipher (P1-TSK-017)")
class SecretCipherTest {

    private static final SecureRandom RANDOMNESS = new SecureRandom();

    private static byte[] key(byte fill) {
        byte[] key = new byte[SecretCipher.KEY_BYTES];
        java.util.Arrays.fill(key, fill);
        return key;
    }

    @Test
    @DisplayName("a secret round-trips")
    void aSecretRoundTrips() {
        SecretCipher cipher = new SecretCipher(key((byte) 1), 1, RANDOMNESS);
        Sensitive<String> secret = Sensitive.of("JBSWY3DPEHPK3PXP");

        assertThat(cipher.decrypt(cipher.encrypt(secret)).expose()).isEqualTo(secret.expose());
    }

    @Test
    @DisplayName("only a 32-byte key is accepted, because AES would take 16 and give AES-128")
    void onlyA256BitKeyIsAccepted() {
        // The mutation this test exists for. A silently-accepted short key produces a cipher that
        // looks like AES-256 and is not - a defect no functional test can see, because everything
        // still encrypts and decrypts correctly.
        for (int length : new int[] {0, 15, 16, 24, 31, 33, 64}) {
            byte[] wrong = new byte[length];
            assertThatThrownBy(() -> new SecretCipher(wrong, 1, RANDOMNESS))
                    .as("a %d-byte key must be refused", length)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("32");
        }
        assertThat(new SecretCipher(key((byte) 1), 1, RANDOMNESS).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("the nonce differs on every encryption")
    void theNonceIsNeverReused() {
        SecretCipher cipher = new SecretCipher(key((byte) 1), 1, RANDOMNESS);
        Sensitive<String> secret = Sensitive.of("JBSWY3DPEHPK3PXP");

        // A nonce reused under one AES-GCM key does not weaken the cipher, it BREAKS it - two
        // messages under the same nonce leak the authentication key. This is the one property of
        // GCM that has no graceful degradation.
        java.util.Set<String> nonces = new java.util.HashSet<>();
        java.util.Set<String> ciphertexts = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) {
            SecretCipher.Encrypted encrypted = cipher.encrypt(secret);
            nonces.add(java.util.Arrays.toString(encrypted.nonce()));
            ciphertexts.add(java.util.Arrays.toString(encrypted.ciphertext()));
        }
        assertThat(nonces).hasSize(200);
        // And the same plaintext must not produce the same ciphertext twice, which is what the
        // fresh nonce is FOR - otherwise a reader of the column could tell which customers share a
        // secret without decrypting anything.
        assertThat(ciphertexts).hasSize(200);
    }

    @Test
    @DisplayName("a tampered ciphertext is refused, not decrypted to something else")
    void tamperingIsRefused() {
        SecretCipher cipher = new SecretCipher(key((byte) 1), 1, RANDOMNESS);
        SecretCipher.Encrypted encrypted = cipher.encrypt(Sensitive.of("JBSWY3DPEHPK3PXP"));

        byte[] tampered = encrypted.ciphertext();
        tampered[0] ^= 0x01;

        // The authentication half of GCM, and the reason the mode was chosen over CBC or CTR: an
        // attacker with WRITE access to the column must not be able to substitute a secret they
        // control, which would be a second factor that authenticates the attacker.
        assertThatThrownBy(
                        () ->
                                cipher.decrypt(
                                        new SecretCipher.Encrypted(
                                                tampered, encrypted.nonce(), 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a different key cannot read it, and says nothing about why")
    void anotherKeyCannotDecrypt() {
        SecretCipher.Encrypted encrypted =
                new SecretCipher(key((byte) 1), 1, RANDOMNESS).encrypt(Sensitive.of("JBSWY3DPEHPK"));

        // INV-IDN-08's actual claim, in one line: holding the database is not holding the secret.
        assertThatThrownBy(() -> new SecretCipher(key((byte) 2), 2, RANDOMNESS).decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                // Indistinguishable from tampering, deliberately: a caller that could tell them
                // apart could probe which key version a row was written under.
                .hasMessage("Could not decrypt an MFA secret")
                .hasNoCause();
    }

    @Test
    @DisplayName("nothing about the ciphertext is rendered")
    void theEncryptedFormMasksItself() {
        SecretCipher.Encrypted encrypted =
                new SecretCipher(key((byte) 1), 7, RANDOMNESS).encrypt(Sensitive.of("JBSWY3DPEHPK"));

        // A record's generated toString prints every component. The ciphertext is what an offline
        // attacker needs, so it must not reach a log line by nobody writing a log statement
        // (INV-AUD-02, the P1-TSK-005 finding).
        assertThat(encrypted.toString())
                .contains("keyVersion=7")
                .doesNotContain(java.util.Arrays.toString(encrypted.ciphertext()));
    }

    @Test
    @DisplayName("the stored arrays cannot be mutated by a caller who kept a reference")
    void theEncryptedFormIsDefensivelyCopied() {
        byte[] nonce = new byte[SecretCipher.NONCE_BYTES];
        byte[] ciphertext = new byte[32];
        SecretCipher.Encrypted encrypted = new SecretCipher.Encrypted(ciphertext, nonce, 1);

        ciphertext[0] = 42;
        encrypted.ciphertext()[1] = 43;

        assertThat(encrypted.ciphertext()[0]).isZero();
        assertThat(encrypted.ciphertext()[1]).isZero();
    }

    // ---------------------------------------------------------------- key rings (P15-TSK-019)

    /**
     * Bytes this class wrote before key rings existed: produced by the code on master 6a1e9626 from the fixture key below and a
     * fixed nonce, so a change to the ciphertext's format, or a read that stops using the stamp, goes red here. Not a real key.
     */
    private static final String RING_GOLDEN_NONCE = "212121212121212121212121";

    private static final String RING_GOLDEN_CIPHERTEXT = "0fd673881f52e0a09496fd782100819d1ba67b3a5dec672545c0d58d9982f9fb4fd850df362f73dae1d5d35fc7ddecdf";

    private static byte[] ringKey(int fill) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) fill);
        return key;
    }

    private static com.finapp.platform.security.KeyRing rotatedRing(int active) {
        return com.finapp.platform.security.KeyRing.of(
                "test key", java.util.Map.of(1, ringKey(0x11), 2, ringKey(0x41)), active, java.util.Set.of());
    }

    private static SecretCipher.Encrypted writtenBeforeRings() {
        return new SecretCipher.Encrypted(
                java.util.HexFormat.of().parseHex(RING_GOLDEN_CIPHERTEXT), java.util.HexFormat.of().parseHex(RING_GOLDEN_NONCE), 1);
    }

    @Test
    @DisplayName("a row written before key rings existed decrypts unchanged, under a ring of one (the code on master, 6a1e9626)")
    void aRowWrittenBeforeRingsDecryptsUnderARingOfOne() {
        SecretCipher cipher = new SecretCipher(ringKey(0x11), 1, new java.security.SecureRandom());
        assertThat(cipher.decrypt(writtenBeforeRings()).expose()).isEqualTo("golden vector, not a real secret");
    }

    @Test
    @DisplayName("a v1 row is read under version 1 after rotation to version 2: the stamp decides, never the active version")
    void aV1RowIsReadUnderItsStampAfterRotation() {
        SecretCipher cipher = new SecretCipher(rotatedRing(2), new java.security.SecureRandom());
        assertThat(cipher.decrypt(writtenBeforeRings()).expose()).isEqualTo("golden vector, not a real secret");
    }

    @Test
    @DisplayName("new rows are written under the active version: version 1 before activation, version 2 after, each readable by every replica holding both")
    void newRowsAreWrittenUnderTheActiveVersion() {
        SecretCipher before = new SecretCipher(rotatedRing(1), new java.security.SecureRandom());
        SecretCipher after = new SecretCipher(rotatedRing(2), new java.security.SecureRandom());

        SecretCipher.Encrypted writtenBefore = before.encrypt(Sensitive.of("a later secret"));
        SecretCipher.Encrypted writtenAfter = after.encrypt(Sensitive.of("a later secret"));

        assertThat(writtenBefore.keyVersion()).as("a known version stays unused until it is activated").isEqualTo(1);
        assertThat(writtenAfter.keyVersion()).as("the active pointer decides the version written").isEqualTo(2);
        assertThat(after.decrypt(writtenAfter).expose()).isEqualTo("a later secret");
        assertThat(before.decrypt(writtenAfter).expose()).as("a replica not yet activated reads its neighbour's new row").isEqualTo("a later secret");
        assertThat(after.decrypt(writtenBefore).expose()).as("an activated replica reads an old row").isEqualTo("a later secret");
    }

    @Test
    @DisplayName("a row stamped with a version the ring does not hold is refused, even when the active key could read it")
    void anUnknownStampedVersionIsRefused() {
        SecretCipher cipher = new SecretCipher(rotatedRing(1), new java.security.SecureRandom());
        SecretCipher.Encrypted written = cipher.encrypt(Sensitive.of("a later secret"));
        assertThatThrownBy(() -> cipher.decrypt(new SecretCipher.Encrypted(written.ciphertext(), written.nonce(), 9)))
                .isInstanceOf(com.finapp.platform.security.UnknownKeyVersionException.class)
                .as("one refusal, whatever went wrong: the version the row claims is not told to a caller")
                .hasMessageNotContaining("9")
                .hasNoCause();
    }
}
