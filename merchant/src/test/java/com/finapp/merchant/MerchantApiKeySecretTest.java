package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The secret's own properties (`P6-TSK-002`) — the ones the whole authentication design rests
 * on, asserted rather than assumed.
 */
@DisplayName("the merchant api key secret (P6-TSK-002)")
class MerchantApiKeySecretTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Test
    @DisplayName("a fresh secret carries the full entropy the SHA-256 argument depends on")
    void freshSecretsCarryTheDeclaredEntropy() {
        // The entire reason this is SHA-256 and not Argon2 is that the input is 32 random
        // bytes. If the generator ever produced fewer, the derivation choice would silently
        // become wrong - so the claim is a test rather than a comment.
        byte[] decoded =
                Base64.getUrlDecoder()
                        .decode(MerchantApiKeySecret.issue(RANDOM).plaintext().expose());
        assertThat(decoded).hasSize(MerchantApiKeySecret.ENTROPY_BYTES);
    }

    @Test
    @DisplayName("secrets do not repeat")
    void secretsDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            assertThat(seen.add(MerchantApiKeySecret.issue(RANDOM).plaintext().expose()))
                    .as("a repeated secret would be two merchants holding one credential")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the stored hash verifies its own secret and refuses every other")
    void theHashVerifiesOnlyItsOwnSecret() {
        MerchantApiKeySecret secret = MerchantApiKeySecret.issue(RANDOM);
        String stored = secret.hash().expose();

        assertThat(MerchantApiKeySecret.of(secret.plaintext().expose()).matches(stored))
                .isTrue();
        assertThat(MerchantApiKeySecret.issue(RANDOM).matches(stored)).isFalse();
        assertThat(MerchantApiKeySecret.of("").matches(stored)).isFalse();
        assertThat(MerchantApiKeySecret.of("not-a-secret").matches(stored)).isFalse();
    }

    @Test
    @DisplayName("the hash is a SHA-256 digest in the shape V003's CHECK accepts")
    void theHashHasTheShapeTheSchemaConstrains() {
        // One definition, two artefacts: if the digest or its encoding changed, every insert
        // would fail against V003's regex - which is a runtime failure this catches first.
        assertThat(MerchantApiKeySecret.issue(RANDOM).hash().expose())
                .matches("^[A-Za-z0-9+/]{43}=$");
        assertThat(MerchantApiKeySecret.ALGORITHM).isEqualTo("SHA-256");
    }

    @Test
    @DisplayName("neither the secret nor its holder renders in a string (INV-AUD-02)")
    void nothingRendersTheSecret() {
        MerchantApiKeySecret secret = MerchantApiKeySecret.issue(RANDOM);
        String plaintext = secret.plaintext().expose();
        assertThat(secret.toString()).doesNotContain(plaintext);
        assertThat(secret.hash().toString()).doesNotContain(secret.hash().expose());
    }
}
