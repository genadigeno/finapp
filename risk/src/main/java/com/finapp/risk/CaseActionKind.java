package com.finapp.risk;

/**
 * The kinds of investigator action (`P13-TSK-016`; {@code PHASE_13_PLAN.md} section 3.1 "Investigator Action";
 * ADR-0110 decision 4; {@code risk V012}'s {@code risk_case_action}) - every one append-only, a person's, audited. The
 * vocabulary is the plan's whole list so the table's {@code CHECK} need not be re-made later: the three
 * {@code ACTION_*} kinds are `-017`'s (a downstream action proposed, approved or rejected against the case - written by
 * {@link RiskActions}, `P13-TSK-017`) and {@link #REPORT_DRAFTED} is `-021`'s.
 */
public enum CaseActionKind {

    /** A note - the text the act's reason, bounded and screened. */
    NOTE,

    /** Evidence attached by reference - signal, assessment and journal entry ids, never copies. */
    EVIDENCE_ATTACHED,

    /** The case taken by an investigator - {@code OPEN → IN_INVESTIGATION}, or reassigned to another. */
    TAKEN,

    /** The case escalated with a reason - its level raised one step, its queue named. */
    ESCALATED,

    /** A downstream action proposed against the case (`-017`). */
    ACTION_PROPOSED,

    /** A downstream action approved by a second person (`-017`). */
    ACTION_APPROVED,

    /** A downstream action rejected (`-017`). */
    ACTION_REJECTED,

    /** A disposition proposed - for a one-person close, decided by this act. Its id is the disposition's id. */
    DISPOSITION_PROPOSED,

    /** A four-eyes disposition approved by a second person - the case {@code DISPOSED}. */
    DISPOSITION_APPROVED,

    /** A disposition rejected, or withdrawn by its proposer - the case stays open. */
    DISPOSITION_REJECTED,

    /** A regulatory report drafted from the case (`-021`). */
    REPORT_DRAFTED;

    /**
     * True when this kind carries a person's text, which is then required - every act `-016` writes but the take (the
     * evidence's text says why it was attached), and `-017`'s three action acts (their reasons; {@code risk V013}
     * re-made {@code risk_case_action_texted} by name).
     */
    public boolean texted() {
        return this == NOTE || this == EVIDENCE_ATTACHED || this == ESCALATED || this == ACTION_PROPOSED
                || this == ACTION_APPROVED || this == ACTION_REJECTED || this == DISPOSITION_PROPOSED
                || this == DISPOSITION_APPROVED || this == DISPOSITION_REJECTED;
    }
}
