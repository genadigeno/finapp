package com.finapp.risk;

/**
 * Risk's span names (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 15: "spans: {@code RiskFlowScope} per step, no
 * attributes"; bnpl's {@code BnplSpans}) - one per step of section 7.1 a risk flow passes through, each recorded
 * through the platform {@code Spans} port with no attribute at all, so no score, rule name, reason text, amount, party,
 * subject, case or report id can reach the trace backend ({@code INV-RSK-08}, {@code INV-AUD-02}). Reserved here; each
 * step's task records its own.
 */
public final class RiskSpans {

    /** T-eval - an evaluation inside a money-moving command's transaction. */
    public static final String EVALUATE = "risk.evaluate";
    /** T-advise - the read at a door that turns an unsatisfied challenge into a step-up. */
    public static final String ADVISE = "risk.advise";
    /** T-pol - a policy version proposed. */
    public static final String POLICY_PROPOSE = "risk.policy.propose";
    /** T-pol - a policy version activated, its predecessor retired. */
    public static final String POLICY_ACTIVATE = "risk.policy.activate";
    /** T-pol - a policy version rejected. */
    public static final String POLICY_REJECT = "risk.policy.reject";
    /** T-ovr - an override proposed. */
    public static final String OVERRIDE_PROPOSE = "risk.override.propose";
    /** T-ovr - an override decided, and the action it issues. */
    public static final String OVERRIDE_DECIDE = "risk.override.decide";
    /** T-sig - a signal recorded from an event. */
    public static final String SIGNAL_RECORD = "risk.signal.record";
    /** T-mon - the intake leg: journal entries into monitored transactions. */
    public static final String MONITORING_INTAKE = "risk.monitoring.intake";
    /** The reprocessing door - an officer moving the intake's watermark back (`P13-TSK-019`). */
    public static final String MONITORING_REPROCESS = "risk.monitoring.reprocess";
    /** T-mon - the monitoring rules over one subject. */
    public static final String MONITORING_EVALUATE = "risk.monitoring.evaluate";
    /** T-mon - a customer risk profile recomputed. */
    public static final String PROFILE_RECOMPUTE = "risk.profile.recompute";
    /** The analyst's door - a party's customer risk profile read (`P13-TSK-007`). */
    public static final String PROFILE_READ = "risk.profile.read";
    /** T-mon - the rescreening leg. */
    public static final String RESCREEN = "risk.rescreen";
    /**
     * The velocity proof - every live window recomputed from its evidence in one snapshot (`P13-TSK-006` reserves it;
     * `-023`'s schedule records it). T-eval's own velocity steps run inside {@link #EVALUATE}.
     */
    public static final String VELOCITY_PROOF = "risk.velocity.proof";
    /**
     * T-eval / T-mon - alerts raised, born once, and folded (`P13-TSK-015`): {@code Alerts.raiseAll} records it inside
     * the caller's transaction.
     */
    public static final String ALERT_RAISE = "risk.alert.raise";
    /** The suppression door - an alert's suppression proposed, approved or rejected (`-015`'s machine; `-018` records it). */
    public static final String ALERT_SUPPRESS = "risk.alert.suppress";
    /** T-case - a case opened. */
    public static final String CASE_OPEN = "risk.case.open";
    /** T-case - an investigator's act on a case. */
    public static final String CASE_ACT = "risk.case.act";
    /** T-case - a case disposed. */
    public static final String CASE_DISPOSE = "risk.case.dispose";
    /** T-act - an approved action dispatched through its owning domain. */
    public static final String ACTION_DISPATCH = "risk.action.dispatch";
    /** T-case - a downstream action proposed on a case (`P13-TSK-017`). */
    public static final String ACTION_PROPOSE = "risk.action.propose";
    /** T-case - a case's downstream action approved or rejected (`P13-TSK-017`). */
    public static final String ACTION_DECIDE = "risk.action.decide";
    /** T-rep - a regulatory report drafted, approved or filed. */
    public static final String REPORT = "risk.report";
    /** T-list - a screening list version recorded or activated. */
    public static final String LIST = "risk.list";
    /** The explanation door - an assessment read or replayed. */
    public static final String EXPLAIN = "risk.explain";

    private RiskSpans() {
    }
}
