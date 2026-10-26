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
 * counts, never self-corrected) open no case; they are an operator's to act on. <em>As built by `P13-TSK-015`:</em>
 * {@code risk V010} admits them (their own code as the rule, no policy, never attached) but no writer raises them as
 * rows - the transition's settled reading ({@code RISK_LIFECYCLES.md} section 8) stands: their alert is the Prometheus
 * rule of section 15 on {@code finapp.risk.monitoring.unclassified} and {@code finapp.risk.velocity.proof{verdict}};
 * raising them as rows is `-023`'s or `-024`'s to decide, with no migration.
 *
 * <p><strong>A third origin, the fact</strong> (`P13-TSK-025`, owner decision C3): {@link #OBLIGATION_DEFAULTED} is
 * neither a policy rule's finding nor an operator's operational signal - it is a recorded fact (lending's or bnpl's
 * default, a born-once signal) that opens a case. Like an operational kind its own code is its rule and no policy names
 * it ({@link #ownCode()}); unlike one it folds into a case, its {@link #factSubject()} the subject kind it is about.
 */
public enum AlertKind {

    /**
     * A fraud rule fired on a customer (`-013`, `-014`): a payment, transfer or withdrawal pattern - since `P13-TSK-014`
     * the party's own refund and chargeback ratios and its BNPL applications across merchants, raised by the profile
     * leg on the {@code PARTY}.
     */
    FRAUD(CaseKind.FRAUD),

    /** An account-takeover sequence - a credential change or recovery, then a new payee, a new device, a withdrawal (`-013`). */
    ACCOUNT_TAKEOVER(CaseKind.ATO),

    /**
     * A merchant's abuse ratio - its refunds or chargebacks over its payments (`P13-TSK-014`), raised by T-eval of a
     * payment to it, on the {@code MERCHANT}. A customer's abuse (its own ratios, BNPL stacking) is {@link #FRAUD}: the
     * case of a merchant is not the case of a customer.
     */
    MERCHANT_ABUSE(CaseKind.MERCHANT_ABUSE),

    /** A monitoring rule fired over the ledger's journal (`-020`). */
    AML(CaseKind.AML),

    /** A rescreening against an activated list version recorded a hit (`-022`). */
    SCREENING_HIT(CaseKind.AML),

    /** A journal entry of a posting-key namespace the intake's register does not know (`-019`). Operational. */
    UNCLASSIFIED_TRANSACTION(null),

    /** A velocity window whose figures differ from the monitored transactions it counts (`-023`). Operational. */
    VELOCITY_DIVERGENCE(null),

    /**
     * A loan or a BNPL obligation defaulted (`P13-TSK-025`, C3) - a fact: lending's {@code LoanDefaulted} or bnpl's
     * {@code BnplDefaulted}, recorded as its born-once signal, about the {@link SubjectKind#CREDIT_AGREEMENT}; it folds
     * into the agreement's party's {@link CaseKind#COLLECTIONS} case ({@link CollectionsCases}).
     */
    OBLIGATION_DEFAULTED(CaseKind.COLLECTIONS, SubjectKind.CREDIT_AGREEMENT);

    private final CaseKind caseKind;
    private final SubjectKind factSubject;

    AlertKind(CaseKind caseKind) {
        this(caseKind, null);
    }

    AlertKind(CaseKind caseKind, SubjectKind factSubject) {
        this.caseKind = caseKind;
        this.factSubject = factSubject;
        if (factSubject != null && caseKind == null) {
            throw new IllegalStateException("a fact opens a case: " + name());
        }
    }

    /** The kind of case this alert folds into - empty for an operational alert, which opens none. */
    public Optional<CaseKind> caseKind() {
        return Optional.ofNullable(caseKind);
    }

    /** True when the alert is an operator's operational signal rather than an investigation's. */
    public boolean operational() {
        return caseKind == null;
    }

    /** True when the alert records a fact (`P13-TSK-025`) - raised from a born-once signal, not by a policy's rule. */
    public boolean fact() {
        return factSubject != null;
    }

    /** The subject kind a fact kind is about - empty for a rule's or an operational kind. */
    public Optional<SubjectKind> factSubject() {
        return Optional.ofNullable(factSubject);
    }

    /**
     * True when the alert's rule is its own code and no policy version names it: an operational kind or a fact kind
     * ({@code risk V017}'s {@code risk_alert_rule_matches_kind}, {@code risk_alert_policy_named}).
     */
    public boolean ownCode() {
        return operational() || fact();
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
