package com.finapp.kyc;

import com.finapp.platform.audit.AuditableAction;

/**
 * What the {@code kyc} module does that must produce an audit record.
 *
 * <p>Declared per module rather than centrally: actions belong to the module that performs them,
 * and {@code platform} sits below every business module. See {@code AUDITABLE_ACTIONS.md} §2.
 *
 * <p><strong>Deliberately small, and it will grow with the tasks that emit each action.</strong>
 * The licence is {@code P1-TSK-003}'s: declare what the module's documented responsibility makes
 * certain — {@code MODULE_ARCHITECTURE.md} §`kyc` states reviewer actions carry reason codes and
 * document access is audited, and {@code INV-KYC-02}, {@code -04} and {@code -06} each name
 * their record outright — never what a later task's design will shape. Case opening joined at
 * {@code P2-TSK-005}, the task whose design fixed its meaning; check outcomes remain
 * <em>absent on purpose</em>, {@code P2-TSK-009}'s to declare.
 *
 * <p>{@code KYC_CASE_OPENED} is emitted since {@code P2-TSK-007} and
 * {@code DOCUMENT_CONTENT_READ} since {@code P2-TSK-008}; each still-unemitted constant names its
 * owning task in {@code AuditCompletenessTest.NOT_YET_EMITTED}, so "deliberately not built yet"
 * and "somebody removed the audit call" stay distinguishable. <em>(This paragraph said "nothing
 * here is emitted yet" until {@code P2-TSK-008} — stale from the day the consumer landed, the
 * recurring claim-the-code-outgrew class.)</em>
 */
public enum KycAuditAction implements AuditableAction {

    /**
     * A KYC/KYB case was opened for a customer.
     *
     * <p>The record that starts every defensible-decision trail: the decision (`INV-KYC-02`)
     * references a case, and a case nobody can date or attribute is a chain with a missing
     * first link. No reason required — opening is either the customer's own act
     * ({@code POST /v1/me/kyc}, {@code P2-TSK-006}) or the platform reacting to a registration
     * ({@code P2-TSK-007}), and neither is an action taken <em>against</em> somebody.
     */
    KYC_CASE_OPENED(
            "kyc.CaseOpened",
            "A KYC/KYB case was opened for a customer, under a named policy version.",
            false),

    /**
     * The platform recorded a KYC/KYB decision on a case.
     *
     * <p>The record the phase exists to make defensible ({@code INV-KYC-02}): immutable,
     * attributable, policy-pinned, referencing its evidence. <strong>Reason required</strong> —
     * the invariant itself makes the reason a {@code NOT NULL} column, because a decision nobody
     * can explain is a decision nobody can defend, whether it was a reviewer's or the platform's
     * under an automatic policy. Emitted by {@code P2-TSK-013}.
     */
    KYC_DECISION_RECORDED(
            "kyc.DecisionRecorded",
            "A KYC/KYB decision was recorded on a case, naming its actor, reason and policy"
                    + " version.",
            true),

    /**
     * A person resolved a review task.
     *
     * <p>{@code INV-KYC-04}: a hit never auto-clears and never auto-rejects — a name match is a
     * probability, silently cleared is a sanctions breach, silently rejected is a person refused
     * service by string similarity. <strong>Reason required</strong>, per the invariant: the
     * resolution is an elevated action against somebody's case and its justification is the
     * record's point. Emitted by {@code P2-TSK-012}.
     *
     * <p><em>Renamed from {@code kyc.ScreeningHitResolved} before its first emission</em>
     * (`P2-TSK-012`): a task is raised by <strong>any</strong> check type's {@code HIT} and by a
     * budget-exhausted {@code INDETERMINATE} (`P2-TSK-010`), so a code claiming every resolution
     * concerns a screening hit is a name that can be false — the {@code NOT_ACTIVE} lesson. A
     * vocabulary correction is free exactly while zero records carry the code, and impossible
     * afterwards ({@code INV-HIST-03}).
     */
    REVIEW_RESOLVED(
            "kyc.ReviewResolved",
            "A reviewer resolved a review task - the judgement a non-clean check owed a person -"
                    + " with its justification.",
            true),

    /**
     * A reviewer read a case.
     *
     * <p>The {@code DOCUMENT_CONTENT_READ} reasoning, one level up ({@code INV-KYC-06}'s shape):
     * the reviewer is the platform's canonical insider surface — somebody with every right to
     * read cases, browsing them — and the trail of <em>who looked</em> is the control. No reason
     * required, deliberately: reading a case is the routine act of every legitimate review, and
     * a mandatory reason on a routine action produces a column of {@code "review"}. Emitted by
     * {@code P2-TSK-012}, the reviewer's read path.
     */
    KYC_CASE_READ(
            "kyc.CaseRead",
            "A reviewer read a KYC/KYB case, naming who looked and at which case.",
            false),

    /**
     * A verification check reached its outcome.
     *
     * <p>Declared and emitted by `P2-TSK-009`, the task whose design fixed its meaning — the
     * "check outcomes are absent on purpose" remainder, arrived. The record is what ties a
     * provider's answer into the defensible-decision trail: the decision ({@code INV-KYC-02})
     * references checks, and a check whose outcome nobody can date or attribute is a chain with
     * a broken middle link. No reason required — the outcome is the platform's normalisation of
     * a provider's answer, and the reason-bearing acts are the review resolution and the
     * decision. The actor is the platform ({@code enterSystem()}, the fifth enumerated site):
     * nobody is present when a machine records what a machine answered.
     */
    KYC_CHECK_COMPLETED(
            "kyc.CheckCompleted",
            "A verification check reached its normalised outcome, with its evidence retained.",
            false),

    /**
     * A beneficial owner was declared onto a KYB case.
     *
     * <p>The declaration changes what a regulator-facing decision will rest on
     * ({@code INV-KYC-02}: the owner set is part of the decision's evidence), which makes it an
     * action of consequence ({@code INV-AUD-01}). No reason required - declaring the graph is
     * the declarant's own compliance act, taken for and against nobody (the consent-pair
     * argument). Emitted by {@code P2-TSK-015}'s declaration service; the acting person arrives
     * with `P2-TSK-016`'s endpoint.
     */
    KYB_OWNER_DECLARED(
            "kyc.OwnerDeclared",
            "A beneficial owner was declared onto a KYB case, pinning the verification case the"
                    + " graph rests on.",
            false),

    /**
     * Somebody read document content.
     *
     * <p>{@code INV-KYC-06}: identity documents are the most sensitive bytes the platform holds
     * before card data, and the reader is an insider-threat surface — <em>the trail of who
     * looked is the control</em>. No reason required, deliberately: reading a document is the
     * routine act of every legitimate review, and a mandatory reason on a routine action
     * produces a column of {@code "review"} — how a required field stops meaning anything
     * ({@code AUDITABLE_ACTIONS.md} §4). What the invariant demands is the actor, and the
     * record names one. Emitted by {@code P2-TSK-008}, the one audited read path.
     */
    DOCUMENT_CONTENT_READ(
            "kyc.DocumentContentRead",
            "Document content was read, naming who looked and at which document.",
            false);

    private final String code;
    private final String description;
    private final boolean requiresReason;

    KycAuditAction(String code, String description, boolean requiresReason) {
        this.code = code;
        this.description = description;
        this.requiresReason = requiresReason;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean requiresReason() {
        return requiresReason;
    }
}
