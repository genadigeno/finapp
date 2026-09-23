package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.sharedkernel.security.Sensitive;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bank details never enter the platform (`P6-TSK-011`, ADR-0056 §5): the grant and the reference
 * accept a token and refuse anything shaped like an account number — domestic digits or an
 * international account — naming the rule and never the value ({@code INV-AUD-02}).
 */
@DisplayName("the destination grant and reference refuse bank details (P6-TSK-011)")
class BankDetailShapesTest {

    private static final List<String> TOKENS =
            List.of("pdg_4f9ZkQ2mX", "pdr-7Kx9QmZ2", "btok_1N3fV2eZvKYlo2C", "a", "ref_000123");

    private static final List<String> BANK_DETAILS =
            List.of(
                    // Domestic account numbers and sort codes.
                    "12345678",
                    "20-00-00",
                    "0000123456789",
                    // International accounts, in both cases.
                    "DE89370400440532013000",
                    "GB29NWBK60161331926819",
                    "de89370400440532013000",
                    "FR1420041010050500013M02606");

    private static final List<String> NOT_TOKENS =
            List.of("", "has space", "semi;colon", "quote\"", "x".repeat(129));

    @Test
    @DisplayName("tokens are accepted by the grant and the reference alike")
    void tokensAreAccepted() {
        for (String token : TOKENS) {
            assertThat(new PayoutDestinationGrant(Sensitive.of(token)).expose()).isEqualTo(token);
            assertThat(PayoutDestinationReference.of(token).expose()).isEqualTo(token);
        }
    }

    @Test
    @DisplayName("an account number or international account is refused, and never echoed")
    void bankDetailsAreRefused() {
        for (String detail : BANK_DETAILS) {
            assertThatIllegalArgumentException()
                    .as("the grant must refuse %s", detail)
                    .isThrownBy(() -> new PayoutDestinationGrant(Sensitive.of(detail)))
                    .withMessageNotContaining(detail);
            assertThatIllegalArgumentException()
                    .as("the reference must refuse %s", detail)
                    .isThrownBy(() -> PayoutDestinationReference.of(detail))
                    .withMessageNotContaining(detail);
        }
    }

    @Test
    @DisplayName("a value outside the token charset or bound is refused")
    void nonTokensAreRefused() {
        for (String value : NOT_TOKENS) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PayoutDestinationGrant(Sensitive.of(value)));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> PayoutDestinationReference.of(value));
        }
        assertThat(PayoutDestinationReference.of("x".repeat(128)).expose()).hasSize(128);
    }

    @Test
    @DisplayName("neither type prints its value: a grant or reference in a log is a way to a bank account")
    void neitherTypePrintsItsValue() {
        String token = "pdr_4f9ZkQ2mX";
        assertThat(new PayoutDestinationGrant(Sensitive.of(token)).toString())
                .doesNotContain(token);
        assertThat(PayoutDestinationReference.of(token).toString()).doesNotContain(token);
    }
}
