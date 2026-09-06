package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The credential state machine, rejected by the aggregate ({@code INV-LIFE-02}, {@code INV-LIFE-04}).
 *
 * <p>Every state pair is enumerated <strong>from the machine</strong> rather than listed by hand, so
 * adding a state cannot leave a pair untested - the {@code CustomerLifecycleTest} convention.
 */
@DisplayName("Credential lifecycle (P1-TSK-007)")
class CredentialLifecycleTest {

    private static final Instant FIXED = Instant.parse("2026-09-06T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    /** Cheap: this suite is about the state machine, not about Argon2's cost. */
    private static final PasswordDeriver DERIVER =
            new Argon2PasswordDeriver(new DerivationParameters(1024, 1, 1));

    private static final RawPassword PASSWORD = RawPassword.of("correct horse battery staple");

    /** A well-formed Argon2id encoded string. Not a real derivation - no password produced it. */
    static final String ENCODED =
            "$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHR2YWx1ZQ$"
                    + "Zm9ydHktdHdvLWJ5dGVzLW9mLW1hZGUtdXAtZGVyaXZhdGlvbg";

    @Test
    @DisplayName("every state pair behaves exactly as the machine declares")
    void everyPairMatchesTheMachine() {
        for (CredentialStatus from : CredentialStatus.values()) {
            for (CredentialStatus to : CredentialStatus.values()) {
                boolean permitted = from.permittedTransitions().contains(to);
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(permitted);
            }
        }
    }

    @Test
    @DisplayName("the machine has exactly one terminal state, and it is SUPERSEDED")
    void supersededIsTheOnlyTerminalState() {
        assertThat(CredentialStatus.values())
                .filteredOn(CredentialStatus::isTerminal)
                .containsExactly(CredentialStatus.SUPERSEDED);
    }

    @Test
    @DisplayName("a credential is created ACTIVE and supersedes once")
    void aCredentialSupersedesOnce() {
        Credential credential = active();

        assertThat(credential.isActive()).isTrue();
        assertThat(credential.supersededAt()).isEmpty();

        Credential superseded = credential.supersede(CLOCK);

        assertThat(superseded.status()).isEqualTo(CredentialStatus.SUPERSEDED);
        assertThat(superseded.isActive()).isFalse();
        assertThat(superseded.supersededAt()).contains(FIXED);
        assertThat(superseded.id()).as("the same credential, in a later state").isEqualTo(credential.id());
    }

    @Test
    @DisplayName("superseding twice raises rather than being a quiet no-op")
    void supersedingTwiceRaises() {
        // INV-LIFE-04. A caller that believes it is retiring a credential retired months ago has a
        // defect worth a stack trace; a silently idempotent supersede would hide it.
        Credential superseded = active().supersede(CLOCK);

        assertThatThrownBy(() -> superseded.supersede(CLOCK))
                .isInstanceOf(IllegalCredentialTransitionException.class);
    }

    @Test
    @DisplayName("a rejected transition leaves the credential untouched")
    void aRejectedTransitionChangesNothing() {
        // The aggregate is immutable, so a rejected transition cannot leave a half-changed object
        // behind. Asserting it is what keeps that true if the implementation ever stops being.
        Credential superseded = active().supersede(CLOCK);
        Instant when = superseded.supersededAt().orElseThrow();

        assertThatThrownBy(() -> superseded.supersede(Clock.offset(CLOCK, Duration.ofHours(1))))
                .isInstanceOf(IllegalCredentialTransitionException.class);

        assertThat(superseded.status()).isEqualTo(CredentialStatus.SUPERSEDED);
        assertThat(superseded.supersededAt()).contains(when);
    }

    @Test
    @DisplayName("the exception names both states, so a caller need not parse the message")
    void theExceptionCarriesItsStates() {
        Credential superseded = active().supersede(CLOCK);

        assertThatThrownBy(() -> superseded.supersede(CLOCK))
                .asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.type(
                                IllegalCredentialTransitionException.class))
                .satisfies(
                        e -> {
                            assertThat(e.from()).isEqualTo(CredentialStatus.SUPERSEDED);
                            assertThat(e.to()).isEqualTo(CredentialStatus.SUPERSEDED);
                        });
    }

    @Test
    @DisplayName("status and supersededAt must agree, in both directions")
    void statusAndTimestampMustAgree() {
        // The same rule the column enforces. Here so a caller gets a domain error rather than a
        // constraint violation from three layers down.
        assertThatThrownBy(
                        () ->
                                Credential.rehydrate(
                                        CredentialId.next(IDS),
                                        IdentityId.next(IDS),
                                        CredentialType.PASSWORD,
                                        CredentialAlgorithm.ARGON2ID,
                                        DerivationParameters.current(),
                                        Sensitive.of(ENCODED),
                                        CredentialStatus.SUPERSEDED,
                                        FIXED,
                                        null))
                .as("superseded with no timestamp cannot be aged or explained")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(
                        () ->
                                Credential.rehydrate(
                                        CredentialId.next(IDS),
                                        IdentityId.next(IDS),
                                        CredentialType.PASSWORD,
                                        CredentialAlgorithm.ARGON2ID,
                                        DerivationParameters.current(),
                                        Sensitive.of(ENCODED),
                                        CredentialStatus.ACTIVE,
                                        FIXED,
                                        FIXED))
                .as("active with a supersession timestamp is a contradiction")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a derivation that is not in the algorithm's encoded form is refused")
    void anUnencodedDerivationIsRefused() {
        // INV-IDN-01's domain half. A value that is not encoded is either a plaintext or the output
        // of a function nobody recorded; neither may become a row.
        assertThatThrownBy(() -> derivedWith("hunter2hunter2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> derivedWith("$2a$10$notargon2"))
                .as("another algorithm's encoded form is not this algorithm's")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatCode(() -> derivedWith(ENCODED)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a credential cannot misdeclare its own strength (INV-IDN-02)")
    void theRecordedParametersAreTheOnesUsed() {
        // The completion gate's finding, made permanent. An earlier factory took the algorithm, the
        // parameters and the finished derivation as three independent arguments, and a caller could
        // record parameters that were not the ones used - columns saying m=19456,t=2,p=1 over a
        // derivation produced at m=1024,t=1,p=1, with isWeakerThan(current()) answering false.
        //
        // That is INV-IDN-02 satisfied in form and defeated in substance: a credential that
        // misreports its strength is WORSE than one recording nothing, because an upgrade campaign
        // skips it while believing it was assessed. `forPassword` takes the deriver, so the three
        // facts come from one place and there is no argument left to get wrong.
        Credential credential =
                Credential.forPassword(
                        IDS, CLOCK, IdentityId.next(IDS), CredentialType.PASSWORD, DERIVER, PASSWORD);

        assertThat(credential.parameters())
                .as("the recorded parameters are the deriver's own")
                .isEqualTo(DERIVER.currentParameters());
        assertThat(DERIVER.parametersOf(credential.credentialDerivation()))
                .as("and they are the ones actually inside the derivation")
                .isEqualTo(credential.parameters());
        assertThat(credential.algorithm()).isEqualTo(DERIVER.algorithm());

        assertThat(credential.isWeakerThan(DerivationParameters.current()))
                .as("a cheaply-derived credential reports itself as weaker, which is the whole point")
                .isTrue();
    }

    @Test
    @DisplayName("the refusal does not repeat the value it refused")
    void theRefusalDoesNotEchoTheValue() {
        // The rejected value is very often the plaintext - that is the case this check exists for -
        // and an exception message reaches a log line (INV-AUD-02).
        assertThatThrownBy(() -> derivedWith("hunter2hunter2"))
                .hasMessageNotContaining("hunter2");
    }

    // -----------------------------------------------------------------

    static Credential active() {
        return Credential.forPassword(
                IDS, CLOCK, IdentityId.next(IDS), CredentialType.PASSWORD, DERIVER, PASSWORD);
    }

    /**
     * Builds a credential around an arbitrary derivation, through {@code rehydrate}.
     *
     * <p>{@code forPassword} takes a deriver and therefore cannot be handed a bad derivation - which
     * is the point of it. Testing the encoded-form check therefore has to go through the
     * reconstitution path, which is also the one a row reaches the domain by.
     */
    private static Credential derivedWith(String derivation) {
        return Credential.rehydrate(
                CredentialId.next(IDS),
                IdentityId.next(IDS),
                CredentialType.PASSWORD,
                CredentialAlgorithm.ARGON2ID,
                DerivationParameters.current(),
                Sensitive.of(derivation),
                CredentialStatus.ACTIVE,
                FIXED,
                null);
    }
}
