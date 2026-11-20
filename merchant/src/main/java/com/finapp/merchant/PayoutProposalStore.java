package com.finapp.merchant;

import java.util.Optional;

/**
 * Storage for payout proposals (`P17-TSK-031`, ADR-0154): {@code merchant.payout_proposal}, V020. The unit of work is the
 * caller's: a proposal is written beside its audit record and its key, an approval inside the payout's dispatch transaction.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface PayoutProposalStore<T> {

    /** Inserts a {@code PROPOSED} proposal; a second open one for the merchant is refused {@code ALREADY_PROPOSED} by the index. */
    void insert(T unitOfWork, PayoutProposal proposed);

    /** The proposal, unlocked - what the approval asks the payout command to do. Empty for an unknown id. */
    Optional<PayoutProposal> find(T unitOfWork, PayoutProposal.Id id);

    /** The proposal, read {@code FOR UPDATE} - the deciders' serialization point. Empty for an unknown id. */
    Optional<PayoutProposal> lock(T unitOfWork, PayoutProposal.Id id);

    /** The merchant's open proposal, if any - at most one, by the index. */
    Optional<PayoutProposal> findOpenFor(T unitOfWork, MerchantId merchantId);

    /** Records the decision (and an approval's payout) on its {@code PROPOSED} row; false when no longer {@code PROPOSED}. */
    boolean decide(T unitOfWork, PayoutProposal decided);
}
