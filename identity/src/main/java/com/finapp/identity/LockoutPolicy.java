package com.finapp.identity;

import java.time.Duration;
import java.util.Objects;

/**
 * How many consecutive failures lock an identity, and for how long (`P1-TSK-011`).
 *
 * <h2>Constants, not configuration</h2>
 *
 * <p>The same reasoning ADR-0032 gives for derivation parameters, one level up: a control whose
 * strength is a deployment setting is a control nobody can reason about, and the first incident is
 * where somebody discovers it was turned down. These are values with recorded reasoning, changed by
 * a commit that a reviewer sees.
 *
 * <p>They are deliberately <em>not</em> recorded per identity the way derivation parameters are.
 * The two look similar and are not: a derivation parameter has to be remembered because it produced
 * a stored artefact that must still be verifiable years later, while a threshold is evaluated fresh
 * on every attempt and nothing survives a change to it.
 */
public record LockoutPolicy(int threshold, Duration window, Duration lockFor) {

    /**
     * Ten failures in fifteen minutes locks for fifteen minutes.
     *
     * <p><strong>Ten.</strong> NIST SP 800-63B is content with far more before throttling; ten is
     * conservative and still generous against a person who has genuinely forgotten which of their
     * passwords this is. The number that matters is not the absolute value but that it is small
     * enough to make guessing useless: with Argon2id at ~46 ms, ten attempts buys an attacker
     * nothing against any password worth having.
     *
     * <p><strong>A window, so failures expire.</strong> Without one, a typo today and a typo next
     * month accumulate, and an account eventually locks for no reason anybody can reconstruct.
     * Consecutive means consecutive <em>within the window</em>.
     *
     * <p><strong>Fifteen minutes, and it clears itself.</strong> Long enough to make sustained
     * guessing pointless, short enough that a customer locked out by somebody else's attack is
     * inconvenienced rather than locked out of their money. See the migration for why there is no
     * operator unlock.
     */
    public static LockoutPolicy current() {
        return new LockoutPolicy(10, Duration.ofMinutes(15), Duration.ofMinutes(15));
    }

    public LockoutPolicy {
        if (threshold < 1) {
            throw new IllegalArgumentException("A lockout threshold below 1 locks on success");
        }
        Objects.requireNonNull(window, "window must not be null");
        Objects.requireNonNull(lockFor, "lockFor must not be null");
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("A window of zero expires every failure immediately");
        }
        if (lockFor.isNegative() || lockFor.isZero()) {
            // A lock that has already expired when it is written is a control that looks like one
            // and is none - the exact shape the table's own CHECK constraint refuses.
            throw new IllegalArgumentException("A lock of zero is not a lock");
        }
    }
}
