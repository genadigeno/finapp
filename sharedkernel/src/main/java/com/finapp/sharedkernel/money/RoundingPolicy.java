package com.finapp.sharedkernel.money;

import java.math.RoundingMode;
import java.util.Objects;

/**
 * A named way of resolving an amount that does not fit its currency exactly.
 *
 * <p><strong>Why a named policy rather than {@link RoundingMode} directly.</strong>
 * {@code INV-HIST-04} lists "rounding policy" among the versioned artefacts that must be
 * recorded on the decision that used them, so that a past decision can be reproduced and
 * defended. A policy therefore needs a stable name of its own — {@link #policyName()} — which
 * is what gets recorded from Phase 6 (fees) onward. A bare JDK enum constant would work today
 * and be the wrong thing to have persisted by then.
 *
 * <p><strong>The trap this type exists to document.</strong> {@link #TOWARDS_ZERO} and
 * {@link #FLOOR} are identical for positive amounts and differ for negative ones:
 * −2.5 rounds to −2 towards zero and to −3 towards negative infinity. Debits are negative,
 * so choosing the wrong one produces an error that is invisible in testing with positive
 * amounts and systematically wrong in production. The same holds for {@link #AWAY_FROM_ZERO}
 * and {@link #CEILING}.
 *
 * <p><strong>Deliberately absent.</strong>
 * {@code RoundingMode.HALF_DOWN} — it biases ties toward zero with no financial
 * justification, and its existence invites someone to pick it because it sounds like a pair
 * with HALF_UP. {@code RoundingMode.UNNECESSARY} — refusing to round is not a rounding
 * policy; that behaviour is {@link Money#of(java.math.BigDecimal, CurrencyCode)}, which takes
 * no policy at all.
 *
 * <p>There is no default. Every rounding operation names its policy at the call site, which
 * is what {@code INV-MON-03} requires: implicit rounding is the most common source of
 * unexplainable cent-level drift.
 */
public enum RoundingPolicy {

    /**
     * Round to nearest; on an exact tie, round to the even neighbour. "Banker's rounding".
     *
     * <p>The usual choice for splitting and for converting a computed amount to money,
     * because over many operations it does not systematically favour either party. HALF_UP
     * gains roughly half a minor unit per tie, which at volume is a real transfer of value in
     * one direction.
     */
    HALF_EVEN(RoundingMode.HALF_EVEN),

    /**
     * Round to nearest; on an exact tie, round away from zero.
     *
     * <p>What most people mean by "round normally", and what several tax and retail
     * jurisdictions require. Use it where a rule says to, not by default — it is biased.
     */
    HALF_UP(RoundingMode.HALF_UP),

    /** Truncate toward zero. −2.7 becomes −2. Contrast {@link #FLOOR}. */
    TOWARDS_ZERO(RoundingMode.DOWN),

    /** Round away from zero. −2.1 becomes −3. Contrast {@link #CEILING}. */
    AWAY_FROM_ZERO(RoundingMode.UP),

    /**
     * Round toward negative infinity. −2.1 becomes −3, 2.9 becomes 2.
     *
     * <p>The conservative choice when the amount is a credit to a counterparty: it never
     * gives away more than is owed.
     */
    FLOOR(RoundingMode.FLOOR),

    /**
     * Round toward positive infinity. −2.9 becomes −2, 2.1 becomes 3.
     *
     * <p>The conservative choice when the amount is something being charged or reserved: it
     * never reserves less than is needed.
     */
    CEILING(RoundingMode.CEILING);

    private final RoundingMode mode;

    RoundingPolicy(RoundingMode mode) {
        this.mode = mode;
    }

    /**
     * The stable identifier recorded alongside a decision that used this policy
     * ({@code INV-HIST-04}). It is the constant's name, and is part of this type's contract:
     * renaming a constant would orphan every decision already recorded against it.
     */
    public String policyName() {
        return name();
    }

    /** The underlying JDK rounding mode. */
    public RoundingMode mode() {
        return mode;
    }

    /**
     * Resolves a policy from its recorded name.
     *
     * @throws IllegalArgumentException if the name is not a known policy — an unrecognised
     *     policy is never silently replaced with a default, because that would change the
     *     meaning of the decision being replayed
     */
    public static RoundingPolicy ofName(String policyName) {
        Objects.requireNonNull(policyName, "policyName must not be null");
        for (RoundingPolicy policy : values()) {
            if (policy.policyName().equals(policyName)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("Unknown rounding policy: '" + policyName + "'");
    }
}
