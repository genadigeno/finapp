package com.finapp.merchant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for the fee schedule version and merchant fee assignment proposals (`P17-TSK-123`, merchant V022), and the
 * approver's reads over every proposal {@code FEE_SCHEDULE_APPROVE} decides - those two and the BNPL fee assignment
 * proposals of V021. The unit of work is the caller's: a proposal is written beside its audit record and its key, an
 * approval beside the version or the assignment it performs.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface FeeProposalStore<T> {

    /**
     * Every proposal {@code FEE_SCHEDULE_APPROVE} decides, by kind: its name in the pending-decision shape
     * ({@code merchant.<ProposalKind>}, `P17-TSK-040`) and the subject its doors are addressed by.
     */
    enum Kind {
        /** A fee schedule version (V022): the subject is the schedule. */
        FEE_SCHEDULE_VERSION("FeeScheduleVersion", "FEE_SCHEDULE"),
        /** A merchant's fee assignment (V022): the subject is the merchant. */
        FEE_ASSIGNMENT("FeeAssignment", "MERCHANT"),
        /** A merchant's BNPL fee assignment (V021, `P17-TSK-116`): the subject is the merchant. */
        BNPL_FEE_ASSIGNMENT("BnplFeeAssignment", "MERCHANT");

        private final String proposalKind;
        private final String subjectKind;

        Kind(String proposalKind, String subjectKind) {
            this.proposalKind = proposalKind;
            this.subjectKind = subjectKind;
        }

        /** {@code merchant.<ProposalKind>}: the kind as the pending-decision shape and the proposal's read name it. */
        public String qualified() {
            return "merchant." + proposalKind;
        }

        public String subjectKind() {
            return subjectKind;
        }
    }

    /** One proposal awaiting a decision, as its kind's queue lists it. */
    record Pending(UUID id, Kind kind, String proposedBy, Instant proposedAt, String reason, UUID subjectId) {}

    /** Where a page of the queue resumes: after this proposal, in (proposed at, id) order. */
    record PendingCursor(Instant proposedAt, UUID id) {}

    /**
     * A version or an assignment proposal, found by its identifier. A BNPL fee assignment proposal is read at its own door
     * (`P17-TSK-124`'s {@code BnplFeeAssignments.read}), never here.
     */
    sealed interface Found permits FoundVersion, FoundAssignment {}

    /** A fee schedule version proposal. */
    record FoundVersion(FeeScheduleVersionProposal proposal) implements Found {}

    /** A merchant fee assignment proposal. */
    record FoundAssignment(FeeAssignmentProposal proposal) implements Found {}


    // ------------------------------------------------------------------ the version proposal

    /** Inserts a {@code PROPOSED} version proposal; a second open one for the schedule is refused {@code ALREADY_PROPOSED}. */
    void insertVersion(T unitOfWork, FeeScheduleVersionProposal proposed);

    /** The version proposal, read {@code FOR UPDATE} - the deciders' serialization point. Empty for an unknown id. */
    Optional<FeeScheduleVersionProposal> lockVersion(T unitOfWork, FeeScheduleVersionProposal.Id id);

    /** Records the decision - and the version an approval minted - on its {@code PROPOSED} row; false when no longer so. */
    boolean decideVersion(T unitOfWork, FeeScheduleVersionProposal decided);

    // ------------------------------------------------------------------ the assignment proposal

    /** Inserts a {@code PROPOSED} assignment proposal; a second open one for the merchant is refused {@code ALREADY_PROPOSED}. */
    void insertAssignment(T unitOfWork, FeeAssignmentProposal proposed);

    /** The assignment proposal, read {@code FOR UPDATE} - the deciders' serialization point. Empty for an unknown id. */
    Optional<FeeAssignmentProposal> lockAssignment(T unitOfWork, FeeAssignmentProposal.Id id);

    /** Records the decision on its {@code PROPOSED} row; false when no longer {@code PROPOSED}. */
    boolean decideAssignment(T unitOfWork, FeeAssignmentProposal decided);

    // ------------------------------------------------------------------ the approver's reads

    /**
     * Up to {@code limit} proposals of {@code kind} awaiting a decision - {@code PROPOSED} - proposed at ascending, id
     * ascending, strictly after {@code after}.
     */
    List<Pending> pending(T unitOfWork, Kind kind, Optional<PendingCursor> after, int limit);

    /** The version or assignment proposal with this identifier, unlocked; empty when none is. */
    Optional<Found> find(T unitOfWork, UUID id);
}
