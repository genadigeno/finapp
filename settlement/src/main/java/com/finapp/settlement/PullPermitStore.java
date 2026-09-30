package com.finapp.settlement;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The pull permits (`P8-TSK-021`, settlement `V008`): pacing for a herd, never correctness —
 * the content unique on {@code settlement.file} is the arbiter of what lands.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface PullPermitStore<T> {

    /**
     * Takes the permit for one attempt when {@code window} has passed since the last one — a
     * conditional upsert that strictly advances {@code last_attempt_at} ({@code GREATEST} of one
     * microsecond past the stored instant and {@code at}). Answers whether THIS call took it; a
     * loser of the race, or a caller inside the window, attempts nothing.
     */
    boolean claim(T unitOfWork, UUID sourceId, String businessKey, Instant at, Duration window);

    /**
     * Takes the permit unconditionally — an operator's explicit fetch (`POST .../fetch`), which
     * the window never refuses — still strictly advancing it, so the schedule's herd sees the
     * attempt and waits its window.
     */
    void renew(T unitOfWork, UUID sourceId, String businessKey, Instant at);
}
