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
 * The dispute-evidence key's own contract (`P7-TSK-014`) — the parts that are <em>this</em>
 * spec's, the mechanism itself being `ConfinedCredentialTest`'s subject; every credential class
 * gets a test (the `P2-TSK-011` lesson, standing).
 */
@DisplayName("DisputeEvidenceKey (P7-TSK-014)")
class DisputeEvidenceKeyTest {

    @Test
    @DisplayName("the local default is domain-separated from every sibling key, the payment"
            + " evidence key included")
    void locallyDomainSeparatedFromEverySibling() {
        byte[] disputes =
                DisputeEvidenceKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true);
        assertThat(disputes)
                .hasSize(32)
                .isNotEqualTo(
                        PaymentEvidenceKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true))
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
                                DisputeEvidenceKey.decode(
                                        ConfinedCredential.MARKED_LOCAL_DEFAULT, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dispute evidence encryption key")
                .hasMessageContaining("FINAPP_PAYMENTS_DISPUTE_EVIDENCE_KEY");
    }

    @Test
    @DisplayName("exactly 32 bytes: 16 and 48 refused - an AES key that is not 32 bytes is not"
            + " AES-256")
    void exactlyThirtyTwoBytes() {
        String sixteen = Base64.getEncoder().encodeToString(new byte[16]);
        String fortyEight = Base64.getEncoder().encodeToString(new byte[48]);

        assertThatThrownBy(() -> DisputeEvidenceKey.decode(sixteen, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 32 bytes");
        assertThatThrownBy(() -> DisputeEvidenceKey.decode(fortyEight, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 32 bytes");
        assertThat(
                        DisputeEvidenceKey.decode(
                                Base64.getEncoder().encodeToString(new byte[32]), true))
                .hasSize(32);
    }
}
