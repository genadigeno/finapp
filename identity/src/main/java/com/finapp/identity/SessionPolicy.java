package com.finapp.identity;

import java.time.Duration;
import java.util.Objects;

/**
 * How long a session lives (`P1-TSK-013`, ADR-0030).
 *
 * <h2>Two bounds, and the policy is only consulted at issue</h2>
 *
 * <p>The values are copied onto the session when it is issued and never read again for that session.
 * That is {@code INV-HIST-04}'s reasoning: <strong>changing this must not retroactively extend
 * sessions issued under the old policy</strong>. Shortening the idle timeout protects sessions
 * issued from that moment onward; it must not lengthen anything, and it cannot.
 *
 * <p>Constants rather than configuration, for the reason {@link LockoutPolicy} gives: a control
 * whose strength is a deployment setting is one nobody can reason about.
 */
public record SessionPolicy(Duration idleTimeout, Duration absoluteLifetime) {

    /**
     * Thirty minutes idle, twelve hours absolute.
     *
     * <p><strong>Thirty minutes idle.</strong> Long enough that a customer reading a statement is
     * not thrown out mid-task, short enough that a session left open on a shared machine is not
     * usable an hour later. It is refreshed on use, so it bounds inactivity rather than usage.
     *
     * <p><strong>Twelve hours absolute.</strong> The bound that cannot be extended by using the
     * session, which is what makes it the one that matters against a stolen identifier: an attacker
     * who uses it steadily keeps the idle bound alive for ever and still loses the session within a
     * working day. Deliberately not twenty-four hours - a session should not outlive the sitting in
     * which it was created.
     *
     * <p>Neither is a financial decision yet. Phase 4's step-up for high-value transfers may want a
     * shorter bound for an elevated session, and that is a separate policy rather than a change to
     * this one.
     */
    public static SessionPolicy current() {
        return new SessionPolicy(Duration.ofMinutes(30), Duration.ofHours(12));
    }

    public SessionPolicy {
        Objects.requireNonNull(idleTimeout, "idleTimeout must not be null");
        Objects.requireNonNull(absoluteLifetime, "absoluteLifetime must not be null");
        if (idleTimeout.isNegative() || idleTimeout.isZero()) {
            throw new IllegalArgumentException("An idle timeout of zero expires every session at once");
        }
        if (absoluteLifetime.isNegative() || absoluteLifetime.isZero()) {
            throw new IllegalArgumentException("An absolute lifetime of zero is not a lifetime");
        }
        if (idleTimeout.compareTo(absoluteLifetime) > 0) {
            // Not merely odd: an idle bound beyond the absolute one can never be reached, so the
            // policy would silently have one bound rather than the two ADR-0030 requires.
            throw new IllegalArgumentException(
                    "An idle timeout longer than the absolute lifetime can never be reached, which"
                            + " leaves the policy with one bound rather than two");
        }
    }
}
