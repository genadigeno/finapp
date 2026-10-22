package com.finapp.risk;

/**
 * How a case ends (`P13-TSK-001`; {@code PHASE_13_PLAN.md} sections 3.1, 5; ADR-0110; {@code INV-RSK-07}).
 *
 * <p>A disposition that <strong>confirms</strong> fraud or suspicion, that <strong>refers</strong> the case onward, or
 * that issues an action is two persons' act on a taken case; a close as a false positive or with no action is one
 * person's reasoned act and may be made straight from {@code OPEN}. The rule is declared here, enforced by `-016` at the
 * domain and by {@code CHECK}; a {@code CONFIRMED_SUSPICIOUS} disposition is what a regulatory report is drafted from
 * (`-021`), and {@code PROHIBITED} is set on a profile only by a four-eyes disposition.
 */
public enum Disposition {

    /** The alerts were a false positive; nothing follows. One person. */
    CLOSED_FALSE_POSITIVE(false),

    /** Investigated; no action warranted. One person. */
    CLOSED_NO_ACTION(false),

    /** Fraud confirmed; actions follow through the owning domains (`-017`). Two persons. */
    CONFIRMED_FRAUD(true),

    /** Suspicion confirmed; a regulatory report may be drafted (`-021`). Two persons. */
    CONFIRMED_SUSPICIOUS(true),

    /** Referred onward - to another desk, or a recommendation the owning domain decides (a reversal is never risk's). Two persons. */
    REFERRED(true);

    private final boolean fourEyes;

    Disposition(boolean fourEyes) {
        this.fourEyes = fourEyes;
    }

    /** True when the disposition needs the case taken and a second person's approval ({@code INV-RSK-07}). */
    public boolean fourEyes() {
        return fourEyes;
    }

    /** True when the disposition confirms fraud or suspicion. */
    public boolean confirms() {
        return this == CONFIRMED_FRAUD || this == CONFIRMED_SUSPICIOUS;
    }
}
