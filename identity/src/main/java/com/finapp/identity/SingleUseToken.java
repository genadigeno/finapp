package com.finapp.identity;

import com.finapp.sharedkernel.security.Sensitive;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * A high-entropy value proving control of a channel, used exactly once (`P1-TSK-023`).
 *
 * <h2>The same reasoning as {@link SessionToken}, and deliberately a separate type</h2>
 *
 * <p>Hashed at rest, because whoever holds it can act — {@code PHASE_1_PLAN.md} §5 already required
 * the recovery token to be <em>"hashed at rest like a credential"</em>. SHA-256 rather than Argon2
 * for {@code P1-TSK-013}'s reason: a work factor protects a <strong>low-entropy</strong> secret a
 * human chose, and there is nothing to guess in {@value #ENTROPY_BYTES} random bytes.
 *
 * <p><strong>Not reused as {@code SessionToken}</strong>, although the mechanics are identical. A
 * session token authenticates every subsequent request; this one is spent on a single transition and
 * then dead. Giving them one type would make it possible to pass either where the other is expected
 * — which is precisely what {@code EntityId} exists to prevent one level up, and the mistake would
 * be handing somebody a live session where a spent verification code was intended.
 *
 * <h2>Single use is enforced by the database, not by this type</h2>
 *
 * <p>Nothing here can make a token single-use: two instances holding the same value both compute the
 * same hash. What makes it single-use is a conditional {@code UPDATE} whose row count is the outcome
 * — {@code MfaEnrolmentStore.confirm}'s shape — so ten instances presenting one token produce one
 * transition and nine are told they lost.
 */
public final class SingleUseToken {

    /** 256 bits — the number that makes the SHA-256 argument above true. */
    public static final int ENTROPY_BYTES = 32;

    private static final String DIGEST = "SHA-256";

    private final Sensitive<String> value;

    private SingleUseToken(Sensitive<String> value) {
        this.value = Objects.requireNonNull(value, "value must not be null");
    }

    /** A fresh token. The only place one is created. */
    public static SingleUseToken issue(SecureRandom randomness) {
        Objects.requireNonNull(randomness, "randomness must not be null");
        byte[] bytes = new byte[ENTROPY_BYTES];
        randomness.nextBytes(bytes);
        return new SingleUseToken(
                Sensitive.of(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)));
    }

    /**
     * A token somebody presented.
     *
     * <p>No shape validation, deliberately: a malformed value is refused by the lookup finding
     * nothing, which is the same answer a spent or expired one gets. Rejecting it <em>differently</em>
     * would tell a caller their guess was at least the right shape — a free bit for anybody probing
     * ({@code SessionToken}'s reasoning).
     */
    public static SingleUseToken of(Sensitive<String> presented) {
        Objects.requireNonNull(presented, "presented must not be null");
        return new SingleUseToken(presented);
    }

    /**
     * From a plaintext.
     *
     * <p>Kept for tests and for a future notifier; production callers pass the wrapper straight
     * through from the request body, so a plaintext never has to exist outside {@code identity}.
     */
    public static SingleUseToken of(String presented) {
        Objects.requireNonNull(presented, "presented must not be null");
        return new SingleUseToken(Sensitive.of(presented));
    }

    /** What the database stores. Wrapped for {@code SessionToken}'s reason: logs. */
    public Sensitive<String> hash() {
        try {
            byte[] digest =
                    MessageDigest.getInstance(DIGEST)
                            .digest(value.expose().getBytes(StandardCharsets.UTF_8));
            return Sensitive.of(Base64.getEncoder().encodeToString(digest));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(DIGEST + " is not available", impossible);
        }
    }

    /**
     * The value to deliver to the channel.
     *
     * <p><strong>In Phase 1 nothing delivers it</strong>, and that is a seam rather than an
     * oversight: {@code PHASE_1_PLAN.md} §8 records that the channel adapter is Phase 15's. The
     * domain returns this to its caller so a notifier can attach in that same transaction; the HTTP
     * layer discards it and answers with no body, because returning it to whoever asked would mean
     * channel control proves nothing.
     */
    public Sensitive<String> presentedValue() {
        return value;
    }

    /** Never the token. */
    @Override
    public String toString() {
        return "SingleUseToken[" + Sensitive.MASK + "]";
    }
}
