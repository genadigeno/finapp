package com.finapp.bnpl;

import java.util.Optional;

/**
 * Storage for the operator's BNPL refund proposals (`P17-TSK-116`, ADR-0154's shape): {@code bnpl.refund_proposal}, V023.
 * The unit of work is the caller's: a proposal is written beside its audit record and its key, an approval inside T-ref.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface BnplRefundProposalStore<T> {

    /** Inserts a {@code PROPOSED} proposal; a second open one for the purchase is refused {@code ALREADY_PROPOSED} by the index. */
    void insert(T unitOfWork, BnplRefundProposal proposed);

    /** The proposal, unlocked. Empty for an unknown id. */
    Optional<BnplRefundProposal> find(T unitOfWork, BnplRefundProposal.Id id);

    /** The proposal, read {@code FOR UPDATE} - the deciders' serialization point. Empty for an unknown id. */
    Optional<BnplRefundProposal> lock(T unitOfWork, BnplRefundProposal.Id id);

    /** The purchase's open proposal, if any - at most one, by the index. */
    Optional<BnplRefundProposal> findOpenFor(T unitOfWork, BnplPurchaseId purchaseId);

    /** Records the decision (and an approval's refund) on its {@code PROPOSED} row; false when no longer {@code PROPOSED}. */
    boolean decide(T unitOfWork, BnplRefundProposal decided);
}
