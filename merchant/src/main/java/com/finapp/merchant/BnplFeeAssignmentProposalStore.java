package com.finapp.merchant;

import java.util.Optional;

/**
 * Storage for BNPL fee assignment proposals (`P17-TSK-116`): {@code merchant.bnpl_fee_assignment_proposal}, V021. The unit of
 * work is the caller's: a proposal is written beside its audit record and its key, an approval beside the assignment.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface BnplFeeAssignmentProposalStore<T> {

    /** Inserts a {@code PROPOSED} proposal; a second open one for the product is refused {@code ALREADY_PROPOSED} by the index. */
    void insert(T unitOfWork, BnplFeeAssignmentProposal proposed);

    /** The proposal, read {@code FOR UPDATE} - the deciders' serialization point. Empty for an unknown id. */
    Optional<BnplFeeAssignmentProposal> lock(T unitOfWork, BnplFeeAssignmentProposal.Id id);

    /** Records the decision on its {@code PROPOSED} row; false when no longer {@code PROPOSED}. */
    boolean decide(T unitOfWork, BnplFeeAssignmentProposal decided);
}
