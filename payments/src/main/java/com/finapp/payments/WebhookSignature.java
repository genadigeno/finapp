package com.finapp.payments;

import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The payment webhook signature (`P5-TSK-012`, ADR-0047 §1): HMAC-SHA256 over
 * {@code timestamp + "." + body}, hex in {@value #SIGNATURE_HEADER}, the timestamp itself in
 * {@value #TIMESTAMP_HEADER} as epoch seconds — the scheme real PSPs use.
 *
 * <h2>What payments adds over the `P2-TSK-011` scheme, and why</h2>
 *
 * <p>{@code CallbackSignature} deliberately omitted a freshness window and recorded the
 * reason: a replayed KYC callback is exactly the duplicate the inbox absorbs. Payments cannot
 * inherit the omission (ADR-0047's own alternative-rejected): once evidence rows — and later
 * meters — react even to duplicates, the window bounds how long a captured-and-replayed
 * message stays <em>processable at all</em>. The timestamp is <strong>inside the signed
 * payload</strong>, so an attacker cannot refresh a captured message's timestamp without the
 * key — a window over an unsigned header would be theatre.
 *
 * <h2>Total, and one refusal</h2>
 *
 * <p>{@link #matches} answers {@code false} for every bad shape — missing, blank, non-hex or
 * wrong-length signature; missing, non-numeric or out-of-window timestamp — never an
 * exception: which way a forgery failed is not information to hand out, and the door renders
 * every {@code false} as one uniform 401.
 *
 * <h2>Constant-time comparison, asserted structurally</h2>
 *
 * <p>{@link MessageDigest#isEqual} rather than an array comparison — a short-circuiting
 * compare leaks how many leading bytes matched, no behavioural test can distinguish the two,
 * and a timing test measures the machine (`P1-TSK-008`). {@code WebhookSignatureTest} pins the
 * mechanism structurally, the {@code CallbackSignature} ceremony.
 */
public final class WebhookSignature {

    /** The header the provider delivers its signature in. */
    public static final String SIGNATURE_HEADER = "X-Provider-Signature";

    /** The header carrying the signed timestamp, epoch seconds. */
    public static final String TIMESTAMP_HEADER = "X-Provider-Timestamp";

    private static final String ALGORITHM = "HmacSHA256";

    /** HMAC key material, not a stored credential value: named for what it is (ADR-0019). */
    private final byte[] key;

    private final Duration tolerance;
    private final Clock clock;

    /**
     * @param tolerance how far a signed timestamp may sit from the server clock, either
     *     direction — the freshness window, server-clock judged (the ADR-0014 discipline)
     */
    public WebhookSignature(byte[] key, Duration tolerance, Clock clock) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length == 0) {
            throw new IllegalArgumentException("the webhook signing key must not be empty");
        }
        this.key = key.clone();
        this.tolerance = Objects.requireNonNull(tolerance, "tolerance must not be null");
        if (tolerance.isNegative() || tolerance.isZero()) {
            throw new IllegalArgumentException("the freshness tolerance must be positive");
        }
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Whether {@code presentedHex} is this key's fresh signature of
     * {@code timestamp + "." + body}.
     *
     * <p>Total: every refusal — whatever its cause — is {@code false}.
     */
    public boolean matches(String presentedTimestamp, byte[] body, String presentedHex) {
        Objects.requireNonNull(body, "body must not be null");
        if (presentedTimestamp == null || presentedTimestamp.isBlank()) {
            return false;
        }
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(presentedTimestamp);
        } catch (NumberFormatException notANumber) {
            return false;
        }
        Instant stated;
        try {
            stated = Instant.ofEpochSecond(epochSeconds);
        } catch (java.time.DateTimeException outsideTime) {
            // Parseable as a long yet outside Instant's range: total means FALSE here too.
            return false;
        }
        Instant now = Instant.now(clock);
        // The window is two-sided: a message from the "future" beyond skew is as suspect as a
        // stale one, and a one-sided check would let an attacker bank messages forward.
        if (Duration.between(stated, now).abs().compareTo(tolerance) > 0) {
            return false;
        }
        if (presentedHex == null || presentedHex.isBlank()) {
            return false;
        }
        byte[] presented;
        try {
            presented = HexFormat.of().parseHex(presentedHex);
        } catch (IllegalArgumentException notHex) {
            return false;
        }
        return MessageDigest.isEqual(signatureOf(presentedTimestamp, body), presented);
    }

    /** The fresh signature of {@code timestamp + "." + body}, hex — what a legitimate sender computes. */
    public String sign(String timestamp, byte[] body) {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(body, "body must not be null");
        return HexFormat.of().formatHex(signatureOf(timestamp, body));
    }

    private byte[] signatureOf(String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            // timestamp + "." + body, the bytes exactly as sent: the timestamp is ASCII
            // digits, so UTF-8 is its only encoding, and the body is never re-encoded.
            mac.update(timestamp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            mac.update((byte) '.');
            return mac.doFinal(body);
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            // HmacSHA256 is required by every JVM, and the key is validated non-empty above.
            throw new IllegalStateException("HmacSHA256 is required by every JVM", impossible);
        }
    }

    /** No key material, ever ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "WebhookSignature[" + ALGORITHM + ", tolerance=" + tolerance + "]";
    }
}
