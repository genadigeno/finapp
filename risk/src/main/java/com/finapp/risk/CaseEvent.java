package com.finapp.risk;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One step of a case's history (`P13-TSK-016`; {@code risk V012}'s {@code risk_case_event},
 * {@code PRIMARY KEY (case_id, revision)}; {@code RISK_LIFECYCLES.md} section 3) - exactly one per revision, written in
 * the step's own transaction, so the history equals the row: the kind, the state after it (status, level, queue), who
 * acted (a person; for the system's openings and attaches, the acting principal - T-eval's caller, a leg's
 * {@code SYSTEM}), and what it names - the action a person wrote, or the alert or assessment the system attached or
 * opened from.
 *
 * @param recordedAt the database's stamp - empty before the row is stored
 */
public record CaseEvent(
        RiskCaseId caseId,
        int revision,
        CaseEventKind kind,
        CaseStatus status,
        CaseLevel level,
        CaseQueue queue,
        RiskCase.Person actor,
        Optional<UUID> action,
        Optional<RiskAlertId> alert,
        Optional<RiskAssessmentId> assessment,
        String correlationId,
        Optional<Instant> recordedAt) {

    public CaseEvent {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(alert, "alert");
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (revision < 0 || (kind == CaseEventKind.OPENED) != (revision == 0)) {
            throw new IllegalArgumentException("revision 0 is the opening, and only the opening");
        }
        if (kind.persons() != action.isPresent()) {
            throw new IllegalArgumentException("a person's step names its action, and only a person's");
        }
        if ((kind == CaseEventKind.ALERT_ATTACHED) && alert.isEmpty()
                || (kind == CaseEventKind.ASSESSMENT_ATTACHED) && assessment.isEmpty()) {
            throw new IllegalArgumentException("an attach names what it attached");
        }
        if (kind != CaseEventKind.OPENED && kind != CaseEventKind.ALERT_ATTACHED && alert.isPresent()
                || kind != CaseEventKind.OPENED && kind != CaseEventKind.ASSESSMENT_ATTACHED && assessment.isPresent()
                || alert.isPresent() && assessment.isPresent()) {
            throw new IllegalArgumentException("only an opening or an attach names an alert or an assessment, one of them");
        }
    }

    @Override
    public String toString() {
        return "CaseEvent[" + caseId.value() + ", rev " + revision + ", " + kind + "]";
    }
}
