package com.finapp.transfers;

import java.util.Optional;

/**
 * Storage for reversal proposals (`P17-TSK-031`, ADR-0154): {@code transfers.transfer_reversal_proposal}, V005. The unit of
 * work is the caller's: a proposal is written beside its audit record, an approval beside the reversal it performs.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface TransferReversalProposalStore<T> {

    /**
     * Inserts a {@code PROPOSED} proposal. The partial unique index {@code transfer_reversal_proposal_one_open} is the backstop
     * beneath the transfer's lock, which the proposer holds: a second open proposal is refused
     * {@link TransferReversalRefusedException.Reason#ALREADY_PROPOSED}.
     */
    void insert(T unitOfWork, TransferReversalProposal proposed);

    /** The proposal, read {@code FOR UPDATE} - the deciders' serialization point. Empty for an unknown id. */
    Optional<TransferReversalProposal> lock(T unitOfWork, TransferReversalProposalId id);

    /** The transfer's open ({@code PROPOSED}) proposal, if any - at most one, by the index. */
    Optional<TransferReversalProposal> findOpenFor(T unitOfWork, TransferId transferId);

    /** Records the decision on its {@code PROPOSED} row; false when the row is no longer {@code PROPOSED}. */
    boolean decide(T unitOfWork, TransferReversalProposal decided);
}
