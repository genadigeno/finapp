package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link CallbackSignature}, proven against RFC 4231's own numbers.
 *
 * <p>The vetted-library reasoning (`P1-TSK-017`, applied again by `P2-TSK-011`): the primitive
 * is the JDK's {@code Mac} either way, and the specification publishes test vectors — so
 * correctness is demonstrated against RFC 4231's published HMAC-SHA256 values rather than
 * against a reputation. The comparison being constant-time is asserted <em>structurally</em>,
 * because no behavioural test can distinguish {@code MessageDigest.isEqual} from
 * {@code Arrays.equals} — both return the same answer, and the difference is only timing,
 * which a wall-clock test measures the machine to see (`P1-TSK-008`).
 */
class CallbackSignatureTest {

    private static final byte[] KEY = "an-adequate-signing-key-for-tests".getBytes(StandardCharsets.UTF_8);

    // -----------------------------------------------------------------
    // RFC 4231 — HMAC-SHA256 test vectors

    @ParameterizedTest(name = "RFC 4231 test case {0}")
    @CsvSource({
        // case, key (hex), data (hex), expected HMAC-SHA256 (hex)
        "1, 0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b, 4869205468657265,"
                + " b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
        "2, 4a656665, 7768617420646f2079612077616e7420666f72206e6f7468696e673f,"
                + " 5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
    })
    @DisplayName("the signature matches RFC 4231's published HMAC-SHA256 vectors")
    void matchesRfc4231(int testCase, String keyHex, String dataHex, String expectedHex) {
        CallbackSignature signature = new CallbackSignature(HexFormat.of().parseHex(keyHex));
        byte[] data = HexFormat.of().parseHex(dataHex);

        assertThat(signature.sign(data)).isEqualTo(expectedHex);
        assertThat(signature.matches(data, expectedHex)).isTrue();
    }

    @Test
    @DisplayName("RFC 4231 test case 3: repeated bytes, built by repetition so nobody miscounts")
    void matchesRfc4231CaseThree() {
        byte[] key = new byte[20];
        Arrays.fill(key, (byte) 0xaa);
        byte[] data = new byte[50];
        Arrays.fill(data, (byte) 0xdd);

        assertThat(new CallbackSignature(key).sign(data))
                .isEqualTo("773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe");
    }

    // -----------------------------------------------------------------
    // The refusal is total: false, never an exception, whatever shape the forgery takes

    @Test
    @DisplayName("a wrong signature of the right shape is refused")
    void aWrongSignatureIsRefused() {
        CallbackSignature signature = new CallbackSignature(KEY);
        byte[] body = "{\"status\":\"clear\"}".getBytes(StandardCharsets.UTF_8);

        // A valid signature of a DIFFERENT body: the right length, the right charset,
        // computed with the right key - the closest possible forgery without the body.
        String ofAnotherBody = signature.sign("{\"status\":\"hit\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(signature.matches(body, ofAnotherBody)).isFalse();
    }

    @Test
    @DisplayName("a signature under a different key is refused")
    void aDifferentKeysSignatureIsRefused() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String signedElsewhere =
                new CallbackSignature("some-other-key".getBytes(StandardCharsets.UTF_8)).sign(body);

        assertThat(new CallbackSignature(KEY).matches(body, signedElsewhere)).isFalse();
    }

    @ParameterizedTest(name = "presented as <{0}>")
    @ValueSource(strings = {"", "   ", "not-hex-at-all", "zz44", "b034", "b0344c61d8db3853X"})
    @DisplayName("a blank, non-hex or truncated presentation is false, never an exception")
    void aMalformedPresentationIsFalse(String presented) {
        assertThat(new CallbackSignature(KEY).matches(new byte[] {1, 2, 3}, presented)).isFalse();
    }

    @Test
    @DisplayName("a missing presentation is false, never an exception")
    void aNullPresentationIsFalse() {
        assertThat(new CallbackSignature(KEY).matches(new byte[] {1, 2, 3}, null)).isFalse();
    }

    @Test
    @DisplayName("an empty key is refused at construction, not at first use")
    void anEmptyKeyIsRefused() {
        assertThatThrownBy(() -> new CallbackSignature(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the key does not leak through the caller's array")
    void theKeyIsDefensivelyCopied() {
        byte[] callers = Arrays.copyOf(KEY, KEY.length);
        CallbackSignature signature = new CallbackSignature(callers);
        String before = signature.sign(new byte[] {1});

        Arrays.fill(callers, (byte) 0);

        assertThat(signature.sign(new byte[] {1})).isEqualTo(before);
    }

    @Test
    @DisplayName("toString reveals no key material (INV-AUD-02)")
    void toStringRevealsNothing() {
        String rendered = new CallbackSignature(KEY).toString();

        assertThat(rendered).doesNotContain(new String(KEY, StandardCharsets.UTF_8));
        assertThat(rendered).doesNotContain(HexFormat.of().formatHex(KEY));
    }

    // -----------------------------------------------------------------
    // Constant time, asserted structurally because timing cannot be

    @Test
    @DisplayName("the comparison is constant-time, asserted structurally because timing cannot be")
    void theComparisonIsConstantTime() throws Exception {
        // MessageDigest.isEqual and Arrays.equals return the same answer for every input; the
        // difference is that a short-circuiting compare leaks how many leading bytes matched.
        // No behavioural assertion can tell them apart, and a wall-clock test measures the
        // machine (P1-TSK-008) - so the mechanism is pinned structurally, the TotpVerifier shape.
        String constantPool = constantPoolOf(CallbackSignature.class);

        assertThat(constantPool)
                .as("the constant-time comparison must actually be referenced")
                .contains("isEqual");

        // A class file's constant pool holds the class name and the method name as SEPARATE
        // UTF-8 entries, so "java/util/Arrays.equals" as one string can never appear and a
        // doesNotContain on it would be vacuous. What does appear: the referenced class's name
        // (CallbackSignature otherwise never touches Arrays), and the descriptor of any
        // equals(Object) - the TotpVerifierTest reasoning, made explicit here.
        assertThat(constantPool)
                .as("a short-circuiting compare is a timing oracle over the signature")
                .doesNotContain("java/util/Arrays")
                .doesNotContain("(Ljava/lang/Object;)Z");
    }

    /**
     * The class file's raw bytes as text - enough to see which methods it references. Crude on
     * purpose (no bytecode library), and its limit is stated: it proves what the class
     * <em>references</em>, not what it does with the result.
     */
    private static String constantPoolOf(Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        try (java.io.InputStream bytes = type.getClassLoader().getResourceAsStream(resource)) {
            return new String(
                    java.util.Objects.requireNonNull(bytes, resource).readAllBytes(),
                    StandardCharsets.ISO_8859_1);
        }
    }
}
