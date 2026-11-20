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
}
