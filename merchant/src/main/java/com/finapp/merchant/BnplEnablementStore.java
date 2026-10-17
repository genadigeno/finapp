package com.finapp.merchant;

import java.util.Optional;

/**
 * Persistence port for {@link BnplEnablement} (`P12-TSK-006`). The unit of work is the caller's. There is no delete and
 * no update of a decision once written: each act has its own conditional statement, and `V010`'s trigger freezes what it
 * wrote. Every instant is stamped by the database, after the lock the act took.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection} (ADR-0033)
 */
public interface BnplEnablementStore<T> {

    /** The merchant already has a live (proposed or active) enablement: the one-live index refused the insert. */
    final class LivePresent extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public LivePresent() {
            super("the merchant already has a live BNPL enablement");
        }
    }

    /**
     * Inserts a {@code PROPOSED} enablement behind a savepoint, returning it as stored (with the database's
     * {@code proposed_at}).
     *
     * @throws LivePresent when the one-live index refuses it - ten proposers, one proposal
     */
    BnplEnablement insertProposal(T unitOfWork, BnplEnablementId id, MerchantId merchant, String proposedBy, String reason);

    /** The enablement under its merchant, or empty - unknown and another merchant's alike. */
    Optional<BnplEnablement> find(T unitOfWork, MerchantId merchant, BnplEnablementId id);

    /** As {@link #find}, the row locked {@code FOR UPDATE} - every decision's serialisation point. */
    Optional<BnplEnablement> lock(T unitOfWork, MerchantId merchant, BnplEnablementId id);

    /**
     * {@code PROPOSED -> to} ({@code ACTIVE} or {@code REJECTED}) naming the decider and reason, conditional on the
     * from-state; the approval also on {@code proposed_by <> ?}. Returns the row as written, or empty when the condition
     * did not hold.
     */
    Optional<BnplEnablement> decide(
            T unitOfWork, BnplEnablementId id, BnplEnablementStatus to, String decidedBy, String reason);

    /** {@code ACTIVE -> WITHDRAWN}, conditional; the row as written, or empty. */
    Optional<BnplEnablement> withdraw(T unitOfWork, BnplEnablementId id, String withdrawnBy, String reason);

    /**
     * The merchant's {@code ACTIVE} enablement, its row held {@code FOR SHARE} to the caller's commit - the standing read:
     * a withdrawal (which takes the row {@code FOR UPDATE}) waits for every purchase that read it, and none reads one
     * withdrawn under it.
     */
    Optional<BnplEnablement> activeForShare(T unitOfWork, MerchantId merchant);

    /** The merchant's live enablement - proposed or active - unlocked, or empty. */
    Optional<BnplEnablement> live(T unitOfWork, MerchantId merchant);
}
