package com.finapp.checkout;

import com.finapp.sharedkernel.security.Sensitive;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * What a customer presents to act on one checkout session (`P6-TSK-006`, ADR-0053's
 * consequences).
 *
 * <h2>A bearer credential with a single purpose</h2>
 *
 * <p>Whoever holds this may confirm <em>that session</em> — and nothing else. It authenticates
 * no person, carries no permission, and expires with the offer it belongs to. That narrowness is
 * the security property: a leaked checkout token buys an attacker one stranger's pending
 * purchase, not an account.
 *
 * <p>It is still a bearer credential, so it gets the full regime ({@code INV-IDN-01}): stored
 * <strong>hashed</strong> and never in clear, with the derivation recorded beside it
 * ({@code INV-IDN-02}), verified in constant time, and wrapped in {@link Sensitive} on every
 * path. The platform's <strong>fifth</strong> credential value and the third to follow this
 * exact shape — {@code SessionToken}, {@code MerchantApiKeySecret}, this.
 *
 * <h2>SHA-256, deliberately not Argon2 — the argument inherited a third time</h2>
 *
 * <p>A password needs a work factor because a human chose it and an attacker guesses. There is
 * nothing to guess in {@value #ENTROPY_BYTES} bytes from {@link SecureRandom}, and a work factor
 * would cost ~46 ms on every checkout page load while buying no security (ADR-0032's
 * distinction, at a third door).
 *
 * <h2>Not a UUID, and not the identifier</h2>
 *
 * <p>{@link CheckoutSessionId} is what the platform calls this session; this is what proves the
 * caller holds it. A UUIDv7 encodes its creation time — structure a client can read — which is
 * exactly right for an identifier and exactly wrong for a secret ({@code SessionToken}'s
 * recorded reasoning). Two identifiers, two jobs.
 */
public final class CheckoutSessionToken {

    /** 256 bits — the number that makes the SHA-256 argument above true. */
    public static final int ENTROPY_BYTES = 32;

    private static final String DIGEST = "SHA-256";

    /** Recorded per {@code INV-IDN-02}: what produced the stored value, beside the value. */
    public static final String ALGORITHM = DIGEST;

    private final Sensitive<String> value;

    private CheckoutSessionToken(Sensitive<String> value) {
        this.value = Objects.requireNonNull(value, "value must not be null");
    }

    /** A fresh token. The only place one is created. */
    public static CheckoutSessionToken issue(SecureRandom randomness) {
        Objects.requireNonNull(randomness, "randomness must not be null");
        byte[] bytes = new byte[ENTROPY_BYTES];
        randomness.nextBytes(bytes);
        return new CheckoutSessionToken(
                Sensitive.of(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)));
    }

    /**
     * A token a customer presented.
     *
     * <p>No validation of shape, deliberately: a token that matches nothing is refused by the
     * verification failing, which is the same answer an expired session gets. Rejecting a
     * malformed value <em>differently</em> would tell a caller its guess was at least the right
     * shape, which is a free bit for anybody probing ({@code SessionToken.of}'s rule).
     */
    public static CheckoutSessionToken of(String presented) {
        Objects.requireNonNull(presented, "presented must not be null");
        return new CheckoutSessionToken(Sensitive.of(presented));
    }

    /**
     * What the database stores.
     *
     * <p>Wrapped for {@code SessionToken.hash()}'s reason: {@code INV-AUD-02} is about what
     * reaches a log, and a token hash in a log is a precise identifier of one live checkout. It
     * is not wrapped because it is crackable — it is not.
     */
    public Sensitive<String> hash() {
        return Sensitive.of(digestOf(value.expose()));
    }

    /** True when this presented token produces {@code storedHash}. Constant-time. */
    public boolean matches(String storedHash) {
        Objects.requireNonNull(storedHash, "storedHash must not be null");
        return MessageDigest.isEqual(
                digestOf(value.expose()).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The freshly minted plaintext, still <strong>wrapped</strong> — handed to the response that
     * shows it once and to nothing else.
     *
     * <p>Package-private and wrapped, for {@code MerchantApiKeySecret.plaintext()}'s recorded
     * reason: the creating command never holds a bare token, so the set of components permitted
     * to unwrap a secret stays as small as it is.
     */
    Sensitive<String> plaintext() {
        return value;
    }

    private static String digestOf(String plaintext) {
        try {
            return Base64.getEncoder()
                    .encodeToString(
                            MessageDigest.getInstance(DIGEST)
                                    .digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM ships SHA-256. If this ever throws, the platform is not on a JVM.
            throw new IllegalStateException(DIGEST + " is not available", impossible);
        }
    }

    /** Masked. The wrapper's own {@code toString} would do this; stating it is cheap. */
    @Override
    public String toString() {
        return "CheckoutSessionToken[" + Sensitive.MASK + "]";
    }
}
