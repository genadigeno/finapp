package com.finapp.payments;

/**
 * What one callback-hinted inquiry came to (`P15-TSK-036`, ADR-0083). {@link #UNDECIDED} is the one answer that adopts
 * nothing and concludes nothing: the provider gave no usable word and the sweep decides.
 */
public enum HintedOutcome {
    /** The provider's answer moved something: this call's own conditional acted. */
    ADOPTED,
    /** The provider's answer agrees with what the row already holds, or another resolver won. Nothing further to do. */
    CONVERGED,
    /** The provider's answer contradicts what the row holds. Nothing moves, and it is flagged: a break to look at. */
    CONTRADICTED,
    /**
     * The provider denies what the callback claimed: no such clearing, dispute or execution. Nothing moves, and it is
     * counted, because a signed callback the credentialed inquiry denies is what a stolen webhook key looks like.
     */
    REFUTED,
    /** No usable answer: nothing adopted, the sweep decides ({@code INV-LIFE-03}). */
    UNDECIDED,
    /** A durable hint no inquiry ever answered, past its bound. Stored only; no inquiry returns it. */
    EXPIRED;

    /** Whether this ends a durable hint. */
    public boolean concludes() {
        return this != UNDECIDED;
    }

    /** Whether the door counts this delivery as a break rather than as processed. */
    public boolean isBreak() {
        return this == CONTRADICTED || this == REFUTED;
    }
}
