package com.finapp.paymentmethods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DestinationReference} (`P7-TSK-007`): the three {@code INV-RAIL-03} refusals, their
 * recorded limits, and the self-masking rendering — the {@code TokenReferenceTest} battery at
 * the bank boundary.
 */
@DisplayName("DestinationReference (P7-TSK-007)")
class DestinationReferenceTest {

    @Test
    @DisplayName("lawful provider references construct: the ProviderReference charset, letters present")
    void lawfulReferencesConstruct() {
        for (String legal :
                new String[] {
                    "dest-acct-7f31",
                    "dest:GB29NWBK60161331926819", // the adapter-prefix contract in action
                    "acct_01hgw2.v7:primary",
                    "a",
                    "d".repeat(128),
                    // 35+ characters cannot be an IBAN, whatever their prefix looks like.
                    "GB29" + "a".repeat(31)
                }) {
            assertThatCode(() -> DestinationReference.of(legal))
                    .as("%s is a lawful opaque reference", legal)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("the charset and bound are ProviderReference's own")
    void charsetAndBoundHold() {
        for (String bad : new String[] {"", "d".repeat(129), "dest/acct", "dest acct", "d€st"}) {
            assertThatThrownBy(() -> DestinationReference.of(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> DestinationReference.of(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a value with no letter is refused: account numbers, sort codes and phones")
    void lettersAreRequired() {
        for (String identifierShaped :
                new String[] {
                    "12345678", // a domestic account number
                    "12-34-56:12345678", // sort code and account, punctuated
                    "4159265358", // a phone number without its plus
                    "0044.79.1112.3456", // a phone number, dotted
                    "31926819"
                }) {
            assertThatThrownBy(() -> DestinationReference.of(identifierShaped))
                    .as("%s has no letter and is refused (INV-RAIL-03)", identifierShaped)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("INV-RAIL-03")
                    // The refusal names the rule, never the value (INV-AUD-02).
                    .satisfies(
                            refusal ->
                                    assertThat(refusal.getMessage())
                                            .doesNotContain(identifierShaped));
        }
    }

    @Test
    @DisplayName("the international identifier shape is refused up to an IBAN's own bound")
    void internationalIdentifierShapeIsRefused() {
        for (String iban :
                new String[] {
                    "GB29NWBK60161331926819",
                    "DE89370400440532013000",
                    "gb29nwbk60161331926819", // case must not evade the rule
                    "FR1420041010050500013M02606"
                }) {
            assertThatThrownBy(() -> DestinationReference.of(iban))
                    .as("%s is IBAN-shaped and refused (INV-RAIL-03)", iban)
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(
                            refusal ->
                                    assertThat(refusal.getMessage()).doesNotContain(iban));
        }
        // The recorded limit, held exactly: at 34 characters the shape still refuses...
        assertThatThrownBy(() -> DestinationReference.of("GB29" + "a".repeat(30)))
                .isInstanceOf(IllegalArgumentException.class);
        // ...and one past the bound nothing is an IBAN, so the reference is lawful.
        assertThatCode(() -> DestinationReference.of("GB29" + "a".repeat(31)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("every rendering masks; expose() is the one way the value comes off")
    void renderingsMask() {
        String needle = "dest-needle-a4b2c9";
        DestinationReference reference = DestinationReference.of(needle);
        assertThat(reference.toString()).doesNotContain(needle);
        assertThat(reference.secret().toString()).doesNotContain(needle);
        assertThat(reference.expose()).isEqualTo(needle);
    }
}
