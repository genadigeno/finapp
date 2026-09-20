package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link WebhookSignature}, proven against RFC 4231's own numbers — the
 * {@code CallbackSignatureTest} ceremony with the freshness half payments adds.
 *
 * <p>The vetted-library reasoning (`P1-TSK-017`, applied by `P2-TSK-011` and again here): the
 * primitive is the JDK's {@code Mac} either way, and the specification publishes test vectors
 * — so correctness is demonstrated against RFC 4231's published HMAC-SHA256 values rather
 * than against a reputation. The scheme's composition ({@code timestamp + "." + body}) is
 * pinned by signing the concatenation directly against the vector.
 */
class WebhookSignatureTest {

    private static final byte[] KEY =
            "an-adequate-webhook-signing-key-for-tests".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    private final WebhookSignature signature = new WebhookSignature(KEY, TOLERANCE, CLOCK);

    private String freshTimestamp() {
        return Long.toString(NOW.getEpochSecond());
    }

    // -----------------------------------------------------------------
    // RFC 4231 — HMAC-SHA256 test vectors over the exact signed payload

    @ParameterizedTest(name = "RFC 4231 test case {0}")
    @CsvSource({
        // case, key (hex), data (hex), expected HMAC-SHA256 (hex)
        "1, 0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b, 4869205468657265,"
                + " b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
        "2, 4a656665, 7768617420646f2079612077616e7420666f72206e6f7468696e673f,"
                + " 5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
    })
    @DisplayName("the MAC matches RFC 4231's published HMAC-SHA256 vectors, composed as"
            + " timestamp-dot-body")
    void matchesRfc4231(int testCase, String keyHex, String dataHex, String expectedHex) {
        // The vector's data is split at the wire scheme's own seam: the bytes BEFORE the dot
        // play the timestamp, the bytes AFTER it play the body — so the assertion pins both
        // the MAC and the exact concatenation order in one stroke. The vectors' data carry no
        // '.', so the split point is ours: after the first byte.
        byte[] data = HexFormat.of().parseHex(dataHex);
        byte[] withDot = new byte[data.length + 1];
        withDot[0] = data[0];
        withDot[1] = '.';
        System.arraycopy(data, 1, withDot, 2, data.length - 1);
        // sign(timestamp, body) must equal HMAC(key, timestamp + "." + body): feed the vector
        // through our composition and compare with an independently computed HMAC of withDot.
        WebhookSignature vectorKeyed =
                new WebhookSignature(HexFormat.of().parseHex(keyHex), TOLERANCE, CLOCK);
        String timestamp = new String(new byte[] {data[0]}, StandardCharsets.ISO_8859_1);
        byte[] body = Arrays.copyOfRange(data, 1, data.length);

        // First: the composition is exactly timestamp + "." + body.
        assertThat(vectorKeyed.sign(timestamp, body))
                .isEqualTo(rawHmacHex(HexFormat.of().parseHex(keyHex), withDot));
        // Second: the primitive itself reproduces the RFC's number over the unsplit data.
        assertThat(rawHmacHex(HexFormat.of().parseHex(keyHex), data)).isEqualTo(expectedHex);
    }

    @Test
    @DisplayName("RFC 4231 test case 3: repeated bytes, built by repetition so nobody miscounts")
    void matchesRfc4231CaseThree() {
        byte[] key = new byte[20];
        Arrays.fill(key, (byte) 0xaa);
        byte[] data = new byte[50];
        Arrays.fill(data, (byte) 0xdd);

        assertThat(rawHmacHex(key, data))
                .isEqualTo("773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe");
    }

    // -----------------------------------------------------------------
    // Round trip and tamper

    @Test
    @DisplayName("a fresh signature over timestamp and body verifies; any tamper refuses")
    void roundTripAndTamper() {
        byte[] body = "{\"eventId\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);
        String timestamp = freshTimestamp();
        String signed = signature.sign(timestamp, body);

        assertThat(signature.matches(timestamp, body, signed)).isTrue();
        // A tampered body refuses.
        assertThat(signature.matches(timestamp, "{}".getBytes(StandardCharsets.UTF_8), signed))
                .isFalse();
        // A shifted timestamp refuses even INSIDE the window: the timestamp is part of the
        // signed payload, so refreshing it without the key is not possible.
        String shifted = Long.toString(NOW.getEpochSecond() + 30);
        assertThat(signature.matches(shifted, body, signed)).isFalse();
        // The KYC scheme replayed — a valid HMAC over the body ALONE — refuses: the dot and
        // the timestamp are load-bearing.
        assertThat(signature.matches(timestamp, body, rawHmacHex(KEY, body))).isFalse();
    }

    // -----------------------------------------------------------------
    // The freshness window, both directions

    @Test
    @DisplayName("the window is two-sided: inside verifies, stale and future-skewed refuse")
    void theWindowIsTwoSided() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        String insidePast = Long.toString(NOW.minus(TOLERANCE).getEpochSecond());
        String insideFuture = Long.toString(NOW.plus(TOLERANCE).getEpochSecond());
        String stale = Long.toString(NOW.minus(TOLERANCE).minusSeconds(1).getEpochSecond());
        String future = Long.toString(NOW.plus(TOLERANCE).plusSeconds(1).getEpochSecond());

        assertThat(signature.matches(insidePast, body, signature.sign(insidePast, body)))
                .as("the boundary itself is inside the window")
                .isTrue();
        assertThat(signature.matches(insideFuture, body, signature.sign(insideFuture, body)))
                .isTrue();
        // A LEGITIMATELY SIGNED but stale message refuses: this is the replay bound itself,
        // not a signature failure - the capture-and-replay ADR-0047 §1 names.
        assertThat(signature.matches(stale, body, signature.sign(stale, body))).isFalse();
        assertThat(signature.matches(future, body, signature.sign(future, body)))
                .as("banking messages forward is as refused as replaying them back")
                .isFalse();
    }

    // -----------------------------------------------------------------
    // Totality: every bad shape is false, never a throw

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-a-number", "12.5", "2026-09-20T12:00:00Z", "99999999999999999999"})
    @DisplayName("a missing or malformed timestamp is false, never an exception")
    void malformedTimestampsRefuse(String timestamp) {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThat(signature.matches(timestamp, body, signature.sign("0", body))).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "zz", "abc", "00ff", "not hex at all"})
    @DisplayName("a missing, short, non-hex or wrong signature is false, never an exception")
    void malformedSignaturesRefuse(String presented) {
        assertThat(signature.matches(freshTimestamp(), "{}".getBytes(StandardCharsets.UTF_8),
                        presented))
                .isFalse();
    }

    // -----------------------------------------------------------------
    // Construction refusals

    @Test
    @DisplayName("an empty key, a non-positive tolerance: refused at construction")
    void constructionRefusals() {
        assertThatThrownBy(() -> new WebhookSignature(new byte[0], TOLERANCE, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebhookSignature(KEY, Duration.ZERO, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebhookSignature(KEY, Duration.ofSeconds(-1), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toString names the algorithm and window, never key material (INV-AUD-02)")
    void toStringCarriesNoKeyMaterial() {
        assertThat(signature.toString())
                .contains("HmacSHA256")
                .doesNotContain(new String(KEY, StandardCharsets.UTF_8))
                .doesNotContain(HexFormat.of().formatHex(KEY));
    }

    // -----------------------------------------------------------------
    // Constant time, asserted structurally because timing cannot be

    @Test
    @DisplayName("the comparison is constant-time, asserted structurally because timing cannot be")
    void theComparisonIsConstantTime() throws Exception {
        // MessageDigest.isEqual and Arrays.equals return the same answer for every input; the
        // difference is that a short-circuiting compare leaks how many leading bytes matched.
        // No behavioural assertion can tell them apart, and a wall-clock test measures the
        // machine (P1-TSK-008) - the CallbackSignatureTest pin, verbatim.
        String constantPool = constantPoolOf(WebhookSignature.class);

        assertThat(constantPool)
                .as("the constant-time comparison must actually be referenced")
                .contains("isEqual");
        assertThat(constantPool)
                .as("a short-circuiting compare is a timing oracle over the signature")
                .doesNotContain("java/util/Arrays")
                .doesNotContain("(Ljava/lang/Object;)Z");
    }

    // -----------------------------------------------------------------

    /** An independent HMAC-SHA256, JDK primitives, so the class under test never checks itself. */
    private static String rawHmacHex(byte[] key, byte[] data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is required by every JVM", impossible);
        }
    }

    /**
     * The class file's raw bytes as text — enough to see which methods it references. Crude on
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
