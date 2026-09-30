package com.finapp.reconciliation;

import com.finapp.platform.audit.AuditableAction;
import lombok.RequiredArgsConstructor;

/**
 * The reconciliation module's auditable actions ({@code AUDITABLE_ACTIONS.md}), arriving with
 * the commands whose designs fix their meaning — the {@code PaymentsAuditAction} rule. The
 * backfill's and the report's arrived with `P8-TSK-007`; matching's, the breaks' and the
 * resolutions' arrive with their tasks.
 */
@RequiredArgsConstructor
public enum ReconciliationAuditAction implements AuditableAction {

    /**
     * A controller adopted the opening position (`P8-TSK-007`, ADR-0067 §8): the keyed,
     * reasoned backfill that walks Phases 5–7's completed clearing operations through the
     * live opener's own path. One record per recorded run; the change summary carries the
     * per-producer counts — <strong>counts only, never an amount</strong>
     * ({@code INV-AUD-02}) — and the reason is the controller's own.
     */
    OPENING_POSITION_RECORDED(
            "reconciliation.OpeningPositionRecorded",
            "A reconciliation controller adopted the opening position: history's completed"
                    + " clearing operations opened as tracked expectations, with the"
                    + " controller's recorded reason and the counts.",
            true),

    /**
     * The platform raised a break (`P8-TSK-010`, ADR-0069 §3) — acting-only: a raise that
     * converged on the standing open break records nothing. The change summary carries the
     * type, cause, severity and source id — identifiers and enumerated names only, never
     * an amount ({@code INV-AUD-02}); the value at issue is the row's and the audited
     * reports'.
     */
    BREAK_RAISED(
            "reconciliation.BreakRaised",
            "The platform raised a classified break from a stored fact it detected; the"
                    + " record names the type, cause, severity and source, never a value.",
            false),

    /**
     * A reconciliation run completed (`P8-TSK-011`, ADR-0068 §4) — acting-only, one per
     * run: the completing chunk's own record, with the counts per outcome in the change
     * summary — identifiers and counts only, never an amount ({@code INV-AUD-02}).
     */
    RUN_COMPLETED(
            "reconciliation.RunCompleted",
            "A reconciliation run completed: every item disposed, the counts per outcome"
                    + " on the record.",
            false),

    /**
     * The platform resolved a break by evidence (`P8-TSK-012`, ADR-0071 §2) —
     * acting-only: a counterparty's own correction or claw-back explained the break to a
     * zero residual, and the {@code EVIDENCED} resolution closed it with no person
     * deciding. The change summary names the resolution, the decision and the park —
     * identifiers and enumerated names only, never an amount ({@code INV-AUD-02}).
     */
    BREAK_RESOLVED_BY_EVIDENCE(
            "reconciliation.BreakResolvedByEvidence",
            "The platform closed a break whose discrepancy the counterparty's own"
                    + " correction explained to zero; the record names the resolution,"
                    + " decision and park, never a value.",
            false),

    /**
     * An investigator assigned a break (`P8-TSK-014`, ADR-0069 §7): the first assignment
     * moves {@code OPEN → INVESTIGATING}, later ones hand the case over. The change summary
     * names the assignee and the status edge — identifiers and enumerated names only.
     */
    BREAK_ASSIGNED(
            "reconciliation.BreakAssigned",
            "An investigator assigned a reconciliation break; the record names the assignee"
                    + " and the status the break moved from and to.",
            false),

    /**
     * An investigator added a note to a break's case file (`P8-TSK-014`, ADR-0069 §7). The
     * body is CONFIDENTIAL and is NEVER in the record: the summary carries the note's id and
     * length only.
     */
    BREAK_NOTE_ADDED(
            "reconciliation.BreakNoteAdded",
            "An investigator added a note to a reconciliation break's case file; the record"
                    + " names the note, never its body.",
            false),

    /**
     * An investigator linked evidence to a break (`P8-TSK-014`, ADR-0069 §7): an identifier
     * into the stored chain, verified to exist, never content or a URL. The summary names the
     * target kind and its identifier.
     */
    BREAK_EVIDENCE_LINKED(
            "reconciliation.BreakEvidenceLinked",
            "An investigator linked stored evidence to a reconciliation break by identifier;"
                    + " the record names the target kind and identifier.",
            false),

    /**
     * An investigator reclassified a break (`P8-TSK-014`, ADR-0069 §7): a reasoned type
     * change in {@code OPEN} or {@code INVESTIGATING}, the cause, subject and value at issue
     * frozen, the severity kept at the higher of stored and recomputed. The reason is the
     * investigator's own; the summary names the types and grades.
     */
    BREAK_RECLASSIFIED(
            "reconciliation.BreakReclassified",
            "An investigator reclassified a reconciliation break with a recorded reason; the"
                    + " record names the type and severity before and after.",
            true),

    /**
     * Somebody read a reconciliation report (`P8-TSK-007`, ADR-0072; the
     * {@code payments.ChargebackRatioRead} precedent): the positions report carries
     * amounts, so every serving is on the record — the report's name and period, never its
     * figures.
     */
    REPORT_READ(
            "reconciliation.ReportRead",
            "A reconciliation report carrying amounts was served; the record names the"
                    + " report, never its figures.",
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
