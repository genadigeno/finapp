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
}
