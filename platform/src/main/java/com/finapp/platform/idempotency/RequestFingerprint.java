package com.finapp.platform.idempotency;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/**
 * A digest of the semantically significant fields of a request, and the algorithm that produced
 * it.
 *
 * <p><strong>What it is for.</strong> {@code INV-IDEM-03}: presenting a known key with a
 * materially different request is an error, never a silent success. Without a fingerprint the
 * only options are to return the first request's response to a second, different request —
 * hiding a client defect and potentially a fraud attempt — or to re-execute it, producing a
 * second financial effect.
 *
 * <p><strong>Why the algorithm travels with the digest.</strong> {@code INV-HIST-04}'s rule —
 * pin the version of whatever produced a decision — applied to the thing that decides whether
 * two requests are the same. If the algorithm changed and were not recorded, every stored
 * digest would be compared against digests from a different function. They never match, so
 * every retry after the change would look like a new request and produce a second effect.
 * Comparison therefore requires the algorithms to agree, and refuses rather than guesses when
 * they do not.
 *
 * <p><strong>What the caller must hash.</strong> The <em>semantically significant</em> fields:
 * the ones that change what the command does. Including a timestamp or a client-generated
 * request id would make every retry look different and defeat the mechanism; excluding the
 * amount would let a different transfer replay a previous one's response. That choice belongs
 * to each command and is deliberately not made here.
 */
public final class RequestFingerprint {

    /** The default, and the only algorithm this platform currently produces. */
    public static final String SHA_256 = "SHA-256";

    /** Matches {@code idempotency_record_fingerprint_sized}. */
    private static final int MIN_DIGEST_BYTES = 32;

    private static final int MAX_DIGEST_BYTES = 64;

    private final byte[] digest;
    private final String algorithm;

    private RequestFingerprint(byte[] digest, String algorithm) {
        this.digest = digest;
        this.algorithm = algorithm;
    }

    /** Hashes the caller's canonical representation of the request. */
    public static RequestFingerprint sha256(byte[] canonicalRequest) {
        Objects.requireNonNull(canonicalRequest, "canonicalRequest must not be null");
        try {
            return new RequestFingerprint(
                    MessageDigest.getInstance(SHA_256).digest(canonicalRequest), SHA_256);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM is required to provide SHA-256", e);
        }
    }

    /** Rebuilds a fingerprint read back from storage. */
    public static RequestFingerprint ofStored(byte[] digest, String algorithm) {
        Objects.requireNonNull(digest, "digest must not be null");
        Objects.requireNonNull(algorithm, "algorithm must not be null");
        if (digest.length < MIN_DIGEST_BYTES || digest.length > MAX_DIGEST_BYTES) {
            // A truncated digest collides far more readily, which turns INV-IDEM-03 from a
            // check into a coin toss.
            throw new IllegalArgumentException(
                    "Digest must be " + MIN_DIGEST_BYTES + " to " + MAX_DIGEST_BYTES
                            + " bytes but was " + digest.length);
        }
        return new RequestFingerprint(digest.clone(), algorithm);
    }

    /** A defensive copy: a fingerprint that could be mutated after storage decides nothing. */
    public byte[] digest() {
        return digest.clone();
    }

    public String algorithm() {
        return algorithm;
    }

    /**
     * Whether this fingerprint stands for the same request as {@code stored}.
     *
     * @throws IllegalStateException if the algorithms differ — the comparison has no meaning,
     *     and answering "different" would silently re-execute every command whose record
     *     predates an algorithm change
     */
    public boolean matches(RequestFingerprint stored) {
        Objects.requireNonNull(stored, "stored fingerprint must not be null");
        if (!algorithm.equals(stored.algorithm)) {
            throw new IllegalStateException(
                    "Cannot compare a " + algorithm + " fingerprint with a " + stored.algorithm
                            + " one; a stored record predates an algorithm change and must be "
                            + "resolved deliberately, not guessed at");
        }
        // Constant-time: a fingerprint is derived from request content, and a timing oracle on
        // digest comparison is a needless disclosure even where the content is not secret.
        return MessageDigest.isEqual(digest, stored.digest);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RequestFingerprint other
                && algorithm.equals(other.algorithm)
                && MessageDigest.isEqual(digest, other.digest);
    }

    @Override
    public int hashCode() {
        return 31 * algorithm.hashCode() + Arrays.hashCode(digest);
    }

    /** Never prints the digest: it is derived from request content. */
    @Override
    public String toString() {
        return "RequestFingerprint[" + algorithm + ", " + digest.length + " bytes]";
    }
}
