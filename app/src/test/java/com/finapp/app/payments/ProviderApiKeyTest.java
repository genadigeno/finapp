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
 * The fifth credential's own contract (`P5-TSK-003`) — the parts that are <em>this</em> spec's,
 * the mechanism itself being `ConfinedCredentialTest`'s subject.
 *
 * <p>Exists because of the `P2-TSK-011` finding: {@code CallbackKey} — the fourth hand-written
 * copy — shipped with no test at all, so removing its confinement left everything green. Every
 * credential class gets a test, declaration-sized or not.
 */
@DisplayName("ProviderApiKey (P5-TSK-003)")
class ProviderApiKeyTest {

    @Test
    @DisplayName("the local default is domain-separated from all three existing keys, pairwise")
    void locallyDomainSeparatedFromEverySibling() {
        byte[] provider = ProviderApiKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true);
        assertThat(provider)
                .hasSize(32)
                .isNotEqualTo(MfaKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true))
                .isNotEqualTo(DocumentKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true))
                .isNotEqualTo(CallbackKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true));
    }

    @Test
    @DisplayName("the confinement is inherited, and the refusal names this credential's variable")
    void confinementIsInherited() {
        assertThatThrownBy(
                        () -> ProviderApiKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payment provider API key")
                .hasMessageContaining("FINAPP_PAYMENT_PROVIDER_KEY");
    }

    @Test
    @DisplayName("at least 32 bytes: 16 refused (a bearer secret is not allowed to be guessable), 48 permitted")
    void atLeastThirtyTwoBytes() {
        String sixteen = Base64.getEncoder().encodeToString(new byte[16]);
        String fortyEight = Base64.getEncoder().encodeToString(new byte[48]);

        assertThatThrownBy(() -> ProviderApiKey.decode(sixteen, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
        assertThat(ProviderApiKey.decode(fortyEight, true)).hasSize(48);
    }
}
