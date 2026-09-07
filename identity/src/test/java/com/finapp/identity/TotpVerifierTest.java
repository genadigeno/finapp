package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.security.Sensitive;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * TOTP, checked against the specification's own numbers (`P1-TSK-017`).
 *
 * <h2>The RFC's vectors are why this is written rather than depended upon</h2>
 *
 * <p>RFC 6238 Appendix B and RFC 4226 Appendix D publish expected outputs for named inputs. That
 * makes correctness <strong>demonstrable against the specification</strong> rather than trusted to
 * a library's reputation — and it is the argument the design made for using JDK primitives instead
 * of adding a dependency for forty lines of specified arithmetic.
 *
 * <p>A test written from my own implementation's output would prove only that it does what it does.
 * Every expected value below comes from the RFC.
 */
@DisplayName("TOTP against RFC 6238 (P1-TSK-017)")
class TotpVerifierTest {

    /** RFC 6238 Appendix B: the ASCII string "12345678901234567890". */
    private static final byte[] RFC_SHA1_KEY =
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    // -----------------------------------------------------------------
    // RFC 4226 Appendix D — HOTP, counter 0..9, the same truncation this uses

    @ParameterizedTest(name = "RFC 4226 counter {0} -> {1}")
    @CsvSource({
        "0, 755224", "1, 287082", "2, 359152", "3, 969429", "4, 338314",
        "5, 254676", "6, 287922", "7, 162583", "8, 399871", "9, 520489"
    })
    @DisplayName("the dynamic truncation matches RFC 4226 Appendix D")
    void matchesRfc4226(long counter, String expected) {
        assertThat(TotpVerifier.generate(RFC_SHA1_KEY, counter, TotpParameters.current()))
                .isEqualTo(expected);
    }

    // -----------------------------------------------------------------
    // RFC 6238 Appendix B — TOTP, SHA-1, 8 digits, 30-second step

    @ParameterizedTest(name = "RFC 6238 T={0} -> {1}")
    @CsvSource({
        "59, 94287082",
        "1111111109, 07081804",
        "1111111111, 14050471",
        "1234567890, 89005924",
        "2000000000, 69279037",
        "20000000000, 65353130"
    })
    @DisplayName("the time-step derivation matches RFC 6238 Appendix B")
    void matchesRfc6238(long epochSecond, String expected) {
        TotpParameters eightDigits = new TotpParameters(TotpAlgorithm.SHA1, 8, 30);

        assertThat(TotpVerifier.generate(RFC_SHA1_KEY, epochSecond / 30, eightDigits))
                .isEqualTo(expected);
    }

    // -----------------------------------------------------------------
    // Base32, against RFC 4648 Appendix — the encoding every authenticator app expects

    @ParameterizedTest(name = "RFC 4648 {0} -> {1}")
    @CsvSource({
        "f, MY", "fo, MZXQ", "foo, MZXW6", "foob, MZXW6YQ", "fooba, MZXW6YTB", "foobar, MZXW6YTBOI"
    })
    @DisplayName("base32 matches RFC 4648")
    void base32MatchesRfc4648(String input, String expected) {
        assertThat(TotpVerifier.Base32.encode(input.getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo(expected);
        assertThat(TotpVerifier.Base32.decode(expected))
                .isEqualTo(input.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("a value that is not base32 is refused without echoing it")
    void invalidBase32IsRefused() {
        // The message must not repeat the input: on this path the input is a secret.
        assertThatThrownBy(() -> TotpVerifier.Base32.decode("MZXW6!!!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("MZXW6");
    }

    // -----------------------------------------------------------------
    // Verification, and the window

    @Test
    @DisplayName("the current code verifies")
    void theCurrentCodeVerifies() {
        Instant now = Instant.parse("2026-09-07T12:00:00Z");
        TotpVerifier verifier = new TotpVerifier(Clock.fixed(now, ZoneOffset.UTC));
        Sensitive<String> secret = Sensitive.of(TotpVerifier.Base32.encode(RFC_SHA1_KEY));

        String code =
                TotpVerifier.generate(
                        RFC_SHA1_KEY, now.getEpochSecond() / 30, TotpParameters.current());

        assertThat(verifier.verify(secret, code, TotpParameters.current())).isTrue();
    }

    @Test
    @DisplayName("one step either side is accepted, and two is not")
    void theWindowIsOneStepEitherSide() {
        Instant now = Instant.parse("2026-09-07T12:00:00Z");
        TotpVerifier verifier = new TotpVerifier(Clock.fixed(now, ZoneOffset.UTC));
        Sensitive<String> secret = Sensitive.of(TotpVerifier.Base32.encode(RFC_SHA1_KEY));
        long step = now.getEpochSecond() / 30;

        // Clocks disagree. A zero-width window makes a correct code fail for a reason the customer
        // cannot diagnose and support cannot reproduce.
        assertThat(verifier.verify(secret, codeAt(step - 1), TotpParameters.current())).isTrue();
        assertThat(verifier.verify(secret, codeAt(step + 1), TotpParameters.current())).isTrue();

        // And it is bounded. A window that kept widening would eventually make a code valid for
        // long enough to be worth intercepting.
        assertThat(verifier.verify(secret, codeAt(step - 2), TotpParameters.current())).isFalse();
        assertThat(verifier.verify(secret, codeAt(step + 2), TotpParameters.current())).isFalse();
    }

    @Test
    @DisplayName("a code from a different secret is refused")
    void aCodeFromAnotherSecretIsRefused() {
        Instant now = Instant.parse("2026-09-07T12:00:00Z");
        TotpVerifier verifier = new TotpVerifier(Clock.fixed(now, ZoneOffset.UTC));
        byte[] other = "09876543210987654321".getBytes(StandardCharsets.US_ASCII);

        String theirCode =
                TotpVerifier.generate(other, now.getEpochSecond() / 30, TotpParameters.current());

        assertThat(
                        verifier.verify(
                                Sensitive.of(TotpVerifier.Base32.encode(RFC_SHA1_KEY)),
                                theirCode,
                                TotpParameters.current()))
                .isFalse();
    }

    @Test
    @DisplayName("no malformed input throws: a caller's typing is untrusted")
    void malformedInputIsRefusedRatherThanThrowing() {
        Instant now = Instant.parse("2026-09-07T12:00:00Z");
        TotpVerifier verifier = new TotpVerifier(Clock.fixed(now, ZoneOffset.UTC));
        Sensitive<String> secret = Sensitive.of(TotpVerifier.Base32.encode(RFC_SHA1_KEY));

        // A code is whatever somebody typed. Every one of these must be a plain refusal, because a
        // thrown exception here would be our fault reported for their input - which
        // ERROR_CONTRACT.md §3 forbids, and which a client may retry for ever.
        for (String malformed :
                new String[] {null, "", "   ", "abcdef", "12345", "1234567", "-000001", "٠١٢٣٤٥"}) {
            assertThat(verifier.verify(secret, malformed, TotpParameters.current()))
                    .as("refused rather than thrown: %s", malformed)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("the code changes when the period elapses")
    void theCodeChangesWithTime() {
        Instant now = Instant.parse("2026-09-07T12:00:00Z");
        Sensitive<String> secret = Sensitive.of(TotpVerifier.Base32.encode(RFC_SHA1_KEY));
        long step = now.getEpochSecond() / 30;
        String current = codeAt(step);

        // Two periods on, the code that was current is outside the window. This is the property
        // that makes it one-TIME rather than merely one-per-account.
        TotpVerifier later =
                new TotpVerifier(Clock.fixed(now.plus(Duration.ofSeconds(90)), ZoneOffset.UTC));

        assertThat(later.verify(secret, current, TotpParameters.current())).isFalse();
    }

    @Test
    @DisplayName("the comparison is constant-time, asserted structurally because timing cannot be")
    void theComparisonIsConstantTime() throws Exception {
        // A mutation replacing MessageDigest.isEqual with String.equals SURVIVED, and no
        // behavioural test can catch it: both return the same answer, and the difference is only in
        // how long a mismatch takes. A wall-clock test would measure the machine and be flaky - the
        // P1-TSK-008 finding that you assert by counting work rather than by reading a clock.
        //
        // So it is asserted STRUCTURALLY, the shape P1-TSK-015 used for the unreachable plaintext:
        // the class must reference the constant-time comparison and must not reference String.equals.
        String constantPool = constantPoolOf(TotpVerifier.class);

        assertThat(constantPool)
                .as("the constant-time comparison must actually be referenced")
                .contains("isEqual");

        // String.equals returns at the first differing character, so comparing a six-digit code
        // with it leaks how many leading digits were right - turning one search of 10^6 into six
        // searches of 10.
        assertThat(constantPool)
                .as("String.equals on a code is a timing oracle over the code")
                .doesNotContain("java/lang/String.equals")
                .doesNotContain("(Ljava/lang/Object;)Z");
    }

    /**
     * The class file's raw bytes as text, which is enough to see which methods it references.
     *
     * <p>Crude on purpose: no bytecode library, and its limit is stated rather than implied - it
     * proves what the class <em>references</em>, not what it does with the result. What it makes
     * impossible is the version somebody actually writes.
     */
    private static String constantPoolOf(Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        try (java.io.InputStream bytes = type.getClassLoader().getResourceAsStream(resource)) {
            return new String(
                    java.util.Objects.requireNonNull(bytes, resource).readAllBytes(),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
        }
    }

    private static String codeAt(long step) {
        return TotpVerifier.generate(RFC_SHA1_KEY, step, TotpParameters.current());
    }
}
