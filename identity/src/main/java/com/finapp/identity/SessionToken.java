package com.finapp.identity;

import com.finapp.sharedkernel.security.Sensitive;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * The value a client presents to prove it holds a session (`P1-TSK-013`, ADR-0030).
 *
 * <h2>A bearer credential, and treated as one</h2>
 *
 * <p>Whoever holds this <em>is</em> the customer, for as long as the session lives. So it is stored
 * <strong>hashed</strong> and never in clear: a database leak with plaintext tokens hands an
 * attacker every live session with no work at all, which makes it worse than the credential table,
 * where Argon2 at least buys time.
 *
 * <p>{@code PHASE_1_PLAN.md} §5 already requires the recovery token to be <em>"hashed at rest"</em>.
 * The same argument applies here and the plan simply never said so.
 *
 * <h2>SHA-256, deliberately not Argon2</h2>
 *
 * <p>This looks inconsistent with ADR-0032 and is not. A password needs a work factor because it is
 * <strong>low-entropy</strong> — a human chose it, and an attacker with the derivation guesses.
 * There is nothing to guess here: the token is {@value #ENTROPY_BYTES} bytes from
 * {@link SecureRandom}, so an attacker holding the hash has no shortcut and brute force is not a
 * strategy. A work factor would buy no security and would cost ~46 ms on <em>every authenticated
 * request</em>, which is the difference between a platform and a demonstration.
 *
 * <h2>Not a UUID</h2>
 *
 * <p>ADR-0030 requires <em>"a random value with no structure a client can read or a server can
 * trust"</em>. A UUIDv7 encodes its creation time, which is structure — and this platform's own
 * {@code EntityId} exists to provide exactly that, deliberately. The aggregate's identifier is
 * {@link SessionId}; this is the secret. Two identifiers, two jobs.
 */
public final class SessionToken {

    /**
     * 256 bits.
     *
     * <p>Enough that guessing is not a strategy anybody would attempt, and the number that makes
     * the SHA-256 argument above true. Below about 128 bits the reasoning changes and so should the
     * hash.
     */
    public static final int ENTROPY_BYTES = 32;

    private static final String DIGEST = "SHA-256";

    private final Sensitive<String> value;

    private SessionToken(Sensitive<String> value) {
        this.value = Objects.requireNonNull(value, "value must not be null");
    }

    /** A fresh token. The only place one is created. */
    public static SessionToken issue(SecureRandom randomness) {
        Objects.requireNonNull(randomness, "randomness must not be null");
        byte[] bytes = new byte[ENTROPY_BYTES];
        randomness.nextBytes(bytes);
        return new SessionToken(
                Sensitive.of(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)));
    }

    /**
     * A token a client presented.
     *
     * <p>No validation of shape, and that is deliberate: a token that does not match anything is
     * refused by the lookup finding nothing, which is the same answer an expired or revoked one
     * gets. Rejecting a malformed value <em>differently</em> would tell a caller that its guess was
     * at least the right shape, which is a free bit for anybody probing.
     */
    public static SessionToken of(String presented) {
        Objects.requireNonNull(presented, "presented must not be null");
        return new SessionToken(Sensitive.of(presented));
    }

    /**
     * What the database stores.
     *
     * <p><strong>Wrapped, and the build decided that rather than taste.</strong> The first version
     * returned a bare {@code String} on the argument that a hash is not the secret — it is what
     * remains after the secret has been thrown away, and presenting it achieves nothing.
     * {@code secretsAreWrapped} rejected it and the rule had the better argument: {@code INV-AUD-02}
     * is about what reaches a <em>log</em>, and a session token hash in a log is a precise
     * identifier of one customer's live session. It is the single most useful thing to an attacker
     * reading log archives, and wrapping it costs nothing.
     *
     * <p>It is <em>not</em> wrapped because it is crackable. It is not: the input is
     * {@value #ENTROPY_BYTES} random bytes. That distinction is why this is SHA-256 and a password
     * derivation is Argon2.
     */
    public Sensitive<String> hash() {
        try {
            byte[] digest =
                    MessageDigest.getInstance(DIGEST)
                            .digest(value.expose().getBytes(StandardCharsets.UTF_8));
            return Sensitive.of(Base64.getEncoder().encodeToString(digest));
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM ships SHA-256. If this ever throws, the platform is not running on a JVM.
            throw new IllegalStateException(DIGEST + " is not available", impossible);
        }
    }

    /**
     * The value to hand the client, exactly once.
     *
     * <p>Named to be found, like every other unwrap. {@code SecretsAreUnwrappedInOnePlaceTest} pins
     * the set of production classes that may do this.
     */
    public Sensitive<String> presentedValue() {
        return value;
    }

    /** Never the token. */
    @Override
    public String toString() {
        return "SessionToken[" + Sensitive.MASK + "]";
    }
}
