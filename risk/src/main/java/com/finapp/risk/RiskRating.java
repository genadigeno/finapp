package com.finapp.risk;

/**
 * The customer risk profile's rating (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 12.5; {@code INV-RSK-10}) - set by
 * the {@code MONITORING} policy's rating bands over the party's signal score, versioned and append-only (`-007`), read by
 * every evaluation ({@link RuleKind#PROFILE_RATING}) and answered to credit's seam with seam version 2.
 *
 * <p>Declared least to most severe, so "at or above" is the declaration order. {@link #PROHIBITED} is never a band's
 * answer: it is set only by a four-eyes disposition ({@code INV-RSK-07}), and a policy may deny on it.
 */
public enum RiskRating {
    LOW,
    MEDIUM,
    HIGH,
    PROHIBITED;

    /** True when this rating is at or above {@code floor} in severity. */
    public boolean atLeast(RiskRating floor) {
        return ordinal() >= floor.ordinal();
    }

    /** True for the one rating a band never sets. */
    public boolean onlyByDisposition() {
        return this == PROHIBITED;
    }
}
