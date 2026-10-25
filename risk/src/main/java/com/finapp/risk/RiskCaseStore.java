package com.finapp.risk;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for cases, their investigator actions and their history (`P13-TSK-016`; {@code risk V012}; ADR-0033) - a
 * port on the caller's unit of work. The arbiters are the database's: the birth
 * {@code INSERT … ON CONFLICT (subject_ref, kind) WHERE status <> 'DISPOSED' DO NOTHING} (the partial unique
 * {@code risk_case_one_open}, {@code INV-RSK-06}); the case row {@code FOR UPDATE} (R4); every step one conditional
 * {@code UPDATE … WHERE id = ? AND revision = ?} that the trigger judges again (revision + 1, the machine's edges,
 * the stamps on the database clock); the actions and the events {@code INSERT}-only for every writer. No delete exists,
 * here or in the grant.
 */
public interface RiskCaseStore {

    /** What a case is born with - revision 0, {@code OPEN}, {@code L1}, {@code FIRST_LINE}; the stamps the database's. */
    record NewCase(RiskCaseId id, CaseKind kind, SignalSubject subject, int slaHours,
            Optional<RiskAlertId> openingAlert, Optional<RiskAssessmentId> openingAssessment,
            Optional<RiskCaseId> predecessor, String correlationId) {
        public NewCase {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(openingAlert, "openingAlert");
            Objects.requireNonNull(openingAssessment, "openingAssessment");
            Objects.requireNonNull(predecessor, "predecessor");
            Objects.requireNonNull(correlationId, "correlationId");
            if (openingAlert.isPresent() && openingAssessment.isPresent()) {
                throw new IllegalArgumentException("a case is opened from one alert or one assessment, never both");
            }
        }
    }

    /**
     * A step's new state - everything an act may move: the status, the level and queue, the assignee, the disposition
     * slot ({@code disposition} empty clears it). The revision is the store's: the step's {@code fromRevision} + 1.
     */
    record Step(CaseStatus status, CaseLevel level, CaseQueue queue, Optional<RiskCase.Person> takenBy,
            Optional<RiskCase.DispositionSlot> disposition) {
        public Step {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(queue, "queue");
            Objects.requireNonNull(takenBy, "takenBy");
            Objects.requireNonNull(disposition, "disposition");
        }

        /** {@code from}'s state, unchanged - the base every act's step is built on. */
        public static Step of(RiskCase from) {
            return new Step(from.status(), from.level(), from.queue(), from.takenBy(), from.disposition());
        }

        public Step withStatus(CaseStatus moved) {
            return new Step(moved, level, queue, takenBy, disposition);
        }

        public Step escalated(CaseLevel raised, CaseQueue routed) {
            return new Step(CaseStatus.ESCALATED, raised, routed, takenBy, disposition);
        }

        public Step takenBy(RiskCase.Person investigator) {
            return new Step(status, level, queue, Optional.of(investigator), disposition);
        }

        public Step withDisposition(Optional<RiskCase.DispositionSlot> slot) {
            return new Step(status, level, queue, takenBy, slot);
        }
    }

    /** A disposed case whose approved disposition sets {@code PROHIBITED} and has no profile version yet. */
    record PendingProhibition(RiskCaseId caseId, UUID partyId, UUID dispositionId) {
        public PendingProhibition {
            Objects.requireNonNull(caseId, "caseId");
            Objects.requireNonNull(partyId, "partyId");
            Objects.requireNonNull(dispositionId, "dispositionId");
        }
    }

    /**
     * {@code subject}'s open case of {@code kind}, locked {@code FOR UPDATE} (R4) - waiting on a holder; empty when none
     * is open (a case disposed while this waited is re-judged by the database and not answered).
     */
    Optional<RiskCase> lockOpen(Connection unitOfWork, SignalSubject subject, CaseKind kind);

    /**
     * The case born {@code OPEN} at revision 0 - one statement; empty when {@code subject} already has an open case of the
     * kind (the partial unique - nothing written; a fresh {@link #lockOpen} finds the winner). A primary-key collision is
     * not a conflict: it throws.
     */
    Optional<RiskCase> insert(Connection unitOfWork, NewCase newCase);

    /** The case {@code id} locked {@code FOR UPDATE} (R4) - a person's act. */
    Optional<RiskCase> lock(Connection unitOfWork, RiskCaseId id);

    /** The case {@code id}, no lock. */
    Optional<RiskCase> find(Connection unitOfWork, RiskCaseId id);

    /** {@code subject}'s most recently disposed case of {@code kind} - the predecessor a new case references. */
    Optional<RiskCaseId> latestDisposed(Connection unitOfWork, SignalSubject subject, CaseKind kind);

    /**
     * The step from {@code fromRevision} to {@code fromRevision + 1} with {@code step}'s state - conditional on the
     * revision (the caller holds the lock; empty means the protocol was bypassed).
     */
    Optional<RiskCase> advance(Connection unitOfWork, RiskCaseId id, int fromRevision, Step step);

    /** An investigator action, born once ({@code INSERT}-only). */
    void appendAction(Connection unitOfWork, CaseAction action);

    /** A step of the history, born once per revision ({@code INSERT}-only). */
    void appendEvent(Connection unitOfWork, CaseEvent event);

    /** The case's actions, oldest first. */
    List<CaseAction> actions(Connection unitOfWork, RiskCaseId id);

    /** The case's history, revision 0 first. */
    List<CaseEvent> events(Connection unitOfWork, RiskCaseId id);

    /** Up to {@code limit} approved prohibiting dispositions with no profile version naming them, oldest first. */
    List<PendingProhibition> prohibitionsDue(Connection unitOfWork, int limit);
}
