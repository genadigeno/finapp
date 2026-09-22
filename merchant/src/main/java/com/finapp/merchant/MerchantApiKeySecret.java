package com.finapp.merchant;

import com.finapp.sharedkernel.security.Sensitive;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * The secret half of a merchant's API credential (`P6-TSK-002`, ADR-0052).
 *
 * <h2>A bearer credential, and treated as one</h2>
 *
 * <p>Whoever holds this <em>is</em> the merchant, until the key is revoked. So it is stored
 * <strong>hashed</strong> and never in clear ({@code INV-IDN-01} applied to the platform's
 * fourth authentication vocabulary): a database leak with plaintext keys hands an attacker
 * every merchant integration with no work at all.
 *
 * <h2>SHA-256, deliberately not Argon2 — {@code SessionToken}'s argument, inherited</h2>
 *
 * <p>A password needs a work factor because it is <strong>low-entropy</strong>: a human chose
 * it and an attacker with the derivation guesses. There is nothing to guess here — the secret
 * is {@value #ENTROPY_BYTES} bytes from {@link SecureRandom}, so brute force is not a
 * strategy. A work factor would buy no security and would cost ~46 ms on <em>every merchant
 * API request</em>, on a credential used by a server in a loop rather than by a person at a
 * login form. That is the same distinction ADR-0032 draws, at a new door.
 *
 * <h2>Verification is constant-time</h2>
 *
 * <p>{@link MessageDigest#isEqual} rather than {@code String.equals}: the comparison is
 * against a value an attacker supplies and can vary, and a short-circuiting compare leaks how
 * many leading bytes were right. The cost is nothing and the property is stated rather than
 * inherited from a library's incidental behaviour.
 */
public final class MerchantApiKeySecret {

    /**
     * 256 bits — enough that guessing is not a strategy anybody would attempt, and the number
     * that makes the SHA-256 argument above true.
     */
    public static final int ENTROPY_BYTES = 32;

    private static final String DIGEST = "SHA-256";

    /** Recorded per {@code INV-IDN-02}: what produced the stored value, beside the value. */
    public static final String ALGORITHM = DIGEST;

    private final Sensitive<String> value;

    private MerchantApiKeySecret(Sensitive<String> value) {
        this.value = Objects.requireNonNull(value, "value must not be null");
    }

    /** A fresh secret. The only place one is created. */
    public static MerchantApiKeySecret issue(SecureRandom randomness) {
        Objects.requireNonNull(randomness, "randomness must not be null");
        byte[] bytes = new byte[ENTROPY_BYTES];
        randomness.nextBytes(bytes);
        return new MerchantApiKeySecret(
                Sensitive.of(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)));
    }

    /**
     * A secret a client presented.
     *
     * <p>No validation of shape, deliberately ({@code SessionToken.of}'s rule): a secret that
     * matches nothing is refused by the verification failing, which is the same answer a
     * revoked key gets. Rejecting a malformed value <em>differently</em> would tell a caller
     * its guess was at least the right shape, which is a free bit for anybody probing.
     */
    public static MerchantApiKeySecret of(String presented) {
        Objects.requireNonNull(presented, "presented must not be null");
        return new MerchantApiKeySecret(Sensitive.of(presented));
    }

    /**
     * What the database stores.
     *
     * <p>Wrapped for the reason {@code SessionToken.hash()} records: {@code INV-AUD-02} is
     * about what reaches a log, and a key hash in a log is a precise identifier of one
     * merchant's live credential. It is not wrapped because it is crackable — it is not.
     */
    public Sensitive<String> hash() {
        return Sensitive.of(digestOf(value.expose()));
    }

    /**
     * True when this presented secret produces {@code storedHash}. Constant-time, see above.
     */
    public boolean matches(String storedHash) {
        Objects.requireNonNull(storedHash, "storedHash must not be null");
        return MessageDigest.isEqual(
                digestOf(value.expose()).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The freshly minted plaintext, still <strong>wrapped</strong> — handed to the issuance
     * response and to nothing else.
     *
     * <p>Wrapped rather than exposed deliberately, and the difference is not cosmetic: it
     * means the issuing command never holds a bare secret, so the set of components permitted
     * to unwrap one ({@code SecretsAreUnwrappedInOnePlaceTest}) stays three rather than four
     * — the mint, the aggregate that compares against a hash, and the ONE boundary that must
     * transmit the value. Package-private, so the carrier is a decision made here rather than
     * a capability every caller inherits.
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
        return "MerchantApiKeySecret[" + Sensitive.MASK + "]";
    }
}
