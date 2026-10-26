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
                    + " the overrides' count. Since P13-TSK-023 the read serves the explanation beside it - the rules in"
                    + " order, each reading and contribution, the band reached by position, the step that decided, the"
                    + " fail-safe - from the sealed rows and the pinned version, never a re-evaluation.",
            false),

    /** An analyst replayed a sealed risk assessment from its sealed inputs under its pinned policy and engine. */
    REPLAYED(
            "risk.Replayed",
            "A holder of RISK_INVESTIGATE replayed a sealed risk assessment from its sealed inputs under its pinned"
                    + " policy version and engine (POST /v1/operator/risk/assessments/{id}/replay, a reason required,"
                    + " P13-TSK-004; INV-RSK-01): IDENTICAL or DIVERGED, recorded in a transaction of its own before the"
                    + " verdict is answered (bnpl.Replayed's shape). Its target the assessment; the reason the analyst's;"
                    + " the summary the verdict and the kinds that diverged - never a figure. Raised too by the daily"
                    + " replay (POST /v1/operator/risk/assessments/replay?date=, P13-TSK-023): every assessment of the"
                    + " UTC day replayed under its own pinned pair on one snapshot; its target the day"
                    + " (risk_assessment_day, the date), the summary the counts and the first divergent's id - never one"
                    + " of the reader's own party.",
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

    /** The owning domain's answer to a risk action was recorded (`P13-TSK-012`; the action machine's, `P13-TSK-017`). */
    ACTION_CONCLUDED(
            "risk.ActionConcluded",
            "The answer the owning domain gave a dispatched risk action was recorded on its risk_action row, once,"
                    + " by a conditional edge (P13-TSK-017's T-act; first raised by P13-TSK-012 for an override's issued"
                    + " action, now folded into the same machine; ADR-0112 decisions 4-6): DONE, ALREADY (the state"
                    + " already held - an identity already suspended, a movement already out of its hold by another hand;"
                    + " concluded DONE) or REFUSED (the owner's own rule said no - a lapsed hold, a closed identity;"
                    + " concluded FAILED, never retried, re-proposable). The actor is the platform, which executes every"
                    + " action (the person decided it - risk.ActionApproved); the effect itself is the owner's act,"
                    + " audited by the owner. The summary names the kind, the source, the outcome and the attempts.",
            false),

    /** An investigator proposed a downstream action on a case (`P13-TSK-017`). */
    ACTION_PROPOSED(
            "risk.ActionProposed",
            "A holder of RISK_INVESTIGATE proposed a downstream action on a case they hold taken, with a reason"
                    + " (POST /v1/operator/risk/cases/{caseId}/risk-actions, the body carrying the revision, P13-TSK-017;"
                    + " ADR-0112 decision 3): a kind the case admits (ActionRules - identity's three on a party's case's own"
                    + " party, SUSPEND_MERCHANT on a merchant-abuse case's merchant, a held movement's resolution on a"
                    + " movement the case holds), one live per (case, kind, target); it awaits a DIFFERENT holder of"
                    + " RISK_CASE_APPROVE. Its target the risk_action row; the summary names the case, the kind and the"
                    + " target's kind - never the target's subject or the reason's text.",
            true),

    /** A second person approved a case's downstream action (`P13-TSK-017`). */
    ACTION_APPROVED(
            "risk.ActionApproved",
            "A holder of RISK_CASE_APPROVE other than its proposer approved a case's standing action from a"
                    + " MULTI_FACTOR session (POST .../risk-actions/{actionId}/approval, the body carrying the revision,"
                    + " P13-TSK-017; INV-RSK-07, INV-AUD-04 - risk_action_four_eyes beneath): the action APPROVED under a"
                    + " permit due at once, announced risk.RiskActionRequested; nothing is dispatched in this"
                    + " transaction - the platform dispatches it after the commit (risk.ActionDispatched). The summary"
                    + " names the case and the kind.",
            true),

    /** A case's downstream action was rejected, or withdrawn by its proposer (`P13-TSK-017`). */
    ACTION_REJECTED(
            "risk.ActionRejected",
            "A standing action was rejected with a reason - or withdrawn by its proposer"
                    + " (POST .../risk-actions/{actionId}/rejection, P13-TSK-017); nothing is dispatched, the case stays as"
                    + " it was, and the live unique is released (the action may be proposed again). The summary names the"
                    + " case, the kind and whether the proposer withdrew it.",
            true),

    /** The platform dispatched an approved risk action to its owning domain (`P13-TSK-017`). */
    ACTION_DISPATCHED(
            "risk.ActionDispatched",
            "The platform claimed an approved risk action - T-act's first attempt: the action row FOR UPDATE SKIP"
                    + " LOCKED on its due permit, moved DISPATCHED with the permit a lease ahead, committed before the"
                    + " owning domain's command is called under the system actor with the key risk.action:<id> and the"
                    + " action's reason code (P13-TSK-017; ADR-0112 decision 4) - by the approval door after its commit"
                    + " or by RiskActionSchedule. A re-claim past the lease presents the same key and is not audited again"
                    + " (the attempts are counted on the row). The summary names the kind, the source and the target's"
                    + " kind.",
            false),

    /** An analyst proposed to suppress an open alert (`P13-TSK-015`'s machine; the door `-018`'s). */
    ALERT_SUPPRESSION_PROPOSED(
            "risk.AlertSuppressionProposed",
            "A holder of RISK_INVESTIGATE proposed, with a reason code (SuppressionReason) and a text, to suppress an"
                    + " open alert - to close it without a case (P13-TSK-015's machine, Alerts.proposeSuppression; the door"
                    + " POST /v1/operator/risk/alerts/{alertId}/suppression, P13-TSK-018's): one live proposal per alert,"
                    + " awaiting a DIFFERENT holder of RISK_CASE_APPROVE; never on the proposer's own party's alert, nor"
                    + " on an alert whose subject the platform cannot place (risk.SelfDealingRefused, fail closed). The"
                    + " text is the record's reason; the summary names the alert's kind and the reason code - never the"
                    + " subject, the evidence or the text.",
            true),

    /** An alert was suppressed - a second analyst approving another's proposal (`P13-TSK-015`; the door `-018`'s). */
    ALERT_SUPPRESSED(
            "risk.AlertSuppressed",
            "A holder of RISK_CASE_APPROVE other than its proposer approved, with a reason and from a MULTI_FACTOR"
                    + " session, the proposal to suppress an open alert (OPEN -> SUPPRESSED; P13-TSK-015's machine,"
                    + " Alerts.approveSuppression; the door .../suppression/approval, P13-TSK-018's - both persons"
                    + " CUSTOMER- or EMPLOYEE-typed, risk V014; INV-RSK-07, INV-AUD-04 - risk_alert_suppression_four_eyes"
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

    /** An analyst was served a page of the alert queue (`P13-TSK-018`). */
    ALERT_READ(
            "risk.AlertRead",
            "A holder of RISK_INVESTIGATE was served a page of the alert queue (GET /v1/operator/risk/alerts?status=&kind="
                    + "&after=&limit=, P13-TSK-018; INV-RSK-08): oldest first on one read-only snapshot, every alert"
                    + " whose subject's party is the reader's own or cannot be placed withheld (self-dealing, fail"
                    + " closed), committed in a transaction of its own before the page is answered so nothing is served"
                    + " unrecorded. Its target the queue (risk_alert_queue, the status read); the summary the count, the"
                    + " filter, the cursor, the ids served, how many were withheld and whether more remain - never a"
                    + " subject, an evidence reference or a reason.",
            false),

    /** An analyst read a case's file, or was served a page of the case queue (`P13-TSK-018`). */
    CASE_READ(
            "risk.CaseRead",
            "A holder of RISK_INVESTIGATE read a case's file (GET /v1/operator/risk/cases/{caseId} - the case, its"
                    + " attached alerts, investigator actions, history and downstream actions, its evidence by reference"
                    + " only) or was served a page of the case queue (GET /v1/operator/risk/cases?status=&kind=&after="
                    + "&limit=), P13-TSK-018; INV-RSK-08: on one read-only snapshot after the self-dealing judgement (a"
                    + " file of the reader's own party's subject, or one the platform cannot place, refused"
                    + " risk.SelfDealingRefused; such a case withheld from a page), committed in a transaction of its own"
                    + " before the answer. Its target the case (risk_case) or the queue (risk_case_queue, the status"
                    + " read); the summary the kind, status and counts of what the file held, or the page's count,"
                    + " filter, cursor, ids served and withheld count - never a note, a subject or a reason. Raised too"
                    + " by the evidence door (GET .../cases/{caseId}/evidence/{signals|journal-entries}/{id},"
                    + " P13-TSK-023): one reference the file holds resolved - a signal's codes or a monitored"
                    + " transaction's amount - after the file's own judgement; the summary act=evidence, the kind and the"
                    + " reference.",
            false),

    /** An AML officer drafted a regulatory report from a confirmed-suspicious AML case (`P13-TSK-021`). */
    REPORT_DRAFTED(
            "risk.ReportDrafted",
            "A holder of AML_REPORT_APPROVE drafted a regulatory report from an AML case disposed CONFIRMED_SUSPICIOUS"
                    + " (POST /v1/operator/risk/cases/{caseId}/reports, keyed risk.report:<actor>, P13-TSK-021; ADR-0111"
                    + " decision 5) under the case row's lock: the jurisdiction and format version its adapter declares,"
                    + " the reporting entity's reference, the subject and its accounts, the transactions the case's"
                    + " evidence names, the grounds (the case's alerts' rule codes) and the narrative (RESTRICTED-PII,"
                    + " screened), the attachments' references - one live or accepted report per case, its REPORT_DRAFTED"
                    + " investigator action in the same transaction. Its target the report (regulatory_report); the reason"
                    + " the drafter's; the summary the case, the jurisdiction, the format version and counts - never the"
                    + " subject or the narrative.",
            true),

    /** A second AML officer approved a drafted regulatory report (`P13-TSK-021`). */
    REPORT_APPROVED(
            "risk.ReportApproved",
            "A holder of AML_REPORT_APPROVE other than its drafter approved a regulatory report from a MULTI_FACTOR"
                    + " session (POST .../reports/{reportId}/approval, P13-TSK-021; INV-RSK-07, regulatory_report_four_eyes"
                    + " beneath) - DRAFT -> APPROVED under the report row's lock. The reason the approver's; the summary"
                    + " the case and the jurisdiction.",
            true),

    /** An AML officer filed an approved regulatory report (`P13-TSK-021`). */
    REPORT_FILED(
            "risk.ReportFiled",
            "A holder of AML_REPORT_FILE filed an approved regulatory report from a MULTI_FACTOR session (POST"
                    + " .../reports/{reportId}/filing, 202, P13-TSK-021; ADR-0111 decision 6): APPROVED -> FILED committed"
                    + " BEFORE the provider is called, with risk.RegulatoryReportFiled (report id and jurisdiction) in the"
                    + " same transaction; the platform then submits it under the filing reference on no connection, and"
                    + " the authority's answer is recorded by the filing's drive (risk.ReportAcknowledged /"
                    + " risk.ReportRejected) - an unknown answer inquired, never re-filed. The reason the filer's; the"
                    + " summary the case and the jurisdiction - never the subject.",
            true),

    /** The authority acknowledged a filed report, recorded by the platform's drive (`P13-TSK-021`). */
    REPORT_ACKNOWLEDGED(
            "risk.ReportAcknowledged",
            "The filing's drive - the platform, from the filing door after its commit or RiskActionSchedule's report"
                    + " leg (P13-TSK-021) - recorded the authority's acknowledgement of a filed report: FILED ->"
                    + " ACKNOWLEDGED, conditional on the claimed attempt, once. The summary the jurisdiction, the"
                    + " authority's reference and the attempts - never the subject.",
            false),

    /** The authority rejected a filed report, recorded by the platform's drive (`P13-TSK-021`). */
    REPORT_REJECTED(
            "risk.ReportRejected",
            "The filing's drive (the platform, P13-TSK-021) recorded the authority's rejection of a filed report:"
                    + " FILED -> REJECTED with the authority's closed code, once; the case may be reported again by a new"
                    + " draft. The summary the jurisdiction, the rejection code and the attempts.",
            false),

    /** An AML officer read a regulatory report (`P13-TSK-021`, DESIGN-021 D9). */
    REGULATORY_REPORT_READ(
            "risk.RegulatoryReportRead",
            "A holder of AML_REPORT_APPROVE read a regulatory report - its content, the narrative among it (GET"
                    + " /v1/operator/risk/cases/{caseId}/reports/{reportId}, P13-TSK-021, DESIGN-021 D9: the second person"
                    + " reads what they approve) - after the self-dealing judgement, committed in a transaction of its own"
                    + " before the answer, so the narrative is never served unrecorded. Its target the report; the"
                    + " summary the case, the status and the jurisdiction - never the narrative.",
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
            true),

    /** An analyst was served a risk operations report (`P13-TSK-023`). */
    REPORT_READ(
            "risk.ReportRead",
            "A holder of RISK_INVESTIGATE was served a risk operations report as of a UTC date (GET"
                    + " /v1/operator/reports/risk/{decisions,alerts,cases,velocity}?date=, P13-TSK-023; F8): read on one"
                    + " read-only REPEATABLE READ snapshot with the census it carries - the assessment census, the"
                    + " born-once census, the one-open and filing censuses, the velocity proof made whole and the"
                    + " monitoring census - committed in a transaction of its own before the answer (bnpl.ReportRead's"
                    + " shape), so no report is served unrecorded. Its target the report (risk_report, the report's"
                    + " name); the summary the report, the date, whether the platform had it, the rows and the verdict -"
                    + " counts and codes, never an amount, a party or a subject (INV-RSK-08).",
            false);

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
