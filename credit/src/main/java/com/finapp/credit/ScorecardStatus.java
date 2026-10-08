package com.finapp.credit;

/**
 * A scorecard model version's machine (`P10-TSK-011`; {@code credit V006}'s edge trigger holds it for every writer):
 * {@code PROPOSED -> ACTIVE | REJECTED}, {@code ACTIVE -> RETIRED}, the retirement only beside its successor.
 */
public enum ScorecardStatus {

    /** Awaiting a decision; its bands are frozen. At most one per family. */
    PROPOSED,

    /** Scores new decisions from its effective start. At most one per family. */
    ACTIVE,

    /** Superseded; still scores every decision that pinned it ({@code INV-HIST-04}). */
    RETIRED,

    /** Rejected or withdrawn; scored nothing. */
    REJECTED
}
