package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.security.Sensitive;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The real Argon2id deriver: what it produces, and what cannot be got back out of it.
 *
 * <h2>The real encoder, at reduced parameters</h2>
 *
 * <p>Most tests here use deliberately cheap parameters, because the property under test is the
 * <em>shape</em> of what is produced and the shipped cost would make the suite slow for no extra
 * assurance. <strong>The reduction is never the production configuration</strong>, and one test
 * asserts exactly that: {@code .claude/rules/security.md} forbids weakening a control to make a
 * test pass, and a cheap parameter set that leaked into {@code DerivationParameters.current()} is
 * precisely that failure.
 */
@DisplayName("Argon2PasswordDeriver (P1-TSK-007)")
class Argon2PasswordDeriverTest {

    /** Cheap, for speed. Never the shipped values - see {@link #theShippedParametersAreNotTheTestOnes}. */
    private static final DerivationParameters CHEAP = new DerivationParameters(1024, 1, 1);

    private static final PasswordDeriver DERIVER = new Argon2PasswordDeriver(CHEAP);

    private static final String PLAINTEXT = "correct horse battery staple";

    @Test
    @DisplayName("the derivation contains nothing of the password (INV-IDN-01)")
    void theDerivationRevealsNothing() {
        Sensitive<String> derivation = DERIVER.derive(RawPassword.of(PLAINTEXT));
        String encoded = derivation.expose();

        assertThat(encoded).doesNotContain(PLAINTEXT);
        for (String word : PLAINTEXT.split(" ")) {
            assertThat(encoded)
                    .as("no fragment of the password survives into the derivation")
                    .doesNotContain(word);
        }
        assertThat(encoded).startsWith(CredentialAlgorithm.ARGON2ID.derivationPrefix());
    }

    @Test
    @DisplayName("the same password derives differently every time, so a derivation is never a lookup key")
    void everyDerivationIsSalted() {
        // The property that makes a rainbow table useless, and the one that makes it impossible to
        // find "everyone who uses this password" by grouping on the column. It is the library's
        // salt generation doing this, which is the reason ADR-0032 forbids writing one.
        Sensitive<String> first = DERIVER.derive(RawPassword.of(PLAINTEXT));
        Sensitive<String> second = DERIVER.derive(RawPassword.of(PLAINTEXT));

        assertThat(first.expose()).isNotEqualTo(second.expose());
        assertThat(DERIVER.matches(RawPassword.of(PLAINTEXT), first)).isTrue();
        assertThat(DERIVER.matches(RawPassword.of(PLAINTEXT), second)).isTrue();
    }

    @Test
    @DisplayName("a derivation verifies against its own password and no other")
    void verificationIsCorrect() {
        Sensitive<String> derivation = DERIVER.derive(RawPassword.of(PLAINTEXT));

        assertThat(DERIVER.matches(RawPassword.of(PLAINTEXT), derivation)).isTrue();
        assertThat(DERIVER.matches(RawPassword.of("correct horse battery stapl"), derivation))
                .as("one character short is a different password")
                .isFalse();
        assertThat(DERIVER.matches(RawPassword.of("Correct horse battery staple"), derivation))
                .as("case matters")
                .isFalse();
    }

    @Test
    @DisplayName("the parameters recorded on the credential are the ones inside the derivation")
    void theQueryableParametersMatchTheEncodedOnes() {
        // ADR-0032 Option D stores the parameters twice on purpose - inside the encoded form for
        // verification, as columns for reporting. Duplication that nothing reconciles is drift
        // waiting to happen, so this is the reconciliation.
        Sensitive<String> derivation = DERIVER.derive(RawPassword.of(PLAINTEXT));

        assertThat(DERIVER.parametersOf(derivation)).isEqualTo(DERIVER.currentParameters());
    }

    @Test
    @DisplayName("the constructor's argument order is not transposed")
    void theArgumentOrderIsCorrect() {
        // The library's constructor is (saltLength, hashLength, parallelism, memory, iterations),
        // which is easy to get wrong and impossible to notice: transposed values still produce a
        // valid derivation that still verifies. Reading them back out of a real derivation is the
        // only check that catches it.
        DerivationParameters distinct = new DerivationParameters(2048, 3, 1);

        DerivationParameters readBack =
                new Argon2PasswordDeriver(distinct)
                        .parametersOf(
                                new Argon2PasswordDeriver(distinct).derive(RawPassword.of(PLAINTEXT)));

        assertThat(readBack.memoryKib()).isEqualTo(2048);
        assertThat(readBack.iterations()).isEqualTo(3);
        assertThat(readBack.parallelism()).isEqualTo(1);
    }

    @Test
    @DisplayName("the shipped parameters are the policy, not this test's cheap ones")
    void theShippedParametersAreNotTheTestOnes() {
        // The guard against a reduction leaking into production. Pinned rather than compared with
        // something, so raising the work factor is a deliberate, reviewable edit to two places.
        DerivationParameters shipped = DerivationParameters.current();

        assertThat(shipped.memoryKib()).isEqualTo(19456);
        assertThat(shipped.iterations()).isEqualTo(2);
        assertThat(shipped.parallelism()).isEqualTo(1);
        assertThat(CHEAP.isWeakerThan(shipped))
                .as("the test parameters must be weaker, or this test proves nothing")
                .isTrue();
    }

    @Test
    @DisplayName("a value that is not an encoded derivation is refused, and not echoed")
    void aMalformedDerivationIsRefused() {
        assertThatThrownBy(() -> DERIVER.parametersOf(Sensitive.of("hunter2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("hunter2");
    }

    @Test
    @DisplayName("the shipped parameters cost something, and the measured cost is recorded")
    void theShippedCostIsMeasured() {
        // ADR-0032 asks for parameters chosen against a STATED verification time, and a stated time
        // nobody measured is not stated. This measures it and prints it; the number is recorded in
        // the ADR follow-up.
        //
        // The assertion is a floor, not a ceiling. A ceiling would be a flaky test on a loaded
        // machine, whereas a derivation that completes in under a millisecond means the parameters
        // are not doing their job - which is the failure actually worth catching.
        PasswordDeriver shipped = new Argon2PasswordDeriver(DerivationParameters.current());
        RawPassword password = RawPassword.of(PLAINTEXT);
        shipped.derive(password); // Warm the JIT, so the measurement is of Argon2 and not of startup.

        Instant start = Instant.now();
        Sensitive<String> derivation = shipped.derive(password);
        Duration elapsed = Duration.between(start, Instant.now());

        System.out.println(
                "MEASURED argon2id m=19456 t=2 p=1 derivation took " + elapsed.toMillis() + " ms");

        assertThat(shipped.matches(password, derivation)).isTrue();
        assertThat(elapsed)
                .as("a derivation this cheap would mean the cost factors are not being applied")
                .isGreaterThan(Duration.ofMillis(1));
    }
}
