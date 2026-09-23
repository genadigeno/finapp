package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.payments.ProviderApiKey;
import com.finapp.app.security.ConfinedCredential;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payout provider API key's own contract (`P6-TSK-012`): separated from the payment
 * provider's key locally too, confined to loopback when defaulted, and at least 32 bytes.
 */
@DisplayName("PayoutProviderKey (P6-TSK-012)")
class PayoutProviderKeyTest {

    @Test
    @DisplayName("the local default is separated from the payment provider's key")
    void locallyDomainSeparated() {
        assertThat(PayoutProviderKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true))
                .hasSizeGreaterThanOrEqualTo(32)
                .isNotEqualTo(ProviderApiKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true));
    }

    @Test
    @DisplayName("the confinement is inherited, and the refusal names this credential's variable")
    void confinementIsInherited() {
        assertThatThrownBy(
                        () -> PayoutProviderKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payout provider API key")
                .hasMessageContaining("FINAPP_PAYOUT_PROVIDER_KEY");
    }

    @Test
    @DisplayName("at least 32 bytes: 16 refused, 48 accepted - a bearer secret may be longer")
    void atLeastThirtyTwoBytes() {
        assertThatThrownBy(
                        () ->
                                PayoutProviderKey.decode(
                                        Base64.getEncoder().encodeToString(new byte[16]), true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(PayoutProviderKey.decode(Base64.getEncoder().encodeToString(new byte[48]), true))
                .hasSize(48);
    }
}
