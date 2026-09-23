package com.finapp.merchant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link MerchantPayout} (`P6-TSK-012`).
 *
 * <p>Every read a merchant can reach carries the merchant in its statement
 * ({@code INV-MER-01}): another merchant's payout and an unknown one are the same absence.
 * Every write is conditional on the state its caller judged, so racing resolvers converge.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface MerchantPayoutStore<T> {

    /** Stores a freshly dispatched payout, with the client key its takeover converges on. */
    void insert(T unitOfWork, MerchantPayout dispatched, String dispatchKey);

    /** The merchant's payout, unlocked. */
    Optional<MerchantPayout> find(T unitOfWork, MerchantId merchant, MerchantPayoutId id);

    /** The merchant's payout, locked for the rest of the transaction. */
    Optional<MerchantPayout> findForUpdate(T unitOfWork, MerchantId merchant, MerchantPayoutId id);

    /** The payout a client key dispatched for the merchant — the takeover's convergence. */
    Optional<MerchantPayout> findByDispatchKey(T unitOfWork, MerchantId merchant, String dispatchKey);

    /**
     * Commits a fresh send permit, conditional on the payout still awaiting the rail's word —
     * {@code false} when a resolver got there first, and then nothing may be sent.
     */
    boolean renewSendPermit(T unitOfWork, MerchantPayout before, Instant at);

    /**
     * Moves the payout {@code before → after}, conditional on {@code before}'s status, with its
     * history row — stamped {@code at}, from the caller's injected clock — in the same
     * transaction. {@code false} when another writer moved it first.
     */
    boolean transition(T unitOfWork, MerchantPayout before, MerchantPayout after, Instant at);

    /**
     * The resolution sweep's candidates: {@code DISPATCHED} rows whose send permit is at or
     * before {@code dispatchedBefore}, and {@code UNKNOWN} rows that entered that state at or
     * before {@code unknownBefore}, oldest first. Unlocked, across every merchant, by design:
     * each candidate is re-judged under its own lock.
     */
    List<MerchantPayout> findSweepable(
            T unitOfWork, Instant dispatchedBefore, Instant unknownBefore, int limit);
}
