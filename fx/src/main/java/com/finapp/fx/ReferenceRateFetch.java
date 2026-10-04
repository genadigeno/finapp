package com.finapp.fx;

import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * One round of the leaderless reference fetch (`P9-TSK-005`, ADR-0075 §1) - safe to run from any
 * number of instances at once.
 *
 * <p><strong>Three steps, three boundaries.</strong> The permit claim is its own committed
 * transaction (a loser does nothing this round - pacing). The fetch holds no connection (ADR-0046:
 * no transaction spans a wire). Each observation is recorded in its own transaction, so one
 * refused row never discards the others, and a crash part-way leaves the rows already committed
 * standing - the next round brings the rest. What is stored is decided by the database: the
 * snapshot's unique and its newer-than-latest trigger, for every writer. Nothing is cached:
 * there is no in-process rate anywhere in this class.
 */
public final class ReferenceRateFetch {

    private final RateSource source;
    private final RateSnapshotStore<Connection> snapshots;
    private final RateFetchPermitStore<Connection> permits;
    private final TransactionRunner transactions;
    private final IdGenerator ids;
    private final Duration window;

    public ReferenceRateFetch(
            RateSource source,
            RateSnapshotStore<Connection> snapshots,
            RateFetchPermitStore<Connection> permits,
            TransactionRunner transactions,
            IdGenerator ids,
            Duration window) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
        this.permits = Objects.requireNonNull(permits, "permits must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.window = Objects.requireNonNull(window, "window must not be null");
        if (window.isNegative()) {
            throw new IllegalArgumentException("a fetch window is never negative: " + window);
        }
    }

    /**
     * What one round did - counts and an outcome, never a rate.
     *
     * @param paced another instance holds this window's permit; nothing was fetched
     * @param failure the source could not be read
     * @param stored observations stored as their pair's newest
     * @param notNewer duplicates and replays, stored nothing
     * @param rejected rows refused by the adapter, the declaration or the database
     */
    public record Result(
            boolean paced,
            Optional<RateSource.FetchFailure> failure,
            int stored,
            int notNewer,
            int rejected) {

        static Result pacedRound() {
            return new Result(true, Optional.empty(), 0, 0, 0);
        }
    }

    public Result fetchOnce() {
        String code = source.sourceCode();
        boolean claimed = transactions.inTransaction(uow -> permits.claim(uow, code, window));
        if (!claimed) {
            return Result.pacedRound();
        }
        RateSource.Fetched fetched = source.fetch();
        if (fetched instanceof RateSource.Fetched.Failed failed) {
            return new Result(false, Optional.of(failed.failure()), 0, 0, 0);
        }
        RateSource.Fetched.Rates rates = (RateSource.Fetched.Rates) fetched;
        int stored = 0;
        int notNewer = 0;
        int rejected = rates.rejected();
        for (RateObservation observation : rates.observations()) {
            if (!ReferenceSourceDeclaration.declares(observation.pair())) {
                // The adapter's own screen should have refused it; the declaration is the
                // second rank and the migration's CHECK the third.
                rejected++;
                continue;
            }
            try {
                RateSnapshotStore.Recorded recorded =
                        transactions.inTransaction(
                                uow -> snapshots.record(uow, code, observation, ids.next()));
                if (recorded == RateSnapshotStore.Recorded.STORED) {
                    stored++;
                } else {
                    notNewer++;
                }
            } catch (RateSnapshotStore.ObservationRefusedException refused) {
                rejected++;
            }
        }
        return new Result(false, Optional.empty(), stored, notNewer, rejected);
    }
}
