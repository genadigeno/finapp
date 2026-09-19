package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.kyc.CallbackKey;
import com.finapp.app.kyc.DocumentKey;
import com.finapp.app.mfa.MfaKey;
import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The generalised confinement's own contract (`P5-TSK-002`) — specifically the
 * <strong>next-consumer</strong> half that no existing suite can cover.
 *
 * <p>The four existing guards' suites ({@code MfaKeyTest}, {@code DocumentKeyTest},
 * {@code CallbackKeyTest}, {@code DatabaseCredentialGuardTest}) are this refactor's equivalence
 * proof and were deliberately not edited; what they cannot say is that a <em>fifth</em>
 * credential — the payment provider's API key (`P5-TSK-003`) and webhook key (`P5-TSK-012`) are
 * the ones coming — gets the whole discipline from a one-line spec. This suite says it, with a
 * stand-in spec shaped like the webhook key's will be.
 */
@DisplayName("ConfinedCredential (P5-TSK-002)")
class ConfinedCredentialTest {

    /** A fifth credential's declaration, shaped like the payment webhook key's will be. */
    private static final KeySpec FIFTH =
            new KeySpec(
                    "payment webhook signing key",
                    "payment webhook signing key",
                    "FINAPP_PAYMENT_WEBHOOK_KEY",
                    "/payment-webhook",
                    KeyLength.AT_LEAST_32,
                    ".");

    @Test
    @DisplayName("a new spec's local default is domain-separated from all three existing keys")
    void aNewSpecIsDomainSeparatedFromEveryExistingKey() {
        // The property that makes one published marker safe across N credentials: no two
        // concerns ever share locally derived key bytes. Pairwise against every existing key,
        // because "different from one sibling" says nothing about the others.
        byte[] fifth = FIFTH.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true);
        assertThat(fifth)
                .isNotEqualTo(MfaKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true))
                .isNotEqualTo(DocumentKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true))
                .isNotEqualTo(CallbackKey.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, true));
    }

    @Test
    @DisplayName("a new spec inherits the confinement, and its refusal names its own variable")
    void aNewSpecInheritsTheConfinement() {
        assertThatThrownBy(() -> FIFTH.decode(ConfinedCredential.MARKED_LOCAL_DEFAULT, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payment webhook signing key")
                .hasMessageContaining("FINAPP_PAYMENT_WEBHOOK_KEY");
    }

    @Test
    @DisplayName("no refusal echoes the configured value - it is key material headed for a log")
    void refusalsNeverEchoTheValue() {
        // INV-AUD-02 at the mechanism, so a sixth credential cannot lose the property by
        // forgetting it: the malformed-base64 refusal is the one whose careless version would
        // print the value.
        String configured = "not/base64!!definitely-not";
        assertThatThrownBy(() -> FIFTH.decode(configured, true))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(configured));
    }

    @Test
    @DisplayName("both length rules are the mechanism's: exactly-32 refuses 16 (the AES-128 trap),"
            + " at-least-32 permits longer")
    void bothLengthRulesAreEnforced() {
        KeySpec exact =
                new KeySpec("probe key", "probe key", "FINAPP_PROBE", "/probe",
                        KeyLength.EXACTLY_32, ".");
        String sixteen = Base64.getEncoder().encodeToString(new byte[16]);
        String fortyEight = Base64.getEncoder().encodeToString(new byte[48]);

        assertThatThrownBy(() -> exact.decode(sixteen, true))
                .hasMessageContaining("exactly 32 bytes");
        assertThatThrownBy(() -> exact.decode(fortyEight, true))
                .hasMessageContaining("exactly 32 bytes");
        assertThatThrownBy(() -> FIFTH.decode(sixteen, true))
                .hasMessageContaining("at least 32 bytes");
        assertThat(FIFTH.decode(fortyEight, true)).hasSize(48);
    }

    @Test
    @DisplayName("the marker is the published string, and both public constants are it")
    void theMarkerIsThePublishedString() {
        // The reference chain: after this task the repository holds ONE Java literal of the
        // marker, and the two long-standing public constants are references to it. A drifted
        // reference would re-split the value this generalisation unified.
        assertThat(ConfinedCredential.MARKED_LOCAL_DEFAULT)
                .isEqualTo("local-development-only-not-a-secret");
        assertThat(MfaKey.MARKED_LOCAL_DEFAULT).isSameAs(ConfinedCredential.MARKED_LOCAL_DEFAULT);
        assertThat(DatabaseCredentialGuard.MARKED_LOCAL_DEFAULT)
                .isSameAs(ConfinedCredential.MARKED_LOCAL_DEFAULT);
    }
}
