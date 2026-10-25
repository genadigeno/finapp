package com.finapp.risk;

import com.finapp.platform.security.ActorType;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One investigator action, born once and never edited (`P13-TSK-016`; {@code risk V012}'s {@code risk_case_action};
 * {@code PHASE_13_PLAN.md} section 3.1 "Investigator Action"; ADR-0110 decision 4) - a person's act on a case: its kind,
 * the person, the text (required for the reasoned kinds, bounded and screened by {@link RiskReasons}), the evidence by
 * reference (exactly for {@link CaseActionKind#EVIDENCE_ATTACHED}), the disposition proposed (exactly for
 * {@link CaseActionKind#DISPOSITION_PROPOSED} - this action's id is the disposition's id) and the escalation's queue
 * (exactly for {@link CaseActionKind#ESCALATED}). Its {@code ordinal} is the case revision the act produced.
 *
 * @param recordedAt the database's stamp - empty before the row is stored
 */
public record CaseAction(
        UUID id,
        RiskCaseId caseId,
        int ordinal,
        CaseActionKind kind,
        RiskCase.Person actor,
        Optional<String> text,
        AlertEvidence evidence,
        Optional<Disposition> disposition,
        boolean prohibits,
        Optional<CaseQueue> queue,
        String correlationId,
        Optional<Instant> recordedAt) {

    public CaseAction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (ordinal < 1) {
            throw new IllegalArgumentException("an action is a step after the case's birth: its ordinal counts from 1");
        }
        if (actor.type() != ActorType.CUSTOMER && actor.type() != ActorType.EMPLOYEE) {
            throw new IllegalArgumentException("an investigator action is a person's - an authenticated human's");
        }
        if (kind.texted() != text.isPresent()) {
            throw new IllegalArgumentException("a " + kind + " action " + (kind.texted() ? "carries" : "carries no")
                    + " text");
        }
        text.ifPresent(RiskReasons::reasoned);
        if ((kind == CaseActionKind.EVIDENCE_ATTACHED) == evidence.isEmpty()) {
            throw new IllegalArgumentException("evidence is attached by its own act, and only there");
        }
        if ((kind == CaseActionKind.DISPOSITION_PROPOSED) != disposition.isPresent()) {
            throw new IllegalArgumentException("a disposition is named by its proposal, and only there");
        }
        if (prohibits && disposition.filter(Disposition::confirms).isEmpty()) {
            throw new IllegalArgumentException("only a confirming disposition's proposal prohibits");
        }
        if ((kind == CaseActionKind.ESCALATED) != queue.isPresent()) {
            throw new IllegalArgumentException("a queue is named by an escalation, and only there");
        }
    }

    /** Identifiers and codes only - never the text or a person ({@code INV-RSK-08}). */
    @Override
    public String toString() {
        return "CaseAction[" + id + ", case " + caseId.value() + ", #" + ordinal + ", " + kind + "]";
    }
}
