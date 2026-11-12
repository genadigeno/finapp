package com.finapp.merchant;

import com.finapp.platform.persistence.SweepListing;
import java.time.Duration;
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
     * The payout a provider's own reference names, lock-free (`P8-TSK-010`): the
     * reconciliation lookup's read — a typing snapshot, never a working claim.
     */
    Optional<MerchantPayout> findByProviderReference(T unitOfWork, String providerReference);

    /** The payout our minted reference names, lock-free (`P8-TSK-010`) — the OUR_REF read. */
    Optional<MerchantPayout> findByReference(T unitOfWork, String reference);

    /**
     * The payout a provider's own reference names, locked {@code FOR UPDATE} (`P8-TSK-019`,
     * ADR-0073 §4): the return's working claim — the row every applier of one payout's return
     * serialises on. A counterparty's stored reference, never a tenant's identifier.
     */
    Optional<MerchantPayout> lockByProviderReference(T unitOfWork, String providerReference);

    /** The payout our minted reference names, locked {@code FOR UPDATE} — the OUR_REF claim. */
    Optional<MerchantPayout> lockByReference(T unitOfWork, String reference);

    /**
     * Commits a fresh send permit, conditional on the payout still awaiting the rail's word and
     * on its permit being the one {@code before} carries — {@code false} when a resolver or
     * another renewal got there first, and then nothing may be sent. The permit is the
     * database's own instant, strictly forward (`X-TSK-013`).
     */
    boolean renewSendPermit(T unitOfWork, MerchantPayout before);

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
    /**
     * The {@code COMPLETED} payouts with {@code id > after}, in id order, at most
     * {@code limit} — the opening-position backfill's page (`P8-TSK-007`, ADR-0067 §8).
     * Cross-owner by design — an administered read under {@code RECONCILIATION_ADMINISTER},
     * composed in {@code app} — and lock-free: a completed row's copied facts are frozen,
     * and the backfill's converge is the register's uniques, never a lock here.
     */
    List<MerchantPayout> pageCompleted(T unitOfWork, java.util.UUID after, int limit);

    /**
     * The payouts the resolution sweep asks about; since `P16-TSK-017` a payout whose failure record's
     * retry is still ahead on the database's clock is left out, and a row that cannot be read is
     * skipped and reported by its id ({@link SweepListing}).
     */
    SweepListing<MerchantPayout> findSweepable(
            T unitOfWork, Instant dispatchedBefore, Instant unknownBefore, int limit);

    /**
     * Records one failure of {@code payout} in the sweep's listing ({@code merchant.sweep_row_failure},
     * leg {@code PAYOUT}) and backs the row off on the database's clock (`P16-TSK-017`): true when this
     * call counted it, false while the row's backoff still runs, so ten instances failing one row
     * count once. {@code failure} is a class name, nothing else ({@code INV-AUD-02}).
     */
    boolean recordSweepFailure(T unitOfWork, java.util.UUID payout, String failure);

    /**
     * The stuck-payout gauges' one reading (`P6-TSK-013`): the payouts the platform has no answer
     * for past the point one was due. That is every {@code UNKNOWN} payout, and every
     * {@code DISPATCHED} one whose latest send permit is older than {@code dispatchedBound} —
     * the sweep's own candidacy, with the {@code UNKNOWN} bound at zero. Within the bound a
     * dispatched payout is mid-question, and counting it would alert on healthy traffic.
     *
     * <p>Aged the sweep's way — an {@code UNKNOWN} payout from its entry into that state, a
     * {@code DISPATCHED} one from its latest permit — on the database server's clock. Read-only
     * and across every merchant by design: a count and a number of seconds, never a row.
     */
    UnknownReading unknownReading(T unitOfWork, Duration dispatchedBound);

    /**
     * How many payouts are waiting past their due for the rail's word, and how long the oldest
     * has waited, in whole seconds; zero and zero when none is.
     */
    record UnknownReading(long active, long oldestAgeSeconds) {

        public UnknownReading {
            if (active < 0 || oldestAgeSeconds < 0) {
                throw new IllegalArgumentException(
                        "a count and an age are never negative: " + active + ", "
                                + oldestAgeSeconds);
            }
        }
    }
}
