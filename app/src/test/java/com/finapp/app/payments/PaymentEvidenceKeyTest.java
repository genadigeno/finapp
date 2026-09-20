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
 * The sixth credential's own contract (`P5-TSK-009`) — the parts that are <em>this</em> spec's,
 * the mechanism itself being `ConfinedCredentialTest`'s subject; every credential class gets a
 * test, declaration-sized or not (the `P2-TSK-011` lesson, standing).
 */
@DisplayName("PaymentEvidenceKey (P5-TSK-009)")
class PaymentEvidenceKeyTest {

    @Test
    @DisplayName("the local default is domain-separated from every existing key, pairwise")
    void locallyDomainSeparatedFromEverySibling() {
        byte[] evidence =
                PaymentEvidenceKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true);
        assertThat(evidence)
                .hasSize(32)
                .isNotEqualTo(MfaKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true))
                .isNotEqualTo(DocumentKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true))
                .isNotEqualTo(CallbackKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true))
                .isNotEqualTo(
                        ProviderApiKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true));
    }

    @Test
    @DisplayName("the confinement is inherited, and the refusal names this credential's variable")
    void confinementIsInherited() {
        assertThatThrownBy(
                        () ->
                                PaymentEvidenceKey.decode(
                                        ConfinedCredential.MARKED_LOCAL_DEFAULT, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payment evidence encryption key")
                .hasMessageContaining("FINAPP_PAYMENT_EVIDENCE_KEY");
    }

    @Test
    @DisplayName("exactly 32 bytes: 16 and 48 refused - an AES key that is not 32 bytes is not AES-256")
    void exactlyThirtyTwoBytes() {
        String sixteen = Base64.getEncoder().encodeToString(new byte[16]);
        String fortyEight = Base64.getEncoder().encodeToString(new byte[48]);

        assertThatThrownBy(() -> PaymentEvidenceKey.decode(sixteen, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 32 bytes");
        assertThatThrownBy(() -> PaymentEvidenceKey.decode(fortyEight, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 32 bytes");
        assertThat(
                        PaymentEvidenceKey.decode(
                                Base64.getEncoder().encodeToString(new byte[32]), true))
                .hasSize(32);
    }
}
