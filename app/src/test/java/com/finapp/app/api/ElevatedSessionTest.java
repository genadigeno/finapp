package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.mfa.ElevatedSession;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The half of `secretsAreWrapped` that still applies to an exempt field (`P1-TSK-018`).
 *
 * <h2>Written by the completion gate, because the exemption claimed it</h2>
 *
 * <p>`NoUnwrappedSecretRulesTest`'s exemption javadoc said *"the `toString` harm is closed by an
 * override, and `ElevatedSessionTest` asserts it"* - and no such test existed. That is the pattern
 * this repository keeps meeting: a comment naming a test that does not exist, after `V005`'s enum
 * claim, `AuthenticationRequest`'s bounds claim and `RequiresSession`'s fail-closed claim.
 *
 * <p>It matters more here than in those cases. **An exemption is a claim that a guard's subject is
 * safe by other means**, and if those other means are imaginary the exemption is simply a hole with
 * a paragraph in front of it.
 */
@DisplayName("ElevatedSession masks its token (P1-TSK-018)")
class ElevatedSessionTest {

    private static final String TOKEN = "a-live-session-token-nobody-should-see";

    @Test
    @DisplayName("toString masks the token")
    void toStringMasksTheToken() {
        ElevatedSession elevated =
                new ElevatedSession(TOKEN, "MULTI_FACTOR", Instant.parse("2026-09-07T12:00:00Z"));

        // A record's GENERATED toString prints every component, which is how a live session token
        // reaches a log line with nobody writing a log statement about it - no getter call, no
        // concatenation, nothing a reviewer stops at (INV-AUD-02).
        assertThat(elevated.toString())
                .as("the exemption permits serialisation, never logging")
                .doesNotContain(TOKEN)
                .contains(com.finapp.sharedkernel.security.Sensitive.MASK);
    }

    @Test
    @DisplayName("interpolation and concatenation mask it too")
    void everyRenderingPathMasks() {
        ElevatedSession elevated =
                new ElevatedSession(TOKEN, "MULTI_FACTOR", Instant.parse("2026-09-07T12:00:00Z"));

        // The paths a log statement actually takes. `Sensitive` exists because these three are how
        // a value escapes without anybody deciding to emit it (P0-TSK-030).
        assertThat("" + elevated).doesNotContain(TOKEN);
        assertThat(String.format("%s", elevated)).doesNotContain(TOKEN);
        assertThat(String.valueOf(elevated)).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("the accessor still returns it, because that is what the exemption is for")
    void theAccessorIsDeliberatelyUnmasked() {
        ElevatedSession elevated =
                new ElevatedSession(TOKEN, "MULTI_FACTOR", Instant.parse("2026-09-07T12:00:00Z"));

        // The positive control, and it is the point rather than a caveat: elevation ROTATES the
        // identifier, so a response that could not carry the replacement would log the customer out
        // at the moment they proved a second factor.
        assertThat(elevated.sessionToken()).isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("the other members are still rendered, so the masking is not a blanket refusal")
    void theRestIsStillLegible() {
        // A toString that said nothing at all would satisfy the assertions above and be useless for
        // diagnosis. Masking is targeted at the one member that must not be printed.
        assertThat(new ElevatedSession(TOKEN, "MULTI_FACTOR", Instant.parse("2026-09-07T12:00:00Z"))
                        .toString())
                .contains("MULTI_FACTOR")
                .contains("2026-09-07T12:00:00Z");
    }
}
