package com.finapp.payments;

import java.util.Optional;

/**
 * Storage for refund proposals (`P17-TSK-031`, ADR-0154): {@code payments.refund_proposal}, V043. The unit of work is the
 * caller's: a proposal is written beside its audit record and its key, an approval inside the refund's dispatch transaction.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface RefundProposalStore<T> {

    /** Inserts a {@code PROPOSED} proposal; a second open one for the payment is refused {@code ALREADY_PROPOSED} by the index. */
    void insert(T unitOfWork, RefundProposal proposed);

    /** The proposal, unlocked - what the approval asks the refund command to do. Empty for an unknown id. */
    Optional<RefundProposal> find(T unitOfWork, RefundProposal.Id id);

    /** The proposal, read {@code FOR UPDATE} - the deciders' serialization point. Empty for an unknown id. */
    Optional<RefundProposal> lock(T unitOfWork, RefundProposal.Id id);

    /** The payment's open proposal, if any - at most one, by the index. */
    Optional<RefundProposal> findOpenFor(T unitOfWork, PaymentIntentId intentId);

    /** Records the decision (and an approval's refund) on its {@code PROPOSED} row; false when no longer {@code PROPOSED}. */
    boolean decide(T unitOfWork, RefundProposal decided);
}
