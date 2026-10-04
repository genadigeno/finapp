package com.finapp.fx;

import java.time.Duration;

/**
 * Pacing for the leaderless reference fetch (`P9-TSK-005`): one permit row per source, renewed
 * by a conditional upsert that the DATABASE stamps ({@code statement_timestamp()}) and that only
 * moves forward ({@code fx V002}'s trigger) - Phase 9 section 7's send-permit shape. Pacing,
 * never correctness: the snapshot's unique and trigger decide what is stored.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface RateFetchPermitStore<T> {

    /**
     * Takes the source's permit when its last attempt is at least {@code window} old on the
     * database clock (or there is none), advancing it - true for exactly one of N concurrent
     * claimants per window; false for the rest, who do nothing this round.
     */
    boolean claim(T unitOfWork, String source, Duration window);
}
