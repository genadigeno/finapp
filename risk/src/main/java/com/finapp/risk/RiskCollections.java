package com.finapp.risk;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The operator's §7 collections over risk's own tables (`P17-TSK-020`, gaps G-91 and G-92; finapp-admin-panel ADR-C133): reads of
 * committed rows, newest first by a row's immutable stamp with its id breaking the tie, strictly after a keyset cursor. A read
 * model - it decides and writes nothing. Who may read what is not judged here: the assessment search's self-dealing judgement
 * and its record are {@link AssessmentSearch}'s, and the two administrative lists are the door's permission alone.
 */
public interface RiskCollections {

    /** A keyset position - a served row's stamp and id; the next page continues strictly before it. */
    record Cursor(Instant at, UUID id) {
        public Cursor {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * What an assessment search reads: the half-open window {@code [from, to)} on {@code assessed_at}, and the exact filters
     * named - a party, an operation, a decision.
     */
    record AssessmentFilter(Instant from, Instant to, Optional<UUID> party, Optional<RiskOperation> operation,
            Optional<RiskDecision> decision) {
        public AssessmentFilter {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(party, "party");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(decision, "decision");
            if (!from.isBefore(to)) {
                throw new IllegalArgumentException("a window is [from, to), from before to");
            }
        }
    }

    /**
     * One sealed assessment's head - the search's item: when, which operation, the decision and score, the version it was judged
     * under (absent for an evaluation that pinned none), the party. Never an input, a hit, a reason or the seal: those are the
     * one-assessment read's, audited on their own ({@code risk.AssessmentRead}).
     */
    record AssessmentHead(RiskAssessmentId id, Instant assessedAt, RiskOperation operation, RiskDecision decision, int score,
            Optional<RiskPolicyVersionId> policyVersionId, Optional<Integer> policyVersion, UUID partyId) {
        public AssessmentHead {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(assessedAt, "assessedAt");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(policyVersionId, "policyVersionId");
            Objects.requireNonNull(policyVersion, "policyVersion");
            Objects.requireNonNull(partyId, "partyId");
        }
    }

    /**
     * Up to {@code limit} assessments of {@code filter}'s window, newest first by {@code (assessed_at, id)}, strictly after
     * {@code after}; never one of {@code withheldParty}'s - the reader's own, withheld by the statement itself.
     */
    List<AssessmentHead> assessments(Connection unitOfWork, AssessmentFilter filter, UUID withheldParty, Optional<Cursor> after,
            int limit);

    /**
     * Up to {@code limit} policy versions, newest first by {@code (proposed_at, id)}, strictly after {@code after}: of one
     * operation and one status when named - {@code PROPOSED} is the approver's queue.
     */
    List<RiskPolicyStore.VersionRow> policyVersions(Connection unitOfWork, Optional<RiskOperation> operation,
            Optional<RiskPolicyVersionStatus> status, Optional<Cursor> after, int limit);

    /** Up to {@code limit} screening list versions, newest first by {@code (recorded_at, id)}, of one list when named. */
    List<ScreeningListVersion> screeningListVersions(Connection unitOfWork, Optional<ScreeningList> list, Optional<Cursor> after,
            int limit);

    // ------------------------------------------------------------------ the proposals awaiting a decision (`P17-TSK-040`)

    /**
     * A proposal awaiting a second person's decision: its id - the one its approve and reject doors name - its proposer and
     * their reason (absent where the kind records none), when it was proposed, and what it acts on.
     */
    record PendingProposal(UUID id, String proposedBy, Instant proposedAt, Optional<String> reason, String subject) {
        public PendingProposal {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(proposedAt, "proposedAt");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * Up to {@code limit} alert suppressions awaiting approval - an {@code OPEN} alert's standing proposal - oldest first by
     * {@code (suppression_proposed_at, id)}, strictly after {@code after}; each the alert's id, its subject the alert.
     */
    List<PendingProposal> pendingSuppressions(Connection unitOfWork, Optional<Cursor> after, int limit);

    /**
     * Up to {@code limit} case dispositions awaiting a second person - the standing proposal of a case's slot - oldest first by
     * {@code (disposition_proposed_at, disposition_id)}; each the proposal's {@code DISPOSITION_PROPOSED} action, its reason
     * that action's text, its subject the case.
     */
    List<PendingProposal> pendingDispositions(Connection unitOfWork, Optional<Cursor> after, int limit);

    /**
     * Up to {@code limit} case risk actions {@code PROPOSED}, oldest first by {@code (proposed_at, id)}; the reason the
     * {@code ACTION_PROPOSED} action's text, the subject the case.
     */
    List<PendingProposal> pendingRiskActions(Connection unitOfWork, Optional<Cursor> after, int limit);

    /**
     * Up to {@code limit} decision overrides {@code PROPOSED}, oldest first by {@code (proposed_at, id)}; the subject the
     * assessment.
     */
    List<PendingProposal> pendingOverrides(Connection unitOfWork, Optional<Cursor> after, int limit);

    /**
     * Up to {@code limit} regulatory reports in {@code DRAFT}, awaiting a second person's approval, oldest first by
     * {@code (drafted_at, id)}; no reason - the narrative is never one - the subject the case.
     */
    List<PendingProposal> pendingReports(Connection unitOfWork, Optional<Cursor> after, int limit);

    /**
     * Up to {@code limit} policy versions {@code PROPOSED}, oldest first by {@code (proposed_at, id)}; the subject the
     * operation.
     */
    List<PendingProposal> pendingPolicyVersions(Connection unitOfWork, Optional<Cursor> after, int limit);

    /**
     * Up to {@code limit} screening list versions {@code RECORDED}, awaiting a second person's activation, oldest first by
     * {@code (recorded_at, id)}; no reason is recorded; the subject the list.
     */
    List<PendingProposal> pendingScreeningListVersions(Connection unitOfWork, Optional<Cursor> after, int limit);
}
