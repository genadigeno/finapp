package com.finapp.risk;

import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;

/**
 * Where risk signals rest (`P13-TSK-005`; {@code risk V003}) - one born-once insert and four bounded reads, every one
 * on the caller's unit of work (ADR-0033): the insert joins the inbox handler's transaction (T-sig), the reads join
 * T-eval's (`-004`) or the sweep's (`-007`). No update, no delete: none exists in the contract, the grant or the
 * table's trigger.
 */
public interface RiskSignalStore {

    /**
     * Records {@code signal} unless a signal of its source event already exists.
     *
     * <p>One statement, {@code INSERT ... ON CONFLICT (source_event_id) DO NOTHING}: the unique is the arbiter beneath
     * the inbox's dedupe, so a replay after the dedupe's retention, or a dedupe row lost, still records nothing twice
     * ({@code INV-RSK-06}, {@code INV-IDEM-04}). {@code recorded_at} is the database's clock, stamped by the table's
     * trigger - never an instance's.
     *
     * @return true when the signal was born by this call; false when its source event had already been recorded
     */
    boolean insert(Connection unitOfWork, RiskSignal signal);

    /** How many signals of {@code kind} about {@code subject} were observed at or after {@code since}. */
    int countSince(Connection unitOfWork, SignalSubject subject, SignalKind kind, Instant since);

    /** The latest signal of {@code kind} about {@code subject}, by {@code observed_at}. */
    Optional<RiskSignal> latest(Connection unitOfWork, SignalSubject subject, SignalKind kind);

    /** When the first signal of {@code kind} about {@code subject} was observed. */
    Optional<Instant> firstObservedAt(Connection unitOfWork, SignalSubject subject, SignalKind kind);

    /** Whether any signal of any kind exists about {@code subject} - a device's novelty is the absence of one. */
    boolean seen(Connection unitOfWork, SignalSubject subject);
}
