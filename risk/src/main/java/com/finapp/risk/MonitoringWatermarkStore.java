package com.finapp.risk;

import java.sql.Connection;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for the monitoring watermark (`P13-TSK-019`; {@code RISK_LIFECYCLES.md} section 4.11) - a port (ADR-0033)
 * whose writes are exactly the two the schema admits: the position moved (forward by the claim, back by an audited
 * reprocessing) with the permit re-stamped on the database clock, and the permit pulled forward by a hint. Every write
 * is under the row's lock or conditional; the sequence and {@code advanced_at} are the database's to derive.
 */
public interface MonitoringWatermarkStore {

    /**
     * The claim (the Phase 13 lock order's R7): the source's row {@code FOR UPDATE SKIP LOCKED} when its permit has come
     * on the database clock. Empty when another sweeper holds it or it is not due - nothing to do, nothing failed.
     */
    Optional<MonitoringWatermark> claimDue(Connection unitOfWork, String source);

    /** The source's row {@code FOR UPDATE}, waiting - the reprocessing door's read: a running batch commits first. */
    Optional<MonitoringWatermark> lock(Connection unitOfWork, String source);

    /** The row as it stands, lock-free - the gauges' read; decides nothing. */
    Optional<MonitoringWatermark> read(Connection unitOfWork, String source);

    /**
     * Moves the position to {@code lastEntryId} and re-stamps the permit {@code clock_timestamp() + permitDelay} - under
     * the caller's lock, in the caller's transaction: the batch's rows and this advance commit together or not at all.
     */
    void move(Connection unitOfWork, String source, UUID lastEntryId, Duration permitDelay);

    /**
     * The hint (`-005`'s completion handlers): the permit pulled to now where it is later, in one conditional statement;
     * a row another transaction holds is skipped - it is sweeping already. Answers whether it pulled.
     */
    boolean hint(Connection unitOfWork, String source);
}
