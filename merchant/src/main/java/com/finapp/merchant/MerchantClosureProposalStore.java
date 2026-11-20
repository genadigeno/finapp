package com.finapp.merchant;

import java.util.Optional;

/**
 * Storage for closure proposals (`P17-TSK-031`, ADR-0154): {@code merchant.merchant_closure_proposal}, V019. The unit of
 * work is the caller's: a proposal is written beside its audit record, an approval beside the closure it performs.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface MerchantClosureProposalStore<T> {

    /**
     * Inserts a {@code PROPOSED} proposal. The partial unique index {@code merchant_closure_proposal_one_open} is the backstop
     * beneath the merchant's lock, which the proposer holds: a second open proposal is refused
     * {@link MerchantClosureRefusedException.Reason#ALREADY_PROPOSED}.
     */
    void insert(T unitOfWork, MerchantClosureProposal proposed);

    /** The proposal, read {@code FOR UPDATE} - the deciders' serialization point. Empty for an unknown id. */
    Optional<MerchantClosureProposal> lock(T unitOfWork, MerchantClosureProposalId id);

    /** The proposal, unlocked - its decider's read (`P17-TSK-124`). Empty for an unknown id. */
    Optional<MerchantClosureProposal> find(T unitOfWork, MerchantClosureProposalId id);

    /** The merchant's open ({@code PROPOSED}) proposal, if any - at most one, by the index. */
    Optional<MerchantClosureProposal> findOpenFor(T unitOfWork, MerchantId merchantId);

    /** Records the decision on its {@code PROPOSED} row; false when the row is no longer {@code PROPOSED}. */
    boolean decide(T unitOfWork, MerchantClosureProposal decided);
}
