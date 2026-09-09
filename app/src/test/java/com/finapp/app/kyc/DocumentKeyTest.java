package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.finapp.app.mfa.MfaKey;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The document key's confinement (`P2-TSK-008`) — the third per-credential loopback guard, and
 * each assertion here exists because `P1-TSK-017` found {@code MfaKey}'s absence of a test let
 * the confinement vanish silently.
 */
@DisplayName("DocumentKey (P2-TSK-008)")
class DocumentKeyTest {

    @Test
    @DisplayName("the published local default is confined to loopback")
    void theLocalDefaultIsConfined() {
        // The default is not a secret: every reader of this repository has it. Anywhere but a
        // developer's machine it must stop the application, not encrypt real passports.
        assertThatIllegalStateException()
                .isThrownBy(() -> DocumentKey.decode(MfaKey.MARKED_LOCAL_DEFAULT, false))
                .withMessageContaining("FINAPP_DOC_KEY");

        assertThat(DocumentKey.decode(MfaKey.MARKED_LOCAL_DEFAULT, true)).hasSize(32);
    }

    @Test
    @DisplayName("the local document key is not the local MFA key")
    void theLocalKeysAreDomainSeparated() {
        // One key per concern (ADR-0036) should hold locally too - the tamper and wrong-key
        // tests run against these bytes, and two concerns quietly sharing them would make
        // "written under a different key" untestable between the two stores.
        assertThat(DocumentKey.decode(MfaKey.MARKED_LOCAL_DEFAULT, true))
                .isNotEqualTo(MfaKey.decode(MfaKey.MARKED_LOCAL_DEFAULT, true));
    }

    @Test
    @DisplayName("a configured key must be base64 of exactly 32 bytes")
    void configuredKeysAreValidated() {
        assertThatIllegalStateException()
                .isThrownBy(() -> DocumentKey.decode("not-base64!!", true));
        assertThatIllegalStateException()
                .isThrownBy(
                        () ->
                                DocumentKey.decode(
                                        Base64.getEncoder().encodeToString(new byte[16]), false));

        assertThat(
                        DocumentKey.decode(
                                Base64.getEncoder().encodeToString(new byte[32]), false))
                .hasSize(32);
    }
}
