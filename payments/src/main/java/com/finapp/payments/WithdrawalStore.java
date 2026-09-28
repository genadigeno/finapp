package com.finapp.payments;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link Withdrawal} (`P7-TSK-008`) — the {@code MerchantPayoutStore}
 * protocols, clause for clause, because the machine is that machine.
 *
 * <p>Every surface read carries the customer in its statement (ADR-0031): another customer's
 * withdrawal and an unknown one are the same absence. The resolvers' lock-first read is
 * internal and unscoped by design — the sweep crosses customers, and each candidate is
 * re-judged under its own lock. Every write is conditional on the state its caller judged,
 * so racing resolvers converge.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface WithdrawalStore<T> {

    /** Stores a freshly dispatched withdrawal, with the client key its takeover converges
     * on ({@code (customer_id, dispatch_key)} unique — ADR-0057 §5's shape per customer). */
    void insert(T unitOfWork, Withdrawal dispatched, String dispatchKey);

    /** The customer's withdrawal, unlocked — the read surface's one query (one 404). */
    Optional<Withdrawal> findOwned(T unitOfWork, WithdrawalId id, UUID customerId);

    /** The withdrawal, locked for the rest of the transaction — the resolvers' read. */
    Optional<Withdrawal> findForUpdate(T unitOfWork, WithdrawalId id);

    /** The withdrawal a client key dispatched for the customer — the takeover's
     * convergence target. */
    Optional<Withdrawal> findByDispatchKey(T unitOfWork, UUID customerId, String dispatchKey);

    /**
     * Commits a fresh send permit, conditional on the withdrawal still awaiting the scheme's
     * word — {@code false} when a resolver got there first, and then nothing may be sent.
     */
    boolean renewSendPermit(T unitOfWork, Withdrawal before, Instant at);

    /**
     * Moves the withdrawal {@code before → after}, conditional on {@code before}'s status,
     * with its history row — stamped {@code at}, from the caller's injected clock — in the
     * same transaction. {@code false} when another writer moved it first.
     */
    boolean transition(T unitOfWork, Withdrawal before, Withdrawal after, Instant at);

    /**
     * The inquiry sweep's candidates: {@code DISPATCHED} rows whose send permit is at or
     * before {@code dispatchedBefore}, and {@code UNKNOWN} rows that entered that state at
     * or before {@code unknownBefore}, oldest first. Unlocked, across every customer, by
     * design: each candidate is re-judged under its own lock.
     */
    List<Withdrawal> findSweepable(
            T unitOfWork, Instant dispatchedBefore, Instant unknownBefore, int limit);

    /**
     * How many withdrawals wait past their due for the scheme's word, and how long the oldest has
     * waited (`P7-TSK-015`) — the stuck-withdrawal gauges' one read, the payout's shape verbatim:
     * {@link #findSweepable}'s candidacy with the {@code UNKNOWN} bound at zero. Every
     * {@code UNKNOWN} withdrawal counts, and every {@code DISPATCHED} one whose send permit is
     * older than the sweep's own {@code dispatchedBound} — before which it is mid-question. Each
     * is a customer's money behind a standing hold. A count and an age, never an amount.
     */
    PaymentAttemptStore.UnknownReading unknownReading(
            T unitOfWork, java.time.Duration dispatchedBound);
}
