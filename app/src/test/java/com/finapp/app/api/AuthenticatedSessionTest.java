package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.authentication.AuthenticatedSession;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The half of {@code secretsAreWrapped} that still applies to an exempt field (`P1-TSK-027`).
 *
 * <h2>Written with the exemption, not after somebody noticed it was unbacked</h2>
 *
 * <p>{@code P1-TSK-018} added the first exemption to {@code secretsAreWrapped} and its completion
 * gate found the javadoc claiming <em>"the {@code toString} harm is closed by an override, and
 * {@code ElevatedSessionTest} asserts it"</em> when no such test existed. <strong>An exemption is a
 * claim that a guard's subject is safe by other means, so if those means are imaginary the exemption
 * is a hole with a paragraph in front of it.</strong>
 *
 * <p>This is that lesson applied rather than repeated: the second exemption arrives with its test.
 * Deliberately a second suite rather than a parameterised one over both records - the two are
 * separate decisions about separate response types, and a shared test would report one number for
 * two claims.
 */
@DisplayName("AuthenticatedSession masks its token (P1-TSK-027)")
class AuthenticatedSessionTest {

    private static final String TOKEN = "a-live-session-token-nobody-should-see";
    private static final Instant EXPIRY = Instant.parse("2026-09-08T12:00:00Z");

    @Test
    @DisplayName("toString masks the token")
    void toStringMasksTheToken() {
        AuthenticatedSession session = new AuthenticatedSession(TOKEN, "PASSWORD", EXPIRY);

        // A record's GENERATED toString prints every component, which is how a live session token
        // reaches a log line with nobody writing a log statement about it - no getter call, no
        // concatenation, nothing a reviewer stops at (INV-AUD-02). And this token is the one a
        // customer's browser will hold for the next hour.
        assertThat(session.toString())
                .as("the exemption permits serialisation, never logging")
                .doesNotContain(TOKEN)
                .contains(com.finapp.sharedkernel.security.Sensitive.MASK);
    }

    @Test
    @DisplayName("interpolation and concatenation mask it too")
    void everyRenderingPathMasks() {
        AuthenticatedSession session = new AuthenticatedSession(TOKEN, "PASSWORD", EXPIRY);

        // The paths a log statement actually takes. `Sensitive` exists because these three are how
        // a value escapes without anybody deciding to emit it (P0-TSK-030).
        assertThat("" + session).doesNotContain(TOKEN);
        assertThat(String.format("%s", session)).doesNotContain(TOKEN);
        assertThat(String.valueOf(session)).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("the accessor still returns it, because that is what the exemption is for")
    void theAccessorIsDeliberatelyUnmasked() {
        // The positive control, and it is the point rather than a caveat: a login that could not
        // hand back the token would leave the customer with a session they cannot use, which is the
        // gap the Phase 1 review found in a different form.
        assertThat(new AuthenticatedSession(TOKEN, "PASSWORD", EXPIRY).sessionToken())
                .isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("the other members are still rendered, so the masking is not a blanket refusal")
    void theRestIsStillLegible() {
        // A toString that said nothing at all would satisfy the assertions above and be useless for
        // diagnosis. Masking is targeted at the one member that must not be printed.
        assertThat(new AuthenticatedSession(TOKEN, "PASSWORD", EXPIRY).toString())
                .contains("PASSWORD")
                .contains("2026-09-08T12:00:00Z");
    }
}
