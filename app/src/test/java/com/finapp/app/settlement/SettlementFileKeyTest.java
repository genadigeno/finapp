package com.finapp.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.kyc.DocumentKey;
import com.finapp.app.mfa.MfaKey;
import com.finapp.app.payments.DisputeEvidenceKey;
import com.finapp.app.payments.PaymentEvidenceKey;
import com.finapp.app.security.ConfinedCredential;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The settlement-file key's own contract (`P8-TSK-002`) — the parts that are <em>this</em>
 * spec's, the mechanism itself being `ConfinedCredentialTest`'s subject; every credential
 * class gets a test (the `P2-TSK-011` lesson, standing).
 */
@DisplayName("SettlementFileKey (P8-TSK-002)")
class SettlementFileKeyTest {

    @Test
    @DisplayName("the local default is domain-separated from every sibling evidence key")
    void locallyDomainSeparatedFromEverySibling() {
        byte[] settlement =
                SettlementFileKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true);
        assertThat(settlement)
                .hasSize(32)
                .isNotEqualTo(
                        PaymentEvidenceKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true))
                .isNotEqualTo(
                        DisputeEvidenceKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true))
                .isNotEqualTo(DocumentKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true))
                .isNotEqualTo(MfaKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true));
    }

    @Test
    @DisplayName("the confinement is inherited, and the refusal names this credential's variable")
    void confinementIsInherited() {
        assertThatThrownBy(
                        () ->
                                SettlementFileKey.decode(
                                        ConfinedCredential.MARKED_LOCAL_DEFAULT, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("settlement file encryption key")
                .hasMessageContaining("FINAPP_SETTLEMENT_FILE_KEY");
    }

    @Test
    @DisplayName("a configured key must decode to exactly 32 bytes - EXACTLY_32, never"
            + " stretched")
    void exactlyThirtyTwoBytes() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> SettlementFileKey.decode(tooShort, true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        SettlementFileKey.decode(
                                Base64.getEncoder().encodeToString(new byte[32]), true))
                .hasSize(32);
    }
}
