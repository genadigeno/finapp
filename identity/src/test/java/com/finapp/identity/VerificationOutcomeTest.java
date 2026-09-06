package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link VerificationOutcome} must give a caller nothing to branch on when it fails
 * (`P1-TSK-008`, {@code INV-IDN-07}).
 *
 * <p>Derived from the type rather than listed by hand, so a member added later is caught without
 * anybody remembering this test exists - the difference between a guard and a comment.
 */
@DisplayName("VerificationOutcome (P1-TSK-008)")
class VerificationOutcomeTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("a failure carries nothing at all")
    void aFailureCarriesNothing() {
        VerificationOutcome failure = VerificationOutcome.failed();

        assertThat(failure.isSuccess()).isFalse();
        assertThat(failure.identityId()).isEmpty();
    }

    @Test
    @DisplayName("two failures are the same object, so not even identity distinguishes them")
    void everyFailureIsTheSameFailure() {
        // A caller cannot tell an absent identity from a wrong password by comparing references
        // either. Belt and braces, and free.
        assertThat(VerificationOutcome.failed()).isSameAs(VerificationOutcome.failed());
    }

    @Test
    @DisplayName("the type declares no member that could carry a reason")
    void thereIsNowhereForAReasonToLive() {
        // The guard that survives a future author. Adding `Optional<String> reason` or
        // `IdentityStatus status` to this type is how INV-IDN-07 is lost - harmless the day it is
        // added, an enumeration oracle the day somebody maps it to a message.
        List<String> permittedFields = List.of("identityId", "FAILED");
        List<String> unexpectedFields =
                Arrays.stream(VerificationOutcome.class.getDeclaredFields())
                        .filter(field -> !field.isSynthetic())
                        .map(Field::getName)
                        .filter(name -> !permittedFields.contains(name))
                        .toList();

        assertThat(unexpectedFields)
                .as("a new member here is a new thing a caller can branch on")
                .isEmpty();

        List<String> permittedMethods = List.of("succeeded", "failed", "isSuccess", "identityId", "toString");
        List<String> unexpectedMethods =
                Arrays.stream(VerificationOutcome.class.getDeclaredMethods())
                        .filter(method -> !method.isSynthetic())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .map(Method::getName)
                        .distinct()
                        .filter(name -> !permittedMethods.contains(name))
                        .toList();

        assertThat(unexpectedMethods).isEmpty();
    }

    @Test
    @DisplayName("toString says whether it succeeded, and never why it failed")
    void toStringRevealsNoReason() {
        assertThat(VerificationOutcome.failed().toString())
                .isEqualTo("VerificationOutcome[failed]");

        IdentityId identity = IdentityId.next(IDS);
        assertThat(VerificationOutcome.succeeded(identity).toString()).contains(identity.toString());
    }

    @Test
    @DisplayName("success carries the identity, because the caller cannot get it another way")
    void successCarriesTheIdentity() {
        IdentityId identity = IdentityId.next(IDS);

        assertThat(VerificationOutcome.succeeded(identity).identityId()).contains(identity);
    }
}
