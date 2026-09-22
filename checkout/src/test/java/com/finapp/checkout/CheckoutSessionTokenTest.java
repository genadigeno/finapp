package com.finapp.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The checkout session's bearer token (`P6-TSK-006`, {@code INV-IDN-01}, {@code INV-IDN-02}) —
 * the platform's fifth credential value and the third to follow {@code SessionToken}'s shape.
 */
@DisplayName("the checkout session token (P6-TSK-006)")
class CheckoutSessionTokenTest {

    private final SecureRandom randomness = new SecureRandom();

    @Test
    @DisplayName("256 bits of entropy, and two tokens are never the same")
    void tokensAreUnguessableAndDistinct() {
        assertThat(CheckoutSessionToken.ENTROPY_BYTES)
                .as("the number that makes the SHA-256 argument true")
                .isEqualTo(32);

        java.util.Set<String> hashes = new java.util.HashSet<>();
        for (int i = 0; i < 500; i++) {
            hashes.add(CheckoutSessionToken.issue(randomness).hash().expose());
        }
        assertThat(hashes).hasSize(500);
    }

    @Test
    @DisplayName("the stored value is a SHA-256 digest of the exact shape V002 constrains")
    void theHashHasTheShapeTheSchemaConstrains() {
        String hash = CheckoutSessionToken.issue(randomness).hash().expose();
        assertThat(hash)
                .as("base64 of 32 digest bytes - 43 characters and one '='")
                .matches("^[A-Za-z0-9+/]{43}=$");
        assertThat(Base64.getDecoder().decode(hash)).hasSize(32);
        assertThat(CheckoutSessionToken.ALGORITHM)
                .as("INV-IDN-02: what produced the stored value, recorded beside it")
                .isEqualTo("SHA-256");
    }

    @Test
    @DisplayName("a token verifies against its own hash and against no other")
    void verificationIsExact() {
        CheckoutSessionToken mine = CheckoutSessionToken.issue(randomness);
        CheckoutSessionToken theirs = CheckoutSessionToken.issue(randomness);

        assertThat(mine.matches(mine.hash().expose())).isTrue();
        assertThat(mine.matches(theirs.hash().expose())).isFalse();
    }

    @Test
    @DisplayName("a presented token of any shape is accepted for COMPARISON and simply fails")
    void aMalformedPresentedTokenJustFails() {
        // No shape validation, deliberately: refusing a malformed value DIFFERENTLY would tell
        // a caller its guess was at least the right shape, which is a free bit for anybody
        // probing (SessionToken.of's rule).
        CheckoutSessionToken real = CheckoutSessionToken.issue(randomness);
        assertThat(CheckoutSessionToken.of("not-a-token").matches(real.hash().expose()))
                .isFalse();
        assertThat(CheckoutSessionToken.of("").matches(real.hash().expose())).isFalse();
    }

    @Test
    @DisplayName("nothing renders the token: toString is masked and the value stays wrapped")
    void nothingRendersTheToken() {
        CheckoutSessionToken token = CheckoutSessionToken.issue(randomness);
        String plaintext = token.plaintext().expose();

        assertThat(token.toString())
                .contains(Sensitive.MASK)
                .doesNotContain(plaintext);
        assertThat(token.hash().toString())
                .as("the hash is wrapped too - in a log it identifies one live checkout")
                .doesNotContain(token.hash().expose());
        assertThat(token.plaintext().toString()).doesNotContain(plaintext);
    }
}
