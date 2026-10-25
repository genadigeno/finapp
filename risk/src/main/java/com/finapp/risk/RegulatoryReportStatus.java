package com.finapp.risk;

/**
 * Where a regulatory report stands (`P13-TSK-021`; {@code risk V016}'s {@code regulatory_report_status_is_known};
 * {@code PHASE_13_PLAN.md} section 5; {@code RISK_LIFECYCLES.md} section 4.8; ADR-0111 decisions 5-6).
 *
 * <pre>
 *   DRAFT ──★ a second AML_REPORT_APPROVE holder──> APPROVED ──AML_REPORT_FILE──> FILED ──the authority's answer──> ACKNOWLEDGED | REJECTED
 * </pre>
 *
 * <p>{@code FILED} is the platform's decision to file, committed before the provider is called; the authority's answer is
 * a later fact the filing's drive records. A {@code REJECTED} report is final - the case may then be reported again by a
 * new draft; an {@code ACKNOWLEDGED} one is the case's one accepted filing.
 */
public enum RegulatoryReportStatus {

    /** Drafted from a disposed confirmed-suspicious AML case; awaiting a second person. */
    DRAFT,

    /** Approved by a second {@code AML_REPORT_APPROVE} holder; awaiting its filing. */
    APPROVED,

    /** Filed - committed before the call; the authority's answer awaited, inquired by the filing reference. */
    FILED,

    /** The authority acknowledged it - the case's one accepted filing. */
    ACKNOWLEDGED,

    /** The authority refused it - final; a new draft of the case may follow. */
    REJECTED;

    /** True while the report occupies its case's one live-or-accepted slot ({@code regulatory_report_one_live_per_case}). */
    public boolean occupiesItsCase() {
        return this != REJECTED;
    }

    /** True once the authority has answered - nothing moves any more. */
    public boolean concluded() {
        return this == ACKNOWLEDGED || this == REJECTED;
    }

    /** True once filed - the content and the persons are history, and only the authority's answer is awaited. */
    public boolean filed() {
        return this == FILED || concluded();
    }
}
