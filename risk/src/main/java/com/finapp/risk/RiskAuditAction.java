package com.finapp.risk;

import com.finapp.platform.audit.AuditableAction;
import lombok.RequiredArgsConstructor;

/**
 * The risk module's audited acts (`P13-TSK-001`; {@code INV-AUD-01}, {@code INV-AUD-04}, {@code INV-RSK-07};
 * {@code PHASE_13_PLAN.md} section 11) - declared per task, the {@code BnplAuditAction} precedent: the two refusals of
 * the role split arrive with the roles, each catalogued in {@code AUDITABLE_ACTIONS.md} and, until the door that raises
 * it lands, named in {@code AuditCompletenessTest}'s deliberate absences with that task; every later act arrives with
 * the task that raises it. Never an amount, a score, a rule name, a note or a subject in a summary ({@code INV-AUD-02},
 * {@code INV-RSK-08}).
 */
@RequiredArgsConstructor
public enum RiskAuditAction implements AuditableAction {

    /** A proposer tried to approve their own proposal and was refused. */
    SELF_APPROVAL_REFUSED(
            "risk.SelfApprovalRefused",
            "A proposer tried to approve their own risk proposal - a policy version's activation (P13-TSK-002), an"
                    + " override's approval (P13-TSK-012), a disposition's, an action's or a suppression's approval"
                    + " (P13-TSK-016, -017, -018), a regulatory report's approval (P13-TSK-021) - and was refused"
                    + " 403 risk.SelfApprovalRefused before anything was written (INV-AUD-04, INV-RSK-07; also held by"
                    + " each table's four-eyes CHECK). Recorded FAILED in a transaction of its own after the act rolled"
                    + " back, so the attempt survives. Its target the proposal; the summary names it and the act -"
                    + " never a subject, a score or an amount.",
            false),

    /** An investigator or officer tried to act on their own party's subject and was refused. */
    SELF_DEALING_REFUSED(
            "risk.SelfDealingRefused",
            "A holder tried to read or work a case, approve an override or file a report whose subject is their own"
                    + " party - or could not be placed in any party, which fails closed (the Phase 12 -> 13 gate's"
                    + " A7-6 applied forward) - and was refused 403 risk.SelfDealingRefused before anything was written"
                    + " (PHASE_13_PLAN.md section 11; first raised by P13-TSK-016's case doors). Recorded FAILED in a"
                    + " transaction of its own. Its target the case, override or report; the summary names it and the"
                    + " act - never the party.",
            false),

    /** An officer ordered the monitoring intake reprocessed from an earlier watermark. */
    REPROCESSING_ORDERED(
            "risk.ReprocessingOrdered",
            "A holder of RISK_POLICY_ADMINISTER ordered the monitoring intake reprocessed from an earlier watermark"
                    + " (POST /v1/operator/risk/monitoring/reprocessing, P13-TSK-019): the watermark row locked, judged"
                    + " at or before its position and moved back to the sequence named, its permit left due now. The"
                    + " re-run creates nothing new - every monitored transaction is born once (INV-RSK-09). Its target"
                    + " the watermark row (monitoring_watermark, the source its id); the reason the officer's; the"
                    + " summary the sequence restarted from and where the watermark stood - never the reason, an"
                    + " amount or a party.",
            true);

    private final String code;
    private final String description;
    private final boolean requiresReason;

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
