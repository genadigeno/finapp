package com.finapp.risk;

import java.util.Objects;
import java.util.Optional;

/**
 * What raised an alert (`P13-TSK-001`; {@code PHASE_13_PLAN.md} sections 3.1, 12.6, 12.7, 15; ADR-0110) - the capability
 * behind it, which decides the kind of case the alert folds into. An alert is born once per subject, rule and window
 * ({@code INV-RSK-06}, `-015`); its folding into the subject's open case of {@link #caseKind} is `-016`'s.
 *
 * <p>Two kinds are operational: an {@link #UNCLASSIFIED_TRANSACTION} (a journal namespace the intake's register does
 * not know - nothing passes unseen) and a {@link #VELOCITY_DIVERGENCE} (a window whose figures no longer equal what it
 * counts, never self-corrected) open no case; they are an operator's to act on and fire the Prometheus rules of section
 * 15 beside the alert row.
 */
public enum AlertKind {

    /** A synchronous fraud rule fired (`-013`, `-014`): a payment, transfer or withdrawal pattern. */
    FRAUD(CaseKind.FRAUD),

    /** An account-takeover sequence - a credential change or recovery, then a new payee, a new device, a withdrawal (`-013`). */
    ACCOUNT_TAKEOVER(CaseKind.ATO),

    /** A merchant's or customer's abuse ratio - refunds, chargebacks, BNPL applications across merchants (`-014`). */
    MERCHANT_ABUSE(CaseKind.MERCHANT_ABUSE),

    /** A monitoring rule fired over the ledger's journal (`-020`). */
    AML(CaseKind.AML),

    /** A rescreening against an activated list version recorded a hit (`-022`). */
    SCREENING_HIT(CaseKind.AML),

    /** A journal entry of a posting-key namespace the intake's register does not know (`-019`). Operational. */
    UNCLASSIFIED_TRANSACTION(null),

    /** A velocity window whose figures differ from the monitored transactions it counts (`-023`). Operational. */
    VELOCITY_DIVERGENCE(null);

    private final CaseKind caseKind;

    AlertKind(CaseKind caseKind) {
        this.caseKind = caseKind;
    }

    /** The kind of case this alert folds into - empty for an operational alert, which opens none. */
    public Optional<CaseKind> caseKind() {
        return Optional.ofNullable(caseKind);
    }

    /** True when the alert is an operator's operational signal rather than an investigation's. */
    public boolean operational() {
        return caseKind == null;
    }

    /** The kind that folds into {@code caseKind} first in declaration order - for the suites' fixtures. */
    public static AlertKind firstFor(CaseKind caseKind) {
        Objects.requireNonNull(caseKind, "caseKind");
        for (AlertKind kind : values()) {
            if (kind.caseKind == caseKind) {
                return kind;
            }
        }
        throw new IllegalArgumentException("no alert kind folds into " + caseKind);
    }
}
