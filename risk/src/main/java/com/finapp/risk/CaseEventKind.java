package com.finapp.risk;

/**
 * What one step of a case's history recorded (`P13-TSK-016`; {@code risk V012}'s {@code risk_case_event} - one row per
 * revision, {@code PRIMARY KEY (case_id, revision)}; {@code RISK_LIFECYCLES.md} section 3, "an append-only history per
 * machine"). The system's steps name the alert or assessment they attached; a person's name the action they wrote.
 */
public enum CaseEventKind {

    /** The case born at revision 0 - by T-eval's decision, an alert's folding, or by hand. */
    OPENED(false),

    /** The system attached a born alert to the open case (`-015`'s folding, the system's dedupe). */
    ALERT_ATTACHED(false),

    /** The system attached a sealed assessment to the open case (T-eval's {@code REVIEW} or {@code HOLD}). */
    ASSESSMENT_ATTACHED(false),

    /** A person took the case, or took it over. */
    TAKEN(true),

    /** A person noted the case. */
    NOTED(true),

    /** A person attached evidence by reference. */
    EVIDENCE_ATTACHED(true),

    /** A person escalated the case. */
    ESCALATED(true),

    /** A person proposed a four-eyes disposition - awaiting a second person. */
    DISPOSITION_PROPOSED(true),

    /** A person rejected (or withdrew) the standing proposal. */
    DISPOSITION_REJECTED(true),

    /** The case reached its disposition - a one-person close, or a second person's approval. */
    DISPOSED(true),

    /** A person proposed a downstream action on the case (`P13-TSK-017`; {@code risk V013}). */
    ACTION_PROPOSED(true),

    /** A second person approved the case's standing action - dispatched by T-act after the commit (`P13-TSK-017`). */
    ACTION_APPROVED(true),

    /** A person rejected (or withdrew) the case's standing action (`P13-TSK-017`). */
    ACTION_REJECTED(true);

    private final boolean persons;

    CaseEventKind(boolean persons) {
        this.persons = persons;
    }

    /** True when the step is a person's act, naming the action it wrote. */
    public boolean persons() {
        return persons;
    }
}
