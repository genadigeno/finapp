package com.finapp.risk;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One case as stored (`P13-TSK-016`; {@code risk V012}'s {@code risk_case}; {@code PHASE_13_PLAN.md} sections 3.1, 4,
 * 5; {@code RISK_LIFECYCLES.md} section 4.6; ADR-0110 decisions 2, 5, 6; {@code INV-RSK-06}, {@code INV-RSK-07}) - the
 * unit of a person's judgement about one subject and one kind, and the machine's judgements over it.
 *
 * <pre>
 *   OPEN ──take──> IN_INVESTIGATION ──escalate──> ESCALATED ──escalate (≤ L3)──> ESCALATED
 *   OPEN | IN_INVESTIGATION | ESCALATED ──a one-person close (CLOSED_FALSE_POSITIVE | CLOSED_NO_ACTION)──> DISPOSED
 *   IN_INVESTIGATION | ESCALATED ──★ proposed, then approved by a second person──> DISPOSED
 *   DISPOSED: final - a later alert or decision opens a NEW case whose predecessor this is (OQ-6).
 * </pre>
 *
 * <p>The {@code revision} counts every step: born 0, one more per act, the system's attaches included - an
 * investigator's screen carries the revision it read and an act on any other is refused
 * ({@link Cases.CaseRevisionStale}), so a stale view never overwrites a newer act (ADR-0110 decision 5). The
 * <strong>disposition slot</strong> holds a standing four-eyes proposal (proposer, no approver) or the decided
 * disposition (proposer and approver - the same person only for a one-person close, by
 * {@code risk_case_disposition_four_eyes} as well as here); a rejection clears it. Its {@link DispositionSlot#id()} is
 * the {@code DISPOSITION_PROPOSED} action's id - the disposition's identity, which the doors name and a
 * {@code PROHIBITED} profile version's foreign key points at.
 *
 * @param takenBy the investigator who took it - present exactly past {@code OPEN}
 * @param slaDeadline {@code opened_at} plus {@code slaHours}, the database's
 */
public record RiskCase(
        RiskCaseId id,
        CaseKind kind,
        SignalSubject subject,
        CaseStatus status,
        int revision,
        CaseLevel level,
        CaseQueue queue,
        Optional<Person> takenBy,
        int slaHours,
        Instant slaDeadline,
        Optional<RiskAlertId> openingAlert,
        Optional<RiskAssessmentId> openingAssessment,
        Optional<RiskCaseId> predecessor,
        Instant openedAt,
        Optional<DispositionSlot> disposition,
        String correlationId) {

    /** A person named on the case - an investigator or a decider; never served by a door. */
    public record Person(String id, ActorType type) {
        public Person {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
        }

        public static Person of(Actor actor) {
            Objects.requireNonNull(actor, "actor");
            return new Person(actor.id(), actor.type());
        }

        /** True when {@code actor} is this person. */
        public boolean is(Actor actor) {
            return id.equals(actor.id()) && type == actor.type();
        }

        @Override
        public String toString() {
            return "Person[" + type + "]";
        }
    }

    /**
     * The disposition slot: a standing proposal ({@code approvedBy} empty) or the decision.
     *
     * @param id the {@code DISPOSITION_PROPOSED} action's id - the disposition's identity
     * @param proposedAt the database's stamp of the proposal - empty before the row is stored
     * @param disposedAt the database's stamp of the decision - present exactly when decided
     */
    public record DispositionSlot(UUID id, Disposition disposition, boolean prohibits, Person proposedBy,
            Optional<Instant> proposedAt, Optional<Person> approvedBy, Optional<Instant> disposedAt) {
        public DispositionSlot {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(proposedBy, "proposedBy");
            Objects.requireNonNull(proposedAt, "proposedAt");
            Objects.requireNonNull(approvedBy, "approvedBy");
            Objects.requireNonNull(disposedAt, "disposedAt");
            if (prohibits && !disposition.confirms()) {
                throw new IllegalArgumentException("only a confirming disposition sets PROHIBITED");
            }
            if (approvedBy.isPresent() && approvedBy.get().equals(proposedBy)
                    && DispositionRules.needsSecondPerson(disposition, prohibits)) {
                // INV-RSK-07: refused by the value as well as by risk_case_disposition_four_eyes.
                throw new IllegalArgumentException("a " + disposition + " disposition is approved by a second person");
            }
        }

        /** A standing proposal - awaiting a second person. */
        public boolean standing() {
            return approvedBy.isEmpty();
        }

        /** True when this disposition is two persons' act. */
        public boolean needsSecondPerson() {
            return DispositionRules.needsSecondPerson(disposition, prohibits);
        }

        @Override
        public String toString() {
            return "DispositionSlot[" + id + ", " + disposition + (prohibits ? ", prohibits" : "")
                    + (standing() ? ", standing" : ", decided") + "]";
        }
    }

    public RiskCase {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(takenBy, "takenBy");
        Objects.requireNonNull(slaDeadline, "slaDeadline");
        Objects.requireNonNull(openingAlert, "openingAlert");
        Objects.requireNonNull(openingAssessment, "openingAssessment");
        Objects.requireNonNull(predecessor, "predecessor");
        Objects.requireNonNull(openedAt, "openedAt");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(correlationId, "correlationId");
        if (revision < 0) {
            throw new IllegalArgumentException("a case's revision counts from 0");
        }
        if (slaHours < 1 || slaHours > SlaDeadlines.MAX_HOURS) {
            throw new IllegalArgumentException("a case's SLA is 1 to " + SlaDeadlines.MAX_HOURS + " hours");
        }
        if (openingAlert.isPresent() && openingAssessment.isPresent()) {
            throw new IllegalArgumentException("a case is opened from one alert or one assessment, never both");
        }
        if ((status == CaseStatus.IN_INVESTIGATION || status == CaseStatus.ESCALATED) && takenBy.isEmpty()) {
            throw new IllegalArgumentException("a taken case names its investigator");
        }
        if (status == CaseStatus.ESCALATED && (level == CaseLevel.L1 || queue == CaseQueue.FIRST_LINE)
                || (status == CaseStatus.OPEN || status == CaseStatus.IN_INVESTIGATION)
                        && (level != CaseLevel.L1 || queue != CaseQueue.FIRST_LINE)) {
            throw new IllegalArgumentException("a case is above L1 and the first line exactly once escalated");
        }
        if ((status == CaseStatus.DISPOSED)
                != disposition.filter(slot -> !slot.standing()).isPresent()) {
            throw new IllegalArgumentException("a case is DISPOSED exactly when its disposition is decided");
        }
    }

    /** True while an investigator may still act on the case. */
    public boolean open() {
        return status.open();
    }

    /** True when an investigator has taken the case ({@code IN_INVESTIGATION} or {@code ESCALATED}). */
    public boolean taken() {
        return status == CaseStatus.IN_INVESTIGATION || status == CaseStatus.ESCALATED;
    }

    /** The standing four-eyes proposal, if one awaits a second person. */
    public Optional<DispositionSlot> standingProposal() {
        return disposition.filter(DispositionSlot::standing);
    }

    // ------------------------------------------------------------------ the machine's judgements

    /**
     * Refuses an act on a disposed case.
     *
     * @throws Cases.CaseNotOpen the case is {@code DISPOSED}
     */
    void judgeOpen() {
        if (!open()) {
            throw new Cases.CaseNotOpen();
        }
    }

    /**
     * Refuses an act on a view of another revision - ADR-0110 decision 5's compare-and-set, judged under the row lock.
     *
     * @throws Cases.CaseRevisionStale {@code asked} is not the case's revision
     */
    void judgeRevision(int asked) {
        if (asked != revision) {
            throw new Cases.CaseRevisionStale();
        }
    }

    /** True when {@code actor} already holds the case - their take converges with nothing written. */
    boolean takenBy(Actor actor) {
        return takenBy.filter(person -> person.is(actor)).isPresent();
    }

    /** The status a take leaves: {@code OPEN → IN_INVESTIGATION}; a taken case keeps its status (a reassignment). */
    CaseStatus afterTake() {
        return status == CaseStatus.OPEN ? CaseStatus.IN_INVESTIGATION : status;
    }

    /**
     * The level one escalation to {@code target} reaches.
     *
     * @throws Cases.CaseNotTaken the case is {@code OPEN} - an escalation needs it taken
     * @throws Cases.CaseRequestInvalid {@code target} is the queue cases are born in
     * @throws Cases.CaseActNotAdmitted the case is at the top level
     */
    CaseLevel judgeEscalation(CaseQueue target) {
        Objects.requireNonNull(target, "target");
        if (!taken()) {
            throw new Cases.CaseNotTaken("a case is escalated once an investigator has taken it");
        }
        if (!target.escalationTarget()) {
            throw new Cases.CaseRequestInvalid("an escalation names a queue above the first line");
        }
        return level.next().orElseThrow(() -> new Cases.CaseActNotAdmitted(
                "the case is at the top level, " + level + "; it is disposed from there"));
    }

    /**
     * Refuses a disposition's proposal the case cannot take now.
     *
     * @throws Cases.CaseActNotAdmitted a proposal already awaits a second person
     * @throws Cases.CaseRequestInvalid {@code PROHIBITED} asked of a disposition that does not confirm, or of a subject
     *     that is no party
     * @throws Cases.CaseNotTaken a two persons' disposition on a case nobody has taken
     */
    void judgeProposal(Disposition proposed, boolean prohibits) {
        Objects.requireNonNull(proposed, "proposed");
        if (standingProposal().isPresent()) {
            throw new Cases.CaseActNotAdmitted("a disposition already awaits a second person on this case; decide that"
                    + " one first");
        }
        if (prohibits && !DispositionRules.mayProhibit(proposed, subject.kind())) {
            throw new Cases.CaseRequestInvalid("PROHIBITED is set only by a confirming disposition of a party's case");
        }
        if (DispositionRules.needsTaken(proposed, prohibits) && !taken()) {
            throw new Cases.CaseNotTaken("a " + proposed + " disposition is two persons' act on a case an investigator"
                    + " has taken");
        }
    }

    /**
     * The standing proposal {@code dispositionId} names.
     *
     * @throws Cases.CaseActNotAdmitted no proposal stands, or another one does
     */
    DispositionSlot judgeDecision(UUID dispositionId) {
        Objects.requireNonNull(dispositionId, "dispositionId");
        return standingProposal().filter(slot -> slot.id().equals(dispositionId)).orElseThrow(() ->
                new Cases.CaseActNotAdmitted("the disposition named is not awaiting a decision on this case"));
    }

    /** Identifiers and codes only - never the subject or a person ({@code INV-RSK-08}). */
    @Override
    public String toString() {
        return "RiskCase[" + id.value() + ", " + kind + ", " + status + ", rev " + revision + "]";
    }
}
