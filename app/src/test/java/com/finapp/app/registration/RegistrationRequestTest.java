package com.finapp.app.registration;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.LoginIdentifier;
import com.finapp.party.PartyName;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The boundary contract and the domain types must agree (`P1-TSK-006`).
 *
 * <p>{@link RegistrationRequest} restates {@code LoginIdentifier}'s and {@code PartyName}'s bounds
 * as annotation constants, because an annotation needs a compile-time constant. That duplication is
 * only safe while it is checked: if the domain type tightened and the boundary did not, a value the
 * boundary accepted would blow up three layers down as an {@code IllegalArgumentException} and be
 * rendered {@code api.InternalError} - our fault, reported for the caller's mistake.
 */
@DisplayName("RegistrationRequest bounds (P1-TSK-006)")
class RegistrationRequestTest {

    @Test
    @DisplayName("the declared bounds are the domain types' bounds")
    void theBoundsAgree() {
        assertThat(RegistrationRequest.LOGIN_MIN).isEqualTo(LoginIdentifier.MIN_LENGTH);
        assertThat(RegistrationRequest.LOGIN_MAX).isEqualTo(LoginIdentifier.MAX_LENGTH);
        assertThat(RegistrationRequest.NAME_MAX).isEqualTo(PartyName.MAX_LENGTH);
    }

    @Test
    @DisplayName("everything the boundary charset admits, the domain type also admits")
    void theCharsetsAgree() {
        // One direction only, and deliberately: the boundary is case-insensitive because a person
        // types a capital, and LoginIdentifier lower-cases it. What must never happen is the
        // reverse - the boundary letting through something the domain type rejects.
        for (char candidate = 0x20; candidate < 0x7f; candidate++) {
            String value = "ab" + candidate;
            boolean boundaryAccepts = value.matches(RegistrationRequest.LOGIN_CHARSET);
            if (!boundaryAccepts) {
                continue;
            }
            assertThat(catching(() -> new LoginIdentifier(value)))
                    .as("the boundary accepts '%s' so the domain type must too", candidate)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("an email address is refused at the boundary, not only in the domain")
    void anEmailAddressIsRefusedAtTheBoundary() {
        // The rule PHASE_1_PLAN.md section 4 states. Refusing it here means api.ValidationFailed
        // rather than an exception from the domain type surfacing as api.InternalError.
        assertThat("ada@example.com".matches(RegistrationRequest.LOGIN_CHARSET)).isFalse();
    }

    @Test
    @DisplayName("a login identifier long enough to be interesting is still within both bounds")
    void aRealisticIdentifierFits() {
        String realistic = "u" + UUID.randomUUID().toString().replace("-", "");
        assertThat(realistic.length()).isBetween(LoginIdentifier.MIN_LENGTH, LoginIdentifier.MAX_LENGTH);
        assertThat(realistic.matches(RegistrationRequest.LOGIN_CHARSET)).isTrue();
    }

    private static boolean catching(Runnable work) {
        try {
            work.run();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
