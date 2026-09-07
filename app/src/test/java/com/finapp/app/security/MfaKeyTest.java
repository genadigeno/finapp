package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.mfa.MfaKey;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The MFA encryption key, and the one property that makes publishing a default safe
 * (`P1-TSK-017`, ADR-0020).
 *
 * <h2>Written because a mutation survived</h2>
 *
 * <p>Removing the loopback check left every test green: the marked default would have been accepted
 * against a production database, and `INV-IDN-08`'s <em>"a key that is not in the database"</em>
 * would have become <em>"a key every reader of this repository already has"</em>. That is worse than
 * no encryption, because the row would look protected.
 */
@DisplayName("MfaKey (P1-TSK-017)")
class MfaKeyTest {

    private static final String VALID = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    @DisplayName("the published default is refused when the database is not on loopback")
    void thePublishedDefaultIsConfinedToLoopback() {
        assertThatThrownBy(() -> MfaKey.decode(MfaKey.MARKED_LOCAL_DEFAULT, false))
                .isInstanceOf(IllegalStateException.class)
                // The message must say what to do. A refusal that only says "no" is a refusal
                // somebody works around by deleting the check.
                .hasMessageContaining("FINAPP_MFA_KEY")
                .hasMessageContaining("not a secret");
    }

    @Test
    @DisplayName("and permitted on a developer machine, so the refusal is not simply always")
    void thePublishedDefaultWorksLocally() {
        // The positive control. Without it the test above passes against an implementation that
        // refuses every key, which would make local development impossible and would still be green.
        assertThat(MfaKey.decode(MfaKey.MARKED_LOCAL_DEFAULT, true)).hasSize(32);
    }

    @Test
    @DisplayName("a real key is accepted wherever the database is")
    void aRealKeyIsAlwaysAccepted() {
        assertThat(MfaKey.decode(VALID, false)).hasSize(32);
        assertThat(MfaKey.decode(VALID, true)).hasSize(32);
    }

    @Test
    @DisplayName("a key of the wrong length is refused rather than stretched")
    void aShortKeyIsRefused() {
        // Silently padding or hashing a short key would produce a cipher that looks like AES-256
        // and is not - the shape of defect that passes every test and fails only to an attacker.
        assertThatThrownBy(
                        () -> MfaKey.decode(Base64.getEncoder().encodeToString(new byte[16]), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("a malformed key is refused without echoing it")
    void aMalformedKeyIsRefusedQuietly() {
        // The value is key material and this message reaches a log line (INV-AUD-02).
        assertThatThrownBy(() -> MfaKey.decode("not!base64!at!all", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("not!base64");
    }

    @Test
    @DisplayName("the published default is not itself a usable key by accident")
    void theMarkedDefaultIsNotValidBase64OfTheRightLength() {
        // If the marked string happened to decode to 32 bytes, the loopback branch could be
        // bypassed by sending it through the ordinary path. Asserted rather than assumed.
        assertThatThrownBy(() -> MfaKey.decode(MfaKey.MARKED_LOCAL_DEFAULT.toUpperCase(), false))
                .isInstanceOf(IllegalStateException.class);
    }
}
