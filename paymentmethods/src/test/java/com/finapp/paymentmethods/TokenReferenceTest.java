package com.finapp.paymentmethods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The wrapped token's own contract (`P5-TSK-004`): the charset that makes the wire and the
 * store safe, the PAN-shape refusal that is {@code INV-PAY-02} at the type, and the masking.
 */
@DisplayName("TokenReference (P5-TSK-004)")
class TokenReferenceTest {

    @Test
    @DisplayName("real token shapes construct; everything outside the charset is refused")
    void charsetIsTheRule() {
        assertThatCode(() -> TokenReference.of("tok_visa-4242")).doesNotThrowAnyException();
        assertThatCode(() -> TokenReference.of("pm_1AbC_x-9")).doesNotThrowAnyException();
        for (String bad : new String[] {"", "tok space", "tok\"quote", "tok.dot", "tok:colon"}) {
            assertThatThrownBy(() -> TokenReference.of(bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(
                            e -> {
                                if (!bad.isBlank()) {
                                    // The refusal names the rule, never the value: a refused
                                    // token is still instrument-linked data (INV-AUD-02).
                                    assertThat(e.getMessage()).doesNotContain(bad);
                                }
                            });
        }
        assertThatThrownBy(() -> TokenReference.of("a".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a PAN-shaped value is refused, bare and separated alike (INV-PAY-02)")
    void panShapedValuesAreRefused() {
        for (String pan :
                new String[] {
                    "4111111111111111", // bare
                    "4111-1111-1111-1111", // hyphen-separated
                    "378282246310005", // 15-digit Amex shape
                    "0000"
                }) {
            assertThatThrownBy(() -> TokenReference.of(pan))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(pan));
        }
        // The refusal is the digits-only shape, not digits anywhere: real tokens carry them.
        assertThatCode(() -> TokenReference.of("tok_4242")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("every rendering masks - the component wraps, and expose() is the one way off")
    void renderingsMask() {
        String needle = "tok_needle-1234x";
        TokenReference token = TokenReference.of(needle);
        assertThat(token.toString()).doesNotContain(needle);
        assertThat(token.secret().toString()).doesNotContain(needle);
        assertThat(token.expose()).isEqualTo(needle);
    }
}
