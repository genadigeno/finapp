package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.finapp.app.mfa.MfaKey;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The callback signing key's confinement (`P2-TSK-011`) — the fourth per-credential loopback
 * guard, and each assertion exists because `P1-TSK-017` found {@code MfaKey}'s absence of a
 * test let the confinement vanish silently. Found by this task's own gate rather than carried:
 * the fourth hand-written instance of the shape must not be the first untested one.
 */
@DisplayName("CallbackKey (P2-TSK-011)")
class CallbackKeyTest {

    @Test
    @DisplayName("the published local default is confined to loopback")
    void theLocalDefaultIsConfined() {
        // The default is not a secret: every reader of this repository has it, and a callback
        // endpoint it signs would accept anybody's check outcomes - including a clear on their
        // own sanctions screening. Anywhere but a developer's machine it must stop the app.
        assertThatIllegalStateException()
                .isThrownBy(() -> CallbackKey.decode(MfaKey.MARKED_LOCAL_DEFAULT, false))
                .withMessageContaining("FINAPP_KYC_CALLBACK_KEY");

        assertThat(CallbackKey.decode(MfaKey.MARKED_LOCAL_DEFAULT, true)).hasSize(32);
    }

    @Test
    @DisplayName("the local callback key is neither the MFA key nor the document key")
    void theLocalKeysAreDomainSeparated() {
        // One key per concern should hold locally too: two concerns quietly sharing bytes
        // would let a reader of one key forge for the other - the DocumentKey reasoning.
        byte[] callback = CallbackKey.decode(MfaKey.MARKED_LOCAL_DEFAULT, true);

        assertThat(callback).isNotEqualTo(MfaKey.decode(MfaKey.MARKED_LOCAL_DEFAULT, true));
        assertThat(callback).isNotEqualTo(DocumentKey.decode(MfaKey.MARKED_LOCAL_DEFAULT, true));
    }

    @Test
    @DisplayName("a configured key must be base64 of AT LEAST 32 bytes - longer is permitted")
    void configuredKeysAreValidated() {
        assertThatIllegalStateException()
                .isThrownBy(() -> CallbackKey.decode("not-base64!!", true));
        assertThatIllegalStateException()
                .isThrownBy(
                        () ->
                                CallbackKey.decode(
                                        Base64.getEncoder().encodeToString(new byte[16]), false));

        // At least, not exactly, and the difference from DocumentKey is deliberate: HMAC
        // accepts any key length (RFC 2104 recommends >= the digest size), and unlike an AES
        // key there is no valid-but-weaker interpretation of a longer one to guard against.
        assertThat(CallbackKey.decode(Base64.getEncoder().encodeToString(new byte[32]), false))
                .hasSize(32);
        assertThat(CallbackKey.decode(Base64.getEncoder().encodeToString(new byte[48]), false))
                .hasSize(48);
    }
}
