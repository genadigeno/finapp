package com.finapp.identity;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * How strongly the person holding a session proved who they are (ADR-0030, {@code INV-IDN-05}).
 *
 * <h2>A level, and never a boolean</h2>
 *
 * <p>A boolean answers <em>"did this session do MFA?"</em> — and <strong>every real MFA bypass is a
 * route that produces a session the boolean says is fine</strong>. A recovery flow, a refresh, an
 * older session, a second-factor enrolment: each is a way to arrive at a session, and with a flag
 * each one must <em>remember</em> to set it correctly. The one that forgets is the bypass, and it is
 * found by an attacker rather than by a reviewer.
 *
 * <p>With a level, an operation asks <em>"was this established to at least {@code MULTI_FACTOR}?"</em>
 * and a route that cannot answer that question cannot produce a session that passes. The check moves
 * from every producer to every <strong>consumer</strong> — and consumers are the ones that have the
 * requirement, so they are the ones that can be trusted to state it.
 *
 * <h2>Declared in escalation order</h2>
 *
 * <p>{@link #ordinal()} is therefore meaningful and {@link #atLeast} is well defined — the same
 * construction {@code TestTier} uses. Reordering these constants changes the meaning of every
 * comparison in the platform, which is why the ordering is stated here rather than inferred.
 *
 * <h2>Two of the three have no producer yet, and that is the seam rather than dead code</h2>
 *
 * <p>Only {@code PASSWORD} is reachable today: {@code P1-TSK-017} produces {@code MULTI_FACTOR} and
 * WebAuthn produces {@code STRONG}. Declaring one value would be a boolean wearing an enum's
 * clothes, which is precisely what ADR-0030 decided against — the three levels <em>are</em> the
 * decision, and the enrolment that reaches them is separate work.
 *
 * <p>Persisted values, in a generated {@code CHECK} constraint — the {@code P0-TSK-022} pattern.
 */
public enum AssuranceLevel {

    /** A single knowledge factor: the customer typed their password. */
    PASSWORD,

    /** A knowledge factor plus a possession or inherence factor. */
    MULTI_FACTOR,

    /**
     * A phishing-resistant authenticator — WebAuthn, a passkey.
     *
     * <p>Above {@code MULTI_FACTOR} rather than beside it: a TOTP code can be read out over the
     * telephone to somebody claiming to be support, and a passkey cannot. They are different
     * strengths of the same thing, not different kinds.
     */
    STRONG;

    /**
     * Whether this level satisfies a requirement for {@code required}.
     *
     * <p>The whole point of the type. An operation states what it needs and asks; it never inspects
     * which factors were used, because that would put the enumeration of routes back into every
     * consumer — which is the boolean's failure with more steps.
     */
    public boolean atLeast(AssuranceLevel required) {
        return ordinal() >= required.ordinal();
    }

    /**
     * The levels as a SQL literal list, for the {@code CHECK} constraint.
     *
     * <p>Generated rather than typed out, so adding a level without a migration cannot be done
     * quietly: the constraint and the enum are one definition.
     */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(level -> "'" + level.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
