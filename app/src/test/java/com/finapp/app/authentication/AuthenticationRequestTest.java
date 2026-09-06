package com.finapp.app.authentication;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.LoginIdentifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The boundary's bounds are the domain type's bounds (`P1-TSK-010`).
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@link AuthenticationRequest} restates {@code LoginIdentifier}'s length and charset as
 * literals, because an annotation needs a compile-time constant. Its javadoc claimed <em>"a test
 * asserts they still match"</em> and <strong>no such test existed</strong> - found by the completion
 * gate, while the sibling {@code RegistrationRequestTest} had one all along. A documented claim
 * about a test that does not exist is the shape {@code DEFINITION_OF_DONE.md} §3 forbids, and it is
 * worse than a missing test because the next reader stops looking.
 *
 * <p>The drift it guards against is not cosmetic. If the domain charset narrowed, the boundary would
 * accept a value the domain then refuses, and {@code new LoginIdentifier(...)} would throw an
 * {@code IllegalArgumentException} inside the service - surfacing as {@code api.InternalError}: our
 * fault reported for the caller's own input, which {@code ERROR_CONTRACT.md} §3 forbids and which a
 * client may retry for ever on a request that can never succeed.
 */
@DisplayName("AuthenticationRequest bounds (P1-TSK-010)")
class AuthenticationRequestTest {

    @Test
    @DisplayName("the declared bounds are the domain type's bounds")
    void boundsMatchTheDomainType() {
        assertThat(AuthenticationRequest.LOGIN_MIN).isEqualTo(LoginIdentifier.MIN_LENGTH);
        assertThat(AuthenticationRequest.LOGIN_MAX).isEqualTo(LoginIdentifier.MAX_LENGTH);
    }

    @Test
    @DisplayName("everything the boundary charset admits, the domain type also admits")
    void theBoundaryIsNoWiderThanTheDomain() {
        // Swept rather than sampled: the failure is one character the boundary lets through and the
        // domain rejects, and nobody guesses in advance which character that will be.
        for (int codePoint = 0; codePoint < 0x250; codePoint++) {
            String value = "ab" + (char) codePoint;
            if (!value.matches(AuthenticationRequest.LOGIN_CHARSET)) {
                continue;
            }
            assertThat(domainAccepts(value))
                    .as(
                            "the boundary admits U+%04X and the domain refuses it, which is a 500"
                                + " for the caller's own input",
                            codePoint)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the sweep is not vacuous: it really admits and really refuses")
    void theSweepIsNotVacuous() {
        // Without this the loop above would pass having admitted nothing at all.
        assertThat("abc".matches(AuthenticationRequest.LOGIN_CHARSET)).isTrue();
        assertThat("ada@example.com".matches(AuthenticationRequest.LOGIN_CHARSET))
                .as("an email address is refused at the boundary, as it is for registration")
                .isFalse();
    }

    private static boolean domainAccepts(String value) {
        try {
            new LoginIdentifier(value);
            return true;
        } catch (RuntimeException refused) {
            return false;
        }
    }
}
