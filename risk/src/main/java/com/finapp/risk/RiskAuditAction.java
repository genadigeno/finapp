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

    /** A risk policy version was proposed for one operation (`P13-TSK-002`). */
    POLICY_PROPOSED(
            "risk.PolicyProposed",
            "A holder of RISK_POLICY_ADMINISTER proposed a risk policy version for one operation"
                    + " (POST /v1/operator/risk/policies, keyed risk.policy:<actor>, P13-TSK-002; ADR-0109): rules as"
                    + " data of closed kinds with typed parameters, weights, score bands, fail-safe bands (C1), the"
                    + " challenge assurance, the override threshold (C7) and the hold validity - judged whole at the door"
                    + " (422 risk.PolicyInvalid), immutable from this moment and awaiting a DIFFERENT holder's activation."
                    + " The summary names the operation, the version and the rule count - never a threshold or a weight.",
            true),

    /** A risk policy version was activated by a second person, its predecessor retired beside it. */
    POLICY_ACTIVATED(
            "risk.PolicyActivated",
            "A holder of RISK_POLICY_ADMINISTER other than its proposer activated a risk policy version from a"
                    + " MULTI_FACTOR session (POST /v1/operator/risk/policies/{id}/activation; INV-RSK-05, INV-AUD-04,"
                    + " the four-eyes CHECK beneath), retiring the operation's predecessor in the same transaction under"
                    + " advisory namespace 13 so the pin never reads \"neither\"; the event risk.RiskPolicyActivated"
                    + " beside it. The summary names the operation and both versions.",
            true),

    /** A proposed risk policy version was rejected, or withdrawn by its proposer. */
    POLICY_REJECTED(
            "risk.PolicyRejected",
            "A proposed risk policy version was rejected - or withdrawn by its proposer"
                    + " (POST /v1/operator/risk/policies/{id}/rejection, P13-TSK-002); it was never in force. The summary"
                    + " names the operation, the version and whether the proposer withdrew it.",
            true),

    /** A risk policy version left force beside its successor's activation. */
    POLICY_RETIRED(
            "risk.PolicyRetired",
            "A risk policy version left force - retired beside its successor's activation in the activation's"
                    + " transaction (the activator the actor; P13-TSK-002), never on its own; every assessment that"
                    + " pinned it still replays under it (INV-HIST-04). The summary names the operation and both"
                    + " versions.",
            false),

    /** A proposer tried to approve their own proposal and was refused. */
    SELF_APPROVAL_REFUSED(
            "risk.SelfApprovalRefused",
            "A proposer tried to approve their own risk proposal - a policy version's activation (P13-TSK-002), an"
                    + " override's approval (P13-TSK-012), an alert's suppression (P13-TSK-015's machine, -018's door),"
                    + " a disposition's or an action's approval (P13-TSK-016, -017), a regulatory report's approval"
                    + " (P13-TSK-021) - and was refused"
                    + " 403 risk.SelfApprovalRefused before anything was written (INV-AUD-04, INV-RSK-07; also held by"
                    + " each table's four-eyes CHECK). Recorded FAILED in a transaction of its own after the act rolled"
                    + " back, so the attempt survives. Its target the proposal; the summary names it and the act -"
                    + " never a subject, a score or an amount.",
            false),

    /** An investigator or officer tried to act on their own party's subject and was refused. */
    SELF_DEALING_REFUSED(
            "risk.SelfDealingRefused",
            "A holder tried to read a customer risk profile, read or replay a risk assessment, read or work a case,"
                    + " approve an override, propose, approve or reject an alert's suppression (P13-TSK-015) or file a"
                    + " report whose subject is their own party - or could not be placed in any party, or (an alert's"
                    + " suppression) the subject's party could not be placed, each of which fails closed (the Phase 12 ->"
                    + " 13 gate's A7-6 applied forward) - and was refused"
                    + " 403 risk.SelfDealingRefused before anything was read or written (PHASE_13_PLAN.md section 11;"
                    + " first raised by P13-TSK-007's profile read, then by P13-TSK-004's assessment doors and"
                    + " P13-TSK-016's case doors). Recorded FAILED in a"
                    + " transaction of its own. Its target the profile, case, override or report; the summary names the"
                    + " act and the cause - never the party.",
            false),

    /** An analyst read a party's customer risk profile - its rating history. */
    PROFILE_READ(
            "risk.ProfileRead",
            "A holder of RISK_INVESTIGATE read a party's customer risk profile - the current rating and every version"
                    + " with what set it (GET /v1/operator/risk/parties/{partyId}/profile, P13-TSK-007; INV-RSK-10),"
                    + " after the self-dealing judgement, recorded on the read's own transaction so nothing is served"
                    + " unrecorded. Its target the profile (the party's id); the summary the number of versions served -"
                    + " never a rating, a score or a reason (INV-RSK-08).",
            false),

    /** An analyst read a sealed risk assessment - its inputs, hits, score, decision and reasons. */
    ASSESSMENT_READ(
            "risk.AssessmentRead",
            "A holder of RISK_INVESTIGATE read a sealed risk assessment with its inputs, rule hits, score, decision and"
                    + " reason codes (GET /v1/operator/risk/assessments/{id}, P13-TSK-004; INV-RSK-01, INV-RSK-04),"
                    + " after the self-dealing judgement, recorded on the read's own transaction so nothing is served"
                    + " unrecorded. Its target the assessment; the summary its operation, its decision and whether its"
                    + " seal holds - never an amount, a subject, a score or a rule's parameters (INV-RSK-08). Raised too"
                    + " by the risk officer's override desk under RISK_OVERRIDE (GET .../assessments/{id}/overrides,"
                    + " P13-TSK-012): the overridden assessment served with its overrides, the summary door=override and"
                    + " the overrides' count.",
            false),

    /** An analyst replayed a sealed risk assessment from its sealed inputs under its pinned policy and engine. */
    REPLAYED(
            "risk.Replayed",
            "A holder of RISK_INVESTIGATE replayed a sealed risk assessment from its sealed inputs under its pinned"
                    + " policy version and engine (POST /v1/operator/risk/assessments/{id}/replay, a reason required,"
                    + " P13-TSK-004; INV-RSK-01): IDENTICAL or DIVERGED, recorded in a transaction of its own before the"
                    + " verdict is answered (bnpl.Replayed's shape). Its target the assessment; the reason the analyst's;"
                    + " the summary the verdict and the kinds that diverged - never a figure.",
            true),

    /** An officer proposed an override of a DENY, HOLD or REVIEW decision (`P13-TSK-012`). */
    OVERRIDE_PROPOSED(
            "risk.OverrideProposed",
            "A holder of RISK_OVERRIDE proposed, with a reason code and text, an override of a DENY, HOLD or REVIEW"
                    + " decision (POST /v1/operator/risk/assessments/{id}/overrides, keyed risk.override:<actor>,"
                    + " P13-TSK-012; ADR-0108 decision 7): the effect its approval will issue fixed now - a held"
                    + " withdrawal released or denied, a held card payment captured or voided, or for a refused command a"
                    + " RESUBMISSION that dispatches nothing - and C7's four eyes fixed with it; one standing override per"
                    + " assessment; the decision itself is never edited, and an ALLOW or a CHALLENGE is never overridden."
                    + " The summary names the assessment, the decision, the effect, the reason code and whether a second"
                    + " holder is required - never an amount, a party or the text.",
            true),

    /** An override was approved - by a second holder where C7 requires one - its effect issued (`P13-TSK-012`). */
    OVERRIDE_APPROVED(
            "risk.OverrideApproved",
            "A holder of RISK_OVERRIDE approved an override from a MULTI_FACTOR session"
                    + " (POST .../overrides/{id}/approval, P13-TSK-012; C7: a holder other than its proposer at or above"
                    + " the pinned threshold or for a DENY - the four-eyes CHECK beneath; below it the proposer's own"
                    + " reasoned approval completes it, recorded byProposer=true), recording the action it issues under a"
                    + " permit - dispatched after the approval commits through payments' keyed door, risk.action:<id> -"
                    + " and announced risk.RiskActionRequested; a RESUBMISSION issues nothing. The summary names the"
                    + " assessment, the effect, whether a second holder was required and whether the proposer approved -"
                    + " never an amount.",
            true),

    /** A proposed override was rejected, or withdrawn by its proposer; the decision stands (`P13-TSK-012`). */
    OVERRIDE_REJECTED(
            "risk.OverrideRejected",
            "A proposed override was rejected - or withdrawn by its proposer (POST .../overrides/{id}/rejection,"
                    + " P13-TSK-012); it issued nothing and the decision stands (a held movement keeps its hold until its"
                    + " expiry or another decision). The summary names the assessment, the effect and whether the proposer"
                    + " withdrew it.",
            true),

    /** The outcome of an override's issued action was recorded (`P13-TSK-012`; `-017` raises it for its actions). */
    ACTION_CONCLUDED(
            "risk.ActionConcluded",
            "The outcome payments answered to an action an approved override issued was recorded on the override,"
                    + " once (P13-TSK-012; ADR-0112 decision 4; P13-TSK-017's risk_action rows raise it too): DONE,"
                    + " ALREADY (the movement had left its hold by another hand - its expiry among them; an outcome, not"
                    + " an error) or REFUSED (the hold's validity had lapsed, or no such movement). The actor is the"
                    + " approver whose door dispatched it, or the platform whose re-drive leg did; the effect itself is"
                    + " payments' act, audited by payments. The summary names the kind, the outcome and the attempts.",
            false),

    /** An analyst proposed to suppress an open alert (`P13-TSK-015`'s machine; the door `-018`'s). */
    ALERT_SUPPRESSION_PROPOSED(
            "risk.AlertSuppressionProposed",
            "A holder of RISK_INVESTIGATE proposed, with a reason code (SuppressionReason) and a text, to suppress an"
                    + " open alert - to close it without a case (P13-TSK-015's machine, Alerts.proposeSuppression; the door"
                    + " POST /v1/operator/risk/alerts/{id}/suppression is P13-TSK-018's): one live proposal per alert,"
                    + " awaiting a DIFFERENT holder of RISK_CASE_APPROVE; never on the proposer's own party's alert, nor"
                    + " on an alert whose subject the platform cannot place (risk.SelfDealingRefused, fail closed). The"
                    + " text is the record's reason; the summary names the alert's kind and the reason code - never the"
                    + " subject, the evidence or the text.",
            true),

    /** An alert was suppressed - a second analyst approving another's proposal (`P13-TSK-015`; the door `-018`'s). */
    ALERT_SUPPRESSED(
            "risk.AlertSuppressed",
            "A holder of RISK_CASE_APPROVE other than its proposer approved, with a reason, the proposal to suppress an"
                    + " open alert (OPEN -> SUPPRESSED; P13-TSK-015's machine, Alerts.approveSuppression; the door"
                    + " .../suppression/approval is P13-TSK-018's; INV-RSK-07, INV-AUD-04 - risk_alert_suppression_four_eyes"
                    + " beneath): the alert leaves investigation without a case. Never the system's act - the sweep's"
                    + " deduplication of an alert into an open case is ATTACHED, its own fact, not audited. The summary"
                    + " names the alert's kind and the reason code - never the subject.",
            true),

    /** A proposal to suppress an alert was rejected, or withdrawn by its proposer; the alert stays open. */
    ALERT_SUPPRESSION_REJECTED(
            "risk.AlertSuppressionRejected",
            "A proposal to suppress an alert was rejected with a reason - or withdrawn by its proposer"
                    + " (P13-TSK-015's machine, Alerts.rejectSuppression; the door .../suppression/rejection is"
                    + " P13-TSK-018's): the proposal cleared from the alert, which stays OPEN and queued; this record and"
                    + " the proposal's keep both acts. The summary names the alert's kind, the reason code and whether the"
                    + " proposer withdrew it.",
            true),

    /** A case was opened - by T-eval's decision, an alert's folding, or by hand (`P13-TSK-016`). */
    CASE_OPENED(
            "risk.CaseOpened",
            "A case was opened (P13-TSK-016; ADR-0110 decision 2): by T-eval on a REVIEW or HOLD decision in the judged"
                    + " command's transaction (the acting principal of that command the actor), by an alert's folding with"
                    + " no open case of its kind (T-eval's or the profile leg's principal, or the case leg's SYSTEM), or by"
                    + " hand by a holder of RISK_INVESTIGATE (POST /v1/operator/risk/cases, keyed risk.case:<actor>, a"
                    + " reason required at the door, never on the operator's own party). One open per (subject, kind) -"
                    + " risk_case_one_open; a disposed case is never re-opened, a new one names it as predecessor. The"
                    + " summary names the kind, what opened it (alert, assessment, hand) and whether a predecessor"
                    + " exists - never the subject.",
            false),

    /** An investigator took a case, or took it over (`P13-TSK-016`). */
    CASE_TAKEN(
            "risk.CaseTaken",
            "A holder of RISK_INVESTIGATE took a case (POST /v1/operator/risk/cases/{id}/actions, act TAKE, the body"
                    + " carrying the revision; OPEN -> IN_INVESTIGATION) or took over a taken one from another"
                    + " investigator (P13-TSK-016; DESIGN-016 D12) - one person's act under the case row's lock and the"
                    + " revision compare-and-set. The summary names the kind and the status after.",
            false),

    /** An investigator noted a case or attached evidence by reference (`P13-TSK-016`). */
    CASE_NOTED(
            "risk.CaseNoted",
            "A holder of RISK_INVESTIGATE appended a note, or attached evidence by reference (signal, assessment and"
                    + " journal entry ids - resolved through audited reads, never copied), to an open case"
                    + " (POST /v1/operator/risk/cases/{id}/actions, acts NOTE and ATTACH_EVIDENCE, P13-TSK-016); the text"
                    + " is the record's reason, bounded and screened by InstrumentShapes. The summary names the kind, the"
                    + " act and the references' counts - never the text or a reference.",
            true),

    /** An investigator escalated a case with a reason (`P13-TSK-016`). */
    CASE_ESCALATED(
            "risk.CaseEscalated",
            "A holder of RISK_INVESTIGATE escalated a taken case with a reason (act ESCALATE, P13-TSK-016:"
                    + " IN_INVESTIGATION | ESCALATED -> ESCALATED), raising its level one step (L1 to L3) and routing it"
                    + " to the queue named; announced risk.CaseEscalated. The summary names the kind, the level and the"
                    + " queue.",
            true),

    /** A case reached its disposition (`P13-TSK-016`). */
    CASE_DISPOSED(
            "risk.CaseDisposed",
            "A case reached its disposition (-> DISPOSED, P13-TSK-016): a one-person close (CLOSED_FALSE_POSITIVE or"
                    + " CLOSED_NO_ACTION, from OPEN too, decided by its proposal - its proposer the actor) or a second"
                    + " person's approval of a CONFIRMED_*, REFERRED or prohibiting disposition (the approver the actor);"
                    + " announced risk.CaseDisposed. A case never re-opens; a new case references it. The summary names"
                    + " the kind, the disposition code and whether it sets PROHIBITED.",
            false),

    /** An investigator proposed a case's disposition (`P13-TSK-016`). */
    DISPOSITION_PROPOSED(
            "risk.DispositionProposed",
            "A holder of RISK_INVESTIGATE proposed a case's disposition with a reason"
                    + " (POST /v1/operator/risk/cases/{id}/dispositions, the body carrying the revision, P13-TSK-016):"
                    + " CLOSED_FALSE_POSITIVE and CLOSED_NO_ACTION are decided by this act (one person, from OPEN too);"
                    + " every CONFIRMED_*, every REFERRED and any that sets the profile PROHIBITED (asked, never implied -"
                    + " a confirming disposition of a party's case alone) needs the case taken and awaits a DIFFERENT"
                    + " holder of RISK_CASE_APPROVE. The summary names the kind, the disposition, whether a second person"
                    + " is required and whether it prohibits.",
            true),

    /** A second person approved a case's disposition (`P13-TSK-016`). */
    DISPOSITION_APPROVED(
            "risk.DispositionApproved",
            "A holder of RISK_CASE_APPROVE other than its proposer approved a case's standing disposition from a"
                    + " MULTI_FACTOR session (POST .../dispositions/{dispositionId}/approval, the body carrying the"
                    + " revision, P13-TSK-016; INV-RSK-07, INV-AUD-04 - risk_case_disposition_four_eyes beneath); the case"
                    + " DISPOSED in the same transaction; a prohibiting disposition's PROHIBITED profile version written"
                    + " after the case commits, in a transaction of its own (never R8 under R4). The summary names the"
                    + " kind, the disposition and whether it prohibits.",
            true),

    /** A case's disposition was rejected, or withdrawn by its proposer (`P13-TSK-016`). */
    DISPOSITION_REJECTED(
            "risk.DispositionRejected",
            "A standing disposition was rejected with a reason - or withdrawn by its proposer"
                    + " (POST .../dispositions/{dispositionId}/rejection, P13-TSK-016); the case stays open and its slot"
                    + " is cleared, this record and the investigator actions keeping both acts. The summary names the"
                    + " kind, the disposition and whether the proposer withdrew it.",
            true),

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
