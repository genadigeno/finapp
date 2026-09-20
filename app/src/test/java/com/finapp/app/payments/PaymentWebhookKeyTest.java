package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.kyc.CallbackKey;
import com.finapp.app.kyc.DocumentKey;
import com.finapp.app.mfa.MfaKey;
import com.finapp.app.security.ConfinedCredential;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The seventh credential's own contract (`P5-TSK-012`) — the parts that are <em>this</em>
 * spec's, the mechanism itself being `ConfinedCredentialTest`'s subject.
 *
 * <p>Exists because of the `P2-TSK-011` finding: a credential class with no test is a
 * confinement nobody would notice removed. The separation that matters most here is from
 * credential five: the bytes that authenticate the provider's statements <em>to us</em> must
 * never be the bytes that authenticate <em>us to the provider</em>.
 */
@DisplayName("PaymentWebhookKey (P5-TSK-012)")
class PaymentWebhookKeyTest {

    @Test
    @DisplayName("the local default is domain-separated from its siblings - credential five"
            + " above all")
    void locallyDomainSeparatedFromEverySibling() {
        byte[] webhook = PaymentWebhookKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true);
        assertThat(webhook)
                .hasSize(32)
                .isNotEqualTo(ProviderApiKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true))
                .isNotEqualTo(MfaKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true))
                .isNotEqualTo(DocumentKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true))
                .isNotEqualTo(CallbackKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true));
    }

    @Test
    @DisplayName("the confinement is inherited, and the refusal names this credential's variable")
    void confinementIsInherited() {
        assertThatThrownBy(
                        () ->
                                PaymentWebhookKey.decode(
                                        ConfinedCredential.MARKED_LOCAL_DEFAULT, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payment webhook signing key")
                .hasMessageContaining("FINAPP_PAYMENT_WEBHOOK_KEY");
    }

    @Test
    @DisplayName("at least 32 bytes: 16 refused (an HMAC key must not be guessable), 48 permitted")
    void atLeastThirtyTwoBytes() {
        String sixteen = Base64.getEncoder().encodeToString(new byte[16]);
        String fortyEight = Base64.getEncoder().encodeToString(new byte[48]);

        assertThatThrownBy(() -> PaymentWebhookKey.decode(sixteen, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
        assertThat(PaymentWebhookKey.decode(fortyEight, true)).hasSize(48);
    }
}
