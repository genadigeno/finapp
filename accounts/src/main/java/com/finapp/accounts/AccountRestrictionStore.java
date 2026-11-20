package com.finapp.accounts;

import java.util.Optional;

/**
 * Storage for restrictions (`P17-TSK-028`, ADR-0153): {@code accounts.account_restriction}, V003. The unit of work is the
 * caller's, because a proposal is written beside its audit record and an approval beside the agreement's move.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface AccountRestrictionStore<T> {

    /**
     * Inserts a {@code PROPOSED} restriction. The partial unique index {@code account_restriction_one_open} is the backstop
     * beneath the agreement's lock, which the proposer holds: a second open proposal for one agreement is refused
     * {@link AccountRestrictionRefusedException.Reason#ALREADY_PROPOSED}.
     */
    void insert(T unitOfWork, AccountRestriction proposed);

    /** The restriction, read {@code FOR UPDATE} - the deciders' serialization point. Empty for an unknown id. */
    Optional<AccountRestriction> lock(T unitOfWork, AccountRestrictionId id);

    /** The agreement's open ({@code PROPOSED}) restriction, if any - at most one, by the index. */
    Optional<AccountRestriction> findOpenFor(T unitOfWork, CustomerAccountId accountId);

    /**
     * Records {@code decided}'s decision on its {@code PROPOSED} row - the conditional whose row count is the outcome: false
     * when the row is no longer {@code PROPOSED}.
     */
    boolean decide(T unitOfWork, AccountRestriction decided);

    /** Where a queue of proposals awaiting a decision resumes (`P17-TSK-040`): the last row served, oldest first. */
    record PendingPosition(java.time.Instant proposedAt, java.util.UUID id) {
        public PendingPosition {
            java.util.Objects.requireNonNull(proposedAt, "proposedAt must not be null");
            java.util.Objects.requireNonNull(id, "id must not be null");
        }
    }

    /** A proposal awaiting a decision (`P17-TSK-040`): its id, its proposer and their reason, when, and what it acts on. */
    record PendingProposal(
            java.util.UUID id, String proposedBy, java.time.Instant proposedAt, String reason, String subject) {
        public PendingProposal {
            java.util.Objects.requireNonNull(id, "id must not be null");
            java.util.Objects.requireNonNull(proposedAt, "proposedAt must not be null");
        }
    }

    /**
     * The suspensions and reinstatements awaiting a decision, each naming its account: {@code PROPOSED} alone, oldest first, the id the tiebreak (`P17-TSK-040`).
     */
    java.util.List<PendingProposal> pendingRestrictions(T unitOfWork, java.util.Optional<PendingPosition> after, int limit);
}
