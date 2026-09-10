package com.finapp.kyc;

import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The simulated callback signature (`P2-TSK-011`): HMAC-SHA256 over the raw body, hex in
 * {@value #HEADER}.
 *
 * <h2>Why real authenticity rather than a checksum</h2>
 *
 * <p>The callback is the input that completes checks — including clearing a sanctions screening
 * — so a "signature" anyone can compute would make the endpoint an open door to check-outcome
 * forgery, which is qualitatively worse than any other unauthenticated surface this platform
 * carries. HMAC with a shared key is the smallest scheme that actually authenticates the
 * caller, and it is JDK primitives end to end ({@code Mac}); RFC 4231 publishes test vectors,
 * so correctness is proven against the specification's own numbers rather than a reputation —
 * the `P1-TSK-017` vetted-library reasoning, verbatim.
 *
 * <h2>The seam's limits, stated (the real scheme is Phase 5's webhook work)</h2>
 *
 * <ul>
 *   <li>One static key, no rotation, no per-provider keys.
 *   <li><strong>No timestamp and no replay window, deliberately</strong>: a replayed callback
 *       is exactly the duplicate delivery the inbox exists for, and produces no second effect —
 *       so a replay bound at the signature layer would duplicate a stronger control with a
 *       weaker one. What the signature answers is <em>who sent this</em>, and nothing else.
 * </ul>
 *
 * <h2>Constant-time comparison, asserted structurally</h2>
 *
 * <p>{@link MessageDigest#isEqual} rather than an array or string comparison, because a
 * short-circuiting compare leaks how many leading bytes matched — and no behavioural test can
 * distinguish the two, only timing can, and a timing test measures the machine
 * (`P1-TSK-008`/`P1-TSK-017`'s recorded reasoning). {@code CallbackSignatureTest} asserts the
 * mechanism structurally.
 */
public final class CallbackSignature {

    /** The header the provider delivers its signature in. */
    public static final String HEADER = "X-Provider-Signature";

    private static final String ALGORITHM = "HmacSHA256";

    /** HMAC key material, not a stored credential value: named for what it is (ADR-0019). */
    private final byte[] key;

    public CallbackSignature(byte[] key) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length == 0) {
            throw new IllegalArgumentException("the callback signing key must not be empty");
        }
        this.key = key.clone();
    }

    /**
     * Whether {@code presentedHex} is this key's signature of {@code body}.
     *
     * <p>Total: a missing, empty, non-hex or wrong-length presentation is {@code false}, never
     * an exception — the refusal must be uniform whatever shape the forgery takes.
     */
    public boolean matches(byte[] body, String presentedHex) {
        Objects.requireNonNull(body, "body must not be null");
        if (presentedHex == null || presentedHex.isBlank()) {
            return false;
        }
        byte[] presented;
        try {
            presented = HexFormat.of().parseHex(presentedHex);
        } catch (IllegalArgumentException notHex) {
            return false;
        }
        return MessageDigest.isEqual(signatureOf(body), presented);
    }

    /** The signature of {@code body}, hex — what a legitimate sender computes. */
    public String sign(byte[] body) {
        Objects.requireNonNull(body, "body must not be null");
        return HexFormat.of().formatHex(signatureOf(body));
    }

    private byte[] signatureOf(byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(body);
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            // HmacSHA256 is required by every JVM, and the key is validated non-empty above.
            throw new IllegalStateException("HmacSHA256 is required by every JVM", impossible);
        }
    }

    /** No key material, ever ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "CallbackSignature[" + ALGORITHM + "]";
    }
}
