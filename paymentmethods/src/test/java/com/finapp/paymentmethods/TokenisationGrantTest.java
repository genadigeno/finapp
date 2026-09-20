package com.finapp.paymentmethods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.security.Sensitive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one-time grant's contract (`P5-TSK-005`): the shape rule that turns a pasted card number
 * away at the boundary ({@code INV-PAY-02} at the surface), and the masking.
 */
@DisplayName("TokenisationGrant (P5-TSK-005)")
class TokenisationGrantTest {

    @Test
    @DisplayName("grant shapes construct; everything outside the charset is refused, non-echoing")
    void charsetIsTheRule() {
        assertThatCode(() -> new TokenisationGrant(Sensitive.of("ctok_visa-4242")))
                .doesNotThrowAnyException();
        for (String bad : new String[] {"", "ctok space", "ctok\"quote", "ctok.dot"}) {
            assertThatThrownBy(() -> new TokenisationGrant(Sensitive.of(bad)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(
                            e -> {
                                if (!bad.isBlank()) {
                                    assertThat(e.getMessage()).doesNotContain(bad);
                                }
                            });
        }
        assertThatThrownBy(() -> new TokenisationGrant(Sensitive.of("a".repeat(129))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a card-number-shaped value is refused - raw instrument data turned away at the boundary")
    void cardNumberShapesAreRefused() {
        for (String pan :
                new String[] {"4111111111111111", "4111-1111-1111-1111", "378282246310005"}) {
            assertThatThrownBy(() -> new TokenisationGrant(Sensitive.of(pan)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(pan));
        }
        // Digits ANYWHERE are fine - it is the digits-only shape that is a card number.
        assertThatCode(() -> new TokenisationGrant(Sensitive.of("ctok_4242")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("every rendering masks, and expose() is the one way off")
    void renderingsMask() {
        String needle = "ctok_needle-888x";
        TokenisationGrant grant = new TokenisationGrant(Sensitive.of(needle));
        assertThat(grant.toString()).doesNotContain(needle);
        assertThat(grant.secret().toString()).doesNotContain(needle);
        assertThat(grant.expose()).isEqualTo(needle);
    }
}
